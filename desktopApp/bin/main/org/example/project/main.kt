package org.example.project

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    val store = FileSchedulerStore.default()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "KotlinScheduler",
        ) {
            App(store = store)
        }
    }
}