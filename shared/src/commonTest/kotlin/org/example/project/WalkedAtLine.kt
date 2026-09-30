package org.example.project

import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerState

/**
 * The breaks a line at [nowMillis] will meet up to [untilMillis] if the mode it is in holds — what a plan over [state]
 * is built around ([SchedulerDomain.breaksTheLineWillMeet]), over the environment the fill reads.
 */
internal fun metBy(
    state: SchedulerState,
    nowMillis: Long,
    untilMillis: Long,
    mode: Int = DynamicPeriods.MODE_AT_SCREEN,
    frozen: FrozenScreenBreaks? = null,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<TaskPanel> {
    val env = SchedulerDomain.breakEnvironment(state, nowMillis, untilMillis, timeZone, mode = mode, frozen = frozen, tasks = emptyList())
    return SchedulerDomain.breaksTheLineWillMeet(state.screenBreaks, nowMillis, untilMillis, env.periods, mode, frozen)
}
