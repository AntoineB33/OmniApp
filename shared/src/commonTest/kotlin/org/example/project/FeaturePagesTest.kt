package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.MenuControl

/**
 * User rule 2026-11-01: a feature page of the app's settings, and in the Search configurations window a drop-down of the
 * feature pages — *"selecting only Claude Code logs will show in the element type selector only the three elements from
 * this feature page"*.
 */
class FeaturePagesTest {
    private val everyPage = SearchDomain.FEATURE_PAGES.mapTo(HashSet()) { it.id }

    @Test
    fun the_pages_of_the_selector_are_the_pages_of_the_app() {
        assertEquals(OmniPage.entries.map { it.searchPage }, SearchDomain.FEATURE_PAGES.map { it.id })
        assertEquals(listOf("Task Scheduler", "Claude Code logs", "Settings"), OmniPage.entries.map { it.label })
        assertEquals(CustomMenuButtons.PAGES, OmniPage.entries.map { it.searchPage })
    }

    /**
     * User rule 2026-11-04: each page's Search button opens with "Only this page's types" on — the logs page's type
     * selector holds its prompts, answers and reasoning chains alone, and so on for every page.
     */
    @Test
    fun each_pages_search_button_offers_that_pages_types_alone() {
        for (page in SearchDomain.FEATURE_PAGES) {
            val config = SearchDomain.pageDefaultConfig(page.id)
            assertTrue(config.pageTypesOnly, page.label)
            assertEquals(Kind.entries.filter { it in page.kinds }, config.offeredKinds, page.label)
            assertTrue(config.searchedKinds.all { it in page.kinds }, page.label)
            // The whole configuration cancelled (Reset twice) keeps it on, every type of the page checked.
            val cleared = SearchDomain.resetConfig(config.copy(query = "x"), null)
            assertTrue(cleared.pageTypesOnly && cleared.searchedKinds == page.kinds.toSet(), page.label)
        }
        val logs = SearchDomain.pageDefaultConfig(SearchDomain.CLAUDE_LOGS_PAGE)
        assertEquals(listOf(Kind.ClaudePrompt, Kind.ClaudeAnswer, Kind.ClaudeReasoning), logs.offeredKinds)
        // Off: every type is offered, and what the window held of the others is searched again.
        val scheduler = SearchDomain.pageDefaultConfig("").copy(kinds = setOf(Kind.Task, Kind.ClaudePrompt))
        assertEquals(setOf(Kind.Task), scheduler.searchedKinds)
        assertEquals(setOf(Kind.Task, Kind.ClaudePrompt), scheduler.copy(pageTypesOnly = false).searchedKinds)
        assertEquals(Kind.entries, scheduler.copy(pageTypesOnly = false).offeredKinds)
        // Kept through a round trip, and a configuration stored before the switch searches as it did.
        assertEquals(scheduler, SearchDomain.Config.decode(scheduler.encode()))
        val before = SearchDomain.Config(kinds = setOf(Kind.Task, Kind.ClaudePrompt)).encode()
        assertFalse("pageTypesOnly\":true" in before, "test premise: a payload with the switch off")
        assertEquals(setOf(Kind.Task, Kind.ClaudePrompt), SearchDomain.Config.decode(before.replace(",\"pageTypesOnly\":false", ""))?.searchedKinds)
    }

    /** User rule 2026-11-04: "only this page's types" offers the settings' type on the settings page alone. */
    @Test
    fun the_settings_types_are_the_settings_pages_alone() {
        for (kind in SearchDomain.SETTINGS_KINDS) {
            assertEquals(listOf(SearchDomain.SETTINGS_PAGE), SearchDomain.FEATURE_PAGES.filter { kind in it.kinds }.map { it.id }, kind.label)
        }
        assertFalse(Kind.AppSetting in SearchDomain.pageDefaultConfig("").offeredKinds)
        assertFalse(Kind.AppSetting in SearchDomain.pageDefaultConfig(SearchDomain.CLAUDE_LOGS_PAGE).offeredKinds)
        assertTrue(Kind.AppSetting in SearchDomain.pageDefaultConfig(SearchDomain.SETTINGS_PAGE).offeredKinds)
        // Every kind is some page's, and one page's only.
        assertEquals(Kind.entries, Kind.entries.filter { k -> SearchDomain.FEATURE_PAGES.count { k in it.kinds } == 1 })
    }

    /**
     * User rule 2026-11-04: a window's button brings back the window that has the same "configurations" — for a Search
     * window its search configurations. Anomaly: the Task Scheduler page's Search button opened a new window at every
     * press, its window having taken its default sorting (tasks newest first) on opening.
     */
    @Test
    fun a_search_window_has_its_buttons_configurations_however_it_was_opened_and_used() {
        for (page in SearchDomain.FEATURE_PAGES) {
            val button = SearchDomain.pageDefaultConfig(page.id)
            val opened = SearchDomain.withDefaultSort(button)
            assertTrue(SearchDomain.sameSearchConfigurations(button.encode(), opened.encode()), page.label)
        }
        val button = SearchDomain.pageDefaultConfig("")
        assertTrue(SearchDomain.withDefaultSort(button) != button, "test premise: the window's sorting is not the button's")
        // What the window does with its elements and actions is not its search.
        val used = SearchDomain.withDefaultSort(button).copy(added = listOf("Task/task/1"), actionQuery = "min", openedFrom = "Search#2")
        assertTrue(SearchDomain.sameSearchConfigurations(button.encode(), used.encode()))
        // A search of its own is another window.
        assertFalse(SearchDomain.sameSearchConfigurations(button.encode(), button.copy(query = "work").encode()))
        assertFalse(SearchDomain.sameSearchConfigurations(button.encode(), button.copy(kinds = setOf(Kind.Alarm)).encode()))
        assertFalse(SearchDomain.sameSearchConfigurations(button.encode(), button.copy(pageTypesOnly = false).encode()))
    }

    @Test
    fun only_the_logs_page_offers_only_its_three_kinds() {
        val own = SearchDomain.ConfigurationSearch(pages = setOf(SearchDomain.CLAUDE_LOGS_PAGE))
        assertEquals(listOf(Kind.ClaudePrompt, Kind.ClaudeAnswer, Kind.ClaudeReasoning), own.offeredKinds)
        // …and the sections of the others go with them.
        val listed = SearchDomain.configurationGroups("", own.shownKinds, emptyMap()).flatMap { it.kinds }.toSet()
        assertTrue(listed.isNotEmpty() && listed.all { it in SearchDomain.CLAUDE_KINDS }, "listed: $listed")
    }

    @Test
    fun the_type_selector_names_what_a_narrowed_list_holds() {
        val logs = SearchDomain.ConfigurationSearch(pages = setOf(SearchDomain.CLAUDE_LOGS_PAGE))
        // Anomaly 2026-11-01: picking the page left its face on "every kind", as it read before.
        assertEquals("prompt, answer, reasoning chain", org.example.project.ui.kindsLabel(logs.kinds, logs.offeredKinds))
        assertEquals("answer", org.example.project.ui.kindsLabel(setOf(Kind.ClaudeAnswer), logs.offeredKinds))
        assertEquals("no kind", org.example.project.ui.kindsLabel(setOf(Kind.Task), logs.offeredKinds))
        val all = SearchDomain.ConfigurationSearch()
        assertEquals("every kind", org.example.project.ui.kindsLabel(all.kinds, all.offeredKinds))
    }

    @Test
    fun a_page_unchecked_keeps_what_its_kinds_held() {
        val own = SearchDomain.ConfigurationSearch(kinds = setOf(Kind.Task, Kind.ClaudePrompt), pages = setOf(SearchDomain.CLAUDE_LOGS_PAGE))
        assertEquals(setOf(Kind.ClaudePrompt), own.shownKinds)
        assertEquals(setOf(Kind.Task, Kind.ClaudePrompt), own.copy(pages = everyPage).shownKinds)
    }

    @Test
    fun no_page_checked_offers_nothing_and_every_page_every_kind() {
        assertEquals(emptyList(), SearchDomain.ConfigurationSearch(pages = emptySet()).offeredKinds)
        assertEquals(Kind.entries, SearchDomain.ConfigurationSearch().offeredKinds)
    }

    @Test
    fun the_pages_are_kept_and_a_configuration_stored_before_them_has_every_page() {
        val own = SearchDomain.ConfigurationSearch(pages = setOf(SearchDomain.SETTINGS_PAGE))
        assertEquals(own, SearchDomain.ConfigurationSearch.decode(own.encode()))
        val none = SearchDomain.ConfigurationSearch(pages = emptySet())
        assertEquals(none, SearchDomain.ConfigurationSearch.decode(none.encode()))
        // The older shape, and every page checked writes the same: a page added later is offered like the others.
        val older = SearchDomain.ConfigurationSearch().encode()
        assertFalse("pages" in older)
        assertEquals(everyPage, SearchDomain.ConfigurationSearch.decode(older)!!.pages)
    }

    @Test
    fun the_settings_page_opens_on_the_settings_with_its_own_menu() {
        val config = SearchDomain.pageDefaultConfig(SearchDomain.SETTINGS_PAGE)
        assertEquals(setOf(Kind.AppSetting, Kind.WorkplaceSetup), config.kinds)
        assertEquals(SearchDomain.SETTINGS_PAGE, config.page)
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        val menu = CustomMenuButtons.defaultsOf(SearchDomain.SETTINGS_PAGE)
        assertEquals(CustomMenuButtons.SETTINGS_DEFAULTS, menu)
        assertEquals("LateralMenu/AppSettings", CustomMenuButtons.placementIdOf(SearchDomain.SETTINGS_PAGE))
        assertEquals(listOf(CustomMenuButtons.GLOBAL_SEARCH, CustomMenuButtons.GLOBAL_MUTE), menu.take(2))
        assertEquals(
            // The free tier's usage and the memory limits last (user rule 2026-11-03), the online button above them
            // (user rule 2026-11-04).
            listOf(
                MenuControl.Voice.name, MenuControl.Notifications.name, MenuControl.SoundVolume.name,
                MenuControl.Online.name, MenuControl.SupabaseUsage.name, MenuControl.MemoryLimits.name,
                // The server this device syncs with, last (user rule 2026-11-04, docs/SERVER_SETUP.md).
                MenuControl.Server.name,
            ),
            menu.drop(2).map { it.control },
        )
        // Every page's own Reset goes back to its own list.
        val lists = CustomMenuButtons.PAGES.associateWith { emptyList<org.example.project.ui.CustomMenuButton>() }
        assertEquals(menu, CustomMenuButtons.withPageReset(lists, SearchDomain.SETTINGS_PAGE).getValue(SearchDomain.SETTINGS_PAGE))
    }
}
