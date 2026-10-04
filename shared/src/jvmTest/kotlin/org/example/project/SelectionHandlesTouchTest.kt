package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskTreeView
import org.example.project.ui.SheetColors
import org.jetbrains.skia.Bitmap

/**
 * `docs/PLATFORMS.md`: **a finger's Shift+click** — the tree's selection wears a dot on its top-left corner and one on
 * its bottom-right, and dragging one moves that end of the range while the other stays put. Driven through real touch
 * events on a rendered tree; the dots are found on the rendered pixels, where the user would look for them.
 */
class SelectionHandlesTouchTest {

    private val titles = listOf("A", "B", "C", "D", "E")

    private fun tree(): SchedulerState {
        var s = SchedulerState.empty()
        titles.forEachIndexed { i, title ->
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        return s
    }

    private fun cellOf(s: SchedulerState, title: String): CellId =
        s.lists[s.rootListId]!!.cellIds.first { s.tasks[s.cells[it]?.taskId]?.title == title }

    private fun selectedTitles(s: SchedulerState): List<String> =
        s.lists[s.rootListId]!!.cellIds.filter { it in s.selection.selected || it == s.selection.main }
            .mapNotNull { s.tasks[s.cells[it]?.taskId]?.title }

    /** The pixels of [color] on the rendered tree. */
    private fun pixelsOf(
        scene: ImageComposeScene,
        time: Long,
        color: androidx.compose.ui.graphics.Color,
        tolerance: Int = 8,
    ): List<Pair<Int, Int>> {
        val bitmap = Bitmap.makeFromImage(scene.render(time * 1_000_000))
        val target = color.toArgb()
        fun close(c: Int) = (0..2).all { k -> abs(((c shr (8 * k)) and 0xFF) - ((target shr (8 * k)) and 0xFF)) <= tolerance }
        val out = ArrayList<Pair<Int, Int>>()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (close(bitmap.getColor(x, y))) out += x to y
        return out
    }

    @Test
    fun dragging_the_dots_extends_and_shortens_the_selection() {
        var state by mutableStateOf(tree())
        state = SchedulerReducer.reduce(
            state,
            SchedulerIntent.ClickCell(cellOf(state, "B"), ctrl = false, shift = false, visibleOrder = emptyList()),
        )
        val scene = ImageComposeScene(width = 500, height = 400, density = Density(1f)) {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    TaskTreeView(
                        state = state,
                        priorities = emptyMap(),
                        onIntent = { state = SchedulerReducer.reduce(state, it) },
                        keyboardActive = true,
                        // As the app gives it: the tree fills its window.
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        try {
            var t = 0L
            fun touch(type: PointerEventType, at: Offset) {
                t += 16
                scene.sendPointerEvent(eventType = type, position = at, timeMillis = t, type = PointerType.Touch)
                scene.render(t * 1_000_000)
            }

            // B's grey (the main selection's fill) gives a row's band; a finger tap on B keeps B and puts the tree
            // in touch mode. No dot before a finger has touched the tree: the desktop look has none.
            assertTrue(pixelsOf(scene, t, SheetColors.activeBorder).isEmpty(), "no handle before a finger touches the tree")
            val outline = pixelsOf(scene, t, SheetColors.mainSelectionFill, tolerance = 0)
            assertTrue(outline.isNotEmpty(), "the selected cell is drawn")
            val top = outline.minOf { it.second }
            val bottom = outline.maxOf { it.second }
            val rowHeight = (bottom - top).toFloat()
            val bCentre = Offset(outline.map { it.first }.average().toFloat(), (top + bottom) / 2f)
            touch(PointerEventType.Press, bCentre)
            touch(PointerEventType.Release, bCentre)
            assertEquals(listOf("B"), selectedTitles(state))

            // The bottom-right dot: drag it two rows down — B stays the top, D becomes the bottom.
            val dots = pixelsOf(scene, t, SheetColors.activeBorder)
            assertTrue(dots.isNotEmpty(), "a finger's selection wears its handles")
            val bottomRight = assertNotNull(dots.maxByOrNull { it.first + it.second })
            val from = Offset(bottomRight.first.toFloat(), bottomRight.second.toFloat())
            touch(PointerEventType.Press, from)
            touch(PointerEventType.Move, from + Offset(0f, rowHeight))
            touch(PointerEventType.Move, from + Offset(0f, 2 * rowHeight))
            touch(PointerEventType.Release, from + Offset(0f, 2 * rowHeight))
            assertEquals(listOf("B", "C", "D"), selectedTitles(state), "the bottom dot extends the range")

            // The top-left dot: drag it one row down — D stays the bottom, the top moves to C.
            val after = pixelsOf(scene, t, SheetColors.activeBorder)
            val topLeft = assertNotNull(after.minByOrNull { it.first + it.second })
            val from2 = Offset(topLeft.first.toFloat(), topLeft.second.toFloat())
            touch(PointerEventType.Press, from2)
            touch(PointerEventType.Move, from2 + Offset(0f, rowHeight))
            touch(PointerEventType.Release, from2 + Offset(0f, rowHeight))
            assertEquals(listOf("C", "D"), selectedTitles(state), "the top dot shortens it")

            // A mouse press in the tree brings back the desktop look: Shift+click is there, so no dots.
            t += 16
            scene.sendPointerEvent(eventType = PointerEventType.Press, position = bCentre, timeMillis = t, type = PointerType.Mouse)
            t += 16
            scene.sendPointerEvent(eventType = PointerEventType.Release, position = bCentre, timeMillis = t, type = PointerType.Mouse)
            assertTrue(pixelsOf(scene, t, SheetColors.activeBorder).isEmpty(), "a mouse hides the handles")
        } finally {
            scene.close()
        }
    }
}
