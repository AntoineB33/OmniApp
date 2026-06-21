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
        // PRD §14: SetChores assigns each reminder a stable id; compare ignoring it, then confirm ids exist.
        assertEquals(entries, s.chores.map { it.copy(id = "") })
        assertTrue(s.chores.all { it.id.isNotBlank() })
    }

    @Test
    fun set_chores_replaces_the_whole_list() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(listOf(ChoreEntry("A", 1.0))))
        val replacement = listOf(ChoreEntry("B", 2.0), ChoreEntry("C", 0.25))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetChores(replacement))
        assertEquals(replacement, s.chores.map { it.copy(id = "") })
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
        assertEquals(listOf(ChoreEntry("A", 1.0)), undone.chores.map { it.copy(id = "") })
    }

    @Test
    fun codec_round_trip_preserves_the_chores_list() {
        val entries = listOf(ChoreEntry("Water plants", 3.5), ChoreEntry("Bins out", 14.0))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        // Round-trip preserves the actual list, including each reminder's assigned id.
        assertEquals(s.chores, decoded.chores)
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
    fun day_offsets_anchor_the_cadence_at_a_past_completion() {
        // PRD §14 tie-breaker: a weekly reminder last done 5 days ago (offset -5, e.g. last Monday) recurs
        // every 7 days from THAT day, not from today — so the in-horizon occurrences are +2, +9, +16, +23
        // (Mondays), and today (offset 0, a Saturday) is NOT one of them.
        assertEquals(
            listOf(2, 9, 16, 23),
            SchedulerDomain.choreOccurrenceDayOffsets(7.0, horizonDays = 28, anchorOffset = -5),
        )
        // A completion exactly one cadence ago re-includes today (offset 0) as the next due day.
        assertEquals(
            listOf(0, 7, 14, 21, 28),
            SchedulerDomain.choreOccurrenceDayOffsets(7.0, horizonDays = 28, anchorOffset = -7),
        )
        // No anchor (the default) keeps the legacy "start from today" behaviour.
        assertEquals(
            listOf(0, 7, 14, 21, 28),
            SchedulerDomain.choreOccurrenceDayOffsets(7.0, horizonDays = 28),
        )
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
        assertEquals(entries, s.chores.map { it.copy(id = "") })
        assertTrue(s.panels.isNotEmpty())
        assertTrue(s.panels.all { it.chore && it.startEpochMillis == it.endEpochMillis }) // zero-duration tags
        assertTrue(s.panels.any { it.startEpochMillis == today + 8 * 60 * 60_000L })
    }

    @Test
    fun add_checked_reminder_places_a_checked_tag_that_survives_regeneration() {
        val today = 1_000_000_000_000L
        val at = today + 9 * HOUR
        val withReminder = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetChores(listOf(ChoreEntry("Weekly", 7.0, 8 * 60)), todayStartMillis = today),
        )
        val reminderId = withReminder.chores.single().id
        val added = SchedulerReducer.reduce(
            withReminder,
            SchedulerIntent.AddCheckedReminder(reminderId, "Weekly", at),
        )
        // A checked, zero-duration manual reminder tag now exists at the chosen time, tied to the id.
        val manual = added.panels.single { it.id.startsWith(SchedulerDomain.MANUAL_REMINDER_PREFIX) }
        assertTrue(manual.chore && manual.checked && manual.startEpochMillis == at && manual.endEpochMillis == at)
        assertEquals(at, manual.checkedAtMillis)
        assertTrue(manual.id.contains(reminderId))

        // It is not produced by the recurrence scheduler, so it must survive regeneration.
        val regen = SchedulerDomain.regenerateChorePanels(added.panels, added.chores, todayStartMillis = today)
        assertTrue(regen.any { it.id == manual.id && it.checked })
    }

    @Test
    fun reducer_flow_add_checked_reminder_then_weekly_row_recurs_on_that_weekday() {
        // Exact reported flow through the real intents: (1) right-click on a past Monday → AddCheckedReminder
        // with a brand-new id, (2) open the reminders manager, add a row that adopts that reminder's id and
        // set it to "once per week" → SetChores. The generated cadence must land on Mondays, not today.
        val today = 1_000_000_000_000L
        val monday = today - 5 * DAY + 8 * HOUR

        val afterCheck = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.AddCheckedReminder(reminderId = "", title = "Weekly", atMillis = monday),
        )
        // The row adopts the manual reminder's id (the id menu default), exactly as the UI does.
        val reminderId = SchedulerDomain.reminderIdForTitle(afterCheck, "Weekly")
        assertNotNull(reminderId)
        val afterRow = SchedulerReducer.reduce(
            afterCheck,
            SchedulerIntent.SetChores(
                entries = listOf(ChoreEntry("Weekly", spanDays = 7.0, timeOfDayMinutes = 8 * 60, id = reminderId)),
                todayStartMillis = today,
                nowMillis = today + 12 * HOUR,
            ),
        )

        val generated = afterRow.panels.filter { it.chore && it.id.startsWith("chore/") }
        assertTrue(generated.isNotEmpty())
        assertTrue(generated.all { (it.startEpochMillis - monday) % (7 * DAY) == 0L }, "tags must fall on Mondays")
        assertFalse(generated.any { it.startEpochMillis in today until today + DAY }, "no tag on today (Saturday)")
        assertEquals(monday + 7 * DAY, generated.minOf { it.startEpochMillis })
    }

    @Test
    fun a_past_checked_reminder_anchors_the_weekly_cadence_to_its_weekday() {
        // PRD §14 tie-breaker (reported anomaly): a checked reminder added on a past Monday, then configured to
        // recur "once per week", must place its future tags on Mondays — anchored at the past completion — not
        // on today's weekday. Today is a "Saturday" (offset 0); the check is 5 days back (last "Monday").
        val today = 1_000_000_000_000L
        val monday = today - 5 * DAY + 8 * HOUR
        val chore = ChoreEntry("Weekly", spanDays = 7.0, timeOfDayMinutes = 8 * 60, id = "reminder-0")
        val manualChecked = TaskPanel(
            id = SchedulerDomain.MANUAL_REMINDER_PREFIX + "reminder-0/0",
            taskId = null,
            title = "Weekly",
            startEpochMillis = monday,
            endEpochMillis = monday,
            chore = true,
            checked = true,
            checkedAtMillis = monday,
        )

        val regen = SchedulerDomain.regenerateChorePanels(
            panels = listOf(manualChecked),
            chores = listOf(chore),
            todayStartMillis = today,
            nowMillis = today + 12 * HOUR,
        )

        val generated = regen.filter { it.chore && it.id.startsWith("chore/") }
        assertTrue(generated.isNotEmpty())
        // Every generated occurrence is a whole number of weeks after the checked Monday (same weekday)...
        assertTrue(generated.all { (it.startEpochMillis - monday) % (7 * DAY) == 0L })
        // ...none lands today (the Saturday) — the bug was recurring from today's weekday instead.
        assertFalse(generated.any { it.startEpochMillis in today until today + DAY })
        // ...and the first future tag is the upcoming Monday, two days out.
        assertEquals(monday + 7 * DAY, generated.minOf { it.startEpochMillis })
    }

    @Test
    fun add_checked_reminder_creates_a_reminder_identity_not_yet_in_the_manager() {
        // PRD §14: a reminder created via "add a checked reminder" is a known reminder identity (sourced
        // from its panel) even though it is not yet a row in the reminders manager. The id menus surface
        // it so the user can add it to the manager (or attach further checks to it).
        val today = 1_000_000_000_000L
        val at = today + 9 * HOUR
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.AddCheckedReminder(reminderId = "reminder-0", title = "Stretch", atMillis = at),
        )
        assertTrue(s.chores.isEmpty(), "no manager row is created by 'add a checked reminder'")
        assertEquals(
            listOf("reminder-0" to "Stretch"),
            SchedulerDomain.reminderMenuEntries(s, "Stretch").map { it.id to it.title },
        )
        assertEquals("Stretch", SchedulerDomain.reminderTitleForId(s, "reminder-0"))
        assertEquals("reminder-0", SchedulerDomain.reminderIdForTitle(s, "Stretch"))
    }

    @Test
    fun add_checked_reminder_for_a_brand_new_reminder_mints_an_id_and_shows_in_the_id_menu() {
        // PRD §14: "add a checked reminder" with a brand-new name (no id picked → blank reminderId) must
        // still become a selectable reminder identity. A blank id would decode to null and be dropped from
        // the id menu, so the reducer mints a fresh `reminder-{n}` id instead.
        val today = 1_000_000_000_000L
        val at = today + 9 * HOUR
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.AddCheckedReminder(reminderId = "", title = "y", atMillis = at),
        )
        val entries = SchedulerDomain.reminderMenuEntries(s, "y")
        assertEquals(listOf("y"), entries.map { it.title })
        assertTrue(entries.single().id.isNotBlank(), "a brand-new checked reminder must carry a stable id")
        assertEquals(entries.single().id, SchedulerDomain.reminderIdForTitle(s, "y"))
    }

    @Test
    fun reminder_id_menu_matches_titles_exactly_and_shows_nothing_for_an_empty_field() {
        // PRD §14: the id menu mirrors the task Change Task menu — it offers a reminder only when the typed
        // text IS its title (exactly, case-insensitively). An empty field matches nothing (so opening the
        // Reminders window and focusing a blank field shows no id menu), and a partial draft must not surface
        // a longer reminder. Partial-as-you-type matches belong to the title-suggestion menu instead.
        val today = 1_000_000_000_000L
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.AddCheckedReminder(reminderId = "", title = "yoga", atMillis = today + 9 * HOUR),
        )
        assertEquals(emptyList(), SchedulerDomain.reminderMenuEntries(s, ""), "empty field → no id menu")
        assertEquals(emptyList(), SchedulerDomain.reminderMenuEntries(s, "y"), "partial draft must not match")
        assertEquals(listOf("yoga"), SchedulerDomain.reminderMenuEntries(s, "yoga").map { it.title })
        assertEquals(listOf("yoga"), SchedulerDomain.reminderMenuEntries(s, "YOGA").map { it.title }, "case-insensitive")
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
    fun a_checked_reminder_keeps_its_identity_when_its_row_detaches_to_a_new_reminder() {
        // PRD §14: checking a reminder records a completion keyed by the reminder's STABLE id. If the row
        // then becomes a different reminder (the user picks "New Reminder"), the checked tag still references
        // the old reminder, so it survives (and stays a selectable identity) instead of being GC'd.
        val today = 1_000_000_000_000L
        var s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetChores(
                listOf(ChoreEntry("rem", spanDays = 1.0, timeOfDayMinutes = 0, id = "rem-B")),
                todayStartMillis = today, nowMillis = today,
            ),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetReminderChecked("chore/rem-B/0", true, today))
        assertTrue("rem-B" in SchedulerDomain.checkedReminderIds(s))

        // Detach: the row now represents a brand-new reminder rem-C; rem-B is no longer a manager row.
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetChores(
                listOf(ChoreEntry("rem", spanDays = 1.0, timeOfDayMinutes = 0, id = "rem-C")),
                todayStartMillis = today, nowMillis = today,
            ),
        )
        assertTrue(s.chores.none { it.id == "rem-B" }) // rem-B is no longer a row
        // rem-B survives, referenced by the checked tag, and is still a selectable reminder identity.
        assertTrue("rem-B" in SchedulerDomain.checkedReminderIds(s))
        assertEquals("rem", SchedulerDomain.reminderTitleForId(s, "rem-B"))
        assertTrue(SchedulerDomain.allReminderEntries(s).any { it.id == "rem-B" && it.title == "rem" })
        // Both reminders match the shared title now — the id menu would offer "rem-C" and the kept "rem-B".
        assertEquals(setOf("rem-B", "rem-C"), SchedulerDomain.reminderMenuEntries(s, "rem").map { it.id }.toSet())
    }

    @Test
    fun a_reminder_with_no_row_and_no_check_is_garbage_collected() {
        // PRD §14: an id referenced by neither a manager row nor a checked tag must be deleted.
        val today = 1_000_000_000_000L
        var s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetChores(
                listOf(ChoreEntry("rem", spanDays = 1.0, timeOfDayMinutes = 0, id = "rem-B")),
                todayStartMillis = today, nowMillis = today,
            ),
        )
        assertTrue(s.panels.any { it.chore && it.id.startsWith("chore/rem-B/") })
        // Remove the row without ever checking it → no row, no checked tag → the reminder ceases to exist.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetChores(emptyList(), todayStartMillis = today, nowMillis = today))
        assertTrue(s.panels.none { it.chore }) // its generated tags are gone (not lingering as orphans)
        assertTrue(SchedulerDomain.allReminderEntries(s).isEmpty())
        assertNull(SchedulerDomain.reminderTitleForId(s, "rem-B"))
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
