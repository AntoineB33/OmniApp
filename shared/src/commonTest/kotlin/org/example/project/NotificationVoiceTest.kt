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
import org.example.project.scheduler.platform.VoiceCue
import org.example.project.scheduler.platform.VoiceUtterance
import org.example.project.scheduler.platform.spokenNotificationText
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §11/§15: **every notification has a voice.**
 *
 * A notification is written for a user who is not looking at OmniApp — that is the whole of what it is for —
 * so one that only appears in the corner of a screen they are not watching has not arrived. The spoken half
 * is therefore no longer an extra attached to two of the cues: it comes out of `SchedulerEngine.notifyUser`,
 * the same funnel and the same call as the posted half, which is what makes it impossible for the app to say
 * one thing and show another, and impossible for a new notification site to be silent by omission.
 *
 * What that has to mean, and what this pins:
 *
 *  * every posted notification is spoken — no exempt caller, exactly as the mute has none;
 *  * the two phrases PRD §15 fixes word for word keep their **bundled** recording, and everything else is
 *    spoken from its own text (a task title, an alarm's label, a chord cannot be pre-rendered);
 *  * the **Notifications** switch silences BOTH halves (a mute that went on talking would not be a mute),
 *    while the **voice** switch silences only the voice — the notification still posts, and the History
 *    column still records it either way.
 */
class NotificationVoiceTest {

    private class Sink {
        val posted = mutableListOf<Pair<String, String>>()
        val spoken = mutableListOf<VoiceUtterance>()
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
        )

    @Test
    fun every_notification_the_app_posts_is_also_spoken() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        val engine = engineWith(vm, sink)

        // Three different notification sites, wired through different call paths: a screen break's end, a
        // chord's own receipt, and the un-mute announcement posted from the far side of the flip.
        engine.announceResumeWork()
        engine.announceShortcutReceived(GlobalShortcut.LookAwayNow)
        engine.setNotificationsEnabled(false)
        engine.setNotificationsEnabled(true)

        assertEquals(
            listOf("Screen break over", "Shortcut received", "Notifications on"),
            sink.posted.map { it.first },
        )
        assertEquals(
            sink.posted.size,
            sink.spoken.size,
            "a notification with no voice is exactly what this funnel exists to prevent",
        )
    }

    /**
     * The two cues §15 fixes the wording of speak their **pre-rendered** phrase, which is what keeps the
     * shared Piper voice on the phrase the user hears most; the rest are read from their own text, which is
     * what lets a notification naming a task, an alarm label or a chord be spoken at all.
     */
    @Test
    fun a_fixed_phrase_speaks_its_recording_and_everything_else_speaks_its_text() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        val engine = engineWith(vm, sink)

        engine.announceResumeWork()
        assertEquals(VoiceUtterance.of(VoiceCue.ResumeWork), sink.spoken.single())
        assertEquals(VoiceCue.ResumeWork, sink.spoken.single().cue, "spoken off the bundled recording")

        sink.spoken.clear()
        engine.announceShortcutReceived(GlobalShortcut.LookAwayNow)
        val receipt = sink.spoken.single()
        assertEquals(null, receipt.cue, "no enum can carry a chord: this one is synthesized")
        // The posted message is written to be READ ("<chord> — Look away now"); the spoken one is the same
        // sentence with the dash turned into the pause a reader hears there.
        val (title, message) = sink.posted.last()
        assertEquals(spokenNotificationText(title, message), receipt.text)
        assertTrue(receipt.text.startsWith("Shortcut received. "), receipt.text)
        assertTrue(receipt.text.endsWith(", Look away now"), receipt.text)
    }

    /** The mute is "cancel every notification", and the loud half is not the one it may leave running. */
    @Test
    fun muting_silences_the_voice_as_well_as_the_post_but_not_the_record() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        val engine = engineWith(vm, sink)
        engine.setNotificationsEnabled(false)
        sink.posted.clear()
        sink.spoken.clear()

        engine.announceResumeWork()
        engine.announceShortcutReceived(GlobalShortcut.SwitchTask)

        assertEquals(emptyList(), sink.posted)
        assertEquals(emptyList(), sink.spoken, "a muted app must not go on talking")
        assertEquals(
            listOf("Resume your work", GlobalShortcut.SwitchTask.action),
            vm.state.value.notificationLog.map { it.message.substringAfter("— ") },
            "the History column keeps the record of what was silenced",
        )
    }

    /** The voice switch governs the spoken half ALONE: the notifications go on posting, silently. */
    @Test
    fun the_voice_switch_silences_only_the_voice() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val sink = Sink()
        val engine = engineWith(vm, sink)
        assertTrue(vm.state.value.notificationVoiceEnabled, "the voice ships ON")

        vm.dispatch(SchedulerIntent.SetNotificationVoice(enabled = false))
        engine.announceResumeWork()

        assertEquals(listOf("Screen break over" to "Resume your work"), sink.posted)
        assertEquals(emptyList(), sink.spoken)
        assertEquals(1, vm.state.value.notificationLog.size)

        vm.dispatch(SchedulerIntent.SetNotificationVoice(enabled = true))
        engine.announceResumeWork()
        assertEquals(2, sink.posted.size)
        assertEquals(1, sink.spoken.size)
    }

    /**
     * A notification is two fields written to be read — a title above a message, a dash between a chord and
     * its action, a line break the eye skips. Spoken verbatim that comes out as punctuation, so the one
     * function that turns one into the other is asserted directly.
     */
    @Test
    fun the_spoken_form_reads_a_notification_as_a_sentence() {
        assertEquals(
            "Task to do now. Write the report",
            spokenNotificationText("Task to do now", "Write the report"),
        )
        // A dash is a pause, not a word.
        assertEquals(
            "Screen break. look 20 feet away, followed by the hour before bed",
            spokenNotificationText("Screen break", "look 20 feet away — followed by the hour before bed"),
        )
        // A message that already opens with the title is not made to say it twice — an alarm whose label is
        // the word "Alarm", and the title-only case under it.
        assertEquals("Alarm", spokenNotificationText("Alarm", "Alarm"))
        assertEquals("Timer. Tea", spokenNotificationText("Timer", "Tea"))
        assertEquals("Stop work", spokenNotificationText("Stop work", ""))
        // Line breaks end sentences; runs of whitespace collapse.
        assertEquals(
            "Task to do now. Write the report. until 14:30",
            spokenNotificationText("Task to do now", "Write   the report\nuntil 14:30"),
        )
    }

    @Test
    fun the_reducer_flips_the_voice_and_no_ops_on_the_same_value() {
        val on = SchedulerState.empty()
        val off = SchedulerReducer.reduce(on, SchedulerIntent.SetNotificationVoice(enabled = false))
        assertEquals(false, off.notificationVoiceEnabled)
        assertSame(off, SchedulerReducer.reduce(off, SchedulerIntent.SetNotificationVoice(enabled = false)))
    }

    /**
     * CLAUDE.md (persisted-DB compatibility): the switch is still written under the key it has always had —
     * `lookAwayVoiceEnabled`, from when it governed the look-away cue alone — so a payload written by the
     * previous build still loads, and one written by this build still loads on it. Only the field's meaning
     * widened, and a meaning is not something a payload carries.
     */
    @Test
    fun the_switch_round_trips_under_its_original_persisted_key() {
        val off = SchedulerState.empty().copy(notificationVoiceEnabled = false)
        val encoded = SchedulerStateCodec.encode(off)
        assertTrue(
            encoded.contains("\"lookAwayVoiceEnabled\""),
            "the persisted key must not be renamed: an older build on the same account still reads it",
        )
        val decoded = SchedulerStateCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(false, decoded.notificationVoiceEnabled)

        // A payload written before the field existed at all decodes to the voice ON, exactly as before.
        val legacy = SchedulerStateCodec.decode("""{"rootListId":"list/main","lists":[],"cells":[],"tasks":[]}""")
        assertNotNull(legacy)
        assertTrue(legacy.notificationVoiceEnabled)
    }
}
