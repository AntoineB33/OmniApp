package org.example.project.ui

import androidx.compose.ui.input.pointer.PointerIcon

/** iOS has no OS resize cursor; fall back to the crosshair indicator (PRD §8). */
actual fun verticalResizePointerIcon(): PointerIcon = PointerIcon.Crosshair
