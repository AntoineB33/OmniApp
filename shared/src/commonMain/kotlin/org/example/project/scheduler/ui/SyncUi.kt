package org.example.project.scheduler.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.sync.SyncState

/**
 * A compact cross-device-sync status chip (PRD §5). Tapping it opens the [SignInDialog]. Renders nothing
 * when sync is disabled ([state] is null — e.g. the web build without a SyncMetaStore).
 */
@Composable
fun SyncStatusChip(state: SyncState?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (state == null) return
    val label =
        when (state) {
            SyncState.SignedOut -> "☁ Sign in"
            SyncState.Idle -> "☁ Synced"
            SyncState.Syncing -> "☁ Syncing…"
            is SyncState.Error -> "☁ Sync error"
        }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * Email + password sign-in / sign-up for cross-device sync (PRD §5). When already signed in (any [state]
 * other than [SyncState.SignedOut]) it shows the current status and a Sign-out action instead of the form.
 */
@Composable
fun SignInDialog(
    state: SyncState?,
    onSignIn: (email: String, password: String) -> Unit,
    onSignUp: (email: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    // PRD §15: manual "fetch from server" (pulls the snapshot + every device's exact pause gaps). Null hides it.
    onFetch: (() -> Unit)? = null,
) {
    val signedIn = state != null && state != SyncState.SignedOut
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (signedIn) "Cross-device sync" else "Sign in to sync") },
        text = {
            Column {
                if (signedIn) {
                    Text(
                        when (state) {
                            SyncState.Syncing -> "Syncing…"
                            is SyncState.Error -> "Signed in. Last sync error: ${state.message}"
                            else -> "Signed in. Your data syncs across devices automatically."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (onFetch != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onFetch() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Fetch from server")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state is SyncState.Error) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (signedIn) {
                TextButton(onClick = { onSignOut(); onDismiss() }) { Text("Sign out") }
            } else {
                val enabled = email.isNotBlank() && password.isNotBlank()
                Button(enabled = enabled, onClick = { onSignIn(email.trim(), password); onDismiss() }) {
                    Text("Sign in")
                }
            }
        },
        dismissButton = {
            if (signedIn) {
                TextButton(onClick = onDismiss) { Text("Close") }
            } else {
                val enabled = email.isNotBlank() && password.isNotBlank()
                TextButton(enabled = enabled, onClick = { onSignUp(email.trim(), password); onDismiss() }) {
                    Text("Create account")
                }
            }
        },
    )
}
