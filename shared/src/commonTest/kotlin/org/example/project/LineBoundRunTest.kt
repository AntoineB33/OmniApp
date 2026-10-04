package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RuleProgram
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes*, **mode 1**, as the user reads it (2026-10-04): the line is on a
 * task even with a "no screen" period right after it, and the task it is on while pushing that period forward is
 * **found by reading the set of rules** — *"if $now line$ mode = 1, then … task B at $now line$"* — never by laying a
 * task inside the period and hiding it. Such a run is laid line-bound ([TaskPanel.lineBound]).
 */
class LineBoundRunTest {
    private val hour = 3_600_000L
    private val now = 1_000_000_000_000L

    private fun withNoScreenAhead(): Pair<SchedulerState, org.example.project.scheduler.model.TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, 30))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, now + hour, now + 2 * hour))
        return s to solo
    }

    private fun overlapsPeriod(p: TaskPanel) = p.startEpochMillis < now + 2 * hour && p.endEpochMillis > now + hour

    @Test
    fun in_mode_1_no_task_is_laid_inside_a_no_screen_period_ahead_only_rules_held_at_the_line() {
        val (s, solo) = withNoScreenAhead()
        val plan = SchedulerDomain.fillSchedule(s, now, TimeZone.UTC, horizonMillis = now + 6 * hour)
        val inside = plan.filter { it.taskId != null && !it.isRestrictivePeriod && overlapsPeriod(it) }
        assertTrue(inside.isNotEmpty(), "the rules name the task a line at a screen is on there")
        // ONE run across the period, not pieces — and the stretch of it inside the period holds only at the line.
        assertEquals(
            listOf(TaskTimeRange(now + hour, now + 2 * hour)),
            SchedulerDomain.mergeOccupied(inside.flatMap { it.heldAtLine }),
            "the run holds at the line exactly over the period, never laid in it: $inside",
        )

        // The calendar draws the panels as the rules give them at the line: nothing inside the period ahead…
        assertTrue(SchedulerDomain.atLine(plan, now).none { it.taskId != null && overlapsPeriod(it) })
        // …and, with the line half an hour into it, the task from the period's start to the line.
        val mid = now + hour + hour / 2
        val drawnMid = SchedulerDomain.atLine(plan, mid).filter { it.taskId == solo && it.startEpochMillis < now + 2 * hour }
        assertEquals(mid, drawnMid.maxOf { it.endEpochMillis }, "inside the period the run reaches the line, never past it")
        assertTrue(drawnMid.any { it.startEpochMillis <= now + hour && it.endEpochMillis == mid }, "from before the period up to the line")

        // The forward cursor reads the task off the rules there.
        val cursor = RuleProgram.Cursor(RuleProgram.compile(plan), now)
        cursor.moveTo(mid)
        assertEquals(solo, cursor.panel?.taskId, "the line pushing the period forward is on the task the rules name")
    }

    @Test
    fun no_task_is_laid_under_a_screen_break_ahead_either() {
        // § *screen breaks*: at a screen the line drags a pose it reaches and is on a task meanwhile. The run the
        // rules name under a break ahead holds at the line there, for a task the break refuses.
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        s = s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val plan = SchedulerDomain.fillSchedule(s, now, TimeZone.UTC, horizonMillis = now + 6 * hour)
        val breaks = plan.filter { it.screenBreak && it.endEpochMillis > now }
        val runs = plan.filter { it.auto && it.taskId == solo }
        fun under(run: TaskPanel) = breaks.flatMap { SchedulerDomain.breakRefusedRanges(it, s.tasks[solo]) }
            .mapNotNull { r ->
                TaskTimeRange(maxOf(r.startEpochMillis, run.startEpochMillis, now), minOf(r.endEpochMillis, run.endEpochMillis))
                    .takeIf { it.endEpochMillis > it.startEpochMillis }
            }
        val crossed = runs.filter { under(it).isNotEmpty() }
        assertTrue(crossed.isNotEmpty(), "the fixture needs a run the rules name under a break ahead")
        for (run in crossed) {
            val held = SchedulerDomain.mergeOccupied(run.heldAtLine)
            for (r in under(run)) {
                assertTrue(
                    held.any { it.startEpochMillis <= r.startEpochMillis && it.endEpochMillis >= r.endEpochMillis },
                    "the stretch of ${run.id} under a break must hold at the line: $r not in $held",
                )
            }
        }
        // As the rules give them at the line, breaks included: no stretch of the task stands under a break ahead.
        val drawn = SchedulerDomain.atLine(plan, breaks, now, s.screenBreaks, s.tasks).filter { it.auto && it.taskId == solo }
        assertTrue(drawn.all { under(it).isEmpty() }, "a task drawn under a break ahead of the line")
    }

    @Test
    fun the_covered_modes_hold_no_run_at_the_line() {
        val (s, _) = withNoScreenAhead()
        val plan = SchedulerDomain.fillSchedule(
            s, now, TimeZone.UTC, horizonMillis = now + 6 * hour, tpMode = DynamicPeriods.MODE_AWAY,
        )
        assertTrue(plan.none { it.lineBound }, "a period stands for a line that is covered: nothing gives way to it")
    }

    @Test
    fun a_run_held_at_the_line_survives_a_restart_and_an_older_payload_holds_none() {
        val (s, _) = withNoScreenAhead()
        val planned = s.copy(panels = SchedulerDomain.fillSchedule(s, now, TimeZone.UTC, horizonMillis = now + 6 * hour))
        assertTrue(planned.panels.any { it.lineBound })
        val reloaded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(planned))!!
        assertEquals(
            planned.panels.filter { it.lineBound }.map { it.heldAtLine },
            reloaded.panels.filter { it.lineBound }.map { it.heldAtLine },
        )
        // A payload written before the field existed: the same panels without it — every run holds outright.
        val encoded = SchedulerStateCodec.encode(planned)
        val older = encoded.replace(Regex(""","heldAtLine":\[[^\]]*\]"""), "")
        assertTrue(older != encoded, "the fixture must have written the field")
        assertFalse(SchedulerStateCodec.decode(older)!!.panels.any { it.lineBound })
    }
}
