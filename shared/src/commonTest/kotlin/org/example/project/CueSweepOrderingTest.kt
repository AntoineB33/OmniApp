package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak

/**
 * PRD §15 / CLAUDE.md "each fires exactly once, **in order**": the unified cue sweep. These pin the reported
 * bug — "a 20 s look-away was announced AFTER the 5-min rest pose that is still starting at the now-line; it
 * should have fired before". Two independent faults combined: the look-away cue read a projection that
 * dropped any occurrence `now` had already crossed, and the two cues ran as independent now-line collectors
 * that could race.
 *
 * Both are answered by one sentence now: **every cue boundary is the START of a placed dynamic period**
 * ([SchedulerDomain.screenBreakCueOccurrencesBetween] — a pose's undragged due, a look-away's at-line
 * placement), and [SchedulerDomain.cueCrossings] merges them into one list sorted by instant. The
 * reconstruction cannot drop a crossing because it is a pure function of the recurrence bars over the window,
 * and the two cues cannot race because there is one list.
 *
 * What changed with `side-dev/README.md`: a break's drawn start used to slide to the now-line while it was
 * owed, so it was never a crossable boundary and the cue had to key on a separate anchored due
 * (`lastRest + interval`). Only a POSE slides now, so only a pose's cue keys on a due; the 20 s look-away is
 * announced where it is drawn.
 */
class CueSweepOrderingTest {
    private val MIN = 60_000L
    private val SEC = 1_000L
    private val HOUR = 3_600_000L
    private val lookAway = ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC)
    private val pose5 =
        ScreenBreak("take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true)

    private fun crossings(
        sides: List<ScreenBreak>,
        from: Long,
        to: Long,
        windDown: List<Long> = emptyList(),
        automatic: Boolean = true,
        already: Map<String, Long> = emptyMap(),
    ) = SchedulerDomain.cueCrossings(sides, windDown, automatic, already, from, to)

    @Test
    fun every_crossing_is_the_start_of_a_placed_period_and_they_come_back_in_order() {
        val sides = listOf(lookAway, pose5)
        val from = 0L
        val to = 4 * HOUR
        val placed = SchedulerDomain.screenBreakCueOccurrencesBetween(sides, from, to, nowMillis = to)
        assertTrue(placed.isNotEmpty(), "the case needs periods to be about")
        val out = crossings(sides, from, to)
        assertEquals(
            placed.map { it.startEpochMillis },
            out.map { it.instant },
            "one crossing per placed period, at its start",
        )
        assertEquals(out.map { it.instant }.sorted(), out.map { it.instant }, "and in boundary order")
    }

    @Test
    fun a_look_away_carries_its_resume_instant_and_a_pose_does_not() {
        val sides = listOf(lookAway, pose5)
        val out = crossings(sides, 0L, 4 * HOUR)
        for (c in out) {
            when (c.kind) {
                SchedulerDomain.CueKind.LookAwayStart ->
                    assertEquals(c.instant + 20 * SEC, c.endInstant, "a look-away resumes 20 s later")
                SchedulerDomain.CueKind.RestPoseDue ->
                    assertEquals(c.instant, c.endInstant, "a pose announces only its start")
                SchedulerDomain.CueKind.WindDown -> Unit
            }
        }
    }

    @Test
    fun a_wind_down_interleaves_by_its_instant() {
        val sides = listOf(lookAway, pose5)
        val placed = SchedulerDomain.screenBreakOccurrencesBetween(sides, 0L, 4 * HOUR)
        // Slip a wind-down between the first two placed periods; it must come back between them.
        val wd = (placed[0].startEpochMillis + placed[1].startEpochMillis) / 2
        val out = crossings(sides, 0L, 4 * HOUR, windDown = listOf(wd))
        val index = out.indexOfFirst { it.kind == SchedulerDomain.CueKind.WindDown }
        assertTrue(index > 0, "the wind-down is not first")
        assertTrue(out[index - 1].instant <= wd && out[index + 1].instant >= wd, "and sits by its instant")
    }

    @Test
    fun auto_schedule_off_yields_no_rest_pose_crossings() {
        val out = crossings(listOf(pose5), 0L, 4 * HOUR, automatic = false)
        assertTrue(out.none { it.kind == SchedulerDomain.CueKind.RestPoseDue })
    }

    @Test
    fun an_already_announced_pose_start_is_not_re_emitted() {
        val placed = SchedulerDomain.screenBreakOccurrencesBetween(listOf(pose5), 0L, 4 * HOUR)
        val first = placed.first()
        val out = crossings(
            listOf(pose5),
            0L,
            4 * HOUR,
            already = mapOf(first.title to first.startEpochMillis),
        )
        assertTrue(out.none { it.instant == first.startEpochMillis })
    }

    @Test
    fun an_inverted_window_reconstructs_nothing() {
        assertTrue(SchedulerDomain.screenBreakOccurrencesBetween(listOf(lookAway), 62 * MIN, 55 * MIN).isEmpty())
    }
}
