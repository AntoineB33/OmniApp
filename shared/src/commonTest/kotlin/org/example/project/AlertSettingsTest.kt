package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.ArmedAlarm
import org.example.project.scheduler.engine.RingKind
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.platform.AlarmTone
import org.example.project.scheduler.platform.AlertSound
import org.example.project.scheduler.platform.VoiceUtterance
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §11/§14/§18: **how an alarm, a timer or a reminder makes itself heard** — the four independent channels
 * ([AlertSettings]) and the small set of sounds ([AlertSound]) each row picks from.
 *
 * What this pins:
 *
 *  * every sound in the set is a playable, loopable, audible cycle, deterministic (so every device of the
 *    account rings identically) and actually different from the others;
 *  * each channel is asked separately at the ring — a row with its sound off does not touch the audio seam
 *    and still posts, one with its notification off still rings, and the account's own switches keep the last
 *    word over all of them;
 *  * a reminder announces itself off the **tags the calendar draws**, once each, never when it is checked;
 *  * a payload written before any of this existed goes on doing exactly what it did.
 */
class AlertSettingsTest {

    // ----- the sounds -------------------------------------------------------------------------------------

    /** Signed 16-bit little-endian sample `i` of a PCM buffer. */
    private fun sampleAt(pcm: ByteArray, i: Int): Int {
        val lo = pcm[i * 2].toInt() and 0xFF
        val hi = pcm[i * 2 + 1].toInt()
        return (hi shl 8) or lo
    }

    @Test
    fun every_sound_of_the_set_is_one_loopable_cycle_of_audible_pcm() {
        for (sound in AlertSound.entries) {
            val pcm = AlarmTone.loopPcm(sound)
            val frames = AlarmTone.SAMPLE_RATE * AlarmTone.loopMillis(sound) / 1000
            assertEquals(frames * 2, pcm.size, "${sound.name}: 16-bit mono, exactly one cycle")

            var peak = 0
            for (i in 0 until frames) peak = maxOf(peak, abs(sampleAt(pcm, i)))
            assertTrue(peak > Short.MAX_VALUE / 8, "${sound.name} must not be near-silent (peak=$peak)")
            assertTrue(peak < Short.MAX_VALUE, "${sound.name} must stay under full scale (peak=$peak)")

            // The cycle is written back-to-back for the whole ring, so both seam edges must be silent.
            assertEquals(0, sampleAt(pcm, 0), "${sound.name} starts at silence")
            assertEquals(0, sampleAt(pcm, frames - 1), "${sound.name} ends at silence")
        }
    }

    @Test
    fun every_sound_is_deterministic_so_every_device_rings_identically() {
        for (sound in AlertSound.entries) {
            assertTrue(
                AlarmTone.loopPcm(sound).contentEquals(AlarmTone.loopPcm(sound)),
                "${sound.name} must synthesize the same bytes every time",
            )
        }
    }

    @Test
    fun the_sounds_are_actually_different_from_one_another() {
        // A set of five names that all played the guitar would be a choice in name only.
        val rendered = AlertSound.entries.associateWith { AlarmTone.loopPcm(it) }
        for (a in AlertSound.entries) {
            for (b in AlertSound.entries) {
                if (a.ordinal >= b.ordinal) continue
                assertFalse(
                    rendered.getValue(a).contentEquals(rendered.getValue(b)),
                    "${a.name} and ${b.name} must not be the same sound",
                )
            }
        }
    }

    @Test
    fun the_default_sound_is_the_guitar_the_alarms_have_always_rung_with() {
        assertEquals(AlertSound.Guitar, AlertSound.DEFAULT)
        assertTrue(AlarmTone.loopPcm().contentEquals(AlarmTone.loopPcm(AlertSound.Guitar)))
    }

    // ----- the model --------------------------------------------------------------------------------------

    @Test
    fun a_row_with_every_channel_off_is_not_schedulable() {
        val silent = AlertSettings(sound = false, voice = false, notification = false, vibrate = false)
        assertFalse(silent.announces)
        assertFalse(silent.rings)
        assertFalse(
            AlarmEntry(id = "alarm-0", timeOfDayMinutes = 7 * 60, alert = silent).schedulable,
            "an alarm that would reach nobody is not worth an OS alarm slot",
        )
        assertFalse(
            TimerEntry(id = "timer-0", endsAtMillis = 1L, alert = silent).schedulable,
        )
        // One channel back on is enough — including one that is not the sound.
        assertTrue(
            AlarmEntry(
                id = "alarm-0",
                timeOfDayMinutes = 7 * 60,
                alert = silent.copy(notification = true),
            ).schedulable,
        )
    }

    @Test
    fun the_sound_a_row_picked_survives_switching_its_sound_off_and_on() {
        // The choice lives on the row, not in the picker, so it is still there to come back to.
        val chosen = AlertSettings.RING.copy(tone = AlertSound.Bell)
        assertEquals(AlertSound.Bell, chosen.copy(sound = false).copy(sound = true).tone)
    }

    // ----- the ring ---------------------------------------------------------------------------------------

    private class Sink {
        val posted = mutableListOf<Pair<String, String>>()
        val spoken = mutableListOf<VoiceUtterance>()
        val rung = mutableListOf<ArmedAlarm>()
    }

    private fun engineWith(vm: TaskSchedulerViewModel, sink: Sink) =
        SchedulerEngine(
            vm = vm,
            clock = object : AppClock { override fun nowMillis(): Long = 11_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            screenActive = { true },
            speak = { sink.spoken.add(it) },
            postNotification = { title, message -> sink.posted.add(title to message) },
            clearNotifications = {},
            ringAlarm = { sink.rung.add(it) },
        )

    private fun fire(alert: AlertSettings): Sink {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        engineWith(vm, sink).onAlarmFire(
            ArmedAlarm("alarm-0", 11_000L, "Wake up", 30, alert = alert),
        )
        return sink
    }

    @Test
    fun a_ring_with_its_sound_off_never_touches_the_audio_seam_and_still_posts() {
        val sink = fire(AlertSettings.RING.copy(sound = false, vibrate = false))
        assertTrue(sink.rung.isEmpty(), "nothing to play and nothing to buzz: the seam is not called at all")
        assertEquals(listOf("Alarm" to "Wake up"), sink.posted)
        assertEquals(1, sink.spoken.size)
    }

    @Test
    fun a_ring_with_only_its_vibration_left_on_still_reaches_the_seam() {
        // The phone's half of "felt, not heard" — the seam is what vibrates, so it must still be called.
        val sink = fire(AlertSettings(sound = false, voice = false, notification = false, vibrate = true))
        assertEquals(1, sink.rung.size)
        assertFalse(sink.rung.single().alert.sound)
        assertTrue(sink.rung.single().alert.vibrate)
        assertTrue(sink.posted.isEmpty(), "this row asked for no notification")
        assertTrue(sink.spoken.isEmpty(), "and for no voice")
    }

    @Test
    fun the_four_channels_are_asked_separately() {
        val postedOnly = fire(AlertSettings(sound = false, voice = false, notification = true, vibrate = false))
        assertEquals(1, postedOnly.posted.size)
        assertTrue(postedOnly.spoken.isEmpty())

        val spokenOnly = fire(AlertSettings(sound = false, voice = true, notification = false, vibrate = false))
        assertTrue(spokenOnly.posted.isEmpty())
        assertEquals(1, spokenOnly.spoken.size, "a row may speak without posting")

        val soundOnly = fire(AlertSettings(sound = true, voice = false, notification = false, vibrate = false))
        assertEquals(1, soundOnly.rung.size)
        assertTrue(soundOnly.posted.isEmpty())
        assertTrue(soundOnly.spoken.isEmpty())
    }

    @Test
    fun the_ring_carries_the_sound_the_row_chose() {
        val sink = fire(AlertSettings.RING.copy(tone = AlertSound.Marimba))
        assertEquals(AlertSound.Marimba, sink.rung.single().alert.tone)
    }

    @Test
    fun the_account_mute_still_silences_a_row_that_asked_for_everything() {
        // PRD §11: the row's switches NARROW the account's, they never widen them.
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        val engine = engineWith(vm, sink)
        engine.setNotificationsEnabled(false)
        sink.posted.clear()
        sink.spoken.clear()

        engine.onAlarmFire(ArmedAlarm("alarm-0", 11_000L, "Wake up", 30, alert = AlertSettings.RING))

        assertTrue(sink.posted.isEmpty(), "the mute is the mute")
        assertTrue(sink.spoken.isEmpty())
        // The sound is the alarm's own and is NOT the notifications switch's business (ADR 0010: an alarm
        // rings a locked machine on purpose) — what the switch cancels is what the app *says*.
        assertEquals(1, sink.rung.size)
    }

    @Test
    fun a_reminder_ring_is_titled_reminder() {
        assertEquals("Reminder", RingKind.Reminder.label)
        assertEquals("Alarm", RingKind.Alarm.label)
        assertEquals("Timer", RingKind.Timer.label)
        assertTrue(ArmedAlarm("timer-0", 0L, kind = RingKind.Timer).timer)
        assertFalse(ArmedAlarm("reminder-tag", 0L, kind = RingKind.Reminder).timer)
    }

    // ----- reminders --------------------------------------------------------------------------------------

    private fun tag(id: String, at: Long, title: String = "Water the plants", checked: Boolean = false) =
        TaskPanel(
            id = id,
            taskId = null,
            title = title,
            startEpochMillis = at,
            endEpochMillis = at,
            chore = true,
            checked = checked,
        )

    @Test
    fun a_reminder_tag_the_now_line_crossed_is_one_cue_crossing_naming_its_tag() {
        val tags = listOf(
            tag("chore/reminder-0/3", 1_000L),
            tag("chore/reminder-1/3", 2_000L, title = "Pills"),
            tag("chore/reminder-2/4", 9_000L, title = "Too far ahead"),
        )
        val out = SchedulerDomain.reminderCueOccurrencesBetween(tags, 0L, 5_000L)

        assertEquals(listOf(1_000L, 2_000L), out.map { it.instant })
        assertEquals(listOf("chore/reminder-0/3", "chore/reminder-1/3"), out.map { it.sourceId })
        assertTrue(out.all { it.kind == SchedulerDomain.CueKind.ReminderDue })
        // A zero-duration tag announces only its instant.
        assertTrue(out.all { it.endInstant == it.instant })
    }

    @Test
    fun a_checked_reminder_is_never_announced() {
        val tags = listOf(tag("chore/reminder-0/3", 1_000L, checked = true))
        assertTrue(
            SchedulerDomain.reminderCueOccurrencesBetween(tags, 0L, 5_000L).isEmpty(),
            "a checked tag is a completion: the user has already done the thing",
        )
    }

    @Test
    fun the_window_is_half_open_so_consecutive_sweeps_announce_each_tag_once() {
        val tags = listOf(tag("chore/reminder-0/3", 1_000L))
        assertEquals(1, SchedulerDomain.reminderCueOccurrencesBetween(tags, 0L, 1_000L).size)
        assertEquals(
            0,
            SchedulerDomain.reminderCueOccurrencesBetween(tags, 1_000L, 2_000L).size,
            "the next sweep starts where this one ended, and must not re-announce its last boundary",
        )
    }

    @Test
    fun only_reminder_tags_are_announced_not_every_panel_on_the_calendar() {
        val panels = listOf(
            tag("chore/reminder-0/3", 1_000L),
            TaskPanel(id = "p1", taskId = null, title = "Work", startEpochMillis = 1_000L, endEpochMillis = 2_000L),
        )
        assertEquals(1, SchedulerDomain.reminderCueOccurrencesBetween(panels, 0L, 5_000L).size)
    }

    @Test
    fun a_reminder_tag_announces_with_its_own_rows_alert() {
        val chores = listOf(
            ChoreEntry(
                title = "Water the plants",
                spanDays = 3.0,
                id = "reminder-0",
                alert = AlertSettings.RING.copy(tone = AlertSound.Chime),
            ),
        )
        assertEquals(
            AlertSound.Chime,
            SchedulerDomain.alertForReminderTag(chores, "chore/reminder-0/3").tone,
        )
        // A tag whose reminder is not a manager row — a hand-placed one, or a row struck off while its
        // checked tag kept the id alive — still announces, with what a reminder does by default.
        assertEquals(
            AlertSettings.REMINDER,
            SchedulerDomain.alertForReminderTag(chores, "chore-manual/reminder-7/a"),
        )
        assertEquals(AlertSettings.REMINDER, SchedulerDomain.alertForReminderTag(chores, "p1"))
    }

    @Test
    fun a_reminder_is_said_and_posted_but_neither_rung_nor_buzzed_by_default() {
        // PRD §14: a reminder is a tag on the day, not an alarm clock. Turning its sound on is what makes
        // it one — which is exactly the choice this feature exists to offer.
        assertFalse(AlertSettings.REMINDER.sound)
        assertFalse(AlertSettings.REMINDER.vibrate)
        assertTrue(AlertSettings.REMINDER.voice)
        assertTrue(AlertSettings.REMINDER.notification)
        assertFalse(AlertSettings.REMINDER.rings)
        assertTrue(AlertSettings.REMINDER.announces)
    }

    // ----- persistence ------------------------------------------------------------------------------------

    private val legacyPayload =
        """
        {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
         "cells":[{"id":"c0","parentListId":"L","taskId":null}],
         "tasks":[{"id":"t0","title":"X"}],
         "alarms":[{"id":"alarm-0","timeOfDayMinutes":420,"soundSeconds":45,"vibrate":false}],
         "timers":[{"id":"timer-0","durationSeconds":180,"vibrate":false}],
         "chores":[{"title":"Water the plants","spanDays":3.0,"id":"reminder-0"}]}
        """.trimIndent()

    @Test
    fun a_payload_written_before_the_alert_block_goes_on_doing_what_it_did() {
        val decoded = SchedulerStateCodec.decode(legacyPayload)
        assertNotNull(decoded)

        // An alarm rang, spoke and posted; its stored vibrate flag is the one thing that was ever a choice.
        val alarm = decoded.alarms.single().alert
        assertTrue(alarm.sound)
        assertTrue(alarm.voice)
        assertTrue(alarm.notification)
        assertFalse(alarm.vibrate, "the vibration the old row actually stored must survive")
        assertEquals(AlertSound.Guitar, alarm.tone)

        assertFalse(decoded.timers.single().alert.vibrate)

        // A reminder announced nothing at all before this existed, and a reminder's default is the nearest
        // thing to that which is still a reminder: said and posted, never rung.
        assertEquals(AlertSettings.REMINDER, decoded.chores.single().alert)
    }

    @Test
    fun the_alert_block_round_trips_through_the_codec() {
        val state = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetAlarms(
                listOf(
                    AlarmEntry(
                        id = "alarm-0",
                        timeOfDayMinutes = 7 * 60,
                        alert = AlertSettings(
                            sound = true,
                            tone = AlertSound.Bell,
                            voice = false,
                            notification = true,
                            vibrate = false,
                        ),
                    ),
                ),
            ),
        )
        val withReminder = SchedulerReducer.reduce(
            state,
            SchedulerIntent.SetChores(
                listOf(
                    ChoreEntry(
                        title = "Pills",
                        spanDays = 1.0,
                        id = "reminder-0",
                        alert = AlertSettings.REMINDER.copy(sound = true, tone = AlertSound.Beeps),
                    ),
                ),
            ),
        )
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(withReminder))
        assertNotNull(decoded)
        assertEquals(withReminder.alarms.single().alert, decoded.alarms.single().alert)
        assertEquals(withReminder.chores.single().alert, decoded.chores.single().alert)
    }

    @Test
    fun a_sound_this_build_does_not_have_decodes_to_the_default_rather_than_to_silence() {
        // The set may grow or be reordered; a payload naming a sound we no longer ship must still ring.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "alarms":[{"id":"alarm-0","timeOfDayMinutes":420,
                        "alert":{"sound":true,"tone":"Theremin","voice":true,"notification":true,"vibrate":true}}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        val alert = decoded.alarms.single().alert
        assertTrue(alert.sound, "the row still rings")
        assertEquals(AlertSound.DEFAULT, alert.tone)
    }

    @Test
    fun changing_a_rows_alert_moves_the_sync_fingerprint() {
        // It is authoritative user data, so a peer must learn about it.
        val base = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetAlarms(listOf(AlarmEntry(id = "alarm-0", timeOfDayMinutes = 7 * 60))),
        )
        val quiet = SchedulerReducer.reduce(
            base,
            SchedulerIntent.SetAlarms(
                listOf(
                    AlarmEntry(
                        id = "alarm-0",
                        timeOfDayMinutes = 7 * 60,
                        alert = AlertSettings.RING.copy(sound = false),
                    ),
                ),
            ),
        )
        assertNotEquals(
            SchedulerStateCodec.syncFingerprint(base),
            SchedulerStateCodec.syncFingerprint(quiet),
        )
    }
}
