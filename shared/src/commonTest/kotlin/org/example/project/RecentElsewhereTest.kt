package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.ConfigurationCategory
import org.example.project.scheduler.domain.SearchDomain.ConfigurationLeaf
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.ui.PopupMemory

/**
 * User rule 2026-11-16: *"If I change the state of down arrow > search > where to search > id, I will find id in down
 * arrow > recent > here. This search configuration will show in its row the path search > where to search. If I did
 * this recently from another down arrow, it will appear in arrow > recent > elsewhere"* — "elsewhere" holding *"changed
 * state recently"*, *"looked into the children"*, then both.
 */
class RecentElsewhereTest {
    private fun recentOf(tree: List<SearchDomain.ConfigurationNode>) = tree.first() as ConfigurationCategory

    @Test
    fun a_change_here_is_in_recent_here_with_its_path() {
        val config = SearchDomain.withChanged(
            SearchDomain.Config(), SearchDomain.Config(filters = SearchDomain.Filters().withSearches(Setting.SearchInId, true)), listOf(Setting.SearchInId), 100,
        )
        val here = recentOf(SearchDomain.configurationTree(config)).children.first() as ConfigurationCategory
        assertEquals("Here", here.label)
        val leaf = here.children.single() as ConfigurationLeaf
        assertEquals(Setting.SearchInId, leaf.setting)
        assertEquals(listOf("Search", "Where to search"), leaf.path)
        assertEquals("Id", leaf.name)
    }

    @Test
    fun what_another_arrow_changed_and_looked_into_is_in_recent_elsewhere() {
        val elsewhere = SearchDomain.Elsewhere(
            changed = SearchDomain.Elsewhere.changedIn(
                listOf(
                    SearchDomain.Config(settingsChangedAt = mapOf(Setting.SearchInId to 300L, Setting.Regex to 100L)),
                    SearchDomain.Config(settingsChangedAt = mapOf(Setting.Regex to 200L)),
                ),
            ),
            looked = mapOf("bar/where/children" to 250L, "sort" to 50L, "no-such-node" to 400L),
        )
        assertEquals(mapOf(Setting.SearchInId to 300L, Setting.Regex to 200L), elsewhere.changed, "the last time any did")
        val recent = recentOf(SearchDomain.configurationTree(SearchDomain.Config(), elsewhere = elsewhere))
        // No change here: no "Here"; "Elsewhere" first.
        val node = recent.children.first() as ConfigurationCategory
        assertEquals("Elsewhere", node.label)
        val changed = node.children[0] as ConfigurationCategory
        assertEquals("Changed state recently", changed.label)
        assertEquals(listOf(Setting.SearchInId, Setting.Regex), changed.children.map { (it as ConfigurationLeaf).setting })
        val looked = node.children[1] as ConfigurationCategory
        assertEquals("Looked into the children", looked.label)
        // A node the tree no longer has is not listed; one that is opens as itself, named by its path.
        assertEquals(listOf("Search › Where to search › Children", "Sorting"), looked.children.map { it.label })
        assertEquals("Search the children", ((looked.children.first() as ConfigurationCategory).children.first() as ConfigurationLeaf).name)
        // Then both, the last first.
        assertEquals(
            listOf("Search › Where to search › Id", "Search › Where to search › Children", "Search › Matching › Use regular expression", "Sorting"),
            node.children.drop(2).map { it.label },
        )
        // "Recent"'s own list: the last changed anywhere first.
        assertEquals(listOf(Setting.SearchInId, Setting.Regex), recent.children.filterIsInstance<ConfigurationLeaf>().take(2).map { it.setting })
        // Nothing done elsewhere: no "Elsewhere".
        assertTrue(recentOf(SearchDomain.configurationTree(SearchDomain.Config())).children.none { it is ConfigurationCategory })
    }

    @Test
    fun only_a_category_of_the_structure_is_worth_remembering() {
        for (id in listOf("bar", "bar/where/children", "kind/Task", "all:bar/matching")) assertTrue(SearchDomain.isLookedIntoWorthy(id), id)
        for (id in listOf("recent", "recent/here", "recent/elsewhere/looked", "on", "context", "default", "bar/default")) assertTrue(!SearchDomain.isLookedIntoWorthy(id), id)
    }

    /** Local-only, on the pop-ups' placement row: each ▾ window's own, the others' read together, bounded. */
    @Test
    fun the_categories_looked_into_are_kept_by_arrow_bounded_and_survive_a_restart() {
        var stored: String? = null
        val memory = PopupMemory({ stored }, { stored = it })
        memory.lookedInto("Search▾", "sort", 10)
        memory.lookedInto("Search#2▾", "sort", 20)
        memory.lookedInto("Search#2▾", "bar", 5)
        assertEquals(mapOf("sort" to 20L, "bar" to 5L), memory.lookedIntoElsewhere("Search▾"))
        assertEquals(mapOf("sort" to 10L), memory.lookedIntoElsewhere("Search#2▾"))
        // Read back after a restart, and kept though the windows are closed: it is history, not a pop-up open.
        val again = PopupMemory({ PopupMemory.kept(stored) { false } }, {})
        assertEquals(mapOf("sort" to 20L, "bar" to 5L), again.lookedIntoElsewhere("Search▾"))
        // Bounded.
        repeat(PopupMemory.LOOKED_INTO_KEPT + 5) { memory.lookedInto("Search▾", "node$it", 100L + it) }
        assertEquals(PopupMemory.LOOKED_INTO_KEPT, memory.lookedIntoElsewhere("Search#2▾").size)
        assertNull(memory.lookedIntoElsewhere("Search#2▾")["node0"], "the oldest dropped")
    }

    /** Persisted-DB compatibility: what was stored before "looked into" reads as none, its pop-ups kept. */
    @Test
    fun what_was_stored_before_reads_as_nothing_looked_into() {
        val older = """{"fields":["Search/field:a"],"trees":{"Search▾":["sort"]}}"""
        val memory = PopupMemory({ older }, {})
        assertEquals(emptyMap(), memory.lookedIntoElsewhere(null))
        assertTrue(memory.fieldOpen("Search/field:a"))
        assertEquals(listOf("sort"), memory.tree("Search▾"))
    }
}
