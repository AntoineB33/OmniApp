package org.example.project.scheduler.state

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds

/**
 * PRD §4/§7 **Default sub-tree**: the seam that lets the template be drawn and edited by the *task tree's own
 * code*.
 *
 * [SchedulerDomain] and the tree UI are both pure functions of a [SchedulerState]. The template is a real
 * tree ([DefaultSubtreeTemplate]), so the window renders it simply by handing that component a state whose
 * tree **is** the template — the same trick the task-tree selector already plays with
 * [SchedulerState.withTaskTreeLoaded]. Nothing about the rows, the gestures, the §13 contextual menu or Edit
 * Mode needs a second implementation.
 *
 * ## Why there are two projections
 *
 * [projectDefaultSubtree] merges the **live** tree's cells/lists/tasks in underneath the template's, so that
 * a template row pointed at a live task (the switch off — PRD §4 "points at one existing task") resolves that
 * task, draws its title, and shows its own sub-tree the way any mirror does. The ordinary Change Task menu
 * can then offer live tasks, because it reads `titleToTaskIds`.
 *
 * But [SchedulerDomain.absoluteTaskPriorities] iterates **every** cell of the state it is given, so computing
 * the percentages on that merged state would divide the template's shares by the whole live tree. So
 * [defaultSubtreePriorities] computes them on a state carrying the template's cells and lists **alone**, with
 * only the live *tasks* merged in (tasks are needed to resolve a bound row's title; cells are what must not
 * be counted). The tree component already takes its percentages as a parameter, so the two simply arrive
 * from different places.
 *
 * ## Why the merge is safe
 *
 * Ids cannot collide across the two trees except at the root. A child list is `{taskId}/children` and a task
 * is `task/user/{n}`, so both are globally unique; a cell is `cell/{listId}/{n}` off a shared counter.
 * The only shared ids are [WellKnownIds.MAIN_LIST], [WellKnownIds.MAIN_TASK] and [WellKnownIds.ROOT_TASK] —
 * every tree in the account is rooted at those, exactly as a stored [TaskTreeEntry] is. The template **wins**
 * those keys, so the projection's root is the template's root and the live tree's own root becomes
 * unreachable within it. That is what makes the projection a view of the template and not a mixture.
 *
 * The shared counters are the one thing that has to be kept honest by hand: the template mints ids from the
 * projection's (maxed) counters, and [withDefaultSubtreeCapturedFrom] writes them back into **both** the
 * template and the live state, so the live tree can never later mint an id the template already used.
 *
 * ## Why writing back is safe
 *
 * A reducer pass over the projection may touch the live half — `purgeOrphanTasks` is the one to watch. That
 * half is **discarded**: [withDefaultSubtreeCapturedFrom] keeps only what is reachable from the template's
 * root and copies it into `defaultSubtree`, leaving every live-tree field of the receiver untouched. So no
 * intent dispatched in the template window can damage the real tree, whatever the reducer did on the way.
 */

/**
 * True when the template holds no titled row — nothing to graft, so the graft is skipped entirely.
 *
 * Read off the **root list**, which is exactly where the graft starts: a blank-titled row is skipped and
 * takes its children with it, so a top level of nothing but blanks grafts nothing however much structure is
 * still hanging off it. That also makes "emptied" and "never filled in" the same answer, which is what lets
 * the codec write an empty template as nothing at all.
 *
 * It lives on the state rather than on [DefaultSubtreeTemplate] because a row whose switch is off points at a
 * task the **live** tree owns, and that row's title lives on that task — the template's own map has no entry
 * for it. Asking the template alone would call a bound row untitled and skip a template that is anything but
 * empty.
 */
val SchedulerState.defaultSubtreeIsEmpty: Boolean
    get() {
        val tree = defaultSubtree.tree
        return tree.lists[WellKnownIds.MAIN_LIST]?.cellIds.orEmpty().none { cellId ->
            val taskId = tree.cells[cellId]?.taskId ?: return@none false
            (tree.tasks[taskId] ?: tasks[taskId])?.title?.isNotBlank() == true
        }
    }

/**
 * The state the "Default sub-tree" window draws and dispatches against: the template as the live tree, with
 * the account's real tree merged in underneath so a bound row resolves.
 */
fun SchedulerState.projectDefaultSubtree(): SchedulerState {
    val template = defaultSubtree
    val mergedTasks = tasks + template.tree.tasks
    return copy(
        rootListId = WellKnownIds.MAIN_LIST,
        cells = cells + template.tree.cells,
        lists = lists + template.tree.lists,
        tasks = mergedTasks,
        titleToTaskIds = SchedulerDomain.buildTitleIndex(mergedTasks),
        expanded = template.expanded,
        selection = defaultSubtreeSelection,
        editSession = defaultSubtreeEditSession,
        nextTaskCounter = maxOf(nextTaskCounter, template.tree.nextTaskCounter),
        nextCellCounter = maxOf(nextCellCounter, template.tree.nextCellCounter),
    )
}

/**
 * The percentages the template window's priority column shows: each row's share **within the template**.
 *
 * Template cells and lists only — see the class note. The live tasks ride along so a row bound to one still
 * resolves a non-blank title and therefore still counts as a populated cell.
 */
fun SchedulerState.defaultSubtreePriorities(): Map<TaskId, Double> {
    val template = defaultSubtree
    val mergedTasks = tasks + template.tree.tasks
    return SchedulerDomain.absoluteTaskPriorities(
        copy(
            rootListId = WellKnownIds.MAIN_LIST,
            cells = template.tree.cells,
            lists = template.tree.lists,
            tasks = mergedTasks,
            titleToTaskIds = SchedulerDomain.buildTitleIndex(mergedTasks),
        ),
    )
}

/**
 * Folds a reduced [projected] state back into the receiver's [SchedulerState.defaultSubtree], keeping every
 * live-tree field of the receiver as it was.
 *
 * The template is whatever is **reachable from its root**. The walk stops at a task the live tree owns: a
 * sub-list belongs to the task id (CLAUDE.md), so a row mirroring a live task must not drag a copy of that
 * task's sub-tree into the template, where it would immediately start going stale. Such a row keeps pointing
 * at the id, and the projection is what resolves it again next time.
 *
 * A task the edit *created* is in neither side's "before" map, so it is owned by the template — which is what
 * makes typing a new row in the window build a template task rather than a live one.
 */
fun SchedulerState.withDefaultSubtreeCapturedFrom(projected: SchedulerState): SchedulerState {
    // "Owned by the live tree" is judged on the state as it was BEFORE the edit: anything minted during it
    // belongs to the template. Root/main are shared by every tree and are never a mirror.
    val ownedByLive =
        tasks.keys - defaultSubtree.tree.tasks.keys -
            setOf(WellKnownIds.ROOT_TASK, WellKnownIds.MAIN_TASK)

    val cells = LinkedHashMap<CellId, Cell>()
    val lists = LinkedHashMap<CellListId, CellList>()
    val capturedTasks = LinkedHashMap<TaskId, Task>()

    fun visitList(listId: CellListId) {
        if (listId in lists) return // also the cycle guard
        val list = projected.lists[listId] ?: return
        lists[listId] = list
        for (cellId in list.cellIds) {
            val cell = projected.cells[cellId] ?: continue
            cells[cellId] = cell
            val taskId = cell.taskId ?: continue
            // A mirror of a live task: keep the binding, take nothing else.
            if (taskId in ownedByLive) continue
            val task = projected.tasks[taskId] ?: continue
            capturedTasks[taskId] = task
            task.childListId?.let(::visitList)
        }
    }

    // The root pair every tree carries, then the tree itself.
    for (id in listOf(WellKnownIds.ROOT_TASK, WellKnownIds.MAIN_TASK)) {
        projected.tasks[id]?.let { capturedTasks[id] = it }
    }
    visitList(WellKnownIds.MAIN_LIST)

    val tree =
        TreeSnapshot(
            cells = cells,
            lists = lists,
            tasks = capturedTasks,
            titleToTaskIds = SchedulerDomain.buildTitleIndex(capturedTasks),
            nextTaskCounter = maxOf(nextTaskCounter, projected.nextTaskCounter),
            nextCellCounter = maxOf(nextCellCounter, projected.nextCellCounter),
        )

    return copy(
        defaultSubtree =
            DefaultSubtreeTemplate(
                tree = tree,
                expanded = projected.expanded.filterTo(mutableSetOf()) { it in cells },
                // A cell the edit removed takes its switch with it. Read off the PROJECTION, not the
                // receiver, so a switch flipped during the reduction survives the fold either way.
                boundCells = projected.defaultSubtree.boundCells.filterTo(mutableSetOf()) { it in cells },
            ),
        defaultSubtreeSelection = projected.selection,
        defaultSubtreeEditSession = projected.editSession,
        // Ids are handed out from one counter but live in every tree: the live side must not be able to
        // re-mint what the template just took.
        nextTaskCounter = maxOf(nextTaskCounter, projected.nextTaskCounter),
        nextCellCounter = maxOf(nextCellCounter, projected.nextCellCounter),
    )
}
