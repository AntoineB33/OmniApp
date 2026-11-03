package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Filters
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-02: *"the Search window that appears should have a filter to exclude the parent element. This filter must
 * be present in the macro group 'Every element' in the Search configuration window, and is a field with a drop-down menu with
 * check boxes. There should be one for exclusions, and one for inclusions overriding the other filters."*
 */
class ElementFiltersTest {
    private val state =
        SchedulerState.empty().copy(chronos = listOf(ChronoEntry(id = "a", label = "First"), ChronoEntry(id = "b", label = "Second")))

    private fun keys(filters: Filters, kinds: Set<Kind> = setOf(Kind.Chrono), query: String = "") =
        SearchDomain.results(state, kinds, query, { emptyMap() }, filters).map(SearchDomain::keyOf).filterNot { it.startsWith("Creation/") }

    @Test
    fun an_excluded_element_leaves_the_list() {
        assertEquals(listOf("Chrono/b"), keys(Filters(excludedElements = setOf("Chrono/a"))))
    }

    @Test
    fun an_inclusion_overrides_an_exclusion() {
        val both = Filters(excludedElements = setOf("Chrono/a"), includedElements = setOf("Chrono/a"))
        assertEquals(setOf("Chrono/a", "Chrono/b"), keys(both).toSet())
    }

    @Test
    fun an_inclusion_overrides_the_other_filters() {
        val filtered = Filters(chronoState = SearchDomain.TimerState.Running)
        assertEquals(emptyList(), keys(filtered))
        assertEquals(listOf("Chrono/a"), keys(filtered.copy(includedElements = setOf("Chrono/a"))))
    }

    @Test
    fun an_inclusion_overrides_the_kinds_and_the_search_text() {
        val included = Filters(includedElements = setOf("Chrono/a"))
        assertEquals(listOf("Chrono/a"), keys(included, kinds = emptySet()))
        assertEquals(listOf("Chrono/b", "Chrono/a"), keys(included, query = "Second"), "what the search finds comes first")
    }

    @Test
    fun an_included_element_is_listed_once_and_one_gone_not_at_all() {
        assertEquals(1, keys(Filters(includedElements = setOf("Chrono/a"))).count { it == "Chrono/a" })
        assertEquals(setOf("Chrono/a", "Chrono/b"), keys(Filters(includedElements = setOf("Chrono/gone"))).toSet())
    }

    @Test
    fun both_stand_in_the_every_element_group_and_count_as_filters_on() {
        val general = SearchDomain.configurationGroups("", Kind.entries.toSet(), emptyMap()).first { it.kinds.isEmpty() }
        assertEquals("all", general.id)
        assertTrue(Setting.ExcludedElements in general.settings)
        assertTrue(Setting.IncludedElements in general.settings)
        assertFalse(Filters().isOn(Setting.ExcludedElements))
        assertTrue(Filters(excludedElements = setOf("Chrono/a")).isOn(Setting.ExcludedElements))
        assertEquals(1, Filters(includedElements = setOf("Chrono/a")).activeCount)
    }

    @Test
    fun they_are_kept_with_the_window_and_an_older_one_has_none() {
        val config = SearchDomain.Config(filters = Filters(excludedElements = setOf("Chrono/a"), includedElements = setOf("Chrono/b")))
        assertEquals(config.filters, SearchDomain.Config.decode(config.encode())!!.filters)
        val older = SearchDomain.Config.decode("{}")!!.filters
        assertEquals(emptySet(), older.excludedElements)
        assertEquals(emptySet(), older.includedElements)
    }

    @Test
    fun the_window_from_a_blank_field_starts_with_its_parent_excluded() {
        val config = ElementTree.childSearchConfig("Chrono/a", "")
        assertEquals(setOf("Chrono/a"), config.filters.excludedElements)
        assertEquals(listOf("Chrono/b"), SearchDomain.results(state, config.kinds, "", { emptyMap() }, config.filters).map(SearchDomain::keyOf).filterNot { it.startsWith("Creation/") })
    }

    @Test
    fun a_window_of_a_single_element_offers_its_creation_row() {
        val alone = state.copy(chronos = state.chronos.take(1))
        val config = ElementTree.childSearchConfig("Chrono/a", "")
        val rows = SearchDomain.withCreationWhenEmpty(alone, SearchDomain.results(alone, config.kinds, "", { emptyMap() }, config.filters), config.kinds)
        assertEquals(listOf("Creation/Chrono"), rows.map(SearchDomain::keyOf))
    }

    @Test
    fun an_included_window_makes_the_window_read_the_windows() {
        assertTrue(SearchDomain.Config(kinds = setOf(Kind.Chrono), filters = Filters(includedElements = setOf("Window/Search"))).readsWindows)
        assertFalse(SearchDomain.Config(kinds = setOf(Kind.Chrono)).readsWindows)
    }

    @Test
    fun what_can_be_picked_is_named_with_its_kind() {
        val named = ElementTree.pickable(state.copy(alarms = listOf(AlarmEntry(id = "x", label = "Wake"))))
        assertTrue("Chrono/a" to "chrono: First" in named, "$named")
    }
}
