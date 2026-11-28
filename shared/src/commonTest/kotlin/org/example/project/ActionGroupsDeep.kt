package org.example.project

import org.example.project.scheduler.domain.SearchDomain

/**
 * Every action of [this] group and of the groups inside it, in order: an element's actions stand under categories since
 * 2026-11-28 ([SearchDomain.actionCategoriesOf]), so "is it listed for this element" reads down through them.
 */
internal fun SearchDomain.ActionGroup.allActions(): List<SearchDomain.AddedAction> = actions + inner.flatMap { it.allActions() }

/** The id of [action]'s row in [this] group or a group inside it ([SearchDomain.microGroupId] of the group holding it). */
internal fun SearchDomain.ActionGroup.rowIdOf(action: SearchDomain.AddedAction): String {
    fun holding(g: SearchDomain.ActionGroup): SearchDomain.ActionGroup? = if (action in g.actions) g else g.inner.firstNotNullOfOrNull(::holding)
    return SearchDomain.microGroupId(requireNotNull(holding(this)) { "$action is in no group of $id" }, action)
}
