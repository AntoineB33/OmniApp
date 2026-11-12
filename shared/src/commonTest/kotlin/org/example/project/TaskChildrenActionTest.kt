package org.example.project

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.TaskId

/**
 * User rule 2026-11-12: a task element's actions hold **"Children"** — its children as tree cells, edited as the tree's
 * are, the switch between renaming and changing the id in their Edit Mode (a result row has none). One block per task.
 */
class TaskChildrenActionTest {
    private fun groups(added: List<SearchDomain.Result>) =
        SearchDomain.actionGroups(
            SearchDomain.actionsFor(
                SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(added)),
                added,
            ),
            added,
        )

    private fun task(id: String) = SearchDomain.TaskResult(TaskId(id), id, emptyList(), inTaskTree = true)

    @Test
    fun a_task_elements_actions_hold_its_children() {
        val one = groups(listOf(task("a")))
        assertTrue(one.any { SearchDomain.AddedAction.TaskChildren in it.actions }, "listed for an added task")
        // One block per task: in each element's own group, never folded into one field over several.
        assertTrue(SearchDomain.AddedAction.TaskChildren in SearchDomain.PER_ELEMENT_ACTIONS)
        val alarm = groups(listOf(SearchDomain.ItemResult(Kind.Alarm, "x", "x", "")))
        assertFalse(alarm.any { SearchDomain.AddedAction.TaskChildren in it.actions }, "a task's alone")
    }
}
