package org.example.project

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.ui.WindowChrome
import org.example.project.ui.WindowFill
import org.example.project.ui.WindowFrameState

/**
 * `docs/invariants/popups.md`: the whole of a window's geometry — the head's drag, the three resizable
 * edges, the two fill buttons, maximize, reduce — is [WindowFrameState]'s arithmetic, so it is checked here
 * without a UI. Every window in the app wears the same frame, so a rule broken here is broken everywhere.
 */
class WindowFrameStateTest {

    private fun state(width: Float = 400f, height: Float = 300f) =
        WindowFrameState("test", Offset.Zero, Size(width, height))

    // ----- moving -------------------------------------------------------------------------------------

    @Test
    fun `dragging the head moves the window`() {
        val s = state()
        s.moveBy(Offset(30f, -20f))
        assertEquals(Offset(30f, -20f), s.offset)
        assertEquals(Offset(30f, -20f), s.appliedOffset)
    }

    // ----- the three resizable edges ------------------------------------------------------------------
    //
    // The window is drawn centred on its offset, so growing it by `d` on ONE edge has to move the centre by
    // `d/2` — that is what keeps the opposite edge where the user left it, which is the whole reason for
    // grabbing this edge rather than that one.

    @Test
    fun `the right edge grows the window and leaves the left edge where it was`() {
        val s = state(width = 400f)
        s.resizeRightBy(60f)
        assertEquals(460f, s.size.width)
        assertEquals(30f, s.offset.x)
    }

    @Test
    fun `the left edge grows the window and leaves the right edge where it was`() {
        val s = state(width = 400f)
        s.resizeLeftBy(-60f)
        assertEquals(460f, s.size.width)
        assertEquals(-30f, s.offset.x)
    }

    @Test
    fun `the bottom edge grows the window and leaves the head where it was`() {
        val s = state(height = 300f)
        s.resizeBottomBy(80f)
        assertEquals(380f, s.size.height)
        assertEquals(40f, s.offset.y)
    }

    @Test
    fun `a resize clamped at the minimum does not slide the window sideways`() {
        // The offset moves by the width ACTUALLY applied. Moving it by the raw drag instead would walk the
        // window across the screen every time the user kept pulling at a window already at its minimum.
        val s = state(width = 400f)
        val floor = s.minWidth
        s.resizeRightBy(-10_000f)
        assertEquals(floor, s.size.width)
        assertEquals(-(400f - floor) / 2f, s.offset.x)

        val before = s.offset.x
        s.resizeRightBy(-10_000f)
        assertEquals(floor, s.size.width)
        assertEquals(before, s.offset.x)
    }

    @Test
    fun `a window is never resized below its minimum on either axis`() {
        val s = state(width = 400f, height = 300f)
        s.resizeLeftBy(10_000f)
        s.resizeBottomBy(-10_000f)
        assertEquals(s.minWidth, s.size.width)
        assertEquals(s.minHeight, s.size.height)
    }

    // ----- fill / maximize ----------------------------------------------------------------------------

    @Test
    fun `maximize is both axes filled, and the two fill buttons reach the same state`() {
        val a = state()
        a.toggleMaximize()
        assertEquals(WindowFill.Both, a.fill)
        assertTrue(a.maximized)

        val b = state()
        b.setFillWidth(true)
        b.setFillHeight(true)
        assertEquals(WindowFill.Both, b.fill)
        assertTrue(b.maximized)
    }

    @Test
    fun `a filled axis is pinned to the container, so its drag offset is not applied`() {
        val s = state()
        s.moveBy(Offset(40f, 25f))
        s.setFillWidth(true)
        assertEquals(Offset(0f, 25f), s.appliedOffset)
        // …and the offset itself is not forgotten, which is what lets un-filling put the window back.
        assertEquals(Offset(40f, 25f), s.offset)
    }

    @Test
    fun `un-maximizing restores the size and position the window had before`() {
        val s = state(width = 400f, height = 300f)
        s.moveBy(Offset(40f, 25f))
        s.resizeRightBy(60f) // width 460, offset.x 70
        val offsetBefore = s.offset
        val sizeBefore = s.size

        s.toggleMaximize()
        s.toggleMaximize()

        assertEquals(WindowFill.None, s.fill)
        assertEquals(offsetBefore, s.offset)
        assertEquals(sizeBefore, s.size)
    }

    @Test
    fun `releasing one fill axis restores only that axis`() {
        val s = state(width = 400f, height = 300f)
        s.moveBy(Offset(40f, 25f))
        s.toggleMaximize()
        s.setFillWidth(false)

        assertEquals(WindowFill.Height, s.fill)
        assertEquals(400f, s.size.width)
        assertEquals(40f, s.offset.x)
        // The height is still filled, so its offset stays unapplied.
        assertEquals(Offset(40f, 0f), s.appliedOffset)
    }

    @Test
    fun `a filled axis ignores the head drag and its own resize edge`() {
        val s = state(width = 400f, height = 300f)
        s.setFillWidth(true)
        s.moveBy(Offset(50f, 12f))
        s.resizeLeftBy(-40f)
        s.resizeRightBy(40f)

        assertEquals(400f, s.size.width)
        assertEquals(0f, s.offset.x)
        // …while the free axis still moves and still resizes.
        assertEquals(12f, s.offset.y)
        s.resizeBottomBy(20f)
        assertEquals(320f, s.size.height)
    }

    // ----- reduce -------------------------------------------------------------------------------------

    @Test
    fun `reduce and restore leave the window exactly as it was, filled axes included`() {
        val s = state()
        s.moveBy(Offset(15f, 15f))
        s.setFillHeight(true)
        s.minimize()
        assertTrue(s.minimized)

        s.restore()
        assertFalse(s.minimized)
        assertEquals(WindowFill.Height, s.fill)
        assertEquals(Offset(15f, 15f), s.offset)
    }

    // ----- the first layout pass ----------------------------------------------------------------------

    @Test
    fun `the measured size is adopted for an axis that has none yet`() {
        val s = WindowFrameState("test")
        s.measured(Size(360f, 240f))
        assertEquals(Size(360f, 240f), s.size)
        // …and is not adopted again once the window has a size of its own.
        s.resizeRightBy(40f)
        s.measured(Size(1f, 1f))
        assertEquals(400f, s.size.width)
    }

    @Test
    fun `a filled axis never adopts what it measures`() {
        // What a filled axis measures is the CONTAINER, not the window; adopting it would make un-filling
        // a no-op and the window would be stuck at the container's size.
        val s = WindowFrameState("test")
        s.setFillWidth(true)
        s.measured(Size(1920f, 240f))
        assertEquals(0f, s.size.width)
        assertEquals(240f, s.size.height)

        s.setFillWidth(false)
        s.measured(Size(360f, 240f))
        assertEquals(360f, s.size.width)
    }

    // ----- the head stays reachable -------------------------------------------------------------------

    @Test
    fun `a window dragged off the top is nudged back until its head is visible`() {
        val s = state(width = 400f, height = 300f)
        s.moveBy(Offset(0f, -10_000f))
        s.clampVertical(containerHeight = 800f, windowHeight = 300f, headHeight = 36f)
        // Resting top is (800 - 300) / 2 = 250, so the offset that puts the head's top at 0 is -250.
        assertEquals(-250f, s.offset.y)
    }

    @Test
    fun `a window dragged off the bottom keeps its head on the last visible row`() {
        val s = state(width = 400f, height = 300f)
        s.moveBy(Offset(0f, 10_000f))
        s.clampVertical(containerHeight = 800f, windowHeight = 300f, headHeight = 36f)
        // The head's top may reach 800 - 36 = 764, i.e. an offset of 764 - 250 = 514.
        assertEquals(514f, s.offset.y)
    }

    @Test
    fun `an over-tall window is nudged down until its head is visible`() {
        // The case the clamp was written for: a fixed-size window on a screen shorter than it, centred, has
        // its head above the top edge — and the head is the only thing that can move it or close it.
        val s = state(width = 400f, height = 1000f)
        s.clampVertical(containerHeight = 600f, windowHeight = 1000f, headHeight = 36f)
        // Resting top is (600 - 1000) / 2 = -200, so the offset that brings the head's top to 0 is +200.
        assertEquals(200f, s.offset.y)
    }

    @Test
    fun `a window already inside the container is left alone`() {
        val s = state(width = 400f, height = 300f)
        s.moveBy(Offset(0f, 30f))
        s.clampVertical(containerHeight = 800f, windowHeight = 300f, headHeight = 36f)
        assertEquals(30f, s.offset.y)
    }

    @Test
    fun `the clamp does nothing before the sizes are known, or on a filled axis`() {
        val s = state()
        s.moveBy(Offset(0f, -10_000f))
        s.clampVertical(containerHeight = 0f, windowHeight = 300f, headHeight = 36f)
        assertEquals(-10_000f, s.offset.y)

        s.setFillHeight(true)
        s.clampVertical(containerHeight = 800f, windowHeight = 300f, headHeight = 36f)
        assertEquals(-10_000f, s.offset.y)
    }

    // ----- coming back as it was left (WindowChromeMemory) ----------------------------------------------

    @Test
    fun `a window left full width comes back full width and un-fills to its normal geometry`() {
        // What the placement row keeps: the NORMAL geometry, and the chrome state on top of it.
        val s =
            WindowFrameState(
                "test", Offset(40f, -25f), Size(400f, 300f),
                initialChrome = WindowChrome(WindowFill.Width, minimized = false),
            )
        assertEquals(WindowFill.Width, s.fill)
        assertEquals(Offset(0f, -25f), s.appliedOffset, "the filled axis is pinned to the container")
        s.setFillWidth(false)
        assertEquals(Offset(40f, -25f), s.appliedOffset)
        assertEquals(Size(400f, 300f), s.size)
    }

    @Test
    fun `a window left reduced comes back reduced and restores as it was`() {
        val s =
            WindowFrameState(
                "test", Offset.Zero, Size(400f, 300f),
                initialChrome = WindowChrome(WindowFill.Both, minimized = true),
            )
        assertTrue(s.minimized)
        assertTrue(s.maximized)
        s.restore()
        assertFalse(s.minimized)
        assertEquals(WindowChrome(WindowFill.Both, minimized = false), s.chrome)
    }
}
