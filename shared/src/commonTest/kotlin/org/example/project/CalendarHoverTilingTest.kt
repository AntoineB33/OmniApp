package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.ui.BubbleOverlay
import org.example.project.ui.CalendarBubbleSection
import org.example.project.ui.CalendarBubbleSection.Kind
import org.example.project.ui.bubbleHoverZones

/**
 * PRD §8 hover: the calendar tiles an element into sub-ranges of constant section stack, one hover reporter
 * each — nesting two reporters at one point makes them race (the parent's Move overwrites the child's).
 *
 * A resize edge is made of that same tiling. The grab strip on a panel's top/bottom edge (and the whole of
 * an Overlap-Mode width handle) needs a tile of its OWN to hang the resize cursor on, because a cursor-only
 * Box laid over the element is still a pointer-input node: it wins the hit test, the tile beneath it stops
 * receiving Enter/Move, and the bubble blinks out on exactly the edge the user is aiming at. So the strip is
 * cut out of the element's own tiling (`extraCuts`) rather than covered by a second layer — which is only
 * safe if a cut never costs a tile its sections. That is what these pin.
 */
class CalendarHoverTilingTest {

    private fun section(kind: Kind) = CalendarBubbleSection(kind, kind.name)

    private fun kindsOf(zone: org.example.project.ui.BubbleHoverZone) = zone.sections.map { it.kind }

    @Test
    fun a_resize_edge_cut_keeps_every_section_on_both_sides_of_it() {
        // A task panel spanning 9:00–11:00, cut at its two 6-dp grab strips (here 0.1 h each).
        val zones = bubbleHoverZones(
            top = 9f,
            bottom = 11f,
            overlays = listOf(BubbleOverlay(9f, 11f, section(Kind.Task))),
            extraCuts = listOf(9.1f, 10.9f),
        )
        assertEquals(listOf(9f to 9.1f, 9.1f to 10.9f, 10.9f to 11f), zones.map { it.top to it.bottom })
        // The whole point: the strip that carries the cursor still names the panel.
        assertTrue(zones.all { kindsOf(it) == listOf(Kind.Task) })
    }

    @Test
    fun a_cut_keeps_the_stack_the_element_is_drawn_inside() {
        // The grab strip of a task inside a sleep window, under the "no computer unlocked" layer: the stack
        // the bubble draws is the whole stack there, edge or no edge.
        val zones = bubbleHoverZones(
            top = 0f,
            bottom = 1f,
            overlays = listOf(
                BubbleOverlay(0f, 1f, section(Kind.Task)),
                BubbleOverlay(0f, 1f, section(Kind.Sleep)),
                BubbleOverlay(0f, 1f, section(Kind.NoComputerUnlocked)),
            ),
            extraCuts = listOf(0.05f),
        )
        assertEquals(2, zones.size)
        assertTrue(
            zones.all { kindsOf(it) == listOf(Kind.Task, Kind.Sleep, Kind.NoComputerUnlocked) },
        )
    }

    @Test
    fun a_cut_outside_the_span_changes_nothing() {
        // A slice too short for its own grab strip caps it at a third of the height, and an interior slice
        // asks for no strip at all — so a cut may land on (or outside) the element's own bounds.
        val overlays = listOf(BubbleOverlay(2f, 3f, section(Kind.Break)))
        val plain = bubbleHoverZones(2f, 3f, overlays)
        val cut = bubbleHoverZones(2f, 3f, overlays, extraCuts = listOf(1f, 2f, 3f, 4f))
        assertEquals(plain.map { it.top to it.bottom }, cut.map { it.top to it.bottom })
        assertEquals(listOf(Kind.Break), kindsOf(cut.single()))
    }

    @Test
    fun a_zero_height_band_still_reports_with_a_cut_asked_for() {
        // A sub-minute look-away is drawn at a coerced minimum height, so its tiling span can collapse; the
        // band must stay hoverable, and asking for a resize strip on it must not empty it.
        val zones = bubbleHoverZones(
            top = 5f,
            bottom = 5f,
            overlays = listOf(BubbleOverlay(5f, 5f, section(Kind.Break))),
            extraCuts = listOf(5f),
        )
        assertEquals(listOf(Kind.Break), kindsOf(zones.single()))
    }
}
