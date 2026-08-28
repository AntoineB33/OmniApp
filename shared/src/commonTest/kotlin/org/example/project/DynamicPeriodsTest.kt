package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel

/**
 * `side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive Period*: **where the 20 s, the 5 min and the 15 min
 * periods go**, and the three recurrence bars that decide it.
 *
 * This replaces the tests of the retired placement engine (a per-break `lastRest` anchor, a grid simulation,
 * the 5↔15 merge, the "a pause re-anchors shorter pauses" rule and the decoupled-pose special case). Every
 * one of those was a way of saying "a rest bars the breaks that follow it", which the bars say once — so
 * what is asserted here is the bars themselves, and the properties that follow from them.
 */
class DynamicPeriodsTest {

    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    private val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
    private val lookAway = breaks.first { it.durationMillis == 20 * SEC }.title
    private val pose5 = breaks.first { it.durationMillis == 5 * MIN }.title
    private val pose15 = breaks.first { it.durationMillis == 15 * MIN }.title

    private fun place(
        toMillis: Long = NOW + 6 * HOUR,
        periods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
        sides: List<ScreenBreak> = breaks,
    ): List<TaskPanel> =
        SchedulerDomain.screenBreakPanels(sides, NOW, toMillis, periods, blocks, tasks)

    private fun starts(panels: List<TaskPanel>, title: String) =
        panels.filter { it.title == title }.map { it.startEpochMillis - NOW }

    // ----- the three bars -----------------------------------------------------------------------

    @Test
    fun after_any_dynamic_period_there_is_no_20s_period_for_twenty_minutes() {
        // The README's first bar, and the only one that keys on ANY of the three rather than on a stretch.
        val panels = place()
        assertTrue(panels.isNotEmpty(), "the placement must produce something to be about")
        for (i in panels.indices) {
            for (j in i + 1 until panels.size) {
                if (panels[j].title != lookAway) continue
                val gap = panels[j].startEpochMillis - panels[i].endEpochMillis
                assertTrue(
                    gap >= DynamicPeriods.BAR_20S_AFTER_ANY_MILLIS || gap < 0,
                    "a 20 s period fell ${gap / 1000}s after a dynamic period ending at " +
                        "${(panels[i].endEpochMillis - NOW) / 1000}s",
                )
            }
        }
    }

    @Test
    fun after_a_five_minute_rest_stretch_there_is_no_5min_period_for_an_hour() {
        val panels = place()
        val fiveMinStarts = starts(panels, pose5)
        assertTrue(fiveMinStarts.isNotEmpty(), "the 5 min period must appear at all")
        // Every dynamic period at least five minutes long is itself a rest stretch, so it bars the next
        // 5 min period for an hour after it ENDS.
        for (panel in panels) {
            val length = panel.endEpochMillis - panel.startEpochMillis
            if (length < DynamicPeriods.STRETCH_SHORT_MILLIS) continue
            for (start in fiveMinStarts.map { it + NOW }) {
                if (start <= panel.endEpochMillis) continue
                assertTrue(
                    start - panel.endEpochMillis >= DynamicPeriods.BAR_5MIN_AFTER_STRETCH_MILLIS,
                    "a 5 min period fell ${(start - panel.endEpochMillis) / 60000}min after a rest stretch",
                )
            }
        }
    }

    @Test
    fun after_a_fifteen_minute_rest_stretch_there_is_no_15min_period_for_two_hours() {
        val panels = place(toMillis = NOW + 12 * HOUR)
        val long = panels.filter { it.title == pose15 }.map { it.startEpochMillis }
        assertTrue(long.size >= 2, "the case needs two 15 min periods to be about")
        for (i in 0 until long.size - 1) {
            val end = long[i] + 15 * MIN
            assertTrue(
                long[i + 1] - end >= DynamicPeriods.BAR_15MIN_AFTER_LONG_MILLIS,
                "two 15 min periods only ${(long[i + 1] - end) / 60000}min apart",
            )
        }
    }

    @Test
    fun a_hand_drawn_rest_stretch_bars_the_periods_that_follow_it() {
        // A rest stretch is not only a dynamic period: an inactivity period the user drew is one too, and it
        // bars exactly the same way. This is what replaces the old "a pause re-anchors shorter pauses" rule.
        val quiet = RestrictivePeriod(NOW, NOW + 30 * MIN, PeriodKinds.NO_TASK, "Inactivity")
        val panels = place(periods = listOf(quiet))
        val firstLookAway = starts(panels, lookAway).minOrNull()
        assertTrue(firstLookAway != null)
        assertTrue(
            firstLookAway >= 30 * MIN + DynamicPeriods.BAR_20S_AFTER_LONG_MILLIS,
            "a 30-minute rest must bar the 20 s period for 20 minutes after it; got ${firstLookAway / 60000}min",
        )
        val first15 = starts(panels, pose15).minOrNull()
        assertTrue(first15 != null)
        assertTrue(
            first15 >= 30 * MIN + DynamicPeriods.BAR_15MIN_AFTER_LONG_MILLIS,
            "…and the 15 min period for two hours; got ${first15 / 60000}min",
        )
    }

    @Test
    fun a_pre_placed_task_is_not_a_rest_however_long_it_is() {
        // "Without any task" is one of the three clauses, and a pre-placed task IS a task: the user was at
        // the screen the whole time, so an hour of maintenance bars nothing.
        val work = TaskId("task/user/0")
        val blocks = listOf(PlanBlock(work, NOW, NOW + HOUR))
        val tasks = listOf(PlanTask(work, 1.0, 45 * MIN))
        val withBlock = starts(place(blocks = blocks, tasks = tasks), lookAway).minOrNull()
        val without = starts(place(tasks = tasks), lookAway).minOrNull()
        assertEquals(without, withBlock, "a pre-placed task must not bar anything")
    }

    // ----- the chain merge ----------------------------------------------------------------------

    @Test
    fun overlapping_periods_collapse_to_the_longest_starting_at_the_earliest_point() {
        // The README's chain rule, exercised through the mode-1 drag, which is the only thing that can make
        // two dynamic periods overlap.
        val base = DynamicPeriods.Base(emptyList(), emptyList(), emptyList())
        val specs = listOf(
            DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 20 * MIN),
            DynamicPeriods.Spec(DynamicPeriods.LABEL_5MIN, 5 * MIN, 60 * MIN),
            DynamicPeriods.Spec(DynamicPeriods.LABEL_15MIN, 15 * MIN, 2 * HOUR),
        )
        val out = DynamicPeriods.instances(base, specs, 0L, 6 * HOUR, tpMillis = 0L)
        for (i in 0 until out.size - 1) {
            assertTrue(
                out[i + 1].startMillis > out[i].endMillis,
                "the chain merge must leave no two dynamic periods overlapping",
            )
        }
    }

    // ----- the kind -----------------------------------------------------------------------------

    @Test
    fun all_three_are_periods_of_the_kind_no_task_allowed() {
        // `side-dev/README.md` states it in as many words, and it is what replaced the three different shapes
        // (a closed look-away, a closed-head-then-break-doable pose, an off-screen-only pose).
        val base = DynamicPeriods.Base(emptyList(), emptyList(), emptyList())
        val specs = listOf(
            DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 20 * MIN),
            DynamicPeriods.Spec(DynamicPeriods.LABEL_5MIN, 5 * MIN, 60 * MIN),
            DynamicPeriods.Spec(DynamicPeriods.LABEL_15MIN, 15 * MIN, 2 * HOUR),
        )
        val periods = DynamicPeriods.periods(base, specs, 0L, 6 * HOUR, tpMillis = 0L)
        assertTrue(periods.isNotEmpty())
        assertTrue(periods.all { it.kind == PeriodKinds.NO_TASK }, "every dynamic period is 'no task allowed'")
    }

    // ----- the two t_p modes --------------------------------------------------------------------

    @Test
    fun mode_one_drags_a_period_the_line_has_reached_ahead_of_it() {
        // "$t_p$ must not be covered by the period 'no on-screen task'": a period the line stands on is pushed
        // to the line and becomes the half-open (t_p, t_p + duration], so the instant t_p itself is free.
        val base = DynamicPeriods.Base(emptyList(), emptyList(), emptyList())
        val specs = listOf(DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 20 * MIN))
        // The line has swept from the origin up to the first period's own slot.
        val out = DynamicPeriods.instances(base, specs, 0L, 2 * HOUR, tpMillis = 25 * MIN, sweepFromMillis = 0L)
        val dragged = out.first()
        assertEquals(25 * MIN, dragged.startMillis, "the reached period is pushed onto the line")
        assertTrue(dragged.openStart, "and becomes the half-open (t_p, t_p + 20s]")
        assertTrue(!dragged.toPeriod().covers(25 * MIN), "so t_p itself is NOT covered")
        assertTrue(dragged.toPeriod().covers(25 * MIN + 1), "while every instant after it is")
        assertTrue(dragged.toPeriod().covers(25 * MIN + 20 * SEC), "up to and including its end")
    }

    @Test
    fun mode_two_covers_the_line_with_a_no_on_screen_task_period() {
        // "$t_p$ must be covered by the period 'no on-screen task'": the gap between the last period's end
        // and the line is covered, and as NO_SCREEN rather than NO_TASK — so a task resilient to that kind
        // may still fill it, which is exactly what the README's own example asks for.
        val base = DynamicPeriods.Base(emptyList(), emptyList(), emptyList())
        val specs = listOf(DynamicPeriods.Spec(DynamicPeriods.LABEL_15MIN, 15 * MIN, 2 * HOUR))
        val out = DynamicPeriods.periods(
            base, specs, 0L, 6 * HOUR,
            tpMillis = 2 * HOUR + 20 * MIN,
            mode = DynamicPeriods.MODE_AWAY,
        )
        val cover = out.single { it.kind == PeriodKinds.NO_SCREEN }
        assertEquals(2 * HOUR + 15 * MIN, cover.startMillis, "the cover starts where the last period ended")
        assertEquals(2 * HOUR + 20 * MIN, cover.endMillis)
        assertTrue(cover.covers(cover.endMillis), "and the line itself is covered")
    }

    // ----- what follows from the bars -----------------------------------------------------------

    @Test
    fun a_sub_minute_cadence_cannot_flood_the_timeline() {
        // The old engine needed an explicit cap for this. The bar IS the cap now: no 20 s period may fall
        // within 20 minutes of any other dynamic period, whatever cadence the break is configured with.
        val dense = ScreenBreak("dense", intervalMillis = 1_000L, durationMillis = 1_000L)
        val panels = place(toMillis = NOW + 6 * HOUR, sides = listOf(dense))
        assertTrue(panels.isNotEmpty())
        val gaps = panels.zipWithNext { a, b -> b.startEpochMillis - a.endEpochMillis }
        assertTrue(
            gaps.all { it >= DynamicPeriods.BAR_20S_AFTER_ANY_MILLIS },
            "the 20-minute bar is what bounds the count: got gaps ${gaps.map { it / 1000 }}",
        )
        assertTrue(panels.size <= 6 * 3 + 1, "…so six hours hold about eighteen of them, not thousands")
    }

    @Test
    fun the_placement_is_the_same_whether_it_is_asked_forward_or_over_a_window() {
        // The calendar asks for a week it has navigated to through the same engine, so a break drawn in one
        // view is the break drawn in the other.
        val forward = place(toMillis = NOW + 6 * HOUR)
        val window = SchedulerDomain.screenBreakPanelsInWindow(breaks, NOW, NOW + 6 * HOUR)
        assertEquals(
            forward.map { it.title to it.startEpochMillis },
            window.map { it.title to it.startEpochMillis },
        )
    }

    @Test
    fun a_break_is_announced_at_the_instant_it_is_drawn() {
        // The cue and the calendar read ONE placement, so they cannot disagree about when a break begins.
        // (While breaks slid this was impossible: a drawn start that moves with the now-line is never
        // crossed, which is why the cue used to key on a separate anchored due.)
        val panels = place(toMillis = NOW + 2 * HOUR)
        val crossings = SchedulerDomain.cueCrossings(
            screenBreaks = breaks,
            windDownInstants = emptyList(),
            automaticSchedule = true,
            alreadyNotifiedPoseDues = emptyMap(),
            fromMillis = NOW,
            toMillis = NOW + 2 * HOUR,
        )
        assertEquals(
            panels.filter { it.startEpochMillis in NOW..(NOW + 2 * HOUR) }.map { it.startEpochMillis },
            crossings.filter { it.kind != SchedulerDomain.CueKind.WindDown }.map { it.instant },
        )
    }
}
