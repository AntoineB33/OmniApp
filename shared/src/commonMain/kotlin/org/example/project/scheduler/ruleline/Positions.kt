package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational

/**
 * Exact interval arithmetic on the line of positions x + kε — a port of `C:\code\line_manager\scripts\intervals.py`.
 *
 * A position is x + kε: x an exact rational, k an integer and ε a positive infinitesimal. Positions are compared by
 * x, then by k, so 1 < 1+ε < 1+2ε < 1.0001: x + ε is the position right after x (`docs/scheduler/scheduler_requirements.md`:
 * "When a rule needs the first position after x, it uses x + ε"). A real number x is the position x + 0ε. Lengths are
 * real: ε has none. ±∞ is a position too ([Pos.INF], [Pos.NEG_INF], k = 0), only ever an open end, and absorbs
 * whatever is added to it, as the reference's `math.inf` does.
 */
class Pos(val x: Rational, val k: Long = 0L) : Comparable<Pos> {
    init {
        require(x.isFinite || k == 0L) { "an infinite position has no ε part" }
    }

    val isInfinite: Boolean get() = x.isInfinite
    val isFinite: Boolean get() = x.isFinite

    operator fun plus(o: Pos): Pos = when {
        isInfinite || o.isInfinite -> {
            require(!(isInfinite && o.isInfinite && x != o.x)) { "inf - inf" }
            if (isInfinite) this else o
        }
        else -> Pos(x + o.x, k + o.k)
    }

    operator fun plus(o: Rational): Pos = this + Pos(o)

    operator fun minus(o: Pos): Pos = this + (-o)

    operator fun minus(o: Rational): Pos = this + Pos(-o)

    operator fun plus(n: Int): Pos = this + Pos(Rational.of(n))

    operator fun minus(n: Int): Pos = this + Pos(Rational.of(-n))

    operator fun unaryMinus(): Pos = Pos(-x, -k)

    /** n·p, for an integer n (the reference multiplies a position by an integer only). */
    operator fun times(n: Long): Pos {
        if (isInfinite) {
            require(n != 0L) { "0 * inf" }
            return if ((x.sign > 0) == (n > 0)) INF else NEG_INF
        }
        return Pos(x * n, mulExact(k, n))
    }

    operator fun times(n: Int): Pos = times(n.toLong())

    /** The largest integer n with n·p <= this (p > 0). */
    fun floorDiv(p: Pos): Long {
        var n = (x / p.x).floor()
        if (p.x * n == x && mulExact(n, p.k) > k) n -= 1
        return n
    }

    fun mod(p: Pos): Pos = this - p * floorDiv(p)

    fun abs(): Pos = if (this >= ZERO) this else -this

    val isZero: Boolean get() = x.sign == 0 && k == 0L

    override fun compareTo(other: Pos): Int {
        val c = x.compareTo(other.x)
        return if (c != 0) c else k.compareTo(other.k)
    }

    override fun equals(other: Any?): Boolean = other is Pos && k == other.k && x == other.x

    override fun hashCode(): Int = 31 * x.hashCode() + k.hashCode()

    /** The reference's `str(Pos)`: `1`, `1/2`, `1+ε`, `-2ε`, `+inf`. */
    override fun toString(): String {
        if (isInfinite) return if (x.sign > 0) "+inf" else "-inf"
        if (k == 0L) return x.toString()
        val e = if (kotlin.math.abs(k) == 1L) "ε" else "${kotlin.math.abs(k)}ε"
        if (x.sign == 0) return if (k > 0) e else "-$e"
        return x.toString() + (if (k > 0) "+" else "-") + e
    }

    companion object {
        val ZERO = Pos(Rational.ZERO)
        val ONE = Pos(Rational.ONE)
        val EPS = Pos(Rational.ZERO, 1L)
        val INF = Pos(Rational.INF)
        val NEG_INF = Pos(Rational.NEG_INF)

        fun of(n: Long, k: Long = 0L) = Pos(Rational.of(n), k)

        fun of(n: Int, k: Long = 0L) = Pos(Rational.of(n), k)

        /** `"3"`, `"-1/2"`, `"inf"`, `"-inf"`, `"+inf"`. */
        fun parse(s: String): Pos = when (s.trim()) {
            "inf", "+inf", "oo" -> INF
            "-inf", "-oo" -> NEG_INF
            else -> Pos(Rational.parse(s.trim()))
        }
    }
}

fun min(a: Pos, b: Pos): Pos = if (a <= b) a else b

fun max(a: Pos, b: Pos): Pos = if (a >= b) a else b

private fun valid(lo: Pos, hi: Pos): Boolean = when {
    lo.isInfinite -> lo.x.sign < 0 && (hi.isFinite || hi.x.sign > 0)
    hi.isInfinite -> hi.x.sign > 0
    else -> lo <= hi
}

/** [lo, hi] in the order of positions; an infinite end is open. */
class Interval internal constructor(val lo: Pos, val hi: Pos) {

    val loClosed: Boolean get() = lo.isFinite
    val hiClosed: Boolean get() = hi.isFinite
    val isPoint: Boolean get() = lo == hi

    /** The real length (ε has none); +inf for an unbounded interval. */
    val length: Rational get() = if (lo.isInfinite || hi.isInfinite) Rational.INF else hi.x - lo.x

    fun contains(p: Pos): Boolean = lo <= p && p <= hi

    /** Minkowski sum. */
    operator fun plus(o: Interval): Interval = Interval(lo + o.lo, hi + o.hi)

    operator fun unaryMinus(): Interval = Interval(-hi, -lo)

    override fun equals(other: Any?): Boolean = other is Interval && lo == other.lo && hi == other.hi

    override fun hashCode(): Int = 31 * lo.hashCode() + hi.hashCode()

    override fun toString(): String {
        if (isPoint) return "{$lo}"
        val a = when {
            lo.isInfinite -> "(-inf"
            lo.k == 1L -> "(" + lo.x
            else -> "[$lo"
        }
        val b = when {
            hi.isInfinite -> "+inf)"
            hi.k == -1L -> hi.x.toString() + ")"
            else -> "$hi]"
        }
        return "$a, $b"
    }

    companion object {
        /** The interval from [lo] to [hi], each end closed or open; null when empty. An infinite end must be open. */
        fun make(lo: Pos, hi: Pos, loClosed: Boolean = true, hiClosed: Boolean = true): Interval? {
            require(!(lo.isInfinite && loClosed) && !(hi.isInfinite && hiClosed)) { "an infinite end is open" }
            val l = if (lo.isFinite && !loClosed) lo + Pos.EPS else lo
            val h = if (hi.isFinite && !hiClosed) hi - Pos.EPS else hi
            return if (valid(l, h)) Interval(l, h) else null
        }

        fun of(lo: Pos, hi: Pos, loClosed: Boolean = true, hiClosed: Boolean = true): Interval =
            make(lo, hi, loClosed, hiClosed) ?: throw IllegalArgumentException("empty or invalid interval $lo $hi")

        fun closed(a: Pos, b: Pos) = of(a, b, true, true)

        fun open(a: Pos, b: Pos) = of(a, b, false, false)

        fun closedOpen(a: Pos, b: Pos) = of(a, b, a != Pos.NEG_INF, false)

        fun openClosed(a: Pos, b: Pos) = of(a, b, false, b != Pos.INF)

        fun point(a: Pos): Interval = Interval(a, a)

        /** `'[a, b)'`, `'(a, b]'`, `'{a}'` or a bare number (a point); `inf`/`-inf` allowed. */
        fun parse(text: String): Interval {
            val t = text.trim()
            if (t.startsWith("{") && t.endsWith("}")) return point(Pos.parse(t.substring(1, t.length - 1)))
            if (t[0] in "[(" && t[t.length - 1] in "])") {
                val (a, b) = t.substring(1, t.length - 1).split(",").map { it.trim() }
                return of(Pos.parse(a), Pos.parse(b), t[0] == '[', t[t.length - 1] == ']')
            }
            return point(Pos.parse(t))
        }
    }
}

private fun few(a: List<Interval>, b: List<Interval>): Boolean = 16 * a.size < b.size

/** The first interval that ends at [x] or after (the intervals are sorted, apart). */
internal fun reaching(ivs: List<Interval>, x: Pos): Int {
    var lo = 0
    var hi = ivs.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (ivs[mid].hi < x) lo = mid + 1 else hi = mid
    }
    return lo
}

/** The first interval that starts after [x]. */
private fun beyond(ivs: List<Interval>, x: Pos): Int {
    var lo = 0
    var hi = ivs.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (x < ivs[mid].lo) hi = mid else lo = mid + 1
    }
    return lo
}

private fun onFew(many: List<Interval>, few: List<Interval>): List<Interval> {
    val out = ArrayList<Interval>()
    for (y in few) {
        val i = reaching(many, y.lo)
        val j = beyond(many, y.hi)
        if (i >= j) continue
        val first = many[i]
        val last = many[j - 1]
        if (i == j - 1) {
            out.add(Interval(max(first.lo, y.lo), min(first.hi, y.hi)))
        } else {
            out.add(Interval(max(first.lo, y.lo), first.hi))
            out.addAll(many.subList(i + 1, j - 1))
            out.add(Interval(last.lo, min(last.hi, y.hi)))
        }
    }
    return out
}

private fun withoutFew(many: List<Interval>, few: List<Interval>): List<Interval> {
    val out = ArrayList<Interval>()
    var pos = 0
    var rest: Interval? = null // what is left of the interval before many[pos]
    for (y in few) {
        val r = rest
        if (r != null) {
            if (r.hi < y.lo) {
                out.add(r)
            } else {
                if (valid(r.lo, y.lo - Pos.EPS)) out.add(Interval(r.lo, y.lo - Pos.EPS))
                if (valid(y.hi + Pos.EPS, r.hi)) {
                    rest = Interval(y.hi + Pos.EPS, r.hi)
                    continue
                }
            }
            rest = null
        }
        val i = maxOf(reaching(many, y.lo), pos)
        val j = maxOf(beyond(many, y.hi), i)
        out.addAll(many.subList(pos, i))
        if (i < j) {
            if (valid(many[i].lo, y.lo - Pos.EPS)) out.add(Interval(many[i].lo, y.lo - Pos.EPS))
            if (valid(y.hi + Pos.EPS, many[j - 1].hi)) rest = Interval(y.hi + Pos.EPS, many[j - 1].hi)
        }
        pos = j
    }
    rest?.let { out.add(it) }
    out.addAll(many.subList(pos, many.size))
    return out
}

private fun withFew(many: List<Interval>, few: List<Interval>): List<Interval> {
    val out = ArrayList<Interval>()
    var pos = 0
    for (y in few) {
        var lo = y.lo
        var hi = y.hi
        if (out.isNotEmpty() && lo <= out.last().hi + Pos.EPS) { // the interval before reaches this one
            val prev = out.removeAt(out.size - 1)
            lo = prev.lo
            hi = max(hi, prev.hi)
        }
        val i = maxOf(reaching(many, lo - Pos.EPS), pos)
        val j = maxOf(beyond(many, hi + Pos.EPS), i)
        out.addAll(many.subList(pos, i))
        if (i < j) {
            lo = min(lo, many[i].lo)
            hi = max(hi, many[j - 1].hi)
        }
        out.add(Interval(lo, hi))
        pos = j
    }
    out.addAll(many.subList(pos, many.size))
    return out
}

/**
 * A finite union of intervals in canonical form (sorted, disjoint, not touching), so two sets are equal iff their
 * interval lists are equal.
 */
class IntervalSet private constructor(val intervals: List<Interval>) {

    val isEmpty: Boolean get() = intervals.isEmpty()
    fun isNotEmpty(): Boolean = intervals.isNotEmpty()

    override fun equals(other: Any?): Boolean = other is IntervalSet && intervals == other.intervals

    override fun hashCode(): Int = intervals.hashCode()

    fun contains(p: Pos): Boolean {
        val i = reaching(intervals, p)
        return i < intervals.size && intervals[i].lo <= p
    }

    fun isSubsetOf(other: IntervalSet): Boolean = (this - other).isEmpty

    // A few intervals against many only look at what they touch; the rest is copied as one slice.
    infix fun or(other: IntervalSet): IntervalSet {
        if (other.isEmpty) return this
        if (isEmpty) return other
        var a = intervals
        var b = other.intervals
        if (few(a, b)) a = b.also { b = a }
        if (few(b, a)) return IntervalSet(withFew(a, b))
        return of(a + b)
    }

    fun complement(): IntervalSet {
        val out = ArrayList<Interval>()
        var lo = Pos.NEG_INF
        for (iv in intervals) {
            val hi = iv.lo - Pos.EPS
            if (valid(lo, hi)) out.add(Interval(lo, hi))
            lo = iv.hi + Pos.EPS
        }
        if (valid(lo, Pos.INF)) out.add(Interval(lo, Pos.INF))
        return IntervalSet(out)
    }

    infix fun and(other: IntervalSet): IntervalSet {
        var a = intervals
        var b = other.intervals
        if (few(a, b)) a = b.also { b = a }
        if (few(b, a)) return IntervalSet(onFew(a, b))
        val out = ArrayList<Interval>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val x = a[i]
            val y = b[j]
            val lo = max(x.lo, y.lo)
            val hi = min(x.hi, y.hi)
            if (valid(lo, hi)) out.add(Interval(lo, hi))
            if (x.hi <= y.hi) i++ else j++
        }
        return IntervalSet(out)
    }

    operator fun minus(other: IntervalSet): IntervalSet {
        if (other.isEmpty || isEmpty) return this
        val a = intervals
        var b = other
        if (few(b.intervals, a)) return IntervalSet(withoutFew(a, b.intervals))
        if (few(a, b.intervals)) { // only what lies between self's first and last interval matters
            val bi = b.intervals
            b = IntervalSet(bi.subList(reaching(bi, a[0].lo), maxOf(reaching(bi, a[0].lo), beyond(bi, a.last().hi))))
        }
        return this and b.complement()
    }

    /** Minkowski sum. */
    operator fun plus(other: IntervalSet): IntervalSet = of(intervals.flatMap { x -> other.intervals.map { y -> x + y } })

    operator fun unaryMinus(): IntervalSet = of(intervals.map { -it })

    fun shift(d: Pos): IntervalSet = IntervalSet(intervals.map { Interval(it.lo + d, it.hi + d) })

    fun measure(): Rational = intervals.fold(Rational.ZERO) { s, iv -> s + iv.length }

    /** The number of components of length 0. */
    fun points(): Int = intervals.count { it.length == Rational.ZERO }

    fun inf(): Pos = intervals.firstOrNull()?.lo ?: Pos.INF

    fun sup(): Pos = intervals.lastOrNull()?.hi ?: Pos.NEG_INF

    fun isBounded(): Boolean = isEmpty || (inf() != Pos.NEG_INF && sup() != Pos.INF)

    /** A subset of measure min([amount], measure) taken from one end. */
    fun take(amount: Rational, fromRight: Boolean = false): IntervalSet {
        val out = ArrayList<Interval>()
        var left = amount
        for (iv in if (fromRight) intervals.asReversed() else intervals) {
            if (left <= Rational.ZERO) break
            if (iv.length == Rational.ZERO) continue
            if (iv.length <= left) {
                out.add(iv)
                left -= iv.length
            } else if (fromRight) {
                out.add(Interval.of(Pos(iv.hi.x - left), iv.hi))
                left = Rational.ZERO
            } else {
                out.add(Interval.of(iv.lo, Pos(iv.lo.x + left), true, false))
                left = Rational.ZERO
            }
        }
        return of(out)
    }

    override fun toString(): String = if (isEmpty) "{}" else intervals.joinToString(" U ")

    companion object {
        val EMPTY = IntervalSet(emptyList())

        val UNIVERSE = IntervalSet(listOf(Interval(Pos.NEG_INF, Pos.INF)))

        /** A set of [ivs], put in canonical form. */
        fun of(ivs: Iterable<Interval>): IntervalSet {
            val sorted = ivs.sortedBy { it.lo }
            val out = ArrayList<Interval>()
            for (iv in sorted) {
                if (out.isNotEmpty()) {
                    val cur = out.last()
                    if (iv.lo <= cur.hi + Pos.EPS) { // overlapping or touching
                        if (iv.hi > cur.hi) out[out.size - 1] = Interval(cur.lo, iv.hi)
                        continue
                    }
                }
                out.add(iv)
            }
            return IntervalSet(out)
        }

        fun of(vararg ivs: Interval): IntervalSet = of(ivs.asList())

        /** Already sorted, disjoint and apart: taken as is. */
        internal fun sorted(ivs: List<Interval>): IntervalSet = IntervalSet(ivs)

        /** (x, +inf) */
        fun after(x: Pos): IntervalSet = of(Interval.of(x, Pos.INF, false, false))

        /** (-inf, x] */
        fun upTo(x: Pos): IntervalSet = of(Interval.of(Pos.NEG_INF, x, false, true))
    }
}

/** a·b, throwing rather than wrapping on overflow (an ε count that wraps would silently reorder positions). */
internal fun mulExact(a: Long, b: Long): Long {
    val r = a * b
    if (a != 0L && (r / a != b || (a == -1L && b == Long.MIN_VALUE))) throw ArithmeticException("Long overflow")
    return r
}
