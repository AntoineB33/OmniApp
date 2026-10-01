package org.example.project.scheduler.domain

import kotlin.math.floor
import kotlin.math.pow

/**
 * **Where a task's place on the colour circle is in sRGB** (user rule 2026-10-01: *"only at more than 256·256·256 tasks
 * to schedule there will be tasks with the same background color"*).
 *
 * [TaskColorSpace] answers with a fraction of a circle: the `n` tasks to schedule sit at `i/n` (plus one rotation), the
 * others in the gaps. A fixed saturation and lightness gave that circle a few thousand distinct 24-bit colours, so a
 * large tree repeated colours long before it ran out of room. This walks the WHOLE sRGB cube instead: the fraction is a
 * position along a **3-D Hilbert curve of order 8**, which visits every one of the `256³` colours exactly once
 * ([CUBE] steps), each a single-channel step of one from the previous. So:
 *
 * - **`n` tasks at `i/n` are `n` different colours for every `n ≤ 256³`**: their positions on the curve are
 *   `2²⁴/n ≥ 1` apart, so no two round to the same step — and the curve never visits a colour twice;
 * - **neighbours on the circle are neighbours in colour**, the curve being continuous and local — the tree's own
 *   "the closer two tasks are, the closer their colours", which the circle's depth-first order already pays for;
 * - the circle has no origin, so the curve is entered half-way along ([OFFSET]) rather than at its black end: a
 *   one-task tree is not painted black.
 *
 * What is drawn ON such a colour is written in whichever of black and white has the higher WCAG 2 contrast ratio with
 * it ([bestForeground]) — the maximum any colour can reach against that background.
 */
object TaskColorCurve {
    /** How many colours the curve visits: every 24-bit sRGB colour. */
    const val CUBE: Long = 1L shl 24

    /** Where hue 0 enters the curve — anywhere would do; the middle keeps a small tree off the black corner. */
    const val OFFSET: Long = CUBE / 2

    /** The colour (`0xRRGGBB`) at [hue], a fraction of the circle (wrapped into `[0, 1)`). */
    fun rgbOf(hue: Double): Int {
        val wrapped = hue - floor(hue)
        val step = (floor(wrapped * CUBE).toLong().coerceIn(0L, CUBE - 1) + OFFSET) % CUBE
        return rgbAt(step)
    }

    /** The [step]-th colour of the curve (`0 ≤ step < 256³`), as `0xRRGGBB`. Every step a different colour. */
    fun rgbAt(step: Long): Int {
        val axes = hilbertAxes(step)
        return (axes[0] shl 16) or (axes[1] shl 8) or axes[2]
    }

    /**
     * John Skilling's transpose-to-axes ("Programming the Hilbert curve", 2004) for 3 axes of 8 bits: the index's 24
     * bits dealt round-robin into the three axes, then Gray-decoded and un-rotated level by level.
     */
    private fun hilbertAxes(step: Long): IntArray {
        val bits = 8
        val n = 3
        val x = IntArray(n)
        // Transpose: bit (b·n + (n−1−i)) of the index is bit b of axis i, most significant first.
        for (b in 0 until bits) {
            for (i in 0 until n) {
                val bit = ((step shr (b * n + (n - 1 - i))) and 1L).toInt()
                x[i] = x[i] or (bit shl b)
            }
        }
        val top = 2 shl (bits - 1)
        // Gray decode.
        var t = x[n - 1] shr 1
        for (i in n - 1 downTo 1) x[i] = x[i] xor x[i - 1]
        x[0] = x[0] xor t
        // Undo the excess work.
        var q = 2
        while (q != top) {
            val p = q - 1
            for (i in n - 1 downTo 0) {
                if (x[i] and q != 0) {
                    x[0] = x[0] xor p
                } else {
                    t = (x[0] xor x[i]) and p
                    x[0] = x[0] xor t
                    x[i] = x[i] xor t
                }
            }
            q = q shl 1
        }
        return x
    }

    // ----- WCAG 2 contrast -----------------------------------------------------------------------------

    /** WCAG 2's relative luminance of an sRGB colour `0xRRGGBB`, in `[0, 1]`. */
    fun relativeLuminance(rgb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((rgb shr 16) and 0xFF) + 0.7152 * channel((rgb shr 8) and 0xFF) + 0.0722 * channel(rgb and 0xFF)
    }

    /** WCAG 2's contrast ratio of two colours, from 1 (identical) to 21 (black on white). */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    const val BLACK: Int = 0x000000
    const val WHITE: Int = 0xFFFFFF

    /**
     * **The colour to draw on [background] for the most contrast it can have** — black or white, whichever has the
     * higher WCAG ratio with it: the ratio is monotone in the foreground's luminance on either side of the
     * background's, so one of the two extremes is always the maximum.
     */
    fun bestForeground(background: Int): Int =
        if (contrastRatio(background, BLACK) >= contrastRatio(background, WHITE)) BLACK else WHITE
}
