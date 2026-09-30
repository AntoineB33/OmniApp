package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak

/**
 * PRD §15 / CLAUDE.md "each fires exactly once, **in order**": the unified cue sweep. The reported bug — "a 20 s
 * look-away was announced AFTER the 5-min rest pose that is still starting at the now-line" — came from two cues read
 * off two projections and racing.
 *
 * `docs/scheduler_requirements.md` § *Rule Structure*: the cues are now the break machine's own transitions as the line
 * crosses them ([BreakMachine.Event]) — a look-away where the line enters it, a pose where it falls due — merged with
 * the wind-down and the reminders into one list sorted by instant ([SchedulerDomain.cueCrossings]).
 */
class CueSweepOrderingTest {
    private val MIN = 60_000L
    private val SEC = 1_000L
    private val HOUR = 3_600_000L
    private val lookAway = ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC)
    private val pose5 = ScreenBreak("take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true)

    /** The transitions a line at a screen crosses from 0 to [to]. */
    private fun events(sides: List<ScreenBreak>, to: Long): List<BreakMachine.Event> {
        val specs = SchedulerDomain.dynamicPeriodSpecs(sides)
        val out = ArrayList<BreakMachine.Event>()
        BreakMachine.advance(BreakMachine.initial(0L, specs), to, emptyList(), specs, out)
        return out
    }

    private fun crossings(
        sides: List<ScreenBreak>,
        to: Long,
        windDown: List<Long> = emptyList(),
        automatic: Boolean = true,
        already: Map<String, Long> = emptyMap(),
    ) =
        SchedulerDomain.cueCrossings(
            sides, events(sides, to), DynamicPeriods.MODE_AT_SCREEN, windDown, automatic, already, 0L, to,
        )

    @Test
    fun every_crossing_is_a_transition_the_line_made_and_they_come_back_in_order() {
        val sides = listOf(lookAway, pose5)
        val out = crossings(sides, 4 * HOUR)
        assertTrue(out.isNotEmpty(), "the case needs breaks to be about")
        val transitions =
            events(sides, 4 * HOUR).mapNotNull {
                when (it) {
                    is BreakMachine.Event.Started -> it.startMillis
                    is BreakMachine.Event.Owed -> it.atMillis
                    else -> null
                }
            }
        assertEquals(transitions.sorted(), out.map { it.instant }, "one crossing per transition, at its instant")
        assertEquals(out.map { it.instant }.sorted(), out.map { it.instant }, "and in boundary order")
    }

    @Test
    fun a_look_away_carries_its_resume_instant_and_a_pose_does_not() {
        for (c in crossings(listOf(lookAway, pose5), 4 * HOUR)) {
            when (c.kind) {
                SchedulerDomain.CueKind.LookAwayStart -> assertEquals(c.instant + 20 * SEC, c.endInstant)
                SchedulerDomain.CueKind.RestPoseDue -> assertEquals(c.instant, c.endInstant)
                SchedulerDomain.CueKind.ReminderDue -> assertEquals(c.instant, c.endInstant)
                SchedulerDomain.CueKind.WindDown -> Unit
            }
        }
    }

    @Test
    fun a_wind_down_interleaves_by_its_instant() {
        val sides = listOf(lookAway, pose5)
        val first = crossings(sides, 4 * HOUR)
        val wd = (first[0].instant + first[1].instant) / 2
        val out = crossings(sides, 4 * HOUR, windDown = listOf(wd))
        val index = out.indexOfFirst { it.kind == SchedulerDomain.CueKind.WindDown }
        assertTrue(index > 0, "the wind-down is not first")
        assertTrue(out[index - 1].instant <= wd && out[index + 1].instant >= wd, "and sits by its instant")
    }

    @Test
    fun auto_schedule_off_yields_no_rest_pose_crossings() {
        val out = crossings(listOf(lookAway, pose5), 4 * HOUR, automatic = false)
        assertTrue(out.none { it.kind == SchedulerDomain.CueKind.RestPoseDue })
    }

    @Test
    fun an_already_announced_pose_is_not_re_emitted() {
        val first = crossings(listOf(lookAway, pose5), 4 * HOUR).first { it.kind == SchedulerDomain.CueKind.RestPoseDue }
        val out = crossings(listOf(lookAway, pose5), 4 * HOUR, already = mapOf(first.title to first.instant))
        assertTrue(out.none { it.kind == SchedulerDomain.CueKind.RestPoseDue && it.instant == first.instant })
    }

    @Test
    fun an_inverted_window_reconstructs_nothing() {
        assertTrue(SchedulerDomain.screenBreakOccurrencesBetween(listOf(lookAway), 62 * MIN, 55 * MIN).isEmpty())
    }
}
