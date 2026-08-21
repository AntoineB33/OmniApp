package org.example.project.scheduler.platform

/**
 * PRD §15: inert on Android. A backgrounded app cannot claim a system-wide key chord, and a phone has no
 * keyboard to strike one on — the left-menu "I'm away" button is the phone's whole surface for this.
 */
actual fun installGlobalAwayHotkey(onPressed: () -> Unit) = Unit
