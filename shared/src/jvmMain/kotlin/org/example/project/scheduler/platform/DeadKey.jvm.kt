package org.example.project.scheduler.platform

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode

// AWT lays out its dead-key virtual key codes contiguously, from VK_DEAD_GRAVE (0x80) through
// VK_DEAD_SEMIVOICED_SOUND (0x8F). 0x90 onward is VK_NUM_LOCK and other non-dead keys.
private val DEAD_KEY_CODES = 0x80..0x8F

actual fun KeyEvent.isDeadKey(): Boolean = key.nativeKeyCode in DEAD_KEY_CODES
