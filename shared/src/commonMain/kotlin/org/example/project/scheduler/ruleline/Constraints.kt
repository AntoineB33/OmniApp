package org.example.project.scheduler.ruleline


/**
 * Constraints the rules generator satisfies — a port of `C:\code\line_manager\scripts\constraints.py`.
 *
 * A constraint gives, for the line as it is and the chip at c, where its elements must be after the chip ([targets])
 * and the edits that make it hold ([ops], after c only: nothing left of the chip changes). [MinimumGap] / [MaximumGap]:
 * at least / at most a gap between the pieces of a kind; [AsEarlyAsPossible]: a kind placed as early as the rest
 * allows; [KeepOn]: an edit written from the chip; [ChipPush]: a script's push of a kind by the chip; [Breaks],
 * [ScreenBreaks]: OmniApp's screen breaks. Nothing in the first four is about breaks.
 */
interface Constraint {
    /** The kind its group is computed by ([constraintOps]); null: a group of its own. */
    val groupKind: String? get() = null

    /** Its edits are written in the script. */
    val explicit: Boolean get() = false

    /** How far the generator's window must reach to see two of its pieces; null: nothing. */
    val cycle: Rational? get() = null

    fun kinds(): Set<String>

    /** Once made, it holds as the chip goes: no new run is needed for it. */
    fun holdsByItself(): Boolean

    fun targets(state: LineState, c: Pos): Map<String, Region>

    /** The edits that make it hold after c ([until]: only that far, where it can). */
    fun ops(state: LineState, c: Pos, until: Pos? = null): List<Op>

    fun bind(rules: RuleSet) {}

    fun bindAll(constraints: List<Constraint>) {}
}

private val HUNDRED = Rational.of(100)

/** The last few (rules, state, c, constraints, until, ops): a run asks them several times. */
private val opsMemo = ArrayList<Pair<List<Any?>, List<Op>>>()

/** The edits that make these constraints hold after the chip at c; the same answer for the same objects is kept. */
fun constraintOps(rules: RuleSet, state: LineState, c: Pos, constraints: List<Constraint>, until: Pos? = null): List<Op> {
    for ((k, ops) in opsMemo) {
        val r = k[0]
        val s = k[1]
        @Suppress("UNCHECKED_CAST")
        val cs = k[3] as List<Constraint>
        if (r === rules && s === state && k[2] == c && k[4] == until && cs.size == constraints.size && cs.indices.all { cs[it] === constraints[it] }) return ops.toList()
    }
    val ops = constraintOpsOf(rules, state, c, constraints, until)
    opsMemo.add(0, listOf(rules, state, c, constraints.toList(), until) to ops)
    while (opsMemo.size > 4) opsMemo.removeAt(opsMemo.size - 1)
    return ops
}

/** k's edits, up to [until] if given (cut there). */
private fun opsOf(k: Constraint, state: LineState, c: Pos, until: Pos?): List<Op> {
    if (until == null) return k.ops(state, c)
    val got = k.ops(state, c, until)
    val w = Region.of(Interval.of(c, until, false, true))
    return got.mapNotNull { o -> (o.region and w).takeIf { it.isNotEmpty() }?.let { Op(o.action, o.kind, it, o.value) } }
}

/**
 * The edits that make these constraints hold after the chip at c. The constraints of one kind are computed together
 * on the same line; each kind on the line the kinds before it made, in the order they first appear.
 */
private fun constraintOpsOf(rules: RuleSet, state: LineState, c: Pos, constraints: List<Constraint>, until: Pos?): List<Op> {
    val groups = LinkedHashMap<Any, MutableList<Constraint>>()
    for (k in constraints) groups.getOrPut(k.groupKind ?: Identity(k)) { ArrayList() }.add(k)
    val ops = ArrayList<Op>()
    var s = state
    val gs = groups.values.toList()
    for ((i, g) in gs.withIndex()) {
        val mine = g.filter { it !is MaximumGap }.flatMap { opsOf(it, s, c, until) }.toMutableList()
        val late = g.filterIsInstance<MaximumGap>()
        if (late.isNotEmpty()) { // (an obligation checked on what the others of its kind put)
            var t = s
            if (mine.isNotEmpty()) {
                try {
                    t = applyEdit(rules, s, mine, Owner.GEN)
                } catch (e: Refused) {
                }
            }
            for (k in late) mine.addAll(opsOf(k, t, c, until))
        }
        if (mine.isEmpty()) continue
        ops.addAll(mine)
        if (i < gs.size - 1) {
            try {
                s = applyEdit(rules, s, mine, Owner.GEN)
            } catch (e: Refused) { // (the edits together are refused where they are applied)
            }
        }
    }
    return ops
}

/** A key by identity (the reference's `id(k)`). */
private class Identity(val of: Any) {
    override fun equals(other: Any?) = other is Identity && other.of === of
    override fun hashCode() = of.hashCode()
}

/** (c, inf). */
internal fun afterR(c: Pos): Region = Region.of(Interval.of(c, Pos.INF, false, false))

/** (-inf, c]. */
internal fun upToR(c: Pos): Region = Region.of(Interval.of(Pos.NEG_INF, c, false, true))

/** The line keeps [op] (an element put on a region written from the chip). */
class KeepOn(val op: Op) : Constraint {
    override fun kinds() = setOf(op.kind)

    override fun holdsByItself() = !op.region.isBounded()

    override fun targets(state: LineState, c: Pos): Map<String, Region> =
        if (op.action == "put") mapOf(op.kind to (op.region.shift(c) and afterR(c))) else emptyMap()

    override fun ops(state: LineState, c: Pos, until: Pos?): List<Op> {
        val o = Op(op.action, op.kind, op.region.shift(c), op.value)
        if (o.action == "put" && o.value == HUNDRED && (o.region - state.line.presence(o.kind)).isEmpty()) return emptyList()
        return listOf(o)
    }

    override fun toString() = "keep " + op.toString().replaceFirst(" on ", " on chip + ")
}

/**
 * What a script's event "if the chip is on K (and mode is m), then from the chip, take K off (0, inf), put K on R"
 * does, as the mode rules keep it: K after the chip is R from the chip, at every position the chip goes through in
 * mode m while it is on K (or K is less than [within] after it). Not a constraint of the generator: the script's own.
 */
class ChipPush(
    val kind: String,
    val region: Region,
    val mode: String?,
    val within: Rational,
    on: String? = null,
    val lasted: Rational? = null,
    val unless: String? = null,
) : Constraint {
    val onKind: String = on ?: kind

    override val groupKind: String get() = kind

    override fun kinds(): Set<String> = setOf(kind, onKind) + listOfNotNull(unless)

    /** Where [on] can change without an end of the line there ([lasted] after the start of each interval of its kind). */
    fun flips(line: Line, a: Pos, b: Pos): Set<Pos> {
        val l = lasted ?: return emptySet()
        return line.presence(onKind).on(a - l, b).intervals.filter { it.lo.isFinite }.map { it.lo + l }.toSet()
    }

    override fun holdsByItself() = true

    /** The chip at c is on K, or K is less than [within] after it, on the line as the chip gets to c. */
    fun on(state: LineState, c: Pos): Boolean {
        if (unless != null && (state.line.presence(unless).contains(c) || state.line.presence(unless).contains(c + Pos.EPS))) return false
        if (lasted != null) return Region.of(Interval.of(c - lasted, c, true, false)).isSubsetOf(state.line.presence(onKind))
        val pres = state.line.presence(kind)
        if (pres.contains(c)) return true
        return within.sign != 0 && (pres and Region.of(Interval.of(c, c + within, false, true))).isNotEmpty()
    }

    override fun targets(state: LineState, c: Pos): Map<String, Region> = if (on(state, c)) fromChip(c) else emptyMap()

    /** What the chip leaves behind it as it pushes: K at each position it went through if R starts right after it. */
    fun behind(): Boolean = region.contains(Pos.EPS)

    /** K after the chip at c, pushed: R from c. */
    fun fromChip(c: Pos): Map<String, Region> = mapOf(kind to (region.shift(c) and afterR(c)))

    override fun ops(state: LineState, c: Pos, until: Pos?): List<Op> = emptyList() // (it acts as the chip goes)

    override fun toString(): String {
        var w = if (lasted != null) " when on $onKind for ${fmtNum(lasted)}" else ""
        if (unless != null) w += " and not on $unless"
        return "push $kind on chip + $region$w" + (if (mode != null) " in mode $mode" else "")
    }
}

/** The push an event of a script is, if it is one; null otherwise (the event then fires at each instant). */
fun pushOf(event: Event): ChipPush? {
    val trig = event.trigger as? ChipOn ?: return null
    if (event.actions.size != 1) return null
    val a = event.actions[0] as? EditAction ?: return null
    if (!a.fromChip) return null
    val kind = if (a.ops.isNotEmpty()) a.ops[0].kind else trig.kind
    if (a.ops.any { it.kind != kind || (it.action != "take" && it.action != "put") }) return null
    if (kind != trig.kind && trig.lasted == null) return null // (another kind than K at the chip: only as long as it has lasted)
    var taken = Region.EMPTY
    var put = Region.EMPTY
    for (o in a.ops) {
        if (o.action == "take") {
            if (put.isNotEmpty()) return null // (a take after a put)
            taken = taken or o.region
        } else if (o.value != HUNDRED) {
            return null
        } else {
            put = put or o.region
        }
    }
    if (put.isEmpty() || (afterR(Pos.ZERO) - taken).isNotEmpty() || (put - afterR(Pos.ZERO)).isNotEmpty()) return null
    val lo = put.inf()
    var within = trig.within ?: maxOf(Rational.ZERO, if (lo.isFinite) lo.x else Rational.ZERO)
    if (trig.lasted != null) within = Rational.ZERO // (read behind the chip: nothing ahead)
    return ChipPush(kind, put, trig.mode, within, on = trig.kind, lasted = trig.lasted, unless = trig.unless)
}

/** Those of these pushes that act in this mode. */
fun pushesIn(pushes: List<ChipPush>, mode: String): List<ChipPush> = pushes.filter { it.mode == null || it.mode == mode }

/**
 * No interval of [kind] within [gap] after the end of an interval of [after] ([kind] by default) at least [atLeast]
 * long. With [pull]: where the gap would end inside an interval of [pull] someone put, the gap ends at its start.
 */
class MinimumGap(val kind: String, val gap: Rational, after: String? = null, val atLeast: Rational = Rational.ZERO, val pull: String? = null) : Constraint {
    val after: String = after ?: kind

    /** True: only where an AsEarlyAsPossible waits, not a rule ([expand]). */
    var soft = false

    /** The MaximumGap this one was made for ([expand]); null: the user's. */
    var placedFor: Constraint? = null

    init {
        require(gap.sign > 0 && atLeast.sign >= 0) { "a gap > 0 and a length >= 0" }
    }

    override val groupKind: String get() = kind
    override val explicit get() = true
    override val cycle: Rational get() = gap + atLeast

    override fun kinds() = setOf(kind, after)

    override fun holdsByItself() = true

    override fun targets(state: LineState, c: Pos): Map<String, Region> = emptyMap()

    /** The intervals of [presence] at least [atLeast] long. */
    fun long(presence: Region): Region {
        if (atLeast.sign == 0 || presence.isEmpty()) return presence
        val l = Pos(atLeast)
        val core = presence.complement().dilate(Interval.of(Pos.ZERO, l, true, false)).complement()
        return core.dilate(Interval.of(-l, Pos.ZERO, false, true)) and presence
    }

    /** Where [kind] cannot be after these intervals of [after]: (end, end + gap] of each long one, short of the pulls. */
    fun apart(presence: Region, pulls: Region? = null): Region {
        val lg = long(presence)
        if (lg.isEmpty()) return Region.EMPTY
        val ends = lg.complement().leftEdges()
        val window = ends.dilate(Interval.of(Pos.ZERO, Pos(gap), true, false))
        return if (pulls != null && pulls.isNotEmpty()) window - pulled(window, pulls, gap) else window
    }

    /** The intervals of [pull] that pull a gap's end to their start: behind the chip the line's, after it the claims'. */
    fun pullsOf(state: LineState, c: Pos): Region? {
        val p = pull ?: return null
        var put = Region.EMPTY
        for (r in state.claims.pos[p].orEmpty().values) put = put or r
        return (state.line.presence(p) and upToR(c)) or (put and afterR(c))
    }

    override fun ops(state: LineState, c: Pos, until: Pos?): List<Op> {
        if (soft) return emptyList() // (a wait, not a rule)
        val pres = state.line.presence(kind)
        val aft = afterR(c)
        val pulls = pullsOf(state, c)
        val bad = pres and apart(state.line.presence(after), pulls) and aft
        if (bad.isEmpty()) return emptyList()
        val mine = state.claims.owned(kind, Owner.GEN)
        var off = bad and mine
        val theirs = bad - mine
        if (theirs.isNotEmpty()) { // what the generator put before them, too close: off
            off = off or (mine and theirs.dilate(Interval.of(-Pos(gap + atLeast), Pos.ZERO, true, true)) and aft)
        }
        val rest = pres - off
        val source = state.line.presence(after) - (if (after == kind) off else Region.EMPTY)
        off = off or (rest and apart(source, pulls) and aft)
        return if (off.isNotEmpty()) listOf(Op.take(kind, off)) else emptyList()
    }

    override fun toString(): String {
        val p = if (pull != null) ", a gap ending inside $pull ending at its start" else ""
        if (after == kind && atLeast.sign == 0) return "keep $kind at least ${fmtNum(gap)} apart$p"
        return "keep $kind at least ${fmtNum(gap)} after ${fmtNum(atLeast)} or more of $after$p"
    }
}

/** The end of each component of [window] that [into] pulls back to its start. */
fun pulled(window: Region, into: Region?, reach: Rational): Region {
    if (into == null || window.isEmpty() || into.isEmpty()) return Region.EMPTY
    val u = window or into
    val tails = u.left.isNotEmpty() || u.right.isNotEmpty()
    val lo = u.lo
    val hi = u.hi
    val pl = if (tails) u.pl else Pos.ONE
    val pr = if (tails) u.pr else Pos.ONE
    val ws = window.on(lo - pl * 3 - 1, hi + pr * 3 + 1).intervals
    val m = (listOf(Pos(reach)) + ws.filter { it.lo != Pos.NEG_INF && it.hi != Pos.INF }.map { it.hi - it.lo }).max() + 1
    val lo2 = lo - m
    val hi2 = hi + m
    val a = lo2 - pl - m - 1
    val b = hi2 + pr + m + 1
    val ins = into.on(a, b)
    val out = ArrayList<Interval>()
    for (w in window.on(a, b).intervals) {
        if (w.lo <= a || w.hi >= b || !w.hiClosed || !ins.contains(w.hi)) continue
        val comp = ins.intervals.first { it.contains(w.hi) }
        out.add(Interval(if (comp.lo > w.lo) comp.lo else w.lo, w.hi))
    }
    val s = IntervalSet.of(out)
    if (!tails) return if (s.isNotEmpty()) Region.of(s) else Region.EMPTY
    return Region.make(lo2, hi2, s, pl, s.shift(-(lo2 - pl)), pr, s.shift(-hi2)).compact()
}

/** The first interval of [no] or [dyn] meeting [t, t + L - ε], or null: each read near t. */
private fun firstBlock(no: Region, dyn: Region, t: Pos, l: Rational): Interval? {
    var best: Interval? = null
    for (r in listOf(no, dyn)) {
        val ivs = r.on(t, t + l).intervals
        if (ivs.isNotEmpty() && (best == null || ivs[0].lo < best.lo)) best = ivs[0]
    }
    return best
}

/** The bars of the pieces placed that are still ahead of t. */
private fun aheadOf(dyn: Region, t: Pos): Region = if (dyn.isNotEmpty()) dyn and Region.of(Interval.of(t, Pos.INF, true, false)) else dyn

/**
 * (c + lo + nP, c + hi + nP] for every n >= 0, 0 <= lo < hi <= P: a piece repeating forever (`ratio_plan.cycle`), as
 * a [Built] region that keeps the call that made it.
 */
fun cycleRegion(c: Rational, p: Rational, lo: Rational, hi: Rational): Region {
    val pattern = if (hi < p) {
        IntervalSet.of(Interval.of(Pos(lo), Pos(hi), false, true))
    } else { // the end of each piece is the start of the next period
        IntervalSet.of(Interval.point(Pos.ZERO)) or IntervalSet.of(Interval.of(Pos(lo), Pos(p), false, false))
    }
    val r = Region.make(Pos(c), Pos(c), IntervalSet.EMPTY, Pos.ONE, IntervalSet.EMPTY, Pos(p), pattern) and Region.of(Interval.of(Pos(c), Pos.INF, false, false))
    return Built(r, "cycle(${pyNum(c)}, ${pyNum(p)}, ${pyNum(lo)}, ${pyNum(hi)})")
}

/** The reference's `script_python.py` writing of a rational in a call: `F(3)`, `F(1, 2)`. */
internal fun pyNum(x: Rational): String = if (x.isInteger) "F(${x.num})" else "F(${x.num}, ${x.den})"

/**
 * A region and the call that built it ([src]): the generated script's text gives the call, not its intervals
 * (`ratio_plan.Built`). An operation that changes nothing gives it back; another one gives the call with the operation.
 */
class Built(private val r: Region, val src: Any) : Region() {
    override val lo get() = r.lo
    override val hi get() = r.hi
    override val core get() = r.core
    override val pl get() = r.pl
    override val left get() = r.left
    override val pr get() = r.pr
    override val right get() = r.right

    override fun and(other: Region): Region {
        if (other is Lazy || longTogether(this, other)) return super.and(other)
        if (isSubsetOf(other)) return this
        return Built(super.and(other), Triple("&", this, other))
    }

    override fun minus(other: Region): Region {
        if (other is Lazy || longTogether(this, other)) return super.minus(other)
        if (super.and(other).isEmpty()) return this
        return Built(super.minus(other), Triple("-", this, other))
    }

    override fun or(other: Region): Region {
        if (other is Lazy || longTogether(this, other)) return super.or(other)
        return Built(super.or(other), Triple("|", this, other))
    }
}

/**
 * [kind] as early as the rules and the other constraints allow. With [length]: each piece that long, the first as
 * early as allowed after the chip, then the next; a piece the chip is in, begun before it, stays whole. Without
 * [length]: an order of the generator's own turns.
 */
class AsEarlyAsPossible(val kind: String, val length: Rational? = null) : Constraint {
    var rules: RuleSet? = null
    var siblings: List<Constraint> = emptyList()
    var placedFor: Constraint? = null

    init {
        require(length == null || length.sign > 0) { "a length > 0" }
    }

    override val groupKind: String get() = kind
    override val explicit get() = true
    override val cycle: Rational get() = (length ?: Rational.ZERO) + (gaps().maxOfOrNull { it.gap + it.atLeast } ?: Rational.ZERO)

    override fun bind(rules: RuleSet) {
        this.rules = rules
    }

    override fun bindAll(constraints: List<Constraint>) {
        siblings = constraints.filter { it !== this }
    }

    override fun kinds() = setOf(kind) + gaps().map { it.after }

    override fun holdsByItself() = true

    fun gaps(): List<MinimumGap> = siblings.filterIsInstance<MinimumGap>().filter { it.kind == kind }

    /** Whether its own pieces are intervals of the gap's [MinimumGap.after]: that kind, or a rule "kind → after". */
    fun counts(gap: MinimumGap): Boolean {
        if (gap.after == kind) return true
        for (r in rules?.element.orEmpty()) {
            val lhs = r.lhs
            if (r.lhs.kinds() == listOf(kind) && lhs is Atom && lhs.op == "present") {
                for (it in r.rhs) {
                    val o = it.options[0]
                    if (!it.isOr && !o.negated && o.kind == gap.after && o.where.isPoint && o.where.lo == Pos.ZERO) return true
                }
            }
        }
        return false
    }

    /** What stays of [k] whatever this one's pieces after the chip at c. */
    fun fixedOf(state: LineState, c: Pos, k: String): Region {
        var out = state.line.presence(k) and upToR(c)
        for (r in state.claims.pos[k].orEmpty().values) out = out or (r and afterR(c))
        for (r in rules?.element.orEmpty()) {
            val ks = r.lhs.kinds()
            val lhs = r.lhs
            if (ks.size == 1 && ks[0] != kind && lhs is Atom && lhs.op == "present" &&
                r.rhs.any { !it.isOr && it.options[0].kind == k && !it.options[0].negated }
            ) {
                out = out or (state.line.presence(ks[0]) and afterR(c))
            }
        }
        return out
    }

    /** (where what stays keeps its pieces away after the chip at c; the pulls of its gaps). */
    fun obstacles(state: LineState, c: Pos, also: Region? = null, soft: Boolean? = null): Pair<Region, List<Pair<MinimumGap, Region?>>> {
        val aft = afterR(c)
        var mine = state.claims.owned(kind, Owner.GEN) and aft
        if (also != null && also.isNotEmpty()) mine = mine or also
        var no = Region.EMPTY
        if (rules != null && soft != true) {
            val vals = state.line.vals.mapValues { (k, d) -> if (k == kind) d.mapValues { it.value - mine } else d }
            no = forbiddenBy(rules, kind, vals)
        }
        val gs = gaps().filter { soft == null || it.soft == soft }
        val pulls = gs.map { it to it.pullsOf(state, c) }
        for ((g, p) in pulls) {
            val src = if (g.after == kind) state.line.presence(g.after) - mine else fixedOf(state, c, g.after)
            no = no or g.apart(src, p)
        }
        return (no and aft) to pulls
    }

    /** Where its pieces are after the chip at c. [forced]: its first piece, where the chip pushes it. */
    fun place(state: LineState, c: Pos, forced: Pair<Pos, Pos>? = null, also: Region? = null, until: Pos? = null): Region {
        val l = length!!
        val aft = afterR(c)
        val (no0, pulls0) = obstacles(state, c, also)
        val counted = gaps().filter { counts(it) && l >= it.atLeast }
        val no = no0.eager()
        val fixed = counted.map { fixedOf(state, c, it.after).eager() }
        val pullOf = pulls0.associate { Identity(it.first) to it.second?.eager() }
        val waits = gaps().any { it.soft }
        var hardNo = Region.EMPTY
        var hardDyn = Region.EMPTY
        var softNo = Region.EMPTY
        if (waits) {
            hardNo = obstacles(state, c, also, soft = false).first
            softNo = obstacles(state, c, also, soft = true).first
        }

        fun stretchEnd(b: Pos, f: Region): Pos {
            var e = b
            repeat(64) {
                val nxt = f.on(e, e + 2).intervals.filter { it.lo <= e + Pos.EPS && e + Pos.EPS <= it.hi }
                if (nxt.isEmpty() || nxt[0].hi <= e) return e
                e = nxt[0].hi
                if (e == Pos.INF) return e
            }
            return e
        }

        fun bar(b: Pos, which: Boolean? = null): Region {
            var out = Region.EMPTY
            for ((gi, g) in counted.withIndex()) {
                if (which != null && g.soft != which) continue
                val e = stretchEnd(b, fixed[gi])
                if (e == Pos.INF) continue
                val w = Region.of(IntervalSet.sorted(listOf(Interval(e + Pos.EPS, e + g.gap))))
                val p = pullOf[Identity(g)]
                out = out or (if (p != null && p.isNotEmpty()) w - pulled(w, p, g.gap) else w)
            }
            return out
        }
        val placed = ArrayList<Pair<Pos, Pos>>()
        if (forced == null) {
            for (iv in state.line.presence(kind).on(c - l - 1, c + l + 1).intervals) {
                if (iv.lo <= c && c < iv.hi + Pos.EPS && iv.lo + l > c) placed.add(iv.lo to (iv.lo + l - Pos.EPS))
            }
        } else {
            placed.add(forced)
        }
        var stop: Region? = if (no.isBounded()) Region.EMPTY else null
        var end = if (stop != null) (if (no.isNotEmpty()) no.bounded().sup() else c) else no.hi
        end = max(end, c)
        for (p in pullOf.values) {
            if (p != null && p.isNotEmpty()) {
                val bd = p.bounded()
                if (bd.isNotEmpty()) end = max(end, bd.sup())
            }
        }
        if (until != null) {
            end = until
            stop = Region.EMPTY
        }
        var t = if (placed.isNotEmpty()) placed.last().second + Pos.EPS else c + Pos.EPS
        var dyn = Region.EMPTY
        for ((_, b) in placed) {
            dyn = dyn or bar(b)
            if (waits) hardDyn = hardDyn or bar(b, false)
        }

        fun sooner(t: Pos, dyn: Region): Pos? {
            val w = softNo or (dyn - hardDyn)
            if (!w.contains(t)) return null
            val d = MaximumGap.endOf(w, t) + Pos.EPS
            if (d == Pos.INF) return null
            val rule = hardNo or hardDyn
            if ((rule and Region.of(IntervalSet.sorted(listOf(Interval(d, d + l - Pos.EPS))))).isEmpty()) return null
            return MaximumGap.latest(rule, t, d, l)
        }

        fun put(t: Pos, dyn: Region): Region {
            placed.add(t to (t + l - Pos.EPS))
            if (waits) hardDyn = hardDyn or bar(t + l - Pos.EPS, false)
            return dyn or bar(t + l - Pos.EPS)
        }
        while (t <= end) {
            checkpoint()
            val first = firstBlock(no, dyn, t, l)
            if (first == null) {
                dyn = aheadOf(put(t, dyn), t)
                t += l
            } else {
                val p = if (waits) sooner(t, dyn) else null
                if (p != null) {
                    dyn = aheadOf(put(p, dyn), p)
                    t = p + l
                    continue
                }
                t = first.hi + Pos.EPS
            }
        }
        if (until != null) return if (placed.isNotEmpty()) Region.of(IntervalSet.of(placed.map { Interval(it.first, it.second) })) and aft else Region.EMPTY
        if (stop == null && no.right.isNotEmpty()) {
            return repeating(no, dyn, ::bar, placed, t, l, counted, if (waits) (::sooner to ::put) else null) and aft
        }
        while (true) {
            val fb = firstBlock(Region.EMPTY, dyn, t, l) ?: break
            t = fb.hi + Pos.EPS
        }
        val spacing = l + (counted.maxOfOrNull { it.gap } ?: Rational.ZERO)
        val starts = placed.map { it.first } + t
        if (spacing != l && starts.zipWithNext().all { (a, b) -> b - a == Pos(spacing) }) {
            val s0 = starts[0] - Pos.EPS
            if (s0.k == 0L) return cycleRegion(s0.x, spacing, Rational.ZERO, l) and aft
        }
        val core = IntervalSet.of(placed.map { Interval(it.first, it.second) })
        val first = (placed.map { it.first } + t).min()
        if (spacing == l) return (Region.of(core) or Region.of(Interval.of(t, Pos.INF, true, false))) and aft
        return Region.make(first, t, core, Pos.ONE, IntervalSet.EMPTY, Pos(spacing), IntervalSet.sorted(listOf(Interval(Pos.ZERO, Pos(l) - Pos.EPS)))) and aft
    }

    /** The pieces from t on, [no] repeating every no.pr from no.hi: swept until the state of the sweep comes back. */
    private fun repeating(
        no: Region, dyn0: Region, bar: (Pos, Boolean?) -> Region, placed: MutableList<Pair<Pos, Pos>>, t0: Pos, l: Rational,
        counted: List<MinimumGap>, waits: Pair<(Pos, Region) -> Pos?, (Pos, Region) -> Region>?,
    ): Region {
        val p = no.pr
        val reach = Pos(l + (counted.maxOfOrNull { it.gap } ?: Rational.ZERO) + Rational.ONE)
        val seen = HashMap<Pair<Pos, List<Pair<Pos, Pos>>>, Pair<Int, Pos>>()
        var t = t0
        var dyn = dyn0
        repeat(MAX_STEPS) {
            checkpoint()
            if (t >= no.hi) {
                val ahead = dyn.on(t, t + reach)
                val key = (t - no.hi).mod(p) to ahead.intervals.map { (it.lo - t) to (it.hi - t) }
                val before0 = seen[key]
                if (before0 != null) {
                    val (i0, tt0) = before0
                    val per = t - tt0
                    val before = IntervalSet.of(placed.subList(0, i0).map { Interval(it.first, it.second) })
                    val pattern = IntervalSet.of(placed.subList(i0, placed.size).map { Interval(it.first - tt0, it.second - tt0) })
                    val first = (placed.map { it.first } + tt0).min()
                    return Region.make(first, tt0, before, Pos.ONE, IntervalSet.EMPTY, per, pattern)
                }
                seen[key] = placed.size to t
            }
            val first = firstBlock(no, dyn, t, l)
            if (waits != null) {
                val (sooner, put) = waits
                val q = if (first == null) t else sooner(t, dyn)
                if (q != null) {
                    dyn = aheadOf(put(q, dyn), q)
                    t = q + l
                    return@repeat
                }
                t = first!!.hi + Pos.EPS
                return@repeat
            }
            if (first == null) {
                placed.add(t to (t + l - Pos.EPS))
                dyn = aheadOf(dyn or bar(t + l - Pos.EPS, null), t)
                t += l
            } else {
                t = first.hi + Pos.EPS
            }
        }
        return if (placed.isNotEmpty()) Region.of(IntervalSet.of(placed.map { Interval(it.first, it.second) })) else Region.EMPTY
    }

    override fun targets(state: LineState, c: Pos): Map<String, Region> = emptyMap()

    /** From the start of one piece to the start of the next, nothing else in the way. */
    fun spacing(): Rational = length!! + (gaps().filter { counts(it) && length >= it.atLeast }.maxOfOrNull { it.gap } ?: Rational.ZERO)

    /** The gaps its own pieces open and that it waits for. */
    fun countedGaps(): List<MinimumGap> = gaps().filter { counts(it) && length != null && length >= it.atLeast }

    /** The modes whose rules put [k] at the chip, not as one choice of several. */
    fun putsAtChip(rules: RuleSet, k: String): List<String> {
        val out = HashSet<String>()
        for (r in rules.modeRules) for (it in r.rhs) {
            if (it.isOr) continue
            val o = it.options[0]
            if (!o.negated && o.kind == k && o.where.isPoint && o.where.lo == Pos.ZERO) out.add(r.mode!!)
        }
        return out.sorted()
    }

    /** Its kind and those it comes with at once. */
    private fun with(rules: RuleSet): Set<String> = setOf(kind) + rules.order.filter { it != kind && counts(MinimumGap(kind, Rational.ONE, after = it)) }

    /** The modes in which it cannot be at the chip. */
    fun pushedIn(rules: RuleSet): List<String> {
        val out = HashSet<String>()
        for (r in rules.modeRules) {
            if (conjuncts(r.lhs).isNotEmpty()) {
                val pt = Region.point(Pos.ZERO)
                val vals = with(rules).associateWith { mapOf(HUNDRED to pt) }
                if (!lhsRegion(r.lhs, vals).contains(Pos.ZERO)) continue
            }
            for (o in r.lits()) {
                if (o.negated && o.where.isPoint && o.where.lo == Pos.ZERO && (o.kind == kind || counts(MinimumGap(kind, Rational.ONE, after = o.kind)))) out.add(r.mode!!)
            }
        }
        return out.sorted()
    }

    override fun ops(state: LineState, c: Pos, until: Pos?): List<Op> {
        if (length == null) return emptyList()
        val want = place(state, c, until = until)
        val now = state.line.presence(kind) and afterR(c)
        val out = ArrayList<Op>()
        if ((want - now).isNotEmpty()) out.add(Op.put(kind, want - (now - state.claims.owned(kind, Owner.GEN))))
        if ((now - want).isNotEmpty()) out.add(Op.take(kind, now - want))
        return out
    }

    override fun toString() = if (length == null) "keep $kind as early as possible" else "keep $kind as early as possible, ${fmtNum(length)} each"

    companion object {
        const val MAX_STEPS = 20000
    }
}

/**
 * Never more than [gap] without a piece of [kind] or an interval of [after] at least [atLeast] long. The obligation:
 * with an AsEarlyAsPossible of the same kind, that one places the pieces and this one checks them; without it, this
 * one places them, each [length] long, as late as its gap allows.
 */
class MaximumGap(val kind: String, val gap: Rational, after: String? = null, val atLeast: Rational = Rational.ZERO, length: Rational? = null) : Constraint {
    val after: String = after ?: kind
    val length: Rational = length ?: atLeast
    var rules: RuleSet? = null
    var siblings: List<Constraint> = emptyList()

    init {
        require(gap.sign > 0 && atLeast.sign >= 0 && this.length.sign > 0 && this.length >= atLeast) { "a gap > 0 and a length > 0, at least `at_least`" }
    }

    override val groupKind: String get() = kind
    override val explicit get() = true
    override val cycle: Rational get() = Rational.ZERO

    override fun bind(rules: RuleSet) {
        this.rules = rules
    }

    override fun bindAll(constraints: List<Constraint>) {
        siblings = constraints.filter { it !== this }
    }

    override fun kinds() = setOf(kind, after)

    override fun holdsByItself() = true

    override fun targets(state: LineState, c: Pos): Map<String, Region> = emptyMap()

    /** Where its kind is after the chip at c: what is there that stays, and what it adds. */
    fun place(state: LineState, c: Pos): Region {
        val aft = afterR(c)
        val kept = state.line.presence(kind)
        var no = Region.EMPTY
        if (rules != null) no = forbiddenBy(rules, kind, state.line.vals) and aft
        val hard = siblings.filterIsInstance<MinimumGap>().filter { it.kind == kind && !it.soft }
        for (g in hard) no = no or (g.apart(state.line.presence(g.after), g.pullsOf(state, c)) and aft)
        val counts = if (after == kind) kept else MinimumGap(kind, Rational.ONE, after = after, atLeast = atLeast).long(state.line.presence(after)) or kept
        if (holds(counts, c)) return kept and aft
        val added = sweep(c, counts, no, hard.filter { it.after == kind })
        return (kept and aft) or (added and aft)
    }

    /** Whether no stretch without what counts that reaches past the chip is longer than the gap. */
    private fun holds(counts: Region, cp: Pos): Boolean {
        if (counts.isEmpty()) return false
        val free = counts.complement()
        val g = Pos(gap)
        val long = free.complement().dilate(Interval.of(Pos.ZERO, g + Pos.EPS, true, true)).complement()
        val bad = long and afterR(cp)
        if (bad.isEmpty()) return true
        val before = counts and upToR(cp)
        if (before.isEmpty()) {
            val late = free.complement().dilate(Interval.of(Pos.ZERO, g + Pos.EPS, true, true)) or upToR(cp + g + Pos.EPS)
            return (late.complement() and afterR(cp)).isEmpty()
        }
        return false
    }

    /** The pieces it adds after cp: each stretch longer than [gap] cut by a piece as late as it can be. */
    private fun sweep(cp: Pos, counts: Region, no: Region, gaps: List<MinimumGap>): Region {
        val g = Pos(gap)
        val l = length
        val first = cp + Pos.EPS
        var u = stretchStart(counts, first)
        val h = (listOf(first) + listOf(counts, no).filter { it.isNotEmpty() }.map { it.hi }).max()
        var p = Pos.ONE
        for (r in listOf(counts, no)) if (r.right.isNotEmpty() && r.right != full(r)) p = plcm(p, r.pr)
        val reach = Pos((gaps.maxOfOrNull { it.gap } ?: Rational.ZERO) + Rational.ONE)
        val placed = ArrayList<Pair<Pos, Pos>>()
        var bars = Region.EMPTY
        val seen = HashMap<Pair<Pos, List<Pair<Pos, Pos>>>, Pair<Int, Pos>>()
        for (step in 0 until MAX_STEPS) {
            checkpoint()
            if (u == Pos.INF) break
            if (u >= h) {
                val ahead = bars.on(u, u + reach)
                val key = (u - h).mod(p) to ahead.intervals.map { (it.lo - u) to (it.hi - u) }
                val b0 = seen[key]
                if (b0 != null) {
                    val (i0, u0) = b0
                    val before = IntervalSet.of(placed.subList(0, i0).map { Interval(it.first, it.second) })
                    val pattern = IntervalSet.of(placed.subList(i0, placed.size).map { Interval(it.first - u0, it.second - u0) })
                    if (pattern.isEmpty) return if (before.isNotEmpty()) Region.of(before) else Region.EMPTY
                    val lo = (placed.map { it.first } + u0).min()
                    return Region.make(lo, u0, before, Pos.ONE, IntervalSet.EMPTY, u - u0, pattern)
                }
                seen[key] = placed.size to u
            }
            val nxt = nextCount(counts, u, g)
            if (nxt != null) {
                u = endOf(counts, nxt) + Pos.EPS
                continue
            }
            val q = latest(no or bars, max(u, first), u + g, l) ?: max(u + g, first)
            placed.add(q to (q + l - Pos.EPS))
            for (gg in gaps) bars = bars or Region.of(IntervalSet.sorted(listOf(Interval(q + l, q + l - Pos.EPS + Pos(gg.gap)))))
            u = endOf(counts or Region.of(IntervalSet.sorted(listOf(Interval(q, q + l - Pos.EPS)))), q + l - Pos.EPS) + Pos.EPS
        }
        return if (placed.isNotEmpty()) Region.of(IntervalSet.of(placed.map { Interval(it.first, it.second) })) else Region.EMPTY
    }

    /** The first position of the stretch x is in; x if nothing counts behind it. */
    private fun stretchStart(counts: Region, x: Pos): Pos {
        if (counts.contains(x)) return endOf(counts, x) + Pos.EPS
        var w = Pos(gap + Rational.ONE)
        repeat(16) {
            val back = counts.on(x - w, x).intervals
            if (back.isNotEmpty()) return back.last().hi + Pos.EPS
            w *= 2
        }
        return x
    }

    override fun ops(state: LineState, c: Pos, until: Pos?): List<Op> {
        val want = place(state, c)
        val now = state.line.presence(kind) and afterR(c)
        return if ((want - now).isNotEmpty()) listOf(Op.put(kind, want - now)) else emptyList()
    }

    override fun toString(): String {
        val each = if (length != atLeast) ", ${fmtNum(length)} each" else ""
        if (after == kind && atLeast.sign == 0) return "keep $kind at most ${fmtNum(gap)} apart$each"
        return "keep $kind at most ${fmtNum(gap)} after ${fmtNum(atLeast)} or more of $after$each"
    }

    companion object {
        const val MAX_STEPS = 20000

        /** The first position from u that counts, if it is at most G after u. */
        fun nextCount(counts: Region, u: Pos, g: Pos): Pos? = counts.on(u, u + g + Pos.EPS).intervals.firstOrNull()?.lo

        /** The last position of the component of [region] that x is in (+inf without end). */
        fun endOf(region: Region, x: Pos): Pos {
            var e = x
            var w = Pos.ONE
            repeat(200) {
                val ivs = region.on(e, e + w + Pos.EPS).intervals.filter { it.lo <= e && e <= it.hi }
                if (ivs.isEmpty()) return e
                val hi = ivs[0].hi
                if (hi == Pos.INF) return Pos.INF
                if (hi < e + w) return hi
                e = hi
                w *= 2
            }
            return Pos.INF
        }

        /** The latest first position p in [lo, hi] with [p, p + L - ε] out of [no]; if hi < lo, the earliest from lo. */
        fun latest(no: Region, lo: Pos, hi: Pos, l: Rational): Pos? {
            if (hi < lo) {
                var t = lo
                repeat(10000) {
                    val block = no.on(t, t + l).intervals
                    if (block.isEmpty()) return t
                    if (block[0].hi == Pos.INF) return null
                    t = block[0].hi + Pos.EPS
                }
                return null
            }
            var p = hi
            while (p >= lo) {
                val block = no.on(p, p + l).intervals
                if (block.isEmpty()) return p
                p = block[0].lo - l
            }
            return null
        }
    }
}

/** The constraints with what places the pieces of each MaximumGap that nothing else places. */
fun expand(constraints: List<Constraint>): List<Constraint> {
    val out = constraints.toMutableList()
    for (k in constraints) {
        for (g in constraints) {
            if (k is MaximumGap && g is MinimumGap && g.kind == k.kind && g.after == k.after && g.atLeast == k.atLeast && g.gap > k.gap) {
                throw IllegalArgumentException("no line keeps both: $g; $k")
            }
        }
        if (k !is MaximumGap || out.any { it is AsEarlyAsPossible && it.kind == k.kind && it.length != null }) continue
        val pull = out.filterIsInstance<MinimumGap>().firstOrNull { it.kind == k.kind && it.pull != null }?.pull
        val gap = MinimumGap(k.kind, k.gap, after = if (k.after != k.kind) k.after else null, atLeast = k.atLeast, pull = pull)
        val early = AsEarlyAsPossible(k.kind, length = k.length)
        gap.soft = true // (a wait: where its end is taken, the piece comes sooner)
        gap.placedFor = k
        early.placedFor = k
        out.add(early)
        out.add(gap)
    }
    return out
}

/** A right tail that is all of its period. */
private fun full(r: Region): IntervalSet = IntervalSet.sorted(listOf(Interval(Pos.ZERO, r.pr - Pos.EPS)))

/**
 * Screen breaks of one length (OmniApp's 15-minute breaks, by their rules, but of any length): with "no screen",
 * everywhere after the chip, each as early as allowed; none within [pause] after [length] or more of "no screen"; one
 * that would start inside a "no screen" period starts at its start; never over a ruled-out position.
 */
class Breaks(val brk: String = "Break15", val ns: String = "NoScreen", val length: Rational = Rational.of(15), val pause: Rational = Rational.of(120)) : Constraint {
    override val cycle: Rational get() = length + pause

    override fun toString() = "keep $brk on (chip, chip + ${fmtNum(length)}] with $ns, as early as possible, none within ${fmtNum(pause)} after as much $ns"

    override fun kinds() = setOf(brk, ns)

    override fun holdsByItself() = true

    /** The breaks there must be after the chip at c (those going on included). */
    fun breaks(state: LineState, c: Pos): Region {
        val l = length
        val p = pause
        val now = state.line.presence(brk)
        var others = Region.EMPTY
        for (r in state.claims.pos[ns].orEmpty().values) others = others or r
        val known = (state.line.presence(ns) and upToR(c)) or (others and afterR(c))
        val ruledOut = (state.claims.negative(ns) or state.claims.negative(brk) or state.claims.off(brk)) and afterR(c)
        val going = now.on(c - l - 1, c + l + 1).intervals.filter { it.lo < c && c < it.hi }
        val ruled = state.claims.negative(ns) or state.claims.negative(brk) or state.claims.off(brk)
        val placed = going.map { iv ->
            val longer = iv.hi < iv.lo + l && (Region.of(IntervalSet.sorted(listOf(Interval(iv.hi, iv.lo + l)))) and ruled).isEmpty()
            iv.lo to (if (longer) iv.lo + l else iv.hi)
        }.toMutableList()
        val ahead = listOf(known and afterR(c), ruledOut).filter { it.isNotEmpty() }
        val end = if (ahead.any { !it.isBounded() }) {
            ahead.filter { !it.isBounded() }.minOf { it.hi }
        } else {
            (ahead.map { it.bounded() }.filter { it.isNotEmpty() }.map { it.sup() } + c).max()
        }
        val lo = c - l - p - 1
        val bans = if (ruledOut.isNotEmpty()) ruledOut.on(c, end + l + 1).intervals.map { it.lo to it.hi } else emptyList()

        fun periods(extra: List<Pair<Pos, Pos>>): List<Pair<Pos, Pos>> {
            val reg = if (extra.isNotEmpty()) known or Region.of(IntervalSet.of(extra.map { Interval(it.first, it.second) })) else known
            return reg.on(lo, end + (l + p) * 2 + 2).intervals.map { it.lo to it.hi }
        }

        fun allowed(t0: Pos): Pos {
            var t = t0
            var changed = true
            while (changed) {
                checkpoint()
                changed = false
                for ((a, e) in periods(placed)) {
                    if ((e - a).x >= l && ((e < t && t < e + p) || (a < t && t <= e && placed.any { (pa, pe) -> a <= pa && pe <= e }))) {
                        t = e + p
                        changed = true
                    }
                }
                for ((a, e) in bans + placed) {
                    if (a <= t + l && e >= t) {
                        t = e + Pos.EPS
                        changed = true
                    }
                }
            }
            return t
        }
        var t = allowed(c + Pos.EPS)
        while (t <= end) {
            checkpoint()
            for ((a, e) in periods(emptyList())) {
                if (a < t && t <= e) {
                    t = if (a > c) a else c + Pos.EPS
                    break
                }
            }
            placed.add(t to (t + l))
            t = allowed(t + l + Pos.EPS)
        }
        val core = IntervalSet.of(placed.map { Interval(it.first, it.second) })
        val first = (placed.map { it.first } + t).min()
        return Region.make(first, t, core, Pos.ONE, IntervalSet.EMPTY, Pos(l + p), IntervalSet.sorted(listOf(Interval(Pos.ZERO, Pos(l)))))
    }

    override fun targets(state: LineState, c: Pos): Map<String, Region> = mapOf(brk to (breaks(state, c) and afterR(c)))

    override fun ops(state: LineState, c: Pos, until: Pos?): List<Op> {
        val want = breaks(state, c) and afterR(c)
        val now = state.line.presence(brk) and afterR(c)
        val out = ArrayList<Op>()
        if ((want - now).isNotEmpty()) out.add(Op.put(brk, want - now))
        if ((now - want).isNotEmpty()) out.add(Op.take(brk, now - want))
        return out
    }
}

/**
 * Several kinds of screen breaks, each (kind, length, pause), all with "no screen": one after the other, each as early
 * as allowed, the longer of two that could start at the same position; none of a kind within its pause after as
 * much "no screen"; a break never overlaps nor touches another or a ruled-out position; one that would start inside
 * a "no screen" period with no break yet starts at its start; [chain]: a chain of those kinds is its longest break.
 */
class ScreenBreaks(
    breaks: List<Triple<String, Rational, Rational>> = listOf(
        Triple("Break20s", Rational.of(20), Rational.of(20 * 60)),
        Triple("Break5", Rational.of(5 * 60), Rational.of(60 * 60)),
        Triple("Break15", Rational.of(15 * 60), Rational.of(120 * 60)),
    ),
    val ns: String = "NoScreen",
    chain: Collection<String> = listOf("Break5", "Break15"),
    var rules: RuleSet? = null,
) : Constraint {
    val kindsOf: List<Triple<String, Rational, Rational>> = breaks.toList()
    val chain: Set<String> = chain.toSet()

    init {
        require(kindsOf.isNotEmpty() && kindsOf.none { it.second.sign <= 0 || it.third.sign < 0 }) { "breaks: (kind, length > 0, pause >= 0), at least one" }
    }

    override val cycle: Rational get() = kindsOf.maxOf { it.second + it.third }

    override fun bind(rules: RuleSet) {
        this.rules = rules
    }

    override fun toString(): String {
        val parts = kindsOf.joinToString(", ") { (k, l, p) -> "$k of ${fmtNum(l)} (none within ${fmtNum(p)} after as much $ns)" }
        val ch = if (chain.isNotEmpty()) "; a chain of ${chain.sorted().joinToString(", ")} is its longest break" else ""
        return "keep $parts with $ns, as early as possible$ch"
    }

    override fun kinds() = kindsOf.map { it.first }.toSet() + ns

    override fun holdsByItself() = true

    // ------------------------------------------------------------ what is known

    private val aheadCache = ArrayList<Pair<Pair<RuleSet, Claims>, Region?>>()
    private val breaksCache = ArrayList<Pair<List<Any>, Map<String, Region>>>()

    /** "No screen" after the chip without the generated breaks there. */
    private fun ahead(state: LineState, c: Pos): Region {
        val later = afterR(c)
        val rs = rules
        if (rs != null) {
            val claims = state.claims.copy()
            for ((k, _, _) in kindsOf) {
                val d = claims.pos[k] ?: continue
                for (key in d.keys.toList()) {
                    if (key.second == Owner.GEN) {
                        val r = d.getValue(key) - later
                        if (r.isEmpty()) d.remove(key) else d[key] = r
                    }
                }
            }
            val hit = aheadCache.firstOrNull { it.first.first === rs && it.first.second.same(claims) }
            val r = if (hit != null) {
                hit.second
            } else {
                val v = try {
                    solve(rs, claims.problem()).presence(ns)
                } catch (e: Exception) { // (the rules refuse the line without them: the claims only)
                    null
                }
                aheadCache.add(0, (rs to claims) to v)
                while (aheadCache.size > CACHE) aheadCache.removeAt(aheadCache.size - 1)
                v
            }
            if (r != null) return r and later
        }
        return state.claims.positive(ns) and later
    }

    // ------------------------------------------------------------ the sweep

    /** {kind: the breaks of that kind there must be after the chip at c (those going on included)}. */
    fun breaks(state: LineState, c: Pos): Map<String, Region> {
        val claims = state.claims
        val known = (state.line.presence(ns) and upToR(c)) or ahead(state, c)
        val ruled = kindsOf.map { (k, _, _) -> (claims.negative(ns) or claims.negative(k) or claims.off(k)) and afterR(c) }
        val placed = ArrayList<Triple<Int, Pos, Pos>>()
        for ((i, kk) in kindsOf.withIndex()) {
            val (k, l, _) = kk
            val ruledI = claims.negative(ns) or claims.negative(k) or claims.off(k)
            for (iv in state.line.presence(k).on(c - l - 1, c + l + 1).intervals) {
                if (iv.lo < c && c < iv.hi) {
                    val longer = iv.hi < iv.lo + l && (Region.of(IntervalSet.sorted(listOf(Interval(iv.hi, iv.lo + l)))) and ruledI).isEmpty()
                    placed.add(Triple(i, iv.lo, if (longer) iv.lo + l else iv.hi))
                }
            }
        }
        placed.sortBy { it.second }
        val key = listOf(c, known, ruled, placed.toList())
        breaksCache.firstOrNull { (k2, _) -> k2[0] == key[0] && k2[3] == key[3] && k2[1] == key[1] && k2[2] == key[2] }?.let { return it.second }
        val v = sweep(c, known, ruled, placed)
        breaksCache.add(0, key to v)
        while (breaksCache.size > CACHE) breaksCache.removeAt(breaksCache.size - 1)
        return v
    }

    private fun sweep(cp: Pos, known: Region, ruled: List<Region>, placed: MutableList<Triple<Int, Pos, Pos>>): Map<String, Region> {
        val k = kindsOf
        val c = cp
        val maxL = k.maxOf { it.second }
        val w = k.maxOf { it.second + it.third } + maxL + Rational.ONE
        var h0 = cp
        var p: Pos? = null
        var stop: Pos? = null
        for (r in listOf(known and afterR(c)) + ruled) {
            if (r.isEmpty()) continue
            h0 = max(h0, r.hi)
            if (r.right.isEmpty) continue
            if (r.right == IntervalSet.sorted(listOf(Interval(Pos.ZERO, r.pr - Pos.EPS)))) {
                val ivs = r.on(min(r.lo, cp), r.hi + 1).intervals
                val end = if (ivs.isNotEmpty() && ivs.last().hi >= r.hi - Pos.EPS) ivs.last().lo else r.hi
                stop = if (stop == null) end else min(stop, end)
            } else {
                p = if (p == null) r.pr else plcm(p, r.pr)
            }
        }
        val wc = if (p != null) p + w else Pos(w)
        var longest = placed.maxOfOrNull { it.third - it.second } ?: Pos.ZERO

        fun recent(bound: Pos): List<Triple<Int, Pos, Pos>> {
            val out = ArrayList<Triple<Int, Pos, Pos>>()
            for (b in placed.asReversed()) {
                if (b.third >= bound) out.add(b) else if (b.second + longest < bound) break
            }
            out.reverse()
            return out
        }
        var knLo: Pos? = null
        var knHi: Pos? = null
        var knIvs: List<Pair<Pos, Pos>> = emptyList()

        fun knownNear(a: Pos, b: Pos): List<Pair<Pos, Pos>> {
            val kl = knLo
            val kh = knHi
            if (kl == null || kh == null || a < kl || b > kh) {
                val lo = if (kl == null) a else min(a, kl)
                var hi = if (kh == null) b else max(b, kh)
                var aheadTo = hi + w * 4
                if (stop != null) aheadTo = min(aheadTo, max(stop + w, hi))
                hi = max(hi, aheadTo)
                knLo = lo
                knHi = hi
                knIvs = around(known, lo, hi)
            }
            return knIvs.filter { (x, y) -> x < b && y >= a }
        }

        fun periods(a: Pos, b: Pos): List<Pair<Pos, Pos>> {
            val mine = recent(a - w).map { Interval(it.second, it.third) }
            if (mine.isEmpty()) return knownNear(a, b)
            val lo = min(a, mine[0].lo) - 1
            val hi = max(b, mine.maxOf { it.hi }) + 1
            val near = IntervalSet.of(knownNear(lo, hi).map { Interval(it.first, it.second) }) or IntervalSet.of(mine)
            return near.intervals.filter { it.lo < b && it.hi >= a }.map { it.lo to it.hi }
        }

        fun allowed(i: Int, t0: Pos): Pos {
            val (_, l, pp) = k[i]
            var t = t0
            var changed = true
            while (changed) {
                checkpoint()
                changed = false
                for ((a, e) in periods(t - w, t + l + 1)) {
                    val lng = (e - a).x >= l
                    if (lng && e < t && t < e + pp) {
                        t = e + pp
                        changed = true
                    } else if (a < t && t <= e && recent(a).any { (_, pa, pe) -> a <= pa && pe <= e }) {
                        t = if (lng) e + pp else e + Pos.EPS
                        changed = true
                    }
                }
                val bans = if (ruled[i].isNotEmpty()) ruled[i].on(t, t + l + w).intervals else emptyList()
                for ((a, e) in bans.map { it.lo to it.hi } + recent(t).map { it.second to it.third }) {
                    if (a <= t + l && e >= t) {
                        t = e + Pos.EPS
                        changed = true
                    }
                }
            }
            return t
        }

        fun moved(t: Pos): Pos {
            for ((a, e) in knownNear(t - 1, t + 1)) if (a < t && t <= e) return if (a > cp) a else cp + Pos.EPS
            return t
        }
        var frm = cp + Pos.EPS
        val seen = HashMap<Any, Int>()
        var tail: Int? = null
        var s = Pos.ZERO
        while (placed.size < MAX_BREAKS) {
            val starts = k.indices.map { allowed(it, frm) }
            val best = starts.withIndex().map { (i, t) -> Triple(moved(t), -k[i].second, i) }
                .minWith(compareBy<Triple<Pos, Rational, Int>>({ it.first }, { it.second }, { it.third }))
            s = best.first
            var i = best.third
            if (stop != null && s > stop) break
            if (k[i].first in chain) {
                for (j in k.indices.filter { k[it].first in chain && k[it].second > k[i].second }.sortedBy { -k[it].second }) {
                    if (starts[j] <= s + k[i].second + Pos.EPS) {
                        i = j
                        break
                    }
                }
            }
            if (stop == null && s >= h0 + wc) { // what follows depends on what is near only
                val ph = if (p != null) (s - h0).mod(p) else Pos.ZERO
                val sig = Pair(ph.x to ph.k, recent(s - wc).map { (j, a, e) -> listOf<Any>(j, (a - s).x, (a - s).k, (e - s).x, (e - s).k) })
                val hit = seen[sig]
                if (hit != null) {
                    tail = hit
                    break
                }
                seen[sig] = placed.size
            }
            placed.add(Triple(i, s, s + k[i].second))
            longest = max(longest, Pos(k[i].second))
            frm = s + Pos.EPS
        }
        val out = LinkedHashMap<String, Region>()
        if (tail == null) {
            for ((i, kk) in k.withIndex()) out[kk.first] = Region.of(IntervalSet.of(placed.filter { it.first == i }.map { Interval(it.second, it.third) }))
            return out
        }
        val a0 = placed[tail].second
        val period = s - a0
        val first = (placed.subList(0, tail).map { it.second } + a0).min()
        for ((i, kk) in k.withIndex()) {
            val core = IntervalSet.of(placed.subList(0, tail).filter { it.first == i }.map { Interval(it.second, it.third) })
            val pattern = IntervalSet.of(placed.subList(tail, placed.size).filter { it.first == i }.map { Interval(it.second - a0, it.third - a0) })
            out[kk.first] = Region.make(first, a0, core, Pos.ONE, IntervalSet.EMPTY, period, pattern)
        }
        return out
    }

    override fun targets(state: LineState, c: Pos): Map<String, Region> = breaks(state, c).mapValues { it.value and afterR(c) }

    override fun ops(state: LineState, c: Pos, until: Pos?): List<Op> {
        val out = ArrayList<Op>()
        for ((k, want) in targets(state, c)) {
            val now = state.line.presence(k) and afterR(c)
            if ((want - now).isNotEmpty()) out.add(Op.put(k, want - now))
            if ((now - want).isNotEmpty()) out.add(Op.take(k, now - want))
        }
        return out
    }

    companion object {
        const val MAX_BREAKS = 20000
        const val CACHE = 8

        /** The intervals of r that meet [a, b), with their true ends (the window grows while one is cut by it). */
        fun around(r: Region, a0: Pos, b0: Pos): List<Pair<Pos, Pos>> {
            var a = a0
            var b = b0
            var span = max(b - a, Pos.ONE)
            var ivs = r.on(a, b).intervals
            var cutLo = false
            var cutHi = false
            repeat(64) {
                ivs = r.on(a, b).intervals
                cutLo = ivs.isNotEmpty() && ivs[0].lo <= a && r.contains(a - Pos.EPS)
                cutHi = ivs.isNotEmpty() && ivs.last().hi >= b - Pos.EPS && r.contains(b)
                if (!cutLo && !cutHi) return ivs.map { it.lo to it.hi }
                // The reference doubles 64 times over unbounded integers; past ~2^40 units an interval still cut runs
                // to the end of the line (a constant tail), and its bound is only ever compared: infinite here.
                if (span.x.abs() > Rational.of(1L shl 40)) {
                    val out = ivs.map { it.lo to it.hi }.toMutableList()
                    if (cutLo) out[0] = Pos.NEG_INF to out[0].second
                    if (cutHi) out[out.size - 1] = out.last().first to Pos.INF
                    return out
                }
                span *= 2
                if (cutLo) a -= span
                if (cutHi) b += span
            }
            return ivs.map { it.lo to it.hi }
        }
    }
}
