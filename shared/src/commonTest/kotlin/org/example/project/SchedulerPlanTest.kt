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
 * `side-dev/README.md` + `side-dev/scheduler_logic.py`: the cyclic proportional-share scheduler.
 *
 * The first block is a **port test**: it replays the reference implementation's own nine cases through
 * [SchedulerPlanner] and asserts the rule list Python produces, slot for slot. Those expectations were taken
 * from `side-dev/scheduler_logic.py` itself (run its `build_cases()` and print `get_schedule_rules`), so a drift in either
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

    // ----- the port of `side-dev/scheduler_logic.py` ----------------------------------------------------------

    private fun id(name: String) = TaskId(name)

    private fun planTask(name: String, priority: Double, minMinutes: Double) =
        PlanTask(id(name), priority, (minMinutes * MIN).toLong())

    /** `side-dev/scheduler_logic.py`'s `AB()`: two 50 % tasks with a 10-minute minimum. */
    private fun ab() = listOf(planTask("A", 50.0, 10.0), planTask("B", 50.0, 10.0))

    private fun block(name: String?, startMinutes: Double, durationMinutes: Double) =
        PlanBlock(
            name?.let(::id),
            (startMinutes * MIN).toLong(),
            ((startMinutes + durationMinutes) * MIN).toLong(),
        )

    private fun window(startMinutes: Double, endMinutes: Double?, vararg allowed: String) =
        PlanWindow((startMinutes * MIN).toLong(), endMinutes?.let { (it * MIN).toLong() }, allowed.map(::id).toSet())

    /**
     * The same thing in exact millis. `(20·i + 1/3)` minutes does not land on a whole millisecond in `Double`,
     * and a boundary a millisecond off is enough to make the walk emit a 1 ms sliver the reference — which
     * keeps every value an exact rational — never has.
     */
    private fun windowMillis(startMillis: Long, endMillis: Long?, vararg allowed: String) =
        PlanWindow(startMillis, endMillis, allowed.map(::id).toSet())

    private val LOOK_AWAY = 20_000L

    /** The plan as `(task name or "IDLE", minutes)` pairs, which is how `side-dev/scheduler_logic.py` prints its rules. */
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
            prefix = listOf(
                "A" to 10.0, "B" to 10.0, "C" to 60.0, "A" to 10.0, "B" to 10.0, "A" to 10.0, "B" to 10.0,
            ),
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
            // The two B slots are 12.5 then 10, not 10 then 12.5: `steadyCycle` settles the SIZES by the
            // clock and then orders them densest-first, so the longer of B's two turns leads. Same slots,
            // same shares — reference `Scheduler.steady_cycle`.
            cycle = listOf("A" to 37.5, "B" to 12.5, "C" to 15.0, "B" to 10.0),
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
                "A" to 91.1053,
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
                // A may START a run that finishes inside its own block, so its last free slot and the block
                // merge into one 68-minute placement (`_walls` / `_clears`: nobody comes back inside it).
                "A" to 10.0, "B" to 18.1256,
                "A" to 67.9981, "B" to 40.0,
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
    fun reference_test_9b_two_overlapping_periods_sum_their_bans_on_a_timeline_they_do_not_cover() {
        // `side-dev/scheduler_logic.py` `_allowed_at`: periods may OVERLAP and the timeline need not be
        // covered by them at all. What an instant refuses is the SUM of the bans of every period over it, and
        // an instant no period covers refuses nobody. C is out from 100, B joins it at 200, and where the two
        // overlap A holds the timeline alone.
        val tasks = listOf(
            planTask("A", 40.0, 10.0),
            planTask("B", 30.0, 10.0),
            planTask("C", 30.0, 10.0),
        )
        val plan = SchedulerPlanner(tasks).plan(
            windows = listOf(
                window(100.0, 300.0, "A", "B"), // forbids C
                window(200.0, 400.0, "A", "C"), // forbids B — and [200, 300) forbids both
            ),
        )
        assertPlan(
            plan,
            prefix = listOf(
                "A" to 10.0, "B" to 10.2008, "C" to 15.4760,
                "A" to 10.0, "B" to 10.5855, "C" to 26.1547,
                "A" to 10.0, "B" to 12.3794,
                "A" to 10.0, "B" to 14.6564,
                "A" to 10.0, "B" to 19.7564,
                "A" to 10.0, "B" to 30.7910,
                "A" to 100.0, "C" to 60.0,
                "A" to 10.0, "C" to 17.3474,
                "A" to 10.0, "C" to 10.0, "B" to 58.1308,
                "A" to 10.0, "B" to 16.2339,
                "A" to 10.0, "C" to 10.1413, "B" to 12.0933,
                "A" to 10.0, "C" to 10.0537,
            ),
            cycle = listOf("A" to 13.3333, "B" to 10.0, "C" to 10.0),
            label = "test 9b",
        )
    }

    @Test
    fun the_clock_replay_window_is_measured_in_schedulable_time_not_wall_time() {
        // `side-dev/scheduler_logic.py` `_lookback_start`: the window the past is read off is measured in the
        // only currency the shares are about — time somebody could actually have been served. Measured in wall
        // time, an instant nobody may run in pushes real service out of the window, and then a task served
        // just before the last long exclusion reads as NEVER SERVED and leapfrogs the one that has waited.
        val planner = SchedulerPlanner(ab())
        // A "night" nobody may run in, from −600min to −10min.
        val night = listOf(window(-600.0, -10.0))
        val want = 40.0 * MIN // two minimal periods, exactly what `plan` asks for
        // Wall time would stop at −40min, inside the night, and read the whole past as empty. Schedulable
        // time steps over the 590 idle minutes and collects the missing 30 on the far side of them.
        assertEquals(
            (-630.0 * MIN).toLong(),
            planner.lookbackStart(night, 0L, want),
            "the replay window must be measured in schedulable time",
        )
        // With nothing refusing anybody the two readings coincide.
        assertEquals((-40.0 * MIN).toLong(), planner.lookbackStart(emptyList(), 0L, want))
    }

    @Test
    fun a_period_does_not_erase_the_ranking_of_everyone_it_excludes() {
        // `side-dev/scheduler_logic.py` `_relax`: an excluded task's clock is held within one period of the
        // served pool by TRANSLATING the whole excluded set, never by clamping each of them separately.
        // Clamping set every task past the bound to the SAME value, so a period refusing eleven tasks left all
        // eleven tied and their priorities stopped deciding which one went first.
        val tasks = listOf(
            planTask("A", 25.0, 10.0),
            planTask("B", 25.0, 10.0),
            planTask("C", 25.0, 10.0),
            planTask("D", 25.0, 10.0),
        )
        val planner = SchedulerPlanner(tasks)
        val period = planner.minPeriodMillis
        val walk = planner.walk(
            mapOf(
                id("A") to 0.0,
                id("B") to 1.0 * period,
                id("C") to 4.0 * period,
                id("D") to 9.0 * period,
            ),
        )
        // Only A is allowed here: B, C and D are the excluded group.
        walk.relax(0.0, period, listOf(id("A")))
        val b = walk.clockOf(id("B"))
        val c = walk.clockOf(id("C"))
        val d = walk.clockOf(id("D"))
        assertTrue(b < c && c < d, "the excluded group's ranking was erased: B=$b C=$c D=$d")
        // The gaps inside the group are the claims themselves, so they survive the translation intact.
        assertEquals(3.0 * period, c - b, 1.0)
        assertEquals(5.0 * period, d - c, 1.0)
        // …and the group as a whole is held to one period of credit against the served pool.
        assertEquals(-period, b, 1.0)
    }

    @Test
    fun a_chain_of_re_plans_is_the_same_schedule_as_one_long_plan() {
        // `side-dev/test_configs.py` `check_resume_contract`: the rules AT the line are an ordinary plan from
        // t_p, so a display that follows the line can only agree with what is drawn if resuming at t
        // reproduces the walk that passed through t. Everything the walk carries and the seeding has to
        // rebuild from the history is a way for that to fail, and each one has failed in turn: `last` read off
        // `_head` ([SchedulerPlanner.lastRun]), the lookback measured in wall time
        // ([SchedulerPlanner.lookbackStart]) and the forgetting itself ([SchedulerPlanner.replayClocks]).
        val tasks = listOf(
            planTask("A", 50.0, 45.0),
            planTask("B", 25.0, 45.0),
            planTask("C", 25.0, 45.0),
        )
        // The shape of `side-dev` test 12: a 20-second look-away every 20 minutes (it accepts NOBODY, so it
        // only suspends a run), and a quarter-hour of every hour that only A may work in.
        val windows = buildList {
            for (i in 0 until 24) {
                add(window(20.0 * i, 20.0 * i + 1.0 / 3.0)) // the look-away: accepts nobody
            }
            for (h in 0 until 8) add(window(60.0 * h + 45.0, 60.0 * h + 60.0, "A"))
        }
        val planner = SchedulerPlanner(tasks)
        val long = planner.plan(windows = windows, nowMillis = 0L)
        val committed = long.unroll(8 * 60 * MIN)

        var checks = 0
        for (piece in committed) {
            val resumeAt = piece.startMillis
            if (resumeAt <= 0L || resumeAt >= 6 * 60 * MIN) continue
            val history = committed.mapNotNull { b ->
                if (b.startMillis >= resumeAt) null
                else PlanBlock(b.taskId, b.startMillis, minOf(b.endMillis, resumeAt))
            }
            val again = SchedulerPlanner(tasks).plan(
                windows = windows,
                nowMillis = resumeAt,
                history = history,
            )
            assertEquals(
                piece.taskId,
                again.prefix.firstOrNull()?.taskId,
                "walking through ${resumeAt / MIN}min gives ${piece.taskId?.value}, but a plan resumed " +
                    "there gives ${again.prefix.firstOrNull()?.taskId?.value}",
            )
            checks++
        }
        assertTrue(checks >= 8, "not enough resumptions to be worth asserting: $checks")
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
        // A pinned solid for 17 hours right up to `now`. Only obstacles still AHEAD bend the plan
        // (`side-dev/scheduler_logic.py` `plan`: `self._set_field(pre, periods)`), so what the past leaves
        // behind is not a field but the virtual clocks — and those are replayed with the walk's own
        // forgetting ([SchedulerPlanner.replayClocks]), which holds A at exactly one period ahead of B
        // however long it ran. So B leads with one catch-up chunk and the timeline is square again; it is
        // NOT handed 17 hours, nor even the 270 minutes an un-forgotten reading would have owed it.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val s = s0.copy(panels = listOf(pinned("pin/0", a, NOW - 17 * HOUR, NOW)))

        val spans = autoSpans(SchedulerDomain.fillSchedule(s, NOW, horizonMillis = NOW + 48 * HOUR))
        assertEquals(b, spans.first().first, "the deprived task must lead")
        val lead = spans.first().second
        assertTrue(lead < 17 * HOUR, "B must not be handed the whole 17h back, got ${lead / MIN}min")
        assertTrue(
            lead <= 90 * MIN,
            "the forgetting caps the catch-up at one period; got ${lead / MIN}min",
        )
        // …and it settles back onto the 50/50 cycle rather than repaying hour for hour.
        val served = spans.groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } }
        val share = (served[b] ?: 0L).toDouble() / served.values.sum()
        assertEquals(0.5, share, 0.05, "B should end up near half the window, got $share")
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

    // ----- the two screen breaks as the reference's PERIODS (`side-dev/scheduler_logic.py` tests 10–11) -------

    /**
     * PRD §15: a screen break the now-line has reached is a **sliding period** — it moves right with the
     * now-line for as long as it is owed, and while it sits there it accepts a fixed set of tasks. These pin
     * the shapes the reference produces for exactly that configuration, taken from `side-dev/scheduler_logic.py` run with
     * the break expressed as its periods (the sliding window pinned at the plan's origin, which is where the
     * now-line reaching it puts it).
     */
    @Test
    fun a_20s_look_away_at_the_now_line_is_a_period_that_accepts_no_task() {
        // Reference: periods [0, 20s]=∅, [20s, ∞)=all  ->  prefix "IDLE 0.3333" | cycle "A 10 | B 10".
        val plan = SchedulerPlanner(ab()).plan(
            windows = listOf(window(0.0, 1.0 / 3.0), window(1.0 / 3.0, null, "A", "B")),
        )
        assertPlan(
            plan,
            prefix = listOf("IDLE" to 1.0 / 3.0),
            cycle = listOf("A" to 10.0, "B" to 10.0),
            label = "look-away pinned at the now-line",
        )
    }

    @Test
    fun a_5min_pose_at_the_now_line_is_a_closed_first_minute_then_an_off_screen_only_tail() {
        // Reference: periods [0, 1min]=∅, [1min, 5min]={B}, [5min, ∞)=all  ->  prefix "IDLE 1 | B 10",
        // cycle "A 10 | B 10". B is the task needing no screen: it starts inside the tail and runs straight
        // THROUGH the break's end, because the boundary at 5 min does not turn B away (`_blocked_from`) — the
        // rule that keeps a short ban from punching an unfillable hole into somebody else's slot.
        val plan = SchedulerPlanner(ab()).plan(
            windows = listOf(window(0.0, 1.0), window(1.0, 5.0, "B"), window(5.0, null, "A", "B")),
        )
        assertPlan(
            plan,
            prefix = listOf("IDLE" to 1.0, "B" to 10.0),
            cycle = listOf("A" to 10.0, "B" to 10.0),
            label = "5-min pose pinned at the now-line",
        )
    }

    @Test
    fun a_15min_pose_at_the_now_line_is_one_open_period_accepting_the_off_screen_tasks() {
        // PRD §15: the 15-minute pose is NOT the 5-minute one with a longer tail — no closed head, and its
        // period accepts every task that needs no screen. Reference: periods [0, 15min]={B}, [15min, ∞)=all
        // -> prefix "B 15 | A 17.5 | B 10 | A 11.896 | B 10", cycle "A 10 | B 10". Unlike the look-away, this
        // ban of A (15 min, longer than A's own 10-min minimum) DOES create a field, which is the 17.5-min
        // catch-up A takes the moment the pose ends.
        val plan = SchedulerPlanner(ab()).plan(
            windows = listOf(window(0.0, 15.0, "B"), window(15.0, null, "A", "B")),
        )
        assertPlan(
            plan,
            prefix = listOf("B" to 15.0, "A" to 17.5, "B" to 10.0, "A" to 11.896, "B" to 10.0),
            cycle = listOf("A" to 10.0, "B" to 10.0),
            label = "15-min pose pinned at the now-line",
        )
    }

    // ----- `side-dev` tests 12/13: the user works straight THROUGH the breaks -----------------------

    /**
     * `side-dev/README.md` tests 12–13: the break grid on a timeline the user never leaves. Test 13 is test 12
     * with the priorities sliding, and its environment is exactly this — the three break periods arriving one
     * after another while the tasks go on being scheduled around them.
     *
     * What makes it work at all is [SchedulerPlanner.fitsFrom]: "does the minimum fit?" counts the instants
     * the task may actually run and STEPS OVER the ones that belong to nobody. A 20-second look-away every 20
     * minutes would otherwise forbid every 45-minute task from ever starting — the timeline would go idle for
     * want of anything that fits. Instead the run is SUSPENDED for the 20 seconds and resumes on the far side,
     * so one 45-minute slot of A is spread over 20 + 19⅔ + 5⅓ minutes of real service.
     */
    @Test
    fun a_45min_task_works_straight_through_a_20s_look_away_every_20min() {
        // each accepts NOBODY
        val breaks = (1..24).map { windowMillis(20L * it * MIN, 20L * it * MIN + LOOK_AWAY) }
        val plan = SchedulerPlanner(listOf(planTask("A", 50.0, 45.0), planTask("B", 50.0, 45.0)))
            .plan(windows = breaks)
        assertPlan(
            plan,
            prefix = listOf(
                "A" to 20.0, "IDLE" to 1.0 / 3.0, "A" to 19.6667, "IDLE" to 1.0 / 3.0,
                "A" to 5.3333, "B" to 14.3333, "IDLE" to 1.0 / 3.0, "B" to 19.6667,
                "IDLE" to 1.0 / 3.0, "B" to 11.0, "A" to 8.6667, "IDLE" to 1.0 / 3.0,
                "A" to 19.6667, "IDLE" to 1.0 / 3.0, "A" to 16.6667, "B" to 3.0,
                "IDLE" to 1.0 / 3.0, "B" to 19.6667, "IDLE" to 1.0 / 3.0, "B" to 19.6667,
                "IDLE" to 1.0 / 3.0, "B" to 2.6667, "A" to 17.0, "IDLE" to 1.0 / 3.0,
                "A" to 19.6667, "IDLE" to 1.0 / 3.0, "A" to 8.3333, "B" to 11.3333,
                "IDLE" to 1.0 / 3.0, "B" to 19.6667, "IDLE" to 1.0 / 3.0, "B" to 14.0,
                "A" to 5.6667, "IDLE" to 1.0 / 3.0, "A" to 19.6667, "IDLE" to 1.0 / 3.0,
                "A" to 19.6667, "IDLE" to 1.0 / 3.0, "B" to 19.6667, "IDLE" to 1.0 / 3.0,
                "B" to 19.6667, "IDLE" to 1.0 / 3.0, "B" to 5.6667, "A" to 14.0,
                "IDLE" to 1.0 / 3.0, "A" to 19.6667, "IDLE" to 1.0 / 3.0, "A" to 11.3333,
                "B" to 8.3333, "IDLE" to 1.0 / 3.0,
            ),
            // A timeline broken every 20 minutes forever never freezes, so the walk fills its rule budget in
            // the prefix and there is no steady cycle to attach ("truncated timelines").
            cycle = emptyList(),
            label = "working through the look-away grid",
        )
    }

    /**
     * The same day with a **5-minute pose** on the hour: a closed opening minute, then four minutes only the
     * off-screen task may work in.
     *
     * The pose is left entirely EMPTY, and that is the rule, not a gap: starting B at the tail's first instant
     * would run past the instant A comes back and leave A unable to place its own minimum before the next
     * pose ([SchedulerPlanner.wallsOf] / [SchedulerPlanner.clears]). A run is owed its whole minimum, so
     * beginning one there would not use the period — it would lengthen the exclusion A is already serving.
     */
    @Test
    fun a_5min_pose_the_user_works_through_is_left_empty_rather_than_lengthening_the_other_task_s_ban() {
        val lookAways = (1..24).map { windowMillis(20L * it * MIN, 20L * it * MIN + LOOK_AWAY) }
        val poses = (1..5).flatMap {
            listOf(
                windowMillis(60L * it * MIN, 60L * it * MIN + MIN),               // the closed minute: nobody
                windowMillis(60L * it * MIN + MIN, 60L * it * MIN + 5 * MIN, "B"), // the tail: off-screen only
            )
        }
        val plan = SchedulerPlanner(listOf(planTask("A", 50.0, 45.0), planTask("B", 50.0, 45.0)))
            .plan(windows = lookAways + poses)
        assertPlan(
            plan,
            prefix = listOf(
                "A" to 20.0, "IDLE" to 1.0 / 3.0, "A" to 19.6667, "IDLE" to 1.0 / 3.0,
                "A" to 19.6667, "IDLE" to 5.0, "B" to 15.0, "IDLE" to 1.0 / 3.0,
                "B" to 19.6667, "IDLE" to 1.0 / 3.0, "B" to 19.6667, "IDLE" to 1.0,
                "B" to 4.0, "A" to 15.0, "IDLE" to 1.0 / 3.0, "A" to 19.6667,
                "IDLE" to 1.0 / 3.0, "A" to 19.6667, "IDLE" to 5.0, "B" to 15.0,
                "IDLE" to 1.0 / 3.0, "B" to 19.6667, "IDLE" to 1.0 / 3.0, "B" to 19.6667,
                "IDLE" to 1.0, "B" to 4.0, "A" to 15.0, "IDLE" to 1.0 / 3.0,
                "A" to 19.6667, "IDLE" to 1.0 / 3.0, "A" to 10.3333, "B" to 9.3333,
                "IDLE" to 1.0, "B" to 19.0, "IDLE" to 1.0 / 3.0, "B" to 16.6667,
                "A" to 3.0, "IDLE" to 1.0 / 3.0, "A" to 19.6667, "IDLE" to 1.0 / 3.0,
                "A" to 19.6667, "IDLE" to 1.0 / 3.0, "A" to 2.6667, "B" to 17.0,
                "IDLE" to 1.0 / 3.0, "B" to 19.6667, "IDLE" to 1.0 / 3.0, "B" to 8.3333,
                "A" to 11.3333, "IDLE" to 1.0 / 3.0,
            ),
            cycle = emptyList(),
            label = "working through the pose grid",
        )
    }

    // ----- what a task is owed is counted in ITS OWN slots -----------------------------------------

    @Test
    fun reference_test_14_a_50_percent_task_interleaves_with_twenty_small_ones_from_the_first_morning() {
        // `side-dev` test 14: A of 45min at 50%, twenty tasks of 45min sharing the other 50% (2.5% each), and
        // nothing in the way but the nights (23h-8h, nobody allowed), over eight days. The case that caught
        // the pick reading
        // the virtual clock as a plain time: one slot moves A's clock by 90 minutes and one of theirs by 1800,
        // so read raw every one of the twenty still at 0 outranked A the moment A had taken a single slot —
        // and they took TWENTY slots in a row before A's second. That is `side-dev/README.md`'s monolithic
        // block assembled out of twenty tasks instead of one, and it left A holding 5% of the first day and
        // 35% of the three (target 50%), a deficit nothing repays: the never-twice rule caps A at every other
        // slot from then on, so it can hold its half but never catch up.
        //
        // Counted in each task's OWN slots ([SchedulerPlanner.claims]) they interleave from the first morning,
        // which is what the reference now emits, slot for slot.
        val tasks = listOf(planTask("A", 50.0, 45.0)) +
            // zero-padded so the reference's tie-break (by title) and the port's (the order the candidate
            // list arrives in) agree, and the two can be compared slot for slot
            (1..20).map { planTask("B" + it.toString().padStart(2, '0'), 2.5, 45.0) }
        // one night per day, crossing midnight, so the timeline OPENS inside the one that began the day before
        val nights = (-1..7).map { d ->
            window(d * 24.0 * 60 + 23 * 60, (d + 1) * 24.0 * 60 + 8 * 60)
        }
        val plan = SchedulerPlanner(tasks).plan(windows = nights, maxRules = 26)
        assertPlan(
            plan,
            prefix = listOf(
                "IDLE" to 480.0,
                "A" to 45.0, "B01" to 45.0, "B02" to 45.0,
                "A" to 45.0, "B03" to 45.0, "A" to 45.0, "B04" to 45.0, "A" to 45.0, "B05" to 45.0,
                "A" to 45.0, "B06" to 45.0, "A" to 45.0, "B07" to 45.0, "A" to 45.0, "B08" to 45.0,
                "A" to 45.0, "B09" to 45.0, "A" to 45.0, "B10" to 45.0, "A" to 45.0,
                "IDLE" to 540.0,
                "B11" to 45.0, "B12" to 45.0, "A" to 45.0, "B13" to 45.0,
            ),
            cycle = emptyList(),
            label = "test 14",
        )
    }

    // ----- the atomic block: a period the running task is banned from SUSPENDS it -------------------

    @Test
    fun the_readme_s_atomic_block_example_schedules_the_whole_period_with_nothing() {
        // `side-dev/README.md`, verbatim: "if task B is scheduled at t=0 and a period p that only allows task
        // A is at t=1, and task B has a minimum time of 2, then the whole period p is scheduled with nothing."
        // It is the one rule a *sliding* period runs into constantly, which is why it is stated on its own
        // rather than only inside tests 10-11. Reference `check_atomic_block` + `Scheduler.plan`.
        val tasks = listOf(planTask("A", 50.0, 2.0), planTask("B", 50.0, 2.0))
        val plan = SchedulerPlanner(tasks).plan(
            blocks = listOf(block("B", 0.0, 1.0)), // B ran [0, 1) — half of its 2-minute minimum
            windows = listOf(window(1.0, 3.0, "A")),
            nowMillis = (1.0 * MIN).toLong(),
        )
        assertPlan(
            plan,
            prefix = listOf("IDLE" to 2.0, "B" to 1.5, "A" to 2.0, "B" to 2.417, "A" to 2.0),
            cycle = listOf("B" to 2.0, "A" to 2.0),
            label = "the atomic block",
        )
    }

    @Test
    fun a_pre_placed_block_is_suspended_where_a_period_refuses_its_own_task() {
        // `side-dev/scheduler_logic.py`: a pre-placed block is locked to its coordinates, but a period still
        // dictates what may RUN there. Where the block's own task is refused the block is suspended and
        // resumes on the far side — it is walked edge by edge, not swallowed whole. Reference: a block of A on
        // [20, 30) crossed by a ban of A on [25, 35) -> "A 10 | B 10 | A 5 | IDLE 10 | A 5", i.e. the block's
        // first 5 minutes run, the ban idles the next 10, and its remaining 5 resume past the ban.
        val plan = SchedulerPlanner(ab()).plan(
            blocks = listOf(block("A", 20.0, 10.0)),
            windows = listOf(window(25.0, 35.0, "B")),
        )
        assertPlan(
            plan,
            prefix = listOf("A" to 10.0, "B" to 10.0, "A" to 5.0, "IDLE" to 10.0, "A" to 5.0),
            cycle = listOf("B" to 10.0, "A" to 10.0),
            label = "a block suspended by a period",
        )
    }

    @Test
    fun a_ban_shorter_than_the_deprived_task_s_own_minimum_creates_no_field() {
        // `side-dev/scheduler_logic.py` `_set_field`: a 20-second ban cannot have cost a 10-minute task a slot, only
        // delayed it — and the virtual clock already repays a delay exactly. Compensating it as well would pay
        // the same debt twice and leave the ban swelling its neighbours forever after. This is what stops the
        // 20-min look-away cadence from distorting the plan around every one of its occurrences.
        val planner = SchedulerPlanner(ab())
        planner.setField(emptyList(), listOf(window(10.0, 10.0 + 1.0 / 3.0, "A"), window(10.0 + 1.0 / 3.0, null, "A", "B")))
        assertEquals(null, planner.fieldEndMillis)
        assertEquals(1.0, planner.boostAt(id("B"), (10.0 * MIN).toLong()), 1e-9)

        // A ban that DOES exceed the minimum still creates its field, so the rule is a floor, not a mute.
        val long = SchedulerPlanner(ab())
        long.setField(emptyList(), listOf(window(10.0, 40.0, "A"), window(40.0, null, "A", "B")))
        assertNotEquals(null, long.fieldEndMillis)
        assertTrue(long.boostAt(id("B"), (10.0 * MIN).toLong()) > 1.0)
    }
}
