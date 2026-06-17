package org.example.project.scheduler.platform

/**
 * PRD §15 (20s look-away): speak [text] aloud as a voice cue. Fire-and-forget and **non-blocking** — it
 * must return immediately without blocking the caller (the UI thread). A no-op on platforms that have no
 * speech synthesis available.
 */
expect fun speak(text: String)
