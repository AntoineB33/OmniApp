package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational

/**
 * The sweep: the "or" choices when the positions that act on each other are not discrete — a port of
 * `C:\code\line_manager\scripts\sweep.py` (its module text says why this is the definition's result).
 *
 * At a position m the "or"s met at m are decided in their order, each taking the leftmost operand that does not
 * contradict the rules with everything on its left; from m on the same operands go on (a run) up to the first position
 * where the decision would differ. If the greedy sweep meets a position where nothing can be chosen, the positions are
 * decided one block at a time, each operand kept only if the rest can still be completed ([complete]). Beyond the data
 * the choices repeat; on an element unbounded on the left they are decided only if they do not act on each other.
 */
const val MAX_EVENTS = 3000
const val MAX_CALLS = 3000
const val PROBE_CALLS = 40

private val HUNDRED = Rational.of(100)

/** The choices: for each "or", for each operand, where it took that operand. */
typealias Choices = List<List<Region>>

private class SweepItem(val rule: Int, val lhs: Formula, val anchor: String, val ops: List<Lit>, val text: String)

private fun relTo(o: Lit, base: Region): Region = if (o.isPoint) base.shift(o.rel!!) else base.dilate(o.where)

private fun after(x: Pos): Region = Region.of(Interval.of(x, Pos.INF, false, false))

private fun upTo(x: Pos): Region = Region.of(Interval.of(Pos.NEG_INF, x, false, true))

private fun with(ch: Choices, ii: Int, oi: Int, reg: Region): Choices =
    ch.mapIndexed { i, row -> if (i != ii) row else row.mapIndexed { j, r -> if (j == oi) r or reg else r } }

private fun ends(reg: Region, lo: Pos, hi: Pos): List<Pos> = reg.on(lo, hi).intervals.flatMap { listOf(it.lo, it.hi) }

private fun views(vals: Vals): Map<String, Region> = vals.mapValues { union(it.value.values) }

private fun constantTail(pattern: IntervalSet, period: Pos): Boolean =
    pattern.isEmpty || pattern == window(Pos.ZERO, period)

/** What a probe stands for: the position, what is present just before it, whether the past placed after it. */
data class ProbeKey(val p: Pos, val atoms: List<Pair<String, Rational>>, val forward: Boolean)

class SweepShared(val probes: HashMap<ProbeKey, Boolean?> = HashMap(), var calls: Int = 0, val limit: Int = MAX_CALLS)

private data class Label(val tag: String, val a: String, val b: String)

private data class StatePart(val label: Label, val ivs: List<Interval>, val before: Boolean, val after: Boolean)

private data class MemoKey(val p: Pos, val start: Int, val ks: List<Long>, val state: List<StatePart>)

private data class FailKey(val p: Pos, val start: Int, val fixed: Any)

private sealed class Greedy {
    class Ok(val ch: Choices) : Greedy()
    class Dead(val at: Pos?, val item: Int?) : Greedy()
}

class Sweep(
    private val rs: RuleSet,
    private val prob: Problem,
    vals: Vals,
    private val fixed: Map<String, Required>,
    forbid: Map<String, Region>,
    private val kindsIn: Set<String>,
    private val probe: ProbeKey? = null,
    shared: SweepShared? = null,
) {
    private val vals0: Map<String, Map<Rational, Region>> = LinkedHashMap(vals)
    private val forbid0: Map<String, Region> = LinkedHashMap(forbid)
    private val claims: Map<String, Map<Rational, Region>>
    private val shared: SweepShared = shared ?: SweepShared()
    private val kin: List<String> = kindsOf(rs, prob).filter { it in kindsIn }
    private val itemAt: List<Int> = rs.orItems.map { it.second }
    private val items: List<SweepItem>
    private val posLits: Map<String, MutableList<Pair<Int, Lit>>>
    private val negLits = ArrayList<Pair<Int, Lit>>()
    private val lits: List<Pair<Int, Lit>>
    private val opPos: Map<String, MutableList<Triple<Int, Int, Lit>>>
    private val opNeg = ArrayList<Triple<Int, Int, Lit>>()
    private val none: Choices

    /** An "or" whose choice is one for a whole largest interval with no left end: that interval, the choice. */
    private val comp = HashMap<Int, Region>()
    private var minf: Map<Int, Int> = emptyMap()
    private val minfOpen = ArrayList<Pair<Pos, Int>>()

    /** [outward]: for each "or", where each operand cannot be chosen. */
    private var barred: MutableList<List<Region>>? = null
    private var fwd: Rational = Rational.ZERO
    private var back: Rational = Rational.ZERO
    private val deltas: Set<Pos>
    private val bigR: Rational
    private val reach: Rational
    private var inside: Region? = null
    private var edgesAfter: Region? = null
    private val staticRegions: List<Region>
    private val periodicOk: Boolean
    private var q: Rational? = null
    private val bigT: Rational
    private var staticEnds: List<Pos>

    // the search's memory
    private var memo = HashMap<MemoKey, Pair<Choices?, Pos?>>()
    private var failed = HashMap<FailKey, MutableList<Map<Label, IntervalSet>>>()
    private var dead = ArrayList<Pair<Pos, Int>>()
    private var stuck: Pos = Pos.NEG_INF
    private var first: Pos? = null
    private var g: Rational = Rational.ONE
    private var residues: List<Rational> = emptyList()

    var choices: Choices? = null
        private set

    init {
        val cl = LinkedHashMap<String, Map<Rational, Region>>()
        for (k in kindsIn) cl[k] = LinkedHashMap(prob.pos[k].orEmpty())
        if (probe != null) { // what is present at P - ε, as claims
            for ((k, v) in probe.atoms) {
                val d = LinkedHashMap(cl.getValue(k))
                d[v] = (d[v] ?: Region.EMPTY) or Region.point(probe.p - Pos.EPS)
                cl[k] = d
            }
        }
        claims = cl
        items = rs.orItems.map { (i, j) ->
            val it = rs.element[i].rhs[j]
            SweepItem(i, rs.element[i].lhs, if (it.allLits().map { o -> o.anchor }.toSet().size > 1) "p" else it.options[0].anchor, it.options, it.toString())
        }
        posLits = kin.associateWith { ArrayList() }
        for ((i, r) in rs.element.withIndex()) {
            if ((r.lhs.kinds().toSet() intersect kindsIn).isEmpty()) continue
            for (it in r.rhs) {
                if (it.isOr) continue
                val o = it.options[0]
                if (o.negated) negLits.add(i to o) else posLits.getValue(o.kind).add(i to o)
            }
        }
        lits = kin.flatMap { posLits.getValue(it) }
        opPos = kin.associateWith { ArrayList() }
        for ((ii, item) in items.withIndex()) {
            val extra = rs.element[item.rule].rhs[itemAt[ii]].extra
            for ((oi, o) in item.ops.withIndex()) {
                for (lit in listOf(o) + (if (extra.isNotEmpty()) extra[oi] else emptyList())) {
                    if (lit.negated) opNeg.add(Triple(ii, oi, lit)) else opPos[lit.kind]?.add(Triple(ii, oi, lit))
                }
            }
        }
        none = items.map { List(it.ops.size) { Region.EMPTY } }
        deltas = computeDeltas()
        bigR = (deltas.maxOfOrNull { it.x.abs() } ?: Rational.ZERO) + Rational.ONE
        reach = fwd + back + Rational.ONE
        if (probe != null) {
            inside = Region.of(Interval.of(probe.p, Pos.INF, true, false))
            edgesAfter = Region.of(Interval.of(if (probe.forward) probe.p + fwd else probe.p, Pos.INF, !probe.forward, false))
        }
        // the ends of the data (not what a probe puts at P - ε, which stands for choices)
        val sr = ArrayList<Region>()
        for (d in vals0.values) sr.addAll(d.values)
        sr.addAll(forbid0.values)
        for (k in kindsIn) sr.addAll(prob.pos[k].orEmpty().values)
        for ((p, _) in fixed.values) sr.add(p)
        for ((_, e) in fixed.values) sr.addAll(e.values)
        val srs = sr.filter { it.isNotEmpty() }.toMutableList()
        for (it in items) if ((it.lhs.kinds().toSet() intersect kindsIn).isEmpty()) {
            val m = lhsRegion(it.lhs, vals0)
            if (m.isNotEmpty()) srs.add(m)
        }
        staticRegions = srs
        var pok = staticRegions.all { constantTail(it.left, it.pl) }
        for (reg in staticRegions) {
            if (!constantTail(reg.right, reg.pr)) {
                if (reg.pr.k != 0L) pok = false else q = q?.let { flcmR(it, reg.pr.x) } ?: reg.pr.x
            }
        }
        periodicOk = pok
        val bounds = staticRegions.flatMap { listOf(it.lo.x.abs(), it.hi.x.abs()) }
        bigT = (bounds.maxOrNull() ?: Rational.ZERO) + bigR * Rational.of(2)
        staticEnds = staticRegions.flatMap { ends(it, Pos(-bigT - bigR), Pos(bigT + bigR)) }.filter { it.isFinite }.distinct().sorted()
    }

    // ------------------------------------------------------------------ the line of given choices

    /** The values of all kinds when the "or"s took these choices. Raises [Conflict] (if [check]). */
    private fun closure(ch: Choices, horizon: Pos, check: Boolean = true): Map<String, Map<Rational, Region>> =
        closureImpl(ch, horizon, check, false).first

    /** (values, where each kind is forbidden), without checking. */
    private fun closureForbids(ch: Choices, horizon: Pos): Pair<Map<String, Map<Rational, Region>>, Map<String, Region>> {
        val (v, f) = closureImpl(ch, horizon, false, true)
        return v to f!!
    }

    private fun closureImpl(ch: Choices, horizon: Pos, check: Boolean, forbids: Boolean): Pair<Map<String, Map<Rational, Region>>, Map<String, Region>?> {
        val anchors = HashMap<Int, Region>()
        val cut: Region? = when (horizon) {
            Pos.INF -> null
            Pos.NEG_INF -> Region.EMPTY
            else -> upTo(horizon)
        }
        var mask: Region? = if (probe != null) edgesAfter else null
        if (cut != null) mask = if (mask == null) cut else mask and cut
        val b = barred
        if (check && b != null && b.withIndex().any { (ii, row) -> row.withIndex().any { (oi, r) -> (r and ch[ii][oi]).isNotEmpty() } }) {
            throw Conflict("an operand barred there")
        }
        if (check && minf.any { (ii, v) -> items[ii].anchor == "p" && v == 1 && (ch[ii][0] and comp.getValue(ii)).isNotEmpty() }) {
            throw Conflict("a first operand that does not fit on an element with no left end")
        }
        val extra = LinkedHashMap<String, Required>()
        for (k in kin) {
            val f = fixed[k]
            var plain = f?.first ?: Region.EMPTY
            val exact = LinkedHashMap(f?.second.orEmpty())
            for ((ii, oi, o) in opPos.getValue(k)) {
                val t = place(ii, oi, o, took(ch, ii, oi))
                if (t.isNotEmpty()) {
                    if (o.value == null) plain = plain or t else exact[o.value] = (exact[o.value] ?: Region.EMPTY) or t
                }
            }
            extra[k] = plain to exact
        }
        val vals = LinkedHashMap(vals0)
        vals.putAll(close(rs, kin, lits, claims, vals0, extra, check && !forbids, mask, anchors))
        fun base(i: Int, o: Lit): Region {
            val a = anchors.getOrPut(i) { lhsRegion(rs.element[i].lhs, vals) }
            if (o.anchor == "p") return a
            val e = a.leftEdges()
            return if (mask == null) e else e and mask
        }
        if (!check && !forbids) return vals to null
        val forbid = LinkedHashMap(forbid0)
        for ((i, o) in negLits) {
            val t = relTo(o, base(i, o))
            forbid[o.kind] = (forbid[o.kind] ?: Region.EMPTY) or (if (unboundReach(o, anchors.getValue(i))) Region.UNIVERSE else t)
        }
        for ((ii, oi, o) in opNeg) {
            val t = place(ii, oi, o, took(ch, ii, oi))
            if (t.isNotEmpty()) forbid[o.kind] = (forbid[o.kind] ?: Region.EMPTY) or t
        }
        if (forbids) return vals to forbid
        for ((k, f) in forbid) {
            val bad = union(vals[k].orEmpty().values) and f
            if (bad.isNotEmpty()) throw Conflict("$k is required to be absent on $bad")
        }
        percentConflict(rs, vals, permanent = true)?.let { throw Conflict(it) } // (more may come: only what cannot be repaired)
        return vals to null
    }

    /** Where the "or" took this operand: ch, and the interval with no left end where it took its first operand. */
    private fun took(ch: Choices, ii: Int, oi: Int): Region {
        var b = ch[ii][oi]
        if (oi == 0 && items[ii].anchor == "p" && minf[ii] == 0) b = b or comp.getValue(ii)
        return b
    }

    /** Where the element o of operand oi goes, the "or" ii having chosen it on b. */
    private fun place(ii: Int, oi: Int, o: Lit, b: Region): Region {
        val it = items[ii]
        val t = if (b.isNotEmpty()) relTo(o, from(ii, o, b)) else Region.EMPTY
        if (o.anchor == "l" && o.where.hi == Pos.INF) {
            if (it.anchor == "l") {
                if (minf[ii] == oi) return Region.UNIVERSE
            } else if (b.isNotEmpty() && lhsRegion(it.lhs, vals0).components().any { c -> c.inf() == Pos.NEG_INF && (c and b).isNotEmpty() }) {
                return Region.UNIVERSE
            }
        }
        return t
    }

    /** What the operand o is relative to when the "or" chose on b: b, or the left edges of the largest intervals of b. */
    private fun from(ii: Int, o: Lit, b: Region): Region {
        val it = items[ii]
        if (o.anchor == it.anchor) return b
        if ((it.lhs.kinds().toSet() intersect kin.toSet()).isNotEmpty()) {
            throw Undetermined("${it.text}: operands relative to p and to l, on a left side that an 'or' can change")
        }
        val met = lhsRegion(it.lhs, vals0)
        return union(met.components().filter { (it and b).isNotEmpty() }).leftEdges()
    }

    private fun valid(ch: Choices, horizon: Pos): Boolean = try {
        closure(ch, horizon)
        true
    } catch (e: Conflict) {
        false
    }

    // ------------------------------------------------------------------ the sweep

    /** The choices; an "or" on a largest interval with no left end decided at its position closest to 0. */
    fun solve(): Map<String, Map<Rational, Region>> {
        val (order, near) = minfFirst()
        if (order.isEmpty()) return finish(rest())
        if (near != null) {
            minfOpen.clear()
            minfOpen.addAll(order)
            return finish(rest(near))
        }
        for (combo in productOf(order.map { minfN(it.second) })) {
            minf = order.map { it.second }.zip(combo).toMap()
            try {
                return finish(rest())
            } catch (e: Conflict) {
                continue
            }
        }
        minf = emptyMap()
        throw Conflict("no choice of the 'or' operands satisfies the rules")
    }

    private fun productOf(ns: List<Int>): List<List<Int>> =
        ns.fold(listOf(emptyList())) { acc, n -> acc.flatMap { a -> (0 until n).map { a + it } } }

    /** The values [minf] takes for this "or": its operands (relative to l), or 0 / 1 (mixing p and l). */
    private fun minfN(ii: Int): Int = if (items[ii].anchor == "l") items[ii].ops.size else 2

    /** The "or"s met on a largest interval with no left end whose choice is one for the whole interval. */
    private fun minfFirst(): Pair<List<Pair<Pos, Int>>, Pos?> {
        val vals = closure(none, Pos.INF, check = false)
        val found = ArrayList<Pair<Pos, Int>>()
        for ((ii, it) in items.withIndex()) {
            if (it.anchor == "p") {
                val extra = rs.element[it.rule].rhs[itemAt[ii]].extra
                val firstLits = listOf(it.ops[0]) + (if (extra.isNotEmpty()) extra[0] else emptyList())
                if (!firstLits.all { o -> o.anchor == "l" }) continue
            }
            val comps = lhsRegion(it.lhs, vals).components().filter { c -> c.inf() == Pos.NEG_INF }
            if (comps.isEmpty()) continue
            if ((it.lhs.kinds().toSet() intersect kin.toSet()).isNotEmpty()) {
                throw Undetermined("${it.text}: on an element with no left end that an 'or' can change")
            }
            comp[ii] = comps[0]
            val s = comps[0].sup()
            found.add((if (s >= Pos.ZERO) Pos.ZERO else s) to ii)
        }
        if (found.isEmpty()) return emptyList<Pair<Pos, Int>>() to null
        val sorted = found.sortedWith { a, b -> b.first.compareTo(a.first) }
        val far = -sorted.last().first
        var near: Pos? = null
        if (far > Pos.ZERO) {
            val zone = Region.of(Interval.of(-far, far, false, true))
            for ((ii, it) in items.withIndex()) {
                var met = lhsRegion(it.lhs, vals)
                if (it.anchor == "l") met = met.leftEdges() else if (ii in comp) met -= comp.getValue(ii)
                if ((met and zone).isNotEmpty()) near = far
            }
        }
        return sorted to near
    }

    /** The other choices ([minf] given, or with [near]: decided among them, [outward]). */
    private fun rest(near: Pos? = null): Choices {
        var p = nextActive(none, null)
        var start = none
        var started = false
        if (p == Pos.NEG_INF) {
            val (s, q) = farLeft()
            start = s
            p = q
            started = true
        }
        memo = HashMap()
        failed = HashMap()
        dead = ArrayList()
        stuck = Pos.NEG_INF
        first = p
        grid()
        val res: Choices? = when {
            started -> if (p == null) (if (valid(start, Pos.INF)) start else null) else complete(p, start, 0).first
            rs.searched -> search()
            p == null -> if (valid(none, Pos.INF)) none else null
            near != null -> {
                if (p == Pos.NEG_INF) {
                    throw Undetermined("the choices on an element with no left end act on each other: this solver does not decide them")
                }
                outward(p, maxOf(p.x.abs(), near.x))
            }
            p < Pos.ZERO && !rs.pointwise && probe == null -> outward(p) // (a probe only asks if a solution exists)
            else -> complete(p, none, 0).first
        }
        return res ?: throw Conflict("no choice of the 'or' operands satisfies the rules")
    }

    // ------------------------------------------------------------------ unbounded on the left

    /** The choice at one position never changes which operands can be chosen at another. */
    private fun independent(): Boolean {
        val kinSet = kin.toSet()
        if (probe != null) return false
        val out = minf.filter { (ii, v) ->
            items[ii].anchor == "p" && v == 1 && lhsRegion(items[ii].lhs, vals0).isSubsetOf(comp.getValue(ii))
        }.keys.map { it to 0 }.toSet()
        val placed = HashSet<String>()
        for (k in kinSet) for ((ii, oi, lit) in opPos.getValue(k)) if ((ii to oi) !in out) placed.add(lit.kind)
        for (r in rs.element) if ((r.lhs.kinds().toSet() intersect kinSet).isNotEmpty()) for (lit in r.lits()) if (!lit.negated) placed.add(lit.kind)
        if (opNeg.any { (ii, oi, lit) -> lit.kind in placed && (ii to oi) !in out }) return false
        val values = kinSet.associateWith { k -> opPos.getValue(k).filter { (ii, oi, _) -> (ii to oi) !in out }.map { it.third.value }.toMutableSet() }
        for (r in rs.element) {
            val read = r.lhs.kinds().toSet() intersect kinSet
            if (read.isEmpty()) continue
            val parts = conjuncts(r.lhs).filter { (it.kinds().toSet() intersect kinSet).isNotEmpty() }
            if (read.size > 1 || parts.size != 1 || !(parts[0] is Atom && (parts[0] as Atom).op == "present")) return false
            for (lit in r.lits()) {
                if (lit.negated || lit.anchor != "p") return false
                if (lit.kind in kinSet) values.getValue(lit.kind).add(lit.value)
            }
        }
        return values.values.all { it.size <= 1 }
    }

    /** An "or" met on an element unbounded on the left: all positions left of -T take the same operand. */
    private fun farLeft(): Pair<Choices, Pos?> {
        if (!(independent() && staticRegions.all { constantTail(it.left, it.pl) })) {
            throw Undetermined("an 'or' acts on an element unbounded on the left, and its choices act on each other: this solver does not decide them")
        }
        val x = Pos(-bigT)
        val far = Region.of(Interval.of(Pos.NEG_INF, x, false, false))
        var ch = none
        val vals = closure(ch, Pos.INF, check = false)
        for ((ii, it) in items.withIndex()) {
            val where = met(ii, vals) and far
            if (where.isEmpty()) continue
            var done = false
            for (oi in it.ops.indices) {
                val c = with(ch, ii, oi, where)
                if (valid(c, Pos.INF)) {
                    ch = c
                    done = true
                    break
                }
            }
            if (!done) throw Conflict("${it.text}: no operand can be chosen on $where")
        }
        return ch to nextActive(ch, x - Pos.EPS)
    }

    // ------------------------------------------------------------------ from 0 outwards

    /** The positions of [-L, L] in cells, the closest to 0 first and the right one first when two are as close. */
    private fun cellsAround(l: Rational): List<Pair<Region, Pos>> {
        val ds = deltas.map { it.x }.toSet() + Rational.ZERO
        val xs = staticEnds.map { it.x }.toSet() + setOf(Rational.ZERO, l)
        val cuts0 = HashSet<Rational>()
        for (x in xs) for (d in ds) cuts0.add(x + d)
        for (d in ds) cuts0.add(d / 2)
        val cuts = cuts0.flatMap { listOf(it, -it) }.filter { -l <= it && it <= l }.distinct().sorted()
        val j = rs.epsReach.toLong()
        val out = ArrayList<Pair<Region, Pos>>()
        for ((n, x) in cuts.withIndex()) {
            for (k in -j..j) out.add(Region.point(Pos(x, k)) to Pos(x, k))
            if (n + 1 < cuts.size) out.add(Region.of(Interval.of(Pos(x, j + 1), Pos(cuts[n + 1], -j - 1))) to Pos((x + cuts[n + 1]) / 2))
        }
        return out.sortedWith(
            compareBy<Pair<Region, Pos>>(
                { it.second.x.abs() },
                { val p = it.second; if (p.x.sign > 0) p.k else if (p.x.sign < 0) -p.k else kotlin.math.abs(p.k) },
                { val p = it.second; p.x.sign < 0 || (p.x.sign == 0 && p.k < 0) },
            ),
        )
    }

    /** The choices of the definition with no order to rely on, searched from the closest cell to 0. */
    private fun search(): Choices? {
        val cells = cellsAround(bigT)
        val j = rs.epsReach.toLong()
        val far = after(Pos(bigT, j)) or Region.of(Interval.of(Pos.NEG_INF, Pos(-bigT, -j), false, false))

        fun nxt(ch: Choices): Triple<Map<String, Map<Rational, Region>>, Int?, Region?> {
            val vals = closure(ch, Pos.INF, check = false)
            for ((reg, p) in cells) {
                for ((ii, it) in items.withIndex()) {
                    if (active(ii, vals, p) && ch[ii].none { r -> r.contains(p) }) {
                        if (it.anchor == "l") return Triple(vals, ii, Region.point(p))
                        if (!reg.isSubsetOf(met(ii, vals))) throw Undetermined("the sweep could not place exactly where a choice changes around $p")
                        return Triple(vals, ii, reg)
                    }
                }
            }
            return Triple(vals, null, null)
        }

        fun good(ch: Choices, vals: Vals): Boolean {
            if (!valid(ch, Pos.INF)) return false
            for (ii in items.indices) {
                val met = met(ii, vals)
                if ((met and far).isNotEmpty()) throw Undetermined("the choices of these rules are only searched on bounded elements")
                if (!ch[ii].all { it.isSubsetOf(met) }) return false // a choice that took its own anchor away
            }
            return true
        }

        val windowR = union(cells.map { it.first })

        fun doomed(ch: Choices): Boolean {
            val most = ch.map { row -> val taken = union(row); row.map { it or (windowR - taken) } }
            val low = closure(ch, Pos.INF, check = false)
            val high = closure(most, Pos.INF, check = false)
            val ph = views(high)
            val sure = LinkedHashMap<String, MutableMap<Rational, Region>>()
            for ((k, d) in low) if (k !in kin) sure[k] = LinkedHashMap(d)
            fun put(k: String, v: Rational, r: Region) {
                val d = sure.getOrPut(k) { LinkedHashMap() }
                d[v] = (d[v] ?: Region.EMPTY) or r
            }
            for (k in kin) {
                val f = fixed[k]
                put(k, HUNDRED, f?.first ?: Region.EMPTY)
                for ((v, r) in f?.second.orEmpty().entries + claims[k].orEmpty().entries) put(k, v, r)
            }
            fun aim(ii: Int, o: Lit, b: Region): Region {
                if (o.anchor == items[ii].anchor) return relTo(o, b)
                return try {
                    relTo(o, from(ii, o, b))
                } catch (e: Undetermined) {
                    Region.EMPTY
                }
            }
            for (k in kin) for ((ii, oi, o) in opPos.getValue(k)) {
                if (ch[ii][oi].isNotEmpty()) put(o.kind, o.value ?: HUNDRED, aim(ii, o, ch[ii][oi]))
            }
            val forbid = LinkedHashMap(forbid0)
            for ((ii, oi, o) in opNeg) {
                if (ch[ii][oi].isNotEmpty()) forbid[o.kind] = (forbid[o.kind] ?: Region.EMPTY) or aim(ii, o, ch[ii][oi])
            }
            val u = Region.UNIVERSE
            val kinSet = kin.toSet()
            repeat(kin.size + 1) {
                val ps = views(sure)
                for (r in rs.element) {
                    if ((r.lhs.kinds().toSet() intersect kinSet).isEmpty()) continue
                    val m = r.lhs.region2(View(sure, ps), View(high, ph), u, Region.EMPTY)
                    for (it in r.rhs) {
                        val o = it.options[0]
                        if (it.isOr || o.anchor != "p" || m.isEmpty()) continue
                        if (o.negated) {
                            forbid[o.kind] = (forbid[o.kind] ?: Region.EMPTY) or relTo(o, m)
                        } else if (o.kind in kinSet) {
                            put(o.kind, o.value ?: HUNDRED, relTo(o, m))
                        }
                    }
                }
            }
            val ps = views(sure)
            return forbid.any { (k, f) -> ((ps[k] ?: Region.EMPTY) and f).isNotEmpty() }
        }

        fun go(ch: Choices): Choices? {
            checkpoint()
            shared.calls++
            if (shared.calls > shared.limit) throw Undetermined("deciding the choices takes more than $MAX_CALLS attempts")
            if (doomed(ch)) return null
            val (vals, ii, where) = nxt(ch)
            if (ii == null) return if (good(ch, vals)) ch else null
            for (oi in items[ii].ops.indices) {
                val c = with(ch, ii, oi, where!!)
                if (rs.settled && !valid(c, Pos.INF)) continue
                val out = go(c)
                if (out != null) return out
            }
            return null
        }
        return go(none)
    }

    /** The choices of the definition when they act on each other on the left of 0, from the closest cell to 0. */
    private fun outward(p: Pos, l: Rational? = null): Choices? {
        val cells = cellsAround(l ?: p.x.abs())
        staticEnds = (staticEnds + cells.flatMap { (reg, _) -> ends(reg, reg.lo, reg.hi) }.filter { it.isFinite }).distinct().sorted()
        grid()
        val bars = items.map { List(it.ops.size) { Region.EMPTY } }.toMutableList()
        barred = bars
        var tries = 0

        fun found(): Choices? {
            tries++
            if (tries > MAX_CALLS) throw Undetermined("deciding the choices from 0 takes more than $MAX_CALLS attempts")
            checkpoint()
            val openIds = minfOpen.map { it.second }.toSet()
            val fixedM = minf.filterKeys { it !in openIds }
            for (combo in productOf(minfOpen.map { minfN(it.second) })) {
                minf = fixedM + minfOpen.map { it.second }.zip(combo)
                memo = HashMap()
                failed = HashMap()
                dead = ArrayList()
                stuck = Pos.NEG_INF
                shared.calls = 0
                val r = complete(p, none, 0).first
                if (r != null) return r
            }
            minf = fixedM
            return null
        }

        var best: Choices? = null

        fun decideMinf(at: Pos) {
            for ((k, ii) in minfOpen.filter { it.first == at }) {
                minfOpen.remove(k to ii)
                val keep = minf
                var done = false
                for (v in 0 until minfN(ii)) {
                    minf = keep + (ii to v)
                    val sol = found()
                    if (sol != null) {
                        best = sol
                        done = true
                        break
                    }
                }
                if (!done) throw Undetermined("the sweep could not decide ${items[ii].text} at $at")
            }
        }

        fun only(ii: Int, oi: Int, reg: Region): List<Region> {
            val was = bars[ii]
            bars[ii] = was.mapIndexed { o, r -> if (o == oi) r else r or reg }
            return was
        }
        try {
            best = found() ?: return null
            for ((reg, at) in cells) {
                if (reg == Region.point(at)) decideMinf(at)
                for ((ii, it) in items.withIndex()) {
                    var where = reg
                    if (it.anchor == "l") { // chosen by its largest interval's position closest to 0
                        if (reg != Region.point(at)) continue
                        val e = edgeChosenAt(ii, best!!, at) ?: continue
                        where = Region.point(e)
                    }
                    val took = best!![ii].withIndex().filter { (it.value and where).isNotEmpty() }.map { it.index }
                    if (took.isEmpty()) continue
                    var changed = false
                    for (oi in 0 until took[0]) {
                        val was = only(ii, oi, where)
                        val sol = found()
                        if (sol != null) {
                            best = sol
                            changed = true
                            break
                        }
                        bars[ii] = was
                    }
                    if (!changed) {
                        only(ii, took[0], where)
                        if (!where.isSubsetOf(best!![ii][took[0]])) {
                            best = found() ?: throw Undetermined("the sweep could not place exactly where a choice changes around $at")
                        }
                    }
                }
            }
            minfOpen.clear() // (chosen beyond the cells: as found)
            return best
        } finally {
            barred = null
        }
    }

    /** For an "or" relative to l: the left edge of the largest interval whose position closest to 0 is [p], or null. */
    private fun edgeChosenAt(ii: Int, ch: Choices, p: Pos): Pos? {
        val vals = closure(ch, Pos.INF, check = false)
        for (c in lhsRegion(items[ii].lhs, vals).components()) {
            if (c.inf() == Pos.NEG_INF) continue
            val e = c.leftEdges().inf()
            val k = if (c.contains(Pos.ZERO)) Pos.ZERO else (if (c.sup() < Pos.ZERO) c.sup() else e)
            if (k == p) return e
        }
        return null
    }

    /** From P, each position takes the leftmost operand that does not contradict the rules with what is on its left. */
    private fun greedy(p0: Pos, ch0: Choices, start0: Int = 0): Greedy {
        var p: Pos? = p0
        var ch = ch0
        var start = start0
        val seen = HashMap<Any, Pos>()
        var events = 0
        while (p != null) {
            checkpoint()
            events++
            if (events > MAX_EVENTS) throw Undetermined("the choices change more than $MAX_EVENTS times without repeating")
            if (start == 0 && periodicOk && p.x > bigT) {
                var sig: Any = signature(ch, p)
                q?.let { sig = Triple(sig, p.x % it, p.k) }
                val before = seen[sig]
                if (before != null && (p - before).x.sign > 0) return Greedy.Ok(repeat(ch, before, p))
                seen[sig] = p
            }
            val (c, f) = decide(p, ch, start)
            ch = c
            start = 0
            if (f != null) return Greedy.Dead(p, f)
            val runs = runsAt(p, ch)
            if (runs.isNotEmpty()) {
                val (np, nch) = run(p, runs, ch)
                p = np
                ch = nch
            } else {
                p = nextActive(ch, p)
            }
        }
        if (!valid(ch, Pos.INF)) return Greedy.Dead(null, null)
        return Greedy.Ok(ch)
    }

    private fun runsAt(p: Pos, ch: Choices): List<Pair<Int, Int>> =
        items.withIndex().filter { it.value.anchor == "p" }.flatMap { (ii, it) -> it.ops.indices.filter { ch[ii][it].contains(p) }.map { ii to it } }

    // ------------------------------------------------------------------ looking ahead

    /**
     * The choices of the definition from P on, given [ch]: (choices, null), or (null, q) if no completion exists, q
     * the first position whose choice could be why.
     */
    private fun complete(p: Pos, ch: Choices, start: Int = 0, u: Pos? = null): Pair<Choices?, Pos?> {
        checkpoint()
        shared.calls++
        if (shared.calls > shared.limit) {
            throw Undetermined("deciding the choices needs to look ahead further than this solver does ($MAX_CALLS attempts)")
        }
        val state = state(ch, p)
        val ks = kset(ch, p)
        val key = MemoKey(p, start, ks, state)
        memo[key]?.let { return it }
        val (fixedPart0, more) = splitState(state)
        val fixedPart = ks to fixedPart0
        for (f in failed[FailKey(p, start, fixedPart)].orEmpty()) {
            if (f.all { (label, have) -> have.isSubsetOf(more[label] ?: IntervalSet.EMPTY) }) return null to (p - reach)
        }
        var r: Greedy = Greedy.Dead(null, null)
        if (p > stuck && ch.none { row -> row.any { it.sup() > p } }) r = greedy(p, ch, start)
        val res: Pair<Choices?, Pos?> = if (r is Greedy.Ok) {
            r.ch to null
        } else {
            val d = r as Greedy.Dead
            if (d.at != null) {
                stuck = d.at
                if (d.item != null && d.item >= 0) learn(d.at, d.item)
            }
            val fst = first
            if (start == 0 && u == null && fst != null && p > fst && probeAt(ch, p) == false) {
                null to (p - reach)
            } else {
                careful(p, ch, start, u ?: unit(p, ks))
            }
        }
        memo[key] = res
        if (res.first == null) failed.getOrPut(FailKey(p, start, fixedPart)) { ArrayList() }.add(more)
        return res
    }

    /** The part of the state that must be equal, and the part where more only makes things harder. */
    private fun splitState(state: List<StatePart>): Pair<List<Pair<Label, Any>>, Map<Label, IntervalSet>> {
        val fx = ArrayList<Pair<Label, Any>>()
        val more = HashMap<Label, IntervalSet>()
        for (s in state) {
            if (s.label.tag == "or") {
                fx.add(s.label to s.ivs)
            } else {
                val set = IntervalSet.sorted(s.ivs)
                more[s.label] = set
                if (s.label.tag != "no") fx.add(s.label to set.contains(-Pos.EPS))
            }
        }
        return fx to more
    }

    /** Decide the block [P, U] that starts at P, operand by operand, in order, jumping back past a failure's reach. */
    private fun careful(p: Pos, ch: Choices, start: Int, u: Pos): Pair<Choices?, Pos?> {
        val vals = try {
            closure(ch, p)
        } catch (e: Conflict) {
            return null to (p - reach)
        }
        for (ii in start until items.size) {
            val it = items[ii]
            if (!active(ii, vals, p)) continue
            if (it.anchor == "l" && u != p) throw Undetermined("the sweep could not place exactly where a choice changes after $p")
            val where = Region.of(Interval.of(p, u))
            var q: Pos? = null
            for (oi in it.ops.indices) {
                val c = with(ch, ii, oi, where)
                val qo: Pos
                if (!valid(c, u)) {
                    qo = p - reach
                } else {
                    val d = doomedAt(c, u)
                    if (d != null) {
                        qo = d - reach
                    } else {
                        val (r, q2) = complete(p, c, ii + 1, u)
                        if (r != null) return r to null
                        qo = q2!!
                    }
                }
                if (qo > p) return null to qo
                q = if (q == null || qo < q) qo else q
            }
            return null to q
        }
        // every "or" met on the block is decided: the next block
        val nxt = if (runsAt(p, ch).isEmpty()) nextActive(ch, u) else u + Pos.EPS
        if (nxt == null) return if (valid(ch, Pos.INF)) ch to null else null to Pos.NEG_INF
        return complete(nxt, ch, 0)
    }

    /** Where a choice can change: real parts r + n·g, and around the ε parts of the ends seen. */
    private fun grid() {
        val ds = ArrayList<Rational>()
        fun add(o: Lit) {
            for (e in listOf(o.where.lo, o.where.hi)) if (e.isFinite) ds.add(e.x.abs())
        }
        for (k in kin) for ((_, _, o) in opPos.getValue(k)) add(o)
        for ((_, _, o) in opNeg) add(o)
        for (l in posLits.values) for ((_, o) in l) add(o)
        for ((_, o) in negLits) add(o)
        var gg: Rational? = null
        for (d in ds) if (d.sign != 0) gg = if (gg == null) d else fgcdR(gg, d)
        g = gg ?: Rational.ONE
        residues = (staticEnds.map { it.x % g } + Rational.ZERO).distinct().sorted()
    }

    /** The ε parts to look at near P. */
    private fun kset(ch: Choices, p: Pos): List<Long> {
        val a = p - bigR * Rational.of(2)
        val b = p + bigR * Rational.of(2)
        val es = staticEnds.filter { a <= it && it <= b }.toMutableList()
        for (row in ch) for (reg in row) for (iv in reg.on(a - 1, p).intervals) {
            for (e in listOf(iv.lo, iv.hi)) if (a <= e && e < p - Pos.EPS) es.add(e)
        }
        val j = rs.epsReach.toLong()
        val out = HashSet<Long>()
        for (e in es) for (k in (e.k - j)..(e.k + j)) out.add(k)
        for (k in -j..j) out.add(k)
        return out.sorted()
    }

    private fun onGrid(x: Rational): Boolean = residues.any { ((x - it) % g).sign == 0 }

    /** The block that starts at P: P alone if a choice can change right after it, else up to the next such position. */
    private fun unit(p: Pos, ks: List<Long>): Pos {
        if (onGrid(p.x) && p.k in ks) return p
        if (onGrid(p.x)) {
            val later = ks.filter { it > p.k }
            if (later.isNotEmpty()) return Pos(p.x, later[0]) - Pos.EPS
        }
        val s = residues.minOf { r -> r + g * Rational.of(((p.x - r) / g).floor() + 1) }
        return Pos(s, ks[0]) - Pos.EPS
    }

    /** What the completion from P depends on: what the choices before P placed and forbade within reach, and the choices from P. */
    private fun state(ch: Choices, p: Pos): List<StatePart> {
        val (vals, forbid) = closureForbids(ch, p - Pos.EPS)
        val a = p - back - Pos.EPS
        val b = p + fwd + Pos.EPS * 2
        fun part(label: Label, reg: Region, lo: Pos, hi: Pos) =
            StatePart(label, reg.on(lo, hi).shift(-p).intervals, reg.contains(lo - Pos.EPS), reg.contains(hi))
        val out = ArrayList<StatePart>()
        for ((i, row) in ch.withIndex()) for ((j, reg) in row.withIndex()) out.add(part(Label("or", "$i", "$j"), reg, p, b + bigR * Rational.of(3)))
        for (k in kin) for ((v, reg) in vals.getValue(k).entries.sortedBy { it.key }) out.add(part(Label("kind", k, "$v"), reg, a, b))
        for ((k, f) in forbid.entries.sortedBy { it.key }) out.add(part(Label("no", k, ""), f, a, b))
        return out
    }

    private fun learn(d: Pos, ii: Int) {
        if ((d to ii) !in dead) dead.add(d to ii)
    }

    /** False if no completion exists from P whatever came before P, given only what is present at P - ε. */
    private fun probeAt(ch: Choices, p: Pos): Boolean? {
        val vals = closure(ch, p - Pos.EPS, check = false)
        val atoms = kin.flatMap { k -> vals.getValue(k).filter { it.value.contains(p - Pos.EPS) }.keys.map { k to it } }
            .sortedWith(compareBy<Pair<String, Rational>>({ it.first }, { it.second }))
        val aft = Region.of(Interval.of(p, p + fwd))
        fun static(k: String): Region {
            val f = fixed[k]
            return union(claims.getValue(k).values + listOf(f?.first ?: Region.EMPTY) + f?.second.orEmpty().values)
        }
        val forward = kin.any { k -> ((union(vals.getValue(k).values) and aft) - static(k)).isNotEmpty() }
        val key = ProbeKey(p, atoms, forward)
        val probes = shared.probes
        if (key !in probes) {
            probes[key] = null // being computed: no answer meanwhile
            val sub = Sweep(rs, prob, vals0, fixed, forbid0, kindsIn, key, SweepShared(probes, 0, PROBE_CALLS))
            probes[key] = try {
                sub.solve()
                true
            } catch (e: Conflict) {
                false
            } catch (e: Undetermined) {
                null
            }
        }
        return probes[key]
    }

    /** A position where nothing could be chosen before, still with nothing whatever is decided after h. */
    private fun doomedAt(ch: Choices, h: Pos): Pos? {
        for ((d, ii) in dead) if (d > h && blocks(ch, d, ii, h)) return d
        return null
    }

    private fun blocks(ch: Choices, d: Pos, ii: Int, h: Pos): Boolean {
        val it = items[ii]
        val vals = closure(ch, h, check = false)
        if (!met(ii, vals).contains(d) || (it.anchor == "l" && (it.lhs.kinds().toSet() intersect kin.toSet()).isNotEmpty())) return false
        return it.ops.indices.none { oi -> valid(with(ch, ii, oi, Region.point(d)), h) }
    }

    /** The first position (after [after]) where an "or" is met, or null. */
    private fun nextActive(ch: Choices, after: Pos?): Pos? {
        val vals = closure(ch, Pos.INF, check = false)
        var best: Pos? = null
        for (ii in items.indices) {
            var a = met(ii, vals)
            if (after != null) a = a and after(after)
            if (a.isNotEmpty()) {
                val m = a.inf()
                if (best == null || m < best) best = m
            }
        }
        return best
    }

    /** Where the "or" must choose: where its left side is met, or (relative to l) the left edges of that. */
    private fun met(ii: Int, vals: Vals): Region {
        val it = items[ii]
        var a = lhsRegion(it.lhs, vals)
        if (it.anchor == "l") {
            a = a.leftEdges()
            if (probe != null) a = a and edgesAfter!!
        } else if (minf.isNotEmpty()) {
            if (minf[ii] == 0) a -= comp.getValue(ii)
        }
        return if (probe == null) a else a and inside!!
    }

    private fun active(ii: Int, vals: Vals, m: Pos): Boolean = met(ii, vals).contains(m)

    /** The "or"s met at m (from [start]) take, in order, the leftmost operand that fits; -1 if the rules already fail. */
    private fun decide(m: Pos, ch0: Choices, start: Int = 0): Pair<Choices, Int?> {
        var ch = ch0
        var vals = try {
            closure(ch, m)
        } catch (e: Conflict) {
            return ch to -1
        }
        for (ii in start until items.size) {
            val it = items[ii]
            if (!active(ii, vals, m)) continue
            var done = false
            for (oi in it.ops.indices) {
                val ch2 = with(ch, ii, oi, Region.point(m))
                vals = try {
                    closure(ch2, m)
                } catch (e: Conflict) {
                    continue
                }
                ch = ch2
                done = true
                break
            }
            if (!done) return ch to ii
        }
        return ch to null
    }

    /** The run of the operands decided at P: the next position where the decision may differ, and the choices with the run. */
    private fun run(p: Pos, runs: List<Pair<Int, Int>>, ch: Choices): Pair<Pos, Choices> {
        val nx = staticEnds.filter { it > p + bigR }
        val cap = if (nx.isNotEmpty()) nx[0] else p + bigR * Rational.of(64)
        fun withRun(qq: Pos): Choices {
            val reg = Region.of(Interval.of(p, qq))
            val out = ch.map { it.toMutableList() }
            for ((ii, oi) in runs) out[ii][oi] = out[ii][oi] or reg
            return out
        }
        // where an "or" starts or stops being met
        val vals = closure(withRun(cap), cap, check = false)
        var end = cap + Pos.EPS
        val running = runs.map { it.first }.toSet()
        for ((ii, it) in items.withIndex()) {
            val a = met(ii, vals)
            val change = when {
                it.anchor == "l" -> (a and after(p)).inf()
                ii in running -> (after(p) - a).inf()
                else -> (a and after(p)).inf()
            }
            if (change < end) end = change
        }
        val cands = candidates(p, end, vals, ch)
        // the run stays valid up to E (validity only gets lost as the run grows)
        val e = last({ qq -> valid(withRun(qq), qq) }, p, end - Pos.EPS, cands)
        if (e < end - Pos.EPS) end = e + Pos.EPS
        // an earlier operand that can be chosen again
        for ((n, r) in runs.withIndex()) {
            val (ii, oi) = r
            for (o2 in 0 until oi) {
                val before = runs.subList(0, n)
                val can = { m: Pos ->
                    val c = withRun(m - Pos.EPS).map { it.toMutableList() }
                    for ((jj, oj) in before) c[jj][oj] = c[jj][oj] or Region.point(m)
                    c[ii][o2] = c[ii][o2] or Region.point(m)
                    valid(c, m)
                }
                val f = firstWhere(can, p, end, cands)
                if (f != null && f < end) end = f
            }
        }
        return end to withRun(end - Pos.EPS)
    }

    /** Offsets from a decided position to the ends of the regions it changes, and the differences of two of them. */
    private fun computeDeltas(): Set<Pos> {
        val out = offsets(rs.reach * Rational.of(kin.size + 1))
        fwd = (out.map { it.x } + Rational.ZERO).max()
        back = (out.map { -it.x } + Rational.ZERO).max()
        val res = HashSet<Pos>()
        for (a in out) for (b in out) res.add(a - b)
        return res
    }

    /** Offsets from a decided position to the ends of the regions it changes, along the rules, up to [cap]. */
    private fun offsets(cap: Rational): Set<Pos> {
        val off = kin.associateWith { HashSet<Pos>() }
        val out = HashSet<Pos>()
        out.add(Pos.ZERO)
        fun endsOf(o: Lit) = listOf(o.where.lo, o.where.hi).filter { it.isFinite }.toSet()
        for (k in kin) for ((_, _, o) in opPos.getValue(k)) off.getValue(o.kind).addAll(endsOf(o))
        for ((_, _, o) in opNeg) out.addAll(endsOf(o))
        val n = kin.size + 1
        fun keep(x: Pos) = x.isFinite && x.x.abs() <= cap && kotlin.math.abs(x.k) <= n + 2
        var changed = true
        while (changed) {
            changed = false
            for (k in kin) {
                for (r in rs.element) {
                    if (k !in r.lhs.kinds()) continue
                    for (it in r.rhs) {
                        if (it.isOr) continue
                        val o = it.options[0]
                        val new = HashSet<Pos>()
                        for (d in off.getValue(k)) for (e in endsOf(o)) {
                            val x = d + e
                            if (keep(x)) new.add(x)
                        }
                        if (o.negated || o.kind !in off) {
                            out.addAll(new)
                        } else if (!off.getValue(o.kind).containsAll(new)) {
                            off.getValue(o.kind).addAll(new)
                            changed = true
                        }
                    }
                }
            }
        }
        for (s in off.values) out.addAll(s)
        return out
    }

    /** The positions in (lo, hi) where the decision may change. */
    private fun candidates(lo: Pos, hi: Pos, vals: Vals, ch: Choices): List<Pos> {
        val a = lo - bigR
        val b = if (hi != Pos.INF) hi + bigR else lo + bigR * Rational.of(70)
        val es = HashSet<Pos>()
        es.add(lo)
        for (e in staticEnds) if (a <= e && e <= b) es.add(e)
        for (d in vals.values) for (reg in d.values) es.addAll(ends(reg, a, b).filter { it.isFinite })
        for (row in ch) for (reg in row) es.addAll(ends(reg, a, b).filter { it.isFinite })
        val out = HashSet<Pos>()
        val j = rs.epsReach.toLong()
        for (e in es) for (d in deltas) {
            val x = e + d
            for (jj in -j..j) {
                val y = Pos(x.x, x.k + jj)
                if (lo < y && y < hi) out.add(y)
            }
        }
        return out.sorted()
    }

    /** The first position in (lo, hi) where [pred] holds, or null. */
    private fun firstWhere(pred: (Pos) -> Boolean, lo: Pos, hi: Pos, cands: List<Pos>): Pos? {
        var prev = lo
        for (c in cands.filter { it < hi } + hi) {
            val f = prev + Pos.EPS
            if (f < c) {
                if (pred(f)) return f
                if (prev.x < c.x && pred(Pos((prev.x + c.x) / 2))) {
                    throw Undetermined("the sweep could not place exactly where a choice changes between $prev and $c")
                }
            }
            if (c != hi && pred(c)) return c
            prev = c
        }
        return null
    }

    /** The last position x in [lo, hi] with pred(x), pred holding at lo and only getting lost as x grows. */
    private fun last(pred: (Pos) -> Boolean, lo: Pos, hi: Pos, cands: List<Pos>): Pos {
        val pts = cands.filter { it <= hi }.toMutableList()
        if (pts.isEmpty() || pts.last() != hi) pts.add(hi)
        var i = -1
        var j = pts.size
        while (j - i > 1) {
            val mid = (i + j).floorDiv(2)
            if (pred(pts[mid])) i = mid else j = mid
        }
        var x = if (i >= 0) pts[i] else lo
        if (j < pts.size) {
            val nxt = pts[j]
            if (x + Pos.EPS < nxt && pred(x + Pos.EPS)) {
                if (!pred(nxt - Pos.EPS)) throw Undetermined("the sweep could not place exactly where a choice stops between $x and $nxt")
                x = nxt - Pos.EPS
            }
        }
        return x
    }

    // ------------------------------------------------------------------ end

    private fun finish(ch: Choices): Map<String, Map<Rational, Region>> {
        choices = ch
        val vals = closure(ch, Pos.INF)
        return kin.associateWith { vals.getValue(it) }
    }

    // ------------------------------------------------------------------ repetition

    private fun signature(ch: Choices, p: Pos): List<List<Interval>> {
        val a = p - bigR
        return ch.flatMap { row -> row.map { it.on(a, p).shift(-p).intervals } }
    }

    /** The choices made before P2, repeating with period P2 - P1 from P2 on. */
    private fun repeat(ch: Choices, p1: Pos, p2: Pos): Choices {
        val per = p2 - p1
        return ch.map { row ->
            row.map { reg ->
                val lo = if (reg.isNotEmpty() && reg.inf() < p1) reg.inf() else p1
                Region.make(lo, p2, reg.on(lo, p2), per, IntervalSet.EMPTY, per, reg.on(p1, p2).shift(-p1))
            }
        }
    }
}
