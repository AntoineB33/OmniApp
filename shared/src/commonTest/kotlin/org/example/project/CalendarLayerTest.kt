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
 * cannot be asked is that it **was locked**, so a computer running with no phone on the account hatches the
 * phone layer across the whole displayed past.
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
    fun a_device_that_cannot_be_asked_is_assumed_to_have_been_locked() {
        // The user's own example: "if I run the app on a computer and the data of the phone is not available
        // because it is the first time I run the app, then it is considered that the phone was always locked
        // in the past". Null is that answer, and it hatches the WHOLE asked window.
        assertEquals(listOf(TaskTimeRange(T0, T4)), regions(null))
        // Same default as the account-wide pause derivation ("no screen unless a device reported activity"):
        // a device nobody can vouch for was not in use.
        assertEquals(listOf(TaskTimeRange(T0, T4)), SchedulerDomain.derivePauses(emptyList(), T0, T4))
        // A degenerate window asserts nothing rather than a zero-length band.
        assertTrue(SchedulerDomain.layerRegions(null, emptyList(), T4, T4).isEmpty())
    }

    @Test
    fun a_device_that_answered_it_was_never_locked_draws_no_layer() {
        // The other answer, and the reason the seam is NULLABLE rather than folding both into "empty": the log
        // WAS read and the device was unlocked throughout, so nothing is hatched — the exact opposite drawing
        // from the unaskable device above.
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
        assertEquals(listOf(lookAway), regions(emptyList(), asserted = listOf(lookAway)))
    }

    @Test
    fun the_asserted_regions_are_drawn_whatever_the_history_says() {
        // A sleep window / screen break / hand-added no-screen period says nobody is unlocked, whatever any
        // device's history does or does not show — so it hatches both layers.
        val night = at(2, 3)
        assertEquals(listOf(night), regions(emptyList(), asserted = listOf(night)))
        assertEquals(listOf(at(1, 3)), regions(listOf(at(1, 2)), asserted = listOf(night)))
        // Including for a device that cannot be asked, whose evidence stops at the now-line: an asserted
        // region AHEAD of it (a §17 sleep window, a projected screen break) is still hatched, and the two
        // fuse rather than double-drawing.
        val tomorrowNight = TaskTimeRange(T4 + 5 * HOUR, T4 + 8 * HOUR)
        assertEquals(
            listOf(TaskTimeRange(T0, T4), tomorrowNight),
            regions(null, asserted = listOf(tomorrowNight)),
        )
    }

    // ----- both layers at once is a no-screen period ---------------------------------------------------

    @Test
    fun both_layers_at_once_is_the_no_screen_period() {
        // The user's definition: "when no computer and no phone is unlocked at the same time, then it is a
        // no-screen period". With a phone that cannot be asked — assumed locked throughout — the no-screen
        // period is exactly where the COMPUTER was locked: nobody else could have been at a screen.
        val computerLocked = regions(listOf(at(1, 3)))
        assertEquals(computerLocked, SchedulerDomain.intersectRegions(computerLocked, regions(null)))
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

    // ----- "I'm away" is a lock the OS cannot see ------------------------------------------------------

    @Test
    fun the_away_button_hatches_its_own_device_layer_for_as_long_as_it_is_on() {
        // `docs/scheduler_requirements.md` § *$now line$ 3 modes*: mode 3 is "at least one device with the
        // I'm away button clicked and all the other devices locked". The machine stays UNLOCKED while the
        // button is on, so the OS log shows nothing at all for it — and without this the calendar would draw
        // no layer over a stretch the now-line was in mode 3 for.
        val away = at(1, 2)
        assertEquals(listOf(away), regions(emptyList(), asserted = listOf(away)))
        // It is a CLAIM, not a reading, so the sub-minute seam filter must not touch it: a 30-second away
        // spell is 30 seconds the mode was 3 for.
        val brief = TaskTimeRange(T0 + HOUR, T0 + HOUR + 30_000L)
        assertEquals(listOf(brief), regions(emptyList(), asserted = listOf(brief)))
    }

    @Test
    fun the_away_stretch_is_the_closed_episodes_plus_the_open_one_growing_with_the_now_line() {
        val closed = listOf(at(0, 1))
        // Button off: only what is closed.
        assertEquals(closed, SchedulerDomain.declaredAwayRegions(closed, null, T4))
        // Button on: the open episode reaches the now-line and no further, exactly as the live Inactivity
        // tail does — nothing ahead of the line has happened yet.
        assertEquals(
            listOf(at(0, 1), at(2, 4)),
            SchedulerDomain.declaredAwayRegions(closed, T0 + 2 * HOUR, T4),
        )
        // Pressed this instant: nothing has elapsed, so there is no region yet (never a zero-length band).
        assertTrue(SchedulerDomain.declaredAwayRegions(emptyList(), T4, T4).isEmpty())
        // Touching episodes fuse, like every other region list.
        assertEquals(listOf(at(0, 4)), SchedulerDomain.declaredAwayRegions(listOf(at(0, 2)), T0 + 2 * HOUR, T4))
    }

    @Test
    fun an_away_stretch_with_every_other_device_locked_is_a_no_screen_period() {
        // The whole point, and the identity the requirement names: the period where one device is declared
        // away and the others are locked carries BOTH layers, so it IS a no-screen period — the same set §9
        // places the off-screen tasks in and refuses to bank an on-screen record over.
        val away = at(1, 2)
        assertEquals(
            listOf(away),
            SchedulerDomain.observedNoScreenRegions(
                computerLocked = emptyList(), // this computer was never locked: the button is the only source
                phoneLocked = null, // no phone can be asked ⇒ assumed locked throughout
                sinceMillis = T0,
                untilMillis = T4,
                computerAway = listOf(away),
            ),
        )
        // The declaration belongs to ITS OWN layer: a press on the computer says nothing about the phone, so
        // it cannot make a no-screen period out of a stretch a phone was being used in.
        assertTrue(
            SchedulerDomain.observedNoScreenRegions(
                computerLocked = emptyList(),
                phoneLocked = emptyList(),
                sinceMillis = T0,
                untilMillis = T4,
                computerAway = listOf(away),
            ).isEmpty(),
        )
        // And a failed lock query does not silence it: the button is the user's own statement, not a scan.
        assertEquals(
            listOf(away),
            SchedulerDomain.observedNoScreenRegions(
                computerLocked = emptyList(),
                phoneLocked = null,
                sinceMillis = T0,
                untilMillis = T4,
                computerAway = listOf(away),
            ),
        )
    }

    // ----- the mode-3 hatch is DOTTED --------------------------------------------------------------------

    private fun dottedIn(
        drawn: List<TaskTimeRange>,
        away: List<TaskTimeRange>,
        locked: List<TaskTimeRange>?,
    ) = SchedulerDomain.declaredLayerRegions(drawn, away, locked, T0, T4)

    @Test
    fun the_stretch_the_button_hatched_is_dotted_and_the_locked_one_beside_it_is_not() {
        // The requirement: "the oblique lines must be dotted if at least one of the corresponding devices
        // was unlocked but the I'm away button was clicked". Over that stretch the machine really was
        // UNLOCKED — the button is the only reason the hatch is there at all — so the line says so.
        //
        // The dots were briefly removed on 2026-09-12 under "an outline says who put this here" and came
        // back the same day: an outline belongs to a PERIOD, and the away button lays no period, so a
        // declared stretch and an observed one were drawing identically with nothing to tell them apart.
        val away = at(1, 2)
        val locked = at(3, 4)
        val drawn = regions(listOf(locked), asserted = listOf(away))
        assertEquals(listOf(away, locked), drawn)
        assertEquals(listOf(away), dottedIn(drawn, listOf(away), listOf(locked)))
        // Dotted and solid TILE the hatch: same slope, same span, same bubble section — only the line differs.
        assertEquals(
            listOf(locked),
            SchedulerDomain.subtractRegions(drawn, dottedIn(drawn, listOf(away), listOf(locked))),
        )
    }

    @Test
    fun a_lock_inside_the_spell_takes_its_slice_back_but_a_standby_flicker_does_not() {
        // The button stays on across a lock (only an unlock clears it), and over that slice nothing of the
        // kind was unlocked — so the hatch is a reading again and the dots stop.
        val away = at(1, 4)
        val locked = at(2, 3)
        val drawn = regions(listOf(locked), asserted = listOf(away))
        assertEquals(listOf(away), drawn) // one band: the declaration already covers the lock
        assertEquals(listOf(at(1, 2), at(3, 4)), dottedIn(drawn, listOf(away), listOf(locked)))
        // A flicker too short to hatch is too short to break the dots: the split reads the SAME evidence the
        // hatch does, sub-minute seam filter included, or a machine dipping in and out of modern standby
        // would slice one declaration into hairlines of solid line.
        val flicker = TaskTimeRange(T0 + 2 * HOUR, T0 + 2 * HOUR + 30_000L)
        assertEquals(listOf(away), dottedIn(drawn, listOf(away), listOf(flicker)))
    }

    @Test
    fun an_asserted_region_over_the_spell_does_not_undot_it() {
        // A sleep window, a screen break or a hand-added no-screen period is a promise about EVERY screen,
        // and a promise cannot un-unlock the machine the button was pressed on. Evidence can; a claim cannot.
        val away = at(1, 2)
        val sleep = at(0, 3)
        val drawn = regions(emptyList(), asserted = listOf(away, sleep))
        assertEquals(listOf(at(0, 3)), drawn)
        assertEquals(listOf(away), dottedIn(drawn, listOf(away), emptyList()))
    }

    @Test
    fun a_layer_no_device_of_its_kind_was_unlocked_for_stays_solid() {
        // The PEER's layer: assumed locked across the whole window, and no channel carries a peer's
        // declaration either — nothing was unlocked, so nothing is dotted.
        val peer = regions(null)
        assertEquals(listOf(TaskTimeRange(T0, T4)), peer)
        assertTrue(dottedIn(peer, emptyList(), null).isEmpty())
        assertTrue(dottedIn(peer, listOf(at(1, 2)), null).isEmpty())
        // The own layer with the button never pressed: an ordinary locked stretch, drawn solid.
        assertTrue(dottedIn(regions(listOf(at(1, 2))), emptyList(), listOf(at(1, 2))).isEmpty())
    }

    @Test
    fun only_what_is_drawn_is_dotted() {
        // The dots are a property of the BAND, so they are clipped to it: a declaration reaching past the
        // display floor dots only the part on screen, and one no band was drawn for dots nothing at all.
        assertEquals(listOf(at(1, 2)), dottedIn(listOf(at(1, 2)), listOf(at(0, 3)), emptyList()))
        assertTrue(dottedIn(listOf(at(2, 3)), listOf(at(0, 1)), emptyList()).isEmpty())
    }

    /**
     * What the 2026-09-12 removal got right and the dots do NOT undo: a declaration still hatches its layer,
     * still rides the ASSERTED slot (so the sub-minute seam filter can never drop one), and still merges
     * with the lock evidence beside it into a single REGION — the dots split the drawing of that region, not
     * the region itself, which is why the ∞ marker is asked of the merged list.
     */
    @Test
    fun a_declared_stretch_is_the_same_region_as_a_locked_one() {
        val away = at(1, 2)
        val locked = at(3, 4)
        assertEquals(listOf(away, locked), regions(listOf(locked), asserted = listOf(away)))
        assertEquals(listOf(at(1, 4)), regions(listOf(at(2, 4)), asserted = listOf(at(1, 2))))
    }
}
