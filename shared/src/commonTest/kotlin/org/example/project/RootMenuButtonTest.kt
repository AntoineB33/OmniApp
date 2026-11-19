package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.ButtonExpandedRows
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.SearchSplits

/**
 * User rule 2026-11-19: *"By default, there is a button in the left-side menu of the 'task scheduler' feature page to show the
 * search window with only the global root task cell. Add a search configuration element that when on updates the button in
 * the left-side menu automatically only for which rows are expanded."*
 */
class RootMenuButtonTest {
    @Test
    fun the_root_button_lists_the_global_root_alone() {
        val config = SearchDomain.rootSearchConfig()
        val state = SchedulerState.empty()
        val rows = SearchDomain.results(state, config.kinds, "", filters = config.filters)
        assertEquals(listOf(SearchDomain.taskKey(WellKnownIds.ROOT_TASK)), rows.map(SearchDomain::keyOf))
        assertTrue(config.buttonFollowsExpansion)
        assertTrue(SearchDomain.Setting.ButtonFollowsExpansion in SearchDomain.activeSettings(config))
        assertEquals(config, SearchDomain.Config.decode(config.encode()), "the setting is kept with the configuration")
    }

    @Test
    fun the_scheduler_menu_holds_it_under_the_search_button_and_an_older_menu_is_given_it_once() {
        val defaults = CustomMenuButtons.DEFAULTS.map { it.id }
        assertEquals(defaults.indexOf("d-calendar") - 1, defaults.indexOf(CustomMenuButtons.ROOT_SEARCH.id), "with the windows' buttons, above the calendar's")
        assertTrue(CustomMenuButtons.CLAUDE_LOGS_DEFAULTS.none { it.id == CustomMenuButtons.ROOT_SEARCH.id }, "the scheduler's page alone")
        // A menu written before (no rootSeeded): given it once, above the calendar's button.
        val older = CustomMenuButtons.encode(CustomMenuButtons.DEFAULTS - CustomMenuButtons.ROOT_SEARCH).replace(",\"rootSeeded\":true", "")
        val given = CustomMenuButtons.decode(older)
        assertEquals(CustomMenuButtons.DEFAULTS.map { it.id }, given.map { it.id })
        // Removed by the user since: it stays removed.
        val removed = CustomMenuButtons.removed(given, CustomMenuButtons.ROOT_SEARCH.id)
        assertEquals(removed, CustomMenuButtons.decode(CustomMenuButtons.encode(removed)))
    }

    @Test
    fun the_button_keeps_the_open_rows_and_nothing_else() {
        val splits = SearchSplits(left = 0.3f, expandedResults = setOf("task/root", "task/a"), expandedElements = setOf("r:Chrono/c"))
        val rows = ButtonExpandedRows.of(splits)
        val lists = mapOf("" to CustomMenuButtons.DEFAULTS)
        val next = CustomMenuButtons.withExpandedRows(lists, setOf(CustomMenuButtons.ROOT_SEARCH.id), rows)
        val root = next.getValue("").first { it.id == CustomMenuButtons.ROOT_SEARCH.id }
        assertEquals(rows, root.expandedRows)
        assertEquals(CustomMenuButtons.ROOT_SEARCH.config, root.config, "its configuration is not touched")
        assertEquals(next.getValue("").filter { it.id != root.id }, CustomMenuButtons.DEFAULTS.filter { it.id != root.id })
        assertEquals(next.getValue(""), CustomMenuButtons.decode(CustomMenuButtons.encode(next.getValue(""))), "kept by the store")
        // Put back on a window it opens: these rows open, its own lines left as they are.
        val opened = rows.applyTo(SearchSplits(left = 0.7f, expandedResults = setOf("task/x")))
        assertEquals(setOf("task/root", "task/a"), opened.expandedResults)
        assertEquals(0.7f, opened.left)
    }

    /**
     * User, 2026-11-19: *"Should I find the search configuration element that automatically updates the button only for row
     * expansions status in 'Currently on' from the down arrow of the window from the default 'Root' button?"* Yes.
     */
    @Test
    fun the_root_windows_down_arrow_shows_the_setting_currently_on() {
        val tree = SearchDomain.configurationTree(SearchDomain.rootSearchConfig())
        val on = tree.filterIsInstance<SearchDomain.ConfigurationCategory>().single { it.label == "Currently on" }
        assertTrue(on.children.any { (it as? SearchDomain.ConfigurationLeaf)?.setting == SearchDomain.Setting.ButtonFollowsExpansion })
        // Anomaly 2026-11-19: *"'Menu keeps open rows' is in 'Currently on' but not in 'Default for this'"*. Opened so, it is
        // the window's default too — switched off since, "Default for this" still says what it opened with.
        val switchedOff = SearchDomain.rootSearchConfig().copy(buttonFollowsExpansion = false)
        val defaults = SearchDomain.configurationTree(switchedOff, opened = SearchDomain.rootSearchConfig())
            .filterIsInstance<SearchDomain.ConfigurationCategory>().single { it.id == "default" }
        assertTrue(defaults.children.any { (it as? SearchDomain.ConfigurationLeaf)?.setting == SearchDomain.Setting.ButtonFollowsExpansion })
        assertTrue(SearchDomain.activeSettings(SearchDomain.rootSearchConfig()).all { it in SearchDomain.contextSettings(SearchDomain.rootSearchConfig()) },
            "what a window opened with is on, every one of it is its default")
    }

    /** User rule 2026-11-19: *"rename 'root' by something like task trees"* — the global root, and its button. */
    @Test
    fun the_global_root_is_called_task_trees_and_an_account_that_said_root_reads_it() {
        assertEquals("Task trees", SchedulerState.empty().tasks.getValue(WellKnownIds.ROOT_TASK).title)
        assertEquals("Task trees", CustomMenuButtons.ROOT_SEARCH.title)
        val stored = org.example.project.scheduler.persistence.SchedulerStateCodec.encode(SchedulerState.empty())
            .replace("\"Task trees\"", "\"root\"")
        assertTrue("\"root\"" in stored)
        val decoded = org.example.project.scheduler.persistence.SchedulerStateCodec.decode(stored)!!
        assertEquals("Task trees", decoded.tasks.getValue(WellKnownIds.ROOT_TASK).title, "healed on load")
        // A menu given the button as "Root" earlier the same day reads the new name; one the user renamed keeps theirs.
        val given = CustomMenuButtons.encode(CustomMenuButtons.DEFAULTS.map { if (it.id == "d-root") it.copy(title = "Root") else it })
        assertEquals("Task trees", CustomMenuButtons.decode(given).single { it.id == "d-root" }.title)
        val renamed = CustomMenuButtons.encode(CustomMenuButtons.DEFAULTS.map { if (it.id == "d-root") it.copy(title = "Mine") else it })
        assertEquals("Mine", CustomMenuButtons.decode(renamed).single { it.id == "d-root" }.title)
    }

    /**
     * Anomaly 2026-11-19: *"when I right-click on the feature page selector button, it gives me the options I should have if I
     * right-click on the background of the left-side menu"*. Its right-click is an item's, from the one list of them: what
     * cannot apply to it greyed, never absent.
     */
    @Test
    fun the_page_selector_has_an_items_right_click_entries() {
        val selector = org.example.project.ui.menuItemActions(null, onReset = {})
        val item = org.example.project.ui.menuItemActions(CustomMenuButtons.ROOT_SEARCH, onReset = {})
        assertEquals(listOf("Rename", "✓ all pages", "Remove", "Reset"), selector.map { it.label })
        assertEquals(listOf("Reset"), selector.filter { it.enabled }.map { it.label })
        assertTrue(selector.map { it.label }.all { label -> item.any { it.label.removePrefix("✓ ") == label.removePrefix("✓ ") } })
    }

    /**
     * Anomaly 2026-11-19: *"I set off 'Menu keeps open rows', then right-clicked the 'task trees' button, but I don't see any
     * 'Update' option"*: the window a button opened is kept across a restart, not found again by its tab's name alone.
     */
    @Test
    fun the_window_a_button_opened_is_kept_by_the_store() {
        val links = mapOf(CustomMenuButtons.ROOT_SEARCH.id to "Search#3", "b1" to "Search")
        assertEquals(links, CustomMenuButtons.decodeButtonWindows(CustomMenuButtons.encodeButtonWindows(links)))
        assertEquals(emptyMap(), CustomMenuButtons.decodeButtonWindows(null))
    }

    /**
     * Anomaly 2026-11-19: *"now i have the update option, even though i set the switch button back to on"*. After a restart
     * the layout the window had when its button opened it was gone, and a button keeping no layout of its own read its window
     * as changed. Kept by the store, the window as it was opened is no change.
     */
    @Test
    fun a_window_that_came_back_as_its_button_opened_it_offers_no_update() {
        val opened = org.example.project.ui.WindowLayout(10f, 20f, 600f, 400f, listOf(0.5f, 0.5f), listOf(false, false, false))
        val kept = CustomMenuButtons.decodeOpenedLayouts(CustomMenuButtons.encodeOpenedLayouts(mapOf("d-root" to opened)))
        assertEquals(mapOf("d-root" to opened), kept)
        val button = CustomMenuButtons.ROOT_SEARCH
        val config = button.config
        assertTrue(!CustomMenuButtons.needsUpdate(button, config, config, opened, kept["d-root"]), "as opened: nothing to update")
        assertTrue(CustomMenuButtons.needsUpdate(button, config, config, opened, null), "the reference lost: an Update is offered")
        val switchedOff = SearchDomain.rootSearchConfig().copy(buttonFollowsExpansion = false).encode()
        assertTrue(CustomMenuButtons.needsUpdate(button, config, switchedOff, opened, kept["d-root"]), "switched off: it is offered")
    }

    /**
     * User, 2026-11-19: *"If it was the right one the name is confusing and deserves a description in a bubble info. Add
     * description bubble info for all the search configuration elements as well."* Every configuration has one that says
     * more than its name: none is its label again, nor a filter's "Keeps the … by 'its name'".
     */
    @Test
    fun every_search_configuration_has_a_description_that_says_more_than_its_name() {
        assertEquals("Button saves open rows", SearchDomain.Setting.ButtonFollowsExpansion.label)
        assertTrue("Off" in SearchDomain.settingDescription(SearchDomain.Setting.ButtonFollowsExpansion), "it says what off does")
        for (setting in SearchDomain.Setting.entries) {
            val text = SearchDomain.settingDescription(setting)
            assertTrue(text.isNotBlank() && text != setting.label + "." && !text.startsWith("Keeps the "), "$setting: $text")
        }
    }
}
