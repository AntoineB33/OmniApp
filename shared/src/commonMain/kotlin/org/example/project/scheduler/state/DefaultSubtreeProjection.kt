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
 * The only shared ids are [WellKnownIds.ROOT_LIST] and [WellKnownIds.ROOT_TASK] —
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
 * A reducer pass over the projection touches far more of the live half than the gesture meant to: the
 * template shadows [WellKnownIds.ROOT_LIST], so the live tree's own top level is **unreachable** in the
 * projection and `pruneDetachedTree` would take the whole account's tree with it. What protects the tree is
 * therefore not a promise about the reducer but the shape of the fold: [withDefaultSubtreeCapturedFrom]
 * writes back only what it can **reach from the template's root**, and nothing else of the live half is
 * looked at at all. Whatever the reducer did to the rest of it is simply never read.
 *
 * Reachable from the template's root, though, is exactly the sub-tree of a row pointing at a live task — and
 * that sub-tree **is** the live tree's, because a sub-list belongs to the task id (CLAUDE.md). Editing it
 * here is editing it everywhere, the same way editing under a mirrored cell in the tree is. So the walk
 * splits in two: what the template owns is captured into `defaultSubtree`, and everything from a live-owned
 * task downwards is written back to the live tree.
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
        return tree.lists[WellKnownIds.ROOT_LIST]?.cellIds.orEmpty().none { cellId ->
            val taskId = tree.cells[cellId]?.taskId ?: return@none false
            (tree.tasks[taskId] ?: tasks[taskId])?.title?.isNotBlank() == true
        }
    }

/**
 * PRD §4: whether [cellId] is a **titled row of the template** — the one question "does this row carry a
 * switch?" is, asked by the window that draws it, by the intent that flips it and by the settle that keeps
 * every sub-list ending in an empty cell.
 *
 * It is [SchedulerDomain.isTextuallyEmptyCell] read against the template, and it has to be written out here
 * for the same reason [defaultSubtreeIsEmpty] does: a row whose switch is off holds its title on the **live**
 * task it points at, so the template's own map has no entry for it. A cell the template does not own answers
 * false — the rows drawn *under* a bound row are the live tree's, and a switch is a fact about a template
 * cell (`boundCells` is keyed by one).
 *
 * The shorthand it replaces was `cell.taskId != null`, which is not the same question: emptying the cell
 * directly above a list's trailing placeholder **drops** that placeholder ([applySetCellTitle]'s inverse of
 * Auto-Expansion), so the emptied cell becomes the list's bottom one and goes on pointing at its now
 * blank-titled task. Delete every row of a template sub-list and that is exactly what is left — a cell the
 * tree treats as a placeholder in every other way, which the window drew a switch on and the settle then
 * re-folded the whole template over on every reduction, for ever (2026-09-20, account 3).
 */
fun SchedulerState.isTitledDefaultSubtreeRow(cellId: CellId): Boolean {
    val tree = defaultSubtree.tree
    val taskId = tree.cells[cellId]?.taskId ?: return false
    return (tree.tasks[taskId] ?: tasks[taskId])?.title.isNullOrEmpty() == false
}

/**
 * The state the "Default sub-tree" window draws and dispatches against: the template as the live tree, with
 * the account's real tree merged in underneath so a bound row resolves.
 */
fun SchedulerState.projectDefaultSubtree(): SchedulerState {
    val template = defaultSubtree
    val mergedTasks = tasks + template.tree.tasks
    return copy(
        rootListId = WellKnownIds.ROOT_LIST,
        cells = cells + template.tree.cells,
        lists = lists + template.tree.lists,
        tasks = mergedTasks,
        titleToTaskIds = SchedulerDomain.buildTitleIndex(mergedTasks),
        expanded = template.expanded,
        selection = defaultSubtreeSelection,
        editSession = defaultSubtreeEditSession,
        nextTaskCounter = maxOf(nextTaskCounter, template.tree.nextTaskCounter),
        nextCellCounter = maxOf(nextCellCounter, template.tree.nextCellCounter),
        // The one thing about this state its own shape does not say — see [SchedulerState.isDefaultSubtreeProjection].
        isDefaultSubtreeProjection = true,
    )
}

/**
 * PRD §4: whether [cellId] is a **template row mirroring a task the LIVE tree owns** — the "switch off"
 * binding, seen from inside a [projectDefaultSubtree] state (and false for every cell of every other tree,
 * because a live cell's id is not one of the template's).
 *
 * Such a row may be re-pointed, moved or removed; the **task** is not the template's to change.
 * [withDefaultSubtreeCapturedFrom] keeps the binding and discards the task itself, so an edit expressed as a
 * change to that task evaporates at the fold — which is exactly what emptying one used to be. Clearing the
 * row's title renamed the live task to blank, the cleanup then dropped the list's trailing placeholder
 * because the emptied cell had become the bottom one, and the fold put the live title back: the row was
 * still there reading "writing", and the placeholder it had eaten was not (2026-09-17, account 3).
 */
fun SchedulerState.mirrorsLiveTaskInDefaultSubtree(cellId: CellId, taskId: TaskId): Boolean =
    isDefaultSubtreeProjection &&
        cellId in defaultSubtree.tree.cells &&
        taskId !in defaultSubtree.tree.tasks &&
        taskId in tasks

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
            rootListId = WellKnownIds.ROOT_LIST,
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
 * A task the edit *created* is in neither side's "before" map, so it is owned by whichever half of the walk
 * reached it — the template for a row typed into the template's own structure, the live tree for one typed
 * under a row that points at a live task.
 */
fun SchedulerState.withDefaultSubtreeCapturedFrom(projected: SchedulerState): SchedulerState {
    // "Owned by the live tree" is judged on the state as it was BEFORE the edit: anything minted during it
    // belongs to whichever side of the walk reached it. The root task is shared by every tree, never a mirror.
    val ownedByLive =
        tasks.keys - defaultSubtree.tree.tasks.keys -
            setOf(WellKnownIds.ROOT_TASK)

    val cells = LinkedHashMap<CellId, Cell>()
    val lists = LinkedHashMap<CellListId, CellList>()
    val capturedTasks = LinkedHashMap<TaskId, Task>()

    // The live tree's own half of what the template's rows reach: a row pointing at a live task draws that
    // task's ONE sub-list, so everything from there down belongs to the account's tree and is written back
    // to it. A task minted under such a row is the live tree's too — which side of the walk reached it is
    // what decides, and this side never consults [ownedByLive].
    val liveCells = LinkedHashMap<CellId, Cell>()
    val liveLists = LinkedHashMap<CellListId, CellList>()
    val liveTasks = LinkedHashMap<TaskId, Task>()

    fun visitLive(listId: CellListId) {
        if (listId in liveLists) return // also the cycle guard
        val list = projected.lists[listId] ?: return
        liveLists[listId] = list
        for (cellId in list.cellIds) {
            val cell = projected.cells[cellId] ?: continue
            liveCells[cellId] = cell
            val taskId = cell.taskId ?: continue
            val task = projected.tasks[taskId] ?: continue
            liveTasks[taskId] = task
            task.childListId?.let(::visitLive)
        }
    }

    fun visitList(listId: CellListId) {
        if (listId in lists) return // also the cycle guard
        val list = projected.lists[listId] ?: return
        lists[listId] = list
        for (cellId in list.cellIds) {
            val cell = projected.cells[cellId] ?: continue
            cells[cellId] = cell
            val taskId = cell.taskId ?: continue
            val task = projected.tasks[taskId] ?: continue
            // A row pointing at a live task: the template keeps the BINDING and copies no part of the task,
            // which is what stops a mirror going stale inside it. The task and its sub-tree cross to the
            // live side instead — one task id, one sub-list, edited wherever it is drawn.
            if (taskId in ownedByLive) {
                liveTasks[taskId] = task
                task.childListId?.let(::visitLive)
                continue
            }
            capturedTasks[taskId] = task
            task.childListId?.let(::visitList)
        }
    }

    // The root task every tree carries, then the tree itself.
    projected.tasks[WellKnownIds.ROOT_TASK]?.let { capturedTasks[WellKnownIds.ROOT_TASK] = it }
    visitList(WellKnownIds.ROOT_LIST)

    val tree =
        TreeSnapshot(
            cells = cells,
            lists = lists,
            tasks = capturedTasks,
            titleToTaskIds = SchedulerDomain.buildTitleIndex(capturedTasks),
            nextTaskCounter = maxOf(nextTaskCounter, projected.nextTaskCounter),
            nextCellCounter = maxOf(nextCellCounter, projected.nextCellCounter),
        )

    // The live half is MERGED, never replaced: only the entries the walk reached are written, so every part
    // of the tree the projection could not see (its whole top level, and everything `pruneDetachedTree`
    // therefore removed in there) stays exactly as the receiver has it.
    val mergedTasks = if (liveTasks.isEmpty()) tasks else tasks + liveTasks

    return copy(
        cells = if (liveCells.isEmpty()) this.cells else this.cells + liveCells,
        lists = if (liveLists.isEmpty()) this.lists else this.lists + liveLists,
        tasks = mergedTasks,
        titleToTaskIds =
            if (liveTasks.isEmpty()) titleToTaskIds else SchedulerDomain.buildTitleIndex(mergedTasks),
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
