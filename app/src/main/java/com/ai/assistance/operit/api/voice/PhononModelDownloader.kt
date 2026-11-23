package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a Phonon checkpoint folder on first run.
 *
 * Google Play's Device and Network Abuse policy forbids downloading *executable code* (dex, JAR,
 * .so) outside Play, but a GGUF checkpoint is weights and metadata, not code: the runtime that
 * consumes it is already bundled. Only data is fetched here.
 *
 * Nothing is downloaded without an explicit user action: [download] must be called from a UI that
 * has already told the user the size and source. This class never starts on its own.
 */
object PhononModelDownloader {

    private const val TAG = "PhononModelDownloader"

    /** The default (31m, English, Apache-2.0) checkpoint repository. */
    const val DEFAULT_REPO = "gradium/phonon-31m-en"

    /**
     * Files the runtime needs, in the layout `ptts_new` expects. `config.json` is required with no
     * fallback; the q8 GGUF is used in preference to safetensors when both exist.
     */
    val REQUIRED_FILES = listOf(
        "config.json",
        "tokenizer.json",
        "model.q8.gguf",
        "default-voice.safetensors",
    )

    /** Approximate download size of [REQUIRED_FILES], for the consent dialog. */
    const val APPROX_BYTES = 56_000_000L

    fun defaultModelDir(context: Context): File = File(context.filesDir, "phonon/31m-en")

    /** True when every required file is present and non-empty. */
    fun isModelReady(dir: File): Boolean =
        REQUIRED_FILES.all { name -> File(dir, name).let { it.isFile && it.length() > 0 } }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Downloads the checkpoint into [targetDir].
     *
     * @param onProgress invoked with (fileName, bytesDone, bytesTotal); total may be 0 if unknown.
     * @throws IOException on network or write failure; partial files are left for resume/retry.
     */
    suspend fun download(
        targetDir: File,
        repo: String = DEFAULT_REPO,
        onProgress: (fileName: String, done: Long, total: Long) -> Unit = { _, _, _ -> },
    ) = withContext(Dispatchers.IO) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Cannot create model directory: ${targetDir.absolutePath}")
        }

        for (name in REQUIRED_FILES) {
            val dest = File(targetDir, name)
            if (dest.isFile && dest.length() > 0) {
                AppLogger.d(TAG, "Skipping already-present $name")
                continue
            }

            val url = "https://huggingface.co/$repo/resolve/main/$name"
            AppLogger.i(TAG, "Downloading $name from $repo")
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} fetching $name")
                }
                val body = response.body ?: throw IOException("Empty body fetching $name")
                val total = body.contentLength()
                var done = 0L

                // Write to .part first so an interrupted run never leaves a half file that looks
                // complete to isModelReady().
                val part = File(targetDir, "$name.part")
                try {
                    body.byteStream().use { input ->
                        FileOutputStream(part).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                done += read
                                onProgress(name, done, total)
                            }
                            output.flush()
                        }
                    }
                    if (!part.renameTo(dest)) {
                        throw IOException("Cannot finalise $name")
                    }
                } catch (t: Throwable) {
                    part.delete()
                    throw t
                }
            }
        }

        if (!isModelReady(targetDir)) {
            throw IOException("Model incomplete after download: ${targetDir.absolutePath}")
        }
        AppLogger.i(TAG, "Phonon model ready at ${targetDir.absolutePath}")
    }
}
