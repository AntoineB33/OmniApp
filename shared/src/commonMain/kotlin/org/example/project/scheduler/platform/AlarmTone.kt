package org.example.project.scheduler.platform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * PRD §18 Alarms: the alarm sound is an **acoustic guitar** — one loopable arpeggio, synthesized here in
 * common code so every platform that rings plays the *identical* sound (desktop's `SourceDataLine`, the
 * phone's `AudioTrack`).
 *
 * It is **synthesized, not a bundled asset** (unlike the §15 voice cues, which must be a recorded human
 * voice): a plucked steel string is exactly what the Karplus–Strong algorithm models — a noise burst
 * circulating through a delay line that loses its highs a little on each pass, which is physically what a
 * string does — so a few hundred lines of arithmetic give a real guitar timbre with no resource that could
 * fail to load in a packaged app image, and no licensing question about a recording.
 *
 * The output is deterministic (fixed-seed noise), so the bytes are the same on every device and every run and
 * can be asserted in a test.
 */
object AlarmTone {

    /** Signed 16-bit little-endian mono, the most widely supported PCM shape on both platforms. */
    const val SAMPLE_RATE: Int = 44_100

    /**
     * One arpeggio and its ring-out; the ring repeats this until the alarm's length elapses. Long enough for
     * the last note to fall away, short enough that the pattern comes back round insistently.
     */
    const val LOOP_MILLIS: Int = 2_600

    /**
     * Am add9 up the neck and back down — A2 E3 A3 C4 E4 A4 E4 C4 A3 E3 — plucked in turn. An arpeggio (rather
     * than a strum) is what makes the sound legible as a guitar at low volume; the rising-then-falling line is
     * what makes it read as an alarm rather than as background music.
     */
    private val PLUCK_HZ = doubleArrayOf(
        110.00, 164.81, 220.00, 261.63, 329.63, 440.00, 329.63, 261.63, 220.00, 164.81,
    )

    /** Spacing between plucks — fast enough that the notes overlap and ring together, as on a real guitar. */
    private const val PLUCK_SPACING_MILLIS = 170

    /** Seconds for a plucked note to fall to −60 dB. */
    private const val DECAY_SECONDS = 2.6

    /** Peak amplitude, well under full scale so neither platform's line clips. */
    private const val PEAK = 0.35

    /** Fades at the loop's edges; without them the seam between two cycles clicks. */
    private const val FADE_IN_MILLIS = 4
    private const val FADE_OUT_MILLIS = 45

    /** Fixed noise seed — see the determinism note above. */
    private const val NOISE_SEED = 0x5DEECE66DL

    /**
     * One full loop cycle of the guitar arpeggio as 16-bit LE mono PCM at [sampleRate].
     *
     * Cheap enough (a few million multiply-adds) to build on the ringing thread at each alarm, so nothing is
     * cached: an alarm rings at most every few minutes, and holding ~260 kB resident for it would be worse.
     */
    fun loopPcm(sampleRate: Int = SAMPLE_RATE): ByteArray {
        val frames = sampleRate * LOOP_MILLIS / 1000
        val mix = DoubleArray(frames)
        var seed = NOISE_SEED
        for ((index, hz) in PLUCK_HZ.withIndex()) {
            val start = index * PLUCK_SPACING_MILLIS * sampleRate / 1000
            seed = pluckInto(mix, start, hz, sampleRate, seed)
        }
        normalize(mix)
        applyLoopFades(mix, sampleRate)
        return toPcm16(mix)
    }

    /**
     * Renders one Karplus–Strong pluck of [hz] into [out] from frame [start], summed onto whatever is already
     * there (the notes ring over each other). Returns the advanced noise seed so each note gets its own burst
     * while the whole cycle stays deterministic.
     */
    private fun pluckInto(out: DoubleArray, start: Int, hz: Double, sampleRate: Int, seed0: Long): Long {
        // The delay line's length IS the pitch: one lap round it takes one period.
        val n = (sampleRate / hz).toInt().coerceAtLeast(2)
        val buffer = DoubleArray(n)
        var seed = seed0
        for (i in buffer.indices) {
            seed = nextSeed(seed)
            buffer[i] = noiseOf(seed)
        }
        // Soften the burst: a fingertip/pick excites a string far less brightly than white noise, and the raw
        // burst reads as a click. Two moving-average passes over the line roll the top octave off.
        repeat(2) { smoothRing(buffer) }
        // Pick-position comb: a string plucked a fifth of the way along cannot sound its 5th harmonic. This
        // notch is a large part of what separates "guitar" from "generic plucked string".
        combAtPickPosition(buffer, n / 5)

        // Per-note loss so every note takes about the same time to fade, regardless of how many laps per
        // second its pitch makes: decay^(hz · DECAY_SECONDS) = 10^-3.
        val decay = exp(ln(0.001) / (hz * DECAY_SECONDS))
        // A body resonance would need a filter bank; a slow amplitude swell of the first few ms is enough to
        // suggest one and keeps the onset from sounding synthetic.
        val attackFrames = (sampleRate * 0.006).toInt().coerceAtLeast(1)

        var index = 0
        var frame = start
        while (frame < out.size) {
            val current = buffer[index]
            val attack = min(1.0, (frame - start + 1).toDouble() / attackFrames)
            out[frame] += current * attack
            // The one-zero average is the string losing its high partials first — the whole reason a plucked
            // note goes from bright to mellow as it rings.
            val next = buffer[(index + 1) % n]
            buffer[index] = decay * 0.5 * (current + next)
            index = (index + 1) % n
            frame++
        }
        return seed
    }

    /** In-place circular moving average — the low-pass applied to the initial noise burst. */
    private fun smoothRing(buffer: DoubleArray) {
        val first = buffer[0]
        for (i in buffer.indices) {
            val next = if (i == buffer.lastIndex) first else buffer[i + 1]
            buffer[i] = 0.5 * (buffer[i] + next)
        }
    }

    /** Subtracts the burst from a delayed copy of itself, notching the harmonics the pick position kills. */
    private fun combAtPickPosition(buffer: DoubleArray, delay: Int) {
        if (delay <= 0) return
        val source = buffer.copyOf()
        for (i in buffer.indices) {
            buffer[i] = source[i] - source[(i + delay) % buffer.size]
        }
    }

    /** Scales the whole cycle so its loudest sample sits at [PEAK]; a silent buffer is left alone. */
    private fun normalize(mix: DoubleArray) {
        var peak = 0.0
        for (sample in mix) peak = maxOf(peak, abs(sample))
        if (peak <= 0.0) return
        val gain = PEAK / peak
        for (i in mix.indices) mix[i] *= gain
    }

    /** Raised-cosine fades at both ends so cycle N's tail meets cycle N+1's head silently. */
    private fun applyLoopFades(mix: DoubleArray, sampleRate: Int) {
        val fadeIn = (sampleRate * FADE_IN_MILLIS / 1000).coerceAtMost(mix.size)
        for (i in 0 until fadeIn) {
            mix[i] *= 0.5 - 0.5 * cos(PI * i /fadeIn)
        }
        val fadeOut = (sampleRate * FADE_OUT_MILLIS / 1000).coerceAtMost(mix.size)
        for (i in 0 until fadeOut) {
            mix[mix.size - 1 - i] *= 0.5 - 0.5 * cos(PI * i /fadeOut)
        }
    }

    /** Doubles in [-1, 1] to signed 16-bit little-endian bytes, clamped so a rounding overshoot can't wrap. */
    private fun toPcm16(mix: DoubleArray): ByteArray {
        val out = ByteArray(mix.size * 2)
        for (i in mix.indices) {
            val value = (mix[i] * Short.MAX_VALUE).toInt().coerceIn(-32_768, 32_767)
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** A plain 64-bit LCG — any fixed-sequence generator does; this one needs no platform library. */
    private fun nextSeed(seed: Long): Long = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L

    /** The seed's high 53 bits mapped to [-1, 1). */
    private fun noiseOf(seed: Long): Double =
        (seed ushr 11).toDouble() / (1L shl 52).toDouble() - 1.0
}
