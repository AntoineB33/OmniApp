package org.example.project.scheduler.ruleline

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The line computed from its claims: what the element rules make of the elements placed — a port of
 * `C:\code\line_manager\scripts\solver.py`.
 *
 * Given claims (elements placed, with percentages; "no K" elements) the line is every claimed element, closed under
 * the rules (where a left side is met its literals hold; for an ordered "or" an operand is chosen), and nothing more.
 * The operands are chosen position by position from left to right, each position taking the leftmost operand that
 * still lets all the other positions be placed — with the positions closest to 0 first on the left of 0.
 *
 * Kinds no "or" can change are computed with region algebra, kind after kind ([close]). For the others, two solvers:
 * the lattices (here), when the rules that read chosen kinds only place single positions relative to p; the sweep
 * ([Sweep]) otherwise.
 */
class Conflict(message: String) : Exception(message)

/** This solver does not decide the "or" choices here, or deciding them would take longer than it is allowed. */
class Undetermined(message: String) : Exception(message)

/** The computation was stopped from outside ([SolverLimits.stop]). */
class Interrupted(message: String) : Exception(message)

/**
 * The reference's `limited` and `STOP`. **Process-wide here** where the reference's limit is per thread: only the
 * generator's runs may set it, never a display path (to settle when the generator is wired, ADR 0018).
 */
object SolverLimits {
    var stop: Boolean = false
    private var at: TimeSource.Monotonic.ValueTimeMark? = null

    /** What is computed inside gives up ([Undetermined]) after [seconds]; inside another limit the earlier holds. */
    fun <T> limited(seconds: Double?, body: () -> T): T {
        val outer = at
        val mine = seconds?.let { TimeSource.Monotonic.markNow() + (it * 1000).toLong().milliseconds }
        at = when {
            outer == null -> mine
            mine == null -> outer
            else -> if (mine < outer) mine else outer
        }
        try {
            return body()
        } finally {
            at = outer
        }
    }

    /** Raise [Interrupted] if stopped, [Undetermined] past the limit: called in the long loops. */
    fun checkpoint() {
        if (stop) throw Interrupted("stopped")
        val a = at
        if (a != null && a.hasPassedNow()) throw Undetermined("it takes too long to compute")
    }
}

internal fun checkpoint() = SolverLimits.checkpoint()

/** kind -> value (percentage) -> region. */
typealias Vals = Map<String, Map<Rational, Region>>

val EMPTY_REGION: Region get() = Region.EMPTY

fun union(regions: Iterable<Region>): Region {
    var out = Region.EMPTY
    for (r in regions) out = out or r
    return out
}

private val HUNDRED = Rational.of(100)

/** The line: for each kind, value (percentage) -> region, pairwise disjoint. */
class Line(vals0: Vals) {
    val vals: Map<String, Map<Rational, Region>> = LinkedHashMap<String, Map<Rational, Region>>().also { out ->
        for ((k, d) in vals0) {
            val kept = LinkedHashMap<Rational, Region>()
            for ((v, r) in d) if (r.isNotEmpty()) kept[v] = r
            if (kept.isNotEmpty()) out[k] = kept
        }
    }

    /** The kinds on the line (not the hidden ones). */
    fun kinds(): List<String> = vals.keys.filter { !isHidden(it) }.sorted()

    fun presence(kind: String): Region = union(vals[kind].orEmpty().values)

    fun valueAt(kind: String, x: Pos): Rational {
        for ((v, r) in vals[kind].orEmpty()) if (r.contains(x)) return v
        return Rational.ZERO
    }

    fun integral(kind: String, window: IntervalSet): Rational =
        vals[kind].orEmpty().entries.fold(Rational.ZERO) { s, (v, r) -> s + v / HUNDRED * r.measureOn(window) }

    fun sameOn(other: Line, region: Region): Boolean {
        for (k in vals.keys + other.vals.keys) {
            val mine = vals[k].orEmpty()
            val theirs = other.vals[k].orEmpty()
            for (v in mine.keys + theirs.keys) {
                val ra = mine[v] ?: Region.EMPTY
                val rb = theirs[v] ?: Region.EMPTY
                if (ra === rb) continue // the same region: unchanged, nothing to compare
                if ((ra and region) != (rb and region)) return false
            }
        }
        return true
    }

    override fun equals(other: Any?): Boolean = other is Line && sameOn(other, Region.UNIVERSE)

    override fun hashCode(): Int = 0

    override fun toString(): String {
        val lines = kinds().flatMap { k -> vals.getValue(k).entries.sortedBy { it.key }.map { (v, r) -> "$k $v% on $r" } }
        return if (lines.isEmpty()) "(empty line)" else lines.joinToString("\n")
    }
}

/** Inputs of a computation of the line: claims (kind -> value -> region) and where a kind must be absent. */
data class Problem(val pos: Vals, val neg: Map<String, Region> = emptyMap())

// ====================================================================== region stage

/** Where a left side is met, given the values of its kinds. */
fun lhsRegion(f: Formula, vals: Vals): Region = f.region(vals, Region.UNIVERSE, Region.EMPTY)

/** Where a literal applies when its rule's left side is met on [anchors]. */
fun target(lit: Lit, anchors: Region): Region {
    if (unboundReach(lit, anchors)) return Region.UNIVERSE
    val base = if (lit.anchor == "p") anchors else anchors.leftEdges()
    return if (lit.isPoint) base.shift(lit.rel!!) else base.dilate(lit.where)
}

/** A literal relative to l reaching +inf, on an interval of [anchors] with no left end: l + [a, +inf) is the whole line. */
fun unboundReach(lit: Lit, anchors: Region): Boolean =
    lit.anchor == "l" && lit.where.hi == Pos.INF && anchors.inf() == Pos.NEG_INF

fun kindsOf(rs: RuleSet, prob: Problem): List<String> {
    val extra = ((prob.pos.keys + prob.neg.keys) - rs.order.toSet()).sorted()
    return rs.order + extra
}

/** (plain, exact): where a kind is required present, and at exact percentages. */
typealias Required = Pair<Region, Map<Rational, Region>>

class RegionStage(val vals: Map<String, Map<Rational, Region>>, val fixed: Map<String, Required>, val forbid: Map<String, Region>)

/** Kinds outside [kindsIn] computed here, group after group ([close]). */
fun regionStage(rs: RuleSet, prob: Problem, kindsIn: Set<String>, check: Boolean = true): RegionStage {
    val static = rs.element.indices.filter { (rs.element[it].lhs.kinds().toSet() intersect kindsIn).isEmpty() }
    val lits = static.flatMap { i -> rs.element[i].rhs.filter { !it.isOr && !it.options[0].negated }.map { i to it.options[0] } }
    val out = kindsOf(rs, prob).filter { it !in kindsIn }
    val vals = close(rs, out, lits.filter { it.second.kind !in kindsIn }, prob.pos, emptyMap(), emptyMap(), check)
    val anchors = HashMap<Int, Region>()
    fun met(i: Int) = anchors.getOrPut(i) { lhsRegion(rs.element[i].lhs, vals) }
    val fixed = LinkedHashMap<String, Pair<Region, MutableMap<Rational, Region>>>()
    for (k in kindsOf(rs, prob)) if (k in kindsIn) fixed[k] = Region.EMPTY to LinkedHashMap()
    for ((i, o) in lits) {
        if (o.kind !in kindsIn) continue
        val (plain, exact) = fixed.getValue(o.kind)
        val t = target(o, met(i))
        if (o.value == null) fixed[o.kind] = (plain or t) to exact else exact[o.value] = (exact[o.value] ?: Region.EMPTY) or t
    }
    val pres = vals.mapValues { union(it.value.values) }
    val forbid = LinkedHashMap(prob.neg)
    for (i in static) for (it in rs.element[i].rhs) {
        val o = it.options[0]
        if (!it.isOr && o.negated) forbid[o.kind] = (forbid[o.kind] ?: Region.EMPTY) or target(o, met(i))
    }
    for ((k, f) in forbid) {
        if (check && k !in kindsIn) {
            val bad = (pres[k] ?: Region.EMPTY) and f
            if (bad.isNotEmpty()) throw Conflict("$k is required to be absent on $bad")
        }
    }
    return RegionStage(vals, fixed, forbid.filterKeys { it in kindsIn })
}

private fun values(
    kind: String,
    claims: Map<Rational, Region>,
    plain: Region,
    exact: Map<Rational, Region>,
    check: Boolean = true,
): Map<Rational, Region> {
    val claimed = union(claims.values)
    val out = LinkedHashMap(claims)
    val exs = exact.keys.sorted()
    for ((i, p) in exs.withIndex()) {
        for ((v, c) in claims) {
            if (check && v != p) {
                val both = exact.getValue(p) and c
                if (both.isNotEmpty()) throw Conflict("$kind is required at $p% where it is $v%: $both")
            }
        }
        for (q in exs.subList(i + 1, exs.size)) {
            if (check) {
                val both = exact.getValue(p) and exact.getValue(q)
                if (both.isNotEmpty()) throw Conflict("$kind is required at $p% and $q% on $both")
            }
        }
        out[p] = (out[p] ?: Region.EMPTY) or (exact.getValue(p) - claimed)
    }
    val rest = plain - claimed - union(exact.values)
    out[HUNDRED] = (out[HUNDRED] ?: Region.EMPTY) or rest
    return out
}

// ====================================================================== the kinds in order

/** kind -> where it is present, computed from the (growing) values when first read. */
internal class PresenceMap(private val vals: Map<String, Map<Rational, Region>>) : AbstractMap<String, Region>() {
    private val cache = HashMap<String, Region>()

    override fun get(key: String): Region? {
        cache[key]?.let { return it }
        val d = vals[key] ?: return null
        return union(d.values).also { cache[key] = it }
    }

    override fun containsKey(key: String): Boolean = key in cache || key in vals

    override val entries: Set<Map.Entry<String, Region>>
        get() = vals.keys.associateWith { get(it)!! }.entries
}

/**
 * The values of [kinds] (closed under the rules): the least state that contains their claims and their [extra]
 * contributions (kind -> (plain, exact)), where each literal of [lits] holds wherever its left side is met. Other
 * kinds are read from [base]. The left edges of anchors relative to l are kept within [mask], if given; [met], if
 * given, receives where the left sides are met. Raises [Conflict] (if [check]).
 */
fun close(
    rs: RuleSet,
    kinds: List<String>,
    lits: List<Pair<Int, Lit>>,
    claims: Vals,
    base: Vals,
    extra: Map<String, Required>,
    check: Boolean = true,
    mask: Region? = null,
    met: MutableMap<Int, Region>? = null,
): Map<String, Map<Rational, Region>> {
    val vals = LinkedHashMap(base)
    val pres = PresenceMap(vals)
    val byKind = LinkedHashMap<String, MutableList<Pair<Int, Lit>>>()
    for (x in lits) byKind.getOrPut(x.second.kind) { ArrayList() }.add(x)
    val want = kinds.toSet()
    val blocks = rs.blocks.filter { it[0] in want } + kinds.filter { it !in rs.blockOf }.map { listOf(it) }
    for (block in blocks) {
        checkpoint()
        val gLits = block.flatMap { byKind[it].orEmpty() }
        val state = groupStep(rs, block, gLits, vals, pres, extra, mask, met ?: HashMap())
        for (k in block) {
            val (plain, exact) = state.getValue(k)
            vals[k] = values(k, claims[k].orEmpty(), plain, exact, check)
        }
    }
    return kinds.associateWith { vals.getValue(it) }
}

/** One group (one kind: the rules have no cycle): the state the rules require, absences read from the same values. */
private fun groupStep(
    rs: RuleSet,
    block: List<String>,
    lits: List<Pair<Int, Lit>>,
    vals: Map<String, Map<Rational, Region>>,
    pres: Map<String, Region>,
    extra: Map<String, Required>,
    mask: Region?,
    met: MutableMap<Int, Region>,
): Map<String, Required> {
    val u = Region.UNIVERSE
    val view = View(vals, pres)
    val plain = HashMap<String, Region>()
    val exact = HashMap<String, MutableMap<Rational, Region>>()
    for (k in block) {
        val e = extra[k]
        plain[k] = e?.first ?: Region.EMPTY
        exact[k] = LinkedHashMap(e?.second.orEmpty())
    }
    for ((i, o) in lits) {
        val lhs = rs.element[i].lhs
        val m = met.getOrPut(i) { lhs.region2(view, view, u, Region.EMPTY) }
        val t = if (o.anchor == "l" && !unboundReach(o, m)) {
            var edges = m.leftEdges()
            if (mask != null) edges = edges and mask
            if (o.isPoint) edges.shift(o.rel!!) else edges.dilate(o.where)
        } else {
            target(o, m)
        }
        if (o.value == null) {
            plain[o.kind] = plain.getValue(o.kind) or t
        } else {
            val ex = exact.getValue(o.kind)
            ex[o.value] = (ex[o.value] ?: Region.EMPTY) or t
        }
    }
    return block.associateWith { plain.getValue(it) to exact.getValue(it) }
}

// ====================================================================== lattice stage

/** What is fixed about one kind at one point. */
data class KP(
    val claim: Rational? = null,
    val fixed: Boolean = false,
    val exacts: Set<Rational> = emptySet(),
    val forbidden: Boolean = false,
)

/** A lattice rule: the names whose presence makes the left side ("!K": K absent), the item, its "or" rank. */
class LRule(val names: List<String>, val item: Item, val rank: Int?)

private class Def(val lhs: IntArray, val kind: Int, val d: Int, val value: Rational?)
private class Neg(val lhs: IntArray, val kind: Int, val d: Int)
private class OrOpt(val kind: Int, val d: Int, val value: Rational?)
private class OrRef(val oi: Int, val t: Int, val d: Int, val value: Rational?)

/** Atoms on points a..b: present (true/false, or null: unknown) and exact values. */
private class Evaluation(
    val present: Array<Array<Boolean?>>,
    val exs: Map<Long, Set<Rational>>,
    val pats: List<List<KP>>,
    val bad: Boolean,
)

private fun key(i: Int, ki: Int): Long = (i.toLong() shl 32) or (ki.toLong() and 0xffffffffL)

/** The rules acting on one lattice, offsets in lattice steps. */
private class Spec(val kinds: List<String>, kindsIn: Set<String>, g: Rational, lrules: List<LRule>, mirror: Boolean = false) {
    val idx = kinds.withIndex().associate { it.value to it.index }
    val inOrder = kinds.filter { it in kindsIn }
    val defs = ArrayList<Def>()
    val negs = ArrayList<Neg>()
    val ors = ArrayList<Pair<IntArray, List<OrOpt>>>()
    val orRanks = ArrayList<Int?>()
    val hasNeg = lrules.any { r -> r.names.any { it.startsWith("!") } }
    val rho: Int
    val nOpts: List<Int>
    val byKindDefs: Map<Int, List<Def>>
    val byKindOrs: Map<Int, List<OrRef>>
    val inIdx: List<Int>
    val independent: Boolean

    init {
        val sgn = if (mirror) -1 else 1
        for (r in lrules) {
            val lhs = r.names.map { if (it.startsWith("!")) idx.getValue(it.substring(1)).inv() else idx.getValue(it) }.toIntArray()
            if (r.item.isOr) {
                ors.add(lhs to r.item.options.map { o -> OrOpt(idx.getValue(o.kind), sgn * steps(o.rel!!.x, g), o.value) })
                orRanks.add(r.rank)
            } else {
                val o = r.item.options[0]
                val d = sgn * steps(o.rel!!.x, g)
                if (o.negated) negs.add(Neg(lhs, idx.getValue(o.kind), d)) else defs.add(Def(lhs, idx.getValue(o.kind), d, o.value))
            }
        }
        // dist[K]: how far (in steps) an atom of K can be from the choices it depends on
        val dist = IntArray(kinds.size)
        fun far(lhs: IntArray) = lhs.maxOfOrNull { dist[if (it >= 0) it else it.inv()] } ?: 0
        for (k in inOrder) {
            val ki = idx.getValue(k)
            for (x in defs) if (x.kind == ki) dist[ki] = maxOf(dist[ki], far(x.lhs) + kotlin.math.abs(x.d))
            for ((lhs, os) in ors) for (o in os) if (o.kind == ki) dist[ki] = maxOf(dist[ki], far(lhs) + kotlin.math.abs(o.d))
        }
        val dmax = dist.maxOrNull() ?: 0
        rho = (listOf(2 * dmax) + negs.map { far(it.lhs) + kotlin.math.abs(it.d) + dist[it.kind] }).max()
        nOpts = ors.map { it.second.size }
        byKindDefs = kinds.indices.associateWith { i -> defs.filter { it.kind == i } }
        byKindOrs = kinds.indices.associateWith { i ->
            ors.withIndex().flatMap { (oi, p) -> p.second.withIndex().filter { it.value.kind == i }.map { (t, o) -> OrRef(oi, t, o.d, o.value) } }
        }
        inIdx = inOrder.map { idx.getValue(it) }
        independent = computeIndependent()
    }

    private fun steps(x: Rational, g: Rational): Int = (x / g).let { q -> if (q.sign >= 0) q.floor() else -((-q).floor()) }.toInt()

    /** No violation can involve the choices of two different positions. */
    private fun computeIndependent(): Boolean {
        if (hasNeg) return false
        val inset = inIdx.toSet()
        val none: Set<Pair<Set<Int>, Rational?>> = setOf(emptySet<Int>() to null)
        val prods = HashMap<Int, Set<Pair<Set<Int>, Rational?>>>()
        for (ki in inIdx) prods[ki] = none
        fun combos(lhs: IntArray): List<List<Pair<Set<Int>, Rational?>>> {
            var out = listOf(emptyList<Pair<Set<Int>, Rational?>>())
            for (l in lhs) {
                val choices = if (l in inset) prods.getValue(l) else none
                out = out.flatMap { a -> choices.map { a + it } }
            }
            return out
        }
        for (ki in inIdx) {
            val out = LinkedHashSet(none)
            for (x in defs) if (x.kind == ki) for (combo in combos(x.lhs)) {
                out.add(combo.flatMap { (pat, _) -> pat.map { x.d + it } }.toSet() to x.value)
            }
            for ((lhs, os) in ors) for (o in os) if (o.kind == ki) for (combo in combos(lhs)) {
                out.add((setOf(o.d) + combo.flatMap { (pat, _) -> pat.map { o.d + it } }) to o.value)
            }
            if (out.size > 64) return false
            prods[ki] = out
        }
        for (ps in prods.values) {
            if (ps.any { it.first.size > 1 }) return false
            val vs = ps.filter { it.second != null }
            if (vs.any { (p1, v1) -> vs.any { (p2, v2) -> v1 != v2 && (p1 + p2).size > 1 } }) return false
        }
        for (n in negs) {
            for (combo in combos(n.lhs)) {
                val pat = combo.flatMap { it.first }.toSet()
                for ((q, _) in (if (n.kind in inset) prods.getValue(n.kind) else none)) {
                    if ((pat + q.map { it - n.d }).size > 1) return false
                }
            }
        }
        return true
    }

    fun evaluate(pat: (Int) -> List<KP>, choose: (Int, Int) -> Int?, a: Int, b: Int, ca: Int, cb: Int, optimistic: Boolean = false): Evaluation {
        if (hasNeg) return evaluate3(pat, if (optimistic) { _, _ -> null } else choose, a, b, ca, cb)
        val n = b - a + 1
        val pats = List(n) { pat(a + it) }
        val present = Array(n) { i -> Array<Boolean?>(kinds.size) { ki -> pats[i][ki].fixed || pats[i][ki].claim != null } }
        val exs = HashMap<Long, Set<Rational>>()
        for (ki in inIdx) {
            val defsK = byKindDefs.getValue(ki)
            val orsK = byKindOrs.getValue(ki)
            for (i in 0 until n) {
                val kp = pats[i][ki]
                var pr = kp.fixed || kp.claim != null
                val ex = LinkedHashSet(kp.exacts)
                for (x in defsK) {
                    val j = i - x.d
                    if (j in 0 until n && x.lhs.all { present[j][it] == true }) {
                        pr = true
                        if (x.value != null) ex.add(x.value)
                    }
                }
                for (x in orsK) {
                    val j = i - x.d
                    if (j in 0 until n && ors[x.oi].first.all { present[j][it] == true } && (optimistic || choose(a + j, x.oi) == x.t)) {
                        pr = true
                        if (x.value != null) ex.add(x.value)
                    }
                }
                present[i][ki] = pr
                if (ex.isNotEmpty()) exs[key(i, ki)] = ex
            }
        }
        var bad = false
        loop@ for (i in maxOf(0, ca - a) until minOf(n, cb - a + 1)) {
            val p = pats[i]
            val row = present[i]
            for (ki in inIdx) {
                if (row[ki] == true) {
                    val kp = p[ki]
                    if (kp.forbidden) {
                        bad = true
                        break@loop
                    }
                    val ex = exs[key(i, ki)]
                    if (ex != null && (ex.size > 1 || (kp.claim != null && kp.claim !in ex))) {
                        bad = true
                        break@loop
                    }
                }
            }
            for (x in negs) {
                val t = i + x.d
                if (t in 0 until n && present[t][x.kind] == true && x.lhs.all { row[it] == true }) {
                    bad = true
                    break@loop
                }
            }
        }
        return Evaluation(present, exs, pats, bad)
    }

    /** A left side on one point, with three values: true, false, null (unknown). */
    fun lhsValue(row: Array<Boolean?>, lhs: IntArray): Boolean? {
        var out: Boolean? = true
        for (l in lhs) {
            val v = if (l >= 0) row[l] else row[l.inv()]?.let { !it }
            if (v == false) return false
            if (v == null) out = null
        }
        return out
    }

    /** [evaluate] when a left side reads a chosen kind as absent: a missing choice is unknown, not absent. */
    fun evaluate3(pat: (Int) -> List<KP>, choose: (Int, Int) -> Int?, a: Int, b: Int, ca: Int, cb: Int): Evaluation {
        val n = b - a + 1
        val pats = List(n) { pat(a + it) }
        val present = Array(n) { i -> Array<Boolean?>(kinds.size) { ki -> pats[i][ki].fixed || pats[i][ki].claim != null } }
        val exs = HashMap<Long, Set<Rational>>()
        for (ki in inIdx) {
            val defsK = byKindDefs.getValue(ki)
            val orsK = byKindOrs.getValue(ki)
            for (i in 0 until n) {
                val kp = pats[i][ki]
                var pr = kp.fixed || kp.claim != null
                var unknown = false
                val ex = LinkedHashSet(kp.exacts)
                for (x in defsK) {
                    val j = i - x.d
                    val c = if (j in 0 until n) lhsValue(present[j], x.lhs) else null
                    if (c == true) {
                        pr = true
                        if (x.value != null) ex.add(x.value)
                    } else if (c == null) {
                        unknown = true
                    }
                }
                for (x in orsK) {
                    val j = i - x.d
                    val c = if (j in 0 until n) lhsValue(present[j], ors[x.oi].first) else null
                    if (c == false) continue
                    val took = if (j in 0 until n) choose(a + j, x.oi) else null
                    if (c == true && took == x.t) {
                        pr = true
                        if (x.value != null) ex.add(x.value)
                    } else if (took == null || took == x.t) {
                        unknown = true
                    }
                }
                present[i][ki] = if (pr) true else if (unknown) null else false
                if (ex.isNotEmpty()) exs[key(i, ki)] = ex
            }
        }
        var bad = false
        loop@ for (i in maxOf(0, ca - a) until minOf(n, cb - a + 1)) {
            val p = pats[i]
            val row = present[i]
            for (ki in inIdx) {
                if (row[ki] == true) {
                    val kp = p[ki]
                    val ex = exs[key(i, ki)]
                    if (kp.forbidden || (ex != null && (ex.size > 1 || (kp.claim != null && kp.claim !in ex)))) {
                        bad = true
                        break@loop
                    }
                }
            }
            for (x in negs) {
                val t = i + x.d
                if (t in 0 until n && present[t][x.kind] == true && lhsValue(row, x.lhs) == true) {
                    bad = true
                    break@loop
                }
            }
        }
        return Evaluation(present, exs, pats, bad)
    }

    /** Some choice could meet this left side on this point. */
    fun met(row: Array<Boolean?>, lhs: IntArray): Boolean =
        if (hasNeg) lhsValue(row, lhs) != false else lhs.all { row[it] == true }

    fun value(e: Evaluation, i: Int, ki: Int): Rational? {
        val p = e.present[i][ki] ?: throw Undetermined("an atom of the lattice is not decided where its value is read")
        if (!p) return null
        val kp = e.pats[i][ki]
        if (kp.claim != null) return kp.claim
        val ex = e.exs[key(i, ki)]
        return if (ex != null && ex.isNotEmpty()) ex.first() else HUNDRED
    }
}

/** The translation-invariant part of a lattice beyond its decided core. */
private class Ray(val spec: Spec, val pattern: List<KP>, val vars: List<Int>, val w: Int) {
    val options: List<List<Int>> = vars.fold(listOf(emptyList())) { acc, oi -> acc.flatMap { a -> (0 until spec.nOpts[oi]).map { a + it } } }
    private val goodMemo = HashMap<List<List<Int>>, Boolean>()
    private val validMemo = HashMap<Pair<List<List<Int>>, List<Int>>, Boolean>()
    private val posOf = vars.withIndex().associate { it.value to it.index }

    fun valid(state: List<List<Int>>, v: List<Int>): Boolean = validMemo.getOrPut(state to v) {
        val seq = state + listOf(v)
        val choose = { k: Int, oi: Int ->
            val vec = seq.getOrNull(k)
            val p = posOf[oi]
            if (vec != null && p != null) vec[p] else null
        }
        !spec.evaluate({ pattern }, choose, 0, w, 0, w).bad
    }

    fun successors(state: List<List<Int>>): List<Pair<List<Int>, List<List<Int>>>> =
        options.filter { valid(state, it) }.map { it to (state.drop(1) + listOf(it)) }

    /** Some infinite sequence of valid choices leaves this state. */
    fun good(state: List<List<Int>>): Boolean {
        goodMemo[state]?.let { return it }
        val seen = LinkedHashSet<List<List<Int>>>()
        seen.add(state)
        val stack = arrayListOf(state)
        val succ = HashMap<List<List<Int>>, List<List<List<Int>>>>()
        while (stack.isNotEmpty()) {
            val s = stack.removeAt(stack.size - 1)
            succ[s] = successors(s).map { it.second }
            for (t in succ.getValue(s)) if (t !in seen && t !in goodMemo) {
                seen.add(t)
                stack.add(t)
            }
        }
        val alive = LinkedHashSet(seen)
        var changed = true
        while (changed) {
            changed = false
            for (s in alive.toList()) {
                if (succ.getValue(s).none { t -> if (t in seen) t in alive else goodMemo.getValue(t) }) {
                    alive.remove(s)
                    changed = true
                }
            }
        }
        for (s in seen) goodMemo[s] = s in alive
        return goodMemo.getValue(state)
    }

    /** Greedy choices from a good state: (prefix, cycle) of choice vectors. */
    fun walk(state0: List<List<Int>>): Pair<List<List<Int>>, List<List<Int>>> {
        var state = state0
        val seen = HashMap<List<List<Int>>, Int>()
        val seq = ArrayList<List<Int>>()
        while (state !in seen) {
            seen[state] = seq.size
            val next = successors(state).firstOrNull { good(it.second) } ?: throw AssertionError("a good state has a good successor")
            seq.add(next.first)
            state = next.second
        }
        val start = seen.getValue(state)
        return seq.subList(0, start) to seq.subList(start, seq.size)
    }
}

private class Atoms(val sL: Int, val lL: Int, val sR: Int, val lR: Int, val out: Map<Int, Map<Int, Rational>>)

/** One lattice r + gZ of a piece of residues. */
private class Lattice(val lp: LatticeProblem, val r: Pos) {
    val kmin: Int
    val kmax: Int
    val r2: Int = 3 * lp.spec.rho + 1
    val pats: List<List<KP>>
    var vars: List<Pair<Int, Int>> = emptyList()
    var rayVars: Pair<List<Int>, List<Int>> = emptyList<Int>() to emptyList()
    lateinit var rays: Pair<Ray, Ray>
    val assign = HashMap<Pair<Int, Int>, Int>()
    lateinit var walks: Pair<Pair<List<List<Int>>, List<List<Int>>>, Pair<List<List<Int>>, List<List<Int>>>>

    init {
        val g = Pos(lp.g)
        kmin = -(r - lp.lo).floorDiv(g).toInt()
        kmax = (Pos(lp.hi) - r).floorDiv(g).toInt()
        pats = lp.patterns(r, kmin - 1, kmax + 1)
    }

    fun x(k: Int): Pos = r + lp.g * Rational.of(k)

    /** Constant beyond the core. */
    fun pat(k: Int): List<KP> = pats[minOf(maxOf(k, kmin - 1), kmax + 1) - kmin + 1]

    fun solve(): Boolean {
        val spec = lp.spec
        val lo = kmin - 2 * r2
        val hi = kmax + 2 * r2
        val opt = spec.evaluate(::pat, { _, _ -> null }, lo, hi, lo, hi, optimistic = true)
        val vs = ArrayList<Pair<Int, Int>>()
        for (i in 0..(hi - lo)) {
            val k = lo + i
            if (k in kmin..kmax) for ((oi, p) in spec.ors.withIndex()) if (spec.met(opt.present[i], p.first)) vs.add(k to oi)
        }
        val midR = kmax + r2
        val midL = kmin - r2
        rayVars = spec.ors.indices.filter { spec.met(opt.present[midL - lo], spec.ors[it].first) } to
            spec.ors.indices.filter { spec.met(opt.present[midR - lo], spec.ors[it].first) }
        // the closest to 0 first, the one on the right if two are as close
        vars = vs.sortedWith(compareBy<Pair<Int, Int>>({ x(it.first).abs() }, { x(it.first) < Pos.ZERO }, { spec.orRanks[it.second] ?: 0 }))
        if (spec.evaluate(::pat, { _, _ -> null }, lo, hi, lo, hi).bad) return false
        rays = Ray(lp.mirrorSpec, pat(kmin - 1), rayVars.first, lp.w) to Ray(spec, pat(kmax + 1), rayVars.second, lp.w)
        if (!dfs()) return false
        walks = rays.first.walk(boundary(0)) to rays.second.walk(boundary(1))
        return true
    }

    fun boundary(side: Int): List<List<Int>> {
        val w = lp.w
        val ks = if (side == 1) (kmax - w + 1..kmax).toList() else (kmin + w - 1 downTo kmin).toList()
        val rv = if (side == 1) rayVars.second else rayVars.first
        return ks.map { k -> rv.map { oi -> assign[k to oi] ?: 0 } }
    }

    /** The first choices that work, in the order of [vars], with its own stack. */
    private fun dfs(): Boolean {
        val spec = lp.spec
        val n = vars.size
        val tried = IntArray(n + 1)
        var i = 0
        while (i >= 0) {
            checkpoint()
            if (i == n) {
                if (rays.first.good(boundary(0)) && rays.second.good(boundary(1))) return true
                i--
                continue
            }
            val (k, oi) = vars[i]
            var found = false
            while (tried[i] < spec.nOpts[oi] && !found) {
                assign[k to oi] = tried[i]
                tried[i]++
                found = !spec.evaluate(::pat, { kk, o -> assign[kk to o] }, k - 2 * r2, k + 2 * r2, k - r2, k + r2).bad
            }
            if (found) {
                i++
            } else {
                assign.remove(k to oi)
                tried[i] = 0
                i--
            }
        }
        return false
    }

    fun choose(k: Int, oi: Int): Int? {
        if (k in kmin..kmax) return assign[k to oi]
        val side = if (k > kmax) 1 else 0
        val i = if (side == 1) k - kmax - 1 else kmin - k - 1
        val (prefix, cycle) = if (side == 1) walks.second else walks.first
        val vec = if (i < prefix.size) prefix[i] else cycle[(i - prefix.size) % cycle.size]
        val vs = if (side == 1) rayVars.second else rayVars.first
        val p = vs.indexOf(oi)
        return if (p >= 0) vec[p] else null
    }

    fun atoms(): Atoms {
        val spec = lp.spec
        val (pl, cl) = walks.first
        val (pr, cr) = walks.second
        val ll = cl.size
        val lr = cr.size
        val sL = kmin - pl.size - r2 - 1
        val sR = kmax + pr.size + r2 + 1
        val a = sL - ll - 2 * r2
        val b = sR + lr + 2 * r2
        val e = spec.evaluate(::pat, ::choose, a, b, a, a - 1)
        val out = HashMap<Int, Map<Int, Rational>>()
        for (k in (sL - ll) until (sR + lr)) {
            val i = k - a
            val m = LinkedHashMap<Int, Rational>()
            for (ki in spec.inIdx) if (e.present[i][ki] == true) {
                val v = spec.value(e, i, ki)
                if (v != null) m[ki] = v
            }
            out[k] = m
        }
        return Atoms(sL, ll, sR, lr, out)
    }
}

/** The regions a lattice reads of one kind: claims, where it is fixed, exact values, where it is forbidden. */
class LatticeRegions(
    val claims: Map<Rational, Region> = emptyMap(),
    val fixed: Region? = null,
    val exact: Map<Rational, Region> = emptyMap(),
    val forbidden: Region? = null,
) {
    fun all(): List<Region> = claims.values + listOfNotNull(fixed) + exact.values + listOfNotNull(forbidden)
}

private data class SameKey(val zero: Boolean, val side: Int, val kmin: Int, val kmax: Int, val pats: List<List<KP>>)

/** The kinds of kindsIn, on every lattice, given the rest as region patterns. */
private class LatticeProblem(val kinds: List<String>, kindsIn: Set<String>, val regions: Map<String, LatticeRegions>, lrules: List<LRule>) {
    val g: Rational = latticeStep(lrules.flatMap { it.item.options }) ?: freeStep(regions)
    val spec = Spec(kinds, kindsIn, g, lrules)
    val mirrorSpec = Spec(kinds, kindsIn, g, lrules, mirror = true)
    val w = spec.rho + 1
    val residues: MutableSet<Pos>
    val lo: Rational
    val hi: Rational
    private val ivs: List<Pair<Region, List<Interval>>>

    init {
        val gp = Pos(g)
        val allRegions = regions.values.flatMap { it.all() }
        val bounds = ArrayList<Rational>()
        residues = linkedSetOf(Pos.ZERO, Pos(g / 2))
        for (reg in allRegions) {
            if (reg.isEmpty() || reg.isEverything()) continue
            bounds.add(reg.lo.x)
            bounds.add(reg.hi.x)
            for (iv in reg.core.intervals) for (e in listOf(iv.lo, iv.hi)) if (e.isFinite) {
                bounds.add(e.x)
                residues.add(e.mod(gp))
            }
            for ((pat, period, base) in listOf(Triple(reg.left, reg.pl, reg.lo), Triple(reg.right, reg.pr, reg.hi))) {
                val full = pat == IntervalSet.of(Interval.of(Pos.ZERO, period, true, false))
                if (pat.isNotEmpty() && !full) {
                    if (!period.mod(gp).isZero) throw IllegalArgumentException("a repeating pattern whose period is not a multiple of the lattice step")
                    for (iv in pat.intervals) for (e in listOf(iv.lo, iv.hi)) residues.add((e + base).mod(gp))
                }
            }
            residues.add(reg.lo.mod(gp))
            residues.add(reg.hi.mod(gp))
        }
        // the window is around the data: where 0 is means nothing
        val margin = g * Rational.of(2 * w + 2 * spec.rho + 4)
        if (withoutEnd(regions, lrules)) bounds.add(Rational.ZERO)
        var first = bounds.minOrNull() ?: Rational.ZERO
        var last = bounds.maxOrNull() ?: Rational.ZERO
        if (first < Rational.ZERO && Rational.ZERO < last) {
            val m = maxOf(-first, last)
            first = -m
            last = m
        }
        lo = first - margin
        hi = last + margin
        val a = Pos(lo - g * Rational.of(4))
        val b = Pos(hi + g * Rational.of(4))
        ivs = allRegions.map { it to it.on(a, b).intervals }
    }

    private fun ivsOf(reg: Region): List<Interval> = ivs.first { it.first === reg }.second

    /** (residues, representative) covering [0, g). */
    fun pieces(): List<Pair<IntervalSet, Pos>> {
        val rs = residues.sorted()
        val out = ArrayList<Pair<IntervalSet, Pos>>()
        for ((i, a) in rs.withIndex()) {
            out.add(IntervalSet.of(Interval.point(a)) to a)
            val b = if (i + 1 < rs.size) rs[i + 1] else Pos(g)
            if (a + Pos.EPS < b) {
                val rep = if (a.x < b.x) Pos((a.x + b.x) / 2) else a + Pos.EPS
                out.add(IntervalSet.of(Interval.open(a, b)) to rep)
            }
        }
        return out
    }

    /** The patterns at r + k g for k = k0..k1. */
    fun patterns(r: Pos, k0: Int, k1: Int): List<List<KP>> {
        val n = k1 - k0 + 1
        fun where(reg: Region): List<Int> {
            val iv = ivsOf(reg)
            val out = ArrayList<Int>()
            if (iv.size > n) {
                val los = iv.map { it.lo }
                val start = maxOf(firstK(iv[0].lo, r, g, k0), k0) - k0
                val end = minOf(lastK(iv.last().hi, r, g, k1), k1) - k0
                for (i in start..end) {
                    val x = r + g * Rational.of(k0 + i)
                    var lo2 = 0
                    var hi2 = los.size
                    while (lo2 < hi2) {
                        val mid = (lo2 + hi2) / 2
                        if (x < los[mid]) hi2 = mid else lo2 = mid + 1
                    }
                    val j = lo2 - 1
                    if (j >= 0 && iv[j].contains(x)) out.add(i)
                }
                return out
            }
            for (x in iv) {
                val a = maxOf(firstK(x.lo, r, g, k0), k0)
                val b = minOf(lastK(x.hi, r, g, k1), k1)
                for (i in (a - k0)..(b - k0)) out.add(i)
            }
            return out
        }
        val cols = kinds.map { k ->
            val d = regions[k]
            val claim = arrayOfNulls<Rational>(n)
            val fixed = BooleanArray(n)
            val exacts = Array<Set<Rational>>(n) { emptySet() }
            val forb = BooleanArray(n)
            if (d != null) {
                for ((v, reg) in d.claims) for (i in where(reg)) claim[i] = v
                d.fixed?.let { for (i in where(it)) fixed[i] = true }
                for ((v, reg) in d.exact) for (i in where(reg)) exacts[i] = exacts[i] + v
                d.forbidden?.let { for (i in where(it)) forb[i] = true }
            }
            listOf(claim, fixed, exacts, forb)
        }
        return List(n) { i ->
            cols.map { c ->
                @Suppress("UNCHECKED_CAST")
                val ex = (c[2] as Array<Set<Rational>>)[i]
                KP((c[0] as Array<Rational?>)[i], (c[1] as BooleanArray)[i] || ex.isNotEmpty(), ex, (c[3] as BooleanArray)[i])
            }
        }
    }

    /** Regions of the kinds of kindsIn; raises [Conflict]. */
    fun solve(): Map<String, Map<Rational, Region>> {
        val gp = Pos(g)
        val results = ArrayList<Pair<IntervalSet, Atoms>>()
        val same = HashMap<SameKey, Atoms>()
        val half = Pos(g / 2)
        for ((piece, r) in pieces()) {
            val lat = Lattice(this, r)
            val key = SameKey(r == Pos.ZERO, r.compareTo(half).coerceIn(-1, 1), lat.kmin, lat.kmax, lat.pats)
            if (key !in same) {
                if (!lat.solve()) throw Conflict("no choice of 'or' operands satisfies the rules on the lattice $r + ${g}Z")
                same[key] = lat.atoms()
            }
            results.add(piece to same.getValue(key))
        }
        if (results.isEmpty()) return emptyMap()
        val sl = results.minOf { it.second.sL }
        val sr = results.maxOf { it.second.sR }
        val pl = results.map { it.second.lL }.fold(1) { a, b -> lcm(a, b) }
        val pr = results.map { it.second.lR }.fold(1) { a, b -> lcm(a, b) }
        val parts = LinkedHashMap<Pair<Int, Rational>, Triple<MutableList<Interval>, MutableList<Interval>, MutableList<Interval>>>()
        for ((piece, at) in results) {
            fun atK(k: Int): Map<Int, Rational> = when {
                k >= at.sR -> at.out.getValue(at.sR + (k - at.sR).mod(at.lR))
                k < at.sL -> at.out.getValue(at.sL - at.lL + (k - at.sL).mod(at.lL))
                else -> at.out.getValue(k)
            }
            for (k in (sl - pl) until (sr + pr)) {
                for ((ki, v) in atK(k)) {
                    val (core, left, right) = parts.getOrPut(ki to v) { Triple(ArrayList(), ArrayList(), ArrayList()) }
                    when {
                        k < sl -> left.addAll(piece.shift(gp * (k - sl + pl)).intervals)
                        k < sr -> core.addAll(piece.shift(gp * k).intervals)
                        else -> right.addAll(piece.shift(gp * (k - sr)).intervals)
                    }
                }
            }
        }
        val res = LinkedHashMap<String, MutableMap<Rational, Region>>()
        for ((kv, p) in parts) {
            val (ki, v) = kv
            res.getOrPut(kinds[ki]) { LinkedHashMap() }[v] = Region.make(
                gp * sl, gp * sr, IntervalSet.of(p.first), gp * pl, IntervalSet.of(p.second), gp * pr, IntervalSet.of(p.third),
            ).compact()
        }
        return res
    }

    companion object {
        /** Whether choices may go on forever: the first to choose is then the closest to 0. */
        fun withoutEnd(regions: Map<String, LatticeRegions>, lrules: List<LRule>): Boolean {
            for (d in regions.values) {
                val notForbidden = d.claims.values + listOfNotNull(d.fixed) + d.exact.values
                for (reg in notForbidden) if ((reg.left.isNotEmpty() || reg.right.isNotEmpty()) && !reg.isEverything()) return true
            }
            return lrules.any { r -> r.item.isOr && r.names.size == 1 && regions[r.names[0]]?.fixed?.isEverything() == true }
        }

        /** The step when no rule has a distance: the common period of what repeats, else longer than the line. */
        fun freeStep(regions: Map<String, LatticeRegions>): Rational {
            var period: Rational? = null
            var far = Rational.ONE
            for (d in regions.values) for (reg in d.all()) {
                if (reg.isEmpty()) continue
                far = maxOf(far, reg.lo.x.abs() + Rational.ONE, reg.hi.x.abs() + Rational.ONE)
                for ((pat, p) in listOf(reg.left to reg.pl, reg.right to reg.pr)) {
                    if (pat.isNotEmpty() && pat != IntervalSet.of(Interval.of(Pos.ZERO, p, true, false))) {
                        if (p.k != 0L) return Rational.ONE
                        period = if (period == null) p.x else flcmR(period, p.x)
                    }
                }
            }
            return period ?: (far * 2)
        }
    }
}

private fun gcdI(a: Int, b: Int): Int = if (b == 0) kotlin.math.abs(a) else gcdI(b, a % b)

private fun lcm(a: Int, b: Int): Int = a / gcdI(a, b) * b

internal fun flcmR(a: Rational, b: Rational): Rational {
    val d = Rational.lcmLong(a.den, b.den)
    val x = (a * Rational.of(d)).toLongExact()
    val y = (b * Rational.of(d)).toLongExact()
    return Rational.of(mulExact(x / Rational.gcd(x, y), y), d)
}

/** The least k with r + k g >= lo. */
private fun firstK(lo: Pos, r: Pos, g: Rational, default: Int): Int {
    if (lo.isInfinite) return default
    val k = ((lo.x - r.x) / g).ceil()
    return (if (r.x + g * Rational.of(k) == lo.x && r.k < lo.k) k + 1 else k).toInt()
}

/** The greatest k with r + k g <= hi. */
private fun lastK(hi: Pos, r: Pos, g: Rational, default: Int): Int {
    if (hi.isInfinite) return default
    val k = ((hi.x - r.x) / g).floor()
    return (if (r.x + g * Rational.of(k) == hi.x && r.k > hi.k) k - 1 else k).toInt()
}


// ====================================================================== entry point

/** How long a set of rules may take to show a line: beyond, it is refused. */
const val SATISFY_SECONDS = 5.0

/** Lines tried at most. */
const val SATISFY_TRIES = 400

/**
 * Null if a line satisfies these rules, else why the set is refused. A set is accepted only on a line found: the empty
 * line, then the kinds that left sides read claimed on repeating patterns on the grid of the rules' distances.
 */
fun satisfiable(rs: RuleSet): String? {
    val read = rs.order.filter { k -> rs.element.any { k in it.lhs.kinds() } }
    val vs = hashSetOf(HUNDRED)
    for (r in rs.element) {
        for (o in r.lits()) o.value?.let { vs.add(it) }
        val todo = arrayListOf(r.lhs)
        while (todo.isNotEmpty()) {
            when (val f = todo.removeAt(todo.size - 1)) {
                is Atom -> if (f.value != null && f.value.sign != 0) vs.add(f.value)
                is And -> todo.addAll(f.parts)
                is Or -> todo.addAll(f.parts)
                is Not -> todo.add(f.f)
            }
        }
    }
    val values = vs.sorted()
    var h: Rational? = null
    for (r in rs.rules) for (o in r.lits()) for (e in listOf(o.where.lo, o.where.hi)) {
        if (e.isFinite && e.x.sign != 0) h = if (h == null) e.x.abs() else fgcdR(h, e.x.abs())
    }
    val step = h ?: Rational.ONE
    var tries = 0
    return try {
        SolverLimits.limited(SATISFY_SECONDS) {
            for (m in 1..2) {
                val cells = (0 until m).flatMap { k ->
                    val a = Pos(step * Rational.of(k))
                    val b = Pos(step * Rational.of(k + 1))
                    listOf(Interval.point(a), Interval.of(a, b, false, false))
                }
                val choices: List<Rational?> = listOf(null) + values
                val perKind = product(List(cells.size) { choices })
                for (combo in productSeq(List(read.size) { perKind })) {
                    val pos = LinkedHashMap<String, MutableMap<Rational, Region>>()
                    for ((kind, pattern) in read.zip(combo)) {
                        for (v in pattern.filterNotNull().toSet()) {
                            val pat = IntervalSet.of(cells.zip(pattern).filter { it.second == v }.map { it.first })
                            val per = Pos(step * Rational.of(m))
                            pos.getOrPut(kind) { LinkedHashMap() }[v] = Region.make(Pos.ZERO, Pos.ZERO, IntervalSet.EMPTY, per, pat, per, pat).compact()
                        }
                    }
                    tries++
                    try {
                        solve(rs, Problem(pos))
                        return@limited null
                    } catch (e: Conflict) {
                    } catch (e: Undetermined) {
                    } catch (e: IncompatiblePeriods) {
                    }
                    if (tries >= SATISFY_TRIES) return@limited "no line satisfying these rules was found in $SATISFY_TRIES tries"
                }
            }
            "no line satisfying these rules was found"
        }
    } catch (e: Undetermined) {
        "no line satisfying these rules was found in reasonable time"
    }
}

private fun <T> product(lists: List<List<T>>): List<List<T>> =
    lists.fold(listOf(emptyList())) { acc, l -> acc.flatMap { a -> l.map { a + it } } }

/** itertools.product, lazily. */
private fun <T> productSeq(lists: List<List<T>>): Sequence<List<T>> = sequence {
    if (lists.isEmpty()) {
        yield(emptyList())
        return@sequence
    }
    val idx = IntArray(lists.size)
    if (lists.any { it.isEmpty() }) return@sequence
    while (true) {
        yield(lists.indices.map { lists[it][idx[it]] })
        var i = lists.size - 1
        while (i >= 0) {
            idx[i]++
            if (idx[i] < lists[i].size) break
            idx[i] = 0
            i--
        }
        if (i < 0) return@sequence
    }
}

internal fun fgcdR(a: Rational, b: Rational): Rational {
    val d = Rational.lcmLong(a.den, b.den)
    return Rational.of(Rational.gcd((a * Rational.of(d)).toLongExact(), (b * Rational.of(d)).toLongExact()), d)
}

/**
 * Where the line breaks a percentage rule, described, or null. [permanent]: only where adding elements cannot repair
 * it (while the choices are being made, more may come).
 */
fun percentConflict(rs: RuleSet, vals: Vals, permanent: Boolean = false): String? {
    if (rs.percent.isEmpty()) return null
    val pres = LinkedHashMap<String, Region>()
    for ((k, d) in vals) if (!isHidden(k)) pres[k] = union(d.values)
    for ((kind, pr) in rs.percent) {
        for ((v, reg) in vals[kind].orEmpty()) {
            if (reg.isEmpty()) continue
            val opts = pr.options.filter { it.value == v }
            val bad = if (permanent) {
                var b = reg
                for (o in opts) b = b and percentLost(o, kind, vals, pres)
                b
            } else {
                reg - union(opts.map { percentHolds(it, kind, vals, pres) })
            }
            if (bad.isNotEmpty()) return "$kind $v% on $bad breaks the percentage rule $pr"
        }
    }
    return null
}

/** sum of the percentages of the elements other than [kind] -> where. */
private fun sums(kind: String, vals: Vals, pres: Map<String, Region>): Map<Rational, Region> {
    var out: Map<Rational, Region> = mapOf(Rational.ZERO to Region.UNIVERSE)
    for ((k, d) in vals) {
        if (k == kind || k !in pres) continue
        val new = LinkedHashMap<Rational, Region>()
        for ((t, reg) in out) {
            val rest = reg - pres.getValue(k)
            if (rest.isNotEmpty()) new[t] = (new[t] ?: Region.EMPTY) or rest
            for ((v, rv) in d) {
                val both = reg and rv
                if (both.isNotEmpty()) new[t + v] = (new[t + v] ?: Region.EMPTY) or both
            }
        }
        out = new
    }
    return out
}

private fun percentHolds(o: PercentOption, kind: String, vals: Vals, pres: Map<String, Region>): Region = when (val c = o.cond) {
    null -> Region.UNIVERSE
    is PercentCond.Others -> sums(kind, vals, pres)[c.sum] ?: Region.EMPTY
    is PercentCond.Only -> {
        val listed = c.listed.toMap()
        var out = Region.UNIVERSE
        for ((k, v) in listed) out = out and (if (v == null) pres[k] ?: Region.EMPTY else vals[k]?.get(v) ?: Region.EMPTY)
        for ((k, r) in pres) if (k != kind && k !in listed) out -= r
        out
    }
}

private fun percentLost(o: PercentOption, kind: String, vals: Vals, pres: Map<String, Region>): Region = when (val c = o.cond) {
    null -> Region.EMPTY
    is PercentCond.Others -> union(sums(kind, vals, pres).filter { it.key > c.sum }.values)
    is PercentCond.Only -> {
        val listed = c.listed.toMap()
        var out = union(pres.filter { it.key != kind && it.key !in listed }.values)
        for ((k, v) in listed) if (v != null) out = out or union(vals[k].orEmpty().filter { it.key != v }.values)
        out
    }
}

/** Cells of a local "or" solved point by point, before the sweep takes over. */
const val CELLS_MAX = 512

/** Solving one point of a cell (not again by cells). Process-wide, like [SolverLimits]. */
private var byPoint = false

/** The cells where every claim is the same, the empty ones left out; null past [CELLS_MAX]. */
private fun cellsOf(prob: Problem): List<Region>? {
    val inputs = prob.pos.values.flatMap { it.values } + prob.neg.values
    var cells = listOf(Region.UNIVERSE)
    for (r in inputs) {
        if (r.isEmpty()) continue
        val nxt = ArrayList<Region>()
        for (c in cells) for (part in listOf(c and r, c - r)) if (part.isNotEmpty()) nxt.add(part)
        cells = nxt
        if (cells.size > CELLS_MAX) return null
    }
    return cells
}

/** A position of a non-empty region (a lazy one read in windows near where its operands start first). */
internal fun aPoint(r: Region): Pos {
    if (r is Lazy && r.computed == null) {
        for (h in r.hints()) {
            val got = r.on(h - Lazy.PROBE, h + Lazy.PROBE).intervals
            if (got.isNotEmpty()) {
                val iv = got[0]
                return if (iv.lo != Pos.NEG_INF) iv.lo else iv.hi
            }
        }
    }
    if (r.core.isNotEmpty()) {
        val iv = r.core.intervals[0]
        return if (iv.lo != Pos.NEG_INF) iv.lo else iv.hi
    }
    if (r.right.isNotEmpty()) {
        val iv = r.right.intervals[0]
        return if (iv.lo != Pos.NEG_INF) iv.lo + r.hi else iv.hi + r.hi
    }
    if (r.left.isNotEmpty()) {
        val iv = r.left.intervals[0]
        return if (iv.lo != Pos.NEG_INF) iv.lo + r.lo - r.pl else iv.hi + r.lo - r.pl
    }
    throw IllegalArgumentException("an empty region")
}

/** A local rule set's line with an "or", solved at one point of each cell where the claims are the same. */
private fun solveByCells(rs: RuleSet, prob: Problem): Line? {
    val cells = cellsOf(prob) ?: return null
    val out = LinkedHashMap<String, MutableMap<Rational, Region>>()
    byPoint = true
    try {
        for (cell in cells) {
            val x = aPoint(cell)
            val pt = Region.point(x)
            val pos = LinkedHashMap<String, Map<Rational, Region>>()
            for ((k, d) in prob.pos) {
                val m = LinkedHashMap<Rational, Region>()
                for ((v, r) in d) if (r.contains(x)) m[v] = pt
                if (m.isNotEmpty()) pos[k] = m
            }
            val neg = LinkedHashMap<String, Region>()
            for ((k, r) in prob.neg) if (r.contains(x)) neg[k] = pt
            val line = solve(rs, Problem(pos, neg))
            for ((k, d) in line.vals) for ((v, r) in d) if (r.contains(x)) {
                val dk = out.getOrPut(k) { LinkedHashMap() }
                dk[v] = dk[v]?.let { it or cell } ?: cell
            }
        }
    } finally {
        byPoint = false
    }
    return Line(out)
}

/** The line of these claims; raises [Conflict] if the rules cannot be satisfied, [Undetermined] if they do not decide. */
fun solve(rs: RuleSet, prob: Problem): Line {
    val hasOr = rs.orItems.isNotEmpty()
    val kindsIn = if (hasOr) rs.choiceDependent else emptySet()
    val st = regionStage(rs, prob, kindsIn)
    val vals = LinkedHashMap(st.vals)
    var cells: Line? = null
    if (hasOr && rs.local && !byPoint) cells = solveByCells(rs, prob)
    val line = if (cells != null) {
        for (k in kindsIn) {
            val c = cells.vals[k]
            if (!c.isNullOrEmpty()) vals[k] = c else vals.remove(k)
        }
        Line(vals)
    } else if (!hasOr) {
        Line(vals)
    } else {
        solveOr(rs, prob, kindsIn, vals, st.fixed, st.forbid)
    }
    percentConflict(rs, line.vals)?.let { throw Conflict(it) }
    return line
}

/** The sweep's values, given only the kinds linked through the rules to those it decides. */
private fun sweep(
    rs: RuleSet, prob: Problem, kindsIn: Set<String>, vals: Vals, fixed: Map<String, Required>, forbid: Map<String, Region>,
): Map<String, Map<Rational, Region>> {
    val groups = kindsIn.mapNotNull { rs.linked[it] }.toSet()
    if (groups.isEmpty()) return Sweep(rs, prob, vals, fixed, forbid, kindsIn).solve()
    fun <V> keep(d: Map<String, V>): Map<String, V> = d.filter { (k, _) -> rs.linked[k] in groups || k.startsWith("#") }
    val part = Problem(keep(prob.pos), keep(prob.neg))
    return Sweep(rs, part, keep(vals), keep(fixed), keep(forbid), kindsIn).solve()
}

private fun solveOr(
    rs: RuleSet, prob: Problem, kindsIn: Set<String>, vals: MutableMap<String, Map<Rational, Region>>,
    fixed: Map<String, Required>, forbid: Map<String, Region>,
): Line {
    if (!rs.lattice) {
        vals.putAll(sweep(rs, prob, kindsIn, vals, fixed, forbid))
        return Line(vals)
    }
    val kinds = kindsOf(rs, prob)
    val regions = LinkedHashMap<String, LatticeRegions>()
    val lrules = ArrayList<LRule>()
    for ((i, r) in rs.element.withIndex()) {
        val (cd, ncd, ctx) = rs.split[i]
        for ((j, it) in r.rhs.withIndex()) {
            if (!(it.isOr || cd.isNotEmpty() || ncd.isNotEmpty())) continue
            val o = it.options[0]
            val name = "#$i${o.anchor}"
            if (name !in regions) {
                val reg = lhsRegion(ctx, vals)
                regions[name] = LatticeRegions(fixed = if (o.anchor == "l") reg.leftEdges() else reg)
            }
            lrules.add(LRule(cd + ncd.map { "!$it" } + name, it, rs.orRank[i to j]))
            if (o.negated && o.kind !in kindsIn) regions[o.kind] = LatticeRegions(fixed = union(vals[o.kind].orEmpty().values))
        }
    }
    for (k in kinds) if (k in kindsIn) {
        val (plain, exact) = fixed[k] ?: (Region.EMPTY to emptyMap())
        regions[k] = LatticeRegions(prob.pos[k].orEmpty(), plain, exact, forbid[k] ?: Region.EMPTY)
    }
    if (regions.values.any { d -> d.all().any { repeats(it) } }) {
        vals.putAll(sweep(rs, prob, kindsIn, vals, fixed, forbid))
        return Line(vals)
    }
    val lp = LatticeProblem(kinds + regions.keys.filter { it.startsWith("#") }.sorted(), kindsIn, regions, lrules)
    vals.putAll(lp.solve())
    return Line(vals)
}

/** The region has a part beyond its ends that repeats without being constant. */
private fun repeats(reg: Region): Boolean {
    fun full(pat: IntervalSet, period: Pos) = pat == IntervalSet.of(Interval.of(Pos.ZERO, period, true, false))
    return reg.isNotEmpty() && ((reg.right.isNotEmpty() && !full(reg.right, reg.pr)) || (reg.left.isNotEmpty() && !full(reg.left, reg.pl)))
}
