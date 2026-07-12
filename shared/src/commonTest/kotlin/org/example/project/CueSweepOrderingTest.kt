package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SideTask

/**
 * PRD §15 / CLAUDE.md "each fires exactly once, **in order**": the unified cue sweep. These pin the reported
 * bug — "a 20s look-away was announced AFTER the 5-min rest pose that is still starting at the now-line; it
 * should have fired before". Two independent faults combined:
 *  1. the look-away cue read `state.panels`, whose FORWARD projection drops any occurrence `now` has already
 *     crossed ([SchedulerDomain.sideTaskNextStart] steps the grid), so a leap over a look-away simply lost
 *     it — while the level-based rest pose still fired;
 *  2. the two cues ran as independent now-line collectors, so even when both fired they could race.
 *
 * [SchedulerDomain.sideTaskOccurrencesBetween] fixes (1) by reconstructing look-away occurrences over the
 * sweep window from the fixed grid (leap-safe, like the rest pose's level rule), and
 * [SchedulerDomain.cueCrossings] fixes (2) by merging every cue boundary into one list sorted by its true
 * instant.
 */
class CueSweepOrderingTest {
    private val MIN = 60_000L
    private val SEC = 1_000L
    private val lookAway = SideTask("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC)
    private val pose5 = SideTask("take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true)

    @Test
    fun reconstruction_recovers_look_aways_the_forward_projection_has_dropped() {
        val la = lookAway.copy(lastRestMillis = 0) // grid: 20, 40, 60, 80… min
        // A sweep window [35, 62] min — the now-line has already crossed the 40- and 60-min look-aways.
        val recovered = SchedulerDomain.sideTaskOccurrencesBetween(listOf(la), 35 * MIN, 62 * MIN)
            .map { it.startEpochMillis }.sorted()
        assertEquals(listOf(40 * MIN, 60 * MIN), recovered)

        // The forward projection at now = 62 min has stepped the grid clean past them (this is exactly why a
        // panel-based cue lost them under a leap): its next occurrence is 80 min, not 40 or 60.
        val forward = SchedulerDomain.sideTaskPanels(listOf(la), nowMillis = 62 * MIN).map { it.startEpochMillis }
        assertTrue(forward.none { it == 40 * MIN || it == 60 * MIN })
    }

    @Test
    fun a_look_away_boundary_before_a_rest_pose_due_is_ordered_first() {
        // The reported scenario, reduced to the pure core. Look-away grid 20/40/60 min; the 5-min pose comes
        // due at 62 min. A single leap sweeps the window [55, 63] min, crossing the 60-min look-away and the
        // 62-min pose due together. They must come back ordered by boundary instant: look-away, THEN pose.
        val la = lookAway.copy(lastRestMillis = 0)
        val p5 = pose5.copy(lastRestMillis = 2 * MIN) // due = 62 min

        val crossings = SchedulerDomain.cueCrossings(
            sideTasks = listOf(la, p5),
            windDownInstants = emptyList(),
            automaticSchedule = true,
            alreadyNotifiedPoseDues = emptyMap(),
            fromMillis = 55 * MIN,
            toMillis = 63 * MIN,
        )

        assertEquals(
            listOf(
                SchedulerDomain.CueCrossing(60 * MIN, SchedulerDomain.CueKind.LookAwayStart, la.title, 60 * MIN + 20 * SEC),
                SchedulerDomain.CueCrossing(62 * MIN, SchedulerDomain.CueKind.RestPoseDue, p5.title, 62 * MIN),
            ),
            crossings,
        )
    }

    @Test
    fun an_already_announced_sliding_pose_due_is_not_re_emitted() {
        // An overdue-unserved pose slides along the now-line; once its (stable) due has been announced it must
        // stay silent, so a later sweep omits it — while still surfacing fresh look-aways.
        val la = lookAway.copy(lastRestMillis = 0)
        val p5 = pose5.copy(lastRestMillis = 2 * MIN) // due = 62 min, still unserved at 70 min

        val crossings = SchedulerDomain.cueCrossings(
            sideTasks = listOf(la, p5),
            windDownInstants = emptyList(),
            automaticSchedule = true,
            alreadyNotifiedPoseDues = mapOf(p5.title to 62 * MIN),
            fromMillis = 55 * MIN,
            toMillis = 70 * MIN,
        )

        assertTrue(crossings.none { it.kind == SchedulerDomain.CueKind.RestPoseDue })
    }

    @Test
    fun a_wind_down_interleaves_by_its_instant() {
        val la = lookAway.copy(lastRestMillis = 0)
        val p5 = pose5.copy(lastRestMillis = 2 * MIN) // due = 62 min

        val crossings = SchedulerDomain.cueCrossings(
            sideTasks = listOf(la, p5),
            windDownInstants = listOf(61 * MIN),
            automaticSchedule = true,
            alreadyNotifiedPoseDues = emptyMap(),
            fromMillis = 55 * MIN,
            toMillis = 63 * MIN,
        )

        assertEquals(
            listOf(
                SchedulerDomain.CueKind.LookAwayStart, // 60 min
                SchedulerDomain.CueKind.WindDown, //       61 min
                SchedulerDomain.CueKind.RestPoseDue, //    62 min
            ),
            crossings.map { it.kind },
        )
    }

    @Test
    fun auto_schedule_off_yields_no_rest_pose_crossings() {
        val p5 = pose5.copy(lastRestMillis = 2 * MIN)
        val crossings = SchedulerDomain.cueCrossings(
            sideTasks = listOf(p5),
            windDownInstants = emptyList(),
            automaticSchedule = false,
            alreadyNotifiedPoseDues = emptyMap(),
            fromMillis = 55 * MIN,
            toMillis = 63 * MIN,
        )
        assertTrue(crossings.isEmpty())
    }

    @Test
    fun an_inverted_window_reconstructs_nothing() {
        val la = lookAway.copy(lastRestMillis = 0)
        assertTrue(SchedulerDomain.sideTaskOccurrencesBetween(listOf(la), 62 * MIN, 55 * MIN).isEmpty())
    }

    @Test
    fun a_dragging_overdue_pose_casts_no_phantom_look_away_after_its_due() {
        // The reported x300 scenario: the user only ACCELERATED time (never actually paused), so the 5-min pose
        // came due and, unserved, DRAGS at the now-line. The static engine would place it at its fixed due and
        // re-anchor the look-away to pose-end+20 — but that slot recedes with `now` (the pose slides), so the
        // now-line never crosses it: no look-away must fire after the pose's due.
        // Both anchored at 5 min (the startup derive seeds a fresh account's poses to ~startup): the look-away
        // grid is 25/45/65/85 min and the pose is due at 5 + 60 = 65 min, coincident with the 65-min look-away.
        val anchor = 5 * MIN
        val la = lookAway.copy(lastRestMillis = anchor) // grid: 25, 45, 65, 85… min
        val p5 = pose5.copy(lastRestMillis = anchor) // due = 65 min, overdue+unserved at now = 90 min

        // The look-aways the now-line actually crossed by 90 min are 25 and 45 only — the 65 is absorbed by the
        // coincident pose and the re-anchored 85+ are phantoms that recede with the dragging pose.
        val lookAways = SchedulerDomain.sideTaskOccurrencesBetween(listOf(la, p5), 0, 90 * MIN)
            .filter { it.title == la.title }
            .map { it.startEpochMillis }
        assertEquals(listOf(25 * MIN, 45 * MIN), lookAways)

        // And through the full cue path: after the pose's 65-min due, no LookAwayStart crossing is emitted.
        val crossings = SchedulerDomain.cueCrossings(
            sideTasks = listOf(la, p5),
            windDownInstants = emptyList(),
            automaticSchedule = true,
            alreadyNotifiedPoseDues = emptyMap(),
            fromMillis = 50 * MIN, // sweep window that already saw the 65-min pose due
            toMillis = 90 * MIN,
        )
        assertTrue(crossings.none { it.kind == SchedulerDomain.CueKind.LookAwayStart })
        // The pose's own due is still reported (level rule), so the pose cue is unaffected.
        assertTrue(crossings.any { it.kind == SchedulerDomain.CueKind.RestPoseDue && it.instant == 65 * MIN })
    }

    @Test
    fun a_served_pose_does_not_shadow_the_look_away_that_resumes_after_it() {
        // Contrast: a pose that was actually TAKEN advances lastRest, so it is not overdue and casts no shadow.
        // Here the pose was taken at 60 min (lastRest = 60), so its next due is 60 + 60 = 120 (future) and the
        // look-away legitimately resumes 20 min after the pose ended.
        val la = lookAway.copy(lastRestMillis = 65 * MIN) // re-anchored to the pose end (device-sleep seeding)
        val p5 = pose5.copy(lastRestMillis = 60 * MIN) // taken at 60 min; next due 120 min (not overdue at 90)

        val lookAways = SchedulerDomain.sideTaskOccurrencesBetween(listOf(la, p5), 60 * MIN, 90 * MIN)
            .filter { it.title == la.title }
            .map { it.startEpochMillis }
        assertEquals(listOf(85 * MIN), lookAways) // 65 + 20 = 85, a real crossable boundary
    }
}
