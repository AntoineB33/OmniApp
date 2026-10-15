package org.example.project.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/**
 * User rule 2026-10-15: *"Add a configuration in the calendar that shows every recorded action on Windows with horizontal
 * lines"* — **every action the user does on the computer**, recorded by the platform system-wide (on Windows the
 * low-level keyboard and mouse hooks: a key pressed, a click, the wheel, the mouse moved), and drawn by the calendar
 * windows that show them as a line at each second one happened.
 *
 * Only the SECOND an action happened is kept — never which key, never where the mouse was — and only on this device:
 * local-only view state, never synced (`server-quota.md`). A platform that records nothing provides none
 * ([LocalInputActivity] stays null) and the calendar offers no switch.
 */
interface InputActivity {
    /** Calls [each] for every second in `[fromSecond, untilSecond)` (epoch seconds) with an action, in order. */
    fun forEachSecond(fromSecond: Long, untilSecond: Long, each: (Long) -> Unit)

    /** Moves on whenever a new second is recorded: what a drawing that shows them reads, to draw again. */
    val revision: StateFlow<Long>
}

/** The platform's recorder, provided by the app's entry point; null where nothing records ([InputActivity]). */
val LocalInputActivity = staticCompositionLocalOf<InputActivity?> { null }

/**
 * **The seconds with an action, one bit each, by UTC day** — what [InputActivity] is stored as: a day is
 * [SECONDS_PER_DAY] bits, 10 800 bytes, whatever was done in it, so a year of use is a few megabytes and a second is
 * set or read in constant time. Not thread-safe: its owner locks.
 */
class SecondsByDay {
    private val days = HashMap<Long, LongArray>()

    /** The days held, by UTC day number. */
    val dayNumbers: Set<Long> get() = days.keys

    /** Marks [second] (epoch seconds) as one with an action. True when it was not marked already. */
    fun add(second: Long): Boolean {
        val bits = days.getOrPut(second.floorDiv(SECONDS_PER_DAY)) { LongArray(WORDS_PER_DAY) }
        val index = second.mod(SECONDS_PER_DAY).toInt()
        val mask = 1L shl (index and 63)
        if (bits[index ushr 6] and mask != 0L) return false
        bits[index ushr 6] = bits[index ushr 6] or mask
        return true
    }

    /** See [InputActivity.forEachSecond]. */
    fun forEachSecond(fromSecond: Long, untilSecond: Long, each: (Long) -> Unit) {
        if (untilSecond <= fromSecond) return
        for (day in fromSecond.floorDiv(SECONDS_PER_DAY)..(untilSecond - 1).floorDiv(SECONDS_PER_DAY)) {
            val bits = days[day] ?: continue
            val dayStart = day * SECONDS_PER_DAY
            val from = (fromSecond - dayStart).coerceAtLeast(0L).toInt()
            val until = (untilSecond - dayStart).coerceAtMost(SECONDS_PER_DAY).toInt()
            var word = from ushr 6
            words@ while (word < WORDS_PER_DAY && word shl 6 < until) {
                var w = bits[word]
                // The bits of this word before [from] are left out; past [until] the day is done.
                if (word == from ushr 6) w = w and (-1L shl (from and 63))
                while (w != 0L) {
                    val bit = (word shl 6) + w.countTrailingZeroBits()
                    if (bit >= until) break@words
                    each(dayStart + bit)
                    w = w and (w - 1)
                }
                word++
            }
        }
    }

    /** One day as stored: its bits, little-endian, [BYTES_PER_DAY] bytes. Null for a day with nothing. */
    fun encodeDay(day: Long): ByteArray? {
        val bits = days[day] ?: return null
        val out = ByteArray(BYTES_PER_DAY)
        for (i in 0 until WORDS_PER_DAY) {
            val w = bits[i]
            for (b in 0 until 8) out[i * 8 + b] = (w ushr (8 * b)).toByte()
        }
        return out
    }

    /** [encodeDay]'s bytes put back; a file of another length (cut short, foreign) is ignored. */
    fun decodeDay(day: Long, bytes: ByteArray) {
        if (bytes.size != BYTES_PER_DAY) return
        val bits = LongArray(WORDS_PER_DAY) { i ->
            var w = 0L
            for (b in 0 until 8) w = w or ((bytes[i * 8 + b].toLong() and 0xFF) shl (8 * b))
            w
        }
        days[day] = bits
    }

    /** Lets go of the days before [day]. */
    fun dropBefore(day: Long) {
        days.keys.removeAll { it < day }
    }

    companion object {
        const val SECONDS_PER_DAY: Long = 86_400L
        private const val WORDS_PER_DAY: Int = 1_350
        const val BYTES_PER_DAY: Int = WORDS_PER_DAY * 8
    }
}
