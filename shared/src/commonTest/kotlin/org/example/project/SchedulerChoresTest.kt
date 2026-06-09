package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * PRD §14 Chores Manager: a standalone vertical list of pairs (title + spanning time in days, a
 * floating-point number). Covers the testable core — the [SchedulerIntent.SetChores] mutation, its
 * persistence, and that it lives outside the tree Undo/Redo history (PRD §6/§14). The floating window
 * and its lateral-menu toggle are Compose UI (like the §7 calendar window) and are not unit-tested here.
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
        // PRD §14/§6: chores live outside the Task Tree snapshot, so Ctrl+Z must not revert them (mirrors
        // the §7 automatic-schedule switch / the task record).
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

    // ----- §14 chore scheduler: recurrence day offsets ----------------------------------------

    private val DAY = 24L * 60 * 60 * 1000

    @Test
    fun day_offsets_for_an_integer_cadence_step_evenly_within_the_horizon() {
        assertEquals(listOf(0, 7, 14, 21, 28), SchedulerDomain.choreOccurrenceDayOffsets(7.0, horizonDays = 28))
        assertEquals(listOf(0, 10, 20, 30), SchedulerDomain.choreOccurrenceDayOffsets(10.0, horizonDays = 30))
    }

    @Test
    fun day_offsets_for_an_invalid_cadence_are_just_today() {
        // PRD §14: the spanning time is a float > 1; a non-cadence (≤ 1) collapses to a single panel today.
        assertEquals(listOf(0), SchedulerDomain.choreOccurrenceDayOffsets(1.0))
        assertEquals(listOf(0), SchedulerDomain.choreOccurrenceDayOffsets(0.5))
    }

    @Test
    fun day_offsets_for_a_fractional_cadence_accumulate_without_drifting() {
        val offsets = SchedulerDomain.choreOccurrenceDayOffsets(2.5, horizonDays = 20)
        assertEquals(0, offsets.first()) // PRD §14: counter starts at today (offset 0)
        // Strictly increasing, all within the horizon.
        for (i in 1 until offsets.size) assertTrue(offsets[i] > offsets[i - 1])
        assertTrue(offsets.all { it in 0..20 })
        // Accumulated (not drifting): each offset is the nearest integer to k·2.5, so the last offset is
        // close to a multiple of 2.5 — average gap stays ≈ 2.5 rather than rounding up every step.
        val avgGap = offsets.last().toDouble() / (offsets.size - 1)
        assertTrue(avgGap in 2.3..2.7, "average gap $avgGap should hover around the 2.5 cadence")
    }

    // ----- §14 chore scheduler: panel generation ----------------------------------------------

    @Test
    fun chore_panels_are_five_minute_fixed_blocks_at_the_time_of_day() {
        val today = 1_000_000_000_000L
        val chores = listOf(ChoreEntry("Water plants", spanDays = 7.0, timeOfDayMinutes = 9 * 60))
        val panels = SchedulerDomain.choreScheduledPanels(chores, todayStartMillis = today, horizonDays = 28)

        assertEquals(5, panels.size) // offsets 0,7,14,21,28
        val first = panels.first()
        assertEquals(today + 9 * 60 * 60_000L, first.startEpochMillis) // 09:00 today
        assertEquals(SchedulerDomain.CHORE_PANEL_MILLIS, first.endEpochMillis - first.startEpochMillis) // 5 min
        assertTrue(panels.all { it.chore && !it.pinned && it.taskId == null })
        assertTrue(panels.all { it.title == "Water plants" })
        // Recurs every 7 days at the same time of day.
        assertEquals(today + 7 * DAY + 9 * 60 * 60_000L, panels[1].startEpochMillis)
    }

    @Test
    fun chore_scheduler_skips_blank_titles_and_non_cadences() {
        val today = 1_000_000_000_000L
        val chores = listOf(
            ChoreEntry("", spanDays = 7.0, timeOfDayMinutes = 0), // blank → skipped
            ChoreEntry("Daily", spanDays = 1.0, timeOfDayMinutes = 0), // ≤ 1 → not a cadence → skipped
            ChoreEntry("Weekly", spanDays = 7.0, timeOfDayMinutes = 60),
        )
        val panels = SchedulerDomain.choreScheduledPanels(chores, todayStartMillis = today)
        assertTrue(panels.all { it.title == "Weekly" })
        assertTrue(panels.isNotEmpty())
    }

    // ----- §14 chore pin system (vs the chore scheduler) --------------------------------------

    @Test
    fun regenerating_chore_panels_keeps_pinned_ones_and_non_chore_panels() {
        val today = 1_000_000_000_000L
        val pinnedChore = TaskPanel("chore/old", null, "Old", today, today + 5 * 60_000L, pinned = true, chore = true)
        val staleChore = TaskPanel("chore/9/0", null, "Stale", today, today + 5 * 60_000L, pinned = false, chore = true)
        val taskPanel = TaskPanel("auto/0", TaskId("t"), "Task", today, today + 60_000L, pinned = false, auto = true)
        val panels = listOf(pinnedChore, staleChore, taskPanel)

        val regen = SchedulerDomain.regenerateChorePanels(
            panels,
            chores = listOf(ChoreEntry("Weekly", 7.0, 0)),
            todayStartMillis = today,
        )

        assertTrue(pinnedChore in regen) // pinned chore kept (chore pin system)
        assertTrue(taskPanel in regen) // non-chore panel untouched
        assertTrue(staleChore !in regen) // non-pinned chore replaced
        assertTrue(regen.any { it.chore && !it.pinned && it.title == "Weekly" }) // freshly generated
    }

    // ----- §14 chore panels behave like pinned panels toward the task scheduler ---------------

    @Test
    fun fill_schedule_keeps_a_chore_panel_and_flows_auto_panels_around_it() {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val now = 1_000_000_000_000L
        val HOUR = 3_600_000L
        // A chore 5-min panel sits 2h ahead; the auto fill must keep it and not overlap it (PRD §14/§9).
        val chore = TaskPanel("chore/0/0", null, "Chore", now + 2 * HOUR, now + 2 * HOUR + 5 * 60_000L, chore = true)
        s = s.copy(panels = listOf(chore))

        val panels = SchedulerDomain.fillSchedule(s, now)

        assertTrue(chore in panels) // chore panel survives (treated as pinned)
        val autos = panels.filter { it.auto }
        assertTrue(autos.none { it.startEpochMillis < chore.endEpochMillis && chore.startEpochMillis < it.endEpochMillis })
        assertTrue(autos.any { it.endEpochMillis == chore.startEpochMillis }) // shortened to fit before it
    }

    // ----- §14 SetChores regenerates the calendar ---------------------------------------------

    @Test
    fun set_chores_generates_chore_panels_on_the_calendar() {
        val today = 1_000_000_000_000L
        val entries = listOf(ChoreEntry("Weekly", spanDays = 7.0, timeOfDayMinutes = 8 * 60))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries, todayStartMillis = today))
        assertEquals(entries, s.chores)
        assertTrue(s.panels.isNotEmpty())
        assertTrue(s.panels.all { it.chore })
        assertTrue(s.panels.any { it.startEpochMillis == today + 8 * 60 * 60_000L })
    }

    @Test
    fun set_chores_preserves_a_pinned_chore_panel() {
        val today = 1_000_000_000_000L
        val pinnedChore = TaskPanel("chore/keep", null, "Keep", today, today + 5 * 60_000L, pinned = true, chore = true)
        val seeded = SchedulerState.empty().copy(panels = listOf(pinnedChore))
        val s = SchedulerReducer.reduce(
            seeded,
            SchedulerIntent.SetChores(listOf(ChoreEntry("Weekly", 7.0, 0)), todayStartMillis = today),
        )
        assertTrue(pinnedChore in s.panels) // pinned chore panel survives a chores-manager change
    }

    @Test
    fun codec_round_trip_preserves_chore_time_of_day_and_panel_flag() {
        val today = 1_000_000_000_000L
        val entries = listOf(ChoreEntry("Weekly", spanDays = 7.0, timeOfDayMinutes = 13 * 60 + 30))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChores(entries, todayStartMillis = today))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(13 * 60 + 30, decoded.chores.first().timeOfDayMinutes)
        assertTrue(decoded.panels.isNotEmpty() && decoded.panels.all { it.chore })
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
