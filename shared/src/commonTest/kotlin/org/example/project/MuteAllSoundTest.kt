package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.platform.VoiceUtterance
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.MenuControl

/**
 * User rule 2026-10-18: *"add by default the switch button to remove all sound, so no voice, notification, alarm ringing or
 * anything"* — on THIS device only (the user's choice: muting the desktop leaves the phone's alarm ringing).
 */
class MuteAllSoundTest {
    @Test
    fun muted_a_ring_has_no_sound_and_no_voice_and_keeps_what_is_silent() {
        val alert = AlertSettings()
        val heard = alert.heard(muted = true)
        assertFalse(heard.sound)
        assertFalse(heard.voice)
        assertTrue(heard.vibrate, "a vibration is not a sound")
        assertTrue(heard.notification, "the notification is still shown, without its sound")
        assertEquals(alert, alert.heard(muted = false))
    }

    @Test
    fun nothing_is_spoken_on_a_muted_device_and_the_notification_still_reaches_the_record() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val spoken = mutableListOf<VoiceUtterance>()
        val posted = mutableListOf<String>()
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock { override fun nowMillis(): Long = 11_000L },
                scope = CoroutineScope(Dispatchers.Unconfined),
                screenActive = { true },
                speak = { spoken.add(it) },
                postNotification = { title, _, _ -> posted.add(title) },
                clearNotifications = {},
            )
        vm.dispatch(SchedulerIntent.SetSoundMuted(true))
        engine.announceResumeWork()
        assertEquals(emptyList(), spoken)
        assertEquals(1, posted.size, "posted — the notifiers post it without its sound")
        assertEquals(1, vm.state.value.notificationLog.size)
        vm.dispatch(SchedulerIntent.SetSoundMuted(false))
        engine.announceResumeWork()
        assertEquals(1, spoken.size)
    }

    @Test
    fun the_mute_is_this_devices_kept_on_it_and_never_synced() {
        val muted = SchedulerState.empty().copy(soundMuted = true)
        assertTrue(SchedulerStateCodec.decode(SchedulerStateCodec.encode(muted))!!.soundMuted)
        // A payload written before it existed: sound on.
        assertFalse(SchedulerStateCodec.decode(SchedulerStateCodec.encode(SchedulerState.empty()))!!.soundMuted)
        // Off the wire: muting is no change for another device, and a pull keeps this device's.
        assertEquals(SchedulerStateCodec.syncFingerprint(SchedulerState.empty()), SchedulerStateCodec.syncFingerprint(muted))
        assertTrue(SchedulerState.empty().withLocalViewStateFrom(muted).soundMuted)
    }

    @Test
    fun the_switch_is_in_every_pages_menu_by_default_and_given_once_to_a_menu_made_before() {
        assertEquals(MenuControl.MuteAll.name, CustomMenuButtons.DEFAULTS[1].control)
        assertTrue(CustomMenuButtons.DEFAULTS[1].global)
        assertEquals(CustomMenuButtons.GLOBAL_MUTE, CustomMenuButtons.CLAUDE_LOGS_DEFAULTS[1])
        // A menu stored before: given it once, just under the global Search button.
        val before = """{"buttons":[{"id":"g-search","window":"Search","title":"Search","global":true},{"id":"d-calendar","window":"Calendar","title":"Calendar"}],"seeded":true,"globalSeeded":true}"""
        assertEquals(listOf("g-search", "g-mute", "d-calendar"), CustomMenuButtons.decode(before).map { it.id })
        val removed = CustomMenuButtons.encode(CustomMenuButtons.decode(before).filterNot { it.id == "g-mute" })
        assertEquals(listOf("g-search", "d-calendar"), CustomMenuButtons.decode(removed).map { it.id }, "removed, it stays removed")
    }
}
