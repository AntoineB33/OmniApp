package org.example.project

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.CellEditPopupHost
import org.example.project.ui.CellEditPopupRequest
import org.example.project.ui.LocalCellEditPopupHost
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow

/**
 * Anomaly 2026-11-11: *"when entering edit mode in a task element in the search result list, it doesn't open the search
 * window, but it does when it is in the task tree window."* A result row in Edit Mode shows the tree cell's rectangle:
 * it publishes its edit to the top layer ([CellEditPopupRequest.rename]), stuck to Rename, with no cell of its own.
 * User rule 2026-11-12: the switch between renaming and changing the id is a TREE CELL's — a child shown under its
 * expanded parent — never a result row's, whether or not its task stands in the tree.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class SearchRowEditPopupTest {
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test
    fun a_result_row_in_edit_mode_shows_the_rectangle() {
        // One task in the tree, "Apple".
        var account = SchedulerState.empty()
        account = SchedulerReducer.reduce(account, SchedulerIntent.SetCellTitle(account.lists[account.rootListId]!!.cellIds.last(), "Apple"))
        val title = "Apple"
        val popups = CellEditPopupHost()
        val scene = onEdt { ImageComposeScene(1200, 620, density = Density(1f)) {
            var state by remember { mutableStateOf(account) }
            var config by remember { mutableStateOf(SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Task), query = title)) }
            CompositionLocalProvider(LocalCellEditPopupHost provides popups) {
                SearchWindow(
                    state = state,
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
                    onIntent = { state = SchedulerReducer.reduce(state, it) },
                    onDismiss = {},
                    config = config,
                    onConfigChange = { config = it },
                    onOpenConfigurations = {},
                    initialSize = Size(1200f, 620f),
                )
            }
        } }
        var time = 0L
        fun frame() = onEdt { time += 16_000_000L; scene.render(time) }
        var request: CellEditPopupRequest? = null
        try {
            // The results are searched off the UI thread: frames until they are in.
            repeat(30) {
                frame()
                Thread.sleep(30)
            }
            assertNull(popups.request, "nothing in Edit Mode yet")
            // PRD §4: ↓ leaves the bar for the first row, ↓ again goes down the list; a letter typed on a selected task row enters its Edit Mode.
            fun key(key: Key, codePoint: Int = 0) = onEdt {
                scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, codePoint = codePoint))
                scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, codePoint = codePoint))
            }
            // The list's first row is the creation row; the task stands under it.
            repeat(2) {
                key(Key.DirectionDown)
                repeat(5) {
                    frame()
                    Thread.sleep(20)
                }
            }
            key(Key.X, 'x'.code)
            repeat(4) { frame() }
            request = popups.request
        } finally {
            onEdt { scene.close() }
        }
        val shown = assertNotNull(request, "a letter typed on the row opened its Edit Mode and its rectangle")
        assertNull(shown.cellId, "a result row changes no cell")
        val rename = assertNotNull(shown.rename, "its edit is the row's own, stuck to Rename")
        assertEquals("x", rename.draft, "the letter typed is the draft")
    }

    @Test
    fun the_picker_opens_on_its_search_section_alone() {
        val splits = CellEditPopupHost().splits
        assertEquals(true, splits.headerRemoved)
        assertEquals(false, splits.searchRemoved)
        assertEquals(true, splits.actionsRemoved)
        assertEquals(true, splits.addedRemoved)
    }
}
