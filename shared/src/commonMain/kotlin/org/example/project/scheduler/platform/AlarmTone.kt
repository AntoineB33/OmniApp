package org.example.project.scheduler.platform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * PRD §18/§14: the **small set of sounds** an alarm, a timer or a reminder can ring with — the whole of the
 * choice offered in the alert block of the Alarms window and the reminders manager.
 *
 * It is a **closed enum**, not a file path or a system ringtone id, for the reason [AlarmTone] is synthesized
 * at all: the sound is part of the account's data and rings on every device of it, so it has to name
 * something every device can produce identically, offline, with nothing to load. Each entry is one
 * [AlarmTone] recipe; adding one is adding a recipe there, never a resource.
 */
enum class AlertSound(val label: String) {
    /** The original ring: an acoustic-guitar arpeggio (Karplus-Strong). The default for alarms and timers. */
    Guitar("Guitar"),

    /** A struck bar chime, four notes rising - bright and short, the doorbell end of the set. */
    Chime("Chime"),

    /** A low bell, struck twice, with the inharmonic partials that make a bell a bell. Long ring-out. */
    Bell("Bell"),

    /** Tuned wooden bars, a quick six-note figure. The gentlest of the set - a nudge, not an alarm. */
    Marimba("Marimba"),

    /** Three electronic beeps. The plainest and most insistent; what a digital alarm clock sounds like. */
    Beeps("Beeps");

    companion object {
        /** What a new alarm, timer or reminder rings with. */
        val DEFAULT: AlertSound = Guitar
    }
}

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
 * The output is deterministic (fixed-seed noise, and pure arithmetic for the other four), so the bytes are the
 * same on every device and every run and can be asserted in a test.
 *
 * The guitar is one of **five** sounds ([AlertSound]) — the set an alarm, a timer or a reminder picks its ring
 * from. They share this one renderer and one output shape; what differs is the recipe: the guitar is plucked
 * (a delay line), the chime, the bell and the marimba are **struck** (inharmonic partials over an exponential
 * decay — the same synthesis at three sets of ratios and decay times), and the beeps are a gated tone.
 */
object AlarmTone {

    /** Signed 16-bit little-endian mono, the most widely supported PCM shape on both platforms. */
    const val SAMPLE_RATE: Int = 44_100

    /**
     * One arpeggio and its ring-out; the ring repeats this until the alarm's length elapses. Long enough for
     * the last note to fall away, short enough that the pattern comes back round insistently. The guitar's
     * own length — every sound has one, and [loopMillis] is where they all are.
     */
    const val LOOP_MILLIS: Int = 2_600

    /**
     * How long one cycle of [sound] lasts. A ring is this cycle written back-to-back until the configured
     * length elapses, so it is the phrase that comes round again rather than the length of the ring.
     */
    fun loopMillis(sound: AlertSound): Int = when (sound) {
        AlertSound.Guitar -> LOOP_MILLIS
        AlertSound.Chime -> 3_000
        AlertSound.Bell -> 3_600
        AlertSound.Marimba -> 2_400
        AlertSound.Beeps -> 2_000
    }

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
     * One full loop cycle of [sound] as 16-bit LE mono PCM at [sampleRate].
     *
     * Cheap enough (a few million multiply-adds) to build on the ringing thread at each alarm, so nothing is
     * cached: an alarm rings at most every few minutes, and holding ~260 kB resident for it would be worse.
     *
     * Every sound ends the same way — normalized to one peak and faded at both edges — so they are equally
     * loud and all loop without a click, whichever one a row picked.
     */
    fun loopPcm(sound: AlertSound = AlertSound.DEFAULT, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val frames = sampleRate * loopMillis(sound) / 1000
        val mix = DoubleArray(frames)
        when (sound) {
            AlertSound.Guitar -> renderGuitar(mix, sampleRate)
            AlertSound.Chime -> renderStruck(mix, sampleRate, CHIME)
            AlertSound.Bell -> renderStruck(mix, sampleRate, BELL)
            AlertSound.Marimba -> renderStruck(mix, sampleRate, MARIMBA)
            AlertSound.Beeps -> renderBeeps(mix, sampleRate)
        }
        normalize(mix)
        applyLoopFades(mix, sampleRate)
        return toPcm16(mix)
    }

    /** [AlertSound.Guitar]: the plucked arpeggio, each note summed onto the ones still ringing. */
    private fun renderGuitar(mix: DoubleArray, sampleRate: Int) {
        var seed = NOISE_SEED
        for ((index, hz) in PLUCK_HZ.withIndex()) {
            val start = index * PLUCK_SPACING_MILLIS * sampleRate / 1000
            seed = pluckInto(mix, start, hz, sampleRate, seed)
        }
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

    // ----- The struck sounds: chime, bell, marimba ----------------------------------------------------------

    /**
     * A **struck** sound's whole recipe: what is hit ([partials] over [decaySeconds]) and in what order
     * ([noteHz] every [spacingMillis]).
     *
     * One renderer for three sounds rather than three renderers, because a chime, a bell and a marimba bar
     * differ in exactly these numbers: the *ratios* of their overtones to the fundamental (harmonic ratios
     * would sound like an organ; what makes these read as struck metal or wood is that theirs are not whole
     * numbers), how long the fundamental rings for, and how much faster the overtones die than it does.
     */
    private class StruckVoice(
        /** The notes of one cycle, in order — the phrase that comes round again. */
        val noteHz: DoubleArray,
        /** Time between two strikes. Shorter than the ring-out, so the notes ring over each other. */
        val spacingMillis: Int,
        /** Overtone frequencies as multiples of the note's own. The first is normally `1.0`, the note itself. */
        val partials: DoubleArray,
        /** Each partial's share of the strike, in the same order as [partials]. */
        val gains: DoubleArray,
        /** Seconds for the fundamental to fall to −60 dB — the whole difference between a bell and a bar. */
        val decaySeconds: Double,
        /**
         * How much faster a higher partial fades: its decay is divided by `ratio^exponent`. `0.0` would keep
         * every overtone ringing as long as the note (which sounds synthetic); a real struck body loses its
         * top first, and that fall from bright to mellow is most of what identifies the material.
         */
        val partialDecayExponent: Double,
    )

    /** Four notes rising, on the partials of a free metal bar — bright, short, the doorbell of the set. */
    private val CHIME = StruckVoice(
        noteHz = doubleArrayOf(659.25, 830.61, 987.77, 1318.51),
        spacingMillis = 300,
        // The ideal free bar's own series (1, 2.76, 5.40, 8.93) — the reason a chime is not a sine tone.
        partials = doubleArrayOf(1.0, 2.756, 5.404, 8.933),
        gains = doubleArrayOf(1.0, 0.42, 0.18, 0.08),
        decaySeconds = 1.6,
        partialDecayExponent = 0.9,
    )

    /** Two strikes of a low bell: the hum note under the prime, a minor third above it, a long ring-out. */
    private val BELL = StruckVoice(
        noteHz = doubleArrayOf(220.0, 220.0),
        spacingMillis = 1_500,
        // A tuned bell's classic partials: hum an octave below, prime, minor third, fifth, nominal, and two
        // above. The minor third is why a bell always sounds faintly sad whatever note it is cast in.
        partials = doubleArrayOf(0.5, 1.0, 1.19, 1.5, 2.0, 2.5, 3.0),
        gains = doubleArrayOf(0.6, 1.0, 0.7, 0.5, 0.35, 0.2, 0.12),
        decaySeconds = 3.2,
        partialDecayExponent = 0.6,
    )

    /** Tuned wooden bars, a quick six-note figure — the gentlest of the set, and the fastest to fall away. */
    private val MARIMBA = StruckVoice(
        noteHz = doubleArrayOf(523.25, 659.25, 783.99, 659.25, 523.25, 392.00),
        spacingMillis = 180,
        // A marimba bar is undercut until its overtones land on 4× and 10× — tuned, unlike the chime's bar,
        // which is why it reads as a pitched instrument rather than as a bell.
        partials = doubleArrayOf(1.0, 4.0, 10.0),
        gains = doubleArrayOf(1.0, 0.28, 0.10),
        decaySeconds = 0.5,
        partialDecayExponent = 1.2,
    )

    /** Seconds of swell at a strike's onset; without it the attack clicks. */
    private const val STRIKE_ATTACK_SECONDS = 0.003

    /** A partial quieter than this is inaudible under the rest, so the inner loop stops there. */
    private const val STRIKE_SILENCE = 1e-4

    /** Plays [voice]'s phrase into [out] — one [strikeInto] per note, summed over whatever still rings. */
    private fun renderStruck(out: DoubleArray, sampleRate: Int, voice: StruckVoice) {
        for ((index, hz) in voice.noteHz.withIndex()) {
            strikeInto(out, index * voice.spacingMillis * sampleRate / 1000, hz, sampleRate, voice)
        }
    }

    /**
     * One strike of [hz] rendered into [out] from frame [start]: every partial of [voice] as a sine under its
     * own exponential decay, summed. Additive rather than Karplus–Strong because what is being modelled is a
     * rigid body with a handful of strong, *inharmonic* modes — a delay line can only produce harmonic ones.
     */
    private fun strikeInto(out: DoubleArray, start: Int, hz: Double, sampleRate: Int, voice: StruckVoice) {
        val attackFrames = (sampleRate * STRIKE_ATTACK_SECONDS).toInt().coerceAtLeast(1)
        for ((index, ratio) in voice.partials.withIndex()) {
            val gain = voice.gains.getOrElse(index) { 0.0 }
            if (gain <= 0.0) continue
            val partialHz = hz * ratio
            // Above Nyquist a partial does not disappear, it folds back down as a buzz at the wrong pitch.
            if (partialHz * 2.0 >= sampleRate) continue
            // −60 dB in `decay` seconds; a higher partial gets proportionally less of them.
            val decay = voice.decaySeconds / ratio.pow(voice.partialDecayExponent)
            val omega = 2.0 * PI * partialHz / sampleRate
            var frame = start
            while (frame < out.size) {
                val elapsed = frame - start
                val envelope = exp(ln(0.001) * (elapsed.toDouble() / sampleRate) / decay)
                if (envelope < STRIKE_SILENCE) break
                val attack = min(1.0, (elapsed + 1).toDouble() / attackFrames)
                out[frame] += gain * envelope * attack * sin(omega * elapsed)
                frame++
            }
        }
    }

    // ----- The beeps --------------------------------------------------------------------------------------

    /** The beeps' pitch — high enough to carry over a room, low enough not to be shrill. */
    private const val BEEP_HZ = 880.0

    /** How many beeps a cycle holds, how often one starts, and how long it sounds. */
    private const val BEEP_COUNT = 3
    private const val BEEP_PERIOD_MILLIS = 260
    private const val BEEP_LENGTH_MILLIS = 130

    /** Seconds of ramp at each end of a beep — a gated sine clicks at both edges without it. */
    private const val BEEP_EDGE_SECONDS = 0.004

    /** [AlertSound.Beeps]: [BEEP_COUNT] gated tones, then the silence that makes the group a group. */
    private fun renderBeeps(out: DoubleArray, sampleRate: Int) {
        for (index in 0 until BEEP_COUNT) {
            beepInto(out, index * BEEP_PERIOD_MILLIS * sampleRate / 1000, sampleRate)
        }
    }

    /** One beep into [out] from frame [start]: a sine with a little of its third harmonic, ramped at both ends. */
    private fun beepInto(out: DoubleArray, start: Int, sampleRate: Int) {
        val length = BEEP_LENGTH_MILLIS * sampleRate / 1000
        val edge = (sampleRate * BEEP_EDGE_SECONDS).toInt().coerceAtLeast(1)
        val omega = 2.0 * PI * BEEP_HZ / sampleRate
        for (i in 0 until length) {
            val frame = start + i
            if (frame >= out.size) return
            val envelope = min(1.0, min(i + 1, length - i).toDouble() / edge)
            // The third harmonic is what separates "alarm clock" from "hearing test".
            out[frame] += envelope * (sin(omega * i) + 0.3 * sin(3.0 * omega * i))
        }
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
