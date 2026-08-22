package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.DefaultSubtreeNode
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

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
 */
class DefaultSubtreeTest {

    private fun node(id: String, title: String, taskId: TaskId? = null, children: List<DefaultSubtreeNode> = emptyList()) =
        DefaultSubtreeNode(id = id, title = title, taskId = taskId, children = children)

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

    private fun childCells(state: SchedulerState, cellId: CellId): List<CellId> {
        val taskId = state.cells[cellId]!!.taskId!!
        val listId = state.tasks[taskId]!!.childListId!!
        return state.lists[listId]!!.cellIds.filter { state.cells[it]?.taskId != null }
    }

    /** An account with the given template, the policy on. */
    private fun withTemplate(nodes: List<DefaultSubtreeNode>): SchedulerState {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtree(nodes))
        return SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(true))
    }

    // ---- the graft -----------------------------------------------------------------------------

    @Test
    fun naming_an_empty_cell_grafts_the_template_under_the_new_task() {
        val s0 = withTemplate(listOf(node("dst/0", "Plan"), node("dst/1", "Do")))
        val cell = firstCell(s0)
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))

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
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))

        assertEquals(listOf("Plan"), childTitles(s, cell))
        val plan = childCells(s, cell).single()
        assertEquals(listOf("Sketch", "Review"), childTitles(s, plan))
    }

    @Test
    fun the_graft_never_seeds_the_tasks_it_just_created() {
        // The graft is what CREATES these tasks, so re-applying the template under each of them would be an
        // unbounded cascade (every seeded row seeding the whole template again, for ever). A seeded row gets
        // the template node's OWN children and nothing else; a leaf node's task gets an empty sub-list.
        val s0 =
            withTemplate(
                listOf(node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch"))), node("dst/2", "Do")),
            )
        val cell = firstCell(s0)
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))

        val (plan, do_) = childCells(s, cell)
        assertEquals(listOf("Sketch"), childTitles(s, plan), "a seeded row keeps its own template children")
        assertEquals(emptyList(), childTitles(s, do_), "a seeded leaf must not be seeded in turn")
        val sketch = childCells(s, plan).single()
        assertEquals(emptyList(), childTitles(s, sketch), "the graft must stop at the bottom of the template")
        // Exactly the named task plus one per template node — no second round anywhere.
        assertEquals(
            s0.tasks.size + 4,
            s.tasks.size,
            "one task per template node plus the one the user named — nothing more",
        )
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

        assertEquals(listOf("Plan", "Do"), childTitles(s, cell))
        val (plan, do_) = childCells(s, cell)
        assertEquals(listOf("Sketch"), childTitles(s, plan))
        assertEquals(emptyList(), childTitles(s, do_))
        assertEquals(emptyList(), childTitles(s, childCells(s, plan).single()))
        assertEquals(
            s0.tasks.size + 4,
            s.tasks.size,
            "one task per template node plus the one the user named — nothing more",
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

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtree(listOf(node("dst/0", "Shared", sharedTask))))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(true))

        val target = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(target, "Project"))

        val seeded = childCells(s, target).single()
        assertEquals(sharedTask, s.cells[seeded]!!.taskId)
        assertEquals(listOf("Inherited"), childTitles(s, seeded))
    }

    @Test
    fun a_binding_this_tree_cannot_honour_falls_back_to_a_new_task() {
        // A template is account-wide but a task id lives in one task tree, so a stale/foreign binding must
        // still produce the row rather than silently dropping it.
        val s0 = withTemplate(listOf(node("dst/0", "Plan", TaskId("task/user/999"))))
        val cell = firstCell(s0)
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))

        assertEquals(listOf("Plan"), childTitles(s, cell))
        assertTrue(s.tasks[TaskId("task/user/999")] == null)
    }

    // ---- the gates -----------------------------------------------------------------------------

    @Test
    fun nothing_is_grafted_while_the_policy_switch_is_off() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtree(listOf(node("dst/0", "Plan"))))
        val cell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Project"))

        assertEquals(emptyList(), childTitles(s, cell))
    }

    @Test
    fun renaming_an_existing_task_does_not_graft_again() {
        val s0 = withTemplate(listOf(node("dst/0", "Plan")))
        val cell = firstCell(s0)
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cell, "Project"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Project renamed"))

        assertEquals(listOf("Plan"), childTitles(s, cell))
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
        assertEquals(listOf("Plan", "Do"), childTitles(s, cell))
        // The seeded sub-tree is shown rather than folded away behind a collapsed cell.
        assertTrue(cell in s.expanded)

        // The whole session is ONE unit, so one Ctrl+Z takes the title and everything it pulled in.
        assertEquals(1, s.histories.forCategory(HistoryCategory.Main).units.size)
        val undone = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertNull(undone.cells[cell]!!.taskId)
        assertEquals(emptyList(), childTitles(undone, cell))
    }

    // ---- the menu / the switch -----------------------------------------------------------------

    @Test
    fun the_identity_menu_leads_with_new_id_and_offers_exact_title_matches() {
        var s = SchedulerState.empty()
        val cell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Shared"))
        val shared = s.cells[cell]!!.taskId!!

        val empty = SchedulerDomain.defaultSubtreeTaskMenuEntries(s, "")
        assertEquals(listOf(SchedulerDomain.DEFAULT_SUBTREE_NEW_ID_LABEL), empty.map { it.label })
        assertNull(empty.single().taskId)

        val matched = SchedulerDomain.defaultSubtreeTaskMenuEntries(s, "Shared")
        assertEquals(2, matched.size)
        assertNull(matched.first().taskId)
        assertEquals(shared, matched[1].taskId)

        // A partial match is not an identity (PRD §4: the row appears only on an exact title).
        assertEquals(1, SchedulerDomain.defaultSubtreeTaskMenuEntries(s, "Sha").size)
    }

    @Test
    fun normalization_drops_empty_rows_with_their_children() {
        // PRD §4: the blank title is what deletes, here as in the tree. The editor keeps a trailing empty row
        // per list (Auto-Expansion) only while its window is open; nothing stored may hold one.
        val nodes =
            listOf(
                node("dst/0", "Plan", children = listOf(node("dst/1", "Sketch"), node("dst/2", ""))),
                node("dst/3", "", TaskId("task/user/1")),
                node("dst/4", "", children = listOf(node("dst/5", "Deep"))),
            )
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetDefaultSubtree(nodes))

        assertEquals(listOf("Plan"), s.defaultSubtree.map { it.title })
        assertEquals(listOf("Sketch"), s.defaultSubtree.single().children.map { it.title })
    }

    @Test
    fun a_bound_row_reads_the_bound_tasks_own_sub_tree_for_the_window_to_draw() {
        // PRD §4: the window draws a bound row's borrowed sub-tree under it, so what it shows is exactly
        // what the graft will produce — the bound task's OWN children, not the template node's.
        var s = SchedulerState.empty()
        val sharedCell = firstCell(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(sharedCell, "Shared"))
        val sharedTask = s.cells[sharedCell]!!.taskId!!
        val sharedChildList = s.tasks[sharedTask]!!.childListId!!
        val childCell = s.lists[sharedChildList]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(childCell, "Inherited"))
        val grandChildList = s.tasks[s.cells[childCell]!!.taskId!!]!!.childListId!!
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetCellTitle(s.lists[grandChildList]!!.cellIds.first(), "Deeper"),
        )

        val outline = SchedulerDomain.taskSubtreeOutline(s, sharedTask)

        // The trailing empty cell of each sub-list is not something the graft produces, so it is not drawn.
        assertEquals(listOf("Inherited"), outline.map { it.title })
        assertEquals(listOf("Deeper"), outline.single().children.map { it.title })
        // A leaf task borrows nothing.
        assertEquals(emptyList(), SchedulerDomain.taskSubtreeOutline(s, s.cells[childCell]!!.taskId!!)
            .single().children)
    }

    @Test
    fun the_switch_is_the_task_binding() {
        assertTrue(node("dst/0", "Plan").newTaskId)
        assertFalse(node("dst/0", "Plan", TaskId("task/user/1")).newTaskId)
    }

    // ---- persistence ---------------------------------------------------------------------------

    @Test
    fun a_payload_written_before_the_feature_decodes_to_no_template_and_the_switch_off() {
        // The previous shape: the same state minus the two fields (an older build's payload).
        val before = SchedulerStateCodec.encode(SchedulerState.empty())
        assertFalse(before.contains("defaultSubtree"), "the fixture must predate the fields")

        val decoded = SchedulerStateCodec.decode(before)
        assertNotNull(decoded)
        assertEquals(emptyList(), decoded.defaultSubtree)
        assertFalse(decoded.defaultSubtreeEnabled)
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

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtree(listOf(node("dst/0", "Plan"))))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetDefaultSubtreeEnabled(true))

        // Typing "Shared" there defaults the id menu to the existing task (PRD §4 Default selection).
        val reusing = s.lists[containerList]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.BeginEdit(reusing, initialText = "Shared"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.PickTaskFromMenu(shared))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExitEdit(EditExitNavigation.Down))

        assertEquals(shared, s.cells[reusing]!!.taskId)
        assertEquals(listOf("Inherited"), childTitles(s, reusing))
    }
}
