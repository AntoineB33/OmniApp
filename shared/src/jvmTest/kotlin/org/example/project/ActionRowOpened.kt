package org.example.project

import org.example.project.scheduler.domain.SearchDomain

/**
 * [config] with the row of [action] open in the actions section of [added], and every row above it — a task's actions
 * stand inside categories since 2026-11-28 ([SearchDomain.TASK_ACTION_CATEGORIES]). Opened by the code a pinned row is,
 * then unpinned.
 */
internal fun withActionRowOpen(
    config: SearchDomain.Config,
    sections: List<Pair<SearchDomain.Kind?, List<SearchDomain.AddedAction>>>,
    added: List<SearchDomain.Result>,
    action: SearchDomain.AddedAction,
): SearchDomain.Config {
    val id = requireNotNull(SearchDomain.actionRowId(added, action)) { "no row lists $action" }
    val groups = SearchDomain.shownActionGroups(sections, added, config)
    val opened = requireNotNull(SearchDomain.withPinnedActionRowOpen(SearchDomain.withActionRowPinned(config, id), groups)) { "$action unreachable" }
    return opened.copy(pinnedActionRow = null, pinOpenedRows = emptySet())
}
