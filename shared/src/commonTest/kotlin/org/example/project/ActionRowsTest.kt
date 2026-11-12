package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind

/**
 * User rule 2026-11-12: **the actions section is a Search window whose results are actions** — rows, some with children,
 * none open by default — laid out by three search configurations of its own: grouped by the added elements involved (on
 * by default), the results expanded (off), the bar sorting and hiding nothing (on, the behaviour of 2026-10-11).
 */
class ActionRowsTest {
    private fun quota(id: String, title: String) = SearchDomain.ItemResult(Kind.Quota, id, title, "")
    private val added = listOf(quota("q1", "Sport"), quota("q2", "Reading"))
    private fun sections(config: SearchDomain.Config) =
        SearchDomain.actionsFor(SearchDomain.addedActions(config.actionQuery, Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)

    @Test
    fun the_section_opens_grouped_closed_and_sorting() {
        val config = SearchDomain.Config()
        assertTrue(config.actionsGrouped)
        assertFalse(config.actionsExpanded)
        assertTrue(config.actionSearchSortsOnly)
        val groups = SearchDomain.shownActionGroups(sections(config), added, config)
        // Grouped from the largest set of elements to the smallest: both quotas, then each.
        assertEquals(listOf("kind/Quota", "element/Quota/q1", "element/Quota/q2"), groups.map { it.id })
        // None open: neither a group nor an action.
        assertTrue(groups.none { SearchDomain.isActionRowOpen(config, it.id, top = true) })
        assertTrue(groups.flatMap { g -> g.actions.map { SearchDomain.microGroupId(g, it) } }.none { SearchDomain.isActionRowOpen(config, it, top = false) })
    }

    @Test
    fun expanded_the_top_rows_open_not_their_children() {
        val config = SearchDomain.Config(actionsExpanded = true)
        val group = SearchDomain.shownActionGroups(sections(config), added, config).first()
        assertTrue(SearchDomain.isActionRowOpen(config, group.id, top = true))
        assertFalse(SearchDomain.isActionRowOpen(config, SearchDomain.microGroupId(group, group.actions.first()), top = false))
        // Its arrow closes it: a row toggled from the default.
        val closed = SearchDomain.withActionGroupToggled(config, group.id, setOf(group.id))
        assertFalse(SearchDomain.isActionRowOpen(closed, group.id, top = true))
    }

    @Test
    fun the_bar_sorts_by_default_and_filters_when_told() {
        val sorting = SearchDomain.Config(actionSearch = "duplicate")
        val sorted = SearchDomain.shownActionGroups(sections(sorting), added, sorting)
        val all = SearchDomain.shownActionGroups(sections(SearchDomain.Config()), added, SearchDomain.Config())
        // Sorting: every group, every action, the answering one first.
        assertEquals(all.map { it.id }, sorted.map { it.id })
        assertEquals(all.map { it.actions.toSet() }, sorted.map { it.actions.toSet() })
        assertTrue(sorted.filter { SearchDomain.AddedAction.QuotaDuplicate in it.actions }.all { it.actions.first() == SearchDomain.AddedAction.QuotaDuplicate })
        // Filtering: only the actions answering, and only the groups holding one.
        val filtering = sorting.copy(actionSearchSortsOnly = false)
        val filtered = SearchDomain.shownActionGroups(sections(filtering), added, filtering)
        assertTrue(filtered.isNotEmpty())
        assertTrue(filtered.all { g -> g.actions.isNotEmpty() && g.actions.all { SearchDomain.actionSearchRank(it, "duplicate") != null } }, filtered.toString())
        assertTrue(filtered.size <= all.size)
    }

    @Test
    fun the_three_are_search_configurations_kept_with_the_window() {
        val settings = SearchDomain.Setting.entries
        assertTrue(SearchDomain.Setting.GroupByAddedElements in settings && SearchDomain.Setting.ExpandResults in settings && SearchDomain.Setting.SearchSortsOnly in settings)
        val changed = SearchDomain.Config(actionsGrouped = false, actionsExpanded = true, actionSearchSortsOnly = false, toggledActionRows = setOf("kind/Quota"))
        assertEquals(changed, SearchDomain.Config.decode(changed.encode()))
    }

    /**
     * User rule 2026-11-12: *"The action section is a Search window"* — its rows ([SearchDomain.actionListRows]), as its
     * Search window lists them: closed, only the groups; an open group shows its actions a level in; an open action is
     * open; not grouped, each action is a top row naming the elements it acts on.
     */
    @Test
    fun the_section_s_search_window_lists_its_rows_as_the_section_did() {
        val config = SearchDomain.Config()
        val groups = SearchDomain.shownActionGroups(sections(config), added, config)
        val closed = SearchDomain.actionListRows(groups, config)
        assertEquals(groups.map { it.id }, closed.map { it.key })
        assertTrue(closed.all { it.action == null && it.depth == 0 && !it.open })

        val first = groups.first()
        val opened = config.copy(toggledActionRows = setOf(first.id))
        val rows = SearchDomain.actionListRows(groups, opened)
        val under = rows.drop(1).takeWhile { it.depth > 0 }
        assertEquals(first.actions.toSet(), under.mapNotNull { it.action }.toSet())
        assertTrue(under.all { it.depth == 1 && !it.open })

        val action = under.first()
        val actionOpen = opened.copy(toggledActionRows = opened.toggledActionRows + action.id)
        assertTrue(SearchDomain.actionListRows(groups, actionOpen).first { it.key == action.key }.open)

        val flat = SearchDomain.actionListRows(groups, config.copy(actionsGrouped = false))
        assertTrue(flat.all { it.depth == 0 || it.action == null })
        assertEquals(first.title, flat.first { it.action != null }.detail, "it names the elements it acts on")
        // Every key once: what the list is keyed by.
        assertEquals(flat.size, flat.map { it.key }.toSet().size)
    }
}
