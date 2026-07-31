package org.example.project.scheduler.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange

/**
 * PRD §9 / `tests/README.md`: the **debt** model the scheduler picks with — the Kotlin port of the reference
 * implementation in `tests/test.py` (`SchedulerFunction`).
 *
 * The rules it implements, verbatim from `tests/README.md`:
 * - each task has a **minimum time** and a **priority percentage**; once a task is placed, nothing else may be
 *   placed until its minimum has elapsed (that part is the caller's cursor walk, see
 *   [SchedulerDomain.fillSchedule]);
 * - the priorities must be satisfied "as much as possible, **in a scale as small as possible**" — i.e. two 50 %
 *   tasks with a 10-min minimum alternate every 10 min, they do not alternate every hour;
 * - "a debt or an excess that is **higher than it could be** in a scheduling on a clear and all-task-accepted
 *   timeline is ignored **exponentially** as we move away from it (in both directions); the decay speed is
 *   always the same."
 *
 * ### The model
 * Each task carries a signed `debt` in milliseconds: the service it is *owed*. While the timeline advances by
 * `dt` with task `x` running, every task's debt integrates `ṙ = pᵢ − eᵢ` where `eᵢ` is 1 for the running task
 * and 0 otherwise. A task that never runs accrues `pᵢ·dt` of debt; the running task discharges `(1 − pₓ)·dt`.
 * Picking the task with the largest `debt / priority` is therefore exactly "satisfy the percentages", and
 * because the pick is re-evaluated at every minimum-length chunk the balancing happens at the smallest scale
 * the minima allow.
 *
 * ### The bound C and the decay
 * On a clear, all-accepting timeline a task's debt never exceeds the sum of every task's minimum time —
 * one full rotation of the round-robin ([boundary], `C = Σ mᵢ`). Debt inside `[−C, +C]` is therefore
 * "natural" and is integrated **linearly, without any decay**: it is real information the scheduler must act
 * on. Only the part **beyond** the bound (a genuinely abnormal excess/deficit, e.g. 1000 min of one task
 * pinned in the past) decays exponentially toward the bound, at a single fixed speed ([halfLifeMillis]).
 * So a huge past excess is *forgotten* rather than being repaid in full, but an ordinary one-rotation
 * imbalance is repaid exactly.
 *
 * Inside a segment the state obeys `ẏ = R − k·y` with `y = |d| − C` (the excess) — solved in **closed form**
 * per segment ([advance]) rather than by stepping, so a 168-h fill costs one `exp` per task per placed chunk
 * and the result is independent of how the caller happens to chop the timeline up.
 *
 * ### Both directions
 * "In both directions" is the future half: work already committed *ahead* of the cursor (a pinned/manual
 * calendar block) discharges the task's debt before the cursor ever reaches it — see
 * [futureCommitmentMillis], which decays the **excess** portion of each future block by its distance in
 * exactly the same way. That is what makes a task with a big block looming soon yield the cursor now.
 */
data class DebtTask(
    val id: TaskId,
    /** The task's absolute priority share, in `[0, 1]` (see [SchedulerDomain.absoluteTaskPriorities]). */
    val priority: Double,
    /** PRD §10 minimum time, in millis — the shortest slot the task may be placed in. */
    val minimumMillis: Long,
)

/**
 * The running debt state of one fill, in millis. Not thread-safe and single-use: [advance] mutates it as the
 * caller's cursor sweeps the timeline (past first, to seed it, then forward).
 */
class DebtLedger(
    private val tasks: List<DebtTask>,
    private val halfLifeMillis: Double = SchedulerDomain.DEBT_HALF_LIFE_MILLIS.toDouble(),
) {
    /** `C = Σ mᵢ` — the largest debt a clear, all-accepting timeline can produce. Zero when there are no tasks. */
    val boundary: Double = tasks.sumOf { it.minimumMillis.toDouble() }

    /** The decay rate `k = ln 2 / halfLife`; 0 disables decay (debt then only ever clamps at ±C). */
    private val k: Double = if (halfLifeMillis > 0.0) ln(2.0) / halfLifeMillis else 0.0

    private val debts: HashMap<TaskId, Double> = HashMap<TaskId, Double>(tasks.size).apply {
        for (t in tasks) put(t.id, 0.0)
    }

    private val priorities: Map<TaskId, Double> = tasks.associate { it.id to it.priority }

    fun debtOf(id: TaskId): Double = debts[id] ?: 0.0

    /**
     * Advance every task's debt over [durationMillis] of timeline with [runningTaskId] being served (null =
     * nothing served — an idle gap, a period that accepts no task, a screen break nobody can work through).
     *
     * Exact piecewise solution of `ḋ = R` inside `[−C, C]` and `ẏ = R − k·y` outside it (`y` = the excess past
     * the bound), splitting the interval at the instant the trajectory reaches the bound so the two regimes
     * are never mixed within one closed-form step.
     */
    fun advance(durationMillis: Long, runningTaskId: TaskId?) {
        if (durationMillis <= 0L) return
        val c = boundary
        for (task in tasks) {
            val rate = task.priority - (if (task.id == runningTaskId) 1.0 else 0.0)
            var remaining = durationMillis.toDouble()
            var guard = 0
            while (remaining > EPSILON && guard++ < MAX_SEGMENTS) {
                val d = debts[task.id] ?: 0.0
                val step: Double
                when {
                    // 1. Above the bound (or sitting on it and still growing): the excess decays.
                    d > c + EPSILON || (d >= c - EPSILON && rate > 0.0) -> {
                        val y0 = d - c
                        step = minOf(remaining, timeToBound(y0, rate))
                        debts[task.id] = c + decayed(y0, rate, step)
                    }
                    // 2. Below the negative bound (or sitting on it and still shrinking): mirror image.
                    d < -c - EPSILON || (d <= -c + EPSILON && rate < 0.0) -> {
                        val y0 = d + c
                        step = minOf(remaining, timeToBound(y0, rate))
                        debts[task.id] = -c + decayed(y0, rate, step)
                    }
                    // 3. Inside [-C, C]: plain linear integration, split at the bound it is heading for.
                    else -> {
                        val toBound = when {
                            rate > 0.0 -> (c - d) / rate
                            rate < 0.0 -> (-c - d) / rate
                            else -> Double.POSITIVE_INFINITY
                        }
                        step = minOf(remaining, toBound)
                        debts[task.id] = d + rate * step
                    }
                }
                snapToBound(task.id, c)
                remaining -= step
                if (step <= 0.0) break // defensive: a zero-length segment can only mean we are pinned on a bound
            }
        }
    }

    /**
     * The time it takes `ẏ = R − k·y` to bring the excess [y0] back to the bound (`y = 0`), or
     * [Double.POSITIVE_INFINITY] when it never gets there (it is growing, or decay is off).
     */
    private fun timeToBound(y0: Double, rate: Double): Double {
        // With decay off the regime outside the bound integrates exactly like the one inside it, so there is
        // nothing to split on — the whole interval is one segment.
        if (k <= 0.0) return Double.POSITIVE_INFINITY
        val denominator = rate - k * y0
        if (abs(denominator) <= TINY) return Double.POSITIVE_INFINITY
        val ratio = rate / denominator
        return if (ratio > 0.0 && ratio < 1.0) -ln(ratio) / k else Double.POSITIVE_INFINITY
    }

    /** The closed-form value of `y` after [step] of `ẏ = R − k·y` starting from [y0]. */
    private fun decayed(y0: Double, rate: Double, step: Double): Double =
        if (k <= 0.0) {
            y0 + rate * step
        } else {
            val asymptote = rate / k
            asymptote + (y0 - asymptote) * exp(-k * step)
        }

    /** Kill the float dust that would otherwise make a task hover a hair off a bound forever. */
    private fun snapToBound(id: TaskId, c: Double) {
        val d = debts[id] ?: return
        if (abs(d - c) < EPSILON) debts[id] = c else if (abs(d + c) < EPSILON) debts[id] = -c
    }

    /**
     * `tests/README.md` "in both directions": the service [blocks] (a task's already-committed future work —
     * pinned/manual calendar panels) will deliver **after** [fromMillis], as seen from [fromMillis].
     *
     * Each block counts in full up to the natural bound `C`; only the part **beyond** it is discounted
     * exponentially by the block's distance, exactly like a past excess. Subtracting this from the task's debt
     * before scoring is what makes a task with an hour of itself pinned an hour from now step aside for its
     * peers *now* — rather than running now and then again when the pinned block arrives.
     */
    fun futureCommitmentMillis(blocks: List<TaskTimeRange>, fromMillis: Long): Double {
        if (blocks.isEmpty()) return 0.0
        val c = boundary
        var total = 0.0
        for (block in blocks) {
            val start = maxOf(fromMillis, block.startEpochMillis)
            val end = block.endEpochMillis
            if (end <= start) continue
            val span = (end - start).toDouble()
            total += if (span > c) {
                c + (span - c) * (if (k > 0.0) exp(-k * (start - fromMillis).toDouble()) else 1.0)
            } else {
                span
            }
        }
        return total
    }

    /**
     * The starvation score used to pick the next task: how far behind its own share the task is, per unit of
     * priority, once its committed future work is netted off. Highest score wins.
     *
     * Dividing by the priority is what keeps the *ratio* of served time at the requested percentages instead of
     * merely equalizing absolute debts. A zero-priority task divides by [MIN_PRIORITY] instead, so it sorts far
     * below everything with a real share yet is still placeable when it is the only accepted task (PRD §9 /
     * `tests/test.py` test 12).
     */
    fun score(id: TaskId, futureCommitmentMillis: Double = 0.0): Double {
        val priority = priorities[id] ?: 0.0
        return (debtOf(id) - futureCommitmentMillis) / maxOf(priority, MIN_PRIORITY)
    }

    private companion object {
        /** A microsecond, in millis: below this two debts are the same number as far as the fill is concerned. */
        const val EPSILON = 1e-3

        /** Guards against a denominator collapsing to zero in [timeToBound]. */
        const val TINY = 1e-12

        const val MIN_PRIORITY = 1e-9

        /**
         * Each [advance] call splits into at most one "reach the bound" segment plus one segment past it, per
         * regime; the cap is pure paranoia against a float pathology spinning the loop.
         */
        const val MAX_SEGMENTS = 8
    }
}
