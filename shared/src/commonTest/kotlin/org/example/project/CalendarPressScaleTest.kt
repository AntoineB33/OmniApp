package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.model.TaskId
import org.example.project.ui.EDIT_LABEL_TASK
import org.example.project.ui.EDIT_LABEL_TASK_PANEL
import org.example.project.ui.PlacedRecord
import org.example.project.ui.calendarEditChoices
import org.example.project.ui.pressHour
import org.example.project.ui.pressSpans

/**
 * PRD §8: **a press is converted to an hour at the scale the column has NOW.**
 *
 * The anomaly this pins: right-clicking a task panel offered "add…" and nothing else. The cursor was inside
 * the panel and the panel was in the hit list — but the column's gesture closure is keyed on its DAY, so it
 * outlives every zoom (a `Modifier.pointerInput` whose key has not changed keeps running the lambda it
 * started with, captures and all), and it was still dividing the press by the hour height the column had
 * when that coroutine started. Zoomed IN since, the press converts to an hour PAST THE END OF THE DAY, where
 * nothing is: no rows, so no "edit…" — and the same stale scale anchored "add…" at the wrong time.
 *
 * The scale is a parameter of [pressHour]/[pressSpans] precisely so this can be said in a test: one pixel is
 * two different hours at two zooms, so the hit test is only ever right if the scale it is handed is live.
 */
class CalendarPressScaleTest {

    /** The 15:53→16:38 record block from the account the anomaly was reported on. */
    private val panel = PlacedRecord(
        title = "planning",
        startHour = 15.9f,
        endHour = 16.64f,
        scheduled = false,
        entryId = "p",
        entryIds = listOf("p"),
        taskId = TaskId("task/user/307"),
        fullStartMillis = 0L,
        fullEndMillis = 0L,
    )

    /** A press inside the panel at the zoom the user is actually looking at. */
    private val zoomedInPx = 120f
    private val zoomedOutPx = 40f
    private val pressY = 16.2f * zoomedInPx

    @Test
    fun onePixelIsTwoHoursAtTwoZooms() {
        assertEquals(16.2f, pressHour(pressY, zoomedInPx), 0.001f)
        // The same press read at the pre-zoom scale: three times the hour, so past the end of the day, where
        // the clamp is all that keeps it on the grid at all.
        assertEquals(23.999f, pressHour(pressY, zoomedOutPx))
    }

    @Test
    fun theLiveScaleFindsThePanelAndTheStaleOneFindsNothing() {
        assertTrue(pressSpans(panel, pressY, zoomedInPx), "the cursor is inside the panel")
        assertFalse(pressSpans(panel, pressY, zoomedOutPx), "at the stale scale the press is hours below it")
    }

    /**
     * The symptom, end to end: the chooser has rows only when the hit test used the live scale. With the
     * stale one the menu is "add…" alone — which is exactly what was reported on a panel the cursor was in.
     */
    @Test
    fun theStaleScaleIsTheAddOnlyMenu() {
        val live = listOf(panel).filter { pressSpans(it, pressY, zoomedInPx) }
        assertEquals(
            listOf(EDIT_LABEL_TASK, EDIT_LABEL_TASK_PANEL),
            calendarEditChoices(live).map { it.label },
        )
        val stale = listOf(panel).filter { pressSpans(it, pressY, zoomedOutPx) }
        assertEquals(emptyList(), calendarEditChoices(stale))
    }

    /** A press is always somewhere in the day, and a column that has not been measured yet is hour 0. */
    @Test
    fun theHourIsAlwaysInsideTheDay() {
        assertEquals(0f, pressHour(-5f, zoomedInPx))
        assertEquals(23.999f, pressHour(1_000_000f, zoomedInPx))
        assertEquals(0f, pressHour(100f, 0f))
        assertEquals(0f, pressHour(100f, -1f))
    }
}
