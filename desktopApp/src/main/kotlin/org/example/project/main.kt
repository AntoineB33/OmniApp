package org.example.project

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.example.project.scheduler.persistence.createDefaultSchedulerStore

fun main() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "OmniApp",
        ) {
            App(store = createDefaultSchedulerStore())
        }
    }
}
