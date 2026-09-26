package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.state.SchedulerState
import kotlin.math.abs

/**
 * PRD §8 the calendar's **"locked on task"** (user spec 2026-09-26): what the view is held on when a task cell's
 * "go to calendar" asked for it — the middle of that task's panel CLOSEST to the now-line, recomputed as the
 * panels move (the line dragging one, a new set of rules) — and whether the menu entry is offered at all.
 */
object CalendarLockDomain {
    /** What "go to calendar" can reach for a task, which decides how the menu offers it. */
    enum class Reach {
        /** A panel of the task is on the calendar: the lock holds its middle. */
        Panel,

        /** No panel yet, but one can come (schedulable, priority above zero): offered with a loading mark. */
        Pending,

        /** No panel can come: the entry is not offered. */
        None,
    }

    /**
     * The middle of [taskId]'s panel closest to [nowMillis] — a scheduled or pinned panel ([SchedulerState.panels]),
     * a PROVISIONAL one ([provisionalPanels]: the plan the calendar draws past the definitive-schedule front by the
     * same rules, not yet settled — user spec 2026-09-26) or a recorded period of the task — or null when it has
     * none. A panel the now-line is inside is at distance zero; two at one distance, the earlier wins. Bounded by
     * one task's panels and records, asked on demand.
     */
    fun closestPanelCenterMillis(
        state: SchedulerState,
        taskId: TaskId,
        nowMillis: Long,
        provisionalPanels: List<TaskPanel> = emptyList(),
    ): Long? {
        val ranges =
            (state.panels.asSequence() + provisionalPanels.asSequence())
                .filter { it.taskId == taskId }
                .map { it.startEpochMillis to it.endEpochMillis } +
                state.tasks[taskId]?.record.orEmpty().asSequence().map { it.startEpochMillis to it.endEpochMillis }
        fun distance(start: Long, end: Long): Long =
            when {
                nowMillis in start until end -> 0L
                nowMillis < start -> start - nowMillis
                else -> abs(nowMillis - end)
            }
        return ranges
            .filter { (start, end) -> end > start }
            .minWithOrNull(compareBy({ (start, end) -> distance(start, end) }, { it.first }))
            ?.let { (start, end) -> start + (end - start) / 2 }
    }

    /** Whether a panel of [taskId] can come at all: a schedulable leaf with a priority above zero. */
    fun canBeScheduled(state: SchedulerState, taskId: TaskId): Boolean =
        SchedulerDomain.isPlaceableTask(state, taskId) &&
            (SchedulerDomain.absoluteTaskPriorities(state)[taskId] ?: 0.0) > 0.0

    fun reach(
        state: SchedulerState,
        taskId: TaskId,
        nowMillis: Long,
        provisionalPanels: List<TaskPanel> = emptyList(),
    ): Reach =
        when {
            closestPanelCenterMillis(state, taskId, nowMillis, provisionalPanels) != null -> Reach.Panel
            canBeScheduled(state, taskId) -> Reach.Pending
            else -> Reach.None
        }

    /**
     * The instant the lock holds at the middle of the view: the closest panel's middle, else — while one is yet to
     * come — the **definitive-schedule front** ([SchedulerDomain.definitiveScheduleFrontMillis], passed in as
     * [definitiveFrontMillis]), the last instant the scheduler has settled, until a panel of the task appears.
     * Null when no panel can ever come.
     */
    fun lockCenterMillis(
        state: SchedulerState,
        taskId: TaskId,
        nowMillis: Long,
        definitiveFrontMillis: Long,
        provisionalPanels: List<TaskPanel> = emptyList(),
    ): Long? =
        closestPanelCenterMillis(state, taskId, nowMillis, provisionalPanels)
            ?: definitiveFrontMillis.takeIf { canBeScheduled(state, taskId) }
}
