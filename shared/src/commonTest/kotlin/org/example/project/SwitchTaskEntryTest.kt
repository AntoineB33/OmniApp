package org.example.project

import org.example.project.scheduler.domain.PeriodKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock
import org.example.project.ui.PlacedRecord

/**
 * PRD §7 **the switch entry**: both ways of saying "I am doing this now" — the picker
 * (`Ctrl+Shift+Alt+T`, [SchedulerIntent.ForceTaskStart]) and the blind switch (`Ctrl+Shift+Alt+Z`,
 * [SchedulerIntent.ForceTaskSwitch]) — lay an **epsilon-long block the user authored** at the now-line and
 * then re-plan around it.
 *
 * Four claims, and they are why the feature is one line of code in two reducers rather than a mechanism:
 *  - the block is an ordinary hand-placed panel, so the calendar draws it with the blue outline and the check
 *    outline **without being told anything about chords** ([SchedulerDomain.panelOutline]);
 *  - it is PINNED, so the fill that runs immediately after plans around it instead of over it — a press
 *    undone by the re-plan it asks for would be no press at all;
 *  - it says *this task, from here* and **nothing about how long**: the length is the scheduler's answer, and
 *    with nothing restricting the timeline the soft minimum-execution-time goal makes the run at least the
 *    task's own minimum;
 *  - that length comes from the REQUEST riding with the seed, never from the seed growing — a pre-placed
 *    block is committed service the walk steps over, and stepping over it sets the walk's `last`, so without
 *    the request the very next pick would refuse the task just started. The last test below pins that.
 */
class SwitchTaskEntryTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val T0 = 1_700_000_000_000L

    private class FixedClock(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    private fun withClock(now: Long, body: () -> Unit) {
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = FixedClock(now)
        try {
            body()
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    private fun stateWithTasks(vararg names: String): Pair<SchedulerState, List<TaskId>> {
        var s = SchedulerState.empty()
        names.forEachIndexed { i, name ->
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[i], name))
        }
        return s to names.map { name -> s.tasks.keys.first { s.tasks[it]!!.title == name } }
    }

    /** The state as the app has it while the user is looking at a real plan — panels with the fill's rules. */
    private fun planned(state: SchedulerState, nowMillis: Long): SchedulerState =
        state.copy(
            panels = SchedulerDomain.fillSchedule(state, nowMillis, horizonMillis = nowMillis + 12 * HOUR),
        )

    /** The switch entry: the one hand-placed task panel at the now-line. */
    private fun entryAt(state: SchedulerState, nowMillis: Long): TaskPanel? =
        state.panels.firstOrNull {
            SchedulerDomain.isUserPlaced(it) && it.taskId != null && it.startEpochMillis == nowMillis
        }

    // ----- the block the chords lay -------------------------------------------------------------

    @Test
    fun the_picker_lays_an_epsilon_block_at_the_now_line_on_the_task_it_named() {
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val state = planned(s0, T0)
        withClock(T0) {
            val after = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(b))
            val entry = assertNotNull(entryAt(after, T0), "the press laid no entry")
            assertEquals(b, entry.taskId)
            assertEquals("B", entry.title)
            assertEquals(
                T0 to T0 + SchedulerDomain.SWITCH_ENTRY_MILLIS,
                entry.startEpochMillis to entry.endEpochMillis,
            )
            // ... and the task the line was on is the one that was left.
            assertEquals(b, SchedulerDomain.taskAtNowLine(after, T0))
            assertEquals(a, SchedulerDomain.taskAtNowLine(state, T0))
        }
    }

    @Test
    fun the_blind_switch_lays_the_same_block_on_the_task_the_plan_hands_the_line_to() {
        // `Ctrl+Shift+Alt+Z` names no task: the one it has selected is the one the rules the last fill
        // returned name as the alternative at the line (`TaskPanel.alternativeTaskId`).
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val state = planned(s0, T0)
        assertEquals(a, SchedulerDomain.taskAtNowLine(state, T0))
        assertEquals(b, SchedulerDomain.alternativeTaskAt(state.panels, T0))

        val after = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskSwitch(T0))
        val entry = assertNotNull(entryAt(after, T0))
        assertEquals(b, entry.taskId)
        assertEquals(T0 + SchedulerDomain.SWITCH_ENTRY_MILLIS, entry.endEpochMillis)
        assertEquals(a, after.forcedSwitch?.taskId, "the refusal of the task left behind still stands")
    }

    @Test
    fun the_blind_switch_still_finds_the_task_when_the_panels_carry_no_rules_yet() {
        // `alternativeTaskId` is derived and never persisted, so the panels of a payload just loaded name
        // nobody. The press must still work — the same answer, reached by re-planning with the refusal
        // standing and reading what the fill put at the line.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val state = planned(s0, T0).let { it.copy(panels = it.panels.map { p -> p.copy(alternativeTaskId = null) }) }
        assertNull(SchedulerDomain.alternativeTaskAt(state.panels, T0))

        val after = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskSwitch(T0))
        val entry = assertNotNull(entryAt(after, T0), "no entry was laid without the fill's rules to read")
        assertEquals(b, entry.taskId)
        assertEquals(a, after.forcedSwitch?.taskId)
    }

    @Test
    fun a_switch_with_nobody_to_hand_the_line_to_lays_no_entry() {
        // The walk's own escape: refusing the sole candidate would leave the timeline empty, so it keeps
        // running — and there is then no task the user has switched TO, so there is nothing to state.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        val state = planned(s, T0)

        val after = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskSwitch(T0))
        assertNull(entryAt(after, T0))
        assertEquals(solo, SchedulerDomain.taskAtNowLine(after, T0))
    }

    // ----- what the calendar then draws ---------------------------------------------------------

    @Test
    fun the_entry_is_drawn_as_a_block_the_user_placed_the_blue_outline() {
        val (s0, ids) = stateWithTasks("A", "B")
        val state = planned(s0, T0)
        withClock(T0) {
            val after = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(ids[1]))
            val entry = assertNotNull(entryAt(after, T0))
            // The two facts the calendar reads, and it reads nothing else about the press.
            assertTrue(SchedulerDomain.isUserPlaced(entry), "no blue outline: the app would own this block")
            assertTrue(entry.pins.existence)

            // A new way of putting a block on the timeline reaches the drawing through the one outline
            // question, never through a flag of its own.
            assertEquals(SchedulerDomain.PanelOutline.User, placed(entry).outline)
        }
    }

    /** The block as the calendar builds it from the panel — only the fields the outline's rule reads. */
    private fun placed(panel: TaskPanel) = PlacedRecord(
        title = panel.title,
        startHour = 9f,
        endHour = 10f,
        scheduled = false,
        outline = SchedulerDomain.panelOutline(panel),
        noScreen = panel.noScreen,
        inactivity = panel.inactivity,
        pins = panel.pins,
    )

    // ----- and what the scheduler then does around it -------------------------------------------

    @Test
    fun the_fill_plans_around_the_entry_and_never_over_it() {
        val (s0, ids) = stateWithTasks("A", "B")
        val state = planned(s0, T0)
        withClock(T0) {
            val after = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(ids[1]))
            val seedEnd = T0 + SchedulerDomain.SWITCH_ENTRY_MILLIS
            val overlapping = after.panels.filter {
                it.auto && it.startEpochMillis < seedEnd && it.endEpochMillis > T0
            }
            assertTrue(overlapping.isEmpty(), "the re-plan laid ${overlapping.size} auto panel(s) over the entry")
        }
    }

    @Test
    fun the_task_carries_on_past_the_seed_for_at_least_its_minimum() {
        // The length of the run is the SCHEDULER's answer, not the press's: the seed says only "this task,
        // from here". With nothing restricting the timeline, the soft minimum-execution-time goal
        // (`PlanWalk.chunkMillis` floors a chunk at the task's minimum) makes that run a usable block.
        val (s0, ids) = stateWithTasks("A", "B")
        val (_, b) = ids
        val minimum = s0.tasks.getValue(b).minimumMinutes * MIN
        val state = planned(s0, T0)
        withClock(T0) {
            val after = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(b))
            val seedEnd = T0 + SchedulerDomain.SWITCH_ENTRY_MILLIS
            val run = assertNotNull(
                after.panels.filter { it.auto && it.startEpochMillis >= seedEnd }
                    .minByOrNull { it.startEpochMillis },
            )
            assertEquals(b, run.taskId)
            assertEquals(seedEnd, run.startEpochMillis, "the fill left a gap after the seed")
            assertTrue(
                run.endEpochMillis - T0 >= minimum,
                "the run is ${(run.endEpochMillis - T0) / MIN} min, short of the ${minimum / MIN} min minimum",
            )
        }
    }

    @Test
    fun the_seed_alone_would_hand_the_line_straight_back() {
        // Why the request rides with the seed. A pre-placed block is committed service the walk steps OVER
        // (`fillSchedule`'s `futureBlocks`): stepping over it charges the task AND sets the walk's `last`, so
        // the never-twice-in-a-row rule refuses the very task just started, and the starved one takes the
        // slot a second later. The resume rule that continues an unfinished chunk cannot save it either — it
        // reads the recorded PAST, and a block at the line is not in it.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val seed = TaskPanel(
            id = "panel/0",
            taskId = b,
            title = "B",
            startEpochMillis = T0,
            endEpochMillis = T0 + SchedulerDomain.SWITCH_ENTRY_MILLIS,
            pinned = true,
            auto = false,
            pins = PanelPins(existence = true),
        )
        val filled =
            SchedulerDomain.fillSchedule(s0.copy(panels = listOf(seed)), T0, horizonMillis = T0 + 12 * HOUR)
        val next = assertNotNull(
            filled.filter { it.auto && it.startEpochMillis >= T0 + SchedulerDomain.SWITCH_ENTRY_MILLIS }
                .minByOrNull { it.startEpochMillis },
        )
        assertEquals(a, next.taskId, "the seed grew on its own — this test's premise is stale")
    }

    @Test
    fun a_restrictive_period_is_what_keeps_the_run_short() {
        // The other half of the rule: the minimum is a SOFT goal and the timeline is what it yields to. A
        // period nobody may run in, five minutes past the line, ends the run there.
        val (s0, ids) = stateWithTasks("A", "B")
        val (_, b) = ids
        var state = planned(s0, T0)
        state = SchedulerReducer.reduce(state, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_TASK, T0 + 5 * MIN, T0 + 2 * HOUR))
        withClock(T0) {
            val after = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(b))
            val run = assertNotNull(
                after.panels.filter { it.auto && it.taskId == b && it.startEpochMillis < T0 + 5 * MIN }
                    .maxByOrNull { it.endEpochMillis },
                "nothing was placed between the seed and the grey period",
            )
            assertEquals(T0 + 5 * MIN, run.endEpochMillis, "the run ran into the grey period")
        }
    }

    @Test
    fun the_entry_is_one_undo_away() {
        // It is a hand-placed block like any other, so it is a Calendar history unit — a chord struck by
        // accident is undone the way a mis-drawn block is.
        val (s0, ids) = stateWithTasks("A", "B")
        val state = planned(s0, T0)
        withClock(T0) {
            val after = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(ids[1]))
            val unit = after.histories.forCategory(HistoryCategory.Calendar).units.lastOrNull()
            assertEquals("Switch task", assertNotNull(unit).delta.label)
        }
    }
}
