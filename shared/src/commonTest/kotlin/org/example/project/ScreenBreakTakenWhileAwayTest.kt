package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.BreakMachine.Event
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AT_SCREEN
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AWAY
import org.example.project.scheduler.domain.DynamicPeriods.Span
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.SchedulerDomain

/**
 * The report: *"I got a notification for a 5min screen break, did the 5min break, woke the app up, and saw in the
 * calendar an inactivity period instead of the 5min break"*.
 *
 * With the break machine this is the line's own history: the pose falls due at a line at a screen (owed, dragged),
 * the user walks away — the line is covered now, so it enters the pose right there — and what it entered is banked.
 * Coming back re-derives nothing; the record is the past.
 */
class ScreenBreakTakenWhileAwayTest {

    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 3_600_000L

    private val specs =
        listOf(
            DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 20 * MIN),
            DynamicPeriods.Spec(DynamicPeriods.LABEL_5MIN, 5 * MIN, 60 * MIN),
        )

    /** At a screen until [walkedAway], away (mode 2) until [cameBack], at a screen again until [toMillis]. */
    private fun live(
        walkedAway: Long,
        cameBack: Long,
        toMillis: Long,
        specs: List<DynamicPeriods.Spec> = this.specs,
        events: MutableList<Event> = ArrayList(),
    ): BreakMachine.State {
        var s = BreakMachine.advance(BreakMachine.initial(0L, specs), walkedAway, emptyList(), specs, events)
        s = BreakMachine.switchMode(s, MODE_AWAY, emptyList(), specs, events)
        s = BreakMachine.advance(s, cameBack, emptyList(), specs, events)
        s = BreakMachine.switchMode(s, MODE_AT_SCREEN, emptyList(), specs, events)
        return BreakMachine.advance(s, toMillis, emptyList(), specs, events)
    }

    @Test
    fun the_pause_the_pose_was_taken_in_holds_the_pose() {
        // The 5-min pose falls due at 60 min, which is when the user walks away; they are back six minutes later.
        val events = ArrayList<Event>()
        live(60 * MIN, 66 * MIN, 67 * MIN, events = events)
        val pose = events.filterIsInstance<Event.Started>().single { it.label == DynamicPeriods.LABEL_5MIN }
        assertEquals(60 * MIN, pose.startMillis, "the pose starts where the pause did")
        assertEquals(5 * MIN, pose.endMillis - pose.startMillis, "and is not stretched to cover the rest of the pause")
        assertTrue(events.none { it is Event.Removed }, "the user came back after it ended: it happened")
    }

    @Test
    fun the_past_does_not_change_when_the_user_comes_back() {
        val t0 = 1_700_000_000_000L
        val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
        var record: FrozenScreenBreaks? = null
        fun at(t: Long, mode: Int) {
            record = SchedulerDomain.stepScreenBreaks(breaks, record, t, emptyList(), mode).record
        }
        at(t0, MODE_AT_SCREEN)
        at(t0 + HOUR, MODE_AT_SCREEN)
        at(t0 + HOUR, MODE_AWAY)
        at(t0 + HOUR + 6 * MIN, MODE_AWAY)
        val whileAway = record!!.breaks
        assertTrue(whileAway.any { it.label == DynamicPeriods.LABEL_5MIN && it.startMillis == t0 + HOUR }, "$whileAway")
        at(t0 + HOUR + 6 * MIN, MODE_AT_SCREEN)
        at(t0 + 2 * HOUR, MODE_AT_SCREEN)
        assertEquals(whileAway, record!!.breaks.filter { it.startMillis < t0 + HOUR + 6 * MIN }, "the past is the record")
        val drawn = SchedulerDomain.takenScreenBreakPanels(breaks, t0, t0 + 2 * HOUR, record)
        val pose5 = breaks.single { it.key == SchedulerDomain.FIVE_MIN_BREAK_KEY }
        assertTrue(
            drawn.any { it.title == pose5.title && it.startEpochMillis == t0 + HOUR },
            "and the calendar draws the pose the user took, where they took it: $drawn",
        )
    }

    @Test
    fun a_pause_too_short_for_the_pose_leaves_it_owed() {
        val events = ArrayList<Event>()
        val s = live(60 * MIN, 62 * MIN, 63 * MIN, events = events)
        assertTrue(events.any { it is Event.Removed && it.label == DynamicPeriods.LABEL_5MIN }, "two minutes is no pose")
        assertEquals(DynamicPeriods.LABEL_5MIN, s.drag?.label, "it is owed, and mode 1 keeps it at the line")
    }

    @Test
    fun a_pause_with_no_break_due_in_it_stays_a_plain_pause() {
        val events = ArrayList<Event>()
        live(10 * MIN, 16 * MIN, 17 * MIN, events = events)
        assertTrue(events.filterIsInstance<Event.Started>().none { it.label == DynamicPeriods.LABEL_5MIN }, "$events")
    }

    @Test
    fun the_stretch_still_bars_what_comes_after_it() {
        // The pose alone, so the answer is the bar itself.
        val pose = listOf(DynamicPeriods.Spec(DynamicPeriods.LABEL_5MIN, 5 * MIN, 60 * MIN))
        val s = live(60 * MIN, 66 * MIN, 67 * MIN, specs = pose)
        assertEquals(66 * MIN + HOUR, BreakMachine.dueOf(s, DynamicPeriods.LABEL_5MIN, emptyList()), "one hour after the stretch ended")
    }

    @Test
    fun a_no_screen_period_the_line_is_in_at_a_screen_does_not_hand_it_a_pose() {
        // Mode 1: the no-screen period the line is in gives way to it (]now line; t2]); a pose falling due there is
        // pulled onto the line and dragged — never entered.
        val period = Span(60 * MIN, 90 * MIN)
        val s = BreakMachine.advance(BreakMachine.initial(0L, specs), 62 * MIN, listOf(period), specs)
        assertEquals(DynamicPeriods.LABEL_5MIN, s.drag?.label)
        assertNull(s.active?.takeIf { it.label == DynamicPeriods.LABEL_5MIN })
    }
}
