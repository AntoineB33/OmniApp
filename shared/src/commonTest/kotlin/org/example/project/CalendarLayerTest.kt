package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerDomain.ActivityLayer
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.platform.DeviceKind

/**
 * PRD §8 calendar LAYERS. Over the whole timeline the calendar draws two oblique-line layers — "no computer
 * unlocked" and (opposite slope) "no phone unlocked" — and a stretch carrying BOTH is a no-screen period, the
 * set §9 places the off-screen tasks in and §15 counts as a pause.
 *
 * The rule these tests pin is **where a layer's evidence comes from**: the DEVICE's own OS history (its
 * lock/unlock record, or its sleep/awake record where the platform exposes no other), never the app's own
 * activity heartbeats — the app only knows when it happened to be running. And the default when a device
 * cannot be asked is that it **was unlocked**, so a first run on a computer never claims the phone was locked
 * all week.
 */
class CalendarLayerTest {

    private val HOUR = 3_600_000L
    private val T0 = 1_000_000_000_000L
    private val T4 = T0 + 4 * HOUR

    private fun at(fromHour: Long, toHour: Long) = TaskTimeRange(T0 + fromHour * HOUR, T0 + toHour * HOUR)

    private fun regions(locked: List<TaskTimeRange>?, asserted: List<TaskTimeRange> = emptyList()) =
        SchedulerDomain.layerRegions(locked, asserted, T0, T4)

    // ----- where a layer's evidence comes from ---------------------------------------------------------

    @Test
    fun a_device_that_cannot_be_asked_is_assumed_to_have_been_unlocked() {
        // The user's own example: "if I run the app on a computer and the data of the phone is not available
        // because it is the first time I run the app, then it is considered that the phone was always unlocked
        // in the past". Null is that answer, and it must draw NOTHING — not a full-window hatch.
        assertTrue(regions(null).isEmpty())
        // Note this is the OPPOSITE default from the account-wide pause derivation, which reads "no screen
        // unless a device reported activity". The two answer different questions, and only this one is about
        // whether a DEVICE was usable — where silence means the question was never asked, not "no".
        assertEquals(listOf(TaskTimeRange(T0, T4)), SchedulerDomain.derivePauses(emptyList(), T0, T4))
    }

    @Test
    fun a_device_that_was_never_locked_draws_no_layer_either() {
        // The other answer: the log WAS read and the device was unlocked throughout. Same drawing, different
        // reason — which is why the seam returns a nullable list rather than folding both into "empty".
        assertTrue(regions(emptyList()).isEmpty())
    }

    @Test
    fun the_layer_is_exactly_the_stretches_the_os_reports_the_device_locked() {
        assertEquals(listOf(at(1, 2)), regions(listOf(at(1, 2))))
        // Clipped to the asked window, and touching/overlapping reports fuse into one region.
        assertEquals(
            listOf(TaskTimeRange(T0, T0 + 3 * HOUR)),
            regions(listOf(TaskTimeRange(T0 - 5 * HOUR, T0 + 2 * HOUR), at(2, 3))),
        )
    }

    @Test
    fun a_sub_minute_standby_flicker_is_not_drawn_but_an_asserted_break_always_is() {
        // A machine dipping in and out of modern standby for seconds would otherwise draw hairlines of hatch
        // all day (the same seam rule the grey bands use).
        val flicker = TaskTimeRange(T0 + HOUR, T0 + HOUR + 30_000L)
        assertTrue(regions(listOf(flicker)).isEmpty())
        // …but an ASSERTED region is a claim the rules make, not a reading, so a 20-second look-away keeps its
        // hatch however short it is.
        val lookAway = TaskTimeRange(T0 + 2 * HOUR, T0 + 2 * HOUR + 20_000L)
        assertEquals(listOf(lookAway), regions(null, asserted = listOf(lookAway)))
    }

    @Test
    fun the_asserted_regions_are_drawn_even_for_a_device_that_cannot_be_asked() {
        // A sleep window / screen break / hand-added no-screen period says nobody is unlocked, whatever any
        // device's history does or does not show — so it hatches both layers.
        val night = at(2, 3)
        assertEquals(listOf(night), regions(null, asserted = listOf(night)))
        assertEquals(listOf(at(1, 3)), regions(listOf(at(1, 2)), asserted = listOf(night)))
    }

    // ----- both layers at once is a no-screen period ---------------------------------------------------

    @Test
    fun both_layers_at_once_is_the_no_screen_period() {
        // The user's definition: "when no computer and no phone is unlocked at the same time, then it is a
        // no-screen period". With a phone that cannot be asked there is no such stretch at all — which is the
        // point of the assumed-unlocked default: an unknown device must not manufacture no-screen time.
        val computerLocked = regions(listOf(at(1, 3)))
        assertTrue(SchedulerDomain.intersectRegions(computerLocked, regions(null)).isEmpty())
        // Once the phone's own history IS known, the overlap is the no-screen period.
        val phoneLocked = regions(listOf(at(2, 4)))
        assertEquals(listOf(at(2, 3)), SchedulerDomain.intersectRegions(computerLocked, phoneLocked))
    }

    @Test
    fun a_device_speaks_for_the_layer_of_its_own_kind() {
        assertEquals(ActivityLayer.NoPhoneUnlocked, SchedulerDomain.layerForDeviceKind(DeviceKind.Phone))
        assertEquals(ActivityLayer.NoComputerUnlocked, SchedulerDomain.layerForDeviceKind(DeviceKind.Desktop))
        // Anything that is not a phone is a computer — an unclassified device must still land on a layer.
        assertEquals(ActivityLayer.NoComputerUnlocked, SchedulerDomain.layerForDeviceKind(DeviceKind.Other))
    }

    // ----- the derived grey bands: the past is fully accounted for -------------------------------------

    @Test
    fun every_past_stretch_no_task_panel_covers_is_a_derived_inactivity_band() {
        // The user's rule: "the areas in the past that don't have a task panel should have a grey panel either
        // labelled inactivity or sleep". So the elapsed timeline minus what is already drawn over it is grey.
        val panels = listOf(at(1, 2), at(3, 4))
        assertEquals(listOf(at(0, 1), at(2, 3)), SchedulerDomain.derivedInactivityBands(panels, T0, T4))
        // A past with nothing at all on it is one band end to end (a freshly emptied account).
        assertEquals(listOf(at(0, 4)), SchedulerDomain.derivedInactivityBands(emptyList(), T0, T4))
        // Fully covered ⇒ no band at all.
        assertTrue(SchedulerDomain.derivedInactivityBands(listOf(at(0, 4)), T0, T4).isEmpty())
    }

    @Test
    fun the_seam_between_two_adjacent_panels_is_not_a_derived_band() {
        // Consecutive panels are separated by seconds all over a real day (a chunk ends at 11:05, the next
        // starts at 11:05:30). Those are seams, not idle time, and drawing them would litter the day with
        // grey slivers ([MIN_INACTIVITY_BAND_MILLIS]).
        val panels = listOf(at(0, 1), TaskTimeRange(T0 + HOUR + 30_000L, T4))
        assertTrue(SchedulerDomain.derivedInactivityBands(panels, T0, T4).isEmpty())
    }
}
