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
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.CellEditPopupHost
import org.example.project.ui.LocalCellEditPopupHost
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchSplits
import org.example.project.ui.SearchWindow

/**
 * Anomaly 2026-11-19: *"I select the child of 'AI', then click the keyboard top arrow, but it doesn't select 'AI'.
 * However, it works from the grand-child to the child."* The expanded row's sub-tree is the tree's own drawing, and the
 * tree's walk goes on past its edges: ↓ on the row reaches its first child, ↑ on that child reaches the row again — and ←
 * / → walk as ↑ / ↓, as in the tree.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class SearchSubtreeWalkTest {

    @Test
    fun the_arrows_walk_between_an_expanded_row_and_its_children() {
        // "AI" with one child, "Kid".
        var account = SchedulerState.empty()
        val aiCell = account.lists[account.rootListId]!!.cellIds.last()
        account = SchedulerReducer.reduce(account, SchedulerIntent.SetCellTitle(aiCell, "AI"))
        val ai = account.cells[aiCell]!!.taskId!!
        val kidCell = account.lists[account.tasks[ai]!!.childListId!!]!!.cellIds[0]
        account = SchedulerReducer.reduce(account, SchedulerIntent.SetCellTitle(kidCell, "Kid"))

        val popups = CellEditPopupHost()
        var live = account
        val scene = onEdt { ImageComposeScene(1200, 620, density = Density(1f)) {
            var state by remember { mutableStateOf(account) }
            var config by remember { mutableStateOf(SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Task), query = "AI")) }
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
                    onIntent = {
                        state = SchedulerReducer.reduce(state, it)
                        live = state
                    },
                    onDismiss = {},
                    config = config,
                    onConfigChange = { config = it },
                    onOpenConfigurations = {},
                    // "AI"'s row opened, its sub-tree under it.
                    splits = SearchSplits(expandedResults = setOf(ai.value)),
                    initialSize = Size(1200f, 620f),
                )
            }
        } }
        var time = 0L
        fun frame() = onEdt { time += 16_000_000L; scene.render(time) }
        fun settle() = repeat(6) {
            frame()
            Thread.sleep(20)
        }
        fun key(key: Key, codePoint: Int = 0) {
            onEdt {
                scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, codePoint = codePoint))
                scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, codePoint = codePoint))
            }
            settle()
        }
        try {
            // The results are searched off the UI thread: frames until they are in.
            repeat(30) {
                frame()
                Thread.sleep(30)
            }
            // Down from the bar along the rows ("AI", under a creation row where the list shows one) until the walk goes
            // into the sub-tree: from the expanded row, its first child, as the tree goes from a cell to its first child.
            var steps = 0
            while (live.searchSelection.main == null && steps < 4) {
                key(Key.DirectionDown)
                steps++
            }
            assertEquals(kidCell, live.searchSelection.main, "↓ on 'AI' selects its first child")
            // ↑ on that first child: 'AI' again — a letter typed now renames 'AI', the row holding the keyboard.
            key(Key.DirectionUp)
            // ← and → are the tree's ↑ and ↓, in the list as in the sub-tree (anomaly 2026-11-19).
            key(Key.DirectionRight)
            // In the sub-tree now: → again moves its own selection, off the first child, and ← brings it back.
            key(Key.DirectionRight)
            assertTrue(live.searchSelection.main != kidCell, "→ walked into the sub-tree and on along it")
            key(Key.DirectionLeft)
            assertEquals(kidCell, live.searchSelection.main, "← walks back up the sub-tree")
            key(Key.DirectionLeft)
            key(Key.X, 'x'.code)
            val request = assertNotNull(popups.request, "↑ on the first child selected 'AI', which a letter renames")
            assertEquals("x", assertNotNull(request.rename).draft)
        } finally {
            onEdt { scene.close() }
        }
    }
}
