package org.example.project

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.ui.WindowFrameState
import org.example.project.ui.fitClear

/**
 * User rule 2026-11-15: *"I want every window to adjust, and not just to the pip but to anything above the app, when a
 * window in the app opens, it must adjust its position and size. If the user drags something over the app, it doesn't
 * adjust in live, only when the window in the app opens or stops being minimized in the system tray of the app."*
 */
class WindowAvoidanceTest {
    private val area = Rect(0f, 0f, 1000f, 800f)
    private val min = Size(220f, 120f)

    @Test
    fun a_clear_window_stays() {
        val window = Rect(100f, 100f, 500f, 400f)
        assertNull(fitClear(window, area, emptyList(), min))
        assertNull(fitClear(window, area, listOf(Rect(600f, 100f, 900f, 300f)), min), "a window over the app elsewhere")
        assertNull(fitClear(window, area, listOf(Rect(-500f, -500f, -100f, -100f)), min), "one off the app")
    }

    @Test
    fun a_covered_window_moves_to_the_nearest_clear_place_at_its_size() {
        // A video at the bottom right over the window's corner: the window steps left of it, nothing else moves.
        val window = Rect(500f, 400f, 900f, 700f)
        val video = Rect(800f, 550f, 1000f, 800f)
        val to = fitClear(window, area, listOf(video), min)!!
        assertEquals(Size(400f, 300f), to.size, "the same size")
        assertEquals(Rect(400f, 400f, 800f, 700f), to, "the nearest clear place: left of the video")
    }

    @Test
    fun where_no_place_holds_it_it_is_made_to_fit_the_largest_clear_room() {
        // Three quarters of the app covered: the window can only have the left quarter.
        val window = Rect(100f, 100f, 900f, 700f)
        val wall = Rect(250f, 0f, 1000f, 800f)
        val to = fitClear(window, area, listOf(wall), min)!!
        assertEquals(Rect(0f, 100f, 250f, 700f), to, "narrowed to the room, its height and top kept")
        assertTrue(to.width >= min.width && to.height >= min.height)
    }

    @Test
    fun nothing_clear_big_enough_and_it_stays() {
        val window = Rect(100f, 100f, 900f, 700f)
        val almostAll = Rect(100f, 0f, 1000f, 800f)
        assertNull(fitClear(window, area, listOf(almostAll), min), "100 wide is less than a usable window")
    }

    @Test
    fun the_window_state_is_moved_once_when_opened_and_when_back_from_the_reduce_bar() {
        val container = Size(1000f, 800f)
        // A 400 × 300 window centred: from (300, 250) to (700, 550).
        val state = WindowFrameState("w", Offset.Zero, Size(400f, 300f))
        val video = Rect(600f, 200f, 1000f, 600f)
        assertFalse(state.avoidCovering(container, listOf(video)), "not asked: a window the app put back stays")
        state.requestCoverCheck()
        assertTrue(state.avoidCovering(container, listOf(video)), "opened by the user: placed clear")
        val left = (container.width - state.size.width) / 2f + state.offset.x
        assertEquals(200f, left, "its right edge on the video's left")
        // The video is dragged over it now: nothing moves (no live adjustment).
        val dragged = Rect(0f, 0f, 1000f, 800f)
        assertFalse(state.avoidCovering(container, listOf(Rect(150f, 250f, 400f, 500f))))
        // Reduced, then brought back: placed clear again.
        state.minimize()
        state.restore()
        assertTrue(state.avoidCovering(container, listOf(Rect(150f, 250f, 400f, 500f))))
        assertFalse(state.avoidCovering(container, listOf(dragged)), "asked once")
    }

    @Test
    fun a_maximized_window_is_left_as_it_is() {
        val state = WindowFrameState("w", Offset.Zero, Size(400f, 300f))
        state.toggleMaximize()
        state.requestCoverCheck()
        assertFalse(state.avoidCovering(Size(1000f, 800f), listOf(Rect(600f, 200f, 1000f, 600f))))
    }
}
