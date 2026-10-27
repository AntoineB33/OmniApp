package org.example.project.scheduler.line

import kotlin.math.abs

/**
 * Operands of ordered disjunctions on one lattice r + g*Z — a port of `C:\code\line_manager\reverse_logic\choice.py`
 * (SEMANTICS.md § 3).
 *
 * The positions r + k*g of the new anchors are decided one after the other, the closest to 0 first (the one on the
 * left first when two are equally close), and at one position the rules in topological order; each takes the first
 * operand that still lets the remaining positions be placed without violation.
 *
 * [solveFinite] does it by backtracking when there are finitely many anchors. [solveInfinite] does it when there are
 * infinitely many: every violation is LOCAL (whether there is one at position j depends only on the operands at
 * j + vlo .. j + vhi), and far enough from 0 every datum is periodic in k, so "the rest can be placed" is a question
 * on a finite graph, and the result repeats on each side from some position on.
 *
 * When no assignment exists, [keepInfinite] keeps the level's claims one after the other in the same order, each if
 * an assignment exists with it and the ones kept before it (with finitely many anchors, `LineSolver.keepInOrder`).
 */
internal object LineChoice {

    /** One operand index (null: no new anchor there) per ordered disjunction, then the claims kept (a [FlagCell]). */
    data class Letter(val ops: List<Int?>, val kept: List<Boolean> = emptyList())

    /** An atom of the base on the lattice: (kind, k). */
    data class KAtom(val kind: String, val k: Long)

    /** (rule index, k): an anchor of an ordered disjunction. */
    data class Anchor(val rule: Int, val k: Long)

    data class CellData(val base: Set<String>, val nb: Set<String>, val old: List<Int?>, val new: List<Boolean>)

    val orderKey: Comparator<Rational> = compareBy<Rational>({ it.abs() }, { it })

    fun hull(a: Pair<Long, Long>?, b: Pair<Long, Long>?): Pair<Long, Long>? {
        if (a == null) return b
        if (b == null) return a
        return minOf(a.first, b.first) to maxOf(a.second, b.second)
    }

    private fun shift(a: Pair<Long, Long>?, d: Long) = a?.let { (it.first + d) to (it.second + d) }

    fun <T> product(ranges: List<List<T>>): List<List<T>> {
        var out = listOf(emptyList<T>())
        for (r in ranges) out = out.flatMap { p -> r.map { p + it } }
        return out
    }

    private fun <T> window(range: LongRange, values: List<T>): Map<Long, T> {
        val out = HashMap<Long, T>()
        var i = 0
        for (k in range) {
            if (i >= values.size) break
            out[k] = values[i++]
        }
        return out
    }

    /** The lattice r + k*g of a cell of residues, and what the level knows on it. */
    open class Cell(
        val s: LineSolver,
        val r: Rational,
        val base: Claims,
        val nb: Claims,
        val acc: LineSolver.Accepted,
        val cand: Map<Int, Region>,
        val removed: Set<KAtom> = emptySet(),
    ) {
        val g: Rational = s.g

        /** The ordered disjunctions, in the order the closure meets them. */
        val anys: List<Int> = s.order.indices.flatMap { pos -> s.trig[pos].filter { s.rules[it] is LineRule.ImpliesAny } }
        val slot: Map<Int, Int> = anys.withIndex().associate { (i, ri) -> ri to i }

        /** The cell of residues, at k = 0. */
        var unit: Region = Region.point(r)
        private val memo = HashMap<Long, CellData>()

        fun x(k: Long): Rational = r + g * k

        /** (base kinds, absent kinds, old operand per rule or null, new anchor per rule) at k. */
        fun data(k: Long): CellData = memo.getOrPut(k) {
            val x = x(k)
            val base = s.order.filter { x in base.at(it) && KAtom(it, k) !in removed }.toSet()
            val nb = s.order.filter { x in nb.at(it) }.toSet()
            val old = anys.map { ri ->
                if (x in (acc.anchor[ri] ?: Region.EMPTY)) (acc.choice[ri] ?: emptyList()).indexOfFirst { x in it }.takeIf { it >= 0 } else null
            }
            val new = anys.map { x in (cand[it] ?: Region.EMPTY) }
            CellData(base, nb, old, new)
        }

        /** The base kinds at k, given the letters. */
        open fun baseAt(k: Long, letters: Map<Long, Letter>): Set<String> = data(k).base

        /** Every letter at k, in the order of the decisions (lexicographic, first operands first). */
        open fun letters(k: Long): List<Letter> {
            val new = data(k).new
            val ranges = anys.mapIndexed { i, ri ->
                if (new[i]) (0 until (s.rules[ri] as LineRule.ImpliesAny).options.size).map<Int, Int?> { it } else listOf<Int?>(null)
            }
            return product(ranges).map { Letter(it) }
        }

        /** The offsets of the letters that a violation anchored at 0 may depend on. */
        open fun dependency(): Pair<Long, Long>? = dependency(s)

        open fun regions(): List<Region> =
            base.values + nb.values + acc.anchor.values + acc.choice.values.flatten() + cand.values

        /** (index, period): beyond the index on that side, every datum is periodic in k. */
        fun side(right: Boolean): Pair<Long, Long> {
            var thr: Rational? = null
            var period = 1L
            for (reg in regions()) {
                if (reg.isEmpty()) continue
                val tail = if (right) reg.right else reg.left
                val ends = reg.parts.flatMap { listOf(it.lo, it.hi) }.filter { it.isFinite } + listOfNotNull(reg.left?.at, reg.right?.at)
                if (ends.isNotEmpty()) {
                    val t = if (right) ends.max() else ends.min()
                    thr = if (thr == null) t else if (right) max(thr, t) else min(thr, t)
                }
                if (tail != null) period = Rational.lcmLong(period, (Rational.lcm(tail.period, g) / g).toLongExact())
            }
            if (thr == null) return 0L to period
            return (if (right) ((thr - r) / g).ceil() + 1 else ((thr - r) / g).floor() - 1) to period
        }

        /**
         * The least model on positions lo..hi, with the given operands (none elsewhere). Python's sets of positions
         * per kind, as one flag per kind and position of the window: the model never holds a position outside it.
         */
        fun closure(lo: Long, hi: Long, letters: Map<Long, Letter>): Model {
            val st = Model(lo, hi, s.order.size)
            for (k in lo..hi) for (kk in baseAt(k, letters)) st.set(s.idx.getValue(kk), k)
            for (pos in s.order.indices) {
                for (ri in s.trig[pos]) {
                    val rule = s.rules[ri]
                    if (rule is LineRule.Forbids) continue
                    val kinds = s.stepKind[ri]
                    val offs = s.stepOff[ri]
                    val nPrem = rule.premises.size
                    // The anchors k with every premise present at k + offset. The rule produces only kinds read after
                    // its premises (the rules are acyclic), so they are all found before anything is added.
                    var maxO = Long.MIN_VALUE
                    var minO = Long.MAX_VALUE
                    for (i in 0 until nPrem) {
                        maxO = maxOf(maxO, offs[i])
                        minO = minOf(minO, offs[i])
                    }
                    val anchors = ArrayList<Long>()
                    for (k in lo - maxO..hi - minO) {
                        var all = true
                        for (i in 0 until nPrem) {
                            if (!st.has(kinds[i], k + offs[i])) {
                                all = false
                                break
                            }
                        }
                        if (all) anchors.add(k)
                    }
                    if (rule is LineRule.Implies) {
                        for (k in anchors) st.set(kinds[nPrem], k + offs[nPrem])
                        continue
                    }
                    val slot = slot.getValue(ri)
                    for (k in anchors) {
                        var j = data(k).old[slot]
                        if (j == null) j = letters[k]?.ops?.get(slot)
                        if (j != null) st.set(kinds[nPrem + j], k + offs[nPrem + j])
                    }
                }
            }
            return st
        }

        /** A violation anchored at j: an atom on an absent claim, or a Forbids instance. */
        fun badAt(j: Long, st: Model): Boolean {
            if (data(j).nb.any { st.has(s.idx.getValue(it), j) }) return true
            for ((ri, rule) in s.rules.withIndex()) {
                if (rule !is LineRule.Forbids) continue
                val kinds = s.stepKind[ri]
                val offs = s.stepOff[ri]
                var all = true
                for (i in kinds.indices) {
                    if (!st.has(kinds[i], j + offs[i])) {
                        all = false
                        break
                    }
                }
                if (all) return true
            }
            return false
        }
    }

    /**
     * A least model on the positions lo..hi of a lattice: whether each kind (by its index in the solver's order) is
     * present at each of them — the reference's sets of positions per kind, as flags.
     */
    class Model(val lo: Long, val hi: Long, kinds: Int) {
        private val flags = Array(kinds) { BooleanArray((hi - lo + 1).toInt()) }

        fun has(kind: Int, k: Long): Boolean = k in lo..hi && flags[kind][(k - lo).toInt()]

        /** Adds an atom; one outside the window is not part of this model. */
        fun set(kind: Int, k: Long) {
            if (k in lo..hi) flags[kind][(k - lo).toInt()] = true
        }

        /** The number of atoms. */
        fun count(): Int = flags.sumOf { f -> f.count { it } }
    }

    /**
     * A cell whose letters also say which of the level's present claims [p] are kept: a letter is (operand per
     * ordered disjunction ..., kept per kind of [p] ...). A claim at a position without a letter is not kept. `base`
     * holds the claims, kept or not.
     */
    class FlagCell(
        s: LineSolver,
        r: Rational,
        base: Claims,
        nb: Claims,
        acc: LineSolver.Accepted,
        cand: Map<Int, Region>,
        val p: Claims,
    ) : Cell(s, r, base, nb, acc, cand) {
        val kinds: List<String> = s.order.filter { p[it]?.isNotEmpty() == true }
        val none: List<Boolean> = List(kinds.size) { false }
        private val claimsMemo = HashMap<Long, List<Boolean>>()

        /** Per kind of [p]: whether the level claims it at k, not already accepted. */
        fun claims(k: Long): List<Boolean> = claimsMemo.getOrPut(k) {
            val x = x(k)
            kinds.map { x in p.getValue(it) && x !in acc.plus.at(it) }
        }

        override fun baseAt(k: Long, letters: Map<Long, Letter>): Set<String> {
            val kept = letters[k]?.kept ?: none
            val claims = claims(k)
            val out = kinds.indices.filter { claims[it] && !kept[it] }.map { kinds[it] }.toSet()
            return data(k).base - out
        }

        override fun letters(k: Long): List<Letter> = letters(k, null)

        /** Every letter at k with these claims kept (default: none), operands as in [Cell]. */
        fun letters(k: Long, kept: List<Boolean>?): List<Letter> = super.letters(k).map { Letter(it.ops, kept ?: none) }

        override fun dependency(): Pair<Long, Long>? = hull(dependency(s), claimDependency(s))

        override fun regions(): List<Region> = super.regions() + p.values
    }

    /**
     * The offsets (in steps of g) of the operands that a violation anchored at 0 may depend on: the hull, over every
     * atom it reads, of the operands that atom may be derived from (null: no operand matters).
     */
    fun dependency(s: LineSolver): Pair<Long, Long>? {
        val dep = HashMap<String, Pair<Long, Long>?>()
        for (kk in s.order) dep[kk] = null
        for (pos in s.order.indices) {
            for (ri in s.trig[pos]) {
                val rule = s.rules[ri]
                val steps = s.steps[ri]
                if (rule is LineRule.Forbids) continue
                val prem = steps.subList(0, rule.premises.size)
                val heads = if (rule is LineRule.Implies) listOf(steps.last()) else steps.subList(rule.premises.size, steps.size)
                for ((h, oh) in heads) {
                    var d: Pair<Long, Long>? = null
                    for ((a, o) in prem) d = hull(d, shift(dep[a], o - oh))
                    if (rule is LineRule.ImpliesAny) d = hull(d, -oh to -oh)
                    dep[h] = hull(dep[h], d)
                }
            }
        }
        return violationHull(s, dep)
    }

    /** The offsets (in steps of g) of the base atoms that a violation anchored at 0 may depend on. */
    fun claimDependency(s: LineSolver): Pair<Long, Long>? {
        val dep = HashMap<String, Pair<Long, Long>?>()
        for (kk in s.order) dep[kk] = 0L to 0L
        for (pos in s.order.indices) {
            for (ri in s.trig[pos]) {
                val rule = s.rules[ri]
                val steps = s.steps[ri]
                if (rule is LineRule.Forbids) continue
                val prem = steps.subList(0, rule.premises.size)
                val heads = if (rule is LineRule.Implies) listOf(steps.last()) else steps.subList(rule.premises.size, steps.size)
                for ((h, oh) in heads) for ((a, o) in prem) dep[h] = hull(dep[h], shift(dep[a], o - oh))
            }
        }
        return violationHull(s, dep)
    }

    /** The hull of [dep] over the atoms a violation anchored at 0 reads. */
    private fun violationHull(s: LineSolver, dep: Map<String, Pair<Long, Long>?>): Pair<Long, Long>? {
        var v: Pair<Long, Long>? = null
        for (kk in s.order) v = hull(v, dep[kk])
        for ((ri, rule) in s.rules.withIndex()) {
            if (rule is LineRule.Forbids) for ((a, o) in s.steps[ri]) v = hull(v, shift(dep[a], o))
        }
        return v
    }

    private sealed interface Zone {
        data class At(val j: Long) : Zone
        data class Periodic(val right: Boolean, val residue: Long) : Zone
    }

    private sealed interface NodeKey {
        data class Periodic(val right: Boolean, val residue: Long, val state: List<Letter>) : NodeKey
        data class Plain(val right: Boolean, val m: Long, val state: List<Letter>) : NodeKey
    }

    /** Violations and placements on the lattice of a cell, for infinitely many positions. */
    private class Walk(val cell: Cell) {
        val vlo: Long
        val vhi: Long
        val w: Long
        val radius: Long
        val kRight: Long
        val tRight: Long
        val kLeft: Long
        val tLeft: Long
        private val memoOk = HashMap<Pair<Zone, List<Letter?>>, Boolean>()
        private val goodMemo = HashMap<NodeKey, Boolean>()

        init {
            val s = cell.s
            val v = cell.dependency()
            vlo = v?.first ?: 0L
            vhi = v?.second ?: 0L
            w = vhi - vlo
            val reach = if (s.reach.sign != 0) (s.reach / cell.g).ceil() else 0L
            radius = reach + maxOf(abs(vlo), abs(vhi)) + (s.steps.flatten().maxOfOrNull { abs(it.second) } ?: 0L) + 1
            cell.side(true).let { kRight = it.first; tRight = it.second }
            cell.side(false).let { kLeft = it.first; tLeft = it.second }
        }

        fun zone(j: Long): Zone {
            if (j - radius > kRight) return Zone.Periodic(true, (j - kRight).mod(tRight))
            if (j + radius < kLeft) return Zone.Periodic(false, (kLeft - j).mod(tLeft))
            return Zone.At(j)
        }

        /** No violation anchored at j, with the letters at j + vlo .. j + vhi. */
        fun ok(j: Long, window: Map<Long, Letter>): Boolean {
            val key = zone(j) to (j + vlo..j + vhi).map { window[it] }
            return memoOk.getOrPut(key) {
                val st = cell.closure(j - radius, j + radius, window)
                !cell.badAt(j, st)
            }
        }

        // ---- the rest can be placed: on each side, a walk on a finite graph -----------------------------------

        fun node(m: Long, state: List<Letter>, right: Boolean): Pair<NodeKey, Boolean> {
            val z = zone(if (right) m + 1 else m - 1)
            val zj = zone(if (right) m + 1 - vhi else m - 1 - vlo)
            val periodic = z is Zone.Periodic && zj is Zone.Periodic && z.right == right && zj.right == right
            return (if (periodic) NodeKey.Periodic(right, m.mod(if (right) tRight else tLeft), state)
            else NodeKey.Plain(right, m, state)) to periodic
        }

        /** The states after adding a position beyond m (on that side), in letter order. */
        fun step(m: Long, state: List<Letter>, right: Boolean): Pair<Long, List<Pair<Letter, List<Letter>>>> {
            val k = if (right) m + 1 else m - 1
            val out = mutableListOf<Pair<Letter, List<Letter>>>()
            for (letter in cell.letters(k)) {
                val win: Map<Long, Letter>
                val j: Long
                val nxt: List<Letter>
                if (right) {
                    val seq = state + letter
                    win = window(k - w..k, seq)
                    j = k - vhi
                    nxt = if (w != 0L) seq.drop(1) else emptyList()
                } else {
                    val seq = listOf(letter) + state
                    win = window(k..k + w, seq)
                    j = k - vlo
                    nxt = if (w != 0L) seq.dropLast(1) else emptyList()
                }
                if (ok(j, win)) out.add(letter to nxt)
            }
            return k to out
        }

        /** Some placement of every position beyond m (on that side) has no violation. */
        fun good(m: Long, state: List<Letter>, right: Boolean): Boolean {
            val (key, periodic) = node(m, state, right)
            goodMemo[key]?.let { return it }
            if (!periodic) {
                val (k, nexts) = step(m, state, right)
                val res = nexts.any { good(k, it.second, right) }
                goodMemo[key] = res
                return res
            }
            // A finite graph of periodic nodes: good iff some infinite walk starts there.
            val graph = LinkedHashMap<NodeKey, MutableList<NodeKey>>()
            val todo = ArrayDeque<Pair<Long, List<Letter>>>()
            todo.addLast(m to state)
            while (todo.isNotEmpty()) {
                val (mm, ss) = todo.removeLast()
                val (kk, _) = node(mm, ss, right)
                if (kk in graph) continue
                val (k, nexts) = step(mm, ss, right)
                val edges = mutableListOf<NodeKey>()
                graph[kk] = edges
                for ((_, nxt) in nexts) {
                    val (nk, nper) = node(k, nxt, right)
                    edges.add(nk)
                    if (nper && nk !in graph && nk !in goodMemo) todo.addLast(k to nxt)
                }
            }
            val alive = graph.keys.toMutableSet()
            var changed = true
            while (changed) {
                changed = false
                for (n in alive.toList()) {
                    if (graph.getValue(n).none { it in alive || goodMemo[it] == true }) {
                        alive.remove(n)
                        changed = true
                    }
                }
            }
            for (n in graph.keys) goodMemo[n] = n in alive
            return goodMemo.getValue(key)
        }

        /** The positions outside a..b can be placed (a > b: none is decided yet). */
        fun canFinish(a: Long, b: Long, letters: Map<Long, Letter>): Boolean {
            if (b - a + 2 > w) {
                val left = (a until a + w).map { letters.getValue(it) }
                val rightState = (b - w + 1..b).map { letters.getValue(it) }
                return good(a, left, false) && good(b, rightState, true)
            }
            // The decided block is shorter than a window: decide w - (b - a + 1) more positions on the right in
            // every way, then each side is alone.
            val extra = (b + 1 until a + w).toList()
            for (combo in product(extra.map { cell.letters(it) })) {
                val trial = HashMap(letters)
                extra.zip(combo).forEach { (k, l) -> trial[k] = l }
                val lo = a
                val hi = a + w - 1
                if ((lo - vlo..hi - vhi).all { ok(it, trial) } && canFinish(lo, hi, trial)) return true
            }
            return false
        }

        /** The position closest to 0. */
        fun first(): Long {
            val k0 = (-cell.r / cell.g + Rational.of(1, 2)).floor()
            return listOf(k0 - 1, k0, k0 + 1).minWith(compareBy(orderKey) { cell.x(it) })
        }

        /** Whether the position after the block a..b, in the order, is on the right. */
        fun nextRight(a: Long, b: Long): Boolean =
            if (a <= b) orderKey.compare(cell.x(b + 1), cell.x(a - 1)) < 0 else true

        /** Both ends of the block a..b are in the periodic parts, a window away. */
        fun periodicBlock(a: Long, b: Long): Boolean = (b - radius - w > kRight) && (a + radius + w < kLeft)
    }

    /** The operands of the new anchors of the lattice, as regions per rule and operand; null if every assignment has a violation. */
    fun solveInfinite(cell: Cell): Map<Int, List<Region>>? {
        val s = cell.s
        val g = cell.g
        val wk = Walk(cell)
        val w = wk.w
        val vlo = wk.vlo
        val vhi = wk.vhi

        // ---- the decisions, closest to 0 first ----------------------------------------------------------------
        val k0 = wk.first()
        var letters: Map<Long, Letter> = emptyMap()
        if (!wk.canFinish(k0, k0 - 1, letters)) return null
        var a = k0
        var b = k0 - 1
        val seenRight = HashMap<NodeKey, Long>()
        val seenLeft = HashMap<NodeKey, Long>()
        var repRight: Pair<Long, Long>? = null // (first index, period) of the repetition on each side
        var repLeft: Pair<Long, Long>? = null
        while (repRight == null || repLeft == null) {
            var right = wk.nextRight(a, b)
            if ((right && repRight != null) || (!right && repLeft != null)) right = !right // only the other side is left
            val k = if (right) b + 1 else a - 1
            var placed = false
            for (letter in cell.letters(k)) {
                val trial = HashMap(letters)
                trial[k] = letter
                val na = if (right) a else k
                val nb = if (right) k else b
                val js = listOf(if (right) k - vhi else k - vlo)
                if (!js.filter { na <= it + vlo && it + vhi <= nb }.all { wk.ok(it, trial) }) continue
                if (wk.canFinish(na, nb, trial)) {
                    letters = trial
                    a = na
                    b = nb
                    placed = true
                    break
                }
            }
            check(placed) { "a placement that could be finished cannot" }
            if (b - a + 2 > w) {
                for (isRight in listOf(true, false)) {
                    if ((if (isRight) repRight else repLeft) != null || isRight != right) continue
                    val m = if (isRight) b else a
                    val window = (if (isRight) (b - w + 1..b) else (a until a + w)).map { letters.getValue(it) }
                    val (key, periodic) = wk.node(m, window, isRight)
                    if (!periodic) continue
                    val seen = if (isRight) seenRight else seenLeft
                    val first = seen[key]
                    if (first != null) {
                        if (isRight) repRight = (first + 1) to (m - first) else repLeft = (first - 1) to (first - m)
                    } else {
                        seen[key] = m
                    }
                }
            }
        }

        // ---- regions: the decided positions, then each side's repetition ------------------------------------------
        fun orbit(k: Long) = cell.unit.shift(g * k)
        val out = cell.anys.associateWith { ri -> List((s.rules[ri] as LineRule.ImpliesAny).options.size) { mutableListOf<Region>() } }
        for ((k, letter) in letters) {
            for ((i, ri) in cell.anys.withIndex()) letter.ops[i]?.let { out.getValue(ri)[it].add(orbit(k)) }
        }
        for ((rep, isRight) in listOf(repRight to true, repLeft to false)) {
            val (first, period) = rep
            for (t in 0 until period) {
                val k = if (isRight) first + t else first - t
                val letter = letters.getValue(k)
                for ((i, ri) in cell.anys.withIndex()) {
                    val j = letter.ops[i] ?: continue
                    val tiles = if (isRight) Region.tiled(orbit(k), g * period, 0, null) else Region.tiled(orbit(k), g * period, null, 0)
                    out.getValue(ri)[j].add(tiles)
                }
            }
        }
        return out.mapValues { (_, ch) -> ch.map { Region.unionOf(it) } }
    }

    /** A state of the decided block: every letter while it is at most 2w long, then its first and last w letters. */
    private sealed interface BlockState {
        data class Full(val letters: List<Letter>) : BlockState
        data class Ends(val first: List<Letter>, val last: List<Letter>) : BlockState
    }

    /**
     * The level's claims on the lattice kept one after the other, the closest to 0 first (the one on the left first,
     * then in topological order): each is kept if some assignment of operands has no violation with it and the ones
     * kept before it, the later ones left out. Returns the claims not kept, as regions per kind; they repeat on each
     * side from some position on.
     *
     * The block a..b of decided positions grows as in [solveInfinite]. Its STATES are the letters it can have with no
     * violation inside it and with the rest placeable. The claims are kept or not by the states, so once the block is
     * in the periodic parts, a state set coming back with the same residues makes the decisions repeat.
     */
    fun keepInfinite(cell: FlagCell): Map<String, Region> {
        val g = cell.g
        val wk = Walk(cell)
        val w = wk.w
        val vlo = wk.vlo
        val vhi = wk.vhi

        fun grow(states: Set<BlockState>, a: Long, b: Long, k: Long, kept: List<Boolean>, right: Boolean): Set<BlockState> {
            val full = b - a + 1 <= 2 * w
            val out = HashSet<BlockState>()
            for (st in states) {
                for (letter in cell.letters(k, kept)) {
                    if (full) {
                        val prev = (st as BlockState.Full).letters
                        val t = if (right) prev + letter else listOf(letter) + prev
                        val lo = if (right) a else k
                        val j = if (right) k - vhi else k - vlo
                        val ws = if (right) k - w else k
                        if (ws >= lo && ws + w <= lo + t.size - 1) {
                            val from = (ws - lo).toInt()
                            if (!wk.ok(j, window(ws..ws + w, t.subList(from, minOf(t.size, from + (w + 1).toInt()))))) continue
                        }
                        out.add(if (t.size <= 2 * w) BlockState.Full(t) else BlockState.Ends(t.subList(0, w.toInt()), t.subList(t.size - w.toInt(), t.size)))
                    } else if (right) {
                        st as BlockState.Ends
                        val win = st.last + letter
                        if (wk.ok(k - vhi, window(k - w..k, win))) out.add(BlockState.Ends(st.first, win.drop(1)))
                    } else {
                        st as BlockState.Ends
                        val win = listOf(letter) + st.first
                        if (wk.ok(k - vlo, window(k..k + w, win))) out.add(BlockState.Ends(win.dropLast(1), st.last))
                    }
                }
            }
            return out
        }

        fun finishes(st: BlockState, a: Long, b: Long): Boolean = when (st) {
            is BlockState.Full -> wk.canFinish(a, b, window(a..b, st.letters))
            is BlockState.Ends -> wk.good(a, st.first, false) && wk.good(b, st.last, true)
        }

        val k0 = wk.first()
        var a = k0
        var b = k0 - 1
        var states: Set<BlockState> = setOf(BlockState.Full(emptyList()))
        val keptAt = HashMap<Long, List<Boolean>>()
        val seen = HashMap<Triple<Long, Long, Set<BlockState>>, Pair<Long, Long>>()
        var rep: LongArray? = null // (a1, b1, period right, period left)
        while (rep == null) {
            val right = wk.nextRight(a, b)
            val k = if (right) b + 1 else a - 1
            val na = if (right) a else k
            val nb = if (right) k else b
            val kept = cell.none.toMutableList()
            for ((i, claimed) in cell.claims(k).withIndex()) {
                if (claimed) {
                    kept[i] = true
                    if (grow(states, a, b, k, kept.toList(), right).none { finishes(it, na, nb) }) kept[i] = false
                }
            }
            states = grow(states, a, b, k, kept.toList(), right).filter { finishes(it, na, nb) }.toSet()
            check(states.isNotEmpty()) { "no placement with the claims kept" }
            a = na
            b = nb
            keptAt[k] = kept.toList()
            if (right && b - a + 1 > 2 * w && wk.periodicBlock(a, b)) {
                val key = Triple((b - wk.kRight).mod(wk.tRight), (wk.kLeft - a).mod(wk.tLeft), states)
                val prev = seen[key]
                if (prev != null) rep = longArrayOf(prev.first, prev.second, b - prev.second, prev.first - a)
                else seen[key] = a to b
            }
        }

        val (a1, b1, pRight, pLeft) = rep.toList()
        val out = LinkedHashMap<String, MutableList<Region>>()
        fun drop(k: Long, tiles: Region) {
            val claims = cell.claims(k)
            val kept = keptAt.getValue(k)
            for ((i, kk) in cell.kinds.withIndex()) if (claims[i] && !kept[i]) out.getOrPut(kk) { mutableListOf() }.add(tiles)
        }
        for (k in a1..b1) drop(k, cell.unit.shift(g * k))
        for (t in 0 until pRight) drop(b1 + 1 + t, Region.tiled(cell.unit.shift(g * (b1 + 1 + t)), g * pRight, 0, null))
        for (t in 0 until pLeft) drop(a1 - 1 - t, Region.tiled(cell.unit.shift(g * (a1 - 1 - t)), g * pLeft, null, 0))
        return out.mapValues { Region.unionOf(it.value) }
    }

    /**
     * The claims of [cand] (in cell.base) to leave out on the lattice so that the line of no new anchor has no
     * violation, losing as few atoms of it as possible, and on a tie keeping the earliest claims: the closest to 0
     * (the left one first), then by [rank] (the leftmost in the rules).
     *
     * Claims more than 2R apart (R: how far a claim and its derivations reach) never take part in one violation, so
     * they form independent groups, each searched exactly ([fewestInGroup]). On an infinite side, the claims repeat
     * with the period of the data; if a gap of more than 2R repeats, one period of groups is solved and repeated. A
     * side without such a gap links infinitely many claims, where "fewest" means nothing: there the claims are kept
     * closest to 0 first instead ([keepInfinite]).
     */
    fun fewestLost(cell: Cell, cand: Claims, rank: Map<String, Int>): Map<String, Region> {
        val s = cell.s
        val g = cell.g
        val r = cell.r
        val bigR = (if (s.reach.sign != 0) (s.reach / g).ceil() else 0L) + (s.steps.flatten().maxOfOrNull { abs(it.second) } ?: 0L) + 1
        val kinds = s.order.filter { cand[it]?.isNotEmpty() == true }
        if (kinds.isEmpty()) return emptyMap()

        fun at(k: Long): List<String> = kinds.filter { cell.x(k) in cand.getValue(it) }

        val out = LinkedHashMap<String, MutableList<Region>>()

        /** Solve the groups of the claims at ks; tile = (period, rightwards) repeats them. */
        fun solve(ks: LongRange, tile: Pair<Long, Boolean>? = null) {
            val atoms = ks.flatMap { k -> at(k).map { KAtom(it, k) } }
            val groups = mutableListOf<MutableList<KAtom>>()
            for (a in atoms.sortedBy { it.k }) {
                if (groups.isNotEmpty() && a.k - groups.last().last().k <= 2 * bigR) groups.last().add(a) else groups.add(mutableListOf(a))
            }
            for (grp in groups) {
                for ((kk, k) in fewestInGroup(cell, grp, bigR, rank)) {
                    var orbit = cell.unit.shift(g * k)
                    if (tile != null) {
                        orbit = if (tile.second) Region.tiled(orbit, g * tile.first, 0, null) else Region.tiled(orbit, g * tile.first, null, 0)
                    }
                    out.getOrPut(kk) { mutableListOf() }.add(orbit)
                }
            }
        }

        val u = Region.unionOf(cand.values.toList())
        if (u.start.isFinite && u.end.isFinite) {
            solve(((u.start - r) / g).floor()..((u.end - r) / g).ceil())
            return out.mapValues { Region.unionOf(it.value) }
        }

        val (kRight, tRight) = cell.side(true)
        val (kLeft, tLeft) = cell.side(false)

        /** (first index of a repeating block, period), or null if the side links forever. */
        fun cut(right: Boolean): Pair<Long, Long>? {
            val start = if (right) kRight + bigR + 1 else kLeft - bigR - 1
            val t = if (right) tRight else tLeft
            val ks = (0 until 2 * t).map { if (right) start + it else start - it }
            var occ = ks.filter { at(it).isNotEmpty() }
            if (occ.isEmpty()) return start to t // no claim beyond
            occ = if (right) occ.sorted() else occ.sortedDescending()
            for ((a1, a2) in occ.zipWithNext()) {
                if (abs(a2 - a1) > 2 * bigR && abs(a1 - start) < t) return (if (right) a1 + 1 else a1 - 1) to t
            }
            return null
        }

        val cr = cut(true)
        val cl = cut(false)
        if (cr == null || cl == null) {
            val flags = FlagCell(s, r, cell.base, cell.nb, cell.acc, emptyMap(), cand)
            flags.unit = cell.unit
            return keepInfinite(flags)
        }
        val (cR, pR) = cr
        val (cL, pL) = cl
        solve(cL + 1 until cR)
        solve(cR until cR + pR, pR to true)
        solve(cL - pL + 1..cL, pL to false)
        return out.mapValues { Region.unionOf(it.value) }
    }

    /**
     * The claims of one group to leave out: among the subsets of claims kept whose line of no new anchor has no
     * violation near them, one with the most atoms, the earliest claims kept on a tie. Depth first, keeping before
     * leaving out, so the first best one found is the earliest; a subset is cut when its claims already have a
     * violation (more claims only add atoms) or when keeping every claim left could not beat the best one.
     */
    private fun fewestInGroup(cell: Cell, grp: List<KAtom>, bigR: Long, rank: Map<String, Int>): List<KAtom> {
        val order = grp.sortedWith(
            compareBy<KAtom, Rational>(orderKey) { cell.x(it.k) }
                .thenBy { rank[it.kind] ?: rank.size }
                .thenBy { it.kind },
        )
        val lo = grp.minOf { it.k } - bigR
        val hi = grp.maxOf { it.k } + bigR

        fun line(kept: Set<KAtom>): Pair<Model, Boolean> {
            val c = Cell(cell.s, cell.r, cell.base, cell.nb, cell.acc, emptyMap(), grp.filter { it !in kept }.toSet() + cell.removed)
            val st = c.closure(lo, hi, emptyMap())
            return st to (lo..hi).none { c.badAt(it, st) }
        }

        fun score(st: Model) = st.count()

        var bestScore = -1
        var bestKept: Set<KAtom> = emptySet()

        fun dfs(i: Int, kept: Set<KAtom>) {
            if (score(line(kept + order.subList(i, order.size)).first) <= bestScore) return
            if (i == order.size) {
                bestScore = score(line(kept).first)
                bestKept = kept
                return
            }
            val withIt = kept + order[i]
            if (line(withIt).second) dfs(i + 1, withIt)
            dfs(i + 1, kept)
        }

        check(line(emptySet()).second) { "a violation without the claims" }
        dfs(0, emptySet())
        return grp.filter { it !in bestKept }
    }

    /**
     * Backtracking over the new anchors at r + k*g, k in [ks], closest to 0 first; [check] says whether a partial
     * assignment has no violation yet (undecided anchors derive nothing, so a violation found stays). Returns
     * {(rule, k): operand}, or null if every assignment has a violation.
     */
    fun solveFinite(cell: Cell, ks: LongRange, check: (Map<Anchor, Int>) -> Boolean): Map<Anchor, Int>? {
        val vars = ks.flatMap { k -> cell.anys.withIndex().filter { (i, _) -> cell.data(k).new[i] }.map { (_, ri) -> Anchor(ri, k) } }
            .sortedWith(compareBy<Anchor, Rational>(orderKey) { cell.x(it.k) }.thenBy { cell.slot.getValue(it.rule) })
        val dec = LinkedHashMap<Anchor, Int>()
        if (!check(dec)) return null
        var i = 0
        val tried = IntArray(vars.size)
        while (i < vars.size) {
            val v = vars[i]
            val n = (cell.s.rules[v.rule] as LineRule.ImpliesAny).options.size
            var ok = false
            while (tried[i] < n) {
                dec[v] = tried[i]
                tried[i]++
                if (check(dec)) {
                    ok = true
                    break
                }
            }
            if (!ok) {
                dec.remove(v)
                tried[i] = 0
                i--
                if (i < 0) return null
                continue
            }
            i++
        }
        return dec
    }
}
