package org.example.project.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/** Desktop: the OS north/south resize cursor — the standard "grab the edge" shape (PRD §8). */
actual fun verticalResizePointerIcon(): PointerIcon = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))
