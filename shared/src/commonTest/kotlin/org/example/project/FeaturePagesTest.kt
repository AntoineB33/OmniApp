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
            ),
            menu.drop(2).map { it.control },
        )
        // Every page's own Reset goes back to its own list.
        val lists = CustomMenuButtons.PAGES.associateWith { emptyList<org.example.project.ui.CustomMenuButton>() }
        assertEquals(menu, CustomMenuButtons.withPageReset(lists, SearchDomain.SETTINGS_PAGE).getValue(SearchDomain.SETTINGS_PAGE))
    }
}
