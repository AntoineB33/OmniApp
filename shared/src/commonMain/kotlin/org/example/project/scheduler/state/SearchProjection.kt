package org.example.project.scheduler.state

import org.example.project.scheduler.model.CellListId

/**
 * PRD §7 *Search*: the seam that lets an expanded task row of the Search window show its sub-tree **as the
 * tree's own cells** — [TaskListProjection]'s idea, simpler: the sub-tree already has a real root list (the
 * task's own sub-list), so the state is only **re-rooted** there, with the Search window's view state
 * ([SchedulerState.searchExpanded] / [SchedulerState.searchSelection] / [SchedulerState.searchEditSession])
 * swapped in for the tree's. No synthetic list: an edit there is an edit to the live tree with no translation,
 * and a move into the sub-tree's top level is an ordinary move into that list.
 *
 * The two root walks that must not follow a re-rooting do not, for the reasons given in [projectTaskList]:
 * pruning seeds from the real root as well, and the colours are read off the live state by the window.
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
