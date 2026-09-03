package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ForcedTaskStart
import org.example.project.scheduler.model.ForcedTaskSwitch
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock

/**
 * PRD §13 **"start this task now"** — the task cell's right-click menu: the named task is what the plan places
 * at the now-line.
 *
 * The mirror image of [ForcedTaskSwitchTest], and what is pinned here is the same contract read from the other
 * end: the request changes the FIRST slot and nothing else (the named task pays for its time in its own clock,
 * so the schedule after it is the schedule the walk would have gone on with), it survives the re-plans that
 * happen while it is still outstanding, and it stops being honoured — and is dropped from the state — the
 * moment the plan has genuinely moved on to another task.
 */
class ForcedTaskStartTest {

    private val MIN = 60_000L
    private val T0 = 1_700_000_000_000L

    private class FixedClock(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    /** Runs [body] with the reducer's clock pinned at [now] — the instant the request is stamped with. */
    private fun withClock(now: Long, body: () -> Unit) {
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = FixedClock(now)
        try {
            body()
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    /** Two equal-priority sibling tasks A and B under "main"; A wins the §9 tie-break (higher, then title). */
    private fun stateWithTwoTasks(): Triple<SchedulerState, TaskId, TaskId> {
        var s = SchedulerState.empty()
        val root = s.rootListId
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[root]!!.cellIds[0], "A"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[root]!!.cellIds[1], "B"))
        val a = s.tasks.keys.first { s.tasks[it]!!.title == "A" }
        val b = s.tasks.keys.first { s.tasks[it]!!.title == "B" }
        return Triple(s, a, b)
    }

    private fun auto(id: String, taskId: TaskId, start: Long, end: Long) =
        TaskPanel(id, taskId, "x", start, end, pinned = false, auto = true)

    /** The task the now-line is on — the one thing every case below is about. */
    private fun taskAt(state: SchedulerState, nowMillis: Long): TaskId? =
        SchedulerDomain.taskAtNowLine(state, nowMillis)

    // ----- the pick ---------------------------------------------------------------------------

    @Test
    fun start_this_task_now_puts_the_named_task_at_the_now_line() {
        val (s0, a, b) = stateWithTwoTasks()
        // A is the task the plain re-plan would keep (see ForcedTaskSwitchTest's baseline); the user asks for B.
        val state = s0.copy(panels = listOf(auto("auto/0", a, T0 - 10 * MIN, T0 + 50 * MIN)))
        withClock(T0) {
            val started = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(b))
            assertEquals(b, taskAt(started, T0))
            assertEquals(ForcedTaskStart(b, T0), started.forcedStart)
        }
    }

    @Test
    fun asking_for_the_task_already_running_keeps_it_there() {
        // The other half of "the plan places what was asked for": naming the task the now-line is already on
        // is a request the plan can only answer by leaving it there.
        val (s0, a, _) = stateWithTwoTasks()
        val state = s0.copy(panels = listOf(auto("auto/0", a, T0 - 10 * MIN, T0 + 50 * MIN)))
        withClock(T0) {
            val started = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(a))
            assertEquals(a, taskAt(started, T0))
        }
    }

    @Test
    fun only_the_first_slot_is_named() {
        // The request is one slot, not a pin: B takes the now-line and pays for it, so the walk goes on and A
        // — the starved one by then — comes back after it.
        val (s0, a, b) = stateWithTwoTasks()
        val state = s0.copy(panels = listOf(auto("auto/0", a, T0 - 10 * MIN, T0 + 50 * MIN)))
        withClock(T0) {
            val started = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(b))
            // As in [ForcedTaskSwitchTest]: the first slot PLACED is the first one at or after the line. A's
            // elapsed head behind it is the frozen past, and the request is also what stops A's unfinished
            // chunk from resuming into this slot.
            val autos = started.panels.filter { it.auto && it.startEpochMillis >= T0 }.sortedBy { it.startEpochMillis }
            assertEquals(b, autos.first().taskId)
            assertNotNull(autos.firstOrNull { it.taskId == a })
        }
    }

    @Test
    fun a_parent_task_is_nothing_to_start() {
        // §9 places the LEAVES: a task with a child is a grouping, so the request would be unanswerable.
        val (s0, a, _) = stateWithTwoTasks()
        val parentCell = s0.cells.values.first { it.taskId == a }.id
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.ToggleExpand(parentCell))
        val childList = s.tasks[a]!!.childListId
        assertNotNull(childList)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[childList]!!.cellIds.first(), "A1"))
        withClock(T0) {
            assertSame(s, SchedulerReducer.reduce(s, SchedulerIntent.ForceTaskStart(a)))
            assertNull(s.forcedStart)
        }
    }

    @Test
    fun a_task_no_cell_points_at_is_nothing_to_start() {
        val (s0, a, _) = stateWithTwoTasks()
        val orphan = s0.copy(cells = s0.cells.mapValues { (_, c) -> if (c.taskId == a) c.copy(taskId = null) else c })
        withClock(T0) {
            assertSame(orphan, SchedulerReducer.reduce(orphan, SchedulerIntent.ForceTaskStart(a)))
        }
    }

    // ----- how long the request is outstanding ------------------------------------------------

    @Test
    fun the_request_is_honoured_by_every_replan_until_the_plan_moves_on() {
        val (s0, a, b) = stateWithTwoTasks()
        // B was asked for and has been running since; nobody else has been served past the request yet, so a
        // re-plan in between (a rule change, the hourly staleness refresh) must not hand the user another task.
        // B's effort is a WHOLE minimum (45 min): a chunk still short of its minimum is one the walk is in the
        // middle of and resumes rather than re-picks (`side-dev/scheduler.py` `Walk.run`'s `pending`), which
        // would keep B on the line for reasons that have nothing to do with the request and make the control
        // below say nothing.
        val state =
            s0.copy(
                forcedStart = ForcedTaskStart(b, T0),
                tasks = s0.tasks + (b to s0.tasks[b]!!.copy(record = listOf(TaskTimeRange(T0, T0 + 45 * MIN)))),
            )
        val replanned = SchedulerReducer.reduce(state, SchedulerIntent.RefreshSchedule(T0 + 45 * MIN))
        assertEquals(b, taskAt(replanned, T0 + 45 * MIN))
        assertEquals(ForcedTaskStart(b, T0), replanned.forcedStart)
        // Without the request the same state is A's — B has just had a full turn, so A is the starved one.
        val plain = SchedulerReducer.reduce(state.copy(forcedStart = null), SchedulerIntent.RefreshSchedule(T0 + 45 * MIN))
        assertEquals(a, taskAt(plain, T0 + 45 * MIN))
    }

    @Test
    fun the_request_stops_being_honoured_once_another_task_has_been_served_past_it() {
        val (_, a, b) = stateWithTwoTasks()
        // A has genuinely worked past the request: the plan moved on, so the request has been answered and B
        // is an ordinary candidate again.
        assertNull(
            SchedulerDomain.liveForcedStartTask(
                ForcedTaskStart(b, T0),
                listOf(PlanBlock(a, T0, T0 + 30 * MIN)),
                T0 + 30 * MIN,
            ),
        )
        // The named task's OWN work answers nothing — that is the task the user asked to be doing.
        assertEquals(
            b,
            SchedulerDomain.liveForcedStartTask(
                ForcedTaskStart(b, T0),
                listOf(PlanBlock(b, T0, T0 + 30 * MIN)),
                T0 + 30 * MIN,
            ),
        )
    }

    @Test
    fun a_request_stamped_in_the_future_is_not_yet_live() {
        val (_, _, b) = stateWithTwoTasks()
        assertNull(SchedulerDomain.liveForcedStartTask(ForcedTaskStart(b, T0 + MIN), emptyList(), T0))
        assertEquals(b, SchedulerDomain.liveForcedStartTask(ForcedTaskStart(b, T0), emptyList(), T0))
    }

    @Test
    fun the_advance_tick_drops_a_spent_request() {
        val (s0, a, b) = stateWithTwoTasks()
        val state =
            s0.copy(
                forcedStart = ForcedTaskStart(b, T0),
                panels = listOf(auto("auto/0", a, T0, T0 + 30 * MIN)),
            )
        // A's panel has elapsed: its record now runs past the request, so the plan has moved on from B.
        val advanced = SchedulerReducer.reduce(state, SchedulerIntent.AdvanceSchedule(T0 + 30 * MIN))
        assertNull(advanced.forcedStart)
    }

    @Test
    fun the_advance_tick_keeps_an_outstanding_request() {
        val (s0, _, b) = stateWithTwoTasks()
        val state =
            s0.copy(
                forcedStart = ForcedTaskStart(b, T0),
                panels = listOf(auto("auto/0", b, T0, T0 + 30 * MIN)),
            )
        // Only the requested task's own work is banked: nothing has moved on from it.
        val advanced = SchedulerReducer.reduce(state, SchedulerIntent.AdvanceSchedule(T0 + 30 * MIN))
        assertEquals(ForcedTaskStart(b, T0), advanced.forcedStart)
    }

    // ----- the two markers together -----------------------------------------------------------

    @Test
    fun asking_for_a_task_clears_an_outstanding_refusal_of_that_same_task() {
        val (s0, a, _) = stateWithTwoTasks()
        // "Switch task" refused A a moment ago; the user now asks for A explicitly. Leaving both standing
        // would have the fill place A and go on refusing it.
        val state = s0.copy(forcedSwitch = ForcedTaskSwitch(a, T0), panels = emptyList())
        withClock(T0 + MIN) {
            val started = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(a))
            assertNull(started.forcedSwitch)
            assertEquals(a, taskAt(started, T0 + MIN))
        }
    }

    @Test
    fun a_refusal_of_another_task_is_left_standing() {
        val (s0, a, b) = stateWithTwoTasks()
        val state = s0.copy(forcedSwitch = ForcedTaskSwitch(a, T0), panels = emptyList())
        withClock(T0 + MIN) {
            val started = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskStart(b))
            assertEquals(ForcedTaskSwitch(a, T0), started.forcedSwitch)
        }
    }

    // ----- persistence ------------------------------------------------------------------------

    @Test
    fun the_request_round_trips_and_a_payload_without_it_decodes_to_none() {
        val (s0, _, b) = stateWithTwoTasks()
        val state = s0.copy(forcedStart = ForcedTaskStart(b, T0))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(state))
        assertNotNull(decoded)
        assertEquals(ForcedTaskStart(b, T0), decoded.forcedStart)

        // A payload written before the menu entry existed: nothing asked for, which is how it always behaved.
        val legacy = SchedulerStateCodec.decode("""{"rootListId":"list/main","lists":[],"cells":[],"tasks":[]}""")
        assertNotNull(legacy)
        assertNull(legacy.forcedStart)
    }
}
