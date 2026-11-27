package org.example.project

import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.ConfigurationNode
import org.example.project.scheduler.state.SchedulerState

/**
 * What a pop-up of the ▾ tree lists of [this] level when its bar holds [query] — through the one funnel the ▾ uses
 * ([SearchDomain.shownConfigurationNodes]), its own search otherwise the ▾'s default ([SearchDomain.CONFIGURATION_SEARCH])
 * but for [filters].
 */
internal fun List<ConfigurationNode>.filterConfigurationNodes(
    query: String,
    filters: SearchDomain.Filters = SearchDomain.CONFIGURATION_SEARCH.filters,
): List<ConfigurationNode> =
    SearchDomain.shownConfigurationNodes(
        SchedulerState.empty(), this, root = false, SearchDomain.ConfigurationTreeRows(this),
        SearchDomain.CONFIGURATION_SEARCH.copy(query = query, filters = filters),
    )

/**
 * User rule 2026-11-27: "Recent"'s list of every configuration, the last changed first — under its own node since it is
 * cut into parts of [SearchDomain.MAX_TREE_NODE_ROWS] ([SearchDomain.bounded]) — read back as one list, in its order.
 */
internal fun SearchDomain.ConfigurationCategory.recentOrder(): List<SearchDomain.Setting> {
    fun leaves(nodes: List<ConfigurationNode>): List<SearchDomain.Setting> = nodes.flatMap {
        when (it) {
            is SearchDomain.ConfigurationLeaf -> listOf(it.setting)
            is SearchDomain.ConfigurationCategory -> leaves(it.children)
            else -> emptyList()
        }
    }
    return leaves(children.filterIsInstance<SearchDomain.ConfigurationCategory>().single { it.id == SearchDomain.RECENT_NODE + "/all" }.children)
}

/** The leaves of "Recent"'s list of every configuration, as nodes ([recentOrder]). */
internal fun SearchDomain.ConfigurationCategory.recentLeaves(): List<SearchDomain.ConfigurationLeaf> {
    fun leaves(nodes: List<ConfigurationNode>): List<SearchDomain.ConfigurationLeaf> = nodes.flatMap {
        when (it) {
            is SearchDomain.ConfigurationLeaf -> listOf(it)
            is SearchDomain.ConfigurationCategory -> leaves(it.children)
            else -> emptyList()
        }
    }
    return leaves(children.filterIsInstance<SearchDomain.ConfigurationCategory>().single { it.id == SearchDomain.RECENT_NODE + "/all" }.children)
}
