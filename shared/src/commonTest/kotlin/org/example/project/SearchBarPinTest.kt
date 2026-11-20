package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    @Test
    fun each_keystroke_is_a_unit_and_ctrl_z_takes_back_one_character() {
        val s = typed()
        assertEquals(6, main(s).count { (it.delta as? ExternalDelta)?.key == bar }, "no keystroke merged into another")
        val undone = r(s, SchedulerIntent.Undo)
        assertEquals("fooba", barText(undone))
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
        assertTrue(main(s).none { it.undone }, "every keystroke applied again")
        assertNull(SchedulerReducer.searchTextStep(s, bar, back = false))
    }

    @Test
    fun typing_after_a_walk_drops_the_texts_walked_back_over() {
        var s = walk(typed(), back = true)
        s = key(s, "foo", "foox", runStart = true)
        val keys = main(s).mapNotNull { it.delta as? ExternalDelta }.filter { it.key == bar }.map { it.after }
        assertEquals(listOf("f", "fo", "foo", "foox"), keys, "a new edit after an undo branches, as any")
        assertEquals(3, SchedulerReducer.searchTextStep(s, bar, back = true), "back to \"foo\", left by the walk")
    }

    @Test
    fun the_run_start_is_stored_and_a_unit_written_before_reads_as_none() {
        val s = typed()
        val back = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(
            main(s).mapNotNull { (it.delta as? ExternalDelta)?.runStart },
            main(back).mapNotNull { (it.delta as? ExternalDelta)?.runStart },
        )
        assertEquals("foo", barText(walk(back, back = true)), "the stops survive a restart")
        // A unit written by the previous shape has no `runStart` at all (it is written only when true).
        val old = SchedulerStateCodec.encode(r(SchedulerState.empty(), SchedulerIntent.RecordExternal(bar, "", "a", "Search text")))
        assertFalse("runStart" in old, "a false run start is not written: the previous shape")
        val decoded = assertNotNull(SchedulerStateCodec.decode(old))
        assertEquals(false, (main(decoded).single().delta as ExternalDelta).runStart)
    }
}
