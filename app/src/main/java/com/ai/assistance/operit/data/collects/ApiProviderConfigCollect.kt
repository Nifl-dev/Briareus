package com.ai.assistance.operit.data.collects

import com.ai.assistance.operit.data.model.ApiProviderType
import java.net.URI

data class ProviderEndpointOption(
    val endpoint: String,
    val label: String
)

data class ProviderApiConfig(
    val providerType: ApiProviderType,
    val defaultModelName: String = "",
    val defaultApiEndpoint: String = "",
    val endpointOptions: List<ProviderEndpointOption> = emptyList(),
    val requiresApiKey: Boolean = true
)

object ApiProviderConfigs {
    private val configs: Map<ApiProviderType, ProviderApiConfig> = listOf(
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI,
            defaultModelName = "gpt-4o",
            defaultApiEndpoint = "https://api.openai.com/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.XAI,
            defaultModelName = "grok-4.6",
            defaultApiEndpoint = "https://api.x.ai/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_RESPONSES,
            defaultModelName = "gpt-4o",
            defaultApiEndpoint = "https://api.openai.com/v1/responses"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_CODEX,
            defaultModelName = "gpt-5.6-luna",
            defaultApiEndpoint = "https://chatgpt.com/backend-api/codex/responses",
            requiresApiKey = false,
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_RESPONSES_GENERIC,
            defaultModelName = "",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_GENERIC,
            defaultModelName = "",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ANTHROPIC,
            defaultModelName = "claude-3-opus-20240229",
            defaultApiEndpoint = "https://api.anthropic.com/v1/messages"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ANTHROPIC_GENERIC,
            defaultModelName = "",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.GOOGLE,
            defaultModelName = "gemini-2.0-flash",
            defaultApiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.GEMINI_GENERIC,
            defaultModelName = "gemini-2.0-flash",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.DEEPSEEK,
            defaultModelName = "deepseek-v4-flash",
            defaultApiEndpoint = "https://api.deepseek.com/v1/chat/completions",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://api.deepseek.com/v1/chat/completions",
                    label = "Chat Completions"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.deepseek.com/v1/responses",
                    label = "Responses"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ALIYUN,
            defaultModelName = "qwen-max",
            // Decoupling: the international DashScope host previously had no option at all, so
            // non-CN users were stuck on the CN host. Verified reachable (401 without a key).
            defaultApiEndpoint = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
                    label = "International (dashscope-intl)"
                ),
                ProviderEndpointOption(
                    endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                    label = "China (dashscope)"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ZHIPU,
            defaultModelName = "glm-4.5",
            // Decoupling: default to the international host (International option already exists).
            defaultApiEndpoint = "https://api.z.ai/api/paas/v4/chat/completions",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                    label = "CN standard"
                ),
                ProviderEndpointOption(
                    endpoint = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions",
                    label = "CN coding"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.z.ai/api/paas/v4/chat/completions",
                    label = "International standard"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.z.ai/api/coding/paas/v4/chat/completions",
                    label = "International coding"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MOONSHOT,
            defaultModelName = "moonshot-v1-128k",
            // Decoupling: default to the international host. The CN host stays available as
            // an explicit option, but must not be the silent default for non-CN users.
            defaultApiEndpoint = "https://api.moonshot.ai/v1/chat/completions",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://api.moonshot.cn/v1/chat/completions",
                    label = "China (moonshot.cn)"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.moonshot.ai/v1/chat/completions",
                    label = "International (moonshot.ai)"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.kimi.com/coding/v1/chat/completions",
                    label = "Kimi Code (api.kimi.com)"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MIMO,
            defaultModelName = "mimo-v2.5-pro",
            defaultApiEndpoint = "https://api.xiaomimimo.com/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MISTRAL,
            defaultModelName = "codestral-latest",
            defaultApiEndpoint = "https://codestral.mistral.ai/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.SILICONFLOW,
            defaultModelName = "yi-1.5-34b",
            // Decoupling: add the .com (international) host; .cn alone was the only route.
            // Verified reachable (401 without a key).
            defaultApiEndpoint = "https://api.siliconflow.com/v1/chat/completions",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://api.siliconflow.com/v1/chat/completions",
                    label = "International (siliconflow.com)"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.siliconflow.cn/v1/chat/completions",
                    label = "China (siliconflow.cn)"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENROUTER,
            defaultModelName = "google/gemini-pro",
            defaultApiEndpoint = "https://openrouter.ai/api/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENCODE,
            defaultModelName = "",
            defaultApiEndpoint = "https://opencode.ai/zen",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://opencode.ai/zen",
                    label = "Zen"
                ),
                ProviderEndpointOption(
                    endpoint = "https://opencode.ai/zen/go",
                    label = "Go"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.FOUR_ROUTER,
            defaultModelName = "gpt-5.4-mini",
            defaultApiEndpoint = "https://4router.net/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.NOUS_PORTAL,
            defaultModelName = "",
            defaultApiEndpoint = "https://inference-api.nousresearch.com/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.DOUBAO,
            defaultModelName = "Doubao-pro-4k",
            // Decoupling: [redacted] International lives on a different host entirely
            // (ark.ap-southeast.[redacted].com, verified reachable — 401 without a key).
            defaultApiEndpoint = "https://ark.ap-southeast.[redacted].com/api/v3/chat/completions",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://ark.ap-southeast.[redacted].com/api/v3/chat/completions",
                    label = "International (ap-southeast)"
                ),
                ProviderEndpointOption(
                    endpoint = "https://ark.ap-southeast.[redacted].com/api/coding/v3/chat/completions",
                    label = "International coding"
                ),
                ProviderEndpointOption(
                    endpoint = "https://ark.cn-beijing.[redacted].com/api/v3/chat/completions",
                    label = "China standard"
                ),
                ProviderEndpointOption(
                    endpoint = "https://ark.cn-beijing.[redacted].com/api/coding/v3/chat/completions",
                    label = "China coding"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.NVIDIA,
            defaultModelName = "nvidia/nemotron-3-nano-30b-a3b",
            defaultApiEndpoint = "https://integrate.api.nvidia.com/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.LMSTUDIO,
            defaultModelName = "meta-llama-3.1-8b-instruct",
            defaultApiEndpoint = "http://localhost:1234/v1/chat/completions",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OLLAMA,
            defaultModelName = "",
            defaultApiEndpoint = "http://localhost:11434/v1/chat/completions",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_LOCAL,
            defaultModelName = "",
            defaultApiEndpoint = "http://localhost:8000/v1/chat/completions",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MNN,
            defaultModelName = "",
            defaultApiEndpoint = "",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.LLAMA_CPP,
            defaultModelName = "",
            defaultApiEndpoint = "",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.NOVITA,
            defaultModelName = "moonshotai/kimi-k2.5",
            defaultApiEndpoint = "https://api.novita.ai/openai/v1/chat/completions",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://api.novita.ai/openai/v1/chat/completions",
                    label = "OpenAI-compatible"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.novita.ai/anthropic/v1/messages",
                    label = "Anthropic-compatible"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MINIMAX,
            defaultModelName = "MiniMax-M2.7",
            // Decoupling: default to the international host (International option already exists).
            defaultApiEndpoint = "https://api.minimax.io/v1/chat/completions",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://api.minimaxi.com/v1/chat/completions",
                    label = "China (minimaxi.com)"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.minimax.io/v1/chat/completions",
                    label = "International (minimax.io)"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OTHER,
            defaultModelName = "",
            defaultApiEndpoint = ""
        )
    ).associateBy(ProviderApiConfig::providerType)

    fun get(providerType: ApiProviderType): ProviderApiConfig {
        return configs[providerType] ?: ProviderApiConfig(providerType = providerType)
    }

    fun getDefaultModelName(providerType: ApiProviderType): String {
        return get(providerType).defaultModelName
    }

    fun getDefaultApiEndpoint(providerType: ApiProviderType): String {
        return get(providerType).defaultApiEndpoint
    }

    fun getEndpointOptions(providerType: ApiProviderType): List<ProviderEndpointOption>? {
        return get(providerType).endpointOptions.takeIf { it.isNotEmpty() }
    }

    fun requiresApiKey(providerType: ApiProviderType, apiEndpoint: String = ""): Boolean {
        if (!get(providerType).requiresApiKey) {
            return false
        }

        return !isLoopbackEndpoint(apiEndpoint)
    }

    fun requiresApiKey(providerTypeId: String, apiEndpoint: String = ""): Boolean {
        val providerType = ApiProviderType.fromProviderTypeId(providerTypeId) ?: return !isLoopbackEndpoint(apiEndpoint)
        return requiresApiKey(providerType, apiEndpoint)
    }

    fun isDefaultModelName(modelName: String): Boolean {
        return configs.values.any { it.defaultModelName == modelName }
    }

    fun isDefaultApiEndpoint(endpoint: String): Boolean {
        return configs.values.any { it.defaultApiEndpoint == endpoint }
    }

    fun isLoopbackEndpoint(apiEndpoint: String): Boolean {
        if (apiEndpoint.isBlank()) {
            return false
        }

        val normalizedEndpoint = apiEndpoint.trim().lowercase()
        return try {
            val host = URI(apiEndpoint).host
            if (host != null) {
                isLoopbackHost(host)
            } else {
                isLoopbackEndpointText(normalizedEndpoint)
            }
        } catch (_: Exception) {
            isLoopbackEndpointText(normalizedEndpoint)
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        return when (host.lowercase().trim('[', ']')) {
            "localhost",
            "127.0.0.1",
            "::1",
            "0.0.0.0",
            "10.0.2.2" -> true
            else -> false
        }
    }

    private fun isLoopbackEndpointText(apiEndpoint: String): Boolean {
        return apiEndpoint.startsWith("localhost:") ||
            apiEndpoint.startsWith("127.0.0.1:") ||
            apiEndpoint.startsWith("[::1]:") ||
            apiEndpoint.startsWith("::1:") ||
            apiEndpoint.startsWith("0.0.0.0:") ||
            apiEndpoint.startsWith("10.0.2.2:")
    }
}
