package org.example.project

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.ui.CalendarElementDrag
import org.example.project.ui.PlacedRecord
import org.example.project.ui.calendarDragBlocks

/**
 * User rule 2026-10-07, the Search window's "Drag on the calendar": *"period A 10h-11h, period B 9h-12h, the user
 * right-clicks on 10h30, clicks on 'edit…' … adds both of them in the added elements list … presses the mouse on the
 * 'drag' button. The calendar appears, and period A and period B follow the mouse such that the mouse is always 30
 * minutes after the start of period A, and 1h30 after the start of period B."*
 */
class CalendarElementDragTest {
    private val HOUR = 3_600_000L
    private val MIN = 60_000L
    private val DAY0 = 1_700_000_000_000L / (24 * HOUR) * (24 * HOUR)

    private fun at(hour: Double) = DAY0 + (hour * HOUR).toLong()

    private fun record(
        id: String,
        from: Double,
        to: Double,
        kind: String = "",
        taskId: TaskId? = null,
        sleep: Boolean = false,
        screenBreak: Boolean = false,
        breakKind: String = "",
        alarm: Boolean = false,
        reminder: Boolean = false,
    ) = PlacedRecord(
        title = id,
        startHour = from.toFloat(),
        endHour = to.toFloat(),
        scheduled = false,
        manual = true,
        entryId = id,
        entryIds = listOf(id),
        taskId = taskId,
        restrictiveKind = kind,
        sleep = sleep,
        screenBreak = screenBreak,
        breakKind = breakKind,
        alarm = alarm,
        reminder = reminder,
        fullStartMillis = at(from),
        fullEndMillis = at(to),
    )

    private val periodA = record("a", 10.0, 11.0, kind = "kind a")
    private val periodB = record("b", 9.0, 12.0, kind = "kind b")
    private val both = SearchDomain.CalendarDragTargets(periodKinds = setOf("kind a", "kind b"))

    /** A calendar of one day column 240 px wide, an hour every 100 px, from the window's top. */
    private fun dragOver(records: List<PlacedRecord>, commits: MutableList<Triple<String, Long, Long>>): CalendarElementDrag {
        val drag = CalendarElementDrag()
        drag.columns[DAY0] =
            CalendarElementDrag.Column(
                timeAt = { p -> if (p.x in 0f..240f) DAY0 + (p.y / 100f * HOUR).toLong() else null },
                records = { records },
            )
        drag.commitBounds = { block, start, end, _ -> commits += Triple(block.entryId.orEmpty(), start, end) }
        return drag
    }

    /** The press as the action's button makes it by default: the blocks the elements have at the right-click. */
    private fun CalendarElementDrag.beginAt(targets: SearchDomain.CalendarDragTargets, atMillis: Long): Boolean =
        begin(calendarDragBlocks(blocksOf(targets), targets, atMillis), atMillis)

    @Test
    fun the_field_lists_every_block_of_the_added_elements_and_the_right_clicked_ones_are_the_default() {
        val other = record("c", 13.0, 14.0, kind = "kind a")
        val drag = dragOver(listOf(periodA, periodB, other), mutableListOf())
        assertEquals(listOf("b", "a", "c"), drag.blocksOf(both).map { it.entryId }, "in the timeline's order")
        assertEquals(listOf("b", "a"), calendarDragBlocks(drag.blocksOf(both), both, at(10.5)).map { it.entryId })
        // Blocks picked by hand are held as picked, from the instant the button gives.
        assertTrue(drag.begin(listOf(other), other.fullStartMillis))
        drag.moveTo(Offset(100f, 1500f))
        assertEquals(listOf("c" to at(15.0)), drag.targets().map { (block, to) -> block.entryId to to.startEpochMillis })
    }

    @Test
    fun a_held_period_is_put_over_what_a_mode_1_line_leaves_of_it_and_stays_where_it_was_when_nothing_is_left() {
        val commits = mutableListOf<Triple<String, Long, Long>>()
        val drag = dragOver(listOf(periodA, record("p", 10.0, 11.0, taskId = TaskId("t"))), commits)
        val line = at(14.0)
        // The line in mode 1 is not in "no screen": a period there is ]line; its end], and gone once the line has all of it.
        drag.atLine = { _, range ->
            when {
                range.endEpochMillis <= line || range.startEpochMillis >= line -> range
                range.endEpochMillis - line < 10 * MIN -> null
                else -> range.copy(startEpochMillis = line)
            }
        }
        val all = SearchDomain.CalendarDragTargets(periodKinds = setOf("kind a"), taskIds = setOf(TaskId("t")))
        drag.beginAt(all, at(10.5))
        drag.moveTo(Offset(100f, 1380f))
        assertEquals(
            setOf("a" to (line to at(14.3)), "p" to (at(13.3) to at(14.3))),
            drag.targets().map { (block, to) -> block.entryId.orEmpty() to (to.startEpochMillis to to.endEpochMillis) }.toSet(),
            "the period is cut at the line; a task panel is not a period",
        )
        drag.moveTo(Offset(100f, 1355f))
        assertEquals(listOf("p"), drag.targets().map { it.first.entryId }, "nothing left of the period: it is not put anywhere")
        drag.release()
        assertEquals(listOf("p"), commits.map { it.first })
    }

    @Test
    fun the_blocks_follow_the_pointer_each_keeping_its_place_under_it() {
        val commits = mutableListOf<Triple<String, Long, Long>>()
        val drag = dragOver(listOf(periodA, periodB, record("c", 13.0, 14.0, kind = "kind a")), commits)

        assertTrue(drag.beginAt(both, at(10.5)))
        assertEquals(setOf("a", "b"), drag.blocks.map { it.entryId }.toSet(), "the blocks at the right-click, not the kind's others")
        // The pointer comes onto the calendar at 14:00.
        drag.moveTo(Offset(100f, 1400f))
        assertEquals(
            setOf("a" to (at(13.5) to at(14.5)), "b" to (at(12.5) to at(15.5))),
            drag.targets().map { (block, to) -> block.entryId.orEmpty() to (to.startEpochMillis to to.endEpochMillis) }.toSet(),
            "30 min after A's start, 1 h 30 after B's",
        )
        // Off every column the blocks stay where the pointer last had them.
        drag.moveTo(Offset(900f, 300f))
        assertEquals(3 * HOUR + 30 * MIN, drag.deltaMillis)

        drag.release()
        assertEquals(
            setOf(Triple("a", at(13.5), at(14.5)), Triple("b", at(12.5), at(15.5))),
            commits.toSet(),
            "each goes through the blocks' one release",
        )
        assertTrue(drag.blocks.isEmpty())
    }

    @Test
    fun a_press_released_where_it_began_or_cut_short_moves_nothing() {
        val commits = mutableListOf<Triple<String, Long, Long>>()
        val drag = dragOver(listOf(periodA), commits)
        drag.beginAt(both, at(10.5))
        drag.release()
        drag.beginAt(both, at(10.5))
        drag.moveTo(Offset(100f, 1400f))
        drag.cancel()
        assertTrue(commits.isEmpty())
        assertTrue(drag.targets().isEmpty())
    }

    @Test
    fun a_press_with_no_block_there_holds_nothing() {
        val drag = dragOver(listOf(periodA), mutableListOf())
        assertFalse(drag.beginAt(both, at(15.0)))
        assertFalse(drag.beginAt(SearchDomain.CalendarDragTargets(), at(10.5)))
    }

    @Test
    fun which_blocks_an_added_element_has_at_an_instant() {
        val task = TaskId("t")
        val panel = record("p", 10.0, 11.0, taskId = task)
        val sleep = record("sleep/1", 0.0, 7.5, sleep = true)
        val pose = record("side/2/1", 10.49, 10.74, screenBreak = true, breakKind = PeriodKinds.BREAK_15MIN)
        val lookAway = record("side/0/2", 10.505, 10.51, screenBreak = true, breakKind = PeriodKinds.BREAK_20S)
        val ring = record("alarm-1", 10.6, 10.6, alarm = true)
        val tag = record("chore/r1/0", 10.5, 10.5, reminder = true)
        // A block crossing midnight is drawn on two days: held once.
        val all = listOf(panel, sleep, pose, lookAway, ring, tag, periodA, periodA.copy(startHour = 0f))
        fun held(targets: SearchDomain.CalendarDragTargets, hour: Double) = calendarDragBlocks(all, targets, at(hour)).map { it.entryId }

        assertEquals(listOf("p"), held(SearchDomain.CalendarDragTargets(taskIds = setOf(task)), 10.5))
        assertEquals(emptyList(), held(SearchDomain.CalendarDragTargets(taskIds = setOf(task)), 11.0), "its end is not in it")
        assertEquals(listOf("sleep/1"), held(SearchDomain.CalendarDragTargets(periodKinds = setOf(PeriodKinds.SLEEP)), 3.0))
        assertEquals(listOf("side/2/1"), held(SearchDomain.CalendarDragTargets(periodKinds = setOf(PeriodKinds.BREAK_15MIN)), 10.5))
        assertEquals(
            listOf("side/0/2"), held(SearchDomain.CalendarDragTargets(periodKinds = setOf(PeriodKinds.BREAK_20S)), 10.5),
            "a 20 s break starting within the minute the right-click names",
        )
        assertEquals(listOf("alarm-1"), held(SearchDomain.CalendarDragTargets(ringIds = setOf("alarm-1")), 10.5))
        assertEquals(listOf("chore/r1/0"), held(SearchDomain.CalendarDragTargets(reminderIds = setOf("r1")), 10.4))
        assertEquals(listOf("a"), held(SearchDomain.CalendarDragTargets(periodKinds = setOf("kind a")), 10.5))
    }
}
