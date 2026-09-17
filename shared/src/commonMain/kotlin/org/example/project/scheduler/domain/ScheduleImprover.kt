package org.example.project.scheduler.domain

import kotlin.math.exp

/**
 * `docs/scheduler_score.md` § *Degradation* — **getting the whole continuation closer to the best score.**
 *
 * [ScheduleOptimizer.plan] builds a continuation one decision at a time, and every decision is judged over a short
 * window. A choice that is best for its window can still cost more over the continuation. This pass works on the
 * continuation as a whole and judges every change by the score of the WHOLE continuation — the number the
 * definition says is minimized — so it can only ever move the plan closer to the best score, never away from it.
 *
 * ### The moves
 * Over the free runs (a pre-placed run is never touched), in a fixed order:
 * 1. **shift** the boundary between two neighbouring runs — to where one of them reaches its minimum, or by a few
 *    set amounts, or all the way (which removes one of them);
 * 2. **swap** two neighbouring runs;
 * 3. **reassign** one run to another task that may run over all of it.
 * A move that would put a task where it may not run is never tried. The first move that lowers the score is kept,
 * and the passes repeat until one of them changes nothing or [budget] moves have been scored.
 *
 * ### Why a move is cheap to score
 * A task's lag depends on its own service and its own target alone, so the score splits into one term per task plus
 * the shortfalls. A move changes the service of at most two tasks, so only their two terms (and the shortfalls, a
 * sum over the runs) are recomputed; every other task's term is reused.
 *
 * Deterministic, and the budget is counted in scored moves, so every device reaches the same plan.
 */
internal class ScheduleImprover(
    private val model: ScoreModel,
    private val start: ScoreCursor,
    private val untilU: Double,
    /** PRD §13 / §7: the first run's task is decided by the caller and never changed here. */
    private val pinFirstTask: Boolean,
    private val budget: Int,
) {
    private data class Seg(val task: Int, val from: Double, val to: Double, val fixed: Boolean) {
        val length: Double get() = to - from
    }

    /** How many moves were scored, and whether the passes stopped on their own (no move left that helps). */
    var scored: Int = 0
        private set
    var converged: Boolean = false
        private set

    fun improve(runs: List<ScheduleOptimizer.Run>): List<ScheduleOptimizer.Run> {
        var segs = runs.map { Seg(it.task, it.fromU, it.toU, fixed = model.fixedAt(it.fromU) >= 0) }
        if (segs.size < 2 && segs.none { !it.fixed }) return runs
        val taskCost = DoubleArray(model.n) { taskCost(segs, it) }
        var total = taskCost.sum() + shortfalls(segs)

        fun tryMove(candidate: List<Seg>, affected: IntArray): Boolean {
            if (scored >= budget) return false
            scored++
            val merged = coalesce(candidate)
            var next = shortfalls(merged)
            val fresh = DoubleArray(affected.size)
            for (i in 0 until model.n) {
                val a = affected.indexOf(i)
                if (a >= 0) {
                    fresh[a] = taskCost(merged, i)
                    next += fresh[a]
                } else {
                    next += taskCost[i]
                }
            }
            if (!ScheduleOptimizer.better(next, total)) return false
            for ((a, i) in affected.withIndex()) taskCost[i] = fresh[a]
            total = next
            segs = merged
            return true
        }

        while (scored < budget) {
            var changed = false
            var k = 0
            while (k < segs.size - 1 && scored < budget) {
                val a = segs[k]
                val b = segs[k + 1]
                if (a.fixed || b.fixed || a.task == b.task) {
                    k++
                    continue
                }
                val firstLocked = pinFirstTask && k == 0
                val moved = shiftMoves(segs, k, firstLocked).any { tryMove(it, intArrayOf(a.task, b.task)) } ||
                    (!firstLocked && swap(segs, k)?.let { tryMove(it, intArrayOf(a.task, b.task)) } == true)
                if (moved) changed = true else k++
            }
            for (k2 in segs.indices) {
                if (scored >= budget) break
                val s = segs.getOrNull(k2) ?: break
                if (s.fixed || (pinFirstTask && k2 == 0)) continue
                for (j in 0 until model.n) {
                    if (j == s.task || model.runLimit(j, s.from) < s.to - ScoreModel.EPS || !model.permitted(j, s.from)) continue
                    val candidate = segs.toMutableList().also { it[k2] = s.copy(task = j) }
                    if (tryMove(candidate, intArrayOf(s.task, j))) {
                        changed = true
                        break
                    }
                }
            }
            if (!changed) {
                converged = scored < budget
                break
            }
        }
        return segs.map { ScheduleOptimizer.Run(it.task, it.from, it.to, -1) }
    }

    /** The boundary between free runs [k] and `k+1` moved to every position worth trying. */
    private fun shiftMoves(segs: List<Seg>, k: Int, firstLocked: Boolean): Sequence<List<Seg>> = sequence {
        val a = segs[k]
        val b = segs[k + 1]
        val boundary = a.to
        val positions = LinkedHashSet<Double>()
        positions += a.from + model.minimum[a.task]
        positions += b.to - model.minimum[b.task]
        for (step in SHIFT_STEPS_MILLIS) {
            positions += boundary - step
            positions += boundary + step
        }
        if (!firstLocked) positions += a.from
        positions += b.to
        for (p in positions) {
            if (p < a.from - ScoreModel.EPS || p > b.to + ScoreModel.EPS || kotlin.math.abs(p - boundary) <= ScoreModel.EPS) continue
            if (firstLocked && p <= a.from + ScoreModel.EPS) continue
            if (p > boundary && model.runLimit(a.task, boundary) < p - ScoreModel.EPS) continue
            if (p < boundary && model.runLimit(b.task, p) < boundary - ScoreModel.EPS) continue
            val out = segs.toMutableList()
            out[k] = a.copy(to = p)
            out[k + 1] = b.copy(from = p)
            yield(out)
        }
    }

    /** Runs [k] and `k+1` exchanged, each keeping its own length; null where either may not run in its new place. */
    private fun swap(segs: List<Seg>, k: Int): List<Seg>? {
        val a = segs[k]
        val b = segs[k + 1]
        val mid = a.from + b.length
        if (!model.permitted(b.task, a.from) || model.runLimit(b.task, a.from) < mid - ScoreModel.EPS) return null
        if (!model.permitted(a.task, mid) || model.runLimit(a.task, mid) < b.to - ScoreModel.EPS) return null
        val out = segs.toMutableList()
        out[k] = Seg(b.task, a.from, mid, false)
        out[k + 1] = Seg(a.task, mid, b.to, false)
        return out
    }

    private fun coalesce(segs: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>(segs.size)
        for (s in segs) {
            if (s.length <= ScoreModel.EPS) continue
            val last = out.lastOrNull()
            if (last != null && !last.fixed && !s.fixed && last.task == s.task) out[out.size - 1] = last.copy(to = s.to)
            else out += s
        }
        return out
    }

    /** Task [i]'s criterion-1 term over the continuation, from the start's lag. */
    private fun taskCost(segs: List<Seg>, i: Int): Double {
        val c = ScoreCursor(start.lag.copyOf(), start.lagU.copyOf(), start.u, start.run, start.runLen, 0.0, start.originU)
        for (s in segs) {
            if (s.task != i) continue
            model.advance(c, i, s.from, served = false)
            model.advance(c, i, s.to, served = true)
        }
        model.advance(c, i, untilU, served = false)
        return c.cost
    }

    /**
     * Criterion 2 over the continuation: every panel that ends inside it — the run in progress at the start included
     * when the first run is somebody else's — charged where it ends. The panel still open at [untilU] is not charged,
     * exactly as [ScoreModel.serve] leaves it.
     */
    private fun shortfalls(segs: List<Seg>): Double {
        var acc = 0.0
        var run = start.run
        var runLen = start.runLen
        var u = start.u
        for (s in segs) {
            if (s.task != run) {
                acc += charge(run, runLen, u)
                run = s.task
                runLen = 0.0
            }
            runLen += s.length
            u = s.to
        }
        return acc
    }

    private fun charge(run: Int, runLen: Double, atU: Double): Double {
        if (run < 0) return 0.0
        val s = model.minimum[run] - runLen
        if (s <= ScoreModel.EPS) return 0.0
        return exp(-(atU - start.originU) / model.theta) * model.shortfallCost(run, s)
    }

    private companion object {
        val SHIFT_STEPS_MILLIS = doubleArrayOf(60_000.0, 5 * 60_000.0, 15 * 60_000.0, 30 * 60_000.0)
    }
}
