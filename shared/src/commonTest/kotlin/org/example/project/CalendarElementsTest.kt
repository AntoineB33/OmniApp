package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import org.example.project.scheduler.domain.CalendarElements
import org.example.project.scheduler.domain.CalendarElements.sharedValue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskId
import org.example.project.ui.PlacedRecord
import org.example.project.ui.calendarElementDrafts

/**
 * PRD §8: the calendar's ONE add/edit window — *a set of things, and their configuration grouped by who
 * shares it.*
 *
 * Four rules, and they are the whole of the window's layout:
 *  - **the shared section is the fields EVERY element owns**, and it is asked once;
 *  - **the rest is partitioned by OWNER SET**, not by element — a section is "the fields exactly these
 *    elements have", so one element appears in as many sections as it has distinct owner sets;
 *  - **a value the elements disagree about is MIXED** and shows nothing, so a Save nobody typed into cannot
 *    silently collapse three starts onto one;
 *  - **a bound mode is offered only where every element in the section can express it** — "∞" and "now" are
 *    a period's sentences and mean nothing about a ring or a tag.
 *
 * The last block is the other side of the same window: which of the things drawn at a point are ELEMENTS of
 * it at all ([calendarElementDrafts]) — and the three that deliberately are not.
 */
class CalendarElementsTest {

    private fun panel(name: String = "write", start: Long = 0L, end: Long = 3_600_000L) =
        CalendarElements.Draft(
            kind = CalendarElements.Kind.TaskPanel,
            name = name,
            startMillis = start,
            endMillis = end,
            taskId = TaskId("t1"),
        )

    private fun period(start: Long = 0L, end: Long = 3_600_000L) =
        CalendarElements.Draft(
            kind = CalendarElements.Kind.RestrictivePeriod,
            name = "Inactivity",
            startMillis = start,
            endMillis = end,
            periodKind = PeriodKinds.INACTIVITY,
        )

    private fun reminder(start: Long = 0L) =
        CalendarElements.Draft(
            kind = CalendarElements.Kind.Reminder,
            name = "water the plants",
            startMillis = start,
            endMillis = start,
        )

    private fun alarm(start: Long = 0L) =
        CalendarElements.Draft(
            kind = CalendarElements.Kind.Alarm,
            name = "wake up",
            startMillis = start,
            endMillis = start + 3_000L,
        )

    // ----- the grouping ------------------------------------------------------------------------

    @Test
    fun oneElementPutsEveryFieldInTheSharedSection() {
        val sections = CalendarElements.calendarConfigSections(listOf(panel()))
        assertEquals(1, sections.size)
        assertTrue(sections.single().sharedByAll)
        // A window of one reads exactly like the single-object editor it replaces: one untitled section.
        assertEquals("", sections.single().title)
        assertEquals(CalendarElements.fieldsOf(CalendarElements.Kind.TaskPanel), sections.single().fields)
    }

    @Test
    fun theSharedSectionIsWhatEveryElementOwns() {
        // A panel, a period, an alarm and a tag: only `Start` is owned by all four (a tag has no end).
        val sections =
            CalendarElements.calendarConfigSections(listOf(panel(), period(), alarm(), reminder()))
        val shared = sections.single { it.sharedByAll }
        assertEquals(listOf(CalendarElements.Field.Start), shared.fields)
        assertEquals(4, shared.indices.size)
    }

    @Test
    fun endIsSharedByTheThreeKindsThatHaveOne() {
        val drafts = listOf(panel(), period(), alarm(), reminder())
        val endSection = CalendarElements.calendarConfigSections(drafts)
            .single { CalendarElements.Field.End in it.fields }
        // Not the shared section — the reminder has no end, so `End` belongs to a group of three.
        assertFalse(endSection.sharedByAll)
        assertEquals(listOf(0, 1, 2), endSection.indices)
        assertEquals(
            "write (task) · Inactivity (restrictive period) · wake up (alarm)",
            endSection.title,
        )
    }

    @Test
    fun fieldsWithTheSameOwnersShareOneSection() {
        // Two panels and a period: `Pins` and `Screen` have the SAME owners (the two panels), so they are
        // one section and not two — "grouped by sharing as much as possible".
        val sections = CalendarElements.calendarConfigSections(listOf(panel("a"), panel("b"), period()))
        val panelOnly = sections.single { CalendarElements.Field.Pins in it.fields }
        assertEquals(
            listOf(CalendarElements.Field.Pins, CalendarElements.Field.Screen),
            panelOnly.fields,
        )
        assertEquals(listOf(0, 1), panelOnly.indices)
    }

    @Test
    fun anElementBelongsToEverySectionItOwnsAFieldOf() {
        // The panel shares `Start`/`End` with the period and `Pins`/`Screen` with nobody — so it is in two
        // sections. A partition of the ELEMENTS could not express that, which is why the partition is of
        // the fields.
        val drafts = listOf(panel(), period())
        val sections = CalendarElements.calendarConfigSections(drafts)
        assertEquals(2, sections.size)
        assertEquals(listOf(CalendarElements.Field.Start, CalendarElements.Field.End), sections[0].fields)
        assertTrue(sections[0].sharedByAll)
        assertEquals(listOf(0), sections[1].indices)
    }

    @Test
    fun sectionsAreOrderedSharedFirstThenByHowManyShare() {
        // Three panels and a reminder: the shared `Start` first, then `End`+`Pins`+`Screen` (three owners).
        val drafts = listOf(panel("a"), panel("b"), panel("c"), reminder())
        val sections = CalendarElements.calendarConfigSections(drafts)
        assertTrue(sections.first().sharedByAll)
        assertEquals(listOf(CalendarElements.Field.Start), sections.first().fields)
        assertEquals(listOf(0, 1, 2), sections[1].indices)
        assertEquals(listOf(3), sections[2].indices)
    }

    @Test
    fun emptyListHasNoSections() {
        assertEquals(emptyList(), CalendarElements.calendarConfigSections(emptyList()))
    }

    // ----- mixed values ------------------------------------------------------------------------

    @Test
    fun disagreeingElementsShowNothing() {
        val drafts = listOf(panel(start = 0L), panel(start = 60_000L))
        val shared = CalendarElements.calendarConfigSections(drafts).single { it.sharedByAll }
        // The bug this exists to stop: a field seeded with the FIRST value would have moved the second one
        // to it on a Save the user never typed into.
        assertNull(shared.sharedValue { it.startMillis })
        assertEquals(3_600_000L, shared.sharedValue { it.endMillis })
    }

    @Test
    fun anUntouchedWindowWritesBackWhatItRead() {
        val drafts = listOf(panel(start = 0L), panel(start = 60_000L))
        val shared = CalendarElements.calendarConfigSections(drafts).single { it.sharedByAll }
        // Nothing is copied across the section until an edit does it, so the drafts are unchanged.
        assertEquals(drafts, CalendarElements.applyToSection(drafts, shared) { it })
    }

    @Test
    fun anEditOfASharedFieldReachesEveryOwnerAndNobodyElse() {
        val drafts = listOf(panel(start = 0L), panel(start = 60_000L), reminder(start = 99L))
        val panelSection =
            CalendarElements.calendarConfigSections(drafts).single { CalendarElements.Field.Pins in it.fields }
        val after = CalendarElements.applyToSection(drafts, panelSection) { it.copy(pins = PanelPins(position = true)) }
        assertTrue(after[0].pins.position)
        assertTrue(after[1].pins.position)
        assertFalse(after[2].pins.position)
    }

    @Test
    fun twoEqualDraftsAreStillTwoElements() {
        // Two fresh panels of one task are equal by value; a write aimed at the section must not be matched
        // by value, or one row's edit would land on both. The section carries INDICES for that reason.
        val drafts = listOf(panel(), panel())
        val section = CalendarElements.calendarConfigSections(drafts).single { it.sharedByAll }
        assertEquals(listOf(0, 1), section.indices)
    }

    // ----- bound modes -------------------------------------------------------------------------

    @Test
    fun onlyAPeriodOffersNowAndInfinity() {
        assertEquals(
            setOf(CalendarElements.Bound.At, CalendarElements.Bound.Now, CalendarElements.Bound.Infinite),
            CalendarElements.offeredBounds(listOf(period(), period())),
        )
        // Mixed with anything else, only an explicit instant survives: "∞ → now" is a sentence about a
        // period, and there is no such sentence about a ring or a tag.
        assertEquals(
            setOf(CalendarElements.Bound.At),
            CalendarElements.offeredBounds(listOf(period(), panel())),
        )
        assertEquals(emptySet(), CalendarElements.offeredBounds(emptyList()))
    }

    // ----- an alarm's start is a time of day ---------------------------------------------------

    @Test
    fun anAlarmStartIsWrittenBackAsATimeOfDay() {
        val dayStart = 1_600_000_000_000L
        val start = dayStart + (7 * 60 + 30) * 60_000L
        assertEquals(7 * 60 + 30, CalendarElements.alarmTimeOfDayMinutes(start, dayStart))
        // A start on the NEXT day is still that day's clock time — an alarm has no date to be moved to.
        assertEquals(
            7 * 60 + 30,
            CalendarElements.alarmTimeOfDayMinutes(start + 24 * 3_600_000L, dayStart),
        )
    }

    @Test
    fun anAlarmEndIsItsRingLength() {
        val start = 1_600_000_000_000L
        assertEquals(
            AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS,
            CalendarElements.alarmSoundSeconds(start, start + AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS * 1000L),
        )
        // A ring is never zero-length — a silenced alarm is what the *Armed* switch says, not a ring of 0 s.
        assertEquals(1, CalendarElements.alarmSoundSeconds(start, start))
        assertEquals(
            AlarmEntry.MAX_ALARM_SOUND_SECONDS,
            CalendarElements.alarmSoundSeconds(start, start + 24 * 3_600_000L),
        )
    }

    @Test
    fun anAlarmsWeekdaysAreAFieldOfTheirOwn() {
        // The `Rings on` set is asked beside the start and never derived from it — which is the reason a
        // start dragged onto another day does not quietly add that weekday.
        val drafts = listOf(alarm().copy(alarmDays = setOf(DayOfWeek.MONDAY)))
        val section = CalendarElements.calendarConfigSections(drafts).single()
        assertTrue(CalendarElements.Field.AlarmDays in section.fields)
        assertEquals(setOf(DayOfWeek.MONDAY), section.sharedValue { it.alarmDays })
    }

    // ----- what is an element of the window at all ---------------------------------------------

    private fun hit(
        title: String = "x",
        entryId: String? = "panel/1",
        taskId: TaskId? = TaskId("t1"),
        reminder: Boolean = false,
        alarm: Boolean = false,
        timer: Boolean = false,
        sleep: Boolean = false,
        screenBreak: Boolean = false,
        layer: SchedulerDomain.ActivityLayer? = null,
        restrictiveKind: String = "",
        start: Long = 0L,
        end: Long = 3_600_000L,
    ) = PlacedRecord(
        title = title,
        startHour = 0f,
        endHour = 1f,
        scheduled = false,
        entryId = entryId,
        taskId = taskId,
        reminder = reminder,
        alarm = alarm,
        timer = timer,
        sleep = sleep,
        screenBreak = screenBreak,
        layer = layer,
        restrictiveKind = restrictiveKind,
        fullStartMillis = start,
        fullEndMillis = end,
    )

    @Test
    fun thePanelPeriodReminderAndAlarmAreElements() {
        val drafts =
            calendarElementDrafts(
                listOf(
                    hit(title = "write"),
                    hit(entryId = "panel/2", taskId = null, restrictiveKind = PeriodKinds.INACTIVITY),
                    hit(entryId = "chore-manual/reminder-3/7", taskId = null, reminder = true),
                    hit(entryId = "alarm-2", taskId = null, alarm = true),
                ),
            )
        assertEquals(
            listOf(
                CalendarElements.Kind.TaskPanel,
                CalendarElements.Kind.RestrictivePeriod,
                CalendarElements.Kind.Reminder,
                CalendarElements.Kind.Alarm,
            ),
            drafts.map { it.kind },
        )
        // PRD §14: the tag's panel id carries the reminder it is an occurrence of.
        assertEquals("reminder-3", drafts[2].reminderId)
        // PRD §18: the marker's id IS the alarm's, so the window edits the rule with no second lookup.
        assertEquals("alarm-2", drafts[3].existingId)
    }

    @Test
    fun aTimerScreenBreakAndLayerAreNotElements() {
        val drafts =
            calendarElementDrafts(
                listOf(
                    hit(entryId = "timer-1", taskId = null, alarm = true, timer = true),
                    hit(entryId = null, taskId = null, screenBreak = true, restrictiveKind = PeriodKinds.NO_SCREEN),
                    hit(entryId = null, taskId = null, layer = SchedulerDomain.ActivityLayer.NoComputerUnlocked),
                ),
            )
        assertEquals(emptyList(), drafts)
    }

    @Test
    fun aDerivedPeriodIsAnElementWithNothingBehindIt() {
        // A past Inactivity band has no panel, so saving it is what MATERIALIZES it — which is exactly what
        // the period editor's Save always did.
        val drafts =
            calendarElementDrafts(
                listOf(hit(entryId = null, taskId = null, restrictiveKind = PeriodKinds.INACTIVITY)),
            )
        assertEquals(CalendarElements.Kind.RestrictivePeriod, drafts.single().kind)
        assertNull(drafts.single().existingId)
    }

    @Test
    fun aSleepBandIsAPeriodLikeAnyOther() {
        // The §17 SCHEDULE behind it is not an element (it is not something the calendar lays), but the band
        // itself is a `sleep` period and is edited as one.
        val drafts =
            calendarElementDrafts(
                listOf(hit(entryId = "panel/9", taskId = null, sleep = true, restrictiveKind = PeriodKinds.SLEEP)),
            )
        assertEquals(CalendarElements.Kind.RestrictivePeriod, drafts.single().kind)
        assertEquals(PeriodKinds.SLEEP, drafts.single().periodKind)
    }

    @Test
    fun anOpenEndedPeriodComesBackOnTheInfiniteBound() {
        val drafts =
            calendarElementDrafts(
                listOf(
                    hit(
                        entryId = "panel/3",
                        taskId = null,
                        restrictiveKind = PeriodKinds.INACTIVITY,
                        start = SchedulerDomain.OPEN_PAST_MILLIS,
                        end = SchedulerDomain.OPEN_FUTURE_MILLIS,
                    ),
                ),
            )
        assertEquals(CalendarElements.Bound.Infinite, drafts.single().startBound)
        assertEquals(CalendarElements.Bound.Infinite, drafts.single().endBound)
    }
}
