package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The search over the whole continuation for OmniApp's score — a port of `C:\code\line_manager\scripts\score_search.py`
 * (`docs/scheduler_score.md` § Degradation): seeds that compete, each improved over the whole continuation, a change
 * kept only when the whole scores lower. It works on a model of the window (c, end]: the schedulable clock u, its
 * stretches with the tasks allowed and the local shares, the lags at the chip. A continuation is a list of runs (task
 * or null for nobody, length on u). Its time budget makes what it returns depend on the machine: only its seeds,
 * costs and conversions are held to the reference exactly.
 */
typealias ScoreRun = Pair<String?, Double>

class Stretch2(val u0: Double, val u1: Double, val w0: Double, val w1: Double, val allowed: Set<String>, val fixed: String?, val shares: Map<String, Double>)

class ScoreModel(problem: ScoreProblem, ctx: ProblemContext, state: LineState, budget: Double = 2.0) {
    val p = problem
    val sc = problem.score
    val c: Rational = ctx.c.x
    val end: Rational = c + problem.window!!
    var deadline = TimeSource.Monotonic.markNow() + (budget * 1000).toLong().milliseconds
    val r: RuleStateR = sc.ruleState(c)
    val names: List<String> = r.names
    val unit: Map<String, Double> = names.associateWith { max(r.m.getValue(it), sc.s.floor) }
    val st = ArrayList<Stretch2>()
    val bigU: Double
    val l0: Map<String, Double>
    var open: Pair<String?, Double> = null to 0.0
    val openUser: Boolean
    val quantities: List<Quad>
    val servedBefore = HashMap<Triple<String, Double, Double>, Double>()

    data class Quad(val i: String, val q: Double, val a: Double, val b: Double)

    init {
        val pre = problem.preplaced(state)
        val full = sc.historySpan()
        val cf = c.toDoubleValue()
        val ef = end.toDoubleValue()
        var segs = sc.segments(state.line, pre, cf - full, ef + full, r.lam.values.min() / 4, at = listOf(c, end))
        var mus = segs.map { sc.mu(it) }
        var sched = mus.map { mu -> mu.values.any { it > 0 } }
        val allSegs = segs
        val allSched = sched
        var u0 = ArrayList<Double>()
        var u = 0.0
        for ((x, ok) in segs.zip(sched)) {
            u0.add(u)
            u += if (ok) x.b - x.a else 0.0
        }
        var targets = sc.targetsFor(r, segs, mus, sched, u0, state.line, pre)
        val keep = segs.indices.filter { segs[it].a >= cf - 1e-9 && segs[it].b <= ef + 1e-9 }
        val base = if (keep.isNotEmpty()) u0[keep[0]] else 0.0
        segs = keep.map { segs[it] }
        mus = keep.map { mus[it] }
        sched = keep.map { sched[it] }
        targets = keep.map { targets[it] }
        u0 = ArrayList(keep.map { u0[it] - base })
        u = if (keep.isNotEmpty()) u0.last() + (if (sched.last()) segs.last().b - segs.last().a else 0.0) else 0.0
        val w = Region.of(Interval.of(Pos(c), Pos(end), false, true))
        val vals = state.line.vals.mapValues { (k, d) -> if (k in sc.tasks) d.mapValues { it.value - (state.claims.owned(k, Owner.GEN) and w) } else d }
        val no = LinkedHashMap<String, Region>()
        for (i in names) {
            var x = forbidden(ctx, i, vals)
            for ((k, rr) in sc.tasks.getValue(i).resilience) if (rr == 0.0) x = x or state.line.presence(k)
            no[i] = x
        }
        val edges = HashSet<Double>()
        for (i in names) for (iv in no.getValue(i).on(Pos(c), Pos(end)).intervals) for (e in listOf(iv.lo, iv.hi)) {
            val ed = e.x.toDoubleValue()
            if (cf < ed && ed < ef) edges.add(ed)
        }
        val sortedEdges = edges.sorted()
        for ((k, x) in segs.withIndex()) {
            if (!sched[k]) continue
            val fixed = x.pre
            val cuts = listOf(x.a) + sortedEdges.filter { x.a < it && it < x.b } + x.b
            for ((p0, p1) in cuts.zipWithNext()) {
                val mid = exactRational(p0 + p1) / 2
                val allowed = if (fixed != null) setOf(fixed) else names.filter { i -> r.pi.getValue(i) > 0 && mus[k].getValue(i) > 0 && !no.getValue(i).contains(Pos(mid)) }.toSet()
                val du = p0 - x.a
                st.add(Stretch2(u0[k] + du, u0[k] + du + p1 - p0, p0, p1, allowed, fixed, targets[k]))
            }
        }
        bigU = u
        l0 = problem.lagsAt(state.line, c, pre)
        var who: String? = null
        var length = 0.0
        for ((x, ok) in allSegs.asReversed().zip(allSched.asReversed())) {
            if (x.a >= cf - 1e-9) continue
            if (!ok) continue
            val here = names.filter { it in x.on }
            val part = min(x.b, cf) - x.a
            if (who == null) {
                if (here.size != 1) break
                who = here[0]
                length = part
            } else if (who in here) {
                length += part
            } else {
                break
            }
        }
        if (who != null) open = who to length
        openUser = open.first != null && state.claims.owned(open.first!!, Owner.USER).contains(Pos(c - Rational.of(1, 2)))
        quantities = names.flatMap { i -> sc.tasks.getValue(i).quantities.filter { it.third.toDoubleValue() > cf }.map { (q, a, b) -> Quad(i, q.toDoubleValue(), a.toDoubleValue(), b.toDoubleValue()) } }
        for (qd in quantities) {
            val lo = exactRational(qd.a)
            servedBefore[Triple(qd.i, qd.a, qd.b)] = if (lo < c) state.line.presence(qd.i).measureOn(IntervalSet.of(Interval.of(Pos(lo), Pos(c), false, true))).toDoubleValue() else 0.0
        }
    }

    fun outOfTime(): Boolean = deadline.hasPassedNow()

    fun stretchAt(u: Double): Stretch2? = st.firstOrNull { it.u0 <= u && u < it.u1 }

    /** (D, J) of a continuation, the score's ([only]: that task's terms alone; D not rounded with [raw]). */
    fun cost(runs: List<ScoreRun>, only: String? = null, raw: Boolean = false): Pair<Double, Double> {
        val ns = if (only == null) names else listOf(only)
        val lag = LinkedHashMap(l0)
        var j1 = 0.0
        var j2 = 0.0
        var j3 = 0.0
        var cur = open.first
        var length = open.second
        val served = HashMap<String, MutableList<Pair<Double, Double>>>()
        var u = 0.0
        var k = 0
        for ((who, h) in runs) {
            val e = u + h
            while (u < e - 1e-9 && k < st.size) {
                val s = st[k]
                if (u >= s.u1 - 1e-9) {
                    k++
                    continue
                }
                val piece = min(e, s.u1) - u
                val whoHere = s.fixed ?: (if (who in s.allowed) who else null)
                for (j in ns) {
                    val tau = r.tau.getValue(j)
                    val a = ((if (j == whoHere) 1.0 else 0.0) - s.shares.getValue(j)) * tau
                    val b = lag.getValue(j) - a
                    j1 += sq(a, b, tau, r.theta.getValue(j), u, piece) / (unit.getValue(j) * unit.getValue(j))
                    lag[j] = a + b * exp(-piece / tau)
                }
                if (whoHere != cur) {
                    if (cur != null && (only == null || cur == only)) {
                        val (n2, n3) = charge(cur, length, u, j2, j3)
                        j2 = n2
                        j3 = n3
                    }
                    cur = whoHere
                    length = 0.0
                }
                if (whoHere != null) {
                    length += piece
                    val w0 = s.w0 + (u - s.u0)
                    served.getOrPut(whoHere) { ArrayList() }.add(w0 to (w0 + piece))
                }
                u += piece
            }
            u = e
        }
        val cc = cur
        if (cc != null && (only == null || cc == only) && length > r.x.getValue(cc)) {
            val e = length - r.x.getValue(cc)
            j3 += exp(-u / r.theta.getValue(cc)) * r.tau.getValue(cc) * e * (2 * r.m.getValue(cc) + e) / (unit.getValue(cc) * unit.getValue(cc))
        }
        for (j in ns) {
            j1 += exp(-u / r.theta.getValue(j)) * lag.getValue(j) * lag.getValue(j) / (1 / r.theta.getValue(j) + 2 / r.tau.getValue(j)) / (unit.getValue(j) * unit.getValue(j))
        }
        var d = 0.0
        for (qd in quantities) {
            if (only != null && qd.i != only) continue
            val got = servedBefore.getValue(Triple(qd.i, qd.a, qd.b)) + (served[qd.i] ?: emptyList<Pair<Double, Double>>()).sumOf { (x, y) -> max(0.0, min(y, qd.b) - max(x, qd.a)) }
            var missing = max(0.0, qd.q - got)
            if (qd.b > end.toDoubleValue()) missing = max(0.0, missing - (qd.b - end.toDoubleValue()))
            d += missing
        }
        val j = j1 + sc.s.minimumWeight * j2 + sc.s.maximumWeight * j3
        return (if (raw) d else pyRound(d).toDouble()) to j
    }

    private fun charge(i: String, ell: Double, u: Double, j2: Double, j3: Double): Pair<Double, Double> {
        val m = r.m.getValue(i)
        val x = r.x.getValue(i)
        val tau = r.tau.getValue(i)
        val th = r.theta.getValue(i)
        val disc = exp(-u / th) / (unit.getValue(i) * unit.getValue(i))
        var n2 = j2
        var n3 = j3
        if (ell < m) {
            val s = m - ell
            n2 += disc * tau * s * (2 * m + s)
        }
        if (ell > x) {
            val e = ell - x
            n3 += disc * tau * e * (2 * m + e)
        }
        return n2 to n3
    }

    /** How far from u the task may run on (the stretches that allow it, unbroken). */
    fun freeUntil(who: String?, u: Double): Double {
        var e = u
        for (s in st) {
            if (s.u1 <= e + 1e-9) continue
            if (s.u0 > e + 1e-9 || (who !in s.allowed && s.fixed != who)) break
            e = s.u1
        }
        return e
    }

    /** (who, length) to try at u. */
    fun options(u: Double, factors: List<Double> = listOf(1.0, 1.5, 2.0)): List<ScoreRun> {
        val s = stretchAt(u) ?: return emptyList()
        if (s.fixed != null) return listOf(s.fixed to (s.u1 - u))
        val out = ArrayList<ScoreRun>()
        for (i in s.allowed.sorted()) {
            val room = freeUntil(i, u) - u
            for (f in factors) {
                val h = min(min(r.m.getValue(i) * f, room), bigU - u)
                if (h > 0 && (i to h) !in out) out.add(i to h)
            }
        }
        val nob = (s.allowed.map { r.m.getValue(it) } + (s.u1 - u) + (bigU - u)).min()
        out.add(null to max(nob, 1.0))
        return out
    }

    /** The base policy from u to [until]: the allowed task due first for its minimum. */
    fun base(runs: List<ScoreRun>, u0: Double, until: Double, l: Map<String, Double>? = null, offset: Double = 0.5): List<ScoreRun> {
        val out = ArrayList(runs)
        var u = u0
        var lag = l
        var guard = 0
        while (u < min(until, bigU) - 1e-9 && guard < 10000) {
            guard++
            val s = stretchAt(u) ?: break
            if (s.fixed != null) {
                val h = s.u1 - u
                out.add(s.fixed to h)
                u += h
                continue
            }
            val ok = s.allowed.sorted()
            if (ok.isEmpty()) {
                out.add(null to (s.u1 - u))
                u = s.u1
                continue
            }
            val lg = lag ?: lags(out)
            val i = ok.minWith(compareBy<String>({ j -> (lg.getValue(j) + offset * r.m.getValue(j)) / max(s.shares.getValue(j), 1e-12) }, { it }))
            val h = min(min(r.m.getValue(i), freeUntil(i, u) - u), bigU - u)
            out.add(i to h)
            u += h
            lag = null
        }
        return out
    }

    fun lags(runs: List<ScoreRun>): Map<String, Double> {
        val lag = LinkedHashMap(l0)
        var u = 0.0
        var k = 0
        for ((who, h) in runs) {
            val e = u + h
            while (u < e - 1e-9 && k < st.size) {
                val s = st[k]
                if (u >= s.u1 - 1e-9) {
                    k++
                    continue
                }
                val piece = min(e, s.u1) - u
                val w = s.fixed ?: (if (who in s.allowed) who else null)
                for (j in names) {
                    val tau = r.tau.getValue(j)
                    val a = ((if (j == w) 1.0 else 0.0) - s.shares.getValue(j)) * tau
                    lag[j] = a + (lag.getValue(j) - a) * exp(-piece / tau)
                }
                u += piece
            }
            u = e
        }
        return lag
    }

    private fun cmp(a: Pair<Double, Double>, b: Pair<Double, Double>): Int = compareValuesBy(a, b, { it.first }, { it.second })

    /** One decision at a time, two runs deep, completed by the base policy (not used by [search]). */
    fun rollout(horizon: Double, depth: Int = 2, factors: List<Double> = listOf(1.0, 1.5, 2.0)): List<ScoreRun> {
        val runs = ArrayList<ScoreRun>()
        var u = 0.0
        while (u < bigU - 1e-9) {
            if (outOfTime()) return base(runs, u, bigU)
            var best: Triple<Pair<Double, Double>, String?, Double>? = null
            for ((who, h) in options(u, factors)) {
                val first = runs + (who to h)
                val tails = ArrayList<Pair<Double, Double>>()
                val second = (if (depth > 1) options(u + h, listOf(1.0)) else emptyList()).ifEmpty { listOf(null to 0.0) }
                for ((who2, h2) in second) {
                    var trial = first + (if (h2 > 0) listOf(who2 to h2) else emptyList())
                    val v = u + h + h2
                    trial = base(trial, v, min(bigU, v + horizon))
                    tails.add(cost(trial))
                }
                val key = tails.minWith(::cmp)
                if (best == null || cmp(key, best.first) < 0) best = Triple(key, who, h)
            }
            runs.add(best!!.second to best.third)
            u += best.third
        }
        return runs
    }

    /** The base policy with what the instant schedule must never show (see the reference); no time in it. */
    fun ruled(offset: Double = 0.5): List<ScoreRun> {
        val out = ArrayList<ScoreRun>()
        var u = 0.0
        var cur = open.first
        var run = open.second
        var afterUser = openUser
        val least = names.filter { r.pi.getValue(it) > 0 }.minOfOrNull { r.m.getValue(it) } ?: 0.0
        val fixed = st.filter { it.fixed != null }.map { it.u0 to it.fixed!! }
        var guard = 0
        while (u < bigU - 1e-9 && guard < 100000) {
            guard++
            val s = stretchAt(u) ?: break
            if (s.fixed != null) {
                val h = s.u1 - u
                out.add(s.fixed to h)
                run = if (cur == s.fixed) run + h else h
                cur = s.fixed
                afterUser = true
                u += h
                continue
            }
            val ok = s.allowed.sorted()
            if (ok.isEmpty()) {
                val h = s.u1 - u
                out.add(null to h)
                cur = null
                run = 0.0
                afterUser = false
                u += h
                continue
            }
            val nxt = fixed.firstOrNull { it.first >= u - 1e-9 }
            val uF = nxt?.first
            val xKind = nxt?.second
            fun room(i: String): Double {
                var e = freeUntil(i, u)
                if (uF != null) e = min(e, uF)
                return min(e, bigU) - u
            }
            fun maxed(i: String) = cur == i && r.x.getValue(i) != Double.POSITIVE_INFINITY && run >= r.x.getValue(i) - 1e-6
            fun touches(i: String): Boolean {
                if (afterUser && i == cur) return true
                if (i == xKind && uF != null && u + room(i) >= uF - 1e-6) return uF - (u + min(r.m.getValue(i), room(i))) < least
                return false
            }
            val cands = ok.filter { !maxed(it) }.ifEmpty { ok }
            val pick = cands.filter { !touches(it) }.ifEmpty { cands }
            val cc = cur
            if (cc != null && cc in pick && !afterUser && run < r.m.getValue(cc) - 1e-6 && room(cc) > 1e-9) {
                val h = min(r.m.getValue(cc) - run, room(cc))
                out.add(cc to h)
                run += h
                u += h
                continue
            }
            val lag = lags(out)
            val i = pick.minWith(compareBy<String>({ j -> (lag.getValue(j) + offset * r.m.getValue(j)) / max(s.shares.getValue(j), 1e-12) }, { it }))
            val rm = room(i)
            var h = min(r.m.getValue(i), rm)
            if (i == cur && r.x.getValue(i) != Double.POSITIVE_INFINITY && r.x.getValue(i) - run > 1e-6) h = min(h, r.x.getValue(i) - run)
            if (uF != null && xKind != i && u + rm >= uF - 1e-6 && 0 < uF - (u + h) && uF - (u + h) < least) h = uF - u
            if (h <= 1e-9) h = if (rm > 1e-9) rm else s.u1 - u
            out.add(i to h)
            run = if (cur == i) run + h else h
            cur = i
            afterUser = false
            u += h
        }
        val merged = ArrayList<ScoreRun>()
        for ((who, h) in out) {
            if (merged.isNotEmpty() && merged.last().first == who) merged[merged.size - 1] = who to (merged.last().second + h) else merged.add(who to h)
        }
        return merged
    }

    /** The window at the shares, nobody never: the base policy alone. */
    fun stride(offset: Double = 0.5): List<ScoreRun> = base(emptyList(), 0.0, bigU, offset = offset)

    /** Changes to the whole continuation, each kept when the whole scores lower, until nothing helps or the time runs out. */
    fun improve(runs0: List<ScoreRun>, focus: Pair<Double, Double>? = null, fraction: Int = 4): List<ScoreRun> {
        var runs = runs0
        var best = cost(runs)
        var changed = true
        while (changed && !outOfTime()) {
            changed = false
            for (n in runs.indices) {
                if (outOfTime()) break
                if (focus != null) {
                    val u = runs.subList(0, n).sumOf { it.second }
                    if (u + runs[n].second <= focus.first || u >= focus.second) continue
                }
                val cands = ArrayList<List<ScoreRun>>()
                if (n + 1 < runs.size) {
                    cands.add(runs.subList(0, n) + listOf(runs[n + 1], runs[n]) + runs.subList(n + 2, runs.size))
                    val (a, ha) = runs[n]
                    val (b, hb) = runs[n + 1]
                    for (d in listOf(min(ha, hb) / fraction, -min(ha, hb) / fraction)) {
                        if (ha + d > 1 && hb - d > 1) cands.add(runs.subList(0, n) + listOf(a to (ha + d), b to (hb - d)) + runs.subList(n + 2, runs.size))
                    }
                }
                val u = runs.subList(0, n).sumOf { it.second }
                val s = stretchAt(u)
                if (s != null && s.fixed == null) {
                    for (who in s.allowed.sorted() + listOf<String?>(null)) {
                        if (who != runs[n].first) cands.add(runs.subList(0, n) + listOf(who to runs[n].second) + runs.subList(n + 1, runs.size))
                    }
                }
                for (cand in cands) {
                    if (!valid(cand)) continue
                    val key = cost(cand)
                    if (cmp(key, best) < 0) {
                        runs = cand
                        best = key
                        changed = true
                        break
                    }
                }
            }
        }
        return runs
    }

    /** While the time lasts: the best plan shaken a little, improved again, kept if the whole scores lower. */
    fun deepen(runs: List<ScoreRun>, rnd: PyRandom, focus: Pair<Double, Double>? = null, fraction: Int = 4): List<ScoreRun> {
        var best = runs
        var key = cost(runs)
        fun uOf(n: Int, rs: List<ScoreRun>) = rs.subList(0, n).sumOf { it.second }
        while (!outOfTime() && best.size > 1) {
            val cand = best.toMutableList()
            val idx = cand.indices.filter { n -> focus == null || (uOf(n, cand) < focus.second && uOf(n, cand) + cand[n].second > focus.first) }
            if (idx.isEmpty()) break
            if (rnd.random() < 0.5 && idx.size > 1) {
                val (a, b) = rnd.sample(idx, 2).sorted()
                val t = cand[a]
                cand[a] = cand[b]
                cand[b] = t
            } else {
                for (n in rnd.sample(idx, min(idx.size, rnd.randint(2, 4)))) {
                    val s = stretchAt(uOf(n, cand))
                    if (s != null && s.fixed == null && s.allowed.isNotEmpty()) {
                        cand[n] = rnd.choice(s.allowed.sorted() + listOf<String?>(null)) to cand[n].second
                    }
                }
            }
            if (!valid(cand)) continue
            val improved = improve(cand, focus, fraction)
            val k = cost(improved)
            if (cmp(k, key) < 0) {
                best = improved
                key = k
            }
        }
        return best
    }

    /**
     * (the best plan, whether it is proved best, how many plans were looked at): every plan of the slice [0, S] of at
     * most [maxRuns] runs whose ends are on whole [grid]s, what follows the slice as [runs] has it. Exact over that
     * family (the score is a sum of one term per task); proved when it was looked at whole before the time ran out.
     */
    fun certify(runs: List<ScoreRun>, bigS: Double, grid: Double = 60.0, maxRuns: Int = 3): Triple<List<ScoreRun>, Boolean, Int> {
        if (bigS <= 0 || st.any { it.fixed != null && it.u0 < bigS }) return Triple(runs, false, 0)
        val rest = ArrayList<ScoreRun>()
        var u = 0.0
        for ((who, h) in runs) {
            val a = u
            val b = u + h
            if (b > bigS) rest.add(who to (b - max(a, bigS)))
            u = b
        }
        val restIvs = HashMap<String, MutableList<Pair<Double, Double>>>()
        u = bigS
        for ((who, h) in rest) {
            if (who != null) restIvs.getOrPut(who) { ArrayList() }.add(u to (u + h))
            u += h
        }
        val tasks = names.filter { r.pi.getValue(it) > 0 }
        val cache = HashMap<Pair<String, List<Pair<Double, Double>>>, Pair<Double, Double>>()
        fun term(j: String, ivs: List<Pair<Double, Double>>): Pair<Double, Double> = cache.getOrPut(j to ivs) {
            val marks = (ivs + restIvs[j].orEmpty()).sortedWith(compareBy({ it.first }, { it.second }))
            val out = ArrayList<ScoreRun>()
            var v = 0.0
            for ((a, b) in marks) {
                if (a > v) out.add(null to (a - v))
                out.add(j to (b - a))
                v = b
            }
            if (v < bigU) out.add(null to (bigU - v))
            cost(out, only = j, raw = true)
        }
        val base = tasks.associateWith { term(it, emptyList()) }
        val bD = base.values.sumOf { it.first }
        val bJ = base.values.sumOf { it.second }
        var bestKey = cost(runs)
        var bestPlan: List<Pair<String?, Pair<Double, Double>>>? = null
        val points = (1..(bigS / grid).toLong().toInt()).map { min(it * grid, bigS) }.toSet().minus(bigS).sorted()
        val allowed = HashMap<Triple<String, Double, Double>, Boolean>()
        fun ok(j: String?, a: Double, b: Double): Boolean {
            if (j == null) return true
            return allowed.getOrPut(Triple(j, a, b)) { freeUntil(j, a) >= b - 1e-6 }
        }
        var n = 0
        for (k in 1..maxRuns) {
            for (cut in combinationsOf(points, k - 1)) {
                val ends = listOf(0.0) + cut + bigS
                val segs = ends.zipWithNext()
                fun walk(i: Int, prev: Any?, plan: List<String?>): Boolean {
                    if (i == segs.size) {
                        n++
                        val mine = LinkedHashMap<String, MutableList<Pair<Double, Double>>>()
                        for ((j, seg) in plan.zip(segs)) if (j != null) mine.getOrPut(j) { ArrayList() }.add(seg)
                        var d = bD
                        var jj = bJ
                        for ((j, ivs) in mine) {
                            val (td, tj) = term(j, ivs)
                            d += td - base.getValue(j).first
                            jj += tj - base.getValue(j).second
                        }
                        val key = pyRound(d).toDouble() to jj
                        if (cmp(key, bestKey) < 0) {
                            bestKey = key
                            bestPlan = plan.zip(segs)
                        }
                        return true
                    }
                    val (a, b) = segs[i]
                    for (j in tasks + listOf<String?>(null)) {
                        if ((j == prev && j != null) || !ok(j, a, b)) continue
                        if (!walk(i + 1, j, plan + j)) return false
                        if (outOfTime()) return false
                    }
                    return true
                }
                if (!walk(0, "", emptyList())) return Triple(withSlice(runs, bestPlan, rest), false, n)
            }
        }
        return Triple(withSlice(runs, bestPlan, rest), true, n)
    }

    private fun <T> combinationsOf(items: List<T>, k: Int): List<List<T>> {
        if (k == 0) return listOf(emptyList())
        if (k > items.size) return emptyList()
        val out = ArrayList<List<T>>()
        val idx = IntArray(k) { it }
        while (true) {
            out.add(idx.map { items[it] })
            var i = k - 1
            while (i >= 0 && idx[i] == items.size - k + i) i--
            if (i < 0) return out
            idx[i]++
            for (j in i + 1 until k) idx[j] = idx[j - 1] + 1
        }
    }

    /** runs with the slice's plan in place of what it had (null: runs as they are). */
    private fun withSlice(runs: List<ScoreRun>, plan: List<Pair<String?, Pair<Double, Double>>>?, rest: List<ScoreRun>): List<ScoreRun> {
        if (plan == null) return runs
        val out = ArrayList<ScoreRun>()
        for ((j, ab) in plan + rest.map { (w, h) -> w to (0.0 to h) }) {
            val h = ab.second - ab.first
            if (out.isNotEmpty() && out.last().first == j) out[out.size - 1] = j to (out.last().second + h) else out.add(j to h)
        }
        return out
    }

    /** Each run's task allowed all along it. */
    fun valid(runs: List<ScoreRun>): Boolean {
        var u = 0.0
        for ((who, h) in runs) {
            if (who != null && freeUntil(who, u) < u + h - 1e-6) return false
            u += h
        }
        return true
    }

    /** The runs of a schedule on the line, on the schedulable clock. */
    fun fromWall(panels: Map<String, List<Pair<Double, Double>>>): List<ScoreRun> {
        val runs = ArrayList<ScoreRun>()
        val marks = panels.flatMap { (k, v) -> v.map { (a, b) -> Triple(a, b, k) } }.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        for (s in st) {
            var pos = s.w0
            while (pos < s.w1 - 1e-9) {
                val here = marks.firstOrNull { it.first <= pos && pos < it.second }
                val e: Double
                val who: String?
                if (here != null && (here.third in s.allowed || here.third == s.fixed)) {
                    e = min(here.second, s.w1)
                    who = here.third
                } else {
                    val nxt = marks.map { it.first }.filter { pos < it && it < s.w1 }
                    e = nxt.minOrNull() ?: s.w1
                    who = s.fixed
                }
                if (runs.isNotEmpty() && runs.last().first == who) runs[runs.size - 1] = who to (runs.last().second + e - pos) else runs.add(who to (e - pos))
                pos = e
            }
        }
        return runs
    }

    /** The schedulable clock at the line's position x. */
    fun uOf(x: Double): Double {
        for (s in st) {
            if (s.w0 <= x && x < s.w1) return s.u0 + (x - s.w0)
            if (x < s.w0) return s.u0
        }
        return bigU
    }

    /** The runs on the line: {task: [(a, b), ...]}, the user's stretches left out. */
    fun toWall(runs: List<ScoreRun>): Map<String, List<Pair<Rational, Rational>>> {
        val out = LinkedHashMap<String, MutableList<Pair<Rational, Rational>>>()
        var u = 0.0
        for ((who, h) in runs) {
            val e = u + h
            if (who != null) {
                for (s in st) {
                    val a = max(u, s.u0)
                    val b = min(e, s.u1)
                    if (b - a > 1e-9 && s.fixed == null && who in s.allowed) {
                        val wa = exactRational(s.w0 + (a - s.u0)).limitDenominator(1000)
                        val wb = exactRational(s.w0 + (b - s.u0)).limitDenominator(1000)
                        out.getOrPut(who) { ArrayList() }.add(wa to wb)
                    }
                }
            }
            u = e
        }
        return out
    }

    companion object {
        fun sq(a: Double, b: Double, tau: Double, th: Double, v0: Double, h: Double): Double {
            fun ex(rate: Double) = if (rate == 0.0) h else (1 - exp(-rate * h)) / rate
            return exp(-v0 / th) * (a * a * ex(1 / th) + 2 * a * b * ex(1 / th + 1 / tau) + b * b * ex(1 / th + 2 / tau))
        }
    }
}

object ScoreSearch {
    /** The base policy's offsets, in the order they are tried. */
    val SEEDS = listOf(0.25, 0.75, 0.0, 0.5, 1.0)

    /** The best continuation found in [budget] seconds, on the line: {task: [(a, b), ...]}. */
    fun search(problem: ScoreProblem, ctx: ProblemContext, state: LineState, budget: Double = 4.0): Map<String, List<Pair<Rational, Rational>>> {
        val m = ScoreModel(problem, ctx, state, budget)
        if (m.bigU <= 0) return emptyMap()
        var best: List<ScoreRun>? = null
        var key: Pair<Double, Double>? = null
        for (off in SEEDS) {
            val runs = m.improve(m.stride(off))
            val k = m.cost(runs)
            if (key == null || compareValuesBy(k, key, { it.first }, { it.second }) < 0) {
                best = runs
                key = k
            }
            if (m.outOfTime()) break
        }
        return m.toWall(best!!)
    }
}
