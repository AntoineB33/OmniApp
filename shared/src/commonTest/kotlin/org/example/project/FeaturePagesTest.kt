package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.ui.CustomMenuButton
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.MenuControl
import org.example.project.ui.WindowLayout

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
    /**
     * Anomaly 2026-11-05 (account 3): "Gemini quota" did nothing on the Claude Code logs page — it and "Claude quota" are
     * global ☆ buttons of one search, one quota added each, and with the Claude quota's window open the Gemini button
     * brought that one back. User rule 2026-11-05: a button's "match the whole state" — on, it brings back only a window
     * whose whole state is the one it keeps (its added elements by id, where it stands, its sections, its scrolls); on by
     * default for a button the user made, off for a default one. On or off, "Update" is offered when any of it changed.
     */
    @Test
    fun a_whole_state_button_brings_back_only_the_window_that_is_all_it_keeps() {
        val layout = WindowLayout(0f, 184f, 1353f, 1120f, splits = listOf(0.2f, 0.7f), hidden = listOf(true, false, true))
        // The two buttons as account 3 keeps them: no page in their configuration, nothing said of the switch.
        val claude = CustomMenuButton("g2", "Search#3", "Claude quota", config = """{"kinds":["Quota"],"added":["Quota/quota-0"]}""",
            layout = CustomMenuButtons.encodeLayout(layout), global = true)
        val gemini = claude.copy(id = "g1", title = "Gemini quota", config = """{"kinds":["Quota"],"added":["Quota/quota-1"]}""")
        assertTrue(CustomMenuButtons.wholeStateOn(claude) && CustomMenuButtons.wholeStateOn(gemini), "a button the user made: on")
        assertFalse(CustomMenuButtons.wholeStateOn(CustomMenuButtons.GLOBAL_SEARCH), "a default one: off")
        assertFalse(CustomMenuButtons.wholeStateOn(CustomMenuButtons.LIFE_PLAN))
        assertFalse(CustomMenuButtons.wholeStateOn(CustomMenuButtons.withWholeState(listOf(claude), "g2", false).single()))
        // A default made "all pages" takes a g… id, and stays off.
        val (lists, newId) = CustomMenuButtons.withGlobal(mapOf("" to listOf(CustomMenuButtons.LIFE_PLAN)), "", CustomMenuButtons.LIFE_PLAN.id, true)
        assertFalse(CustomMenuButtons.wholeStateOn(lists.getValue("").single { it.id == newId }))

        val logs = SearchDomain.CLAUDE_LOGS_PAGE
        val claudeAsked = CustomMenuButtons.globalSearchConfig(claude, logs)
        val geminiAsked = CustomMenuButtons.globalSearchConfig(gemini, logs)
        // The Claude quota's window, as it was opened on the logs page and stored, and put where the button keeps it.
        val claudeWindow = SearchDomain.withDefaultSort(SearchDomain.Config.decode(claudeAsked)!!).encode()
        fun same(button: CustomMenuButton, asked: String, window: String, now: WindowLayout, opened: WindowLayout? = null) =
            CustomMenuButtons.isSameWindow(button, SearchDomain.sameSearchWindowState(window, asked), now, opened)
        assertTrue(same(claude, claudeAsked, claudeWindow, layout))
        assertFalse(same(gemini, geminiAsked, claudeWindow, layout), "another quota added: another window")
        // The added elements are told apart by their ids, not their order.
        val both = SearchDomain.Config(kinds = setOf(Kind.Quota), added = listOf("Quota/quota-0", "Quota/quota-1"))
        assertTrue(SearchDomain.sameSearchWindowState(both.copy(added = both.added.reversed()).encode(), both.encode()))
        // Scrolled, a line moved, moved: no longer the button's window...
        assertFalse(same(claude, claudeAsked, claudeWindow, layout.copy(scrolls = listOf(4, 0, 0, 0))))
        assertFalse(same(claude, claudeAsked, claudeWindow, layout.copy(splits = listOf(0.5f, 0.7f))))
        assertFalse(same(claude, claudeAsked, claudeWindow, layout.copy(x = 300f)))
        // ...but put back as far as the app let it (cut to the screen) it still is, as it was when the button opened it.
        val cut = layout.copy(height = 900f)
        assertTrue(same(claude, claudeAsked, claudeWindow, cut, opened = cut))
        // A layout kept before scrolls were: its lists at their top.
        assertTrue(layout.sameAs(layout.copy(scrolls = listOf(0, 0, 0, 0))))

        // Off, the bring-back is by the search configurations alone (the Search button's rule, 2026-11-04)...
        assertTrue(SearchDomain.sameSearchConfigurations(claudeWindow, geminiAsked))
        // ...and on or off, "Update" is offered when the added elements or a scroll changed.
        for (button in listOf(claude, claude.copy(wholeState = false))) {
            val saved = SearchDomain.Config.decode(claudeAsked)!!
            fun offered(window: String, now: WindowLayout) =
                CustomMenuButtons.needsUpdate(
                    button, savedConfig = saved.encode(),
                    config = if (SearchDomain.sameSearchWindowState(window, claudeAsked)) saved.encode() else window,
                    layout = now, opened = null,
                )
            assertFalse(offered(claudeWindow, layout))
            assertTrue(offered(SearchDomain.Config.decode(claudeWindow)!!.copy(added = listOf("Quota/quota-1")).encode(), layout))
            assertTrue(offered(claudeWindow, layout.copy(scrolls = listOf(0, 0, 2, 10))))
        }
    }

    /** What an older build kept still reads: a button without the switch, sections and a layout without scrolls. */
    @Test
    fun buttons_sections_and_layouts_kept_before_the_whole_state_still_load() {
        val button = CustomMenuButtons.decode("""{"buttons":[{"id":"b3","window":"Search","title":"timers Search","config":"{}"}],"seeded":true}""")
            .single { it.id == "b3" }
        assertEquals(null, button.wholeState)
        assertTrue(CustomMenuButtons.wholeStateOn(button))
        val sections = CustomMenuButtons.decodeWindowSections("""{"sections":{"Search#2":{"splits":[0.5,0.5],"hidden":[false,false,false]}}}""")
        assertEquals(emptyList(), sections.getValue("Search#2").scrolls)
        val old = CustomMenuButtons.decodeLayout("""{"x":1.0,"y":2.0,"width":3.0,"height":4.0,"splits":[0.5,0.5]}""")!!
        assertEquals(emptyList(), old.scrolls)
        assertEquals(old, CustomMenuButtons.decodeLayout(CustomMenuButtons.encodeLayout(old)))
    }

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
