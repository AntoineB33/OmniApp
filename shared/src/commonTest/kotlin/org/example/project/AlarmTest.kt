package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §18 Alarms: the alarm list (edited in the left-menu Alarms window) plus the pure time arithmetic each
 * phone arms its OS alarm from. Covers the [AlarmDomain] boundary math (next occurrence + the crossings a
 * moving now-line passed, incl. a multi-day jump), the [SchedulerIntent.SetAlarms] /
 * [SchedulerIntent.SetAlarmEnabled] mutations, and persistence — including that a DB written **before** alarms
 * existed still loads (CLAUDE.md persisted-DB rule). The window itself and the Android ring service are
 * platform/UI code and not unit-tested here.
 */
class AlarmTest {

    private val tz = TimeZone.UTC
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** Epoch millis of `date` at `hh:mm` in [tz]. */
    private fun at(date: LocalDate, hh: Int, mm: Int): Long =
        LocalDateTime(date.year, date.month, date.day, hh, mm).toInstant(tz).toEpochMilliseconds()

    private val today = LocalDate(2026, 7, 24)

    private fun alarm(
        id: String = "alarm-0",
        minutes: Int = 7 * 60,
        soundSeconds: Int = 30,
        vibrate: Boolean = true,
        repeatDaily: Boolean = true,
        enabled: Boolean = true,
    ) = AlarmEntry(
        id = id,
        timeOfDayMinutes = minutes,
        soundSeconds = soundSeconds,
        vibrate = vibrate,
        repeatDaily = repeatDaily,
        enabled = enabled,
    )

    // ----- boundary math ------------------------------------------------------------------------

    @Test
    fun next_occurrence_is_today_when_the_time_is_still_ahead() {
        val next = AlarmDomain.nextOccurrenceMillis(alarm(minutes = 7 * 60), at(today, 6, 0), tz)
        assertEquals(at(today, 7, 0), next)
    }

    @Test
    fun next_occurrence_rolls_to_tomorrow_once_todays_time_has_passed() {
        // Exactly AT the boundary counts as passed (the ring is strictly after `after`), so the alarm that
        // just fired arms for tomorrow instead of immediately re-firing.
        assertEquals(
            at(today, 7, 0) + day,
            AlarmDomain.nextOccurrenceMillis(alarm(minutes = 7 * 60), at(today, 7, 0), tz),
        )
        assertEquals(
            at(today, 7, 0) + day,
            AlarmDomain.nextOccurrenceMillis(alarm(minutes = 7 * 60), at(today, 9, 30), tz),
        )
    }

    @Test
    fun a_disabled_or_zero_length_alarm_never_occurs() {
        val now = at(today, 6, 0)
        assertNull(AlarmDomain.nextOccurrenceMillis(alarm(enabled = false), now, tz))
        assertNull(AlarmDomain.nextOccurrenceMillis(alarm(soundSeconds = 0), now, tz))
    }

    @Test
    fun next_occurrence_across_alarms_picks_the_soonest_then_the_id() {
        val early = alarm(id = "alarm-1", minutes = 7 * 60)
        val late = alarm(id = "alarm-2", minutes = 9 * 60)
        val tie = alarm(id = "alarm-0", minutes = 7 * 60)
        val now = at(today, 6, 0)

        val soonest = AlarmDomain.nextOccurrence(listOf(late, early), now, tz)
        assertNotNull(soonest)
        assertEquals("alarm-1", soonest.entry.id)
        assertEquals(at(today, 7, 0), soonest.instant)

        // Two alarms at the same minute: the id breaks the tie, so every device arms the same one.
        assertEquals("alarm-0", AlarmDomain.nextOccurrence(listOf(early, tie), now, tz)?.entry?.id)
        assertNull(AlarmDomain.nextOccurrence(emptyList(), now, tz))
    }

    @Test
    fun crossings_are_half_open_so_consecutive_sweeps_neither_gap_nor_double_fire() {
        val a = alarm(minutes = 7 * 60)
        val ring = at(today, 7, 0)
        // (from, to]: the instant belongs to the sweep that ENDS on it, not the one that starts there.
        assertEquals(listOf(ring), AlarmDomain.crossingsBetween(listOf(a), ring - minute, ring, tz).map { it.instant })
        assertTrue(AlarmDomain.crossingsBetween(listOf(a), ring, ring + minute, tz).isEmpty())
        assertTrue(AlarmDomain.crossingsBetween(listOf(a), ring, ring, tz).isEmpty())
    }

    @Test
    fun a_multi_day_jump_yields_every_crossed_ring_in_order() {
        // CLAUDE.md: a trigger is a pure function of the boundary instants the clock crossed — a leap over
        // three days must yield all three rings of each alarm, ordered by instant (not per alarm).
        val morning = alarm(id = "alarm-0", minutes = 7 * 60)
        val evening = alarm(id = "alarm-1", minutes = 21 * 60)
        val from = at(today, 0, 0)
        val to = from + 3 * day
        val crossings = AlarmDomain.crossingsBetween(listOf(evening, morning), from, to, tz)
        assertEquals(6, crossings.size)
        assertEquals(crossings.map { it.instant }.sorted(), crossings.map { it.instant })
        assertEquals(
            listOf("alarm-0", "alarm-1", "alarm-0", "alarm-1", "alarm-0", "alarm-1"),
            crossings.map { it.entry.id },
        )
        assertEquals(at(today, 7, 0), crossings.first().instant)
    }

    @Test
    fun an_alarm_keeps_its_wall_clock_time_across_a_dst_shift() {
        // Paris springs forward at 02:00 on 2026-03-29 (CET→CEST): a 07:00 alarm stays 07:00 local, so the
        // gap between consecutive rings is 23 h, not 24 — the alarm follows the calendar, not a fixed period.
        val paris = TimeZone.of("Europe/Paris")
        val a = alarm(minutes = 7 * 60)
        val before = LocalDateTime(2026, 3, 28, 1, 0).toInstant(paris).toEpochMilliseconds()
        val first = AlarmDomain.nextOccurrenceMillis(a, before, paris)
        assertNotNull(first)
        val second = AlarmDomain.nextOccurrenceMillis(a, first, paris)
        assertNotNull(second)
        assertEquals(23 * hour, second - first)
        // Both are 07:00 local.
        listOf(first, second).forEach {
            val local = Instant.fromEpochMilliseconds(it).toLocalDateTime(paris)
            assertEquals(7, local.hour)
            assertEquals(0, local.minute)
        }
    }

    // ----- state / reducer ----------------------------------------------------------------------

    @Test
    fun alarms_default_to_an_empty_list() {
        assertTrue(SchedulerState.empty().alarms.isEmpty())
    }

    @Test
    fun set_alarms_stores_the_list_and_mints_ids_for_blank_rows() {
        val entries = listOf(
            AlarmEntry(id = "", label = "Wake up", timeOfDayMinutes = 7 * 60, soundSeconds = 45, vibrate = true),
            AlarmEntry(id = "", label = "Pills", timeOfDayMinutes = 20 * 60, soundSeconds = 10, vibrate = false),
        )
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetAlarms(entries))
        assertEquals(listOf("Wake up", "Pills"), s.alarms.map { it.label })
        assertEquals(listOf(45, 10), s.alarms.map { it.soundSeconds })
        assertEquals(listOf(true, false), s.alarms.map { it.vibrate })
        assertTrue(s.alarms.all { it.id.isNotBlank() })
        assertEquals(2, s.alarms.map { it.id }.toSet().size, "minted ids must be unique")
    }

    @Test
    fun set_alarms_replaces_the_list_and_is_a_no_op_when_unchanged() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetAlarms(listOf(alarm())))
        val again = SchedulerReducer.reduce(s, SchedulerIntent.SetAlarms(listOf(alarm())))
        assertEquals(s, again)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetAlarms(emptyList()))
        assertTrue(s.alarms.isEmpty())
    }

    @Test
    fun set_alarm_enabled_disarms_one_alarm_and_ignores_unknown_ids() {
        val s0 = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetAlarms(listOf(alarm(id = "alarm-0"), alarm(id = "alarm-1"))),
        )
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetAlarmEnabled("alarm-0", false))
        assertFalse(s1.alarms.first { it.id == "alarm-0" }.enabled)
        assertTrue(s1.alarms.first { it.id == "alarm-1" }.enabled)
        // Unknown id / already in that state: no change at all.
        assertEquals(s1, SchedulerReducer.reduce(s1, SchedulerIntent.SetAlarmEnabled("nope", false)))
        assertEquals(s1, SchedulerReducer.reduce(s1, SchedulerIntent.SetAlarmEnabled("alarm-0", false)))
    }

    @Test
    fun editing_alarms_is_not_part_of_the_tree_undo_history() {
        val withAlarms = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetAlarms(listOf(alarm())))
        val undone = SchedulerReducer.reduce(withAlarms, SchedulerIntent.Undo)
        assertEquals(withAlarms.alarms, undone.alarms)
    }

    // ----- persistence --------------------------------------------------------------------------

    @Test
    fun codec_round_trip_preserves_every_alarm_field() {
        val entries = listOf(
            AlarmEntry("alarm-0", "Wake up", 7 * 60, 45, vibrate = true, repeatDaily = true, enabled = true),
            AlarmEntry("alarm-1", "One-off", 20 * 60 + 15, 5, vibrate = false, repeatDaily = false, enabled = false),
        )
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetAlarms(entries))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(entries, decoded.alarms)
    }

    @Test
    fun codec_decodes_a_payload_written_before_alarms_existed() {
        // Persisted-DB rule: an on-disk DB from a build with no alarm list must still load, with no alarms.
        val oldJson =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(oldJson)
        assertNotNull(decoded)
        assertTrue(decoded.alarms.isEmpty())
    }

    @Test
    fun codec_decodes_an_alarm_row_missing_the_newer_fields() {
        // Adding a field to a Persisted* type must keep older payloads loadable (defaults fill in).
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "alarms":[{"id":"alarm-0","timeOfDayMinutes":420}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        val alarm = decoded.alarms.single()
        assertEquals(7 * 60, alarm.timeOfDayMinutes)
        assertEquals(AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS, alarm.soundSeconds)
        assertTrue(alarm.vibrate)
        assertTrue(alarm.repeatDaily)
        assertTrue(alarm.enabled)
    }

    @Test
    fun alarms_are_authoritative_so_they_ride_the_sync_wire() {
        // CLAUDE.md reconstructibility rule: an alarm is user-authored and NOT re-derivable, so changing the
        // list must move the sync fingerprint (i.e. it is pushed to the account's other devices).
        val s0 = SchedulerState.empty()
        val s1 = SchedulerReducer.reduce(s0, SchedulerIntent.SetAlarms(listOf(alarm())))
        assertTrue(
            SchedulerStateCodec.syncFingerprint(s0) != SchedulerStateCodec.syncFingerprint(s1),
            "adding an alarm must change the sync fingerprint",
        )
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.SetAlarmEnabled("alarm-0", false))
        assertTrue(
            SchedulerStateCodec.syncFingerprint(s1) != SchedulerStateCodec.syncFingerprint(s2),
            "disarming an alarm must change the sync fingerprint",
        )
    }
}
