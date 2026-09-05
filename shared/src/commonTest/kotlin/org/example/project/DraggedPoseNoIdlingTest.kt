package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes* (**mode 1**) and § *No idling*: **the period the
 * line drags obstructs nothing.**
 *
 * The requirement is one sentence — in mode 1 the line reaching a pose *"would continuously delay that
 * period (while creating task panels in its passing)"* — and it has two halves the app used to fail both of:
 *
 *  - the *delay* is real, so the pose is still drawn at `(t_p, t_p + d]` and still re-anchors the recurrence
 *    bars, and
 *  - the *passing* leaves TASK PANELS, so no stretch the line has swept may be left with nothing in it.
 *
 * What shipped instead planned around the dragged pose as though it were a fixed block. The half-open form
 * leaves exactly `[t_p, t_p + 1)` free, so a fill placed one millisecond of work and then idled for the
 * pose's whole length; every later re-plan regenerated the pose at the NEW line, and the stretch the line had
 * swept in between came out with no panel at all. The calendar draws whatever the past leaves uncovered as a
 * derived grey "Inactivity" band, which is what the user saw growing behind the now-line on account 3 —
 * while a device was unlocked and every task was free to run, which is precisely what § *No idling* forbids.
 *
 * Modes 2 and 3 are the controls: mode 3 does not drag at all (the account said the break is being taken, so
 * it really happens), and mode 2 drags but covers the line with "no on-screen task" — there the passing
 * creates coverage rather than task panels, so the grey band behind the line is correct.
 */
class DraggedPoseNoIdlingTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_700_000_000_000L

    /** Two ordinary on-screen tasks and the three production breaks. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        listOf("Alpha", "Beta").forEachIndexed { i, title ->
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        return s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
    }

    private fun fill(state: SchedulerState, now: Long, mode: Int = DynamicPeriods.MODE_AT_SCREEN) =
        SchedulerDomain.fillSchedule(state, now, horizonMillis = now + 3 * HOUR, tpMode = mode)

    /** The pose the line is dragging, i.e. the one the fill must NOT plan around. */
    private fun draggedPose(panels: List<TaskPanel>): TaskPanel? =
        panels.firstOrNull { SchedulerDomain.isDraggedScreenBreak(it) }

    /** What the calendar would draw grey: every past instant no panel of its own covers (see `App.kt`). */
    private fun inactivityBehind(panels: List<TaskPanel>, sinceMillis: Long, nowMillis: Long) =
        SchedulerDomain.derivedInactivityBands(
            panels.filterNot { it.screenBreak }.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) },
            sinceMillis,
            nowMillis,
        )

    // ----- the fixture really is a dragged pose ---------------------------------------------------

    @Test
    fun the_line_drags_a_pose_and_it_is_marked_as_dragged() {
        val panels = fill(account(), NOW)
        val pose = draggedPose(panels)
        assertTrue(pose != null, "the case needs the line to be dragging a pose: ${panels.map { it.title }}")
        // The half-open `(t_p, t_p + d]` of the requirements, in the app's discrete millisecond time.
        assertEquals(NOW + 1, pose.startEpochMillis, "a dragged period starts one millisecond past the line")
        assertTrue(pose.screenBreak, "it is still drawn as one of the three")
    }

    // ----- mode 1: the passing creates task panels ------------------------------------------------

    @Test
    fun mode_one_plans_straight_through_the_pose_it_drags() {
        val panels = fill(account(), NOW)
        val pose = draggedPose(panels)!!
        // The rule: the plan runs THROUGH the dragged pose. Before the fix the fill could place only the
        // single millisecond the half-open form left free, and then idled for the pose's whole length.
        val atLine = panels.filter { it.auto && it.startEpochMillis <= NOW && it.endEpochMillis > NOW }
        assertEquals(1, atLine.size, "exactly one task is scheduled at the line: $atLine")
        assertTrue(
            atLine.single().endEpochMillis > pose.endEpochMillis,
            "the task at the line must not stop at the dragged pose (ends " +
                "${atLine.single().endEpochMillis - NOW}, pose ends ${pose.endEpochMillis - NOW})",
        )
    }

    @Test
    fun the_stretch_the_line_sweeps_while_dragging_is_filled_with_task_panels() {
        // The reported anomaly, end to end: plan at NOW, let the line run on for ten minutes, re-plan. The
        // pose is dragged the whole way, so it never happens — and every millisecond it was dragged over has
        // to have come out of the passing as a task panel, or the calendar draws a growing grey band.
        val s = account()
        val first = fill(s, NOW)
        assertTrue(draggedPose(first) != null, "the case needs a dragged pose")
        val later = NOW + 10 * MIN
        val second = fill(s.copy(panels = first), later)
        assertTrue(draggedPose(second) != null, "and it must still be owed ten minutes on")

        assertEquals(
            emptyList(),
            inactivityBehind(second, NOW, later),
            "no stretch the line swept may be left for the calendar to draw as an Inactivity band",
        )
    }

    @Test
    fun the_display_clip_reaches_forward_only_so_the_swept_stretch_survives_it() {
        // The calendar does not draw the fill's panels raw: `App.kt` runs them through
        // [SchedulerDomain.clipPlanForPinnedScreenBreak], which cuts the work out of the break chain sitting
        // on the now-line — and that clip is now the whole of what makes a DRAGGED pose read as a period on
        // screen, since the plan deliberately runs under it. It may only ever reach forward: a clip that
        // reached behind the line would put the grey band straight back.
        val s = account()
        val first = fill(s, NOW)
        val later = NOW + 10 * MIN
        val second = fill(s.copy(panels = first), later)
        val drawn =
            SchedulerDomain.clipPlanForPinnedScreenBreak(
                second.filterNot { it.screenBreak },
                second.filter { it.screenBreak },
                later,
                s.screenBreaks,
                s.tasks,
            )
        assertEquals(
            emptyList(),
            inactivityBehind(drawn, NOW, later),
            "the display clip must not re-open the stretch the line swept",
        )
        // …and it really does still hide the dragged pose's own span ahead of the line, or the calendar would
        // draw a task panel through the band.
        val pose = draggedPose(second)!!
        assertTrue(
            drawn.none {
                it.auto && it.startEpochMillis < pose.endEpochMillis && it.endEpochMillis > pose.startEpochMillis
            },
            "the dragged pose must still read as a period on screen",
        )
    }

    @Test
    fun the_dragged_pose_is_still_drawn_and_still_bars_the_breaks_after_it() {
        // Dropping it from the PLAN must not drop it from anything else: it is still the calendar's band, and
        // the recurrence bars still count from it — the whole point of the drag is that the break is OWED.
        val panels = fill(account(), NOW)
        val pose = draggedPose(panels)!!
        // A dynamic period bars the 20 s look-away for twenty minutes after it, dragged or not.
        val lookAways =
            panels.filter { it.screenBreak && it.title == SchedulerDomain.DEFAULT_SCREEN_BREAKS[0].title }
        assertTrue(
            lookAways.none { it.startEpochMillis < pose.endEpochMillis + 20 * MIN },
            "the dragged pose must still re-anchor the 20 s bar: ${lookAways.map { it.startEpochMillis - NOW }}",
        )
    }

    // ----- the controls ---------------------------------------------------------------------------

    @Test
    fun a_break_the_line_is_not_dragging_still_obstructs() {
        // The rule is about the DRAG and nothing else. Every other placement is a real stretch of the
        // timeline, and no task without a resilience to "no task allowed" may be in one.
        val panels = fill(account(), NOW)
        val standing = panels.filter { it.screenBreak && !SchedulerDomain.isDraggedScreenBreak(it) }
        assertTrue(standing.isNotEmpty(), "the case needs a standing break to be about")
        val work = panels.filter { it.auto }
        for (band in standing) {
            assertTrue(
                work.none {
                    it.startEpochMillis < band.endEpochMillis && it.endEpochMillis > band.startEpochMillis
                },
                "a task was placed inside ${band.title} at ${band.startEpochMillis - NOW}",
            )
        }
    }

    @Test
    fun mode_three_does_not_drag_so_its_pose_still_obstructs() {
        // Mode 3 is the account SAYING the break is being taken: the pose elapses under the line and really
        // happens, so it is an ordinary stretch of the timeline and nothing may run in it.
        val panels = fill(account(), NOW, DynamicPeriods.MODE_ON_BREAK)
        assertTrue(panels.none { SchedulerDomain.isDraggedScreenBreak(it) }, "mode 3 drags nothing")
        val bands = panels.filter { it.screenBreak }
        val work = panels.filter { it.auto }
        assertTrue(bands.isNotEmpty(), "the case needs a break to be about")
        for (band in bands) {
            assertTrue(
                work.none {
                    it.startEpochMillis < band.endEpochMillis && it.endEpochMillis > band.startEpochMillis
                },
                "a task was placed inside a break the line is crossing in mode 3",
            )
        }
    }

    @Test
    fun mode_two_keeps_the_drag_as_an_obstacle() {
        // Mode 2 drags the pose exactly as mode 1 does, but its own rule is that the line IS covered by "no
        // on-screen task" — so the passing creates COVERAGE, not task panels, and an on-screen task must not
        // be planned into the stretch behind the line (that grey band is what "no device is unlocked" looks
        // like). This is the one place the two modes' answers to the drag differ.
        val panels = fill(account(), NOW, DynamicPeriods.MODE_AWAY)
        val pose = draggedPose(panels)
        assertTrue(pose != null, "mode 2 drags the pose too")
        val work = panels.filter { it.auto }
        assertTrue(
            work.none {
                it.startEpochMillis < pose.endEpochMillis && it.endEpochMillis > pose.startEpochMillis
            },
            "no on-screen task may be planned into the pose mode 2 is dragging",
        )
    }
}
