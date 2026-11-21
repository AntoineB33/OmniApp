package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.ExternalDelta
import org.example.project.scheduler.state.ExternalKeys
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-20: *"The up and down keyboard arrow in the search bar must navigate a specific pin in the history
 * units, but only those where the user did something else than changing the search bar content right after, like
 * scrolling or clicking elsewhere."* — and, asked: each keystroke is a unit (Ctrl+Z goes back a character at a time),
 * each pin walks its own units (the bar's, not the window's other changes), and at the end of its walk ↓ goes to the row
 * below.
 */
class SearchBarPinTest {
    private var stamped = false

    @BeforeTest
    fun stampWindows() {
        stamped = SchedulerReducer.stampsWindow
        SchedulerReducer.stampsWindow = true
    }

    @AfterTest
    fun restore() {
        SchedulerReducer.stampsWindow = stamped
    }

    private val bar = ExternalKeys.SEARCH_TEXT + "Search"

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun main(state: SchedulerState): List<HistoryUnit> = state.histories.forCategory(HistoryCategory.Main).units

    /** The Search bars' keystrokes: a list of their own (user rule 2026-11-21). */
    private fun texts(state: SchedulerState): List<HistoryUnit> = state.histories.forCategory(HistoryCategory.SearchText).units

    /** One keystroke of the bar, as `App.setSearchConfig` records it. */
    private fun key(state: SchedulerState, before: String, after: String, runStart: Boolean = false) =
        r(state, SchedulerIntent.RecordExternal(bar, before, after, "Search text", runStart = runStart))

    /** "foo", a filter changed, "bar" typed after a scroll: the bar's text, as `App` would put it back. */
    private fun typed(): SchedulerState {
        var s = r(SchedulerState.empty(), SchedulerIntent.FocusWindow(HistoryWindow.Search, ""))
        s = key(s, "", "f", runStart = true)
        s = key(s, "f", "fo")
        s = key(s, "fo", "foo")
        // Another change of the same window — another pin's.
        s = r(s, SchedulerIntent.RecordExternal(ExternalKeys.SEARCH + "Search", "{a}", "{b}", "Filters"))
        // A scroll, then typing again: the next keystroke starts a run.
        s = key(s, "foo", "foob", runStart = true)
        s = key(s, "foob", "fooba")
        return key(s, "fooba", "foobar")
    }

    private fun walk(state: SchedulerState, back: Boolean): SchedulerState {
        val moved = r(state, SchedulerIntent.WalkSearchText(bar, back))
        assertTrue(moved !== state, "the pin moved")
        return moved
    }

    private fun barText(state: SchedulerState): String? = state.externalRestores.last().let { assertEquals(bar, it.key); it.to }

    /**
     * User rule 2026-11-21 (*"make them isolated lists. Two pins can't share history units"*, asked: the Search bars
     * only): each keystroke is a unit of the Search bars' own list, which Ctrl+Z and Ctrl+Shift+Z never walk.
     */
    @Test
    fun each_keystroke_is_a_unit_of_a_list_no_chord_walks() {
        val s = typed()
        assertEquals(6, texts(s).count { (it.delta as? ExternalDelta)?.key == bar }, "no keystroke merged into another")
        assertTrue(main(s).none { (it.delta as? ExternalDelta)?.key == bar }, "none in the changes' list")
        val undone = r(s, SchedulerIntent.Undo)
        assertEquals(texts(s), texts(undone), "Ctrl+Z took no keystroke back")
        assertTrue(main(undone).single().undone, "it undid the window's own change")
        assertEquals(texts(s), texts(r(s, SchedulerIntent.UndoAll)), "nor did Ctrl+Shift+Z")
    }

    @Test
    fun typing_in_one_bar_keeps_the_texts_another_bar_walked_back_over() {
        val other = ExternalKeys.SEARCH_TEXT + "Search#2"
        var s = walk(typed(), back = true)
        s = r(s, SchedulerIntent.RecordExternal(other, "", "x", "Search text", runStart = true))
        assertEquals("foobar", barText(walk(s, back = false)), "the first bar's redo is still there")
    }

    /** Healed on load: keystrokes a build before wrote into the changes' list move to their own. */
    @Test
    fun keystrokes_stored_with_the_changes_move_to_their_own_list_on_load() {
        val s = typed()
        val units = (main(s) + texts(s)).sortedBy { it.deviceSeq }
        val old = s.copy(
            histories = s.histories
                .withCategory(HistoryCategory.Main, org.example.project.scheduler.state.SchedulerHistory(units.lastIndex, units))
                .withCategory(HistoryCategory.SearchText, org.example.project.scheduler.state.SchedulerHistory()),
        )
        val back = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(old)))
        assertEquals(1, main(back).size, "the window's change alone stays")
        assertEquals(0, back.histories.forCategory(HistoryCategory.Main).pointer)
        assertEquals(6, texts(back).size)
        assertEquals("foo", barText(walk(back, back = true)))
        val snapshot = SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(old))
        assertEquals(6, texts(assertNotNull(snapshot)).size, "the store's rows heal alike")
    }

    @Test
    fun up_stops_only_where_the_user_did_something_else_and_moves_nothing_but_the_bar() {
        var s = typed()
        s = walk(s, back = true)
        assertEquals("foo", barText(s), "the text left at the scroll — not \"fooba\"")
        assertEquals("foobar", s.externalRestores.last().from)
        assertEquals(1, s.externalRestores.count { it.key == bar }, "one restore for the walk, not one a keystroke")
        val filters = main(s).single { (it.delta as? ExternalDelta)?.key == ExternalKeys.SEARCH + "Search" }
        assertFalse(filters.undone, "the window's filter change is not this pin's")

        s = walk(s, back = true)
        assertEquals("", barText(s), "\"f\" and \"fo\" are no stops: the text before the first keystroke is")
        assertNull(SchedulerReducer.searchTextStep(s, bar, back = true), "the oldest end: ↑ is the row above's")
    }

    @Test
    fun down_walks_forward_then_hands_the_key_to_the_list_at_the_newest_text() {
        val typed = typed()
        assertNull(SchedulerReducer.searchTextStep(typed, bar, back = false), "at the newest text ↓ goes to the row below")
        var s = walk(walk(typed, back = true), back = true)
        s = walk(s, back = false)
        assertEquals("foo", barText(s))
        s = walk(s, back = false)
        assertEquals("foobar", barText(s))
        assertTrue(texts(s).none { it.undone }, "every keystroke applied again")
        assertNull(SchedulerReducer.searchTextStep(s, bar, back = false))
    }

    @Test
    fun typing_after_a_walk_drops_the_texts_walked_back_over() {
        var s = walk(typed(), back = true)
        s = key(s, "foo", "foox", runStart = true)
        val keys = texts(s).mapNotNull { it.delta as? ExternalDelta }.filter { it.key == bar }.map { it.after }
        assertEquals(listOf("f", "fo", "foo", "foox"), keys, "a new edit after an undo branches, as any")
        assertEquals(3, SchedulerReducer.searchTextStep(s, bar, back = true), "back to \"foo\", left by the walk")
    }

    /** Another bar, whose keystrokes fill the Search bars' list. */
    private val otherBar = ExternalKeys.SEARCH_TEXT + "Search#2"

    /** [state] with keystrokes of [otherBar] appended, each a run of its own, until the keystrokes' list holds [total]. */
    private fun filledTo(state: SchedulerState, total: Int): SchedulerState {
        val withOne = r(state, SchedulerIntent.RecordExternal(otherBar, "x", "y", "Search text", runStart = true))
        val template = texts(withOne).last()
        val start = template.timeMillis + 1
        val fillers = (0 until total - texts(state).size).map { i ->
            template.copy(timeMillis = start + i, deviceSeq = SchedulerReducer.deviceSeqOf(start + i, 0))
        }
        val units = texts(state) + fillers
        return state.copy(
            histories = state.histories.withCategory(
                HistoryCategory.SearchText,
                org.example.project.scheduler.state.SchedulerHistory(units.lastIndex, units),
            ),
        )
    }

    private fun keystrokes(state: SchedulerState) = texts(state).mapNotNull { it.delta as? ExternalDelta }.filter { it.key == bar }

    private fun other(state: SchedulerState, before: String, after: String) =
        r(state, SchedulerIntent.RecordExternal(otherBar, before, after, "Search text", runStart = true))

    /**
     * User rule 2026-11-20 (*"history units that are old get merged when they can (two character typing become one
     * history unit for example)"*): at the cap, the oldest keystrokes of a run are merged before any is evicted - and
     * never across a run's start, so the bar's pin keeps its stops. The keystrokes' list has its own cap: they never
     * push a change out (user rule 2026-11-21).
     */
    @Test
    fun at_the_cap_old_keystrokes_of_a_run_merge_before_any_is_evicted() {
        var s = r(SchedulerState.empty(), SchedulerIntent.FocusWindow(HistoryWindow.Search, ""))
        s = r(s, SchedulerIntent.RecordExternal(ExternalKeys.WINDOW_TITLE + "Search", "t", "u", "Title"))
        s = key(s, "", "a", runStart = true)
        s = key(s, "a", "ab")
        s = key(s, "ab", "abc")
        s = key(s, "abc", "abcd", runStart = true)
        s = filledTo(s, 1000)
        val firstFiller = texts(s)[4]

        s = other(s, "y", "z")
        assertEquals(1000, texts(s).size)
        assertEquals(listOf("" to "ab", "ab" to "abc", "abc" to "abcd"), keystrokes(s).map { it.before to it.after })
        assertTrue(texts(s).first().delta.let { it is ExternalDelta && it.runStart }, "the merged unit keeps the run's start, and its place")

        s = other(s, "z", "w")
        assertEquals(listOf("" to "abc", "abc" to "abcd"), keystrokes(s).map { it.before to it.after }, "never across a run's start")
        assertTrue(firstFiller in texts(s), "no other keystroke was evicted while keystrokes could merge")
        assertEquals("abc", barText(walk(s, back = true)), "the stop between the two runs is still a stop")

        s = other(s, "w", "v")
        assertEquals(listOf("abc" to "abcd"), keystrokes(s).map { it.before to it.after }, "nothing left to merge: the oldest goes")
        assertEquals(1, main(s).size, "the changes' list was never touched")
    }

    @Test
    fun a_keystroke_never_reaches_the_server_nor_asks_for_a_push() {
        val unit = texts(typed()).first { (it.delta as? ExternalDelta)?.key == bar }
        assertFalse(unit.delta.synced, "a Search bar's text is this device's view")
        assertTrue(unit.delta.localOnly, "no reason for a push")
        val filters = main(typed()).first { (it.delta as? ExternalDelta)?.key == ExternalKeys.SEARCH + "Search" }
        assertTrue(filters.delta.synced, "the window's other changes are pushed as before")
    }

    @Test
    fun a_window_drawn_inside_has_a_bar_key_of_its_own() {
        val key = ExternalKeys.searchText("Search#2", "Search#2/actions/▾x")
        assertEquals("Search#2" to "/actions/▾x", ExternalKeys.searchTextOf(key))
        assertEquals("Search#2" to "", ExternalKeys.searchTextOf(ExternalKeys.searchText("Search#2", "Search#2")))
        assertEquals(ExternalKeys.SEARCH_TEXT + "Search", ExternalKeys.searchText("Search", "Search"))
    }

    /**
     * Anomaly 2026-11-21 (*"I click "Task trees", add a row in the added elements list, type something in the search bar
     * of the action section, close the window, re-open it, click on the search bar of the action section, click the top
     * arrow keyboard key, but it doesn't give me the previous text"*): its keystrokes were whole-window units, under no
     * bar's key. The section's bar is the window's `actionSearch`, its key the section's id — the one its pin reads.
     */
    @Test
    fun the_actions_section_bar_is_a_bar_with_its_pin() {
        val section = ExternalKeys.searchText("Search#2", "Search#2/" + SearchDomain.ACTIONS_SECTION_PART)
        val (root, relative) = assertNotNull(ExternalKeys.searchTextOf(section))
        assertEquals("Search#2", root)
        assertEquals("", SearchDomain.actionsBarOf(relative), "the window's own actions section: its actionSearch")
        assertEquals("/actions", SearchDomain.actionsBarOf("/actions/actions"), "an actions section's own one: that entry's")
        assertNull(SearchDomain.actionsBarOf(""), "the window's own bar is its query")
        assertNull(SearchDomain.actionsBarOf("/actions/▾x"), "a ▾ window's bar is its query")

        // Typed in an actions section's OWN actions section: its entry's actionSearch alone changed.
        val own = SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Action), pageTypesOnly = false)
        val typed = mapOf("/actions" to own.copy(actionSearch = "pi").encode())
        assertEquals("/actions", SearchDomain.insideActionsTypedIn(emptyMap(), typed), "an entry gained by its text alone")
        assertEquals("/actions", SearchDomain.insideActionsTypedIn(typed, mapOf("/actions" to own.copy(actionSearch = "pie").encode())))
        assertNull(SearchDomain.insideActionsTypedIn(typed, mapOf("/actions" to own.copy(actionSearch = "pi", query = "x").encode())))

        // Its keystrokes walk as any bar's.
        var s = r(SchedulerState.empty(), SchedulerIntent.FocusWindow(HistoryWindow.Search, ""))
        s = r(s, SchedulerIntent.RecordExternal(section, "", "p", "Search an action", runStart = true))
        s = r(s, SchedulerIntent.RecordExternal(section, "p", "pi", "Search an action"))
        assertEquals(0, SchedulerReducer.searchTextStep(s, section, back = true, shown = "pi"), "↑ gives back the text before")
    }

    /**
     * User rule 2026-11-21 (*"the same pin persists just like the 'added elements' list section"*): a bar opened again
     * with another text than its pin's (emptied) is given the pin's text back by up, nothing walked; down is the list's.
     */
    @Test
    fun a_bar_showing_another_text_than_its_pin_is_given_the_pins_back_by_up() {
        val s = typed()
        assertNull(SchedulerReducer.searchTextStep(s, bar, back = false, shown = ""))
        assertEquals(3, SchedulerReducer.searchTextStep(s, bar, back = true, shown = "foobar"))
        val given = r(s, SchedulerIntent.WalkSearchText(bar, back = true, shown = ""))
        assertEquals("" to "foobar", given.externalRestores.last().let { it.from to it.to })
        assertEquals(texts(s), texts(given), "nothing walked")
    }

    /** User rule 2026-11-21: in a field's window, the bar's pin is its caller's - the same caller finds it again. */
    @Test
    fun a_bar_in_a_fields_window_has_a_pin_by_caller() {
        val e1e2 = SearchDomain.fieldCallerOf(listOf("Task/b", "Task/a"))
        val key = ExternalKeys.searchText("Search", "Search/actions/field:x/actions", e1e2)
        assertEquals("Search" to "/actions/field:x/actions", ExternalKeys.searchTextOf(key), "restored where the bar is")
        assertEquals(key, ExternalKeys.searchText("Search", "Search/actions/field:x/actions", SearchDomain.fieldCallerOf(listOf("Task/a", "Task/b"))))
        val e3 = ExternalKeys.searchText("Search", "Search/actions/field:x/actions", SearchDomain.fieldCallerOf(listOf("Task/c")))
        var s = r(SchedulerState.empty(), SchedulerIntent.RecordExternal(key, "", "p", "Search an action", runStart = true))
        s = r(s, SchedulerIntent.RecordExternal(key, "p", "pi", "Search an action"))
        assertNull(SchedulerReducer.searchTextStep(s, e3, back = true, shown = ""), "another caller: a pin of its own, empty")
        assertEquals(0, SchedulerReducer.searchTextStep(s, key, back = true, shown = "pi"))
    }

    @Test
    fun the_run_start_is_stored_and_a_unit_written_before_reads_as_none() {
        val s = typed()
        val back = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(
            texts(s).mapNotNull { (it.delta as? ExternalDelta)?.runStart },
            texts(back).mapNotNull { (it.delta as? ExternalDelta)?.runStart },
        )
        assertEquals("foo", barText(walk(back, back = true)), "the stops survive a restart")
        // A unit written by the previous shape has no `runStart` at all (it is written only when true).
        val old = SchedulerStateCodec.encode(r(SchedulerState.empty(), SchedulerIntent.RecordExternal(bar, "", "a", "Search text")))
        assertFalse("runStart" in old, "a false run start is not written: the previous shape")
        val decoded = assertNotNull(SchedulerStateCodec.decode(old))
        assertEquals(false, (texts(decoded).single().delta as ExternalDelta).runStart)
    }
}
