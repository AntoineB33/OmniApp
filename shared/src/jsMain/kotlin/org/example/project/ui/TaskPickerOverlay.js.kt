package org.example.project.ui

import androidx.compose.runtime.Composable

/** The browser cannot claim a system-wide chord while the app is backgrounded ([installGlobalHotkeys] reports Unsupported there), so nothing ever asks for the picker. */
actual fun globalPointerLocation(): ScreenPoint? = null

/** Inert — see [globalPointerLocation]. */
@Composable
actual fun TaskPickerOverlay(
    anchor: ScreenPoint?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) = Unit
