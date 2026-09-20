package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.RelativePriorityPinKey
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskRelationKey
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * The tree used to be rooted at a conceptual `task/root` whose one child was `task/main`, with `task/main`'s
 * cells in `list/main`. The second level existed only so that sibling trees could hang beside `main`; named
 * task trees (PRD §6) are how the account actually holds several trees, so the level is gone and the one
 * survivor is `task/root` / `list/root`.
 *
 * CLAUDE.md *Persisted-DB compatibility*: every one of these loads a payload written by the PREVIOUS shape
 * and asserts what it becomes. The ids are named from far more places than the tree — pin keys, relation
 * keys, a category rule's legacy scope, stored task trees, and the whole tree snapshot inside a history
 * unit — and each of those is a separate way for the account to come back wrong.
 */
class RootTaskMigrationTest {

    /**
     * A payload in the old shape: `task/root` over `task/main` over `list/main`, holding "Book" with a
     * child "Chapter". Written the way the old build wrote it, including the cell ids that embed the list
     * id and the `task/main` legacy scope token on the account-wide category rule.
     */
    private fun legacyPayload(): String =
        """
        {"rootListId":"list/main",
         "lists":[
           {"id":"list/main","parentCellId":null,"cellIds":["cell/main/0","cell/main/1"]},
           {"id":"task/user/0/children","parentCellId":"cell/main/0",
            "cellIds":["cell/task/user/0/children/2"]}],
         "cells":[
           {"id":"cell/main/0","parentListId":"list/main","taskId":"task/user/0"},
           {"id":"cell/main/1","parentListId":"list/main","taskId":null},
           {"id":"cell/task/user/0/children/2","parentListId":"task/user/0/children",
            "taskId":"task/user/1"}],
         "tasks":[
           {"id":"task/root","title":"root","childTaskIds":["task/main"]},
           {"id":"task/main","title":"main","childListId":"list/main",
            "childTaskIds":["task/user/0"]},
           {"id":"task/user/0","title":"Book","childListId":"task/user/0/children",
            "occurrences":["cell/main/0"]},
           {"id":"task/user/1","title":"Chapter",
            "occurrences":["cell/task/user/0/children/2"]}],
         "expanded":["cell/main/0"],
         "nextTaskCounter":2,
         "nextCellCounter":3,
         "relativePriorityPins":[
           {"taskId":"task/user/1","relativeTo":"task/main","cellIds":["cell/main/0"]}],
         "taskRelations":[
           {"taskId":"task/user/1","relativeTo":"task/main","kept":true}],
         "categories":[
           {"id":"category/0","title":"deep",
            "rules":[{"scopeTaskId":"task/main","share":0.5}]}]}
        """.trimIndent()

    private val book = TaskId("task/user/0")
    private val chapter = TaskId("task/user/1")

    @Test
    fun a_pre_rename_payload_loads_with_one_root_task_and_keeps_its_tree() {
        val s = assertNotNull(SchedulerStateCodec.decode(legacyPayload()))

        // ONE well-known task, at the surviving id, titled "root", owning the tree's top-level list.
        val root = assertNotNull(s.tasks[WellKnownIds.ROOT_TASK])
        assertEquals("root", root.title)
        assertEquals(WellKnownIds.ROOT_LIST, root.childListId)
        assertEquals(WellKnownIds.ROOT_LIST, s.rootListId)
        // The conceptual level is gone — nothing is left calling itself `main`.
        assertNull(s.tasks[TaskId("task/main")])
        assertNull(s.lists[CellListId("list/main")])
        assertNull(s.titleToTaskIds["main"])

        // The user's tree came through unharmed.
        assertEquals("Book", s.tasks[book]!!.title)
        assertEquals("Chapter", s.tasks[chapter]!!.title)
        assertEquals(listOf(book), s.lists[WellKnownIds.ROOT_LIST]!!.cellIds.mapNotNull { s.cells[it]?.taskId })

        // ...and the root row is installed on top of it, expanded so the tree is visible.
        assertEquals(WellKnownIds.ROOT_CELL, SchedulerDomain.rootCellId(s))
        assertEquals(WellKnownIds.ROOT_CELL, s.lists[WellKnownIds.ROOT_LIST]!!.parentCellId)
        assertTrue(WellKnownIds.ROOT_CELL in s.expanded)
        // The expansion the payload carried is kept beside it.
        assertTrue(CellId("cell/main/0") in s.expanded)
    }

    /**
     * The reason the rewrite matches whole strings only: a cell id embeds the id of the list it was minted
     * in, so `cell/main/0` and `cell/task/user/0/children/2` must come through byte for byte. Rewriting
     * them would break every expansion, selection and category-rule scope in the payload at once.
     */
    @Test
    fun cell_ids_that_merely_contain_the_old_list_id_are_left_alone() {
        val s = assertNotNull(SchedulerStateCodec.decode(legacyPayload()))
        assertTrue(CellId("cell/main/0") in s.cells)
        assertTrue(CellId("cell/main/1") in s.cells)
        assertEquals(WellKnownIds.ROOT_LIST, s.cells[CellId("cell/main/0")]!!.parentListId)
    }

    /** The pin keys are authoritative user data, and `task/main` in one of them meant "the whole tree". */
    @Test
    fun a_relative_priority_pin_against_the_old_root_still_points_at_the_root() {
        val s = assertNotNull(SchedulerStateCodec.decode(legacyPayload()))
        assertEquals(
            setOf(CellId("cell/main/0")),
            s.relativePriorityPins[RelativePriorityPinKey(chapter, WellKnownIds.ROOT_TASK)],
        )
        assertTrue(s.relativePriorityPins.keys.none { it.relativeTo == TaskId("task/main") })
    }

    /** Same for the task-relations window's pairs — "kept" against the root must stay kept. */
    @Test
    fun a_task_relation_against_the_old_root_still_points_at_the_root() {
        val s = assertNotNull(SchedulerStateCodec.decode(legacyPayload()))
        val mark = assertNotNull(s.taskRelations[TaskRelationKey(chapter, WellKnownIds.ROOT_TASK)])
        assertTrue(mark.kept)
        assertTrue(s.taskRelations.keys.none { it.relativeTo == TaskId("task/main") })
    }

    /**
     * PRD §5: the whole tree is the one category scope that is not a cell. An old payload spelled it
     * `task/main`; it must still decode to that scope and not to a rule about some task.
     */
    @Test
    fun the_account_wide_category_rule_survives_the_rename() {
        val s = assertNotNull(SchedulerStateCodec.decode(legacyPayload()))
        val rule = s.categories.single().rules.single()
        assertNull(rule.scopeCellId)
        assertEquals(0.5, rule.share)
    }

    /**
     * ...and it is written back in the one form every build has ever read as "the whole tree". Writing the
     * root's new id there would be the single thing this migration could not undo: a build made before the
     * rename does not know `task/root`, and would drop the rule instead of keeping it account-wide.
     */
    @Test
    fun the_account_wide_scope_is_written_in_a_form_an_older_build_still_reads() {
        val s = assertNotNull(SchedulerStateCodec.decode(legacyPayload()))
        val encoded = SchedulerStateCodec.encode(s)

        // The legacy field is written as the empty string, and the encoder drops a field equal to its
        // default — so the rule ships with NO `scopeTaskId` at all. A current build and a pre-rename one
        // both read that as "the whole tree" (`legacy.isBlank()`), which is the point: the account's own
        // rules survive a downgrade, where the root's new id would have been dropped as unresolvable.
        assertFalse("scopeTaskId" in encoded, "the whole-tree scope must not name a task:\n$encoded")
        assertFalse("task/main" in encoded, "the migrated payload must not name `main` at all")

        // ...and it still round-trips as the account-wide scope here.
        val again = assertNotNull(SchedulerStateCodec.decode(encoded))
        assertNull(again.categories.single().rules.single().scopeCellId)
    }

    /**
     * The case the JSON rewrite exists for. A history unit carries a WHOLE tree snapshot, in its own row and
     * its own decode call; undo replays it over the live tree. Left unmigrated, one Ctrl+Z would put the
     * account back on `list/main` while `rootListId` said `list/root` — a tree with no rows at all.
     */
    @Test
    fun undoing_into_a_pre_rename_history_unit_still_lands_on_the_migrated_root() {
        // Build a real history unit the old way, then rewrite the payload back into the old shape and load
        // it as an older build would have written it.
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Book"))
        val snapshot = SchedulerStateCodec.encodeSnapshot(s)
        val downgraded =
            snapshot.copy(
                statePayload = snapshot.statePayload.toLegacyShape(),
                history = snapshot.history.map { it.copy(deltaJson = it.deltaJson.toLegacyShape()) },
            )

        val loaded = assertNotNull(SchedulerStateCodec.decodeSnapshot(downgraded))
        assertEquals(WellKnownIds.ROOT_LIST, loaded.rootListId)

        // Walk the whole history back: every unit's snapshot must land on the migrated shape too.
        var undone = loaded
        repeat(loaded.histories.main.units.size + loaded.histories.edit.units.size + 1) {
            undone = SchedulerReducer.reduce(undone, SchedulerIntent.Undo)
        }
        assertEquals(WellKnownIds.ROOT_LIST, undone.rootListId)
        assertNotNull(undone.tasks[WellKnownIds.ROOT_TASK])
        assertNull(undone.tasks[TaskId("task/main")])
        assertEquals(WellKnownIds.ROOT_CELL, SchedulerDomain.rootCellId(undone))
        // The row the tree is drawn under is still there, so the tree is still on screen.
        assertTrue(SchedulerDomain.visibleCellOrder(undone).contains(WellKnownIds.ROOT_CELL))
    }

    /**
     * A build that had the root cell but not yet the rename wrote `cell/root` into a list *called*
     * `list/root`, which is the very id the tree's top-level list is about to take. The migration has to
     * take that intermediate wrapper out before renaming, or the two collide and one of them wins silently.
     */
    @Test
    fun a_payload_from_the_intermediate_shape_migrates_without_an_id_collision() {
        val intermediate =
            """
            {"rootListId":"list/main",
             "lists":[
               {"id":"list/root","parentCellId":null,"cellIds":["cell/root"]},
               {"id":"list/main","parentCellId":"cell/root","cellIds":["cell/main/0"]}],
             "cells":[
               {"id":"cell/root","parentListId":"list/root","taskId":"task/main"},
               {"id":"cell/main/0","parentListId":"list/main","taskId":"task/user/0"}],
             "tasks":[
               {"id":"task/root","title":"root","childTaskIds":["task/main"]},
               {"id":"task/main","title":"main","childListId":"list/main"},
               {"id":"task/user/0","title":"Book","occurrences":["cell/main/0"]}],
             "expanded":["cell/root"],
             "nextTaskCounter":1,"nextCellCounter":1}
            """.trimIndent()

        val s = assertNotNull(SchedulerStateCodec.decode(intermediate))
        assertEquals(WellKnownIds.ROOT_LIST, s.rootListId)
        // `list/root` is now the tree's top-level list and holds the tree, not the root cell.
        assertEquals(listOf(CellId("cell/main/0")), s.lists[WellKnownIds.ROOT_LIST]!!.cellIds)
        // ...and the root cell has moved into the list that holds it alone.
        assertEquals(WellKnownIds.ROOT_CELL_LIST, s.cells[WellKnownIds.ROOT_CELL]!!.parentListId)
        assertEquals(listOf(WellKnownIds.ROOT_CELL), s.lists[WellKnownIds.ROOT_CELL_LIST]!!.cellIds)
        assertEquals(WellKnownIds.ROOT_TASK, s.cells[WellKnownIds.ROOT_CELL]!!.taskId)
        assertEquals("Book", s.tasks[book]!!.title)
    }

    /** A payload this build wrote never trips the migration: decoding it is the identity on the ids. */
    @Test
    fun a_current_payload_round_trips_unchanged() {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Book"))

        val once = SchedulerStateCodec.encode(s)
        val decoded = assertNotNull(SchedulerStateCodec.decode(once))
        assertEquals(once, SchedulerStateCodec.encode(decoded))
        assertEquals(s.tasks.keys, decoded.tasks.keys)
        assertEquals(s.lists.keys, decoded.lists.keys)
        assertEquals(s.cells.keys, decoded.cells.keys)
    }

    /** The one accepted false positive, stated so it is a decision and not a surprise. */
    @Test
    fun a_task_titled_like_the_old_id_is_retitled_rather_than_left_alone() {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "task/main"))
        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        val renamed = decoded.cells[cell]!!.taskId!!
        assertEquals("task/root", decoded.tasks[renamed]!!.title)
    }

    /**
     * The trees the payload carries BESIDE the live one — the PRD §4 template's snapshot and every stored
     * task tree's — hold the root task too, and [SchedulerDomain.withRoot] never sees either of them. So the
     * rename left both of them titled `main`: on the release account every Change Task row naming a
     * template-owned task read `main / planning / …`, a path the account's tree has nowhere, and the user
     * went looking for it there (2026-09-20, account 3).
     */
    @Test
    fun the_template_and_a_stored_tree_come_back_with_no_root_titled_main() {
        val payload =
            """
            {"rootListId":"list/main",
             "lists":[{"id":"list/main","parentCellId":null,"cellIds":["cell/main/0"]}],
             "cells":[{"id":"cell/main/0","parentListId":"list/main","taskId":"task/user/0"}],
             "tasks":[
               {"id":"task/root","title":"root","childTaskIds":["task/main"]},
               {"id":"task/main","title":"main","childListId":"list/main",
                "childTaskIds":["task/user/0"]},
               {"id":"task/user/0","title":"Book","occurrences":["cell/main/0"]}],
             "nextTaskCounter":1,"nextCellCounter":1,
             "defaultSubtreeEnabled":true,
             "defaultSubtreeTree":{
               "lists":[{"id":"list/main","parentCellId":null,
                         "cellIds":["cell/dst/0","cell/dst/1"]}],
               "cells":[{"id":"cell/dst/0","parentListId":"list/main","taskId":"task/user/9"},
                        {"id":"cell/dst/1","parentListId":"list/main","taskId":null}],
               "tasks":[{"id":"task/main","title":"main","childListId":"list/main",
                         "childTaskIds":["task/user/9"]},
                        {"id":"task/user/9","title":"planning","occurrences":["cell/dst/0"]}],
               "nextTaskCounter":10,"nextCellCounter":2},
             "taskTrees":[{"id":"tree/0","title":"Studies","tree":{
               "lists":[{"id":"list/main","parentCellId":null,"cellIds":["cell/main/9"]}],
               "cells":[{"id":"cell/main/9","parentListId":"list/main","taskId":"task/user/5"}],
               "tasks":[{"id":"task/main","title":"main","childListId":"list/main",
                         "childTaskIds":["task/user/5"]},
                        {"id":"task/user/5","title":"Reading","occurrences":["cell/main/9"]}],
               "nextTaskCounter":6,"nextCellCounter":10}}]}
            """.trimIndent()

        val s = assertNotNull(SchedulerStateCodec.decode(payload))

        val templateRoot = assertNotNull(s.defaultSubtree.tree.tasks[WellKnownIds.ROOT_TASK])
        assertEquals(SchedulerDomain.ROOT_TASK_TITLE, templateRoot.title)
        assertEquals(WellKnownIds.ROOT_LIST, templateRoot.childListId)
        assertTrue(s.defaultSubtree.tree.tasks.values.none { it.title == "main" })
        // ...and the template itself came through: the row is still there to be grafted.
        assertEquals(
            listOf("planning"),
            s.defaultSubtree.tree.lists[WellKnownIds.ROOT_LIST]!!.cellIds
                .mapNotNull { s.defaultSubtree.tree.cells[it]?.taskId }
                .mapNotNull { s.defaultSubtree.tree.tasks[it]?.title },
        )

        val storedRoot = assertNotNull(s.taskTrees.single().tree.tasks[WellKnownIds.ROOT_TASK])
        assertEquals(SchedulerDomain.ROOT_TASK_TITLE, storedRoot.title)
        assertEquals(WellKnownIds.ROOT_LIST, storedRoot.childListId)
        assertEquals("Reading", s.taskTrees.single().tree.tasks[TaskId("task/user/5")]!!.title)
    }

    /** Turns a payload this build wrote back into the shape the previous build wrote. */
    private fun String.toLegacyShape(): String =
        replace("\"task/root\"", "\"task/main\"")
            .replace("\"list/root\"", "\"list/main\"")
            .replace("\"list/root-cell\"", "\"list/root\"")
}
