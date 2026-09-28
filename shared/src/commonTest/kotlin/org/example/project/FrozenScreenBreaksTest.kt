package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import kotlinx.datetime.TimeZone

/**
 * `docs/scheduler_requirements.md` § *frozen past*: **the three dynamic periods behind the line are banked as the
 * line passes them, and never move again** ([SchedulerDomain.bankScreenBreaks]).
 *
 * They used to be re-derived at every reading from a walk whose origin was the day before the line: the calendar
 * drew no break at all older than about a day and a half (a week view showed none for its first days), and the rest
 * moved whenever the origin rolled at midnight or whenever the environment, the tasks, the configuration or the
 * mode changed. The record is what the walk now continues from.
 */
class FrozenScreenBreaksTest {
    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR

    private val breaks = listOf(
        ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC),
        ScreenBreak("take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true),
        ScreenBreak("take a 15min pose", intervalMillis = 120 * MIN, durationMillis = 15 * MIN, restBreak = true),
    )

    private val onScreen = listOf(PlanTask(TaskId("task/user/0"), 1.0, 30 * MIN, mapOf(PeriodKinds.NO_SCREEN to 0.0)))

    /** A night of "no screen" every day, 23:00 → 07:00 (UTC), from well before [fromMillis] to well after [toMillis]. */
    private fun nights(fromMillis: Long, toMillis: Long): List<RestrictivePeriod> {
        val out = mutableListOf<RestrictivePeriod>()
        var day = (fromMillis / DAY - 2) * DAY
        while (day < toMillis + 2 * DAY) {
            out += RestrictivePeriod(day + 23 * HOUR, day + DAY + 7 * HOUR, PeriodKinds.NO_SCREEN, "night")
            day += DAY
        }
        return out
    }

    /** Walks the line from [fromMillis] to [toMillis] in [stepMillis] steps, banking at each, in [mode]. */
    private fun bankAlong(fromMillis: Long, toMillis: Long, stepMillis: Long, mode: Int, periods: List<RestrictivePeriod>): FrozenScreenBreaks {
        var frozen: FrozenScreenBreaks? = null
        var now = fromMillis
        while (now <= toMillis) {
            frozen = SchedulerDomain.bankScreenBreaks(breaks, frozen, now, periods, emptyList(), onScreen, mode)
            now += stepMillis
        }
        return frozen!!
    }

    @Test
    fun banking_as_the_line_moves_records_what_one_continuous_walk_places_behind_it() {
        val t0 = 100 * DAY + 9 * HOUR
        val end = t0 + 3 * DAY
        val periods = nights(t0, end)
        for (mode in listOf(DynamicPeriods.MODE_AWAY, DynamicPeriods.MODE_AT_SCREEN)) {
            val frozen = bankAlong(t0, end, 5 * MIN, mode, periods)
            val origin = SchedulerDomain.dynamicPlacementOriginMillis(t0)
            val continuous =
                DynamicPeriods.instances(
                    DynamicPeriods.Base(periods, emptyList(), onScreen),
                    SchedulerDomain.dynamicPeriodSpecs(breaks),
                    origin, frozen.untilMillis, tpMillis = end, mode = mode, sweepFromMillis = origin,
                ).filter { !it.openStart && it.coveredUntilMillis <= frozen.untilMillis }
                    .map { Triple(it.spec.label, it.coveredFromMillis, it.coveredUntilMillis) }
            val banked = frozen.breaks.map { Triple(it.label, it.startMillis, it.endMillis) }
            assertTrue(banked.size > 100, "mode $mode: three days bank a few hundred breaks, got ${banked.size}")
            assertEquals(continuous, banked, "mode $mode: the banked record is the walk the line made")
        }
    }

    @Test
    fun a_week_view_of_the_past_draws_every_banked_day_and_nothing_moves_them_afterwards() {
        val t0 = 100 * DAY + 9 * HOUR
        val now = t0 + 6 * DAY
        val periods = nights(t0, now)
        val frozen = bankAlong(t0, now, 5 * MIN, DynamicPeriods.MODE_AWAY, periods)
        fun drawn(tasks: List<PlanTask>, configured: List<ScreenBreak>, env: List<RestrictivePeriod>) =
            SchedulerDomain.takenScreenBreakPanels(
                configured, t0, now - 1, basePeriods = env, tasks = tasks,
                anchorMillis = now, tpMillis = now, mode = DynamicPeriods.MODE_AT_SCREEN, frozen = frozen,
            ).map { it.startEpochMillis to it.endEpochMillis }
        val past = drawn(onScreen, breaks, periods)
        for (d in 0 until 6) {
            val dayStart = t0 + d * DAY
            assertTrue(past.any { it.first in dayStart until dayStart + DAY }, "day $d of the week still shows its breaks")
        }
        // Nothing the user or the devices change later moves them: not the tasks, not the configuration (a longer
        // look-away), not the environment (a new period drawn over the past), not the mode.
        val offScreen = listOf(PlanTask(TaskId("task/user/0"), 1.0, 30 * MIN, mapOf(PeriodKinds.NO_SCREEN to 1.0)))
        val longer = breaks.map { if (it.restBreak) it else it.copy(durationMillis = 40 * SEC) }
        val drawnOver = periods + RestrictivePeriod(t0 + DAY, t0 + 2 * DAY, PeriodKinds.NO_SCREEN, "later")
        val frontier = frozen.untilMillis
        fun behindFront(list: List<Pair<Long, Long>>) = list.filter { it.second <= frontier }
        assertEquals(behindFront(past), behindFront(drawn(offScreen, breaks, periods)))
        assertEquals(behindFront(past), behindFront(drawn(onScreen, longer, periods)))
        assertEquals(behindFront(past), behindFront(drawn(onScreen, breaks, drawnOver)))
    }

    @Test
    fun the_front_waits_at_the_start_of_the_no_screen_chain_the_line_is_in() {
        val t0 = 100 * DAY + 9 * HOUR
        val chainStart = t0 + 3 * HOUR
        val now = chainStart + 40 * MIN
        val periods = listOf(RestrictivePeriod(chainStart, now, PeriodKinds.NO_SCREEN, "away", closedEnd = true))
        val frozen = bankAlong(t0, now, 5 * MIN, DynamicPeriods.MODE_AWAY, periods)
        assertTrue(frozen.untilMillis <= chainStart, "the chain may still pull a break back onto its start")
        assertTrue(frozen.breaks.none { it.startMillis >= chainStart })
    }

    @Test
    fun a_future_week_is_drawn_where_the_plan_cut_its_holes() {
        // The calendar's forward placement over a far span is ONE walk from the line — the walk the fill makes —
        // not a grid restarted at the span's own edge, which put breaks where the plan had not cut its holes.
        val now = 100 * DAY + 13 * HOUR
        val from = now + 5 * DAY
        val to = from + DAY
        var state = SchedulerState.empty()
        val row = state.lists[state.rootListId]!!.cellIds.first()
        state = SchedulerReducer.reduce(state, SchedulerIntent.SetCellTitle(row, "Work")).copy(screenBreaks = breaks)
        val tz = TimeZone.UTC
        val planned =
            SchedulerDomain.fillSchedule(state, now, timeZone = tz, horizonMillis = to)
                .filter { it.screenBreak && it.startEpochMillis >= from && it.startEpochMillis < to }
                .map { it.title to it.startEpochMillis }
        val env = SchedulerDomain.breakEnvironment(state, now, to, tz)
        val drawn =
            SchedulerDomain.screenBreakPanels(breaks, now, to, env.periods, env.blocks, env.tasks)
                .filter { it.startEpochMillis >= from && it.startEpochMillis < to }
                .map { it.title to it.startEpochMillis }
        val restartedAtTheEdge =
            SchedulerDomain.screenBreakPanelsInWindow(breaks, from, to, env.periods, env.blocks, env.tasks)
                .filter { it.startEpochMillis >= from && it.startEpochMillis < to }
                .map { it.title to it.startEpochMillis }
        assertTrue(planned.isNotEmpty())
        assertEquals(planned, drawn, "the calendar draws the breaks the plan was built around")
        assertTrue(restartedAtTheEdge != drawn, "the grid restarted at the week's edge is a different grid")
    }

    @Test
    fun a_look_away_being_conducted_absorbs_the_one_falling_due_inside_it() {
        // PRD §15 "Look away now": the break the user is taking is a dynamic period from the press, so a look-away
        // falling due during it is not laid (and banked) over it — two dynamic periods of the same length never
        // overlap — and the next is barred for twenty minutes after it.
        val t0 = 100 * DAY + 9 * HOUR
        val quiet = bankAlong(t0, t0 + 2 * HOUR, 5 * MIN, DynamicPeriods.MODE_AT_SCREEN, emptyList())
        val lookAway = quiet.breaks.last { it.label == DynamicPeriods.LABEL_20S && it.startMillis > t0 + HOUR }
        // Replay the same stretch with the user pressing "Look away now" ten seconds before that look-away was due.
        val press = lookAway.startMillis - 10 * SEC
        val conducting = SchedulerDomain.conductingBreakPeriod("look 20 feet away", press, 20 * SEC)
        var frozen: FrozenScreenBreaks? = null
        var now = t0
        while (now <= t0 + 2 * HOUR) {
            val periods = if (now >= press) listOf(conducting) else emptyList()
            frozen = SchedulerDomain.bankScreenBreaks(breaks, frozen, now, periods, emptyList(), onScreen, DynamicPeriods.MODE_AT_SCREEN)
            now += 5 * SEC
        }
        val banked = frozen!!.breaks.filter { it.label == DynamicPeriods.LABEL_20S }
        assertTrue(
            banked.none { it.startMillis < conducting.endMillis && it.endMillis > conducting.startMillis },
            "a look-away was banked over the one being conducted: ${banked.map { it.startMillis - press }}",
        )
        assertTrue(
            banked.none { it.startMillis >= conducting.endMillis && it.startMillis < conducting.endMillis + 20 * MIN },
            "the conducted break bars the next look-away for twenty minutes",
        )
    }
}
