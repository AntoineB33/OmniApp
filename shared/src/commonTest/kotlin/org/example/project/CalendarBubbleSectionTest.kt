package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.CalendarBubbleSection
import org.example.project.ui.CalendarBubbleSection.Kind
import org.example.project.ui.orderedBubbleSections

/**
 * PRD §8 hover bubble. The calendar deliberately draws its elements across each other — a task inside a
 * sleep window, a screen break over that task, and the two "nobody unlocked" LAYERS hatched over all of it
 * — so a hover reports a STACK of sections rather than one element's title.
 *
 * These tests pin the user's two rules for that stack:
 *
 * 1. the order, top to bottom: `task = break > inactivity = sleep > no computer unlocked = no phone unlocked`;
 * 2. **when there is a break, there can't be a task**.
 */
class CalendarBubbleSectionTest {

    private fun section(kind: Kind) = CalendarBubbleSection(kind, kind.name)

    private fun kindsOf(vararg kinds: Kind) =
        orderedBubbleSections(kinds.map(::section)).map { it.kind }

    // ----- the ordering --------------------------------------------------------------------------------

    @Test
    fun a_layer_is_a_section_of_the_bubble() {
        // The point of the whole stack: hovering a layer must NAME it. A layer is a non-interactive overlay,
        // so its section rides whatever the cursor is over — here a task in a sleep window with both
        // "nobody unlocked" hatches across it.
        assertEquals(
            listOf(Kind.Task, Kind.Sleep, Kind.NoComputerUnlocked, Kind.NoPhoneUnlocked),
            kindsOf(Kind.NoComputerUnlocked, Kind.NoPhoneUnlocked, Kind.Sleep, Kind.Task),
        )
    }

    @Test
    fun sections_are_ordered_task_then_grey_period_then_layers() {
        assertEquals(
            listOf(Kind.Task, Kind.Inactivity, Kind.NoComputerUnlocked),
            kindsOf(Kind.NoComputerUnlocked, Kind.Inactivity, Kind.Task),
        )
    }

    @Test
    fun equal_ranks_keep_the_order_they_were_collected_in() {
        // "inactivity = sleep" and "no computer unlocked = no phone unlocked" are ties, not a second
        // ordering: whichever the calendar collected first stays first (a sleep band's own "No screen" line
        // still follows the band, and the two layers keep the column's order).
        assertEquals(
            listOf(Kind.Sleep, Kind.NoScreen, Kind.NoPhoneUnlocked, Kind.NoComputerUnlocked),
            kindsOf(Kind.NoPhoneUnlocked, Kind.Sleep, Kind.NoComputerUnlocked, Kind.NoScreen),
        )
    }

    @Test
    fun a_repeated_section_is_shown_once() {
        // Two overlapping layer regions of the same kind cover one instant; the bubble is not a tally.
        assertEquals(listOf(Kind.NoPhoneUnlocked), kindsOf(Kind.NoPhoneUnlocked, Kind.NoPhoneUnlocked))
    }

    // ----- a break excludes the task -------------------------------------------------------------------

    @Test
    fun when_there_is_a_break_there_is_no_task() {
        // A §15 screen break SUSPENDS the chunk it lands in rather than cutting it, so the task's panel
        // really does span the break — but the user is not on that task during it.
        assertEquals(
            listOf(Kind.Break, Kind.Sleep, Kind.NoComputerUnlocked),
            kindsOf(Kind.Task, Kind.Break, Kind.Sleep, Kind.NoComputerUnlocked),
        )
    }

    @Test
    fun a_break_drops_only_the_task_section() {
        // Everything below the top rank still stacks under the break — including the OTHER rank-0 kinds a
        // break can legitimately coincide with (a user-authored "No screen" period is not a task).
        assertEquals(
            listOf(Kind.Break, Kind.NoScreen, Kind.Inactivity, Kind.NoPhoneUnlocked),
            kindsOf(Kind.NoPhoneUnlocked, Kind.NoScreen, Kind.Break, Kind.Task, Kind.Inactivity),
        )
    }

    @Test
    fun a_task_alone_is_untouched() {
        assertEquals(listOf(Kind.Task), kindsOf(Kind.Task))
    }

    @Test
    fun the_title_and_times_of_each_section_are_carried_through() {
        val ordered =
            orderedBubbleSections(
                listOf(
                    CalendarBubbleSection(Kind.NoComputerUnlocked, "No computer unlocked", "08:00 – 09:00"),
                    CalendarBubbleSection(Kind.Task, "Write the report", "08:30 – 08:45"),
                ),
            )
        assertEquals(listOf("Write the report", "No computer unlocked"), ordered.map { it.title })
        assertEquals(listOf("08:30 – 08:45", "08:00 – 09:00"), ordered.map { it.times })
    }
}
