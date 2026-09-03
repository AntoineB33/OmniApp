package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ForcedTaskSwitch
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §7 **"Switch task"** — the lateral-menu button and its system-wide `Ctrl+Shift+Alt+Z` chord: the task
 * the now-line sits on is refused there, so the plan starts a *different* one from now.
 *
 * What is pinned here is the whole of the feature's contract: the refusal changes the pick and nothing else,
 * it is stated as the walk's `last` (so a task nothing can replace still runs rather than the timeline being
 * left empty), it survives the re-plans that happen while it is still outstanding, and it stops being
 * honoured — and is dropped from the state — the moment another task has actually been served past it.
 */
class ForcedTaskSwitchTest {

    private val MIN = 60_000L
    private val T0 = 1_700_000_000_000L

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
    fun a_plain_replan_keeps_the_task_the_now_line_is_on() {
        // The baseline the button is measured against: with A and B tied, the §9 tie-break picks A, so a
        // re-plan at the now-line leaves the user on the very task they are already doing.
        val (s0, a, _) = stateWithTwoTasks()
        val state = s0.copy(panels = listOf(auto("auto/0", a, T0 - 10 * MIN, T0 + 50 * MIN)))
        val replanned = SchedulerReducer.reduce(state, SchedulerIntent.RefreshSchedule(T0))
        assertEquals(a, taskAt(replanned, T0))
    }

    @Test
    fun switch_task_starts_a_different_task_from_now() {
        val (s0, a, b) = stateWithTwoTasks()
        val state = s0.copy(panels = listOf(auto("auto/0", a, T0 - 10 * MIN, T0 + 50 * MIN)))
        val switched = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskSwitch(T0))
        assertEquals(b, taskAt(switched, T0))
        assertEquals(ForcedTaskSwitch(a, T0), switched.forcedSwitch)
    }

    @Test
    fun the_refused_task_is_free_again_from_the_second_slot() {
        // The refusal is one slot, not a ban: A keeps its share, so it comes back as soon as B has had a turn.
        val (s0, a, _) = stateWithTwoTasks()
        val state = s0.copy(panels = listOf(auto("auto/0", a, T0 - 10 * MIN, T0 + 50 * MIN)))
        val switched = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskSwitch(T0))
        // The first slot the fill PLACES, which is the first one at or after the line — A's elapsed head
        // behind it is the frozen past (`side-dev/README.md`), not something this fill chose.
        val autos = switched.panels.filter { it.auto && it.startEpochMillis >= T0 }.sortedBy { it.startEpochMillis }
        assertNotEquals(a, autos.first().taskId)
        assertNotNull(autos.firstOrNull { it.taskId == a })
    }

    @Test
    fun a_task_nothing_can_replace_still_runs() {
        // The escape the walk's `last` already has: refusing the only candidate would leave the timeline
        // empty, which serves nobody — so a sole task keeps running and the press simply changes nothing.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        val state = s.copy(panels = listOf(auto("auto/0", solo, T0 - 10 * MIN, T0 + 50 * MIN)))
        val switched = SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskSwitch(T0))
        assertEquals(solo, taskAt(switched, T0))
    }

    @Test
    fun a_now_line_on_no_task_is_nothing_to_switch_away_from() {
        val (s0, _, _) = stateWithTwoTasks()
        // No panel under the now-line: there is no task here, so the press must not record a refusal.
        val state = s0.copy(panels = emptyList())
        assertSame(state, SchedulerReducer.reduce(state, SchedulerIntent.ForceTaskSwitch(T0)))
        assertNull(state.forcedSwitch)
    }

    // ----- how long the refusal is outstanding ------------------------------------------------

    @Test
    fun the_refusal_is_honoured_by_every_replan_until_it_is_granted() {
        val (s0, a, b) = stateWithTwoTasks()
        // A's own work stops at the refusal; nobody else has been served past it yet.
        val state =
            s0.copy(
                forcedSwitch = ForcedTaskSwitch(a, T0),
                tasks = s0.tasks + (a to s0.tasks[a]!!.copy(record = listOf(TaskTimeRange(T0 - 30 * MIN, T0)))),
            )
        val replanned = SchedulerReducer.reduce(state, SchedulerIntent.RefreshSchedule(T0 + 5 * MIN))
        assertEquals(b, taskAt(replanned, T0 + 5 * MIN))
        assertEquals(ForcedTaskSwitch(a, T0), replanned.forcedSwitch)
    }

    @Test
    fun the_refusal_stops_being_honoured_once_another_task_has_been_served_past_it() {
        val (s0, a, b) = stateWithTwoTasks()
        // Same shape, except B has now genuinely worked past the refusal: the request was granted, so A is an
        // ordinary candidate again — and, being the starved one, the one the walk picks.
        //
        // B's effort is a WHOLE minimum (45 min), not a part of one: a chunk still short of its minimum is one
        // the walk is in the middle of and resumes rather than re-picks (`side-dev/scheduler.py` `Walk.run`'s
        // `pending`), so a 30-minute record would hand the line back to B for the missing quarter of an hour
        // and say nothing about whether the refusal is still standing.
        val state =
            s0.copy(
                forcedSwitch = ForcedTaskSwitch(a, T0),
                tasks = s0.tasks + (b to s0.tasks[b]!!.copy(record = listOf(TaskTimeRange(T0, T0 + 45 * MIN)))),
            )
        val filled = SchedulerDomain.fillSchedule(state, T0 + 45 * MIN, horizonMillis = T0 + 6 * 60 * MIN)
        assertEquals(a, taskAt(state.copy(panels = filled), T0 + 45 * MIN))
    }

    @Test
    fun a_refusal_stamped_in_the_future_is_not_yet_live() {
        val (s0, a, _) = stateWithTwoTasks()
        assertNull(SchedulerDomain.liveForcedSwitchTask(ForcedTaskSwitch(a, T0 + MIN), emptyList(), T0))
        assertEquals(a, SchedulerDomain.liveForcedSwitchTask(ForcedTaskSwitch(a, T0), emptyList(), T0))
    }

    @Test
    fun the_advance_tick_drops_a_spent_refusal() {
        val (s0, a, b) = stateWithTwoTasks()
        val state =
            s0.copy(
                forcedSwitch = ForcedTaskSwitch(a, T0),
                panels = listOf(auto("auto/0", b, T0, T0 + 30 * MIN)),
            )
        // B's panel has elapsed: its record now runs past the refusal, so the marker has been honoured.
        val advanced = SchedulerReducer.reduce(state, SchedulerIntent.AdvanceSchedule(T0 + 30 * MIN))
        assertNull(advanced.forcedSwitch)
    }

    @Test
    fun the_advance_tick_keeps_an_outstanding_refusal() {
        val (s0, a, _) = stateWithTwoTasks()
        val state =
            s0.copy(
                forcedSwitch = ForcedTaskSwitch(a, T0),
                panels = listOf(auto("auto/0", a, T0 - 30 * MIN, T0)),
            )
        // Only A's own elapsed work is banked, and it stops at the refusal: nothing was granted.
        val advanced = SchedulerReducer.reduce(state, SchedulerIntent.AdvanceSchedule(T0 + MIN))
        assertEquals(ForcedTaskSwitch(a, T0), advanced.forcedSwitch)
    }

    // ----- persistence ------------------------------------------------------------------------

    @Test
    fun the_refusal_round_trips_and_a_payload_without_it_decodes_to_none() {
        val (s0, a, _) = stateWithTwoTasks()
        val state = s0.copy(forcedSwitch = ForcedTaskSwitch(a, T0))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(state))
        assertNotNull(decoded)
        assertEquals(ForcedTaskSwitch(a, T0), decoded.forcedSwitch)

        // A payload written before the button existed: no refusal outstanding, which is how it always behaved.
        val legacy = SchedulerStateCodec.decode("""{"rootListId":"list/main","lists":[],"cells":[],"tasks":[]}""")
        assertNotNull(legacy)
        assertNull(legacy.forcedSwitch)
    }
}
