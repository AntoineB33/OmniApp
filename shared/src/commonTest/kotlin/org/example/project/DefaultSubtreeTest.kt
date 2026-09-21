package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.persistence.HistoryRow
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.defaultSubtreeIsEmpty
import org.example.project.scheduler.state.defaultSubtreePriorities
import org.example.project.scheduler.state.isTitledDefaultSubtreeRow
import org.example.project.scheduler.state.projectDefaultSubtree

/**
 * PRD §4/§7 **Default sub-tree**: the template grafted under every task the user creates, and the lateral-menu
 * switch that says whether the policy is applied.
 *
 * Covers the graft itself (both the direct `SetCellTitle` path and a real Edit-Mode session), the two ways a
 * node can appear — a brand-new task id ("New id", the switch on) or a binding to one existing task (the
 * switch off, which mirrors that task's own sub-tree) — the gates that keep the graft from firing where it
 * must not, that one Ctrl+Z takes the whole seeded sub-tree back with the title that pulled it in, and — per
 * the persisted-DB compatibility rule — that a payload written before the feature existed decodes to "no
 * template, switch off" while a template round-trips.
 *
 * Also the two clipboard rules (§13): pasting FOREIGN text creates a task, so it seeds — but a payload the app
 * wrote carries an id, and a copy of a sub-tree comes back as itself however that id resolves. And the menu's
 * on-demand "add default sub-tree", which ignores the switch and appends.
 */
class DefaultSubtreeTest {

    /**
     * A template row, for the fixtures below. The template itself is a real tree now, so this is only a
     * convenient description of one — [withTemplate] builds it by driving the SAME intents the window sends,
     * which is what makes these fixtures exercise the projection round-trip rather than a hand-built tree.
     *
     * [id] is vestigial (the pre-1.6.0 template keyed its rows by a handle of its own); it is ignored.
     */
    private data class Row(val title: String, val taskId: TaskId?, val children: List<Row>)

    private fun node(id: String, title: String, taskId: TaskId? = null, children: List<Row> = emptyList()) =
        Row(title = title, taskId = taskId, children = children)

    /** The root list's first (empty) cell of a fresh account. */
    private fun firstCell(state: SchedulerState): CellId = state.lists[state.rootListId]!!.cellIds.first()

    /** The titles of the cells under [cellId], in list order, ignoring the trailing empty placeholder. */
    private fun childTitles(state: SchedulerState, cellId: CellId): List<String> {
        val taskId = state.cells[cellId]?.taskId ?: return emptyList()
        val listId = state.tasks[taskId]?.childListId ?: return emptyList()
        return state.lists[listId]!!.cellIds
            .mapNotNull { state.cells[it]?.taskId }
            .mapNotNull { state.tasks[it]?.title }
            .filter { it.isNotBlank() }
    }

    /** The template sub-list [cellId] parents. */
    private fun childListOf(state: SchedulerState, cellId: CellId): CellListId {
        val tree = state.defaultSubtree.tree
        val taskId = tree.cells[cellId]!!.taskId!!
        return (tree.tasks[taskId] ?: state.tasks[taskId])!!.childListId!!
    }

    /** A press on a template row, through the window's own intent. */
    private fun clickTemplateCell(state: SchedulerState, cellId: CellId, ctrl: Boolean): SchedulerState =
        reduceInTemplate(
            state,
            SchedulerIntent.ClickCell(
                cellId = cellId,
                ctrl = ctrl,
                shift = false,
                visibleOrder = SchedulerDomain.selectableVisibleOrder(state.projectDefaultSubtree()),
            ),
        )

    private fun childCells(state: SchedulerState, cellId: CellId): List<CellId> {
        val taskId = state.cells[cellId]!!.taskId!!
        val listId = state.tasks[taskId]!!.childListId!!
        return state.lists[listId]!!.cellIds.filter { state.cells[it]?.taskId != null }
    }

    /**
     * An account with the given template, the policy on — built through [SchedulerIntent.InDefaultSubtree],
     * i.e. exactly the intents the "Default sub-tree" window raises when the rows are typed into it.
     */
    private fun withTemplate(rows: List<Row>, from: SchedulerState = SchedulerState.empty()): SchedulerState {
        var s = from
        fun build(rows: List<Row>, listId: CellListId) {
            for (row in rows) {
                // The trailing empty placeholder Auto-Expansion keeps, exactly as the window would type into.
                val target =
                    s.defaultSubtree.tree.lists[listId]!!.cellIds
                        .last { s.defaultSubtree.tree.cells[it]?.taskId == null }
                if (row.taskId != null) {
                    s = reduceInTemplate(s, SchedulerIntent.AssignTaskId(target, row.taskId))
                    s = reduceInTemplate(s, SchedulerIntent.SetDefaultSubtreeCellBound(target, bound = true))
                }
                s = reduceInTemplate(s, SchedulerIntent.SetCellTitle(target, row.title))
                if (row.children.isEmpty()) continue
                val childListId =
                    s.defaultSubtree.tree.cells[target]?.taskId
                        ?.let { s.defaultSubtree.tree.tasks[it]?.childListId } ?: continue
                build(row.children, childListId)
            }
        }
        build(rows, WellKnownIds.ROOT_LIST)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(true))
        // Fixture setup, not user actions: hand back the history the caller started with so a test can count
        // the units ITS OWN gesture recorded.
        return s.copy(histories = from.histories)
    }

    /** [SchedulerIntent.SetDefaultSubtreeCellBound] is already about the template; everything else wraps. */
    private fun reduceInTemplate(state: SchedulerState, intent: SchedulerIntent): SchedulerState =
        SchedulerReducer.reduce(
            state,
            if (intent is SchedulerIntent.SetDefaultSubtreeCellBound) intent
            else SchedulerIntent.InDefaultSubtree(intent),
        )

    /**
     * The expand arrow — the gesture that PAYS what a cell owes (PRD 4 *Default sub-tree*), so it is what
     * every test below uses to make the next round of the template exist.
     */
    private fun open(state: SchedulerState, cellId: CellId): SchedulerState =
        SchedulerReducer.reduce(state, SchedulerIntent.ToggleExpand(cellId))

    /** [open], through the template window's own wrapper. */
    private fun openInTemplate(state: SchedulerState, cellId: CellId): SchedulerState =
        reduceInTemplate(state, SchedulerIntent.ToggleExpand(cellId))

    /** The titles of the template's top-level rows, ignoring the trailing empty placeholder. */
    private fun templateTitles(state: SchedulerState, listId: CellListId = WellKnownIds.ROOT_LIST): List<String> {
        val tree = state.defaultSubtree.tree
        return tree.lists[listId]?.cellIds.orEmpty()
            .mapNotNull { tree.cells[it]?.taskId }
            .mapNotNull { tree.tasks[it]?.title }
            .filter { it.isNotBlank() }
    }

    // ---- the graft -----------------------------------------------------------------------------

    @Test
    fun naming_an_empty_cell_grafts_the_template_under_the_new_task() {
        val s0 = withTemplate(listOf(node("dst/0", "Plan"), node("dst/1", "Do")))
        val cell = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))

        // The rows are OWED, not written: nothing sits under the new task until the cell is opened, which is
        // what keeps a task nobody has looked into a schedulable leaf.
        assertEquals(emptyList(), childTitles(s, cell), "the template is a promise until the cell is opened")
        assertTrue(SchedulerDomain.isLeafTask(s, s.cells[cell]!!.taskId!!))
        s = open(s, cell)

        assertEquals(listOf("Plan", "Do"), childTitles(s, cell))
        // Each row got a task of its own, distinct from the parent's.
        val ids = childCells(s, cell).map { s.cells[it]!!.taskId!! }
        assertEquals(ids.toSet().size, ids.size)
        assertFalse(s.cells[cell]!!.taskId!! in ids)
        // PRD §4: the parent knows its children (the denormalized index the menus read).
        assertEquals(ids, s.tasks[s.cells[cell]!!.taskId!!]!!.childTaskIds)
    }

    @Test
    fun the_template_is_grafted_at_every_level() {
        val s0 =
            withTemplate(
                listOf(node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch"), node("dst/2", "Review")))),
            )
        val cell = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))
        s = open(s, cell)

        assertEquals(listOf("Plan"), childTitles(s, cell))
        val plan = childCells(s, cell).single()
        // One level per gesture: the row's own children are owed until the row is opened in turn.
        assertEquals(emptyList(), childTitles(s, plan), "a row writes one level, the next is a promise")
        s = open(s, plan)
        // Its template children first, then the template's root rows — because a row the graft wrote is a
        // new task id too, and the policy is about every one of them.
        assertEquals(listOf("Sketch", "Review", "Plan"), childTitles(s, plan))
    }

    @Test
    fun one_gesture_writes_one_round_however_deep_the_template_is() {
        // The template appears under every new task id, the rows the graft itself writes included — so what
        // the rule describes has no bottom, and writing ONE round is the only thing that can bound a
        // gesture. Written eagerly, that is the fractal which turned four rows into 41 tasks (2026-09-21).
        val s0 =
            withTemplate(
                listOf(node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch"))), node("dst/2", "Do")),
            )
        val cell = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))
        s = open(s, cell)

        val (plan, do_) = childCells(s, cell)
        assertEquals(emptyList(), childTitles(s, plan), "what is under a written row is owed, not written")
        assertEquals(emptyList(), childTitles(s, do_))
        // Exactly the named task plus one per row of the template's ROOT list — one round, nothing deeper.
        assertEquals(
            s0.tasks.size + 3,
            s.tasks.size,
            "one task per root row of the template plus the one the user named",
        )
        // ...and the round under a row that has no template children of its own is the template itself,
        // which is what makes the structure endless.
        s = open(s, do_)
        assertEquals(listOf("Plan", "Do"), childTitles(s, do_))
    }

    @Test
    fun an_edit_session_grafts_only_under_the_cell_it_edited() {
        // Same guarantee through the session path, which seeds at ExitEdit: the seeded rows are not sessions
        // of their own, so none of them is a "task the user created" and none pulls the template in again.
        val s0 =
            withTemplate(
                listOf(node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch"))), node("dst/2", "Do")),
            )
        val cell = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.BeginEdit(cell, initialText = "Project"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Down))
        s = open(s, cell)

        assertEquals(listOf("Plan", "Do"), childTitles(s, cell))
        val (plan, do_) = childCells(s, cell)
        assertEquals(emptyList(), childTitles(s, plan))
        assertEquals(emptyList(), childTitles(s, do_))
        assertEquals(
            s0.tasks.size + 3,
            s.tasks.size,
            "one task per root row of the template plus the one the user named — nothing more",
        )
    }

    @Test
    fun each_application_mints_fresh_task_ids() {
        // The switch ON means "a brand new task id", so two cells seeded from the same template must NOT
        // share their children — they are separate tasks that merely start out with the same names.
        val s0 = withTemplate(listOf(node("dst/0", "Plan")))
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(firstCell(s0), "First"))
        val second = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(second, "Second"))
        s = open(open(s, firstCell(s0)), second)

        val firstPlan = childCells(s, firstCell(s0)).single().let { s.cells[it]!!.taskId }
        val secondPlan = childCells(s, second).single().let { s.cells[it]!!.taskId }
        assertNotNull(firstPlan)
        assertNotNull(secondPlan)
        assertTrue(firstPlan != secondPlan, "each application must mint its own task id")
    }

    @Test
    fun a_node_bound_to_an_existing_task_mirrors_it_instead_of_minting() {
        // Build "Shared" with a child, then bind a template node to it: the seeded cell must point at the
        // SAME task id and therefore show that task's own sub-tree (a sub-list belongs to the task id).
        var s = SchedulerState.empty()
        val sharedCell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(sharedCell, "Shared"))
        val sharedTask = s.cells[sharedCell]!!.taskId!!
        val sharedChildList = s.tasks[sharedTask]!!.childListId!!
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetCellTitle(s.lists[sharedChildList]!!.cellIds.first(), "Inherited"),
        )

        s = withTemplate(listOf(node("dst/0", "Shared", sharedTask)), from = s)

        val target = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(target, "Project"))
        s = open(s, target)

        val seeded = childCells(s, target).single()
        assertEquals(sharedTask, s.cells[seeded]!!.taskId)
        assertEquals(listOf("Inherited"), childTitles(s, seeded))
        // A mirror owes nothing: the task is not new, and its sub-list came with the id.
        assertEquals(emptyList(), s.tasks[sharedTask]!!.pendingDefaultSubtree)
    }

    @Test
    fun a_binding_this_tree_cannot_honour_falls_back_to_a_new_task() {
        // A template is account-wide but a task id lives in one task tree, so a stale/foreign binding must
        // still produce the row rather than silently dropping it.
        val s0 = withTemplate(listOf(node("dst/0", "Plan", TaskId("task/user/999"))))
        val cell = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))
        s = open(s, cell)

        assertEquals(listOf("Plan"), childTitles(s, cell))
        assertTrue(s.tasks[TaskId("task/user/999")] == null)
    }

    // ---- the gates -----------------------------------------------------------------------------

    @Test
    fun nothing_is_grafted_while_the_policy_switch_is_off() {
        var s = SchedulerState.empty()
        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(false))
        val cell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Project"))

        assertEquals(emptyList(), childTitles(s, cell))
        assertEquals(emptyList(), s.tasks[s.cells[cell]!!.taskId!!]!!.pendingDefaultSubtree, "nothing is owed")
        s = open(s, cell)
        assertEquals(emptyList(), childTitles(s, cell), "and opening it pays nothing either")
    }

    @Test
    fun the_switch_is_read_when_the_promise_is_PAID_not_when_it_was_made() {
        // PRD 7 calls it "whether the policy is CURRENTLY applied", so a promise waits while it is off and
        // is paid when it comes back on, rather than being spent against a policy nobody has switched on.
        var s = withTemplate(listOf(node("dst/0", "Plan")))
        val cell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Project"))
        val owed = s.tasks[s.cells[cell]!!.taskId!!]!!.pendingDefaultSubtree
        assertEquals(listOf(WellKnownIds.ROOT_LIST), owed)

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(false))
        s = open(s, cell)
        assertEquals(emptyList(), childTitles(s, cell), "the policy is off, so no rows appear")
        assertEquals(owed, s.tasks[s.cells[cell]!!.taskId!!]!!.pendingDefaultSubtree, "the promise waits")

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(true))
        s = open(open(s, cell), cell) // close, then open again
        assertEquals(listOf("Plan"), childTitles(s, cell))
    }

    @Test
    fun a_sub_list_the_user_has_built_drops_the_promise_unpaid() {
        // The template has nothing to add to a sub-tree the user wrote — the same condition the graft
        // checks before promising anything, asked again when the promise would be paid.
        var s = withTemplate(listOf(node("dst/0", "Plan")))
        val cell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Project"))
        val childList = s.tasks[s.cells[cell]!!.taskId!!]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[childList]!!.cellIds.first(), "Mine"))

        s = open(s, cell)

        assertEquals(listOf("Mine"), childTitles(s, cell), "the template must not land beside the user's rows")
        assertEquals(emptyList(), s.tasks[s.cells[cell]!!.taskId!!]!!.pendingDefaultSubtree)
    }

    @Test
    fun a_row_typed_in_the_template_window_owes_the_template_like_any_other() {
        // The rule is the same in all three drawings of the tree: a new task id is a new task id. Inside the
        // template that is self-referential, which is exactly why the rows are OWED and not written — the
        // eager graft turned the release account's four-row template into 41 tasks nested
        // `planning / AI / planning / AI / ...`, doubling with every row typed (2026-09-21, account 3).
        var s = withTemplate(listOf(node("dst/0", "planning")))
        assertTrue(s.defaultSubtreeEnabled)
        val planning = s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds.first()
        val planningList = childListOf(s, planning)
        val tasksBefore = s.defaultSubtree.tree.tasks.size

        // Type into planning's own trailing placeholder — exactly what the window sends.
        val target = s.defaultSubtree.tree.lists[planningList]!!.cellIds.last()
        s = reduceInTemplate(s, SchedulerIntent.SetCellTitle(target, "AI"))

        assertEquals(listOf("AI"), templateTitles(s, planningList))
        assertEquals(emptyList(), templateTitles(s, childListOf(s, target)), "owed, not written")
        assertEquals(tasksBefore + 1, s.defaultSubtree.tree.tasks.size, "one task per row typed, and no more")
        assertEquals(
            listOf(WellKnownIds.ROOT_LIST),
            s.defaultSubtree.tree.tasks[s.defaultSubtree.tree.cells[target]!!.taskId!!]!!.pendingDefaultSubtree,
        )

        // Opening it writes ONE round — the template's root rows — into the template itself.
        s = openInTemplate(s, target)
        assertEquals(listOf("planning"), templateTitles(s, childListOf(s, target)))
        assertEquals(tasksBefore + 2, s.defaultSubtree.tree.tasks.size, "one round, not a copy of everything")
    }

    @Test
    fun an_edit_session_in_the_template_window_owes_it_too() {
        // The route the user actually took: a session, which promises at `endEditSession`.
        var s = withTemplate(listOf(node("dst/0", "planning")))
        val planningList = childListOf(s, s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds.first())
        val target = s.defaultSubtree.tree.lists[planningList]!!.cellIds.last()

        s = reduceInTemplate(s, SchedulerIntent.BeginEdit(target, initialText = "AI"))
        s = reduceInTemplate(s, SchedulerIntent.ExitEdit(EditExitNavigation.Down))

        assertEquals(listOf("AI"), templateTitles(s, planningList))
        assertEquals(emptyList(), templateTitles(s, childListOf(s, target)))
        s = openInTemplate(s, target)
        assertEquals(listOf("planning"), templateTitles(s, childListOf(s, target)))
    }

    @Test
    fun add_default_sub_tree_works_inside_the_template_window_too() {
        // 13's entry is the ASKING, so it writes its round there and then rather than waiting to be opened
        // — and PRD 4 lists it among the window's own menu entries.
        var s = withTemplate(listOf(node("dst/0", "planning")))
        val planning = s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds.first()
        val planningList = childListOf(s, planning)
        val target = s.defaultSubtree.tree.lists[planningList]!!.cellIds.last()
        s = reduceInTemplate(s, SchedulerIntent.SetCellTitle(target, "AI"))
        assertEquals(emptyList(), templateTitles(s, childListOf(s, target)), "typing only owes it")

        s = reduceInTemplate(s, SchedulerIntent.AddDefaultSubtree(listOf(target)))

        assertEquals(listOf("planning"), templateTitles(s, childListOf(s, target)), "asking writes it now")
    }

    @Test
    fun renaming_an_existing_task_does_not_graft_again() {
        val s0 = withTemplate(listOf(node("dst/0", "Plan")))
        val cell = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Project renamed"))
        s = open(s, cell)

        assertEquals(listOf("Plan"), childTitles(s, cell), "the rename owes nothing of its own")
    }

    // ---- the edit session ----------------------------------------------------------------------

    @Test
    fun an_edit_session_grafts_once_at_the_end_and_undoes_as_one_step() {
        val s0 = withTemplate(listOf(node("dst/0", "Plan"), node("dst/1", "Do")))
        val cell = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.BeginEdit(cell, initialText = "P"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("Pr"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.UpdateEditText("Project"))
        // Still mid-session: nothing seeded yet, so the keystrokes never fight the template.
        assertEquals(emptyList(), childTitles(s, cell))

        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Down))
        assertEquals(
            listOf(WellKnownIds.ROOT_LIST),
            s.tasks[s.cells[cell]!!.taskId!!]!!.pendingDefaultSubtree,
            "the session owes the template once, at its end",
        )
        // Creating a task is not asking to see the template unfold under it: the cell stays COLLAPSED, so
        // the row just typed keeps its place instead of jumping down behind rows the user did not write.
        assertFalse(cell in s.expanded, "creating a task must not expand its cell")

        // The whole session is ONE unit, so one Ctrl+Z takes the title and everything it pulled in.
        assertEquals(1, s.histories.forCategory(HistoryCategory.Main).units.size)
        val undone = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertNull(undone.cells[cell]!!.taskId)
        assertEquals(emptyList(), childTitles(undone, cell))
    }

    @Test
    fun creating_a_task_never_expands_its_cell_even_when_the_template_seeds_rows() {
        // The anomaly: with the default sub-tree on, every task typed into a cell unfolded the template
        // under it. Neither of the two creation paths — the SetCellTitle primitive and a whole edit session
        // — may add the cell to the expansion set.
        val s0 = withTemplate(listOf(node("dst/0", "Plan"), node("dst/1", "Do")))
        val cell = firstCell(s0)

        val typed = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))
        assertFalse(cell in typed.expanded)

        var s = SchedulerReducer.reduce(s0, SchedulerIntent.BeginEdit(cell, initialText = "Project"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Down))
        assertFalse(cell in s.expanded)

        // And the arrow still opens it afterwards — which is also what writes the rows it owes.
        s = open(s, cell)
        assertTrue(cell in s.expanded)
        assertEquals(listOf("Plan", "Do"), childTitles(s, cell))
    }

    @Test
    fun expanding_the_cell_being_edited_seeds_it_instead_of_opening_onto_nothing() {
        // The graft fires at the END of the session, so the arrow clicked while still typing used to open the
        // freshly named task onto its bare placeholder — the template only turned up after a click elsewhere
        // had ended the session. Asking for the sub-tree is a forced exit (PRD §4), so it seeds it first.
        val s0 = withTemplate(listOf(node("dst/0", "Plan"), node("dst/1", "Do")))
        val cell = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.BeginEdit(cell, initialText = "Project"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cell))

        assertNull(s.editSession, "asking for the sub-tree ends the session")
        assertEquals(listOf("Plan", "Do"), childTitles(s, cell))
        assertTrue(cell in s.expanded, "the click asked for the sub-tree — it must be open")
        // Three units, and each is a gesture of its own: the session ("Edit"), the rows the opening paid
        // for ("Default sub-tree") and the toggle itself. The rows cannot ride the toggle — a
        // ToggleExpandDelta undoes by expanding again, so it can carry no tree mutation.
        assertEquals(3, s.histories.forCategory(HistoryCategory.Main).units.size)
    }

    @Test
    fun expanding_another_cell_while_editing_seeds_the_edited_one_and_still_opens_the_other() {
        val s0 = withTemplate(listOf(node("dst/0", "Plan")))
        val other = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(other, "Other"))
        val cell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cell, initialText = "Project"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(other))

        assertNull(s.editSession)
        assertEquals(
            listOf(WellKnownIds.ROOT_LIST),
            s.tasks[s.cells[cell]!!.taskId!!]!!.pendingDefaultSubtree,
            "the forced exit made the edited cell owe the template",
        )
        assertTrue(other in s.expanded, "the arrow that was clicked still opens its own cell")
        assertEquals(listOf("Plan"), childTitles(s, other), "...and paid what THAT cell owed")
    }

    @Test
    fun collapsing_the_cell_being_edited_still_collapses_it() {
        // The forced exit seeds and leaves the cell collapsed, so the first click opens it; the second one,
        // after re-entering Edit Mode, must close it again rather than re-open what is already open.
        val s0 = withTemplate(listOf(node("dst/0", "Plan")))
        val cell = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.BeginEdit(cell, initialText = "Project"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cell))
        assertTrue(cell in s.expanded)

        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(cell))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cell))
        assertFalse(cell in s.expanded)
        assertEquals(listOf("Plan"), childTitles(s, cell), "and nothing is paid twice")
    }

    // ---- the menu / the switch -----------------------------------------------------------------

    @Test
    fun the_windows_change_task_menu_is_the_trees_own_and_offers_live_tasks() {
        // PRD §4: pointing a template row at an existing task is the tree's ordinary Change Task menu, not a
        // menu of its own. That works because the projection merges the live tasks in underneath.
        var s = SchedulerState.empty()
        val cell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Shared"))
        val shared = s.cells[cell]!!.taskId!!

        val projected = s.projectDefaultSubtree()
        val templateCell = projected.lists[projected.rootListId]!!.cellIds.first()
        val entries = SchedulerDomain.changeTaskMenuEntries(projected, templateCell, "Shared")

        assertTrue(entries.any { it.taskId == shared }, "a live task must be offerable to a template row")
    }

    @Test
    fun emptying_a_row_bound_to_a_live_task_empties_the_ROW_and_leaves_the_TASK_alone() {
        // PRD §4: the blank title is what deletes — but the title of a row whose switch is off belongs to the
        // LIVE task it mirrors, and the fold discards every change to that task. Blanking it emptied nothing:
        // the row came back reading its old title, and the trailing placeholder the cleanup dropped beneath it
        // (an emptied cell becomes its list's bottom one) stayed dropped, so the sub-list had nowhere left to
        // type (2026-09-17, account 3).
        var s = SchedulerState.empty()
        val liveCell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(liveCell, "writing"))
        val live = s.cells[liveCell]!!.taskId!!
        s = withTemplate(listOf(node("dst/0", "writing", live)), from = s)

        val row = s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds.first()
        s = reduceInTemplate(
            s,
            SchedulerIntent.ClickCell(
                cellId = row,
                ctrl = false,
                shift = false,
                visibleOrder = SchedulerDomain.selectableVisibleOrder(s.projectDefaultSubtree()),
            ),
        )
        s = reduceInTemplate(s, SchedulerIntent.EmptySelectedCells)

        assertEquals(emptyList(), templateTitles(s), "the row the user deleted must be gone")
        assertEquals("writing", s.tasks[live]?.title, "the live task is not the template's to rename")
        // ... and the sub-list still ends in exactly ONE empty cell, so it can be typed into again.
        val remaining = s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds
        assertEquals(1, remaining.size)
        assertNull(s.defaultSubtree.tree.cells[remaining.single()]?.taskId)
    }

    @Test
    fun a_template_sub_list_left_without_its_trailing_placeholder_gets_one_back() {
        // The heal for the accounts the above already damaged: a sub-list ending in a TITLED row has nowhere
        // left to type, so it could never be added to again. Put back on load, as `settleDefaultSubtree` is
        // run by the codec's decode as well as after every reduction.
        val s = withTemplate(listOf(node("dst/0", "Plan")))
        val tree = s.defaultSubtree.tree
        val ids = tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds
        val placeholder = ids.last()
        assertNull(tree.cells[placeholder]?.taskId, "the fixture's list ends with the placeholder")
        val stripped =
            s.copy(
                defaultSubtree =
                    s.defaultSubtree.copy(
                        tree =
                            tree.copy(
                                cells = tree.cells - placeholder,
                                lists =
                                    tree.lists +
                                        (
                                            WellKnownIds.ROOT_LIST to
                                                tree.lists[WellKnownIds.ROOT_LIST]!!
                                                    .copy(cellIds = ids.dropLast(1))
                                            ),
                            ),
                    ),
            )

        val healed = SchedulerReducer.settleDefaultSubtree(stripped)

        val healedIds = healed.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds
        assertEquals(listOf("Plan"), templateTitles(healed), "healing adds a row, it never removes one")
        assertEquals(2, healedIds.size)
        assertNull(healed.defaultSubtree.tree.cells[healedIds.last()]?.taskId)
        // A healthy template is never rewritten — the settle is on every reduction.
        assertTrue(SchedulerReducer.settleDefaultSubtree(healed) === healed)
    }

    @Test
    fun deleting_every_row_of_a_template_sub_list_leaves_a_placeholder_with_no_switch() {
        // 2026-09-20, account 3: under "how to measure improvement / planning" the user selected every titled
        // row and pressed Delete, and the sub-list came back holding one EMPTY row that still carried the
        // "new task" switch. Emptying the cell directly above a list's trailing placeholder drops that
        // placeholder (`applySetCellTitle`'s inverse of Auto-Expansion), so the last row emptied becomes the
        // list's bottom cell and goes on pointing at its now blank-titled task — and the window asked
        // `taskId != null` rather than whether the row is titled.
        var s = withTemplate(
            listOf(
                node(
                    "dst/0",
                    "planning",
                    children = listOf(node("dst/1", "typing fast"), node("dst/2", "look for best LLM")),
                ),
            ),
        )
        val parent = s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds.first()
        val childList = childListOf(s, parent)
        val rows = s.defaultSubtree.tree.lists[childList]!!.cellIds
        assertEquals(3, rows.size, "fixture: two titled rows and the trailing placeholder")

        s = reduceInTemplate(s, SchedulerIntent.ToggleExpand(parent))
        s = clickTemplateCell(s, rows[0], ctrl = false)
        s = clickTemplateCell(s, rows[1], ctrl = true)
        s = reduceInTemplate(s, SchedulerIntent.EmptySelectedCells)

        val remaining = s.defaultSubtree.tree.lists[childList]!!.cellIds
        assertEquals(1, remaining.size, "the deleted rows leave ONE row to type into: $remaining")
        val placeholder = remaining.single()
        assertFalse(
            s.isTitledDefaultSubtreeRow(placeholder),
            "the row the delete left is empty, so the window draws no switch on it",
        )
        assertTrue(
            SchedulerDomain.isTextuallyEmptyCell(s.projectDefaultSubtree(), placeholder),
            "it is a placeholder to the tree as well, whatever it still points at",
        )
        // ... and the settle has nothing left to do, which is the other half of the bug: the sub-list read as
        // "ending in a titled row" for ever, so every reduction re-projected and re-folded the whole template.
        assertTrue(
            SchedulerReducer.settleDefaultSubtree(s) === s,
            "an emptied sub-list is settled, not re-folded on every reduction",
        )
        assertTemplateListsSettled(s)
        // The switch is not there to be flipped either — the intent answers for itself.
        assertTrue(
            SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeCellBound(placeholder, bound = true)) === s,
            "an empty row has no switch to flip",
        )
    }

    @Test
    fun a_row_drawn_UNDER_a_bound_row_belongs_to_the_live_tree_and_carries_no_switch() {
        // A bound row shows the live task's own sub-tree, as any mirror does. Those rows are the live tree's
        // cells: `boundCells` is keyed by a TEMPLATE cell, so a switch flipped on one of them wrote an entry
        // the next fold silently dropped.
        var s = SchedulerState.empty()
        val live = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(live, "Shared"))
        val shared = s.cells[live]!!.taskId!!
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetCellTitle(s.lists[s.tasks[shared]!!.childListId!!]!!.cellIds.first(), "child"),
        )
        val (mirrored, row) = withRowMirroring(s, shared)
        val liveChild = mirrored.lists[mirrored.tasks[shared]!!.childListId!!]!!.cellIds.first()

        assertTrue(mirrored.isTitledDefaultSubtreeRow(row), "the bound row itself carries the switch")
        assertFalse(
            mirrored.isTitledDefaultSubtreeRow(liveChild),
            "the live task's own child is not a template row",
        )
        assertTrue(
            SchedulerReducer.reduce(
                mirrored,
                SchedulerIntent.SetDefaultSubtreeCellBound(liveChild, bound = true),
            ) === mirrored,
            "flipping a switch the window does not draw changes nothing",
        )
    }

    @Test
    fun the_menus_rows_are_named_by_where_they_live_in_the_ACCOUNT_tree() {
        // PRD §4 *Presentation*: the path on an id row says WHICH task of that title this is. The template
        // window draws a projection rooted at the template, so read off the drawing a live task has no path
        // at all — every row of the release account's menu read a bare "planning", sixty-odd times over, and
        // the row bound to the user's "writing" task was named after its place in the TEMPLATE.
        var s = SchedulerState.empty()
        val englishCell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(englishCell, "english"))
        val english = s.cells[englishCell]!!.taskId!!
        val englishChildren = s.tasks[english]!!.childListId!!
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetCellTitle(s.lists[englishChildren]!!.cellIds.first(), "writing"),
        )
        val deepWriting = s.cells[s.lists[englishChildren]!!.cellIds.first()]!!.taskId!!
        // A second task of the SAME title one level up: the two rows are told apart by their paths alone.
        val topWritingCell = s.lists[s.rootListId]!!.cellIds.last { s.cells[it]?.taskId == null }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(topWritingCell, "writing"))
        val topWriting = s.cells[topWritingCell]!!.taskId!!

        s = withTemplate(listOf(node("dst/0", "planning")), from = s)
        val projected = s.projectDefaultSubtree()
        val planningCell =
            projected.lists[projected.rootListId]!!.cellIds
                .first { projected.cells[it]?.taskId != null }
        val target =
            projected.lists[projected.tasks[projected.cells[planningCell]!!.taskId!!]!!.childListId!!]!!
                .cellIds.first()

        val entries =
            SchedulerDomain.changeTaskMenuEntries(projected, target, "writing", namingSource = s)
        val labels = entries.filter { it.taskId != null }.associate { it.taskId to it.label }
        assertEquals(
            SchedulerDomain.taskPathLabel(s, topWriting),
            labels[topWriting],
            "a live task must be named exactly as the account's own tree names it",
        )
        assertEquals(SchedulerDomain.taskPathLabel(s, deepWriting), labels[deepWriting])
        // ... which is the whole point: two tasks of one title, told apart.
        assertTrue(labels[topWriting] != labels[deepWriting])
    }

    @Test
    fun a_template_owned_task_is_still_named_by_the_template() {
        // The other half: a task the account tree does not hold lives in the template, so that is where it
        // is named from. Naming everything from the account would leave it labelled by nothing at all.
        var s = withTemplate(listOf(node("dst/0", "planning", children = listOf(node("dst/1", "writing")))))
        val projected = s.projectDefaultSubtree()
        val planningCell =
            projected.lists[projected.rootListId]!!.cellIds
                .first { projected.cells[it]?.taskId != null }
        val templateWriting =
            projected.lists[projected.tasks[projected.cells[planningCell]!!.taskId!!]!!.childListId!!]!!
                .cellIds.mapNotNull { projected.cells[it]?.taskId }.first()
        // Asked from a cell elsewhere in the template, so the row is not filtered out as its own sibling.
        val elsewhere = projected.lists[projected.rootListId]!!.cellIds.last()

        val entries =
            SchedulerDomain.changeTaskMenuEntries(projected, elsewhere, "writing", namingSource = s)
        assertEquals(
            SchedulerDomain.taskPathLabel(projected, templateWriting),
            entries.first { it.taskId == templateWriting }.label,
        )
    }

    @Test
    fun a_template_owned_row_is_named_after_the_template_and_not_after_the_tree() {
        // The other half of the same question: a path only says WHICH task this is if the user can tell
        // where it starts, and the projection's root is the template. Named by the root task's own title it
        // read "root / planning / write good prompt" — an account path, for a row the account's tree has
        // nowhere, which sent the user looking for it in the real tree (2026-09-20, account 3).
        var s = withTemplate(listOf(node("dst/0", "planning", children = listOf(node("dst/1", "writing")))))
        val projected = s.projectDefaultSubtree()
        val planningCell =
            projected.lists[projected.rootListId]!!.cellIds
                .first { projected.cells[it]?.taskId != null }
        val templateWriting =
            projected.lists[projected.tasks[projected.cells[planningCell]!!.taskId!!]!!.childListId!!]!!
                .cellIds.mapNotNull { projected.cells[it]?.taskId }.first()
        val elsewhere = projected.lists[projected.rootListId]!!.cellIds.last()

        val label =
            SchedulerDomain.changeTaskMenuEntries(projected, elsewhere, "writing", namingSource = s)
                .first { it.taskId == templateWriting }
                .label
        assertEquals("${SchedulerDomain.DEFAULT_SUBTREE_ROOT_LABEL} / planning / writing", label)
        // ...and the account's own tree still names its own rows from its own root.
        assertEquals(
            SchedulerDomain.ROOT_TASK_TITLE,
            SchedulerDomain.taskPathLabel(s, WellKnownIds.ROOT_TASK),
        )
    }

    @Test
    fun a_blank_title_deletes_a_template_row_with_its_children() {
        // PRD §4: the blank title is what deletes, in the template exactly as in the tree — and it is the
        // tree's own rule doing it, not a normalization step of the template's own.
        var s = withTemplate(listOf(node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch")))))
        assertEquals(listOf("Plan"), templateTitles(s))

        val planCell =
            s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds
                .first { s.defaultSubtree.tree.cells[it]?.taskId != null }
        s = reduceInTemplate(s, SchedulerIntent.SetCellTitle(planCell, ""))

        assertEquals(emptyList(), templateTitles(s))
        assertTrue(s.defaultSubtreeIsEmpty, "an emptied template holds nothing to graft")
    }

    @Test
    fun a_bound_row_shows_the_bound_tasks_own_sub_tree_through_the_projection() {
        // PRD §4: a bound row draws the task's OWN children, because a sub-list belongs to the task id. The
        // window needs no special path for that — the projection resolves the live task and the tree draws
        // its sub-list like any other mirror.
        var s = SchedulerState.empty()
        val sharedCell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(sharedCell, "Shared"))
        val sharedTask = s.cells[sharedCell]!!.taskId!!
        val sharedChildList = s.tasks[sharedTask]!!.childListId!!
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetCellTitle(s.lists[sharedChildList]!!.cellIds.first(), "Inherited"),
        )

        s = withTemplate(listOf(node("dst/0", "Shared", sharedTask)), from = s)
        val projected = s.projectDefaultSubtree()
        val boundCell =
            projected.lists[projected.rootListId]!!.cellIds
                .first { projected.cells[it]?.taskId == sharedTask }

        // The row resolves the live task, and its sub-list is reachable in the projection.
        assertEquals("Shared", projected.tasks[sharedTask]!!.title)
        assertTrue(SchedulerDomain.hasExpandableSubTree(projected, boundCell))
        assertEquals(
            listOf("Inherited"),
            projected.lists[sharedChildList]!!.cellIds
                .mapNotNull { projected.cells[it]?.taskId }
                .mapNotNull { projected.tasks[it]?.title }
                .filter { it.isNotBlank() },
        )
    }

    @Test
    fun the_switch_is_the_rows_binding_and_toggles_both_ways() {
        // PRD §4: on = a brand new task id at every graft, off = every grafted cell mirrors the row's task.
        var s = withTemplate(listOf(node("dst/0", "Plan")))
        val planCell =
            s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds
                .first { s.defaultSubtree.tree.cells[it]?.taskId != null }
        assertFalse(planCell in s.defaultSubtree.boundCells, "a row starts with its switch ON")

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeCellBound(planCell, bound = true))
        assertTrue(planCell in s.defaultSubtree.boundCells)
        // Unlike the old editor's switch, this one turns back on: the row always has a task to point at.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeCellBound(planCell, bound = false))
        assertFalse(planCell in s.defaultSubtree.boundCells)
    }

    @Test
    fun editing_the_template_never_touches_the_live_tree() {
        // The safety property the projection is built on: intents reduce against a state that MERGES the
        // live tree in, and the live half is discarded on the way back.
        var s = SchedulerState.empty()
        val cell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Real work"))
        val liveTree = s.captureTreeWithRecords()

        s = withTemplate(listOf(node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch")))), from = s)

        val after = s.captureTreeWithRecords()
        assertEquals(liveTree.cells, after.cells, "the account's own cells must be untouched")
        assertEquals(liveTree.lists, after.lists, "the account's own lists must be untouched")
        assertEquals(liveTree.tasks, after.tasks, "the account's own tasks must be untouched")
        // The id counters are the one thing that DOES move: they are handed out from one counter but live in
        // every tree, so the live side must not be able to re-mint what the template just took.
        assertTrue(after.nextTaskCounter >= liveTree.nextTaskCounter)
        assertEquals(listOf("Plan"), templateTitles(s))
    }

    @Test
    fun a_template_edit_is_one_undoable_main_unit() {
        var s = withTemplate(listOf(node("dst/0", "Plan")))
        val before = s.defaultSubtree
        val mainUnits = s.histories.forCategory(HistoryCategory.Main).units.size

        val target =
            s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds
                .last { s.defaultSubtree.tree.cells[it]?.taskId == null }
        s = reduceInTemplate(s, SchedulerIntent.SetCellTitle(target, "Do"))

        assertEquals(listOf("Plan", "Do"), templateTitles(s))
        assertEquals(
            mainUnits + 1,
            s.histories.forCategory(HistoryCategory.Main).units.size,
            "one gesture in the window is one Main unit, however many inner reductions it took",
        )
        val undone = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        // Undo never hands an id back (`TreeDiff.applyTo`): the counters stay where the gesture left them.
        val u = undone.defaultSubtree
        assertEquals(
            before.copy(tree = before.tree.copy(nextTaskCounter = u.tree.nextTaskCounter, nextCellCounter = u.tree.nextCellCounter)),
            u,
            "Ctrl+Z takes the whole gesture back",
        )
        assertTrue(u.tree.nextTaskCounter >= before.tree.nextTaskCounter && u.tree.nextCellCounter >= before.tree.nextCellCounter)
    }

    // ---- the §13 contextual menu (the reason the window is the tree) ---------------------------

    @Test
    fun a_template_row_offers_the_whole_contextual_menu() {
        // The anomaly this shape was built for: right-clicking a row in the "Default sub-tree" window did
        // nothing, because the window was a hand-rolled tree with no menu in it. It is the real tree now, so
        // the menu is present by construction — a template row is a populated, selectable cell holding a real
        // task, which is the whole of what the tree asks before offering the menu.
        val s = withTemplate(listOf(node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch")))))
        val projected = s.projectDefaultSubtree()
        val planCell =
            projected.lists[projected.rootListId]!!.cellIds.first { projected.cells[it]?.taskId != null }
        val planTask = projected.cells[planCell]!!.taskId!!

        // The menu appears at all: a selectable cell pointing at a titled task.
        assertTrue(SchedulerDomain.isSelectableCell(projected, planCell))
        assertNotNull(projected.tasks[planTask])

        // "edit task" — the §13 window needs a real Task, and a parent shows the text section alone.
        assertFalse(SchedulerDomain.isLeafTask(projected, planTask), "Plan parents Sketch")
        val sketchCell =
            projected.lists[projected.tasks[planTask]!!.childListId!!]!!.cellIds
                .first { projected.cells[it]?.taskId != null }
        // "start this task now" — offered on a schedulable leaf, exactly as in the tree.
        assertTrue(SchedulerDomain.isLeafTask(projected, projected.cells[sketchCell]!!.taskId!!))

        // "copy" / "deep copy" — the block the menu acts on, and text that actually renders.
        assertEquals(
            listOf(planCell),
            SchedulerDomain.contextMenuCopyTargets(projected, projected.selection, planCell),
        )
        assertTrue(SchedulerDomain.copyCellsText(projected, listOf(planCell), maxDepth = 2).isNotEmpty())

        // "add default sub-tree" — offered wherever a template exists, and one does here.
        assertFalse(s.defaultSubtreeIsEmpty)
    }

    @Test
    fun the_template_window_shows_its_own_shares_not_the_accounts() {
        // The percentage column is a readout of the row's share WITHIN the template — computed off the
        // template's cells alone, or the account's tree would divide them.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Real work"))
        s = withTemplate(listOf(node("dst/0", "Plan"), node("dst/1", "Do")), from = s)

        val shares = s.defaultSubtreePriorities()
        val tree = s.defaultSubtree.tree
        val rows =
            tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds
                .mapNotNull { tree.cells[it]?.taskId }
                .filter { tree.tasks[it]?.title?.isNotBlank() == true }
        assertEquals(2, rows.size)
        // Two equally-weighted rows at the top of the template: half each, and the account's own task is
        // nowhere in the answer.
        rows.forEach { assertEquals(0.5, shares[it]!!, 1e-9) }
        assertTrue(shares.keys.all { id -> tree.tasks[id] != null }, "no live-tree task may appear")
    }

    /**
     * A switch-off row mirroring the live task [shared] under a titled template row "Plan", as the window builds
     * it: the ordinary Change Task menu, then the switch. Returns the state and the mirroring cell.
     */
    private fun withRowMirroring(from: SchedulerState, shared: TaskId): Pair<SchedulerState, CellId> {
        var s = withTemplate(listOf(node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch")))), from = from)
        val planTask = s.defaultSubtree.tree.cells.values.first { s.defaultSubtree.tree.tasks[it.taskId]?.title == "Plan" }.taskId!!
        val planList = s.defaultSubtree.tree.tasks[planTask]!!.childListId!!
        val row = s.defaultSubtree.tree.lists[planList]!!.cellIds.last()
        s = reduceInTemplate(s, SchedulerIntent.BeginEdit(row))
        s = reduceInTemplate(s, SchedulerIntent.PickTaskFromMenu(shared))
        s = reduceInTemplate(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))
        s = reduceInTemplate(s, SchedulerIntent.SetDefaultSubtreeCellBound(row, bound = true))
        assertEquals(shared, s.defaultSubtree.tree.cells[row]!!.taskId, "fixture: the row mirrors the live task")
        return s to row
    }

    /** Every template sub-list is titled cells ending in exactly one empty cell (PRD §4, as in the tree). */
    private fun assertTemplateListsSettled(s: SchedulerState) {
        val tree = s.defaultSubtree.tree
        for (list in tree.lists.values) {
            val empty = list.cellIds.map { id ->
                val taskId = tree.cells[id]?.taskId
                taskId == null || (tree.tasks[taskId] ?: s.tasks[taskId])?.title.isNullOrBlank()
            }
            assertEquals(listOf(true), empty.filter { it } , "one empty cell in ${list.id.value}: ${list.cellIds}")
            assertTrue(empty.last(), "the empty cell ends ${list.id.value}")
        }
        assertTrue(
            s.defaultSubtree.boundCells.all { tree.cells[it]?.taskId != null },
            "a switch belongs to a row with a task: ${s.defaultSubtree.boundCells}",
        )
    }

    @Test
    fun a_mirrored_live_draft_the_tree_cancels_leaves_no_empty_row_in_the_template() {
        // 2026-09-17, account 3: a "New task" draft still being typed in the tree was picked for a template row
        // and the row's switch turned off; the tree then dropped the draft. The row pointed at nothing and drew
        // as an empty cell in the middle of "planning", switch and all.
        var s = SchedulerState.empty()
        val live = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(live, initialText = "Draft"))
        val draft = s.cells[live]!!.taskId!!
        val (mirrored, row) = withRowMirroring(s, draft)

        s = SchedulerReducer.reduce(mirrored, SchedulerIntent.CancelEdit)

        assertNull(s.tasks[draft], "fixture: the cancel dropped the draft")
        assertFalse(row in s.defaultSubtree.tree.cells, "the row that pointed at nothing is removed")
        assertFalse(row in s.defaultSubtree.boundCells)
        assertEquals(listOf("Plan"), templateTitles(s))
        assertTemplateListsSettled(s)
    }

    @Test
    fun a_row_mirroring_a_task_the_live_tree_still_has_is_kept() {
        var s = SchedulerState.empty()
        val live = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(live, "Shared"))
        val (mirrored, row) = withRowMirroring(s, s.cells[live]!!.taskId!!)

        assertTrue(row in mirrored.defaultSubtree.boundCells)
        assertTrue(SchedulerReducer.settleDefaultSubtree(mirrored) === mirrored, "a healthy template is left alone")
        assertTemplateListsSettled(mirrored)
    }

    // ---- persistence ---------------------------------------------------------------------------

    @Test
    fun a_stored_template_row_pointing_at_a_vanished_task_is_healed_on_decode() {
        // CLAUDE.md: decode heals a state an older build wrote. The previous build persisted exactly this — a
        // switch-off row whose live task is gone — so the payload is that state, written without the heal.
        var s = SchedulerState.empty()
        val live = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(live, "Shared"))
        val shared = s.cells[live]!!.taskId!!
        val (mirrored, row) = withRowMirroring(s, shared)
        val vanished = mirrored.copy(tasks = mirrored.tasks - shared)
        val payload = SchedulerStateCodec.encode(vanished)
        assertTrue(payload.contains(row.value), "fixture: the dangling row is in the payload")

        val decoded = SchedulerStateCodec.decode(payload)

        assertNotNull(decoded)
        assertFalse(row in decoded.defaultSubtree.tree.cells)
        assertEquals(listOf("Plan"), templateTitles(decoded))
        assertTemplateListsSettled(decoded)
    }

    @Test
    fun a_payload_written_before_the_feature_decodes_to_no_template_and_the_switch_off() {
        // The previous shape: the same state minus the two fields (an older build's payload).
        val before = SchedulerStateCodec.encode(SchedulerState.empty())
        assertFalse(before.contains("defaultSubtree"), "the fixture must predate the fields")

        val decoded = SchedulerStateCodec.decode(before)
        assertNotNull(decoded)
        assertTrue(decoded.defaultSubtreeIsEmpty)
        assertFalse(decoded.defaultSubtreeEnabled)
    }

    @Test
    fun a_payload_written_before_the_promise_decodes_to_owing_nothing() {
        // CLAUDE.md persisted-DB compatibility: the previous shape wrote no `pendingDefaultSubtree`, because
        // the build that wrote it grafted the template eagerly. Absent must decode to "owes nothing" — which
        // is exactly right for those tasks: their rows are already there.
        val s = withTemplate(listOf(node("dst/0", "Plan")))
        val named = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Project"))
        val payload = SchedulerStateCodec.encode(named)
        assertTrue(payload.contains("pendingDefaultSubtree"), "the fixture must carry the field")
        // The previous shape: the same payload with every occurrence of the field dropped.
        val before = payload.replace(Regex(""",\"pendingDefaultSubtree\":\[[^]]*]"""), "")
        assertFalse(before.contains("pendingDefaultSubtree"))

        val decoded = SchedulerStateCodec.decode(before)
        assertNotNull(decoded)
        val task = decoded.cells[firstCell(decoded)]!!.taskId!!
        assertEquals(emptyList(), decoded.tasks[task]!!.pendingDefaultSubtree)
    }

    @Test
    fun what_a_task_owes_survives_a_round_trip() {
        // Authoritative: nothing can re-derive "the rows this task has not been shown yet", so it is
        // persisted and synced with the task — and paid after a reload exactly as before one.
        val s = withTemplate(listOf(node("dst/0", "Plan")))
        val named = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Project"))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(named))

        assertNotNull(decoded)
        val cell = firstCell(decoded)
        assertEquals(
            listOf(WellKnownIds.ROOT_LIST),
            decoded.tasks[decoded.cells[cell]!!.taskId!!]!!.pendingDefaultSubtree,
        )
        assertEquals(listOf("Plan"), childTitles(open(decoded, cell), cell))
    }

    @Test
    fun the_template_round_trips() {
        val nodes =
            listOf(node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch", TaskId("task/user/3")))))
        val s = withTemplate(nodes)
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))

        assertNotNull(decoded)
        assertEquals(s.defaultSubtree, decoded.defaultSubtree)
        assertTrue(decoded.defaultSubtreeEnabled)
    }

    @Test
    fun a_pre_1_6_0_template_of_titles_is_migrated_into_the_real_tree() {
        // CLAUDE.md persisted-DB compatibility: a payload written by the PREVIOUS shape — the template as a
        // tree of titles — must still load, migrated. The fixture is that exact shape: an empty account's
        // payload (which carries no template at all) with the old node array spliced in.
        val bare = SchedulerStateCodec.encode(SchedulerState.empty())
        assertFalse(bare.contains("defaultSubtreeTree"), "an empty template is written as nothing")
        // The old fields, spliced in ahead of everything else (they end with a comma, so it stays valid).
        val legacyFields = """
  "defaultSubtree": [
    { "id": "dst/0", "title": "Plan", "children": [ { "id": "dst/1", "title": "Sketch" } ] },
    { "id": "dst/2", "title": "Do" }
  ],
  "defaultSubtreeEnabled": true,
"""
        val legacy = bare.replaceFirst("{", "{" + legacyFields.trimEnd())

        val decoded = SchedulerStateCodec.decode(legacy)
        assertNotNull(decoded)
        assertEquals(listOf("Plan", "Do"), templateTitles(decoded))
        assertTrue(decoded.defaultSubtreeEnabled)

        // It is a real tree now: the rows have tasks, and the nesting survived.
        val planCell =
            decoded.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds
                .first { decoded.defaultSubtree.tree.cells[it]?.taskId != null }
        val planTask = decoded.defaultSubtree.tree.cells[planCell]!!.taskId!!
        val planChildren = decoded.defaultSubtree.tree.tasks[planTask]!!.childListId!!
        assertEquals(listOf("Sketch"), templateTitles(decoded, planChildren))

        // And the account's counters cleared the ids the migration minted, so the live tree cannot re-mint
        // one the template already uses.
        assertTrue(decoded.nextTaskCounter > 0)
        assertTrue(
            decoded.defaultSubtree.tree.tasks.keys.none { it.value == "task/user/${decoded.nextTaskCounter}" },
        )
    }

    @Test
    fun a_migrated_template_still_grafts() {
        val s0 = withTemplate(listOf(node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch")))))
        val reloaded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s0))
        assertNotNull(reloaded)

        val cell = firstCell(reloaded)
        var s = SchedulerReducer.reduce(reloaded, SchedulerIntent.SetCellTitle(cell, "Project"))
        s = open(s, cell)
        assertEquals(listOf("Plan"), childTitles(s, cell))
        val plan = childCells(s, cell).single()
        s = open(s, plan)
        assertEquals(listOf("Sketch", "Plan"), childTitles(s, plan))
    }

    @Test
    fun the_template_is_part_of_the_sync_fingerprint() {
        // Authoritative user data: nothing re-derives it, so an edit must reach the other devices.
        val bare = SchedulerState.empty()
        val templated = withTemplate(listOf(node("dst/0", "Plan")))
        assertTrue(
            SchedulerStateCodec.syncFingerprint(bare) != SchedulerStateCodec.syncFingerprint(templated),
            "a template change must move the fingerprint",
        )
    }

    @Test
    fun an_edit_session_that_reuses_an_existing_task_seeds_nothing() {
        // PRD §4: typing a title that resolves to an existing task MIRRORS it — the sub-tree arrives with the
        // id (a sub-list belongs to the task id), so the template must stay out of it. The reusing cell has
        // to sit in another list: PRD §4 Filtering forbids the same task twice in one sub-list.
        var s = SchedulerState.empty()
        val sharedCell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(sharedCell, "Shared"))
        val shared = s.cells[sharedCell]!!.taskId!!
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetCellTitle(s.lists[s.tasks[shared]!!.childListId!!]!!.cellIds.first(), "Inherited"),
        )
        // A container built BEFORE the policy is on, so its own sub-list starts empty.
        val containerCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(containerCell, "Container"))
        val containerList = s.tasks[s.cells[containerCell]!!.taskId!!]!!.childListId!!

        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)

        // Typing "Shared" there defaults the id menu to the existing task (PRD §4 Default selection).
        val reusing = s.lists[containerList]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(reusing, initialText = "Shared"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.PickTaskFromMenu(shared))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Down))

        assertEquals(shared, s.cells[reusing]!!.taskId)
        assertEquals(listOf("Inherited"), childTitles(s, reusing))
    }

    // ---- the graft and the clipboard ----------------------------------------------------------

    /** Select [cellId] the way a click does, so a paste has a main selection to land on. */
    private fun select(state: SchedulerState, cellId: CellId): SchedulerState =
        SchedulerReducer.reduce(
            state,
            SchedulerIntent.ClickCell(
                cellId = cellId,
                ctrl = false,
                shift = false,
                visibleOrder = SchedulerDomain.selectableVisibleOrder(state),
            ),
        )

    private fun paste(state: SchedulerState, cellId: CellId, text: String): SchedulerState =
        SchedulerReducer.reduce(select(state, cellId), SchedulerIntent.PasteTree(text))

    @Test
    fun pasting_a_title_onto_an_empty_cell_grafts_the_template() {
        // PRD §4/§7: a paste that MINTS a task creates one exactly as typing its title does. Pasting foreign
        // text onto a selected empty cell never opens an Edit session, so the graft cannot ride
        // `endEditSession` here — it has to happen in the paste itself.
        val s0 = withTemplate(listOf(node("dst/0", "Plan"), node("dst/1", "Do")))
        val cell = firstCell(s0)
        var s = paste(s0, cell, "Project")

        assertEquals("Project", s.tasks[s.cells[cell]!!.taskId!!]!!.title)
        s = open(s, cell)
        assertEquals(listOf("Plan", "Do"), childTitles(s, cell))
    }

    @Test
    fun a_pasted_forest_seeds_every_minted_leaf_and_never_over_the_clipboard_s_own_children() {
        val s0 = withTemplate(listOf(node("dst/0", "Plan")))
        val cell = firstCell(s0)
        var s = paste(s0, cell, "A\n\tB\n\tC")

        assertEquals(listOf("B", "C"), childTitles(s, cell), "the clipboard's children are the sub-tree")
        val (b, c) = childCells(s, cell)
        s = open(open(s, b), c)
        assertEquals(listOf("Plan"), childTitles(s, b), "a leaf the paste minted is a task the user created")
        assertEquals(listOf("Plan"), childTitles(s, c))
    }

    @Test
    fun a_cut_leaf_pasted_back_is_restored_not_seeded() {
        // Restore rebuilds a task under its own id — it is not a task the user just created, so a
        // Ctrl+X → Ctrl+V round-trip must return the leaf exactly as it was cut.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Leaf"))
        val leafId = s.cells[firstCell(s)]!!.taskId!!
        val text = SchedulerDomain.copyTreeText(select(s, firstCell(s)), select(s, firstCell(s)).selection)
        s = SchedulerReducer.reduce(select(s, firstCell(s)), SchedulerIntent.CutSelection)
        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)

        val target = firstCell(s)
        s = paste(s, target, text)

        assertEquals(leafId, s.cells[target]!!.taskId, "the cut id comes back, so this is a Restore")
        assertEquals(emptyList(), childTitles(s, target), "a restored task must not gain rows it never had")
    }

    @Test
    fun pasting_a_mirror_of_a_live_task_does_not_seed_it() {
        // A sub-list belongs to the task id: a Mirror shows the task's OWN sub-tree, so there is nothing to
        // seed — the same rule the graft already follows for a bound template node.
        var s = SchedulerState.empty()
        val leafCell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(leafCell, "Leaf"))
        val leafId = s.cells[leafCell]!!.taskId!!
        val boxCell = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(boxCell, "Box"))
        val boxList = s.tasks[s.cells[boxCell]!!.taskId!!]!!.childListId!!
        val text = SchedulerDomain.copyTreeText(select(s, leafCell), select(s, leafCell).selection)

        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)

        val target = s.lists[boxList]!!.cellIds.first()
        s = paste(s, target, text)

        assertEquals(leafId, s.cells[target]!!.taskId, "the same task, mirrored")
        assertEquals(emptyList(), childTitles(s, target))
    }

    @Test
    fun a_copy_of_a_sub_tree_never_seeds_even_when_its_ids_cannot_be_honoured() {
        // The gate is the clipboard's id, not the identity it resolves to: a deep copy pasted into a list
        // that already holds the task falls back to Fresh (canAssignTaskId refuses a duplicate sibling), and
        // a clone of a copied sub-tree must still come back as itself, not as itself plus the template.
        var s = SchedulerState.empty()
        val leafCell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(leafCell, "Leaf"))
        val leafId = s.cells[leafCell]!!.taskId!!
        val text = SchedulerDomain.copyTreeText(select(s, leafCell), select(s, leafCell).selection)

        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)

        // The trailing placeholder of the SAME list, so the copied id would duplicate a sibling.
        val target = s.lists[s.rootListId]!!.cellIds.last()
        s = paste(s, target, text)

        val pasted = s.cells[target]!!.taskId!!
        assertTrue(pasted != leafId, "the id could not be honoured, so this is a Fresh clone")
        assertEquals("Leaf", s.tasks[pasted]!!.title)
        assertEquals(emptyList(), childTitles(s, target), "an app copy is a task's content, not a new task")
    }

    // ---- "add default sub-tree" (the §13 menu entry) --------------------------------------------

    @Test
    fun add_default_sub_tree_applies_the_template_under_the_cell() {
        // Named before the template existed, so its sub-list starts empty.
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetCellTitle(firstCell(SchedulerState.empty()), "Project"))
        s = withTemplate(listOf(node("dst/0", "Plan"), node("dst/1", "Do")), from = s)
        val cell = firstCell(s)
        assertEquals(emptyList(), childTitles(s, cell))

        s = SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(cell)))

        assertEquals(listOf("Plan", "Do"), childTitles(s, cell))
        assertTrue(cell in s.expanded, "what was just added must be visible, not folded away")
    }

    @Test
    fun add_default_sub_tree_ignores_the_policy_switch() {
        // The switch governs the AUTOMATIC graft; asking for the template explicitly is always an answer.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Project"))
        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(false))
        assertFalse(s.defaultSubtreeEnabled)

        val cell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(cell)))

        assertEquals(listOf("Plan"), childTitles(s, cell))
    }

    @Test
    fun add_default_sub_tree_lands_under_the_cell_even_when_it_is_already_broken_down() {
        // The entry acts on the row it was opened on, beside whatever that row already parents — it does NOT
        // descend to the pieces. It used to fill the sub-tree's leaves instead, so right-clicking a cell two
        // levels above one wrote the template somewhere the gesture never named (2026-09-21, account 3).
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Project"))
        val cell = firstCell(s)
        val childList = s.tasks[s.cells[cell]!!.taskId!!]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[childList]!!.cellIds.first(), "Existing"))
        val existing = s.lists[childList]!!.cellIds.first()
        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(false))

        s = SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(cell)))

        assertEquals(listOf("Existing", "Plan"), childTitles(s, cell), "beside the children it already had")
        assertEquals(emptyList(), childTitles(s, existing), "the pieces are left alone")
        assertTrue(cell in s.expanded, "a collapsed cell would fold away what was just asked for")
    }

    @Test
    fun add_default_sub_tree_touches_only_the_cells_it_is_given() {
        // Project { A { A1, A2 }, B }. Asked on Project, exactly Project's own sub-list is filled: nothing
        // below it is walked, so no seeded row seeds in turn and no descendant is written behind the user.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Project"))
        val cell = firstCell(s)
        val projectList = s.tasks[s.cells[cell]!!.taskId!!]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[projectList]!!.cellIds.first(), "A"))
        val cellA = s.lists[projectList]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[projectList]!!.cellIds.last(), "B"))
        val cellB = s.lists[projectList]!!.cellIds[1]
        val aList = s.tasks[s.cells[cellA]!!.taskId!!]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[aList]!!.cellIds.first(), "A1"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[aList]!!.cellIds.last(), "A2"))
        val (cellA1, cellA2) = s.lists[aList]!!.cellIds.take(2)
        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(false))
        val tasksBefore = s.tasks.size

        s = SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(cell)))

        assertEquals(listOf("A", "B", "Plan"), childTitles(s, cell), "beside the children it already had")
        assertEquals(listOf("A1", "A2"), childTitles(s, cellA), "nothing below the cell is touched")
        assertEquals(emptyList(), childTitles(s, cellA1))
        assertEquals(emptyList(), childTitles(s, cellA2))
        assertEquals(emptyList(), childTitles(s, cellB))
        // Exactly one new task, and the row it wrote did not seed in turn.
        assertEquals(tasksBefore + 1, s.tasks.size)
    }

    @Test
    fun add_default_sub_tree_fills_a_mirrored_task_once_however_many_of_its_cells_are_selected() {
        // "Shared" appears under both A and B (two root cells, so the mirror is allowed). A sub-list belongs
        // to the task id, so both occurrences ARE one sub-list: asking on both must write the template into
        // it once, not twice.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "A"))
        val cellA = firstCell(s)
        val cellB = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellB, "B"))
        val aList = s.tasks[s.cells[cellA]!!.taskId!!]!!.childListId!!
        val bList = s.tasks[s.cells[cellB]!!.taskId!!]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[aList]!!.cellIds.first(), "Shared"))
        val sharedUnderA = s.lists[aList]!!.cellIds.first()
        val shared = s.cells[sharedUnderA]!!.taskId!!
        val sharedUnderB = s.lists[bList]!!.cellIds.first()
        // Point B's first cell at the SAME task (the Change Task menu's "existing task" pick).
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(sharedUnderB, initialText = "Shared"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.PickTaskFromMenu(shared))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Down))
        assertEquals(shared, s.cells[sharedUnderB]!!.taskId, "both cells point at the one task")
        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(false))
        val tasksBefore = s.tasks.size

        s = SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(sharedUnderA, sharedUnderB)))

        assertEquals(listOf("Plan"), childTitles(s, sharedUnderA))
        assertEquals(listOf("Plan"), childTitles(s, sharedUnderB), "one sub-list, seen from both sides")
        assertEquals(tasksBefore + 1, s.tasks.size, "filled once, not once per occurrence")
    }

    @Test
    fun add_default_sub_tree_takes_every_cell_it_is_given_as_one_undoable_unit() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "A"))
        val cellA = firstCell(s)
        val cellB = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellB, "B"))
        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(false))
        val beforeAdd = s

        s = SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(cellA, cellB)))
        assertEquals(listOf("Plan"), childTitles(s, cellA))
        assertEquals(listOf("Plan"), childTitles(s, cellB))
        assertEquals(1, s.histories.forCategory(HistoryCategory.Main).units.size - beforeAdd.histories.forCategory(HistoryCategory.Main).units.size)

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(emptyList(), childTitles(s, cellA), "one Ctrl+Z takes the whole set back")
        assertEquals(emptyList(), childTitles(s, cellB))
        // Undo never hands an id back (`TreeDiff.applyTo`): everything but the id counters is as it was.
        val undoneTree = s.captureTree()
        assertEquals(
            beforeAdd.captureTree().copy(nextTaskCounter = undoneTree.nextTaskCounter, nextCellCounter = undoneTree.nextCellCounter),
            undoneTree,
        )
    }

    @Test
    fun add_default_sub_tree_fills_a_sub_list_whose_only_row_was_emptied() {
        // A row EMPTIED back to nothing keeps pointing at its now blank-titled task, and becomes its list's
        // trailing placeholder (`applySetCellTitle` drops the real one — the inverse of Auto-Expansion). So a
        // sub-list that looks empty on screen can hold a cell with a taskId, and `cell.taskId != null` is not
        // the question "is this row populated?" (2026-09-21, account 3: the entry did nothing at all).
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Project"))
        val cell = firstCell(s)
        val childList = s.tasks[s.cells[cell]!!.taskId!!]!!.childListId!!
        val child = s.lists[childList]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(child, "Child"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(child, ""))
        assertEquals(listOf(child), s.lists[childList]!!.cellIds, "the emptied row IS the trailing placeholder")
        assertNotNull(s.cells[child]!!.taskId, "and it still points at its blank-titled task")
        assertEquals(emptyList(), childTitles(s, cell))

        s = withTemplate(listOf(node("dst/0", "Plan")), from = s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(cell)))

        assertEquals(listOf("Plan"), childTitles(s, cell), "the blank row is the one typed into, not skipped")
    }

    @Test
    fun add_default_sub_tree_fills_a_template_row_whose_only_child_was_emptied() {
        // The same shape inside the §4 window, which is where the user met it: `why / how to measure
        // improvement / planning` had one child cell left over from an emptied row, so the walk called
        // `planning` a branch, took that blank cell for the leaf, found its task had no sub-list at all, and
        // the whole reduction wrote nothing.
        var s = withTemplate(listOf(node("dst/0", "Plan")))
        val row = s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds.first()
        s = openInTemplate(s, row)
        val rowList = childListOf(s, row)
        val under = s.defaultSubtree.tree.lists[rowList]!!.cellIds.first()
        s = reduceInTemplate(s, SchedulerIntent.SetCellTitle(under, "Sketch"))
        s = reduceInTemplate(s, SchedulerIntent.SetCellTitle(under, ""))
        assertEquals(listOf(under), s.defaultSubtree.tree.lists[rowList]!!.cellIds)
        assertNotNull(s.defaultSubtree.tree.cells[under]!!.taskId)
        assertFalse(s.isTitledDefaultSubtreeRow(under))

        s = reduceInTemplate(s, SchedulerIntent.AddDefaultSubtree(listOf(row)))

        assertEquals(listOf("Plan"), templateTitles(s, rowList), "the template lands on the row itself")
    }

    @Test
    fun add_default_sub_tree_skips_a_bound_row_the_sub_list_already_holds() {
        // Constraint 1: the same task cannot appear twice in one list. A BOUND template row carries a task
        // id, so when the clicked cell's sub-list already holds that very task the row has nothing to add
        // and is SKIPPED — not cloned under a fresh id, which would put the title in the list twice over a
        // task already sitting there. This is the one refusal that is not the mint fallback.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Project"))
        val cell = firstCell(s)
        val childList = s.tasks[s.cells[cell]!!.taskId!!]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[childList]!!.cellIds.first(), "Shared"))
        val sharedCell = s.lists[childList]!!.cellIds.first()
        val shared = s.cells[sharedCell]!!.taskId!!
        val tasksBefore = s.tasks.size
        // A template whose one root row is BOUND to that very task.
        s = withTemplate(listOf(node("dst/0", "Shared", taskId = shared)), from = s)

        s = SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(cell)))

        val placed = s.lists[childList]!!.cellIds.mapNotNull { s.cells[it]?.taskId }
        assertEquals(placed.size, placed.toSet().size, "one task id can never be twice in one list")
        assertEquals(listOf("Shared"), childTitles(s, cell), "the row is skipped, not cloned")
        assertEquals(shared, s.cells[sharedCell]!!.taskId, "the cell that was already there is untouched")
        assertEquals(tasksBefore, s.tasks.size, "and no task was minted for it")
    }

    @Test
    fun add_default_sub_tree_still_mints_for_a_binding_refused_by_constraint_2() {
        // The skip is Constraint 1 ONLY. Constraint 2 — the bound task's own sub-tree holds one of the
        // cell's ancestors, so mirroring it would make it its own descendant — is still the mint fallback:
        // the task is not in this list, so dropping the row would lose it silently.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Project"))
        val project = firstCell(s)
        val projectTask = s.cells[project]!!.taskId!!
        val projectList = s.tasks[projectTask]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[projectList]!!.cellIds.first(), "Child"))
        val child = s.lists[projectList]!!.cellIds.first()
        // A template row bound to the ANCESTOR of the cell we will ask on.
        s = withTemplate(listOf(node("dst/0", "Project", taskId = projectTask)), from = s)

        s = SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(child)))

        assertEquals(listOf("Project"), childTitles(s, child), "the row still appears rather than vanishing")
        val placed = childCells(s, child).first()
        assertTrue(s.cells[placed]!!.taskId != projectTask, "under a fresh task, never its own ancestor")
    }

    @Test
    fun add_default_sub_tree_mirrors_a_bound_row_when_the_sub_list_does_not_already_hold_it() {
        // The other side of the same rule: nothing to collide with, so the binding IS honoured and the row
        // mirrors the live task rather than cloning it.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Project"))
        val cell = firstCell(s)
        val sibling = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(sibling, "Shared"))
        val shared = s.cells[sibling]!!.taskId!!
        s = withTemplate(listOf(node("dst/0", "Shared", taskId = shared)), from = s)

        s = SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(cell)))

        val childList = s.tasks[s.cells[cell]!!.taskId!!]!!.childListId!!
        val placedCell = s.lists[childList]!!.cellIds.first { s.cells[it]?.taskId != null }
        assertEquals(shared, s.cells[placedCell]!!.taskId, "the binding is honoured: one task, two cells")
    }

    // ---- a row pointing at a live task edits the LIVE tree ---------------------------------------

    /**
     * An account whose tree is `Existing { Kept }` beside `Other { Sibling }`, with one template root row
     * pointed at `Existing` — PRD §4's "points at one existing task". Returns the state and the ids the
     * tests below name.
     */
    private fun withRowOnLiveTask(): Triple<SchedulerState, CellId, CellListId> {
        var s = SchedulerState.empty()
        val root = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(root, "Existing"))
        val liveTask = s.cells[root]!!.taskId!!
        val liveChildList = s.tasks[liveTask]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[liveChildList]!!.cellIds.first(), "Kept"))
        // A second branch, so the test can see whether the fold damaged the rest of the tree.
        val other = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(other, "Other"))
        val otherList = s.tasks[s.cells[other]!!.taskId!!]!!.childListId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[otherList]!!.cellIds.first(), "Sibling"))

        val templateRoot = s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds.first()
        s = reduceInTemplate(s, SchedulerIntent.AssignTaskId(templateRoot, liveTask))
        s = reduceInTemplate(s, SchedulerIntent.SetCellTitle(templateRoot, "Existing"))
        return Triple(s, templateRoot, liveChildList)
    }

    /** The row to type into, inside the sub-list the template window draws under [cellId]. */
    private fun typingRowUnder(state: SchedulerState, cellId: CellId): CellId {
        val p = state.projectDefaultSubtree()
        val listId = p.tasks[p.cells[cellId]!!.taskId!!]!!.childListId!!
        return p.lists[listId]!!.cellIds.last { SchedulerDomain.isTextuallyEmptyCell(p, it) }
    }

    @Test
    fun a_row_pointing_at_a_live_task_edits_that_task_s_own_sub_tree() {
        // A sub-list belongs to the task id, so the rows drawn under such a row ARE the account's tree's.
        // Typing there is typing into the tree — the same thing it is under a mirrored cell in the tree
        // itself. It used to evaporate at the fold instead, silently (2026-09-21, account 3).
        val (s0, row, liveChildList) = withRowOnLiveTask()

        val s = reduceInTemplate(s0, SchedulerIntent.SetCellTitle(typingRowUnder(s0, row), "AddedInWindow"))

        val liveTitles =
            s.lists[liveChildList]!!.cellIds.mapNotNull { s.cells[it]?.taskId }.mapNotNull { s.tasks[it]?.title }
        assertTrue("AddedInWindow" in liveTitles, "the edit reaches the account's tree: $liveTitles")
        assertTrue("Kept" in liveTitles, "beside what the task already had")
    }

    @Test
    fun editing_under_such_a_row_leaves_the_rest_of_the_tree_alone() {
        // The projection re-roots at the template, so the live tree's OWN top level is unreachable inside it
        // and `pruneDetachedTree` deletes the lot. The fold is what protects the tree: it writes back only
        // what it reached from the template's root, so nothing else of the live half is even read.
        val (s0, row, _) = withRowOnLiveTask()
        val otherCell = s0.lists[s0.rootListId]!!.cellIds.first { s0.cells[it]?.taskId != null && it != s0.lists[s0.rootListId]!!.cellIds.first() }

        val s = reduceInTemplate(s0, SchedulerIntent.SetCellTitle(typingRowUnder(s0, row), "AddedInWindow"))

        assertEquals("Other", s.tasks[s.cells[otherCell]!!.taskId!!]!!.title, "the untouched branch survives")
        assertEquals(listOf("Sibling"), childTitles(s, otherCell), "and so does everything under it")
        assertEquals(
            s0.lists[s0.rootListId]!!.cellIds,
            s.lists[s.rootListId]!!.cellIds,
            "the tree's own top level is untouched",
        )
    }

    @Test
    fun one_undo_takes_back_an_edit_made_through_such_a_row() {
        // One gesture is one unit, and the unit now carries both halves — undoing the template alone would
        // leave the tree change standing.
        val (s0, row, liveChildList) = withRowOnLiveTask()
        val before = s0.captureTree()

        var s = reduceInTemplate(s0, SchedulerIntent.SetCellTitle(typingRowUnder(s0, row), "AddedInWindow"))
        assertEquals(
            1,
            s.histories.forCategory(HistoryCategory.Main).units.size -
                s0.histories.forCategory(HistoryCategory.Main).units.size,
            "one gesture, one unit",
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)

        val liveTitles =
            s.lists[liveChildList]!!.cellIds.mapNotNull { s.cells[it]?.taskId }.mapNotNull { s.tasks[it]?.title }
        assertFalse("AddedInWindow" in liveTitles, "one Ctrl+Z takes the tree change back too: $liveTitles")
        // Undo never hands an id back, so compare everything but the counters.
        val undone = s.captureTree()
        assertEquals(
            before.copy(nextTaskCounter = undone.nextTaskCounter, nextCellCounter = undone.nextCellCounter),
            undone,
        )
    }

    @Test
    fun a_template_only_gesture_still_carries_no_live_half() {
        // The common case must not start writing the tree back: a row of the template's own structure is a
        // template task, and the account's tree is untouched by it.
        var s = withTemplate(listOf(node("dst/0", "Plan")))
        val before = s.captureTree()
        val row = s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds.first()
        s = openInTemplate(s, row)
        s = reduceInTemplate(s, SchedulerIntent.SetCellTitle(typingRowUnder(s, row), "Sketch"))

        assertEquals(listOf("Sketch"), templateTitles(s, childListOf(s, row)))
        val after = s.captureTree()
        assertEquals(
            before.copy(nextTaskCounter = after.nextTaskCounter, nextCellCounter = after.nextCellCounter),
            after,
            "the account's tree never moved",
        )
    }

    @Test
    fun renaming_such_a_row_renames_the_live_task() {
        // The same rule one level up, and it settles what used to be an open question: the row draws the live
        // task's title because it IS that task, so renaming the row renames the task — everywhere it is
        // drawn. It used to be a silent no-op, for the same reason the sub-tree edit was.
        val (s0, row, _) = withRowOnLiveTask()
        val liveTask = s0.defaultSubtree.tree.cells[row]!!.taskId!!

        val s = reduceInTemplate(s0, SchedulerIntent.SetCellTitle(row, "RenamedInWindow"))

        assertEquals("RenamedInWindow", s.tasks[liveTask]!!.title)
        assertFalse(liveTask in s.defaultSubtree.tree.tasks, "the template still copies no part of the task")
    }

    @Test
    fun emptying_such_a_row_unbinds_it_and_leaves_the_live_task_alone() {
        // The one edit that must NOT reach the task. A blank title is what deletes (PRD §4), so blanking it
        // through the mirror would delete the user's task from the account; emptying unbinds the cell
        // instead, exactly as it did before the live half was written back at all.
        val (s0, row, _) = withRowOnLiveTask()
        val liveTask = s0.defaultSubtree.tree.cells[row]!!.taskId!!

        val s = reduceInTemplate(s0, SchedulerIntent.SetCellTitle(row, ""))

        assertEquals("Existing", s.tasks[liveTask]!!.title, "the account's task keeps its title")
        assertNull(s.defaultSubtree.tree.cells[row]!!.taskId, "and the row lets go of it")
    }

    @Test
    fun the_live_half_of_a_unit_round_trips_through_the_store() {
        val (s0, row, liveChildList) = withRowOnLiveTask()
        val s = reduceInTemplate(s0, SchedulerIntent.SetCellTitle(typingRowUnder(s0, row), "AddedInWindow"))

        val loaded = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s)))
        val undone = SchedulerReducer.reduce(loaded, SchedulerIntent.Undo)

        val titles =
            undone.lists[liveChildList]!!.cellIds
                .mapNotNull { undone.cells[it]?.taskId }
                .mapNotNull { undone.tasks[it]?.title }
        assertFalse("AddedInWindow" in titles, "a reloaded unit still undoes its tree half: $titles")
        assertTrue("Kept" in titles)
    }

    @Test
    fun a_unit_written_before_the_live_half_existed_still_loads_and_undoes() {
        // Persisted-DB compatibility (CLAUDE.md): every `defaultSubtree` unit already on disk was written by
        // a build that moved only the template, and says so by carrying no live half at all.
        val (s0, row, liveChildList) = withRowOnLiveTask()
        val s = reduceInTemplate(s0, SchedulerIntent.SetCellTitle(typingRowUnder(s0, row), "AddedInWindow"))

        val encoded = SchedulerStateCodec.encodeSnapshot(s)
        val newest = encoded.history.filter { it.category == HistoryCategory.Main.name }.maxBy { it.ordinal }
        assertTrue("liveAfter" in newest.deltaJson, "the unit this build writes carries the tree half")

        // The previous shape: the same unit with those two fields simply absent.
        val json = Json { ignoreUnknownKeys = true }
        val legacyUnit =
            JsonObject(json.parseToJsonElement(newest.deltaJson).jsonObject - "liveBefore" - "liveAfter").toString()
        val legacy =
            PersistedSnapshot(
                encoded.statePayload,
                encoded.history.filterNot { it === newest } +
                    HistoryRow(
                        newest.category,
                        newest.ordinal,
                        newest.timeMillis,
                        newest.chronoId,
                        newest.debugTainted,
                        legacyUnit,
                        newest.window,
                    ),
                encoded.pointers,
            )

        val loaded = assertNotNull(SchedulerStateCodec.decodeSnapshot(legacy))
        val undone = SchedulerReducer.reduce(loaded, SchedulerIntent.Undo)

        // It moves the template only — which is exactly what such a unit meant — and undoing it neither
        // throws nor touches the tree.
        val titles =
            undone.lists[liveChildList]!!.cellIds
                .mapNotNull { undone.cells[it]?.taskId }
                .mapNotNull { undone.tasks[it]?.title }
        assertTrue("AddedInWindow" in titles, "a template-only unit leaves the tree where it is: $titles")
    }

    @Test
    fun add_default_sub_tree_is_a_no_op_without_a_template() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(firstCell(s), "Project"))
        val cell = firstCell(s)
        assertTrue(s.defaultSubtreeIsEmpty)

        assertTrue(s === SchedulerReducer.reduce(s, SchedulerIntent.AddDefaultSubtree(listOf(cell))))
    }
}
