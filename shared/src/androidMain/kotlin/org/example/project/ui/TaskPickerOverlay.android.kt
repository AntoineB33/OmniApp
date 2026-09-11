package org.example.project.ui

import androidx.compose.runtime.Composable

/** Android has no system-wide chord for a background app to claim ([installGlobalHotkeys] reports Unsupported there) and no pointer to open a menu at, so nothing ever asks for the picker. */
actual fun globalPointerLocation(): ScreenPoint? = null

/** Inert — see [globalPointerLocation]. */
@Composable
actual fun TaskPickerOverlay(
    anchor: ScreenPoint?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) = Unit
