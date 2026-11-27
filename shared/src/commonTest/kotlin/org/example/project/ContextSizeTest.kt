package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ContextDefault
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.WindowSize

/**
 * User rule 2026-11-27 (*"For a context element, there should be a width and height values, right? Or maybe other values
 * to get the perfect size of the window for this context when there is not enough room"*): **a context says the size a
 * window opens at** — in dp, each side at most a share of the room ([SearchDomain.Config.size], [ContextDomain.sizeOf],
 * [ContextDomain.fitted]) — and a window's head writes its own as its context's ([ContextDomain.withSize]).
 */
class ContextSizeTest {
    private val alarms = ContextDomain.frameContextId("Alarms")

    @Test
    fun a_window_opens_at_its_context_size_the_code_s_until_the_account_sets_one() {
        assertEquals(WindowSize(440, 560), ContextDomain.sizeOf(emptyList(), alarms))
        val defaults = ContextDomain.withSize(emptyList(), alarms, 600, 700)
        assertEquals(WindowSize(600, 700), ContextDomain.sizeOf(defaults, alarms))
        // One row, and the other windows untouched.
        assertEquals(listOf(alarms), defaults.map { it.id })
        assertEquals(WindowSize(320, 300), ContextDomain.sizeOf(defaults, ContextDomain.frameContextId("Sleep")))
    }

    @Test
    fun writing_the_code_s_own_size_back_stores_nothing() {
        val defaults = ContextDomain.withSize(emptyList(), alarms, 600, 700)
        assertEquals(emptyList(), ContextDomain.withSize(defaults, alarms, 440, 560))
    }

    @Test
    fun the_shares_of_the_room_are_kept_when_the_head_writes_a_size() {
        val config = ContextDomain.configOf(emptyList(), alarms)
        val halfRoom = ContextDomain.written(emptyList(), alarms, config.copy(size = WindowSize(440, 560, 50, 80)))
        assertEquals(WindowSize(500, 400, 50, 80), ContextDomain.sizeOf(ContextDomain.withSize(halfRoom, alarms, 500, 400), alarms))
    }

    @Test
    fun a_search_window_takes_its_own_context_s_size_else_a_search_window_s() {
        assertEquals(ContextDomain.SEARCH_WINDOW_SIZE, ContextDomain.sizeOf(emptyList(), ContextDomain.SEARCH_WINDOW))
        assertEquals(ContextDomain.SEARCH_WINDOW_SIZE, ContextDomain.sizeOf(emptyList(), SearchDomain.HISTORY_CONTEXT))
        val searchWide = ContextDomain.withSize(emptyList(), ContextDomain.SEARCH_WINDOW, 1200, 700)
        assertEquals(WindowSize(1200, 700), ContextDomain.sizeOf(searchWide, SearchDomain.HISTORY_CONTEXT))
        val historyOwn = ContextDomain.withSize(searchWide, SearchDomain.HISTORY_CONTEXT, 900, 500)
        assertEquals(WindowSize(900, 500), ContextDomain.sizeOf(historyOwn, SearchDomain.HISTORY_CONTEXT))
        assertEquals(WindowSize(1200, 700), ContextDomain.sizeOf(historyOwn, ContextDomain.SEARCH_WINDOW))
    }

    @Test
    fun a_side_is_cut_to_its_share_of_the_room_never_under_the_least() {
        val size = WindowSize(1040, 600, maxWidthPercent = 90, maxHeightPercent = 100)
        assertEquals(1040f to 600f, ContextDomain.fitted(size, 2000f, 1000f))
        // A narrow room: 90% of its width; the height fits.
        assertEquals(720f to 600f, ContextDomain.fitted(size, 800f, 1000f))
        // A short room: all of its height.
        assertEquals(1040f to 400f, ContextDomain.fitted(size, 2000f, 400f))
        // No room known yet, or unbounded: nothing cut.
        assertEquals(1040f to 600f, ContextDomain.fitted(size, null, Float.POSITIVE_INFINITY))
        assertEquals(200f to 200f, ContextDomain.fitted(size, 100f, 100f, minDp = 200f))
    }

    @Test
    fun the_size_is_a_search_configuration_shown_by_value_in_each_context() {
        assertEquals(SearchDomain.SettingRole.Display, SearchDomain.roleOf(SearchDomain.Setting.Size))
        val defaults = ContextDomain.withSize(emptyList(), alarms, 600, 700)
        val groups = ContextDomain.groups(defaults, SearchDomain.Setting.Size)
        val alarmsGroup = groups.single { group -> group.contexts.any { it.id == alarms } }
        assertEquals("600 × 700 dp", alarmsGroup.value)
        assertEquals(listOf(alarms), alarmsGroup.contexts.map { it.id })
    }

    @Test
    fun the_head_menu_lists_the_entry_by_default() {
        assertTrue(ContextDomain.USE_SIZE_ENTRY in ContextDomain.MENU_ENTRIES.getValue("HeadMenu"))
    }

    /** Persisted-DB compatibility: a configuration and a context row stored before the size existed. */
    @Test
    fun a_configuration_stored_before_the_size_reads_as_none_and_a_stored_one_round_trips_healed() {
        val before = """{"query":"x","kinds":["Task"],"separators":true}"""
        val decoded = SearchDomain.Config.decode(before)!!
        assertNull(decoded.size)
        assertEquals(WindowSize(440, 560), ContextDomain.sizeOf(listOf(ContextDefault(alarms, before)), alarms))

        val sized = SearchDomain.Config(size = WindowSize(500, 400, 70, 60))
        assertEquals(sized.size, SearchDomain.Config.decode(sized.encode())!!.size)

        // Out of range (an older or a hand-edited row): healed into range, never refused.
        val wild = """{"sizeWidthDp":5,"sizeHeightDp":99999,"sizeMaxWidthPercent":0,"sizeMaxHeightPercent":250}"""
        assertEquals(WindowSize(WindowSize.MIN_DP, WindowSize.MAX_DP, 1, 100), SearchDomain.Config.decode(wild)!!.size)
        // One side alone says no size.
        assertNull(SearchDomain.Config.decode("""{"sizeWidthDp":500}""")!!.size)
    }

    @Test
    fun a_change_of_size_made_on_one_context_s_configuration_is_made_on_another_side_by_side() {
        // The field-by-field merge of "state in each context": changing the height alone keeps the other's width.
        val a = SearchDomain.Config(size = WindowSize(400, 300))
        val b = SearchDomain.Config(size = WindowSize(800, 300))
        val merged = ContextDomain.withChange(b, a, a.copy(size = WindowSize(400, 500)))
        assertEquals(WindowSize(800, 500), merged.size)
    }
}
