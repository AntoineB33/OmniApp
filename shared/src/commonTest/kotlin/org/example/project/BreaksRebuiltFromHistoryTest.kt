package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.scheduler.domain.BankedBreak
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.DynamicPeriods.Span
import org.example.project.scheduler.domain.SchedulerDomain

/**
 * `docs/scheduler_requirements.md` § *Use of the set of rules output*: a re-run of the scheduler *"removes everything
 * deduced from the previous set of rules but not saved in history"*. The break machine's bars are deduced; history is
 * the banked breaks, the breaks the app conducted and the "no screen" the devices observed
 * ([BreakMachine.rebuildFromHistory]).
 *
 * Account 3, 2026-09-30: a restart counted 37 minutes at an unlocked computer as "no screen" and barred the 5-min break
 * until 17:29:49. Nothing in history held that stretch, so the next re-run must drop the bar.
 */
class BreaksRebuiltFromHistoryTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val T0 = 1_700_000_000_000L
    private val specs = SchedulerDomain.dynamicPeriodSpecs(SchedulerDomain.DEFAULT_SCREEN_BREAKS)

    private fun machine(bars: Map<String, Long>, drag: BreakMachine.Drag? = null) =
        BreakMachine.State(atMillis = T0, baseMode = DynamicPeriods.MODE_AT_SCREEN, bars = bars, drag = drag)

    @Test
    fun a_bar_history_does_not_hold_is_dropped_for_the_one_it_does() {
        // The last 5-min break ended two hours ago, so history bars the next one an hour after it: already due.
        val banked = listOf(BankedBreak(DynamicPeriods.LABEL_5MIN, T0 - 2 * HOUR - 5 * MIN, T0 - 2 * HOUR))
        val carried = machine(mapOf(DynamicPeriods.LABEL_20S to T0 + 5 * MIN, DynamicPeriods.LABEL_5MIN to T0 + HOUR - 10 * MIN, DynamicPeriods.LABEL_15MIN to T0 + HOUR))
        val rebuilt = BreakMachine.rebuildFromHistory(carried, emptyList(), specs, banked)
        assertEquals(T0 - HOUR, rebuilt.bars[DynamicPeriods.LABEL_5MIN], "the 5-min bar is the one history sets")
        // Due at the line in mode 1: the next step drags it, from the line.
        val stepped = BreakMachine.advance(rebuilt, T0, emptyList(), specs)
        assertEquals(DynamicPeriods.LABEL_5MIN, stepped.drag?.label)
    }

    @Test
    fun a_label_history_says_nothing_about_keeps_its_carried_bar() {
        // Nothing banked, nothing observed: the carried bars are the rested start, and a re-run must not push them on.
        val carried = machine(mapOf(DynamicPeriods.LABEL_20S to T0 + 5 * MIN, DynamicPeriods.LABEL_5MIN to T0 + 30 * MIN, DynamicPeriods.LABEL_15MIN to T0 + HOUR))
        assertEquals(carried, BreakMachine.rebuildFromHistory(carried, emptyList(), specs, emptyList()))
    }

    @Test
    fun an_observed_stretch_of_no_screen_is_history() {
        // A 10-min pause observed twenty minutes ago bars the 5-min break an hour past its end — the carried bar was lower.
        val carried = machine(mapOf(DynamicPeriods.LABEL_20S to T0 + 5 * MIN, DynamicPeriods.LABEL_5MIN to T0, DynamicPeriods.LABEL_15MIN to T0 + HOUR))
        val rebuilt = BreakMachine.rebuildFromHistory(carried, emptyList(), specs, emptyList(), stretches = listOf(Span(T0 - 30 * MIN, T0 - 20 * MIN)))
        assertEquals(T0 + 40 * MIN, rebuilt.bars[DynamicPeriods.LABEL_5MIN])
    }

    @Test
    fun the_drag_is_the_same_break_while_history_still_owes_it() {
        val banked = listOf(BankedBreak(DynamicPeriods.LABEL_5MIN, T0 - 2 * HOUR - 5 * MIN, T0 - 2 * HOUR))
        val drag = BreakMachine.Drag(DynamicPeriods.LABEL_5MIN, T0 - HOUR, listOf(DynamicPeriods.LABEL_5MIN))
        val carried = machine(mapOf(DynamicPeriods.LABEL_20S to T0 + 5 * MIN, DynamicPeriods.LABEL_5MIN to T0 - HOUR, DynamicPeriods.LABEL_15MIN to T0 + HOUR), drag)
        val rebuilt = BreakMachine.rebuildFromHistory(carried, emptyList(), specs, banked)
        assertEquals(drag, rebuilt.drag, "the owed pose keeps the instant it fell due: announced once")
    }

    /**
     * Account 3, 2026-09-30: the 5-min pose was dragged at a screen; the 15-min bar lay inside "the hour before bed"
     * ([beforeBed]), so the pull (requirements: *"must now start at max($now line$, $t_s$)"*) brought it to the period's
     * start and the drag's reach teleported it onto the line. [from] is where the line is.
     */
    private fun draggingThePulledPose(beforeBed: Span, from: Long): BreakMachine.State {
        val s =
            BreakMachine.advance(
                machine(
                    mapOf(DynamicPeriods.LABEL_20S to T0 - 30 * MIN, DynamicPeriods.LABEL_5MIN to T0 - 30 * MIN, DynamicPeriods.LABEL_15MIN to T0 + 50 * MIN),
                    drag = BreakMachine.Drag(DynamicPeriods.LABEL_5MIN, T0 - 30 * MIN, listOf(DynamicPeriods.LABEL_5MIN, DynamicPeriods.LABEL_20S)),
                ).copy(atMillis = from),
                from, listOf(beforeBed), specs,
            )
        assertEquals(DynamicPeriods.LABEL_15MIN, s.drag?.label, "the pull brought the 15-min pose onto the line")
        return s
    }

    /** Re-runs, each followed by a step of the line: the owed pose must be the same one, announced once. */
    private fun assertOwedOnceAcrossReRuns(start: BreakMachine.State, chains: List<Span>) {
        var s = start
        val owedAt = s.drag!!.dueMillis
        for (i in 1..5) {
            s = BreakMachine.rebuildFromHistory(s, chains, specs, emptyList())
            assertEquals(DynamicPeriods.LABEL_15MIN, s.drag?.label, "re-run $i kept the owed pose")
            val events = ArrayList<BreakMachine.Event>()
            s = BreakMachine.advance(s, s.atMillis + 1_000L, chains, specs, events)
            assertEquals(emptyList(), events.filterIsInstance<BreakMachine.Event.Owed>(), "step $i owed nothing new")
            assertEquals(owedAt, s.drag?.dueMillis, "the pose is owed from the instant it first fell due")
        }
    }

    @Test
    fun a_pose_the_pull_brought_onto_a_line_inside_the_period_is_owed_once_across_re_runs() {
        val beforeBed = Span(T0 - 20 * MIN, T0 + 10 * HOUR)
        assertOwedOnceAcrossReRuns(draggingThePulledPose(beforeBed, T0), listOf(beforeBed))
    }

    @Test
    fun a_pose_joined_within_the_drag_s_reach_before_the_period_is_owed_once_across_re_runs() {
        // 22:09 on account 3: the line is not in "before bed" yet; the pulled pose's due is its start, 5 minutes on —
        // within the dragged 5-min pose's reach, which is what joined it.
        val beforeBed = Span(T0 + 5 * MIN, T0 + 10 * HOUR)
        assertOwedOnceAcrossReRuns(draggingThePulledPose(beforeBed, T0), listOf(beforeBed))
    }

    @Test
    fun a_pulled_pose_no_period_pulls_any_more_falls_back_to_its_bar() {
        // The sleep schedule moved: no no-screen period holds the 15-min bar now. Re-applying the rules gives the 2-hour
        // bar back; only the 5-min pose (due by its own bar) is still owed.
        val dragging = draggingThePulledPose(Span(T0 - 20 * MIN, T0 + 10 * HOUR), T0)
        val rebuilt = BreakMachine.rebuildFromHistory(dragging, emptyList(), specs, emptyList())
        assertEquals(DynamicPeriods.LABEL_5MIN, rebuilt.drag?.label)
        assertEquals(listOf(DynamicPeriods.LABEL_5MIN, DynamicPeriods.LABEL_20S), rebuilt.drag?.members)
    }

    @Test
    fun a_drag_history_no_longer_owes_is_dropped() {
        // The carried machine dragged a 5-min break, but history shows one taken forty minutes ago.
        val banked = listOf(BankedBreak(DynamicPeriods.LABEL_5MIN, T0 - 45 * MIN, T0 - 40 * MIN))
        val drag = BreakMachine.Drag(DynamicPeriods.LABEL_5MIN, T0 - 10 * MIN, listOf(DynamicPeriods.LABEL_5MIN))
        val carried = machine(mapOf(DynamicPeriods.LABEL_20S to T0 + 5 * MIN, DynamicPeriods.LABEL_5MIN to T0 - 10 * MIN, DynamicPeriods.LABEL_15MIN to T0 + HOUR), drag)
        val rebuilt = BreakMachine.rebuildFromHistory(carried, emptyList(), specs, banked)
        assertNull(rebuilt.drag)
        assertEquals(T0 + 20 * MIN, rebuilt.bars[DynamicPeriods.LABEL_5MIN])
    }
}
