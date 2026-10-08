package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.ui.PanelSlice
import org.example.project.ui.PlacedRecord
import org.example.project.ui.draggedBlockBounds
import org.example.project.ui.layoutWithBreakHoles
import org.example.project.ui.refusingBreaks

/**
 * The user, 2026-10-08: *"when held the block always remembers its length while avoiding appearing where it would break
 * the requirements … If the user drags a task panel into a 15min screen break that is in the future, then the 15min
 * break retracts the held task panel, which comes out on the other side to keep its length."*
 */
class HeldBlockKeepsLengthTest {
    private val HOUR = 3_600_000L
    private val MIN = 60_000L
    private val task = TaskId("task/user/1")

    private fun range(start: Long, end: Long) = TaskTimeRange(start, end)

    private fun block(start: Long, end: Long) =
        PlacedRecord(
            title = "p", startHour = start / HOUR.toFloat(), endHour = end / HOUR.toFloat(), scheduled = false,
            entryId = "p", entryIds = listOf("p"), taskId = task, fullStartMillis = start, fullEndMillis = end,
        )

    // The 15-minute pose over 3:00–3:15.
    private val pose =
        PlacedRecord(
            title = "Pose", startHour = 3f, endHour = 3.25f, scheduled = false, screenBreak = true,
            breakKind = PeriodKinds.INACTIVITY, fullStartMillis = 3 * HOUR, fullEndMillis = 3 * HOUR + 15 * MIN,
        )
    private val refused = listOf(range(3 * HOUR, 3 * HOUR + 15 * MIN))

    @Test
    fun a_span_keeps_its_length_across_what_refuses_it() {
        // Nothing in the way: where it is put.
        assertEquals(range(HOUR, 2 * HOUR), SchedulerDomain.spanKeepingLength(HOUR, HOUR, refused))
        assertEquals(range(4 * HOUR, 5 * HOUR), SchedulerDomain.spanKeepingLength(4 * HOUR, HOUR, refused))
        // Its end touches the break: still whole.
        assertEquals(range(2 * HOUR, 3 * HOUR), SchedulerDomain.spanKeepingLength(2 * HOUR, HOUR, refused))
        // Carried into it: it comes out on the other side by as much.
        assertEquals(range(2 * HOUR + 30 * MIN, 3 * HOUR + 45 * MIN), SchedulerDomain.spanKeepingLength(2 * HOUR + 30 * MIN, HOUR, refused))
        // Begun inside it: it starts where the break ends, whole.
        assertEquals(range(3 * HOUR + 15 * MIN, 4 * HOUR + 15 * MIN), SchedulerDomain.spanKeepingLength(3 * HOUR + 5 * MIN, HOUR, refused))
        // Two breaks in the way: across both.
        val two = refused + range(3 * HOUR + 30 * MIN, 3 * HOUR + 35 * MIN)
        assertEquals(range(2 * HOUR + 30 * MIN, 3 * HOUR + 50 * MIN), SchedulerDomain.spanKeepingLength(2 * HOUR + 30 * MIN, HOUR, two))
        // What stands of each is the length asked for.
        for (start in listOf(HOUR, 2 * HOUR + 30 * MIN, 3 * HOUR + 5 * MIN, 4 * HOUR)) {
            assertEquals(HOUR, SchedulerDomain.standingLength(SchedulerDomain.spanKeepingLength(start, HOUR, two), two), "from $start")
        }
    }

    @Test
    fun a_task_panel_dragged_into_a_break_comes_out_on_the_other_side() {
        val panel = block(HOUR, 2 * HOUR)
        val breaks = refusingBreaks(panel, listOf(pose)) { _, _ -> true }
        assertEquals(refused, breaks)
        // Moved by 1h30: 2:30–3:30 asked, 2:30–3:45 taken, and the drawing shows an hour of it round the break.
        val held = draggedBlockBounds(panel, edge = null, 90 * MIN, armed = false, others = emptyList(), breaks)
        assertEquals(range(2 * HOUR + 30 * MIN, 3 * HOUR + 45 * MIN), held)
        val moved = panel.copy(startHour = 2.5f, endHour = 3.75f, fullStartMillis = held.startEpochMillis, fullEndMillis = held.endEpochMillis)
        val drawn = layoutWithBreakHoles(mapOf("p" to listOf(PanelSlice(2.5f, 3.75f, 0f, 1f))), listOf(moved), listOf(pose)) { _, _ -> true }
        assertEquals(listOf(PanelSlice(2.5f, 3f, 0f, 1f), PanelSlice(3.25f, 3.75f, 0f, 1f)), drawn.values.single())
        // Carried wholly into the break, it is whole on the other side — never gone, never drawn inside it.
        assertEquals(
            range(3 * HOUR + 15 * MIN, 4 * HOUR + 15 * MIN),
            draggedBlockBounds(panel, edge = null, 2 * HOUR + 5 * MIN, armed = false, others = emptyList(), breaks),
        )
        // Picked up again from across the break, it is the hour it was — not the hour and a quarter it spans.
        assertEquals(range(5 * HOUR, 6 * HOUR), draggedBlockBounds(moved, edge = null, 150 * MIN, armed = false, others = emptyList(), breaks))
        // A task resilient to the break is refused nowhere and goes where the hand has it.
        assertEquals(emptyList(), refusingBreaks(panel, listOf(pose)) { _, _ -> false })
        assertEquals(range(2 * HOUR + 30 * MIN, 3 * HOUR + 30 * MIN), draggedBlockBounds(panel, edge = null, 90 * MIN, armed = false, others = emptyList()))
    }
}
