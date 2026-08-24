package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskTimeRange

/**
 * PRD §8/§9: [SchedulerDomain.observedNoScreenRegions] — the stretches the DEVICES observed nobody at a screen
 * for, which is the two calendar layers intersected.
 *
 * This is the same identity `layerRegions` draws ("a stretch carrying BOTH layers is a no-screen period"), read
 * for the scheduler instead of for the calendar. The load-bearing case is `null`: a device kind nobody can
 * vouch for was LOCKED, which is what makes a phone-less account's answer turn on the computer's history alone.
 */
class ObservedNoScreenRegionsTest {

    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L
    private val SINCE = NOW - 12 * HOUR

    private fun observed(computer: List<TaskTimeRange>?, phone: List<TaskTimeRange>?) =
        SchedulerDomain.observedNoScreenRegions(computer, phone, SINCE, NOW)

    @Test
    fun both_layers_must_agree_for_a_stretch_to_be_no_screen() {
        // The computer was locked 8h→4h back, the phone 6h→2h back: only the overlap is a no-screen period.
        val result = observed(
            computer = listOf(TaskTimeRange(NOW - 8 * HOUR, NOW - 4 * HOUR)),
            phone = listOf(TaskTimeRange(NOW - 6 * HOUR, NOW - 2 * HOUR)),
        )
        assertEquals(1, result.size, "only the overlap counts: $result")
        assertEquals(NOW - 6 * HOUR, result[0].startEpochMillis)
        assertEquals(NOW - 4 * HOUR, result[0].endEpochMillis)
    }

    @Test
    fun a_device_that_cannot_be_asked_was_locked_throughout() {
        // The account-3 shape: a desktop with no phone. The phone cannot be asked, so the answer is exactly
        // the computer's own locked spans — which is what put task panels under both hatches.
        val locked = TaskTimeRange(NOW - 8 * HOUR, NOW - 4 * HOUR)
        val result = observed(computer = listOf(locked), phone = null)
        assertEquals(1, result.size)
        assertEquals(locked.startEpochMillis, result[0].startEpochMillis)
        assertEquals(locked.endEpochMillis, result[0].endEpochMillis)
    }

    @Test
    fun neither_device_askable_means_the_whole_window() {
        // Matches derivePauses' own default: no screen unless a device reported activity.
        val result = observed(computer = null, phone = null)
        assertEquals(1, result.size)
        assertEquals(SINCE, result[0].startEpochMillis)
        assertEquals(NOW, result[0].endEpochMillis)
    }

    @Test
    fun an_empty_list_is_not_null_and_yields_nothing() {
        // The OS answering "this device was never locked" must draw a blank, not the whole window — the two
        // answers stay deliberately different, which is why the seam is nullable.
        assertTrue(
            observed(computer = emptyList(), phone = null).isEmpty(),
            "an unlocked computer means no no-screen period, whatever the phone says",
        )
    }

    @Test
    fun sub_minute_dips_are_seam_filtered_out() {
        // A Modern-Standby machine dips in and out for seconds all day; those are not pauses and must not
        // suppress recording (MIN_INACTIVITY_BAND_MILLIS, the same filter the layer's evidence half uses).
        val blip = TaskTimeRange(NOW - 5 * HOUR, NOW - 5 * HOUR + 10_000)
        assertTrue(observed(computer = listOf(blip), phone = null).isEmpty(), "a 10 s dip is not a no-screen period")
    }

    @Test
    fun nothing_is_observed_ahead_of_the_now_line() {
        // Evidence only speaks for elapsed time; a degenerate window asserts nothing.
        assertTrue(SchedulerDomain.observedNoScreenRegions(null, null, NOW, NOW).isEmpty())
        val clipped = SchedulerDomain.observedNoScreenRegions(
            listOf(TaskTimeRange(NOW - HOUR, NOW + 5 * HOUR)),
            null,
            SINCE,
            NOW,
        )
        assertEquals(NOW, clipped.single().endEpochMillis, "evidence is clipped at the now-line")
    }
}
