package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.atStartOfDayIn
import org.example.project.scheduler.domain.PatternExceptions
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.PanelRepeat
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_input_requirements.md`, its last paragraph: *"When an orange outlined block/chip becomes blue
 * outlined, that means that the configuration that created this orange outlined block/chip gets an 'exception' info
 * (e.g., timer every day at 10h, with an exception for the occurrence of tomorrow that got dragged 1h later)."*
 *
 * Anomaly 2026-10-11: *"I modified the occurrence of 'Sleep' at 11/10/2026, but I don't see the exception info."* —
 * the exception was stored (the schedule skipped the night) and said nowhere. Each configuration that lays orange
 * blocks now lists its exceptions, with where each went ([PatternExceptions]).
 */
class PatternExceptionsTest {
    private val tz = TimeZone.currentSystemDefault()
    private val hour = 3_600_000L
    private val day = 24 * hour
    // Far ahead of any clock the test may run under: every occurrence touched is in the future.
    private val from = 2_000_000_000_000L - 2_000_000_000_000L % day

    private fun r(s: SchedulerState, i: SchedulerIntent) = SchedulerReducer.reduce(s, i)

    @Test
    fun a_night_edited_out_of_the_sleep_schedule_is_an_exception_the_schedule_says_with_where_it_went() {
        val s = SchedulerState.empty().copy(sleep = SleepSchedule())
        assertEquals(emptyList(), PatternExceptions.ofSleep(s, tz))
        val nights = SchedulerDomain.sleepPanels(s.sleep, from, from + 10 * day, tz)
        val night = nights[2]
        val was = TaskTimeRange(night.startEpochMillis, night.endEpochMillis)

        // Dragged an hour later: the schedule's one exception, and it reads where the night is now.
        val moved =
            r(s, SchedulerIntent.PlaceDerivedPeriod(PeriodKinds.SLEEP, was.startEpochMillis, was.endEpochMillis, was.startEpochMillis + hour, was.endEpochMillis + hour))
        val one = PatternExceptions.ofSleep(moved, tz).single()
        assertEquals(was, one.occurrence)
        assertEquals(TaskTimeRange(was.startEpochMillis + hour, was.endEpochMillis + hour), one.movedTo)
        val line = PatternExceptions.texts(listOf(one), tz).single()
        assertTrue("→" in line && line.endsWith("(1 h later)"), line)
        // User rule 2026-10-11: "The exception block must be displayed the same way an occurence block is displayed
        // under 'Blocks on the calendar'" — so the exception names the very block that list holds.
        val shown = assertNotNull(SearchDomain.calendarBlockOf(moved, assertNotNull(one.blockId), tz))
        assertEquals(one.movedTo, TaskTimeRange(shown.startMillis, shown.endMillis))
        assertEquals(PeriodKinds.periodTitle(PeriodKinds.SLEEP), shown.name)

        // The block edited again: the exception follows it (the link is the block's, not the gesture's).
        val block = moved.panels.single { it.patternOrigin == night.id }
        val again = r(moved, SchedulerIntent.UpdateTaskPanel(block.id, block.taskId, block.title, block.startEpochMillis - 3 * hour, block.endEpochMillis, block.pins))
        assertEquals(was.startEpochMillis - 2 * hour, PatternExceptions.ofSleep(again, tz).single().movedTo?.startEpochMillis)
        assertTrue(PatternExceptions.texts(PatternExceptions.ofSleep(again, tz), tz).single().endsWith("(2 h earlier)"))

        // Removed from the calendar, the occurrence stays an exception — "removed" — and so does a night removed outright.
        val gone = again.copy(panels = again.panels.filterNot { it.patternOrigin == night.id })
        assertNull(PatternExceptions.ofSleep(gone, tz).single().movedTo)
        assertNull(PatternExceptions.ofSleep(gone, tz).single().blockId)
        assertTrue(PatternExceptions.texts(PatternExceptions.ofSleep(gone, tz), tz).single().endsWith("→ removed"))
        val other = nights[4]
        var cut = moved
        val owners = SearchDomain.resolve(moved, listOf("RestrictivePeriod/" + PeriodKinds.SLEEP))
        val listed = SearchDomain.blocksOfAdded(moved, owners, tz, from, from + 10 * day).single { it.id == other.id }
        SearchDomain.blockRemoveIntents(moved, listOf(listed), from, tz).forEach { cut = r(cut, it) }
        val two = PatternExceptions.ofSleep(cut, tz)
        assertEquals(2, two.size)
        assertEquals(listOf(true, false), two.map { it.movedTo != null }, "in the timeline's order: the moved night, then the removed one")

        // The hour before bed is the schedule's too: said beside the nights, each under its name.
        val evening = SchedulerDomain.beforeBedPanels(s.sleep, from, from + 10 * day, tz)[3]
        val both =
            r(moved, SchedulerIntent.PlaceDerivedPeriod(PeriodKinds.BEFORE_BED, evening.startEpochMillis, evening.endEpochMillis, evening.startEpochMillis - hour / 2, evening.endEpochMillis - hour / 2))
        val lines = PatternExceptions.texts(PatternExceptions.ofSleep(both, tz), tz)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith(PeriodKinds.periodTitle(PeriodKinds.SLEEP)) && lines[1].startsWith(PeriodKinds.periodTitle(PeriodKinds.BEFORE_BED)), lines.toString())
        assertTrue(lines[1].endsWith("(30 min earlier)"), lines[1])
    }

    @Test
    fun an_exception_made_before_the_link_was_stored_still_says_where_the_night_is() {
        val s = SchedulerState.empty().copy(sleep = SleepSchedule())
        val night = SchedulerDomain.sleepPanels(s.sleep, from, from + 10 * day, tz)[2]
        val moved =
            r(s, SchedulerIntent.PlaceDerivedPeriod(PeriodKinds.SLEEP, night.startEpochMillis, night.endEpochMillis, night.startEpochMillis + hour, night.endEpochMillis))
        // What the previous build left: the same period, with no link.
        val legacy = moved.copy(panels = moved.panels.map { it.copy(patternOrigin = "") })
        assertEquals(night.startEpochMillis + hour, PatternExceptions.ofSleep(legacy, tz).single().movedTo?.startEpochMillis)
    }

    @Test
    fun an_occurrence_edited_out_of_a_repeating_panel_is_an_exception_of_that_panel() {
        val base =
            TaskPanel(
                "panel/7", null, "No screen", from + 10 * hour, from + 11 * hour, noScreen = true, periodKind = PeriodKinds.NO_SCREEN,
                pins = PanelPins(existence = true), repeat = PanelRepeat(1),
            )
        val s = SchedulerState.empty().copy(panels = listOf(base), nextPanelCounter = 8)
        assertEquals(emptyList(), PatternExceptions.ofPanel(s, "panel/7", tz))
        val owners = SearchDomain.resolve(s, listOf("RestrictivePeriod/" + PeriodKinds.NO_SCREEN))
        val third = SearchDomain.blocksOfAdded(s, owners, tz, from, from + 7 * day)[3]
        val moved = r(s, assertNotNull(SearchDomain.blockEditIntent(s, third, third.startMillis + hour, third.endMillis + hour, from, tz)))
        // Asked of the panel, or of any of its occurrences: the pattern's exception, where it went.
        for (id in listOf("panel/7", third.id)) {
            val one = PatternExceptions.ofPanel(moved, id, tz).single()
            assertEquals(TaskTimeRange(third.startMillis, third.endMillis), one.occurrence)
            assertEquals(TaskTimeRange(third.startMillis + hour, third.endMillis + hour), one.movedTo)
        }
        // The block that left is a block of its own: it has no pattern, so no exception of its own.
        val own = moved.panels.single { it.patternOrigin == third.id }
        assertEquals(emptyList(), PatternExceptions.ofPanel(moved, own.id, tz))
    }

    @Test
    fun a_ring_dragged_out_of_an_alarm_is_an_exception_of_that_alarm() {
        val dayNumber = from / day + 3
        val rule = AlarmEntry(id = "alarm/1", label = "Wake", timeOfDayMinutes = 10 * 60, skippedEpochDays = setOf(dayNumber, dayNumber + 2))
        val ring = AlarmEntry(id = "alarm/2", label = "Wake", timeOfDayMinutes = 11 * 60, onlyOnEpochDay = dayNumber, isolatedFrom = "alarm/1")
        val s = SchedulerState.empty().copy(alarms = listOf(rule, ring))
        val lines = PatternExceptions.texts(PatternExceptions.ofAlarm(s, "alarm/1", tz), tz)
        assertEquals(2, lines.size)
        // The requirement's own example: "an exception for the occurrence … that got dragged 1h later".
        assertTrue(lines[0].endsWith("10:00 → 11:00 (1 h later)"), lines[0])
        assertTrue(lines[1].endsWith("10:00 → removed"), lines[1])
        val ringBlock = assertNotNull(PatternExceptions.ofAlarm(s, "alarm/1", tz).first().blockId)
        assertEquals("Wake", assertNotNull(SearchDomain.calendarBlockOf(s, ringBlock, tz)).name)
        assertEquals(emptyList(), PatternExceptions.ofAlarm(s, "alarm/2", tz))
    }

    @Test
    fun the_links_are_stored_and_a_payload_written_before_them_still_loads() {
        val s = SchedulerState.empty().copy(sleep = SleepSchedule())
        val night = SchedulerDomain.sleepPanels(s.sleep, from, from + 10 * day, tz)[2]
        val moved =
            r(s, SchedulerIntent.PlaceDerivedPeriod(PeriodKinds.SLEEP, night.startEpochMillis, night.endEpochMillis, night.startEpochMillis + hour, night.endEpochMillis + hour))
                .copy(alarms = listOf(AlarmEntry(id = "alarm/2", timeOfDayMinutes = 60, onlyOnEpochDay = 3, isolatedFrom = "alarm/1")))
        val text = SchedulerStateCodec.encode(moved)
        val back = assertNotNull(SchedulerStateCodec.decode(text))
        assertEquals(night.id, back.panels.single { it.restrictiveKind == PeriodKinds.SLEEP }.patternOrigin)
        assertEquals("alarm/1", back.alarms.single().isolatedFrom)
        assertEquals(PatternExceptions.ofSleep(moved, tz), PatternExceptions.ofSleep(back, tz))

        // The previous shape: the same payload without the two fields.
        val older = text.replace(",\"patternOrigin\":\"" + night.id + "\"", "").replace(",\"isolatedFrom\":\"alarm/1\"", "")
        assertTrue("patternOrigin" !in older && "isolatedFrom" !in older, "the fields were not where the test strips them")
        val loaded = assertNotNull(SchedulerStateCodec.decode(older))
        assertEquals("", loaded.panels.single { it.restrictiveKind == PeriodKinds.SLEEP }.patternOrigin)
        assertEquals("", loaded.alarms.single().isolatedFrom)
        // …and its exception is still said, by where the period stands.
        assertEquals(night.startEpochMillis + hour, PatternExceptions.ofSleep(loaded, tz).single().movedTo?.startEpochMillis)
    }

    /**
     * Anomaly 2026-10-11: *"I added the reminder 'Claude (if already walked)', selected one block, changed 14:50 to
     * 14:51, but I didn't see an exception info under the Every 1 day configuration."*
     */
    @Test
    fun a_reminder_tag_moved_off_its_cadence_is_an_exception_of_that_reminder() {
        val chore = org.example.project.scheduler.model.ChoreEntry(title = "Walk", spanDays = 1.0, timeOfDayMinutes = 14 * 60 + 50, id = "reminder-7")
        val today = kotlinx.datetime.Instant.fromEpochMilliseconds(from).toLocalDateTime(tz).date.atStartOfDayIn(tz).toEpochMilliseconds()
        val base = SchedulerState.empty().copy(chores = listOf(chore))
        val s = base.copy(panels = SchedulerDomain.regenerateChorePanels(base.panels, base.chores, today, 7, today))
        assertEquals(emptyList(), PatternExceptions.ofReminder(s, "reminder-7", tz))
        // The Search window's "Blocks on the calendar": one tag, its time changed by a minute.
        val owners = SearchDomain.resolve(s, listOf("Reminder/reminder-7"))
        val tag = SearchDomain.blocksOfAdded(s, owners, tz, today, today + 7 * day)[2]
        val moved = r(s, assertNotNull(SearchDomain.blockEditIntent(s, tag, tag.startMillis + 60_000L, tag.startMillis + 60_000L, today, tz)))
        val one = PatternExceptions.ofReminder(moved, "reminder-7", tz).single()
        assertEquals(tag.startMillis, one.occurrence.startEpochMillis)
        assertEquals(tag.startMillis + 60_000L, one.movedTo?.startEpochMillis)
        val line = PatternExceptions.texts(listOf(one), tz).single()
        assertTrue(line.endsWith("14:50 → 14:51 (1 min later)"), line)
        // Its block is the tag itself, and its fields edit it: 14:52 written there is where the exception reads next.
        val shown = assertNotNull(SearchDomain.calendarBlockOf(moved, assertNotNull(one.blockId), tz))
        assertEquals(tag.startMillis + 60_000L, shown.startMillis)
        val edited = r(moved, assertNotNull(SearchDomain.blockEditIntent(moved, shown, shown.startMillis + 60_000L, shown.startMillis + 60_000L, today, tz)))
        assertEquals(tag.startMillis + 120_000L, PatternExceptions.ofReminder(edited, "reminder-7", tz).single().movedTo?.startEpochMillis)
        // It is still the reminder's after the tags are laid again, and no other reminder's.
        val again = moved.copy(panels = SchedulerDomain.regenerateChorePanels(moved.panels, moved.chores, today, 7, today))
        assertEquals(listOf(one), PatternExceptions.ofReminder(again, "reminder-7", tz))
        assertEquals(emptyList(), PatternExceptions.ofReminder(again, "reminder-8", tz))
        // The release account's own tag (probed 2026-10-11): pinned a minute off the cadence under an id of the form
        // written before the ids named a day. It is that day's occurrence, moved.
        val legacy =
            TaskPanel("chore/reminder-7/0", null, "Walk", tag.startMillis - 21 * day + 60_000L, tag.startMillis - 21 * day + 60_000L, chore = true, checked = true)
                .copy(pins = PanelPins(existence = true))
        val old = s.copy(panels = s.panels + legacy.copy(pinned = true))
        val was = PatternExceptions.ofReminder(old, "reminder-7", tz).single()
        assertEquals(tag.startMillis - 21 * day, was.occurrence.startEpochMillis)
        assertTrue(PatternExceptions.texts(listOf(was), tz).single().endsWith("14:50 → 14:51 (1 min later)"))
    }
}
