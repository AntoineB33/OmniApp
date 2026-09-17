package org.example.project.scheduler.domain

import kotlin.math.abs

/**
 * `docs/scheduler_score.md` — **the search for the best continuation.**
 *
 * The score ([ScoreModel]) says what "best" means; this class finds it, or gets as close as the budget allows —
 * the one degradation the requirements accept.
 *
 * ### Three passes, one score ([plan])
 * 1. **The rollout policy** builds the continuation one decision at a time ([evaluate]). It tries every candidate
 *    task with every candidate length, looks [lookaheadDepth] runs deep over the most promising of them, continues
 *    every trial with a simple base policy to a common window, and keeps the trial whose score is lowest. Comparing
 *    every trial over the SAME window is what keeps a short run from looking cheaper merely because it covers less
 *    time; the window is closed with each lag's [ScoreModel.lowerBound].
 * 2. **The whole-continuation improvement** ([ScheduleImprover]) then lowers the score of the continuation as a
 *    whole, within [improveBudget] scored moves. A decision that is best for its own window can still cost more
 *    over the continuation, and only this pass judges by the number the definition minimizes.
 * 3. **The alternatives** are named for every run of the result.
 *
 * **The exhaustive search** ([certify]) enumerates every sequence of candidate runs to a horizon, prunes with the
 * lower bound, and starts from [plan]'s own continuation as the incumbent. When it finishes inside [searchBudget],
 * nothing over the candidate lengths scores better than what it returns, and [Plan.certified] says so. It is out
 * of reach on a real account, so it checks the passes above on small cases rather than producing the rules.
 *
 * Every pass is deterministic, and every budget is counted in steps rather than wall time, so the same inputs give
 * the same rules on every device (`docs/scheduler_score.md` § *Degradation*).
 *
 * ### Why a trial costs only the tasks it serves
 * A task's lag depends on its own service and its own target alone. Between two trials from the same instant, a
 * task neither serves to the end of the window has the same lag trajectory and the same share of the score, so
 * each decision computes that share once per task (its baseline) and a trial pays only for the tasks it touches.
 *
 * ### Candidate lengths
 * A run of task `i` starting at `u` is tried for what is left of its minimum execution time, that plus half, one
 * and two minimums, up to the next three environment edges, and as far as it may run within the window.
 */
class ScheduleOptimizer(
    val model: ScoreModel,
    /** Steps the exhaustive search ([certify]) may take before it returns the best continuation found. */
    val searchBudget: Int = DEFAULT_SEARCH_BUDGET,
    /** How many runs deep the rollout policy looks before handing over to the base policy. */
    val lookaheadDepth: Int = DEFAULT_LOOKAHEAD_DEPTH,
    /** Moves [ScheduleImprover] may score on the whole continuation; 0 keeps the rollout policy's plan. */
    val improveBudget: Int = DEFAULT_IMPROVE_BUDGET,
) {
    /** One run of the continuation on the schedulable clock, with the alternative in force from [fromU]. */
    data class Run(val task: Int, val fromU: Double, val toU: Double, val alternative: Int)

    class Plan(val runs: List<Run>, val cost: Double, val certified: Boolean)

    private val maxMinimum: Double = (model.minimum.maxOrNull() ?: 0.0).coerceAtLeast(ScoreModel.MIN_WINDOW_MILLIS)

    /** The window every trial is compared over: the longest minimum, twice. */
    val windowMillis: Double = 4.0 * maxMinimum

    // ----- candidate runs --------------------------------------------------------------------------

    /** The lengths a run of [task] starting at `cursor.u` is tried for, ascending, none past [untilU]. */
    fun lengthsFor(cursor: ScoreCursor, task: Int, untilU: Double): DoubleArray {
        val u = cursor.u
        val limit = minOf(model.runLimit(task, u), untilU)
        if (limit <= u + ScoreModel.EPS) return DoubleArray(0)
        val m = maxOf(model.minimum[task], ScoreModel.MIN_WINDOW_MILLIS)
        val owed = if (cursor.run == task) model.minimum[task] - cursor.runLen else model.minimum[task]
        val out = ArrayList<Double>(10)
        fun offer(d: Double) {
            if (d > ScoreModel.EPS) out += minOf(d, limit - u)
        }
        if (owed > ScoreModel.EPS) {
            offer(owed)
            offer(owed + 0.5 * m)
            offer(owed + m)
            offer(owed + 2.0 * m)
        } else {
            offer(0.25 * m)
            offer(0.5 * m)
            offer(m)
            offer(2.0 * m)
        }
        var e = u
        repeat(3) {
            e = model.nextEdge(e)
            if (e < limit) offer(e - u)
        }
        offer(limit - u)
        out.sort()
        val dedup = ArrayList<Double>(out.size)
        for (d in out) if (dedup.isEmpty() || d - dedup.last() > SAME_LENGTH_MILLIS) dedup += d
        return dedup.toDoubleArray()
    }

    // ----- the base policy -------------------------------------------------------------------------

    /**
     * The base policy the trials are continued with: the most-behind candidate (lowest estimated lag counted in its
     * own minimum), for what is left of its minimum. Never the run just completed while somebody else may run.
     */
    private fun baseStep(cursor: ScoreCursor, untilU: Double) {
        val u = cursor.u
        val fixed = model.fixedAt(u)
        if (fixed >= 0) {
            model.serve(cursor, fixed, minOf(untilU, model.fixedEnd(u)))
            return
        }
        val candidates = model.candidatesAt(u)
        if (candidates.isEmpty()) {
            model.serve(cursor, -1, minOf(untilU, model.nextEdge(u)))
            return
        }
        var best = -1
        var bestKey = Double.POSITIVE_INFINITY
        for (j in candidates) {
            if (j == cursor.run && candidates.size > 1 && cursor.runLen >= model.minimum[j] - ScoreModel.EPS) continue
            val key = model.estimateLag(cursor, j, u) / maxOf(model.minimum[j], ScoreModel.MIN_WINDOW_MILLIS)
            if (best < 0 || key < bestKey - TIE) {
                best = j
                bestKey = key
            }
        }
        if (best < 0) best = candidates.first()
        val owed = if (cursor.run == best) model.minimum[best] - cursor.runLen else model.minimum[best]
        val d = maxOf(owed, ScoreModel.MIN_WINDOW_MILLIS)
        val stop = minOf(untilU, model.runLimit(best, u), u + d)
        model.serve(cursor, best, if (stop > u) stop else minOf(untilU, model.nextEdge(u)))
    }

    private fun rollout(cursor: ScoreCursor, untilU: Double) {
        var guard = 0
        while (cursor.u < untilU - ScoreModel.EPS && guard++ < MAX_ROLLOUT_STEPS) {
            val before = cursor.u
            baseStep(cursor, untilU)
            if (cursor.u <= before) cursor.u = untilU
        }
    }

    // ----- one decision's frame --------------------------------------------------------------------

    /**
     * Everything a decision at [start] compares against: the common window end, and every task's baseline — its
     * share of the score to the window end if nothing serves it, plus the lower bound past it.
     */
    private inner class Frame(val start: ScoreCursor, val windowEnd: Double) {
        val baseline = DoubleArray(model.n)

        init {
            for (i in 0 until model.n) {
                val c = start.copy()
                model.advance(c, i, windowEnd, served = false)
                baseline[i] = c.cost + model.lowerBound(i, c.lag[i], windowEnd, windowEnd + windowMillis, c.originU)
            }
        }

        /** The value of [trial] (disposable: it is advanced to the window end): its score minus the baselines. */
        fun value(trial: ScoreCursor): Double {
            if (trial.u < windowEnd - ScoreModel.EPS) rollout(trial, windowEnd)
            val touched = BooleanArray(model.n)
            for (i in 0 until model.n) if (trial.lagU[i] > start.u + ScoreModel.EPS) touched[i] = true
            for (i in 0 until model.n) if (touched[i]) model.advance(trial, i, windowEnd, served = false)
            var v = trial.cost
            for (i in 0 until model.n) {
                if (!touched[i]) continue
                v += model.lowerBound(i, trial.lag[i], windowEnd, windowEnd + windowMillis, trial.originU) - baseline[i]
            }
            return v
        }

        /** The best value reachable from [c] trying every next run [depth] levels deep (beam of [BEAM]). */
        fun best(c: ScoreCursor, depth: Int): Double {
            var best = value(c.copy())
            if (depth <= 0 || c.u >= windowEnd - ScoreModel.EPS) return best
            val fixed = model.fixedAt(c.u)
            if (fixed >= 0) {
                val next = c.copy()
                model.serve(next, fixed, minOf(windowEnd, model.fixedEnd(c.u)))
                return minOf(best, best(next, depth))
            }
            val trials = ArrayList<Pair<ScoreCursor, Double>>()
            for (j in model.candidatesAt(c.u)) {
                for (d in lengthsFor(c, j, windowEnd)) {
                    val next = c.copy()
                    model.serve(next, j, c.u + d)
                    val v = value(next.copy())
                    trials += next to v
                    if (better(v, best)) best = v
                }
            }
            if (depth > 1) {
                trials.sortBy { it.second }
                for (k in 0 until minOf(BEAM, trials.size)) best = minOf(best, best(trials[k].first, depth - 1))
            }
            return best
        }
    }

    // ----- the rollout policy ----------------------------------------------------------------------

    /**
     * Every candidate's best first run at one instant: its length and its value (∞ where it may not start), or
     * the pre-placed task the instant is inside ([fixed] ≥ 0, running to [fixedLength]).
     */
    class Evaluation(
        val candidates: List<Int>,
        val lengths: DoubleArray,
        val values: DoubleArray,
        val fixed: Int = -1,
        val fixedLength: Double = 0.0,
    ) {
        /** The best candidate's index, honouring PRD §13's [forced] task and PRD §7's [refused] one; -1 if none. */
        fun choose(forced: Int = -1, refused: Int = -1): Int {
            val forcedHere = forced >= 0 && forced in candidates
            var best = -1
            for ((k, j) in candidates.withIndex()) {
                if (values[k] == Double.POSITIVE_INFINITY) continue
                if (forcedHere && j != forced) continue
                if (j == refused && candidates.size > 1) continue
                if (best < 0 || better(values[k], values[best])) best = k
            }
            return best
        }

        /** § *Alternative Schedules*: the best first task other than [scheduled], or -1. */
        fun alternativeTo(scheduled: Int): Int {
            var best = -1
            for ((k, j) in candidates.withIndex()) {
                if (j == scheduled || values[k] == Double.POSITIVE_INFINITY) continue
                if (best < 0 || better(values[k], values[best])) best = k
            }
            return if (best < 0) -1 else candidates[best]
        }
    }

    /** Evaluate every first run from [at]; null when there is nothing left to decide before [untilU]. */
    fun evaluate(at: ScoreCursor, untilU: Double, depth: Int = lookaheadDepth): Evaluation? {
        val u = at.u
        if (u >= untilU - ScoreModel.EPS) return null
        val fixed = model.fixedAt(u)
        if (fixed >= 0) {
            return Evaluation(emptyList(), DoubleArray(0), DoubleArray(0), fixed, minOf(untilU, model.fixedEnd(u)) - u)
        }
        val candidates = model.candidatesAt(u)
        if (candidates.isEmpty()) return null
        // Every trial is valued from THIS instant: the discount is exponential, so rebasing it multiplies every
        // value by one factor and changes no comparison — while a far origin shrinks them towards the tie tolerance.
        val start = model.rebase(at)
        val frame = Frame(start, minOf(model.uEnd, u + windowMillis).coerceAtLeast(u + ScoreModel.EPS))
        val limitU = minOf(untilU, frame.windowEnd)
        class Trial(val k: Int, val length: Double, val after: ScoreCursor, var value: Double)
        val trials = ArrayList<Trial>()
        for ((k, j) in candidates.withIndex()) {
            for (d in lengthsFor(start, j, limitU)) {
                val next = start.copy()
                model.serve(next, j, u + d)
                trials += Trial(k, d, next, frame.value(next.copy()))
            }
        }
        if (depth > 1) {
            val order = trials.sortedBy { it.value }
            for (t in order.take(BEAM)) t.value = minOf(t.value, frame.best(t.after, depth - 1))
        }
        val lengths = DoubleArray(candidates.size)
        val values = DoubleArray(candidates.size) { Double.POSITIVE_INFINITY }
        for (t in trials) {
            if (better(t.value, values[t.k])) {
                values[t.k] = t.value
                lengths[t.k] = t.length
            }
        }
        return Evaluation(candidates, lengths, values)
    }

    /**
     * The continuation from [start] to [untilU], with the alternative named for every position of the now-line
     * inside each run. Three passes over one score:
     * 1. the rollout policy builds it one decision at a time ([construct]);
     * 2. [ScheduleImprover] lowers the score of the WHOLE continuation, within [improveBudget] scored moves;
     * 3. every run of the result is given its alternative ([annotate]).
     */
    fun plan(
        start: ScoreCursor,
        untilU: Double,
        forcedFirst: Int = -1,
        refusedFirst: Int = -1,
        alternatives: Boolean = true,
    ): Plan {
        val built = construct(start, untilU, forcedFirst, refusedFirst)
        val runs =
            if (improveBudget <= 0 || built.isEmpty()) built
            else ScheduleImprover(model, start, untilU, forcedFirst >= 0 || refusedFirst >= 0, improveBudget).improve(built)
        val named = if (alternatives) annotate(start, runs, untilU) else runs
        return Plan(coalesce(named), score(start, runs), certified = false)
    }

    /** Pass 1: the rollout policy's continuation, one run per decision, pre-placed runs kept apart. */
    private fun construct(start: ScoreCursor, untilU: Double, forcedFirst: Int, refusedFirst: Int): List<Run> {
        val cursor = start.copy()
        val runs = ArrayList<Run>()
        var first = true
        var guard = 0
        while (cursor.u < untilU - ScoreModel.EPS && guard++ < MAX_PLAN_STEPS) {
            val eval = evaluate(cursor, untilU) ?: break
            val from = cursor.u
            if (eval.fixed >= 0) {
                val to = from + eval.fixedLength
                if (to <= from + ScoreModel.EPS) break
                model.serve(cursor, eval.fixed, to)
                runs += Run(eval.fixed, from, to, -1)
                continue
            }
            val k = eval.choose(if (first) forcedFirst else -1, if (first) refusedFirst else -1)
            if (k < 0) break
            first = false
            val to = minOf(untilU, from + eval.lengths[k])
            if (to <= from + ScoreModel.EPS) break
            model.serve(cursor, eval.candidates[k], to)
            runs += Run(eval.candidates[k], from, to, -1)
        }
        return runs
    }

    /**
     * Pass 3: § *Alternative Schedules* for every run of [runs]. The alternative at a run's start is the next-best
     * first run of the decision asked where it starts; the one at its end is read off the decision asked where it
     * stops. Where the two differ, the instant the answer changes is found by bisection, to
     * [ALTERNATIVE_RESOLUTION_MILLIS]. A pre-placed run names none.
     */
    private fun annotate(start: ScoreCursor, runs: List<Run>, untilU: Double): List<Run> {
        val cursor = start.copy()
        val out = ArrayList<Run>(runs.size)
        var pending: PendingRun? = null
        for (r in runs) {
            if (r.fromU > cursor.u + ScoreModel.EPS) model.serve(cursor, -1, r.fromU)
            val eval = evaluate(cursor, untilU)
            pending?.let { out += it.close(if (eval != null && eval.fixed < 0) eval.alternativeTo(it.task) else -1, true) }
            pending = null
            if (eval == null || eval.fixed >= 0) {
                model.serve(cursor, r.task, r.toU)
                out += r.copy(alternative = -1)
                continue
            }
            val before = cursor.copy()
            model.serve(cursor, r.task, r.toU)
            pending = PendingRun(r.task, r.fromU, r.toU, eval.alternativeTo(r.task), before, untilU)
        }
        pending?.let { out += it.close(-1, true, atHorizon = true) }
        return out
    }

    /** A run whose alternative at its end is not known yet. */
    private inner class PendingRun(
        val task: Int,
        val from: Double,
        val to: Double,
        val altAtStart: Int,
        val before: ScoreCursor,
        val untilU: Double,
    ) {
        private fun alternativeAt(v: Double): Int {
            val c = before.copy()
            if (v > from) model.serve(c, task, v)
            // Only the ranking of the OTHER tasks' first runs is asked here, one level deep.
            val eval = evaluate(c, untilU, depth = 1) ?: return -1
            return if (eval.fixed >= 0) -1 else eval.alternativeTo(task)
        }

        fun close(altAtEnd: Int, alternatives: Boolean, atHorizon: Boolean = false): List<Run> {
            if (!alternatives) return listOf(Run(task, from, to, -1))
            // At the horizon nothing follows to ask; the start's answer stands for the rest of the run.
            val end = if (atHorizon) altAtStart else altAtEnd
            if (end == altAtStart || to - from <= ALTERNATIVE_RESOLUTION_MILLIS) return listOf(Run(task, from, to, altAtStart))
            // The probes are one level deep, so the instant is found where THEIR answer leaves the one they give at
            // the start; the two ends keep the full decisions' answers.
            val probeAtStart = alternativeAt(from)
            var lo = from
            var hi = to
            var guard = 0
            while (hi - lo > ALTERNATIVE_RESOLUTION_MILLIS && guard++ < 40) {
                val mid = (lo + hi) / 2.0
                if (alternativeAt(mid) == probeAtStart) lo = mid else hi = mid
            }
            return listOf(Run(task, from, hi, altAtStart), Run(task, hi, to, end))
        }
    }

    // ----- the exhaustive search -------------------------------------------------------------------

    /**
     * Every sequence of candidate runs from [start] to [untilU], pruned by the lower bound, started from the rollout
     * policy's continuation. [Plan.certified] is true when the search finished inside [searchBudget]: nothing over the
     * candidate lengths scores better than what it returns.
     */
    fun certify(start: ScoreCursor, untilU: Double, forcedFirst: Int = -1, refusedFirst: Int = -1): Plan {
        val incumbent = plan(start, untilU, forcedFirst, refusedFirst, alternatives = false)
        var bestCost = incumbent.cost
        var bestRuns: List<Run> = incumbent.runs
        var steps = 0
        var exhausted = false
        val path = ArrayList<Run>()

        fun dfs(c: ScoreCursor, first: Boolean) {
            if (exhausted) return
            if (c.u >= untilU - ScoreModel.EPS) {
                val done = c.copy()
                model.settle(done)
                if (better(done.cost, bestCost)) {
                    bestCost = done.cost
                    bestRuns = coalesce(path.toList())
                }
                return
            }
            if (++steps > searchBudget) {
                exhausted = true
                return
            }
            val settled = c.copy()
            model.settle(settled)
            if (settled.cost + model.lowerBound(settled, untilU) >= bestCost * (1.0 - TIE)) return
            val fixed = model.fixedAt(c.u)
            val options = if (fixed >= 0) listOf(fixed) else model.candidatesAt(c.u)
            for (j in options) {
                if (first && forcedFirst >= 0 && j != forcedFirst && fixed < 0) continue
                if (first && refusedFirst >= 0 && j == refusedFirst && options.size > 1) continue
                val lengths = if (fixed >= 0) doubleArrayOf(minOf(untilU, model.fixedEnd(c.u)) - c.u)
                else lengthsFor(c, j, untilU)
                for (d in lengths) {
                    val next = c.copy()
                    val from = c.u
                    model.serve(next, j, from + d)
                    path += Run(j, from, from + d, -1)
                    dfs(next, false)
                    path.removeAt(path.size - 1)
                    if (exhausted) return
                }
            }
        }
        dfs(start.copy(), true)
        return Plan(bestRuns, bestCost, certified = !exhausted)
    }

    /** Re-score a sequence of runs from [start], every lag integrated to the end of the last run. */
    fun score(start: ScoreCursor, runs: List<Run>): Double {
        val c = start.copy()
        for (r in runs) model.serve(c, r.task, r.toU)
        model.settle(c)
        return c.cost
    }

    companion object {
        const val DEFAULT_SEARCH_BUDGET: Int = 20_000
        const val DEFAULT_LOOKAHEAD_DEPTH: Int = 2
        const val DEFAULT_IMPROVE_BUDGET: Int = 5_000
        /** How many of the most promising trials are looked into one run deeper. */
        const val BEAM: Int = 4
        const val TIE: Double = 1e-9
        const val SAME_LENGTH_MILLIS: Double = 1_000.0
        const val ALTERNATIVE_RESOLUTION_MILLIS: Double = 60_000.0
        private const val MAX_ROLLOUT_STEPS = 10_000
        private const val MAX_PLAN_STEPS = 100_000

        /** Strictly better beyond the tie tolerance (`docs/scheduler_score.md` § *Ties*). */
        fun better(a: Double, b: Double): Boolean {
            if (b == Double.POSITIVE_INFINITY) return a < b
            return a < b - TIE * maxOf(abs(a), abs(b))
        }

        /** Join consecutive runs of one task whose alternative did not change. */
        fun coalesce(runs: List<Run>): List<Run> {
            val out = ArrayList<Run>(runs.size)
            for (r in runs) {
                if (r.toU <= r.fromU + ScoreModel.EPS) continue
                val last = out.lastOrNull()
                if (last != null && last.task == r.task && last.alternative == r.alternative &&
                    abs(last.toU - r.fromU) <= ScoreModel.EPS
                ) {
                    out[out.size - 1] = last.copy(toU = r.toU)
                } else {
                    out += r
                }
            }
            return out
        }
    }
}
