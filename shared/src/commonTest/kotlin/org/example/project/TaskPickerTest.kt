package org.example.project

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock
import org.example.project.ui.restrictionColor
import org.example.project.ui.taskTouchedAgoLabel

/**
 * PRD §7 the **task picker** — the menu the "Choose the task to do now" chord
 * ([org.example.project.scheduler.platform.GlobalShortcut.PickTask]) opens at the pointer.
 *
 * What is pinned here is the whole of the menu that can be pinned without a screen: **which tasks it offers
 * and in what order** ([SchedulerDomain.taskPickerEntries]), **what Enter takes**
 * ([SchedulerDomain.taskPickerCommit]), and that every row it offers is a row the intent behind it
 * ([SchedulerIntent.ForceTaskStart]) actually honours — a menu that can offer a task the reducer then drops
 * is a menu that silently does nothing, which is the one failure the user cannot tell from a lost keystroke.
 */
class TaskPickerTest {

    /** The two hues the picker writes a restricted row in — the app's own red and orange. */
    private val RED = androidx.compose.ui.graphics.Color(0xFFD93025)
    private val ORANGE = androidx.compose.ui.graphics.Color(0xFFE8710A)

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

    private fun auto(id: String, taskId: TaskId, start: Long, end: Long) =
        TaskPanel(id, taskId, "x", start, end, pinned = false, auto = true)

    /** Bank [ranges] as [taskId]'s recorded past — the other half of "the now-line was on it". */
    private fun withRecord(
        state: SchedulerState,
        taskId: TaskId,
        vararg ranges: Pair<Long, Long>,
    ): SchedulerState {
        val task = state.tasks.getValue(taskId)
        return state.copy(
            tasks = state.tasks + (
                taskId to task.copy(
                    record = ranges.map { (start, end) -> TaskTimeRange(start, end) },
                )
                ),
        )
    }

    private fun titles(state: SchedulerState, entries: List<SchedulerDomain.TaskPickEntry>): List<String> =
        entries.map { state.tasks.getValue(it.taskId).title }

    // ----- the list ---------------------------------------------------------------------------

    @Test
    fun the_list_runs_from_the_most_recently_worked_task_to_the_least() {
        val (s0, ids) = stateWithTasks("A", "B", "C")
        val (a, b, c) = ids
        var s = withRecord(s0, a, (T0 - 3 * HOUR) to (T0 - 2 * HOUR))
        s = withRecord(s, b, (T0 - 30 * MIN) to (T0 - 20 * MIN))
        // C has never run: it goes after both, and it is still offered — a task that has never run is
        // exactly the one the user may be reaching for.
        assertEquals(listOf("B", "A", "C"), titles(s, SchedulerDomain.taskPickerEntries(s, T0)))
        val entries = SchedulerDomain.taskPickerEntries(s, T0)
        assertEquals(T0 - 20 * MIN, entries[0].lastTouchedMillis)
        assertEquals(T0 - 2 * HOUR, entries[1].lastTouchedMillis)
        assertNull(entries[2].lastTouchedMillis)
    }

    @Test
    fun the_panel_the_now_line_is_in_counts_at_the_line_and_not_at_its_end() {
        // The half of a straddling panel ahead of the line has not happened yet: a task whose panel runs
        // until midnight must not read as "touched at midnight" and outrank everything.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val s = withRecord(s0, a, (T0 - 5 * MIN) to (T0 - 1 * MIN))
            .copy(panels = listOf(auto("auto/0", b, T0 - 10 * MIN, T0 + 5 * HOUR)))
        val entries = SchedulerDomain.taskPickerEntries(s, T0)
        // B is the task at the line, so it is not in the list at all (below); A's touch is its own.
        assertEquals(listOf("A"), titles(s, entries))
        assertEquals(T0 - 1 * MIN, entries.single().lastTouchedMillis)
    }

    @Test
    fun a_panel_wholly_ahead_of_the_now_line_is_not_a_touch() {
        // The plan's intentions are not history: a task the fill has placed for this evening has not been
        // worked, and ranking it by that panel would put the future at the top of a list about the past.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val s = withRecord(s0, a, (T0 - 2 * HOUR) to (T0 - 1 * HOUR))
            .copy(panels = listOf(auto("auto/0", b, T0 + 2 * HOUR, T0 + 3 * HOUR)))
        val entries = SchedulerDomain.taskPickerEntries(s, T0)
        assertEquals(listOf("A", "B"), titles(s, entries))
        assertNull(entries[1].lastTouchedMillis)
    }

    @Test
    fun the_task_the_now_line_is_on_is_not_in_the_list() {
        // The list is what to switch TO. Left in, the running task would lead it — it is being touched at
        // this very instant — and Enter straight after the chord would re-ask for the task being left.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, b) = ids
        val s = s0.copy(panels = listOf(auto("auto/0", a, T0 - 10 * MIN, T0 + 50 * MIN)))
        assertEquals(a, SchedulerDomain.taskAtNowLine(s, T0))
        assertEquals(listOf("B"), titles(s, SchedulerDomain.taskPickerEntries(s, T0)))
        // ... and it comes back the moment the line is no longer on it.
        assertEquals(listOf("A", "B"), titles(s, SchedulerDomain.taskPickerEntries(s, T0 + 2 * HOUR)))
    }

    @Test
    fun a_break_over_the_now_line_is_not_a_task_being_left() {
        // A screen break is not work, so nothing is excluded for it: during a pause every task is a
        // candidate, the one worked before it included.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, _) = ids
        val s = s0.copy(
            panels = listOf(
                auto("auto/0", a, T0 - 30 * MIN, T0 - 5 * MIN),
                TaskPanel("break/0", null, "Look away", T0 - 5 * MIN, T0 + 5 * MIN, screenBreak = true),
            ),
        )
        assertEquals(listOf("A", "B"), titles(s, SchedulerDomain.taskPickerEntries(s, T0)))
    }

    @Test
    fun only_tasks_the_plan_can_be_started_on_are_offered() {
        // The same predicate the intent enforces: a parent task is a grouping, so it is no row here.
        val (s0, ids) = stateWithTasks("A", "B")
        val (a, _) = ids
        val parentCell = s0.cells.values.first { it.taskId == a }.id
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.ToggleExpand(parentCell))
        val childList = s.tasks[a]!!.childListId
        assertNotNull(childList)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[childList]!!.cellIds.first(), "A1"))
        val offered = titles(s, SchedulerDomain.taskPickerEntries(s, T0))
        assertEquals(listOf("A1", "B"), offered.sorted())
        assertTrue("A" !in offered)
    }

    @Test
    fun every_row_the_menu_offers_is_a_row_the_intent_honours() {
        // The funnel: the picker's list and `reduceForceTaskStart` ask ONE predicate
        // ([SchedulerDomain.isPlaceableTask]), so no row of this menu can be a press that does nothing.
        val (s0, ids) = stateWithTasks("A", "B", "C")
        val s = withRecord(s0, ids[1], (T0 - HOUR) to (T0 - 30 * MIN))
        val entries = SchedulerDomain.taskPickerEntries(s, T0)
        assertTrue(entries.isNotEmpty())
        withClock(T0) {
            entries.forEach { entry ->
                val after = SchedulerReducer.reduce(s, SchedulerIntent.ForceTaskStart(entry.taskId))
                assertEquals(entry.taskId, after.forcedStart?.taskId, "row \"${entry.label}\" was refused")
            }
        }
    }

    // ----- the search field -------------------------------------------------------------------

    @Test
    fun the_id_rows_are_the_tasks_the_typed_text_names() {
        val (s, _) = stateWithTasks("Emails", "Email drafts")
        // An exact title match only, as in a cell's Change Task menu — a partial draft names no task.
        assertTrue(SchedulerDomain.taskPickerIdentityRows(s, "Email").isEmpty())
        val rows = SchedulerDomain.taskPickerIdentityRows(s, "emails")
        assertEquals(1, rows.size)
        assertEquals(s.tasks.keys.first { s.tasks[it]!!.title == "Emails" }, rows.single().taskId)
        // ... and there is never a "New task" row: a task that does not exist cannot be started.
        assertTrue(rows.none { it.taskId == null })
    }

    @Test
    fun the_suggestions_only_offer_titles_a_task_can_be_started_on() {
        val (s0, ids) = stateWithTasks("Alpha", "Beta")
        val (a, _) = ids
        val parentCell = s0.cells.values.first { it.taskId == a }.id
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.ToggleExpand(parentCell))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetCellTitle(s.lists[s.tasks[a]!!.childListId!!]!!.cellIds.first(), "Alpha child"),
        )
        val suggestions = SchedulerDomain.placeableTaskTitleSuggestions(s, "Alp")
        assertContains(suggestions, "Alpha child")
        assertTrue("Alpha" !in suggestions, "a parent title is nowhere to start")
    }

    // ----- what Enter takes -------------------------------------------------------------------

    @Test
    fun enter_on_a_fresh_menu_takes_the_task_worked_before_this_one() {
        val (s0, ids) = stateWithTasks("A", "B", "C")
        val (a, b, _) = ids
        // The line is on A; B was worked right before it.
        var s = withRecord(s0, b, (T0 - 40 * MIN) to (T0 - 10 * MIN))
        s = s.copy(panels = listOf(auto("auto/0", a, T0 - 10 * MIN, T0 + 50 * MIN)))
        val entries = SchedulerDomain.taskPickerEntries(s, T0)
        assertEquals(b, SchedulerDomain.taskPickerCommit(s, entries, draftText = "", highlighted = 0))
    }

    @Test
    fun the_arrows_move_what_enter_takes() {
        val (s0, ids) = stateWithTasks("A", "B", "C")
        val s = withRecord(s0, ids[2], (T0 - 10 * MIN) to (T0 - 5 * MIN))
        val entries = SchedulerDomain.taskPickerEntries(s, T0)
        assertEquals(entries[1].taskId, SchedulerDomain.taskPickerCommit(s, entries, "", highlighted = 1))
        // Out of range commits nothing rather than the nearest row: the highlight is the UI's to clamp.
        assertNull(SchedulerDomain.taskPickerCommit(s, entries, "", highlighted = entries.size))
    }

    @Test
    fun text_in_the_field_is_what_enter_takes_instead() {
        val (s0, ids) = stateWithTasks("A", "B", "C")
        val s = withRecord(s0, ids[1], (T0 - 10 * MIN) to (T0 - 5 * MIN))
        val entries = SchedulerDomain.taskPickerEntries(s, T0)
        // The highlight still sits on the first row, but the field now names a task, and that is the
        // answer the user can see in the id menu under it.
        assertEquals(ids[2], SchedulerDomain.taskPickerCommit(s, entries, "C", highlighted = 0))
        // Text naming no task commits nothing — a half-typed title must never start the wrong task.
        assertNull(SchedulerDomain.taskPickerCommit(s, entries, "Cx", highlighted = 0))
        // Blank is not text: spaces leave the list in charge.
        assertEquals(entries[0].taskId, SchedulerDomain.taskPickerCommit(s, entries, "   ", highlighted = 0))
    }

    @Test
    fun an_empty_account_offers_nothing_and_commits_nothing() {
        val s = SchedulerState.empty()
        val entries = SchedulerDomain.taskPickerEntries(s, T0)
        assertTrue(entries.isEmpty())
        assertNull(SchedulerDomain.taskPickerCommit(s, entries, "", highlighted = 0))
    }

    // ----- what the now-line's own periods make of each row -------------------------------------

    @Test
    fun a_task_the_periods_at_the_line_forbid_is_red_and_one_they_merely_scale_is_orange() {
        // The resilience model end to end (`docs/invariants/scheduler.md`): 0 forbids, a fraction scales,
        // 1 is unaffected — and the picker writes exactly those three answers as red, orange and nothing.
        val (s0, ids) = stateWithTasks("A", "B", "C")
        val (a, b, c) = ids
        // A grey period over the line: its kind refuses everybody by default...
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_TASK, T0 - 5 * MIN, T0 + 5 * MIN))
        // ... unless a task has deliberately been given a value for it.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(b, PeriodKinds.NO_TASK, 0.5))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(c, PeriodKinds.NO_TASK, 1.0))

        assertEquals(setOf(PeriodKinds.NO_TASK), SchedulerDomain.restrictiveKindsAt(s, T0))
        assertEquals(0.0, SchedulerDomain.taskResilienceAt(s, a, T0))
        assertEquals(0.5, SchedulerDomain.taskResilienceAt(s, b, T0))
        assertEquals(1.0, SchedulerDomain.taskResilienceAt(s, c, T0))

        assertEquals(RED, restrictionColor(SchedulerDomain.taskResilienceAt(s, a, T0)))
        assertEquals(ORANGE, restrictionColor(SchedulerDomain.taskResilienceAt(s, b, T0)))
        assertNull(restrictionColor(SchedulerDomain.taskResilienceAt(s, c, T0)), "unaffected rows wear no colour")
    }

    @Test
    fun the_colours_follow_the_now_line_into_and_out_of_the_periods() {
        // "The colours update as soon as the now-line is on new periods": the answer is a pure function of
        // the instant, so the menu re-asking it at the display's own instant is the whole of the update.
        val (s0, ids) = stateWithTasks("A", "B")
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_TASK, T0 + HOUR, T0 + 2 * HOUR))

        assertTrue(SchedulerDomain.restrictiveKindsAt(s, T0).isEmpty(), "nothing covers the line yet")
        assertNull(restrictionColor(SchedulerDomain.taskResilienceAt(s, ids[0], T0)))
        // One millisecond inside the period, every default task is forbidden...
        assertEquals(RED, restrictionColor(SchedulerDomain.taskResilienceAt(s, ids[0], T0 + HOUR)))
        // ... and one millisecond past its end, nothing is.
        assertNull(restrictionColor(SchedulerDomain.taskResilienceAt(s, ids[0], T0 + 2 * HOUR)))
    }

    @Test
    fun overlapping_periods_multiply_so_the_strictest_still_forbids() {
        // `PeriodKinds.multiplier` is the one reading, and the picker asks it through the same door: a task
        // half-resilient to one kind and forbidden by another is red, not orange.
        val (s0, ids) = stateWithTasks("A")
        val a = ids[0]
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_TASK, T0 - MIN, T0 + MIN))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, T0 - MIN, T0 + MIN))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(a, PeriodKinds.NO_TASK, 0.5))
        // The task is on screen (a 0 against "no on-screen task", `Task.DEFAULT_RESILIENCE`), so the
        // no-screen period alone already forbids it.
        assertEquals(0.0, SchedulerDomain.taskResilienceAt(s, a, T0))
        assertEquals(RED, restrictionColor(SchedulerDomain.taskResilienceAt(s, a, T0)))
    }

    @Test
    fun a_break_the_now_line_is_DRAGGING_restricts_nothing() {
        // Reported: the line was dragging a 15-minute pose — which by mode 1 means no instant of the timeline
        // is inside it — and the picker painted every task red. The dragged panel is materialized as
        // `[t_p + 1, t_p + d + 1)` when the fill runs, so the line sweeps into it long before the next
        // re-plan pushes it forward, and a reading that only asks "does this panel cover the line" finds it.
        // `isDraggedScreenBreak` is the ONE reading of that, and this answer asks it exactly as the fill does.
        val (s0, ids) = stateWithTasks("A", "B")
        val dragged = TaskPanel(
            id = "side/2/${T0 - MIN}" + SchedulerDomain.DRAGGED_BREAK_ID_SUFFIX,
            taskId = null,
            title = "take a 15min pose",
            startEpochMillis = T0 - MIN,
            endEpochMillis = T0 + 14 * MIN,
            screenBreak = true,
        )
        val s = s0.copy(panels = listOf(dragged))

        assertTrue(SchedulerDomain.restrictiveKindsAt(s, T0).isEmpty(), "a dragged pose restricted the line")
        assertEquals(1.0, SchedulerDomain.taskResilienceAt(s, ids[0], T0))
        assertNull(restrictionColor(SchedulerDomain.taskResilienceAt(s, ids[0], T0)))
    }

    @Test
    fun a_break_that_is_NOT_dragged_restricts_the_line_as_any_period_does() {
        // The other side of the same rule, so the drop stays narrow: a pose the app actually conducted (or
        // one the line walks through in an away mode) is an ordinary `no task allowed` period and every task
        // without a resilience to that kind is red inside it.
        val (s0, ids) = stateWithTasks("A", "B")
        val taken = TaskPanel(
            id = "side/2/${T0 - MIN}",
            taskId = null,
            title = "take a 15min pose",
            startEpochMillis = T0 - MIN,
            endEpochMillis = T0 + 14 * MIN,
            screenBreak = true,
        )
        val s = s0.copy(panels = listOf(taken))

        assertEquals(setOf(PeriodKinds.NO_TASK), SchedulerDomain.restrictiveKindsAt(s, T0))
        assertEquals(RED, restrictionColor(SchedulerDomain.taskResilienceAt(s, ids[0], T0)))
    }

    // ----- the row's own label ----------------------------------------------------------------

    @Test
    fun a_row_says_how_long_ago_the_now_line_was_on_it() {
        assertEquals("never", taskTouchedAgoLabel(null, T0))
        assertEquals("just now", taskTouchedAgoLabel(T0 - 30_000L, T0))
        assertEquals("12 min ago", taskTouchedAgoLabel(T0 - 12 * MIN, T0))
        assertEquals("3 h ago", taskTouchedAgoLabel(T0 - 3 * HOUR, T0))
        assertEquals("2 d ago", taskTouchedAgoLabel(T0 - 50 * HOUR, T0))
        // A clock that has gone backwards (a peer's stamp ahead of ours) reads as the present, never as a
        // negative age.
        assertEquals("just now", taskTouchedAgoLabel(T0 + HOUR, T0))
    }
}
