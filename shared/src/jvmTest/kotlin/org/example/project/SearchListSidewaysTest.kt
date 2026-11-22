package org.example.project

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow

/**
 * User rule 2026-11-15: *"I shrinked the width of the search result list to the point that the vertical scrollbar wasn't
 * showing, but shift+scroll doesn't do anything. It must scroll horizontally when the scrollbar becomes hidden, and when
 * the mouse is on the search result list."* Drawn off screen, in the real Search window, narrower than the list's least
 * width: Shift+wheel over the list moves its rows sideways, and back; it stops at the list's left edge.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SearchListSidewaysTest {

    private val chronos = (0 until 40).map { ChronoEntry(id = "c$it", label = "Chrono $it") }

    @Test
    fun shift_wheel_scrolls_a_cut_list_sideways() {
        val account = QuotaScenario.account().copy(chronos = chronos)
        val width = 300
        val scene = onEdt {
            ImageComposeScene(width, 620, density = Density(1f)) {
                SearchWindow(
                    state = account,
                    openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {}),
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
                    config = SearchDomain.Config(kinds = setOf(Kind.Chrono)),
                    onConfigChange = {},
                    initialSize = Size(width.toFloat(), 620f),
                )
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun boundsOf(text: String): Rect = onEdt {
            scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
                .boundsInWindow
        }
        fun wheel(at: Offset, dx: Float) {
            onEdt {
                time += 16L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(
                    PointerEventType.Scroll, at, scrollDelta = Offset(dx, 0f), timeMillis = time,
                    keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true),
                )
            }
            frames(3)
        }
        try {
            frames(6)
            val start = boundsOf("Chrono 0")
            val over = Offset(start.left.coerceAtLeast(20f) + 10f, start.center.y)

            wheel(over, -3f)
            assertEquals(start.left, boundsOf("Chrono 0").left, 0.5f, "at the left edge already: nothing further left")

            wheel(over, 3f)
            val moved = boundsOf("Chrono 0")
            assertTrue(moved.left < start.left - 1f, "Shift+wheel scrolls the cut list sideways: ${start.left} -> ${moved.left}")
            assertEquals(start.top, moved.top, 0.5f, "sideways only, not down")

            wheel(over, -3f)
            assertEquals(start.left, boundsOf("Chrono 0").left, 0.5f, "and back")
        } finally {
            onEdt { scene.close() }
        }
    }
}
