package org.example.project.scheduler.domain

import com.google.ortools.linearsolver.MPSolver
import com.google.ortools.linearsolver.MPVariable
import kotlin.math.abs
import kotlin.math.exp

/**
 * `docs/invariants/scheduler.md` § *The best score*: **a mixed-integer program over short windows of the
 * continuation** — large-neighbourhood search with SCIP as the neighbourhood solver.
 *
 * A whole continuation (a week, sixty tasks, minute slots) is far too large a MIP to solve in the seconds a stage is
 * given, so the program is asked about one WINDOW at a time, with everything outside it held as the incumbent has it:
 *
 * - **Variables.** The window is cut into slots at a regular grid, every environment edge and every boundary of the
 *   incumbent (so the incumbent itself is a feasible answer, and is handed to SCIP as its hint). A binary `x[i,k]`
 *   says task `i` holds slot `k`; the lag `L[i,k]` follows the score's own dynamics exactly
 *   (`L' = aL + (1−a)τ(x − f)`, `a = e^(−h/τ)`, the target `f` read at the slot's middle).
 * - **Criterion 1** over a slot is the exact discounted integral of the squared lag, a convex quadratic in `(L, x)`.
 *   Because `x` is binary it splits into a LINEAR term in `x` and `C·z²` with `z = L + (B/C)(x − f)`; `z²` is bounded
 *   below by tangent cuts. The lag the window leaves behind is charged EXACTLY: the rest of the continuation is held
 *   fixed, so its cost is a quadratic in that lag, fitted from three evaluations of [ScoreModel].
 * - **Criterion 2** is set partitioning: a binary `y[i,p,q]` for every run of task `i` over slots `p..q` it may hold,
 *   costing EXACTLY its panel's shortfall `τ(2Ms + s²)` discounted at the panel's end — with the panel running in at
 *   the window's start and the one running on after its end joined across the boundary. Two runs of one task back to
 *   back are forbidden (they would be one panel), and `x[i,k]` is the sum of the runs covering slot `k`. (A run-length
 *   variable with big-M constraints was tried first: its relaxation was so loose that SCIP proved nothing within a
 *   window's time limit.)
 * - **Hard constraints**: exactly one task per slot (§ *No idling*), `x = 0` where a task may not run (resilience 0),
 *   `x = 1` for the owner of a pre-placed slot.
 *
 * Everything inside the program is in MINUTES (the score is in millis): a cost of millis³ puts coefficients near
 * `1e18` next to feasibility tolerances near `1e-6`, which no MIP solver resolves.
 *
 * The program is an approximation of the score (the tangent cuts, the target at the slot's middle), so it is never
 * trusted: every window's answer is re-scored over the WHOLE continuation by [ScheduleOptimizer.score] and kept only
 * when that goes down — and the optimizer re-checks the final continuation against every hard constraint.
 */
internal class MipScheduleSolver : ExternalScheduleSolver {
    override val name: String = "OR-Tools $BACKEND neighbourhood MIP"

    /** Windows asked, and windows whose answer lowered the score — for the benchmark and diagnostics. */
    var windowsAsked: Int = 0
        private set
    var windowsKept: Int = 0
        private set

    override fun improve(
        model: ScoreModel,
        start: ScoreCursor,
        untilU: Double,
        incumbent: List<ScheduleOptimizer.Run>,
        pinFirstRun: Boolean,
        budget: SearchBudget,
    ): List<ScheduleOptimizer.Run>? {
        if (!OrTools.available || incumbent.isEmpty() || model.n < 2) return null
        val scorer = ScheduleOptimizer(model, improveBudget = 0)
        var runs = ScheduleOptimizer.coalesce(incumbent.map { it.copy(alternative = -1) })
        var cost = scorer.score(start, runs)
        val maxMinimum = (model.minimum.maxOrNull() ?: 0.0).coerceAtLeast(ScoreModel.MIN_WINDOW_MILLIS)
        val span = (WINDOW_MINIMUMS * maxMinimum).coerceIn(MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS)
        val floor =
            if (!pinFirstRun) start.u
            else runs.firstOrNull { model.fixedAt(it.fromU) < 0 }?.toU ?: start.u
        var improved = false
        var sweep = 0
        while (!budget.expired() && sweep++ < MAX_SWEEPS) {
            var changed = false
            var a = floor
            while (a < untilU - ScoreModel.EPS && !budget.expired()) {
                val b = minOf(untilU, a + span)
                if (b - a > ScoreModel.MIN_WINDOW_MILLIS) {
                    windowsAsked++
                    val proposal = runCatching { solveWindow(model, start, untilU, runs, a, b, budget) }.getOrNull()
                    if (proposal != null) {
                        val c = scorer.score(start, proposal)
                        if (ScheduleOptimizer.better(c, cost)) {
                            runs = proposal
                            cost = c
                            changed = true
                            improved = true
                            windowsKept++
                        }
                    }
                }
                a += span / 2.0
            }
            if (!changed) break
        }
        return if (improved) runs else null
    }

    private class Slot(val from: Double, val to: Double, val fixed: Int, val allowed: BooleanArray, val target: DoubleArray)

    /**
     * The continuation the program finds with everything outside `[a, b]` as [runs] has it, or null (nothing
     * different found, too many tasks for one window, the solver gave up).
     */
    private fun solveWindow(
        model: ScoreModel,
        start: ScoreCursor,
        untilU: Double,
        runs: List<ScheduleOptimizer.Run>,
        a: Double,
        b: Double,
        budget: SearchBudget,
    ): List<ScheduleOptimizer.Run>? {
        // The runs cut at the window's edges: a run crossing one continues as one panel through the joins below.
        fun clip(r: ScheduleOptimizer.Run, lo: Double, hi: Double): ScheduleOptimizer.Run? =
            if (r.toU <= lo + ScoreModel.EPS || r.fromU >= hi - ScoreModel.EPS) null
            else r.copy(fromU = maxOf(r.fromU, lo), toU = minOf(r.toU, hi))
        val before = runs.mapNotNull { clip(it, Double.NEGATIVE_INFINITY, a) }
        val inside = runs.mapNotNull { clip(it, a, b) }
        val after = runs.mapNotNull { clip(it, b, Double.POSITIVE_INFINITY) }
        if (inside.isEmpty()) return null

        // --- the state at the window's start
        val atA = start.copy()
        for (r in before) model.serve(atA, r.task, r.toU)
        if (a > atA.u + ScoreModel.EPS) model.serve(atA, -1, a)
        val runIn = atA.run
        val runInLen = atA.runLen
        model.settle(atA)

        // --- the slots (on the schedulable clock, millis)
        val edges = sortedSetOf(a, b)
        var g = a + SLOT_MILLIS
        while (g < b - ScoreModel.EPS) {
            edges += g
            g += SLOT_MILLIS
        }
        var e = a
        while (true) {
            val next = model.nextEdge(e)
            if (next <= e + ScoreModel.EPS || next >= b - ScoreModel.EPS) break
            edges += next
            e = next
        }
        for (r in inside) {
            edges += r.fromU
            edges += r.toU
        }
        val cuts = ArrayList<Double>()
        for (v in edges) if (cuts.isEmpty() || v - cuts.last() >= MIN_SLOT_MILLIS) cuts += v
        if (b - cuts.last() > ScoreModel.EPS) cuts[cuts.size - 1] = b
        if (cuts.size < 2 || cuts.size - 1 > MAX_SLOTS) return null
        val K = cuts.size - 1
        val slots = (0 until K).map { k ->
            val mid = (cuts[k] + cuts[k + 1]) / 2.0
            val fixed = model.fixedAt(mid)
            val allowed = BooleanArray(model.n) { i -> if (fixed >= 0) i == fixed else model.permitted(i, mid) }
            Slot(cuts[k], cuts[k + 1], fixed, allowed, model.targetAt(mid))
        }
        val incumbentTask = IntArray(K) { k ->
            val mid = (slots[k].from + slots[k].to) / 2.0
            inside.firstOrNull { mid >= it.fromU && mid < it.toU }?.task ?: -1
        }
        if (incumbentTask.any { it < 0 }) return null

        // --- who may change: every task served in the window, the run coming in, the run going on, the most behind
        val chosen = LinkedHashSet<Int>()
        for (r in inside) chosen += r.task
        if (runIn >= 0) chosen += runIn
        after.firstOrNull()?.let { chosen += it.task }
        if (chosen.size > MAX_TASKS) return null
        val behind = (0 until model.n)
            .filter { it !in chosen && slots.any { s -> s.allowed[it] } }
            .sortedBy { atA.lag[it] / maxOf(model.minimum[it], ScoreModel.MIN_WINDOW_MILLIS) }
        for (i in behind) {
            if (chosen.size >= MAX_TASKS) break
            chosen += i
        }
        val tasks = chosen.toIntArray()

        // --- the tail after the window: the panel it opens (consecutive runs of one task) and where that panel ends
        val tailPanel = after.firstOrNull()
        var tailLen = 0.0
        var tailEnd = b
        if (tailPanel != null) {
            for (r in after) {
                if (r.task != tailPanel.task || abs(r.fromU - tailEnd) > ScoreModel.EPS) break
                tailLen += r.toU - r.fromU
                tailEnd = r.toU
            }
        }
        val tailCharged = tailPanel != null && tailEnd < untilU - ScoreModel.EPS

        val solver = MPSolver.createSolver(BACKEND) ?: return null
        try {
            val inf = MPSolver.infinity()
            val objective = solver.objective()
            objective.setMinimization()
            fun addObj(v: MPVariable, c: Double) = objective.setCoefficient(v, objective.getCoefficient(v) + c)
            var offset = 0.0
            val theta = model.theta
            val origin = start.originU
            val cutMin = DoubleArray(cuts.size) { cuts[it] / UNIT }

            // --- the runs: y[t][p][q] = task t holds exactly slots p..q, as one panel (set partitioning)
            val y = Array(tasks.size) { arrayOfNulls<Array<MPVariable?>>(K) }
            for ((t, i) in tasks.withIndex()) {
                val m = model.minimum[i] / UNIT
                val tau = model.tau[i] / UNIT
                for (p in 0 until K) {
                    if (!slots[p].allowed[i]) continue
                    val row = arrayOfNulls<MPVariable>(K)
                    y[t][p] = row
                    for (q in p until K) {
                        if (!slots[q].allowed[i]) break
                        val v = solver.makeBoolVar("y_${t}_${p}_$q")
                        row[q] = v
                        val joinIn = p == 0 && runIn == i
                        val joinOut = q == K - 1 && tailPanel?.task == i
                        val length = cutMin[q + 1] - cutMin[p] + (if (joinIn) runInLen / UNIT else 0.0) + (if (joinOut) tailLen / UNIT else 0.0)
                        val charged = when {
                            q < K - 1 -> true
                            tailPanel == null -> false // open at the end of the continuation
                            joinOut -> tailCharged
                            else -> true
                        }
                        val short = m - length
                        if (charged && short > 0.0) {
                            val endU = if (joinOut) tailEnd else cuts[q + 1]
                            addObj(v, exp(-(endU - origin) / theta) * tau * short * (2.0 * m + short))
                        }
                    }
                }
            }
            // x[t][k]: task t holds slot k — the sum of the runs covering it.
            val x = Array(tasks.size) { t -> Array(K) { k -> solver.makeNumVar(0.0, 1.0, "x_${t}_$k") } }
            for (t in tasks.indices) for (k in 0 until K) {
                val link = solver.makeConstraint(0.0, 0.0)
                link.setCoefficient(x[t][k], 1.0)
                for (p in 0..k) {
                    val row = y[t][p] ?: continue
                    for (q in k until K) row[q]?.let { link.setCoefficient(it, -1.0) }
                }
            }
            // § No idling: exactly one task per slot. A pre-placed slot admits its owner alone (allowed).
            for (k in 0 until K) {
                val one = solver.makeConstraint(1.0, 1.0)
                for (t in tasks.indices) one.setCoefficient(x[t][k], 1.0)
            }
            // Two runs of one task back to back are ONE panel: never two variables for it.
            for (t in tasks.indices) for (k in 0 until K - 1) {
                val row = solver.makeConstraint(-inf, 1.0)
                for (p in 0..k) y[t][p]?.get(k)?.let { row.setCoefficient(it, 1.0) }
                y[t][k + 1]?.let { next -> for (q in k + 1 until K) next[q]?.let { row.setCoefficient(it, 1.0) } }
            }
            // The panel coming in is cut at the window's start unless a run of its task continues it.
            if (runIn >= 0 && runIn in tasks && model.minimum[runIn] > runInLen + ScoreModel.EPS) {
                val t = tasks.indexOf(runIn)
                val m = model.minimum[runIn] / UNIT
                val short = m - runInLen / UNIT
                val c = exp(-(a - origin) / theta) * (model.tau[runIn] / UNIT) * short * (2.0 * m + short)
                offset += c
                y[t][0]?.forEach { v -> v?.let { addObj(it, -c) } }
            }
            // The tail's panel is short on its own unless a run of its task runs into it.
            if (tailPanel != null && tailCharged && model.minimum[tailPanel.task] > tailLen + ScoreModel.EPS) {
                val j = tailPanel.task
                val t = tasks.indexOf(j)
                val m = model.minimum[j] / UNIT
                val short = m - tailLen / UNIT
                val c = exp(-(tailEnd - origin) / theta) * (model.tau[j] / UNIT) * short * (2.0 * m + short)
                offset += c
                for (p in 0 until K) y[t][p]?.get(K - 1)?.let { addObj(it, -c) }
            }

            // --- criterion 1: the lag dynamics, exact per slot, and its square bounded by tangents
            for ((t, i) in tasks.withIndex()) {
                val tau = model.tau[i] / UNIT
                val thetaMin = theta / UNIT
                var lagVar: MPVariable? = null
                var lagConst = atA.lag[i] / UNIT
                var incLag = lagConst
                val spread = maxOf(abs(incLag), tau) + (b - a) / UNIT
                for (k in 0 until K) {
                    val s = slots[k]
                    val h = cutMin[k + 1] - cutMin[k]
                    val f = s.target[i]
                    val xInc = if (incumbentTask[k] == i) 1.0 else 0.0
                    val decay = exp(-h / tau)
                    val disc = exp(-(s.from - origin) / theta)
                    val i0 = integral(1.0 / thetaMin, h)
                    val i1 = integral(1.0 / thetaMin + 1.0 / tau, h)
                    val i2 = integral(1.0 / thetaMin + 2.0 / tau, h)
                    val aa = tau * tau * (i0 - 2.0 * i1 + i2)
                    val bb = tau * (i1 - i2)
                    val cc = i2
                    // (A − B²/C)·y², and y² = x(1 − 2f) + f² for x in {0, 1}.
                    val lin = (aa - bb * bb / cc).coerceAtLeast(0.0)
                    addObj(x[t][k], disc * lin * (1.0 - 2.0 * f))
                    offset += disc * lin * f * f
                    // C·z², z = L + ratio·(x − f): tz ≥ 2·z0·z − z0².
                    val ratio = bb / cc
                    val tz = solver.makeNumVar(0.0, inf, "tz_${t}_$k")
                    addObj(tz, disc * cc)
                    val zInc = incLag + ratio * (xInc - f)
                    for (z0 in cutPoints(zInc, spread)) {
                        val cut = solver.makeConstraint(-z0 * z0 + 2.0 * z0 * (lagConst - ratio * f), inf)
                        cut.setCoefficient(tz, 1.0)
                        lagVar?.let { cut.setCoefficient(it, -2.0 * z0) }
                        cut.setCoefficient(x[t][k], -2.0 * z0 * ratio)
                    }
                    incLag = decay * incLag + (1.0 - decay) * tau * (xInc - f)
                    // L' = decay·L + (1 − decay)·τ·(x − f).
                    val next = solver.makeNumVar(-inf, inf, "L_${t}_$k")
                    val rhs = decay * lagConst - (1.0 - decay) * tau * f
                    val dyn = solver.makeConstraint(rhs, rhs)
                    dyn.setCoefficient(next, 1.0)
                    lagVar?.let { dyn.setCoefficient(it, -decay) }
                    dyn.setCoefficient(x[t][k], -(1.0 - decay) * tau)
                    lagVar = next
                    lagConst = 0.0
                }
                // The lag the window leaves: the rest of the continuation, held fixed, costs αL² + βL + γ.
                val (alpha, beta) = tailCost(model, start, after, i, b, untilU)
                val lastLag = lagVar ?: continue
                addObj(lastLag, beta)
                if (alpha > 0.0) {
                    val tl = solver.makeNumVar(0.0, inf, "tl_$t")
                    addObj(tl, alpha)
                    for (z0 in cutPoints(incLag, spread)) {
                        solver.makeConstraint(-z0 * z0, inf).apply {
                            setCoefficient(tl, 1.0)
                            setCoefficient(lastLag, -2.0 * z0)
                        }
                    }
                }
            }
            objective.setOffset(offset)

            // --- the incumbent as the hint: EVERY binary, so SCIP starts from a complete feasible assignment
            val hintVars = ArrayList<MPVariable>()
            val hintValues = ArrayList<Double>()
            val incumbentRun = HashSet<Triple<Int, Int, Int>>()
            var p0 = 0
            while (p0 < K) {
                var q0 = p0
                while (q0 + 1 < K && incumbentTask[q0 + 1] == incumbentTask[p0]) q0++
                incumbentRun += Triple(tasks.indexOf(incumbentTask[p0]), p0, q0)
                p0 = q0 + 1
            }
            for (t in tasks.indices) {
                for (k in 0 until K) {
                    hintVars += x[t][k]
                    hintValues += if (incumbentTask[k] == tasks[t]) 1.0 else 0.0
                }
                for (p in 0 until K) {
                    val row = y[t][p] ?: continue
                    for (q in p until K) {
                        val v = row[q] ?: continue
                        hintVars += v
                        hintValues += if (Triple(t, p, q) in incumbentRun) 1.0 else 0.0
                    }
                }
            }
            solver.setHint(hintVars.toTypedArray(), hintValues.toDoubleArray())
            val limit = minOf(budget.remainingMillis(), WINDOW_TIME_LIMIT_MILLIS)
            if (limit <= 0L) return null
            solver.setTimeLimit(limit)
            val status = solver.solve()
            if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) return null

            val windowRuns = ArrayList<ScheduleOptimizer.Run>(K)
            var changed = false
            for (k in 0 until K) {
                var holder = -1
                for ((t, i) in tasks.withIndex()) if (x[t][k].solutionValue() > 0.5) holder = i
                if (holder < 0) return null
                if (holder != incumbentTask[k]) changed = true
                windowRuns += ScheduleOptimizer.Run(holder, slots[k].from, slots[k].to, -1)
            }
            if (!changed) return null
            return ScheduleOptimizer.coalesce(before + windowRuns + after)
        } finally {
            solver.delete()
        }
    }
    /**
     * `(α, β)` in minutes such that task [i]'s criterion 1 from [b] to [untilU], with [after] held fixed, is
     * `αL² + βL + γ` in its lag `L` (minutes) at [b]. Exact: the lag dynamics are linear in `L` and the cost is the
     * integral of its square, so three evaluations fit it.
     */
    private fun tailCost(
        model: ScoreModel,
        start: ScoreCursor,
        after: List<ScheduleOptimizer.Run>,
        i: Int,
        b: Double,
        untilU: Double,
    ): Pair<Double, Double> {
        val d = model.tau[i]
        fun costFrom(lag: Double): Double {
            val lags = start.lag.copyOf().also { it[i] = lag }
            val lagU = start.lagU.copyOf().also { it[i] = b }
            val c = ScoreCursor(lags, lagU, b, -1, 0.0, 0.0, start.originU)
            for (r in after) {
                if (r.task != i) continue
                model.advance(c, i, r.fromU, served = false)
                model.advance(c, i, r.toU, served = true)
            }
            model.advance(c, i, untilU, served = false)
            return c.cost
        }
        val c0 = costFrom(0.0)
        val cp = costFrom(d)
        val cm = costFrom(-d)
        // In millis: cost = α·L² + β·L. With L = UNIT·ℓ and cost = UNIT³·cost_min: α_min = α·UNIT⁻¹, β_min = β·UNIT⁻².
        val alpha = ((cp + cm) / 2.0 - c0) / (d * d)
        val beta = (cp - cm) / (2.0 * d)
        return (alpha / UNIT).coerceAtLeast(0.0) to beta / (UNIT * UNIT)
    }

    private fun cutPoints(center: Double, spread: Double): DoubleArray =
        doubleArrayOf(center, center - spread / 8, center + spread / 8, center - spread / 2, center + spread / 2, center - spread, center + spread)

    /** `∫₀ʰ e^(−k·w) dw`. */
    private fun integral(k: Double, h: Double): Double {
        val kh = k * h
        return if (kh < 1e-8) h * (1.0 - kh / 2.0) else (1.0 - exp(-kh)) / k
    }

    companion object {
        const val BACKEND: String = "SCIP"

        /** The shared instance, or null when OR-Tools cannot load here. */
        val instance: MipScheduleSolver? by lazy { if (OrTools.available) MipScheduleSolver() else null }

        /** Millis per minute: the program's unit of time. */
        private const val UNIT: Double = 60_000.0
        private const val SLOT_MILLIS: Double = 5 * 60_000.0
        private const val MIN_SLOT_MILLIS: Double = 1_000.0
        private const val MAX_SLOTS: Int = 96
        private const val MAX_TASKS: Int = 8
        private const val WINDOW_MINIMUMS: Double = 2.0
        private const val MIN_WINDOW_MILLIS: Double = 45 * 60_000.0
        private const val MAX_WINDOW_MILLIS: Double = 4 * 60 * 60_000.0
        private const val WINDOW_TIME_LIMIT_MILLIS: Long = 2_000
        private const val MAX_SWEEPS: Int = 3
    }
}
