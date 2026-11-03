package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock

/**
 * User rule 2026-11-03: *"Suppose there is only one task A … types ABC, then presses enter. This means that in the
 * calendar, there is A, then AB, then ABC. If the user had pressed escape instead, this would have been rewritten as only
 * A. If the user had pressed enter then ctrl+z, same thing."* and *"It must be one per keystroke. The bouncing is for when
 * it is sent to the server."*
 */
class EditKeystrokeTasksTest {
    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L
    private var now = t0

    private inner class Clock : AppClock {
        override fun nowMillis(): Long = now
    }

    /**
     * The reducer's hooks are globals an engine of another test (`ServerQuotaTest`'s) leaves set — its no-screen
     * evidence alone empties the plan. Each is put to its default here, and back as it was after.
     */
    private fun scenario(body: () -> Unit) {
        val r = SchedulerReducer
        val clock = r.clock
        val device = r.deviceId
        val abandoned = r.planAbandoned
        val liveRest = r.liveRestGap
        val noScreen = r.noScreenEvidence
        val atScreen = r.atScreenEvidence
        val mode = r.tpMode
        val frozen = r.frozenScreenBreaks
        val layers = r.layerKindsAt
        val horizon = r.scheduleHorizonEndMillis
        r.clock = Clock()
        r.deviceId = { SchedulerReducer.LOCAL_DEVICE_ID }
        r.planAbandoned = { false }
        r.liveRestGap = { null }
        r.noScreenEvidence = { emptyList() }
        r.atScreenEvidence = { emptyList() }
        r.tpMode = { org.example.project.scheduler.domain.DynamicPeriods.MODE_AT_SCREEN }
        r.frozenScreenBreaks = { null }
        r.layerKindsAt = { emptySet() }
        r.scheduleHorizonEndMillis = { it + 2 * org.example.project.scheduler.domain.SchedulerDomain.SCHEDULE_GOAL_FLOOR_MILLIS }
        now = t0
        try {
            body()
        } finally {
            r.clock = clock
            r.deviceId = device
            r.planAbandoned = abandoned
            r.liveRestGap = liveRest
            r.noScreenEvidence = noScreen
            r.atScreenEvidence = atScreen
            r.tpMode = mode
            r.frozenScreenBreaks = frozen
            r.layerKindsAt = layers
            r.scheduleHorizonEndMillis = horizon
        }
    }

    private fun r(s: SchedulerState, i: SchedulerIntent) = SchedulerReducer.reduce(s, i)
    private fun plan(s: SchedulerState) = r(s, SchedulerIntent.RefreshSchedule(now))
    private fun pass(s: SchedulerState, minutes: Long): SchedulerState {
        now += minutes * minute
        return r(s, SchedulerIntent.AdvanceSchedule(now))
    }

    /** What the timeline holds behind the line: each stretch's title, in order, the same title's neighbours joined. */
    private fun behindTheLine(s: SchedulerState): List<Pair<String, Pair<Long, Long>>> {
        val fromRecords = s.tasks.values.flatMap { t -> t.record.map { Triple(t.title, it.startEpochMillis, it.endEpochMillis) } }
        val fromPanels =
            s.panels.filter { it.taskId != null && it.startEpochMillis < now }
                .map { Triple(it.title, it.startEpochMillis, minOf(it.endEpochMillis, now)) }
        val joined = ArrayList<Pair<String, Pair<Long, Long>>>()
        for ((title, from, to) in (fromRecords + fromPanels).sortedBy { it.second }) {
            val last = joined.lastOrNull()
            if (last != null && last.first == title && from <= last.second.second) {
                joined[joined.lastIndex] = title to (last.second.first to maxOf(to, last.second.second))
            } else {
                joined += title to (from to to)
            }
        }
        return joined.map { (title, span) -> title to ((span.first - t0) / minute to (span.second - t0) / minute) }
    }

    private class Typed(val state: SchedulerState, val cell: CellId)

    /** A alone on the line for ten minutes, then A → AB → ABC in Edit Mode, five minutes apart, the plan following. */
    private fun typed(): Typed {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(cell, "A"))
        s = plan(s)
        s = pass(s, 10)
        s = r(s, SchedulerIntent.BeginEdit(cell))
        s = r(s, SchedulerIntent.UpdateEditText("AB"))
        s = plan(s)
        s = pass(s, 5)
        s = r(s, SchedulerIntent.UpdateEditText("ABC"))
        s = plan(s)
        s = pass(s, 5)
        return Typed(s, cell)
    }

    @Test
    fun enter_leaves_a_then_ab_then_abc() = scenario {
        val t = typed()
        var s = r(t.state, SchedulerIntent.ExitEdit(EditExitNavigation.Down))
        s = plan(s)
        assertEquals(listOf("A" to (0L to 10L), "AB" to (10L to 15L), "ABC" to (15L to 20L)), behindTheLine(s))
        assertEquals("ABC", s.tasks[s.cells[t.cell]!!.taskId!!]!!.title)
    }

    @Test
    fun the_calendar_says_so_on_the_keystroke_without_waiting_for_a_plan() = scenario {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(cell, "A"))
        s = plan(s)
        s = pass(s, 10)
        s = r(s, SchedulerIntent.BeginEdit(cell))
        s = r(s, SchedulerIntent.UpdateEditText("AB"))
        assertEquals(listOf("A" to (0L to 10L)), behindTheLine(s))
        assertTrue(s.panels.any { it.title == "AB" && it.startEpochMillis == now }, "the run goes on under the new task")
    }

    @Test
    fun each_keystroke_is_its_own_task() = scenario {
        val t = typed()
        assertEquals(setOf("A", "AB", "ABC"), t.state.tasks.values.map { it.title }.filter { it.isNotBlank() && it != "root" }.toSet())
        assertEquals(2, t.state.editSession!!.draftTaskIds.size)
    }

    @Test
    fun escape_rewrites_it_as_a_alone() = scenario {
        val t = typed()
        var s = r(t.state, SchedulerIntent.CancelEdit)
        assertNull(s.editSession)
        s = plan(s)
        assertEquals(listOf("A" to (0L to 20L)), behindTheLine(s))
        assertEquals(setOf("A"), s.tasks.values.map { it.title }.filter { it.isNotBlank() && it != "root" }.toSet())
    }

    @Test
    fun enter_then_undo_does_the_same_whenever_it_is_pressed() = scenario {
        val t = typed()
        var s = r(t.state, SchedulerIntent.ExitEdit(EditExitNavigation.Down))
        s = plan(s)
        s = pass(s, 15) // the panels behind the line are banked as records by now
        s = r(s, SchedulerIntent.Undo)
        s = plan(s)
        assertEquals(listOf("A" to (0L to 35L)), behindTheLine(s))
        assertEquals("A", s.tasks[s.cells[t.cell]!!.taskId!!]!!.title)
        assertEquals(setOf("A"), s.tasks.values.map { it.title }.filter { it.isNotBlank() && it != "root" }.toSet())
    }

    @Test
    fun undo_still_gives_the_time_back_after_a_save_and_a_reload() = scenario {
        val t = typed()
        var s = r(t.state, SchedulerIntent.ExitEdit(EditExitNavigation.Down))
        s = plan(s)
        s = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))!!
        s = r(s, SchedulerIntent.Undo)
        s = plan(s)
        assertEquals(listOf("A" to (0L to 20L)), behindTheLine(s))
    }

    @Test
    fun a_cell_the_line_is_not_on_leaves_no_task_behind() = scenario {
        var s = SchedulerState.empty()
        val empty = s.lists[s.rootListId]!!.cellIds.last()
        s = r(s, SchedulerIntent.BeginEdit(empty))
        for (text in listOf("x", "xy", "xyz")) s = r(s, SchedulerIntent.UpdateEditText(text))
        s = r(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))
        val titles = s.tasks.values.map { it.title }
        assertTrue("xyz" in titles)
        assertFalse("x" in titles || "xy" in titles, "nothing keeps them: no row for the server, $titles")
    }

    @Test
    fun rename_stays_one_task() = scenario {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(cell, "A"))
        val id = s.cells[cell]!!.taskId
        s = r(s, SchedulerIntent.BeginEdit(cell, mode = CellEditMode.Rename))
        s = r(s, SchedulerIntent.UpdateEditText("AB"))
        s = r(s, SchedulerIntent.UpdateEditText("ABC"))
        assertEquals(id, s.cells[cell]!!.taskId)
        assertTrue(s.editSession!!.draftTaskIds.isEmpty())
    }

    @Test
    fun a_unit_and_a_session_stored_before_the_list_load_without_it() = scenario {
        val t = typed()
        val text = SchedulerStateCodec.encode(r(t.state, SchedulerIntent.ExitEdit(EditExitNavigation.Stay)))
        val older = text.replace(Regex(""","?draftTaskIds":\[[^\]]*\]"""), "").replace(Regex(""","baseTaskId":"[^"]*""""), "")
        assertFalse("draftTaskIds" in older)
        assertEquals("ABC", SchedulerStateCodec.decode(older)!!.tasks.values.first { it.title == "ABC" }.title)
    }

    @Test
    fun a_task_no_cell_holds_keeps_no_placeholder() = scenario {
        val t = typed()
        val s = r(t.state, SchedulerIntent.ExitEdit(EditExitNavigation.Down))
        val left = s.tasks.values.filter { it.title == "A" || it.title == "AB" }
        assertEquals(2, left.size, "both keep their time")
        for (task in left) assertNull(task.childListId, "${task.title} keeps a sub-list of placeholders")
        val held = s.cells.values.mapNotNullTo(HashSet()) { it.taskId }
        assertTrue(s.lists.values.all { list -> list.cellIds.all { it in s.cells } }, "no list names a cell that is gone")
        assertTrue(s.tasks.values.filter { it.id !in held }.none { it.childListId != null && s.lists[it.childListId] == null })
    }
}
