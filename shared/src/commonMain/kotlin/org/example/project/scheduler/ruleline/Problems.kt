package org.example.project.scheduler.ruleline


/**
 * The optimization problems of the rules generator — a port of `C:\code\line_manager\scripts\problems.py` (its module
 * text defines both errors).
 *
 * The generator minimises the weighted sum of the problems' errors. Bound to a window W = (c, c + H] ([withWindow]),
 * a problem gives its error, proposals (an edit that brings the state closer, for a prediction of the mode rules and
 * a placement strategy) and extra edits worth trying.
 */
interface ProblemContext {
    val c: Pos
    val state: LineState
    val rules: RuleSet?
    val openEnd: Rational? get() = null
    val weightsFull: Any? get() = null
    val weightsBest: Any? get() = null
    val weightsUniform: Any? get() = null

    /** The integral of [kind] on [w] expected with what the mode rules will do there ([weights]: a prediction). */
    fun expected(s: LineState, kind: String, weights: Any?, w: IntervalSet): Rational
}

interface OptProblem {
    val name: String
    val window: Rational?
    fun withWindow(h: Rational): OptProblem
    fun hasTargets(): Boolean
    fun error(ctx: ProblemContext, s: LineState): Num

    /** What its quantities by a date miss, before anything else; null: it has none (`score.ScoreProblem`). */
    fun shortfall(ctx: ProblemContext, s: LineState): Num? = null

    /** How far shorter stretches from the chip are from its targets; null: no such measure. */
    fun stretchError(ctx: ProblemContext, s: LineState): Rational? = null

    /** Its presence ratio targets, if any. */
    val targets: Map<String, Rational> get() = emptyMap()

    /** Its minimum lengths, if any. */
    val lengths: Map<String, Rational> get() = emptyMap()
    fun proposals(ctx: ProblemContext, pred: String, strategy: String): List<Op>
    fun extra(ctx: ProblemContext): List<Pair<String, List<Op>>>
}

private val HUNDRED = Rational.of(100)

/** Where the element rules forbid [kind]: their literals "no kind" not in an "or", where their left sides are met. */
fun forbiddenBy(rules: RuleSet?, kind: String, vals: Vals): Region {
    if (rules == null) return Region.EMPTY
    var out = Region.EMPTY
    for (r in rules.element) for (it in r.rhs) {
        val o = it.options[0]
        if (!it.isOr && o.negated && o.kind == kind) out = out or target(o, lhsRegion(r.lhs, vals))
    }
    return out
}

fun forbidden(ctx: ProblemContext, kind: String, vals: Vals? = null): Region = forbiddenBy(ctx.rules, kind, vals ?: ctx.state.line.vals)

/** The presence ratio targets sum to 1 (none at all: no target). */
fun checkSum(targets: Map<String, Rational>) {
    val total = targets.values.fold(Rational.ZERO) { a, b -> a + b }
    if (targets.isNotEmpty() && total != Rational.ONE) {
        throw IllegalArgumentException("the presence ratio targets sum to $total, not 1: " + targets.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" })
    }
}

private fun windowSet(c: Pos, h: Rational): IntervalSet = IntervalSet.of(Interval.of(c, c + h, false, true))

/** Targets r_K in [0, 1] for the presence ratio (1/H) ∫_W s_K(x)/100 dx of each kind. */
class PresenceRatioProblem(targets: Map<String, Rational>, override val window: Rational? = null) : OptProblem {
    override val targets: Map<String, Rational> = LinkedHashMap(targets)

    init {
        require(this.targets.values.all { it >= Rational.ZERO && it <= Rational.ONE }) { "targets in [0, 1]" }
    }

    override val name = "presence ratio"

    override fun withWindow(h: Rational): PresenceRatioProblem {
        require(h.sign > 0) { "a window > 0" }
        return PresenceRatioProblem(targets, h)
    }

    override fun hasTargets() = targets.isNotEmpty()

    override fun error(ctx: ProblemContext, s: LineState): Num {
        val h = window!!
        val w = windowSet(ctx.c, h)
        return Num.Exact(targets.entries.fold(Rational.ZERO) { acc, (k, r) -> acc + (ctx.expected(s, k, ctx.weightsFull, w) / h - r).abs() })
    }

    /** How far shorter stretches from the chip are from the targets (the earlier first). */
    override fun stretchError(ctx: ProblemContext, s: LineState): Rational {
        val h = window!!
        var out = Rational.ZERO
        for (j in 1..STRETCH_SCALES) {
            var p2 = 1L
            repeat(j) { p2 *= 2 }
            val length = h / Rational.of(p2)
            val n = minOf(p2, STRETCHES.toLong()).toInt()
            for (i in 0 until n) {
                val w = windowSet(ctx.c + length * i, length)
                val weight = Rational.of((n - i).toLong(), (n.toLong() * (n + 1)) / 2)
                out += weight * targets.entries.fold(Rational.ZERO) { acc, (k, r) -> acc + (s.line.integral(k, w) / length - r).abs() }
            }
        }
        return out / STRETCH_SCALES
    }

    override fun proposals(ctx: ProblemContext, pred: String, strategy: String): List<Op> {
        val weights = when (pred) {
            "scenario" -> ctx.weightsBest
            "uniform" -> ctx.weightsUniform
            else -> null
        }
        val h = window!!
        val c = ctx.c
        val s = ctx.state
        val wSet = windowSet(c, h)
        val w = Region.of(wSet)
        val ops = ArrayList<Op>()
        val vals = LinkedHashMap(s.line.vals) // with what this proposal places: the next kinds avoid it too
        for ((kind, goal) in targets.entries.sortedBy { it.key }) {
            val need = goal * h - ctx.expected(s, kind, weights, wSet)
            if (need.sign > 0) {
                val free = (w - s.line.presence(kind) - forbidden(ctx, kind, vals)).on(c, c + h + 1) and wSet
                val place = placeIn(free, need, strategy, c, h)
                if (place.isNotEmpty()) {
                    ops.add(Op.put(kind, Region.of(place)))
                    val d = LinkedHashMap(vals[kind].orEmpty())
                    d[HUNDRED] = (d[HUNDRED] ?: Region.EMPTY) or Region.of(place)
                    vals[kind] = d
                }
            } else if (need.sign < 0) {
                val mine = (s.claims.owned(kind, Owner.GEN) and w).on(c, c + h + 1) and wSet
                val cut = mine.take(-need, fromRight = strategy != "right")
                if (cut.isNotEmpty()) ops.add(Op.take(kind, Region.of(cut)))
            }
        }
        return ops
    }

    override fun extra(ctx: ProblemContext): List<Pair<String, List<Op>>> = emptyList()

    companion object {
        const val STRETCH_SCALES = 8
        const val STRETCHES = 8
    }
}

private fun placeIn(free: IntervalSet, amount: Rational, strategy: String, c: Pos, h: Rational): IntervalSet {
    if (strategy == "left") return free.take(amount)
    if (strategy == "right") return free.take(amount, fromRight = true)
    val k = strategy.removePrefix("spread").toInt()
    var out = IntervalSet.EMPTY
    var carry = Rational.ZERO
    for (i in 0 until k) {
        val part = IntervalSet.of(Interval.of(c + h * Rational.of(i, k), c + h * Rational.of(i + 1, k), false, true)) and free
        val got = part.take(amount / k + carry)
        carry = amount / k + carry - got.measure()
        out = out or got
    }
    return out
}

/** A minimum length L_K > 0 for the intervals of each kind. */
class MinimumLengthProblem(lengths: Map<String, Rational>, override val window: Rational? = null) : OptProblem {
    override val lengths: Map<String, Rational> = LinkedHashMap(lengths)

    init {
        require(this.lengths.values.all { it.sign > 0 }) { "minimum lengths > 0" }
    }

    override val name = "minimum length"

    override fun withWindow(h: Rational): MinimumLengthProblem {
        require(h.sign > 0) { "a window > 0" }
        return MinimumLengthProblem(lengths, h)
    }

    override fun hasTargets() = lengths.isNotEmpty()

    /** An interval that reaches the stage's frontier (ctx.openEnd) can still grow: its shortfall does not count yet. */
    override fun error(ctx: ProblemContext, s: LineState): Num {
        val end = ctx.openEnd
        var out = Rational.ZERO
        for ((k, l) in lengths) {
            var sum = Rational.ZERO
            for ((iv, d) in shortfalls(s.line.presence(k), l, ctx.c, window!!)) {
                if (end == null || !(iv.lo <= Pos(end) && Pos(end) <= iv.hi + Pos.EPS)) sum += d
            }
            out += sum / l
        }
        return Num.Exact(out)
    }

    /** Each interval that falls short grows by its shortfall, on the free positions after the chip. */
    override fun proposals(ctx: ProblemContext, pred: String, strategy: String): List<Op> {
        val c = ctx.c
        val s = ctx.state
        val ops = ArrayList<Op>()
        for ((k, l) in lengths.entries.sortedBy { it.key }) {
            val pres = s.line.presence(k)
            val blocked = pres or s.claims.off(k) or s.claims.negative(k) or forbidden(ctx, k)
            var free = (Region.UNIVERSE - blocked).on(c, c + window!! + l * 3) and IntervalSet.after(c)
            var grow = IntervalSet.EMPTY
            for ((iv, d) in shortfalls(pres, l, c, window)) {
                if (d.sign == 0) continue
                val want = when (strategy) {
                    "left" -> d
                    "right" -> Rational.ZERO
                    else -> d / 2
                }
                val before = Interval.make(max(c, iv.lo - l * 2), iv.lo, false, false)
                val got = if (before != null && want.sign != 0) (free and IntervalSet.of(before)).take(want, fromRight = true) else IntervalSet.EMPTY
                val after = Interval.make(iv.hi, iv.hi + l * 3, false, true)
                val more = if (after != null && d > got.measure()) (free and IntervalSet.of(after)).take(d - got.measure()) else IntervalSet.EMPTY
                grow = grow or got or more
                free = free - got - more
            }
            if (grow.isNotEmpty()) ops.add(Op.put(k, Region.of(grow)))
        }
        return ops
    }

    /** Remove the generated intervals that fall short, after the chip. */
    override fun extra(ctx: ProblemContext): List<Pair<String, List<Op>>> {
        val s = ctx.state
        val c = ctx.c
        val ops = ArrayList<Op>()
        for ((k, l) in lengths.entries.sortedBy { it.key }) {
            val mine = s.claims.owned(k, Owner.GEN)
            val drop = shortfalls(s.line.presence(k), l, ctx.c, window!!)
                .filter { (iv, d) -> d.sign != 0 && iv.lo > c && Region.of(IntervalSet.of(iv)).isSubsetOf(mine) }.map { it.first }
            if (drop.isNotEmpty()) ops.add(Op.take(k, Region.of(IntervalSet.of(drop))))
        }
        return if (ops.isNotEmpty()) listOf("minimum length: remove the short generated intervals" to ops) else emptyList()
    }
}

/** (interval, shortfall) for the intervals of [pres] that meet (c, c + H]. */
fun shortfalls(pres: Region, l: Rational, c: Pos, h: Rational): List<Pair<Interval, Rational>> {
    val ivs = pres.on(c - l * 2, c + h + l * 2).intervals // an interval cut there is longer than 2L
    val w = windowSet(c, h)
    val out = ArrayList<Pair<Interval, Rational>>()
    for ((i, a) in ivs.withIndex()) {
        if ((IntervalSet.of(a) and w).isEmpty) continue
        var support = Rational.ZERO
        for ((j, b) in ivs.withIndex()) {
            val gap = if (i == j) Rational.ZERO else maxOf(Rational.ZERO, if (j > i) b.lo.x - a.hi.x else a.lo.x - b.hi.x)
            if (gap < l) support += b.length * (Rational.ONE - gap / l)
        }
        out.add(a to maxOf(Rational.ZERO, l - support))
    }
    return out
}
