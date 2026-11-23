package com.ai.assistance.operit.ui.features.github

import android.content.Context
import com.ai.assistance.operit.data.api.GitHubDeviceCodeResponse
import com.ai.assistance.operit.data.api.GitHubDeviceFlowClient
import com.ai.assistance.operit.data.api.GitHubDevicePollResult
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.data.preferences.GitHubUser
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException

/*
 * Drives GitHub login through the OAuth Device Flow.
 *
 * History: this previously delegated to a hosted broker at the vendor host that
 * held the client_secret and performed the browser round-trip on our behalf.
 * Device flow removes that dependency entirely - GitHub shows the code prompt on
 * its own domain, the user approves there, and we poll for the token. No secret
 * is involved on either side.
 */
class GitHubOAuthCoordinator(context: Context) {

    private val githubAuth = GitHubAuthPreferences.getInstance(context.applicationContext)
    private val deviceFlowClient = GitHubDeviceFlowClient()

    /**
     * Step 1: request a device code. The returned code and URL must be shown to
     * the user; nothing is stored yet because no token exists.
     */
    suspend fun startDeviceLogin(): Result<GitHubDeviceCodeResponse> =
        deviceFlowClient.requestDeviceCode(GitHubAuthPreferences.GITHUB_SCOPE)

    /**
     * Step 2: poll until the user approves (or the code expires) and persist the
     * resulting session.
     *
     * Cancellation is rethrown rather than converted into a failure: the caller
     * cancels this when the dialog is dismissed, and treating that as an error
     * would surface a spurious login-failure toast.
     */
    suspend fun awaitAuthorization(
        code: GitHubDeviceCodeResponse,
        onSlowDown: (Long) -> Unit = {}
    ): Result<GitHubUser> {
        val result = try {
            deviceFlowClient.pollUntilAuthorized(code, onSlowDown)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            AppLogger.e(TAG, "GitHub device flow polling failed", e)
            return Result.failure(e)
        }

        return when (result) {
            is GitHubDevicePollResult.Authorized -> {
                val token = result.token
                try {
                    githubAuth.saveAuthInfo(
                        accessToken = token.accessToken,
                        tokenType = token.tokenType,
                        // Both null by design: this app is not configured to
                        // expire tokens, so there is no refresh capability to
                        // record. Passing a value here would imply we can renew.
                        expiresIn = null,
                        refreshToken = null,
                        userInfo = token.user,
                        grantedScope = token.scope
                    )
                    Result.success(token.user)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to persist GitHub session", e)
                    Result.failure(e)
                }
            }
            is GitHubDevicePollResult.Pending -> Result.failure(
                IllegalStateException("GitHub authorization is still pending")
            )
            is GitHubDevicePollResult.SlowDown -> Result.failure(
                IllegalStateException("GitHub asked to slow down the authorization poll")
            )
            is GitHubDevicePollResult.Expired -> Result.failure(
                IllegalStateException("The GitHub authorization code expired; please try again")
            )
            is GitHubDevicePollResult.Denied -> Result.failure(
                IllegalStateException("GitHub authorization was cancelled")
            )
            is GitHubDevicePollResult.Failed -> Result.failure(
                IllegalStateException(result.message)
            )
        }
    }

    suspend fun cancelLogin() {
        githubAuth.clearActiveOAuthTransaction()
    }

    companion object {
        private const val TAG = "GitHubOAuthCoordinator"
    }
}