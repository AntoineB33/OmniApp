package org.example.project.scheduler.platform

import android.view.KeyCharacterMap
import androidx.compose.ui.input.key.KeyEvent

actual fun KeyEvent.isDeadKey(): Boolean =
    (nativeKeyEvent.unicodeChar and KeyCharacterMap.COMBINING_ACCENT) != 0
