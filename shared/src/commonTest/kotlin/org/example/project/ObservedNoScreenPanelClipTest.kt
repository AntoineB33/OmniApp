package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange

/**
 * PRD §8/§9 + ADR 0002: **a stretch carrying BOTH calendar layers is a "no on-screen task" period**, and a
 * no-screen period overrides the on-screen task panels it covers.
 *
 * Only half of that was implemented. §9 already refused to BANK a record over an observed no-screen stretch
 * ([NoScreenEvidenceTest]), but the panel the record would have come from went on being drawn straight across
 * the two hatches — so the calendar showed an on-screen task running on a machine the OS reported asleep,
 * which is the exact thing the bank rule denies. [SchedulerDomain.clipPanelsForObservedNoScreen] is the other
 * half, and the two read the same set ([SchedulerDomain.observedNoScreenRegions]).
 */
class ObservedNoScreenPanelClipTest {

    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    private val onScreenId = TaskId("task/user/1")
    private val offScreenId = TaskId("task/user/2")

    /** A task is on-screen exactly when it is forbidden inside a "no on-screen task" period. */
    private val tasks: Map<TaskId, Task> =
        mapOf(
            onScreenId to Task(id = onScreenId, title = "At the desk", resilience = Task.DEFAULT_RESILIENCE),
            offScreenId to Task(id = offScreenId, title = "Thinking", resilience = emptyMap()),
        )

    private fun panel(id: String, taskId: TaskId?, from: Long, to: Long, kind: String = "") =
        TaskPanel(
            id = id,
            taskId = taskId,
            title = taskId?.let { tasks[it]!!.title } ?: "period",
            startEpochMillis = from,
            endEpochMillis = to,
            auto = taskId != null,
            periodKind = kind,
            inactivity = kind.isNotEmpty(),
        )

    private val observed = listOf(TaskTimeRange(NOW - 2 * HOUR, NOW - HOUR))

    @Test
    fun an_on_screen_task_panel_is_cut_where_the_devices_observed_no_screen() {
        val out =
            SchedulerDomain.clipPanelsForObservedNoScreen(
                listOf(panel("auto/0", onScreenId, NOW - 3 * HOUR, NOW)),
                tasks,
                observed,
            ).sortedBy { it.startEpochMillis }
        assertEquals(2, out.size, "the observed hour must be cut out of the panel: $out")
        assertEquals(NOW - 3 * HOUR to NOW - 2 * HOUR, out[0].startEpochMillis to out[0].endEpochMillis)
        assertEquals(NOW - HOUR to NOW, out[1].startEpochMillis to out[1].endEpochMillis)
        // Two display blocks can never share one id.
        assertEquals(2, out.map { it.id }.distinct().size)
    }

    /** §9 lets an off-screen task run in a no-screen period, so its panel there is true. */
    @Test
    fun an_off_screen_task_panel_is_left_alone() {
        val panels = listOf(panel("auto/0", offScreenId, NOW - 3 * HOUR, NOW))
        assertEquals(panels, SchedulerDomain.clipPanelsForObservedNoScreen(panels, tasks, observed))
    }

    @Test
    fun an_on_screen_task_record_is_cut_where_the_devices_observed_no_screen() {
        val record = listOf(TaskTimeRange(NOW - 3 * HOUR, NOW))
        val out = SchedulerDomain.clipRecordsForObservedNoScreen(record, tasks[onScreenId], observed)
        assertEquals(2, out.size, "the observed hour must be cut out of the record: $out")
        assertEquals(NOW - 3 * HOUR to NOW - 2 * HOUR, out[0].startEpochMillis to out[0].endEpochMillis)
        assertEquals(NOW - HOUR to NOW, out[1].startEpochMillis to out[1].endEpochMillis)
    }

    @Test
    fun an_off_screen_task_record_is_left_alone() {
        val record = listOf(TaskTimeRange(NOW - 3 * HOUR, NOW))
        assertEquals(record, SchedulerDomain.clipRecordsForObservedNoScreen(record, tasks[offScreenId], observed))
    }

    /** A period is not work: it is what the cut is made OF, and it must survive its own region. */
    @Test
    fun a_restrictive_period_and_a_taskless_panel_are_left_alone() {
        val panels =
            listOf(
                panel("period/0", null, NOW - 3 * HOUR, NOW, kind = PeriodKinds.NO_TASK),
                panel("period/1", null, NOW - 3 * HOUR, NOW),
            )
        assertEquals(panels, SchedulerDomain.clipPanelsForObservedNoScreen(panels, tasks, observed))
    }

    @Test
    fun a_panel_wholly_inside_the_observed_region_disappears() {
        val out =
            SchedulerDomain.clipPanelsForObservedNoScreen(
                listOf(panel("auto/0", onScreenId, NOW - 2 * HOUR + 60_000, NOW - HOUR - 60_000)),
                tasks,
                observed,
            )
        assertTrue(out.isEmpty(), "an on-screen panel entirely inside an observed no-screen stretch: $out")
    }

    /**
     * No evidence must change nothing at all — the identity the "a failed lock query is NOT evidence" rule
     * relies on, since `App.kt` passes an empty list whenever the own scan has not succeeded.
     */
    @Test
    fun no_observed_region_is_a_no_op() {
        val panels = listOf(panel("auto/0", onScreenId, NOW - 3 * HOUR, NOW))
        assertTrue(SchedulerDomain.clipPanelsForObservedNoScreen(panels, tasks, emptyList()) === panels)
    }
}
