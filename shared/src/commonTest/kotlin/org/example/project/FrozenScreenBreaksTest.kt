package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *frozen past*: **the three screen breaks behind the line are banked as the line
 * enters them, and never move again** ([SchedulerDomain.stepScreenBreaks], the break machine's runtime step).
 */
class FrozenScreenBreaksTest {
    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR

    private val breaks = listOf(
        ScreenBreak("look 20 feet away", intervalMillis = 20 * MIN, durationMillis = 20 * SEC),
        ScreenBreak("take a 5min pose", intervalMillis = 60 * MIN, durationMillis = 5 * MIN, restBreak = true, key = SchedulerDomain.FIVE_MIN_BREAK_KEY),
        ScreenBreak("take a 15min pose", intervalMillis = 120 * MIN, durationMillis = 15 * MIN, restBreak = true, key = SchedulerDomain.FIFTEEN_MIN_BREAK_KEY),
    )

    /** A night of "no screen" every day, 23:00 → 07:00 (UTC). */
    private fun nights(fromMillis: Long, toMillis: Long): List<RestrictivePeriod> {
        val out = mutableListOf<RestrictivePeriod>()
        var day = (fromMillis / DAY - 2) * DAY
        while (day < toMillis + 2 * DAY) {
            out += RestrictivePeriod(day + 23 * HOUR, day + DAY + 7 * HOUR, PeriodKinds.NO_SCREEN, "night")
            day += DAY
        }
        return out
    }

    private fun step(frozen: FrozenScreenBreaks?, now: Long, periods: List<RestrictivePeriod>, mode: Int): FrozenScreenBreaks =
        SchedulerDomain.stepScreenBreaks(breaks, frozen, now, periods, mode).record

    /**
     * The line walked from [fromMillis] to [toMillis] in [stepMillis] steps. [modeAt] says the mode at each step from the
     * record so far — so a user who takes the pose they owe can be written as "mode 3 while a pose is owed or entered".
     */
    private fun bankAlong(
        fromMillis: Long,
        toMillis: Long,
        stepMillis: Long,
        periods: List<RestrictivePeriod> = emptyList(),
        modeAt: (Long, FrozenScreenBreaks?) -> Int,
    ): FrozenScreenBreaks {
        var frozen: FrozenScreenBreaks? = null
        var now = fromMillis
        while (now <= toMillis) {
            frozen = step(frozen, now, periods, modeAt(now, frozen))
            now += stepMillis
        }
        return frozen!!
    }

    /** A user who takes each pose where it falls due, and is away at night. */
    private fun follower(periods: List<RestrictivePeriod>): (Long, FrozenScreenBreaks?) -> Int = { t, frozen ->
        val machine = frozen?.machine
        val takingPose =
            machine?.drag?.let { it.label != DynamicPeriods.LABEL_20S } == true ||
                machine?.active?.let { it.label != DynamicPeriods.LABEL_20S && t < it.endMillis } == true
        when {
            periods.any { it.startMillis <= t && t < it.endMillis } -> DynamicPeriods.MODE_ON_BREAK
            takingPose -> DynamicPeriods.MODE_ON_BREAK
            else -> DynamicPeriods.MODE_AT_SCREEN
        }
    }

    @Test
    fun a_week_view_of_the_past_draws_every_banked_day_and_nothing_moves_them_afterwards() {
        val t0 = 100 * DAY + 9 * HOUR
        val now = t0 + 6 * DAY
        val periods = nights(t0, now)
        val frozen = bankAlong(t0, now, 5 * MIN, periods, follower(periods))
        val past = SchedulerDomain.takenScreenBreakPanels(breaks, t0, now - 1, frozen).map { it.startEpochMillis to it.endEpochMillis }
        for (d in 0 until 6) {
            val dayStart = t0 + d * DAY
            assertTrue(past.any { it.first in dayStart until dayStart + DAY }, "day $d of the week still shows its breaks")
        }
        // What the calendar draws behind the line is the record: a later configuration (a longer look-away) or a later
        // mode is not even an input to it.
        val longer = breaks.map { if (it.restBreak) it else it.copy(durationMillis = 40 * SEC) }
        assertEquals(past, SchedulerDomain.takenScreenBreakPanels(longer, t0, now - 1, frozen).map { it.startEpochMillis to it.endEpochMillis })
    }

    @Test
    fun the_front_is_the_line() {
        val t0 = 100 * DAY + 9 * HOUR
        val chainStart = t0 + 3 * HOUR
        val now = chainStart + 40 * MIN
        val frozen = bankAlong(t0, now, 5 * MIN) { t, _ -> if (t >= chainStart) DynamicPeriods.MODE_AWAY else DynamicPeriods.MODE_AT_SCREEN }
        assertEquals(now, frozen.untilMillis, "the front is the line")
        assertTrue(frozen.breaks.all { it.startMillis <= now }, "nothing is banked ahead of the line")
    }

    @Test
    fun what_the_line_banked_never_changes_when_the_mode_or_environment_does_later() {
        // The anomaly of 2026-09-29 (account 3): a 15-min pose appeared at 11:32 behind the line. Bank a morning at the
        // screen, then keep advancing in an away mode with a new stretch drawn over the past: what was banked before
        // stays exactly as it was, and the calendar's past is that record.
        val t0 = 100 * DAY + 9 * HOUR
        val mid = t0 + 3 * HOUR
        val atScreen = bankAlong(t0, mid, 5 * MIN) { _, _ -> DynamicPeriods.MODE_AT_SCREEN }
        val before = atScreen.breaks.toList()
        assertTrue(before.isNotEmpty())
        val drawnOver = listOf(RestrictivePeriod(t0 + HOUR, mid + HOUR, PeriodKinds.NO_SCREEN, "later", closedEnd = true))
        var frozen = atScreen
        var now = mid + 5 * MIN
        while (now <= mid + HOUR) {
            frozen = step(frozen, now, drawnOver, DynamicPeriods.MODE_AWAY)
            now += 5 * MIN
        }
        assertEquals(before, frozen.breaks.filter { it.startMillis < mid }, "the past the line banked did not move")
        assertEquals(
            before.map { it.startMillis to it.endMillis },
            SchedulerDomain.takenScreenBreakPanels(breaks, t0, mid - 1, frozen).map { it.startEpochMillis to it.endEpochMillis },
            "the calendar draws the past from the record",
        )
        assertTrue(SchedulerDomain.takenScreenBreakPanels(breaks, t0, mid - 1, null).isEmpty(), "no record, no invented past")
    }

    @Test
    fun a_pose_the_line_is_inside_is_removed_when_the_line_goes_back_to_the_screen() {
        // The requirements' one exception: "when a 'screen break' period needs to get removed to satisfy its restrictive
        // period rules". Mode 1 may not be covered by a pose, so the one the line is inside goes.
        // At the screen until the pose falls due (owed), then away for a minute: the line enters it, and is inside it.
        val t0 = 100 * DAY + 9 * HOUR
        var frozen: FrozenScreenBreaks? = null
        var now = t0
        while (frozen?.machine?.drag == null) {
            frozen = step(frozen, now, emptyList(), DynamicPeriods.MODE_AT_SCREEN)
            now += MIN
            check(now < t0 + 6 * HOUR) { "the case needs a pose" }
        }
        frozen = step(frozen, now, emptyList(), DynamicPeriods.MODE_AWAY)
        now += MIN
        frozen = step(frozen, now, emptyList(), DynamicPeriods.MODE_AWAY)
        val inside = frozen.breaks.last()
        assertEquals(inside.label, frozen.machine!!.active!!.label, "the line is inside the pose it walked away to take")
        val back = step(frozen, now, emptyList(), DynamicPeriods.MODE_AT_SCREEN)
        assertTrue(inside !in back.breaks, "the pose the line is inside is removed in mode 1")
        assertEquals(frozen.breaks - inside, back.breaks, "and nothing else behind the line moves")
        assertEquals(inside.label, back.machine!!.drag!!.label, "…and it is owed again")
        val stays = step(frozen, now, emptyList(), DynamicPeriods.MODE_AWAY)
        assertTrue(inside in stays.breaks, "an away line stays inside it")
    }

    @Test
    fun an_owed_pose_at_the_screen_is_never_banked_and_its_published_window_holds_still() {
        // 2026-09-29: an owed pose chained with an overdue look-away was banked, removed and banked again at every advance,
        // and the published rules flipped each time (`ServerQuotaTest` saw the extra writes).
        val t0 = 100 * DAY + 9 * HOUR
        var frozen: FrozenScreenBreaks? = null
        var now = t0
        var lastWindows: List<SchedulerDomain.PoseWindow>? = null
        var changes = 0
        while (now < t0 + 3 * HOUR) {
            val before = frozen?.breaks.orEmpty()
            frozen = step(frozen, now, emptyList(), DynamicPeriods.MODE_AT_SCREEN)
            assertTrue(before.all { it in frozen.breaks }, "at the screen nothing banked is ever removed again")
            assertTrue(
                frozen.breaks.none { it.label != DynamicPeriods.LABEL_20S },
                "a pose owed at the screen is dragged, never banked",
            )
            val windows = SchedulerDomain.poseWindowsBetween(breaks, now, frozen = frozen)
            if (windows != lastWindows) changes++
            lastWindows = windows
            now += 10 * SEC
        }
        assertTrue(changes < 10, "the published pose windows change only as a pose falls due, not every advance: $changes changes")
    }

    @Test
    fun a_future_week_is_drawn_where_the_plan_cut_its_holes() {
        // The calendar's forward placement and the plan's come out of one machine, from the line.
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
            SchedulerDomain.screenBreakPanels(breaks, now, to, env.periods)
                .filter { it.startEpochMillis >= from && it.startEpochMillis < to }
                .map { it.title to it.startEpochMillis }
        assertTrue(planned.isNotEmpty())
        assertEquals(planned, drawn, "the calendar draws the breaks the plan was built around")
    }

    @Test
    fun a_look_away_being_conducted_absorbs_the_one_falling_due_inside_it() {
        // PRD §15 "Look away now": a look-away falling due while the conducted one runs joins it (the chain rule), and the
        // next is barred for twenty minutes after it.
        val t0 = 100 * DAY + 9 * HOUR
        var frozen: FrozenScreenBreaks? = null
        var now = t0
        while (now < t0 + 20 * MIN - 10 * SEC) {
            frozen = step(frozen, now, emptyList(), DynamicPeriods.MODE_AT_SCREEN)
            now += 5 * SEC
        }
        val press = now
        val conducted = SchedulerDomain.conductScreenBreak(breaks, frozen, press, emptyList(), DynamicPeriods.MODE_AT_SCREEN)
        assertTrue(conducted.events.any { it is BreakMachine.Event.Started && it.conducted })
        frozen = conducted.record
        now += 5 * SEC
        while (now <= t0 + 2 * HOUR) {
            frozen = step(frozen, now, emptyList(), DynamicPeriods.MODE_AT_SCREEN)
            now += 5 * SEC
        }
        val banked = assertNotNull(frozen).breaks.filter { it.label == DynamicPeriods.LABEL_20S }
        assertTrue(banked.none { it.startMillis < press + 20 * SEC && it.endMillis > press }, "no look-away banked over the conducted one")
        assertTrue(
            banked.none { it.startMillis >= press + 20 * SEC && it.startMillis < press + 20 * SEC + 20 * MIN },
            "the conducted break bars the next look-away for twenty minutes: ${banked.map { (it.startMillis - press) / SEC }}",
        )
    }
}
