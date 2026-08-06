package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.platform.AlarmTone
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
        days: Set<DayOfWeek> = AlarmEntry.EVERY_DAY,
        repeats: Boolean = true,
        enabled: Boolean = true,
    ) = AlarmEntry(
        id = id,
        timeOfDayMinutes = minutes,
        soundSeconds = soundSeconds,
        vibrate = vibrate,
        days = days,
        repeats = repeats,
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

    // ----- the days an alarm is triggered on (PRD §18) -------------------------------------------

    @Test
    fun an_alarm_rings_every_day_by_default() {
        // "By default it is everyday": a freshly built entry carries the whole week, so a week-long sweep
        // yields seven rings.
        assertEquals(AlarmEntry.EVERY_DAY, AlarmEntry("alarm-0").days)
        val from = at(today, 0, 0)
        val crossings = AlarmDomain.crossingsBetween(listOf(alarm(minutes = 7 * 60)), from, from + 7 * day, tz)
        assertEquals(7, crossings.size)
    }

    @Test
    fun an_alarm_only_rings_on_the_days_it_is_triggered_on() {
        // 2026-07-24 is a Friday. A Monday/Wednesday alarm crossed by a whole week yields exactly those two.
        assertEquals(DayOfWeek.FRIDAY, today.dayOfWeek)
        val a = alarm(minutes = 7 * 60, days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        val from = at(today, 0, 0)
        val crossings = AlarmDomain.crossingsBetween(listOf(a), from, from + 7 * day, tz)
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            crossings.map { Instant.fromEpochMilliseconds(it.instant).toLocalDateTime(tz).date.dayOfWeek },
        )
    }

    @Test
    fun the_next_occurrence_skips_forward_to_the_next_day_the_alarm_is_triggered_on() {
        // From Friday morning, a Monday-only alarm is three days out — the scan must reach past tomorrow.
        val monday = alarm(minutes = 7 * 60, days = setOf(DayOfWeek.MONDAY))
        val next = AlarmDomain.nextOccurrenceMillis(monday, at(today, 6, 0), tz)
        assertNotNull(next)
        val date = Instant.fromEpochMilliseconds(next).toLocalDateTime(tz).date
        assertEquals(DayOfWeek.MONDAY, date.dayOfWeek)
        assertEquals(at(today, 7, 0) + 3 * day, next)
        // And the ring after it is a full week later, not the next day.
        assertEquals(next + 7 * day, AlarmDomain.nextOccurrenceMillis(monday, next, tz))
    }

    @Test
    fun an_alarm_with_no_day_left_never_rings() {
        // An empty set is not "every day" — it is a week with nothing selected, so there is no boundary at
        // all (the UI keeps the last day, but a hand-edited/merged DB may still hold one).
        val none = alarm(days = emptySet())
        assertFalse(none.schedulable)
        assertNull(AlarmDomain.nextOccurrenceMillis(none, at(today, 0, 0), tz))
        val from = at(today, 0, 0)
        assertTrue(AlarmDomain.crossingsBetween(listOf(none), from, from + 7 * day, tz).isEmpty())
    }

    // ----- what the calendar draws (PRD §18) -----------------------------------------------------

    @Test
    fun the_calendar_window_draws_one_marker_per_ring_including_the_start_instant() {
        // The display form is [from, to) — closed at the start, so each occurrence belongs to exactly one
        // displayed week and a ring exactly at midnight isn't drawn twice.
        val midnight = alarm(minutes = 0)
        val weekStart = at(today, 0, 0)
        val weekEnd = weekStart + 7 * day
        val drawn = AlarmDomain.occurrencesInWindow(listOf(midnight), weekStart, weekEnd, tz)
        assertEquals(7, drawn.size)
        assertEquals(weekStart, drawn.first().instant)
        assertEquals(weekEnd - day, drawn.last().instant)
        // The next window starts where this one ended and picks the ring up exactly once.
        assertEquals(weekEnd, AlarmDomain.occurrencesInWindow(listOf(midnight), weekEnd, weekEnd + day, tz).single().instant)
    }

    @Test
    fun the_calendar_draws_past_rings_too_and_none_for_a_disarmed_alarm() {
        // An alarm is a fixed wall-clock boundary: a ring that already went off stays on the calendar where
        // it happened (unlike a reminder, which follows the now-line until checked).
        val a = alarm(minutes = 7 * 60)
        val weekStart = at(today, 0, 0) - 7 * day
        assertEquals(7, AlarmDomain.occurrencesInWindow(listOf(a), weekStart, weekStart + 7 * day, tz).size)
        assertTrue(
            AlarmDomain.occurrencesInWindow(
                listOf(alarm(enabled = false)), weekStart, weekStart + 7 * day, tz,
            ).isEmpty(),
        )
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
            AlarmEntry(
                "alarm-0", "Wake up", 7 * 60, 45, vibrate = true,
                days = AlarmEntry.EVERY_DAY, repeats = true, enabled = true,
            ),
            AlarmEntry(
                "alarm-1", "One-off", 20 * 60 + 15, 5, vibrate = false,
                days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), repeats = false, enabled = false,
            ),
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
        assertTrue(alarm.repeats)
        assertTrue(alarm.enabled)
        // PRD §18 "by default it is everyday": a row written before the days existed rings every day, which
        // is exactly what it did — the days must not decode as an empty (never-ringing) set.
        assertEquals(AlarmEntry.EVERY_DAY, alarm.days)
    }

    @Test
    fun codec_decodes_the_legacy_repeat_daily_flag_as_the_repeat_flag() {
        // The flag was named `repeatDaily` before an alarm carried its own days; @JsonNames keeps those
        // payloads loading (a one-off stays a one-off) while new writes use `repeats`.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "alarms":[{"id":"alarm-0","timeOfDayMinutes":420,"repeatDaily":false}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertFalse(decoded.alarms.single().repeats)
        assertEquals(AlarmEntry.EVERY_DAY, decoded.alarms.single().days)
    }

    @Test
    fun codec_round_trips_a_narrowed_set_of_days() {
        val entry = alarm(days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetAlarms(listOf(entry)))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(entry.days, decoded.alarms.single().days)
    }

    @Test
    fun the_days_an_alarm_rings_on_are_synced_to_the_other_devices() {
        // CLAUDE.md reconstructibility rule: the days are user-authored and not re-derivable, so narrowing
        // them must move the sync fingerprint — otherwise a peer would keep ringing on the old days.
        val everyDay = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetAlarms(listOf(alarm())))
        val weekdaysOnly =
            SchedulerReducer.reduce(
                everyDay,
                SchedulerIntent.SetAlarms(listOf(alarm(days = AlarmEntry.EVERY_DAY - DayOfWeek.SUNDAY))),
            )
        assertTrue(
            SchedulerStateCodec.syncFingerprint(everyDay) != SchedulerStateCodec.syncFingerprint(weekdaysOnly),
            "changing the days an alarm rings on must change the sync fingerprint",
        )
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

    // ---- The alarm sound (PRD §18: an acoustic guitar, synthesized in common code) ----

    /** Signed 16-bit little-endian sample `i` of a PCM buffer. */
    private fun sampleAt(pcm: ByteArray, i: Int): Int {
        val lo = pcm[i * 2].toInt() and 0xFF
        val hi = pcm[i * 2 + 1].toInt()
        return (hi shl 8) or lo
    }

    @Test
    fun the_alarm_tone_is_one_loopable_cycle_of_playable_pcm() {
        val pcm = AlarmTone.loopPcm()
        // 16-bit mono: two bytes a frame, exactly one LOOP_MILLIS cycle.
        val frames = AlarmTone.SAMPLE_RATE * AlarmTone.LOOP_MILLIS / 1000
        assertEquals(frames * 2, pcm.size)

        var peak = 0
        for (i in 0 until frames) {
            val value = sampleAt(pcm, i)
            if (kotlin.math.abs(value) > peak) peak = kotlin.math.abs(value)
        }
        // Actually audible…
        assertTrue(peak > Short.MAX_VALUE / 8, "the alarm tone must not be near-silent (peak=$peak)")
        // …and normalized under full scale, so neither platform's line clips.
        assertTrue(peak < Short.MAX_VALUE, "the alarm tone must stay under full scale (peak=$peak)")

        // The cycle is written back-to-back for the whole ring, so both seam edges must be silent — a
        // non-zero sample there clicks once per loop.
        assertEquals(0, sampleAt(pcm, 0))
        assertEquals(0, sampleAt(pcm, frames - 1))
    }

    @Test
    fun the_alarm_tone_is_deterministic_so_every_device_rings_identically() {
        // The pluck is seeded noise, not random noise: the desktop and the phone synthesize the same bytes.
        assertTrue(AlarmTone.loopPcm().contentEquals(AlarmTone.loopPcm()))
    }
}
