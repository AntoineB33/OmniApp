package org.example.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchSplits
import org.example.project.ui.SearchWindow

/**
 * The Search window's three sections, resized by the two lines between them. Anomaly 2026-10-21: *"the lines move
 * slower than the mouse"* — a line moves exactly as far as the pointer — and *"the line must be draggable even if its
 * section got minimized"*: a drag of the line beside a retracted section brings the section back.
 */
class SearchSectionsTest {
    private val width = 1200
    private val height = 700

    /**
     * Drags from each of [froms] in turn, in one window, by [by] in [steps] mouse moves, and stops at the first that
     * grabs a line — that tells a split whose shares moved (a press on a section's arrow only retracts it). Returns the
     * point that grabbed and every split told by its drag; null if none did.
     */
    private fun drag(initial: SearchSplits, froms: List<Offset>, by: Offset, steps: Int): Pair<Offset, List<SearchSplits>>? {
        val told = ArrayList<SearchSplits>()
        var dragging = false
        val account = QuotaScenario.account()
        // The scene's effects are resumed on the AWT event thread: driven from the test thread, one ran in the middle of
        // a layout pass ("performMeasureAndLayout called during measure layout") and the drag died. Every step runs on
        // that thread instead, one hop each, so the effects run between frames as they do in the app.
        val scene = onEdt { ImageComposeScene(width, height, density = Density(1f)) {
            var splits by remember { mutableStateOf(initial) }
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
                onSplitsChange = { splits = it; if (dragging) told += it },
                initialSize = Size(width.toFloat(), height.toFloat()),
            )
        } }
        try {
            repeat(3) { onEdt { scene.render() } }
            dragging = true
            for (from in froms) {
                told.clear()
                onEdt {
                    scene.sendPointerEvent(PointerEventType.Move, from)
                    scene.sendPointerEvent(PointerEventType.Press, from)
                    scene.render()
                }
                for (step in 1..steps) onEdt {
                    scene.sendPointerEvent(PointerEventType.Move, from + by * (step.toFloat() / steps))
                    scene.render()
                }
                onEdt {
                    scene.sendPointerEvent(PointerEventType.Release, from + by)
                    scene.render()
                }
                onEdt { scene.render() }
                if (told.any { it.left != initial.left || it.topRight != initial.topRight }) return from to told.toList()
            }
        } finally {
            onEdt { scene.close() }
        }
        return null
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Finds the line among [froms] with a quick drag, then drags it from there in a fresh window, as a mouse does. */
    private fun dragFirstHit(initial: SearchSplits, froms: Sequence<Offset>, by: Offset): List<SearchSplits> {
        // A fresh window per probe: a press that misses the line may start something of its own (a row's drag).
        val at = checkNotNull(froms.firstNotNullOfOrNull { drag(initial, listOf(it), by, steps = 2)?.first }) { "no line found" }
        return checkNotNull(drag(initial, listOf(at), by, steps = 40)).second
    }

    @Test
    fun the_vertical_line_follows_the_pointer_exactly() {
        val told = dragFirstHit(SearchSplits(), (width / 2 - 16..width / 2 + 16 step 3).asSequence().map { Offset(it.toFloat(), 400f) }, Offset(-120f, 0f))
        assertEquals(1, told.size, "told once, at the end of the drag")
        // The room the two sections share: the window less its 1-px border on each side and the 9-px line.
        val moved = (told.last().left - 0.5f) * (width - 2 - 9)
        assertEquals(-120f, moved, 2f, "the line moved as far as the pointer")
    }

    @Test
    fun the_horizontal_line_follows_the_pointer_exactly() {
        val x = width * 3 / 4f
        val told = dragFirstHit(SearchSplits(), (40..660 step 3).asSequence().map { Offset(x, it.toFloat()) }, Offset(0f, 80f))
        assertEquals(1, told.size, "told once, at the end of the drag")
        val first = told.last()
        assertTrue(first.topRight > 0.5f, "dragged down, the actions grow: $first")
    }

    @Test
    fun a_drag_out_of_the_retracted_search_brings_it_back() {
        val told = dragFirstHit(
            SearchSplits(searchHidden = true),
            (4..80 step 3).asSequence().map { Offset(it.toFloat(), 400f) },
            Offset(300f, 0f),
        )
        val last = told.last()
        assertEquals(false, last.searchHidden, "the search is back")
        assertTrue(last.left * (width - 11) in 250f..400f, "about as wide as dragged: $last")
    }

    @Test
    fun a_drag_out_of_the_retracted_actions_brings_them_back() {
        val x = width * 3 / 4f
        val told = dragFirstHit(
            SearchSplits(actionsHidden = true),
            (30..140 step 3).asSequence().map { Offset(x, it.toFloat()) },
            Offset(0f, 200f),
        )
        assertEquals(false, told.last().actionsHidden, "the actions are back")
    }

    @Test
    fun a_drag_out_of_the_retracted_added_elements_brings_them_back() {
        val x = width * 3 / 4f
        val told = dragFirstHit(
            SearchSplits(addedHidden = true),
            ((height - 20) downTo (height - 140) step 3).asSequence().map { Offset(x, it.toFloat()) },
            Offset(0f, -200f),
        )
        assertEquals(false, told.last().addedHidden, "the added elements are back")
    }
}
