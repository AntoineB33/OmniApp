package org.example.project.ui

import androidx.compose.ui.input.pointer.PointerIcon

/** JS/browser: fall back to the crosshair indicator for the resize edge (PRD §8). */
actual fun verticalResizePointerIcon(): PointerIcon = PointerIcon.Crosshair
