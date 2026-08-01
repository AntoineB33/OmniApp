package org.example.project.scheduler.platform

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * PRD §18 Alarms on the desktop: a repeating two-tone beep on the default audio line for exactly the
 * alarm's configured length.
 *
 * The tone is **synthesized**, not a bundled asset: an alarm has no spoken content (unlike the §15 voice
 * cues, which are pre-rendered WAVs so every platform speaks the identical voice), and a generated square-ish
 * sine burst is both unmistakable and free of a resource that could fail to load on a packaged app image.
 *
 * Its own single daemon thread — deliberately NOT the [playVoiceCue] worker: that one plays each cue to
 * completion before the next, so a 10-minute alarm queued there would mute every look-away cue for ten
 * minutes. A ring supersedes the previous one via [alarmGeneration] (two alarms due in the same minute must
 * not play over each other), and the audio line is held in [alarmLine] so the superseding ring can cut it.
 *
 * Desktops do not vibrate, so `vibrate` is accepted and ignored — the phone is what honours it.
 */
private const val SAMPLE_RATE = 44_100f

/** The beep pitch — high enough to carry over a room, low enough not to be piercing. */
private const val TONE_HZ = 880.0

/** One ring cycle: a beep and the silence after it. */
private const val BEEP_MILLIS = 400L
private const val GAP_MILLIS = 250L

/** Peak amplitude, well under full scale so the line never clips. */
private const val AMPLITUDE = 0.35

/** Linear ramp at each end of a beep; without it the abrupt start/stop clicks. */
private const val RAMP_MILLIS = 8L

private val alarmExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "alarm-ring").apply { isDaemon = true }
}

/** Bumped by every ring; a sounding ring stops as soon as it sees a newer value. */
private val alarmGeneration = AtomicLong(0)

/** The line backing the ring currently sounding, so a superseding ring can cut it mid-beep. */
@Volatile private var alarmLine: SourceDataLine? = null

actual fun ringAlarmPlatform(label: String, soundSeconds: Int, vibrate: Boolean) {
    if (soundSeconds <= 0) return
    val generation = alarmGeneration.incrementAndGet()
    // Cut whatever is still sounding right away, rather than waiting for the worker to pick this ring up.
    alarmLine?.let { line -> runCatching { line.stop(); line.flush() } }
    alarmExecutor.execute {
        if (generation != alarmGeneration.get()) return@execute // superseded before it started
        runCatching { playAlarmTone(soundSeconds, generation) }
    }
}

/**
 * Writes exactly [soundSeconds] of the beep/silence pattern to the default line, bailing early once
 * [generation] is superseded. One cycle's worth of samples is generated up front and written repeatedly
 * (a 10-minute ring materialized whole would be ~53 MB), and [SourceDataLine.write] blocking on a full
 * buffer is what paces the loop in real time.
 */
private fun playAlarmTone(soundSeconds: Int, generation: Long) {
    // Signed 16-bit little-endian mono — the most widely supported PCM line format.
    val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false)
    val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
    line.open(format)
    line.start()
    alarmLine = line
    try {
        val cycle = cycleBytes()
        val totalBytes = (SAMPLE_RATE * soundSeconds).toInt() * 2
        var written = 0
        while (written < totalBytes && generation == alarmGeneration.get()) {
            val chunk = min(cycle.size, totalBytes - written)
            line.write(cycle, 0, chunk)
            written += chunk
        }
        if (generation == alarmGeneration.get()) line.drain()
    } finally {
        if (alarmLine === line) alarmLine = null
        runCatching { line.stop() }
        runCatching { line.close() }
    }
}

/** One beep-then-silence cycle as 16-bit LE PCM samples. */
private fun cycleBytes(): ByteArray {
    val beepSamples = (SAMPLE_RATE * BEEP_MILLIS / 1000).toInt()
    val gapSamples = (SAMPLE_RATE * GAP_MILLIS / 1000).toInt()
    val rampSamples = (SAMPLE_RATE * RAMP_MILLIS / 1000).toInt().coerceAtLeast(1)
    val out = ByteArray((beepSamples + gapSamples) * 2)
    for (i in 0 until beepSamples) {
        val ramp = min(i, beepSamples - 1 - i).toDouble() / rampSamples
        val envelope = AMPLITUDE * ramp.coerceAtMost(1.0)
        val sample = (sin(2.0 * PI * TONE_HZ * i / SAMPLE_RATE) * envelope * Short.MAX_VALUE).toInt()
        out[i * 2] = (sample and 0xFF).toByte()
        out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    // The gap is left as zeroes (silence).
    return out
}
