package org.example.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow

/**
 * User rule 2026-11-03: *"Remove the check boxes on the search result list, and "Select all" and "add" button, as they are
 * redundant with right-click and ctrl+a."* So Ctrl+A in the list selects every row, and the row menu's "add" adds them
 * all. Drawn off screen, in the real Search window.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class SearchSelectAllTest {
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private val chronos = listOf(ChronoEntry(id = "a", label = "First"), ChronoEntry(id = "b", label = "Second"), ChronoEntry(id = "c", label = "Third"))

    @Test
    fun ctrl_a_then_the_menus_add_adds_every_row() {
        val added = ArrayList<List<String>>()
        val account = QuotaScenario.account().copy(chronos = chronos)
        val scene = onEdt {
            ImageComposeScene(1200, 620, density = Density(1f)) {
                var state by remember { mutableStateOf(account) }
                var config by remember { mutableStateOf(SearchDomain.Config(kinds = setOf(Kind.Chrono))) }
                SearchWindow(
                    state = state,
                    openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {}),
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
                    onConfigChange = {
                        config = it
                        added += it.added
                    },
                    onOpenConfigurations = {},
                    initialSize = Size(1200f, 620f),
                )
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun click(at: Offset, button: PointerButton = PointerButton.Primary) {
            val buttons = if (button == PointerButton.Secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
            onEdt {
                time += 16L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = buttons, button = button)
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 30, button = button)
            }
            frames(3)
        }
        try {
            frames(4)
            // The first row ("First") stands at y 134..168 under this head (drawn and looked at, 2026-11-03).
            val row = Offset(300f, 151f)
            click(row)
            time += 1_000L // past the double-click window: a second press there would ADD the row
            frames(2)
            onEdt {
                scene.sendKeyEvent(KeyEvent(key = Key.A, type = KeyEventType.KeyDown, isCtrlPressed = true))
                scene.sendKeyEvent(KeyEvent(key = Key.A, type = KeyEventType.KeyUp, isCtrlPressed = true))
            }
            frames(2)
            // The menu opens under the row, at the list's left edge, "add" first (drawn and looked at: about 50, 194).
            for (dy in 20..80 step 6) {
                time += 1_000L
                click(row, PointerButton.Secondary)
                frames(10)
                click(Offset(60f, row.y + dy))
                if (added.isNotEmpty()) break
            }
            assertEquals(listOf("Chrono/a", "Chrono/b", "Chrono/c", "Creation/Chrono"), added.lastOrNull()?.sorted(), "every row, the creation row's included: $added")
        } finally {
            onEdt { scene.close() }
        }
    }
}
