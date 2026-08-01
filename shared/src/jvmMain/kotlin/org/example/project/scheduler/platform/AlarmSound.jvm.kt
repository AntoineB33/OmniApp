package org.example.project.scheduler.platform

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.min

/**
 * PRD §18 Alarms on the desktop: the **acoustic guitar** arpeggio ([AlarmTone]) repeating on the default audio
 * line for exactly the alarm's configured length.
 *
 * The sound is **synthesized in common code**, not a bundled asset — see [AlarmTone] for why — so the desktop
 * and the phone ring with the identical waveform, and nothing here can fail to load in a packaged app image.
 *
 * Its own single daemon thread — deliberately NOT the [playVoiceCue] worker: that one plays each cue to
 * completion before the next, so a 10-minute alarm queued there would mute every look-away cue for ten
 * minutes. A ring supersedes the previous one via [alarmGeneration] (two alarms due in the same minute must
 * not play over each other), and the audio line is held in [alarmLine] so the superseding ring can cut it.
 *
 * Desktops do not vibrate, so `vibrate` is accepted and ignored — the phone is what honours it.
 */
private val alarmExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "alarm-ring").apply { isDaemon = true }
}

/** Bumped by every ring; a sounding ring stops as soon as it sees a newer value. */
private val alarmGeneration = AtomicLong(0)

/** The line backing the ring currently sounding, so a superseding ring can cut it mid-note. */
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
 * Writes exactly [soundSeconds] of the guitar loop to the default line, bailing early once [generation] is
 * superseded. One loop cycle is synthesized up front and written repeatedly (a 10-minute ring materialized
 * whole would be ~53 MB), and [SourceDataLine.write] blocking on a full buffer is what paces the loop in real
 * time. A cut mid-cycle is inaudible as a seam because the cycle fades in and out at its edges.
 */
private fun playAlarmTone(soundSeconds: Int, generation: Long) {
    // Signed 16-bit little-endian mono — the most widely supported PCM line format.
    val format = AudioFormat(AlarmTone.SAMPLE_RATE.toFloat(), 16, 1, true, false)
    val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
    line.open(format)
    line.start()
    alarmLine = line
    try {
        val cycle = AlarmTone.loopPcm()
        val totalBytes = AlarmTone.SAMPLE_RATE * soundSeconds * 2
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
