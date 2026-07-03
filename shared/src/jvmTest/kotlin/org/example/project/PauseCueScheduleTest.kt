package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.SideTask
import org.example.project.scheduler.model.TaskPanel

/**
 * PRD §15 / ARCHITECTURE.md §8: the "next pause-end instant" the engine publishes to `pause_cue_schedule` and
 * schedules the phone's local cue from. Only a `restBreak` rest pose (a `sideTask` panel whose title matches a
 * `restBreak` side task) counts — the look-away and ordinary task panels are ignored — and only ends strictly
 * after `now`; when none remain the result is null (the engine then clears the schedule).
 */
class PauseCueScheduleTest {
    private fun pose(title: String, start: Long, end: Long) =
        TaskPanel(id = "p-$start", taskId = null, title = title, startEpochMillis = start, endEpochMillis = end, sideTask = true)

    private val rest = SideTask(title = "5-min pose", intervalMillis = 60_000, durationMillis = 300_000, restBreak = true)
    private val lookAway = SideTask(title = "Look away", intervalMillis = 60_000, durationMillis = 20_000, restBreak = false)

    @Test
    fun picks_earliest_rest_pose_end_after_now() {
        val panels = listOf(pose("5-min pose", 1_000, 2_000), pose("5-min pose", 5_000, 6_000))
        assertEquals(2_000L, SchedulerEngine.nextRestPoseEndMillis(panels, listOf(rest), now = 500))
        // Once the first pose's end is reached, the next one is chosen.
        assertEquals(6_000L, SchedulerEngine.nextRestPoseEndMillis(panels, listOf(rest), now = 3_000))
    }

    @Test
    fun ignores_look_away_and_ordinary_panels() {
        val panels = listOf(
            pose("Look away", 1_000, 1_020), // a side task but NOT a rest pose
            TaskPanel(id = "t", taskId = null, title = "Work", startEpochMillis = 1_000, endEpochMillis = 9_000),
        )
        assertNull(SchedulerEngine.nextRestPoseEndMillis(panels, listOf(rest, lookAway), now = 0))
    }

    @Test
    fun null_when_all_poses_are_past() {
        val panels = listOf(pose("5-min pose", 1_000, 2_000))
        // Strictly-after: an end exactly at `now` no longer qualifies.
        assertNull(SchedulerEngine.nextRestPoseEndMillis(panels, listOf(rest), now = 2_000))
    }
}
