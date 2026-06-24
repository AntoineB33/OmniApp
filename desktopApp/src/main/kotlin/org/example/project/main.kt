package org.example.project

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.example.project.scheduler.persistence.createDefaultSchedulerStore

fun main() {
    // Debug tooling (time simulation) is off unless the `omniapp.timeSim` property is set. The dev `run`
    // task sets it (defaulting true); the packaged release never does, so it ships without the debug panel.
    DebugFlags.TIME_SIMULATION = System.getProperty("omniapp.timeSim").toBoolean()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "OmniApp",
        ) {
            App(store = createDefaultSchedulerStore())
        }
    }
}
