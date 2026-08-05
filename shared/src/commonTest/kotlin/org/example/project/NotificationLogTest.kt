package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.platform.VoiceCue
import org.example.project.scheduler.state.NotificationLogEntry
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The History Manager's local-only Notifications column: recording appends (capped at
 * [SchedulerState.MAX_NOTIFICATION_LOG], keeping the FIRST that many), the log round-trips through the codec,
 * an old payload written before the field existed still decodes, and the log is a per-device diagnostic that
 * never affects the sync fingerprint and is carried across a remote pull.
 */
class NotificationLogTest {
    @Test
    fun the_end_of_a_screen_break_is_logged_not_only_spoken() {
        // The reported anomaly: "I don't see resume your work in the history notification." The look-away's
        // START posted a notification (so the column listed it) while its END only played a voice cue, which
        // writes the Diagnostics timeline and nothing else — so every break in the column began and none of
        // them ever finished. The end now posts too; the SPOKEN half stays gated on the look-away voice switch.
        val spoken = mutableListOf<VoiceCue>()
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = SchedulerEngine(
            vm = vm,
            clock = object : AppClock { override fun nowMillis(): Long = 7_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            screenActive = { true },
            playCue = { spoken.add(it) },
        )

        engine.announceResumeWork(voice = true)
        assertEquals(
            NotificationLogEntry(7_000L, "Screen break over", "Resume your work"),
            vm.state.value.notificationLog.single(),
        )
        assertEquals(listOf(VoiceCue.ResumeWork), spoken)

        // Voice off: still logged (like the break's start, whose notification the switch doesn't gate).
        engine.announceResumeWork(voice = false)
        assertEquals(2, vm.state.value.notificationLog.size)
        assertEquals(listOf(VoiceCue.ResumeWork), spoken)
    }

    @Test
    fun record_notification_appends_an_entry() {
        val state = SchedulerState.empty()
        val next = SchedulerReducer.reduce(state, SchedulerIntent.RecordNotification("Screen break", "Look away", 1_000))
        assertEquals(1, next.notificationLog.size)
        assertEquals(NotificationLogEntry(1_000, "Screen break", "Look away"), next.notificationLog.single())
    }

    @Test
    fun keeps_only_the_first_max_entries() {
        // Fill the log to the cap, then assert the next record is a no-op (same instance -> no persist/push).
        val full =
            SchedulerState.empty().copy(
                notificationLog =
                    (0 until SchedulerState.MAX_NOTIFICATION_LOG).map {
                        NotificationLogEntry(it.toLong(), "t$it", "m$it")
                    },
            )
        val next = SchedulerReducer.reduce(full, SchedulerIntent.RecordNotification("overflow", "dropped", 9_999))
        assertSame(full, next)
        assertEquals(SchedulerState.MAX_NOTIFICATION_LOG, next.notificationLog.size)
        // The earliest entries are the ones retained; the overflow one never appears.
        assertEquals("t0", next.notificationLog.first().title)
        assertTrue(next.notificationLog.none { it.title == "overflow" })
    }

    @Test
    fun log_round_trips_through_the_codec() {
        val entries = listOf(
            NotificationLogEntry(1_000, "Screen break", "Look away"),
            NotificationLogEntry(2_000, "Stop work", "Wind down — bedtime in 1 hour"),
        )
        val state = SchedulerState.empty().copy(notificationLog = entries)
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(state))
        assertNotNull(decoded)
        assertEquals(entries, decoded.notificationLog)
    }

    /**
     * Persisted-DB compatibility (CLAUDE.md): a payload written before the notification log existed (no
     * `notificationLog` field) must still decode, with the log defaulting to empty.
     */
    @Test
    fun legacy_payload_without_the_field_decodes_to_an_empty_log() {
        val legacy = """{"rootListId":"list/main","lists":[],"cells":[],"tasks":[]}"""
        val decoded = SchedulerStateCodec.decode(legacy)
        assertNotNull(decoded)
        assertTrue(decoded.notificationLog.isEmpty())
    }

    @Test
    fun log_is_local_only_and_never_affects_the_sync_fingerprint() {
        val base = SchedulerState.empty()
        val withLog = base.copy(notificationLog = listOf(NotificationLogEntry(1_000, "t", "m")))
        assertEquals(
            SchedulerStateCodec.syncFingerprint(base),
            SchedulerStateCodec.syncFingerprint(withLog),
        )
    }

    @Test
    fun a_remote_pull_keeps_the_local_log() {
        val local = SchedulerState.empty().copy(notificationLog = listOf(NotificationLogEntry(1_000, "t", "m")))
        val remote = SchedulerState.empty() // a pulled snapshot ships an empty (neutralized) log
        assertEquals(local.notificationLog, remote.withLocalViewStateFrom(local).notificationLog)
    }
}
