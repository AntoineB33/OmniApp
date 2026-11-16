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
