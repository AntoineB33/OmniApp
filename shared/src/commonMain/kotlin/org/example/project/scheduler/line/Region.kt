package org.example.project.scheduler.line

import org.example.project.scheduler.line.Rational.Companion.INF
import org.example.project.scheduler.line.Rational.Companion.NEG_INF
import org.example.project.scheduler.line.Rational.Companion.ZERO

/**
 * Exact 1D region algebra — a port of `C:\code\line_manager\reverse_logic\region.py` (SEMANTICS.md § 1).
 *
 * A region is a union of intervals with rational (or infinite) ends, each end open or closed: finitely many
 * [parts] (the core), and on each side, optionally, a [Tail] repeating a pattern forever with a rational period.
 * The family is closed under union, intersection, difference and translation; removing [0, 1] from [0, 2]
 * gives (1, 2], not [1, 2].
 *
 * Regions are kept CANONICAL — the core sorted, disjoint and maximal; a tail with its least period, starting
 * where the region starts to repeat; a constant tail turned into a core part — so data-class equality is set
 * equality. The constructor does not normalize: build regions with the companion's functions.
 */
data class Region(val parts: List<Part> = emptyList(), val left: Tail? = null, val right: Tail? = null) {

    /** One interval; a point is a degenerate closed one. */
    data class Part(val lo: Rational, val loClosed: Boolean, val hi: Rational, val hiClosed: Boolean) {
        fun has(x: Rational): Boolean = (lo < x || (loClosed && lo == x)) && (x < hi || (hiClosed && x == hi))
    }

    /**
     * Beyond [at] (above it for a right tail, below it for a left one), x is in the region iff
     * `(x - at) mod period` is in [pattern], the canonical parts of a region within [0, period).
     */
    data class Tail(val at: Rational, val period: Rational, val pattern: List<Part>) {
        fun has(x: Rational): Boolean {
            val y = (x - at) % period
            return pattern.any { it.has(y) }
        }
    }

    /** A cell of a partition of the line: a point (a == b) or an open interval, with a point inside it. */
    data class Cell(val isPoint: Boolean, val a: Rational, val b: Rational, val rep: Rational)

    // ---- queries ------------------------------------------------------------

    operator fun contains(x: Rational): Boolean {
        if (right != null && x > right.at) return right.has(x)
        if (left != null && x < left.at) return left.has(x)
        return parts.any { it.has(x) }
    }

    fun isEmpty(): Boolean = parts.isEmpty() && left == null && right == null

    fun isNotEmpty(): Boolean = !isEmpty()

    /** Finitely many parts (no tail). */
    fun isFinite(): Boolean = left == null && right == null

    /** The infimum of the region. */
    val start: Rational
        get() {
            require(!isEmpty()) { "empty region has no start" }
            if (left != null) return NEG_INF
            if (parts.isNotEmpty()) return parts[0].lo
            return right!!.at + right.pattern[0].lo
        }

    /** The supremum of the region. */
    val end: Rational
        get() {
            require(!isEmpty()) { "empty region has no end" }
            if (right != null) return INF
            if (parts.isNotEmpty()) return parts.last().hi
            return left!!.at - left.period + left.pattern.last().hi
        }

    /** The region on [lo, hi], without tails ([lo], [hi] may be infinite on a side without a tail). */
    fun within(lo: Rational, hi: Rational): Region {
        val out = mutableListOf(flat(parts) and closedWindow(lo, hi))
        for ((tail, isRight) in listOf(right to true, left to false)) {
            if (tail == null) continue
            val a = if (isRight) max(lo, tail.at) else lo
            val b = if (isRight) hi else min(hi, tail.at)
            if (a > b) continue
            check(a.isFinite && b.isFinite) { "an unbounded window of a periodic tail" }
            val pat = flat(tail.pattern)
            val side = if (isRight) above(tail.at) else below(tail.at)
            val window = closedWindow(a, b)
            for (k in ((a - tail.at) / tail.period).floor() - 1 until ((b - tail.at) / tail.period).ceil() + 1) {
                out.add(pat.shift(tail.at + tail.period * k) and side and window)
            }
        }
        return unionOf(out)
    }

    /** The finite ends of the parts of the region within [lo, hi]. */
    fun ends(lo: Rational, hi: Rational): List<Rational> =
        within(lo, hi).parts.flatMap { listOf(it.lo, it.hi) }.filter { it.isFinite && lo <= it && it <= hi }.distinct().sorted()

    fun periods(): List<Rational> = listOfNotNull(left?.period, right?.period)

    /** The total length (the region must be bounded). */
    fun measure(): Rational {
        check(isFinite())
        return parts.fold(ZERO) { s, p -> s + (p.hi - p.lo) }
    }

    // ---- algebra ------------------------------------------------------------

    infix fun or(other: Region): Region {
        if (other.isEmpty()) return this
        if (isEmpty()) return other
        return combine(listOf(this, other)) { it[0] || it[1] }
    }

    infix fun and(other: Region): Region {
        if (isEmpty() || other.isEmpty()) return EMPTY
        return combine(listOf(this, other)) { it[0] && it[1] }
    }

    operator fun minus(other: Region): Region {
        if (isEmpty() || other.isEmpty()) return this
        return combine(listOf(this, other)) { it[0] && !it[1] }
    }

    infix fun xor(other: Region): Region = combine(listOf(this, other)) { it[0] != it[1] }

    fun shift(delta: Rational): Region {
        if (delta == ZERO) return this
        return Region(
            parts.map { Part(it.lo + delta, it.loClosed, it.hi + delta, it.hiClosed) },
            left?.let { Tail(it.at + delta, it.period, it.pattern) },
            right?.let { Tail(it.at + delta, it.period, it.pattern) },
        )
    }

    override fun toString(): String {
        val out = mutableListOf<String>()
        if (left != null) out.add("(…${show(left.pattern)} every ${left.period} below ${left.at})")
        parts.forEach { out.add(show(listOf(it))) }
        if (right != null) out.add("(${show(right.pattern)} every ${right.period} above ${right.at}…)")
        return if (out.isEmpty()) "∅" else out.joinToString(" ∪ ")
    }

    companion object {
        val EMPTY = Region()
        val FULL = Region(listOf(Part(NEG_INF, false, INF, false)))

        fun above(x: Rational) = Region(listOf(Part(x, false, INF, false)))

        fun below(x: Rational) = Region(listOf(Part(NEG_INF, false, x, false)))

        fun atOrBelow(x: Rational) = Region(listOf(Part(NEG_INF, false, x, true)))

        fun atOrAbove(x: Rational) = Region(listOf(Part(x, true, INF, false)))

        fun interval(a: Rational, b: Rational, leftClosed: Boolean = true, rightClosed: Boolean = true): Region {
            require(a <= b) { "interval start $a is after its end $b" }
            if (a == b && !(leftClosed && rightClosed)) return EMPTY
            return combine(listOf(Region(listOf(Part(a, leftClosed, b, rightClosed))))) { it[0] }
        }

        fun closed(a: Rational, b: Rational) = interval(a, b, true, true)

        fun open(a: Rational, b: Rational) = interval(a, b, false, false)

        fun point(x: Rational) = interval(x, x)

        fun points(vararg xs: Rational): Region = unionOf(xs.map { point(it) })

        fun closed(a: Int, b: Int) = closed(a.q, b.q)

        fun open(a: Int, b: Int) = open(a.q, b.q)

        fun point(x: Int) = point(x.q)

        fun points(vararg xs: Int): Region = unionOf(xs.map { point(it.q) })

        fun interval(a: Int, b: Int, leftClosed: Boolean = true, rightClosed: Boolean = true) =
            interval(a.q, b.q, leftClosed, rightClosed)

        fun unionOf(regions: List<Region>): Region {
            if (regions.isEmpty()) return EMPTY
            if (regions.any { !it.isFinite() }) return regions.fold(EMPTY) { out, r -> out or r }
            return combine(regions) { m -> m.any { it } }
        }

        fun unionOf(vararg regions: Region): Region = unionOf(regions.toList())

        /** Partition of the line on which every given region (without tails) is constant. */
        fun cellsOf(regions: Iterable<Region>): List<Cell> {
            val ends = ArrayList<Rational>()
            for (r in regions) {
                check(r.isFinite()) { "cells of a periodic region" }
                for (p in r.parts) {
                    if (p.lo.isFinite) ends.add(p.lo)
                    if (p.hi.isFinite) ends.add(p.hi)
                }
            }
            val sorted = ends.distinct().sorted()
            if (sorted.isEmpty()) return listOf(Cell(false, NEG_INF, INF, ZERO))
            val cells = ArrayList<Cell>(2 * sorted.size + 1)
            cells.add(Cell(false, NEG_INF, sorted[0], sorted[0] - Rational.ONE))
            for ((i, e) in sorted.withIndex()) {
                cells.add(Cell(true, e, e, e))
                val nxt = if (i + 1 < sorted.size) sorted[i + 1] else INF
                cells.add(Cell(false, e, nxt, if (nxt.isInfinite) e + Rational.ONE else (e + nxt) / 2))
            }
            return cells
        }

        fun fromCells(cells: List<Cell>, flags: List<Boolean>): Region {
            val parts = ArrayList<Part>()
            var cur: Part? = null
            for (i in cells.indices) {
                val c = cells[i]
                if (flags[i]) {
                    cur = if (cur == null) Part(c.a, c.isPoint, c.b, c.isPoint) else cur.copy(hi = c.b, hiClosed = c.isPoint)
                } else if (cur != null) {
                    parts.add(cur)
                    cur = null
                }
            }
            if (cur != null) parts.add(cur)
            return Region(parts)
        }

        internal fun combine(regions: List<Region>, fn: (List<Boolean>) -> Boolean): Region {
            if (regions.all { it.isFinite() }) {
                val cells = cellsOf(regions)
                return fromCells(cells, cells.map { c -> fn(regions.map { c.rep in it }) })
            }
            return canonical(combinePeriodic(regions, fn))
        }

        /**
         * The union of `cell + k * step` for `first <= k <= last` (null: without bound). [cell] is a bounded
         * region without tails.
         */
        fun tiled(cell: Region, step: Rational, first: Long?, last: Long?): Region {
            if (cell.isEmpty()) return EMPTY
            check(cell.isFinite() && step > ZERO)
            val lo = cell.parts[0].lo
            val hi = cell.parts.last().hi
            check(lo.isFinite && hi.isFinite) { "a tiled cell must be bounded" }
            val span = ((hi - lo) / step).ceil() + 1
            fun copies(range: LongRange) = unionOf(range.map { cell.shift(step * it) })
            if (first != null && last != null) return copies(first..last)
            if (first == null && last == null) return tiled(cell, step, 0, null) or tiled(cell, step, null, -1)
            if (last == null) {
                // Above `at`, only copies first + i, i >= 0, can meet x, and all of them do as x grows.
                val at = hi + step * (first!! - 1)
                val core = copies(first..first + span)
                val window = copies(first..first + 2 * span + 1)
                val pattern = pattern(window, at, step, right = true)
                return canonical(Region((core and atOrBelow(at)).parts, null, Tail(at, step, pattern)))
            }
            val at = lo + step * (last + 1)
            val core = copies(last - span..last)
            val window = copies(last - 2 * span - 1..last)
            val pattern = pattern(window, at, step, right = false)
            return canonical(Region((core and atOrAbove(at)).parts, Tail(at, step, pattern), null))
        }

        private fun flat(parts: List<Part>) = Region(parts)

        private fun closedWindow(lo: Rational, hi: Rational) = Region(listOf(Part(lo, lo.isFinite, hi, hi.isFinite)))

        private fun show(parts: List<Part>): String =
            if (parts.isEmpty()) "∅" else parts.joinToString(" ∪ ") {
                if (it.lo == it.hi) "{${it.lo}}"
                else "${if (it.loClosed) "[" else "("}${it.lo}, ${it.hi}${if (it.hiClosed) "]" else ")"}"
            }

        /**
         * The pattern, within [0, period), of [region] just beyond [at]: on (at, at + period] for a right tail,
         * [at - period, at) for a left one.
         */
        private fun pattern(region: Region, at: Rational, period: Rational, right: Boolean): List<Part> {
            if (right) {
                val piece = region.within(at, at + period) and above(at)
                return ((piece.shift(-at) and below(period)) or (if ((at + period) in piece) point(ZERO) else EMPTY)).parts
            }
            val piece = region.within(at - period, at) and below(at)
            return piece.shift(period - at).parts
        }

        /** Beyond it (above for right, below for left), [r] is periodic or constant. */
        private fun threshold(r: Region, right: Boolean): Rational? {
            val tail = if (right) r.right else r.left
            val other = if (right) r.left else r.right
            if (tail != null) return tail.at
            val ends = r.parts.flatMap { listOf(it.lo, it.hi) }.filter { it.isFinite }.toMutableList()
            if (other != null) ends.add(other.at)
            if (ends.isEmpty()) return null
            return if (right) ends.max() else ends.min()
        }

        /** [fn] applied pointwise, for regions with tails (the result is not canonical). */
        private fun combinePeriodic(regions: List<Region>, fn: (List<Boolean>) -> Boolean): Region {
            val sides = LinkedHashMap<Boolean, Pair<Rational, Rational>>()
            for (right in listOf(true, false)) {
                val periods = regions.mapNotNull { (if (right) it.right else it.left)?.period }
                if (periods.isEmpty()) continue
                val p = periods.drop(1).fold(periods[0]) { acc, q -> Rational.lcm(acc, q) }
                val ts = regions.mapNotNull { threshold(it, right) }
                sides[right] = (if (right) ts.max() else ts.min()) to p
            }
            val lo = sides[false]?.first ?: NEG_INF
            val hi = sides[true]?.first ?: INF
            val core = combine(regions.map { it.within(lo, hi) }, fn)
            val tails = HashMap<Boolean, Tail>()
            for ((right, ap) in sides) {
                val (at, period) = ap
                val piece = if (right) combine(regions.map { it.within(at, at + period) }, fn)
                else combine(regions.map { it.within(at - period, at) }, fn)
                tails[right] = Tail(at, period, pattern(piece, at, period, right))
            }
            return Region(core.parts, tails[false], tails[true])
        }

        /** The pattern shifted by [d], modulo [period]. */
        private fun rotate(pattern: Region, d: Rational, period: Rational): Region {
            val s = pattern.shift(d)
            return (s and below(period)) or (s and atOrAbove(period)).shift(-period)
        }

        /** The least period of the pattern (a divisor of [period]), and the pattern for it. */
        private fun leastPeriod(pattern: List<Part>, period: Rational): Pair<List<Part>, Rational> {
            val pat = flat(pattern)
            val ends = pattern.flatMap { listOf(it.lo, it.hi) }.toSet()
            for (n in ends.size + 1 downTo 2) {
                val d = period / n
                if (rotate(pat, d, period) == pat) return (pat and below(d)).parts to d
            }
            return pattern to period
        }

        /** The supremum of a region, canonical or not (-inf if empty). */
        private fun sup(r: Region): Rational {
            var out = NEG_INF
            if (r.right != null && r.right.pattern.isNotEmpty()) return INF
            if (r.parts.isNotEmpty()) out = max(out, r.parts.last().hi)
            if (r.left != null && r.left.pattern.isNotEmpty()) out = max(out, r.left.at - r.left.period + r.left.pattern.last().hi)
            return out
        }

        /** The infimum of a region, canonical or not (+inf if empty). */
        private fun inf(r: Region): Rational {
            var out = INF
            if (r.left != null && r.left.pattern.isNotEmpty()) return NEG_INF
            if (r.parts.isNotEmpty()) out = min(out, r.parts[0].lo)
            if (r.right != null && r.right.pattern.isNotEmpty()) out = min(out, r.right.at + r.right.pattern[0].lo)
            return out
        }

        /**
         * The canonical form: tails with their least period, starting where the region starts to repeat; constant
         * tails become core parts. The right tail of a set S with period P starts at sup{x : (x in S) != (x + P in S)}
         * (0 if S is P-periodic everywhere); the left one at the corresponding infimum, but not after the right
         * one's start. Both are functions of S, so equal sets have equal canonical forms.
         */
        private fun canonical(r: Region): Region {
            var core = flat(r.parts)
            val tails = LinkedHashMap<Boolean, Tail>()
            for ((right, tail) in listOf(true to r.right, false to r.left)) {
                if (tail == null) continue
                val pat = flat(tail.pattern)
                if (pat.isEmpty()) continue
                if (pat == interval(ZERO, tail.period, true, false)) {
                    core = core or (if (right) above(tail.at) else below(tail.at))
                    continue
                }
                val (pp, d) = leastPeriod(tail.pattern, tail.period)
                tails[right] = Tail(tail.at, d, pp)
            }
            if (tails.isEmpty()) return core
            val whole = Region(core.parts, tails[false], tails[true])
            val starts = HashMap<Boolean, Rational>()
            tails[true]?.let { t ->
                val diff = combinePeriodic(listOf(whole, whole.shift(-t.period))) { it[0] != it[1] }
                starts[true] = if (!diff.isEmpty() && sup(diff) > NEG_INF) sup(diff) else ZERO
            }
            tails[false]?.let { t ->
                val diff = combinePeriodic(listOf(whole, whole.shift(t.period))) { it[0] != it[1] }
                val at = if (!diff.isEmpty() && inf(diff) < INF) inf(diff) else ZERO
                starts[false] = starts[true]?.let { min(at, it) } ?: at
            }
            val lo = starts[false] ?: NEG_INF
            val hi = starts[true] ?: INF
            val newCore = whole.within(lo, hi)
            val newTails = tails.mapValues { (right, t) -> Tail(starts[right]!!, t.period, pattern(whole, starts[right]!!, t.period, right)) }
            return Region(newCore.parts, newTails[false], newTails[true])
        }
    }
}
