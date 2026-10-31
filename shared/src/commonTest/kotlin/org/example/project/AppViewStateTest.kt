package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.state.HistoryChord
import org.example.project.scheduler.state.HistorySource
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.ui.AppViewState
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.HistoryFilterConfig
import org.example.project.ui.HistoryFilterStore
import org.example.project.ui.WindowSections

/** User rule 2026-10-31: what the app was showing when it closed comes back, and what an older build wrote still reads. */
class AppViewStateTest {

    @Test
    fun thePageTheMenuAndTheWindowsOrderSurviveTheStore() {
        val view = AppViewState("ClaudeLogs", menuCollapsed = true, windowOrder = listOf("Search", "Calendar"))
        assertEquals(view, AppViewState.decode(view.encode()))
    }

    @Test
    fun nothingStoredOrNothingReadableIsAnAppNeverClosed() {
        assertEquals(AppViewState(), AppViewState.decode(null))
        assertEquals(AppViewState(), AppViewState.decode("not json"))
        // A later build's field is ignored; a page it names is the caller's to read or not.
        assertEquals(AppViewState(page = "Later"), AppViewState.decode("""{"page":"Later","other":1}"""))
    }

    @Test
    fun theHistoryFilterSurvivesTheStoreAndItsDefaultKeepsNothing() {
        val filter =
            HistoryFilterConfig(
                filterBySource = true, window = HistoryWindow.Calendar, chords = setOf(HistoryChord.Undo, HistoryChord.Selection),
                source = HistorySource.Api, query = "rename",
            )
        assertEquals(filter, HistoryFilterStore.decode(HistoryFilterStore.encode(filter)))
        assertEquals(null, HistoryFilterStore.encode(HistoryFilterConfig()))
        assertEquals(HistoryFilterConfig(), HistoryFilterStore.decode(null))
        // A value this build does not know reads as "any"; the rest of the filter stands.
        assertEquals(HistoryFilterConfig(query = "x"), HistoryFilterStore.decode("""{"window":"Later","source":"Later","query":"x"}"""))
    }

    @Test
    fun aSearchWindowsOpenRowsSurviveAndAnOlderRowHasNone() {
        val sections = mapOf("Search" to WindowSections(listOf(0.4f, 0.6f), listOf(false, true, false), listOf("t1"), listOf("t2")))
        assertEquals(sections, CustomMenuButtons.decodeWindowSections(CustomMenuButtons.encodeWindowSections(sections)))
        val older = CustomMenuButtons.decodeWindowSections("""{"sections":{"Search":{"splits":[0.4,0.6],"hidden":[false,false,false]}}}""")
        assertEquals(emptyList(), older.getValue("Search").expanded)
    }
}
