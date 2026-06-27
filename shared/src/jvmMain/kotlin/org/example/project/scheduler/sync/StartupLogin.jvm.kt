package org.example.project.scheduler.sync

/**
 * Desktop startup credentials. Priority mirrors [org.example.project.scheduler.persistence.createDefaultSchedulerStore]:
 * the `omniapp.loginUser`/`omniapp.loginPass` JVM properties first (the dev `run` task forwards them via
 * Gradle `-P`, since a reused Gradle daemon makes env vars unreliable for `run`), then the
 * `OMNIAPP_LOGIN_USER`/`OMNIAPP_LOGIN_PASS` env vars (the packaged release launcher sets these — a real exe
 * inherits its launcher's environment). Returns `null` unless both halves are present.
 */
actual fun startupLoginCredentials(): StartupLogin? {
    val user =
        System.getProperty("omniapp.loginUser")?.takeIf { it.isNotBlank() }
            ?: System.getenv("OMNIAPP_LOGIN_USER")?.takeIf { it.isNotBlank() }
    val pass =
        System.getProperty("omniapp.loginPass")?.takeIf { it.isNotBlank() }
            ?: System.getenv("OMNIAPP_LOGIN_PASS")?.takeIf { it.isNotBlank() }
    return if (user != null && pass != null) StartupLogin(user, pass) else null
}
