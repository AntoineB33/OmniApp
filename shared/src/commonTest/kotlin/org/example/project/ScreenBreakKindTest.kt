package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive Period*: all three screen breaks are restrictive
 * periods of **one kind — `no task allowed` — end to end**, so a break has no *shape*.
 *
 * These replace `ScreenBreakHollowTest`, which pinned the retired reading: a break used to be two spans with
 * two different accepted sets (a closed head, then a tail for the off-screen / break-doable work), the fill
 * scheduled from that split and the calendar drew the open half **hollow**. `ScreenBreakPeriod`,
 * `screenBreakOpenStartMillis` and the hollow band are gone with it (ADR 0003); what is left is one question,
 * asked of each task rather than of each span — **is its resilience to `no task allowed` above zero?** — and
 * these pin that the fill and the display clip both ask exactly it.
 */
class ScreenBreakKindTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    private fun panel(start: Long, end: Long, title: String) =
        TaskPanel(
            id = "side/0/$start",
            taskId = null,
            title = title,
            startEpochMillis = start,
            endEpochMillis = end,
            screenBreak = true,
        )

    @Test
    fun every_dynamic_period_carries_the_kind_no_task_allowed() {
        // The one thing there is to read off a break now. Not three shapes — one kind, on all three.
        val panels =
            SchedulerDomain.screenBreakPanels(
                SchedulerDomain.DEFAULT_SCREEN_BREAKS, NOW, NOW + 6 * HOUR,
            )
        assertTrue(panels.isNotEmpty(), "the case needs breaks to be about")
        assertTrue(
            panels.all { it.restrictiveKind == PeriodKinds.NO_TASK },
            "every break must be \"no task allowed\": ${panels.map { it.title to it.restrictiveKind }.distinct()}",
        )
        // …and over the WHOLE span. A shape would show up here as a second panel, or a shorter one.
        val byTitle = panels.groupBy { it.title }
        for (side in SchedulerDomain.DEFAULT_SCREEN_BREAKS) {
            val drawn = byTitle[side.title].orEmpty()
            if (drawn.isEmpty()) continue
            assertTrue(
                drawn.all { it.endEpochMillis - it.startEpochMillis == side.durationMillis },
                "${side.title} must be one span of its own length",
            )
        }
    }

    @Test
    fun a_dynamic_period_admits_only_what_declares_a_resilience_to_its_kind() {
        // The fill puts inside a break exactly the tasks whose resilience to "no task allowed" is above zero,
        // and nothing else. This is where the old "open tail" went: a task works through a break when it has
        // been given that resilience, which is the same sentence — and the same code path — as any other kind.
        val (state, resilient) = stateWithResilientTask()
        val panels = SchedulerDomain.fillSchedule(state, NOW, horizonMillis = NOW + 4 * HOUR)
        val bands = panels.filter { it.screenBreak }
        assertTrue(bands.isNotEmpty(), "the case needs a break to be about")
        val inside = panels.filter { p ->
            p.auto && bands.any { p.startEpochMillis < it.endEpochMillis && p.endEpochMillis > it.startEpochMillis }
        }
        assertTrue(inside.isNotEmpty(), "the resilient task must be given the break to work in")
        assertTrue(
            inside.all { it.taskId == resilient },
            "nothing without a resilience to \"no task allowed\" may be there: ${inside.map { it.title }}",
        )
    }

    /** An account with one on-screen task and one that declares a resilience to "no task allowed". */
    private fun stateWithResilientTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Screen work"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[1], "Stretch"))
        val resilient = s.tasks.keys.first { s.tasks[it]!!.title == "Stretch" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(resilient, 3))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(resilient, PeriodKinds.NO_TASK, 1.0))
        return s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS) to resilient
    }

    @Test
    fun a_break_over_the_plan_cuts_exactly_what_its_kind_refuses() {
        // The display clip (ADR 0001 §8) asks the same question the fill does. A task the period admits is
        // not cut at all, and a task it refuses loses the whole span and resumes past it — where the clip
        // used to cut a closed head and leave an open tail alone.
        val screen = TaskId("screen")
        val resilient = TaskId("resilient")
        val tasks =
            mapOf(
                screen to Task(id = screen, title = "Screen work", resilience = mapOf(PeriodKinds.NO_SCREEN to 0.0)),
                resilient to Task(
                    id = resilient,
                    title = "Stretch",
                    minimumMinutes = 3,
                    resilience = mapOf(PeriodKinds.NO_TASK to 1.0),
                ),
            )
        val pose = ScreenBreak(
            title = "take a 5min pose and blink hard",
            intervalMillis = HOUR,
            durationMillis = 5 * MIN,
            restBreak = true,
        )
        val band = panel(NOW, NOW + 5 * MIN, pose.title)
        fun auto(id: String, taskId: TaskId, start: Long, end: Long) =
            TaskPanel(
                id = id, taskId = taskId, title = tasks.getValue(taskId).title,
                startEpochMillis = start, endEpochMillis = end, auto = true,
            )
        val plan =
            listOf(
                auto("s", screen, NOW - 10 * MIN, NOW + 30 * MIN),
                auto("o", resilient, NOW, NOW + 5 * MIN),
            )
        val out = SchedulerDomain.clipPlanForPinnedScreenBreak(plan, listOf(band), NOW, listOf(pose), tasks)

        // The screen task keeps its elapsed head and resumes past the break: the period refuses it throughout.
        assertEquals(
            listOf(NOW - 10 * MIN to NOW, NOW + 5 * MIN to NOW + 30 * MIN),
            out.filter { it.taskId == screen }.map { it.startEpochMillis to it.endEpochMillis },
        )
        // The task that declared a resilience to "no task allowed" is not cut at all.
        assertEquals(
            listOf(NOW to NOW + 5 * MIN),
            out.filter { it.taskId == resilient }.map { it.startEpochMillis to it.endEpochMillis },
        )
    }
}
