package org.example.project

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **Renaming a task is not a rule change, and it renames the schedule at once.**
 *
 * The user's rule: *"the scheduler must run each time the data for the schedule changes … except if it is in
 * rename mode, which only renames the task panels in the schedule"*, and *"each time a title is renamed with
 * a new keystroke, the titles in the calendar must update at the same time"*.
 *
 * Both halves are a statement about what a title IS to the scheduler. `docs/scheduler_score.md` § *Ties*
 * reads one thing off it — the order it puts the tasks in, "higher priority first, then title" — and
 * `docs/invariants/task-tree.md` reads one other, that a BLANK title deletes. Nothing else about the text
 * reaches the plan, so:
 *
 *  - a rename that moves no task past another must leave [SchedulerDomain.schedulingSignature] alone (it is
 *    the one trigger of a re-plan, CLAUDE.md), while emptying a title, or moving a task in title order, must
 *    move it;
 *  - and because the plan is then NOT what rewrites the panels, the rename itself must — otherwise every
 *    block already on the calendar would keep the old name until some unrelated edit re-planned.
 */
class RenameIsNotARuleTest {

    private val T0 = 1_700_000_000_000L

    /** Two titled top-level tasks, "alpha" and "zulu", the first carrying a scheduled panel. */
    private fun account(): Pair<SchedulerState, SchedulerState> {
        var s = SchedulerState.empty()
        val root = s.lists[s.rootListId]!!.cellIds
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(root[0], "alpha"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[1], "zulu"))
        return s to s
    }

    private fun taskIdOf(state: SchedulerState, title: String) =
        state.tasks.values.first { it.title == title }.id

    private fun withPanel(state: SchedulerState, title: String): SchedulerState {
        val id = taskIdOf(state, title)
        return state.copy(
            panels = listOf(
                TaskPanel(
                    id = "panel-1",
                    taskId = id,
                    title = title,
                    startEpochMillis = T0,
                    endEpochMillis = T0 + 3_600_000L,
                    auto = true,
                ),
            ),
        )
    }

    /** Rename the cell holding [from] to [to], through Edit Mode's Rename, as the tree does. */
    private fun rename(state: SchedulerState, from: String, to: String): SchedulerState {
        val cellId = state.cells.values.first { it.taskId == taskIdOf(state, from) }.id
        var s = SchedulerReducer.reduce(state, SchedulerIntent.BeginEdit(cellId, mode = CellEditMode.Rename))
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText(to))
        return s
    }

    @Test
    fun a_rename_that_moves_nothing_in_title_order_is_not_a_rule_change() {
        val (s, _) = account()
        val before = SchedulerDomain.schedulingSignature(s)
        // "alpha" → "alphabet": still first of the two, so the order the tie-break reads is the same one.
        val renamed = rename(s, "alpha", "alphabet")
        assertEquals(
            before,
            SchedulerDomain.schedulingSignature(renamed),
            "a rename that cannot change the plan must not re-plan the account",
        )
    }

    @Test
    fun a_rename_that_reorders_the_tasks_is_a_rule_change() {
        val (s, _) = account()
        val before = SchedulerDomain.schedulingSignature(s)
        // "alpha" → "zzz": it now sorts AFTER "zulu", which is exactly the tie-break's input.
        val renamed = rename(s, "alpha", "zzz")
        assertNotEquals(
            before,
            SchedulerDomain.schedulingSignature(renamed),
            "docs/scheduler_score.md § Ties: the title ORDER is a scheduling input",
        )
    }

    @Test
    fun emptying_a_title_is_a_rule_change() {
        val (s, _) = account()
        val before = SchedulerDomain.schedulingSignature(s)
        // A blank title deletes (docs/invariants/task-tree.md), so the task leaves the schedulable set.
        val emptied = rename(s, "alpha", "")
        assertNotEquals(
            before,
            SchedulerDomain.schedulingSignature(emptied),
            "a blank title deletes, which changes what there is to schedule",
        )
    }

    @Test
    fun a_rename_renames_the_panels_already_on_the_calendar() {
        val (s0, _) = account()
        val s = withPanel(s0, "alpha")
        val renamed = rename(s, "alpha", "alphabet")
        val panel = renamed.panels.single()
        assertEquals("alphabet", panel.title, "the schedule must show the new name at the keystroke")
        assertEquals(taskIdOf(renamed, "alphabet"), panel.taskId, "the same panel, renamed — never a new one")
        assertEquals(s.panels.single().startEpochMillis, panel.startEpochMillis, "a rename places nothing")
        assertEquals(s.panels.single().endEpochMillis, panel.endEpochMillis, "a rename places nothing")
    }

    @Test
    fun a_rename_leaves_another_tasks_panels_alone() {
        val (s0, _) = account()
        val zulu = taskIdOf(s0, "zulu")
        val s =
            withPanel(s0, "alpha").let {
                it.copy(
                    panels = it.panels + TaskPanel(
                        id = "panel-2",
                        taskId = zulu,
                        title = "zulu",
                        startEpochMillis = T0 + 7_200_000L,
                        endEpochMillis = T0 + 10_800_000L,
                        auto = true,
                    ),
                )
            }
        val renamed = rename(s, "alpha", "alphabet")
        assertTrue(
            renamed.panels.any { it.taskId == zulu && it.title == "zulu" },
            "renaming one task must not touch another task's panels",
        )
    }
}
