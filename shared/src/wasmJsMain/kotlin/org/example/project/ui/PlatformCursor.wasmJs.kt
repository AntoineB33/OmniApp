package org.example.project.ui

import androidx.compose.ui.input.pointer.PointerIcon

/** Wasm/browser: fall back to the crosshair indicator for the resize edges (PRD §8). */
actual fun verticalResizePointerIcon(): PointerIcon = PointerIcon.Crosshair

/** Wasm/browser: fall back to the crosshair indicator for the resize edges (PRD §8). */
actual fun horizontalResizePointerIcon(): PointerIcon = PointerIcon.Crosshair
