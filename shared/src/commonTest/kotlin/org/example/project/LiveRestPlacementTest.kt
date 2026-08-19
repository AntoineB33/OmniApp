package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerDomain.LiveRest
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §15 live-pause placement overlay ([SchedulerDomain.liveRestGap] +
 * [SchedulerDomain.screenBreaksForPlacement]): while this device observes a pause the derives haven't
 * banked yet, screen-break placement folds it in. An ONGOING pause is presumed to keep going until it
 * has served each screen break (`max(gapEnd, gapStart + duration)`), so the whole projected grid moves
 * at the walk-away instant and stays fluid under an accelerated leap — nothing downstream of a
 * not-yet-served pose freezes until the leap's end (the reported anomaly), and the look-away's slot
 * can never be crossed by the now-line mid-pause. A HELD gap (user already back) counts only for
 * what it actually contained, exactly what the derive will bank — an aborted short pause retracts
 * the presumption. Placement-only — the stored [ScreenBreak.lastRestMillis] is never advanced by the
 * overlay.
 */
class LiveRestPlacementTest {

    private val MIN = 60_000L
    private val now = 1_000_000_000_000L

    private fun lookAway(lastRest: Long = 0L) =
        ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20_000L, lastRestMillis = lastRest)

    private fun pose(lastRest: Long = 0L) =
        ScreenBreak("take a 5min pose and blink hard", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true, lastRestMillis = lastRest)

    private fun held(startMillis: Long, endMillis: Long) = LiveRest(TaskTimeRange(startMillis, endMillis), ongoing = false)

    private fun ongoing(startMillis: Long, endMillis: Long) = LiveRest(TaskTimeRange(startMillis, endMillis), ongoing = true)

    // ----- liveRestGap -------------------------------------------------------------------------

    @Test
    fun live_rest_gap_is_null_without_a_pending_pause() {
        assertNull(SchedulerDomain.liveRestGap(null, null, now))
        assertNull(SchedulerDomain.liveRestGap(null, now - 5 * MIN, now))
    }

    @Test
    fun live_rest_gap_slides_with_the_now_line_while_the_device_stays_inactive() {
        val start = now - 5 * MIN
        assertEquals(ongoing(start, now), SchedulerDomain.liveRestGap(start, null, now))
        // A later tick: the same walk-away instant, a larger `now` → the gap end follows the now-line.
        assertEquals(ongoing(start, now + MIN), SchedulerDomain.liveRestGap(start, null, now + MIN))
    }

    @Test
    fun live_rest_gap_freezes_at_the_reopened_session_start_once_the_user_is_back() {
        val start = now - 10 * MIN
        val reopened = now - 2 * MIN
        assertEquals(held(start, reopened), SchedulerDomain.liveRestGap(start, reopened, now))
        // Degenerate/inverted held gap (session reopened at/before the walk-away instant) → no gap.
        assertNull(SchedulerDomain.liveRestGap(start, start, now))
        assertNull(SchedulerDomain.liveRestGap(start, start - MIN, now))
    }

    // ----- screenBreaksForPlacement ---------------------------------------------------------------

    @Test
    fun placement_overlay_is_identity_without_a_gap() {
        val sides = listOf(lookAway(now - 25 * MIN), pose(now - 30 * MIN))
        assertSame(sides, SchedulerDomain.screenBreaksForPlacement(sides, null))
    }

    @Test
    fun a_held_gap_shorter_than_the_look_away_duration_does_not_qualify() {
        // A finished 10-second pause can't have served a 20-second look-away.
        val sides = listOf(lookAway(now - 25 * MIN))
        assertEquals(sides, SchedulerDomain.screenBreaksForPlacement(sides, held(now - 10_000L, now)))
    }

    @Test
    fun an_ongoing_gap_presumes_each_task_served_at_gap_start_plus_duration() {
        // 10 seconds into an ongoing pause, nothing is served YET — but the pause is presumed to continue:
        // the look-away completes at gapStart+20s, the pose at gapStart+5min. Both anchors move at the
        // walk-away instant, so the whole projected grid re-places fluidly instead of freezing.
        val gapStart = now - 10_000L
        val sides = listOf(lookAway(now - 25 * MIN), pose(now - 30 * MIN))
        val overlaid = SchedulerDomain.screenBreaksForPlacement(sides, ongoing(gapStart, now))
        assertEquals(gapStart + 20_000L, overlaid[0].lastRestMillis)
        assertEquals(gapStart + 5 * MIN, overlaid[1].lastRestMillis)
        // The input list is untouched (the overlay copies): the stored screen breaks never advance here.
        assertEquals(now - 25 * MIN, sides[0].lastRestMillis)
        assertEquals(now - 30 * MIN, sides[1].lastRestMillis)
    }

    @Test
    fun an_ongoing_gap_older_than_the_duration_keeps_re_satisfying_at_the_sliding_gap_end() {
        // Once the pause has lasted the task's duration, the presumed rest end IS the gap's moving end —
        // continuous with the fixed gapStart+duration instant it transitions from.
        val sides = listOf(lookAway(now - 25 * MIN), pose(now - 30 * MIN))
        val overlaid = SchedulerDomain.screenBreaksForPlacement(sides, ongoing(now - 5 * MIN, now))
        assertEquals(now, overlaid[0].lastRestMillis)
        assertEquals(now, overlaid[1].lastRestMillis)
    }

    @Test
    fun a_gap_already_behind_the_stored_anchor_does_not_qualify() {
        // The derive already banked a rest at/after this gap's end → nothing to overlay.
        val sides = listOf(lookAway(lastRest = now))
        assertEquals(sides, SchedulerDomain.screenBreaksForPlacement(sides, held(now - 5 * MIN, now)))
    }

    @Test
    fun a_held_gap_counts_only_what_it_actually_contained() {
        // The user is back after 4 minutes: the pause served the 20-s look-away (anchor → gap end) but
        // NOT the 5-min pose — the mid-pause presumption is retracted and the pose keeps its stored
        // anchor (it is still owed). Exactly the seedScreenBreaksFromGaps rule the derive will bank.
        val sides = listOf(lookAway(now - 25 * MIN), pose(now - 30 * MIN))
        val overlaid = SchedulerDomain.screenBreaksForPlacement(sides, held(now - 4 * MIN, now))
        assertEquals(now, overlaid[0].lastRestMillis)
        assertEquals(now - 30 * MIN, overlaid[1].lastRestMillis)
    }

    // ----- panel placement under the overlay ---------------------------------------------------

    @Test
    fun an_ongoing_pause_slides_the_look_away_ahead_of_the_now_line() {
        // The anomaly: look-away grid anchored 25 min ago → next slot at now+15min. During a 5-min
        // ongoing pause the overlay re-anchors it to the gap's end (= now), so the next occurrence is a
        // full interval ahead (now+20min) and, as the pause keeps growing, keeps sliding — the now-line
        // can never cross it mid-pause.
        val sides = listOf(lookAway(now - 25 * MIN))
        val gap = SchedulerDomain.liveRestGap(now - 5 * MIN, null, now)
        val panels = SchedulerDomain.screenBreakPanels(SchedulerDomain.screenBreaksForPlacement(sides, gap), now)
        val first = panels.minByOrNull { it.startEpochMillis }!!
        assertEquals(now + 20 * MIN, first.startEpochMillis)
        assertTrue(panels.none { it.startEpochMillis in (now - 5 * MIN)..now })
    }

    @Test
    fun a_held_gap_after_reopen_anchors_the_look_away_at_the_gap_end() {
        // User came back 2 min ago from a 10→2-min-ago pause; no derive has banked it yet. The held gap
        // keeps the look-away anchored at the pause's END (not the now-line): next slot at gapEnd+20min,
        // exactly what the derive will bank — so placement doesn't snap when the derive lands.
        val sides = listOf(lookAway(now - 40 * MIN))
        val gap = SchedulerDomain.liveRestGap(now - 10 * MIN, now - 2 * MIN, now)
        val panels = SchedulerDomain.screenBreakPanels(SchedulerDomain.screenBreaksForPlacement(sides, gap), now)
        val first = panels.minByOrNull { it.startEpochMillis }!!
        assertEquals(now - 2 * MIN + 20 * MIN, first.startEpochMillis)
    }

    @Test
    fun a_young_ongoing_pause_re_places_the_pose_at_its_presumed_completion() {
        // The 5-min break leap, 1 minute in (gap 4min < 5min): the pose is presumed to complete at
        // gapStart+5min, so its next occurrence already sits an interval past that (gapStart+65min) —
        // moved at the walk-away instant instead of freezing (with everything re-anchored downstream of
        // it) until the leap's end. The instant is FIXED while the pause is younger than the duration,
        // transitioning continuously into the sliding gap end at gap = 5min.
        val gapStart = now - 4 * MIN
        val sides = listOf(pose(now - 90 * MIN)) // overdue; without a gap it would pin at the now-line
        val gap = SchedulerDomain.liveRestGap(gapStart, null, now)
        val panels = SchedulerDomain.screenBreakPanels(SchedulerDomain.screenBreaksForPlacement(sides, gap), now)
        val first = panels.minByOrNull { it.startEpochMillis }!!
        assertEquals(gapStart + 5 * MIN + 60 * MIN, first.startEpochMillis)
    }

    @Test
    fun a_pause_that_reached_the_pose_duration_slides_the_pose_out_of_the_pause() {
        // Gap ≥ duration: the pose keeps being re-satisfied — its next occurrence re-places a full
        // interval past the gap's moving end (now + 60min), fluidly, instead of staying pinned until the
        // post-leap derive banks the pause. Continuous with the presumed instant above: at gap = 5min
        // both formulas give gapStart + 65min.
        val sides = listOf(pose(now - 90 * MIN))
        val gap = SchedulerDomain.liveRestGap(now - 5 * MIN, null, now)
        val panels = SchedulerDomain.screenBreakPanels(SchedulerDomain.screenBreaksForPlacement(sides, gap), now)
        val first = panels.minByOrNull { it.startEpochMillis }!!
        assertEquals(now + 60 * MIN, first.startEpochMillis)
        assertTrue(panels.none { it.startEpochMillis in (now - 5 * MIN)..now })
    }

    @Test
    fun an_aborted_short_pause_retracts_the_pose_presumption() {
        // The user returned after 4 minutes: the held gap never served the 5-min pose, so its placement
        // is back to the stored projection (overdue → pinned at the now-line). The rest was not taken;
        // the pose is still owed.
        val sides = listOf(pose(now - 90 * MIN))
        val gap = SchedulerDomain.liveRestGap(now - 6 * MIN, now - 2 * MIN, now)
        val without = SchedulerDomain.screenBreakPanels(sides, now)
        val with = SchedulerDomain.screenBreakPanels(SchedulerDomain.screenBreaksForPlacement(sides, gap), now)
        assertEquals(without, with)
    }

    @Test
    fun a_held_gap_after_reopen_anchors_the_pose_exactly_where_the_derive_will_bank_it() {
        // User came back 2 min ago from a 10→2-min-ago pause (8 min ≥ the pose's 5): the held gap
        // anchors the pose at the pause's END, so the next occurrence sits at gapEnd + 60min — exactly
        // the anchor the derive will bank, so placement doesn't snap when it lands.
        val sides = listOf(pose(now - 90 * MIN))
        val gap = SchedulerDomain.liveRestGap(now - 10 * MIN, now - 2 * MIN, now)
        val panels = SchedulerDomain.screenBreakPanels(SchedulerDomain.screenBreaksForPlacement(sides, gap), now)
        val first = panels.minByOrNull { it.startEpochMillis }!!
        assertEquals(now - 2 * MIN + 60 * MIN, first.startEpochMillis)
    }

    @Test
    fun the_grid_downstream_of_a_pose_moves_with_the_pose_during_an_ongoing_pause() {
        // The reported anomaly: only the look-aways BEFORE the next pose slid; every occurrence after it
        // was re-anchored to the pose's frozen slot ([screenBreakPanels]' pause-re-anchors-shorter-pauses
        // rule) and waited for the leap's end. With the presumption the pose's slot moves at walk-away
        // and the downstream look-aways move with it.
        val sides = listOf(lookAway(now - 25 * MIN), pose(now - 30 * MIN)) // pose due at now+30min
        fun poseStart(panels: List<org.example.project.scheduler.model.TaskPanel>) =
            panels.filter { it.title == sides[1].title }.minOf { it.startEpochMillis }
        fun lookAwayAfter(panels: List<org.example.project.scheduler.model.TaskPanel>, t: Long) =
            panels.filter { it.title == sides[0].title && it.startEpochMillis > t }.minOf { it.startEpochMillis }

        val stale = SchedulerDomain.screenBreakPanels(sides, now)
        assertEquals(now + 30 * MIN, poseStart(stale))
        // Downstream look-away re-anchored to the pose's end + its interval (now+35min+20min).
        assertEquals(now + 55 * MIN, lookAwayAfter(stale, poseStart(stale)))

        // One minute into an ongoing pause: the pose is presumed served at gapStart+5min (= now+4min),
        // so its occurrence moves to now+64min — and the look-away behind it re-anchors to now+89min.
        val gap = SchedulerDomain.liveRestGap(now - MIN, null, now)
        val fluid = SchedulerDomain.screenBreakPanels(SchedulerDomain.screenBreaksForPlacement(sides, gap), now)
        assertEquals(now + 64 * MIN, poseStart(fluid))
        assertEquals(now + 89 * MIN, lookAwayAfter(fluid, poseStart(fluid)))
    }

    @Test
    fun fill_schedule_folds_the_live_rest_into_screen_break_placement() {
        val s = SchedulerState.empty().copy(screenBreaks = listOf(lookAway(now - 25 * MIN)))
        val gap = ongoing(now - 5 * MIN, now)
        val side = SchedulerDomain.fillSchedule(s, now, liveRest = gap)
            .filter { it.screenBreak }.minByOrNull { it.startEpochMillis }!!
        assertEquals(now + 20 * MIN, side.startEpochMillis)
        // Without the overlay there is no evidence of a pause, so the look-away is still OWED: its due
        // (now − 5 min) has passed unserved, and an owed break sits at the now-line and slides right with it
        // (PRD §15 / `side-dev/scheduler_logic.py` tests 10–11) instead of stepping its grid to the next slot.
        val owed = SchedulerDomain.fillSchedule(s, now)
            .filter { it.screenBreak }.minByOrNull { it.startEpochMillis }!!
        assertEquals(now, owed.startEpochMillis)
    }

    // ----- reducer wiring ----------------------------------------------------------------------

    @Test
    fun refresh_schedule_uses_the_injected_live_rest_gap_and_leaves_stored_screen_breaks_alone() {
        val previous = SchedulerReducer.liveRestGap
        SchedulerReducer.liveRestGap = { ongoing(now - 5 * MIN, now) }
        try {
            val s0 = SchedulerState.empty().copy(screenBreaks = listOf(lookAway(now - 25 * MIN)))
            val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.RefreshSchedule(now))
            val sidePanels = s1.panels.filter { it.screenBreak }
            // No look-away panel inside the pause or at the stale now+15min slot; the first occurrence
            // slid a full interval past the pause's end.
            assertTrue(sidePanels.none { it.startEpochMillis in (now - 5 * MIN)..now })
            assertEquals(now + 20 * MIN, sidePanels.minOf { it.startEpochMillis })
            // Placement-only: the stored anchor is untouched (only derives may advance it).
            assertEquals(now - 25 * MIN, s1.screenBreaks[0].lastRestMillis)
        } finally {
            SchedulerReducer.liveRestGap = previous
        }
    }
}
