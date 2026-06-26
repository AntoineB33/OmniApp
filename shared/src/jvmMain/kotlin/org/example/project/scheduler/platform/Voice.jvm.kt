package org.example.project.scheduler.platform

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Single daemon worker that plays voice cues one at a time. Each [speak] enqueues onto it and returns
 * immediately (non-blocking to the UI), while the worker speaks each utterance to completion before the
 * next — so cues that come due together (e.g. a 20s look-away's "look away" and "resume" lines arriving in
 * the same tick under heavy time-acceleration) play back-to-back in FIFO order instead of talking over
 * each other.
 *
 * A bare [ThreadPoolExecutor] (rather than `Executors.newSingleThreadExecutor`) so [stopSpeaking] can reach
 * the backing queue and drop cues that haven't started yet.
 */
private val speechWorkQueue = LinkedBlockingQueue<Runnable>()
private val speechQueue = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, speechWorkQueue) { runnable ->
    Thread(runnable, "voice-cue").apply { isDaemon = true }
}

/**
 * Cancellation token bumped by [stopSpeaking]. Each enqueued cue captures the value at submit time and
 * no-ops once it has been superseded; [playWav] and the synth steps poll it so a cut cue stops mid-stream.
 */
private val speechGeneration = AtomicLong(0)

/** The audio line / synth process backing the *currently playing* cue, so [stopSpeaking] can cut it. */
@Volatile private var currentLine: SourceDataLine? = null

@Volatile private var currentProcess: Process? = null

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
 * worker and blocks until the utterance finishes so the next cue does not start mid-sentence. The cue
 * captures [speechGeneration] at submit time and bails if a [stopSpeaking] has since superseded it.
 */
actual fun speak(text: String) {
    val sanitized = text.replace('\n', ' ').trim()
    if (sanitized.isEmpty()) return

    val generation = speechGeneration.get()
    speechQueue.execute {
        if (generation != speechGeneration.get()) return@execute // superseded before this cue started
        val spoke = runCatching { speakWithPiper(sanitized, generation) }.getOrDefault(false)
        if (!spoke && generation == speechGeneration.get()) {
            runCatching { speakWithSapi(sanitized) }
        }
    }
}

/**
 * PRD §15 (20s look-away): cut the cue currently playing and drop every cue still queued. Bumps
 * [speechGeneration] (so a queued cue that has already been dequeued onto the worker no-ops), clears the
 * pending queue, then stops the live audio line / synth process so an in-progress utterance ends at once.
 */
actual fun stopSpeaking() {
    speechGeneration.incrementAndGet()
    speechWorkQueue.clear()
    currentProcess?.destroyForcibly()
    currentLine?.let { line -> runCatching { line.stop(); line.flush() } }
}

/**
 * Synthesize [text] to a temporary WAV with Piper (text on stdin) and play it. Returns `true` only if
 * Piper is installed, produced audio, and we played it — letting the caller fall back otherwise. A cut
 * ([generation] superseded) returns `true` so the caller does NOT fall back to SAPI and re-speak it.
 */
private fun speakWithPiper(text: String, generation: Long): Boolean {
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
        currentProcess = process
        try {
            process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            process.inputStream.readBytes() // drain so the process can't block on a full pipe
            process.waitFor()
        } finally {
            currentProcess = null
        }

        if (generation != speechGeneration.get()) return true // cut during synth: swallow, don't fall back
        if (process.exitValue() != 0 || wav.length() == 0L) return false
        playWav(wav, generation)
        return true
    } finally {
        wav.delete()
    }
}

/**
 * Stream a PCM WAV file to the default audio line, blocking until playback completes — or until
 * [stopSpeaking] bumps [generation], in which case the line is flushed (unblocking [write]) and we bail
 * without draining so the cue is cut.
 */
private fun playWav(file: File, generation: Long) {
    AudioSystem.getAudioInputStream(file).use { stream ->
        val format = stream.format
        val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
        line.open(format)
        line.start()
        currentLine = line
        try {
            val buffer = ByteArray(4096)
            while (generation == speechGeneration.get()) {
                val read = stream.read(buffer)
                if (read < 0) break
                line.write(buffer, 0, read)
            }
            if (generation == speechGeneration.get()) line.drain()
        } finally {
            currentLine = null
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
 * A single quote in [text] is replaced so it can't break the PowerShell single-quoted string. The process
 * is tracked in [currentProcess] so [stopSpeaking] can destroy it mid-utterance.
 */
private fun speakWithSapi(text: String) {
    val preferredVoice = "Microsoft Zira Desktop"
    val sanitized = text.replace('\'', ' ')

    val process = ProcessBuilder(
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
    ).start()
    currentProcess = process
    try {
        process.waitFor()
    } finally {
        currentProcess = null
    }
}
