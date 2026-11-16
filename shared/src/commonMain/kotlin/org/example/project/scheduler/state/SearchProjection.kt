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
        lists = withoutRootCellAbove(listId),
    )

/**
 * Anomaly 2026-11-16 (*"'root' is the parent of a glitchy task element that is supposed to be the root of a task tree
 * … it has no name, no expansion arrow with a color background, no other numbers in the row, and the row itself is
 * short in height"*): the root task's row expanded, its sub-tree is the tree's top-level list — which a drawing starts
 * ABOVE, at the inert root cell ([org.example.project.scheduler.domain.SchedulerDomain.displayRootListId]): the strip
 * the tree is drawn under, collapsed in the window's own expansion. The row IS that root here, so the projection
 * draws the list itself — its link to the root cell cut, and put back by [withSearchSubtreeCapturedFrom].
 */
private fun SchedulerState.withoutRootCellAbove(listId: CellListId): Map<CellListId, org.example.project.scheduler.model.CellList> {
    val list = lists[listId] ?: return lists
    if (list.parentCellId == null || org.example.project.scheduler.domain.SchedulerDomain.rootCellId(copy(rootListId = listId)) == null) return lists
    return lists + (listId to list.copy(parentCellId = null))
}

/**
 * Folds a reduced [projected] sub-tree back: the tree as the reduction left it, the root, the tree's own view
 * state and the histories put back (the caller commits the gesture as one unit), the window's view state taken
 * from where the reduction left it.
 */
fun SchedulerState.withSearchSubtreeCapturedFrom(projected: SchedulerState): SchedulerState =
    projected.copy(
        rootListId = rootListId,
        // The root cell's link the projection cut ([withoutRootCellAbove]), put back.
        lists = lists[projected.rootListId]?.parentCellId
            ?.takeIf { projected.lists[projected.rootListId]?.parentCellId == null }
            ?.let { cell -> projected.lists[projected.rootListId]?.let { projected.lists + (projected.rootListId to it.copy(parentCellId = cell)) } }
            ?: projected.lists,
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
