package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §14 Reminders: a standalone vertical list of rows (title + recurrence in days + time of day) that
 * the reminder scheduler turns into recurring **zero-duration, checkable calendar tags**. Covers the
 * testable core — the [SchedulerIntent.SetChores] mutation + persistence, that reminders are NOT
 * obstacles to the §9 scheduler, that checking a reminder is an undoable Calendar History Unit, that a
 * checked state survives regeneration, and the now-line accumulation query for overdue reminders. The
 * floating window / its rendering as tags + checkboxes on the now-line are Compose UI and not unit-tested.
 */
class SchedulerChoresTest {

    @Test
    fun chores_default_to_an_empty_list() {
        assertTrue(SchedulerState.empty().chores.isEmpty())
    }

    @Test
    fun set_chores_stores_the_list_with_floating_point_day_spans() {
        val entries = listOf(ChoreEntry("Water plants", 3.5), ChoreEntry("Vacuum", 7.0))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries))
        assertEquals(entries, s.chores)
    }

    @Test
    fun set_chores_replaces_the_whole_list() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(listOf(ChoreEntry("A", 1.0))))
        val replacement = listOf(ChoreEntry("B", 2.0), ChoreEntry("C", 0.25))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetChores(replacement))
        assertEquals(replacement, s.chores)
    }

    @Test
    fun set_chores_can_clear_the_list() {
        val seeded = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(listOf(ChoreEntry("A", 1.0))))
        val cleared = SchedulerReducer.reduce(seeded, SchedulerIntent.SetChores(emptyList()))
        assertTrue(cleared.chores.isEmpty())
    }

    @Test
    fun set_chores_with_the_same_list_is_a_no_op() {
        val entries = listOf(ChoreEntry("A", 1.0))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries))
        val again = SchedulerReducer.reduce(s, SchedulerIntent.SetChores(entries))
        assertEquals(s, again)
    }

    @Test
    fun editing_chores_is_not_part_of_the_tree_undo_history() {
        // PRD §14/§6: the reminders list lives outside the Task Tree snapshot, so Ctrl+Z must not revert it
        // (mirrors the §7 automatic-schedule switch / the task record).
        val s0 = SchedulerState.empty()
        val withChores = SchedulerReducer.reduce(s0, SchedulerIntent.SetChores(listOf(ChoreEntry("A", 1.0))))
        val undone = SchedulerReducer.reduce(withChores, SchedulerIntent.Undo)
        assertEquals(listOf(ChoreEntry("A", 1.0)), undone.chores)
    }

    @Test
    fun codec_round_trip_preserves_the_chores_list() {
        val entries = listOf(ChoreEntry("Water plants", 3.5), ChoreEntry("Bins out", 14.0))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(entries, decoded.chores)
    }

    // ----- §14 reminder scheduler: recurrence day offsets -------------------------------------

    private val DAY = 24L * 60 * 60 * 1000
    private val HOUR = 3_600_000L
    private val MIN = 60_000L

    @Test
    fun day_offsets_for_an_integer_cadence_step_evenly_within_the_horizon() {
        assertEquals(listOf(0, 7, 14, 21, 28), SchedulerDomain.choreOccurrenceDayOffsets(7.0, horizonDays = 28))
        assertEquals(listOf(0, 10, 20, 30), SchedulerDomain.choreOccurrenceDayOffsets(10.0, horizonDays = 30))
    }

    @Test
    fun day_offsets_for_a_blank_recurrence_are_just_today() {
        // PRD §14: a blank / non-positive recurrence collapses to a single tag today (a one-off).
        assertEquals(listOf(0), SchedulerDomain.choreOccurrenceDayOffsets(0.0))
        assertEquals(listOf(0), SchedulerDomain.choreOccurrenceDayOffsets(-3.0))
    }

    @Test
    fun day_offsets_for_a_sub_day_recurrence_are_every_day() {
        // PRD §14: a value < 1 happens every day (e.g. 0.5 → alternate? no — daily, placed at its time).
        assertEquals((0..28).toList(), SchedulerDomain.choreOccurrenceDayOffsets(0.5, horizonDays = 28))
        assertEquals((0..10).toList(), SchedulerDomain.choreOccurrenceDayOffsets(0.1, horizonDays = 10))
        // A cadence of exactly 1 day is also every day.
        assertEquals((0..28).toList(), SchedulerDomain.choreOccurrenceDayOffsets(1.0))
    }

    @Test
    fun day_offsets_for_a_fraction_hit_the_exact_count_in_the_smallest_window() {
        // PRD §14: 31/21 ≈ 1.476 → exactly 21 occurrences across any 31-day window (the smallest exact one).
        val span = 31.0 / 21.0
        val within31 = SchedulerDomain.choreOccurrenceDayOffsets(span, horizonDays = 30)
        assertEquals(21, within31.size)
        assertTrue(within31.all { it in 0..30 })
        // Two alternate-day reminders (cadence 2) are exactly every other day.
        assertEquals(listOf(0, 2, 4, 6, 8, 10), SchedulerDomain.choreOccurrenceDayOffsets(2.0, horizonDays = 10))
    }

    @Test
    fun day_formula_evaluates_arithmetic_to_the_recurrence_value() {
        assertEquals(31.0 / 21.0, SchedulerDomain.evaluateDayFormula("31/21")!!, 1e-12)
        assertEquals(0.5, SchedulerDomain.evaluateDayFormula("1/2")!!, 1e-12)
        assertEquals(14.0, SchedulerDomain.evaluateDayFormula("7*2")!!, 1e-12)
        assertEquals(3.5, SchedulerDomain.evaluateDayFormula(" 2 + 3 / 2 ")!!, 1e-12) // precedence: 2 + 1.5
        assertEquals(10.0, SchedulerDomain.evaluateDayFormula("(2+3)*2")!!, 1e-12)
        assertEquals(7.0, SchedulerDomain.evaluateDayFormula("7")!!, 1e-12)
        // Blank / malformed / non-finite → null (the caller treats it as 0 → a one-off).
        assertNull(SchedulerDomain.evaluateDayFormula(""))
        assertNull(SchedulerDomain.evaluateDayFormula("7/"))
        assertNull(SchedulerDomain.evaluateDayFormula("1+*2"))
        assertNull(SchedulerDomain.evaluateDayFormula("1/0"))
    }

    @Test
    fun reminder_with_no_time_of_day_is_placed_at_the_current_time() {
        val today = 1_000_000_000_000L
        val now = today + (13 * 60 + 37) * MIN // 13:37
        val chores = listOf(ChoreEntry("Stretch", spanDays = 7.0, timeOfDayMinutes = -1))
        val tags = SchedulerDomain.choreScheduledPanels(chores, todayStartMillis = today, nowMillis = now)
        assertEquals(now, tags.first().startEpochMillis) // placed at the current time-of-day, not midnight
    }

    @Test
    fun day_offsets_for_a_fractional_cadence_accumulate_without_drifting() {
        val offsets = SchedulerDomain.choreOccurrenceDayOffsets(2.5, horizonDays = 20)
        assertEquals(0, offsets.first()) // PRD §14: counter starts at today (offset 0)
        for (i in 1 until offsets.size) assertTrue(offsets[i] > offsets[i - 1])
        assertTrue(offsets.all { it in 0..20 })
        val avgGap = offsets.last().toDouble() / (offsets.size - 1)
        assertTrue(avgGap in 2.3..2.7, "average gap $avgGap should hover around the 2.5 cadence")
    }

    // ----- §14 reminder scheduler: tag generation ---------------------------------------------

    @Test
    fun reminders_are_zero_duration_checkable_tags_at_the_time_of_day() {
        val today = 1_000_000_000_000L
        val chores = listOf(ChoreEntry("Water plants", spanDays = 7.0, timeOfDayMinutes = 9 * 60))
        val tags = SchedulerDomain.choreScheduledPanels(chores, todayStartMillis = today, horizonDays = 28)

        assertEquals(5, tags.size) // offsets 0,7,14,21,28
        val first = tags.first()
        assertEquals(today + 9 * 60 * 60_000L, first.startEpochMillis) // 09:00 today
        assertEquals(first.startEpochMillis, first.endEpochMillis) // zero duration: a tag, not a panel
        assertTrue(tags.all { it.chore && !it.pinned && !it.checked && it.taskId == null })
        assertTrue(tags.all { it.title == "Water plants" })
        assertEquals(today + 7 * DAY + 9 * 60 * 60_000L, tags[1].startEpochMillis) // recurs every 7 days
    }

    @Test
    fun reminder_scheduler_skips_blank_titles_only_and_treats_no_recurrence_as_one_off_today() {
        val today = 1_000_000_000_000L
        val chores = listOf(
            ChoreEntry("", spanDays = 7.0, timeOfDayMinutes = 0), // blank → skipped
            ChoreEntry("One-off", spanDays = 0.0, timeOfDayMinutes = 0), // no recurrence → just today
            ChoreEntry("Weekly", spanDays = 7.0, timeOfDayMinutes = 60),
        )
        val tags = SchedulerDomain.choreScheduledPanels(chores, todayStartMillis = today)
        assertTrue(tags.none { it.title.isBlank() }) // blank-titled reminder skipped
        // A reminder with no recurrence appears exactly once, today.
        val oneOff = tags.filter { it.title == "One-off" }
        assertEquals(1, oneOff.size)
        assertEquals(today, oneOff.single().startEpochMillis)
        // The weekly one still recurs (multiple occurrences).
        assertTrue(tags.count { it.title == "Weekly" } > 1)
    }

    // ----- §14 reminders are NOT obstacles to the §9 task scheduler ---------------------------

    @Test
    fun fill_schedule_keeps_a_reminder_tag_but_flows_straight_through_it() {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val now = 1_000_000_000_000L
        // A reminder tag sits 2h ahead; unlike the old 5-min chore panel it must NOT block or shorten the
        // auto fill (PRD §14: no spanning time → not an obstacle), but it must still be kept.
        val reminder = TaskPanel("chore/0/0", null, "Reminder", now + 2 * HOUR, now + 2 * HOUR, chore = true)
        s = s.copy(panels = listOf(reminder))

        val panels = SchedulerDomain.fillSchedule(s, now)

        assertTrue(reminder in panels) // tag survives the fill
        val autos = panels.filter { it.auto }
        // The sole task fills the whole window as one continuous block — the tag did not split it.
        assertEquals(1, autos.size, "reminder must not break the auto fill, got ${autos.size} auto panels")
        assertEquals(now, autos[0].startEpochMillis)
        assertTrue(autos[0].startEpochMillis < reminder.startEpochMillis && reminder.startEpochMillis < autos[0].endEpochMillis)
    }

    // ----- §14 checking a reminder off (Calendar History Unit) --------------------------------

    @Test
    fun checking_a_reminder_is_an_undoable_calendar_history_unit() {
        val now = 1_000_000_000_000L
        val reminder = TaskPanel("chore/0/0", null, "Weekly", now - HOUR, now - HOUR, chore = true)
        val s0 = SchedulerState.empty().copy(panels = listOf(reminder)).let {
            SchedulerReducer.reduce(it, SchedulerIntent.SetCalendarFocus(true))
        }

        val checked = SchedulerReducer.reduce(s0, SchedulerIntent.SetReminderChecked("chore/0/0", true))
        assertTrue(checked.panels.single { it.id == "chore/0/0" }.checked)

        // PRD §14 "saved in a History Unit": undoable while the calendar is focused.
        val undone = SchedulerReducer.reduce(checked, SchedulerIntent.Undo)
        assertFalse(undone.panels.single { it.id == "chore/0/0" }.checked)
    }

    @Test
    fun checking_a_non_reminder_or_a_redundant_check_is_a_no_op() {
        val now = 1_000_000_000_000L
        val task = TaskPanel("auto/0", TaskId("t"), "Task", now, now + HOUR, auto = true)
        val reminder = TaskPanel("chore/0/0", null, "Weekly", now - HOUR, now - HOUR, chore = true, checked = true)
        val s = SchedulerState.empty().copy(panels = listOf(task, reminder))

        assertEquals(s, SchedulerReducer.reduce(s, SchedulerIntent.SetReminderChecked("auto/0", true))) // not a reminder
        assertEquals(s, SchedulerReducer.reduce(s, SchedulerIntent.SetReminderChecked("chore/0/0", true))) // already checked
        assertEquals(s, SchedulerReducer.reduce(s, SchedulerIntent.SetReminderChecked("missing", true))) // unknown id
    }

    // ----- §14 accumulation on the now-line ---------------------------------------------------

    @Test
    fun overdue_unchecked_reminders_accumulate_on_the_now_line() {
        val now = 1_000_000_000_000L
        val due1 = TaskPanel("chore/0/0", null, "Oldest", now - 2 * HOUR, now - 2 * HOUR, chore = true)
        val due2 = TaskPanel("chore/1/0", null, "Recent", now - HOUR, now - HOUR, chore = true)
        val done = TaskPanel("chore/2/0", null, "Done", now - 3 * HOUR, now - 3 * HOUR, chore = true, checked = true)
        val future = TaskPanel("chore/3/0", null, "Later", now + HOUR, now + HOUR, chore = true)

        val overdue = SchedulerDomain.overdueReminders(listOf(future, due2, done, due1), now)

        // Only the past, unchecked reminders, oldest first; the checked one (done) and the future one drop.
        assertEquals(listOf("chore/0/0", "chore/1/0"), overdue.map { it.id })
    }

    // ----- §14 SetChores regenerates the calendar ---------------------------------------------

    @Test
    fun set_chores_generates_reminder_tags_on_the_calendar() {
        val today = 1_000_000_000_000L
        val entries = listOf(ChoreEntry("Weekly", spanDays = 7.0, timeOfDayMinutes = 8 * 60))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries, todayStartMillis = today))
        assertEquals(entries, s.chores)
        assertTrue(s.panels.isNotEmpty())
        assertTrue(s.panels.all { it.chore && it.startEpochMillis == it.endEpochMillis }) // zero-duration tags
        assertTrue(s.panels.any { it.startEpochMillis == today + 8 * 60 * 60_000L })
    }

    @Test
    fun regenerating_reminders_preserves_a_checked_one_and_leaves_other_panels_alone() {
        val today = 1_000_000_000_000L
        // A previously checked reminder occurrence (same deterministic id the scheduler will regenerate).
        val checkedTag = TaskPanel("chore/0/0", null, "Weekly", today + 8 * HOUR, today + 8 * HOUR, chore = true, checked = true)
        val taskPanel = TaskPanel("auto/0", TaskId("t"), "Task", today, today + HOUR, auto = true)

        val regen = SchedulerDomain.regenerateChorePanels(
            listOf(checkedTag, taskPanel),
            chores = listOf(ChoreEntry("Weekly", 7.0, 8 * 60)),
            todayStartMillis = today,
        )

        assertTrue(taskPanel in regen) // non-reminder panel untouched
        // The regenerated tag with the matching id keeps its checked state (PRD §14 survives regeneration).
        assertTrue(regen.single { it.id == "chore/0/0" }.checked)
        // Freshly generated future occurrences exist and start unchecked.
        assertTrue(regen.any { it.chore && it.id != "chore/0/0" && !it.checked })
    }

    @Test
    fun set_chores_preserves_a_checked_reminder() {
        val today = 1_000_000_000_000L
        val checkedTag = TaskPanel("chore/0/0", null, "Weekly", today, today, chore = true, checked = true)
        val seeded = SchedulerState.empty().copy(panels = listOf(checkedTag))
        val s = SchedulerReducer.reduce(
            seeded,
            SchedulerIntent.SetChores(listOf(ChoreEntry("Weekly", 7.0, 0)), todayStartMillis = today),
        )
        assertTrue(s.panels.single { it.id == "chore/0/0" }.checked) // checked state survives the manager change
    }

    @Test
    fun codec_round_trip_preserves_reminder_time_of_day_checked_and_tag_flag() {
        val today = 1_000_000_000_000L
        val entries = listOf(ChoreEntry("Weekly", spanDays = 7.0, timeOfDayMinutes = 13 * 60 + 30))
        val seeded = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries, todayStartMillis = today))
        // Check the first occurrence so the round-trip has a checked tag to preserve.
        val firstId = seeded.panels.minByOrNull { it.startEpochMillis }!!.id
        val s = SchedulerReducer.reduce(seeded, SchedulerIntent.SetReminderChecked(firstId, true))

        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(13 * 60 + 30, decoded.chores.first().timeOfDayMinutes)
        assertTrue(decoded.panels.isNotEmpty() && decoded.panels.all { it.chore })
        assertTrue(decoded.panels.single { it.id == firstId }.checked) // checked state persisted
    }

    @Test
    fun codec_decodes_old_payload_to_an_empty_chores_list() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded.chores.isEmpty())
    }
}
