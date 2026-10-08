package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.stickyOffsetPx

/**
 * User rule 2026-10-08: "When expanded, the expand button is always visible on the right even if the user scrolls on
 * the action section to read the whole text." The button is moved down its line by what is scrolled away above.
 */
class HistoryTextLineTest {
    @Test
    fun the_button_follows_the_part_of_the_text_on_screen_and_stops_at_its_end() {
        // The whole line on screen: where it is.
        assertEquals(0, stickyOffsetPx(hiddenAbovePx = 0f, lineHeightPx = 900, controlHeightPx = 30))
        // 400 px of the text scrolled away above: 400 px down, at the top of what is left.
        assertEquals(400, stickyOffsetPx(400f, 900, 30))
        // Nearly all of it scrolled away: at the line's last 30 px, never below the text it belongs to.
        assertEquals(870, stickyOffsetPx(890f, 900, 30))
        assertEquals(870, stickyOffsetPx(5000f, 900, 30))
        // A line no taller than its button, and a reading from above the screen: it does not move.
        assertEquals(0, stickyOffsetPx(12f, 20, 30))
        assertEquals(0, stickyOffsetPx(-3f, 900, 30))
    }
}
