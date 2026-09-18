package org.example.project

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.state.SchedulerReducer

/**
 * The reducer's re-plans fill to `SchedulerReducer.scheduleHorizonEndMillis`, whose default with no engine
 * attached is the rolling floor: `now + 2 × 10 min` (`docs/scheduler_requirements.md` § *Progressive
 * Calculation*, [SchedulerDomain.scheduleGoalEndMillis]). A test about a rule that only shows over a longer
 * plan — a run reaching its minimum, a second task being rotated in — stands in for an open calendar with
 * [show] and restores the default with [close], rather than asserting against a plan the production app would
 * not make.
 *
 * Both install a horizon as a plain offset from the line rather than calling
 * [SchedulerDomain.scheduleHorizonEndMillis]: the real goal's third term is the END OF THE CURRENT WEEK, in the
 * zone the app runs in, so asking for it here would make every fill plan to the next Monday of whatever
 * machine the suite is on — and a `show(2 h)` would quietly plan seven days. The goal's own three terms are
 * pinned directly in `ScheduleHorizonTest`.
 */
internal object CalendarHorizonFixture {
    const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** The calendar shows [spanMillis] past the now-line (a week by default, the materialization ceiling). */
    fun show(spanMillis: Long = SchedulerDomain.SCHEDULE_HORIZON_MILLIS) {
        SchedulerReducer.scheduleHorizonEndMillis = { it + spanMillis }
    }

    fun close() {
        SchedulerReducer.scheduleHorizonEndMillis = { it + 2 * SchedulerDomain.SCHEDULE_GOAL_FLOOR_MILLIS }
    }
}
