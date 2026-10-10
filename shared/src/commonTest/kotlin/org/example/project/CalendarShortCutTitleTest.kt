package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.model.TaskId
import org.example.project.ui.PlacedRecord
import org.example.project.ui.SHORT_CUT_MILLIS
import org.example.project.ui.blocksContinuingAfterShortCut
import org.example.project.ui.calendarBlockKey

/**
 * User rule 2026-10-10: *"In the calendar, if a panel is cut somewhere by only 20 seconds, then the title isn't shown
 * again right after the cut."* — [blocksContinuingAfterShortCut].
 */
class CalendarShortCutTitleTest {
    private val MIN = 60_000L
    private val T0 = 1_700_000_000_000L

    private fun block(id: String, task: String?, title: String, from: Long, to: Long) =
        PlacedRecord(
            title = title,
            startHour = (from - T0) / 3_600_000f,
            endHour = (to - T0) / 3_600_000f,
            scheduled = true,
            entryId = id,
            taskId = task?.let(::TaskId),
            fullStartMillis = from,
            fullEndMillis = to,
        )

    private fun continuing(vararg blocks: PlacedRecord) = blocksContinuingAfterShortCut(blocks.toList())

    @Test
    fun the_block_after_a_cut_of_twenty_seconds_writes_no_title_and_the_one_after_a_longer_cut_does() {
        val a1 = block("a1", "A", "A", T0, T0 + 20 * MIN)
        val a2 = block("a2", "A", "A", T0 + 20 * MIN + SHORT_CUT_MILLIS, T0 + 40 * MIN)
        val a3 = block("a3", "A", "A", T0 + 45 * MIN, T0 + 60 * MIN)
        assertEquals(setOf(calendarBlockKey(a2)), continuing(a3, a1, a2))
        // One millisecond more than twenty seconds is a cut long enough to say again who is back.
        val late = block("a2", "A", "A", T0 + 20 * MIN + SHORT_CUT_MILLIS + 1, T0 + 40 * MIN)
        assertEquals(emptySet(), continuing(a1, late))
        // Two blocks that touch are not cut at all: each is its own block and keeps its title.
        assertEquals(emptySet(), continuing(a1, block("a2", "A", "A", T0 + 20 * MIN, T0 + 40 * MIN)))
    }

    @Test
    fun it_is_the_same_task_that_continues() {
        val a = block("a", "A", "A", T0, T0 + 20 * MIN)
        val b = block("b", "B", "B", T0 + 20 * MIN + 20_000, T0 + 40 * MIN)
        assertEquals(emptySet(), continuing(a, b))
        // Blocks with no task behind them are told apart by their title.
        val n1 = block("n1", null, "Call", T0, T0 + 10 * MIN)
        val n2 = block("n2", null, "Call", T0 + 10 * MIN + 20_000, T0 + 20 * MIN)
        val n3 = block("n3", null, "Mail", T0 + 20 * MIN + 20_000, T0 + 30 * MIN)
        assertEquals(setOf(calendarBlockKey(n2)), continuing(n1, n2, n3))
        // A chain of look-aways: every block but the first is a continuation.
        val c2 = block("c2", "A", "A", T0 + 20 * MIN + 20_000, T0 + 40 * MIN)
        val c3 = block("c3", "A", "A", T0 + 40 * MIN + 20_000, T0 + 60 * MIN)
        assertEquals(setOf(calendarBlockKey(c2), calendarBlockKey(c3)), continuing(a, c2, c3))
    }
}
