package org.example.project.scheduler.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.example.project.scheduler.persistence.AndroidSchedulerStoreHolder

/**
 * PRD §11/§15 spoken output on Android. An utterance naming a [VoiceCue] plays the **same bundled Piper WAV**
 * as the desktop through an [AudioTrack] (raw PCM), so the phone speaks the fixed phrases with the desktop's
 * neural voice instead of the device's robotic built-in `TextToSpeech` — which for the 20 s look-away often
 * produced no audible cue at all (the reported anomaly). An utterance with no cue — every ordinary
 * notification, whose text carries a task title, an alarm label or a chord — is spoken by `TextToSpeech`,
 * which is the only thing on a phone that can say a phrase nobody pre-rendered. The bundled asset is always
 * preferred where there is one, so the voice the user hears most is still the shared one.
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

actual fun speak(utterance: VoiceUtterance) {
    val gen = generation.get()
    worker.execute {
        if (gen != generation.get()) return@execute // superseded before this utterance started
        val cue = utterance.cue
        if (cue != null) {
            val bytes = runCatching { runBlocking { voiceCueBytes(cue) } }.getOrNull()
            if (bytes != null && runCatching { playWavPcm(bytes, gen) }.getOrDefault(false)) return@execute
        }
        // No bundled recording for this phrase (the ordinary case), or it could not be played: synthesize.
        runCatching { speakWithTts(utterance.text, gen) }
    }
}

actual fun stopSpeaking() {
    generation.incrementAndGet()
    workQueue.clear()
    currentTrack?.let { track -> runCatching { track.pause(); track.flush() } }
    runCatching { tts?.stop() }
}

/**
 * The phone's own synthesizer, created on first use and kept: initializing one costs hundreds of
 * milliseconds, which a cue announced at the instant a break falls due cannot spend every time. Built from
 * the app `Context` the store holder owns (the same one the notifications use), so it is simply unavailable —
 * and the utterance silently dropped, like any other audio the platform cannot produce — before the app has
 * one.
 */
@Volatile private var tts: TextToSpeech? = null

@Volatile private var ttsReady = false
private val ttsLock = Any()
private val utteranceSeq = AtomicLong(0)

private fun textToSpeech(): TextToSpeech? {
    tts?.let { return if (ttsReady) it else null }
    synchronized(ttsLock) {
        if (tts == null) {
            val context = AndroidSchedulerStoreHolder.context ?: return null
            val ready = CountDownLatch(1)
            val engine = TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                ready.countDown()
            }
            tts = engine
            ready.await(TTS_INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (ttsReady) {
                // US English and the MEDIA stream, so a synthesized phrase matches the bundled cues: same
                // accent as the Piper recordings, same volume slider (see the AudioAttributes above).
                runCatching { engine.language = Locale.US }
                runCatching {
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                }
            }
        }
    }
    return tts?.takeIf { ttsReady }
}

/**
 * Speak [text] and block the worker until the utterance finishes — so the next one does not start
 * mid-sentence, exactly as the bundled-WAV path blocks. A [stopSpeaking] bumping [gen] ends the wait at once
 * (it has already told the engine to stop), and a hard deadline keeps a synthesizer that never reports
 * completion from wedging the worker.
 */
private fun speakWithTts(text: String, gen: Long) {
    if (text.isBlank()) return
    val engine = textToSpeech() ?: return
    val id = "omniapp-" + utteranceSeq.incrementAndGet()
    val done = CountDownLatch(1)
    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { if (utteranceId == id) done.countDown() }
        @Deprecated("the parameterless overload is what older engines call")
        override fun onError(utteranceId: String?) { if (utteranceId == id) done.countDown() }
    })
    val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
    if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) return
    val deadline = System.currentTimeMillis() + TTS_MAX_UTTERANCE_MILLIS
    while (gen == generation.get() && System.currentTimeMillis() < deadline) {
        if (done.await(20, TimeUnit.MILLISECONDS)) break
    }
}

private const val TTS_INIT_TIMEOUT_SECONDS = 5L
private const val TTS_MAX_UTTERANCE_MILLIS = 30_000L

/**
 * Parse a canonical PCM WAV (Piper output: 16-bit mono 22.05 kHz) and play it through a MODE_STATIC
 * [AudioTrack], blocking the worker until playback completes — or until [stopSpeaking] bumps [gen], in which
 * case the wait ends at once (the track was already paused/flushed). Returns whether the bytes were playable
 * at all, so a caller holding a phrase can synthesize it instead of losing the utterance.
 */
private fun playWavPcm(bytes: ByteArray, gen: Long): Boolean {
    val wav = parseWav(bytes) ?: return false
    if (wav.data.isEmpty()) return false
    val channelMask =
        if (wav.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    val track = AudioTrack(
        // USAGE_MEDIA is what puts the cue on STREAM_MUSIC, i.e. under the phone's own media slider — the
        // one the user reaches for. USAGE_ASSISTANCE_ACCESSIBILITY (what this used to be) routes to
        // STREAM_ACCESSIBILITY instead: a separate stream most volume panels never expose, so turning the
        // music down left the cue at full blast. An alarm keeps USAGE_ALARM for the same reason in reverse
        // (AlarmRingService) — each cue belongs on the slider that governs it.
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
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
    return true
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
