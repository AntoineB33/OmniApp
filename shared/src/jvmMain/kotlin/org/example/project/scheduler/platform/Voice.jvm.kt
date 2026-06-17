package org.example.project.scheduler.platform

/**
 * PRD §15 (20s look-away) voice cue on the desktop: speak [text] via the Windows SAPI speech synthesizer,
 * driven through PowerShell (no extra dependency). Launched on a daemon thread so the ~process start-up
 * never blocks the UI, and wrapped in `runCatching` because speech is unavailable on some setups
 * (non-Windows, no audio device). A single quote in [text] is replaced so it can't break the PowerShell
 * single-quoted string (our cues have none, but this keeps the call robust).
 */
actual fun speak(text: String) {
    val sanitized = text.replace('\'', ' ').replace('\n', ' ')
    Thread {
        runCatching {
            ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-Command",
                "Add-Type -AssemblyName System.Speech; " +
                    "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('$sanitized')",
            ).start()
        }
    }.apply { isDaemon = true }.start()
}
