package org.example.project.scheduler.platform

import androidx.compose.ui.input.key.KeyEvent

/**
 * True when this key event is a dead key — the first half of an accent composition such as `^`,
 * `¨` or `~`. A dead key carries no character of its own on `KeyDown`; the composed letter (e.g.
 * `ê`) is only delivered later, through the OS text-input path, to a *focused* text field. The tree
 * uses this to open Edit Mode immediately on the dead key so the following letter composes into the
 * now-focused field.
 */
expect fun KeyEvent.isDeadKey(): Boolean
