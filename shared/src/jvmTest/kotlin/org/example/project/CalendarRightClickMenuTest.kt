package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import org.example.project.ui.CalendarDisplayMode
import org.example.project.ui.CalendarElementGrid
import org.example.project.ui.CalendarElementView
import org.example.project.ui.LocalEmbeddedSearch
import org.example.project.ui.transientMenuDismissRoot

/**
 * Anomaly 2026-11-21 (*"The right-click doesn't work..."*, the `[popup]` trace: the calendar's menu window gone 15–130 ms
 * after it opened, no close reason of its own): **a right-click on the calendar grid opens its menu, and the menu stays**,
 * "add…" in it — the real grid ([CalendarElementGrid]), a Search window able to draw another inside it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class CalendarRightClickMenuTest {
    @Test
    fun a_right_click_on_the_calendar_opens_its_menu_and_it_stays() = check(hoverWindows = true)

    /** The menu as it was before the hover windows: its entries open a window of the app at a click. */
    @Test
    fun without_hover_windows_too() = check(hoverWindows = false)

    private fun check(hoverWindows: Boolean) {
        val day = LocalDate(2026, 11, 21)
        val noon = 1_795_000_000_000L
        val menus = org.example.project.ui.TransientMenuHost()
        val view = CalendarElementView(day)
        val scene = onEdt {
            ImageComposeScene(1000, 800, density = Density(1f)) {
                CompositionLocalProvider(
                    org.example.project.ui.LocalTransientMenuHost provides menus,
                    LocalEmbeddedSearch provides if (hoverWindows) { _, _ -> Text("EMBEDDED SEARCH") } else null,
                ) {
                    Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus)) {
                        CalendarElementGrid(
                            view = view, selectedDate = day, today = day, nowMillis = noon, nowExactMillis = { noon },
                            records = emptyList(), pieces = null, realMillisFor = { it },
                            taskColors = emptyMap(), taskSheetColors = emptyMap(), calculating = false,
                            onFocus = {}, onAddAt = {}, onCommitBounds = { _, _, _, _ -> }, onEditChoice = {},
                            onEditElementsAt = { _, _ -> }, onGoToTaskTree = { _, _ -> }, onToggleReminder = {},
                            onAdjustWeights = {}, overlapArmed = false, onToggleOverlap = {},
                            displayMode = CalendarDisplayMode.Day, lockTaskMillis = null, lockOnTask = false,
                            onLockOnTaskChange = {}, lockOnTaskNonce = 0, openingSpan = null,
                            onVisibleDaysChanged = { _, _ -> }, onNowLineResolutionChanged = {},
                        )
                    }
                }
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun texts(): List<String> = onEdt {
            scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        }
        try {
            frames(20)
            // Inside the first day column (68–256 px wide, under the day heads).
            val at = Offset(160f, 300f)
            onEdt {
                time += 100L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
            }
            frames(3)
            onEdt { time += 60L; scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time, button = PointerButton.Secondary) }
            frames(3)
            assertTrue("add…" in texts(), "the menu opens: ${texts()}")
            frames(30)
            assertTrue("add…" in texts(), "the menu stays open: ${texts()}")
        } finally {
            onEdt { scene.close() }
        }
    }
}
