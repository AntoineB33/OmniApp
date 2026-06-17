package org.example.project.scheduler.platform

import java.util.concurrent.Executors

/**
 * Single daemon worker that plays voice cues one at a time. Each [speak] enqueues onto it and returns
 * immediately (non-blocking to the UI), while the worker speaks each utterance to completion before the
 * next — so cues that come due together (e.g. a 20s look-away's "look away" and "resume" lines arriving in
 * the same tick under heavy time-acceleration) play back-to-back in FIFO order instead of talking over
 * each other.
 */
private val speechQueue = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "voice-cue").apply { isDaemon = true }
}

/**
 * PRD §15 (20s look-away) voice cue on the desktop: speak [text] via the Windows SAPI speech synthesizer,
 * driven through PowerShell (no extra dependency). Enqueued on [speechQueue] so it never blocks the UI and
 * cues are serialized; wrapped in `runCatching` because speech is unavailable on some setups (non-Windows,
 * no audio device). `waitFor()` holds the worker until the utterance finishes so the next cue does not
 * start mid-sentence. A single quote in [text] is replaced so it can't break the PowerShell single-quoted
 * string (our cues have none, but this keeps the call robust).
 */
actual fun speak(text: String) {
    val sanitized = text.replace('\'', ' ').replace('\n', ' ')
    speechQueue.execute {
        runCatching {
            ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('$sanitized')",
            ).start().waitFor()
        }
    }
}
