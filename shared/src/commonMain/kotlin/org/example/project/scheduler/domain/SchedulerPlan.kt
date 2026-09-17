package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TaskId

/**
 * The scheduler's three inputs, as `docs/scheduler_requirements.md` names them: a **task** of the rule state, a
 * **pre-placed** block, and a **restrictive period** read as every task's multiplier. The score that is computed
 * over them is [ScoreModel]; the search for its best continuation is [ScheduleOptimizer]; the driver OmniApp's
 * calendar fill hands its world to is [ScheduleFill].
 *
 * A task with a priority of 0 is still a candidate wherever it may run: § *No idling* forbids leaving a stretch it
 * could fill empty, and the score gives it a target share only where nothing with a priority may run.
 */
data class PlanTask(
    val id: TaskId,
    /** The task's absolute priority share (see [SchedulerDomain.absoluteTaskPriorities]); 0 is allowed. */
    val priority: Double,
    /** PRD §10 minimum time, in millis — the shortest slot the task may be placed in. */
    val minimumMillis: Long,
    /**
     * `side-dev/README.md`: the task's **resilience** to each kind of restrictive period — a multiplier in
     * `[0, 1]` on its priority percentage for as long as a period of that kind lasts. Overrides only: a kind
     * absent from the map takes [PeriodKinds.defaultResilience], which is `0` for every kind except
     * [PeriodKinds.NO_SCREEN]. So a kind the user has only just defined turns everybody away until somebody
     * is given a value above zero, and "on screen" is exactly a `0` against [PeriodKinds.NO_SCREEN].
     */
    val resilience: Map<String, Double> = emptyMap(),
) {
    /** This task's multiplier inside a period of [kind]; see [PeriodKinds.resilienceFor]. */
    fun resilienceFor(kind: String): Double = PeriodKinds.resilienceFor(resilience, kind)
}

/** A pre-placed piece of timeline. [taskId] `null` = owned by nobody, so it excludes everyone equally. */
data class PlanBlock(val taskId: TaskId?, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

/**
 * A restrictive period, as the walk reads it: `[startMillis, endMillis)` with the per-task **multiplier** the
 * kinds covering it work out to. [endMillis] `null` = FOREVER (it never re-opens).
 *
 * The README's object is a *kind*, and the multiplier is `Π resilience(kind)` over every kind covering the
 * span ([PeriodKinds.multiplier]) — evaluated once, when the window is built ([of]), because the walk asks
 * for it at every step and the tasks it applies to do not change inside a plan. [multipliers] holds
 * **overrides only**, exactly like [PlanTask.resilience]: a task absent from it is unaffected here.
 */
data class PlanWindow(
    val startMillis: Long,
    val endMillis: Long?,
    /** taskId → its multiplier inside this window. Absent ⇒ [defaultMultiplier]. */
    val multipliers: Map<TaskId, Double> = emptyMap(),
    /** The README kinds this span is covered by — carried for diagnostics and for the recurrence bars. */
    val kinds: Set<String> = emptySet(),
    /**
     * What a task the map says nothing about gets. `1.0` for a window built from kinds ([of]) — the
     * resilience model's own default, "a kind this task was never told about leaves it alone". `0.0` for the
     * binary form ([accepting]), which names the accepted set and refuses everybody else, so it needs no
     * roster of who "everybody else" is.
     */
    val defaultMultiplier: Double = 1.0,
) {
    /** The multiplier this window applies to [id]: an override if it has one, else [defaultMultiplier]. */
    fun multiplierFor(id: TaskId): Double = multipliers[id] ?: defaultMultiplier

    /** Whether [id] may run here at all — a resilience of `0` is the README's own word for "forbidden". */
    fun allows(id: TaskId): Boolean = multiplierFor(id) > 0.0

    /**
     * The tasks this window explicitly does not turn away. Only meaningful for a window whose
     * [defaultMultiplier] is `0.0` (the binary form); a kind-built window turns nobody away by default and
     * this is the empty set, which is why the walk asks [multiplierFor] and never this.
     */
    val allowed: Set<TaskId> get() = multipliers.filterValues { it > 0.0 }.keys

    companion object {
        /**
         * The window a set of [kinds] makes for [tasks] — the README's model read straight: each task's
         * multiplier is the product of its resilience to every kind covering the span. A task the kinds leave
         * at `1` is omitted from [multipliers], so an uncovered stretch carries an empty map and costs
         * nothing to ask about.
         */
        fun of(
            startMillis: Long,
            endMillis: Long?,
            kinds: Collection<String>,
            tasks: Collection<PlanTask>,
        ): PlanWindow {
            if (kinds.isEmpty()) return PlanWindow(startMillis, endMillis)
            val mult = HashMap<TaskId, Double>()
            for (t in tasks) {
                val m = PeriodKinds.multiplier(t.resilience, kinds)
                if (m < 1.0) mult[t.id] = m
            }
            return PlanWindow(startMillis, endMillis, mult, kinds.toSet())
        }

        /**
         * The binary form, for a caller that has already worked out who is accepted (the tests). Everybody else is refused outright — stated
         * by the DEFAULT rather than by listing them, so the caller needs no roster of who exists.
         */
        fun accepting(startMillis: Long, endMillis: Long?, allowed: Set<TaskId>): PlanWindow =
            PlanWindow(startMillis, endMillis, allowed.associateWith { 1.0 }, defaultMultiplier = 0.0)
    }
}

