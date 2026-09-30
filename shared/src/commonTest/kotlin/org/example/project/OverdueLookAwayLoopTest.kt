package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.example.project.scheduler.domain.BankedBreak
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.FrozenScreenBreakStore
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerRunEntry
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * Account 3, 2026-09-28 09:27–09:31: **"look 20 feet away" announced every thirty seconds, for ever**, and a 20 s hole
 * in the plan eighteen minutes ahead with no break drawn in it — a hole no score chose.
 *
 * An overdue look-away is placed at the banked front — the line. The task the plan had there is recorded as the tick
 * banks it, over the look-away that has not ended yet; the look-away, once over, was then refused banking as a break
 * over recorded work — so the bars never moved past it, and at the next tick the same overdue look-away was placed at
 * the new front, the at-line check re-planned around it, the cue sweep announced it, and the plan's hole for the next
 * one (twenty minutes after it) was left where the rules no longer put anything.
 */
class OverdueLookAwayLoopTest {
    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val T0 = 1_700_000_000_000L

    @AfterTest
    fun resetSeams() {
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.conductingBreak = { null }
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
        SchedulerReducer.recordSchedulerRun = {}
    }

    private class MemoryStore(var frozen: FrozenScreenBreaks?) : FrozenScreenBreakStore {
        override fun loadFrozenScreenBreaks() = frozen
        override fun saveFrozenScreenBreaks(added: List<BankedBreak>, untilMillis: Long, lineMillis: Long, pruneBeforeMillis: Long, removed: List<BankedBreak>) {}
    }

    @Test
    fun an_overdue_look_away_is_placed_once_banked_once_and_the_line_moves_on() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        s = s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val work = s.tasks.values.single { it.title == "Work" }.id
        // The last look-away was banked two hours ago, the front is the line: the next one is long overdue. A rest a
        // few minutes ago keeps the poses from being owed, so the look-away is what falls due.
        val lastLookAway = BankedBreak(DynamicPeriods.LABEL_20S, T0 - 2 * HOUR, T0 - 2 * HOUR + 20 * SEC)
        val rest = org.example.project.scheduler.model.TaskPanel("rest/0", null, "No screen", T0 - 40 * MIN, T0 - 22 * MIN, noScreen = true)
        s = s.copy(
            panels = listOf(rest),
            tasks = s.tasks + (work to s.tasks.getValue(work).copy(record = listOf(TaskTimeRange(T0 - 22 * MIN, T0)))),
        )
        var now = T0
        // Real time creeps on inside a tick: the re-plan the at-line check asks for reads a clock a few milliseconds
        // past the one the tick banked at — which is what left the recorded work overlapping the look-away.
        var drift = 0L
        val runs = mutableListOf<SchedulerRunEntry>()
        val vm = TaskSchedulerViewModel(initial = s, store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock { override fun nowMillis(): Long = now + drift++ },
                scope = CoroutineScope(Dispatchers.Unconfined),
                screenActive = { true },
                speak = {},
                postNotification = { _, _ -> },
                frozenBreakStore = MemoryStore(FrozenScreenBreaks(listOf(lastLookAway), T0, T0)),
            )
        engine.start()
        SchedulerReducer.recordSchedulerRun = { runs += it }
        vm.dispatch(SchedulerIntent.RefreshSchedule(now, now + 2 * HOUR))
        runs.clear()
        // Twenty minutes of thirty-second ticks.
        repeat(40) {
            now += 30 * SEC
            drift = 0
            engine.advanceTo(now)
        }
        val banked = engine.frozenBreaks.value!!.breaks.filter { it.label == DynamicPeriods.LABEL_20S && it.startMillis >= T0 }
        assertTrue(banked.size in 1..2, "one look-away is due in twenty minutes, and it is banked: ${banked.map { (it.startMillis - T0) / SEC }}")
        val replans = runs.count { it.kind == SchedulerRunEntry.Kind.Replan }
        // The one re-plan here is a RULE change — the look-away the engine conducted is recorded as a period
        // (`RecordConductedBreak`) — never the line: the check at the line reports a mismatch and the rules apply.
        assertTrue(replans <= 2, "the line does not re-plan at every tick: $replans re-plans")
        // And the plan leaves no hole the rules put no break in: every gap ahead of the line holds a break.
        val st = vm.state.value
        val env = SchedulerDomain.breakEnvironment(st, now, now + 2 * HOUR, kotlinx.datetime.TimeZone.UTC, frozen = engine.frozenBreaks.value)
        val drawn = SchedulerDomain.screenBreakPanels(st.screenBreaks, now, now + HOUR, env.periods, env.blocks, env.tasks, frozen = env.frozen)
            .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        val work2 = st.panels.filter { it.auto && it.endEpochMillis > now }.map { TaskTimeRange(maxOf(now, it.startEpochMillis), it.endEpochMillis) }
        // Time the plan DECIDED to leave to nobody is not a hole (`SchedulerState.plannedIdle`).
        // …as far as the plan is materialized: past its front there is nothing to check (no re-plan extends it
        // any more — the check at the line reports and never re-plans).
        val front = minOf(now + HOUR, st.panels.filter { it.auto }.maxOf { it.endEpochMillis })
        val holes = SchedulerDomain.subtractRegions(listOf(TaskTimeRange(now, front)), SchedulerDomain.mergeOccupied(work2 + drawn + st.plannedIdle))
        assertTrue(holes.isEmpty(), "holes with neither a task, a break nor a decision: ${holes.map { (it.startEpochMillis - now) / SEC to (it.endEpochMillis - it.startEpochMillis) / SEC }}")
    }

    @Test
    fun a_look_away_the_line_places_is_banked_even_where_the_last_record_ran_a_few_milliseconds_into_it() {
        // The real sequence (account 3, 09:27:20): the front is the line, the look-away is overdue, so it is placed AT
        // the front; the task panel under it is recorded up to the instant the at-line re-plan read the clock — a few
        // milliseconds INTO the look-away. Refusing to bank a break over recorded work refused this one for good, and
        // the next tick placed it again at the next front.
        val onScreen = listOf(org.example.project.scheduler.domain.PlanTask(org.example.project.scheduler.model.TaskId("t"), 1.0, 30 * MIN, mapOf(org.example.project.scheduler.domain.PeriodKinds.NO_SCREEN to 0.0)))
        val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
        val front = T0
        val frozen = FrozenScreenBreaks(listOf(BankedBreak(DynamicPeriods.LABEL_20S, T0 - 2 * HOUR, T0 - 2 * HOUR + 20 * SEC)), front, front)
        // A 15-minute rest ending just before: no pose owed, the look-away is what is overdue.
        val rest = listOf(org.example.project.scheduler.domain.RestrictivePeriod(T0 - 40 * MIN, T0 - 22 * MIN, org.example.project.scheduler.domain.PeriodKinds.NO_SCREEN, "rest"))
        val next = SchedulerDomain.bankScreenBreaks(breaks, frozen, T0 + 30 * SEC, rest, emptyList(), onScreen, DynamicPeriods.MODE_AT_SCREEN)
        val banked = next.breaks.filter { it.label == DynamicPeriods.LABEL_20S && it.startMillis >= T0 }
        assertTrue(banked.isNotEmpty(), "the look-away the line placed at the front is banked: ${next.breaks.map { it.label to (it.startMillis - T0) / SEC }}")
        // It is banked the moment the line reaches it, so work is never recorded inside it (the record append leaves
        // out every banked break) — there is no "started but not banked yet" any more.
    }
}
