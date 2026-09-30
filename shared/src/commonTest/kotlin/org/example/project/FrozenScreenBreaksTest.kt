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
    fun a_week_view_of_the_past_draws_every_banked_day_and_nothing_moves_them_afterwards() {
        val t0 = 100 * DAY + 9 * HOUR
        val now = t0 + 6 * DAY
        val periods = nights(t0, now)
        val frozen = bankAlong(t0, now, 5 * MIN, DynamicPeriods.MODE_AWAY, periods)
        fun drawn(tasks: List<PlanTask>, configured: List<ScreenBreak>, env: List<RestrictivePeriod>) =
            // What the calendar draws behind the line is the record: [tasks], [env] and a mode are not even inputs.
            SchedulerDomain.takenScreenBreakPanels(configured, t0, now - 1, frozen = frozen)
                .map { it.startEpochMillis to it.endEpochMillis }
                .also { check(tasks.isNotEmpty() && env.isNotEmpty()) }
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
    fun the_front_is_the_line_even_inside_a_no_screen_chain() {
        // `docs/scheduler_requirements.md`: a break in a "no screen" stretch the line has not reached starts at
        // max(now line, t_s) — never behind the line — so nothing behind it waits to be decided: the front is the
        // line, and what the line met inside the chain is banked where it met it.
        val t0 = 100 * DAY + 9 * HOUR
        val chainStart = t0 + 3 * HOUR
        val now = chainStart + 40 * MIN
        val periods = listOf(RestrictivePeriod(chainStart, now, PeriodKinds.NO_SCREEN, "away", closedEnd = true))
        val frozen = bankAlong(t0, now, 5 * MIN, DynamicPeriods.MODE_AWAY, periods)
        assertEquals(now, frozen.untilMillis, "the front is the line")
        // Nothing is banked at the chain's start by a pull-back: a break there is one the line met there.
        val inChain = frozen.breaks.filter { it.startMillis >= chainStart }
        assertTrue(inChain.all { b -> (b.startMillis - t0) % (5 * MIN) == 0L || b.startMillis == chainStart },
            "every break in the chain starts where the line reached it: ${inChain.map { (it.startMillis - chainStart) / SEC }}")
    }

    @Test
    fun what_the_line_banked_never_changes_when_the_mode_or_environment_does_later() {
        // The anomaly of 2026-09-29 (account 3): a 15-min pose appeared at 11:32 behind the line. The calendar
        // re-derived the past with the mode of NOW; the record held only two look-aways. Bank a morning at the
        // screen, then keep advancing in an away mode with a new stretch drawn over the past: what was banked
        // before stays exactly as it was, and the calendar's past is that record.
        val t0 = 100 * DAY + 9 * HOUR
        val mid = t0 + 3 * HOUR
        val atScreen = bankAlong(t0, mid, 5 * MIN, DynamicPeriods.MODE_AT_SCREEN, emptyList())
        val before = atScreen.breaks.toList()
        val drawnOver = listOf(RestrictivePeriod(t0 + HOUR, mid + HOUR, PeriodKinds.NO_SCREEN, "later", closedEnd = true))
        var frozen = atScreen
        var now = mid + 5 * MIN
        while (now <= mid + HOUR) {
            frozen = SchedulerDomain.bankScreenBreaks(breaks, frozen, now, drawnOver, emptyList(), onScreen, DynamicPeriods.MODE_AWAY)
            now += 5 * MIN
        }
        assertEquals(before, frozen.breaks.filter { it.startMillis < mid }, "the past the line banked did not move")
        assertEquals(
            before.filter { it.endMillis > t0 }.map { it.startMillis to it.endMillis },
            SchedulerDomain.takenScreenBreakPanels(breaks, t0, mid - 1, frozen).filter { it.startEpochMillis < mid }
                .map { it.startEpochMillis to it.endEpochMillis },
            "the calendar draws the past from the record",
        )
        assertTrue(SchedulerDomain.takenScreenBreakPanels(breaks, t0, mid - 1, null).isEmpty(), "no record, no invented past")
    }

    @Test
    fun a_pose_the_line_is_inside_is_removed_when_the_line_goes_back_to_the_screen() {
        // The requirements' one exception: "when a 'screen break' period needs to get removed to satisfy its
        // restrictive period rules". Mode 1 may not be covered by a pose, so the one the line is inside goes.
        val t0 = 100 * DAY + 9 * HOUR
        val away = bankAlong(t0, t0 + 4 * HOUR, MIN, DynamicPeriods.MODE_AWAY, emptyList())
        val inside = away.breaks.lastOrNull { it.label != DynamicPeriods.LABEL_20S }
            ?: error("the case needs a pose")
        val line = inside.startMillis + MIN
        val trimmed = FrozenScreenBreaks(away.breaks.filter { it.startMillis <= inside.startMillis }, line - 1, line - 1)
        val back = SchedulerDomain.bankScreenBreaks(breaks, trimmed, line, emptyList(), emptyList(), onScreen, DynamicPeriods.MODE_AT_SCREEN)
        assertTrue(back.breaks.none { it == inside }, "the pose the line is inside is removed in mode 1")
        assertEquals(trimmed.breaks - inside, back.breaks.filter { it.startMillis < line }, "and nothing else behind the line moves")
        val stays = SchedulerDomain.bankScreenBreaks(breaks, trimmed, line, emptyList(), emptyList(), onScreen, DynamicPeriods.MODE_AWAY)
        assertTrue(inside in stays.breaks, "an away line stays inside it")
    }

    @Test
    fun an_owed_pose_meeting_a_look_away_at_the_screen_stays_on_the_line_and_is_never_banked_behind_it() {
        // 2026-09-29: an overdue look-away at the front and the pose the mode-1 line drags chained, the merge brought
        // the pose back to the look-away's start — behind the line and covering it — so the bank banked it, the next
        // advance removed it for covering a mode-1 line, and the one after banked it again: the published rules
        // flipped every advance (`ServerQuotaTest` saw the extra writes). The requirements put the merged break
        // "right after $now line$".
        val t0 = 100 * DAY + 9 * HOUR
        var frozen: FrozenScreenBreaks? = null
        var now = t0
        var lastWindows: List<Pair<String, Long>>? = null
        var changes = 0
        while (now < t0 + 2 * HOUR) {
            val before = frozen?.breaks.orEmpty()
            frozen = SchedulerDomain.bankScreenBreaks(breaks, frozen, now, emptyList(), emptyList(), onScreen, DynamicPeriods.MODE_AT_SCREEN)
            assertTrue(before.all { it in frozen.breaks || it.endMillis <= now - SchedulerDomain.SCREEN_BREAK_HISTORY_RETENTION_MILLIS },
                "at the screen nothing banked is ever removed again: ${before - frozen.breaks.toSet()}")
            assertTrue(frozen.breaks.none { it.label != DynamicPeriods.LABEL_20S && it.startMillis < now && now < it.endMillis },
                "a mode-1 line is never inside a banked pose")
            val windows = SchedulerDomain.poseWindowsBetween(breaks, now, tasks = onScreen, frozen = frozen).map { it.key to it.startMillis }
            if (windows != lastWindows) changes++
            lastWindows = windows
            now += 10 * SEC
        }
        assertTrue(changes < 10, "the published pose windows change only as the line passes a due, not every advance: $changes changes")
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
        // A rest just before, so no pose is owed: an owed pose riding the line bars every look-away behind it.
        val rest = listOf(RestrictivePeriod(t0 - 40 * MIN, t0 - 22 * MIN, PeriodKinds.NO_SCREEN, "rest"))
        val quiet = bankAlong(t0, t0 + 2 * HOUR, 5 * SEC, DynamicPeriods.MODE_AT_SCREEN, rest)
        val lookAway = quiet.breaks.first { it.label == DynamicPeriods.LABEL_20S && it.startMillis > t0 }
        // Replay the same stretch with the user pressing "Look away now" ten seconds before that look-away was due.
        val press = lookAway.startMillis - 10 * SEC
        val conducting = SchedulerDomain.conductingBreakPeriod("look 20 feet away", press, 20 * SEC)
        var frozen: FrozenScreenBreaks? = null
        var now = t0
        while (now <= t0 + 2 * HOUR) {
            val periods = if (now >= press) rest + conducting else rest
            frozen = SchedulerDomain.bankScreenBreaks(breaks, frozen, now, periods, emptyList(), onScreen, DynamicPeriods.MODE_AT_SCREEN)
            now += 5 * SEC
        }
        val banked = frozen!!.breaks.filter { it.label == DynamicPeriods.LABEL_20S }
        assertTrue(
            banked.none { it.startMillis < conducting.endMillis && it.endMillis > conducting.startMillis },
            "a look-away was banked over the one being conducted: ${banked.filter { it.startMillis < conducting.endMillis && it.endMillis > conducting.startMillis }.map { it.startMillis - press }}",
        )
        assertTrue(
            banked.none { it.startMillis >= conducting.endMillis && it.startMillis < conducting.endMillis + 20 * MIN },
            "the conducted break bars the next look-away for twenty minutes",
        )
    }
}
