package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *screen breaks*: *"There are the 20s, 5min and 15min screen break periods, always
 * accompanied by the 'no screen' period. The 20s screen break allows no task. The 5min break is accompanied by two
 * periods: the first minute that allow no tasks and the 4 next minutes."*
 *
 * So the 20 s is [PeriodKinds.INACTIVITY] (nobody may be resilient to it), the 5 min and the 15 min are kinds of their
 * own a task may be given a resilience to ([PeriodKinds.BREAK_5MIN], [PeriodKinds.BREAK_15MIN]), and the 5 min's first
 * minute allows nobody whatever their resilience ([SchedulerDomain.screenBreakPeriods]). Until 2026-09-29 all three
 * were "no task allowed" end to end, from an older README.
 */
class ScreenBreakKindTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    private fun panel(start: Long, end: Long, title: String, kind: String) =
        TaskPanel(
            id = "side/0/$start",
            taskId = null,
            title = title,
            startEpochMillis = start,
            endEpochMillis = end,
            screenBreak = true,
            periodKind = kind,
        )

    @Test
    fun each_break_carries_the_kind_of_its_role() {
        val panels = SchedulerDomain.screenBreakPanels(SchedulerDomain.DEFAULT_SCREEN_BREAKS, NOW, NOW + 6 * HOUR)
        assertTrue(panels.isNotEmpty(), "the case needs breaks to be about")
        val kinds = panels.associate { it.title to it.restrictiveKind }
        val byDuration = SchedulerDomain.DEFAULT_SCREEN_BREAKS.sortedBy { it.durationMillis }
        assertEquals(PeriodKinds.INACTIVITY, kinds[byDuration[0].title], "the 20 s allows no task")
        assertEquals(PeriodKinds.BREAK_5MIN, kinds[byDuration[1].title])
        assertEquals(PeriodKinds.BREAK_15MIN, kinds[byDuration[2].title])
        // Each is one span of its own length, accompanied by "no screen" through its kind.
        for (side in SchedulerDomain.DEFAULT_SCREEN_BREAKS) {
            assertTrue(panels.filter { it.title == side.title }.all { it.endEpochMillis - it.startEpochMillis == side.durationMillis })
        }
        for (kind in PeriodKinds.BREAK_KINDS) {
            assertTrue(PeriodKinds.NO_SCREEN in org.example.project.scheduler.domain.PeriodKindConfig.DEFAULT.kindsOf(kind), "$kind comes with no screen")
        }
    }

    @Test
    fun the_20s_and_the_5min_first_minute_allow_no_task_whatever_the_resilience() {
        val everything = Task(
            id = TaskId("t"), title = "t",
            resilience = mapOf(PeriodKinds.INACTIVITY to 1.0, PeriodKinds.BREAK_5MIN to 1.0, PeriodKinds.BREAK_15MIN to 1.0),
        )
        val lookAway = panel(NOW, NOW + 20_000, "20s", PeriodKinds.INACTIVITY)
        assertEquals(
            listOf(NOW to NOW + 20_000),
            SchedulerDomain.breakRefusedRanges(lookAway, everything).map { it.startEpochMillis to it.endEpochMillis },
        )
        val pose5 = panel(NOW, NOW + 5 * MIN, "5min", PeriodKinds.BREAK_5MIN)
        assertEquals(
            listOf(NOW to NOW + MIN),
            SchedulerDomain.breakRefusedRanges(pose5, everything).map { it.startEpochMillis to it.endEpochMillis },
            "the first minute refuses even a task resilient to the 5 min break",
        )
        val pose15 = panel(NOW, NOW + 15 * MIN, "15min", PeriodKinds.BREAK_15MIN)
        assertTrue(SchedulerDomain.breakRefusedRanges(pose15, everything).isEmpty(), "the 15 min admits whom its kind admits")
        // And by default every break refuses everybody, as a new kind does.
        val plain = Task(id = TaskId("p"), title = "p")
        for (band in listOf(lookAway, pose5, pose15)) {
            assertEquals(
                listOf(band.startEpochMillis to band.endEpochMillis),
                SchedulerDomain.breakRefusedRanges(band, plain).map { it.startEpochMillis to it.endEpochMillis },
            )
        }
    }

    @Test
    fun the_fill_puts_a_resilient_task_in_a_pose_and_never_in_what_allows_no_task() {
        val (state, resilient) = stateWithResilientTask()
        val rest = TaskPanel("rest/0", null, "No screen", NOW - 30 * MIN, NOW - 5 * MIN, noScreen = true)
        val panels = SchedulerDomain.fillSchedule(state.copy(panels = listOf(rest)), NOW, horizonMillis = NOW + 8 * HOUR)
        val bands = metBy(state.copy(panels = listOf(rest)), NOW, NOW + 8 * HOUR)
        fun workIn(from: Long, to: Long) =
            panels.filter { it.auto && it.taskId != null && it.startEpochMillis < to && it.endEpochMillis > from }
        val noTask = bands.flatMap { SchedulerDomain.screenBreakPeriods(it) }.filter { it.kind == PeriodKinds.INACTIVITY }
        assertTrue(noTask.isNotEmpty(), "the case needs a look-away or a 5 min first minute")
        for (r in noTask) assertTrue(workIn(r.startMillis, r.endMillis).isEmpty(), "work inside what allows no task: $r")
        // Inside the rest of the breaks the plan meets, only the task resilient to their kinds works.
        val open = bands.filter { it.restrictiveKind != PeriodKinds.INACTIVITY }
        for (band in open) {
            val from = if (band.restrictiveKind == PeriodKinds.BREAK_5MIN) band.startEpochMillis + MIN else band.startEpochMillis
            assertTrue(workIn(from, band.endEpochMillis).all { it.taskId == resilient }, "only the resilient task works in $band")
        }
    }

    /** An account with one on-screen task and one resilient to the two pose kinds. */
    private fun stateWithResilientTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Screen work"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[1], "Stretch"))
        val resilient = s.tasks.keys.first { s.tasks[it]!!.title == "Stretch" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(resilient, 3))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(resilient, PeriodKinds.BREAK_5MIN, 1.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(resilient, PeriodKinds.BREAK_15MIN, 1.0))
        return s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS) to resilient
    }

    @Test
    fun a_break_over_the_plan_cuts_exactly_what_it_refuses_each_task() {
        // The display clip asks the same question the fill does ([SchedulerDomain.breakRefusedRanges]).
        val screen = TaskId("screen")
        val resilient = TaskId("resilient")
        val tasks =
            mapOf(
                screen to Task(id = screen, title = "Screen work", resilience = mapOf(PeriodKinds.NO_SCREEN to 0.0)),
                resilient to Task(id = resilient, title = "Stretch", minimumMinutes = 3, resilience = mapOf(PeriodKinds.BREAK_5MIN to 1.0)),
            )
        val band = panel(NOW, NOW + 5 * MIN, "take a 5min pose and blink hard", PeriodKinds.BREAK_5MIN)
        fun auto(id: String, taskId: TaskId, start: Long, end: Long) =
            TaskPanel(id = id, taskId = taskId, title = tasks.getValue(taskId).title, startEpochMillis = start, endEpochMillis = end, auto = true)
        val plan = listOf(auto("s", screen, NOW - 10 * MIN, NOW + 30 * MIN), auto("o", resilient, NOW, NOW + 5 * MIN))
        val out = SchedulerDomain.clipPlanForPinnedScreenBreak(plan, listOf(band), NOW, tasks = tasks)
        // The screen task keeps its elapsed head and resumes past the break: the break refuses it throughout.
        assertEquals(
            listOf(NOW - 10 * MIN to NOW, NOW + 5 * MIN to NOW + 30 * MIN),
            out.filter { it.taskId == screen }.map { it.startEpochMillis to it.endEpochMillis },
        )
        // The resilient task loses the first minute only.
        assertEquals(
            listOf(NOW + MIN to NOW + 5 * MIN),
            out.filter { it.taskId == resilient }.map { it.startEpochMillis to it.endEpochMillis },
        )
    }
}
