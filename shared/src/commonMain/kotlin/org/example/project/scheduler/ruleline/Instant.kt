package org.example.project.scheduler.ruleline

import kotlin.math.max

/**
 * The instant schedule — a port of `C:\code\line_manager\scripts\instant.py` (`docs/scheduler/scheduler_requirements.md`:
 * "Instantaneous scheduler. When it runs, it instantly gives a schedule for [now, now + 10s] and all the currently
 * displayed parts of the timeline. The schedule must satisfy all the constraints, and must not appear obviously
 * wrong regarding the optimization goals").
 *
 * For any window, at once: the free time filled panel after panel by stride scheduling at the local shares, a task's
 * minimum as far as it may go on, no idle time a task could have; then a rolling pass on parts of the day by the
 * score's own stride with the rules the instant schedule keeps. The future is cut into days on a fixed grid, each
 * filled from its own start: looking at t, away and back shows the same. Nothing in it reads a clock.
 */
class Instant(val rules: RuleSet, val score: Score, constraints: List<Constraint> = emptyList()) {
    val tasks: List<String> = score.tasks.keys.sorted()
    val periods: List<String> = score.tasks.values.flatMap { it.resilience.keys }.toSet().sorted()
    private val days = LinkedHashMap<Pair<Rational, Rational>, Pair<List<Any>, Map<String, List<Pair<Rational, Rational>>>>>()
    private val rolls = LinkedHashMap<Pair<Rational, Rational>, Pair<List<Any>, Map<String, List<Pair<Rational, Rational>>>>>()

    /** The rolling pass; false while the user drags (the fill alone). */
    var refine = true
    val constraints: List<Constraint> = constraints.toList()
    private var placedMemo: List<Any?>? = null

    init {
        for (k in this.constraints) {
            k.bind(rules)
            k.bindAll(this.constraints)
        }
    }

    /**
     * The state with each constraint that places pieces placed from the chip, forever (or up to [until] when near),
     * in the constraints' order, written straight into the line with the kinds its pieces come with: no solving.
     */
    fun placed(state: LineState, chip: Rational, until0: Rational? = null): LineState {
        val until = if (until0 == null || until0 - chip > Rational.of(NEAR)) null else until0
        val got = placedMemo
        if (got != null && got[0] === state.line && got[1] === state.claims && got[2] == chip) {
            val gu = got[4] as Rational?
            if (gu == null || (until != null && until <= gu)) return got[3] as LineState
        }
        var cur = state
        val upto = Region.of(Interval.of(Pos.NEG_INF, Pos(chip), false, true))
        for (k in constraints) {
            if (k !is AsEarlyAsPossible || k.length == null) continue
            val pieces = (cur.line.presence(k.kind) and upto) or k.place(cur, Pos(chip), until = until?.let { Pos(it) })
            val vals = LinkedHashMap(cur.line.vals)
            for (kind in withKinds(k)) {
                if (kind == k.kind) {
                    vals[kind] = mapOf(HUNDRED to pieces)
                } else {
                    val old = vals[kind]?.get(HUNDRED)
                    val d = LinkedHashMap(vals[kind].orEmpty())
                    d[HUNDRED] = if (old == null) pieces else old or pieces
                    vals[kind] = d
                }
            }
            cur = LineState(cur.claims, Line(vals), cur.history)
        }
        placedMemo = listOf(state.line, state.claims, chip, cur, until)
        return cur
    }

    /** Its kind and those it comes with at once (`AsEarlyAsPossible._with`). */
    private fun withKinds(k: AsEarlyAsPossible): Set<String> = setOf(k.kind) + rules.order.filter { it != k.kind && k.counts(MinimumGap(k.kind, Rational.ONE, after = it)) }

    /** The kinds [placed] writes. */
    fun placedKinds(): Set<String> = constraints.filterIsInstance<AsEarlyAsPossible>().filter { it.length != null }.flatMap { withKinds(it) }.toSet()

    /** {task: [(start, end), ...]} on [a, b): the instant schedule, the user's tasks left out, nothing before [start]. */
    fun schedule(state: LineState, a: Rational, b: Rational, start: Rational? = null): Map<String, List<Pair<Rational, Rational>>> {
        val out = LinkedHashMap<String, MutableList<Pair<Rational, Rational>>>()
        val day = Rational.of(DAY)
        var k = day * (maxOf(a, start ?: a) / day).floor()
        while (k < b) {
            val lo = if (start != null) maxOf(k, start) else k
            val hi = k + day
            if (lo < hi) {
                for ((i, v) in dayOf(state, lo, hi)) {
                    val got = v.filter { (x, y) -> y > a && x < b }.map { (x, y) -> maxOf(x, a) to minOf(y, b) }
                    if (got.isNotEmpty()) out.getOrPut(i) { ArrayList() }.addAll(got)
                }
            }
            k += day
        }
        return out
    }

    /** One day's instant schedule: the rare tasks at their due dates, then the others. */
    private fun dayOf(state: LineState, lo: Rational, hi: Rational): Map<String, List<Pair<Rational, Rational>>> {
        val inputs = listOf<Any>(state.line, state.claims, rules, score)
        val got = days[lo to hi]
        if (got != null && got.first.indices.all { got.first[it] === inputs[it] }) return got.second
        val rareOnes = rareOf(state, lo, hi)
        var day: Map<String, List<Pair<Rational, Rational>>> = fill(state, lo, hi, rareOnes)
        if (refine && rare(score.ruleState(lo)).isEmpty()) day = rolled(state, lo, hi, day)
        val out = LinkedHashMap<String, MutableList<Pair<Rational, Rational>>>()
        for ((i, v) in day) out[i] = v.toMutableList()
        for ((i, v) in rareOnes) out.getOrPut(i) { ArrayList() }.addAll(v)
        if (days.size >= CACHE) days.remove(days.keys.first())
        days[lo to hi] = inputs to out
        return out
    }

    private fun rolled(state: LineState, lo: Rational, hi: Rational, fill: Map<String, List<Pair<Rational, Rational>>>): Map<String, List<Pair<Rational, Rational>>> {
        val fillKey = fill.entries.sortedBy { it.key }.map { it.key to it.value }
        val got = rolls[lo to hi]
        if (got != null && got.first[0] == fillKey && got.first[1] === rules && got.first[2] === score) return got.second
        val out = roll(state, lo, hi, fill)
        if (rolls.size >= ROLLED) rolls.remove(rolls.keys.first())
        rolls[lo to hi] = listOf<Any>(fillKey, rules, score) to out
        return out
    }

    private fun roll(state: LineState, lo: Rational, hi: Rational, fill: Map<String, List<Pair<Rational, Rational>>>): Map<String, List<Pair<Rational, Rational>>> {
        val day = Region.of(Interval.of(Pos(lo), Pos(hi), false, true))
        var base: Map<String, Map<Rational, Region>> = LinkedHashMap(state.line.vals).also { vals ->
            for (i in tasks) {
                val d = vals[i] ?: continue
                val gen = state.claims.owned(i, Owner.GEN) and day
                vals[i] = d.mapValues { it.value - gen }
            }
        }
        val out = LinkedHashMap<String, MutableList<Pair<Rational, Rational>>>()
        var t = lo
        val part = Rational.of(PART)
        val look = Rational.of(LOOK)
        val starts = tasks.flatMap { i -> state.claims.owned(i, Owner.USER).on(Pos(lo), Pos(hi)).intervals.map { it.lo.x } }.sorted()
        while (t < hi) {
            var e = minOf(t + part, hi)
            val near = starts.filter { t + part / 4 < it && it <= t + part * 5 / 4 && it < hi }
            if (near.isNotEmpty()) e = near[0]
            val w = minOf(e + look, hi + look)
            val cur = LineState(state.claims, Line(base), state.history)
            val partFill = LinkedHashMap<String, List<Pair<Rational, Rational>>>()
            for ((i, v) in fill) {
                val got = v.filter { (x, y) -> y > t && x < w }.map { (x, y) -> maxOf(x, t) to minOf(y, w) }
                if (got.isNotEmpty()) partFill[i] = got
            }
            var pick: Map<String, List<Pair<Rational, Rational>>> = partFill
            val tt = t
            val m = try {
                ScoreModel(ScoreProblem(score, w - t), object : ProblemContext {
                    override val c = Pos(tt)
                    override val state = cur
                    override val rules = this@Instant.rules
                    override fun expected(s: LineState, kind: String, weights: Any?, w: IntervalSet) = s.line.integral(kind, w)
                }, cur, 60.0)
            } catch (ex: Exception) {
                null
            }
            if (m != null && m.bigU > 0) {
                var best: Pair<Pair<Double, Double>, List<ScoreRun>>? = null
                val tries = STRIDES.map { m.ruled(it) } + listOf(m.fromWall(partFill.mapValues { (_, v) -> v.map { (a, b) -> a.toDoubleValue() to b.toDoubleValue() } }))
                for (r in tries) {
                    val c = m.cost(r)
                    val b = best
                    if (b == null || compareValuesBy(c, b.first, { it.first }, { it.second }) < 0) best = c to r
                }
                pick = m.toWall(best!!.second)
            }
            for ((i, v0) in pick) {
                val v = v0.filter { (x, y) -> y > t && x < e }.map { (x, y) -> maxOf(x, t) to minOf(y, e) }.filter { (x, y) -> y > x }
                if (v.isEmpty()) continue
                out.getOrPut(i) { ArrayList() }.addAll(v)
                val r = Region.of(IntervalSet.of(v.map { (x, y) -> Interval.of(Pos(x), Pos(y), false, true) }))
                val d = LinkedHashMap(base[i].orEmpty())
                d[HUNDRED] = d[HUNDRED]?.let { it or r } ?: r
                base = LinkedHashMap(base).also { it[i] = d }
            }
            t = e
        }
        for ((i, v) in out) { // (neighbours of a task across a part's end: one panel)
            v.sortWith(compareBy({ it.first }, { it.second }))
            val merged = arrayListOf(v[0])
            for ((x, y) in v.drop(1)) {
                if (x <= merged.last().second) merged[merged.size - 1] = merged.last().first to maxOf(merged.last().second, y) else merged.add(x to y)
            }
            out[i] = merged
        }
        return out
    }

    fun rare(r: RuleStateR): List<String> = tasks.filter { r.pi.getValue(it) > 0 && r.tau.getValue(it) > RARE }

    /** The rare tasks in [lo, hi): each at its due dates, at the first place from there it may have for its minimum. */
    private fun rareOf(state: LineState, lo: Rational, hi: Rational): Map<String, List<Pair<Rational, Rational>>> {
        val r = score.ruleState(lo)
        val out = LinkedHashMap<String, MutableList<Pair<Rational, Rational>>>()
        for (i in rare(r)) {
            val tau = limitDenominatorOf(r.tau.getValue(i), 1000)
            val m = limitDenominatorOf(r.m.getValue(i), 1000)
            val before = state.line.presence(i) and Region.of(Interval.of(Pos.NEG_INF, Pos(lo), false, false))
            var last: Rational? = null
            if (before.isNotEmpty()) {
                val e = before.sup().x
                val piece = before.on(Pos(e - tau * 10 - Rational.ONE), Pos(e + Rational.ONE)).intervals
                last = if (piece.isNotEmpty()) piece.last().lo.x else e
            }
            val base = last ?: Rational.ZERO
            val ceilDiv = -((-(lo - base)) / tau).floor()
            val n = if (last != null) maxOf(1L, ceilDiv) else maxOf(0L, ceilDiv)
            var due = base + tau * Rational.of(n)
            var no = forbiddenFor(state, i, lo, hi) or state.claims.owned(i, Owner.USER).on(Pos(lo), Pos(hi))
            for (j in tasks) if (j != i) no = no or state.claims.owned(j, Owner.USER).on(Pos(lo), Pos(hi))
            while (due < hi) {
                var t = due
                for (step in 0 until 200) {
                    val block = if (t < hi) (no and IntervalSet.of(Interval.of(Pos(t), Pos(minOf(t + m, hi)), false, true))).intervals else emptyList()
                    if (block.isEmpty()) break
                    t = block[0].hi.x
                }
                if (t < hi) out.getOrPut(i) { ArrayList() }.add(t to minOf(t + m, hi))
                due += tau
            }
        }
        return out
    }

    /** Where task i may not be in [a, b): resilience 0, a rule "X → no i" (X not a task). */
    private fun forbiddenFor(state: LineState, i: String, a: Rational, b: Rational): IntervalSet {
        var out = IntervalSet.EMPTY
        for ((k, rr) in score.tasks.getValue(i).resilience) if (rr == 0.0) out = out or state.line.presence(k).on(Pos(a), Pos(b))
        for (rule in rules.element) {
            val kinds = rule.lhs.kinds()
            val lhs = rule.lhs
            if (kinds.size != 1 || kinds[0] in score.tasks || !(lhs is Atom && lhs.op == "present")) continue
            for (it in rule.rhs) {
                val o = it.options[0]
                if (!it.isOr && o.negated && o.kind == i && o.where.isPoint) out = out or state.line.presence(kinds[0]).on(Pos(a), Pos(b))
            }
        }
        return out
    }

    /** The tasks but the rare ones on [a, b), filled from a. */
    private fun fill(state: LineState, a: Rational, b: Rational, rareOnes: Map<String, List<Pair<Rational, Rational>>>): Map<String, List<Pair<Rational, Rational>>> {
        val r = score.ruleState(a)
        val names = tasks
        val rareNames = rare(r).toSet()
        val user = names.associateWith { state.claims.owned(it, Owner.USER).on(Pos(a), Pos(b)) }.toMutableMap()
        for ((i, v) in rareOnes) user[i] = user.getValue(i) or IntervalSet.of(v.map { (x, y) -> Interval.of(Pos(x), Pos(y), false, true) })
        val no = names.associateWith { forbiddenFor(state, it, a, b) }
        val pres = periods.associateWith { state.line.presence(it).on(Pos(a), Pos(b)) }
        val lag = lags(state, a, r)
        val out = names.associateWith { ArrayList<Pair<Rational, Rational>>() }
        val cutSet = HashSet<Rational>()
        cutSet.add(a)
        cutSet.add(b)
        for (s in user.values + no.values + pres.values) for (iv in s.intervals) for (x in listOf(iv.lo, iv.hi)) {
            if (x.isFinite && a < x.x && x.x < b) cutSet.add(x.x)
        }
        val cuts = cutSet.sorted()
        var t = a
        var guard = 0
        var run: Pair<String?, Rational> = null to a
        for (i in names) {
            val back = state.line.presence(i).on(Pos(a - Rational.of(DAY_X)), Pos(a))
            val ivs = back.intervals.filter { it.hi.x >= a }
            if (ivs.isNotEmpty()) run = i to ivs.last().lo.x
        }
        while (t < b && guard < 100000) {
            guard++
            val nxt = cuts.firstOrNull { it > t } ?: b
            val mid = (t + nxt) / 2
            val mine = names.firstOrNull { has(user.getValue(it), mid) }
            if (mine != null) { // the user's: served, nothing placed
                serve(lag, r, mine, (nxt - t).toDoubleValue(), shares(r, pres, no, mid, names))
                if (run.first != mine) run = mine to t
                t = nxt
                continue
            }
            var allowed = names.filter { r.pi.getValue(it) > 0 && it !in rareNames && !has(no.getValue(it), mid) }
            if (allowed.isEmpty()) { // nobody may be here (a night): not on the clock
                run = null to nxt
                t = nxt
                continue
            }
            val xs = allowed.associateWith { if (r.x.getValue(it) != Double.POSITIVE_INFINITY) limitDenominatorOf(r.x.getValue(it), 1000) else null }
            val longer = allowed.filter { i -> !(run.first == i && xs[i] != null && t - run.second >= xs.getValue(i)!!) }
            allowed = longer.ifEmpty { allowed }
            val share = shares(r, pres, no, mid, names)
            val room = allowed.associateWith { roomOf(t, b, it, no, user, names) - t }
            val due = allowed.sortedWith(compareBy<String>({ i -> (lag.getValue(i) + r.m.getValue(i) / 2) / max(share.getValue(i), 1e-12) }, { it }))
            val ms = allowed.associateWith { limitDenominatorOf(r.m.getValue(it), 1000) }
            val (e, xk) = nextUser(t, b, user, names)
            val p = names.firstOrNull { j -> user.getValue(j).intervals.any { it.hi.x == t } }
            val least = ms.values.min()
            fun touches(i: String): Boolean {
                if (i == p) return true
                return xk == i && room.getValue(i) == e - t && e - (t + minOf(room.getValue(i), ms.getValue(i))) < least
            }
            val pick = due.filter { !touches(it) }.ifEmpty { due }
            val fits = pick.filter { room.getValue(it) >= ms.getValue(it) }
            val i = if (fits.isNotEmpty()) fits[0] else pick[0]
            var h = if (fits.isNotEmpty()) minOf(room.getValue(i), ms.getValue(i)) else room.getValue(i)
            if (xk != null && xk != i && room.getValue(i) == e - t && (e - (t + h)).sign > 0 && e - (t + h) < least) h = e - t
            val began = if (run.first == i) run.second else t
            val xi = xs.getValue(i)
            if (xi != null && h > xi - (t - began) && (xi - (t - began)).sign > 0) h = xi - (t - began)
            if (h.sign <= 0) {
                t = nxt
                continue
            }
            out.getValue(i).add(t to (t + h))
            serve(lag, r, i, h.toDoubleValue(), share)
            run = i to began
            t += h
        }
        return out.filter { it.value.isNotEmpty() }
    }

    private fun roomOf(t: Rational, b: Rational, i: String, no: Map<String, IntervalSet>, user: Map<String, IntervalSet>, names: List<String>): Rational {
        var end = b
        for (s in listOf(no.getValue(i)) + names.map { user.getValue(it) }) {
            for (iv in s.intervals) {
                val lo = iv.lo.x
                if (lo > t) {
                    end = minOf(end, lo)
                    break
                }
            }
        }
        return end
    }

    private fun nextUser(t: Rational, b: Rational, user: Map<String, IntervalSet>, names: List<String>): Pair<Rational, String?> {
        var e = b
        var x: String? = null
        for (j in names) {
            for (iv in user.getValue(j).intervals) {
                val lo = iv.lo.x
                if (lo > t) {
                    if (lo < e) {
                        e = lo
                        x = j
                    }
                    break
                }
            }
        }
        return e to x
    }

    /** The local shares at x: priority times multiplier, normalized over the tasks allowed. */
    private fun shares(r: RuleStateR, pres: Map<String, IntervalSet>, no: Map<String, IntervalSet>, x: Rational, names: List<String>): Map<String, Double> {
        val mu = LinkedHashMap<String, Double>()
        for (i in names) {
            var m = if (has(no.getValue(i), x)) 0.0 else 1.0
            for ((k, rr) in score.tasks.getValue(i).resilience) if (m != 0.0 && k in pres && has(pres.getValue(k), x)) m *= rr
            mu[i] = m
        }
        val tot = names.sumOf { r.p.getValue(it) * mu.getValue(it) }
        return names.associateWith { if (tot > 0) r.p.getValue(it) * mu.getValue(it) / tot else 0.0 }
    }

    private fun serve(lag: MutableMap<String, Double>, r: RuleStateR, i: String, h: Double, share: Map<String, Double>) {
        for (j in lag.keys.toList()) lag[j] = lag.getValue(j) + (if (j == i) h else 0.0) - (share[j] ?: 0.0) * h
    }

    /** Each task's lag at a: what it had of the look back's time, minus its share of it. */
    private fun lags(state: LineState, a: Rational, r: RuleStateR): MutableMap<String, Double> {
        val lo = a - Rational.of(LOOK_BACK)
        val served = tasks.associateWith { state.line.presence(it).measureOn(IntervalSet.of(Interval.of(Pos(lo), Pos(a), false, true))).toDoubleValue() }
        val total = served.values.sum()
        return tasks.associateWith { served.getValue(it) - r.pi.getValue(it) * total }.toMutableMap()
    }

    companion object {
        const val LOOK_BACK = 7L * 86400
        const val DAY = 86400L
        const val RARE = 168.0 * 3600
        const val CACHE = 256
        const val NEAR = 7L * 86400
        const val PART = 2L * 3600
        const val LOOK = 2L * 3600
        val STRIDES = listOf(0.25, 0.5, 0.75)
        const val ROLLED = 64
        const val DAY_X = 86400L
        private val HUNDRED = Rational.of(100)

        private fun has(s: IntervalSet, x: Rational): Boolean {
            val p = Pos(x)
            for (iv in s.intervals) {
                if (iv.lo <= p && p <= iv.hi) return true
                if (iv.lo > p) return false
            }
            return false
        }
    }
}

/**
 * (the line, the claims) the calendar shows on [lo, hi): the line, its tasks after lo replaced by the instant
 * schedule there, the user's tasks kept; every kind then as the rules make it of what is shown.
 */
fun displayed(instant: Instant, line: Line, claims: Claims, history: List<Record>, lo: Rational, hi: Rational, start: Rational? = null, chip: Rational? = null): Pair<Line, Claims> {
    if (lo >= hi) return line to claims
    var state = LineState(claims, line, history)
    if (chip != null && instant.constraints.isNotEmpty()) state = instant.placed(state, chip, hi)
    val got = instant.schedule(state, lo, hi, start ?: lo)
    val after = Region.of(Interval.of(Pos(lo), Pos(hi), false, false))
    val hundred = Rational.of(100)
    val vals = LinkedHashMap(line.vals)
    val shown = claims.copy()
    for (kind in (if (chip != null) instant.placedKinds() else emptySet())) {
        val new = state.line.presence(kind) and after
        val d = LinkedHashMap<Rational, Region>()
        for ((v, r) in vals[kind].orEmpty()) d[v] = r - after
        if (new.isNotEmpty()) d[hundred] = (d[hundred] ?: Region.EMPTY) or new
        val kept = d.filter { it.value.isNotEmpty() }
        if (kept.isNotEmpty()) vals[kind] = kept else vals.remove(kind)
        val c = shown.pos[kind]
        if (c != null) for (key in c.keys.filter { it.second == Owner.GEN }) {
            val r = c.getValue(key) - after
            if (r.isEmpty()) c.remove(key) else c[key] = r
        }
        if (new.isNotEmpty() && kind in instant.constraints.filterIsInstance<AsEarlyAsPossible>().map { it.kind }) shown.addPos(kind, new, hundred, Owner.GEN)
    }
    for (i in instant.tasks) {
        val d = shown.pos[i]
        if (d != null) for (key in d.keys.filter { it.second == Owner.GEN }) {
            val r = d.getValue(key) - after
            if (r.isEmpty()) d.remove(key) else d[key] = r
        }
        val gi = got[i]
        if (!gi.isNullOrEmpty()) shown.addPos(i, Region.of(IntervalSet.of(gi.map { (a, b) -> Interval.of(Pos(a), Pos(b), false, true) })), hundred, Owner.GEN)
        val mine = claims.owned(i, Owner.USER)
        val keep = LinkedHashMap<Rational, Region>()
        for ((v, r) in vals[i].orEmpty()) keep[v] = (r - after) or (r and mine and after)
        val new = if (!gi.isNullOrEmpty()) Region.of(IntervalSet.of(gi.map { (a, b) -> Interval.of(Pos(a), Pos(b), false, true) })) else Region.EMPTY
        if (new.isNotEmpty()) keep[hundred] = (keep[hundred] ?: Region.EMPTY) or new
        val k2 = keep.filter { it.value.isNotEmpty() }
        if (k2.isNotEmpty()) vals[i] = k2 else vals.remove(i)
    }
    val solved = solvedOf(instant, shown, lo, hi)
    if (solved != null) {
        val out = LinkedHashMap(line.vals)
        for (k in out.keys + solved.vals.keys) {
            val d = LinkedHashMap<Rational, Region>()
            for ((v, r) in out[k].orEmpty()) d[v] = r - after
            for ((v, r) in solved.vals[k].orEmpty()) d[v] = d[v]?.let { it or (r and after) } ?: (r and after)
            val kept = d.filter { it.value.isNotEmpty() }
            if (kept.isNotEmpty()) out[k] = kept else out.remove(k)
        }
        return Line(out) to shown
    }
    return Line(vals) to shown
}

/** The line the rules make on [lo, hi) of what is shown there; null where they do not decide it or refuse it. */
private fun solvedOf(instant: Instant, claims: Claims, lo: Rational, hi: Rational): Line? {
    val rs = instant.rules
    val reach = rs.reach
    val w = Region.of(Interval.of(Pos(lo - reach - Rational.ONE), Pos(hi + reach + Rational.ONE), false, false))
    return try {
        solve(rs, claims.on(w).problem())
    } catch (e: Conflict) {
        null
    } catch (e: Undetermined) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
