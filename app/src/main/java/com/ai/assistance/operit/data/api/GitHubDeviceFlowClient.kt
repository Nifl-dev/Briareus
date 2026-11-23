package com.ai.assistance.operit.data.api

import com.ai.assistance.operit.data.preferences.GitHubUser
import com.ai.assistance.operit.util.AppLogger
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * GitHub OAuth Device Flow client.
 *
 * Replaces the previous hosted broker (a vendor-operated service). Device flow needs no
 * client_secret and no redirect URI, so authorization happens entirely on
 * GitHub's own domain and the app only polls for the result.
 *
 * IMPORTANT: the requested scope must never include `offline_access`.
 * GitHub returns an expiring access token (8 h) plus a refresh token when that
 * scope is present, even for an OAuth app that is not configured to expire
 * tokens. Adding it here would silently reintroduce mid-session logouts and
 * require refresh handling that this flow does not implement.
 */
@Serializable
data class GitHubDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("interval") val interval: Long = FALLBACK_POLL_INTERVAL_SECONDS
)

/** GitHub’s documented minimum poll interval, used when the response omits it. */
internal const val FALLBACK_POLL_INTERVAL_SECONDS = 5L

data class GitHubDeviceFlowToken(
    val accessToken: String,
    val tokenType: String,
    val scope: String,
    val user: GitHubUser
)

/** Outcome of a single poll attempt. */
sealed class GitHubDevicePollResult {
    data class Authorized(val token: GitHubDeviceFlowToken) : GitHubDevicePollResult()
    object Pending : GitHubDevicePollResult()

    /** GitHub asked us to back off; `extraSeconds` must be added to the interval. */
    data class SlowDown(val extraSeconds: Long) : GitHubDevicePollResult()
    object Expired : GitHubDevicePollResult()
    object Denied : GitHubDevicePollResult()
    data class Failed(val message: String) : GitHubDevicePollResult()
}

class GitHubDeviceFlowClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Step 1: ask GitHub for a device code and the user code to display. */
    suspend fun requestDeviceCode(scope: String): Result<GitHubDeviceCodeResponse> =
        withContext(Dispatchers.IO) {
            try {
                val body = buildForm(
                    "client_id" to CLIENT_ID,
                    "scope" to scope
                )
                val request = Request.Builder()
                    .url("$GITHUB_WEB_BASE/login/device/code")
                    .header("Accept", JSON_ACCEPT)
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                        return@withContext Result.failure(
                            IllegalStateException(
                                "GitHub device code request failed: HTTP ${response.code}"
                            )
                        )
                    }
                    Result.success(
                        json.decodeFromString<GitHubDeviceCodeResponse>(responseBody)
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "GitHub device code request failed", e)
                Result.failure(e)
            }
        }

    /** Step 2 (single attempt): exchange the device code for an access token. */
    suspend fun pollOnce(deviceCode: String): GitHubDevicePollResult =
        withContext(Dispatchers.IO) {
            try {
                val body = buildForm(
                    "client_id" to CLIENT_ID,
                    "device_code" to deviceCode,
                    "grant_type" to DEVICE_CODE_GRANT_TYPE
                )
                val request = Request.Builder()
                    .url("$GITHUB_WEB_BASE/login/oauth/access_token")
                    .header("Accept", JSON_ACCEPT)
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrBlank()) {
                        return@withContext GitHubDevicePollResult.Failed(
                            "GitHub token endpoint returned an empty body (HTTP ${response.code})"
                        )
                    }
                    val obj = json.parseToJsonElement(responseBody) as? JsonObject
                        ?: return@withContext GitHubDevicePollResult.Failed(
                            "GitHub token endpoint returned an unexpected payload"
                        )

                    val accessToken = obj.stringOrNull("access_token")
                    if (!accessToken.isNullOrBlank()) {
                        val user = fetchUser(accessToken).getOrElse { error ->
                            return@withContext GitHubDevicePollResult.Failed(
                                error.message ?: "Failed to load GitHub user"
                            )
                        }
                        return@withContext GitHubDevicePollResult.Authorized(
                            GitHubDeviceFlowToken(
                                accessToken = accessToken,
                                tokenType = obj.stringOrNull("token_type") ?: "bearer",
                                scope = obj.stringOrNull("scope").orEmpty(),
                                user = user
                            )
                        )
                    }

                    when (val error = obj.stringOrNull("error")) {
                        "authorization_pending" -> GitHubDevicePollResult.Pending
                        "slow_down" -> GitHubDevicePollResult.SlowDown(SLOW_DOWN_EXTRA_SECONDS)
                        "expired_token" -> GitHubDevicePollResult.Expired
                        "incorrect_device_code" -> GitHubDevicePollResult.Expired
                        "access_denied" -> GitHubDevicePollResult.Denied
                        else -> GitHubDevicePollResult.Failed(
                            error ?: "GitHub token endpoint returned no access token"
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "GitHub device flow poll failed", e)
                GitHubDevicePollResult.Failed(e.message ?: "GitHub device flow poll failed")
            }
        }

    /*
     * Step 2 (full): poll until GitHub answers or the code expires.
     * Honours the server-provided interval and the slow_down back-off.
     */
    suspend fun pollUntilAuthorized(
        code: GitHubDeviceCodeResponse,
        onSlowDown: (Long) -> Unit = {}
    ): GitHubDevicePollResult {
        var intervalSeconds = code.interval.coerceAtLeast(DEFAULT_POLL_INTERVAL_SECONDS)
        val deadline = System.currentTimeMillis() + (code.expiresIn * 1000L)
        while (System.currentTimeMillis() < deadline) {
            delay(intervalSeconds * 1000L)
            val result = pollOnce(code.deviceCode)
            when (result) {
                is GitHubDevicePollResult.Pending -> Unit
                is GitHubDevicePollResult.SlowDown -> {
                    intervalSeconds += result.extraSeconds
                    onSlowDown(intervalSeconds)
                }
                else -> return result
            }
        }
        return GitHubDevicePollResult.Expired
    }

    /** Step 3: resolve the authenticated account. */
    suspend fun fetchUser(accessToken: String): Result<GitHubUser> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$GITHUB_API_BASE/user")
                    .header("Accept", JSON_ACCEPT)
                    .header("Authorization", "Bearer $accessToken")
                    .build()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                        return@withContext Result.failure(
                            IllegalStateException(
                                "GitHub user request failed: HTTP ${response.code}"
                            )
                        )
                    }
                    Result.success(json.decodeFromString<GitHubUser>(responseBody))
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load GitHub user", e)
                Result.failure(e)
            }
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun buildForm(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toRequestBody(FORM_MEDIA_TYPE)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "GitHubDeviceFlow"

        /*
         * Public by design: a device-flow client_id appears in the consent
         * screen URL. No client_secret exists for this app, so none is stored.
         */
        const val CLIENT_ID = "Ov23liYxbhXyGEFirk6g"

        private const val GITHUB_WEB_BASE = "https://github.com"
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val DEVICE_CODE_GRANT_TYPE =
            "urn:ietf:params:oauth:grant-type:device_code"
        private const val JSON_ACCEPT = "application/json"
        private const val TIMEOUT_SECONDS = 30L
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 5L

        /** GitHub adds 5 s to the interval when it answers slow_down. */
        private const val SLOW_DOWN_EXTRA_SECONDS = 5L
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}