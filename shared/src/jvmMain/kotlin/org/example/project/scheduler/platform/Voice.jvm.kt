package org.example.project.scheduler.platform

import java.io.File
import java.util.concurrent.Executors
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

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

private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * Directory holding the Piper executable and at least one voice model. Defaults to `~/.omniapp/piper`
 * (the layout produced by `scripts/setup-piper.ps1`), overridable via the `omniapp.piperDir` system
 * property. Piper resolves its bundled `espeak-ng-data` relative to its working directory, so we run it
 * with this directory as the CWD.
 */
private val piperDir: File by lazy {
    val override = System.getProperty("omniapp.piperDir")?.takeIf { it.isNotBlank() }
    if (override != null) File(override) else File(System.getProperty("user.home"), ".omniapp/piper")
}

/** The Piper binary inside [piperDir], or `null` if it isn't installed yet. */
private fun piperExecutable(): File? =
    File(piperDir, if (isWindows) "piper.exe" else "piper").takeIf { it.isFile }

/**
 * The voice model to synthesize with. Honors the `omniapp.piperModel` system property (a file name in
 * [piperDir]); otherwise picks the first `*.onnx` alphabetically. Piper auto-loads the sibling
 * `<model>.onnx.json` config, so only the `.onnx` path is passed.
 */
private fun piperModel(): File? {
    val named = System.getProperty("omniapp.piperModel")?.takeIf { it.isNotBlank() }
        ?.let { File(piperDir, it) }
    if (named != null && named.isFile) return named
    return piperDir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }
        ?.minByOrNull { it.name }
}

/**
 * PRD §15 (20s look-away) voice cue on the desktop. Prefers a local Piper neural voice (free, offline,
 * far less robotic than SAPI); see `scripts/setup-piper.ps1` to install it. If Piper isn't present or
 * fails, it falls back to the Windows SAPI synthesizer so cues still work out of the box.
 *
 * Enqueued on [speechQueue] so it never blocks the UI and cues are serialized; the whole body runs on the
 * worker and blocks until the utterance finishes so the next cue does not start mid-sentence.
 */
actual fun speak(text: String) {
    val sanitized = text.replace('\n', ' ').trim()
    if (sanitized.isEmpty()) return

    speechQueue.execute {
        val spoke = runCatching { speakWithPiper(sanitized) }.getOrDefault(false)
        if (!spoke) {
            runCatching { speakWithSapi(sanitized) }
        }
    }
}

/**
 * Synthesize [text] to a temporary WAV with Piper (text on stdin) and play it. Returns `true` only if
 * Piper is installed, produced audio, and we played it — letting the caller fall back otherwise.
 */
private fun speakWithPiper(text: String): Boolean {
    val exe = piperExecutable() ?: return false
    val model = piperModel() ?: return false

    val wav = File.createTempFile("voice-cue", ".wav")
    try {
        val process = ProcessBuilder(
            exe.absolutePath,
            "--model", model.absolutePath,
            "--output_file", wav.absolutePath,
        ).directory(piperDir) // so Piper finds its bundled espeak-ng-data
            .redirectErrorStream(true)
            .start()

        process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        process.inputStream.readBytes() // drain so the process can't block on a full pipe
        process.waitFor()

        if (process.exitValue() != 0 || wav.length() == 0L) return false
        playWav(wav)
        return true
    } finally {
        wav.delete()
    }
}

/** Stream a PCM WAV file to the default audio line, blocking until playback completes. */
private fun playWav(file: File) {
    AudioSystem.getAudioInputStream(file).use { stream ->
        val format = stream.format
        val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
        line.open(format)
        line.start()
        try {
            val buffer = ByteArray(4096)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                line.write(buffer, 0, read)
            }
            line.drain()
        } finally {
            line.stop()
            line.close()
        }
    }
}

/**
 * Fallback: speak [text] via the Windows SAPI speech synthesizer, driven through PowerShell (no extra
 * dependency). Wrapped by the caller in `runCatching` because speech is unavailable on some setups
 * (non-Windows, no audio device).
 *
 * The synthesizer is forced onto an installed English voice; otherwise it uses the machine's default
 * voice, which on a French-locale Windows reads our English cues (and numbers like "20") with a French
 * accent. We prefer "Microsoft Zira Desktop", then any enabled `en` voice, and fall back to the default.
 * A single quote in [text] is replaced so it can't break the PowerShell single-quoted string.
 */
private fun speakWithSapi(text: String) {
    val preferredVoice = "Microsoft Zira Desktop"
    val sanitized = text.replace('\'', ' ')

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
            "\$en = \$s.GetInstalledVoices() | Where-Object { \$_.Enabled -and \$_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'en' } | Select-Object -First 1; " +
            "if (\$en) { \$s.SelectVoice(\$en.VoiceInfo.Name) } " +
            "}; " +
            "\$s.Speak('$sanitized')",
    ).start().waitFor()
}
