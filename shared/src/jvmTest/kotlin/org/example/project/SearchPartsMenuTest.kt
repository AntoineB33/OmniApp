package org.example.project

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.SearchPart
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchSplits
import org.example.project.ui.SearchWindow
import org.example.project.ui.WindowLayout

/**
 * User rule 2026-11-11: the Search window's background menu hides and shows the header and each of the three sections
 * ([SearchPart]); a section shown again is expanded, never back as its arrow alone.
 */
class SearchPartsMenuTest {
    @Test
    fun each_entry_hides_then_shows_its_part() {
        for (part in SearchPart.entries) {
            val hidden = part.toggled(SearchSplits())
            assertTrue(part.isRemoved(hidden), "$part hidden")
            assertEquals("Show", part.menuLabel(part.isRemoved(hidden)).substringBefore(' '))
            assertEquals("Hide", part.menuLabel(part.isRemoved(SearchSplits())).substringBefore(' '))
            assertFalse(part.isRemoved(part.toggled(hidden)), "$part shown again")
            // Only its own part moved.
            SearchPart.entries.filter { it != part }.forEach { assertFalse(it.isRemoved(hidden), "$it untouched by $part") }
        }
    }

    @Test
    fun a_section_shown_again_is_not_minimized() {
        val retracted = SearchSplits(searchHidden = true, actionsHidden = true, addedHidden = true)
        val search = SearchPart.Search.toggled(SearchPart.Search.toggled(retracted))
        assertFalse(search.searchHidden)
        val actions = SearchPart.Actions.toggled(SearchPart.Actions.toggled(retracted))
        assertFalse(actions.actionsHidden)
        assertTrue(actions.addedHidden, "the other sections stay as they were")
        val added = SearchPart.Added.toggled(SearchPart.Added.toggled(retracted))
        assertFalse(added.addedHidden)
        // Hiding keeps the retraction; only showing expands.
        assertTrue(SearchPart.Search.toggled(retracted).searchHidden)
    }

    @Test
    fun the_header_is_the_windows_head_and_leaves_the_sections_alone() {
        val retracted = SearchSplits(searchHidden = true, searchRemoved = true)
        val gone = SearchPart.Header.toggled(retracted)
        assertTrue(gone.headerRemoved)
        val back = SearchPart.Header.toggled(gone)
        assertFalse(back.headerRemoved)
        assertEquals(retracted, back, "the sections as they were")
    }

    @Test
    fun the_flags_round_trip_and_an_older_payload_removes_nothing() {
        val splits = SearchSplits(0.3f, 0.6f, searchHidden = true, headerRemoved = true, addedRemoved = true, barRemoved = true)
        assertEquals(splits, SearchSplits.of(0.3f, 0.6f, splits.hiddenFlags))
        // What a build before 2026-11-12 kept: seven flags, the bar shown.
        assertFalse(SearchSplits.of(0.5f, 0.5f, splits.hiddenFlags.take(7)).barRemoved)
        // What a build before 2026-11-11 kept: the three retracted flags alone.
        val older = SearchSplits.of(0.5f, 0.5f, listOf(false, true, false))
        assertTrue(older.actionsHidden)
        assertFalse(older.headerRemoved || older.searchRemoved || older.actionsRemoved || older.addedRemoved)
        // A menu button's layout kept with three flags is the same as one now told with seven, all new ones off.
        val kept = WindowLayout(0f, 0f, 100f, 100f, listOf(0.5f, 0.5f), listOf(false, true, false))
        assertTrue(kept.sameAs(kept.copy(hidden = older.hiddenFlags)))
        assertFalse(kept.sameAs(kept.copy(hidden = older.copy(addedRemoved = true).hiddenFlags)))
    }

    @Test
    fun the_window_draws_with_any_part_taken_off() {
        val account = QuotaScenario.account()
        val cases =
            listOf(
                SearchSplits(headerRemoved = true),
                SearchSplits(barRemoved = true),
                SearchSplits(searchRemoved = true),
                SearchSplits(actionsRemoved = true),
                SearchSplits(addedRemoved = true),
                SearchSplits(actionsRemoved = true, addedRemoved = true),
                SearchSplits(addedRemoved = true, actionsHidden = true),
                SearchSplits(headerRemoved = true, searchRemoved = true, actionsRemoved = true, addedRemoved = true),
            )
        for (splits in cases) {
            val told = ArrayList<SearchSplits>()
            onEdt {
                val scene = ImageComposeScene(1000, 600, density = Density(1f)) {
                    SearchWindow(
                        state = account,
                        openers = SearchRowOpeners(
                            onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {},
                        ),
                        onStartTaskNow = {},
                        onGoToTaskTree = { _, _ -> },
                        onDeepCopyCell = {},
                        actionHandlers = AddedActionHandlers(
                            onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
                            onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
                            onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
                        ),
                        onIntent = {},
                        onDismiss = {},
                        config = SearchDomain.Config(),
                        onConfigChange = {},
                        onOpenConfigurations = {},
                        splits = splits,
                        onSplitsChange = { told += it },
                        initialSize = Size(1000f, 600f),
                    )
                }
                try {
                    repeat(3) { scene.render() }
                } finally {
                    scene.close()
                }
            }
            // Whatever the window tells back keeps what was taken off.
            told.forEach { assertEquals(splits.hiddenFlags, it.hiddenFlags, "told back as given: $splits") }
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
