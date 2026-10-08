package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * Anomaly 2026-10-08: *"when dragging a task panel from the past to the $now line$, it gets retracted by the $now
 * line$. The $now line$ is in mode 1, so it has no reason to retract the task panel, and the sleep block right after
 * the $now line$ must retract to the task panel."*
 *
 * Two causes. A run the fill laid AT the line (inside the schedule's Sleep window, which gives way to a mode-1 line)
 * carries the stretches it holds only there ([TaskPanel.heldAtLine]); dragged by hand it kept them, so dragged back
 * onto the line it was cut at the line. And the Sleep band gave way to the past the line crossed at a screen, never
 * to a task panel the user placed.
 */
class PlacedTaskAtTheLineTest {
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    private fun oneTask(): SchedulerState {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Solo"))
        return s
    }

    private fun spans(panels: List<TaskPanel>) = panels.map { it.startEpochMillis to it.endEpochMillis }

    @Test
    fun a_run_laid_at_the_line_and_dragged_by_hand_is_whole_on_the_line() {
        val s0 = oneTask()
        val solo = s0.tasks.values.single { it.title == "Solo" }.id
        // As the fill lays it inside a window that gives way: held at the line over the whole night.
        val night = TaskTimeRange(NOW - 4 * HOUR, NOW + 4 * HOUR)
        val run = TaskPanel("auto/0", solo, "Solo", NOW - 3 * HOUR, NOW - 2 * HOUR, auto = true, heldAtLine = listOf(night))
        val s = s0.copy(panels = s0.panels + run)
        // The rules' own run is the line's: nothing of it ahead of the line.
        val ahead = run.copy(startEpochMillis = NOW - HOUR / 2, endEpochMillis = NOW + HOUR / 2)
        assertEquals(listOf(NOW - HOUR / 2 to NOW), spans(SchedulerDomain.atLine(listOf(ahead), NOW)))

        // Dragged by hand onto the line: placed, so whole — it holds nowhere "only at the line" any more.
        val moved = SchedulerReducer.reduce(
            s,
            SchedulerIntent.UpdateTaskPanel("auto/0", solo, "Solo", NOW - HOUR / 2, NOW + HOUR / 2, PanelPins(existence = true)),
        )
        val placed = moved.panels.single { it.taskId == solo }
        assertTrue(placed.heldAtLine.isEmpty() && !placed.auto)
        assertEquals(listOf(NOW - HOUR / 2 to NOW + HOUR / 2), spans(SchedulerDomain.atLine(moved.panels.filter { it.taskId == solo }, NOW)))

        // A panel stored by a build that kept the stretches is read the same way.
        val stale = placed.copy(heldAtLine = listOf(night))
        assertEquals(listOf(NOW - HOUR / 2 to NOW + HOUR / 2), spans(SchedulerDomain.atLine(listOf(stale), NOW)))
    }

    @Test
    fun the_sleep_band_gives_way_to_a_task_panel_the_user_placed() {
        val s0 = oneTask()
        val solo = s0.tasks.values.single { it.title == "Solo" }.id
        val config = s0.periodKindConfig
        val night = TaskPanel("sleep/1", null, "Sleep", NOW - 4 * HOUR, NOW + 4 * HOUR, sleep = true)
        val placed = TaskPanel("panel/1", solo, "Solo", NOW - HOUR / 2, NOW + HOUR / 2, pinned = true, pins = PanelPins(existence = true))
        // The line at a screen up to now: the band is ]now; its end] …
        val crossed = listOf(TaskTimeRange(NOW - 4 * HOUR, NOW))
        assertEquals(listOf(NOW to NOW + 4 * HOUR), spans(SchedulerDomain.retractOverAtScreenPast(listOf(night), crossed, config)))
        // … and with the task panel on the line, it starts where the panel ends.
        assertEquals(
            listOf(NOW + HOUR / 2 to NOW + 4 * HOUR),
            spans(SchedulerDomain.retractOverAtScreenPast(listOf(night), crossed, config, placedTasks = listOf(placed), tasks = s0.tasks)),
        )
        // The panel itself is untouched, whichever list it is read in.
        val both = SchedulerDomain.retractOverAtScreenPast(listOf(night, placed), crossed, config, tasks = s0.tasks)
        assertEquals(listOf(NOW - HOUR / 2 to NOW + HOUR / 2), spans(both.filter { it.taskId == solo }))
        assertEquals(listOf(NOW + HOUR / 2 to NOW + 4 * HOUR), spans(both.filter { it.sleep }))
        // A run the RULES laid takes nothing from the band: it is the band that decides where that one may stand.
        val auto = placed.copy(id = "auto/1", auto = true, pinned = false, pins = PanelPins())
        assertEquals(listOf(NOW to NOW + 4 * HOUR), spans(SchedulerDomain.retractOverAtScreenPast(listOf(night), crossed, config, placedTasks = listOf(auto))))
        // A task the user allowed to run through sleep stands IN the band: nothing gives way.
        val allowed = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskResilience(solo, org.example.project.scheduler.domain.PeriodKinds.SLEEP, 1.0))
        assertEquals(
            listOf(NOW to NOW + 4 * HOUR),
            spans(SchedulerDomain.retractOverAtScreenPast(listOf(night), crossed, config, placedTasks = listOf(placed), tasks = allowed.tasks)),
        )
    }

    /**
     * The user, 2026-10-08: "If it is a 15min screen break, then it is my task panel that must get retracted,
     * otherwise docs\scheduler_requirements.md would be violated" — and "strangely the sleep block retracts even
     * though it is not replaced by the task panel". The band gives way to the panel where the panel STANDS.
     */
    @Test
    fun under_a_break_the_placed_panel_is_retracted_and_the_sleep_band_is_not() {
        val s0 = oneTask()
        val solo = s0.tasks.values.single { it.title == "Solo" }.id
        val night = TaskPanel("sleep/1", null, "Sleep", NOW - 4 * HOUR, NOW + 4 * HOUR, sleep = true)
        val placed = TaskPanel("panel/1", solo, "Solo", NOW - HOUR / 2, NOW + HOUR / 2, pinned = true, pins = PanelPins(existence = true))
        // The pose the line carries: ]now; now + 15 min].
        val pose = TaskPanel("side/2/x", null, "take a 15min pose", NOW, NOW + HOUR / 4, screenBreak = true)
        val standing = SchedulerDomain.placedTasksOutsideBreaks(listOf(night, placed), listOf(pose), s0.tasks)
        assertEquals(listOf(NOW - HOUR / 2 to NOW, NOW + HOUR / 4 to NOW + HOUR / 2), spans(standing))
        val crossed = listOf(TaskTimeRange(NOW - 4 * HOUR, NOW))
        assertEquals(
            listOf(NOW to NOW + HOUR / 4, NOW + HOUR / 2 to NOW + 4 * HOUR),
            spans(SchedulerDomain.retractOverAtScreenPast(listOf(night), crossed, s0.periodKindConfig, placedTasks = standing, tasks = s0.tasks)),
            "behind the break the Sleep band still stands; it gives way only where the panel does",
        )
        // No break: the panel stands whole.
        assertEquals(listOf(NOW - HOUR / 2 to NOW + HOUR / 2), spans(SchedulerDomain.placedTasksOutsideBreaks(listOf(placed), emptyList(), s0.tasks)))
    }

    /**
     * Anomaly 2026-10-08: "the held task panel retracts to the now line, but strangely both 'no computer unlocked' and
     * 'no phone unlocked' are retracted in the 15min break right after the now line". The layers the Sleep window lays
     * follow the window: it gives way where the placed panel STANDS, so under the break — where the panel is retracted
     * — the window and everything it lays are still there.
     */
    @Test
    fun the_layers_the_sleep_window_lays_stay_under_a_break_a_placed_panel_was_retracted_from() {
        val s0 = oneTask()
        val solo = s0.tasks.values.single { it.title == "Solo" }.id
        val night = TaskPanel("sleep/1", null, "Sleep", NOW - 4 * HOUR, NOW + 4 * HOUR, sleep = true)
        val placed = TaskPanel("panel/1", solo, "Solo", NOW - HOUR / 2, NOW + HOUR / 2, pinned = true, pins = PanelPins(existence = true))
        val pose = TaskTimeRange(NOW, NOW + HOUR / 4)
        val crossed = listOf(TaskTimeRange(NOW - 4 * HOUR, NOW))
        val stated = SchedulerDomain.statedKindRegions(listOf(night, placed), s0.periodKindConfig, atScreenPast = crossed, breaks = listOf(pose))
        fun covers(kind: String, from: Long, to: Long) =
            stated[kind].orEmpty().any { it.startEpochMillis <= from && it.endEpochMillis >= to }
        val sleep = org.example.project.scheduler.domain.PeriodKinds.SLEEP
        val noScreen = org.example.project.scheduler.domain.PeriodKinds.NO_SCREEN
        assertTrue(covers(sleep, NOW, NOW + HOUR / 4), "under the break the window stands: " + stated[sleep])
        assertTrue(covers(noScreen, NOW, NOW + HOUR / 4), "and so does the no-screen it carries: " + stated[noScreen])
        // Past the break the placed panel stands, and the window gives way to it.
        assertTrue(stated[sleep].orEmpty().none { it.startEpochMillis < NOW + HOUR / 2 && it.endEpochMillis > NOW + HOUR / 4 }, "" + stated[sleep])
        // Every layer the window lays is there under the break exactly as it is further on, past the panel.
        for ((kind, _) in stated) {
            if (covers(kind, NOW + HOUR, NOW + 2 * HOUR)) assertTrue(covers(kind, NOW, NOW + HOUR / 4), "$kind is laid past the panel but not under the break")
        }
    }
}
