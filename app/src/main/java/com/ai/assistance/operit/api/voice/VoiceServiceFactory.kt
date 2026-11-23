package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import kotlinx.coroutines.runBlocking

/** 语音服务工厂，用于创建不同类型的语音服务实例 */
object VoiceServiceFactory {
    /**
     * Default model id sent to a Kokoro server. Most OpenAI-compatible Kokoro servers ignore or
     * default this, but the request shape requires the field to be present.
     */
    const val DEFAULT_KOKORO_MODEL = "kokoro"

    /** 语音服务类型枚举 */
    enum class VoiceServiceType {
        /** 基于Android系统TTS的简单语音实现 */
        SIMPLE_TTS,
        /** 基于HTTP请求的TTS实现 */
        HTTP_TTS,
        /** 基于 OpenAI Realtime WebSocket 的 TTS 实现 */
        OPENAI_WS_TTS,
        /** 硅基流动TTS服务 */
        SILICONFLOW_TTS,
        /** MiniMax TTS 服务 */
        MINIMAX_TTS,
        /** MiMo TTS 服务 */
        MIMO_TTS,
        /** 豆包 TTS 服务 */
        DOUBAO_TTS,
        OPENAI_TTS,
        /** 基于 VITS/Piper ONNX Runtime 推理形态的本地 TTS 服务 */
        VITS_TTS,
        /** Phonon: bundled libptts_ffi.so, on-device GGUF inference (Briareus) */
        PHONON_TTS,
        /** Kokoro-82M served from a user-run OpenAI-compatible endpoint (Briareus) */
        KOKORO_TTS,
    }

    /**
     * 创建语音服务实例 (现在从Preferences中读取配置)
     *
     * @param context 应用上下文
     * @return 对应类型的VoiceService实例
     */
    fun createVoiceService(
        context: Context
    ): VoiceService {
        val profiles = SpeechServiceProfilesPreferences(context)
        // 使用runBlocking同步获取配置，这在工厂方法中是可接受的
        return runBlocking {
            val profile = profiles.getCurrentTtsProfile()

            when (profile.serviceType) {
                VoiceServiceType.SIMPLE_TTS -> {
                    SimpleVoiceProvider(
                        context = context,
                        initialLocaleTag = profile.httpConfig.localeTag,
                        initialVoiceId = profile.httpConfig.voiceId
                    )
                }
                VoiceServiceType.HTTP_TTS -> {
                    HttpVoiceProvider(context).apply {
                        setConfiguration(profile.httpConfig)
                    }
                }
                VoiceServiceType.OPENAI_WS_TTS -> {
                    OpenAIRealtimeVoiceProvider(
                        context = context,
                        endpointUrl = profile.httpConfig.urlTemplate,
                        apiKey = profile.httpConfig.apiKey,
                        model = profile.httpConfig.modelName,
                        initialVoiceId = profile.httpConfig.voiceId
                    )
                }
                VoiceServiceType.SILICONFLOW_TTS -> {
                    SiliconFlowVoiceProvider(
                        context = context,
                        apiKey = profile.httpConfig.apiKey,
                        initialVoiceId = profile.httpConfig.voiceId,
                        initialModelName = profile.httpConfig.modelName
                    )
                }
                VoiceServiceType.MINIMAX_TTS -> {
                    MiniMaxVoiceProvider(
                        context = context,
                        config = profile.httpConfig
                    )
                }
                VoiceServiceType.MIMO_TTS -> {
                    MimoVoiceProvider(
                        context = context,
                        config = profile.httpConfig
                    )
                }
                VoiceServiceType.DOUBAO_TTS -> {
                    DoubaoVoiceProvider(
                        context = context,
                        config = profile.httpConfig
                    )
                }
                VoiceServiceType.OPENAI_TTS -> {
                    OpenAIVoiceProvider(
                        context = context,
                        endpointUrl = profile.httpConfig.urlTemplate,
                        apiKey = profile.httpConfig.apiKey,
                        model = profile.httpConfig.modelName,
                        initialVoiceId = profile.httpConfig.voiceId
                    )
                }
                VoiceServiceType.KOKORO_TTS -> {
                    // Kokoro is served by the user's own OpenAI-compatible endpoint, so auth and
                    // voice are both optional. No default URL is shipped: the model card warns
                    // that third-party "kokoro" domains are likely scams.
                    OpenAIVoiceProvider(
                        context = context,
                        endpointUrl = profile.httpConfig.urlTemplate,
                        apiKey = profile.httpConfig.apiKey,
                        model = profile.httpConfig.modelName.ifBlank { DEFAULT_KOKORO_MODEL },
                        initialVoiceId = profile.httpConfig.voiceId,
                        allowAnonymous = true
                    )
                }
                VoiceServiceType.VITS_TTS -> {
                    VitsVoiceProvider(
                        context = context,
                        config = profile.vitsConfig
                    )
                }
                VoiceServiceType.PHONON_TTS -> {
                    PhononVoiceProvider(
                        context = context,
                        config = profile.phononConfig
                    )
                }
            }
        }
    }

    // 单例实例缓存
    private var instance: VoiceService? = null
    private var currentProfileId: String? = null

    /**
     * 获取语音服务单例实例
     *
     * @param context 应用上下文
     * @return VoiceService实例
     */
    fun getInstance(context: Context): VoiceService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val selectedProfileId = runBlocking { profiles.getCurrentTtsProfile().id }

        if (instance == null || selectedProfileId != currentProfileId) {
            instance?.shutdown()
            instance = createVoiceService(context)
            currentProfileId = selectedProfileId
        }
        return instance!!
    }

    /** 重置单例实例 在需要更改语音服务类型或释放资源时调用 */
    fun resetInstance() {
        instance?.shutdown()
        instance = null
        currentProfileId = null
    }
}