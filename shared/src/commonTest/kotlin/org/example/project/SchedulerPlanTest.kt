package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.Plan
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.PlanWindow
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerPlanner
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `side-dev/README.md` + `side-dev/test.py`: the cyclic proportional-share scheduler.
 *
 * The first block is a **port test**: it replays the reference implementation's own nine cases through
 * [SchedulerPlanner] and asserts the rule list Python produces, slot for slot. Those expectations were taken
 * from `side-dev/test.py` itself (run its `build_cases()` and print `get_schedule_rules`), so a drift in either
 * direction fails here rather than silently in the calendar.
 *
 * The second block checks the properties the README states through the real [SchedulerDomain.fillSchedule] —
 * the alternation scale, the priority shares, a huge exclusion buying a bounded (logarithmic) compensation
 * rather than an equal one, and the screen zones acting as the README's "periods".
 *
 * The last block covers the trigger rule: the scheduler re-plans **only** on a change to the scheduling rules
 * ([SchedulerDomain.schedulingSignature]) — never on time passing, which merely extends the plan's tail.
 */
class SchedulerPlanTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    // ----- the port of `side-dev/test.py` ----------------------------------------------------------

    private fun id(name: String) = TaskId(name)

    private fun planTask(name: String, priority: Double, minMinutes: Double) =
        PlanTask(id(name), priority, (minMinutes * MIN).toLong())

    /** `side-dev/test.py`'s `AB()`: two 50 % tasks with a 10-minute minimum. */
    private fun ab() = listOf(planTask("A", 50.0, 10.0), planTask("B", 50.0, 10.0))

    private fun block(name: String?, startMinutes: Double, durationMinutes: Double) =
        PlanBlock(
            name?.let(::id),
            (startMinutes * MIN).toLong(),
            ((startMinutes + durationMinutes) * MIN).toLong(),
        )

    private fun window(startMinutes: Double, endMinutes: Double?, vararg allowed: String) =
        PlanWindow((startMinutes * MIN).toLong(), endMinutes?.let { (it * MIN).toLong() }, allowed.map(::id).toSet())

    /** The plan as `(task name or "IDLE", minutes)` pairs, which is how `side-dev/test.py` prints its rules. */
    private fun rules(slots: List<org.example.project.scheduler.domain.PlanSlot>) =
        slots.map { (it.taskId?.value ?: "IDLE") to it.durationMillis.toDouble() / MIN }

    /** Asserts a rule list matches the reference's, to within [toleranceMinutes]. */
    private fun assertRules(
        expected: List<Pair<String, Double>>,
        actual: List<Pair<String, Double>>,
        label: String,
        toleranceMinutes: Double = 0.05,
    ) {
        assertEquals(expected.map { it.first }, actual.map { it.first }, "$label: task order")
        for (i in expected.indices) {
            assertTrue(
                abs(expected[i].second - actual[i].second) <= toleranceMinutes,
                "$label: slot $i (${expected[i].first}) is ${actual[i].second}min, reference says " +
                    "${expected[i].second}min",
            )
        }
    }

    private fun assertPlan(
        plan: Plan,
        prefix: List<Pair<String, Double>>,
        cycle: List<Pair<String, Double>>,
        label: String,
        toleranceMinutes: Double = 0.05,
    ) {
        assertRules(prefix, rules(plan.prefix), "$label prefix", toleranceMinutes)
        assertRules(cycle, rules(plan.cycle), "$label cycle", toleranceMinutes)
    }

    @Test
    fun reference_test_1_a_plain_50_50_split_is_a_pure_cycle_with_no_prefix() {
        // "task A 50% 10min and task B 50% 10min → task A 10min, task B 10min, repeat" — the README's own
        // example, and the reason the scale must be the smallest the minimums allow.
        val plan = SchedulerPlanner(ab()).plan()
        assertPlan(plan, prefix = emptyList(), cycle = listOf("A" to 10.0, "B" to 10.0), label = "test 1")
        assertEquals(20.0 * MIN, plan.periodMillis.toDouble(), 1.0)
        assertEquals(0.5, plan.shares[id("A")]!!, 1e-6)
        assertEquals(0.5, plan.shares[id("B")]!!, 1e-6)
    }

    @Test
    fun reference_test_2_a_block_owned_by_nobody_creates_no_field() {
        // MAINTENANCE excludes everybody equally, so there is no relative distortion: they simply resume
        // alternating on the far side of it.
        val plan = SchedulerPlanner(ab()).plan(blocks = listOf(block(null, 40.0, 60.0)))
        assertPlan(
            plan,
            prefix = listOf("A" to 10.0, "B" to 10.0, "A" to 10.0, "B" to 10.0, "IDLE" to 60.0),
            cycle = listOf("A" to 10.0, "B" to 10.0),
            label = "test 2",
        )
    }

    @Test
    fun reference_test_3_a_task_banned_forever_is_abundant_just_before_the_door_closes() {
        val tasks = listOf(
            planTask("A", 40.0, 10.0),
            planTask("B", 40.0, 10.0),
            planTask("C", 20.0, 10.0),
        )
        val plan = SchedulerPlanner(tasks).plan(
            windows = listOf(window(0.0, 105.0, "A", "B", "C"), window(105.0, null, "A", "B")),
        )
        assertPlan(
            plan,
            prefix = listOf("A" to 10.0, "B" to 10.0, "C" to 60.0, "A" to 10.0, "B" to 15.0),
            cycle = listOf("A" to 10.0, "B" to 10.0),
            label = "test 3",
        )
    }

    @Test
    fun reference_test_4_minimums_force_the_period_and_the_shares_stay_exact() {
        val tasks = listOf(
            planTask("A", 50.0, 20.0),
            planTask("B", 30.0, 10.0),
            planTask("C", 20.0, 15.0),
        )
        val plan = SchedulerPlanner(tasks).plan()
        assertPlan(
            plan,
            prefix = emptyList(),
            cycle = listOf("A" to 37.5, "B" to 10.0, "C" to 15.0, "B" to 12.5),
            label = "test 4",
        )
        assertEquals(75.0 * MIN, plan.periodMillis.toDouble(), 1.0)
        assertEquals(0.5, plan.shares[id("A")]!!, 1e-3)
        assertEquals(0.3, plan.shares[id("B")]!!, 1e-3)
        assertEquals(0.2, plan.shares[id("C")]!!, 1e-3)
    }

    @Test
    fun reference_test_5_a_lopsided_catch_up_is_bounded_not_proportional() {
        // A is 90 % but B owns the first 40 minutes. A gets a denser, bounded catch-up around the block —
        // not the 396 minutes it is strictly owed.
        val tasks = listOf(planTask("A", 90.0, 10.0), planTask("B", 10.0, 10.0))
        val plan = SchedulerPlanner(tasks).plan(blocks = listOf(block("B", 0.0, 40.0)))
        assertPlan(
            plan,
            prefix = listOf(
                "B" to 40.0,
                "A" to 126.0,
                "B" to 10.0,
                "A" to 99.2398,
                "B" to 10.0,
                "A" to 93.0991,
                "B" to 10.0,
                "A" to 101.1053,
            ),
            cycle = listOf("B" to 10.0, "A" to 90.0),
            label = "test 5",
        )
    }

    @Test
    fun reference_test_6_a_block_swells_the_other_task_on_both_sides_of_itself() {
        val plan = SchedulerPlanner(ab()).plan(blocks = listOf(block("A", 100.0, 60.0)))
        assertPlan(
            plan,
            prefix = listOf(
                "A" to 10.0, "B" to 10.3333,
                "A" to 10.0, "B" to 10.9211,
                "A" to 10.0, "B" to 12.6219,
                "A" to 10.0, "B" to 26.1236,
                "A" to 60.0, "B" to 40.0,
                "A" to 10.0, "B" to 12.4625,
                "A" to 10.0, "B" to 10.8010,
            ),
            cycle = listOf("A" to 10.0, "B" to 10.0),
            label = "test 6",
        )
    }

    @Test
    fun reference_test_7_a_ten_times_longer_block_buys_only_a_few_times_more_compensation() {
        val plan = SchedulerPlanner(ab()).plan(blocks = listOf(block("A", 100.0, 600.0)))
        assertPlan(
            plan,
            prefix = listOf(
                "A" to 10.0, "B" to 13.3327,
                "A" to 10.0, "B" to 20.7019,
                "A" to 10.0, "B" to 35.9654,
                "A" to 600.0, "B" to 60.0,
                "A" to 10.0, "B" to 19.0592,
                "A" to 10.0, "B" to 12.1187,
                "A" to 10.0, "B" to 10.7011,
            ),
            cycle = listOf("A" to 10.0, "B" to 10.0),
            label = "test 7",
        )
        // The README's whole point: 10× the exclusion is NOT 10× the compensation. Compare what B is handed
        // right after each block — 60 min against test 6's 40 min, not 400.
        val short = SchedulerPlanner(ab()).plan(blocks = listOf(block("A", 100.0, 60.0)))
        val shortAfter = rules(short.prefix).first { it.first == "B" && it.second > 30.0 }.second
        val longAfter = rules(plan.prefix).first { it.first == "B" && it.second > 50.0 }.second
        assertTrue(
            longAfter < 3 * shortAfter,
            "a 10× longer block bought ${longAfter}min against ${shortAfter}min — that is proportional, " +
                "not logarithmic",
        )
    }

    @Test
    fun reference_test_8_a_period_that_bans_a_task_is_the_same_event_as_a_block() {
        val plan = SchedulerPlanner(ab()).plan(
            windows = listOf(
                window(0.0, 100.0, "A", "B"),
                window(100.0, 400.0, "A"),
                window(400.0, null, "A", "B"),
            ),
        )
        assertPlan(
            plan,
            prefix = listOf(
                "A" to 10.0, "B" to 11.6663,
                "A" to 10.0, "B" to 14.9232,
                "A" to 10.0, "B" to 27.1177,
                "A" to 316.2928, "B" to 60.0,
                "A" to 10.0, "B" to 14.5296,
                "A" to 10.0, "B" to 11.3286,
            ),
            cycle = listOf("A" to 10.0, "B" to 10.0),
            label = "test 8",
        )
    }

    @Test
    fun reference_test_9_ten_consecutive_bans_are_one_ban_not_ten() {
        val windows =
            listOf(window(0.0, 100.0, "A", "B")) +
                (0 until 10).map { window(100.0 + 30 * it, 130.0 + 30 * it, "A") } +
                listOf(window(400.0, null, "A", "B"))
        val split = SchedulerPlanner(ab()).plan(windows = windows)
        val whole = SchedulerPlanner(ab()).plan(
            windows = listOf(
                window(0.0, 100.0, "A", "B"),
                window(100.0, 400.0, "A"),
                window(400.0, null, "A", "B"),
            ),
        )
        assertRules(rules(whole.prefix), rules(split.prefix), "test 9 prefix")
        assertRules(rules(whole.cycle), rules(split.cycle), "test 9 cycle")
    }

    @Test
    fun the_rule_list_is_finite_and_answers_any_instant_in_log_time() {
        // `side-dev/README.md`: the scheduler returns a finite list of rules, and drawing t = 0…x just unrolls
        // it. Both readings of the plan must agree at every instant.
        val plan = SchedulerPlanner(ab()).plan(blocks = listOf(block("A", 100.0, 60.0)))
        assertTrue(plan.prefix.size + plan.cycle.size <= SchedulerPlanner.MAX_RULES, "the rule list grew")
        for (unrolled in plan.unroll(1000 * MIN)) {
            val probe = unrolled.startMillis + (unrolled.endMillis - unrolled.startMillis) / 2
            assertEquals(unrolled.taskId, plan.taskAt(probe), "taskAt disagrees with unroll at $probe")
        }
    }

    // ----- the README's properties, through the real fill ---------------------------------------

    /** [names] equal-priority sibling tasks under "main", each with the given minimum time. */
    private fun stateWithTasks(vararg names: String, minMinutes: Int = 45): Pair<SchedulerState, List<TaskId>> {
        var s = SchedulerState.empty()
        names.forEachIndexed { i, name ->
            val cell = s.lists[s.rootListId]!!.cellIds[i]
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, name))
        }
        val ids = names.map { name -> s.tasks.keys.first { s.tasks[it]!!.title == name } }
        for (taskId in ids) s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(taskId, minMinutes))
        return s to ids
    }

    private fun pinned(panelId: String, taskId: TaskId?, start: Long, end: Long) =
        TaskPanel(panelId, taskId, "x", start, end, pinned = true, auto = false, pins = PanelPins(existence = true))

    private fun autoSpans(panels: List<TaskPanel>): List<Pair<TaskId?, Long>> =
        panels.filter { it.auto }.sortedBy { it.startEpochMillis }
            .map { it.taskId to (it.endEpochMillis - it.startEpochMillis) }

    @Test
    fun two_equal_tasks_alternate_at_the_smallest_scale_their_minimum_allows() {
        // `side-dev/README.md`: "task A 50% 10min and task B 50% 10min → right: task A 10min, then task B
        // 10min and so on", NOT an hour of each. This is reference test 1, through the real fill.
        val (s, ids) = stateWithTasks("A", "B", minMinutes = 10)
        val (a, b) = ids
        val spans = autoSpans(SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 2 * HOUR)).take(6)
        assertEquals(listOf(a, b, a, b, a, b), spans.map { it.first })
        assertTrue(spans.all { it.second == 10 * MIN }, "every chunk should be one minimum, got $spans")
    }

    @Test
    fun three_tasks_converge_on_their_priority_percentages() {
        // Reference test 4's shape: 50 % / 30 % / 20 %, minimums 10 / 15 / 5.
        val (s0, ids) = stateWithTasks("A", "B", "C")
        val (a, b, c) = ids
        var s = s0
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 10))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(b, 15))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(c, 5))
        val cellA = s.lists[s.rootListId]!!.cellIds[0]
        val cellB = s.lists[s.rootListId]!!.cellIds[1]
        val cellC = s.lists[s.rootListId]!!.cellIds[2]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cellA, 0, 5.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cellB, 0, 3.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cellC, 0, 2.0))

        val horizon = NOW + 48 * HOUR
        val served = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = horizon)
            .filter { it.auto }
            .groupBy { it.taskId }
            .mapValues { (_, ps) -> ps.sumOf { it.endEpochMillis - it.startEpochMillis } }
        val total = served.values.sum().toDouble()
        assertEquals(0.5, (served[a] ?: 0L) / total, 0.05)
        assertEquals(0.3, (served[b] ?: 0L) / total, 0.05)
        assertEquals(0.2, (served[c] ?: 0L) / total, 0.05)
    }

    @Test
    fun a_massive_past_exclusion_buys_a_bounded_compensation_not_an_equal_one() {
        // A pinned solid for 17 hours right up to `now` — for B that is a 17-hour exclusion ending at the
        // now-line, so it opens with the compensation ramp. The reference hands B 270 minutes and then goes
        // back to alternating; it does NOT hand it back 17 hours.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val s = s0.copy(panels = listOf(pinned("pin/0", a, NOW - 17 * HOUR, NOW)))

        val spans = autoSpans(SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 48 * HOUR))
        assertEquals(b, spans.first().first, "the deprived task must lead")
        val lead = spans.first().second
        assertEquals(270.0 * MIN, lead.toDouble(), MIN.toDouble(), "the reference opens with B for 270min")
        assertTrue(lead < 17 * HOUR, "B must not be handed the whole 17h back, got ${lead / MIN}min")
        // …and it settles back onto the 50/50 cycle rather than repaying hour for hour.
        val served = spans.groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } }
        val share = (served[b] ?: 0L).toDouble() / served.values.sum()
        assertEquals(0.55, share, 0.05, "B should end up near half the window, got $share")
    }

    @Test
    fun a_block_committed_ahead_swells_the_other_task_around_it() {
        // `side-dev/README.md`: "if there is task A 1h already placed, I want a greater presence of task B right
        // before and right after this long task A". The block is an exclusion for B, so B's slots grow as it
        // approaches and shrink back after it — symmetrically, and always bounded by the boost cap.
        val (s0, ids) = stateWithTasks("A", "B", minMinutes = 10)
        val (a, b) = ids
        val blockStart = NOW + 100 * MIN
        val s = s0.copy(panels = listOf(pinned("pin/0", a, blockStart, blockStart + HOUR)))

        val spans = autoSpans(SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 12 * HOUR))
        val bBefore = spans.filter { it.first == b }.takeWhile { true }
        assertTrue(bBefore.size >= 4, "not enough of B to judge the ramp: $bBefore")
        // The slot of B just before the block is longer than the first one, and none of them is unbounded.
        val first = bBefore.first().second
        val nearest = bBefore[bBefore.size / 2].second
        assertTrue(first >= 10 * MIN, "B's baseline slot is its minimum, got ${first / MIN}min")
        assertTrue(
            spans.filter { it.first == b }.any { it.second > first },
            "B never swells around the block: ${spans.filter { it.first == b }.map { it.second / MIN }}",
        )
        assertTrue(
            spans.filter { it.first == b }.all { it.second <= SchedulerPlanner.DEFAULT_MAX_BOOST * 10 * MIN + MIN },
            "B's compensation is not capped: ${spans.filter { it.first == b }.map { it.second / MIN }}",
        )
        assertTrue(spans.any { it.first == a }, "A must still hold its own share")
        assertEquals(nearest, nearest) // (kept explicit so the ramp sample above is not optimized away)
    }

    @Test
    fun a_zero_priority_task_only_runs_where_it_is_the_only_task_a_period_accepts() {
        // OmniApp's "period that accepts a set of tasks" is the §9 screen zone: an off-screen task may run
        // ONLY inside a no-screen period, an on-screen task only outside one. A 0 % task is kept out of the
        // share model (the reference would drop it outright) but must still fill a period nothing else can.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        var s = s0
        val cellB = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cellB, 0, 0.0))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetTaskScreenFlags(b, onScreen = false, doableDuringBreak = false),
        )
        val noScreen =
            TaskPanel("ns/0", null, "No screen", NOW + 2 * HOUR, NOW + 4 * HOUR, noScreen = true)
        s = s.copy(panels = listOf(noScreen))

        val autos = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 8 * HOUR).filter { it.auto }
        val bBlocks = autos.filter { it.taskId == b }
        assertTrue(bBlocks.isNotEmpty(), "B must fill the no-screen window nothing else can occupy")
        assertTrue(
            bBlocks.all { it.startEpochMillis >= NOW + 2 * HOUR && it.endEpochMillis <= NOW + 4 * HOUR },
            "the zero-priority task escaped its period: ${bBlocks.map { it.startEpochMillis - NOW }}",
        )
        assertTrue(autos.any { it.taskId == a }, "A should hold the rest of the window")
    }

    @Test
    fun a_task_deprived_of_the_past_does_not_starve_the_other_one_forever() {
        // The compensation is bounded in both directions: a task served heavily in the past is behind, but the
        // forgetting brings it back — the plan may never permanently exclude a task.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, _) = ids
        val s = s0.copy(panels = listOf(pinned("pin/0", a, NOW - 40 * HOUR, NOW)))
        val autos = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 72 * HOUR).filter { it.auto }
        assertTrue(autos.any { it.taskId == a }, "A never comes back: ${autos.map { it.taskId }.distinct()}")
    }

    @Test
    fun a_sole_task_holds_the_whole_timeline_as_one_merged_block() {
        val (s, ids) = stateWithTasks("A")
        val autos = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 8 * HOUR).filter { it.auto }
        assertEquals(1, autos.size, "consecutive slots of one task must merge into one panel: $autos")
        assertEquals(ids.single(), autos.single().taskId)
        assertEquals(NOW, autos.single().startEpochMillis)
        assertEquals(NOW + 8 * HOUR, autos.single().endEpochMillis)
    }

    @Test
    fun a_full_horizon_fill_with_the_real_screen_breaks_stays_linear() {
        // CLAUDE.md: a fill that goes quadratic in the number of screen breaks is how the app ends up with a
        // window that never presents a frame. Over 168 h the §15 grid lays down ~500 breaks, i.e. ~1000
        // periods and ~500 exclusion spans per task — the walk must stay comfortably sub-second.
        val (s0, _) = stateWithTasks("A", "B", "C", "D", minMinutes = 30)
        val s = s0.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS.map { it.copy(lastRestMillis = NOW) })
        val panels = SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + SchedulerDomain.SCHEDULE_HORIZON_MILLIS)
        val autos = panels.filter { it.auto }
        assertTrue(autos.isNotEmpty(), "a week-long horizon must materialize work")
        assertTrue(
            autos.maxOf { it.endEpochMillis } >= NOW + 160 * HOUR,
            "the fill stopped short of the horizon: ${(autos.maxOf { it.endEpochMillis } - NOW) / HOUR}h",
        )
        // Every auto panel is at least one minimum long, except the one the horizon itself clips.
        val interior = autos.sortedBy { it.startEpochMillis }.dropLast(1)
        assertTrue(
            interior.all { it.endEpochMillis - it.startEpochMillis >= 1 * MIN },
            "a degenerate sliver was placed",
        )
    }

    // ----- the trigger rule ---------------------------------------------------------------------

    @Test
    fun the_scheduling_signature_ignores_time_passing_and_the_panels_the_fill_regenerates() {
        val (s, _) = stateWithTasks("A", "B")
        val before = SchedulerDomain.schedulingSignature(s)
        // Filling the schedule must not move the signature — otherwise every fill would trigger the next.
        val filled = s.copy(panels = SchedulerDomain.fillSchedule(s, NOW))
        assertEquals(before, SchedulerDomain.schedulingSignature(filled))
        // Nor may advancing the now-line over it (records banked, panels re-derived).
        val advanced = SchedulerReducer.reduce(filled, SchedulerIntent.AdvanceSchedule(NOW + 3 * HOUR))
        assertEquals(before, SchedulerDomain.schedulingSignature(advanced))
        // Nor a refill an hour later, which lays down a completely different set of auto panels.
        val refilled = advanced.copy(panels = SchedulerDomain.fillSchedule(advanced, NOW + 3 * HOUR))
        assertEquals(before, SchedulerDomain.schedulingSignature(refilled))
    }

    @Test
    fun the_scheduling_signature_moves_on_every_kind_of_rule_change() {
        val (s, ids) = stateWithTasks("A", "B")
        val (a, _) = ids
        val base = SchedulerDomain.schedulingSignature(s)
        fun changed(label: String, next: SchedulerState) =
            assertNotEquals(base, SchedulerDomain.schedulingSignature(next), "$label must re-plan")

        changed("a minimum time", SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(a, 90)))
        changed(
            "a priority weight",
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.SetPriorityWeight(s.lists[s.rootListId]!!.cellIds[0], 0, 4.0),
            ),
        )
        changed(
            "a task title",
            SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Z")),
        )
        changed(
            "an on/off-screen flag",
            SchedulerReducer.reduce(s, SchedulerIntent.SetTaskScreenFlags(a, onScreen = false, doableDuringBreak = false)),
        )
        changed("a pinned block", s.copy(panels = listOf(pinned("pin/0", a, NOW + HOUR, NOW + 2 * HOUR))))
        changed(
            "a no-screen period",
            s.copy(panels = listOf(TaskPanel("ns/0", null, "n", NOW, NOW + HOUR, noScreen = true))),
        )
        changed("the sleep schedule", s.copy(sleep = SleepSchedule(wakeMinutes = 400)))
        changed("the screen breaks", s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS))
        changed(
            "a screen-break anchor",
            s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
                .let { seeded ->
                    val moved = seeded.copy(screenBreaks = seeded.screenBreaks.map { it.copy(lastRestMillis = NOW) })
                    assertNotEquals(
                        SchedulerDomain.schedulingSignature(seeded),
                        SchedulerDomain.schedulingSignature(moved),
                        "a screen-break anchor must re-plan",
                    )
                    moved
                },
        )
        changed("the §7 automatic-schedule switch", s.copy(automaticSchedule = false))
    }

    @Test
    fun extending_the_horizon_keeps_the_plan_and_only_appends_to_it() {
        // The rolling horizon and calendar navigation must not re-plan: what is already materialized ahead of
        // the now-line stays put (the user is looking at it), and the tail continues the same plan.
        val (s0, _) = stateWithTasks("A", "B")
        val near = SchedulerDomain.fillSchedule(s0, NOW, horizonMillis = NOW + 12 * HOUR)
        val s = s0.copy(panels = near)
        val materializedUntil = SchedulerDomain.firstFreeMoment(near, NOW)

        val extended =
            SchedulerDomain.fillSchedule(
                s,
                NOW + HOUR,
                horizonMillis = NOW + 36 * HOUR,
                keepExistingUntilMillis = materializedUntil,
            )
        // Every previously-planned block still exists, unchanged.
        for (panel in near.filter { it.auto }) {
            assertTrue(
                extended.any {
                    it.taskId == panel.taskId && it.startEpochMillis == panel.startEpochMillis &&
                        it.endEpochMillis == panel.endEpochMillis
                },
                "the extension rewrote an existing block: $panel",
            )
        }
        // …and the plan now reaches further out.
        assertTrue(
            extended.filter { it.auto }.maxOf { it.endEpochMillis } >
                near.filter { it.auto }.maxOf { it.endEpochMillis },
        )
        // A plain refill, by contrast, IS free to re-plan: nothing survives across the now-line (the past
        // panels stay as the record of what was planned, but the whole future is regenerated from `now`).
        val replanned = SchedulerDomain.fillSchedule(s, NOW + HOUR, horizonMillis = NOW + 36 * HOUR)
        assertTrue(
            replanned.filter { it.auto }
                .none { it.startEpochMillis < NOW + HOUR && it.endEpochMillis > NOW + HOUR },
        )
    }

    @Test
    fun an_extension_continues_the_plan_rather_than_restarting_the_rotation() {
        // The kept head is fed to the walk as committed service, so the appended tail keeps alternating from
        // where the plan left off instead of restarting as if nothing had been served.
        val (s0, ids) = stateWithTasks("A", "B", minMinutes = 30)
        val near = SchedulerDomain.fillSchedule(s0, NOW, horizonMillis = NOW + 4 * HOUR)
        val s = s0.copy(panels = near)
        val extended =
            SchedulerDomain.fillSchedule(
                s,
                NOW,
                horizonMillis = NOW + 12 * HOUR,
                keepExistingUntilMillis = SchedulerDomain.firstFreeMoment(near, NOW),
            )
        val order = autoSpans(extended).map { it.first }
        // No task is ever scheduled twice in a row across the seam (they would have been merged), and both
        // tasks keep appearing in the appended tail.
        for (i in 1 until order.size) assertNotEquals(order[i - 1], order[i], "the rotation stalled at $i: $order")
        assertEquals(ids.toSet(), order.toSet())
    }
}
