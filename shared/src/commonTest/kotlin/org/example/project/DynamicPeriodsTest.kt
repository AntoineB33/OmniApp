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
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

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
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
    ): List<TaskPanel> =
        SchedulerDomain.screenBreakPanels(sides, NOW, toMillis, periods, blocks, tasks, mode)

    /** The bars' own answer over the same span - the dues, with no line dragging anything. */
    private fun dues(
        toMillis: Long = NOW + 6 * HOUR,
        periods: List<RestrictivePeriod> = emptyList(),
        blocks: List<PlanBlock> = emptyList(),
        tasks: List<PlanTask> = emptyList(),
        sides: List<ScreenBreak> = breaks,
    ): List<TaskPanel> =
        SchedulerDomain.screenBreakOccurrencesBetween(
            sides, NOW, toMillis, periods, blocks, tasks, anchorMillis = NOW,
        )

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
    fun mode_one_drags_a_pose_the_line_has_reached_ahead_of_it() {
        // "$t_p$ must not be covered by the period 'no on-screen task'": a POSE the line stands on is pushed
        // to the line and becomes the half-open (t_p, t_p + duration], so the instant t_p itself is free. It
        // is a thing the user has to actually do, so an untaken one is owed, not spent.
        val base = DynamicPeriods.Base(emptyList(), emptyList(), emptyList())
        val specs = listOf(DynamicPeriods.Spec(DynamicPeriods.LABEL_5MIN, 5 * MIN, 60 * MIN))
        // The line has swept from the origin up to the first period's own slot.
        val out = DynamicPeriods.instances(base, specs, 0L, 6 * HOUR, tpMillis = 70 * MIN, sweepFromMillis = 0L)
        val dragged = out.first()
        assertEquals(70 * MIN, dragged.startMillis, "the reached period is pushed onto the line")
        assertTrue(dragged.openStart, "and becomes the half-open (t_p, t_p + 5min]")
        assertTrue(!dragged.toPeriod().covers(70 * MIN), "so t_p itself is NOT covered")
        assertTrue(dragged.toPeriod().covers(70 * MIN + 1), "while every instant after it is")
        assertTrue(dragged.toPeriod().covers(70 * MIN + 5 * MIN), "up to and including its end")
    }

    @Test
    fun mode_one_never_drags_the_look_away_the_line_crosses_it_instead() {
        // The look-away is the ONE exception to the drag (`DynamicPeriods.dragsAtLine`). Looking twenty feet
        // away for twenty seconds costs no working time, so the app assumes it is being done the moment it
        // falls due: the period stays where the bars put it, the line walks across it in mode 2 (covered,
        // which is exactly what mode 1 forbids of a pose), and behind the line it stays on the timeline as
        // what really happened.
        val base = DynamicPeriods.Base(emptyList(), emptyList(), emptyList())
        val specs = listOf(DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 20 * MIN))

        // The line is INSIDE the first occurrence's own slot: it is not pushed onto the line, and it covers it.
        val inside =
            DynamicPeriods.instances(base, specs, 0L, 2 * HOUR, tpMillis = 20 * MIN + 10 * SEC, sweepFromMillis = 0L)
                .first()
        assertEquals(20 * MIN, inside.startMillis, "the look-away sits where the bars put it, not on the line")
        assertTrue(!inside.openStart, "so it keeps the ordinary closed [start, start + 20s)")
        assertTrue(inside.toPeriod().covers(20 * MIN + 10 * SEC), "and the line is inside it - mode 2, for 20 s")

        // The line has gone PAST it: it is still there, at the same instant. The past is drawn from the same
        // placement, so a look-away goes on being drawn where it happened.
        val passed =
            DynamicPeriods.instances(base, specs, 0L, 2 * HOUR, tpMillis = 25 * MIN, sweepFromMillis = 0L)
        assertEquals(20 * MIN, passed.first().startMillis, "and it stays there once the line is past it")
        assertTrue(passed.first().endMillis < 25 * MIN, "wholly behind the line, an ordinary fact of the past")

        // Which is exactly the bars' own answer - so for the look-away, the DUE and where it is DRAWN are one
        // instant, whether the question is asked at the line or away from it.
        val undragged =
            DynamicPeriods.instances(base, specs, 0L, 2 * HOUR, tpMillis = 25 * MIN, sweepFromMillis = 25 * MIN)
        assertEquals(
            undragged.map { it.startMillis },
            passed.map { it.startMillis },
            "the line changes nothing about where a look-away falls",
        )
    }

    @Test
    fun a_look_away_the_line_has_crossed_stays_drawn_where_it_happened() {
        // What the calendar shows behind the line ([SchedulerDomain.takenScreenBreakPanels]) is the same
        // placement asked about a window that has gone by, at the line, in the line's own mode. Because the
        // look-away is never dragged, the elapsed timeline really did hold it - so it goes on being drawn
        // there, and it is a fact of the past like any other.
        val only = listOf(ScreenBreak("20s", intervalMillis = 20 * MIN, durationMillis = 20 * SEC))
        val past =
            SchedulerDomain.takenScreenBreakPanels(
                only,
                fromMillis = NOW - 2 * HOUR,
                toMillis = NOW - 1,
                anchorMillis = NOW,
                tpMillis = NOW,
                mode = DynamicPeriods.MODE_AT_SCREEN,
            )
        assertTrue(past.isNotEmpty(), "a look-away the line crossed at the screen is still on the calendar")
        assertTrue(past.all { it.title == "20s" && it.screenBreak })
        assertTrue(
            past.all { it.endEpochMillis - it.startEpochMillis == 20 * SEC },
            "each one lasts exactly as long as it was asked to",
        )
        // And it is where the bars put it, with nothing dragged - the cue's own due.
        val due =
            SchedulerDomain.screenBreakOccurrencesBetween(only, NOW - 2 * HOUR, NOW - 1, anchorMillis = NOW)
                .map { it.startEpochMillis }
        assertEquals(due, past.map { it.startEpochMillis }, "the break is drawn at the instant it fell due")
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
        assertTrue(!cover.label.equals("Away", ignoreCase = true), "the scheduler period is no-screen logic, not an Away band label")
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
    fun the_bars_give_one_answer_wherever_they_are_asked() {
        // The drag is the ONLY thing that moves a period, and only the line drags. So every question that is
        // not about the line - the cue's dues, a week the calendar has navigated to - gets the same placement.
        val cue = dues(toMillis = NOW + 6 * HOUR)
        val window = SchedulerDomain.screenBreakPanelsInWindow(breaks, NOW, NOW + 6 * HOUR)
        assertEquals(
            cue.map { it.title to it.startEpochMillis },
            // The window keeps a period that STRADDLES its left edge; the dues are the ones that begin
            // inside it. Same placement either way, which is what this is about.
            window.filter { it.startEpochMillis >= NOW }.map { it.title to it.startEpochMillis },
        )
    }

    @Test
    fun a_break_straddling_the_now_line_is_drawn_on_the_past_side_only() {
        val shortBreak = ScreenBreak("20s", intervalMillis = 20 * MIN, durationMillis = 20 * SEC)
        val refusedTask = PlanTask(TaskId("task/user/refused"), 1.0, 0L)
        val origin = SchedulerDomain.dynamicPlacementOriginMillis(NOW)
        val due =
            SchedulerDomain.screenBreakPanelsInWindow(
                listOf(shortBreak), origin, origin + HOUR, tasks = listOf(refusedTask), anchorMillis = NOW,
            ).minBy { it.startEpochMillis }
        val now = due.startEpochMillis + 10 * SEC
        val past = due
        val future =
            SchedulerDomain.screenBreakPanels(
                listOf(shortBreak), now, now + HOUR, tasks = listOf(refusedTask), mode = DynamicPeriods.MODE_AWAY,
            ).filter { it.startEpochMillis >= now }

        assertTrue(past.startEpochMillis < now, "the occurrence starts before the line and belongs to the past")
        assertTrue(past.endEpochMillis > now, "the regression must straddle the line")
        assertTrue(future.none { it.startEpochMillis == past.startEpochMillis })
    }

    @Test
    fun a_POSE_is_announced_at_its_DUE_not_where_the_line_leaves_it() {
        // A pose's cue keys on the due - where the bars put it - because in mode 1 the period itself has no
        // crossable start: the line pushes it, so it is always "starting now" and a sweep keyed on it would
        // announce a break at every scan for as long as one is owed.
        val line = NOW + 2 * HOUR
        val crossings = SchedulerDomain.cueCrossings(
            screenBreaks = breaks,
            windDownInstants = emptyList(),
            automaticSchedule = true,
            alreadyNotifiedPoseDues = emptyMap(),
            fromMillis = NOW,
            toMillis = line,
        )
        assertEquals(
            dues(toMillis = line)
                .filter { it.title != lookAway && it.startEpochMillis in NOW..line }
                .map { it.startEpochMillis },
            crossings.filter { it.kind == SchedulerDomain.CueKind.RestPoseDue }.map { it.instant },
        )
        // ...and those dues are NOT where mode 1 leaves the periods: the swept ones are all on the line.
        assertTrue(
            place(toMillis = line).any { it.startEpochMillis == NOW + 1 },
            "the swept chain is owed at the line",
        )
    }

    @Test
    fun a_LOOK_AWAY_is_announced_where_the_line_actually_puts_it() {
        // The other half of the same rule, and the anomaly it was written for (account 3, 2026-09-04): a
        // look-away is never dragged, so its cue keys on the AT-LINE run - the one the calendar draws.
        //
        // The two runs are NOT the same sequence of look-aways, which is why this cannot be folded into the
        // pose assertion above. An owed pose is a placed dynamic period in the undragged run (so it bars the
        // 20 s for twenty minutes after itself) and is dragged onto the line in the at-line run (so it bars
        // nothing where it was owed). Read off the undragged run, the app drew look-aways it never announced
        // and would have announced ones it never draws.
        val line = NOW + 2 * HOUR
        val crossings = SchedulerDomain.cueCrossings(
            screenBreaks = breaks,
            windDownInstants = emptyList(),
            automaticSchedule = true,
            alreadyNotifiedPoseDues = emptyMap(),
            fromMillis = NOW,
            toMillis = line,
        )
        val drawn =
            SchedulerDomain.takenScreenBreakPanels(
                breaks, NOW, line, anchorMillis = line, tpMillis = line,
                mode = DynamicPeriods.MODE_AT_SCREEN,
            ).filter { it.title == lookAway }.map { it.startEpochMillis }
        assertTrue(drawn.isNotEmpty(), "the case needs look-aways to be about")
        assertEquals(
            drawn,
            crossings.filter { it.kind == SchedulerDomain.CueKind.LookAwayStart }.map { it.instant },
            "every look-away the line draws is announced, and nothing else is",
        )
        // ...and it really is a different set from the undragged run's, or this test proves nothing.
        assertTrue(
            drawn != dues(toMillis = line).filter { it.title == lookAway }.map { it.startEpochMillis },
            "the two runs must disagree here, or the case does not exercise the drag",
        )
    }

    // ----- the two modes, as the app asks them -------------------------------------------------

    @Test
    fun mode_one_leaves_the_line_uncovered_and_mode_two_covers_it() {
        // The two modes, applied at the one place they bite: the placement asked AT the line.
        val atScreen = place(mode = DynamicPeriods.MODE_AT_SCREEN)
        assertTrue(atScreen.isNotEmpty(), "there must be periods for this to be about")
        assertTrue(
            atScreen.none { it.startEpochMillis <= NOW && NOW < it.endEpochMillis },
            "mode 1: nothing the line placed may cover t_p itself",
        )
        // Mode 2's cover lies BEHIND the line (it is the gap the line has already crossed while away), so it
        // is the elapsed window that holds it, not the forward projection. Whatever the grid happens to put
        // near the line, the invariant is the README's: t_p is covered, by something the on-screen tasks are
        // turned away by.
        val away =
            SchedulerDomain.takenScreenBreakPanels(
                breaks, NOW - 6 * HOUR, NOW - 1, tpMillis = NOW, mode = DynamicPeriods.MODE_AWAY,
            )
        val covering = away.filter { it.startEpochMillis <= NOW && NOW <= it.endEpochMillis }
        assertTrue(covering.isNotEmpty(), "mode 2: t_p must be covered")
        assertTrue(
            covering.all { PeriodKinds.coversNoScreen(it.restrictiveKind) },
            "…by a period that turns the on-screen tasks away",
        )

        // And where the gap is a real one, the cover is the README's own object: `no on-screen task`, from
        // the last such period's end up to the line. (One break with a cadence longer than the window, so the
        // grid places nothing and the standing period is what the gap is measured from.)
        val rare = listOf(ScreenBreak("rare", intervalMillis = 48 * HOUR, durationMillis = 15 * MIN))
        val standing = RestrictivePeriod(NOW - 2 * HOUR, NOW - 10 * MIN, PeriodKinds.NO_SCREEN, "No screen")
        val gap =
            SchedulerDomain.takenScreenBreakPanels(
                rare, NOW - 6 * HOUR, NOW - 1,
                basePeriods = listOf(standing),
                tpMillis = NOW,
                mode = DynamicPeriods.MODE_AWAY,
            )
        assertTrue(
            gap.none { it.title.equals("Away", ignoreCase = true) },
            "a no-screen stretch must not be rendered as a synthetic Away band",
        )
    }

    @Test
    fun the_mode_follows_whether_any_device_is_unlocked() {
        // The rule, and the whole of it: mode 1 while a device of the account is unlocked, mode 2 otherwise -
        // read off the same account-wide pause the calendar draws as its Inactivity band.
        val unlocked = SchedulerDomain.anyDeviceUnlockedAt(emptyList(), null, null, NOW)
        assertTrue(unlocked, "no pause anywhere means somebody is at a screen")
        assertEquals(DynamicPeriods.MODE_AT_SCREEN, SchedulerDomain.tpMode(unlocked))

        // An OPEN pause on this device - the lock signal, or the "I'm away" button - reaches up to the line.
        val locked = SchedulerDomain.anyDeviceUnlockedAt(emptyList(), NOW - 10 * MIN, null, NOW)
        assertTrue(!locked, "an ongoing pause covers the line, so no device is unlocked")
        assertEquals(DynamicPeriods.MODE_AWAY, SchedulerDomain.tpMode(locked))

        // Once the user is back the pause is capped at the return, and the line is outside it again.
        assertTrue(SchedulerDomain.anyDeviceUnlockedAt(emptyList(), NOW - 10 * MIN, NOW - MIN, NOW))
        // A pause the account-wide derive banked, which the line has since left, says nothing about now.
        val banked = listOf(TaskTimeRange(NOW - HOUR, NOW - 30 * MIN))
        assertTrue(SchedulerDomain.anyDeviceUnlockedAt(banked, null, null, NOW))
    }

    @Test
    fun an_ongoing_pause_covers_the_line_so_mode_two_needs_no_cover_of_its_own() {
        // The two halves compose: where the app has live evidence that nobody is at a screen, that evidence IS
        // mode 2's cover, and `awayCover` finds nothing left to do.
        val ongoing =
            SchedulerDomain.liveRestPeriod(
                SchedulerDomain.LiveRest(TaskTimeRange(NOW - 10 * MIN, NOW), ongoing = true),
            )
        assertTrue(ongoing != null && ongoing.covers(NOW), "an ongoing pause covers the now-line")
        val held =
            SchedulerDomain.liveRestPeriod(
                SchedulerDomain.LiveRest(TaskTimeRange(NOW - 10 * MIN, NOW), ongoing = false),
            )
        assertTrue(held != null && !held.covers(NOW), "one the user has come back from does not")

        val away =
            SchedulerDomain.takenScreenBreakPanels(
                breaks, NOW - 6 * HOUR, NOW - 1,
                basePeriods = listOf(ongoing!!),
                tpMillis = NOW,
                mode = DynamicPeriods.MODE_AWAY,
            )
        assertTrue(
            away.none { it.title.equals("Away", ignoreCase = true) },
            "the live pause already covers t_p and no synthetic Away band is rendered",
        )
    }

    // ----- what the DEVICES observed is a rest stretch too ---------------------------------------

    @Test
    fun a_pause_the_devices_observed_bars_the_5min_period_for_an_hour() {
        // The reported anomaly (2026-08-29): both layers said "nobody unlocked" from 12:15 to 12:28 and a
        // 5-minute pose was owed at 12:40, thirteen minutes into the hour the README bars it in.
        //
        // A rest stretch is read out of the TIMELINE, so a pause has to be on the timeline. The live gap only
        // ever holds the pause this device is in the middle of (and a restart clears even that), so a pause
        // that has ENDED left no mark at all and the bars went on counting from the last recorded break.
        //
        // The window is anchored on the baseline's own answer so the assertion cannot go vacuous: the rest is
        // placed to end ten minutes before the 5-min pose the bars would otherwise put there.
        val baselineFirst5 = starts(place(), pose5).minOrNull()
        assertTrue(baselineFirst5 != null, "the 5 min period must appear at all")
        val restEnd = NOW + baselineFirst5 - 10 * MIN
        val restStart = restEnd - 13 * MIN
        val observed = listOf(TaskTimeRange(restStart, restEnd))

        // The baseline really does put a pose inside the barred hour - that IS the anomaly.
        assertTrue(
            starts(place(), pose5).any { NOW + it >= restEnd && NOW + it < restEnd + HOUR },
            "the scenario must contain the pose the rest is supposed to bar",
        )

        val periods = SchedulerDomain.observedNoScreenPeriods(observed)
        assertEquals(1, periods.size)
        assertEquals(PeriodKinds.NO_SCREEN, periods.single().kind)

        // An ordinary on-screen task: a 0 against "no on-screen task" is what "on screen" IS.
        val onScreen = listOf(PlanTask(TaskId("task/user/0"), 1.0, 15 * MIN, mapOf(PeriodKinds.NO_SCREEN to 0.0)))
        val barred = place(periods = periods, tasks = onScreen)
        val offending = starts(barred, pose5).filter { NOW + it >= restEnd && NOW + it < restEnd + HOUR }
        assertTrue(
            offending.isEmpty(),
            "a 13-minute observed pause must bar the 5 min period for an hour after it; " +
                "got one at ${offending.firstOrNull()?.div(60000)}min",
        )
    }

    @Test
    fun an_observed_pause_is_no_on_screen_task_so_an_off_screen_task_keeps_it_from_being_a_rest() {
        // The evidence says nobody was at a SCREEN, and that is all it says - which is why the period's kind
        // is `no on-screen task` and not `no task allowed`. The README's clause takes all three of its parts:
        // *covered by "no on-screen task"* **without any task**. A task that may run off a screen could have
        // been working straight through it, so the stretch is correctly not a rest on such an account.
        val baselineFirst5 = starts(place(), pose5).minOrNull()
        assertTrue(baselineFirst5 != null)
        val restEnd = NOW + baselineFirst5 - 10 * MIN
        val periods = SchedulerDomain.observedNoScreenPeriods(listOf(TaskTimeRange(restEnd - 13 * MIN, restEnd)))

        val onScreen = listOf(PlanTask(TaskId("task/user/0"), 1.0, 15 * MIN, mapOf(PeriodKinds.NO_SCREEN to 0.0)))
        // No override at all = resilience 1 to every kind but `no task allowed`: an off-screen task.
        val offScreen = listOf(PlanTask(TaskId("task/user/1"), 1.0, 15 * MIN))

        assertEquals(
            starts(place(tasks = offScreen), pose5),
            starts(place(periods = periods, tasks = offScreen), pose5),
            "somebody could have been working there, so it bars nothing",
        )
        assertTrue(
            starts(place(periods = periods, tasks = onScreen), pose5) !=
                starts(place(tasks = onScreen), pose5),
            "with only on-screen tasks the same stretch IS a rest",
        )
    }

    @Test
    fun the_fill_is_handed_what_the_devices_observed() {
        // The wiring, at the level the app actually runs at. `fillSchedule` assembles the bars' environment
        // itself (out of the panels it is keeping), so an observed pause has to reach it as a parameter or
        // everything above is unreachable from the running app - which is exactly what shipped.
        var state = SchedulerState.empty()
        state =
            SchedulerReducer.reduce(
                state,
                SchedulerIntent.SetCellTitle(state.lists[state.rootListId]!!.cellIds[0], "Screen work"),
            )
        state = state.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)

        fun poses(evidence: List<TaskTimeRange>) =
            SchedulerDomain.fillSchedule(
                state, NOW, horizonMillis = NOW + 6 * HOUR, noScreenEvidence = evidence,
            ).filter { it.title == pose5 }.map { it.startEpochMillis }

        val baselineFirst = poses(emptyList()).minOrNull()
        assertTrue(baselineFirst != null, "the fill must place a 5 min pose to be about")
        val restEnd = baselineFirst - 10 * MIN
        val observed = listOf(TaskTimeRange(restEnd - 13 * MIN, restEnd))
        assertTrue(
            poses(observed).none { it >= restEnd && it < restEnd + HOUR },
            "the fill must bar the 5 min pose for an hour after a pause the devices observed",
        )
    }

    @Test
    fun the_placement_environment_is_assembled_in_one_place() {
        // `dynamicPeriodBase` is the one funnel: the standing periods a set of panels holds, the live pause,
        // and what the devices observed. The fill, the cue sweep, the published pause-cue due and the calendar
        // all ask through it - asked three different ways they would answer three different timelines, and the
        // app would announce a break at an instant the calendar does not draw one at.
        val drawn =
            TaskPanel(
                id = "p1", taskId = null, title = "Inactivity",
                startEpochMillis = NOW - 4 * HOUR, endEpochMillis = NOW - 3 * HOUR,
                inactivity = true, periodKind = PeriodKinds.NO_TASK,
            )
        val base =
            SchedulerDomain.dynamicPeriodBase(
                panels = listOf(drawn),
                liveRest = SchedulerDomain.LiveRest(TaskTimeRange(NOW - 10 * MIN, NOW), ongoing = true),
                noScreenEvidence = listOf(TaskTimeRange(NOW - 2 * HOUR, NOW - 90 * MIN)),
            )
        assertEquals(3, base.size, "one period per source, and none of the three dropped")
        assertTrue(base.any { it.startMillis == NOW - 4 * HOUR }, "the panel the user drew")
        assertTrue(base.any { it.covers(NOW) }, "the live pause, covering the line")
        assertTrue(
            base.any { it.startMillis == NOW - 2 * HOUR && it.kind == PeriodKinds.NO_SCREEN },
            "what the devices observed",
        )
    }

    // ----- a break the app CONDUCTED is a dynamic period, and bars like one -----------------------

    @Test
    fun a_break_the_app_conducted_bars_the_20s_period_for_twenty_minutes() {
        // The reported anomaly (2026-09-03): the user missed the 12:39 look-away, pressed "Look away now" at
        // 12:40, and the instant it finished ANOTHER 20 s period was owed at the now-line.
        //
        // The README's first bar is "after ANY dynamic restrictive period, no 20 s period in the next 20
        // minutes", and a break the app CONDUCTED is one of the three. It reached the bars as an ordinary
        // `no task allowed` period, which is right - but twenty seconds is neither of the two STRETCH bars
        // (>= 5 min, >= 15 min), and the first bar was only ever fired for the occurrences the walk placed
        // itself. So the conducted break barred nothing, and the next occurrence fell straight after it.
        //
        // This is also what the user's own exception rests on: a look-away the line has crossed stays on the
        // calendar where it happened, unless pressing "Look away now" less than twenty minutes later
        // re-anchors the bar off the break they actually took.
        //
        // The scenario is the user's: a rest half an hour back has the 5 min and the 15 min barred, so the
        // 20 s is the only one in play, and one falls due inside the next twenty minutes.
        val onScreen = listOf(PlanTask(TaskId("task/user/0"), 1.0, 15 * MIN, mapOf(PeriodKinds.NO_SCREEN to 0.0)))
        val earlierRest = RestrictivePeriod(NOW - 50 * MIN, NOW - 30 * MIN, PeriodKinds.NO_TASK, "Inactivity")
        assertTrue(
            starts(place(periods = listOf(earlierRest), tasks = onScreen), lookAway)
                .any { it in 0 until DynamicPeriods.BAR_20S_AFTER_ANY_MILLIS },
            "the scenario must be one where a 20 s period falls due inside the next twenty minutes",
        )

        val conducted =
            SchedulerReducer.reduce(
                SchedulerState.empty(),
                SchedulerIntent.RecordConductedBreak(lookAway, NOW - 20 * SEC, NOW),
            ).panels
        assertTrue(conducted.single().conductedBreak, "the recorded break must say it was one of the three")
        val periods = SchedulerDomain.restrictivePeriodsOf(conducted)
        assertEquals(1, periods.size, "the conducted break must reach the bars as a period")
        assertTrue(periods.single().dynamic, "…as a DYNAMIC one")

        val after = place(periods = listOf(earlierRest) + periods, tasks = onScreen)
        val offending = starts(after, lookAway).filter { it < DynamicPeriods.BAR_20S_AFTER_ANY_MILLIS }
        assertTrue(
            offending.isEmpty(),
            "a 20 s period fell ${offending.firstOrNull()?.div(1000)}s after a conducted look-away",
        )
    }

    @Test
    fun a_hand_drawn_twenty_second_inactivity_is_not_a_dynamic_period() {
        // The other half of the same rule. The first bar keys on a dynamic restrictive PERIOD; a 20-second
        // span the user drew is a pre-placed restrictive period, and it is too short to be a rest stretch, so
        // the README bars nothing after it. Read as "a short `no task allowed` period" instead of as the mark
        // it is, the fix above would have quietly changed what a hand-drawn period means.
        val onScreen = listOf(PlanTask(TaskId("task/user/0"), 1.0, 15 * MIN, mapOf(PeriodKinds.NO_SCREEN to 0.0)))
        val earlierRest = RestrictivePeriod(NOW - 50 * MIN, NOW - 30 * MIN, PeriodKinds.NO_TASK, "Inactivity")
        val drawn = RestrictivePeriod(NOW - 20 * SEC, NOW, PeriodKinds.NO_TASK, "Inactivity")
        assertEquals(
            starts(place(periods = listOf(earlierRest), tasks = onScreen), lookAway),
            starts(place(periods = listOf(earlierRest, drawn), tasks = onScreen), lookAway),
            "a hand-drawn 20 s inactivity bars nothing",
        )
    }
}
