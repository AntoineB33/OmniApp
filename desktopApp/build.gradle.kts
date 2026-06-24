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
    // The dev `run` task (a JavaExec) enables time simulation by default; override with
    // `-Pomniapp.timeSim=false`. The packaged release (createDistributable, not a JavaExec) never sets it,
    // so main() reads it absent and ships with the debug tooling off.
    systemProperty("omniapp.timeSim", (project.findProperty("omniapp.timeSim") as String?) ?: "true")
}