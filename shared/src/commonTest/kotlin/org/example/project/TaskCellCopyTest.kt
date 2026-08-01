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
 * Ctrl+V pastes back. Covers the format, the shallow/deep split, the paste restore, and that a payload
 * written before these fields existed still parses.
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
    fun copy_takes_only_the_cell_while_deep_copy_takes_the_subtree() {
        val (s, cP) = stateWithParentAndChild()
        val shallow = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellText(s, cP, deep = false))
        val deep = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellText(s, cP, deep = true))
        assertNotNull(shallow)
        assertNotNull(deep)
        assertEquals(listOf("P"), shallow.map { it.title })
        assertTrue(shallow.single().children.isEmpty())
        assertEquals(listOf("C1"), deep.single().children.map { it.title })
    }

    @Test
    fun copy_carries_the_no_screen_switch_the_schedule_unit_and_the_text() {
        val (s, cP) = stateWithParentAndChild()
        val node = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellText(s, cP, deep = true))!!.single()
        assertTrue(node.noScreenDoable)
        assertEquals(listOf(ScheduleUnitEntry("warm up", 5), ScheduleUnitEntry("run", 25)), node.scheduleUnit)
        // The text is escaped onto one appendix line, so its newline must survive the round-trip.
        assertEquals("line one\nline two", node.text)
        assertEquals("child note", node.children.single().text)
    }

    @Test
    fun deep_copy_pasted_back_restores_every_edit_window_field() {
        val (s, cP) = stateWithParentAndChild()
        val (dst, target) = pasteIntoFreshTree(SchedulerDomain.copyCellText(s, cP, deep = true))

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
        val text = SchedulerDomain.copyCellText(s, c, deep = false)
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
    fun copy_is_empty_for_a_cell_holding_no_task() {
        val s = SchedulerState.empty()
        val empty = s.lists[s.rootListId]!!.cellIds[0]
        assertEquals("", SchedulerDomain.copyCellText(s, empty, deep = false))
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
