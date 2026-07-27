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
import org.example.project.scheduler.sync.AccountInfo
import org.example.project.scheduler.sync.SyncState

/**
 * A compact account / cross-device-sync status chip (PRD §5). Tapping it opens the [SignInDialog]. Renders
 * nothing when sync is disabled ([state] is null — e.g. the web build without a SyncMetaStore).
 *
 * The app is always connected to an account, so an idle chip reads "Guest" on the automatically created
 * credential-less account and "Synced" once the account has an email/password. "No account" is the rare
 * startup window in which the guest account could not be created yet (offline first launch).
 */
@Composable
fun SyncStatusChip(
    state: SyncState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    account: AccountInfo? = null,
) {
    if (state == null) return
    val label =
        when (state) {
            SyncState.SignedOut -> "☁ No account"
            SyncState.Idle -> if (account?.isGuest != false) "☁ Guest" else "☁ Synced"
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
 * The account dialog (PRD §5). The app is **always** connected to an account, so this never offers "use the
 * app without one":
 * - on a **guest** account (the credential-less one created automatically on first launch / after a
 *   sign-out) it offers **Create account**, which gives *this* account the typed email + password — same
 *   account, same data, now reachable from the user's other devices — and **Sign in**, which switches this
 *   device to an existing account (the guest is simply left behind, nothing is deleted);
 * - on an account that has credentials it shows who is signed in, the manual fetch, and **Sign out**, which
 *   lands on a fresh guest account.
 */
@Composable
fun SignInDialog(
    state: SyncState?,
    onSignIn: (email: String, password: String) -> Unit,
    onCreateAccount: (email: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    // PRD §15: manual "fetch from server" (pulls the snapshot + every device's exact pause gaps). Null hides it.
    onFetch: (() -> Unit)? = null,
    // The active account; null while the guest account could not be created yet (offline first launch).
    account: AccountInfo? = null,
) {
    // A device with no account yet behaves like the guest case: the same form claims the account it gets.
    val guest = account?.isGuest != false
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (guest) "Create your account" else "Cross-device sync") },
        text = {
            Column {
                Text(
                    when {
                        state is SyncState.Error -> "Last sync error: ${state.message}"
                        state == SyncState.Syncing -> "Syncing…"
                        guest && account == null ->
                            "Working offline on this device. Your data is kept locally and will be attached " +
                                "to an account as soon as the app can reach the server."
                        guest ->
                            "You are on a guest account: it works exactly like a normal one, but it has no " +
                                "email or password, so no other device can open it. Give it an email and a " +
                                "password below and it becomes your account — same data, on all your devices."
                        else -> "Signed in as ${account.email}. Your data syncs across devices automatically."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (state is SyncState.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (guest) {
                    Spacer(Modifier.height(8.dp))
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
                    Spacer(Modifier.height(8.dp))
                    val enabled = email.isNotBlank() && password.isNotBlank()
                    TextButton(
                        enabled = enabled,
                        onClick = { onSignIn(email.trim(), password); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Already have an account? Sign in")
                    }
                } else if (onFetch != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onFetch() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Fetch from server")
                    }
                }
            }
        },
        confirmButton = {
            if (guest) {
                val enabled = email.isNotBlank() && password.isNotBlank()
                Button(enabled = enabled, onClick = { onCreateAccount(email.trim(), password); onDismiss() }) {
                    Text("Create account")
                }
            } else {
                TextButton(onClick = { onSignOut(); onDismiss() }) { Text("Sign out") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
