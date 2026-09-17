package org.example.project

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.state.SchedulerReducer

/**
 * The reducer's re-plans fill to `SchedulerReducer.scheduleHorizonEndMillis`, which with no engine attached is
 * the CALENDAR-CLOSED goal: `now + 10 min` (`docs/scheduler_requirements.md` § *Progressive Calculation*,
 * [SchedulerDomain.scheduleGoalEndMillis]). A test about a rule that only shows over a longer plan — a run
 * reaching its minimum, a second task being rotated in — stands in for an open calendar with [show] and
 * restores the default with [close], rather than asserting against a plan the production app would not make.
 */
internal object CalendarHorizonFixture {
    const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** The calendar shows [spanMillis] past the now-line (a week by default, the materialization ceiling). */
    fun show(spanMillis: Long = SchedulerDomain.SCHEDULE_HORIZON_MILLIS) {
        SchedulerReducer.scheduleHorizonEndMillis = { SchedulerDomain.scheduleHorizonEndMillis(it, it + spanMillis) }
    }

    fun close() {
        SchedulerReducer.scheduleHorizonEndMillis = { SchedulerDomain.scheduleHorizonEndMillis(it, null) }
    }
}
