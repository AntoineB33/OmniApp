package org.example.project.scheduler.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking

/**
 * PRD §15 voice cues on Android. Plays the **same bundled Piper WAVs** as the desktop through an [AudioTrack]
 * (raw PCM), so the phone speaks with the desktop's neural voice instead of the device's robotic built-in
 * `TextToSpeech` — which for the 20 s look-away often produced no audible cue at all (the reported anomaly).
 *
 * A single daemon worker serializes cues (so a "look away" / "resume" pair that comes due together plays
 * back-to-back, FIFO), mirroring the desktop worker; [stopSpeaking] bumps [generation], clears the pending
 * queue, and pauses/flushes the live track so a superseded cue stops mid-stream. The worker plays the whole
 * clip to completion before the next so cues never overlap.
 */
private val workQueue = LinkedBlockingQueue<Runnable>()
private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, workQueue) { runnable ->
    Thread(runnable, "voice-cue").apply { isDaemon = true }
}

/** Cancellation token bumped by [stopSpeaking]; each cue captures it at submit time and bails once superseded. */
private val generation = AtomicLong(0)

/** The [AudioTrack] backing the *currently playing* cue, so [stopSpeaking] can cut it. */
@Volatile private var currentTrack: AudioTrack? = null

actual fun playVoiceCue(cue: VoiceCue) {
    val gen = generation.get()
    worker.execute {
        if (gen != generation.get()) return@execute // superseded before this cue started
        val bytes = runCatching { runBlocking { voiceCueBytes(cue) } }.getOrNull() ?: return@execute
        runCatching { playWavPcm(bytes, gen) }
    }
}

actual fun stopSpeaking() {
    generation.incrementAndGet()
    workQueue.clear()
    currentTrack?.let { track -> runCatching { track.pause(); track.flush() } }
}

/**
 * Parse a canonical PCM WAV (Piper output: 16-bit mono 22.05 kHz) and play it through a MODE_STATIC
 * [AudioTrack], blocking the worker until playback completes — or until [stopSpeaking] bumps [gen], in which
 * case the wait ends at once (the track was already paused/flushed).
 */
private fun playWavPcm(bytes: ByteArray, gen: Long) {
    val wav = parseWav(bytes) ?: return
    if (wav.data.isEmpty()) return
    val channelMask =
        if (wav.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    val track = AudioTrack(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        AudioFormat.Builder()
            .setSampleRate(wav.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelMask)
            .build(),
        wav.data.size,
        AudioTrack.MODE_STATIC,
        AudioManager.AUDIO_SESSION_ID_GENERATE,
    )
    currentTrack = track
    try {
        track.write(wav.data, 0, wav.data.size)
        track.play()
        val totalFrames = wav.data.size / (2 * maxOf(1, wav.channels)) // 16-bit samples
        // A hard cap so a stuck track can't wedge the worker: the clip's duration plus a little slack.
        val maxWaitMs = totalFrames.toLong() * 1000 / maxOf(1, wav.sampleRate) + 500
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (gen == generation.get() &&
            track.playbackHeadPosition < totalFrames &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
    } finally {
        currentTrack = null
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}

private class Wav(val sampleRate: Int, val channels: Int, val data: ByteArray)

/**
 * Minimal little-endian RIFF/WAVE parser: walks the chunks for `fmt ` (sample rate / channels) and `data`
 * (the PCM payload). Returns null if the bytes aren't a WAV or have no data chunk. We only ever feed it
 * Piper's own canonical 16-bit PCM output, but walking the chunks (rather than assuming a 44-byte header)
 * keeps it correct if Piper ever emits a `LIST`/`fact` chunk before `data`.
 */
private fun parseWav(bytes: ByteArray): Wav? {
    if (bytes.size < 44) return null
    fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
    fun u32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
        ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
    fun tag(o: Int) = String(bytes, o, 4, Charsets.US_ASCII)
    if (tag(0) != "RIFF" || tag(8) != "WAVE") return null

    var sampleRate = 0
    var channels = 1
    var dataStart = -1
    var dataLen = 0
    var o = 12
    while (o + 8 <= bytes.size) {
        val id = tag(o)
        val size = u32(o + 4)
        val body = o + 8
        when (id) {
            "fmt " -> if (body + 16 <= bytes.size) {
                channels = u16(body + 2)
                sampleRate = u32(body + 4)
            }
            "data" -> {
                dataStart = body
                dataLen = size
            }
        }
        o = body + size + (size and 1) // chunks are word-aligned
    }
    if (dataStart < 0 || sampleRate <= 0) return null
    val end = minOf(dataStart + dataLen, bytes.size)
    if (end <= dataStart) return null
    return Wav(sampleRate, channels, bytes.copyOfRange(dataStart, end))
}
