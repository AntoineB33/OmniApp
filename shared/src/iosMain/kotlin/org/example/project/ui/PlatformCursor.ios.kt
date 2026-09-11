package org.example.project.ui

import androidx.compose.ui.input.pointer.PointerIcon

/** iOS has no OS resize cursor; fall back to the crosshair indicator (PRD §8). */
actual fun verticalResizePointerIcon(): PointerIcon = PointerIcon.Crosshair

/** iOS has no OS resize cursor; fall back to the crosshair indicator (PRD §8). */
actual fun horizontalResizePointerIcon(): PointerIcon = PointerIcon.Crosshair

/** No OS cursor concept off desktop — every panel edge falls back to the crosshair (PRD §8). */
actual fun panelResizePointerIcon(edge: PanelResizeEdge): PointerIcon = PointerIcon.Crosshair
