package org.example.project

import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.PlanWindow
import org.example.project.scheduler.domain.ScheduleFill
import org.example.project.scheduler.domain.ScoreModel
import org.example.project.scheduler.model.TaskId

/**
 * 2026-09-17: the release app never drew a window. The EDT and two workers sat for minutes in
 * `ScoreModel.advance`, replaying the week of history: the cell index was re-derived from a `u` that had landed on a
 * cell end, and at real epoch offsets that rounds back into the same cell about a quarter of the time, so `u` never
 * moved. `ScheduleScoreTest` builds its models from 0 in whole hours, where the arithmetic is exact and it never shows.
 *
 * Run on a thread with a deadline, because the failure is a hang, not a wrong number.
 */
class ScoreAdvanceTerminatesTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun replaying_a_week_at_real_epoch_millis_through_compensated_pieces_terminates() {
        val from = 1_789_031_234_567L // 2026-09-10 and some odd millis
        val to = from + 8 * 24 * hour
        val tasks =
            listOf(
                PlanTask(TaskId("A"), 0.37, 7 * minute),
                PlanTask(TaskId("B"), 0.41, 13 * minute + 1_234),
                PlanTask(TaskId("C"), 0.22, 3 * minute + 777),
            )
        // Deprivations with odd edges, so pieces start at non-round offsets and carry compensation.
        val windows =
            (0 until 40).map { d ->
                val s = from + d * 4 * hour + 1_111L * d + 37
                PlanWindow(s, s + 47 * minute + 313, mapOf(TaskId(listOf("A", "B", "C")[d % 3]) to 0.0))
            }
        val model = ScoreModel(tasks, emptyList(), windows, from, to)
        val now = from + 7 * 24 * hour + 12_345
        val history =
            (0 until 400).map { k ->
                val s = from + k * 25 * minute + 9_871L * k
                PlanBlock(tasks[k % 3].id, s, s + 19 * minute + 4_321)
            }.filter { it.endMillis <= now }

        var done = false
        val worker = Thread {
            val cursor = ScheduleFill.replay(model, history, now)
            model.settle(cursor, model.uEnd)
            done = true
        }
        worker.isDaemon = true
        worker.start()
        worker.join(20_000)
        assertTrue(done, "ScoreModel.advance did not finish within 20 s: the cell walk stalled on a cell end")
    }
}
