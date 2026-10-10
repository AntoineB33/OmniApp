package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TimelineLevels
import org.example.project.scheduler.model.HiddenPanel
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.EntityRows
import org.example.project.scheduler.sync.SnapshotMerge

/**
 * `docs/scheduler_input_requirements.md`: *"The timeline has … hidden levels called tm_levels. When a blue/orange
 * outlined block is positioned at t_r, then what was there before is added at t_r to the lowest tm_level where nothing
 * is at t_r. What is in the timeline is the result of the overlap of those tm_levels, applying them from top to
 * bottom, ignoring those that are incompatible with the higher tm_levels."* — [TimelineLevels], and the document's
 * own examples read through the reducer.
 */
class TimelineLevelsTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR

    /** Ahead of the real line (the reducer reads the clock): nothing here is the past, so nothing is banked or purged. */
    private val T0 = (kotlin.time.Clock.System.now().toEpochMilliseconds() / DAY + 10) * DAY
    private fun h(hour: Int, minute: Int = 0) = T0 + hour * HOUR + minute * MIN

    private val pins = PanelPins(existence = true)

    /** Tasks A and B, both at a screen (resilience 0 to "no screen"), no automatic schedule, no night. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        listOf("A", "B").forEachIndexed { i, title ->
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        return s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0), automaticSchedule = false, focusedWindow = HistoryWindow.Calendar)
    }

    private fun SchedulerState.task(title: String): TaskId = tasks.values.first { it.title == title }.id

    private fun SchedulerState.period(kind: String, from: Long, to: Long) =
        SchedulerReducer.reduce(this, SchedulerIntent.AddRestrictivePeriod(kind, from, to))

    private fun SchedulerState.panel(title: String, from: Long, to: Long) =
        SchedulerReducer.reduce(this, SchedulerIntent.AddTaskPanel(task(title), title, from, to, pins))

    private fun SchedulerState.move(panel: TaskPanel, from: Long, to: Long) =
        SchedulerReducer.reduce(this, SchedulerIntent.UpdateTaskPanel(panel.id, panel.taskId, panel.title, from, to, pins, allowOverlap = true))

    private fun SchedulerState.spansOf(kind: String) =
        panels.filter { it.restrictiveKind == kind }.map { it.startEpochMillis to it.endEpochMillis }.sortedBy { it.first }

    private fun SchedulerState.spansOfTask(title: String) =
        panels.filter { it.taskId == task(title) }.map { it.startEpochMillis to it.endEpochMillis }.sortedBy { it.first }

    @Test
    fun a_task_placed_over_a_period_that_refuses_it_hides_that_part_and_gives_it_back_when_it_leaves() {
        val start = account().period(PeriodKinds.NO_SCREEN, h(10), h(14))
        val period = start.panels.single()
        val placed = start.panel("A", h(11), h(12))
        // The timeline: the period around the task; the hour under the task is on a hidden level, not gone.
        assertEquals(listOf(h(10) to h(11), h(12) to h(14)), placed.spansOf(PeriodKinds.NO_SCREEN))
        assertEquals(listOf(h(11) to h(12)), placed.spansOfTask("A"))
        assertEquals(listOf(h(11) to h(12)), placed.hiddenPanels.map { it.panel.startEpochMillis to it.panel.endEpochMillis })
        assertTrue(placed.panels.single { it.taskId != null }.tmLevel > period.tmLevel, "the positioned block stands above what was there")
        // Dragged four hours later: what it stood over is back, as the ONE period it was, under its own id.
        val task = placed.panels.single { it.taskId != null }
        val moved = placed.move(task, h(15), h(16))
        assertEquals(listOf(period.copy(tmLevel = moved.panels.first { it.id == period.id }.tmLevel)), moved.panels.filter { it.isRestrictivePeriod })
        assertEquals(listOf(h(10) to h(14)), moved.spansOf(PeriodKinds.NO_SCREEN))
        assertTrue(moved.hiddenPanels.isEmpty())
        // Dragged back and then removed: the same.
        val back = moved.move(moved.panels.single { it.taskId != null }, h(13), h(15))
        assertEquals(listOf(h(10) to h(13)), back.spansOf(PeriodKinds.NO_SCREEN))
        val removed = SchedulerReducer.reduce(back, SchedulerIntent.RemoveTaskPanel(back.panels.single { it.taskId != null }.id))
        assertEquals(listOf(h(10) to h(14)), removed.spansOf(PeriodKinds.NO_SCREEN))
        assertTrue(removed.hiddenPanels.isEmpty())
        // One unit each: Ctrl+Z walks the removal, then the drag back, back.
        val undone = SchedulerReducer.reduce(removed, SchedulerIntent.Undo)
        assertEquals(back.panels.toSet(), undone.panels.toSet())
        assertEquals(back.hiddenPanels, undone.hiddenPanels)
    }

    @Test
    fun a_period_placed_over_a_task_panel_it_refuses_hides_it_and_gives_it_back() {
        val start = account().panel("A", h(10), h(12))
        val task = start.panels.single()
        val covered = start.period(PeriodKinds.NO_SCREEN, h(11), h(13))
        assertEquals(listOf(h(10) to h(11)), covered.spansOfTask("A"))
        assertEquals(1, covered.hiddenPanels.size)
        val period = covered.panels.single { it.isRestrictivePeriod }
        val away = covered.move(period, h(15), h(17))
        assertEquals(listOf(h(10) to h(12)), away.spansOfTask("A"))
        assertEquals(task.id, away.panels.single { it.taskId != null }.id)
        assertTrue(away.hiddenPanels.isEmpty())
        // A period wholly over the task hides it whole; it is back, under its own id, when the period goes.
        val whole = start.period(PeriodKinds.INACTIVITY, h(9), h(13))
        assertTrue(whole.panels.none { it.taskId != null })
        assertEquals(listOf(task.id), whole.hiddenPanels.map { it.id })
        val gone = SchedulerReducer.reduce(whole, SchedulerIntent.RemoveTaskPanel(whole.panels.single().id))
        assertEquals(listOf(task.id), gone.panels.map { it.id })
        assertEquals(listOf(h(10) to h(12)), gone.spansOfTask("A"))
    }

    @Test
    fun a_period_edited_over_a_period_of_its_kind_takes_the_stretch_they_share() {
        // The document: "10h-12h of 'no screen' and 11h-13h of 'no screen' outlined in blue, and the user edits the
        // 10h-12h 'no screen' period by dragging its past edge 1h further into the past, there is the 9h-12h block
        // outlined in blue, and the 12h-13h block outlined in blue."
        val two = account().period(PeriodKinds.NO_SCREEN, h(11), h(13)).period(PeriodKinds.NO_SCREEN, h(10), h(12))
        val edited = two.panels.single { it.startEpochMillis == h(10) }
        val after = SchedulerReducer.reduce(two, SchedulerIntent.UpdateTaskPanel(edited.id, null, edited.title, h(9), h(12), pins))
        assertEquals(listOf(h(9) to h(12), h(12) to h(13)), after.spansOf(PeriodKinds.NO_SCREEN))
        assertTrue(after.panels.all(SchedulerDomain::isUserPlaced), "both are outlined as the user's")
        // The other period is whole underneath: with the edited one gone it is 11h-13h again.
        val gone = SchedulerReducer.reduce(after, SchedulerIntent.RemoveTaskPanel(edited.id))
        assertEquals(listOf(h(11) to h(13)), gone.spansOf(PeriodKinds.NO_SCREEN))
    }

    @Test
    fun the_level_whose_period_would_refuse_the_task_above_it_is_the_one_ignored() {
        // Example 1: "the user drags a 'no phone unlocked' period to a 'no computer unlocked' period, then drags task
        // A with 0 resilience to 'no screen'. On the timeline, it would be task A with 'no phone unlocked' and no
        // 'no computer unlocked' because it would imply a 'no screen' period which is incompatible with task A."
        val both =
            account().period(PeriodKinds.NO_COMPUTER_UNLOCKED, h(10), h(12)).period(PeriodKinds.NO_PHONE_UNLOCKED, h(10), h(12))
        assertEquals(listOf(h(10) to h(12)), both.spansOf(PeriodKinds.NO_COMPUTER_UNLOCKED))
        assertEquals(listOf(h(10) to h(12)), both.spansOf(PeriodKinds.NO_PHONE_UNLOCKED))
        val placed = both.panel("A", h(10), h(12))
        assertEquals(listOf(h(10) to h(12)), placed.spansOfTask("A"))
        assertEquals(listOf(h(10) to h(12)), placed.spansOf(PeriodKinds.NO_PHONE_UNLOCKED))
        assertEquals(emptyList(), placed.spansOf(PeriodKinds.NO_COMPUTER_UNLOCKED))
        assertEquals(listOf(PeriodKinds.NO_COMPUTER_UNLOCKED), placed.hiddenPanels.map { it.panel.restrictiveKind })
        // And with the task gone, both periods are there again.
        val gone = SchedulerReducer.reduce(placed, SchedulerIntent.RemoveTaskPanel(placed.panels.single { it.taskId != null }.id))
        assertEquals(listOf(h(10) to h(12)), gone.spansOf(PeriodKinds.NO_COMPUTER_UNLOCKED))
        assertEquals(listOf(h(10) to h(12)), gone.spansOf(PeriodKinds.NO_PHONE_UNLOCKED))
    }

    @Test
    fun a_block_dragged_twice_gives_back_what_the_first_drop_covered() {
        // (The document's third example before it was shortened: two periods dragged over a task, then on — the
        // task is there again.)
        val start = account().panel("B", h(14, 40), h(14, 50)).period(PeriodKinds.NO_SCREEN, h(11), h(12))
        val period = start.panels.single { it.isRestrictivePeriod }
        val over = start.move(period, h(14, 30), h(15, 30))
        assertEquals(emptyList(), over.spansOfTask("B"))
        val on = over.move(over.panels.single { it.isRestrictivePeriod }, h(15, 30), h(16, 30))
        assertEquals(listOf(h(14, 40) to h(14, 50)), on.spansOfTask("B"))
        assertTrue(on.hiddenPanels.isEmpty())
    }

    @Test
    fun a_task_dragged_into_another_task_hides_it_unless_shift_is_held() {
        // "Dragging task A into task B hides task B, unless shift is pressed, which makes the two task panels share
        // the width".
        val start = account().panel("B", h(10), h(12)).panel("A", h(14), h(15))
        val a = start.panels.single { it.taskId == start.task("A") }
        val plain = SchedulerReducer.reduce(start, SchedulerIntent.UpdateTaskPanel(a.id, a.taskId, a.title, h(11), h(12), pins))
        assertEquals(listOf(h(10) to h(11)), plain.spansOfTask("B"))
        assertEquals(listOf(h(11) to h(12)), plain.spansOfTask("A"))
        val shifted = SchedulerReducer.reduce(start, SchedulerIntent.UpdateTaskPanel(a.id, a.taskId, a.title, h(11), h(12), pins, allowOverlap = true))
        assertEquals(listOf(h(10) to h(12)), shifted.spansOfTask("B"))
        assertEquals(listOf(h(11) to h(12)), shifted.spansOfTask("A"))
        assertTrue(shifted.hiddenPanels.isEmpty())
        // Dragged on, without shift: B is whole again.
        val on = SchedulerReducer.reduce(plain, SchedulerIntent.UpdateTaskPanel(a.id, a.taskId, a.title, h(16), h(17), pins))
        assertEquals(listOf(h(10) to h(12)), on.spansOfTask("B"))
        // Two panels an older build left overlapping stand on one level: they go on sharing the width.
        val legacy =
            account().let { s ->
                s.copy(
                    panels = listOf(
                        TaskPanel("panel/90", s.task("A"), "A", h(10), h(12), pinned = true, pins = pins),
                        TaskPanel("panel/91", s.task("B"), "B", h(11), h(13), pinned = true, pins = pins),
                    ),
                    nextPanelCounter = 92,
                )
            }
        val touched = legacy.period(PeriodKinds.NO_COMPUTER_UNLOCKED, h(10), h(13))
        assertEquals(listOf(h(10) to h(12)), touched.spansOfTask("A"))
        assertEquals(listOf(h(11) to h(13)), touched.spansOfTask("B"))
    }

    @Test
    fun work_banked_under_a_period_laid_over_the_past_is_hidden_and_back_when_the_period_leaves() {
        // docs/scheduler_requirements.md § frozen past: "The schedule at t < now line never changes" — a period laid
        // over worked time does not destroy the work.
        val previous = SchedulerReducer.clock
        val now = h(0)
        SchedulerReducer.clock = object : org.example.project.time.AppClock { override fun nowMillis(): Long = now }
        try {
            val base = account()
            val a = base.task("A")
            val worked = listOf(TaskTimeRange(now - 10 * HOUR, now - 8 * HOUR), TaskTimeRange(now - 5 * HOUR, now - 4 * HOUR))
            val s0 = base.copy(tasks = base.tasks + (a to base.tasks.getValue(a).copy(record = worked)))
            val laid = s0.period(PeriodKinds.NO_SCREEN, now - 9 * HOUR, now - 7 * HOUR)
            assertEquals(listOf(TaskTimeRange(now - 10 * HOUR, now - 9 * HOUR), worked[1]), laid.tasks.getValue(a).record)
            val piece = laid.hiddenPanels.single()
            assertTrue(piece.record)
            assertEquals(Triple(a, now - 9 * HOUR, now - 8 * HOUR), Triple(piece.panel.taskId, piece.panel.startEpochMillis, piece.panel.endEpochMillis))
            // Never purged, however old: it is the past itself.
            assertEquals(listOf(piece), TimelineLevels.purged(listOf(piece), now + 10 * TimelineLevels.HIDDEN_KEEP_MILLIS))
            // Persisted as work.
            assertTrue(assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(laid))).hiddenPanels.single().record)
            // Shrunk off half of it: that half is back; the rest waits.
            val period = laid.panels.single { it.isRestrictivePeriod }
            val shrunk = SchedulerReducer.reduce(laid, SchedulerIntent.UpdateTaskPanel(period.id, null, period.title, now - 8 * HOUR - 30 * MIN, now - 7 * HOUR, pins))
            assertEquals(listOf(TaskTimeRange(now - 10 * HOUR, now - 8 * HOUR - 30 * MIN), worked[1]), shrunk.tasks.getValue(a).record)
            // Removed: the record is what it was, and nothing is hidden.
            val removed = SchedulerReducer.reduce(laid, SchedulerIntent.RemoveTaskPanel(period.id))
            assertEquals(worked, removed.tasks.getValue(a).record)
            assertTrue(removed.hiddenPanels.isEmpty())
            // Laying the period is one unit: Ctrl+Z gives the work back with it.
            val undone = SchedulerReducer.reduce(laid, SchedulerIntent.Undo)
            assertEquals(worked, undone.tasks.getValue(a).record)
            assertTrue(undone.hiddenPanels.isEmpty() && undone.panels.none { it.isRestrictivePeriod })
            // A task the period does not refuse keeps its record where it is.
            val resilient = s0.copy(tasks = s0.tasks + (a to s0.tasks.getValue(a).copy(resilience = mapOf(PeriodKinds.NO_SCREEN to 1.0))))
            assertEquals(worked, resilient.period(PeriodKinds.NO_SCREEN, now - 9 * HOUR, now - 7 * HOUR).tasks.getValue(a).record)
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    @Test
    fun settling_is_idempotent_and_touches_nothing_outside_the_edit() {
        val s = account().period(PeriodKinds.NO_SCREEN, h(10), h(14)).panel("A", h(11), h(12)).period(PeriodKinds.NO_SCREEN, h(30), h(31))
        var n = 0
        val again =
            TimelineLevels.settle(
                s.panels, s.hiddenPanels, s.tasks, s.periodKindConfig, listOf(TaskTimeRange(h(0), h(48))), allocate = { "fresh/${n++}" },
            )
        assertTrue(again.panels === s.panels && again.hidden === s.hiddenPanels, "a settled timeline settles to itself")
        assertEquals(0, n)
    }

    @Test
    fun the_hidden_levels_are_persisted_synced_by_rows_merged_and_purged() {
        val s = account().period(PeriodKinds.NO_SCREEN, h(10), h(14)).panel("A", h(11), h(12))
        assertEquals(1, s.hiddenPanels.size)
        // Persisted, with the level of every block.
        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        // (Compared on what a panel persists: its pins are stored as the one flag `pinned`, shown or hidden alike.)
        fun TaskPanel.stored() = listOf(id, taskId, startEpochMillis, endEpochMillis, restrictiveKind, tmLevel, tmOrigin)
        assertEquals(s.hiddenPanels.map { it.panel.stored() }, decoded.hiddenPanels.map { it.panel.stored() })
        assertEquals(s.panels.map { it.tmLevel to it.tmOrigin }, decoded.panels.map { it.tmLevel to it.tmOrigin })
        // Authoritative: in the synced projection, one row per piece.
        val rows = EntityRows.split(SchedulerStateCodec.syncFingerprint(s).statePayload)
        assertEquals(listOf(s.hiddenPanels.single().id), rows.keys.filter { it.kind == "hiddenPanels" }.map { it.id })
        // The unit that hid it survives a save: undone after a reload, the period is whole and nothing is hidden.
        val reloaded = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))).copy(focusedWindow = HistoryWindow.Calendar)
        val undone = SchedulerReducer.reduce(reloaded, SchedulerIntent.Undo)
        assertTrue(undone.hiddenPanels.isEmpty())
        assertEquals(listOf(h(10) to h(14)), undone.spansOf(PeriodKinds.NO_SCREEN))
        // A payload written before the levels existed: level 0 everywhere, nothing hidden.
        val older =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[],
             "panels":[{"id":"panel/0","title":"No screen","start":0,"end":3600000,"noScreen":true}]}
            """.trimIndent()
        val old = assertNotNull(SchedulerStateCodec.decode(older))
        assertTrue(old.hiddenPanels.isEmpty())
        assertEquals(0, old.panels.single().tmLevel)
        // Merged piece by piece: a piece one device hid survives the other device's unrelated edit.
        val base = account().period(PeriodKinds.NO_SCREEN, h(10), h(14))
        val local = base.panel("A", h(11), h(12))
        val remote = base.period(PeriodKinds.INACTIVITY, h(40), h(41))
        assertEquals(local.hiddenPanels, SnapshotMerge.mergeStates(base, local, remote).hiddenPanels)
        // Bounded: a piece wholly more than the kept past behind the line is dropped, and never more than the cap.
        val piece = s.hiddenPanels.single()
        val now = piece.panel.endEpochMillis + TimelineLevels.HIDDEN_KEEP_MILLIS
        assertTrue(TimelineLevels.purged(listOf(piece), now).isEmpty())
        assertEquals(listOf(piece), TimelineLevels.purged(listOf(piece), now - 1))
        val many = (0 until TimelineLevels.MAX_HIDDEN_PANELS + 7).map { i -> HiddenPanel(piece.panel.copy(id = "p/$i", endEpochMillis = piece.panel.endEpochMillis + i)) }
        val kept = TimelineLevels.purged(many, piece.panel.startEpochMillis)
        assertEquals(TimelineLevels.MAX_HIDDEN_PANELS, kept.size)
        assertEquals("p/7", kept.first().id)
    }
}
