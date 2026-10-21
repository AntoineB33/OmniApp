package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TaskLinks
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskLink
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-20: *"The task ids are not the same, but add a right-click option to a task cell to associate it to a
 * task id that is only used to link one task id per task tree. […] if there was already another task id from the current
 * task tree that was linked to this selected task id from the other task tree, then an error message shows up. When
 * creating a new task tree, the task ids are already linked to the task ids they are the copy of from the previous task
 * tree."*
 */
class TaskLinksTest {
    private fun r(s: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(s, intent)

    /** A live tree holding [titles], named "First" — a stored entry, as every account's tree is after the first start. */
    private fun firstTree(vararg titles: String): SchedulerState {
        var s = SchedulerState.empty()
        titles.forEachIndexed { i, title ->
            val cells = s.lists[s.rootListId]!!.cellIds
            s = r(s, SchedulerIntent.SetCellTitle(cells.getOrNull(i) ?: cells.last(), title))
        }
        return r(s, SchedulerIntent.CreateTaskTree("First"))
    }

    private fun liveId(s: SchedulerState, title: String): TaskId = s.tasks.values.first { it.title == title }.id

    private fun storedId(s: SchedulerState, tree: String, title: String): TaskId =
        s.taskTrees.first { it.title == tree }.tree.tasks.values.first { it.title == title }.id

    private fun treeId(s: SchedulerState, title: String) = s.taskTrees.first { it.title == title }.id

    @Test
    fun a_new_tree_is_a_copy_under_new_ids_each_linked_to_the_task_it_copies() {
        var s = firstTree("Read", "Walk")
        val read = liveId(s, "Read")
        s = r(s, SchedulerIntent.CreateTaskTree("Second"))

        assertEquals("Second", s.activeTaskTree?.title)
        assertEquals(setOf("Read", "Walk"), s.tasks.values.map { it.title }.filter { it != "root" }.toSet(), "a copy of what was on screen")
        val readCopy = liveId(s, "Read")
        assertNotEquals(read, readCopy, "under a new id")
        assertEquals(read, storedId(s, "First", "Read"), "the tree copied keeps its own")
        assertEquals(read, TaskLinks.keyOf(TaskLinks.keys(s), readCopy), "linked to the task it copies")
        // A copy of the copy joins the same group.
        s = r(s, SchedulerIntent.CreateTaskTree("Third"))
        assertEquals(read, TaskLinks.keyOf(TaskLinks.keys(s), liveId(s, "Read")))
        // Every task is its own: editing the copy leaves the original alone.
        s = r(s, SchedulerIntent.SetTaskMinimumTime(liveId(s, "Read"), 90))
        s = r(s, SchedulerIntent.SelectTaskTree(treeId(s, "First")))
        assertNotEquals(90, s.tasks[read]!!.minimumMinutes)
    }

    @Test
    fun a_task_is_linked_by_hand_and_one_task_per_tree_is_enforced() {
        var s = firstTree("Read", "Walk")
        s = r(s, SchedulerIntent.CreateTaskTree("Second"))
        // "Run" is added to "First" only: nothing in "Second" is linked to it.
        s = r(s, SchedulerIntent.SelectTaskTree(treeId(s, "First")))
        s = r(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "Run"))
        s = r(s, SchedulerIntent.SelectTaskTree(treeId(s, "Second")))
        s = r(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "Swim"))
        val first = treeId(s, "First")
        val readThere = storedId(s, "First", "Read")
        val runThere = storedId(s, "First", "Run")
        val walkHere = liveId(s, "Walk")
        val swim = liveId(s, "Swim")

        // "Walk" here may not take "Read" there: "Read" here (its copy) is already linked to it — said, and refused.
        val refusal = TaskLinks.refusal(s, walkHere, first, readThere)
        assertNotNull(refusal)
        assertTrue("“Read”" in refusal, refusal)
        assertEquals(s.taskLinks, r(s, SchedulerIntent.LinkTask(walkHere, first, readThere)).taskLinks, "refused: nothing changes")

        // "Swim" may take "Run": nothing here is linked to it.
        assertNull(TaskLinks.refusal(s, swim, first, runThere))
        s = r(s, SchedulerIntent.LinkTask(swim, first, runThere))
        assertEquals(runThere, TaskLinks.keyOf(TaskLinks.keys(s), swim))
        // …and then "Walk" may not: one task per tree.
        assertNotNull(TaskLinks.refusal(s, walkHere, first, runThere))
        // The live tree itself is not "another tree".
        assertNotNull(TaskLinks.refusal(s, swim, treeId(s, "Second"), walkHere))
    }

    @Test
    fun the_timeline_blends_linked_tasks_as_one() {
        val t0 = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        var s = firstTree("Read")
        s = r(s, SchedulerIntent.CreateTaskTree("Second"))
        // Two trees whose "Read" tasks are NOT linked (as two trees built apart are): each fades, in or out.
        s = s.copy(taskLinks = emptyList())
        s = r(s, SchedulerIntent.SetTaskTreeDate(treeId(s, "First"), t0))
        s = r(s, SchedulerIntent.SetTaskTreeDate(treeId(s, "Second"), t0 + 10 * day))
        val readHere = liveId(s, "Read")
        val readThere = storedId(s, "First", "Read")
        val apart = SchedulerDomain.blendedTaskPriorities(s, t0 + 5 * day)
        assertEquals(0.5, apart[readHere]!!, 1e-9, "the new one rises from 0")
        assertEquals(0.5, apart[readThere]!!, 1e-9, "the old one falls to 0")

        // Linked by hand: one task across the timeline, at its full share throughout, known by the live tree's id.
        s = r(s, SchedulerIntent.LinkTask(readHere, treeId(s, "First"), readThere))
        val together = SchedulerDomain.blendedTaskPriorities(s, t0 + 5 * day)
        assertEquals(readHere, TaskLinks.representativeOf(s, readThere))
        assertEquals(1.0, together[readHere]!!, 1e-9)
        assertNull(together[readThere])
    }

    @Test
    fun the_links_are_kept_and_an_older_payload_has_none() {
        var s = firstTree("Read")
        s = r(s, SchedulerIntent.CreateTaskTree("Second"))
        assertTrue(s.taskLinks.isNotEmpty())
        val decoded = SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))!!
        assertEquals(s.taskLinks.toSet(), decoded.taskLinks.toSet())

        // Written before links existed: no field at all.
        val payload = SchedulerStateCodec.encode(s).replace(Regex(""","taskLinks":\[[^\]]*\]"""), "")
        assertTrue("taskLinks" !in payload)
        assertEquals(emptyList<TaskLink>(), SchedulerStateCodec.decode(payload)!!.taskLinks)
    }
}
