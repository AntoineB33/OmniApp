package org.example.project

/**
 * Switches for in-app debug tooling, set once at startup from the platform entry point (before [App]
 * composes) and never changed afterwards. **Off by default**, so a packaged/release build never shows the
 * debug tooling. The desktop dev `run` task turns [TIME_SIMULATION] on via the `omniapp.timeSim` system
 * property (see `desktopApp/build.gradle.kts` and `desktopApp/.../main.kt`); `createDistributable` does
 * not set it, so the installed release stays off.
 */
object DebugFlags {
    /** Shows the time-acceleration panel and drives the scheduler/calendar from a [org.example.project.time.SimAppClock]. */
    var TIME_SIMULATION: Boolean = false
}
