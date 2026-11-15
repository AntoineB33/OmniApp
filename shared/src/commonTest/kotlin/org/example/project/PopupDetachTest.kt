package org.example.project

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.WindowFrameState
import org.example.project.ui.frameOffsetAt

/**
 * User rule 2026-11-15: a pop-up dragged by its head becomes a window where it was let go
 * ([org.example.project.ui.PopupDetacher]). A window frame keeps its offset from its centred resting place in the
 * content area ([frameOffsetAt]).
 */
class PopupDetachTest {
    @Test
    fun the_window_stands_where_the_pop_up_stood() {
        val area = Rect(200f, 40f, 1200f, 840f)
        val size = Size(300f, 400f)
        // Resting place: centred in the area, top-left (550, 240).
        assertEquals(Offset.Zero, frameOffsetAt(Offset(550f, 240f), size, area))
        assertEquals(Offset(-350f, -200f), frameOffsetAt(Offset(200f, 40f), size, area), "the area's top-left corner")
        // A frame given that offset draws its top-left there: area origin + resting place + offset.
        val frame = WindowFrameState("ConfigSearch", frameOffsetAt(Offset(900f, 500f), size, area), size)
        val restingLeft = area.left + (area.width - size.width) / 2f
        val restingTop = area.top + (area.height - size.height) / 2f
        assertEquals(Offset(900f, 500f), Offset(restingLeft + frame.offset.x, restingTop + frame.offset.y))
    }
}
