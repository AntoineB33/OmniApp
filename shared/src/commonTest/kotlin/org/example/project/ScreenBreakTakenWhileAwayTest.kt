package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange

/**
 * The report: *"I got a notification for a 5min screen break, did the 5min break, woke the app up, and saw in
 * the calendar an inactivity period instead of the 5min break"* — with, where the absence ran a little past
 * five minutes, the little Inactivity band that SHOULD follow the break and nothing else.
 *
 * Two rules were cancelling the break the app had just announced, and both are about the pause the user spent
 * taking it ([DynamicPeriods.chainTaking]):
 *
 *  * the pause is a rest stretch, and *"after a >= 5-minute stretch ... no 5min period in the next 1 hour"*
 *    was read as barring the very break the stretch IS. The occurrence was pushed a whole hour past the
 *    pause, so nothing touched it any more and the requirements' pull-back — *"when a 'no on-screen task'
 *    period touches the start of a dynamic restrictive period ... the period now starts at the start of this
 *    chain"* — found nothing to pull back. It bit the instant the pause reached five minutes: a shorter one
 *    still drew the break, which is why the break appeared and then vanished as the user stayed away.
 *  * and once the user came back, the account is in mode 1 again, so the past re-derivation
 *    ([SchedulerDomain.takenScreenBreakPanels] asks the bars with the mode NOW) dragged the pose onto the
 *    now-line — a stretch the line had crossed in mode 2 re-read as one it had crossed at the screen. The
 *    **frozen past** says the schedule below the line does not change as the line advances; a mode flip is
 *    not an exception to it, and the pause itself is the record of which mode the line was in.
 *
 * What the calendar must draw is the pause's first five minutes as the break and the remainder as the
 * ordinary Inactivity band behind it (`docs/invariants/screen-breaks.md`: *a break is never STRETCHED to keep
 * covering the line; the gap behind it is covered instead*).
 */
class ScreenBreakTakenWhileAwayTest {

    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 3_600_000L

    /** One on-screen task, so a PeriodKinds.NO_SCREEN period is a stretch nobody can run in. */
    private val onScreenOnly =
        listOf(PlanTask(TaskId("task/user/0"), 1.0, 0L, mapOf(PeriodKinds.NO_SCREEN to 0.0)))

    private val specs =
        listOf(
            DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 20 * MIN),
            DynamicPeriods.Spec(DynamicPeriods.LABEL_5MIN, 5 * MIN, 60 * MIN),
        )

    /** The pause as the devices OBSERVED it, which is how one that has ENDED reaches the bars. */
    private fun pause(startMillis: Long, endMillis: Long) =
        DynamicPeriods.Base(
            SchedulerDomain.observedNoScreenPeriods(listOf(TaskTimeRange(startMillis, endMillis))),
            emptyList(),
            onScreenOnly,
        )

    @Test
    fun the_pause_the_pose_was_taken_in_is_drawn_as_the_pose() {
        // The 5-min pose falls due at 60 min, which is when the user walks away; they are back six minutes
        // later. The break happened, at the walk-away: the minutes spent away count towards it.
        val walkedAway = 60 * MIN
        val cameBack = walkedAway + 6 * MIN
        val base = pause(walkedAway, cameBack)
        val placed =
            DynamicPeriods.instances(
                base, specs, 0L, 3 * HOUR, tpMillis = cameBack + MIN,
                mode = DynamicPeriods.MODE_AT_SCREEN, sweepFromMillis = 0L,
            )
        val pose = placed.single { it.startMillis in walkedAway..cameBack }
        assertEquals(walkedAway, pose.startMillis, "the pose starts where the pause did: $placed")
        assertEquals(5 * MIN, pose.durationMillis, "and is not stretched to cover the rest of the pause")
        assertTrue(
            pose.endMillis < cameBack,
            "so the last minute of the absence is the Inactivity band behind it: $pose",
        )
    }

    @Test
    fun the_past_does_not_change_when_the_user_comes_back() {
        // The frozen past, stated as the anomaly stated it: the picture the calendar draws over a stretch the
        // line has crossed is the same before and after the mode flips back to 1 at the unlock.
        val walkedAway = 60 * MIN
        val cameBack = walkedAway + 6 * MIN
        val base = pause(walkedAway, cameBack)
        fun placedAt(tp: Long, mode: Int) =
            DynamicPeriods.instances(base, specs, 0L, 3 * HOUR, tpMillis = tp, mode = mode, sweepFromMillis = 0L)
                .filter { it.startMillis < cameBack }

        val whileAway = placedAt(cameBack, DynamicPeriods.MODE_AWAY)
        assertEquals(whileAway, placedAt(cameBack + MIN, DynamicPeriods.MODE_AT_SCREEN), "one minute later")
        assertEquals(whileAway, placedAt(cameBack + HOUR, DynamicPeriods.MODE_AT_SCREEN), "an hour later")
    }

    @Test
    fun the_calendars_past_side_markers_hold_the_pose_the_user_took() {
        // The same thing asked through the funnel the calendar actually reads, with the account's real breaks:
        // the past-side markers over the elapsed window ([SchedulerDomain.takenScreenBreakPanels]), asked in
        // mode 1 because that is what the machine is in once the user is back at it.
        val now = 1_700_000_000_000L
        val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
        val pose5 = breaks.single { it.key == SchedulerDomain.FIVE_MIN_BREAK_KEY }
        // The walk-away is the instant the app ANNOUNCED the pose at — its due, where the bars put it with
        // nothing dragged — because that is the notification the user got up in answer to.
        val walkedAway =
            SchedulerDomain.screenBreakOccurrencesBetween(
                breaks, now - 6 * HOUR, now - 10 * MIN, tasks = onScreenOnly, anchorMillis = now,
            ).last { it.title == pose5.title }.startEpochMillis
        val cameBack = walkedAway + 6 * MIN
        val panels =
            SchedulerDomain.takenScreenBreakPanels(
                breaks,
                now - 6 * HOUR,
                now - 1,
                basePeriods =
                    SchedulerDomain.observedNoScreenPeriods(listOf(TaskTimeRange(walkedAway, cameBack))),
                tasks = onScreenOnly,
                tpMillis = now,
                mode = DynamicPeriods.MODE_AT_SCREEN,
            )
        val taken = panels.filter { it.startEpochMillis in walkedAway..cameBack }
        assertTrue(taken.isNotEmpty(), "the break the user took is drawn in the past: $panels")
        assertEquals(
            walkedAway,
            taken.first().startEpochMillis,
            "at the instant the pause began, not at its end and not on the now-line: $taken",
        )
        assertTrue(
            taken.any { it.endEpochMillis - it.startEpochMillis == pose5.durationMillis },
            "and it is the 5-min pose that fell due there: $taken",
        )
    }

    @Test
    fun a_pause_too_short_for_the_pose_leaves_it_owed() {
        // The other side of the rule: two minutes away is not a five-minute pose. Nothing is written into the
        // past, and mode 1 goes back to parking the owed break on the now-line.
        val walkedAway = 60 * MIN
        val cameBack = walkedAway + 2 * MIN
        val tp = cameBack + MIN
        val base = pause(walkedAway, cameBack)
        val placed =
            DynamicPeriods.instances(
                base, specs, 0L, 3 * HOUR, tpMillis = tp,
                mode = DynamicPeriods.MODE_AT_SCREEN, sweepFromMillis = 0L,
            )
        assertTrue(
            placed.none { it.spec.label == DynamicPeriods.LABEL_5MIN && it.startMillis < tp },
            "a two-minute pause takes no five-minute pose: $placed",
        )
        assertTrue(
            placed.any { it.spec.label == DynamicPeriods.LABEL_5MIN && it.startMillis == tp },
            "it is still owed, and mode 1 keeps it at the line: $placed",
        )
    }

    @Test
    fun a_pause_with_no_break_due_in_it_stays_a_plain_pause() {
        // Nothing here manufactures a break out of an absence: the bars decide, and a pause the user takes
        // well before one falls due draws no period at all.
        val walkedAway = 10 * MIN
        val cameBack = walkedAway + 6 * MIN
        val base = pause(walkedAway, cameBack)
        val placed =
            DynamicPeriods.instances(
                base, specs, 0L, 3 * HOUR, tpMillis = cameBack + MIN,
                mode = DynamicPeriods.MODE_AT_SCREEN, sweepFromMillis = 0L,
            )
        assertTrue(
            placed.none { it.spec.label == DynamicPeriods.LABEL_5MIN && it.startMillis < cameBack },
            "the 5-min pose is not due yet, so the pause is only a pause: $placed",
        )
    }

    @Test
    fun the_stretch_still_bars_what_comes_after_it() {
        // What the "no 5min period in the next 1 hour" bar is FOR is untouched: it fires off the pause the
        // break was taken in, measured from that stretch's end, so the next pose is an hour later and not one
        // interval after the break.
        val walkedAway = 60 * MIN
        val cameBack = walkedAway + 6 * MIN
        val base = pause(walkedAway, cameBack)
        // The pose alone, so the answer is the bar itself and not the chain merge collapsing a look-away that
        // ends where it begins.
        val pose = specs.last()
        val placed =
            DynamicPeriods.instances(
                base, listOf(pose), 0L, 5 * HOUR, tpMillis = cameBack + MIN,
                mode = DynamicPeriods.MODE_AT_SCREEN, sweepFromMillis = 0L,
            )
        val next = placed.first { it.startMillis > cameBack }
        assertEquals(cameBack + HOUR, next.startMillis, "one hour after the stretch ended: $placed")
    }

    @Test
    fun a_user_drawn_no_screen_period_still_refuses_to_cover_the_line_with_a_pose() {
        // Mode 1's own rule survives the pull-back: a chain that reaches the line cannot hand the line a pose
        // to sit inside, so the drag is kept. (Here the chain begins two minutes back, so a five-minute pose
        // pulled onto its start would still be covering `t_p`.)
        val chainStart = 60 * MIN
        val tp = chainStart + 2 * MIN
        val base =
            DynamicPeriods.Base(
                listOf(RestrictivePeriod(chainStart, tp, PeriodKinds.NO_SCREEN, "no screen", closedEnd = true)),
                emptyList(),
                onScreenOnly,
            )
        val placed =
            DynamicPeriods.instances(
                base, specs, 0L, 3 * HOUR, tpMillis = tp,
                mode = DynamicPeriods.MODE_AT_SCREEN, sweepFromMillis = 0L,
            )
        val pose = placed.single { it.spec.label == DynamicPeriods.LABEL_5MIN && it.startMillis <= tp }
        assertEquals(tp, pose.startMillis, "mode 1: the pose is still owed AT the line: $placed")
        assertTrue(pose.openStart, "as the half-open (t_p, t_p + d], so t_p itself stays uncovered")
    }
}
