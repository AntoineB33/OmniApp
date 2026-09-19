package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.PeriodDrawing
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.PeriodKindStyle
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.ui.CalendarBubbleSection
import org.example.project.ui.CalendarBubbleSection.Kind
import org.example.project.ui.PlacedRecord
import org.example.project.ui.alarmBubbleSection
import org.example.project.ui.bubbleTimeRange
import org.example.project.ui.companionBubbleSections
import org.example.project.ui.orderedBubbleSections
import org.example.project.ui.reminderBubbleSection

/**
 * PRD §8 hover bubble. The calendar deliberately draws its elements across each other — a task inside a
 * sleep window, a screen break over that task, and the two "nobody unlocked" LAYERS hatched over all of it
 * — so a hover reports a STACK of sections rather than one element's title.
 *
 * These tests pin the user's two rules for that stack:
 *
 * 1. the order, top to bottom:
 *    `reminder > alarm/timer ring > task = break > inactivity = sleep > no computer unlocked = no phone
 *    unlocked`;
 * 2. **when there is a break, there can't be a task**.
 *
 * The two ZERO-DURATION MARKERS lead the order — a §14 reminder tag and a §18 alarm/timer ring — because
 * they are the top-most things the column draws: each is what the cursor is actually on, and each hides the
 * panel and the layers under it, which stack below it in the bubble. The tag outranks the ring, being drawn
 * over it.
 */
class CalendarBubbleSectionTest {

    private fun section(kind: Kind) = CalendarBubbleSection(kind, kind.name)

    private fun kindsOf(vararg kinds: Kind) =
        orderedBubbleSections(kinds.map(::section)).map { it.kind }

    // ----- a period's companions ------------------------------------------------------------------------

    @Test
    fun a_sleep_window_names_its_no_screen_companion_over_its_own_span() {
        // The anomaly: hovering a sleep window named "Sleep" only. Its "No screen" line hung on pause
        // EVIDENCE enclosing the band (an inactivity gap, passed by position into the wrong parameter), so a
        // night with none — every future one — named nothing but the sleep. The companion is a period in
        // force wherever the sleep is, and the bubble names it with the sleep's own times.
        assertEquals(
            listOf(CalendarBubbleSection(Kind.NoScreen, "No screen", "23:00:00 – 07:00:00")),
            companionBubbleSections(PeriodKinds.SLEEP, PeriodKindConfig.DEFAULT, "23:00:00 – 07:00:00"),
        )
        // The wind-down hour carries one too.
        assertEquals(
            listOf(Kind.NoScreen),
            companionBubbleSections(PeriodKinds.BEFORE_BED, PeriodKindConfig.DEFAULT, "x").map { it.kind },
        )
    }

    @Test
    fun a_companion_the_account_removed_is_not_named_and_a_layer_is_left_to_its_band() {
        assertEquals(emptyList(), companionBubbleSections(PeriodKinds.INACTIVITY, PeriodKindConfig.DEFAULT, "x"))
        val noCompanion =
            PeriodKindConfig(mapOf(PeriodKinds.SLEEP to PeriodKindStyle(emptySet(), PeriodDrawing.HorizontalLines)))
        assertEquals(emptyList(), companionBubbleSections(PeriodKinds.SLEEP, noCompanion, "x"))
        // A layer companion is named by the layer band (which unions it with the OS evidence), not twice.
        val layers =
            PeriodKindConfig(
                mapOf(
                    PeriodKinds.SLEEP to
                        PeriodKindStyle(
                            setOf(PeriodKinds.NO_SCREEN, PeriodKinds.NO_PHONE_UNLOCKED),
                            PeriodDrawing.HorizontalLines,
                        ),
                ),
            )
        assertEquals(
            listOf(Kind.NoScreen),
            companionBubbleSections(PeriodKinds.SLEEP, layers, "x").map { it.kind },
        )
    }

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
    fun a_reminder_leads_the_stack_and_everything_it_covers_follows() {
        // Hovering a reminder tag: the reminder first, then the panel it is drawn over and the layers
        // hatched across that panel. The tag hides all of them, so it owes the bubble all of them.
        assertEquals(
            listOf(Kind.Reminder, Kind.Task, Kind.Sleep, Kind.NoComputerUnlocked),
            kindsOf(Kind.NoComputerUnlocked, Kind.Task, Kind.Sleep, Kind.Reminder),
        )
    }

    @Test
    fun a_reminder_outranks_a_break_too() {
        // The other element drawn over the panels. Only the reminder tags go above a screen-break band, so
        // when both are true at the cursor the reminder is named first — and the break still drops the task.
        assertEquals(
            listOf(Kind.Reminder, Kind.Break, Kind.Inactivity),
            kindsOf(Kind.Task, Kind.Break, Kind.Reminder, Kind.Inactivity),
        )
    }

    @Test
    fun a_reminder_alone_is_a_stack_of_one() {
        // A tag over empty grid — the common case, and the one the whole feature is for.
        assertEquals(listOf(Kind.Reminder), kindsOf(Kind.Reminder))
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
        // Everything below the break still stacks under it — including the OTHER kinds of its own rank a
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

    // ----- a reminder's own section --------------------------------------------------------------------

    @Test
    fun a_reminder_section_names_the_time_the_reminder_is_for() {
        // Not where the tag is DRAWN: an overdue one is parked on the now-line and a checked one is frozen
        // at the moment it was ticked off, and the user hovering it is asking when the reminder is for.
        // A reminder has no duration, so the line is one time and not a range.
        val tz = TimeZone.UTC
        val due = Instant.parse("2026-09-06T09:30:20Z").toEpochMilliseconds()
        val tag = PlacedRecord(
            title = "Take the pills",
            startHour = 14f,
            endHour = 14f,
            scheduled = false,
            reminder = true,
            checked = true,
            checkedAtMillis = Instant.parse("2026-09-06T14:00:00Z").toEpochMilliseconds(),
            fullStartMillis = due,
            fullEndMillis = due,
        )
        val section = reminderBubbleSection(tag, tz)
        assertEquals(Kind.Reminder, section.kind)
        assertEquals("Take the pills", section.title)
        assertEquals("09:30:20", section.times)
    }

    // ----- a ring's own section ------------------------------------------------------------------------

    @Test
    fun a_ring_leads_the_stack_over_what_it_hides() {
        // Reported as: hovering a timer's end named the task panel under it and never the timer. A ring is
        // INERT — it registers no click — so nothing about drawing it forced the omission to show.
        assertEquals(
            listOf(Kind.Alarm, Kind.Task, Kind.Sleep, Kind.NoPhoneUnlocked),
            kindsOf(Kind.NoPhoneUnlocked, Kind.Task, Kind.Sleep, Kind.Alarm),
        )
    }

    @Test
    fun a_reminder_outranks_a_ring_and_a_ring_outranks_a_break() {
        // Draw order read back out of the bubble: a tag goes over a break band, which goes over a ring —
        // and the ring, being the instant the cursor is pointing at, still comes above the spans below it.
        assertEquals(
            listOf(Kind.Reminder, Kind.Alarm, Kind.Break, Kind.Inactivity),
            kindsOf(Kind.Inactivity, Kind.Break, Kind.Alarm, Kind.Reminder),
        )
    }

    @Test
    fun a_ring_section_names_the_instant_it_goes_off_at() {
        // Not where the marker is DRAWN: coinciding rings stack downward, so a marker can sit below its own
        // time, and "when does this go off" is the whole of what hovering it asks.
        val tz = TimeZone.UTC
        val due = Instant.parse("2026-09-10T16:45:07Z").toEpochMilliseconds()
        val section = alarmBubbleSection(ring("5:00", due, timer = true), tz)
        assertEquals(Kind.Alarm, section.kind)
        assertEquals("16:45:07", section.times)
    }

    @Test
    fun the_icon_is_what_tells_a_timer_from_an_alarm_in_the_bubble_too() {
        // The marker's icon is the ONLY thing that tells the two apart (the labels fall back to a duration
        // and a time of day, which are not reliably distinguishable), so the bubble carries it rather than
        // naming a ring less precisely than the marker it stands in for.
        val tz = TimeZone.UTC
        val due = Instant.parse("2026-09-10T16:45:00Z").toEpochMilliseconds()
        assertEquals("⏳ Tea", alarmBubbleSection(ring("Tea", due, timer = true), tz).title)
        assertEquals("⏰ Wake up", alarmBubbleSection(ring("Wake up", due, timer = false), tz).title)
    }

    // ----- the times, to the second --------------------------------------------------------------------

    @Test
    fun a_section_names_its_times_to_the_second() {
        // The bubble is the one place that answers "when exactly is this". Truncated to the minute, a 20s
        // look-away (§15) reads as an empty range and two abutting derived bands read as overlapping.
        val tz = TimeZone.UTC
        val start = Instant.parse("2026-09-10T11:04:38Z").toEpochMilliseconds()
        val end = Instant.parse("2026-09-10T11:04:58Z").toEpochMilliseconds()
        assertEquals("11:04:38 – 11:04:58", bubbleTimeRange(start, end, tz))
    }

    @Test
    fun an_open_ended_side_is_infinity_and_the_other_side_still_carries_its_seconds() {
        // PRD §12: an ∞ end is the absence of a time, not a time formatted differently — so adding the
        // seconds must not turn either open side into a printed clock.
        val tz = TimeZone.UTC
        val start = Instant.parse("2026-09-10T07:15:09Z").toEpochMilliseconds()
        assertEquals(
            "∞ – 07:15:09",
            bubbleTimeRange(SchedulerDomain.OPEN_PAST_MILLIS, start, tz),
        )
        assertEquals(
            "07:15:09 – ∞",
            bubbleTimeRange(start, SchedulerDomain.OPEN_FUTURE_MILLIS, tz),
        )
        assertEquals("∞ – 07:15:09", bubbleTimeRange(start, start, tz, openStart = true))
    }

    private fun ring(title: String, dueMillis: Long, timer: Boolean) =
        PlacedRecord(
            title = title,
            startHour = 16.75f,
            endHour = 16.75f,
            scheduled = false,
            alarm = true,
            timer = timer,
            fullStartMillis = dueMillis,
            fullEndMillis = dueMillis,
        )

    @Test
    fun the_title_and_times_of_each_section_are_carried_through() {
        val ordered =
            orderedBubbleSections(
                listOf(
                    CalendarBubbleSection(
                        Kind.NoComputerUnlocked,
                        "No computer unlocked",
                        "08:00:00 – 09:00:00",
                    ),
                    CalendarBubbleSection(Kind.Task, "Write the report", "08:30:00 – 08:45:00"),
                ),
            )
        assertEquals(listOf("Write the report", "No computer unlocked"), ordered.map { it.title })
        assertEquals(listOf("08:30:00 – 08:45:00", "08:00:00 – 09:00:00"), ordered.map { it.times })
    }
}
