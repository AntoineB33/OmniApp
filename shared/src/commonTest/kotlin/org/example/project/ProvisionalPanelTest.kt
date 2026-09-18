package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.CalendarRecord
import org.example.project.ui.recordsForDay

/**
 * `docs/scheduler_requirements.md` § *Progressive Calculation*: which task panels the calendar may NOT draw as
 * settled.
 *
 * *"When the schedule is definitive for any t < $t_1$, it means that for all the next set of rules the
 * scheduler will return until it is done, they will all indicate the same schedule rules for any t < $t_1$."*
 * The front $t_1$ is exactly how far a fill materializes into `state.panels`
 * ([SchedulerDomain.definitiveScheduleFrontMillis]); past it the calendar is drawing the far-week display fill,
 * which is never retained and is recomputed from scratch the next time that week is looked at. Those panels
 * are drawn with blurred edges, and this pins WHICH ones they are.
 */
class ProvisionalPanelTest {

    private val tz = TimeZone.UTC
    private val hour = 60L * 60 * 1000
    private val now = LocalDateTime(2026, 9, 18, 9, 0).toInstant(tz).toEpochMilliseconds()

    private fun panel(
        id: String,
        startMillis: Long,
        endMillis: Long,
        auto: Boolean = true,
    ) = TaskPanel(
        id = id,
        taskId = TaskId("t"),
        title = "Deep work",
        startEpochMillis = startMillis,
        endEpochMillis = endMillis,
        auto = auto,
    )

    @Test
    fun theFrontIsHowFarTheFillMaterializes() {
        // A calendar showing five days — past this Friday's week end: the whole span is materialized, so the
        // front is its end.
        val fiveDays = now + 5 * 24 * hour
        assertEquals(fiveDays, SchedulerDomain.definitiveScheduleFrontMillis(now, fiveDays, tz))

        // A calendar scrolled two weeks out: the fill stops at the 168 h ceiling and the rest is the far-week
        // display fill, so the front is the ceiling — not the displayed end.
        val twoWeeks = now + 14 * 24 * hour
        assertEquals(
            now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS,
            SchedulerDomain.definitiveScheduleFrontMillis(now, twoWeeks, tz),
        )

        // No calendar open: $t_{goal}$ still reaches the END OF THE CURRENT WEEK, so the front does too —
        // Monday 2026-09-21 00:00 UTC, from a Friday 09:00 now-line.
        assertEquals(
            now + 2 * 24 * hour + 15 * hour,
            SchedulerDomain.definitiveScheduleFrontMillis(now, null, tz),
        )
    }

    @Test
    fun onlyTheFillsOwnPanelsPastTheFrontAreProvisional() {
        val front = now + 4 * hour

        // Below the front: published by a progressive stage, and an extension keeps what a stage published.
        assertFalse(SchedulerDomain.isProvisionalPanel(panel("a", now, now + hour), front))
        // Ending exactly AT the front is still definitive: the guarantee is over `t < t_1`.
        assertFalse(SchedulerDomain.isProvisionalPanel(panel("b", now + 3 * hour, front), front))
        // Straddling it: the front did not bound this run's length, so it is unsettled as a whole.
        assertTrue(SchedulerDomain.isProvisionalPanel(panel("c", front - hour, front + hour), front))
        // Wholly past it: the far-week display fill's.
        assertTrue(SchedulerDomain.isProvisionalPanel(panel("d", front + hour, front + 2 * hour), front))

        // § *Starting timeline* input is as fixed past the front as before it: a hand-drawn or pinned panel is
        // not the scheduler's answer, so nothing about it is provisional.
        assertFalse(
            SchedulerDomain.isProvisionalPanel(panel("e", front + hour, front + 2 * hour, auto = false), front),
        )
    }

    /**
     * The other half of the rule, and the one that would be a visible bug: a span the fill MATERIALIZED must
     * blur nothing. A run the fill let cross its own horizon would blur the last block of every near week —
     * *"a stage searches one decision window past its horizon and emits only up to the horizon"*
     * (`docs/invariants/scheduler.md` § *Progressive Calculation*), which is what this reads back through the
     * predicate that will draw it.
     */
    @Test
    fun nothingBlursInsideTheSpanTheFillMaterialized() {
        var state = SchedulerState.empty()
        listOf("A", "B").forEachIndexed { i, name ->
            val cell = state.lists[state.rootListId]!!.cellIds[i]
            state = SchedulerReducer.reduce(state, SchedulerIntent.SetCellTitle(cell, name))
        }
        state.tasks.keys.forEach {
            state = SchedulerReducer.reduce(state, SchedulerIntent.SetTaskMinimumTime(it, 10))
        }

        val front = now + 8 * hour
        val drawnAsBlocks = SchedulerDomain.fillSchedule(state, now, horizonMillis = front).filter {
            !it.screenBreak && !it.sleep && !it.id.startsWith(SchedulerDomain.BEFORE_BED_PANEL_ID_PREFIX)
        }
        assertTrue(drawnAsBlocks.any { it.auto }, "the fill should have placed something")
        val provisional = drawnAsBlocks.filter { SchedulerDomain.isProvisionalPanel(it, front) }
        assertEquals(
            emptyList(),
            provisional.map { it.title to (it.endEpochMillis - front) },
            "a materialized span must draw no unsettled block",
        )
    }

    @Test
    fun theFlagReachesTheDrawnBlock() {
        val day = LocalDate(2026, 9, 18)
        val start = LocalDateTime(2026, 9, 18, 10, 0).toInstant(tz).toEpochMilliseconds()
        val records = listOf(
            CalendarRecord(
                title = "Deep work",
                range = TaskTimeRange(start, start + hour),
                manual = true,
                entryId = "a",
                taskId = TaskId("t"),
                provisional = true,
            ),
            CalendarRecord(
                title = "Deep work",
                range = TaskTimeRange(start + 2 * hour, start + 3 * hour),
                manual = true,
                entryId = "b",
                taskId = TaskId("t"),
            ),
        )
        val placed = recordsForDay(records, day, tz)
        assertEquals(2, placed.size)
        assertTrue(placed.first { it.entryId == "a" }.provisional)
        assertFalse(placed.first { it.entryId == "b" }.provisional)
    }
}
