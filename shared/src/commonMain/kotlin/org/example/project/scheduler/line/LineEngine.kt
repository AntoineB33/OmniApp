package org.example.project.scheduler.line

import org.example.project.scheduler.line.LineChoice.Anchor
import org.example.project.scheduler.line.LineChoice.KAtom
import org.example.project.scheduler.line.LineChoice.Letter
import org.example.project.scheduler.line.Region.Companion.EMPTY
import org.example.project.scheduler.line.Region.Companion.FULL

/*
 * Edits, history reduction and the prioritized solver — a port of `C:\code\line_manager\reverse_logic\engine.py`
 * (SEMANTICS.md §§ 2–4).
 *
 * The line is never stored. It is recomputed from the sequence of active edits:
 *
 * 1. Reduction. Each edit contributes a signed integer count per kind and point (Add +1, Remove -1, Move -1 on the
 *    source and +1 on the target). At every point the sequence of contributions is reduced newest first; opposite
 *    signs cancel. Whatever survives at the level of edit e is e's EFFECTIVE claim there: present (> 0) or absent
 *    (< 0).
 * 2. Solving. Levels are processed from newest to oldest. A level's claims are accepted unless they conflict with
 *    what newer levels accepted; conflicting claims (and every same-level claim needed for the conflict) are dropped,
 *    i.e. older elements adapt. The line is the least model of the accepted claims under the rules.
 * 3. An edit is accepted iff, once appended as the newest edit, none of its effective claims is dropped.
 */

/** The edit contradicts the rules by itself; it is not applied. */
class ContradictionError(message: String) : IllegalArgumentException(message)

/** The edit removes or moves an element that is not on the line; it is not applied. */
class MissingElementError(message: String) : IllegalArgumentException(message)

/** The edit cannot be reversed because a later active edit requires it. */
class DependencyError(message: String) : IllegalArgumentException(message)

// ================================================================================================================
// Edit operations
// ================================================================================================================

sealed class LineOp {
    abstract val kind: String
    abstract val region: Region

    /** (sign, region): -1 where the op takes, +1 where it puts. */
    abstract fun contributions(): List<Pair<Int, Region>>

    /** The op that exactly undoes this one (Add<->Remove, Extend<->Retract, Move back). */
    abstract fun inverse(): LineOp

    data class Add(override val kind: String, override val region: Region) : LineOp() {
        override fun contributions() = listOf(+1 to region)
        override fun inverse() = Remove(kind, region)
    }

    data class Remove(override val kind: String, override val region: Region) : LineOp() {
        override fun contributions() = listOf(-1 to region)
        override fun inverse() = Add(kind, region)
    }

    /** Translate the elements of [kind] on [region] by [delta]. */
    data class Move(override val kind: String, override val region: Region, val delta: Rational) : LineOp() {
        val target: Region get() = region.shift(delta)
        override fun contributions() = listOf(-1 to region, +1 to region.shift(delta))
        override fun inverse() = Move(kind, region.shift(delta), -delta)

        companion object {
            /** Move so that the start of [region] lands on [start]. */
            fun to(kind: String, region: Region, start: Rational) = Move(kind, region, start - region.start)
        }
    }

    /** Grow elements of [kind]: every part of [region] touches one of them. */
    data class Extend(override val kind: String, override val region: Region) : LineOp() {
        override fun contributions() = listOf(+1 to region)
        override fun inverse() = Retract(kind, region)
    }

    /** Shrink elements of [kind]: every part of [region] touches what remains of them. */
    data class Retract(override val kind: String, override val region: Region) : LineOp() {
        override fun contributions() = listOf(-1 to region)
        override fun inverse() = Extend(kind, region)
    }
}

/** The edit that exactly undoes [ops]. */
fun inverse(ops: List<LineOp>): List<LineOp> = ops.map { it.inverse() }

/** Where the op takes elements from: they must be on the line. */
fun taken(op: LineOp): Region = when (op) {
    is LineOp.Remove, is LineOp.Retract, is LineOp.Move -> op.region
    else -> EMPTY
}

/** Where the op puts elements: they must be on the line afterwards, and only there. */
fun put(op: LineOp): Region = when (op) {
    is LineOp.Add, is LineOp.Extend -> op.region
    is LineOp.Move -> op.target
    else -> EMPTY
}

/**
 * [part] (one interval, disjoint from [other]) is adjacent to [other]. They can only meet at an end of [part]; near
 * it, both are canonical and disjoint, so their union has fewer parts iff some part of [other] merges with [part].
 */
fun touches(part: Region, other: Region): Boolean {
    for (e in listOf(part.start, part.end)) {
        if (e.isInfinite) continue
        val lo = e - Rational.ONE
        val hi = e + Rational.ONE
        val near = other.within(lo, hi)
        val here = part and Region.closed(lo, hi)
        if ((here or near).parts.size < here.parts.size + near.parts.size) return true
    }
    return false
}

// ================================================================================================================
// 1. History reduction
// ================================================================================================================

/** Effective (present, absent) claims of each edit, oldest first. */
fun reduceHistory(edits: List<List<LineOp>>): List<Pair<Claims, Claims>> {
    val n = edits.size
    val byKind = LinkedHashMap<String, MutableList<Triple<Int, Int, Region>>>()
    for ((lvl, ops) in edits.withIndex()) {
        for (op in ops) for ((sign, reg) in op.contributions()) if (reg.isNotEmpty()) byKind.getOrPut(op.kind) { mutableListOf() }.add(Triple(lvl, sign, reg))
    }
    val levels = List(n) { LinkedHashMap<String, Region>() to LinkedHashMap<String, Region>() }
    for ((kind, items) in byKind) {
        val cells = Region.cellsOf(items.map { it.third })
        val pos = List(n) { BooleanArray(cells.size) }
        val neg = List(n) { BooleanArray(cells.size) }
        for ((ci, cell) in cells.withIndex()) {
            val counts = IntArray(n)
            for ((lvl, sign, reg) in items) if (cell.rep in reg) counts[lvl] += sign
            val stack = ArrayList<IntArray>() // [level, signed count], newest at the bottom
            for (lvl in n - 1 downTo 0) {
                var c = counts[lvl]
                while (c != 0 && stack.isNotEmpty() && (stack.last()[1] > 0) != (c > 0)) {
                    val top = stack.last()
                    val m = minOf(kotlin.math.abs(c), kotlin.math.abs(top[1]))
                    c -= if (c > 0) m else -m
                    top[1] -= if (top[1] > 0) m else -m
                    if (top[1] == 0) stack.removeAt(stack.size - 1)
                }
                if (c != 0) stack.add(intArrayOf(lvl, c))
            }
            for ((lvl, c) in stack.map { it[0] to it[1] }) (if (c > 0) pos else neg)[lvl][ci] = true
        }
        for (lvl in 0 until n) {
            val p = Region.fromCells(cells, pos[lvl].toList())
            val q = Region.fromCells(cells, neg[lvl].toList())
            if (p.isNotEmpty()) levels[lvl].first[kind] = p
            if (q.isNotEmpty()) levels[lvl].second[kind] = q
        }
    }
    return levels
}

// ================================================================================================================
// 2. Prioritized solver
// ================================================================================================================

private fun union(a: Claims, b: Claims): Claims {
    val out = LinkedHashMap(a)
    for ((k, r) in b) out[k] = out.at(k) or r
    return out
}

private fun nonEmpty(d: Claims): Claims = d.filterValues { it.isNotEmpty() }

private fun Claims.minusClaims(other: Claims): Claims = nonEmpty(mapValues { (k, r) -> r - other.at(k) })

/** The residues mod g of every finite end of the region's parts. */
private fun endsMod(region: Region, g: Rational): Set<Rational> {
    val out = HashSet<Rational>()
    for (p in region.parts) for (e in listOf(p.lo, p.hi)) if (e.isFinite) out.add(e % g)
    for (tail in listOfNotNull(region.left, region.right)) {
        val n = (Rational.lcm(tail.period, g) / tail.period).toLongExact()
        val pat = Region(tail.pattern)
        out.add(tail.at % g)
        for (i in 0 until n) {
            val copy = pat.shift(tail.at + tail.period * i)
            for (p in copy.parts) for (e in listOf(p.lo, p.hi)) out.add(e % g)
        }
    }
    return out
}

/** Operands per ImpliesAny rule: (regions where each operand is chosen, region where the choice failed). */
internal typealias Choices = Map<Int, Pair<List<Region>, Region>>

data class LineSolution(val state: Claims, val dropped: List<Pair<Claims, Claims>>)

internal class LineSolver(ruleset: RuleSet, kinds: Collection<String>) {
    /** What the levels processed so far (all newer than the current one) fixed. */
    class Accepted {
        var base: Claims = emptyMap() // accepted present claims
        var neg: Claims = emptyMap() // accepted absent claims
        var plus: Claims = emptyMap() // least model of the above
        val anchor = HashMap<Int, Region>() // ImpliesAny anchors in `plus`
        val choice = HashMap<Int, MutableList<Region>>() // operand chosen per anchor
    }

    val rules: List<LineRule> = ruleset.rules
    val order: List<String> = ruleset.order(kinds)
    val idx: Map<String, Int> = order.withIndex().associate { (i, k) -> k to i }

    /** Rules fire once every atom they read is final: premise kinds for producing rules, all atoms for Forbids. */
    val trig: List<MutableList<Int>> = List(order.size) { mutableListOf() }
    val producers = HashMap<String, MutableList<Pair<Int, Int?>>>()
    val anyRules = mutableListOf<Int>()

    /** Every offset is an integer multiple of g, so rules only relate points of one lattice r + g*Z. */
    val g: Rational = Rational.gcdOf(rules.flatMap { r -> r.atoms.map { it.offset } }).let { if (it.sign == 0) Rational.ONE else it }
    val steps: List<List<Pair<String, Long>>> = rules.map { r -> r.atoms.map { it.kind to (it.offset / g).toLongExact() } }

    /** [steps] as primitive arrays, for the window closures of [LineChoice]: the kind's index in [order], the offset. */
    val stepKind: List<IntArray> = steps.map { st -> IntArray(st.size) { idx.getValue(st[it].first) } }
    val stepOff: List<LongArray> = steps.map { st -> LongArray(st.size) { st[it].second } }

    /** Decisions at an anchor reach atoms at most `reach` away: a derivation chain has at most one rule per kind, and a violation one more rule. */
    val reach: Rational

    /** Kinds by their first appearance in the rules, left to right: on a tie between losses, the leftmost elements are kept. */
    val rank = LinkedHashMap<String, Int>()

    init {
        var span = Rational.ZERO
        for (r in rules) {
            val offs = r.atoms.map { it.offset }
            span = max(span, offs.max() - offs.min())
        }
        reach = span * (order.size + 1)
        for ((ri, r) in rules.withIndex()) {
            val read = r.premises.map { it.kind } + (if (r is LineRule.Forbids) listOf(r.head.kind) else emptyList())
            trig[read.maxOf { idx.getValue(it) }].add(ri)
            when (r) {
                is LineRule.Implies -> producers.getOrPut(r.head.kind) { mutableListOf() }.add(ri to null)
                is LineRule.ImpliesAny -> {
                    anyRules.add(ri)
                    for ((j, a) in r.options.withIndex()) producers.getOrPut(a.kind) { mutableListOf() }.add(ri to j)
                }
                is LineRule.Forbids -> {}
            }
        }
        for (r in rules) for (a in r.atoms) if (a.kind !in rank) rank[a.kind] = rank.size
        for (k in order) if (k !in rank) rank[k] = rank.size
    }

    private fun options(ri: Int) = (rules[ri] as LineRule.ImpliesAny).options

    private fun noChoices(): Choices = anyRules.associateWith { List(options(it).size) { EMPTY } to EMPTY }

    // ---- least model -----------------------------------------------------------------------------------------

    /**
     * Least model of [base]. Old ImpliesAny anchors keep their recorded choice; new anchors use [chi] (or are not
     * expanded when [chi] is null).
     */
    fun close(base: Claims, acc: Accepted, chi: Choices?): Pair<MutableMap<String, Region>, Map<Int, Region>> {
        val c = LinkedHashMap<String, Region>()
        for (k in order) c[k] = base.at(k)
        val newAnchor = LinkedHashMap<Int, Region>()
        for (pos in order.indices) {
            for (ri in trig[pos]) {
                val r = rules[ri]
                if (r is LineRule.Forbids) continue
                val x = anchors(r, c)
                if (r is LineRule.Implies) {
                    c[r.head.kind] = c.at(r.head.kind) or x.shift(r.head.offset)
                    continue
                }
                r as LineRule.ImpliesAny
                val new = x - (acc.anchor[ri] ?: EMPTY)
                newAnchor[ri] = new
                for ((j, a) in r.options.withIndex()) {
                    var sel = acc.choice[ri]?.let { x and it[j] } ?: EMPTY
                    if (chi != null) sel = sel or (new and chi.getValue(ri).first[j])
                    c[a.kind] = c.at(a.kind) or sel.shift(a.offset)
                }
            }
        }
        return c to newAnchor
    }

    // ---- exact ordered-disjunction choice -------------------------------------------------------------------

    private fun residueCells(regions: List<Region>): List<Pair<Rational, Rational>> {
        val breaks = (setOf(Rational.ZERO, g / 2) + regions.flatMap { endsMod(it, g) }).sorted()
        return breaks.map { it to it } + breaks.zip(breaks.drop(1) + g)
    }

    /**
     * Operands for the new ImpliesAny anchors such that the level has no violation: positions are decided one after
     * the other, the closest to 0 first (the one on the left first when two are equally close), the rules at one
     * position in topological order, and each takes the first operand that still lets the remaining positions be
     * placed ([LineChoice]).
     *
     * Rules only relate points of one lattice r + g*Z, and on every cell of residues r the same problem arises, so it
     * is solved once per cell. On a cell where no assignment exists, the level's present claims [p] there are kept
     * one after the other in the same order ([keepInOrder]), and the others are returned, to be dropped (with
     * infinitely many anchors there, [LineChoice.keepInfinite]).
     */
    fun exactChoices(base: Claims, nb: Claims, acc: Accepted, p: Claims): Pair<Choices, Claims> {
        val (_, newAnchor) = close(base, acc, anyRules.associateWith { List(options(it).size) { FULL } to EMPTY })
        val chosen = anyRules.associateWith { ri -> List(options(ri).size) { mutableListOf<Region>() } }
        val drop = LinkedHashMap<String, MutableList<Region>>()
        if (newAnchor.values.none { it.isNotEmpty() }) return noChoices() to emptyMap()
        val anchorsAll = Region.unionOf(newAnchor.values.toList())
        val finite = anchorsAll.isFinite() && anchorsAll.start.isFinite && anchorsAll.end.isFinite

        val regions = base.values + nb.values + acc.anchor.values + acc.choice.values.flatten() + newAnchor.values
        // Within a cell, every region is the same on every lattice, and so is the order of the positions by
        // distance to 0 (it changes at 0 and g/2).
        for ((a, b) in residueCells(regions)) {
            val unit = if (a == b) Region.point(a) else Region.open(a, b)
            val r = if (a == b) a else (a + b) / 2
            if (finite) {
                // Decisions only matter within `reach` of the anchors, and atoms there are derived from atoms
                // within `reach` again.
                val lo = anchorsAll.start - reach * 2
                val hi = anchorsAll.end + reach * 2
                val ks = ((lo - r) / g).floor()..((hi - r) / g).ceil()
                var dec = solveCell(r, ks, base, nb, acc, newAnchor)
                if (dec == null) {
                    val (d, removed) = keepInOrder(r, ks, base, nb, acc, newAnchor, p)
                    dec = d
                    for ((kk, k) in removed) drop.getOrPut(kk) { mutableListOf() }.add(unit.shift(g * k))
                }
                for ((anchor, j) in dec) chosen.getValue(anchor.rule)[j].add(unit.shift(g * anchor.k))
            } else {
                val cell = LineChoice.Cell(this, r, base, nb, acc, newAnchor)
                cell.unit = unit
                var res = LineChoice.solveInfinite(cell)
                if (res == null) {
                    val flags = LineChoice.FlagCell(this, r, base, nb, acc, newAnchor, p)
                    flags.unit = unit
                    val removed = LineChoice.keepInfinite(flags)
                    for ((kk, reg) in removed) drop.getOrPut(kk) { mutableListOf() }.add(reg)
                    val kept = LineChoice.Cell(this, r, base.mapValues { (kk, reg) -> reg - removed.at(kk) }, nb, acc, newAnchor)
                    kept.unit = unit
                    res = checkNotNull(LineChoice.solveInfinite(kept)) { "no assignment with the claims kept" }
                }
                for ((ri, regs) in res) for ((j, reg) in regs.withIndex()) chosen.getValue(ri)[j].add(reg)
            }
        }
        val chi = chosen.mapValues { (_, ch) -> ch.map { Region.unionOf(it) } to EMPTY }
        return chi to drop.mapValues { Region.unionOf(it.value) }
    }

    /**
     * The claims of [cand] to drop so that the line of no new anchor has no violation, per cell of residues as in
     * [exactChoices] ([LineChoice.fewestLost]).
     */
    private fun fewestLost(cand: Claims, base: Claims, nb: Claims, acc: Accepted): Claims {
        val regions = base.values + nb.values + acc.anchor.values + acc.choice.values.flatten()
        val drop = LinkedHashMap<String, MutableList<Region>>()
        for ((a, b) in residueCells(regions)) {
            val unit = if (a == b) Region.point(a) else Region.open(a, b)
            val cell = LineChoice.Cell(this, if (a == b) a else (a + b) / 2, base, nb, acc, emptyMap())
            cell.unit = unit
            for ((kk, reg) in LineChoice.fewestLost(cell, cand, rank)) drop.getOrPut(kk) { mutableListOf() }.add(reg)
        }
        return nonEmpty(drop.mapValues { Region.unionOf(it.value) })
    }

    /** [LineChoice.solveFinite] on the lattice r + k*g, k in [ks], without the atoms [removed] of [base]. */
    private fun solveCell(
        r: Rational,
        ks: LongRange,
        base: Claims,
        nb: Claims,
        acc: Accepted,
        cand: Map<Int, Region>,
        removed: Set<KAtom> = emptySet(),
    ): Map<Anchor, Int>? {
        val cell = LineChoice.Cell(this, r, base, nb, acc, cand, removed)
        return LineChoice.solveFinite(cell, ks) { dec ->
            val letters = HashMap<Long, Array<Int?>>()
            for ((anchor, j) in dec) letters.getOrPut(anchor.k) { arrayOfNulls(cell.anys.size) }[cell.slot.getValue(anchor.rule)] = j
            val st = cell.closure(ks.first, ks.last, letters.mapValues { Letter(it.value.toList()) })
            ks.none { cell.badAt(it, st) }
        }
    }

    /**
     * The level's present claims on the lattice r + k*g, kept one after the other, the closest to 0 first (the one
     * on the left first, then in topological order): each is kept if some assignment has no violation with it and
     * the ones kept before it, the later ones left out. Returns the assignment for the kept ones and the atoms
     * (kind, k) not kept.
     */
    private fun keepInOrder(
        r: Rational,
        ks: LongRange,
        base: Claims,
        nb: Claims,
        acc: Accepted,
        cand: Map<Int, Region>,
        p: Claims,
    ): Pair<Map<Anchor, Int>, Set<KAtom>> {
        val atoms = ks.flatMap { k -> order.filter { kk -> (r + g * k) in p.at(kk) && (r + g * k) !in acc.plus.at(kk) }.map { KAtom(it, k) } }
            .sortedWith(compareBy<KAtom, Rational>(LineChoice.orderKey) { r + g * it.k }.thenBy { idx.getValue(it.kind) })
        val kept = HashSet<KAtom>()
        for (atom in atoms) {
            val removed = atoms.filter { it != atom && it !in kept }.toSet()
            if (solveCell(r, ks, base, nb, acc, cand, removed) != null) kept.add(atom)
        }
        val removed = atoms.filter { it !in kept }.toSet()
        val dec = solveCell(r, ks, base, nb, acc, cand, removed)
        // With none of them, the lattice has the line of no new anchor.
        return checkNotNull(dec) { "no assignment with the claims kept" } to removed
    }

    // ---- conflicts -------------------------------------------------------------------------------------------

    /** Atoms taking part in a violation (absent claim, Forbids, failed choice). */
    fun violations(c: Claims, nb: Claims, newAnchor: Map<Int, Region>, chi: Choices?): Claims {
        val seeds = LinkedHashMap<String, Region>()
        fun seed(k: String, reg: Region) {
            if (reg.isNotEmpty()) seeds[k] = seeds.at(k) or reg
        }
        for (k in order) seed(k, c.at(k) and nb.at(k))
        for ((ri, r) in rules.withIndex()) {
            if (r is LineRule.Forbids) {
                val x = anchors(r, c) and c.at(r.head.kind).shift(-r.head.offset)
                for (a in r.premises + r.head) seed(a.kind, x.shift(a.offset))
            } else if (r is LineRule.ImpliesAny && chi != null) {
                val x = (newAnchor[ri] ?: EMPTY) and chi.getValue(ri).second
                for (a in r.premises) seed(a.kind, x.shift(a.offset))
            }
        }
        return seeds
    }

    /** Every atom outside acc.plus used in some derivation of a seed atom. */
    fun support(seeds: Claims, c: Claims, acc: Accepted, chi: Choices): Claims {
        val s = LinkedHashMap<String, Region>()
        for (k in order) s[k] = seeds.at(k)
        for (k in order.reversed()) {
            s[k] = s.at(k) - acc.plus.at(k)
            if (s.at(k).isEmpty()) continue
            for ((ri, j) in producers[k].orEmpty()) {
                val r = rules[ri]
                val x = if (r is LineRule.Implies) {
                    s.at(k).shift(-r.head.offset) and anchors(r, c)
                } else {
                    r as LineRule.ImpliesAny
                    var chosen = acc.choice[ri]?.get(j!!) ?: EMPTY
                    chosen = chosen or (chi.getValue(ri).first[j!!] - (acc.anchor[ri] ?: EMPTY))
                    s.at(k).shift(-r.options[j].offset) and anchors(r, c) and chosen
                }
                for (a in r.premises) s[a.kind] = s.at(a.kind) or x.shift(a.offset)
            }
        }
        return s
    }

    // ---- one level -------------------------------------------------------------------------------------------

    fun level(p0: Claims, n0: Claims, acc: Accepted): Pair<Claims, Claims> {
        var p = p0
        var n = n0
        var dropP: Claims = emptyMap()

        // 1. Conflicts no choice of operands avoids (the rules are monotone): those of the line of no new anchor.
        // Absent claims on it go (they lose no element); then the present claims taking part in a conflict are kept
        // so as to lose the fewest elements, the leftmost on a tie.
        var (b0, _) = close(union(acc.base, p), acc, null)
        var dropN = nonEmpty(n.mapValues { (k, r) -> r and b0.at(k) })
        n = n.minusClaims(dropN)
        var nb = union(acc.neg, n)
        val seeds = violations(b0, nb, emptyMap(), null)
        if (seeds.isNotEmpty()) {
            val supp = support(seeds, b0, acc, noChoices())
            val cand = nonEmpty(p.mapValues { (k, r) -> r and supp.at(k) })
            dropP = fewestLost(cand, union(acc.base, p), nb, acc)
            p = p.minusClaims(dropP)
            b0 = close(union(acc.base, p), acc, null).first
            check(violations(b0, nb, emptyMap(), null).isEmpty()) { "solver invariant broken" }
        }

        // 2. The operands; where no assignment exists, the claims kept in order.
        var base = union(acc.base, p)
        nb = union(acc.neg, n)
        val (chi, unkept) = exactChoices(base, nb, acc, p)
        if (unkept.isNotEmpty()) {
            dropP = union(dropP, unkept)
            p = p.minusClaims(unkept)
            base = union(acc.base, p)
        }
        val (c, newAnchor) = close(base, acc, chi)
        // Theorem 3.2 of SEMANTICS.md: adaptation removes every conflict.
        check(violations(c, nb, newAnchor, chi).isEmpty()) { "solver invariant broken" }
        dropP = nonEmpty(dropP)
        dropN = nonEmpty(dropN)

        acc.base = base
        acc.neg = nb
        acc.plus = c
        for ((ri, new) in newAnchor) {
            val ch = acc.choice.getOrPut(ri) { MutableList(options(ri).size) { EMPTY } }
            for (j in ch.indices) ch[j] = ch[j] or (new and chi.getValue(ri).first[j])
            acc.anchor[ri] = (acc.anchor[ri] ?: EMPTY) or new
        }
        return dropP to dropN
    }

    fun solve(levels: List<Pair<Claims, Claims>>): LineSolution {
        val acc = Accepted()
        val dropped = MutableList<Pair<Claims, Claims>>(levels.size) { emptyMap<String, Region>() to emptyMap() }
        for (lvl in levels.indices.reversed()) dropped[lvl] = level(levels[lvl].first, levels[lvl].second, acc)
        return LineSolution(nonEmpty(order.associateWith { acc.plus.at(it) }), dropped)
    }
}

// ================================================================================================================
// 3. Line system
// ================================================================================================================

private fun Claims.restrict(region: Region): Claims = nonEmpty(mapValues { it.value and region })

/**
 * The line of a history of edits under [rules]. Accepting, reversing and dragging follow SEMANTICS.md § 4: an edit
 * is valid when it fits the line, none of its claims is dropped, everything it puts is on the result and its inverse
 * fits the result.
 */
class LineSystem(rules: List<LineRule> = emptyList()) {
    constructor(ruleset: RuleSet) : this(ruleset.rules)

    val ruleset: RuleSet = RuleSet(rules)

    class Edit(val id: Int, var ops: List<LineOp>, var active: Boolean = true, val internal: Boolean = false)

    private val edits = mutableListOf<Edit>()
    private var base: Claims = emptyMap() // line of the active edits
    private var pending: List<LineOp>? = null
    private var shown: Claims = emptyMap() // line of the active edits and the pending edit

    /**
     * An edit of the system that is the NEWEST of all, whatever is applied after it: `rules_generation`'s pin, which
     * keeps part of the line fixed (here the folded past, [FoldedLine]). Every edit adapts around it, and an edit
     * whose own claims it would drop is refused. Null: none.
     */
    var pin: List<LineOp>? = null
        private set

    val allEdits: List<Edit> get() = edits

    /** The line of the active edits, without the pending one. */
    val baseState: Claims get() = base

    // ---- internals -------------------------------------------------------------------------------------------

    /** The solution of [history], with the [pin] as the newest level (after the levels of [history]) unless not [withPin]. */
    private fun solveHistory(history: List<List<LineOp>>, withPin: Boolean = true): LineSolution {
        val levels = pin?.takeIf { withPin }?.let { history + listOf(it) } ?: history
        val kinds = levels.flatMap { ops -> ops.map { it.kind } }.toSet()
        return LineSolver(ruleset, kinds).solve(reduceHistory(levels))
    }

    /**
     * Replaces the [pin] (null: none) and solves the active edits under it. Throws [ContradictionError], and changes
     * nothing, if the pin's own claims cannot all hold (it contradicts the rules by itself).
     */
    fun setPin(ops: List<LineOp>?) {
        noPending()
        val old = pin
        pin = ops
        val history = active().map { it.ops }
        val sol = solveHistory(history)
        if (ops != null) {
            val (dropP, dropN) = sol.dropped[history.size]
            if (dropP.isNotEmpty() || dropN.isNotEmpty()) {
                pin = old
                throw ContradictionError("the fixed part of the line contradicts the rules: " + (dropP.keys + dropN.keys).sorted())
            }
        }
        setBase(sol.state)
    }

    fun active(exclude: Set<Int> = emptySet(), before: Int? = null): List<Edit> =
        edits.filter { it.active && it.id !in exclude && (before == null || it.id < before) }

    private fun checkOps(ops: List<LineOp>): List<LineOp> {
        // Within one edit and kind: no two ops take from the same place, no two put on the same place, and nothing
        // is added where something is taken or removed where something is put. The condition is symmetric under
        // `inverse`, so the inverse of a valid edit is valid.
        fun added(op: LineOp) = if (op is LineOp.Add || op is LineOp.Extend) op.region else EMPTY
        fun removed(op: LineOp) = if (op is LineOp.Remove || op is LineOp.Retract) op.region else EMPTY
        for ((i, a) in ops.withIndex()) {
            for (b in ops.subList(i + 1, ops.size)) {
                if (a.kind != b.kind) continue
                val clash = Region.unionOf(
                    taken(a) and taken(b), put(a) and put(b),
                    added(a) and taken(b), added(b) and taken(a),
                    removed(a) and put(b), removed(b) and put(a),
                )
                if (clash.isNotEmpty()) throw ContradictionError("edit uses '${a.kind}' on $clash in two conflicting ways")
            }
        }
        return ops
    }

    /**
     * Throws [MissingElementError] unless [ops] fit the line [state]: they take only what is there, put nothing
     * where the same kind already is (except where the edit itself takes it away), and every part of an extension
     * or retraction touches the element it grows or shrinks.
     */
    private fun checkOn(state: Claims, ops: List<LineOp>) {
        for (op in ops) {
            val have = state.at(op.kind)
            val missing = taken(op) - have
            if (missing.isNotEmpty()) {
                val verb = when (op) { is LineOp.Remove -> "remove"; is LineOp.Move -> "move"; else -> "retract" }
                throw MissingElementError("there is no '${op.kind}' on $missing to $verb")
            }
            val freed = Region.unionOf(ops.filter { it.kind == op.kind }.map { taken(it) })
            val dup = (put(op) and have) - freed
            if (dup.isNotEmpty()) throw MissingElementError("'${op.kind}' is already on $dup")
            if (op is LineOp.Extend || op is LineOp.Retract) {
                val rest = have - op.region
                for (part in op.region.parts) {
                    if (!touches(Region(listOf(part)), rest)) {
                        val name = if (op is LineOp.Extend) "extend" else "retract"
                        throw MissingElementError("$name of '${op.kind}' on ${Region(listOf(part))} does not touch ${if (op is LineOp.Extend) "an element" else "what remains"}")
                    }
                }
            }
        }
    }

    /**
     * Solution of `history + [ops]`, where [state] is the line of [history]. Throws [MissingElementError] if the
     * edit does not fit the line, and [ContradictionError] if one of its claims would be dropped, one of the elements
     * it puts would not be on the line, the edit could not be undone right after, or the line on [keep] would change.
     */
    private fun validate(
        history: List<List<LineOp>>,
        state: Claims,
        ops: List<LineOp>,
        keep: Region? = null,
        withPin: Boolean = true,
    ): LineSolution {
        checkOn(state, ops)
        val sol = solveHistory(history + listOf(ops), withPin)
        val (dropP, dropN) = sol.dropped[history.size]
        if (dropP.isNotEmpty() || dropN.isNotEmpty()) {
            val parts = dropP.map { (k, r) -> "'$k' present on $r" } + dropN.map { (k, r) -> "'$k' absent on $r" }
            throw ContradictionError("edit contradicts the rules: cannot have " + parts.joinToString("; "))
        }
        for (op in ops) {
            val missing = put(op) - sol.state.at(op.kind)
            if (missing.isNotEmpty()) throw ContradictionError("edit contradicts the rules: cannot have '${op.kind}' on $missing")
        }
        try {
            checkOn(sol.state, inverse(ops))
        } catch (err: MissingElementError) {
            throw ContradictionError("edit could not be undone afterwards: ${err.message}")
        }
        if (keep != null && state.restrict(keep) != sol.state.restrict(keep)) throw ContradictionError("edit would change the line on $keep")
        return sol
    }

    /** The line of the active edits plus [ops] as the newest, validated like [apply]; nothing changes. */
    fun validated(ops: List<LineOp>): Claims = validate(active().map { it.ops }, base, checkOps(ops)).state

    private fun noPending() {
        check(pending == null) { "an edit is pending (a drag is in progress): drop or cancel it first" }
    }

    /**
     * Line of [list] applied in order, and the ids of the invalid ones. Internal edits are not validated. Each edit is
     * validated on top of the edits before it, as when it was made: the [pin] is there as their BASE (the oldest
     * level — what the folded edits left), not as the newest, which outranks them all and would make an edit the line
     * once adapted look invalid. The line returned is under the pin as the newest, as always.
     */
    private fun replay(list: List<Edit>, skipInvalid: Boolean): Pair<Claims, Set<Int>> {
        val base = pin
        val history = mutableListOf<List<LineOp>>()
        if (base != null) history.add(base)
        var state: Claims = if (base != null) solveHistory(history, withPin = false).state else emptyMap()
        val invalid = HashSet<Int>()
        for (e in list) {
            if (e.internal) {
                state = solveHistory(history + listOf(e.ops), withPin = false).state
            } else {
                try {
                    state = validate(history, state, e.ops, withPin = false).state
                } catch (err: IllegalArgumentException) {
                    if (err !is ContradictionError && err !is MissingElementError) throw err
                    if (!skipInvalid) throw DependencyError("edit ${e.id}: ${err.message}")
                    invalid.add(e.id)
                    continue
                }
            }
            history.add(e.ops)
        }
        return (if (base != null) solveHistory(history.drop(1)).state else state) to invalid
    }

    /**
     * Appends [history] as user edits WITHOUT validating them, and solves once: a history that was valid as it was
     * made, rebuilt elsewhere ([FoldedLine.fold]). Returns their ids, in order.
     */
    fun restore(history: List<List<LineOp>>): List<Int> {
        noPending()
        val ids = history.map { ops ->
            val id = edits.size
            edits.add(Edit(id, checkOps(ops)))
            id
        }
        setBase(solveHistory(active().map { it.ops }).state)
        return ids
    }

    private fun setBase(state: Claims) {
        base = state
        shown = state
    }

    private fun append(ops: List<LineOp>, state: Claims, internal: Boolean = false): Int {
        val id = edits.size
        edits.add(Edit(id, ops, internal = internal))
        setBase(state)
        return id
    }

    // ---- public API ------------------------------------------------------------------------------------------

    /**
     * Apply an edit as the newest one; returns its id. Throws [MissingElementError] if it does not fit the line, and
     * [ContradictionError] if it contradicts the rules by itself or would change the line on [keep]; the line is
     * then unchanged.
     */
    fun apply(ops: List<LineOp>, keep: Region? = null): Int {
        noPending()
        val checked = checkOps(ops)
        val sol = validate(active().map { it.ops }, base, checked, keep)
        return append(checked, sol.state)
    }

    /**
     * Apply an edit made by the system, without the checks of [apply]. None of its claims may be dropped: the caller
     * makes them consistent with the rules. With [replace], if the newest active edit is one made by the system,
     * [ops] takes its place (for a pin that restates a larger part of the line).
     */
    fun force(ops: List<LineOp>, replace: Boolean = false): Int {
        noPending()
        val act = active()
        val last = if (replace && act.isNotEmpty() && act.last().internal) act.last() else null
        val history = (if (last != null) act.dropLast(1) else act).map { it.ops }
        val sol = solveHistory(history + listOf(ops))
        check(sol.dropped[history.size].let { it.first.isEmpty() && it.second.isEmpty() }) { "forced edit contradicts the rules" }
        if (last == null) return append(ops, sol.state, internal = true)
        last.ops = ops
        setBase(sol.state)
        return last.id
    }

    /** The line if the system's edits [extra] were applied as the newest edits; nothing changes. */
    fun preview(extra: List<List<LineOp>>): Claims = solveHistory(active().map { it.ops } + extra).state

    /**
     * The line if the system's edits [extra], then [ops], were applied as the newest edits. [ops] is validated like
     * [apply], and the same errors are thrown; nothing changes.
     */
    fun trial(extra: List<List<LineOp>>, ops: List<LineOp>, keep: Region? = null): Claims {
        val history = active().map { it.ops } + extra
        val state = if (extra.isNotEmpty()) solveHistory(history).state else base
        return validate(history, state, checkOps(ops), keep).state
    }

    /**
     * Undo an edit: the line becomes what it would be had it never been made. Throws [DependencyError], and changes
     * nothing, if a later active edit requires it (it would not be valid had this edit never been made).
     */
    fun reverse(editId: Int) {
        noPending()
        val e = find(editId)
        require(e.active && !e.internal) { "edit $editId cannot be reversed" }
        val state = try {
            replay(active(exclude = setOf(editId)), skipInvalid = false).first
        } catch (err: DependencyError) {
            throw DependencyError("a later edit requires edit $editId (${err.message}); reverse it first")
        }
        e.active = false
        setBase(state)
    }

    /**
     * Undo the given edits and every later edit that requires one of them. Returns the ids of all the reversed
     * edits. The line becomes what it would be had none of them been made.
     */
    fun reverseAll(editIds: Collection<Int>): List<Int> {
        noPending()
        val gone = editIds.toMutableSet()
        for (i in gone) {
            val e = find(i)
            require(e.active && !e.internal) { "edit $i cannot be reversed" }
        }
        val (state, invalid) = replay(active(exclude = gone), skipInvalid = true)
        gone.addAll(invalid)
        for (i in gone) edits[i].active = false
        setBase(state)
        return gone.sorted()
    }

    /**
     * Show [ops] as if applied as the newest edit, without committing it. It replaces any previous pending edit and
     * is validated like [apply] on the active edits alone, so the result never depends on the previous pending edit.
     * If it is not valid, the previous pending edit stays.
     */
    fun setPending(ops: List<LineOp>, keep: Region? = null) {
        val checked = checkOps(ops)
        val sol = validate(active().map { it.ops }, base, checked, keep)
        pending = checked
        shown = sol.state
    }

    fun clearPending() {
        pending = null
        shown = base
    }

    /** Make the pending edit a normal edit. Returns its id (null if no edit is pending). */
    fun commitPending(): Int? {
        val ops = pending ?: return null
        pending = null
        return append(ops, shown)
    }

    /** Start dragging a block: [block] is a list of (kind, region) on the line. */
    fun drag(block: List<Pair<String, Region>>): Drag {
        noPending()
        return Drag(this, block)
    }

    fun state(): Claims = LinkedHashMap(shown)

    /**
     * B requires A iff B would not be valid had A never been made, nor any edit that itself requires A (those are
     * skipped, oldest first).
     */
    fun requires(bId: Int, aId: Int): Boolean {
        val b = find(bId)
        find(aId)
        val (_, invalid) = replay(active(exclude = setOf(aId), before = bId) + b, skipInvalid = true)
        return bId in invalid
    }

    /** Effective (present, absent) claims of an active edit in the current history. */
    fun effective(editId: Int): Pair<Claims, Claims> {
        val act = active()
        val pos = act.map { it.id }.indexOf(find(editId).id)
        return reduceHistory(act.map { it.ops } + listOfNotNull(pin))[pos]
    }

    /** The line of the active edits solved from scratch, with what each level dropped. */
    fun solveActive(): LineSolution = solveHistory(active().map { it.ops })

    private fun find(editId: Int): Edit {
        if (editId !in edits.indices) throw NoSuchElementException("edit $editId not found")
        return edits[editId]
    }
}

/**
 * A block of elements being dragged. Every step shows the block moved by `delta` from where it started, as a pending
 * edit computed from the active edits alone: after each step the line is exactly what it would be had the block been
 * moved there directly. A step that is not valid (for example onto an element of the same kind) is refused, and the
 * block stays where the previous step put it.
 */
class Drag internal constructor(private val line: LineSystem, private val block: List<Pair<String, Region>>) {
    var delta: Rational = Rational.ZERO
        private set

    fun step(d: Rational) {
        if (d == Rational.ZERO) line.clearPending() else line.setPending(block.map { (k, r) -> LineOp.Move(k, r, d) })
        delta = d
    }

    /** End the drag. Returns the id of the move edit (null if back at the start). */
    fun drop(): Int? = line.commitPending()

    fun cancel() {
        line.clearPending()
        delta = Rational.ZERO
    }
}
