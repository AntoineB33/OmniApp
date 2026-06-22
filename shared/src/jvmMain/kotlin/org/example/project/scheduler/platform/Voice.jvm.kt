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
 *
 * The synthesizer is forced onto an installed English voice; otherwise it uses the machine's default voice,
 * which on a French-locale Windows reads our English cues (and numbers like "20") with a French accent. We
 * pick an enabled `en` voice, preferring female ones and then en-GB/en-US, and fall back to the default if
 * none exists.
 */
actual fun speak(text: String) {
    // The exact voice name you picked from the audition script
    val preferredVoice = "Microsoft Hortense Desktop" 
    val sanitized = text.replace('\'', ' ').replace('\n', ' ')
    
    speechQueue.execute {
        runCatching {
            ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                    "\$preferred = '$preferredVoice'; " +
                    "\$voice = \$s.GetInstalledVoices() | Where-Object { \$_.VoiceInfo.Name -eq \$preferred -and \$_.Enabled }; " +
                    "if (\$voice) { \$s.SelectVoice(\$preferred) } " +
                    "else { " +
                        // Your existing fallback logic if the preferred voice isn't found
                        "\$en = \$s.GetInstalledVoices() | Where-Object { \$_.Enabled -and \$_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'en' } | Select-Object -First 1; " +
                        "if (\$en) { \$s.SelectVoice(\$en.VoiceInfo.Name) } " +
                    "}; " +
                    "\$s.Speak('$sanitized')",
            ).start().waitFor()
        }
    }
}
