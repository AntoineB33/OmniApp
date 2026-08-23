package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §13 cell contextual menu: "copy" / "deep copy" serialize the cell's task — including everything the
 * "edit" window holds (the no-screen switch, the schedule unit and the text) — to the clipboard text that
 * Ctrl+V pastes back. Covers the readable format, the depth the deep copy is asked for, the path its
 * window prints, the paste restore, and that a payload written before this format existed still parses.
 */
class TaskCellCopyTest {

    /** P{no-screen, unit, text} with one child C1{text}. Returns the state and P's cell. */
    private fun stateWithParentAndChild(): Pair<SchedulerState, CellId> {
        var s = SchedulerState.empty()
        val cP = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cP, "P"))
        val pTask = s.cells[cP]!!.taskId!!
        val childList = s.tasks[pTask]!!.childListId!!
        val cC1 = s.lists[childList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cC1, "C1"))
        val c1Task = s.cells[cC1]!!.taskId!!

        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetTaskScreenFlags(taskId = pTask, onScreen = false, doableDuringBreak = false),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetScheduleUnit(pTask, listOf(ScheduleUnitEntry("warm up", 5), ScheduleUnitEntry("run", 25))),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskText(pTask, "line one\nline two"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskText(c1Task, "child note"))
        return s to cP
    }

    /** Paste [text] into a fresh tree's first empty cell and return the resulting state + that cell. */
    private fun pasteIntoFreshTree(text: String): Pair<SchedulerState, CellId> {
        var dst = SchedulerState.empty()
        val target = dst.lists[dst.rootListId]!!.cellIds[0]
        dst = SchedulerReducer.reduce(
            dst,
            SchedulerIntent.ClickCell(
                cellId = target,
                ctrl = false,
                shift = false,
                visibleOrder = SchedulerDomain.selectableVisibleOrder(dst),
            ),
        )
        return SchedulerReducer.reduce(dst, SchedulerIntent.PasteTree(text)) to target
    }

    @Test
    fun the_clipboard_text_is_readable() {
        // PRD §13: what lands in the clipboard is the user's copy of the task as much as the app's, so
        // every field is a named line and the task text is carried verbatim, not escaped onto one line.
        val (s, cP) = stateWithParentAndChild()
        assertEquals(
            "P\n" +
                "\t- minimum time: 45 min\n" +
                "\t- can be done during a no-screen period: yes\n" +
                "\t- schedule unit:\n" +
                "\t\t- warm up: 5 min\n" +
                "\t\t- run: 25 min\n" +
                "\t- text:\n" +
                "\t\tline one\n" +
                "\t\tline two\n" +
                "\tC1\n" +
                "\t\t- minimum time: 45 min\n" +
                "\t\t- text:\n" +
                "\t\t\tchild note\n",
            SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20),
        )
    }

    @Test
    fun deep_copy_stops_at_the_asked_for_depth() {
        // A → A1 → A1a, and the deep-copy window asks for two levels: A1 comes, A1a does not.
        var s = SchedulerState.empty()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA, "A"))
        val cA1 = s.lists[s.tasks[s.cells[cA]!!.taskId!!]!!.childListId!!]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA1, "A1"))
        val cA1a = s.lists[s.tasks[s.cells[cA1]!!.taskId!!]!!.childListId!!]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA1a, "A1a"))

        val two = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellsText(s, listOf(cA), maxDepth = 2))!!.single()
        assertEquals(listOf("A1"), two.children.map { it.title })
        assertTrue(two.children.single().children.isEmpty())
        // A depth of 0 has nothing to copy at all.
        assertEquals("", SchedulerDomain.copyCellsText(s, listOf(cA), maxDepth = 0))
        // The window opens on (and its reset button returns to) 20 levels.
        assertEquals(20, SchedulerDomain.DEEP_COPY_DEFAULT_DEPTH)
    }

    @Test
    fun the_deep_copy_path_follows_the_deepest_branch_and_stops_at_the_depth() {
        // A → {A1, A2 → A2a}: the window's path must show the branch that actually reaches the depth.
        var s = SchedulerState.empty()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA, "A"))
        val aChildren = s.tasks[s.cells[cA]!!.taskId!!]!!.childListId!!
        val cA1 = s.lists[aChildren]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA1, "A1"))
        val cA2 = s.lists[aChildren]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA2, "A2"))
        val cA2a = s.lists[s.tasks[s.cells[cA2]!!.taskId!!]!!.childListId!!]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA2a, "A2a"))

        assertEquals(listOf("A", "A2", "A2a"), SchedulerDomain.deepCopyPathTitles(s, listOf(cA), 20))
        // Cut to the depth asked for — the path is what that number reaches, nothing more.
        assertEquals(listOf("A", "A2"), SchedulerDomain.deepCopyPathTitles(s, listOf(cA), 2))
        assertEquals(listOf("A"), SchedulerDomain.deepCopyPathTitles(s, listOf(cA), 1))
        assertEquals(emptyList(), SchedulerDomain.deepCopyPathTitles(s, listOf(cA), 0))
    }

    @Test
    fun a_title_that_reads_like_an_attribute_line_round_trips() {
        // "- text:" as a real title must come back as a title, not be swallowed as P's text.
        var s = SchedulerState.empty()
        val cP = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cP, "P"))
        val childList = s.tasks[s.cells[cP]!!.taskId!!]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[childList]!!.cellIds[0], "- text:"))

        val node = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20))!!.single()
        assertEquals(listOf("- text:"), node.children.map { it.title })
        assertTrue(node.text.isEmpty())
    }

    @Test
    fun a_task_text_holding_a_blank_line_survives_the_round_trip() {
        // The text block is bounded by indentation, so an empty line has to keep its indent or it would
        // end the block early and the rest of the note would be read as a child task.
        var s = SchedulerState.empty()
        val c = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c, "P"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskText(s.cells[c]!!.taskId!!, "a\n\nb"))

        val nodes = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellsText(s, listOf(c), maxDepth = 20))!!
        assertEquals("a\n\nb", nodes.single().text)
        assertTrue(nodes.single().children.isEmpty())
    }

    @Test
    fun copy_takes_only_the_cell_while_deep_copy_takes_the_subtree() {
        val (s, cP) = stateWithParentAndChild()
        val shallow = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 1))
        val deep = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20))
        assertNotNull(shallow)
        assertNotNull(deep)
        assertEquals(listOf("P"), shallow.map { it.title })
        assertTrue(shallow.single().children.isEmpty())
        assertEquals(listOf("C1"), deep.single().children.map { it.title })
    }

    @Test
    fun copy_carries_the_no_screen_switch_the_schedule_unit_and_the_text() {
        val (s, cP) = stateWithParentAndChild()
        val node = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20))!!.single()
        assertTrue(node.noScreenDoable)
        assertEquals(listOf(ScheduleUnitEntry("warm up", 5), ScheduleUnitEntry("run", 25)), node.scheduleUnit)
        // The text is escaped onto one appendix line, so its newline must survive the round-trip.
        assertEquals("line one\nline two", node.text)
        assertEquals("child note", node.children.single().text)
    }

    @Test
    fun deep_copy_pasted_back_restores_every_edit_window_field() {
        val (s, cP) = stateWithParentAndChild()
        val (dst, target) = pasteIntoFreshTree(SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20))

        val newP = dst.tasks[dst.cells[target]!!.taskId!!]!!
        assertEquals("P", newP.title)
        assertEquals(false, newP.onScreen)
        assertEquals(listOf(ScheduleUnitEntry("warm up", 5), ScheduleUnitEntry("run", 25)), newP.scheduleUnit)
        assertEquals("line one\nline two", newP.text)

        val newChildList = newP.childListId!!
        val newC1cell = dst.lists[newChildList]!!.cellIds.first { dst.cells[it]!!.taskId != null }
        val newC1 = dst.tasks[dst.cells[newC1cell]!!.taskId!!]!!
        assertEquals("C1", newC1.title)
        // C1 was never switched off-screen, so it must come back on-screen (the default).
        assertEquals(true, newC1.onScreen)
        assertEquals("child note", newC1.text)
    }

    @Test
    fun an_on_screen_task_writes_no_switch_field_and_pastes_back_on_screen() {
        var s = SchedulerState.empty()
        val c = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c, "Plain"))
        val text = SchedulerDomain.copyCellsText(s, listOf(c), maxDepth = 1)
        // The default (on-screen) is omitted, so an ordinary task's line is unchanged from before §13.
        assertEquals("Plain", text.split('\n')[0])
        val (dst, target) = pasteIntoFreshTree(text)
        assertEquals(true, dst.tasks[dst.cells[target]!!.taskId!!]!!.onScreen)
    }

    @Test
    fun a_payload_written_before_these_fields_existed_still_pastes() {
        // The pre-§13 shape: tree lines, one separator, the min-time appendix, and nothing after it.
        val sep = SchedulerDomain.COPY_SECTION_SEPARATOR
        val old = "P\n\tC1\n$sep\nP\t30\nC1\t90"
        val nodes = SchedulerDomain.parseTreeText(old)
        assertNotNull(nodes)
        val p = nodes.single()
        assertEquals(30, p.minMinutes)
        assertEquals(90, p.children.single().minMinutes)
        // The fields the payload says nothing about land on their defaults.
        assertTrue(!p.noScreenDoable && p.scheduleUnit.isEmpty() && p.text.isEmpty())

        val (dst, target) = pasteIntoFreshTree(old)
        val task = dst.tasks[dst.cells[target]!!.taskId!!]!!
        assertEquals(30, task.minimumMinutes)
        assertEquals(true, task.onScreen)
        assertTrue(task.text.isEmpty())
    }

    @Test
    fun a_plain_title_tree_still_pastes_and_keeps_the_default_minimum_time() {
        // No appendix at all: paste must not reset the fresh task's default minimum time.
        val (dst, target) = pasteIntoFreshTree("A\n\tB")
        val task = dst.tasks[dst.cells[target]!!.taskId!!]!!
        assertEquals("A", task.title)
        assertEquals(org.example.project.scheduler.model.DEFAULT_MINIMUM_MINUTES, task.minimumMinutes)
    }

    @Test
    fun a_right_click_inside_a_multi_selection_copies_the_whole_block() {
        // PRD §13: the menu and §4's Ctrl+C must agree about what "the cell" means — right-clicking one of
        // several selected cells copies them all, not just the one under the cursor.
        var s = SchedulerState.empty()
        val root = s.rootListId
        val cA = s.lists[root]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA, "A"))
        val cB = s.lists[root]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cB, "B"))
        val cC = s.lists[root]!!.cellIds[2]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cC, "C"))
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cA, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cC, ctrl = false, shift = true, visibleOrder = visible),
        )

        // Right-clicking B — inside the block — takes A, B and C, in the list's order.
        val inside = SchedulerDomain.contextMenuCopyTargets(s, s.selection, cB)
        assertEquals(listOf(cA, cB, cC), inside)
        val text = SchedulerDomain.copyCellsText(s, inside, maxDepth = 20)
        assertEquals(listOf("A", "B", "C"), SchedulerDomain.parseTreeText(text)!!.map { it.title })

        // A right-click on a cell OUTSIDE the selection is that cell alone.
        val cD = s.lists[root]!!.cellIds[3]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cD, "D"))
        assertEquals(listOf(cD), SchedulerDomain.contextMenuCopyTargets(s, s.selection, cD))
    }

    @Test
    fun the_deep_copy_path_takes_the_deepest_of_the_copied_cells() {
        // Two selected roots: A (a leaf) and B → B1. The window must print the path the depth bites on.
        var s = SchedulerState.empty()
        val root = s.rootListId
        val cA = s.lists[root]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA, "A"))
        val cB = s.lists[root]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cB, "B"))
        val cB1 = s.lists[s.tasks[s.cells[cB]!!.taskId!!]!!.childListId!!]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cB1, "B1"))

        assertEquals(listOf("B", "B1"), SchedulerDomain.deepCopyPathTitles(s, listOf(cA, cB), 20))
    }

    @Test
    fun copy_is_empty_for_a_cell_holding_no_task() {
        val s = SchedulerState.empty()
        val empty = s.lists[s.rootListId]!!.cellIds[0]
        assertEquals("", SchedulerDomain.copyCellsText(s, listOf(empty), maxDepth = 1))
    }

    @Test
    fun a_malformed_schedule_unit_appendix_is_rejected_so_paste_is_a_no_op() {
        val sep = SchedulerDomain.COPY_SECTION_SEPARATOR
        // Section 2 must be `<task>\t<step>\t<minutes>`; a non-numeric span is not our format.
        assertEquals(null, SchedulerDomain.parseTreeText("P\n$sep\nP\t30\n$sep\nP\tstep\tnope"))
        // An unknown per-line field is likewise rejected.
        assertEquals(null, SchedulerDomain.parseTreeText("P\tzz=1"))
        assertEquals(null, SchedulerDomain.parseTreeText("P\tns=2"))
    }
}
