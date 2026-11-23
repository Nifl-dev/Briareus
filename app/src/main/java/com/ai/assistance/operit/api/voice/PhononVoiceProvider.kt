package com.ai.assistance.operit.api.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * On-device TTS backed by Phonon (libptts_ffi.so, bundled in jniLibs).
 *
 * Phonon streams 24 kHz mono float PCM as it synthesises. Every libptts call blocks and must not
 * run concurrently, so all synthesis is serialised behind [synthLock] on [Dispatchers.IO].
 *
 * The checkpoint is never downloaded implicitly: [initialize] fails with a clear message when the
 * model folder is missing, and the settings screen offers an explicit, size-disclosed download.
 */
class PhononVoiceProvider(
    private val context: Context,
    private val config: SpeechServicesPreferences.PhononTtsConfig,
) : VoiceService {

    private companion object {
        private const val TAG = "PhononVoiceProvider"

        /** Marker so the thread-count env var is set before the very first ptts_new. */
        @Volatile
        private var rayonEnvSet = false
    }

    private val synthLock = Mutex()
    private val stateLock = Any()

    private var engine: PhononTTS? = null
    private var currentTrack: AudioTrack? = null
    private var playbackGeneration = 0L
    private var paused = false

    private val _isSpeaking = MutableStateFlow(false)
    private val _isInitialized = MutableStateFlow(false)

    override val isInitialized: Boolean get() = _isInitialized.value
    override val isSpeaking: Boolean get() = _isSpeaking.value
    override val speakingStateFlow: Flow<Boolean> = _isSpeaking.asStateFlow()

    /** Absolute path of the checkpoint folder actually in use. */
    private fun modelDir(): File {
        val configured = config.modelDir.trim()
        return if (configured.isEmpty()) {
            PhononModelDownloader.defaultModelDir(context)
        } else {
            File(configured)
        }
    }

    /**
     * RAYON_NUM_THREADS must be set before the first ptts_new: Phonon's default is one thread per
     * core, which oversubscribes big.LITTLE phones into worse latency than a small fixed count.
     */
    private fun ensureRayonEnv() {
        if (rayonEnvSet) return
        synchronized(PhononVoiceProvider::class.java) {
            if (rayonEnvSet) return
            val threads = max(1, config.threads)
            try {
                android.system.Os.setenv("RAYON_NUM_THREADS", threads.toString(), true)
                rayonEnvSet = true
                AppLogger.i(TAG, "RAYON_NUM_THREADS=$threads")
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Could not set RAYON_NUM_THREADS", t)
            }
        }
    }

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (_isInitialized.value) return@withContext true

        val dir = modelDir()
        if (!PhononModelDownloader.isModelReady(dir)) {
            AppLogger.e(
                TAG,
                "Phonon model missing or incomplete at ${dir.absolutePath}. " +
                    "Open Settings > Speech services to download it."
            )
            _isInitialized.value = false
            return@withContext false
        }

        ensureRayonEnv()

        try {
            val loaded = PhononTTS(dir.absolutePath, config.lang.ifBlank { "en" })
            val voice = config.voice.trim()
            if (voice.isNotEmpty()) {
                runCatching { loaded.setVoice(voice) }
                    .onFailure { AppLogger.w(TAG, "Voice '$voice' not accepted by checkpoint", it) }
            }
            synchronized(stateLock) { engine = loaded }
            _isInitialized.value = true
            AppLogger.i(
                TAG,
                "Initialised dir=${dir.absolutePath} voices=${loaded.voices} langs=${loaded.languages}"
            )
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Phonon initialisation failed", t)
            _isInitialized.value = false
            false
        }
    }

    override suspend fun speak(
        text: String,
        interrupt: Boolean,
        rate: Float?,
        pitch: Float?,
        extraParams: Map<String, String>,
    ): Boolean {
        if (text.isBlank()) return false
        if (!initialize()) return false

        val generation = synchronized(stateLock) {
            if (interrupt) playbackGeneration++ else playbackGeneration
        }

        return synthLock.withLock {
            withContext(Dispatchers.IO) {
                val tts = synchronized(stateLock) { engine }
                    ?: return@withContext false

                val track = createTrack(rate)
                if (track == null) {
                    AppLogger.e(TAG, "AudioTrack could not be created")
                    return@withContext false
                }

                var owned = false
                synchronized(stateLock) {
                    if (playbackGeneration == generation) {
                        currentTrack?.release()
                        currentTrack = track
                        paused = false
                        owned = true
                    }
                }
                if (!owned) {
                    track.release()
                    return@withContext false
                }

                try {
                    track.play()
                    _isSpeaking.value = true
                    val result = tts.speak(text) { pcm ->
                        // Returning false aborts synthesis early; Phonon treats it as a stop.
                        if (playbackGeneration != generation) return@speak false
                        while (isPaused(generation)) Thread.sleep(50)
                        writeFloats(track, pcm)
                        playbackGeneration == generation
                    }
                    AppLogger.d(
                        TAG,
                        "Spoke ${result.samples} samples in ${result.totalMs}ms " +
                            "(ttfa ${result.ttfaMs}ms)"
                    )
                    // Let the buffer drain so the tail is audible before we report completion.
                    drain(track, generation)
                    playbackGeneration == generation
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "Phonon speak failed", t)
                    false
                } finally {
                    synchronized(stateLock) {
                        if (currentTrack === track) currentTrack = null
                        paused = false
                    }
                    _isSpeaking.value = false
                    runCatching { track.stop() }
                    track.release()
                }
            }
        }
    }

    private fun createTrack(rate: Float?): AudioTrack? {
        val sampleRate = PhononTTS.SAMPLE_RATE
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_FLOAT

        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            AppLogger.e(TAG, "Invalid min buffer size: $minBuffer")
            return null
        }

        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
            applyRate(track, rate)
            track
        } catch (t: Throwable) {
            AppLogger.e(TAG, "AudioTrack build failed", t)
            null
        }
    }

    /** Phonon synthesises at a fixed rate; speed is applied at playback instead. */
    private fun applyRate(track: AudioTrack, rate: Float?) {
        val speed = rate ?: return
        if (speed <= 0f || speed == 1f) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            track.playbackParams = track.playbackParams.setSpeed(speed.coerceIn(0.5f, 2.0f))
        }.onFailure { AppLogger.w(TAG, "Could not apply playback speed", it) }
    }

    private fun writeFloats(track: AudioTrack, pcm: FloatArray) {
        var offset = 0
        while (offset < pcm.size) {
            val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) throw TtsException("AudioTrack write failed: $written")
            if (written == 0) {
                Thread.sleep(5)
                continue
            }
            offset += written
        }
    }

    private fun drain(track: AudioTrack, generation: Long) {
        var idle = 0
        while (playbackGeneration == generation && idle < 20) {
            if (track.playbackHeadPosition > 0) {
                idle = 0
            } else {
                idle++
            }
            Thread.sleep(20)
        }
    }

    private fun isPaused(generation: Long): Boolean = synchronized(stateLock) {
        playbackGeneration == generation && paused
    }

    override suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            playbackGeneration++
            currentTrack?.let { runCatching { it.pause(); it.flush() } }
            paused = false
        }
        _isSpeaking.value = false
        true
    }

    override suspend fun pause(): Boolean = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            currentTrack?.let {
                runCatching { it.pause() }
                paused = true
            }
        }
        true
    }

    override suspend fun resume(): Boolean = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            currentTrack?.let {
                runCatching { it.play() }
                paused = false
            }
        }
        true
    }

    override fun shutdown() {
        synchronized(stateLock) {
            playbackGeneration++
            currentTrack?.let { runCatching { it.stop(); it.release() } }
            currentTrack = null
            engine?.let { runCatching { it.close() } }
            engine = null
            paused = false
        }
        _isSpeaking.value = false
        _isInitialized.value = false
        AppLogger.i(TAG, "Shut down")
    }

    override suspend fun getAvailableVoices(): List<VoiceService.Voice> = withContext(Dispatchers.IO) {
        val tts = synchronized(stateLock) { engine } ?: return@withContext emptyList()
        runCatching {
            tts.voices.map { name ->
                VoiceService.Voice(
                    id = name,
                    name = name,
                    locale = config.lang.ifBlank { "en" },
                    gender = "NEUTRAL",
                )
            }
        }.getOrElse {
            AppLogger.w(TAG, "Could not read voice list", it)
            emptyList()
        }
    }

    override suspend fun setVoice(voiceId: String): Boolean = withContext(Dispatchers.IO) {
        val tts = synchronized(stateLock) { engine } ?: return@withContext false
        runCatching {
            tts.setVoice(voiceId)
            true
        }.getOrElse {
            AppLogger.w(TAG, "setVoice('$voiceId') rejected", it)
            false
        }
    }
}
