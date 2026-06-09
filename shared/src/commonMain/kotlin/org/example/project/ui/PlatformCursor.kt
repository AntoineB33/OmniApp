package org.example.project.ui

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * PRD §8 extend/shorten: the standard "grab this edge to resize" mouse cursor shown when hovering a task
 * panel's top/bottom edge. On desktop this is the OS vertical-resize cursor; other platforms fall back to
 * a crosshair (no OS resize cursor concept).
 */
expect fun verticalResizePointerIcon(): PointerIcon
