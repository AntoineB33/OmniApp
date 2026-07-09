package org.example.project.scheduler.platform

import omniapp.shared.generated.resources.Res

/**
 * PRD §15: the fixed set of spoken cues. Each phrase is pre-rendered **once** with the desktop's Piper neural
 * voice (`en_US-amy-medium`) into a bundled WAV under `commonMain/composeResources/files/`, so every platform
 * — desktop and phone alike — speaks with the **same** voice, offline, and with no live TTS engine to
 * initialize. Previously the phone used its robotic built-in `TextToSpeech`, which sounded nothing like the
 * desktop and (for the 20 s look-away) often produced no audible cue at all; playing the shared WAV fixes both.
 *
 * [fallbackText] is used only by a platform that fails to load/play the bundled asset (e.g. the desktop can
 * re-synthesize the phrase live via Piper/SAPI). If a phrase changes, re-render its WAV with the same Piper
 * model (see `scripts/setup-piper.ps1` for the model; the desktop's `Voice.jvm.kt` documents the invocation).
 */
enum class VoiceCue(val resourcePath: String, val fallbackText: String) {
    LookAway("files/voice_look_away.wav", "Look 20 feet away"),
    ResumeWork("files/voice_resume_work.wav", "Resume your work"),
    PauseOver("files/voice_pause_over.wav", "Your pause is over. You can resume your work."),
}

/** Load the bundled audio bytes for [cue] from the shared compose resources, or null if it can't be read. */
suspend fun voiceCueBytes(cue: VoiceCue): ByteArray? =
    runCatching { Res.readBytes(cue.resourcePath) }.getOrNull()

/**
 * PRD §15: play [cue] aloud with the shared bundled voice. Fire-and-forget and **non-blocking** — it returns
 * immediately and the audio plays on a platform worker. Cues that come due together are serialized (played
 * back-to-back, FIFO) rather than overlapping. A no-op on a platform with no audio output available.
 */
expect fun playVoiceCue(cue: VoiceCue)

/**
 * PRD §15: cut the cue currently playing (if any) and drop every cue still queued behind it. Used by the
 * manual "look away now" trigger to supersede a look-away cue that is still sounding or pending. Non-blocking
 * and a no-op where audio isn't available.
 */
expect fun stopSpeaking()
