package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.PlacedRecord
import org.example.project.ui.calendarElementDrafts

/**
 * User rule 2026-10-07: *"If the user simply double-clicks on a block to drag, then the block (or chip) being dragged
 * is the one at the top of the priority rank."* The chip is a reminder's tag, the top of that rank, and it had no move
 * at all. Its release is the save its own edit window makes (`AddCalendarElements`), at the instant it was dropped,
 * pinned there — what `App`'s `onCommitBounds` dispatches for a tag.
 */
class DraggedReminderTagTest {
    private val HOUR = 3_600_000L
    private val T0 = 1_700_000_000_000L

    @Test
    fun a_dragged_tag_is_the_tag_its_edit_window_would_save_there_pinned() {
        val tag = TaskPanel("chore/r1/0", null, "Water the plants", T0, T0, chore = true)
        val s0 = SchedulerState.empty().copy(panels = listOf(tag), automaticSchedule = false)
        val drawn =
            PlacedRecord(
                title = tag.title, startHour = 10f, endHour = 10f, scheduled = false, manual = true,
                entryId = tag.id, entryIds = listOf(tag.id), reminder = true, fullStartMillis = T0, fullEndMillis = T0,
            )
        val draft = calendarElementDrafts(listOf(drawn)).single()
        val to = T0 + 2 * HOUR
        val s1 =
            SchedulerReducer.reduce(
                s0,
                SchedulerIntent.AddCalendarElements(listOf(draft.copy(startMillis = to, endMillis = to, reminderPinned = true))),
            )
        val moved = s1.panels.single { it.chore }
        assertEquals(to to to, moved.startEpochMillis to moved.endEpochMillis)
        assertEquals(tag.title, moved.title)
        assertTrue(moved.pinned, "a drag is that pin: the tag holds the place it was given")
    }
}
