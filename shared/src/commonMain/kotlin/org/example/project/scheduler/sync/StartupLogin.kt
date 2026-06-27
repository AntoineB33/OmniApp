package org.example.project.scheduler.sync

/**
 * Credentials supplied to the app at launch so it can sign in to cross-device sync (PRD §5)
 * non-interactively — used by the per-account `/scripts` entry points to open the app already logged in.
 *
 * The [username] is an arbitrary string (not necessarily an email); [usernameToEmail] maps it to the
 * email Supabase GoTrue actually authenticates with.
 */
data class StartupLogin(val username: String, val password: String)

/**
 * Domain appended to a bare [StartupLogin.username] to form the Supabase account email. Supabase auth needs
 * an email, but the accounts are keyed by plain usernames; `omniapp.local` is a non-routable placeholder, so
 * the Supabase project must have email confirmation disabled for these accounts. Keep this in sync with the
 * `LOGIN_DOMAIN` default in `scripts/internal/account_db_admin.py`.
 */
const val LOGIN_EMAIL_DOMAIN: String = "omniapp.local"

/** Maps a sign-in username to the Supabase email. A value that already looks like an email passes through. */
fun usernameToEmail(username: String): String =
    if ('@' in username) username else "$username@$LOGIN_EMAIL_DOMAIN"

/**
 * Platform-supplied startup credentials, or `null` when none were provided (the normal interactive case).
 *
 * - Desktop reads the `omniapp.loginUser`/`omniapp.loginPass` JVM properties (forwarded by the dev `run`
 *   task) or the `OMNIAPP_LOGIN_USER`/`OMNIAPP_LOGIN_PASS` env vars (set by the packaged release launcher).
 * - Android reads credentials handed in as `MainActivity` launch-Intent extras.
 * - Other platforms have no script-driven launch and always return `null`.
 */
expect fun startupLoginCredentials(): StartupLogin?
