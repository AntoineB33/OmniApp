package org.example.project.scheduler.domain

import kotlin.math.abs

/**
 * `docs/scheduler_score.md` — **the search for the best continuation.**
 *
 * The score ([ScoreModel]) says what "best" means; this class finds it, or gets as close as the budget allows —
 * the one degradation the requirements accept.
 *
 * ### The passes, one score ([plan])
 * 1. **The rollout policy** builds the continuation one decision at a time ([evaluate]). It tries every candidate
 *    task with every candidate length, looks [lookaheadDepth] runs deep over the most promising of them, continues
 *    every trial with a simple base policy to a common window, and keeps the trial whose score is lowest. Comparing
 *    every trial over the SAME window is what keeps a short run from looking cheaper merely because it covers less
 *    time; the window is closed with each lag's [ScoreModel.lowerBound].
 * 2. **The seeds compete.** Every continuation handed in as a seed — the plan this one replaces, another device's
 *    plan for the same rules — is checked against every hard constraint, completed by the rollout policy where it
 *    stops short, and scored; the lowest score of all of them and of pass 1 goes on. A re-plan therefore never
 *    returns a continuation worse than one it was shown.
 * 3. **The whole-continuation improvement** ([ScheduleImprover]) then lowers the score of the continuation as a
 *    whole, within [improveBudget] scored moves. A decision that is best for its own window can still cost more
 *    over the continuation, and only this pass judges by the number the definition minimizes.
 * 4. **While the [SearchBudget] lasts** (`docs/scheduler_requirements.md` § *Strict requirements*: the best score
 *    must be reached when it is reachable in the time): the exhaustive search ([certify]) started from the best
 *    continuation so far — when it finishes, nothing over the candidate lengths scores better and [Plan.certified]
 *    says so — and, when it does not, the platform's [ExternalScheduleSolver] with the time that is left.
 * 5. **The alternatives** are named for every run of the result.
 *
 * Passes 1–3 are bounded in steps; pass 4 is bounded in wall time. Nothing requires two devices to reach the same
 * rules (user rule, 2026-09-17): when they differ, the score decides between them.
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
    /** Steps the exhaustive search ([certify]) may take when it is given no wall-time [SearchBudget]. */
    val searchBudget: Int = DEFAULT_SEARCH_BUDGET,
    /** How many runs deep the rollout policy looks before handing over to the base policy. */
    val lookaheadDepth: Int = DEFAULT_LOOKAHEAD_DEPTH,
    /** Moves [ScheduleImprover] may score on the whole continuation; 0 keeps the rollout policy's plan. */
    val improveBudget: Int = DEFAULT_IMPROVE_BUDGET,
    /** The platform's own solver, asked while the [SearchBudget] lasts (null: none). */
    val solver: ExternalScheduleSolver? = null,
) {
    /** One run of the continuation on the schedulable clock, with the alternative in force from [fromU]. */
    data class Run(val task: Int, val fromU: Double, val toU: Double, val alternative: Int)

    class Plan(
        val runs: List<Run>,
        val cost: Double,
        val certified: Boolean,
        val report: SearchReport = SearchReport(certified = certified),
    )

    private val maxMinimum: Double = (model.minimum.maxOrNull() ?: 0.0).coerceAtLeast(ScoreModel.MIN_WINDOW_MILLIS)

    /** The window every trial is compared over: the longest minimum, four times. */
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
        /**
         * The best candidate's index, honouring PRD §13's [forced] task, PRD §7's [refused] one and the instant
         * restriction at the line ([among], see [firstOptions]); -1 if none.
         */
        fun choose(forced: Int = -1, refused: Int = -1, among: Set<Int> = emptySet()): Int {
            val allowed = firstOptions(candidates, forced, refused, among)
            var best = -1
            for ((k, j) in candidates.withIndex()) {
                if (values[k] == Double.POSITIVE_INFINITY) continue
                if (j !in allowed) continue
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
     * inside each run — the passes of the class comment, in order.
     *
     * [seeds] are continuations to compete with the rollout policy's (pass 2), each on this model's schedulable
     * clock from [start]; [budget] is the wall time passes 4 may spend.
     */
    fun plan(
        start: ScoreCursor,
        untilU: Double,
        forcedFirst: Int = -1,
        refusedFirst: Int = -1,
        alternatives: Boolean = true,
        seeds: List<List<Run>> = emptyList(),
        budget: SearchBudget = SearchBudget.NONE,
        firstAmong: Set<Int> = emptySet(),
    ): Plan {
        val pinFirst = forcedFirst >= 0 || refusedFirst >= 0 || firstAmong.isNotEmpty()
        val built = construct(start, untilU, forcedFirst, refusedFirst, firstAmong)
        var runs = built
        var cost = score(start, runs)
        for (seed in seeds) {
            val completed = complete(start, seed, untilU, forcedFirst, refusedFirst, firstAmong, built) ?: continue
            val c = score(start, completed)
            if (better(c, cost)) {
                runs = completed
                cost = c
            }
        }
        runs = improve(start, runs, untilU, pinFirst)
        cost = score(start, runs)

        var certified = false
        var solverImproved = false
        val extraGranted = !budget.expired()
        val searchStart = kotlin.time.TimeSource.Monotonic.markNow()
        if (extraGranted && runs.isNotEmpty()) {
            // The exhaustive search first: where it finishes, nothing over the candidate lengths is better and the
            // solver has nothing left to find. It keeps a share of the time back for the solver when it does not.
            val external = solver
            val exactBudget = if (external == null) budget else budget.share(budget.remainingMillis() * EXACT_SHARE_PERCENT / 100)
            val exact = certify(start, untilU, forcedFirst, refusedFirst, incumbent = runs, budget = exactBudget, firstAmong = firstAmong)
            if (better(exact.cost, cost)) {
                runs = exact.runs
                cost = exact.cost
            }
            certified = exact.certified
            if (!certified && external != null && !budget.expired()) {
                val proposed = runCatching { external.improve(model, start, untilU, runs, pinFirst, budget) }.getOrNull()
                val checked = proposed?.let { accepted(start, it, untilU, forcedFirst, refusedFirst, firstAmong, runs.firstOrNull()) }
                if (checked != null) {
                    val polished = improve(start, checked, untilU, pinFirst)
                    val c = score(start, polished)
                    if (better(c, cost)) {
                        runs = polished
                        cost = c
                        solverImproved = true
                    }
                }
            }
        }
        val named = if (alternatives) annotate(start, runs, untilU) else runs
        val report = SearchReport(
            certified = certified,
            solverImproved = solverImproved,
            exhausted = extraGranted && !certified,
            searchMillis = if (extraGranted) searchStart.elapsedNow().inWholeMilliseconds else 0L,
        )
        return Plan(coalesce(named), cost, certified, report)
    }

    private fun improve(start: ScoreCursor, runs: List<Run>, untilU: Double, pinFirst: Boolean): List<Run> =
        if (improveBudget <= 0 || runs.isEmpty()) runs
        else ScheduleImprover(model, start, untilU, pinFirst, improveBudget).improve(runs)

    /** Pass 1: the rollout policy's continuation, one run per decision, pre-placed runs kept apart. */
    private fun construct(
        start: ScoreCursor,
        untilU: Double,
        forcedFirst: Int,
        refusedFirst: Int,
        firstAmong: Set<Int>,
        prefix: List<Run> = emptyList(),
        firstDecided: Boolean = false,
    ): List<Run> {
        val cursor = start.copy()
        val runs = ArrayList<Run>(prefix)
        for (r in prefix) model.serve(cursor, r.task, r.toU)
        var first = !firstDecided
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
            val k = if (first) eval.choose(forcedFirst, refusedFirst, firstAmong) else eval.choose()
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
     * The longest prefix of [runs] that is a legal continuation from [start] — every run starts where the previous
     * one ended, is a task that may run over all of it, never enters another task's pre-placed block, and the first
     * free run honours §13's [forcedFirst], §7's [refusedFirst] and the instant restriction at the line
     * ([firstAmong]) — with the pre-placed blocks the environment holds laid in between. Null when the first free
     * run breaks one of them (then the whole continuation is unusable).
     * The second value says whether a free run has been decided.
     */
    private fun legalPrefix(
        start: ScoreCursor,
        runs: List<Run>,
        untilU: Double,
        forcedFirst: Int,
        refusedFirst: Int,
        firstAmong: Set<Int> = emptySet(),
    ): Pair<List<Run>, Boolean>? {
        val out = ArrayList<Run>()
        var u = start.u
        val sorted = runs.filter { it.task >= 0 && it.toU > u + ScoreModel.EPS }.sortedBy { it.fromU }
        var idx = 0
        var decided = false
        var guard = 0
        while (u < untilU - ScoreModel.EPS && guard++ < MAX_PLAN_STEPS) {
            val fixed = model.fixedAt(u)
            if (fixed >= 0) {
                val e = minOf(untilU, model.fixedEnd(u))
                if (e <= u + ScoreModel.EPS) break
                out += Run(fixed, u, e, -1)
                u = e
                continue
            }
            while (idx < sorted.size && sorted[idx].toU <= u + ScoreModel.EPS) idx++
            val r = sorted.getOrNull(idx) ?: break
            if (r.fromU > u + ScoreModel.EPS) break
            val task = r.task
            if (!model.permitted(task, u)) break
            if (!decided && task !in firstOptions(model.candidatesAt(u), forcedFirst, refusedFirst, firstAmong)) return null
            val e = minOf(r.toU, untilU, model.runLimit(task, u), model.nextFixedStart(u))
            if (e <= u + ScoreModel.EPS) break
            out += Run(task, u, e, -1)
            decided = true
            u = e
        }
        return out to decided
    }

    /**
     * Whether [runs], exactly as given, are a legal continuation from [start] all the way to [untilU]: no stretch left
     * to nobody where somebody may run (§ *No idling*), no task where it may not run, no other task's pre-placed block
     * entered. What a follower checks another device's runs against before laying them on its own timeline.
     */
    fun isLegalContinuation(start: ScoreCursor, runs: List<Run>, untilU: Double, firstAmong: Set<Int> = emptySet()): Boolean {
        if (untilU <= start.u + ScoreModel.EPS) return true
        val (prefix, _) = legalPrefix(start, runs, untilU, -1, -1, firstAmong) ?: return false
        return prefix.isNotEmpty() && prefix.last().toU >= untilU - ScoreModel.EPS
    }

    /**
     * Pass 2: [seed] cut to its legal prefix and completed by what pass 1 built from the same instant on ([built],
     * cut where the prefix ends — a run cut short is still a task that may run over what is left of it); null when
     * it is unusable. Completing with a second rollout doubled what every re-plan costs, for a tail the improver
     * re-shapes anyway.
     */
    private fun complete(
        start: ScoreCursor,
        seed: List<Run>,
        untilU: Double,
        forcedFirst: Int,
        refusedFirst: Int,
        firstAmong: Set<Int>,
        built: List<Run>,
    ): List<Run>? {
        val (prefix, decided) = legalPrefix(start, seed, untilU, forcedFirst, refusedFirst, firstAmong) ?: return null
        if (prefix.isEmpty()) return null
        val end = prefix.last().toU
        if (end >= untilU - ScoreModel.EPS) return prefix
        val tail = built.mapNotNull { r ->
            if (r.toU <= end + ScoreModel.EPS) null else r.copy(fromU = maxOf(r.fromU, end))
        }
        // The tail must start where the prefix ends; a pre-placed run the prefix stopped inside is laid again whole.
        if (tail.isEmpty() || abs(tail.first().fromU - end) > ScoreModel.EPS) {
            return construct(start, untilU, forcedFirst, refusedFirst, firstAmong, prefix = prefix, firstDecided = decided)
        }
        return prefix + tail
    }

    /**
     * An external solver's continuation, kept only when ALL of it is legal and it reaches [untilU] (a solver answer is
     * never completed or cut: it is either a whole continuation or nothing), and — when §7/§13 or the instant
     * restriction at the line decided the first run ([pinned]) — it keeps that run's task.
     */
    private fun accepted(
        start: ScoreCursor,
        proposed: List<Run>,
        untilU: Double,
        forcedFirst: Int,
        refusedFirst: Int,
        firstAmong: Set<Int>,
        pinned: Run?,
    ): List<Run>? {
        val (prefix, _) = legalPrefix(start, proposed, untilU, forcedFirst, refusedFirst, firstAmong) ?: return null
        if (prefix.isEmpty() || prefix.last().toU < untilU - ScoreModel.EPS) return null
        if ((forcedFirst >= 0 || refusedFirst >= 0 || firstAmong.isNotEmpty()) && pinned != null) {
            val firstFree = prefix.firstOrNull { model.fixedAt(it.fromU) < 0 } ?: return null
            val pinnedFree = if (model.fixedAt(pinned.fromU) < 0) pinned.task else -1
            if (pinnedFree >= 0 && firstFree.task != pinnedFree) return null
        }
        return prefix
    }

    /**
     * Pass 5: § *Alternative Schedules* for every run of [runs]. The alternative at a run's start is the next-best
     * first run of the decision asked where it starts; the one at its end is read off the decision asked where it
     * stops. Where the two differ, the instant the answer changes is found by bisection to
     * [ALTERNATIVE_RESOLUTION_MILLIS]; the probes look one run deep (as deep as the decisions, a week's fill cost
     * nearly twice as much), while the answers at the run's two ends are the full decisions'. A pre-placed run names
     * none.
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
            val eval = evaluate(c, untilU, depth = 1) ?: return -1
            return if (eval.fixed >= 0) -1 else eval.alternativeTo(task)
        }

        fun close(altAtEnd: Int, alternatives: Boolean, atHorizon: Boolean = false): List<Run> {
            if (!alternatives) return listOf(Run(task, from, to, -1))
            // At the end of the search nothing follows to ask; the probe at the run's last resolvable instant answers.
            val end = if (atHorizon) alternativeAt(maxOf(from, to - ALTERNATIVE_RESOLUTION_MILLIS)) else altAtEnd
            if (end == altAtStart || to - from <= ALTERNATIVE_RESOLUTION_MILLIS) return listOf(Run(task, from, to, altAtStart))
            var lo = from
            var hi = to
            var guard = 0
            while (hi - lo > ALTERNATIVE_RESOLUTION_MILLIS && guard++ < 60) {
                val mid = (lo + hi) / 2.0
                if (alternativeAt(mid) == altAtStart) lo = mid else hi = mid
            }
            return listOf(Run(task, from, hi, altAtStart), Run(task, hi, to, end))
        }
    }

    // ----- the exhaustive search -------------------------------------------------------------------

    /**
     * Every sequence of candidate runs from [start] to [untilU], pruned by the lower bound, started from [incumbent]
     * (the rollout policy's plan when none is given). [Plan.certified] is true when the search finished: nothing over
     * the candidate lengths scores better than what it returns. It stops at [budget] when one is given, else after
     * [searchBudget] steps.
     */
    fun certify(
        start: ScoreCursor,
        untilU: Double,
        forcedFirst: Int = -1,
        refusedFirst: Int = -1,
        incumbent: List<Run>? = null,
        budget: SearchBudget = SearchBudget.NONE,
        firstAmong: Set<Int> = emptySet(),
    ): Plan {
        val initial = incumbent ?: plan(start, untilU, forcedFirst, refusedFirst, alternatives = false, firstAmong = firstAmong).runs
        var bestCost = score(start, initial)
        var bestRuns: List<Run> = initial
        val timed = !budget.expired()
        var steps = 0L
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
            steps++
            if (if (timed) steps % CLOCK_CHECK_STEPS == 0L && budget.expired() else steps > searchBudget) {
                exhausted = true
                return
            }
            val settled = c.copy()
            model.settle(settled)
            if (settled.cost + model.lowerBound(settled, untilU) >= bestCost * (1.0 - TIE)) return
            val fixed = model.fixedAt(c.u)
            val options = if (fixed >= 0) listOf(fixed) else model.candidatesAt(c.u)
            val allowed = if (first && fixed < 0) firstOptions(options, forcedFirst, refusedFirst, firstAmong) else options
            for (j in options) {
                if (j !in allowed) continue
                val lengths = if (fixed >= 0) doubleArrayOf(minOf(untilU, model.fixedEnd(c.u)) - c.u)
                else lengthsFor(c, j, untilU)
                for (d in lengths) {
                    val next = c.copy()
                    val from = c.u
                    model.serve(next, j, from + d)
                    path += Run(j, from, from + d, -1)
                    // A pre-placed run decides nothing: the first FREE run is still to come.
                    dfs(next, first && fixed >= 0)
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

        /**
         * The tasks the first FREE run may be, out of the [candidates] the instant offers: PRD §13's [forced] task
         * alone when it may run; else, when anybody in [among] may run, only them — the instant restriction at the
         * line (`ScheduleFill.firstAmong`: modes 2 & 3 cover the line itself by "no on-screen task"); and never
         * PRD §7's [refused] task while somebody else may.
         */
        fun firstOptions(candidates: List<Int>, forced: Int, refused: Int, among: Set<Int>): List<Int> {
            if (forced >= 0 && forced in candidates) return listOf(forced)
            val restricted =
                if (among.isEmpty()) candidates else candidates.filter { it in among }.ifEmpty { candidates }
            return if (refused >= 0 && restricted.size > 1) restricted.filter { it != refused } else restricted
        }
        const val DEFAULT_LOOKAHEAD_DEPTH: Int = 2
        const val DEFAULT_IMPROVE_BUDGET: Int = 5_000
        /** How many of the most promising trials are looked into one run deeper. */
        const val BEAM: Int = 4
        const val TIE: Double = 1e-9
        const val SAME_LENGTH_MILLIS: Double = 1_000.0
        /** How finely the instant an alternative changes inside a run is located. */
        const val ALTERNATIVE_RESOLUTION_MILLIS: Double = 1_000.0
        private const val MAX_ROLLOUT_STEPS = 10_000
        private const val MAX_PLAN_STEPS = 100_000
        private const val CLOCK_CHECK_STEPS = 64L
        /** The share of the extra time the exhaustive search may use when a platform solver waits behind it. */
        private const val EXACT_SHARE_PERCENT = 60L

        /**
         * How far past the instant it materializes a fill searches (`docs/invariants/scheduler.md` § *Progressive
         * Calculation*): one decision window, so the runs published at a stage's end are decided with the same view
         * ahead as any other — never bent by where that stage happened to stop.
         */
        fun searchMarginMillis(tasks: List<PlanTask>): Long =
            (4L * maxOf(tasks.maxOfOrNull { it.minimumMillis } ?: 0L, ScoreModel.MIN_WINDOW_MILLIS.toLong()))

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
