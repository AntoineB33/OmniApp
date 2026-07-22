package org.example.project

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.example.project.scheduler.persistence.createDefaultSchedulerStore

fun main() {
    // Debug tooling (time simulation) is off unless the `omniapp.timeSim` property is set. The dev `run`
    // task sets it (defaulting true); the packaged release never does, so it ships without the debug panel.
    DebugFlags.TIME_SIMULATION = System.getProperty("omniapp.timeSim").toBoolean()
    // Debug fast-break override (for testing the pause-cue voice message on real phones): shrink the 5-min
    // screen break with `-Pomniapp.breakDurationMs` (its length) and `-Pomniapp.breakIntervalMs` (how long
    // after the previous pause it comes due) so a break — and the cron's server→phone cue — arrives in
    // seconds. `-Pomniapp.breakPauseThresholdMs` decouples the qualifying-pause length from the drawn length
    // (place a short break only after a long pause). Absent leaves production timings. Never set by the release.
    System.getProperty("omniapp.breakDurationMs")?.toLongOrNull()?.let { DebugFlags.breakDurationMillisOverride = it }
    System.getProperty("omniapp.breakIntervalMs")?.toLongOrNull()?.let { DebugFlags.breakIntervalMillisOverride = it }
    System.getProperty("omniapp.breakPauseThresholdMs")?.toLongOrNull()?.let { DebugFlags.breakPauseThresholdMillisOverride = it }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "OmniApp",
        ) {
            App(store = createDefaultSchedulerStore())
        }
    }
}
