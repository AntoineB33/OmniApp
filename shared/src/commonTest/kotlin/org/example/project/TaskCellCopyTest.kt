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
 * "edit task" window holds (the no-screen switch, the schedule unit and the text) — to the clipboard text that
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
        val text = SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20)
        assertTrue(text.startsWith("P\n"), text)
        assertTrue(text.contains("\t- id: $idP\n"), text)
        assertTrue(text.contains("\t- minimum time: 45 min\n"), text)
        assertTrue(!text.contains("\t- text:\n"), text)
        assertTrue(text.contains("\tC1\n"), text)
        assertTrue(text.contains("\t\t- id: $idC1\n"), text)
        assertTrue(text.contains("Copied tasks:\n"), text)
        assertTrue(text.contains("- P: minimum time: 45 min, id: $idP, text: line one\\nline two"), text)
        assertTrue(text.contains("- C1: minimum time: 45 min, id: $idC1, text: child note"), text)
    }

    @Test
    fun copied_text_appends_a_readable_summary_of_unique_tasks_below_the_tree() {
        val (s, cP) = stateWithParentAndChild()
        val idP = s.cells[cP]!!.taskId!!.value
        val text = SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20)

        assertTrue(text.contains("Copied tasks:"), text)
        assertTrue(text.contains("- P: minimum time: 45 min, id: $idP"), text)
        assertTrue(!text.contains("\n\tP: minimum time: 45 min, id: $idP"), text)
        assertTrue(SchedulerDomain.parseTreeText(text) != null, text)
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
    fun copy_paste_round_trips_titles_that_look_like_the_summary_header_or_field_markers() {
        // Titles matching the summary footer header or carrying a field delimiter must stay literal and not
        // be mistaken for part of the copy format itself.
        var s = SchedulerState.empty()
        val c = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c, "Copied tasks:"))
        val text = SchedulerDomain.copyTreeText(
            s,
            org.example.project.scheduler.state.SchedulerSelection(main = c, selected = setOf(c)),
        )
        val nodes = SchedulerDomain.parseTreeText(text)
        assertEquals(listOf("Copied tasks:"), nodes!!.map { it.title })
    }

    @Test
    fun new_copy_text_is_summary_only_but_older_text_blocks_still_parse() {
        // New copies omit task text from the tree payload and keep it in the summary footer.
        var s = SchedulerState.empty()
        val c = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c, "P"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskText(s.cells[c]!!.taskId!!, "a\n\nb"))

        val text = SchedulerDomain.copyCellsText(s, listOf(c), maxDepth = 20)
        assertTrue(text.contains("Copied tasks:"), text)
        assertTrue(text.contains("- P: minimum time: 45 min, id: ${s.cells[c]!!.taskId!!.value}, text: a\\n\\nb"), text)
        assertTrue(!text.contains("\t- text:\n"), text)

        // Older clipboard payloads with a text block still parse for compatibility.
        val legacy = "P\n\t- text:\n\ta\n\n\tb"
        val nodes = SchedulerDomain.parseTreeText(legacy)!!
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
    fun copy_carries_the_resilience_map_the_schedule_unit_and_the_text_summary() {
        val (s, cP) = stateWithParentAndChild()
        val text = SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20)
        val node = SchedulerDomain.parseTreeText(text)!!.single()
        // The tree payload no longer carries raw task notes; the human-readable summary below it does.
        assertEquals(emptyMap(), node.resilience)
        assertEquals(listOf(ScheduleUnitEntry("warm up", 5), ScheduleUnitEntry("run", 25)), node.scheduleUnit)
        assertTrue(!text.contains("\t- text:\n"), text)
        val childListId = s.tasks[s.cells[cP]!!.taskId!!]!!.childListId!!
        val childCellId = s.lists[childListId]!!.cellIds.first { s.cells[it]?.taskId != null }
        val childId = s.cells[childCellId]!!.taskId!!.value
        assertTrue(text.contains("- P: minimum time: 45 min, id: ${s.cells[cP]!!.taskId!!.value}, text: line one\\nline two"), text)
        assertTrue(text.contains("- C1: minimum time: 45 min, id: $childId, text: child note"), text)
        assertEquals("", node.text)
        assertEquals("", node.children.single().text)
    }

    @Test
    fun deep_copy_pasted_back_restores_every_edit_window_field() {
        val (s, cP) = stateWithParentAndChild()
        val (dst, target) = pasteIntoFreshTree(SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20))

        val newP = dst.tasks[dst.cells[target]!!.taskId!!]!!
        assertEquals("P", newP.title)
        assertEquals(false, newP.onScreen)
        assertEquals(listOf(ScheduleUnitEntry("warm up", 5), ScheduleUnitEntry("run", 25)), newP.scheduleUnit)
        assertEquals("", newP.text)

        val newChildList = newP.childListId!!
        val newC1cell = dst.lists[newChildList]!!.cellIds.first { dst.cells[it]!!.taskId != null }
        val newC1 = dst.tasks[dst.cells[newC1cell]!!.taskId!!]!!
        assertEquals("C1", newC1.title)
        // C1 was never switched off-screen, so it must come back on-screen (the default).
        assertEquals(true, newC1.onScreen)
        assertEquals("", newC1.text)
    }

    @Test
    fun an_on_screen_task_writes_no_switch_field_and_pastes_back_on_screen() {
        var s = SchedulerState.empty()
        val c = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c, "Plain"))
        val text = SchedulerDomain.copyCellsText(s, listOf(c), maxDepth = 1)
        // The default (on-screen) is omitted, so an ordinary task is its title, its id and its minimum time.
        assertTrue(text.startsWith("Plain\n"), text)
        assertTrue(text.contains("\t- id: ${s.cells[c]!!.taskId!!.value}"), text)
        assertTrue(text.contains("\t- minimum time: 45 min"), text)
        assertTrue(text.contains("Copied tasks:\n- Plain: minimum time: 45 min, id: ${s.cells[c]!!.taskId!!.value}"), text)
        val (dst, target) = pasteIntoFreshTree(text)
        assertEquals(true, dst.tasks[dst.cells[target]!!.taskId!!]!!.onScreen)
    }

    /**
     * A clipboard cut before the 2026-09-12 rename spells the kind `no on-screen task`, and the paste must
     * land it on [PeriodKinds.NO_SCREEN] — the same migration a stored payload's resilience map gets
     * ([PeriodKinds.migrateStoredKind]), because the clipboard is a stored payload that happens to live in
     * the OS.
     *
     * Read as its literal self it would be a USER-DEFINED kind of that name, whose default is `0` — so the
     * `0 %` that makes the task ON-SCREEN reads as redundant, is dropped, and the task pastes back
     * off-screen: free to be scheduled inside the very no-screen periods it was being kept out of. That is
     * the failure the codec's own migration exists to prevent, arriving by the other door.
     */
    @Test
    fun a_clipboard_naming_a_kind_by_its_old_spelling_pastes_onto_that_kind() {
        val sep = SchedulerDomain.COPY_SECTION_SEPARATOR
        val old = "P" +
            "\n\t- resilience to no on-screen task: 0 %" +
            "\n\t- minimum time: 45 min" +
            "\n$sep\nP\t45"
        val p = SchedulerDomain.parseTreeText(old)!!.single()
        assertEquals(mapOf(PeriodKinds.NO_SCREEN to 0.0), p.resilience)
        assertTrue("no on-screen task" !in p.resilience)

        val (dst, target) = pasteIntoFreshTree(old)
        assertEquals(true, dst.tasks[dst.cells[target]!!.taskId!!]!!.onScreen)
        // And the stale spelling mints no kind of its own on the way in.
        assertTrue(dst.periodKinds.none { it == "no on-screen task" })
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
        // on screen, i.e. a 0 against PeriodKinds.NO_SCREEN.
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
        assertTrue(text.contains("- resilience to ${PeriodKinds.NO_SCREEN}: 100 %"), text)
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

    // ----- PRD §4/§13 "copy task id (ctrl c)": the chord and the menu entry -----------------------

    @Test
    fun ctrl_c_copies_the_task_id_alone_in_a_shape_nothing_else_writes() {
        // PRD §4/§13: Ctrl+C is now the identity, not the sub-tree — one line no other application writes,
        // so a paste can tell it from text the user copied somewhere else.
        var s = stateWithTwoBranches()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        val aTask = s.cells[cA]!!.taskId!!
        s = click(s, cA)

        val text = SchedulerDomain.taskIdReferenceText(s, SchedulerDomain.copyTreeTargets(s, s.selection))
        assertEquals(SchedulerDomain.TASK_ID_REFERENCE_PREFIX + aTask.value, text)
        assertTrue(!text.contains("minimum time"), text)
        assertTrue(!text.contains("A1"), text)
        // The intent writes that very text to the app's clipboard.
        assertEquals(text.split('\n'), SchedulerReducer.reduce(s, SchedulerIntent.CopySelection).clipboard)
        // Ctrl+X is unchanged: a cut has to carry back everything it deleted.
        assertEquals(
            SchedulerDomain.copyTreeText(s, s.selection).split('\n'),
            SchedulerReducer.reduce(s, SchedulerIntent.CutSelection).clipboard,
        )
    }

    @Test
    fun a_copied_task_id_parses_back_to_a_bare_reference() {
        val node =
            SchedulerDomain.parseTreeText(SchedulerDomain.TASK_ID_REFERENCE_PREFIX + "task/user/7")!!.single()
        assertEquals(org.example.project.scheduler.model.TaskId("task/user/7"), node.taskId)
        assertTrue(node.reference)
        assertEquals("", node.title)
        assertTrue(node.children.isEmpty())
        // Only the id shape the app itself mints — never the tree's own root/main ids.
        assertEquals(null, SchedulerDomain.parseTreeText(SchedulerDomain.TASK_ID_REFERENCE_PREFIX + "task/root"))
        assertEquals(null, SchedulerDomain.parseTreeText(SchedulerDomain.TASK_ID_REFERENCE_PREFIX + "hello"))
    }

    @Test
    fun a_pasted_task_id_points_the_cell_at_that_task() {
        // PRD §13: the whole purpose of the format — pasted onto a cell it puts the task id there, which is
        // the mirror the sub-list-belongs-to-the-task-id rule already means.
        var s = stateWithTwoBranches()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        val aTask = s.cells[cA]!!.taskId!!
        s = click(s, cA)
        val text = SchedulerDomain.taskIdReferenceText(s, SchedulerDomain.copyTreeTargets(s, s.selection))

        val cX = s.lists[s.rootListId]!!.cellIds[1]
        val underX = s.lists[s.tasks[s.cells[cX]!!.taskId!!]!!.childListId!!]!!.cellIds.last()
        s = click(s, underX)
        val tasksBefore = s.tasks.size
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree(text))

        assertEquals(aTask, s.cells[underX]!!.taskId)
        assertEquals(tasksBefore, s.tasks.size, "a reference creates no task")
        assertEquals("A", s.tasks[aTask]!!.title)
        assertEquals(setOf(cA, underX), s.tasks[aTask]!!.occurrences.toSet())
        // The sub-list belongs to the task id, so A's own children show under the new cell too.
        assertEquals(listOf("A1"), childTitles(s, aTask))
    }

    @Test
    fun a_task_id_reference_that_cannot_be_mirrored_is_a_no_op() {
        // A reference carries no title and no field, so Restore and Fresh have nothing to build: an id the
        // cell cannot hold, or one naming no live task, must leave the tree exactly as it was rather than
        // mint a blank-titled husk (a blank title is what DELETES, PRD §4).
        val base = stateWithTwoBranches()
        val cA = base.lists[base.rootListId]!!.cellIds[0]
        val aTask = base.cells[cA]!!.taskId!!
        val cX = base.lists[base.rootListId]!!.cellIds[1]
        val xTask = base.cells[cX]!!.taskId!!
        val s = click(base, cX)

        // A already sits in this very sub-list, so it may not sit in it twice.
        val duplicated =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.PasteTree(SchedulerDomain.TASK_ID_REFERENCE_PREFIX + aTask.value),
            )
        assertEquals(xTask, duplicated.cells[cX]!!.taskId)
        assertEquals(s.tasks.size, duplicated.tasks.size)
        assertEquals(setOf(cA), duplicated.tasks[aTask]!!.occurrences.toSet())

        // An id naming no task at all.
        val unknown =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.PasteTree(SchedulerDomain.TASK_ID_REFERENCE_PREFIX + "task/user/999"),
            )
        assertEquals(xTask, unknown.cells[cX]!!.taskId)
        assertEquals(s.tasks.size, unknown.tasks.size)
    }

    @Test
    fun the_menu_entry_and_the_chord_write_the_same_ids_for_a_block() {
        // PRD §13: right-clicking inside a multi-selection takes the whole block, exactly as Ctrl+C does —
        // one line per DISTINCT task, in the list's order — and pasted back they mirror every one of them.
        var s = SchedulerState.empty()
        val root = s.rootListId
        val cA = s.lists[root]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA, "A"))
        val cB = s.lists[root]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cB, "B"))
        val cC = s.lists[root]!!.cellIds[2]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cC, "C"))
        val aTask = s.cells[cA]!!.taskId!!
        val bTask = s.cells[cB]!!.taskId!!
        val visible = SchedulerDomain.selectableVisibleOrder(s)
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cA, ctrl = false, shift = false, visibleOrder = visible),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(cellId = cB, ctrl = false, shift = true, visibleOrder = visible),
        )

        val menuTargets = SchedulerDomain.contextMenuCopyTargets(s, s.selection, cB)
        assertEquals(listOf(cA, cB), menuTargets)
        val text = SchedulerDomain.taskIdReferenceText(s, menuTargets)
        assertEquals(
            listOf(
                SchedulerDomain.TASK_ID_REFERENCE_PREFIX + aTask.value,
                SchedulerDomain.TASK_ID_REFERENCE_PREFIX + bTask.value,
            ),
            text.split('\n'),
        )
        assertEquals(text, SchedulerDomain.taskIdReferenceText(s, SchedulerDomain.copyTreeTargets(s, s.selection)))

        // Pasted under C, both come back as mirrors, side by side.
        val underC = s.lists[s.tasks[s.cells[cC]!!.taskId!!]!!.childListId!!]!!.cellIds.first()
        s = click(s, underC)
        val tasksBefore = s.tasks.size
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree(text))
        assertEquals(listOf("A", "B"), childTitles(s, s.cells[cC]!!.taskId!!))
        assertEquals(tasksBefore, s.tasks.size, "mirrors create no task")
    }

    @Test
    fun a_title_that_reads_like_a_task_id_reference_still_travels_as_a_title() {
        // The format's one ambiguity, closed the way the attribute lines already close theirs: the title is
        // escaped on the way out, so a copied sub-tree is never read back as a bare id reference.
        var s = SchedulerState.empty()
        val cA = s.lists[s.rootListId]!!.cellIds[0]
        val looksLikeOne = SchedulerDomain.TASK_ID_REFERENCE_PREFIX + "task/user/3"
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cA, looksLikeOne))
        val text = SchedulerDomain.copyCellsText(s, listOf(cA), maxDepth = 1)
        assertTrue(!text.startsWith(SchedulerDomain.TASK_ID_REFERENCE_PREFIX), text)

        val node = SchedulerDomain.parseTreeText(text)!!.single()
        assertTrue(!node.reference)
        assertEquals(looksLikeOne, node.title)
        assertEquals(s.cells[cA]!!.taskId, node.taskId)
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
