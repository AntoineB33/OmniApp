package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §11: the lateral menu's **Notifications** switch and its `Ctrl+Shift+Alt+N` chord — "cancel every
 * notification", and the two halves of what that has to mean.
 *
 * The switch is a rule about **what the app hands to the OS**, and about nothing else. Two things must stay
 * true of it, and they pull in opposite directions:
 *
 *  * *every* notification is silenced, the system-wide chords' own receipts included — they all funnel
 *    through `SchedulerEngine.notifyUser`, and a mute that let one class through would not be a mute; and
 *  * the **record** survives untouched, so the History window's Notifications column still answers "what did
 *    the app decide to say while I had it muted".
 */
class NotificationMuteTest {

    private class Sink {
        val posted = mutableListOf<Pair<String, String>>()
        var cleared = 0
    }

    private fun engineWith(vm: TaskSchedulerViewModel, sink: Sink) =
        SchedulerEngine(
            vm = vm,
            clock = object : AppClock { override fun nowMillis(): Long = 4_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            screenActive = { true },
            playCue = {},
            postNotification = { title, message -> sink.posted.add(title to message) },
            clearNotifications = { sink.cleared++ },
        )

    @Test
    fun on_by_default_a_notification_reaches_the_os() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        assertTrue(vm.state.value.notificationsEnabled, "notifications ship ON")

        engineWith(vm, sink).announceResumeWork(voice = false)

        assertEquals(listOf("Screen break over" to "Resume your work"), sink.posted)
        assertEquals(1, vm.state.value.notificationLog.size)
    }

    @Test
    fun muted_nothing_is_posted_but_everything_is_still_logged() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        val engine = engineWith(vm, sink)
        engine.setNotificationsEnabled(false)
        sink.posted.clear()

        engine.announceResumeWork(voice = false)
        // A chord's receipt is a notification like any other — the mute is not a list of exempt callers.
        engine.announceShortcutReceived(GlobalShortcut.LookAwayNow)

        assertEquals(emptyList(), sink.posted, "a muted notification must never reach the OS")
        assertEquals(
            listOf("Resume your work", GlobalShortcut.LookAwayNow.action),
            vm.state.value.notificationLog.map { it.message.substringAfter("— ") },
            "the History column keeps the record of what was silenced",
        )
    }

    /**
     * "Cancel every notification" has to answer the pile already in the shade as well as the ones still to
     * come: switching off with an unanswered notification on screen must leave nothing on screen. Cancelling
     * is the platform seam ([cancelSystemNotifications]); on desktop its actual is a documented no-op, since
     * a tray balloon cannot be recalled.
     */
    @Test
    fun switching_off_withdraws_what_the_os_is_already_showing() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        val engine = engineWith(vm, sink)

        engine.announceResumeWork(voice = false)
        engine.setNotificationsEnabled(false)

        assertEquals(1, sink.cleared)
        assertEquals(false, vm.state.value.notificationsEnabled)
    }

    /**
     * The un-mute press is the one whose receipt the mute would eat: the receipt is raised at the hot-key
     * seam BEFORE the action, so at that moment notifications are still off. Turning them back on therefore
     * says so from the far side of the flip — otherwise the single press whose whole subject is notifications
     * would be the only one the user cannot see landing.
     */
    @Test
    fun switching_back_on_announces_itself() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        val engine = engineWith(vm, sink)
        engine.setNotificationsEnabled(false)
        engine.setNotificationsEnabled(true)

        assertEquals(listOf("Notifications on" to "OmniApp will notify you again"), sink.posted)
        assertEquals(1, sink.cleared, "only the switch-off cleared; turning them back on clears nothing")
    }

    @Test
    fun a_same_value_call_does_nothing_at_all() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        val engine = engineWith(vm, sink)

        engine.setNotificationsEnabled(true) // already on

        assertEquals(emptyList(), sink.posted)
        assertEquals(0, sink.cleared)
        assertTrue(vm.state.value.notificationLog.isEmpty())
    }

    @Test
    fun the_reducer_flips_the_flag_and_no_ops_on_the_same_value() {
        val on = SchedulerState.empty()
        val off = SchedulerReducer.reduce(on, SchedulerIntent.SetNotificationsEnabled(false))
        assertEquals(false, off.notificationsEnabled)
        assertSame(off, SchedulerReducer.reduce(off, SchedulerIntent.SetNotificationsEnabled(false)))
    }

    @Test
    fun the_switch_round_trips_through_the_codec() {
        val muted = SchedulerState.empty().copy(notificationsEnabled = false)
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(muted))
        assertNotNull(decoded)
        assertEquals(false, decoded.notificationsEnabled)
    }

    /**
     * Persisted-DB compatibility (CLAUDE.md): a payload written before the switch existed carries no such
     * field and must decode to exactly the behaviour it had — notifications ON.
     */
    @Test
    fun a_payload_written_before_the_switch_existed_decodes_to_on() {
        val legacy = """{"rootListId":"list/main","lists":[],"cells":[],"tasks":[]}"""
        val decoded = SchedulerStateCodec.decode(legacy)
        assertNotNull(decoded)
        assertTrue(decoded.notificationsEnabled)
    }

    /** Unlike the notification LOG beside it, the switch is the account's: it must ride the wire. */
    @Test
    fun the_switch_is_authoritative_and_moves_the_sync_fingerprint() {
        val on = SchedulerState.empty()
        val off = on.copy(notificationsEnabled = false)
        assertTrue(
            SchedulerStateCodec.syncFingerprint(on) != SchedulerStateCodec.syncFingerprint(off),
            "muting is a user-authored change and must push",
        )
    }
}
