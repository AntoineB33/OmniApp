package org.example.project.scheduler.ui

import androidx.compose.foundation.layout.Column
import org.example.project.ui.rememberWindowFrameState
import org.example.project.ui.AppWindowFrame
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
 * The account / cross-device-sync status in a few words (PRD §5) — the lateral menu's "Online" button and the
 * Online window's first line say the same thing through this, the one reading of it. Null [state] = sync is not
 * part of this build (e.g. the web build without a SyncMetaStore).
 *
 * The app is always connected to an account, so an idle status reads "Guest" on the automatically created
 * credential-less account and "Synced" once the account has an email/password. "No account" is the rare startup
 * window in which the guest account could not be created yet (offline first launch).
 */
fun syncStatusLabel(state: SyncState?, account: AccountInfo?): String =
    when (state) {
        null -> "unavailable"
        SyncState.SignedOut -> "☁ No account"
        SyncState.Idle -> if (account?.isGuest != false) "☁ Guest" else "☁ Synced"
        SyncState.Syncing -> "☁ Syncing…"
        is SyncState.Error -> "☁ Sync error"
        SyncState.Offline -> "✈ Offline"
    }

/**
 * PRD §5: the **Online** window — every online configuration of this device in one lateral-menu window, which
 * replaced the status chip and the "Work offline / Go online" button in the app's top-right corner and the account
 * dialog the chip opened.
 *
 * - **Status**: [syncStatusLabel], and the last sync error when there is one.
 * - **Work offline** (`docs/invariants/sync-and-accounts.md` § *Working offline*): the device-wide switch —
 *   nothing sent, nothing received, edits kept for later; switching it off reconnects and pushes what was done
 *   meanwhile.
 * - **Account**: the app is **always** connected to an account, so this never offers "use the app without one".
 *   On a **guest** account (the credential-less one created on first launch / after a sign-out) it offers
 *   **Create account**, which gives *this* account the typed email + password — same account, same data, now
 *   reachable from the user's other devices — and **Sign in**, which switches this device to an existing account
 *   (the guest is left behind, nothing deleted). On an account with credentials: who is signed in, the manual
 *   **Fetch from server**, and **Sign out**, which lands on a fresh guest account. Working offline, the account
 *   actions need the server, so the section only says so.
 */
@Composable
fun OnlineWindow(
    state: SyncState?,
    account: AccountInfo?,
    /** Null when this build has no offline switch. */
    offline: Boolean?,
    onSetOffline: (Boolean) -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
    onCreateAccount: (email: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    /** PRD §15: manual "fetch from server" (pulls the snapshot + every device's exact pause gaps). */
    onFetch: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
) {
    val frame = rememberWindowFrameState("Online", initialOffset, initialSize)
    AppWindowFrame(
        title = "Online",
        state = frame,
        onClose = onDismiss,
        defaultWidth = 420.dp,
        defaultHeight = 460.dp,
        modifier = modifier,
        onRaise = onRaise,
        onGeometryChange = onGeometryChange,
        claimsKeyboard = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state == null) {
                Text(
                    "Cross-device sync is not part of this build: everything is kept on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            // --- Status ---------------------------------------------------------------------------------
            Text("Status: " + syncStatusLabel(state, account), style = MaterialTheme.typography.titleSmall)
            if (state is SyncState.Error) {
                Text(
                    "Last sync error: ${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // --- Work offline ---------------------------------------------------------------------------
            if (offline != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Work offline", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (offline) {
                                "Nothing is sent to or received from the server on this device. Everything you do " +
                                    "is kept here and synced with your other devices when you go online."
                            } else {
                                "Cut this device off the server: nothing is sent or received, and your edits are " +
                                    "kept for later."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = offline, onCheckedChange = onSetOffline)
                }
            }

            HorizontalDivider()

            // --- Account --------------------------------------------------------------------------------
            AccountSection(
                state = state,
                account = account,
                offline = offline == true,
                onSignIn = onSignIn,
                onCreateAccount = onCreateAccount,
                onSignOut = onSignOut,
                onFetch = onFetch,
            )
        }
    }
}

@Composable
private fun AccountSection(
    state: SyncState,
    account: AccountInfo?,
    offline: Boolean,
    onSignIn: (String, String) -> Unit,
    onCreateAccount: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onFetch: () -> Unit,
) {
    Text("Account", style = MaterialTheme.typography.titleSmall)
    if (offline) {
        Text(
            "The account needs the server: go online to sign in, create an account or sign out" +
                (account?.email?.let { " (signed in as $it)" } ?: "") + ".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // A device with no account yet behaves like the guest case: the same form claims the account it gets.
    val guest = account?.isGuest != false
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text(
        when {
            state == SyncState.Syncing -> "Syncing…"
            guest && account == null ->
                "Working offline on this device. Your data is kept locally and will be attached to an account as " +
                    "soon as the app can reach the server."
            guest ->
                "You are on a guest account: it works exactly like a normal one, but it has no email or password, " +
                    "so no other device can open it. Give it an email and a password below and it becomes your " +
                    "account — same data, on all your devices."
            else -> "Signed in as ${account.email}. Your data syncs across devices automatically."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (guest) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        val enabled = email.isNotBlank() && password.isNotBlank()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = enabled, onClick = { onCreateAccount(email.trim(), password); password = "" }) {
                Text("Create account")
            }
            TextButton(enabled = enabled, onClick = { onSignIn(email.trim(), password); password = "" }) {
                Text("Already have an account? Sign in")
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onFetch) { Text("Fetch from server") }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}
