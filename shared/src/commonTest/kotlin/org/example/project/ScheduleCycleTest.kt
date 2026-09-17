package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.ScheduleCycle
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_score.md` § *The rules repeat*: when the environment ahead is uniform on the schedulable clock
 * (nights and breaks refuse everybody, so they are transparent to it), the best continuation settles into a
 * repeating run sequence, and the rules return it as a CYCLE. An extension — the calendar scrolled further, the
 * line moving on — unrolls the cycle instead of searching again, and refuses to wherever the cycle no longer holds.
 */
class ScheduleCycleTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR
    private val UTC = TimeZone.UTC

    /** Wednesday 2026-09-02, 10:00 UTC. */
    private val NOW = 1_788_343_200_000L

    @AfterTest
    fun closeTheCalendar() = CalendarHorizonFixture.close()

    /**
     * The requirements' own example (A 30 min, B 15 min, C 15 min at a third each), with the production screen
     * breaks and a sleep schedule — the environment every real account has.
     */
    private fun account(): Pair<SchedulerState, List<TaskId>> {
        var s = SchedulerState.empty()
        val names = listOf("A" to 30, "B" to 15, "C" to 15)
        names.forEachIndexed { i, (name, _) ->
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[i], name))
        }
        val ids = names.map { (name, _) -> s.tasks.keys.first { s.tasks[it]!!.title == name } }
        names.forEachIndexed { i, (_, minutes) -> s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(ids[i], minutes)) }
        return s.copy(sleep = SleepSchedule(), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS) to ids
    }

    private class Filled(val panels: List<TaskPanel>, val cycle: ScheduleCycle?)

    private fun fill(state: SchedulerState, horizon: Long, keep: Boolean = false): Filled {
        var cycle: ScheduleCycle? = null
        val panels =
            SchedulerDomain.fillSchedule(
                state, NOW, timeZone = UTC, horizonMillis = horizon,
                keepExistingUntilMillis = if (keep) SchedulerDomain.firstFreeMoment(state.panels, NOW) else null,
                cycleSink = { cycle = it },
            )
        return Filled(panels, cycle)
    }

    private fun autoRuns(panels: List<TaskPanel>, until: Long): List<Triple<TaskId?, Long, Long>> =
        SchedulerDomain.mergeSameTaskPanels(panels.filter { it.auto && it.taskId != null })
            .filter { it.startEpochMillis < until }
            .sortedBy { it.startEpochMillis }
            .map { Triple(it.taskId, it.startEpochMillis, minOf(it.endEpochMillis, until)) }

    @Test
    fun a_continuation_that_settles_returns_its_repeating_runs() {
        val (s, ids) = account()
        val (a, b, c) = ids
        val cycle = assertNotNull(fill(s, NOW + 2 * DAY).cycle, "two days of a uniform environment must repeat")
        // A30 B15 C15 B15 C15 — the requirements' example — whatever rotation the anchor lands on. The cycle keeps
        // the alternative-schedule splits inside a run, so the runs are merged per task to read it.
        assertEquals(90 * MIN.toDouble(), cycle.lengthMillis)
        val merged = ArrayList<Pair<TaskId, Double>>()
        for (r in cycle.runs) {
            val last = merged.lastOrNull()
            if (last != null && last.first == r.taskId) merged[merged.size - 1] = last.first to last.second + r.lengthMillis
            else merged += r.taskId to r.lengthMillis
        }
        val expected = listOf(a to 30.0 * MIN, b to 15.0 * MIN, c to 15.0 * MIN, b to 15.0 * MIN, c to 15.0 * MIN)
        val rotations = expected.indices.map { k -> expected.drop(k) + expected.take(k) }
        assertTrue(merged in rotations, "expected a rotation of $expected, got $merged")
    }

    @Test
    fun a_continuation_too_short_to_repeat_returns_no_cycle() {
        val (s, _) = account()
        assertNull(fill(s, NOW + 2 * HOUR).cycle)
    }

    @Test
    fun an_extension_unrolls_the_cycle_into_the_plan_one_long_fill_makes() {
        // The cycle is the rules, so continuing from it must be continuing the plan: the tail an extension unrolls
        // is exactly the tail a single fill to the same horizon lays.
        val (s, _) = account()
        val first = fill(s, NOW + 2 * DAY)
        val extended = fill(s.copy(panels = first.panels, scheduleCycle = assertNotNull(first.cycle)), NOW + 6 * DAY, keep = true)
        val direct = fill(s, NOW + 6 * DAY)

        val horizon = NOW + 6 * DAY - HOUR
        assertEquals(autoRuns(direct.panels, horizon), autoRuns(extended.panels, horizon))
        val rebased = assertNotNull(extended.cycle, "an unrolled extension keeps the cycle")
        assertTrue(rebased.anchorMillis > first.cycle.anchorMillis + 3 * DAY, "the cycle is rebased onto the extension")
        assertEquals(first.cycle.runs.map { it.taskId to it.lengthMillis }.toSet(), rebased.runs.map { it.taskId to it.lengthMillis }.toSet())
    }

    @Test
    fun a_pre_placed_task_ahead_stops_the_unroll() {
        // A block the cycle never saw: unrolling over it would schedule a task on top of it.
        val (s, ids) = account()
        val first = fill(s, NOW + 2 * DAY)
        val blockStart = NOW + 3 * DAY + 2 * HOUR
        val block =
            TaskPanel("panel/pinned", ids[0], "A", blockStart, blockStart + 2 * HOUR, pinned = true, auto = false,
                pins = PanelPins(existence = true))
        val extended =
            fill(s.copy(panels = first.panels + block, scheduleCycle = assertNotNull(first.cycle)), NOW + 5 * DAY, keep = true)
        // The old cycle could not be unrolled over it; the search that ran instead may find the rules repeating
        // again, but only once the block is behind them.
        extended.cycle?.let { assertTrue(it.anchorMillis >= block.endEpochMillis, "a cycle anchored before the block: $it") }
        assertNotEquals(first.cycle, extended.cycle)
        assertTrue(
            extended.panels.none { it.auto && it.startEpochMillis < block.endEpochMillis && it.endEpochMillis > block.startEpochMillis },
            "nothing is scheduled over the pre-placed task",
        )
    }

    @Test
    fun a_changed_rule_state_is_not_answered_by_the_old_cycle() {
        val (s, ids) = account()
        val first = fill(s, NOW + 2 * DAY)
        val changed = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(ids[1], 20))
        val extended =
            fill(changed.copy(panels = first.panels, scheduleCycle = assertNotNull(first.cycle)), NOW + 4 * DAY, keep = true)
        extended.cycle?.let { assertNotEquals(first.cycle.ruleStateHash, it.ruleStateHash) }
        val tail = autoRuns(extended.panels, NOW + 4 * DAY - HOUR).filter { it.second > NOW + 3 * DAY && it.first == ids[1] }
        assertTrue(tail.none { it.third - it.second == 15 * MIN }, "B's runs follow its new minimum, not the old cycle: $tail")
    }

    @Test
    fun the_reducer_keeps_the_cycle_a_fill_returns_and_a_short_plan_clears_it() {
        val (s, _) = account()
        CalendarHorizonFixture.show(3 * DAY)
        val planned = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(NOW))
        assertNotNull(planned.scheduleCycle)
        CalendarHorizonFixture.close()
        val replanned = SchedulerReducer.reduce(planned, SchedulerIntent.RefreshSchedule(NOW))
        assertNull(replanned.scheduleCycle, "a twenty-minute re-plan does not repeat, and the old cycle answered old rules")
    }

    /** Six equal tasks whose minimums share no small common scale: their runs never repeat exactly in a week. */
    private fun unevenAccount(): SchedulerState {
        var s = SchedulerState.empty()
        val names = listOf("A" to 45, "B" to 30, "C" to 20, "D" to 60, "E" to 15, "F" to 25)
        names.forEachIndexed { i, (name, _) ->
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[i], name))
        }
        for ((name, minutes) in names) {
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(s.tasks.keys.first { s.tasks[it]!!.title == name }, minutes))
        }
        return s.copy(sleep = SleepSchedule(), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
    }

    @Test
    fun below_the_limit_only_an_exact_repetition_is_returned() {
        assertNull(fill(unevenAccount(), NOW + 3 * DAY).cycle)
    }

    @Test
    fun at_the_limit_the_rules_repeat_the_closest_approximate_copy() {
        // The limit on how heavy the rules may grow: a fill reaching the materialization ceiling returns a
        // repetition whatever the runs did, and a far week is then that repetition, unrolled rather than searched.
        val s = unevenAccount()
        val week = fill(s, NOW + SchedulerDomain.SCHEDULE_HORIZON_MILLIS)
        val cycle = assertNotNull(week.cycle)
        assertTrue(!cycle.exact, "these runs never repeat exactly")
        assertTrue(cycle.runs.size <= 4 * org.example.project.scheduler.domain.ScheduleFill.MAX_CYCLE_RUNS, "the repetition is bounded: ${cycle.runs.size} runs")

        val far = fill(s.copy(panels = week.panels, scheduleCycle = cycle), NOW + 30 * DAY, keep = true)
        val from = NOW + 10 * DAY
        val to = NOW + 30 * DAY
        val served = HashMap<TaskId, Long>()
        for (p in far.panels) {
            if (!p.auto || p.taskId == null) continue
            val a = maxOf(from, p.startEpochMillis)
            val b = minOf(to, p.endEpochMillis)
            if (b > a) served[p.taskId!!] = (served[p.taskId!!] ?: 0L) + (b - a)
        }
        val total = served.values.sum().toDouble()
        assertEquals(6, served.size, "every task keeps running in the far weeks")
        for ((task, millis) in served) {
            val share = millis / total
            assertTrue(kotlin.math.abs(share - 1.0 / 6) < 0.05, "$task runs ${share} of the far weeks, target 1/6")
        }
        val periods = far.panels.filter { it.screenBreak || it.sleep }
        assertTrue(
            far.panels.none { a ->
                a.auto && a.startEpochMillis > NOW + HOUR &&
                    periods.any { it.startEpochMillis < a.endEpochMillis && it.endEpochMillis > a.startEpochMillis }
            },
            "the unrolled repetition runs nobody through a break or a night",
        )
    }

    @Test
    fun the_cycle_is_derived_and_never_persisted_nor_synced() {
        val (s, _) = account()
        val withCycle = s.copy(scheduleCycle = assertNotNull(fill(s, NOW + 2 * DAY).cycle))
        assertNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(withCycle))?.scheduleCycle)
        assertEquals(SchedulerStateCodec.syncFingerprint(s.copy(scheduleCycle = null)), SchedulerStateCodec.syncFingerprint(withCycle))
        assertEquals(SchedulerDomain.schedulingSignature(s), SchedulerDomain.schedulingSignature(withCycle))
    }
}
