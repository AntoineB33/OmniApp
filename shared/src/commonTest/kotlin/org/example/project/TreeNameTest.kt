package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.AddedAction
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-13: *"Any task that is the direct child of [the global root] is the root of a new tree. Giving the global
 * root as a parent of a task gives this task actions relative to the tree (renaming it for example)"* — and renaming it
 * names the TREE, not the task.
 */
class TreeNameTest {
    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    /** "Top" at the top of the tree, "Child" under it. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        s = r(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "Top"))
        val top = s.tasks.values.first { it.title == "Top" }
        return r(s, SchedulerIntent.SetCellTitle(s.lists[top.childListId]!!.cellIds.last(), "Child"))
    }

    private fun added(s: SchedulerState, title: String) =
        SearchDomain.resolve(s, listOf(SearchDomain.taskKey(s.tasks.values.first { it.title == title }.id)), { emptyMap() })

    private fun offered(s: SchedulerState, title: String): Boolean {
        val rows = added(s, title)
        return SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(rows)), rows)
            .flatMap { it.second }.contains(AddedAction.TaskTreeName)
    }

    /** User rule 2026-11-13: *"the tree action should be in all tasks belonging to a tree."* */
    @Test
    fun every_task_of_a_tree_is_offered_its_tree_name_and_a_task_in_no_tree_is_not() {
        val s = account()
        assertTrue(offered(s, "Top"), "the tree's root")
        assertTrue(offered(s, "Child"), "a task of the tree")
        val loose = org.example.project.scheduler.model.Task(org.example.project.scheduler.model.TaskId("loose"), "Loose")
        val withLoose = s.copy(tasks = s.tasks + (loose.id to loose))
        assertFalse(offered(withLoose, "Loose"), "in no tree")
        // Given the global root as a parent, it is a tree's root and has it.
        val root = SearchDomain.taskKey(WellKnownIds.ROOT_TASK)
        val moved = ElementTree.parentIntents(withLoose, listOf(SearchDomain.taskKey(loose.id)), root, on = true).fold(withLoose, ::r)
        assertTrue(offered(moved, "Loose"))
    }

    @Test
    fun a_task_s_tree_name_names_its_root_s_tree() {
        val s = account()
        val named = SearchDomain.addedIntents(s, added(s, "Child"), SearchDomain.AddedCommand.TreeName("Health"), 0L).fold(s, ::r)
        assertEquals("Health", named.tasks.values.first { it.title == "Top" }.treeName, "the tree's root holds it")
        assertEquals("", named.tasks.values.first { it.title == "Child" }.treeName)
    }

    /**
     * Anomaly 2026-11-16 (*"it says 'tree-2026-10-20 / Very Short Term'. However, when I go to the list of parents of 'Very
     * Short Term', it is not a task element named 'tree-2026-10-20', but one named 'root'"*): **a path is its parents'
     * titles** — the first segment the global root's, whatever the tree's name (it began with the tree's name, user rule
     * 2026-11-13, and named no element above the task). The tree's name stays the top task's ([a_task_s_tree_name_names_its_root_s_tree]).
     */
    @Test
    fun a_path_is_its_parents_titles_whatever_the_tree_s_name() {
        val s = account()
        val top = s.tasks.values.first { it.title == "Top" }
        val child = s.tasks.values.first { it.title == "Child" }
        val named = r(s, SchedulerIntent.SetTreeName(listOf(top.id), "Health"))
        val root = named.tasks.getValue(WellKnownIds.ROOT_TASK).title
        assertEquals(listOf(root, "Top"), SearchDomain.shortestPathsInAnyTree(named).getValue(child.id))
        assertEquals(listOf(root), SearchDomain.shortestPathsInAnyTree(named).getValue(top.id))
        assertEquals(listOf(listOf(root, "Top")), SearchDomain.allPathsInAnyTree(named).getValue(child.id))
        // Each segment is the title of a parent element, one level up each time.
        assertEquals(listOf(SearchDomain.taskKey(top.id)), ElementTree.parentsOf(named, SearchDomain.taskKey(child.id)))
        assertEquals(listOf(SearchDomain.taskKey(WellKnownIds.ROOT_TASK)), ElementTree.parentsOf(named, SearchDomain.taskKey(top.id)))
        // "Go to task tree" from that path finds the cell; a path from another root does not.
        assertTrue(SearchDomain.occurrenceAtPath(named, child.id, listOf(root, "Top")) != null)
        assertTrue(SearchDomain.occurrenceAtPath(named, child.id, listOf("Health", "Top")) == null)
    }

    @Test
    fun the_tree_name_is_the_tree_s_not_the_task_s_title_and_one_unit() {
        val s = account()
        val rows = added(s, "Top")
        val named = SearchDomain.addedIntents(s, rows, SearchDomain.AddedCommand.TreeName("Health"), 0L).fold(s, ::r)
        val top = named.tasks.values.first { it.title == "Top" }
        assertEquals("Health", top.treeName)
        assertEquals("Top", top.title, "the task keeps its title")
        assertEquals("", r(named, SchedulerIntent.Undo).tasks.values.first { it.title == "Top" }.treeName, "one undo")
    }

    @Test
    fun the_tree_name_survives_the_store_and_an_older_payload_has_none() {
        val s = account()
        val named = r(s, SchedulerIntent.SetTreeName(listOf(s.tasks.values.first { it.title == "Top" }.id), "Health"))
        val back = SchedulerStateCodec.decode(SchedulerStateCodec.encode(named))!!
        assertEquals("Health", back.tasks.values.first { it.title == "Top" }.treeName)
        // What a build before the field wrote: no "treeName" anywhere.
        val older = SchedulerStateCodec.encode(s)
        assertFalse("treeName" in older, "an empty name is not written")
        assertEquals("", SchedulerStateCodec.decode(older)!!.tasks.values.first { it.title == "Top" }.treeName)
    }
}
