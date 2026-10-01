package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.TimerDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §5/§18: the Alarms window's **History Units**.
 *
 * The window edits two authoritative lists, so everything the user does to them — adding a row, striking one
 * off with the bin, changing any of a row's settings — is one Main History Unit, undone with Ctrl+Z and shown
 * in the History window. Three rules are what this file is about, and each one has bitten:
 *
 *  1. the **bin** is undoable (the report that started this: a timer struck off by mistake never came back);
 *  2. a field edited live is **one** unit per focus session, not one per keystroke;
 *  3. the writes the app authors itself — a one-off alarm disarming after it rang, a timer resetting after it
 *     rang, every run-state transition — are **not** units, so Ctrl+Z never means "un-ring that".
 */
class AlarmHistoryTest {

    private fun alarm(
        id: String,
        minutes: Int = 7 * 60,
        label: String = "",
        enabled: Boolean = true,
    ) = AlarmEntry(id = id, label = label, timeOfDayMinutes = minutes, enabled = enabled)

    private fun timer(
        id: String,
        seconds: Int = 300,
        label: String = "",
        endsAtMillis: Long? = null,
    ) = TimerEntry(id = id, label = label, durationSeconds = seconds, endsAtMillis = endsAtMillis)

    private fun stateWith(
        alarms: List<AlarmEntry> = emptyList(),
        timers: List<TimerEntry> = emptyList(),
    ): SchedulerState = SchedulerState.empty().copy(alarms = alarms, timers = timers)

    private fun main(state: SchedulerState) = state.histories.forCategory(HistoryCategory.Main)

    // -----------------------------------------------------------------------------------------------
    // The bin
    // -----------------------------------------------------------------------------------------------

    @Test
    fun removing_a_timer_is_undoable_and_redoable() {
        val kept = timer("timer-0", label = "Tea")
        val struck = timer("timer-1", seconds = 900, label = "Oven", endsAtMillis = 1_700_000_000_000L)
        val s0 = stateWith(timers = listOf(kept, struck))

        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetTimers(listOf(kept)))
        assertEquals(listOf(kept), s1.timers)
        assertEquals(1, main(s1).units.size)
        assertEquals("Remove timer", main(s1).units.last().delta.label)
        assertTrue(main(s1).units.last().delta.details.any { it.startsWith("removed") })

        // The whole point: the row comes back exactly as it was, still running and still due at its instant.
        val undone = SchedulerReducer.reduce(s1, SchedulerIntent.Undo)
        assertEquals(listOf(kept, struck), undone.timers)
        assertEquals(1_700_000_000_000L, undone.timers[1].endsAtMillis)

        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertEquals(listOf(kept), redone.timers)
    }

    @Test
    fun removing_an_alarm_is_undoable() {
        val kept = alarm("alarm-0")
        val struck = alarm("alarm-1", minutes = 9 * 60, label = "Standup")
        val s0 = stateWith(alarms = listOf(kept, struck))

        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetAlarms(listOf(kept)))
        assertEquals("Remove alarm", main(s1).units.last().delta.label)
        assertTrue(main(s1).units.last().delta.details.any { it.contains("09:00 Standup") })

        assertEquals(listOf(kept, struck), SchedulerReducer.reduce(s1, SchedulerIntent.Undo).alarms)
    }

    @Test
    fun adding_a_row_reads_as_an_add_and_undoes_to_the_shorter_list() {
        val s0 = stateWith(alarms = listOf(alarm("alarm-0")))
        val s1 = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetAlarms(listOf(alarm("alarm-0"), alarm("alarm-1", minutes = 8 * 60))),
        )
        assertEquals("Add alarm", main(s1).units.last().delta.label)
        assertEquals(1, SchedulerReducer.reduce(s1, SchedulerIntent.Undo).alarms.size)
    }

    @Test
    fun an_unchanged_list_records_nothing() {
        val s0 = stateWith(alarms = listOf(alarm("alarm-0")), timers = listOf(timer("timer-0")))
        assertEquals(
            s0.histories,
            SchedulerReducer.reduce(s0, SchedulerIntent.SetAlarms(listOf(alarm("alarm-0")))).histories,
        )
        assertEquals(
            s0.histories,
            SchedulerReducer.reduce(s0, SchedulerIntent.SetTimers(listOf(timer("timer-0")))).histories,
        )
    }

    // -----------------------------------------------------------------------------------------------
    // One unit per field-focus session
    // -----------------------------------------------------------------------------------------------

    /** Typing "Tea" into a label: three pushes, one focus session — and therefore ONE Ctrl+Z. */
    @Test
    fun the_keystrokes_of_one_field_session_are_one_unit() {
        val s0 = stateWith(alarms = listOf(alarm("alarm-0")))
        val key = "alarm-0/label@1"
        var s = s0
        for (text in listOf("T", "Te", "Tea")) {
            s = SchedulerReducer.reduce(
                s,
                SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", label = text)), editKey = key),
            )
        }
        assertEquals(1, main(s).units.size)
        assertEquals(main(s).units.lastIndex, main(s).pointer)
        assertEquals("Tea", s.alarms.single().label)
        // The merged unit keeps the FIRST push's before side: one undo walks the whole word back.
        assertEquals("", SchedulerReducer.reduce(s, SchedulerIntent.Undo).alarms.single().label)
    }

    /** Leaving the field and coming back is a second session, so it is a second unit. */
    @Test
    fun a_second_focus_session_is_a_second_unit() {
        val s0 = stateWith(alarms = listOf(alarm("alarm-0")))
        val s1 = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", label = "Tea")), editKey = "alarm-0/label@1"),
        )
        val s2 = SchedulerReducer.reduce(
            s1,
            SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", label = "Teas")), editKey = "alarm-0/label@2"),
        )
        assertEquals(2, main(s2).units.size)
        assertEquals("Tea", SchedulerReducer.reduce(s2, SchedulerIntent.Undo).alarms.single().label)
    }

    /** A structural change carries no key, so it can never be absorbed into the text edit before it. */
    @Test
    fun a_structural_change_never_merges_into_a_text_edit() {
        val s0 = stateWith(alarms = listOf(alarm("alarm-0")))
        val s1 = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", label = "Tea")), editKey = "alarm-0/label@1"),
        )
        // The on/off switch, while the label still holds the focus.
        val s2 = SchedulerReducer.reduce(
            s1,
            SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", label = "Tea", enabled = false))),
        )
        assertEquals(2, main(s2).units.size)

        val undone = SchedulerReducer.reduce(s2, SchedulerIntent.Undo)
        assertTrue(undone.alarms.single().enabled)
        assertEquals("Tea", undone.alarms.single().label)
    }

    /** Two different fields of the same row are two gestures even with no push in between. */
    @Test
    fun two_fields_of_one_row_are_two_units() {
        val s0 = stateWith(timers = listOf(timer("timer-0")))
        val s1 = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetTimers(listOf(timer("timer-0", label = "Tea")), editKey = "timer-0/label@1"),
        )
        val s2 = SchedulerReducer.reduce(
            s1,
            SchedulerIntent.SetTimers(
                listOf(timer("timer-0", seconds = 600, label = "Tea")),
                editKey = "timer-0/duration@2",
            ),
        )
        assertEquals(2, main(s2).units.size)
        assertEquals(300, SchedulerReducer.reduce(s2, SchedulerIntent.Undo).timers.single().durationSeconds)
    }

    /** A new edit after an undo orphans the redo units — the branching rule, through the coalescing path. */
    @Test
    fun a_text_edit_after_an_undo_does_not_merge_into_the_orphaned_unit() {
        val s0 = stateWith(alarms = listOf(alarm("alarm-0")))
        val key = "alarm-0/label@1"
        val s1 = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", label = "Tea")), editKey = key),
        )
        val undone = SchedulerReducer.reduce(s1, SchedulerIntent.Undo)
        val s2 = SchedulerReducer.reduce(
            undone,
            SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", label = "Oat")), editKey = key),
        )
        // The orphaned unit is dropped and this is a fresh one, not a merge onto a unit behind the pointer.
        assertEquals(1, main(s2).units.size)
        assertEquals("", SchedulerReducer.reduce(s2, SchedulerIntent.Undo).alarms.single().label)
    }

    // -----------------------------------------------------------------------------------------------
    // What the app authors itself is never a unit
    // -----------------------------------------------------------------------------------------------

    @Test
    fun the_engine_disarming_a_one_off_alarm_records_nothing() {
        val s0 = stateWith(alarms = listOf(alarm("alarm-0")))
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetAlarmEnabled("alarm-0", false))
        assertTrue(s1.alarms.single().enabled.not())
        assertEquals(s0.histories, s1.histories)
    }

    @Test
    fun every_timer_run_state_transition_the_user_makes_is_a_unit_and_the_ring_is_not() {
        val now = 1_700_000_000_000L
        val s0 = stateWith(timers = listOf(timer("timer-0")))
        val started = SchedulerReducer.reduce(s0, SchedulerIntent.StartTimer("timer-0", now))
        val paused = SchedulerReducer.reduce(started, SchedulerIntent.PauseTimer("timer-0", now + 1_000L))
        val nudged = SchedulerReducer.reduce(
            paused,
            SchedulerIntent.NudgeTimerRemaining("timer-0", 10_000L, now + 2_000L),
        )
        val typed = SchedulerReducer.reduce(
            nudged,
            SchedulerIntent.SetTimerCountdownField(
                "timer-0", TimerDomain.TimerField.MINUTES, 2, now + 3_000L,
            ),
        )
        val reset = SchedulerReducer.reduce(typed, SchedulerIntent.ResetTimer("timer-0"))

        // Every one of them moved the run state...
        assertEquals(now + 300_000L, started.timers.single().endsAtMillis)
        assertNull(reset.timers.single().endsAtMillis)
        assertNull(reset.timers.single().remainingMillis)
        // ...and, since 2026-10-01 (a History Unit for almost every user action), each is a unit of its own.
        val units = { s: SchedulerState -> s.histories.forCategory(HistoryCategory.Main).units.size }
        assertEquals(5, units(reset))
        // Undoing the reset puts the typed countdown back.
        assertEquals(typed.timers, SchedulerReducer.reduce(reset, SchedulerIntent.Undo).timers)
        // The ring is the engine's, not the user's: no unit.
        val rang = SchedulerReducer.reduce(started, SchedulerIntent.TimerRang("timer-0", now + 300_000L))
        assertEquals(started.histories, rang.histories)
    }

    /** Editing a row's settings while it runs is a unit, and undoing it leaves the countdown alone. */
    @Test
    fun undoing_a_settings_edit_does_not_disturb_a_running_countdown() {
        val now = 1_700_000_000_000L
        val s0 = stateWith(timers = listOf(timer("timer-0")))
        val running = SchedulerReducer.reduce(s0, SchedulerIntent.StartTimer("timer-0", now))
        val endsAt = running.timers.single().endsAtMillis

        val renamed = SchedulerReducer.reduce(
            running,
            SchedulerIntent.SetTimers(
                listOf(timer("timer-0", label = "Tea", endsAtMillis = endsAt)),
                editKey = "timer-0/label@1",
            ),
        )
        assertEquals(endsAt, renamed.timers.single().endsAtMillis)

        val undone = SchedulerReducer.reduce(renamed, SchedulerIntent.Undo)
        assertEquals("", undone.timers.single().label)
        assertEquals(endsAt, undone.timers.single().endsAtMillis)
    }

    // -----------------------------------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------------------------------

    @Test
    fun codec_round_trips_alarm_and_timer_history_units() {
        val s0 = stateWith(alarms = listOf(alarm("alarm-0")), timers = listOf(timer("timer-0")))
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetTimers(emptyList()))
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.SetAlarms(emptyList()))

        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s2))!!
        assertEquals(2, decoded.histories.forCategory(HistoryCategory.Main).units.size)

        // Both units still walk their list back, in order.
        val undoneAlarms = SchedulerReducer.reduce(decoded, SchedulerIntent.Undo)
        assertEquals(listOf(alarm("alarm-0")), undoneAlarms.alarms)
        val undoneTimers = SchedulerReducer.reduce(undoneAlarms, SchedulerIntent.Undo)
        assertEquals(listOf(timer("timer-0")), undoneTimers.timers)
    }

    /**
     * A unit read back from the DB has closed its gesture: it carries no coalescing key, so a key minted in a
     * later session can never merge a fresh keystroke into a unit written before the restart.
     */
    @Test
    fun a_reloaded_unit_absorbs_no_further_keystroke() {
        val s0 = stateWith(alarms = listOf(alarm("alarm-0")))
        val key = "alarm-0/label@1"
        val s1 = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", label = "Tea")), editKey = key),
        )
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s1))!!
        assertNull(decoded.histories.forCategory(HistoryCategory.Main).units.single().delta.coalesceKey)

        val s2 = SchedulerReducer.reduce(
            decoded,
            SchedulerIntent.SetAlarms(listOf(alarm("alarm-0", label = "Teas")), editKey = key),
        )
        assertEquals(2, main(s2).units.size)
    }

    /**
     * CLAUDE.md persisted-DB rule: a payload written by the **previous** shape — alarms and timers in the
     * state, a Main history that predates these unit kinds — still loads, and the units it does hold still
     * walk. The new [org.example.project.scheduler.state.AlarmsDelta] /
     * [org.example.project.scheduler.state.TimersDelta] are new `PersistedDelta` subtypes, not new fields on
     * an existing one, so nothing older had to change to make room for them.
     */
    @Test
    fun a_payload_written_before_these_units_existed_still_loads() {
        val previousShape =
            """
            {
              "rootListId": "L",
              "lists": [{"id": "L", "parentCellId": null, "cellIds": ["c0"]}],
              "cells": [{"id": "c0", "parentListId": "L", "taskId": null}],
              "tasks": [{"id": "t0", "title": "X"}],
              "alarms": [
                {"id": "alarm-0", "label": "Wake up", "timeOfDayMinutes": 420, "soundSeconds": 30,
                 "vibrate": true, "days": [1,2,3,4,5], "repeats": true, "enabled": true}
              ],
              "timers": [
                {"id": "timer-0", "label": "Tea", "durationSeconds": 180, "soundSeconds": 30,
                 "vibrate": true}
              ],
              "histories": {
                "main": {
                  "pointer": 0,
                  "units": [
                    {"timeMillis": 1700000000000, "chronoId": 0,
                     "delta": {"type": "sleep",
                               "before": {"wakeMinutes": 450, "goalWakeMinutes": 450,
                                          "sleepDurationMinutes": 510},
                               "after": {"wakeMinutes": 420, "goalWakeMinutes": 420,
                                         "sleepDurationMinutes": 480}}}
                  ]
                }
              }
            }
            """.trimIndent()

        val decoded = SchedulerStateCodec.decode(previousShape)
        assertTrue(decoded != null, "a payload of the previous shape must still decode")
        assertEquals("Wake up", decoded.alarms.single().label)
        assertEquals(420, decoded.alarms.single().timeOfDayMinutes)
        assertEquals(5, decoded.alarms.single().days.size)
        assertEquals(180, decoded.timers.single().durationSeconds)

        // The old unit is intact and still undoes.
        val units = decoded.histories.forCategory(HistoryCategory.Main).units
        assertEquals(1, units.size)
        assertEquals(510, SchedulerReducer.reduce(decoded, SchedulerIntent.Undo).sleep?.sleepDurationMinutes)
        // PRD §6: the payload predates the `window` field, so the unit names no window — and is still
        // listed, under the History window's "All windows" (CLAUDE.md persisted-DB compatibility).
        assertNull(units.single().window)
    }
}
