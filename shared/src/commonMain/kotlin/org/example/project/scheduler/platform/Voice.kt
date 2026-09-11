package org.example.project.scheduler.platform

import omniapp.shared.generated.resources.Res

/**
 * PRD §15: the fixed set of cues that have a **pre-rendered** phrase. Each is rendered **once** with the
 * desktop's Piper neural voice (`en_US-amy-medium`) into a bundled WAV under `commonMain/composeResources/
 * files/`, so every platform — desktop and phone alike — speaks these with the **same** voice, offline, and
 * with no live TTS engine to initialize. Previously the phone used its robotic built-in `TextToSpeech`, which
 * sounded nothing like the desktop and (for the 20 s look-away) often produced no audible cue at all; playing
 * the shared WAV fixes both.
 *
 * These are the phrases PRD §15 fixes word for word (a look-away's "look away" / "resume", the pause-over
 * push). **Every other notification is spoken from its own text** ([VoiceUtterance.forNotification]) — the
 * app says "task to do now: <this task>", and no enum can carry a task's title — so a phrase with no entry
 * here is not a missing cue, it is the ordinary case.
 *
 * [fallbackText] is what a platform synthesizes when it fails to load/play the bundled asset; it must stay
 * the phrase the WAV says. If a phrase changes, re-render its WAV with the same Piper model (see
 * `scripts/setup-piper.ps1` for the model; the desktop's `Voice.jvm.kt` documents the invocation).
 */
enum class VoiceCue(val resourcePath: String, val fallbackText: String) {
    LookAway("files/voice_look_away.wav", "Look 20 feet away"),
    ResumeWork("files/voice_resume_work.wav", "Resume your work"),
    PauseOver("files/voice_pause_over.wav", "Your pause is over. You can resume your work."),
}

/**
 * PRD §11/§15: **one thing to say aloud** — the single currency of the speech seam, so there is one queue,
 * one cut ([stopSpeaking]) and one worker however the phrase was arrived at.
 *
 * [text] is always the whole phrase. [cue] is non-null only when that phrase is one of the fixed ones with a
 * bundled recording, in which case the platform plays the WAV and falls back to synthesizing [text]; with no
 * [cue] there is nothing to play and the platform synthesizes [text] directly. That is what lets *every*
 * notification have a voice without pre-rendering phrases that contain a task title, an alarm's label or a
 * chord.
 */
data class VoiceUtterance(val text: String, val cue: VoiceCue? = null) {
    companion object {
        /** The fixed phrase [cue] records. */
        fun of(cue: VoiceCue): VoiceUtterance = VoiceUtterance(cue.fallbackText, cue)

        /**
         * What a notification `(title, message)` SOUNDS like — the one place that is decided, so the spoken
         * half of a notification can never drift from the posted half.
         *
         * [cue] is passed only by the notifications PRD §15 fixes the wording of (the look-away's start and
         * its resume): there the recorded phrase *is* the message, and playing it keeps those two cues on the
         * bundled voice they have always had. Everything else is read from its own text, which is the whole
         * point — a notification the user can see but not hear would be exactly the gap this closes.
         */
        fun forNotification(title: String, message: String, cue: VoiceCue? = null): VoiceUtterance =
            if (cue != null) of(cue) else VoiceUtterance(spokenNotificationText(title, message))
    }
}

/**
 * The notification `(title, message)` as a spoken sentence.
 *
 * A notification is written to be READ — two fields, a dash between a chord and its action, a line break the
 * eye skips. Spoken verbatim that comes out as punctuation ("Ctrl plus Shift em-dash look away now"), so the
 * text is normalized first: line breaks and dashes become sentence punctuation, runs of whitespace collapse,
 * and the title is dropped when the message already carries it (an alarm whose label is the word "Alarm"
 * should not say it twice). Kept pure and shared — it is asserted directly in `NotificationVoiceTest`.
 */
fun spokenNotificationText(title: String, message: String): String {
    val spokenTitle = speakableForm(title)
    val spokenMessage = speakableForm(message)
    return when {
        spokenMessage.isEmpty() -> spokenTitle
        spokenTitle.isEmpty() -> spokenMessage
        spokenMessage.startsWith(spokenTitle, ignoreCase = true) -> spokenMessage
        else -> "$spokenTitle. $spokenMessage"
    }
}

/** One field of a notification as speech: dashes to commas, line breaks to sentence ends, whitespace collapsed. */
private fun speakableForm(raw: String): String =
    raw.replace('\r', '\n')
        .split('\n')
        .map { line ->
            line.replace('\t', ' ').split(' ').filter { it.isNotEmpty() }.joinToString(" ")
                // A dash set off by spaces is a pause, so it becomes the comma a reader hears there; one
                // inside a word (a range, a compound) becomes a bare comma rather than the word "dash".
                .replace(" — ", ", ").replace(" – ", ", ")
                .replace('—', ',').replace('–', ',')
                .trim().trimEnd(',')
        }
        .filter { it.isNotEmpty() }
        .joinToString(". ")

/** Load the bundled audio bytes for [cue] from the shared compose resources, or null if it can't be read. */
suspend fun voiceCueBytes(cue: VoiceCue): ByteArray? =
    runCatching { Res.readBytes(cue.resourcePath) }.getOrNull()

/**
 * PRD §11/§15: say [utterance] aloud. Fire-and-forget and **non-blocking** — it returns immediately and the
 * audio plays on a platform worker. Utterances that come due together are serialized (spoken back-to-back,
 * FIFO) rather than overlapping. A no-op on a platform with no audio output available.
 */
expect fun speak(utterance: VoiceUtterance)

/** PRD §15: say the fixed phrase [cue] records. */
fun playVoiceCue(cue: VoiceCue) = speak(VoiceUtterance.of(cue))

/**
 * PRD §15: cut the utterance currently being spoken (if any) and drop every one still queued behind it. Used
 * by the manual "look away now" trigger to supersede a look-away cue that is still sounding or pending.
 * Non-blocking and a no-op where audio isn't available.
 */
expect fun stopSpeaking()
