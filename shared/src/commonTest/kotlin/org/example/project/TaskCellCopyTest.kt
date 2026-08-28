package org.example.project

import org.example.project.scheduler.domain.PeriodKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs
import org.example.project.scheduler.domain.RelativePriorityDomain
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
            SchedulerIntent.SetTaskResilience(pTask, PeriodKinds.NO_SCREEN, 1.0),
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
        val idP = s.cells[cP]!!.taskId!!.value
        val childList = s.tasks[s.cells[cP]!!.taskId!!]!!.childListId!!
        val idC1 = s.cells[s.lists[childList]!!.cellIds[0]]!!.taskId!!.value
        assertEquals(
            "P\n" +
                "\t- id: $idP\n" +
                "\t- minimum time: 45 min\n" +
                "\t- resilience to no on-screen task: 100 %\n" +
                "\t- schedule unit:\n" +
                "\t\t- warm up: 5 min\n" +
                "\t\t- run: 25 min\n" +
                "\t- text:\n" +
                "\t\tline one\n" +
                "\t\tline two\n" +
                "\tC1\n" +
                "\t\t- id: $idC1\n" +
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
    fun copy_carries_the_resilience_map_the_schedule_unit_and_the_text() {
        val (s, cP) = stateWithParentAndChild()
        val node = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20))!!.single()
        // `side-dev/README.md`: what travels is the resilience MAP, one `- resilience to <kind>: <n> %` line
        // per override — so a task that needs no screen comes back needing no screen.
        assertEquals(emptyMap(), node.resilience)
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
        // The default (on-screen) is omitted, so an ordinary task is its title, its id and its minimum time.
        assertEquals(
            listOf("Plain", "\t- id: ${s.cells[c]!!.taskId!!.value}", "\t- minimum time: 45 min", ""),
            text.split('\n'),
        )
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
        // A pre-1.6.0 payload says nothing about resilience, so the node carries the fresh-task default:
        // on screen, i.e. a 0 against "no on-screen task".
        assertEquals(mapOf(PeriodKinds.NO_SCREEN to 0.0), p.resilience)
        assertTrue(p.scheduleUnit.isEmpty() && p.text.isEmpty())

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

    // ----- PRD §13: the task id, the account-wide depth, Ctrl+X, and the replacing paste ----------

    /** Root-level A{child A1{child A1a}} and X{child X1}. Returns the state. */
    private fun stateWithTwoBranches(): SchedulerState {
        var s = SchedulerState.empty()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA, "A"))
        val cA1 = s.lists[s.tasks[s.cells[cA]!!.taskId!!]!!.childListId!!]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA1, "A1"))
        val cA1a = s.lists[s.tasks[s.cells[cA1]!!.taskId!!]!!.childListId!!]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA1a, "A1a"))
        val cX = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cX, "X"))
        val cX1 = s.lists[s.tasks[s.cells[cX]!!.taskId!!]!!.childListId!!]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cX1, "X1"))
        return s
    }

    private fun click(state: SchedulerState, cellId: CellId): SchedulerState =
        SchedulerReducer.reduce(
            state,
            SchedulerIntent.ClickCell(
                cellId = cellId,
                ctrl = false,
                shift = false,
                visibleOrder = SchedulerDomain.selectableVisibleOrder(state),
            ),
        )

    @Test
    fun ctrl_c_copies_the_whole_sub_tree_whatever_the_window_depth_says() {
        // PRD §4: Ctrl+C is "all of it" — the account's deep-copy depth belongs to the WINDOW, and the
        // chord asks nobody, so lowering that number must not silently shorten what the chord takes.
        var s = stateWithTwoBranches()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        assertEquals(SchedulerDomain.DEEP_COPY_DEFAULT_DEPTH, s.deepCopyMaxDepth)
        s = click(s, cA)

        val deep = SchedulerDomain.parseTreeText(SchedulerDomain.copyTreeText(s, s.selection))!!.single()
        assertEquals(listOf("A1"), deep.children.map { it.title })
        assertEquals(listOf("A1a"), deep.children.single().children.map { it.title })

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDeepCopyMaxDepth(2))
        assertEquals(2, s.deepCopyMaxDepth)
        val still = SchedulerDomain.parseTreeText(SchedulerDomain.copyTreeText(s, s.selection))!!.single()
        assertEquals(listOf("A1a"), still.children.single().children.map { it.title })
        // The window's own copy is what the number cuts short.
        val two = SchedulerDomain.parseTreeText(SchedulerDomain.copyCellsText(s, listOf(cA), maxDepth = 2))!!.single()
        assertTrue(two.children.single().children.isEmpty())

        // Out-of-range numbers are healed rather than stored (the window's field takes free text).
        assertEquals(1, SchedulerReducer.reduce(s, SchedulerIntent.SetDeepCopyMaxDepth(0)).deepCopyMaxDepth)
        assertEquals(999, SchedulerReducer.reduce(s, SchedulerIntent.SetDeepCopyMaxDepth(5000)).deepCopyMaxDepth)
    }

    /**
     * A -> {A1, A2} with a second weight column on A's sub-list and hand-set values, plus a value row on A
     * itself. Returns the state and A's cell — the fixture both weight-table tests below copy.
     */
    private fun stateWithWeightTables(): Pair<SchedulerState, CellId> {
        var s = SchedulerState.empty()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA, "A"))
        val aList = s.tasks[s.cells[cA]!!.taskId!!]!!.childListId!!
        val cA1 = s.lists[aList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA1, "A1"))
        val cA2 = s.lists[aList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA2, "A2"))
        // A second column on A's sub-list, with a header of its own and a value per row.
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(aList))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(aList, 1, 0.25))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cA1, 0, 3.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cA1, 1, 2.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cA2, 1, 1.0))
        // And a row value on A itself, in the root list.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cA, 0, 4.0))
        return s to cA
    }

    @Test
    fun the_weight_table_of_every_copied_sub_list_travels_and_pastes_back() {
        // PRD §4/§13: a copied sub-tree carries the priority weight TABLE of each sub-list it walks — the
        // column header row and every cell's values — not just the cells' own rows.
        val (s, cA) = stateWithWeightTables()
        val aList = s.tasks[s.cells[cA]!!.taskId!!]!!.childListId!!
        val text = SchedulerDomain.copyCellsText(s, listOf(cA), maxDepth = 20)
        assertTrue(text.contains("- priority weights: 4.0"), text)
        assertTrue(text.contains("- sub-list weight columns: 1.0, 0.25"), text)
        assertTrue(text.contains("- priority weights: 3.0, 2.0"), text)

        val (pasted, target) = pasteIntoFreshTree(text)
        assertEquals(listOf(4.0), pasted.cells[target]!!.priorityWeights)
        val newList = pasted.tasks[pasted.cells[target]!!.taskId!!]!!.childListId!!
        assertEquals(s.lists[aList]!!.weightColumns, pasted.lists[newList]!!.weightColumns)
        assertEquals(
            s.lists[aList]!!.cellIds.take(2).map { s.cells[it]!!.priorityWeights },
            pasted.lists[newList]!!.cellIds.take(2).map { pasted.cells[it]!!.priorityWeights },
        )
    }

    @Test
    fun the_priority_tables_switch_writes_the_sub_list_percentage_instead() {
        // PRD §13: with the switch off the table is replaced by the one number it produces — each cell's
        // percentage of its own sub-list — and pasting that back reproduces those very percentages.
        val (s, cA) = stateWithWeightTables()
        val aList = s.tasks[s.cells[cA]!!.taskId!!]!!.childListId!!
        val shares = s.lists[aList]!!.cellIds.take(2).map { RelativePriorityDomain.cellShare(s, it) }
        val text =
            SchedulerDomain.copyCellsText(
                s,
                listOf(cA),
                maxDepth = 20,
                options = SchedulerDomain.CopyOptions(priorityTables = false),
            )
        assertTrue(!text.contains("- priority weights:"), text)
        assertTrue(!text.contains("- sub-list weight columns:"), text)
        // A is the root list's only populated cell, so it holds all of it.
        assertTrue(text.contains("- priority in its sub-list: 100 %"), text)

        val (pasted, target) = pasteIntoFreshTree(text)
        val newList = pasted.tasks[pasted.cells[target]!!.taskId!!]!!.childListId!!
        val restored = pasted.lists[newList]!!.cellIds.take(2).map { RelativePriorityDomain.cellShare(pasted, it) }
        assertEquals(shares.size, restored.size)
        shares.forEachIndexed { i, expected ->
            assertTrue(abs(expected - restored[i]) < 1e-4, "share $i: $expected vs ${restored[i]}")
        }
    }

    @Test
    fun the_id_and_text_switches_leave_those_out_of_the_clipboard() {
        // PRD §13: two of the three switches simply drop a line. Without the id the payload is foreign by
        // construction, so pasting it mints NEW tasks rather than mirroring the ones it came from.
        val (s, cP) = stateWithParentAndChild()
        val pTask = s.cells[cP]!!.taskId!!
        val text =
            SchedulerDomain.copyCellsText(
                s,
                listOf(cP),
                maxDepth = 20,
                options = SchedulerDomain.CopyOptions(includeIds = false, includeText = false),
            )
        assertTrue(!text.contains("- id:"), text)
        assertTrue(!text.contains("- text:"), text)
        // Everything else the edit window holds still travels.
        assertTrue(text.contains("- resilience to no on-screen task: 100 %"), text)
        assertTrue(text.contains("- schedule unit:"), text)

        val node = SchedulerDomain.parseTreeText(text)!!.single()
        assertEquals(null, node.taskId)
        assertEquals("", node.text)
        // Pasted back into the very tree it came from, it lands on a task of its own.
        var dst = click(s, s.lists[s.rootListId]!!.cellIds[1])
        dst = SchedulerReducer.reduce(dst, SchedulerIntent.PasteTree(text))
        val landed = dst.cells[dst.lists[dst.rootListId]!!.cellIds[1]]!!.taskId!!
        assertTrue(landed != pTask, "a copy with no id must never mirror the task it came from")
        assertEquals("P", dst.tasks[landed]!!.title)
        assertEquals("", dst.tasks[landed]!!.text)
    }

    @Test
    fun the_copy_switches_are_the_accounts_and_survive_a_reload() {
        // Like the depth: one answer per account, so Ctrl+C obeys what the deep-copy window was told.
        var s = SchedulerState.empty()
        assertEquals(SchedulerDomain.CopyOptions(), SchedulerDomain.CopyOptions.from(s))
        val off = SchedulerDomain.CopyOptions(includeIds = false, priorityTables = false, includeText = false)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCopyOptions(off))
        assertEquals(off, SchedulerDomain.CopyOptions.from(s))
        // Not an Undo/Redo unit (nothing to undo).
        assertEquals(off, SchedulerDomain.CopyOptions.from(SchedulerReducer.reduce(s, SchedulerIntent.Undo)))

        val reloaded =
            org.example.project.scheduler.persistence.SchedulerStateCodec.decode(
                org.example.project.scheduler.persistence.SchedulerStateCodec.encode(s),
            )
        assertNotNull(reloaded)
        assertEquals(off, SchedulerDomain.CopyOptions.from(reloaded))

        // Persisted-DB compatibility: a payload written before the switches existed carried all three.
        val before = org.example.project.scheduler.persistence.SchedulerStateCodec.encode(SchedulerState.empty())
        assertTrue(!before.contains("copyIncludeIds"), "the fixture must predate the field")
        val old = org.example.project.scheduler.persistence.SchedulerStateCodec.decode(before)
        assertNotNull(old)
        assertEquals(SchedulerDomain.CopyOptions(), SchedulerDomain.CopyOptions.from(old))
    }

    @Test
    fun cut_copies_the_sub_tree_and_empties_it_as_one_history_unit() {
        var s = stateWithTwoBranches()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        val aTask = s.cells[cA]!!.taskId!!
        s = click(s, cA)
        val cut = SchedulerReducer.reduce(s, SchedulerIntent.CutSelection)

        // The clipboard holds the same deep copy Ctrl+C takes...
        assertEquals(SchedulerDomain.copyTreeText(s, s.selection).split('\n'), cut.clipboard)
        // ...and the cells are gone, ids and all (a blank title is what deletes, PRD §4).
        assertTrue(cA !in cut.cells)
        assertTrue(aTask !in cut.tasks)
        assertEquals(listOf("X"), rootTitles(cut))

        // One Ctrl+Z puts the whole sub-tree back.
        val undone = SchedulerReducer.reduce(cut, SchedulerIntent.Undo)
        assertEquals(listOf("A", "X"), rootTitles(undone))
        assertEquals("A1a", undone.tasks[undone.cells[cA]!!.taskId!!]?.let { deepestTitle(undone, it.id) })
    }

    @Test
    fun a_cut_sub_tree_pastes_back_under_its_own_task_ids() {
        // PRD §13: the clipboard carries the id, so cut + paste MOVES the tasks rather than cloning them.
        var s = stateWithTwoBranches()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        val aTask = s.cells[cA]!!.taskId!!
        val a1Task = s.cells[s.lists[s.tasks[aTask]!!.childListId!!]!!.cellIds[0]]!!.taskId!!
        s = click(s, cA)
        val text = SchedulerDomain.copyTreeText(s, s.selection)
        s = SchedulerReducer.reduce(s, SchedulerIntent.CutSelection)

        // Paste it under X, where nothing of it survives.
        val cX = s.lists[s.rootListId]!!.cellIds[0]
        val underX = s.lists[s.tasks[s.cells[cX]!!.taskId!!]!!.childListId!!]!!.cellIds.last()
        s = click(s, underX)
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree(text))

        val pasted = s.cells[underX]!!.taskId
        assertEquals(aTask, pasted, "the pasted cell must hold the very task that was cut")
        val pastedChildren = s.lists[s.tasks[aTask]!!.childListId!!]!!.cellIds.mapNotNull { s.cells[it]?.taskId }
        assertEquals(listOf(a1Task), pastedChildren)
        // The counter must be walked past a resurrected id, or the next new task would collide with it.
        assertTrue(s.nextTaskCounter > aTask.value.substringAfterLast('/').toInt())
    }

    @Test
    fun pasting_a_live_id_mirrors_that_task_instead_of_duplicating_it() {
        // A sub-list belongs to the task id: pasting A where A may legally sit points the cell at A itself,
        // so what shows under it is A's own sub-tree, not a second copy of it.
        var s = stateWithTwoBranches()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        val aTask = s.cells[cA]!!.taskId!!
        s = click(s, cA)
        val text = SchedulerDomain.copyTreeText(s, s.selection)

        val cX = s.lists[s.rootListId]!!.cellIds[1]
        val underX = s.lists[s.tasks[s.cells[cX]!!.taskId!!]!!.childListId!!]!!.cellIds.last()
        s = click(s, underX)
        val tasksBefore = s.tasks.size
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree(text))

        assertEquals(aTask, s.cells[underX]!!.taskId)
        assertEquals(tasksBefore, s.tasks.size, "a mirror creates no task")
        // A itself is untouched: both cells point at it.
        assertEquals(setOf(cA, underX), s.tasks[aTask]!!.occurrences.toSet())
    }

    @Test
    fun paste_replaces_the_target_cell_and_leaves_its_task_a_detached_parent() {
        // PRD §4/§13: the copied cell REPLACES the cell pasted onto — the task that was there is not
        // renamed (it is mirrored elsewhere, it has a sub-tree), it is re-pointed away from.
        var s = stateWithTwoBranches()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        val aTask = s.cells[cA]!!.taskId!!
        s = click(s, cA)
        val text = SchedulerDomain.copyTreeText(s, s.selection)

        val cX = s.lists[s.rootListId]!!.cellIds[1]
        val xTask = s.cells[cX]!!.taskId!!
        s = click(s, cX)
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree(text))

        val landed = s.cells[cX]!!.taskId!!
        assertTrue(landed != xTask, "the cell must not still hold the task it replaced")
        assertEquals("A", s.tasks[landed]!!.title)
        // A is already this list's first cell, so the copy cannot mirror it here: a fresh task carries the
        // copied content instead (the tree may not hold one task twice in one sub-list).
        assertTrue(landed != aTask)
        assertEquals(listOf("A1"), childTitles(s, landed))
        // X keeps its title and its sub-tree, as a detached parent its id can bring back.
        assertEquals("X", s.tasks[xTask]!!.title)
        assertTrue(s.cells.values.none { it.taskId == xTask })
        assertEquals(listOf("X1"), childTitles(s, xTask))
    }

    @Test
    fun an_id_line_the_app_never_writes_makes_paste_a_no_op() {
        // PRD §4: an unparseable value ⇒ null ⇒ the reducer returns the state unchanged. An id of another
        // shape could otherwise build a task over the tree's own root/main ids.
        assertEquals(null, SchedulerDomain.parseTreeText("P\n\t- id: task/root\n"))
        assertEquals(null, SchedulerDomain.parseTreeText("P\n\t- id: hello\n"))
        assertEquals(null, SchedulerDomain.parseTreeText("P\n\t- id:\n"))
        // The id the app writes parses.
        assertEquals(
            org.example.project.scheduler.model.TaskId("task/user/7"),
            SchedulerDomain.parseTreeText("P\n\t- id: task/user/7\n")!!.single().taskId,
        )
    }

    @Test
    fun the_account_deep_copy_depth_survives_a_reload_and_a_payload_without_it() {
        // Persisted-DB compatibility: a payload written before the setting existed decodes to the depth the
        // deep-copy window used to open on, which is exactly how that build behaved.
        val before = org.example.project.scheduler.persistence.SchedulerStateCodec.encode(SchedulerState.empty())
        assertTrue(!before.contains("deepCopyMaxDepth"), "the fixture must predate the field")
        val old = org.example.project.scheduler.persistence.SchedulerStateCodec.decode(before)
        assertNotNull(old)
        assertEquals(SchedulerDomain.DEEP_COPY_DEFAULT_DEPTH, old.deepCopyMaxDepth)

        val set = SchedulerState.empty().copy(deepCopyMaxDepth = 3)
        val reloaded =
            org.example.project.scheduler.persistence.SchedulerStateCodec.decode(
                org.example.project.scheduler.persistence.SchedulerStateCodec.encode(set),
            )
        assertNotNull(reloaded)
        assertEquals(3, reloaded.deepCopyMaxDepth)
    }

    private fun rootTitles(state: SchedulerState): List<String> =
        state.lists[state.rootListId]!!.cellIds
            .mapNotNull { state.cells[it]?.taskId?.let { id -> state.tasks[id]?.title } }
            .filter { it.isNotBlank() }

    private fun childTitles(state: SchedulerState, taskId: org.example.project.scheduler.model.TaskId): List<String> =
        state.tasks[taskId]?.childListId?.let { state.lists[it] }?.cellIds.orEmpty()
            .mapNotNull { state.cells[it]?.taskId?.let { id -> state.tasks[id]?.title } }
            .filter { it.isNotBlank() }

    /** The title at the bottom of the first branch under [taskId] — enough to say a sub-tree came back. */
    private fun deepestTitle(state: SchedulerState, taskId: org.example.project.scheduler.model.TaskId): String {
        var current = taskId
        while (true) {
            val next =
                state.tasks[current]?.childListId?.let { state.lists[it] }?.cellIds.orEmpty()
                    .mapNotNull { state.cells[it]?.taskId }
                    .firstOrNull { state.tasks[it]?.title?.isNotBlank() == true } ?: return state.tasks[current]!!.title
            current = next
        }
    }
}
