package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellListId

/**
 * PRD §7 *Search*: the seam that lets an expanded task row of the Search window show its sub-tree **as the
 * tree's own cells**, the idea [projectDefaultSubtree] is built on: the sub-tree already has a real root list (the
 * task's own sub-list), so the state is only **re-rooted** there, with the Search window's view state
 * ([SchedulerState.searchExpanded] / [SchedulerState.searchSelection] / [SchedulerState.searchEditSession])
 * swapped in for the tree's. No synthetic list: an edit there is an edit to the live tree with no translation,
 * and a move into the sub-tree's top level is an ordinary move into that list.
 *
 * Everything the tree navigates by — the visible order, `Ctrl+A`, the arrow keys, Ctrl+F's walk — reads
 * [SchedulerState.rootListId], so re-rooting is the whole of what makes them follow the window's rows. Two root
 * walks must NOT follow it, and neither does:
 *  - [org.example.project.scheduler.domain.SchedulerDomain.pruneDetachedTree] seeds from
 *    [org.example.project.scheduler.model.WellKnownIds.ROOT_LIST] as well as `rootListId`, because a cell the
 *    real root holds is not reachable from the sub-tree's root — and without that seed the first edit boundary
 *    in the window would delete it;
 *  - the colours ([org.example.project.scheduler.domain.TaskColorSpace]) are read off the **live** state by the
 *    window (`TaskTreeView`'s `colorSource`), so a task is the same colour here, in the tree and on the calendar.
 */
fun SchedulerState.projectSearchSubtree(listId: CellListId): SchedulerState =
    copy(
        rootListId = listId,
        expanded = searchExpanded,
        selection = searchSelection,
        editSession = searchEditSession,
    )

/**
 * Folds a reduced [projected] sub-tree back: the tree as the reduction left it, the root, the tree's own view
 * state and the histories put back (the caller commits the gesture as one unit), the window's view state taken
 * from where the reduction left it.
 */
fun SchedulerState.withSearchSubtreeCapturedFrom(projected: SchedulerState): SchedulerState =
    projected.copy(
        rootListId = rootListId,
        expanded = expanded,
        selection = selection,
        editSession = editSession,
        searchExpanded = projected.expanded.filterTo(mutableSetOf()) { it in projected.cells },
        searchSelection = projected.selection,
        searchEditSession = projected.editSession,
        histories = histories,
    )

/** [withSearchSubtreeCapturedFrom] keeping ONLY the window's view state — a read-only sub-tree's gesture. */
fun SchedulerState.withSearchViewStateFrom(projected: SchedulerState): SchedulerState =
    copy(
        searchExpanded = projected.expanded.filterTo(mutableSetOf()) { it in projected.cells },
        searchSelection = projected.selection,
        searchEditSession = null,
    )
