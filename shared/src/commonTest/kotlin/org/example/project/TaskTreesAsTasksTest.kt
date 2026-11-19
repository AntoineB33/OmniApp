package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.model.TaskTreeId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.TaskTreeEntry
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * User rule 2026-11-16: *"The type 'task tree' is redundant, since there already are task elements. … the default db must
 * have a task element, acting as the root where all different task trees diverge, and a second task element, acting as
 * the root of a task tree, child of the first task element."* — and *"Why deleting them? I just want a unified logic."*
 */
class TaskTreesAsTasksTest {
    private val DAY_2026_08_09 = 1_786_233_600_000L

    private fun r(s: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(s, intent)

    private fun withTask(s: SchedulerState, listId: org.example.project.scheduler.model.CellListId, title: String): SchedulerState =
        r(s, SchedulerIntent.SetCellTitle(s.lists.getValue(listId).cellIds.last(), title))

    private fun task(s: SchedulerState, title: String) = s.tasks.values.first { it.title == title }

    private fun rootChildren(s: SchedulerState) =
        s.tasks.getValue(WellKnownIds.ROOT_TASK).childTaskIds.map { s.tasks.getValue(it).title }

    @Test
    fun the_default_tree_name_is_the_local_day() {
        val utc = TimeZone.of("UTC")
        assertEquals("tree-2026-08-09", SchedulerDomain.defaultTaskTreeTitle(DAY_2026_08_09, utc))
        assertEquals("tree-2026-08-10", SchedulerDomain.defaultTaskTreeTitle(DAY_2026_08_09 + 23 * 3_600_000L, TimeZone.of("Europe/Paris")))
    }

    /** The default account: the root, and one tree under it — a task, named after the day — holding the empty row. */
    @Test
    fun a_first_start_has_the_root_and_one_tree_under_it_and_records_nothing() {
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = object : AppClock {
            override fun nowMillis(): Long = DAY_2026_08_09
        }
        try {
            val s0 = SchedulerState.empty()
            val loaded = TaskSchedulerViewModel.loadInitialState(store = null, initial = s0)
            val title = SchedulerDomain.defaultTaskTreeTitle(DAY_2026_08_09)
            assertEquals(listOf(title), rootChildren(loaded))
            val tree = task(loaded, title)
            assertEquals(listOf(SearchDomain.taskKey(WellKnownIds.ROOT_TASK)), ElementTree.parentsOf(loaded, SearchDomain.taskKey(tree.id)))
            // Its sub-list is where the user types the first task.
            val withFirst = withTask(loaded, tree.childListId!!, "Alpha")
            assertEquals(listOf(listOf(SchedulerDomain.ROOT_TASK_TITLE, title)), SearchDomain.allPathsInAnyTree(withFirst).getValue(task(withFirst, "Alpha").id))
            assertEquals(s0.histories.main.units.size, loaded.histories.main.units.size, "a default is no user action")
            // A reload never seeds a second tree.
            assertEquals(listOf(title), rootChildren(TaskSchedulerViewModel.loadInitialState(store = null, initial = withFirst)))
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    @Test
    fun an_account_whose_root_already_holds_a_task_is_left_as_it_is() {
        val s = withTask(SchedulerState.empty(), WellKnownIds.ROOT_LIST, "Health")
        val loaded = TaskSchedulerViewModel.loadInitialState(store = null, initial = s)
        assertEquals(listOf("Health"), rootChildren(loaded))
    }

    /**
     * What an older build wrote: an OPEN tree (the live fields) and a stored one beside it, sharing a task with it and
     * holding one of its own with its record. Persisted-DB compatibility: it decodes into the one structure.
     */
    private fun olderShape(): SchedulerState {
        var live = withTask(SchedulerState.empty(), WellKnownIds.ROOT_LIST, "Shared")
        live = withTask(live, task(live, "Shared").childListId!!, "Leaf")
        var stored = withTask(live, task(live, "Shared").childListId!!, "Only there")
        val only = task(stored, "Only there")
        stored = stored.copy(tasks = stored.tasks + (only.id to only.copy(record = listOf(TaskTimeRange(1_000L, 2_000L)))))
        val shared = task(live, "Shared")
        live = live.copy(tasks = live.tasks + (shared.id to shared.copy(record = listOf(TaskTimeRange(5_000L, 6_000L)))))
        val open = TaskTreeEntry(TaskTreeId("tree/0"), "Open", live.captureTreeWithRecords())
        val old = TaskTreeEntry(TaskTreeId("tree/1"), "Old", stored.captureTreeWithRecords())
        return live.copy(taskTrees = listOf(open, old), activeTaskTreeId = open.id)
    }

    @Test
    fun the_stored_trees_an_older_build_wrote_become_tasks_under_the_root() {
        val before = olderShape()
        val s = SchedulerStateCodec.decode(SchedulerStateCodec.encode(before))!!
        assertTrue(s.taskTrees.isEmpty() && s.activeTaskTreeId == null, "nothing beside the one structure")
        assertEquals(listOf("Open", "Old"), rootChildren(s))
        val open = task(s, "Open")
        val old = task(s, "Old")
        // The open tree holds what the live tree held at its top; the old one its own tree, under ids of its own.
        assertEquals(listOf("Shared"), open.childTaskIds.map { s.tasks.getValue(it).title })
        val sharedHere = s.tasks.values.filter { it.title == "Shared" }
        assertEquals(2, sharedHere.size, "the stored tree's copy is a task of its own")
        val copy = sharedHere.single { it.id != task(before, "Shared").id }
        assertEquals(listOf(copy.id), old.childTaskIds)
        assertEquals(listOf("Leaf", "Only there"), copy.childTaskIds.map { s.tasks.getValue(it).title })
        // The live task counts the work the two shared; a task only the stored tree held keeps its record.
        assertTrue(copy.record.isEmpty())
        assertEquals(1, task(s, "Shared").let { live -> s.tasks.getValue(task(before, "Shared").id) }.record.size)
        assertEquals(1, task(s, "Only there").record.size)
        // The paths are the parents' titles, from the root.
        val paths = SearchDomain.allPathsInAnyTree(s)
        assertEquals(listOf(listOf(SchedulerDomain.ROOT_TASK_TITLE, "Open", "Shared")), paths.getValue(task(before, "Leaf").id))
        assertEquals(listOf(listOf(SchedulerDomain.ROOT_TASK_TITLE, "Old", "Shared")), paths.getValue(task(s, "Only there").id))
        assertTrue(paths.values.flatten().none { it.firstOrNull() != SchedulerDomain.ROOT_TASK_TITLE })
    }

    @Test
    fun what_the_plan_follows_does_not_change() {
        val before = olderShape()
        val s = SchedulerStateCodec.decode(SchedulerStateCodec.encode(before))!!
        val was = SchedulerDomain.absoluteTaskPriorities(before)
        val now = SchedulerDomain.absoluteTaskPriorities(s)
        for ((id, p) in was) if (!SchedulerDomain.isRootTask(id)) assertEquals(p, now.getValue(id), 1e-12, s.tasks[id]?.title)
        // The stored tree takes no share until the user gives it one: its tasks are 0 % tasks, which fill only what
        // nothing else may (`SchedulerFillTest`).
        assertEquals(0.0, now.getValue(task(s, "Old").id))
        assertTrue(SchedulerDomain.schedulableLeaves(s).all { (now[it] ?: 0.0) == 0.0 || s.tasks.getValue(it).title == "Leaf" })
    }

    /** Two devices bringing in the same rows write the same rows; bringing in twice changes nothing. */
    @Test
    fun the_migration_is_deterministic_and_idempotent() {
        val payload = SchedulerStateCodec.encode(olderShape())
        val a = SchedulerStateCodec.decode(payload)!!
        val b = SchedulerStateCodec.decode(payload)!!
        assertEquals(SchedulerStateCodec.syncFingerprint(a), SchedulerStateCodec.syncFingerprint(b))
        val again = SchedulerStateCodec.decode(SchedulerStateCodec.encode(a))!!
        assertEquals(SchedulerStateCodec.syncFingerprint(a), SchedulerStateCodec.syncFingerprint(again))
    }

    /** User rule 2026-11-13, a tree being its root's task (2026-11-16): the "Tree name" action renames that task. */
    @Test
    fun the_tree_name_action_renames_the_task_at_the_tree_s_root() {
        var s = withTask(SchedulerState.empty(), WellKnownIds.ROOT_LIST, "Top")
        s = withTask(s, task(s, "Top").childListId!!, "Child")
        val rows = SearchDomain.resolve(s, listOf(SearchDomain.taskKey(task(s, "Child").id)), { emptyMap() })
        val named = SearchDomain.addedIntents(s, rows, SearchDomain.AddedCommand.TreeName("Health"), 0L).fold(s, ::r)
        assertEquals(listOf("Health"), rootChildren(named))
        assertEquals(listOf(listOf(SchedulerDomain.ROOT_TASK_TITLE, "Health")), SearchDomain.allPathsInAnyTree(named).getValue(task(named, "Child").id))
        assertNotEquals(s, named)
        assertEquals(listOf("Top"), rootChildren(r(named, SchedulerIntent.Undo)), "one undo")
    }
}
