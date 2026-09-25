package org.example.project

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.persistence.WindowPlacement
import org.example.project.ui.AppWindowBounds
import org.example.project.ui.settledAppWindowBounds

/** The desktop app's own window: kept as it was left, and never brought back where no screen shows it. */
class AppWindowPlacementTest {

    @Test
    fun the_bounds_round_trip_through_the_placement_row_maximized_included() {
        val bounds = AppWindowBounds(x = 40f, y = 60f, width = 1200f, height = 800f, maximized = true)
        assertEquals(bounds, AppWindowBounds.of(bounds.toPlacement()))
        assertEquals(bounds.copy(maximized = false), AppWindowBounds.of(bounds.copy(maximized = false).toPlacement()))
    }

    @Test
    fun a_row_with_no_size_or_no_row_is_no_saved_window() {
        assertNull(AppWindowBounds.of(null))
        assertNull(AppWindowBounds.of(WindowPlacement(x = 10f, y = 10f, visible = true)))
    }

    /** The anomaly: built, maximized at once (never moved, never resized), rebuilt — and not maximized. */
    @Test
    fun a_window_maximized_before_it_was_ever_seen_floating_is_still_kept_maximized() {
        val screen = Rectangle(0, 0, 1920, 1080)
        val kept = settledAppWindowBounds(normal = null, maximized = true, floating = null, screen = screen)
        assertEquals(AppWindowBounds(560f, 240f, 800f, 600f, maximized = true), kept)
    }

    @Test
    fun a_floating_window_is_kept_where_the_os_window_stands_and_a_maximize_keeps_those_bounds_under_it() {
        val floating = settledAppWindowBounds(null, maximized = false, floating = Rectangle(100, 50, 1200, 800), screen = null)
        assertEquals(AppWindowBounds(100f, 50f, 1200f, 800f, maximized = false), floating)
        val maximized = settledAppWindowBounds(floating, maximized = true, floating = null, screen = Rectangle(0, 0, 1920, 1080))
        assertEquals(AppWindowBounds(100f, 50f, 1200f, 800f, maximized = true), maximized)
    }

    @Test
    fun a_window_is_placed_back_only_where_a_screen_shows_its_title_bar() {
        val laptop = Rectangle(0, 0, 1920, 1080)
        val external = Rectangle(1920, 0, 2560, 1440)
        val onExternal = AppWindowBounds(x = 2200f, y = 100f, width = 1200f, height = 800f, maximized = false)
        assertTrue(onExternal.reachableOn(listOf(laptop, external)))
        // The external screen was unplugged since.
        assertFalse(onExternal.reachableOn(listOf(laptop)))
        // Hanging mostly off the left edge, but with a grabbable stretch of its title bar still showing.
        assertTrue(AppWindowBounds(x = -1000f, y = 0f, width = 1200f, height = 800f, maximized = false).reachableOn(listOf(laptop)))
        // Its title bar above the top of every screen.
        assertFalse(AppWindowBounds(x = 100f, y = -500f, width = 1200f, height = 800f, maximized = false).reachableOn(listOf(laptop)))
    }
}
