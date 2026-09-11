package org.example.project.ui

import androidx.compose.runtime.Composable

/** iOS has no system-wide chord to claim ([installGlobalHotkeys] reports Unsupported there) and no pointer to open a menu at, so nothing ever asks for the picker. */
actual fun globalPointerLocation(): ScreenPoint? = null

/** Inert — see [globalPointerLocation]. */
@Composable
actual fun TaskPickerOverlay(
    anchor: ScreenPoint?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) = Unit
