package com.ai.assistance.operit.ui.features.github

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.GitHubDeviceCodeResponse
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * GitHub login via OAuth Device Flow.
 *
 * The previous UI offered "embedded page" and "external browser" modes, both of
 * which routed through a hosted broker. Device flow replaces both with a single
 * flow: we show a short code, the user approves it on github.com in whatever
 * browser they prefer, and we poll for the result. There is no WebView, no
 * loopback server and no third-party endpoint involved.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun GitHubLoginDialog(
    onDismissRequest: () -> Unit,
    onLoginSuccess: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coordinator = remember { GitHubOAuthCoordinator(context) }
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf<GitHubDeviceCodeResponse?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var completed by remember { mutableStateOf(false) }

    // Step 1: request a fresh code. Re-runs when the user taps Retry, which is
    // the only recovery path when the 15-minute device code expires.
    LaunchedEffect(attempt) {
        errorMessage = null
        code = null
        completed = false
        coordinator.startDeviceLogin().fold(
            onSuccess = { requested -> code = requested },
            onFailure = { error ->
                AppLogger.e(TAG, "Failed to start GitHub device login", error)
                errorMessage = error.message.orEmpty()
            }
        )
    }

    // Step 2: poll until the user approves. Cancelled automatically when the
    // dialog leaves composition, which is why CancellationException is expected.
    val activeCode = code
    LaunchedEffect(activeCode) {
        if (activeCode == null) return@LaunchedEffect
        try {
            coordinator.awaitAuthorization(activeCode).fold(
                onSuccess = { user ->
                    completed = true
                    Toast.makeText(
                        context,
                        context.getString(R.string.main_github_login_success, user.login),
                        Toast.LENGTH_LONG
                    ).show()
                    onLoginSuccess?.invoke()
                    onDismissRequest()
                },
                onFailure = { error -> errorMessage = error.message.orEmpty() }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLogger.e(TAG, "GitHub device login failed", error)
            errorMessage = error.message.orEmpty()
        }
    }

    val dismiss = {
        scope.launch { coordinator.cancelLogin() }
        onDismissRequest()
    }

    AlertDialog(
        onDismissRequest = { if (!completed) dismiss() },
        title = { Text(stringResource(R.string.login_github)) },
        text = {
            Column {
                when {
                    errorMessage != null -> {
                        Text(
                            stringResource(
                                R.string.main_github_login_failed,
                                errorMessage.orEmpty()
                            )
                        )
                    }
                    activeCode == null -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(stringResource(R.string.github_login_starting))
                        }
                    }
                    else -> {
                        Text(
                            stringResource(
                                R.string.github_device_code_description,
                                activeCode.verificationUri
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = activeCode.userCode,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.github_device_login_waiting))
                        }
                    }
                }
            }
        },
        confirmButton = {
            val current = activeCode
            if (current != null && errorMessage == null) {
                // AlertDialog places a single composable here, so the action
                // button and the copy shortcut share one row explicitly.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(
                                ClipboardManager::class.java
                            )
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(
                                    context.getString(R.string.login_github),
                                    current.userCode
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.github_device_login_copy))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(current.verificationUri)
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.github_device_login_open))
                    }
                }
            } else if (errorMessage != null) {
                TextButton(
                    onClick = { attempt += 1 },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(stringResource(R.string.github_device_login_retry))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !completed) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private const val TAG = "GitHubLoginDialog"