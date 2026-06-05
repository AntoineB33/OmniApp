package org.example.project

/**
 * Compile-time switches for in-app debug tooling. Because these are `const`, flipping one to `false`
 * dead-code-eliminates the guarded UI, so it never appears in a shipped build — flip [TIME_SIMULATION]
 * off before release.
 */
object DebugFlags {
    /** Shows the time-acceleration panel and drives the scheduler/calendar from a [org.example.project.time.SimAppClock]. */
    const val TIME_SIMULATION: Boolean = true
}
