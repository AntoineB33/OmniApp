package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId

/**
 * PRD §7 **All tasks**: the seam that lets that window be drawn and edited by the *task tree's own code*.
 *
 * The window lists every task the tree holds, ordered by its sorter — and each row is a **task cell**, with
 * the tree's chrome, its Edit Mode, its selection and keyboard, its drag-move, its §13 contextual menu and
 * its Ctrl+C/X/V/F. That is possible for exactly the reason the PRD §4 template window is
 * ([projectDefaultSubtree]): the tree UI and [org.example.project.scheduler.domain.SchedulerDomain] are pure
 * functions of a [SchedulerState], so a second drawing is the same code over a different state.
 *
 * ## What the projection changes, and what it must not
 *
 * Only two things: the state is **re-rooted** at [TASK_LIST_ROOT_ID] — a synthetic list holding, in the
 * sorter's order, the **first occurrence cell** of every listed task — and the window's own view state
 * ([SchedulerState.taskListExpanded] / [SchedulerState.taskListSelection] /
 * [SchedulerState.taskListEditSession]) is swapped in for the tree's.
 *
 * The root rows are **real cells of the live tree**, never synthetic ones. That is what makes an edit in the
 * window an edit to the tree with no translation at all: renaming a root row renames the task, its minimum
 * time is the task's, its percentage is the tree's own. It is also what keeps every count honest —
 * [org.example.project.scheduler.domain.SchedulerDomain.absoluteTaskPriorities] and `taskListEntries` count
 * occurrences off `state.cells`, and a synthetic cell per task would silently double every one of them.
 *
 * ## Why re-rooting is safe
 *
 * Everything the tree navigates by — the visible order, `Ctrl+A`, the arrow keys, Ctrl+F's walk — reads
 * [SchedulerState.rootListId], so re-rooting is the whole of what makes them follow the *window's* rows.
 *
 * Two root walks must NOT follow it, and neither does:
 *  - [org.example.project.scheduler.domain.SchedulerDomain.pruneDetachedTree] seeds from
 *    [org.example.project.scheduler.model.WellKnownIds.MAIN_LIST] as well as `rootListId`, because a cell the
 *    real root holds that is not one of the first occurrences is reachable from neither the synthetic root
 *    nor a detached parent — and without that seed the first edit boundary in this window would delete it;
 *  - the colours ([org.example.project.scheduler.domain.TaskColorSpace]) are read off the **live** state by
 *    the window (`TaskTreeView`'s `colorSource`), so a task is the same colour in the list, in the tree and
 *    on the calendar. Solved over the projection they would be ordered by the sorter instead of by the tree.
 *
 * ## Why writing back is safe
 *
 * [withTaskListCapturedFrom] takes the reduced tree whole — these ARE the live tree's cells, lists and tasks
 * — and puts back only the three things the projection borrowed: the root id, the live tree's own view
 * state, and the histories (the window's gesture is committed as ONE unit by
 * `SchedulerReducer.reduceInTaskList`, so the inner reduction's units evaporate with the projection). The
 * synthetic list is dropped there and nowhere else, which is what keeps it out of every history delta, out
 * of the persisted payload and off the wire.
 */

/**
 * The synthetic root list the "All tasks" window is drawn from: one cell per task, in the sorter's order.
 *
 * It exists only inside a projection — [withTaskListCapturedFrom] removes it again — so it is never
 * persisted, never synced and never named by a history unit.
 */
val TASK_LIST_ROOT_ID: CellListId = CellListId("list/all-tasks")

/**
 * The state the "All tasks" window draws and dispatches against.
 *
 * [rootCells] is the window's row order — the first occurrence cell of each listed task, already sorted (and
 * possibly still on the *previous* sort, which is what the window's "update order" button is about). The
 * window computes it, and the same list rides on [SchedulerIntent.InTaskList] so the reducer re-projects
 * exactly the rows the gesture was made on.
 */
fun SchedulerState.projectTaskList(rootCells: List<CellId>): SchedulerState =
    copy(
        rootListId = TASK_LIST_ROOT_ID,
        lists =
            lists +
                (
                    TASK_LIST_ROOT_ID to
                        CellList(id = TASK_LIST_ROOT_ID, parentCellId = null, cellIds = rootCells)
                    ),
        expanded = taskListExpanded,
        selection = taskListSelection,
        editSession = taskListEditSession,
    )

/**
 * Folds a reduced [projected] state back onto the receiver: the tree as the reduction left it, everything
 * the projection borrowed put back.
 *
 * The tree half needs no translation — the projection re-rooted the live tree, it did not copy it — so the
 * result IS [projected] with the synthetic list removed, the real root restored, the tree's own view state
 * restored, and the receiver's histories kept (the caller commits the whole gesture as one unit).
 */
fun SchedulerState.withTaskListCapturedFrom(projected: SchedulerState): SchedulerState =
    projected.copy(
        rootListId = rootListId,
        lists = projected.lists - TASK_LIST_ROOT_ID,
        // The tree's own view state: an edit made in the window never moves the tree's caret or its rows.
        expanded = expanded,
        selection = selection,
        editSession = editSession,
        // ... and the window's own, taken from where the reduction left it. A cell the edit removed takes
        // its expansion and its selection with it.
        taskListExpanded = projected.expanded.filterTo(mutableSetOf()) { it in projected.cells },
        taskListSelection = projected.selection,
        taskListEditSession = projected.editSession,
        histories = histories,
    )
