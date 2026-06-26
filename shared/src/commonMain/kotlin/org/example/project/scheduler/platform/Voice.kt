package org.example.project.scheduler.platform

/**
 * PRD §15 (20s look-away): speak [text] aloud as a voice cue. Fire-and-forget and **non-blocking** — it
 * must return immediately without blocking the caller (the UI thread). A no-op on platforms that have no
 * speech synthesis available.
 */
expect fun speak(text: String)

/**
 * PRD §15 (20s look-away): cut the utterance currently being spoken (if any) and drop every cue still
 * queued behind it (see [speak]). Used by the manual "look away now" trigger to supersede a look-away
 * cue that is still sounding or pending. Non-blocking and a no-op where speech isn't available.
 */
expect fun stopSpeaking()
