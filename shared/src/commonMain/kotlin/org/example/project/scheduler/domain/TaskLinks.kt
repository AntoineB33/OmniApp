package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskLink
import org.example.project.scheduler.model.TaskTreeId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.TaskTreeEntry
import org.example.project.scheduler.state.TreeSnapshot

/**
 * User rule 2026-10-20 — **the tasks of the different task trees that stand for the same thing**: *"The task ids are not
 * the same, but add a right-click option to a task cell to associate it to a task id that is only used to link one task
 * id per task tree. […] When creating a new task tree, the task ids are already linked to the task ids they are the copy
 * of from the previous task tree."*
 *
 * A link group is named by a KEY ([TaskLink.linkedTo]); a task with no link is its own key, so the trees that shared ids
 * before links existed stay linked by being the same id. The ONE place the groups are read:
 *  - [copyWithFreshIds]: a new tree, its tasks under new ids, each linked to the task it copies;
 *  - [inLinkSpace]: the task-tree timeline's keyframes with every group under ONE id — so the blend, the union of the
 *    leaves and the blended attributes (all keyed by [TaskId]) treat linked tasks as one task;
 *  - [refusal]: one task per tree per key.
 */
object TaskLinks {
    /** Each linked task's key; a task absent from it is its own key ([keyOf]). */
    fun keys(state: SchedulerState): Map<TaskId, TaskId> = state.taskLinks.associate { it.taskId to it.linkedTo }

    fun keyOf(keys: Map<TaskId, TaskId>, id: TaskId): TaskId = keys[id] ?: id

    /** [tree] with its tasks renamed by [rename] — every place a tree names a task. A task [rename] leaves out keeps its id. */
    fun renamed(tree: TreeSnapshot, rename: Map<TaskId, TaskId>): TreeSnapshot {
        if (rename.isEmpty()) return tree
        fun r(id: TaskId) = rename[id] ?: id
        return tree.copy(
            cells = tree.cells.mapValues { (_, cell) -> cell.taskId?.let { cell.copy(taskId = r(it)) } ?: cell },
            lists = tree.lists.mapValues { (_, list) ->
                if (list.optionalTaskIds.isEmpty() && list.optionalTaskValues.isEmpty()) list
                else list.copy(
                    optionalTaskIds = list.optionalTaskIds.mapTo(LinkedHashSet()) { r(it) },
                    optionalTaskValues = list.optionalTaskValues.mapKeys { (id, _) -> r(id) },
                )
            },
            tasks = tree.tasks.entries.associate { (id, task) ->
                r(id) to task.copy(
                    id = r(id),
                    childTaskIds = task.childTaskIds.map { r(it) },
                    fulfilment = task.fulfilment.mapKeys { (other, _) -> r(other) },
                )
            },
            titleToTaskIds = tree.titleToTaskIds.mapValues { (_, ids) -> ids.map { r(it) } },
        )
    }

    /** What [copyWithFreshIds] makes: the copy, the counter past its new ids, the links and each old id's new one. */
    data class Copy(val tree: TreeSnapshot, val nextTaskCounter: Int, val links: List<TaskLink>, val renamed: Map<TaskId, TaskId>)

    /**
     * [tree] copied for a new task tree: every task a cell holds under a NEW id (from [nextTaskCounter]), linked to the
     * key of the task it copies. The root stays the root, and a task no cell holds (one the calendar keeps) keeps its id:
     * it is not part of what was copied.
     */
    fun copyWithFreshIds(tree: TreeSnapshot, keys: Map<TaskId, TaskId>, nextTaskCounter: Int): Copy {
        var counter = maxOf(nextTaskCounter, tree.nextTaskCounter)
        val rename = LinkedHashMap<TaskId, TaskId>()
        for (cell in tree.cells.values.sortedBy { it.id.value }) {
            val id = cell.taskId ?: continue
            if (id == WellKnownIds.ROOT_TASK || id in rename) continue
            rename[id] = TaskId("task/user/${counter++}")
        }
        val copied = renamed(tree, rename).copy(nextTaskCounter = counter)
        return Copy(copied, counter, rename.map { (old, new) -> TaskLink(new, keyOf(keys, old)) }, rename)
    }

    /**
     * [entries] (the timeline's keyframes, the live tree flushed into its own) with every link group under ONE id — the
     * live tree's task where it holds one, else the first keyframe's (in [entries]' order) — so the blend reads linked
     * tasks as one. Nothing to rename without links: the entries come back as they are.
     */
    fun inLinkSpace(state: SchedulerState, entries: List<TaskTreeEntry>): List<TaskTreeEntry> {
        if (state.taskLinks.isEmpty() || entries.isEmpty()) return entries
        val keys = keys(state)
        val representative = representatives(state, keys, entries)
        return entries.map { entry ->
            val own = entry.tree.tasks.keys
            val rename =
                own.mapNotNull { id ->
                    val to = representative[keyOf(keys, id)] ?: return@mapNotNull null
                    // Two tasks of one tree under one key ([refusal] forbids it; a merge could still bring it): kept apart.
                    if (to == id || to in own) null else id to to
                }.toMap()
            if (rename.isEmpty()) entry else entry.copy(tree = renamed(entry.tree, rename))
        }
    }

    /** Each link group's ONE id in [inLinkSpace]: the live tree's task where it holds one, else the first of [entries]'. */
    private fun representatives(state: SchedulerState, keys: Map<TaskId, TaskId>, entries: List<TaskTreeEntry>): Map<TaskId, TaskId> {
        val representative = HashMap<TaskId, TaskId>()
        for (id in state.tasks.keys) representative.getOrPut(keyOf(keys, id)) { id }
        for (entry in entries) for (id in entry.tree.tasks.keys) representative.getOrPut(keyOf(keys, id)) { id }
        return representative
    }

    /**
     * The id the task-tree timeline knows [id] by ([inLinkSpace] over the dated trees): the id the blend reports its link
     * group's priority, leaves and attributes under. [id] itself without links.
     */
    fun representativeOf(state: SchedulerState, id: TaskId): TaskId {
        if (state.taskLinks.isEmpty()) return id
        val keys = keys(state)
        val dated = state.withActiveTaskTreeFlushed().taskTrees
            .filter { it.dateMillis != null }
            .sortedWith(compareBy({ it.dateMillis }, { it.id.value }))
        return representatives(state, keys, dated)[keyOf(keys, id)] ?: id
    }

    /**
     * Why [taskId] (a task of the live tree) cannot be linked to [otherTaskId] of the tree [otherTreeId] — or null when it
     * can. One task per tree per key: refused where ANOTHER task of the live tree is already linked to that task.
     */
    fun refusal(state: SchedulerState, taskId: TaskId, otherTreeId: TaskTreeId, otherTaskId: TaskId): String? {
        val other = state.taskTrees.firstOrNull { it.id == otherTreeId } ?: return "That task tree no longer exists."
        if (other.id == state.activeTaskTreeId) return "Pick a task of another task tree."
        val target = other.tree.tasks[otherTaskId] ?: return "That task is no longer in “${other.title}”."
        if (taskId == WellKnownIds.ROOT_TASK || otherTaskId == WellKnownIds.ROOT_TASK) return "The root is not a task to link."
        val keys = keys(state)
        val key = keyOf(keys, otherTaskId)
        val taken =
            state.cells.values.asSequence().mapNotNull { it.taskId }.distinct()
                .firstOrNull { it != taskId && keyOf(keys, it) == key }
        if (taken != null) {
            val title = state.tasks[taken]?.title?.ifBlank { null } ?: taken.value
            return "“$title” of this task tree is already linked to “${target.title.ifBlank { otherTaskId.value }}” of “${other.title}”."
        }
        return null
    }

    /** [state]'s links with [taskId] linked to [otherTaskId]'s group. */
    fun linked(state: SchedulerState, taskId: TaskId, otherTaskId: TaskId): List<TaskLink> {
        val key = keyOf(keys(state), otherTaskId)
        val rest = state.taskLinks.filter { it.taskId != taskId }
        return pruned(state, if (key == taskId) rest else rest + TaskLink(taskId, key))
    }

    /**
     * [links] without those of a task no tree holds any more (a tree whose creation was undone, a task deleted): they are
     * synced rows (`server-quota.md`), so they do not outlive what they are about. Asked where links are written.
     */
    fun pruned(state: SchedulerState, links: List<TaskLink>): List<TaskLink> {
        if (links.isEmpty()) return links
        val held = HashSet<TaskId>(state.tasks.keys)
        for (entry in state.taskTrees) held += entry.tree.tasks.keys
        return links.filter { it.taskId in held }
    }
}
