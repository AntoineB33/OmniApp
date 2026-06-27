package org.example.project.scheduler.sync

/**
 * Holds startup credentials handed in via the launch Intent. `MainActivity` populates [creds] from the
 * `omniapp_login_user`/`omniapp_login_pass` extras (the `account3-deploy-android` script passes them on
 * `am start`) before the shared `TaskSchedulerViewModel` is built, so the VM's auto-login picks them up.
 * Boot/Service launches carry no extras, leaving this `null` (the cached session is restored instead).
 */
object AndroidStartupLogin {
    @Volatile var creds: StartupLogin? = null
}

actual fun startupLoginCredentials(): StartupLogin? = AndroidStartupLogin.creds
