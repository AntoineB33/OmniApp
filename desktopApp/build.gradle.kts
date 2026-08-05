import org.gradle.api.tasks.JavaExec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "org.example.project.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.example.project"
            packageVersion = "0.5.0"
            // The packaged app jlinks a minimal runtime; SQLDelight's SQLite driver needs java.sql
            // (java.sql.DriverManager), so include it or the release crashes when it opens the DB.
            modules("java.sql")
        }
    }
}

// Forward `-Pomniapp.stateDir=<path>` to the launched app JVM as a system
// property so dev scripts can point the app at an isolated state directory.
// (The compose `run` task is a JavaExec subtype.)
tasks.withType<JavaExec>().configureEach {
    (project.findProperty("omniapp.stateDir") as String?)?.let { stateDir ->
        systemProperty("omniapp.stateDir", stateDir)
    }
    // Forward non-interactive login credentials (the per-account `/scripts` pass these via -P) so the app
    // can sign in to a chosen account at launch. Properties (not env) because the `run` task forks from a
    // reused Gradle daemon whose environment is unreliable.
    (project.findProperty("omniapp.loginUser") as String?)?.let { systemProperty("omniapp.loginUser", it) }
    (project.findProperty("omniapp.loginPass") as String?)?.let { systemProperty("omniapp.loginPass", it) }
    // The dev `run` task (a JavaExec) enables time simulation by default; override with
    // `-Pomniapp.timeSim=false`. The packaged release (createDistributable, not a JavaExec) never sets it,
    // so main() reads it absent and ships with the debug tooling off.
    systemProperty("omniapp.timeSim", (project.findProperty("omniapp.timeSim") as String?) ?: "true")
    // Debug fast-break override for testing the pause-cue voice message (`-Pomniapp.breakDurationMs`,
    // `-Pomniapp.breakIntervalMs`, `-Pomniapp.breakPauseThresholdMs`); forwarded only when set, so the app
    // defaults to production break timings. This unprefixed trio retimes the 5-min pose only — the older
    // spelling, kept for account2-open-fast-break.bat and the Android deploy scripts.
    (project.findProperty("omniapp.breakDurationMs") as String?)?.let { systemProperty("omniapp.breakDurationMs", it) }
    (project.findProperty("omniapp.breakIntervalMs") as String?)?.let { systemProperty("omniapp.breakIntervalMs", it) }
    (project.findProperty("omniapp.breakPauseThresholdMs") as String?)?.let { systemProperty("omniapp.breakPauseThresholdMs", it) }
    // Per-break form: `-Pomniapp.break.<lookAway|pose5|pose15>.<durationMs|intervalMs|pauseThresholdMs>`, so
    // ALL THREE screen breaks can be retimed independently (account1-empty-open-fast-break.bat). Same rule:
    // forwarded only when set, and `main()` leaves every unset rule at its production value.
    listOf("lookAway", "pose5", "pose15").forEach { breakName ->
        listOf("durationMs", "intervalMs", "pauseThresholdMs").forEach { rule ->
            val property = "omniapp.break.$breakName.$rule"
            (project.findProperty(property) as String?)?.let { systemProperty(property, it) }
        }
    }
}