package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TaskTimeRange

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes*: **the line's walk at a screen, and when what it crossed is
 * banked.** A mode-1 line is never in "no screen", so what it walks of a period the user stated that is or carries
 * one is taken from that period (`PeriodCrossing`, [SchedulerDomain.withAtScreenCrossing]).
 *
 * § *Rule Structure*: nothing is scanned as the line moves. The walk holds the start of the mode-1 stretch it has not
 * banked and ONE armed trigger — the end of the next period that gives way — and [moveTo] compares the line with it.
 * A stretch is banked at exactly two events: that trigger, and the edge out of mode 1. The trigger is armed where a
 * stretch starts, where it fired, and where the periods changed ([moveTo]'s `periodsChanged`: the user drew one, the
 * plan was made again) — which is the only time [nextEndAfter] is asked.
 *
 * In memory only: a process that ends in mode 1 loses the stretch it had not banked.
 */
class AtScreenWalk(
    /** The next instant after the given one a period that gives way ends; `Long.MAX_VALUE` when none does. */
    private val nextEndAfter: (Long) -> Long,
) {
    private var at: Long? = null
    private var trigger = Long.MAX_VALUE
    private var armed = false

    /**
     * The start of the mode-1 stretch walked and not banked yet — what the calendar takes from the periods LIVE, so
     * a period the line is in reads ]line; its end] between two triggers. Null in modes 2 and 3.
     */
    var since: Long? = null
        private set

    /**
     * The line moved to [now] at a screen ([atScreen]) or not. The stretch to bank, or null before a trigger. The
     * first call only says where the walk starts.
     */
    fun moveTo(now: Long, atScreen: Boolean, periodsChanged: Boolean = false): TaskTimeRange? {
        val prev = at
        at = now
        if (prev == null || now <= prev) return null
        var from = since
        if (atScreen && from == null) {
            // The stretch starts where the line was: it was walked to here at a screen.
            from = prev
            since = prev
            armed = false
        }
        if (from == null) return null
        if (atScreen) {
            if (!armed || periodsChanged) {
                trigger = nextEndAfter(from)
                armed = true
            }
            if (now < trigger) return null
        }
        // The step that left the screen is no part of the stretch.
        val until = if (atScreen) now else prev
        since = if (atScreen) until else null
        armed = false
        trigger = Long.MAX_VALUE
        return TaskTimeRange(from, until).takeIf { until > from }
    }
}
