package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * 2026-11-18, the long simulations' first week: **an extension replays the pre-placed tasks inside the head it keeps**.
 * Replayed without them, a pre-placed block between the line and the extension's start read as its owner NOT served where
 * its target is the whole of the time — a deficit of the block's length — so the extension served that task far over its
 * share right after the head (a 0.7 % task given 125 min after its 117-min block), and the next fill, reading the block
 * as served, found it as far AHEAD and left time to nobody to bring it back (136 min).
 */
class ExtensionHeadBlocksTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val t0 = 1_900_800_000_000L / (24 * hour) * (24 * hour)

    @BeforeTest
    fun horizon() = CalendarHorizonFixture.show(24 * hour)

    @AfterTest
    fun close() = CalendarHorizonFixture.close()

    @Test
    fun a_pre_placed_block_inside_the_kept_head_counts_as_served() {
        var s = SchedulerState.empty().copy(sleep = null)
        for (title in listOf("Rare", "Main", "Other")) {
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), title))
        }
        val cells = s.lists[s.rootListId]!!.cellIds
        fun id(title: String) = s.tasks.values.first { it.title == title }.id
        // "Rare" holds about 1 % of the time: its window is days long, so two hours of it are far over its share.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cells[0], 0, 0.1))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cells[1], 0, 5.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cells[2], 0, 5.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(id("Rare"), 20))
        // The user's two hours of it, after the line and before where the extension will start (the head's end, a day on).
        val blockStart = t0 + 14 * hour
        val blockEnd = blockStart + 2 * hour
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskPanel(id("Rare"), "Rare", blockStart, blockEnd, PanelPins(existence = true)))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(t0))
        val headEnd = s.panels.filter { it.auto }.maxOf { it.endEpochMillis }
        assertTrue(headEnd > blockEnd, "the block lies inside the head the extension keeps")
        s = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(t0 + 12 * hour))
        s = SchedulerReducer.reduce(s, SchedulerIntent.ExtendSchedule(t0 + 12 * hour))
        val rareAfterHead = s.panels.filter { it.auto && it.taskId == id("Rare") && it.startEpochMillis >= headEnd }
            .sumOf { it.endEpochMillis - it.startEpochMillis }
        assertTrue(
            rareAfterHead <= 20 * min,
            "the extension served the task its block already over-served, ${rareAfterHead / min} min, as if the block had not happened",
        )
    }
}
