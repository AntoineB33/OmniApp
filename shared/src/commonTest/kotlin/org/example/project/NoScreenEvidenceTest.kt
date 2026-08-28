package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §9/§12: the OS-EVIDENCE half of the "assume nothing happened" rule, and the retroactive strip.
 *
 * The rule pinned by [NoScreenInactivityPanelTest] used to key on hand-drawn "No screen" PANELS alone, and the
 * §8 contextual-menu action is the only thing that creates one — so on an account where the user had never
 * drawn one it never fired at all, and the app banked records straight through a machine its own OS reported
 * asleep. Account 3 carried 43 h of recorded on-screen "work" across 206 records that way (2026-08-24).
 *
 * The second source is `SchedulerReducer.noScreenEvidence`: the two calendar layers' OS lock/standby evidence
 * intersected (`SchedulerDomain.observedNoScreenRegions`) — the same "a stretch carrying BOTH layers is a
 * no-screen period" identity the calendar draws, read for the scheduler.
 */
class NoScreenEvidenceTest {

    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    @AfterTest
    fun resetSeam() {
        // The seam is a global var; leaving one installed would leak evidence into every later test.
        SchedulerReducer.noScreenEvidence = { emptyList() }
    }

    private fun withEvidence(vararg ranges: TaskTimeRange) {
        SchedulerReducer.noScreenEvidence = { ranges.toList() }
    }

    /** A single on-screen task "Solo". */
    private fun oneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, 45))
        return s to solo
    }

    /** An elapsed 3-hour auto panel, the shape the advance tick banks. */
    private fun elapsedPanel(state: SchedulerState, taskId: TaskId) =
        state.copy(
            panels = state.panels + TaskPanel(
                id = "auto/0",
                taskId = taskId,
                title = "Solo",
                startEpochMillis = NOW - 3 * HOUR,
                endEpochMillis = NOW,
                auto = true,
            ),
        )

    private fun sortedRecord(state: SchedulerState, taskId: TaskId) =
        state.tasks[taskId]!!.record.sortedBy { it.startEpochMillis }

    // ----- banking against observed evidence -------------------------------------------------

    @Test
    fun advance_banks_no_record_over_observed_evidence_with_no_panel_drawn() {
        val (s0, solo) = oneTask()
        val s = elapsedPanel(s0, solo)
        // No AddNoScreenPeriod anywhere: the ONLY signal is what the devices observed. This is the account-3
        // shape, where the old panel-only rule banked all three hours as completed work.
        assertTrue(s.panels.none { it.noScreen }, "the fixture must carry no hand-drawn no-screen panel")
        withEvidence(TaskTimeRange(NOW - 2 * HOUR, NOW - HOUR))
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(NOW))
        val record = sortedRecord(advanced, solo)
        assertEquals(2, record.size, "the observed hour must be a hole, not completed work: $record")
        assertEquals(NOW - 3 * HOUR to NOW - 2 * HOUR, record[0].startEpochMillis to record[0].endEpochMillis)
        assertEquals(NOW - HOUR to NOW, record[1].startEpochMillis to record[1].endEpochMillis)
        // …and the covered span still materializes as a real grey panel, as the drawn-panel path does.
        val inactivity = advanced.panels.single { it.inactivity }
        assertEquals(NOW - 2 * HOUR, inactivity.startEpochMillis)
        assertEquals(NOW - HOUR, inactivity.endEpochMillis)
    }

    @Test
    fun observed_evidence_and_a_drawn_panel_are_unioned_not_replaced() {
        val (s0, solo) = oneTask()
        // The drawn panel covers 3h→2h back; the evidence covers 2h→1h back. Both must bite.
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddNoScreenPeriod(NOW - 3 * HOUR, NOW - 2 * HOUR))
        s = elapsedPanel(s, solo)
        withEvidence(TaskTimeRange(NOW - 2 * HOUR, NOW - HOUR))
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(NOW))
        val record = sortedRecord(advanced, solo)
        assertEquals(1, record.size, "only the last hour was at a screen: $record")
        assertEquals(NOW - HOUR to NOW, record[0].startEpochMillis to record[0].endEpochMillis)
    }

    @Test
    fun observed_evidence_does_not_stop_an_off_screen_task_recording() {
        val (s0, solo) = oneTask()
        // PRD §9: an off-screen task is ALLOWED to run in a no-screen period, so its record over one is true.
        val offScreen = s0.copy(tasks = s0.tasks + (solo to s0.tasks[solo]!!.copy(resilience = emptyMap())))
        val s = elapsedPanel(offScreen, solo)
        withEvidence(TaskTimeRange(NOW - 2 * HOUR, NOW - HOUR))
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(NOW))
        val record = advanced.tasks[solo]!!.record
        assertEquals(1, record.size, "an off-screen task banks its whole span: $record")
        assertEquals(NOW - 3 * HOUR to NOW, record[0].startEpochMillis to record[0].endEpochMillis)
    }

    @Test
    fun no_evidence_and_no_panel_banks_the_whole_span_as_before() {
        val (s0, solo) = oneTask()
        val s = elapsedPanel(s0, solo)
        // The seam defaults to empty (no engine / tests): behaviour must be exactly what it was before it existed.
        val advanced = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(NOW))
        val record = advanced.tasks[solo]!!.record
        assertEquals(1, record.size)
        assertEquals(NOW - 3 * HOUR to NOW, record[0].startEpochMillis to record[0].endEpochMillis)
        assertTrue(advanced.panels.none { it.inactivity }, "nothing observed means nothing to materialize")
    }

    // ----- the retroactive strip -------------------------------------------------------------

    /** A task whose record already holds one banked span, as an older build would have left it. */
    private fun bankedRecord(span: TaskTimeRange): Pair<SchedulerState, TaskId> {
        val (s0, solo) = oneTask()
        return s0.copy(tasks = s0.tasks + (solo to s0.tasks[solo]!!.copy(record = listOf(span)))) to solo
    }

    @Test
    fun strip_removes_the_covered_part_of_an_on_screen_record_and_greys_it() {
        val (s, solo) = bankedRecord(TaskTimeRange(NOW - 3 * HOUR, NOW))
        val stripped = SchedulerReducer.reduce(
            s,
            SchedulerIntent.StripNoScreenRecords(listOf(TaskTimeRange(NOW - 2 * HOUR, NOW - HOUR))),
        )
        val record = sortedRecord(stripped, solo)
        assertEquals(2, record.size, "the observed hour must be carved out of the stored record: $record")
        assertEquals(NOW - 3 * HOUR to NOW - 2 * HOUR, record[0].startEpochMillis to record[0].endEpochMillis)
        assertEquals(NOW - HOUR to NOW, record[1].startEpochMillis to record[1].endEpochMillis)
        val inactivity = stripped.panels.single { it.inactivity }
        assertEquals(NOW - 2 * HOUR, inactivity.startEpochMillis)
        assertEquals(NOW - HOUR, inactivity.endEpochMillis)
    }

    @Test
    fun strip_is_idempotent() {
        val (s, _) = bankedRecord(TaskTimeRange(NOW - 3 * HOUR, NOW))
        val ranges = listOf(TaskTimeRange(NOW - 2 * HOUR, NOW - HOUR))
        val once = SchedulerReducer.reduce(s, SchedulerIntent.StripNoScreenRecords(ranges))
        val twice = SchedulerReducer.reduce(once, SchedulerIntent.StripNoScreenRecords(ranges))
        // Same INSTANCE: the start-up pass must not churn storage or re-materialize a second grey panel.
        assertTrue(twice === once, "a second strip must be a no-op")
    }

    @Test
    fun strip_covering_no_record_is_a_no_op() {
        val (s, _) = bankedRecord(TaskTimeRange(NOW - 3 * HOUR, NOW))
        val untouched = SchedulerReducer.reduce(
            s,
            SchedulerIntent.StripNoScreenRecords(listOf(TaskTimeRange(NOW + HOUR, NOW + 2 * HOUR))),
        )
        assertTrue(untouched === s, "a strip covering no record must return the same state")
    }

    @Test
    fun strip_leaves_an_off_screen_task_alone() {
        val (s0, solo) = bankedRecord(TaskTimeRange(NOW - 3 * HOUR, NOW))
        val s = s0.copy(tasks = s0.tasks + (solo to s0.tasks[solo]!!.copy(resilience = emptyMap())))
        val stripped = SchedulerReducer.reduce(
            s,
            SchedulerIntent.StripNoScreenRecords(listOf(TaskTimeRange(NOW - 2 * HOUR, NOW - HOUR))),
        )
        assertTrue(stripped === s, "an off-screen task's record over a no-screen period is true work")
    }
}
