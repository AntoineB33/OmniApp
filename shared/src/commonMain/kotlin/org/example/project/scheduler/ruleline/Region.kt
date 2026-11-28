package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational

/**
 * Exact subsets of the line that may repeat forever — a port of `C:\code\line_manager\scripts\region.py`.
 *
 * A region is a core plus a periodic tail on each side:
 *
 *     x < lo:        x ∈ R  iff  (x - lo) mod pl ∈ left      (left  ⊆ [0, pl))
 *     lo <= x < hi:  x ∈ R  iff  x ∈ core                     (core  ⊆ [lo, hi))
 *     x >= hi:       x ∈ R  iff  (x - hi) mod pr ∈ right     (right ⊆ [0, pr))
 *
 * Bounds and periods are positions x + kε ([Pos]); a period has a positive real part and may have an ε part. Every
 * finite union of intervals is a region whose tails are constant (empty or full), and the family is closed under ∪, ∩,
 * \, complement and translation, except that two tails repeating with periods of different slopes (k/x) have no common
 * period ([IncompatiblePeriods]).
 *
 * [Lazy] keeps a region as the operation that makes it when computing it would write thousands of periods out; it
 * answers exactly what the computed region would.
 */
class IncompatiblePeriods(message: String) : IllegalArgumentException(message)

private fun flcm(a: Rational, b: Rational): Rational {
    val d = Rational.lcmLong(a.den, b.den)
    val x = (a * Rational.of(d)).toLongExact()
    val y = (b * Rational.of(d)).toLongExact()
    return Rational.of(mulExact(x / Rational.gcd(x, y), y), d)
}

/** The least common multiple of two periods. */
fun plcm(p: Pos, q: Pos): Pos {
    val l = flcm(p.x, q.x)
    val n = (l / p.x).toLongExact()
    val m = (l / q.x).toLongExact()
    if (mulExact(n, p.k) != mulExact(m, q.k)) throw IncompatiblePeriods("no common period for $p and $q")
    return Pos(l, mulExact(n, p.k))
}

private val NONE = IntervalSet.EMPTY

/** [a, b) for positions a, b. */
internal fun window(a: Pos, b: Pos): IntervalSet = if (a < b) IntervalSet.sorted(listOf(Interval(a, b - Pos.EPS))) else NONE

private fun constant(pattern: IntervalSet, period: Pos): Boolean = pattern.isEmpty || pattern == window(Pos.ZERO, period)

/** What repeats, as text: in parentheses if it is several intervals. */
private fun group(s: IntervalSet): String = if (s.intervals.size > 1) "($s)" else s.toString()

/** [s] on [a, b): the intervals that can meet it found by bisection, then cut as `s & [a, b)` would. */
private fun clip(s: IntervalSet, a: Pos, b: Pos): IntervalSet {
    val ivs = s.intervals
    if (ivs.size < 32) return s and window(a, b)
    val i = reaching(ivs, a)
    var j = i
    while (j < ivs.size && ivs[j].lo < b) j++
    if (i >= j) return NONE
    return IntervalSet.sorted(ivs.subList(i, j)) and window(a, b)
}

/** The prime factors of n > 0, with their multiplicity. */
private fun factors(n0: Long): List<Long> {
    var n = n0
    val out = ArrayList<Long>()
    var d = 2L
    while (d * d <= n && d < 100000) {
        while (n % d == 0L) {
            out.add(d)
            n /= d
        }
        d++
    }
    if (n > 1) out.add(n)
    return out
}

/** A cheap test before the whole one: a few pieces, each found again q further, and as many pieces as p copies make. */
private fun probablyRepeats(pattern: IntervalSet, q: Pos, period: Pos): Boolean {
    val ivs = pattern.intervals
    val p = (period.x / q.x).toLongExact()
    val r = ivs.size.toLong() % p
    if (r != 0L && r != 1L && r != p - 1 && p > 2) return false
    val probe = ivs.take(3) + ivs.subList(minOf(ivs.size / 2, ivs.size), minOf(ivs.size / 2 + 2, ivs.size))
    for (iv in probe) {
        var lo = iv.lo + q
        var hi = iv.hi + q
        if (hi >= period) {
            lo -= period
            hi -= period
            if (lo < Pos.ZERO) continue
        }
        val got = clip(pattern, lo, hi + Pos.EPS).intervals
        if (got.isEmpty() || got[0].lo > lo || got[0].hi < hi) return false
    }
    return true
}

/** Periods up to it are kept as they are ([shortest]). */
const val LONG_PERIOD: Long = 86400

/**
 * (the pattern, its period) with the shortest period it repeats with, among the period's divisions by a prime factor
 * of its real part's numerator (and of its ε part) — only for a period longer than [LONG_PERIOD]. A constant pattern
 * is left as it is.
 */
private fun shortest(pattern0: IntervalSet, period0: Pos): Pair<IntervalSet, Pos> {
    var pattern = pattern0
    var period = period0
    if (constant(pattern, period) || period.x <= Rational.of(LONG_PERIOD)) return pattern to period
    var changed = true
    while (changed) {
        changed = false
        for (p in factors(period.x.num).distinct().sorted()) {
            if (period.k % p != 0L) continue
            val q = Pos(period.x / Rational.of(p), period.k / p)
            if (!probablyRepeats(pattern, q, period)) continue
            val part = pattern and window(Pos.ZERO, q)
            val again = IntervalSet.of((0 until p).flatMap { i -> part.shift(q * i).intervals })
            if (again == pattern) {
                pattern = part
                period = q
                changed = true
                break
            }
        }
    }
    return pattern to period
}

/** A bound h with core ⊆ (-inf, h). */
private fun last(core: IntervalSet, default: Pos): Pos = if (core.isEmpty) default else core.intervals.last().hi + Pos.EPS

/** Two regions whose tails repeat together only after more than this: a lazy region. */
const val LAZY_PERIOD: Long = 7 * 86400

/** A lazy region built on this many others is computed (its reads would chain too far). */
const val LAZY_DEPTH: Int = 24

/** A combination that would write more periods than this between its parts: lazy. */
const val LAZY_PIECES: Long = 4096

/** [Region.componentsMeeting]'s answer for infinitely many components. */
const val INFINITELY_MANY: Long = Long.MAX_VALUE

enum class RegionOp { OR, AND, SUB }

private fun apply(op: RegionOp, a: IntervalSet, b: IntervalSet): IntervalSet = when (op) {
    RegionOp.OR -> a or b
    RegionOp.AND -> a and b
    RegionOp.SUB -> a - b
}

abstract class Region {
    abstract val lo: Pos
    abstract val hi: Pos
    abstract val core: IntervalSet
    abstract val pl: Pos
    abstract val left: IntervalSet
    abstract val pr: Pos
    abstract val right: IntervalSet

    // ------------------------------------------------------------ read
    open fun contains(x: Pos): Boolean {
        if (x < lo) return left.isNotEmpty() && left.contains((x - lo).mod(pl))
        if (x >= hi) return right.isNotEmpty() && right.contains((x - hi).mod(pr))
        return core.contains(x)
    }

    /** The part of the region in [a, b) (a, b finite), as a finite union of intervals. */
    open fun on(a: Pos, b: Pos): IntervalSet {
        val parts = ArrayList<IntervalSet>()
        parts.add(clip(core, a, b))
        if (b > hi && right.isNotEmpty()) {
            val start = if (a > hi) a else hi
            if (constant(right, pr)) {
                parts.add(window(start, b))
            } else {
                var k = (start - hi).floorDiv(pr)
                while (hi + pr * k < b) {
                    val base = hi + pr * k
                    parts.add(clip(right, a - base, b - base).shift(base))
                    k++
                }
            }
        }
        if (a < lo && left.isNotEmpty()) {
            val end = if (b < lo) b else lo
            if (constant(left, pl)) {
                parts.add(window(a, end))
            } else {
                var k = (lo - end).floorDiv(pl)
                while (lo - pl * k > a) {
                    val base = lo - pl * (k + 1)
                    parts.add(clip(left, a - base, b - base).shift(base))
                    k++
                }
            }
        }
        if (parts.size == 1) return parts[0]
        return IntervalSet.of(parts.flatMap { it.intervals })
    }

    open fun isEmpty(): Boolean = core.isEmpty && left.isEmpty && right.isEmpty

    fun isNotEmpty(): Boolean = !isEmpty()

    fun isBounded(): Boolean = left.isEmpty && right.isEmpty

    fun bounded(): IntervalSet {
        require(isBounded()) { "unbounded region" }
        return core
    }

    fun measureOn(w: IntervalSet): Rational = (on(w.inf(), w.sup() + 1) and w).measure()

    // ------------------------------------------------------------ algebra
    internal fun combine(other: Region, op: RegionOp, lazy: Boolean = true): Region {
        // With a bounded region, only the other one's part on its span counts, read there.
        if (op == RegionOp.AND || op == RegionOp.SUB) {
            val pairs = if (op == RegionOp.AND) listOf(this to other, other to this) else listOf(this to other)
            for ((a, b) in pairs) {
                if (a !is Lazy && a.isBounded() && (if (b !is Lazy) b.left.isNotEmpty() || b.right.isNotEmpty() else true)) {
                    if (a.core.isEmpty) return EMPTY
                    val lo = a.core.intervals.first().lo
                    val hi = a.core.intervals.last().hi + Pos.EPS
                    val part = b.on(lo, hi)
                    val got = if (op == RegionOp.AND) a.core and part else a.core - part
                    return if (got.isNotEmpty()) of(got) else EMPTY
                }
            }
        }
        if (lazy && (this is Lazy || other is Lazy || longTogether(this, other))) return Lazy(op, this, other)
        if (left.isEmpty && right.isEmpty && other.left.isEmpty && other.right.isEmpty) { // both bounded: the cores only
            val c = apply(op, core, other.core)
            if (c.isEmpty) return EMPTY
            return Plain(c.intervals.first().lo, c.intervals.last().hi + Pos.EPS, c, Pos.ONE, NONE, Pos.ONE, NONE)
        }
        val lo = if (lo <= other.lo) lo else other.lo
        val hi = if (hi >= other.hi) hi else other.hi
        val pl = period(left, pl, other.left, other.pl)
        val pr = period(right, pr, other.right, other.pr)
        val c = apply(op, on(lo, hi), other.on(lo, hi))
        val r = apply(op, on(hi, hi + pr), other.on(hi, hi + pr)).shift(-hi)
        val l = apply(op, on(lo - pl, lo), other.on(lo - pl, lo)).shift(-(lo - pl))
        return make(lo, hi, c, pl, l, pr, r).compact()
    }

    open infix fun or(other: Region): Region {
        if (other is Lazy) return combine(other, RegionOp.OR)
        if (other.isEmpty()) return this
        if (isEmpty()) return other
        return combine(other, RegionOp.OR)
    }

    open infix fun and(other: Region): Region {
        if (isEmpty()) return this
        if (other is Lazy) return combine(other, RegionOp.AND)
        if (other.isEmpty()) return other
        return combine(other, RegionOp.AND)
    }

    open operator fun minus(other: Region): Region {
        if (isEmpty()) return this
        if (other is Lazy) return combine(other, RegionOp.SUB)
        if (other.isEmpty()) return this
        return combine(other, RegionOp.SUB)
    }

    open fun complement(): Region =
        make(lo, hi, window(lo, hi) - core, pl, window(Pos.ZERO, pl) - left, pr, window(Pos.ZERO, pr) - right)

    open fun shift(d: Pos): Region = make(lo + d, hi + d, core.shift(d), pl, left, pr, right)

    fun inf(): Pos = when {
        left.isNotEmpty() -> Pos.NEG_INF
        core.isNotEmpty() -> core.inf()
        right.isNotEmpty() -> hi + right.inf()
        else -> Pos.INF
    }

    fun sup(): Pos = when {
        right.isNotEmpty() -> Pos.INF
        core.isNotEmpty() -> core.sup()
        left.isNotEmpty() -> lo - pl + left.sup()
        else -> Pos.NEG_INF
    }

    /**
     * The left edge of each component of the region, as points; a component unbounded on the left has none. Being a
     * left edge is local, so the tails of the result repeat with the same periods.
     */
    open fun leftEdges(): Region {
        val a = lo - pl * 2
        val b = hi + pr * 2
        val starts = on(a, b).intervals.filter { it.lo > a }.map { it.lo }
        fun pts(l: Pos, h: Pos, d: Pos) = IntervalSet.of(starts.filter { l <= it && it < h }.map { Interval.point(it + d) })
        val h = hi + pr
        return make(lo, h, pts(lo, h, Pos.ZERO), pl, pts(lo - pl, lo, pl - lo), pr, pts(h, h + pr, -h)).compact()
    }

    /** {x + t : x in the region, t in iv}. */
    open fun dilate(iv: Interval): Region {
        if (isEmpty()) return this
        if (iv.lo == Pos.NEG_INF || iv.hi == Pos.INF) {
            var l = Pos.NEG_INF
            var h = Pos.INF
            if (iv.hi == Pos.INF && iv.lo != Pos.NEG_INF && inf() != Pos.NEG_INF) l = inf() + iv.lo
            if (iv.lo == Pos.NEG_INF && iv.hi != Pos.INF && sup() != Pos.INF) h = sup() + iv.hi
            return of(IntervalSet.of(Interval(l, h)))
        }
        val a = iv.lo
        val b = iv.hi
        val s = on(lo + a - b - pl - 1, hi + b - a + pr + 1)
        val d = IntervalSet.of(s.intervals.map { it + iv })
        val l = lo + a
        val h = hi + b
        return make(l, h, d, pl, d.shift(pl - l), pr, d.shift(-h)).compact()
    }

    /** The components of the region; a tail that repeats a pattern (infinitely many components) counts as one. */
    fun components(): List<Region> {
        val out = ArrayList<Region>()
        var rest: Region = this
        if (left.isNotEmpty() && !constant(left, pl)) {
            val t = this and of(IntervalSet.of(Interval.of(Pos.NEG_INF, lo, false, false)))
            out.add(t)
            rest -= t
        }
        if (right.isNotEmpty() && !constant(right, pr)) {
            val t = this and of(IntervalSet.of(Interval.of(hi, Pos.INF, true, false)))
            out.add(t)
            rest -= t
        }
        var s = rest.core
        if (rest.left.isNotEmpty()) s = s or IntervalSet.of(Interval.of(Pos.NEG_INF, rest.lo, false, false))
        if (rest.right.isNotEmpty()) s = s or IntervalSet.of(Interval.of(rest.hi, Pos.INF, true, false))
        return s.intervals.map { of(IntervalSet.of(it)) } + out
    }

    /** How many components of the region meet [other] ([INFINITELY_MANY] if infinitely many). */
    fun componentsMeeting(other: Region): Long {
        val x = this and other
        if (x.isEmpty()) return 0
        if ((x.left.isNotEmpty() && !constant(left, pl)) || (x.right.isNotEmpty() && !constant(right, pr))) return INFINITELY_MANY
        val m = maxOf(maxOf(pl.x, pr.x), maxOf(x.pl.x, x.pr.x)) + Rational.ONE
        val l = if (lo <= x.lo) lo else x.lo
        val h = if (hi >= x.hi) hi else x.hi
        val a = l - m
        val b = h + m
        val xs = x.on(a, b).intervals
        var n = 0L
        var j = 0
        for (iv in on(a, b).intervals) {
            while (j < xs.size && xs[j].hi < iv.lo) j++
            if (j < xs.size && xs[j].lo <= iv.hi) n++
        }
        return n
    }

    fun isSubsetOf(other: Region): Boolean = (this - other).isEmpty()

    override fun equals(other: Any?): Boolean {
        if (other !is Region) return false
        if (other is Lazy) return other == this
        return try {
            (this - other).isEmpty() && (other - this).isEmpty()
        } catch (e: IncompatiblePeriods) {
            false
        }
    }

    override fun hashCode(): Int = 0

    /** Every position of the line. */
    fun isEverything(): Boolean =
        left == window(Pos.ZERO, pl) && right == window(Pos.ZERO, pr) && core == window(lo, hi)

    /**
     * Shrinks the core where it is empty next to an empty tail and where its ends repeat the tails; each tail with
     * the shortest period it repeats with ([shortest]).
     */
    internal open fun compact(): Region {
        val (right, pr) = shortest(this.right, this.pr)
        val (left, pl) = shortest(this.left, this.pl)
        if (pr != this.pr || pl != this.pl) return make(lo, hi, core, pl, left, pr, right).compact()
        var lo = this.lo
        var hi = this.hi
        if (right.isEmpty) {
            val h = last(core, this.lo)
            hi = if (h > lo) h else lo
        } else {
            if (core.isNotEmpty() && right == window(Pos.ZERO, pr) && core.intervals.last().hi + Pos.EPS >= hi) {
                // a full tail after a full end of the core: at once, however long it is
                hi -= pr * (hi - max(core.intervals.last().lo, lo)).floorDiv(pr)
            }
            while (hi - pr >= lo && (core and window(hi - pr, hi)).shift(pr - hi) == right) hi -= pr
        }
        if (left.isEmpty) {
            lo = if (core.isNotEmpty()) (if (core.inf() < hi) core.inf() else hi) else hi
        } else {
            if (core.isNotEmpty() && left == window(Pos.ZERO, pl) && core.intervals.first().lo <= lo) {
                lo += pl * (min(core.intervals.first().hi + Pos.EPS, hi) - lo).floorDiv(pl)
            }
            while (lo + pl <= hi && (core and window(lo, lo + pl)).shift(-lo) == left) lo += pl
        }
        if (lo == this.lo && hi == this.hi) return this
        var c = core
        if ((right.isNotEmpty() && hi != this.hi) || (left.isNotEmpty() && lo != this.lo)) c = c and window(lo, hi)
        return make(lo, hi, c, pl, left, pr, right)
    }

    override fun toString(): String {
        var shown = core
        val leftTails = ArrayList<String>()
        val rightTails = ArrayList<String>()
        if (left == window(Pos.ZERO, pl)) {
            shown = shown or IntervalSet.of(Interval.of(Pos.NEG_INF, lo, false, false))
        } else if (left.isNotEmpty()) {
            leftTails.add("... ${group(left.shift(lo - pl))} repeating every $pl to the left")
        }
        if (right == window(Pos.ZERO, pr)) {
            shown = shown or IntervalSet.of(Interval.of(hi, Pos.INF, true, false))
        } else if (right.isNotEmpty()) {
            rightTails.add("${group(right.shift(hi))} repeating every $pr to the right ...")
        }
        val parts = leftTails + (if (shown.isNotEmpty()) listOf(shown.toString()) else emptyList()) + rightTails
        return if (parts.isEmpty()) "{}" else parts.joinToString(" U ")
    }

    companion object {
        /** A region from its parts, each cut to its window. */
        fun make(
            lo: Pos,
            hi: Pos,
            core: IntervalSet,
            pl: Pos = Pos.ONE,
            left: IntervalSet = NONE,
            pr: Pos = Pos.ONE,
            right: IntervalSet = NONE,
        ): Region {
            require(lo <= hi && pl.x.sign > 0 && pr.x.sign > 0) { "bad region bounds" }
            return Plain(lo, hi, core and window(lo, hi), pl, left and window(Pos.ZERO, pl), pr, right and window(Pos.ZERO, pr))
        }

        fun of(s: IntervalSet): Region {
            val finite = s.intervals.flatMap { listOf(it.lo, it.hi) }.filter { it.isFinite }
            val lo = finite.minOrNull() ?: Pos.ZERO
            val hi = (finite.maxOrNull() ?: Pos.ZERO) + 1
            val full = window(Pos.ZERO, Pos.ONE)
            val left = if (s.inf() == Pos.NEG_INF) full else NONE
            val right = if (s.sup() == Pos.INF) full else NONE
            return make(lo, hi, s, Pos.ONE, left, Pos.ONE, right)
        }

        fun of(iv: Interval): Region = of(IntervalSet.of(iv))

        fun point(x: Pos): Region = of(Interval.point(x))

        val EMPTY: Region = make(Pos.ZERO, Pos.ZERO, NONE)

        val UNIVERSE: Region = of(IntervalSet.UNIVERSE)
    }
}

/** A region as its seven parts. */
class Plain internal constructor(
    override val lo: Pos,
    override val hi: Pos,
    override val core: IntervalSet,
    override val pl: Pos,
    override val left: IntervalSet,
    override val pr: Pos,
    override val right: IntervalSet,
) : Region()

/** Whether combining a and b would write a repeating tail out for many periods between them. */
private fun farApart(a: Region, b: Region): Boolean {
    for ((r, o) in listOf(a to b, b to a)) {
        if (r.right.isNotEmpty() && !constant(r.right, r.pr) && o.hi > r.hi && (o.hi - r.hi).x / r.pr.x > Rational.of(LAZY_PIECES)) return true
        if (r.left.isNotEmpty() && !constant(r.left, r.pl) && o.lo < r.lo && (r.lo - o.lo).x / r.pl.x > Rational.of(LAZY_PIECES)) return true
    }
    return false
}

/** Whether combining a and b would give a tail repeating only after [LAZY_PERIOD], or write many periods out. */
private fun longTogether(a: Region, b: Region): Boolean {
    if ((a.left.isNotEmpty() || a.right.isNotEmpty() || b.left.isNotEmpty() || b.right.isNotEmpty()) && farApart(a, b)) return true
    for ((x, y) in listOf((a.right to a.pr) to (b.right to b.pr), (a.left to a.pl) to (b.left to b.pl))) {
        val (pa, ka) = x
        val (pb, kb) = y
        if (pa.isNotEmpty() && pb.isNotEmpty() && ka != kb && !constant(pa, ka) && !constant(pb, kb)) {
            try {
                if (plcm(ka, kb).x > Rational.of(LAZY_PERIOD)) return true
            } catch (e: IncompatiblePeriods) {
                return false
            }
        }
    }
    return false
}

/** A period for combining two tails: a constant tail goes with any period. */
private fun period(pa: IntervalSet, a: Pos, pb: IntervalSet, b: Pos): Pos {
    val ca = constant(pa, a)
    val cb = constant(pb, b)
    return when {
        ca && cb -> Pos.ONE
        ca -> b
        cb -> a
        a == b -> a
        else -> plcm(a, b)
    }
}

internal fun Region.eager(): Region = if (this is Lazy) full() else this

/**
 * A region kept as the operation that makes it, computed only where it is read. Reading a window ([on]) or a position
 * ([contains]) reads the operands there; what needs the whole of it computes it once, exactly as a [Region] is
 * computed, and keeps it.
 */
class Lazy private constructor(
    private val op: String,
    internal var a: Region?,
    internal var b: Region?,
    private val d: Pos?,
    private val iv: Interval?,
) : Region() {
    internal var computed: Region? = null
    internal val depth: Int = 1 + maxOf((a as? Lazy)?.depth ?: 0, (b as? Lazy)?.depth ?: 0)

    init {
        if (depth > LAZY_DEPTH) full()
    }

    internal constructor(op: RegionOp, a: Region, b: Region) : this(op.name, a, b, null, null)

    private constructor(op: String, a: Region) : this(op, a, null, null, null)

    /** The region itself, computed once. */
    fun full(): Region {
        computed?.let { return it }
        val x = a!!.eager()
        val out = when (op) {
            "OR", "AND", "SUB" -> {
                val y = b!!.eager()
                when {
                    op == "OR" && (x.isEmpty() || y.isEmpty()) -> if (x.isEmpty()) y else x
                    op != "OR" && (x.isEmpty() || y.isEmpty()) -> if (op == "SUB") x else EMPTY
                    else -> x.combine(y, RegionOp.valueOf(op), lazy = false)
                }
            }
            "comp" -> x.complement()
            "shift" -> x.shift(d!!)
            "dilate" -> x.dilate(iv!!)
            "edges" -> x.leftEdges()
            else -> error(op)
        }
        computed = out
        a = null
        b = null
        return out
    }

    override val lo: Pos get() = full().lo
    override val hi: Pos get() = full().hi
    override val core: IntervalSet get() = full().core
    override val pl: Pos get() = full().pl
    override val left: IntervalSet get() = full().left
    override val pr: Pos get() = full().pr
    override val right: IntervalSet get() = full().right

    override fun on(a: Pos, b: Pos): IntervalSet {
        computed?.let { return it.on(a, b) }
        val x0 = this.a!!
        return when (op) {
            "shift" -> x0.on(a - d!!, b - d).shift(d)
            "dilate" -> { // what reaches [a, b) comes from [a - hi - 1, b - lo + 1)
                val got = x0.on(a - iv!!.hi - 1, b - iv.lo + 1)
                IntervalSet.of(got.intervals.map { it + iv }) and window(a, b)
            }
            "edges" -> { // a start in [a, b) is the start of an interval read from a - 1
                val got = x0.on(a - 1, b)
                IntervalSet.of(got.intervals.filter { a <= it.lo && it.lo != Pos.NEG_INF }.map { Interval.point(it.lo) })
            }
            "comp" -> window(a, b) - x0.on(a, b)
            else -> apply(RegionOp.valueOf(op), x0.on(a, b), this.b!!.on(a, b))
        }
    }

    override fun contains(x: Pos): Boolean {
        computed?.let { return it.contains(x) }
        val x0 = a!!
        return when (op) {
            "shift" -> x0.contains(x - d!!)
            "dilate", "edges" -> on(x, x + Pos.EPS).intervals.any { it.lo <= x && x <= it.hi }
            "comp" -> !x0.contains(x)
            else -> {
                val ia = x0.contains(x)
                val ib = b!!.contains(x)
                when (op) {
                    "OR" -> ia || ib
                    "AND" -> ia && ib
                    else -> ia && !ib
                }
            }
        }
    }

    override fun or(other: Region): Region = Lazy(RegionOp.OR, this, other)

    override fun and(other: Region): Region = boundedPart(this, other) ?: Lazy(RegionOp.AND, this, other)

    override fun minus(other: Region): Region = Lazy(RegionOp.SUB, this, other)

    override fun complement(): Region = Lazy("comp", this)

    override fun shift(d: Pos): Region = Lazy("shift", this, null, d, null)

    override fun dilate(iv: Interval): Region {
        if (computed != null || iv.lo == Pos.NEG_INF || iv.hi == Pos.INF) return full().dilate(iv)
        return Lazy("dilate", this, null, null, iv)
    }

    override fun leftEdges(): Region = computed?.leftEdges() ?: Lazy("edges", this)

    override fun compact(): Region = full().compact()

    /** A position no point of it is before, from its operands (-inf if none is known without computing it). */
    fun lowerBound(): Pos {
        computed?.let { return if (!it.isEmpty()) it.inf() else Pos.INF }
        fun lb(r: Region?): Pos = when {
            r is Lazy -> r.lowerBound()
            r == null -> Pos.INF
            r.isEmpty() -> Pos.INF
            else -> r.inf()
        }
        return when (op) {
            "OR" -> min(lb(a), lb(b))
            "AND" -> max(lb(a), lb(b))
            "SUB" -> lb(a)
            "shift" -> lb(a) + d!!
            "dilate" -> lb(a) + iv!!.lo
            "edges" -> lb(a)
            else -> Pos.NEG_INF
        }
    }

    /** Empty or not, exactly: a piece found near where the operands start says no at once. */
    override fun isEmpty(): Boolean {
        computed?.let { return it.isEmpty() }
        for (h in hints()) if (on(h - PROBE, h + PROBE).intervals.isNotEmpty()) return false
        return full().isEmpty()
    }

    /** Positions near which the operands start: where to look for a piece first. */
    internal fun hints(): List<Pos> {
        val out = ArrayList<Pos>()
        val todo = ArrayList<Region>()
        todo.add(this)
        while (todo.isNotEmpty() && out.size < 6) {
            val r = todo.removeAt(todo.size - 1)
            if (r is Lazy && r.computed == null) {
                listOfNotNull(r.a, r.b).forEach { todo.add(it) }
            } else {
                val e = r.eager()
                if (!e.isEmpty()) out.add(if (e.lo != Pos.NEG_INF) e.lo else e.hi)
            }
        }
        return out.ifEmpty { listOf(Pos.ZERO) }
    }

    override fun equals(other: Any?): Boolean = other is Region && full() == other.eager()

    override fun hashCode(): Int = 0

    override fun toString(): String = full().toString()

    companion object {
        /** How wide each window read to find a piece is ([isEmpty]). */
        val PROBE: Pos = Pos.of(3 * 86400)
    }
}

/** lazy & other when other is a bounded, plain region: lazy read on other's span, exact. */
private fun boundedPart(lazy: Lazy, other: Region): Region? {
    if (other is Lazy || !other.isBounded()) return null
    if (other.core.isEmpty) return Region.EMPTY
    val lo = other.core.intervals.first().lo
    val hi = other.core.intervals.last().hi + Pos.EPS
    val got = other.core and lazy.on(lo, hi)
    return if (got.isNotEmpty()) Region.of(got) else Region.EMPTY
}
