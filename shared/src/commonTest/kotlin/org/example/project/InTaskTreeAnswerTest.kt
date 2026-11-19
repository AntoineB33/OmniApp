package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.TaskTreesAsTasks
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState

/**
 * Anomaly 2026-11-19 (*"find somewhere else in the app where a logic is not unified enough"*): whether a task is in a tree
 * was said by the Search rows one way and by the edit menus' id rows another, which called the global root "in no tree";
 * whether a task's sub-tree is the live one was said twice, and both took the root for a task cut from the tree.
 */
class InTaskTreeAnswerTest {
    @Test
    fun one_answer_for_in_a_tree_and_for_a_live_sub_tree() {
        val s = TaskTreesAsTasks.seeded(SchedulerState.empty(), "tree-x")
        val root = WellKnownIds.ROOT_TASK
        val tree = s.tasks.values.first { it.title == "tree-x" }.id
        val everyPath = SearchDomain.allPathsInAnyTree(s)
        assertTrue(SearchDomain.isInTaskTree(root, everyPath), "the root, with no path above it")
        assertTrue(SearchDomain.isInTaskTree(tree, everyPath))
        assertFalse(SearchDomain.isInTaskTree(TaskId("task/user/gone"), everyPath))
        // The Search rows say the same.
        val rows = SearchDomain.taskResults(s, "", everyPath, withRoot = true).associateBy { it.taskId }
        assertEquals(true, rows[root]?.inTaskTree)
        assertEquals(true, rows[tree]?.inTaskTree)
        assertTrue(SchedulerDomain.hasLiveSubtree(s, root))
        assertTrue(SchedulerDomain.hasLiveSubtree(s, tree))
        assertFalse(SchedulerDomain.hasLiveSubtree(s, TaskId("task/user/gone")))
    }
}
