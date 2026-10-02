package org.example.project.scheduler.platform

import kotlin.concurrent.Volatile

/**
 * The app's **global volume** as the platform players read it (user spec 2026-10-02): the account's
 * `SchedulerState.soundVolume`, mirrored here by the engine on every change so the audio threads — the voice
 * worker, the alarm ring — need no state of their own. `1` (full) until the engine has said otherwise, which is
 * also what a sound played with no engine running gets.
 *
 * It scales the app's OWN samples, under the device's volume: the voice (bundled cues and synthesized phrases)
 * and an alarm's or a timer's ring.
 */
object AppVolume {
    @Volatile
    var level: Float = 1f
        set(value) {
            field = if (value.isNaN()) 1f else value.coerceIn(0f, 1f)
        }

    /**
     * The first [length] bytes of [pcm] — signed 16-bit little-endian samples — scaled by [level], as a new
     * array; [pcm] itself when there is nothing to scale (a shared tone cycle must never be written to).
     */
    fun scaledPcm16Le(pcm: ByteArray, length: Int = pcm.size, level: Float = this.level): ByteArray {
        if (level >= 1f || length <= 0) return pcm
        val out = pcm.copyOf(length)
        var i = 0
        while (i + 1 < length) {
            val sample = (out[i].toInt() and 0xFF) or (out[i + 1].toInt() shl 8)
            val scaled = (sample * level).toInt()
            out[i] = scaled.toByte()
            out[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
        return out
    }
}
