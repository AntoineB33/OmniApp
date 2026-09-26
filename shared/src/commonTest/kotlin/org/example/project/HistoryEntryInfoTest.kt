package org.example.project

import org.example.project.scheduler.platform.VoiceCue
import org.example.project.scheduler.platform.VoiceUtterance
import org.example.project.scheduler.state.NotificationLogEntry
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerRunEntry
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.SupabaseUsageEntry
import org.example.project.ui.FilteredHistoryEntry
import org.example.project.ui.HistoryFilterConfig
import org.example.project.ui.filteredHistoryUnits
import org.example.project.ui.historyEntryHasMoreInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRD §6: every row of the History window has a button that opens its information window — **except** a row
 * that already shows everything that window would. And a notification's window replays the vocal message the
 * notification was spoken with, which is why the log has to remember the bundled cue it used.
 */
class HistoryEntryInfoTest {
    @Test
    fun only_a_row_with_something_more_to_show_has_an_information_window() {
        var s = SchedulerState.empty()
        val cellId = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cellId, "Daily"))
        val unit = filteredHistoryUnits(s.histories, HistoryFilterConfig()).first()
        assertTrue(unit is FilteredHistoryEntry.Unit)

        // A unit's chrono id is never on its row; a notification's voice is not either; a run's rules never fit.
        assertTrue(historyEntryHasMoreInfo(unit))
        assertTrue(historyEntryHasMoreInfo(FilteredHistoryEntry.Notification(NotificationLogEntry(1, "t", "m"))))
        assertTrue(
            historyEntryHasMoreInfo(
                FilteredHistoryEntry.SchedulerRun(
                    SchedulerRunEntry(
                        timeMillis = 7_000,
                        kind = SchedulerRunEntry.Kind.Replan,
                        horizonMillis = 8_000,
                        panelCount = 0,
                        ruleState = emptyList(),
                        rules = emptyList(),
                    ),
                ),
            ),
        )
        // A Supabase call's row prints every field it stores: no button, and no double click either.
        assertFalse(
            historyEntryHasMoreInfo(
                FilteredHistoryEntry.SupabaseUsage(SupabaseUsageEntry(6_000, "scheduler_snapshot", "push", 10, 20, 200)),
            ),
        )
    }

    @Test
    fun a_notification_replays_the_utterance_it_was_spoken_with() {
        // The look-away's start and a rest pose's are both titled "Screen break"; only the recorded cue tells
        // the WAV from the synthesized text, so the replay must read it off the entry, not guess it.
        val lookAway = NotificationLogEntry(1_000, "Screen break", "look 20 feet away", VoiceCue.LookAway)
        val restPose = NotificationLogEntry(2_000, "Screen break", "stretch — 5 min")
        assertEquals(VoiceUtterance.of(VoiceCue.LookAway), lookAway.utterance)
        assertEquals(VoiceUtterance.forNotification("Screen break", "stretch — 5 min"), restPose.utterance)
        assertEquals(null, restPose.utterance?.cue)
    }

    @Test
    fun recording_a_notification_keeps_its_cue() {
        val next = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.RecordNotification("Screen break over", "Resume your work", 1_000, VoiceCue.ResumeWork),
        )
        assertEquals(VoiceCue.ResumeWork, next.notificationLog.single().cue)
    }
}
