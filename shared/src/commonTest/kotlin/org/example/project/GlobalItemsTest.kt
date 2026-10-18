package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.ui.CustomMenuButton
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.WindowFrameState

/**
 * User rule 2026-10-18: a lateral-menu item (and a window) set GLOBAL stands on every feature page — the global ones in
 * the same order on all of them, each page's own between them where that page has them — and a global Search button is
 * in every page's menu by default. The two windows of the last unfocused notifications open at the bottom of the
 * focus rank.
 */
class GlobalItemsTest {
    private val claude = SearchDomain.CLAUDE_LOGS_PAGE

    private fun item(id: String, global: Boolean = false) = CustomMenuButton(id, "Search", id, global = global)

    private fun ids(lists: Map<String, List<CustomMenuButton>>, page: String) = lists.getValue(page).map { it.id }

    @Test
    fun an_item_made_global_stands_on_every_page_under_an_id_of_its_own() {
        // Each page mints its own ids: the two "b1" are two different items.
        val lists = mapOf("" to listOf(item("b1"), item("b2")), claude to listOf(item("b1")))
        val (next, id) = CustomMenuButtons.withGlobal(lists, "", "b2", on = true)
        assertTrue(id.startsWith("g"))
        assertEquals(listOf("b1", id), ids(next, ""))
        assertEquals(listOf("b1", id), ids(next, claude))
        assertTrue(next.getValue(claude).last().global)
        // Its page's own again: it leaves the other pages, and stays on its own.
        val (back, _) = CustomMenuButtons.withGlobal(next, "", id, on = false)
        assertEquals(listOf("b1", id), ids(back, ""))
        assertEquals(listOf("b1"), ids(back, claude))
    }

    @Test
    fun an_item_made_its_pages_own_again_from_another_page_goes_back_to_the_page_it_came_from() {
        // Anomaly 2026-10-18: made global on the logs page, un-set on the scheduler's — it stayed on the scheduler's only.
        val lists = mapOf("" to listOf(item("a")), claude to listOf(item("dir")))
        val (global, id) = CustomMenuButtons.withGlobal(lists, claude, "dir", on = true)
        assertEquals(claude, global.getValue("").first { it.id == id }.homePage)
        val (back, _) = CustomMenuButtons.withGlobal(global, "", id, on = false)
        assertEquals(listOf("a"), ids(back, ""))
        assertEquals(listOf(id), ids(back, claude))
        assertFalse(back.getValue(claude).single().global)
        // One global from the start (the Search button) stays on the page it is un-set on.
        val search = mapOf("" to listOf(CustomMenuButtons.GLOBAL_SEARCH), claude to listOf(CustomMenuButtons.GLOBAL_SEARCH))
        val (kept, _) = CustomMenuButtons.withGlobal(search, claude, "g-search", on = false)
        assertEquals(listOf("g-search"), ids(kept, claude))
        assertEquals(emptyList(), ids(kept, ""))
    }

    @Test
    fun the_global_items_keep_one_order_on_every_page_with_each_pages_own_between_them() {
        val lists =
            mapOf(
                "" to listOf(item("g1", true), item("a"), item("g2", true)),
                claude to listOf(item("x"), item("g1", true), item("y"), item("g2", true), item("z")),
            )
        // Moved on the scheduler's page: g2 before g1 — the logs page keeps x, y, z where they stand.
        val moved = CustomMenuButtons.withGlobalsPropagated(lists, "", listOf(item("g2", true), item("a"), item("g1", true)))
        assertEquals(listOf("x", "g2", "y", "g1", "z"), ids(moved, claude))
        // Renamed on one page, renamed on all; removed on one, removed from all.
        val renamed = CustomMenuButtons.withGlobalsPropagated(moved, claude, moved.getValue(claude).map { if (it.id == "g1") it.copy(title = "Find") else it })
        assertEquals("Find", renamed.getValue("").first { it.id == "g1" }.title)
        val removed = CustomMenuButtons.withGlobalsPropagated(renamed, "", renamed.getValue("").filterNot { it.id == "g2" })
        assertEquals(listOf("x", "y", "g1", "z"), ids(removed, claude))
    }

    @Test
    fun a_global_search_button_is_in_every_pages_menu_by_default_and_given_once_to_a_menu_made_before() {
        assertEquals(CustomMenuButtons.GLOBAL_SEARCH, CustomMenuButtons.DEFAULTS.first())
        assertEquals(CustomMenuButtons.GLOBAL_SEARCH, CustomMenuButtons.CLAUDE_LOGS_DEFAULTS.first())
        // A menu stored before: given it once, on top — and on the logs page in the place of its own Search button.
        val before = """{"buttons":[{"id":"d-calendar","window":"Calendar","title":"Calendar"}],"seeded":true}"""
        assertEquals(listOf("g-search", "d-calendar"), CustomMenuButtons.decode(before).map { it.id })
        val logsBefore =
            """{"buttons":[{"id":"d-claude-search","window":"Search","title":"Search"},{"id":"d-ClaudeLogsDirectory","window":"","title":"","control":"ClaudeLogsDirectory"}],"seeded":true}"""
        assertEquals(listOf("g-search", "d-ClaudeLogsDirectory"), CustomMenuButtons.decode(logsBefore, CustomMenuButtons.CLAUDE_LOGS_DEFAULTS).map { it.id })
        // …and once only: the user who removes it keeps it removed.
        val removed = CustomMenuButtons.encode(CustomMenuButtons.decode(before).filterNot { it.id == "g-search" })
        assertEquals(listOf("d-calendar"), CustomMenuButtons.decode(removed).map { it.id })
        // Its Search window is the page's own.
        assertEquals(claude, SearchDomain.pageDefaultConfig(claude).page)
        assertEquals("", SearchDomain.pageDefaultConfig("").page)
    }

    @Test
    fun every_pages_menu_is_one_history_text() {
        val lists = mapOf("" to listOf(item("g1", true), item("a")), claude to listOf(item("g1", true)))
        assertEquals(lists, CustomMenuButtons.decodePages(CustomMenuButtons.encodePages(lists)))
        val windows = listOf("Calendar#2", "Search#3")
        assertEquals(windows, CustomMenuButtons.decodeGlobalWindows(CustomMenuButtons.encodeGlobalWindows(windows)))
        assertEquals(emptyList(), CustomMenuButtons.decodeGlobalWindows("{not json"))
    }

    @Test
    fun the_notifications_pair_opens_at_the_bottom_of_the_focus_rank_without_the_focus() {
        val host = WindowFrameHost()
        fun open(id: String) = host.register(WindowFrameHost.Registration(id, id, WindowFrameState(id), true, null) {})
        open("Search")
        host.focus("Search")
        host.openAtBack("Calendar#2")
        host.openAtBack("Search#2")
        assertTrue(open("Calendar#2"), "opened at the back: it claims no focus")
        assertTrue(open("Search#2"))
        assertFalse(open("Tasks"), "an ordinary window opens on top")
        assertEquals("Tasks", host.stackOrder.last())
        // The notifications over their calendar, both under every other window.
        host.sendToBack("Search#2")
        host.sendToBack("Calendar#2")
        assertEquals(listOf("Calendar#2", "Search#2"), host.stackOrder.take(2))
        assertEquals("Search", host.focusedId)
    }

    @Test
    fun a_global_button_is_never_given_one_pages_configuration_at_the_start() {
        // Anomaly 2026-10-18: the global Search button opened the scheduler's alarm search on every start.
        val frozen = CustomMenuButtons.withConfigsFrozen(listOf(CustomMenuButtons.GLOBAL_SEARCH, item("b1"))) { """{"kinds":["Alarm"]}""" }
        assertEquals(null, frozen[0].config)
        assertEquals("""{"kinds":["Alarm"]}""", frozen[1].config)
    }
}
