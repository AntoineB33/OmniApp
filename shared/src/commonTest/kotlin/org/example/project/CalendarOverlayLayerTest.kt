package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.BubbleOverlay
import org.example.project.ui.CalendarBubbleSection
import org.example.project.ui.CalendarBubbleSection.Kind
import org.example.project.ui.CalendarOverlayLayer
import org.example.project.ui.orderedBubbleSections
import org.example.project.ui.overlaysUnder

/**
 * PRD §8: the three things a day column draws OVER its panels, and the ONE declaration that says which is on
 * top of which — [CalendarOverlayLayer].
 *
 * It answers two questions at once, and the bug these pin is what happens when they are answered separately:
 * the EMISSION order in the column (what is composed last is painted and hit-tested on top) and what each
 * element owes the hover bubble (everything it hides). The 2026-09-04 z-order fix lifted the §14 reminder
 * tags to the top and left the §18 alarm/timer rings under the §15 screen-break bands, while
 * [CalendarBubbleSection.Kind] went on ranking a ring ABOVE a break. A ring is a fixed height whatever the
 * zoom, so zoomed out its marker is minutes wide and a 20-second look-away landing inside it was painted
 * straight across the ring the bubble claimed was on top of it.
 *
 * A ring is inert — there is no click to lose, the way a tag's was — so nothing but the paint said so.
 */
class CalendarOverlayLayerTest {

    private fun overlay(kind: Kind) = BubbleOverlay(0f, 1f, CalendarBubbleSection(kind, kind.name))

    private val breaks = listOf(overlay(Kind.Break))
    private val rings = listOf(overlay(Kind.Alarm))
    private val panels = listOf(overlay(Kind.Task), overlay(Kind.Sleep))

    private fun under(layer: CalendarOverlayLayer) =
        overlaysUnder(layer, breaks, rings, panels).map { it.section.kind }

    @Test
    fun a_break_band_stacks_the_panels_and_nothing_else() {
        // The regression: it used to stack the rings, which are now drawn OVER it.
        assertEquals(listOf(Kind.Task, Kind.Sleep), under(CalendarOverlayLayer.ScreenBreak))
    }

    @Test
    fun a_ring_stacks_the_bands_it_is_now_drawn_over() {
        // The other half of the same regression: a ring named the task panel under it and never the break
        // that was painted across it.
        assertEquals(listOf(Kind.Break, Kind.Task, Kind.Sleep), under(CalendarOverlayLayer.Ring))
    }

    @Test
    fun a_reminder_tag_stacks_both_markers_below_it() {
        assertEquals(
            listOf(Kind.Alarm, Kind.Break, Kind.Task, Kind.Sleep),
            under(CalendarOverlayLayer.Tag),
        )
    }

    @Test
    fun no_element_stacks_itself_or_anything_drawn_above_it() {
        // The whole rule, read off the declaration rather than off three hand-written lists: a layer carries
        // exactly the layers below it. Adding a fourth cannot quietly keep the old answers.
        val own = mapOf(
            CalendarOverlayLayer.ScreenBreak to Kind.Break,
            CalendarOverlayLayer.Ring to Kind.Alarm,
            CalendarOverlayLayer.Tag to Kind.Reminder,
        )
        CalendarOverlayLayer.entries.forEach { layer ->
            val stacked = under(layer)
            CalendarOverlayLayer.entries.forEach { other ->
                val present = own.getValue(other) in stacked
                assertEquals(other < layer, present, "$layer stacking $other")
            }
        }
    }

    @Test
    fun the_paint_order_and_the_bubble_order_are_one_answer() {
        // What a tag hides, ordered as the bubble draws it: the two zero-duration markers lead, the ring
        // above the break it is painted over. If the two ever disagree again, this is where it shows.
        val hovered = listOf(CalendarBubbleSection(Kind.Reminder, "Reminder")) +
            overlaysUnder(CalendarOverlayLayer.Tag, breaks, rings, panels).map { it.section }
        assertEquals(
            listOf(Kind.Reminder, Kind.Alarm, Kind.Break, Kind.Sleep),
            orderedBubbleSections(hovered).map { it.kind },
        )
    }
}
