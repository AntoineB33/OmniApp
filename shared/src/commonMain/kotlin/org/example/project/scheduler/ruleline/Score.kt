package org.example.project.scheduler.ruleline

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The score of OmniApp's scheduler (`docs/scheduler_score.md`) on a line — a port of
 * `C:\code\line_manager\scripts\score.py` (its module text has the notation and definitions).
 *
 * The tasks are kinds of the line, served wherever they are; the restrictive periods are the other kinds a task has
 * a resilience to. The score of the line from the chip c to `end` is (D, J), J = J1 + w J2 + w_X J3; past `end`
 * criterion 1 is closed in closed form. The score is a double, as the reference's is a float.
 *
 * This is the second copy of the score while the app's scheduler (`domain/ScheduleScore.kt`) is still in place: it
 * goes when the rule engine replaces it (ADR 0018 § 2026-11-28, step 7).
 */
interface ValueFormula {
    fun at(x: Rational): Double
    fun changes(a: Rational, b: Rational): List<Rational>
}

data class Const(val v: Double) : ValueFormula {
    override fun at(x: Rational) = v
    override fun changes(a: Rational, b: Rational): List<Rational> = emptyList()
}

/** A value per interval: [before] up to the first change, then each (x, v): v from x on (x excluded). */
class StepFormula(before: Double, changes: List<Pair<Rational, Double>> = emptyList()) : ValueFormula {
    val before = before
    val points = changes.sortedWith(compareBy({ it.first }, { it.second }))

    override fun at(x: Rational): Double {
        var v = before
        for ((p, w) in points) if (x > p) v = w
        return v
    }

    override fun changes(a: Rational, b: Rational) = points.map { it.first }.filter { a < it && it < b }
}

/** From v0 at x0 to v1 at x1, linearly; read in [steps] steps between them. */
class LinearFormula(val x0: Rational, val v0: Double, val x1: Rational, val v1: Double, val steps: Int = 32) : ValueFormula {
    override fun at(x: Rational): Double = when {
        x <= x0 -> v0
        x >= x1 -> v1
        else -> v0 + (v1 - v0) * ((x - x0) / (x1 - x0)).toDoubleValue()
    }

    override fun changes(a: Rational, b: Rational): List<Rational> {
        val d = (x1 - x0) / steps
        return (0..steps).map { x0 + d * it }.filter { a < it && it < b }
    }
}

/** A formula over [0, period) repeated. */
class PeriodicFormula(val f: ValueFormula, val period: Rational) : ValueFormula {
    override fun at(x: Rational) = f.at(x % period)

    override fun changes(a: Rational, b: Rational): List<Rational> {
        val out = HashSet<Rational>()
        var k = period * (a / period).floor()
        while (k < b) {
            for (p in f.changes(Rational.ZERO, period) + Rational.ZERO) if (a < k + p && k + p < b) out.add(k + p)
            k += period
        }
        return out.sorted()
    }
}

data class ScoreTask(
    val priority: ValueFormula,
    val minimum: ValueFormula,
    val maximum: ValueFormula = Const(Double.POSITIVE_INFINITY),
    val resilience: Map<String, Double> = emptyMap(),
    val compensation: Double? = null,
    /** (Q, a, b): Q of presence within [a, b]. */
    val quantities: List<Triple<Rational, Rational, Rational>> = emptyList(),
)

data class ScoreSettings(
    val compensation: Double = 4.0 * 3600,
    val rareAfter: Double = 168.0 * 3600,
    val floor: Double = 60.0,
    val minimumWeight: Double = 1.0,
    val maximumWeight: Double = 1.0,
    val history: Double? = null,
)

data class ScoreResult(val d: Double, val j: Double, val j1: Double, val j2: Double, val j3: Double) {
    /** D within a second is equal. */
    fun key(): Pair<Long, Double> = pyRound(d) to j
}

/** Python's round() to an integer: half to even. */
fun pyRound(x: Double): Long = kotlin.math.round(x).toLong()

/** Python's round(x, 12) for grouping equal multipliers. */
private fun round12(x: Double): Double = kotlin.math.round(x * 1e12) / 1e12

class RuleStateR(
    val p: Map<String, Double>,
    val pi: Map<String, Double>,
    val m: Map<String, Double>,
    val x: Map<String, Double>,
    val tau: Map<String, Double>,
    val lam: Map<String, Double>,
    val rare: Map<String, Boolean>,
    val theta: Map<String, Double>,
    val names: List<String>,
)

class Seg(val a: Double, val b: Double, val on: Set<String>, val pre: String?)

class Score(tasks: Map<String, ScoreTask>, settings: ScoreSettings? = null) {
    val tasks: Map<String, ScoreTask> = LinkedHashMap(tasks)
    val s: ScoreSettings = settings ?: ScoreSettings()
    val periods: List<String> = (this.tasks.values.flatMap { it.resilience.keys }.toSet() - this.tasks.keys).sorted()

    /** Set to leave out the continuation past `end` (the reference's `_no_tail`). */
    var noTail = false
    private val farCache = HashMap<List<Any>, Double>()
    private var nobodyMemo: Pair<List<Region>, Region>? = null

    init {
        for ((name, t) in this.tasks) {
            if (t.maximum.at(Rational.ZERO) < t.minimum.at(Rational.ZERO)) throw IllegalArgumentException("$name: a maximum execution time below its minimum")
        }
    }

    /** The rule state at a position (the formulas read there). */
    fun ruleState(x: Rational): RuleStateR {
        val names = tasks.keys.sorted()
        val p = names.associateWith { max(0.0, tasks.getValue(it).priority.at(x)) }
        val total = p.values.sum()
        val pi = names.associateWith { if (total > 0) p.getValue(it) / total else 1.0 / names.size }
        val m = names.associateWith { max(tasks.getValue(it).minimum.at(x), 0.0) }
        val xx = names.associateWith { tasks.getValue(it).maximum.at(x) }
        val tau = names.associateWith { if (pi.getValue(it) > 0) max(m.getValue(it), s.floor) / pi.getValue(it) else Double.POSITIVE_INFINITY }.toMutableMap()
        val lamD = s.compensation
        val lam = names.associateWith { i ->
            val c = tasks.getValue(i).compensation
            if (c != null && c != 0.0) c else lamD + 3 * max(0.0, tau.getValue(i) - lamD)
        }.toMutableMap()
        val ordinary = names.filter { pi.getValue(it) > 0 && tau.getValue(it) <= s.rareAfter }.map { tau.getValue(it) }
        val theta = if (ordinary.isNotEmpty()) ordinary.max() else (names.filter { pi.getValue(it) > 0 }.map { tau.getValue(it) }.maxOrNull() ?: s.rareAfter)
        for (i in names) {
            if (pi.getValue(i) == 0.0) {
                tau[i] = theta // (a task with no share forgets over Theta)
                val c = tasks.getValue(i).compensation
                lam[i] = if (c != null && c != 0.0) c else lamD
            }
        }
        val rare = names.associateWith { tau.getValue(it) > s.rareAfter }
        val th = names.associateWith { if (rare.getValue(it)) tau.getValue(it) else theta }
        return RuleStateR(p, pi, m, xx, tau, lam, rare, th, names)
    }

    /** The segments of [lo, hi] where nothing on the line changes, none longer than [finest]. */
    fun segments(line: Line, preplaced: Map<String, Region>, lo0: Double, hi0: Double, finest: Double? = null, at: List<Rational> = emptyList()): List<Seg> {
        val kinds = tasks.keys.sorted() + periods
        val pres = kinds.associateWith { line.presence(it) }
        val lo = exactRational(lo0)
        val hi = exactRational(hi0)
        val cutSet = HashSet<Rational>()
        cutSet.add(lo)
        cutSet.add(hi)
        for (x in at) if (lo < x && x < hi) cutSet.add(x)
        for (k in kinds) {
            for (r in listOf(pres.getValue(k), preplaced[k] ?: Region.EMPTY)) {
                for (iv in r.on(Pos(lo), Pos(hi)).intervals) for (p in listOf(iv.lo, iv.hi)) {
                    val x = p.x
                    if (lo < x && x < hi) cutSet.add(x)
                }
            }
        }
        var cuts = cutSet.sorted()
        if (finest != null && finest != 0.0) {
            val step = exactRational(finest)
            val fine = ArrayList<Rational>()
            for ((a, b) in cuts.zipWithNext()) {
                val n = max(1L, ((b - a) / step).ceil())
                for (j in 0 until n) fine.add(a + (b - a) * Rational.of(j) / Rational.of(n))
            }
            cuts = fine + cuts.last()
        }
        val out = ArrayList<Seg>()
        for ((a, b) in cuts.zipWithNext()) {
            val mid = Pos((a + b) / 2)
            val on = kinds.filter { pres.getValue(it).contains(mid) }.toSet()
            val pre = tasks.keys.sorted().firstOrNull { preplaced[it]?.contains(mid) == true }
            out.add(Seg(a.toDoubleValue(), b.toDoubleValue(), on, pre))
        }
        return out
    }

    /** How far from the chip the whole line is read. */
    fun historySpan(): Double = s.history ?: (4 * s.compensation)

    fun mu(seg: Seg): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for ((i, t) in tasks) {
            if (seg.pre != null) {
                out[i] = if (i == seg.pre) 1.0 else 0.0
                continue
            }
            var m = 1.0
            for (k in seg.on) t.resilience[k]?.let { m *= it }
            out[i] = m
        }
        return out
    }

    /** f_i at x, under the rule state at x. */
    fun target(line: Line, x: Rational, preplaced: Map<String, Region> = emptyMap()): Map<String, Double> {
        val r = ruleState(x)
        val span = historySpan()
        val xf = x.toDoubleValue()
        val segs = segments(line, preplaced, xf - span, xf + span, r.lam.values.min() / 16)
        val mus = segs.map { mu(it) }
        val sched = mus.map { mu -> mu.values.any { it > 0 } }
        val u0 = ArrayList<Double>()
        var u = 0.0
        for ((sg, ok) in segs.zip(sched)) {
            u0.add(u)
            u += if (ok) sg.b - sg.a else 0.0
        }
        val targets = targetsFor(r, segs, mus, sched, u0, line, preplaced)
        val k = segs.indexOfFirst { it.a <= xf && xf < it.b }
        return targets[k]
    }

    /** (D, J) of the line from the chip c to [end]. */
    fun evaluate(line: Line, c: Rational, end: Rational, preplaced: Map<String, Region> = emptyMap()): ScoreResult {
        val cuts0 = HashSet<Rational>()
        cuts0.add(c)
        for (t in tasks.values) for (v in listOf(t.priority, t.minimum, t.maximum)) cuts0.addAll(v.changes(c, end))
        val cuts = cuts0.sorted() + end
        val d = shortfall(line, c, end, preplaced)
        var j1 = 0.0
        var j2 = 0.0
        var j3 = 0.0
        for ((a, b) in cuts.zipWithNext()) {
            val r = ruleState(a)
            val (x1, x2, x3) = piece(line, preplaced, r, c, a, b, end)
            j1 += x1
            j2 += x2
            j3 += x3
        }
        return ScoreResult(d, j1 + s.minimumWeight * j2 + s.maximumWeight * j3, j1, j2, j3)
    }

    /** One piece (a, b] of (c, end], scored under the rule state R. */
    private fun piece(line: Line, preplaced: Map<String, Region>, r: RuleStateR, c: Rational, a: Rational, b: Rational, end: Rational): Triple<Double, Double, Double> {
        val names = r.names
        val reach = names.associateWith { 4 * r.lam.getValue(it) }
        val full = historySpan()
        val cf = c.toDoubleValue()
        val lo = cf - full
        val hi = end.toDoubleValue() + full
        val segs = segments(line, preplaced, lo, hi, r.lam.values.min() / 16)
        val mus = segs.map { mu(it) }
        val sched = mus.map { mu -> mu.values.any { it > 0 } }
        val u0 = ArrayList<Double>()
        var u = 0.0
        for ((sg, ok) in segs.zip(sched)) {
            u0.add(u)
            if (ok) u += sg.b - sg.a
        }
        val uc = uAt(segs, sched, u0, cf)
        val targets = targetsFor(r, segs, mus, sched, u0, line, preplaced)
        val lag = names.associateWith { farLag(r, it, line, preplaced, lo, cf, reach.getValue(it)) }.toMutableMap()
        val run = names.associateWith { 0.0 }.toMutableMap()
        var j1 = 0.0
        var j2 = 0.0
        var j3 = 0.0
        val fa = a.toDoubleValue()
        val fb = b.toDoubleValue()
        for ((k, sg) in segs.withIndex()) {
            val ok = sched[k]
            val x0 = sg.a
            val x1 = sg.b
            if (x1 <= lo || x0 >= fb || !ok) continue
            val x0c = max(x0, lo)
            val x1c = min(x1, fb)
            val h = x1c - x0c
            val served = names.associateWith { if (it in sg.on) 1.0 else 0.0 }
            val ustart = u0[k] + (x0c - x0)
            for (i in names) {
                val tau = r.tau.getValue(i)
                val f = targets[k].getValue(i)
                val aa = (served.getValue(i) - f) * tau
                val bb = lag.getValue(i) - aa
                if (x1c > fa && x0c < fb) { // (in the piece: criterion 1 on its part)
                    val p0 = max(x0c, fa)
                    val d = p0 - x0c
                    val bp = bb * exp(-d / tau)
                    val hp = min(x1c, fb) - p0
                    val v0 = ustart + d - uc
                    val unit = max(r.m.getValue(i), s.floor)
                    j1 += sqIntegral(aa, bp, tau, r.theta.getValue(i), v0, hp) / (unit * unit)
                }
                lag[i] = aa + bb * exp(-h / tau)
                if (served.getValue(i) != 0.0) {
                    run[i] = run.getValue(i) + h
                } else if (run.getValue(i) > 0) {
                    if ((fa < x0c && x0c <= fb) || (x0c == fa && fa == cf)) {
                        val (n2, n3) = charge(r, i, run.getValue(i), ustart - uc, j2, j3)
                        j2 = n2
                        j3 = n3
                    }
                    run[i] = 0.0
                }
            }
        }
        if (fb == end.toDoubleValue() && !noTail) { // the continuation past `end`, closed in closed form
            val ue = uAt(segs, sched, u0, end.toDoubleValue()) - uc
            for (i in names) {
                val unit = max(r.m.getValue(i), s.floor)
                j1 += tail(lag.getValue(i), r.tau.getValue(i), r.theta.getValue(i), ue) / (unit * unit)
            }
        }
        for (i in names) { // the panel still open at the end of the piece: its excess only
            if (run.getValue(i) > 0 && fb == end.toDoubleValue()) {
                val ue = uAt(segs, sched, u0, end.toDoubleValue()) - uc
                val xx = r.x.getValue(i)
                if (run.getValue(i) > xx) {
                    val e = run.getValue(i) - xx
                    val unit = max(r.m.getValue(i), s.floor)
                    j3 += exp(-ue / r.theta.getValue(i)) * r.tau.getValue(i) * e * (2 * r.m.getValue(i) + e) / (unit * unit)
                }
            }
        }
        return Triple(j1, j2, j3)
    }

    private fun charge(r: RuleStateR, i: String, ell: Double, ue: Double, j2: Double, j3: Double): Pair<Double, Double> {
        val m = r.m.getValue(i)
        val x = r.x.getValue(i)
        val tau = r.tau.getValue(i)
        val th = r.theta.getValue(i)
        val unit = max(m, s.floor).let { it * it }
        val disc = exp(-max(ue, 0.0) / th)
        var n2 = j2
        var n3 = j3
        val sh = max(0.0, m - ell)
        if (sh != 0.0) n2 += disc * tau * sh * (2 * m + sh) / unit
        val e = max(0.0, ell - x)
        if (e != 0.0) n3 += disc * tau * e * (2 * m + e) / unit
        return n2 to n3
    }

    internal fun targetsFor(r: RuleStateR, segs: List<Seg>, mus: List<Map<String, Double>>, sched: List<Boolean>, u0: List<Double>, line: Line, preplaced: Map<String, Region>): List<Map<String, Double>> {
        val names = r.names
        val out = ArrayList<Map<String, Double>>()
        val cc = compensation(r, segs, mus, sched, u0)
        for (k in segs.indices) {
            if (!sched[k]) {
                out.add(names.associateWith { 0.0 })
                continue
            }
            val mu = mus[k]
            val tot = names.sumOf { r.p.getValue(it) * mu.getValue(it) }
            val q = if (tot > 0) {
                names.associateWith { r.p.getValue(it) * mu.getValue(it) / tot }
            } else {
                val allowed = names.filter { mu.getValue(it) > 0 }
                names.associateWith { if (it in allowed) 1.0 / allowed.size else 0.0 }
            }
            val cTot = names.sumOf { cc.getValue(it)[k] }
            val f = names.associateWith { i -> if (cTot <= 1) q.getValue(i) * (1 - cTot) + cc.getValue(i)[k] else cc.getValue(i)[k] / cTot }
            out.add(raise(f, k, segs, sched, mus, line, names))
        }
        return out
    }

    /** c_i at each segment's middle, in linear time (see the reference). */
    private fun compensation(r: RuleStateR, segs: List<Seg>, mus: List<Map<String, Double>>, sched: List<Boolean>, u0: List<Double>): Map<String, DoubleArray> {
        val names = r.names
        val n = segs.size
        val idx = (0 until n).filter { sched[it] }
        val mid = HashMap<Int, Double>()
        for (k in idx) mid[k] = u0[k] + (segs[k].b - segs[k].a) / 2
        val out = LinkedHashMap<String, DoubleArray>()
        for (i in names) {
            val lam = r.lam.getValue(i)
            val pi = r.pi.getValue(i)
            val values = idx.map { round12(mus[it].getValue(i)) }.toSet().sorted()
            val ci = DoubleArray(n)
            if (values.size > 1 && pi > 0) {
                val near = HashMap<Double, DoubleArray>()
                for (v in values) {
                    val arr = DoubleArray(n)
                    near[v] = arr
                    var acc = 0.0
                    var at: Double? = null // behind: from the left
                    for (k in idx) {
                        if (at != null) acc *= exp(-(mid.getValue(k) - at) / lam)
                        arr[k] += acc
                        val ln = segs[k].b - segs[k].a
                        acc = if (round12(mus[k].getValue(i)) == v) {
                            acc * exp(-(u0[k] + ln - mid.getValue(k)) / lam) + lam * (1 - exp(-ln / lam))
                        } else {
                            acc * exp(-(u0[k] + ln - mid.getValue(k)) / lam)
                        }
                        at = u0[k] + ln
                    }
                    acc = 0.0
                    at = null // ahead: from the right
                    for (k in idx.asReversed()) {
                        if (at != null) acc *= exp(-(at - mid.getValue(k)) / lam)
                        arr[k] += acc
                        val ln = segs[k].b - segs[k].a
                        acc = if (round12(mus[k].getValue(i)) == v) {
                            acc * exp(-(mid.getValue(k) - u0[k]) / lam) + lam * (1 - exp(-ln / lam))
                        } else {
                            acc * exp(-(mid.getValue(k) - u0[k]) / lam)
                        }
                        at = u0[k]
                    }
                }
                for (k in idx) {
                    val m = mus[k].getValue(i)
                    var sum = 0.0
                    for (v in values) if (m - v > 0) sum += (m - v) * near.getValue(v)[k]
                    ci[k] = pi / lam * sum
                }
            }
            out[i] = ci
        }
        return out
    }

    /** The quantities by a date: the target raised to the share each asks there. */
    private fun raise(f: Map<String, Double>, k: Int, segs: List<Seg>, sched: List<Boolean>, mus: List<Map<String, Double>>, line: Line, names: List<String>): Map<String, Double> {
        val x = (segs[k].a + segs[k].b) / 2
        val g = LinkedHashMap<String, Double>()
        for (i in names) {
            for ((qq, a, b) in tasks.getValue(i).quantities) {
                if (!(a.toDoubleValue() <= x && x < b.toDoubleValue())) continue
                val served = line.presence(i).measureOn(IntervalSet.of(Interval.of(Pos(a), Pos(exactRational(x)), false, true)))
                val missing = max(0.0, qq.toDoubleValue() - served.toDoubleValue())
                var avail = 0.0
                for ((j, sg) in segs.withIndex()) {
                    if (sched[j] && mus[j].getValue(i) > 0 && sg.b > x && sg.a < b.toDoubleValue()) avail += min(sg.b, b.toDoubleValue()) - max(sg.a, x)
                }
                if (avail > 0) g[i] = max(g[i] ?: 0.0, min(1.0, missing / avail))
            }
        }
        val raised = g.filter { (i, v) -> v > f.getValue(i) }
        if (raised.isEmpty()) return f
        val total = raised.values.sum()
        if (total >= 1) return names.associateWith { if (it in raised) raised.getValue(it) / total else 0.0 }
        val rest = names.filter { it !in raised }.sumOf { f.getValue(it) }
        val scale = if (rest > 0) (1 - total) / rest else 0.0
        return names.associateWith { if (it in raised) raised.getValue(it) else f.getValue(it) * scale }
    }

    /** The lag of task i at [lo]: its own presence and the schedulable clock from c - reach, at its nominal share. */
    fun farLag(r: RuleStateR, i: String, line: Line, preplaced: Map<String, Region>, lo: Double, c: Double, reach: Double): Double {
        val start = c - reach
        if (start >= lo) return 0.0
        val tau = r.tau.getValue(i)
        val pi = r.pi.getValue(i)
        val pres = line.presence(i).on(Pos(exactRational(start)), Pos(exactRational(lo))).intervals
        val key = listOf(i, start, lo, tau, pi, pres.map { it.lo to it.hi })
        farCache[key]?.let { return it }
        if (farCache.size > 4096) farCache.clear()
        val blocked = nobody(line)
        fun du(a: Double, b: Double): Double {
            if (b <= a) return 0.0
            val w = IntervalSet.of(Interval.of(Pos(exactRational(a)), Pos(exactRational(b)), false, true))
            return (exactRational(b) - exactRational(a) - blocked.measureOn(w)).toDoubleValue()
        }
        var lag = 0.0
        var x = start
        for (iv in pres) {
            val a = max(iv.lo.x.toDoubleValue(), start)
            val b = min(iv.hi.x.toDoubleValue(), lo)
            if (b <= a) continue
            lag = -pi * tau + (lag + pi * tau) * exp(-du(x, a) / tau) // (not served)
            lag = (1 - pi) * tau + (lag - (1 - pi) * tau) * exp(-du(a, b) / tau) // (served)
            x = b
        }
        val out = -pi * tau + (lag + pi * tau) * exp(-du(x, lo) / tau)
        farCache[key] = out
        return out
    }

    /** Where no task may be by its resilience: not on the schedulable clock. */
    fun nobody(line: Line): Region {
        val kinds = tasks.values.flatMap { t -> t.resilience.filter { it.value == 0.0 }.keys }.toSet().sorted()
        val regions = kinds.map { line.presence(it) }
        val memo = nobodyMemo
        if (memo != null && memo.first.size == regions.size && memo.first.indices.all { memo.first[it] === regions[it] || memo.first[it] == regions[it] }) return memo.second
        var out: Region? = null
        for (t in tasks.values) {
            var mine = Region.EMPTY
            for ((k, rr) in t.resilience) if (rr == 0.0) mine = mine or line.presence(k)
            out = if (out == null) mine else out and mine
        }
        val res = out ?: Region.EMPTY
        nobodyMemo = regions to res
        return res
    }

    /** D: what each quantity misses at its date. */
    fun shortfall(line: Line, c: Rational, end: Rational, preplaced: Map<String, Region> = emptyMap()): Double {
        var d = 0.0
        for ((i, t) in tasks) {
            for ((qq, a, b) in t.quantities) {
                if (b <= c) continue
                val upto = minOf(b, end)
                val served = line.presence(i).measureOn(IntervalSet.of(Interval.of(Pos(a), Pos(upto), false, true)))
                var missing = max(0.0, qq.toDoubleValue() - served.toDoubleValue())
                if (b > end) missing = max(0.0, missing - (b - end).toDoubleValue())
                d += missing
            }
        }
        return d
    }

    companion object {
        /** Criterion 1 past `end` for a lag L there, the task served at its target from then on. */
        fun tail(l: Double, tau: Double, th: Double, ue: Double): Double = exp(-ue / th) * l * l / (1 / th + 2 / tau)

        /** ∫_0^h e^{-(v0 + v)/Th} (A + B e^{-v/tau})^2 dv. */
        fun sqIntegral(a: Double, b: Double, tau: Double, th: Double, v0: Double, h: Double): Double {
            fun ex(rate: Double) = if (rate == 0.0) h else (1 - exp(-rate * h)) / rate
            val base = exp(-v0 / th)
            return base * (a * a * ex(1 / th) + 2 * a * b * ex(1 / th + 1 / tau) + b * b * ex(1 / th + 2 / tau))
        }

        fun uAt(segs: List<Seg>, sched: List<Boolean>, u0: List<Double>, x: Double): Double {
            for ((k, sg) in segs.withIndex()) if (sg.a <= x && x <= sg.b) return u0[k] + (if (sched[k]) x - sg.a else 0.0)
            return u0.lastOrNull() ?: 0.0
        }
    }
}

/**
 * `Fraction(d).limit_denominator(maxDen)`. A float under about 2^-9 has an exact fraction whose denominator no `Long`
 * holds (CPython's integers are unbounded): there the closest p/q with q <= [maxDen] is found by trying every q, an
 * exact tie (which CPython settles by its bounds) left to the smaller denominator.
 */
fun limitDenominatorOf(d: Double, maxDen: Long): Rational {
    val exact = try {
        exactRational(d)
    } catch (e: ArithmeticException) {
        null
    }
    if (exact != null) return exact.limitDenominator(maxDen)
    require(kotlin.math.abs(d) < 1.0) { "too large for a Long fraction: $d" }
    var best = Rational.ZERO
    var off = kotlin.math.abs(d)
    for (q in 1..maxDen) {
        val p = kotlin.math.round(d * q).toLong()
        val e = kotlin.math.abs(p.toDouble() / q - d)
        if (e < off) {
            off = e
            best = Rational.of(p, q)
        }
    }
    return best
}

/** CPython's `Fraction.limit_denominator`: the closest fraction with a denominator at most [maxDen]. */
fun Rational.limitDenominator(maxDen: Long): Rational {
    if (den <= maxDen) return this
    var p0 = 0L
    var q0 = 1L
    var p1 = 1L
    var q1 = 0L
    var n = num
    var d = den
    while (true) {
        val a = n.floorDiv(d)
        if (q1 != 0L && a > (maxDen - q0) / q1) break // (q0 + a q1 > maxDen, without overflowing)
        val q2 = q0 + a * q1
        val np1 = p0 + a * p1
        p0 = p1
        q0 = q1
        p1 = np1
        q1 = q2
        val nd = n - a * d
        n = d
        d = nd
    }
    val k = (maxDen - q0).floorDiv(q1)
    val bound1 = Rational.of(p0 + k * p1, q0 + k * q1)
    val bound2 = Rational.of(p1, q1)
    // |b2 - x| <= |b1 - x|, exactly: |p2 d - n q2| q1 <= |p1 d - n q1| q2 (Python's unbounded integers; a Long overflows)
    fun off(b: Rational) = (BigInt.of(b.num) * BigInt.of(den) - BigInt.of(num) * BigInt.of(b.den)).abs()
    val lhs = off(bound2) * BigInt.of(bound1.den)
    val rhs = off(bound1) * BigInt.of(bound2.den)
    return if (lhs <= rhs) bound2 else bound1
}

/** Just enough of an unbounded integer for an exact comparison: sign and magnitude in base 2^16, little end first. */
internal class BigInt private constructor(private val negative: Boolean, private val mag: IntArray) : Comparable<BigInt> {
    private val isZero get() = mag.isEmpty()

    operator fun times(o: BigInt): BigInt {
        if (isZero || o.isZero) return ZERO
        val out = LongArray(mag.size + o.mag.size + 1)
        for (i in mag.indices) for (j in o.mag.indices) out[i + j] += mag[i].toLong() * o.mag[j]
        return BigInt(negative != o.negative, normalize(out))
    }

    operator fun minus(o: BigInt): BigInt = this + BigInt(!o.negative && !o.isZero, o.mag)

    operator fun plus(o: BigInt): BigInt {
        if (o.isZero) return this
        if (isZero) return o
        if (negative == o.negative) {
            val out = LongArray(maxOf(mag.size, o.mag.size) + 1)
            for (i in mag.indices) out[i] += mag[i].toLong()
            for (i in o.mag.indices) out[i] += o.mag[i].toLong()
            return BigInt(negative, normalize(out))
        }
        val c = compareMag(mag, o.mag)
        if (c == 0) return ZERO
        val (big, small, neg) = if (c > 0) Triple(mag, o.mag, negative) else Triple(o.mag, mag, o.negative)
        val out = LongArray(big.size)
        var borrow = 0L
        for (i in big.indices) {
            var v = big[i].toLong() - borrow - (if (i < small.size) small[i].toLong() else 0L)
            borrow = 0L
            if (v < 0) {
                v += BASE
                borrow = 1L
            }
            out[i] = v
        }
        return BigInt(neg, normalize(out))
    }

    fun abs(): BigInt = if (negative) BigInt(false, mag) else this

    override fun compareTo(other: BigInt): Int {
        if (negative != other.negative) return if (negative) -1 else 1
        val c = compareMag(mag, other.mag)
        return if (negative) -c else c
    }

    companion object {
        private const val BASE = 1L shl 16
        val ZERO = BigInt(false, IntArray(0))

        fun of(v: Long): BigInt {
            if (v == 0L) return ZERO
            val out = ArrayList<Int>()
            var u = v.toULong().let { if (v < 0) (0UL - it) else it } // (Long.MIN_VALUE's magnitude too)
            while (u != 0UL) {
                out.add((u and 0xffffUL).toInt())
                u = u shr 16
            }
            return BigInt(v < 0, out.toIntArray())
        }

        private fun normalize(a: LongArray): IntArray {
            var carry = 0L
            for (i in a.indices) {
                val v = a[i] + carry
                a[i] = v and (BASE - 1)
                carry = v ushr 16
            }
            require(carry == 0L)
            var n = a.size
            while (n > 0 && a[n - 1] == 0L) n--
            return IntArray(n) { a[it].toInt() }
        }

        private fun compareMag(a: IntArray, b: IntArray): Int {
            if (a.size != b.size) return a.size.compareTo(b.size)
            for (i in a.indices.reversed()) if (a[i] != b[i]) return a[i].compareTo(b[i])
            return 0
        }
    }
}

/** The score as a problem of the rules generator: its error is J on (c, c + H], its shortfall D comes first. */
class ScoreProblem(val score: Score, override val window: Rational? = null) : OptProblem {
    override val name = "score"
    private var memo: Triple<LineState, Pos, ScoreResult>? = null
    private var lagsMemo: Triple<LineState, Rational, Map<String, Double>>? = null
    var searchMemo: Triple<LineState, Rational, List<Op>>? = null

    override fun withWindow(h: Rational) = ScoreProblem(score, h)

    override val lengths: Map<String, Rational>
        get() = score.tasks.mapValues { (_, t) -> limitDenominatorOf(max(t.minimum.at(Rational.ZERO), 1.0), 1000) }

    override fun hasTargets() = score.tasks.isNotEmpty()

    fun preplaced(s: LineState): Map<String, Region> = score.tasks.keys.associateWith { s.claims.owned(it, Owner.USER) }

    private fun result(ctx: ProblemContext, s: LineState): ScoreResult {
        val m = memo
        if (m != null && m.first === s && m.second == ctx.c) return m.third
        val r = score.evaluate(s.line, ctx.c.x, ctx.c.x + window!!, preplaced(s))
        memo = Triple(s, ctx.c, r)
        return r
    }

    override fun error(ctx: ProblemContext, s: LineState): Num = Num.Real(result(ctx, s).j)

    override fun shortfall(ctx: ProblemContext, s: LineState): Num = Num.Real(result(ctx, s).d)

    override fun extra(ctx: ProblemContext): List<Pair<String, List<Op>>> = emptyList()

    override fun proposals(ctx: ProblemContext, pred: String, strategy: String): List<Op> {
        if (strategy == "spread4") return searched(ctx) // (the first strategy: the search over the whole continuation)
        val factor = FACTORS[strategy] ?: 1.0
        val s = ctx.state
        val c = ctx.c.x
        val end = c + window!!
        val w = Region.of(Interval.of(Pos(c), Pos(end), false, true))
        val sc = score
        val r = sc.ruleState(c)
        val names = r.names
        val pre = preplaced(s)
        var taken = Region.EMPTY
        for (x in pre.values) taken = taken or x
        val lm = lagsMemo
        val lags = if (lm != null && lm.first === s && lm.second == c) lm.third else lagsAt(s.line, c, pre).also { lagsMemo = Triple(s, c, it) }
        var lag: Map<String, Double> = LinkedHashMap(lags)
        val no = LinkedHashMap<String, Region>()
        for (i in names) {
            var x = forbidden(ctx, i) or taken
            for ((k, rr) in sc.tasks.getValue(i).resilience) if (rr == 0.0) x = x or s.line.presence(k)
            no[i] = x
        }
        val out = names.associateWith { ArrayList<Pair<Rational, Rational>>() }
        var t = c
        val eps = Rational.of(1, 1000)
        for (step in 0 until 10000) {
            if (t >= end) break
            val ok = names.filter { r.pi.getValue(it) > 0 && !no.getValue(it).contains(Pos(t + eps)) }
            if (ok.isEmpty()) {
                val nxt = names.flatMap { i -> no.getValue(i).on(Pos(t), Pos(end)).intervals.filter { it.lo <= Pos(t + eps) && Pos(t + eps) <= it.hi }.map { it.hi } }
                t = (nxt.filter { it != Pos.INF && it.x > t }.map { it.x } + end).min()
                continue
            }
            val share = shares(r, s.line, out, t)
            val ends = LinkedHashMap<String, Rational>()
            for (i in ok) {
                val want = limitDenominatorOf(r.m.getValue(i) * factor, 1000)
                val stop = no.getValue(i).on(Pos(t), Pos(t + want)).intervals.map { it.lo.x }
                var b = (stop.filter { it > t } + (t + want) + end).min()
                for ((qq, qa, qb) in sc.tasks.getValue(i).quantities) {
                    if (qa <= t && t < qb && share.getValue(i) > r.pi.getValue(i)) {
                        val left = qq - served(s.line, out, i, qa, t)
                        if (left.sign > 0 && left < b - t) b = t + left
                    }
                }
                ends[i] = b
            }
            val far = ends.values.max()
            val unit = names.associateWith { max(r.m.getValue(it), sc.s.floor) }
            fun after(i: String?, b: Rational): Double {
                var cost = 0.0
                var m2: Map<String, Double> = LinkedHashMap(lag)
                var v = 0.0
                val parts = (if (i != null) listOf(i to (b - t).toDoubleValue()) else emptyList()) + listOf(null to (far - (if (i != null) b else t)).toDoubleValue())
                for ((who, h) in parts) {
                    if (h <= 0) continue
                    for (j in names) {
                        val tau = r.tau.getValue(j)
                        val aa = ((if (j == who) 1.0 else 0.0) - share.getValue(j)) * tau
                        cost += Score.sqIntegral(aa, m2.getValue(j) - aa, tau, r.theta.getValue(j), v, h) / (unit.getValue(j) * unit.getValue(j))
                    }
                    m2 = stepLags(r, share, m2, who, h)
                    v += h
                }
                return cost + names.sumOf { Score.tail(m2.getValue(it), r.tau.getValue(it), r.theta.getValue(it), v) / (unit.getValue(it) * unit.getValue(it)) }
            }
            val options = ok.map { after(it, ends.getValue(it)) to it } + (after(null, t) to null)
            val best = options.minWith(compareBy<Pair<Double, String?>>({ it.first }, { it.second ?: "" }))
            val i = best.second
            if (i == null) { // nobody: until the shortest panel would end
                val b = ends.values.min()
                lag = stepLags(r, share, lag, null, (b - t).toDoubleValue())
                t = b
                continue
            }
            val b = ends.getValue(i)
            out.getValue(i).add(t to b)
            lag = stepLags(r, share, lag, i, (b - t).toDoubleValue())
            t = b
        }
        val ops = ArrayList<Op>()
        for (i in names) {
            val mine = s.claims.owned(i, Owner.GEN) and w
            if (mine.isNotEmpty()) ops.add(Op.take(i, mine))
            if (out.getValue(i).isNotEmpty()) ops.add(Op.put(i, Region.of(IntervalSet.of(out.getValue(i).map { (a, b) -> Interval.of(Pos(a), Pos(b), false, true) }))))
        }
        return ops
    }

    /** The search's continuation as an edit, once per state and chip (`score_search.search`). */
    private fun searched(ctx: ProblemContext): List<Op> {
        val s = ctx.state
        val c = ctx.c.x
        val m = searchMemo
        if (m != null && m.first === s && m.second == c) return m.third
        val w = Region.of(Interval.of(Pos(c), Pos(c + window!!), false, true))
        val found = ScoreSearch.search(this, ctx, s, SEARCH_TIME)
        val ops = ArrayList<Op>()
        for (i in score.tasks.keys.sorted()) {
            val mine = s.claims.owned(i, Owner.GEN) and w
            if (mine.isNotEmpty()) ops.add(Op.take(i, mine))
            val runs = found[i]
            if (!runs.isNullOrEmpty()) ops.add(Op.put(i, Region.of(IntervalSet.of(runs.map { (a, b) -> Interval.of(Pos(a), Pos(b), false, true) }))))
        }
        searchMemo = Triple(s, c, ops)
        return ops
    }

    /** The nominal shares, raised where a quantity by a date asks more. */
    private fun shares(r: RuleStateR, line: Line, out: Map<String, List<Pair<Rational, Rational>>>, t: Rational): Map<String, Double> {
        val share = LinkedHashMap(r.pi)
        val g = LinkedHashMap<String, Double>()
        for ((i, task) in score.tasks) {
            for ((qq, a, b) in task.quantities) {
                if (a <= t && t < b) {
                    val missing = maxOf(Rational.ZERO, qq - served(line, out, i, a, t))
                    g[i] = max(g[i] ?: 0.0, min(1.0, (missing / (b - t)).toDoubleValue()))
                }
            }
        }
        val raised = g.filter { (i, v) -> v > share.getValue(i) }
        if (raised.isEmpty()) return share
        val total = raised.values.sum()
        val rest = share.filter { it.key !in raised }.values.sum()
        val scale = if (rest > 0) max(0.0, 1 - total) / rest else 0.0
        return share.mapValues { (i, v) -> if (i in raised) raised.getValue(i) else v * scale }
    }

    /** Each task's lag at c, under the rule state at c. */
    fun lagsAt(line: Line, c: Rational, preplaced: Map<String, Region>): Map<String, Double> {
        val sc = score
        val r = sc.ruleState(c)
        val names = r.names
        val reach = names.associateWith { 4 * r.lam.getValue(it) }
        val full = sc.historySpan()
        val cf = c.toDoubleValue()
        val lo = cf - full
        val segs = sc.segments(line, preplaced, lo, cf + full, r.lam.values.min() / 16)
        val mus = segs.map { sc.mu(it) }
        val sched = mus.map { mu -> mu.values.any { it > 0 } }
        val u0 = ArrayList<Double>()
        var u = 0.0
        for ((x, ok) in segs.zip(sched)) {
            u0.add(u)
            u += if (ok) x.b - x.a else 0.0
        }
        val targets = sc.targetsFor(r, segs, mus, sched, u0, line, preplaced)
        val lag = names.associateWith { sc.farLag(r, it, line, preplaced, lo, cf, reach.getValue(it)) }.toMutableMap()
        for ((k, x) in segs.withIndex()) {
            if (!sched[k] || x.a >= cf) continue
            val h = min(x.b, cf) - x.a
            for (i in names) {
                val tau = r.tau.getValue(i)
                val aa = ((if (i in x.on) 1.0 else 0.0) - targets[k].getValue(i)) * tau
                lag[i] = aa + (lag.getValue(i) - aa) * exp(-h / tau)
            }
        }
        return lag
    }

    companion object {
        val FACTORS = mapOf("left" to 1.0, "right" to 2.0, "spread2" to 1.5, "spread4" to 1.0, "spread8" to 1.0, "spread16" to 1.0)

        /** Seconds of the whole-continuation search per run (`score_search.py`). */
        const val SEARCH_TIME = 4.0

        /** The lags after h of task i (null: nobody), each at its share. */
        fun stepLags(r: RuleStateR, share: Map<String, Double>, lag: Map<String, Double>, i: String?, h: Double): Map<String, Double> {
            val out = LinkedHashMap<String, Double>()
            for ((j, v) in lag) {
                val tau = r.tau.getValue(j)
                val aa = ((if (j == i) 1.0 else 0.0) - share.getValue(j)) * tau
                out[j] = aa + (v - aa) * exp(-h / tau)
            }
            return out
        }

        /** What task i has on [a, t): on the line before the chip, and what the fill put. */
        fun served(line: Line, out: Map<String, List<Pair<Rational, Rational>>>, i: String, a: Rational, t: Rational): Rational {
            var got = Rational.ZERO
            for ((x, y) in out.getValue(i)) got += maxOf(Rational.ZERO, minOf(y, t) - maxOf(x, a))
            val first = out.getValue(i).minOfOrNull { it.first } ?: t
            if (first > a && a < minOf(first, t)) got += line.presence(i).measureOn(IntervalSet.of(Interval.of(Pos(a), Pos(minOf(first, t)), false, true)))
            return got
        }
    }
}
