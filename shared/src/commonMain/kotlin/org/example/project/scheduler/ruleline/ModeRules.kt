package org.example.project.scheduler.ruleline


/**
 * The mode rules acting at the chip, one position after the other — a port of
 * `C:\code\line_manager\scripts\mode_rules.py` (its module text says how it is computed exactly).
 *
 * When the chip is at x, the mode rules of the mode at x are decided at x, in order, on the line as it is: a rule
 * whose left side is not met does nothing; for each item, the leftmost operand that already holds or whose edit on
 * {x} is accepted (resolved like any edit, and respecting M1); else nothing. Between two positions the decision only
 * changes at a few: a run of the same decision is compiled ahead ([RunPlan]), and inside a checked stretch the line
 * is an affine function of the chip's position.
 */
sealed class Choice {
    data class Holds(val oi: Int) : Choice()
    data class Put(val oi: Int, val gone: List<Pair<String, Boolean>>) : Choice()
    data object Nothing : Choice()

    /** The last item of a decision where the mode rules keep pushes: the kinds the chip pushes. */
    data class Pushing(val kinds: Set<String>) : Choice()
}

typealias Decision = List<Choice?>

/** A change of the mode rules: one decision on the positions [at], the first being [start]. */
class Run(val start: Pos, val at: Region, val before: LineState, val after: LineState, val end: Pos? = null, val plan: RunPlan? = null)

/** The mode rules compiled by a generator run: with the chip at p of [a, b] in [mode], the state is [stateAt]. */
class Stretch(val mode: String, val a: Pos, val b: Pos, val before: LineState, val after: LineState, val plan: RunPlan? = null) {
    fun stateAt(p: Pos): LineState = if (plan == null || p == b) after else plan.stateAt(p)
}

/** Something the chip goes through: a [Run], or an operand refused (position, reason). */
sealed class Act {
    class Change(val run: Run) : Act()
    class Refusal(val at: Pos, val why: String) : Act()
}

/** The mode rules along each (lo, hi, mode) segment from [state]: the stretches they make, and the operands refused. */
fun compileRuns(
    rs: RuleSet, state0: LineState, segments: List<Triple<Pos, Pos, String>>, constraints: List<Constraint> = emptyList(), pushes: List<ChipPush> = emptyList(),
): Pair<List<Stretch>, List<Pair<Pos, String>>> {
    var state = state0
    val out = ArrayList<Stretch>()
    val refused = ArrayList<Pair<Pos, String>>()
    val mrs = HashMap<String, ModeRules>()
    for ((lo, hi, mode) in segments) {
        val mr = mrs.getOrPut(mode) { ModeRules(rs, mode, constraints + pushesIn(pushes, mode)) }
        for (ev in mr.act(state, lo, hi)) {
            when (ev) {
                is Act.Change -> {
                    val r = ev.run
                    out.add(Stretch(mode, r.start, r.end!!, r.before, r.after, r.plan?.frozen()))
                    state = r.after
                }
                is Act.Refusal -> refused.add(ev.at to ev.why)
            }
        }
    }
    return out to refused
}

class RunChanges(val at: Pos) : Exception("the decision changes at $at")

class ModeRules(val rs: RuleSet, val mode: String, constraints: List<Constraint> = emptyList()) {
    val constraints: List<Constraint> = constraints.toList()

    /** The runs after [plan], decided and checked ahead elsewhere. */
    var ready: MutableList<RunPlan> = ArrayList()
    val pushes: List<ChipPush> = this.constraints.filterIsInstance<ChipPush>()

    /** The placement of each pushed kind: its AsEarlyAsPossible with a length. */
    val placers: Map<String, AsEarlyAsPossible> =
        this.constraints.filterIsInstance<AsEarlyAsPossible>().filter { it.length != null }.associateBy { it.kind }
    val rules: List<Rule> = rs.modeRules.filter { it.mode == mode }
    val items: List<Pair<Rule, Item>> = rules.flatMap { r -> r.rhs.map { r to it } }
    val g: Rational?

    /** How far a moving end can be from x. */
    val span: Rational = Rational.of(2 * (rs.order.size + 1)) * rs.reach
    var plan: RunPlan? = null

    /** The position where the user edited after the chip got there. */
    var kept: Pos? = null
    private val reads: ((String) -> Boolean)?

    init {
        var gg: Rational? = null
        for (r in rs.element) for (o in r.lits()) for (e in listOf(o.where.lo, o.where.hi)) {
            if (e.isFinite && e.x.sign != 0) gg = if (gg == null) e.x.abs() else fgcdR(gg, e.x.abs())
        }
        g = gg
        val read = HashSet<String>()
        for (r in rules) {
            read.addAll(r.lhs.kinds())
            read.addAll(r.writtenLits().map { it.kind })
        }
        for (k in this.constraints) read.addAll(k.kinds())
        val linked = rs.linked
        val groups = read.mapNotNull { linked[it] }.toSet()
        reads = { k: String -> k in read || linked[k] in groups }
    }

    // ------------------------------------------------------------ one position

    /** The decision at x on this state, the state after it, and why operands were refused. */
    fun decide(state: LineState, x: Pos): Triple<Decision, LineState, List<String>> {
        var s = state
        val out = ArrayList<Choice?>()
        val why = ArrayList<String>()
        val pushing = pushing(state, x) // (before the rules act at x)

        fun item(r: Rule, it: Item, s0: LineState): Pair<Choice, LineState> {
            for ((oi, o) in it.options.withIndex()) {
                if (holds(o, s0, x)) return Choice.Holds(oi) to s0
                val at = Region.point(x)
                val op = if (o.negated) Op.putNo(o.kind, at) else Op.put(o.kind, at, o.value ?: Rational.of(100))
                val after = try {
                    applyEdit(rs, s0, listOf(op), Owner.MODE)
                } catch (e: Refused) {
                    why.add("$r: $o at $x: ${e.message}")
                    continue
                }
                var reason = checkModeEdit(s0, after, at, x)
                if (x == kept && !sameDict(userClaims(s0.claims), userClaims(after.claims))) {
                    reason = "N2: the user edited this position after the chip got there"
                }
                if (reason != null) {
                    why.add("$r: $o at $x: $reason")
                    continue
                }
                return Choice.Put(oi, gone(s0, after, x, o.kind)) to after
            }
            return Choice.Nothing to s0
        }
        for (r in rules) {
            val met = lhsRegion(r.lhs, s.line.vals).contains(x)
            for (it in r.rhs) {
                if (!met) {
                    out.add(null)
                    continue
                }
                val (choice, s1) = item(r, it, s)
                out.add(choice)
                s = s1
            }
        }
        // an item found already true that a later one made false: made true again, in order, until each holds
        repeat(items.size) {
            val redo = items.indices.filter { i ->
                val ch = out[i]
                ch is Choice.Holds && !holds(items[i].second.options[ch.oi], s, x)
            }
            if (redo.isEmpty()) return@repeat
            for (i in redo) {
                val (r, it) = items[i]
                val (choice, s1) = item(r, it, s)
                out[i] = choice
                s = s1
            }
        }
        if (pushes.isNotEmpty()) {
            out.add(Choice.Pushing(pushing))
            return Triple(out, constrained(s, x, pushing), why)
        }
        return Triple(out, constrained(s, x), why)
    }

    /** The kinds the chip at p pushes on this state. */
    fun pushing(state: LineState, p: Pos): Set<String> = pushes.filter { it.on(state, p) }.map { it.kind }.toSet()

    /** The state with the decision's edits on [at]. [pushing]: the kinds the chip pushes all along the run. */
    fun withRun(state0: LineState, decision: Decision, at: Region, pushing: Set<String>? = null): LineState {
        var state = state0
        val p = at.bounded().sup()
        var claims = state.claims
        if (!pushing.isNullOrEmpty()) {
            claims = pushedBehind(claims, pushing, at.bounded().inf() - Pos.EPS, p)
            state = LineState(claims, state.line, state.history)
        }
        val c = runClaims(state, decision, at) ?: if (pushing.isNullOrEmpty()) return state else claims
        return constrained(LineState(c, solve(rs, c.problem()), state.history), p, pushing)
    }

    /** The claims with what the chip pushing these kinds from m leaves behind it on (m, p]. */
    fun pushedBehind(claims: Claims, pushing: Set<String>, m: Pos, p: Pos): Claims {
        var c: Claims? = null
        val went = Region.of(Interval.of(m, p, false, true))
        for (k in constraints) {
            if (k is ChipPush && k.kind in pushing) {
                val cc = c ?: claims.copy().also { c = it }
                val d = cc.pos.getOrPut(k.kind) { LinkedHashMap() }
                for (key in d.keys.filter { it.second == Owner.GEN }) {
                    val r = d.getValue(key) - went
                    if (r.isEmpty()) d.remove(key) else d[key] = r
                }
                if (k.behind()) {
                    val key = Rational.of(100) to Owner.GEN
                    d[key] = d[key]?.let { it or went } ?: went
                }
            }
        }
        return c ?: claims
    }

    private fun targetsOf(k: Constraint, st: LineState, p: Pos, pushing: Set<String>?): Map<String, Region> {
        if (pushing != null && k is ChipPush) {
            if (k.kind !in pushing) return emptyMap()
            val free = k.fromChip(p)
            val a = placers[k.kind] ?: return free
            val moving = moving(k, st, p)
            val (no, _) = a.obstacles(st, p, moving)
            if ((free.getValue(k.kind) and no).isEmpty() && !touches(a, st, p, free.getValue(k.kind), moving)) return free
            // it would run into what stays: the pushed piece where the chip pushes it, the others placed after it
            var forced: Pair<Pos, Pos>? = null
            if (k.behind()) {
                val iv = free.getValue(k.kind).on(p, p + firstEnd(k.region) + 1).intervals[0]
                forced = iv.lo to iv.hi
            }
            return mapOf(k.kind to (a.place(st, p, forced, moving) and Region.of(Interval.of(p, Pos.INF, false, false))))
        }
        return k.targets(st, p)
    }

    /**
     * The claims with the constraints' elements after p where they want them, as the generator's. [giveWay]: the
     * generator's other elements are taken off where those go.
     */
    fun constrain(claims: Claims, line: Line, p: Pos, history: List<Record> = emptyList(), giveWay: Boolean = false, pushing: Set<String>? = null): Claims {
        if (constraints.isEmpty()) return claims
        val st = LineState(claims, line, history)
        var c = claims.copy()
        val later = Region.of(Interval.of(p, Pos.INF, false, false))
        val targets = constraints.map { targetsOf(it, st, p, pushing) }
        val wanted = LinkedHashMap<String, Region>()
        for (t in targets) wanted.putAll(t)
        if (giveWay) {
            var moved = Region.EMPTY
            for (want in wanted.values) moved = moved or want
            for ((kind, d) in c.pos) {
                if (kind in wanted) continue
                for (key in d.keys.filter { it.second == Owner.GEN }) {
                    val r = d.getValue(key) - moved
                    if (r.isEmpty()) d.remove(key) else d[key] = r
                }
            }
        }
        for (t in targets) for ((kind, want) in t) {
            val d = c.pos.getOrPut(kind) { LinkedHashMap() }
            for (key in d.keys.toList()) {
                if (key.second == Owner.GEN) {
                    val r = d.getValue(key) - later
                    if (r.isEmpty()) d.remove(key) else d[key] = r
                }
            }
            if (want.isNotEmpty()) {
                val key = Rational.of(100) to Owner.GEN
                d[key] = d[key]?.let { it or want } ?: want
            }
        }
        if (!pushing.isNullOrEmpty()) c = placedAfterPushes(c, p, pushing, history)
        return c
    }

    /** The claims with the kinds that read a pushed one placed again on the line the push made. */
    private fun placedAfterPushes(c0: Claims, p: Pos, pushing: Set<String>, history: List<Record>): Claims {
        var c = c0
        val later = Region.of(Interval.of(p, Pos.INF, false, false))
        for ((kind, a) in placers) {
            if (kind in pushing || pushing.none { readsPushed(a, it) }) continue
            val st = try {
                LineState(c, solve(rs, c.problem()), history)
            } catch (e: Conflict) {
                return c
            }
            val want = a.place(st, p) and later
            var have = Region.EMPTY
            for ((key, r) in c.pos[kind].orEmpty()) if (key.second == Owner.GEN) have = have or (r and later)
            if (have == want) continue
            c = c.copy()
            val d = c.pos.getOrPut(kind) { LinkedHashMap() }
            for (key in d.keys.toList()) {
                if (key.second == Owner.GEN) {
                    val r = d.getValue(key) - later
                    if (r.isEmpty()) d.remove(key) else d[key] = r
                }
            }
            if (want.isNotEmpty()) {
                val key = Rational.of(100) to Owner.GEN
                d[key] = d[key]?.let { it or want } ?: want
            }
        }
        return c
    }

    /** Whether placer [a] reads pushed kind k. */
    private fun readsPushed(a: AsEarlyAsPossible, k: String): Boolean {
        val brought = HashSet<String>()
        brought.add(k)
        for (r in rs.element) if (r.lhs.kinds() == listOf(k)) brought.addAll(r.lits().map { it.kind })
        if (a.gaps().any { it.after in brought }) return true
        return a.kind in brought
    }

    /** The state with the constraints kept after p, solved. */
    private fun constrained(state: LineState, p: Pos, pushing: Set<String>? = null): LineState {
        if (constraints.isEmpty()) return state
        val c = constrain(state.claims, state.line, p, state.history, pushing = pushing)
        if (c.same(state.claims)) return state
        return try {
            LineState(c, solve(rs, c.problem()), state.history)
        } catch (e: Conflict) { // the generator's other elements are in the way
            val c2 = constrain(state.claims, state.line, p, state.history, giveWay = true, pushing = pushing)
            try {
                LineState(c2, solve(rs, c2.problem()), state.history)
            } catch (e2: Conflict) {
                state // the constraints cannot be kept here: the mode rules' decision alone
            }
        }
    }

    /** The claims of [withRun], or null if the decision places nothing. */
    fun runClaims(state: LineState, decision: Decision, at: Region): Claims? {
        if (decision.none { it is Choice.Put }) return null
        val c = state.claims.copy()
        for ((i, choice) in decision.withIndex()) {
            if (choice !is Choice.Put || i >= items.size) continue
            val it = items[i].second
            for ((kind, positive) in choice.gone) if (positive) c.removePos(kind, at) else c.removeNeg(kind, at)
            val o = it.options[choice.oi]
            if (o.negated) c.addNeg(o.kind, at, Owner.MODE) else c.addPos(o.kind, at, o.value ?: Rational.of(100), Owner.MODE)
        }
        return c
    }

    // ------------------------------------------------------------ the positions of a sweep

    /** The positions of [lo, hi] where a run started at m can change its decision. */
    fun candidates(state: LineState, m: Pos, lo: Pos, hi: Pos, pushing: Set<String>? = null): List<Pos> {
        val keep = reads ?: { _: String -> true }
        val a = Pos(lo.x - span - Rational.ONE)
        val b = Pos(hi.x + span + Rational.ONE)
        val pushed = pushing.orEmpty()
        val moving = HashSet<Pos>()
        for (kind in pushed) {
            val regs = state.claims.pos[kind].orEmpty().filter { it.key.second == Owner.GEN }.values + state.line.vals[kind].orEmpty().values
            for (reg in regs) for (iv in reg.on(a, b).intervals) for (e in listOf(iv.lo, iv.hi)) if (e.isFinite && e > m) moving.add(e)
        }
        val regions = ArrayList<Region>()
        for ((k, d) in state.claims.pos) if (keep(k)) for ((key, r) in d) if (!(k in pushed && key.second == Owner.GEN)) regions.add(r)
        for ((k, d) in state.claims.neg) if (keep(k)) regions.addAll(d.values)
        for ((k, d) in state.claims.taken) if (keep(k)) regions.addAll(d.values)
        val fixed = state.line.vals.filterKeys { keep(it) }.values.flatMap { it.values }
        val ends = HashSet<Pos>()
        ends.add(m)
        for (reg in regions) for (iv in reg.on(a, b).intervals) for (e in listOf(iv.lo, iv.hi)) if (e.isFinite) ends.add(e)
        for (reg in fixed) for (iv in reg.on(a, b).intervals) for (e in listOf(iv.lo, iv.hi)) if (e.isFinite && e !in moving) ends.add(e)
        for (k in pushes) ends.addAll(k.flips(state.line, a, b))
        val outCross = HashSet<Pos>()
        for (k in pushes) {
            val pl = placers[k.kind]
            if (k.kind !in pushed || pl == null) continue
            val (no, _) = pl.obstacles(state, m, moving(k, state, m))
            val edges = no.on(lo, hi + span + 1).intervals.flatMap { listOf(it.lo, it.hi) }.filter { it.isFinite }.toSet()
            val offsets = k.region.on(Pos.ZERO, Pos(span) + 1).intervals.flatMap { listOf(it.lo, it.hi) }.filter { it.isFinite }.toSet()
            for (e in edges) for (o in offsets) for (j in -1L..1L) {
                val x = e - o + Pos.EPS * j
                if (lo <= x && x <= hi) outCross.add(x)
            }
        }
        val out = HashSet<Pos>()
        val jr = rs.epsReach.toLong()
        for (e in ends) {
            val gg = g
            val ks: LongRange = if (gg == null) {
                0L..0L
            } else {
                val n = (span / gg).floor()
                maxOf(-n, ((e.x - hi.x - Rational.ONE) / gg).ceil())..minOf(n, ((e.x - lo.x + Rational.ONE) / gg).floor())
            }
            for (k in ks) {
                val base = if (gg != null) e - gg * Rational.of(k) else e
                for (j in -jr..jr) {
                    val c = base + Pos.EPS * j
                    if (lo <= c && c <= hi) out.add(c)
                }
            }
        }
        return (out + outCross).sorted()
    }

    /** The chip goes through [lo, hi]: changes (runs) and refusals, in order. A run compiled earlier goes on if it can. */
    fun act(state0: LineState, lo: Pos, hi: Pos): List<Act> {
        val events = ArrayList<Act>()
        var state = state0
        var x = lo
        while (x <= hi) {
            var pl = plan
            val nxt: Pos
            val pending = pl?.pending
            if (pl != null && pl.last === state && pl.next == x) {
                nxt = x
            } else if (pl != null && pending != null && pl.before === state && pl.m == x) {
                val (why, s1) = pending // decided ahead: it happens now
                pl.pending = null
                for (w in why) events.add(Act.Refusal(x, w))
                if (s1 !== state) events.add(Act.Change(Run(x, Region.point(x), state, s1, x)))
                state = s1
                pl.last = s1
                nxt = x + Pos.EPS
                if (nxt > hi) return events
            } else if (takeReady(state, x)) {
                continue // (decided ahead, elsewhere)
            } else {
                val (decision, s1, why) = decide(state, x)
                for (w in why) events.add(Act.Refusal(x, w))
                if (s1 !== state) events.add(Act.Change(Run(x, Region.point(x), state, s1, x)))
                state = s1
                pl = RunPlan(this, s1, decision, x)
                plan = pl
                nxt = x + Pos.EPS
                if (nxt > hi) return events
            }
            val end = try {
                pl.extend(nxt, hi)
            } catch (e: RunChanges) {
                plan = null
                events.add(Act.Refusal(e.at, "mode rules: the decision changes at a position that is not looked at; the rest is refused"))
                return events
            }
            if (end == null) { // the decision changes at nxt
                plan = null
                x = nxt
                continue
            }
            val state2 = pl.stateAt(end)
            if (state2 !== state) events.add(Act.Change(Run(nxt, Region.of(Interval.of(nxt, end)), state, state2, end, pl)))
            state = state2
            pl.last = state2
            pl.next = end + Pos.EPS
            x = end + Pos.EPS
        }
        return events
    }

    /** Compile ahead the run the chip at x starts, checked up to x + [ahead] at least. */
    fun prepare(state: LineState, x: Pos, ahead: Rational = Rational.ONE) {
        val pl = plan
        if (pl != null && ((pl.last === state && pl.next == x) || (pl.pending != null && pl.before === state && pl.m == x))) return
        val (decision, s1, why) = decide(state, x)
        val p = RunPlan(this, s1, decision, x)
        p.before = state
        p.pending = why to s1
        plan = p
        try {
            p.extend(x + Pos.EPS, Pos(x.x + ahead))
        } catch (e: RunChanges) { // the chip will refuse there when it gets there
        }
    }

    // ------------------------------------------------------------ checked ahead elsewhere

    private fun takeReady(state: LineState, x: Pos): Boolean {
        while (ready.isNotEmpty() && ready[0].m < x) ready.removeAt(0)
        if (ready.isEmpty() || ready[0].m != x || !sameState(ready[0].before!!, state)) return false
        val p = ready.removeAt(0)
        plan = p
        val (why, s1) = p.pending!!
        if (sameState(s1, state)) {
            p.state0 = state
            p.last = state
            p.pending = why to state
        }
        p.before = state
        return true
    }

    /** The run to check further ahead elsewhere: the last of [plan] and [ready]. */
    fun aheadTarget(): RunPlan? = ready.lastOrNull() ?: plan

    /** How far the chip's runs are known. */
    fun frontier(): Pos? = aheadTarget()?.checked

    /** With the run on [lo, p), is the decision at p the same? And the state with the run on [lo, p]. */
    fun same(state: LineState, decision: Decision, lo: Pos, p: Pos, pushing: Set<String>? = null): Pair<Boolean, LineState?> {
        return try {
            val before = if (lo < p) withRun(state, decision, Region.of(Interval.of(lo, p - Pos.EPS)), pushing) else state
            val (d, after, _) = decide(before, p)
            (d == decision) to after
        } catch (e: Conflict) {
            false to null
        } catch (e: Undetermined) {
            false to null
        } catch (e: IncompatiblePeriods) {
            false to null
        }
    }

    companion object {
        /** The kinds a decision pushes; null if the mode rules push nothing. */
        fun pushingOf(decision: Decision): Set<String>? = (decision.lastOrNull() as? Choice.Pushing)?.kinds

        /** Whether a piece of the pattern meets or touches what stays of a kind one of a's gaps counts after. */
        fun touches(a: AsEarlyAsPossible, st: LineState, p: Pos, pattern: Region, moving: Region): Boolean {
            val near = pattern.dilate(Interval.of(-Pos.EPS, Pos.EPS, true, true))
            for (g in a.gaps()) if (g.after != a.kind && (near and a.fixedOf(st, p, g.after)).isNotEmpty()) return true
            return false
        }

        /** The piece of a pushed kind the chip is in or right before: it moves with the chip. */
        fun moving(k: ChipPush, st: LineState, p: Pos): Region {
            val pres = st.line.presence(k.kind)
            val out = pres.on(p - 1 - firstEnd(k.region), p + 1).intervals.filter { it.lo <= p + Pos.EPS && it.hi + Pos.EPS >= p }
            return if (out.isNotEmpty()) Region.of(IntervalSet.of(out)) else Region.EMPTY
        }
    }
}

/** How many reaches a stretch looks ahead for where a decision can change. */
const val LOOK_AHEAD = 256

/**
 * A run compiled ahead: decided at m on [state0], the same decision on [m + ε, checked], checked one stretch at a
 * time. Each stretch keeps its checked states, and the line as an affine function of the chip's position when found.
 */
class RunPlan(val mr: ModeRules, var state0: LineState, val decision: Decision, val m: Pos) {
    val a0: Pos = m + Pos.EPS
    var checked: Pos? = null

    /** The decision differs right after [checked]. */
    var over = false
    var pieces: MutableList<Piece> = ArrayList()
    var queue: MutableList<Pos> = ArrayList()
    var top: Pos? = null
    var last: LineState = state0
    var next: Pos = a0
    var before: LineState? = null
    var pending: Pair<List<String>, LineState>? = null
    val pushing: Set<String>? = ModeRules.pushingOf(decision)

    class Piece(val a: Pos, val b: Pos, val line: AffineLine?, val states: Map<Pos, LineState>)

    /** The last position of [nxt, hi] the run reaches (null: not nxt), checking the stretches up to hi. */
    fun extend(nxt: Pos, hi: Pos): Pos? {
        pieces = pieces.filter { it.b >= nxt }.toMutableList()
        while (!over && (checked == null || checked!! < hi)) {
            checkpoint()
            stretch(hi)
        }
        val ch = checked
        if (ch == null || ch < nxt) return null
        return if (ch < hi) ch else hi
    }

    private fun stretch(hi: Pos) {
        val cur = checked?.let { it + Pos.EPS } ?: a0
        val tp = top
        if (queue.isEmpty() && (tp == null || tp < cur)) {
            val g = mr.g ?: (Rational.of(LOOK_AHEAD) * (if (mr.rs.reach.sign != 0) mr.rs.reach else Rational.ONE))
            top = Pos(maxOf(hi.x, cur.x) + g)
            queue = mr.candidates(state0, m, cur, top!!, pushing).toMutableList()
        }
        queue = queue.filter { it >= cur }.toMutableList()
        val a: Pos
        val b: Pos
        if (queue.isNotEmpty() && queue[0] == cur) {
            a = queue.removeAt(0)
            b = a
        } else {
            a = cur
            b = if (queue.isNotEmpty()) queue[0] - Pos.EPS else top!!
        }
        val reps = if (a == b) listOf(a) else listOf(a) + (if (a.x < b.x) listOf(Pos((a.x + b.x) / 2)) else emptyList()) + b
        val states = LinkedHashMap<Pos, LineState>()
        for ((i, p) in reps.withIndex()) {
            val (ok, st) = mr.same(state0, decision, a0, p, pushing)
            if (!ok) {
                if (i == 0) {
                    over = true
                    return
                }
                throw RunChanges(p)
            }
            states[p] = st!!
        }
        var line: AffineLine? = null
        if (reps.size == 3) {
            line = affine(states.getValue(reps[0]).line, states.getValue(reps[1]).line, reps[0], reps[1] - reps[0])
            if (line != null) {
                try {
                    if (!sameLine(line(b), states.getValue(b).line)) line = null
                } catch (e: NotAffine) {
                    line = null
                }
            }
        }
        pieces.add(Piece(a, b, line, states))
        checked = b
    }

    /** A copy that keeps its checked stretches (the run goes on without it). */
    fun frozen(): RunPlan {
        val out = RunPlan(mr, state0, decision, m)
        out.checked = checked
        out.over = over
        out.pieces = pieces.toMutableList()
        out.queue = queue.toMutableList()
        out.top = top
        out.last = last
        out.next = next
        out.before = before
        out.pending = pending
        return out
    }

    /** The state with the run on [m + ε, p], p checked. */
    fun stateAt(p: Pos): LineState = exact(p) ?: mr.withRun(state0, decision, Region.of(Interval.of(a0, p)), pushing)

    /** Where the formula of the line at p stops; null if there is none at p. */
    fun formulaUntil(p: Pos): Pos? {
        for (pc in pieces.toList()) if (pc.a <= p && p <= pc.b) return if (pc.line != null) pc.b else null
        return null
    }

    /** That state, where the run's formula gives it with nothing to solve; else null. */
    fun exact(p: Pos): LineState? {
        for (pc in pieces.toList()) {
            if (pc.a <= p && p <= pc.b) {
                pc.states[p]?.let { return it }
                val line = pc.line
                if (line != null) {
                    try {
                        var s0 = state0
                        if (!pushing.isNullOrEmpty()) s0 = LineState(mr.pushedBehind(s0.claims, pushing, m, p), s0.line, s0.history)
                        val c = mr.runClaims(s0, decision, Region.of(Interval.of(a0, p))) ?: if (pushing.isNullOrEmpty()) return state0 else s0.claims
                        val lp = line(p)
                        return LineState(mr.constrain(c, lp, p, state0.history, pushing = pushing), lp, state0.history)
                    } catch (e: NotAffine) {
                    }
                }
                break
            }
        }
        return null
    }
}

class NotAffine : Exception()

/** The end of the first piece of a pattern written from the chip (after 0). */
fun firstEnd(r: Region): Pos {
    val ivs = r.on(Pos.ZERO, r.hi + r.pr * 2 + 1).intervals
    return if (ivs.isNotEmpty()) ivs[0].hi else Pos.ZERO
}

/** The line at p: each region an [AffineRegion] of p - p1. */
class AffineLine(val parts: Map<String, Map<Rational, AffineRegion>>, val p1: Pos) {
    operator fun invoke(p: Pos): Line = Line(parts.mapValues { (_, d) -> d.mapValues { it.value(p - p1) } })
}

/** The line at p as a function of p, from l1 at p1 and l2 at p1 + s; null if not so. */
fun affine(l1: Line, l2: Line, p1: Pos, s: Pos): AffineLine? {
    if (l1.vals.keys != l2.vals.keys || l1.vals.any { (k, d) -> d.keys != l2.vals.getValue(k).keys }) return null
    val parts = LinkedHashMap<String, Map<Rational, AffineRegion>>()
    for ((k, d) in l1.vals) {
        val m = LinkedHashMap<Rational, AffineRegion>()
        for ((v, r1) in d) m[v] = affineRegion(r1, l2.vals.getValue(k).getValue(v), s) ?: return null
        parts[k] = m
    }
    return AffineLine(parts, p1)
}

/** r(d) with r(0) = r1 and r(s) = r2, each end fixed or moving by d; null if not so. */
fun affineRegion(r1: Region, r2: Region, s: Pos): AffineRegion? {
    val a: Pos
    val x: Pos
    val left: Region
    val tail: Region
    val moves: Boolean
    try {
        if (r1 == r2) return AffineRegion(r1, emptyList(), r1, false)
        var aa = if (r1.lo <= r2.lo) r1.lo else r2.lo
        var xx = if (r1.hi >= r2.hi) r1.hi else r2.hi
        if (r1.left.isNotEmpty() || r2.left.isNotEmpty()) aa -= if (r1.pl >= r2.pl) r1.pl else r2.pl
        if (r1.right.isNotEmpty() || r2.right.isNotEmpty()) xx += if (r1.pr >= r2.pr) r1.pr else r2.pr
        a = aa
        x = xx
        left = Region.of(Interval.of(Pos.NEG_INF, a, false, false))
        if ((r1 and left) != (r2 and left)) return null
        val right = Region.of(Interval.of(x, Pos.INF, true, false))
        tail = r1 and right
        moves = (r2 and right) != tail
        if (moves && (r2 and Region.of(Interval.of(x + s, Pos.INF, true, false))) != tail.shift(s)) return null
    } catch (e: IncompatiblePeriods) {
        return null
    }
    val i1 = r1.on(a, x).intervals
    val i2 = r2.on(a, if (moves) x + s else x).intervals
    if (i1.size != i2.size) return null
    val ends = ArrayList<AffineEnd>()
    for ((p, q) in i1.zip(i2)) {
        val lo = moved(p.lo, q.lo, s) ?: return null
        val hi = moved(p.hi, q.hi, s) ?: return null
        ends.add(AffineEnd(p.lo, lo, p.hi, hi))
    }
    return AffineRegion(r1 and left, ends, tail, moves)
}

class AffineEnd(val lo: Pos, val loMoves: Boolean, val hi: Pos, val hiMoves: Boolean)

/** r(d): the fixed part on the left, the intervals between (each end fixed or moving by d), and the right part. */
class AffineRegion(val fixed: Region, val ends: List<AffineEnd>, val tail: Region, val moves: Boolean) {
    operator fun invoke(d: Pos): Region {
        if (ends.isEmpty() && !moves) return tail
        val ivs = ends.map { e ->
            val x = if (e.loMoves) e.lo + d else e.lo
            val y = if (e.hiMoves) e.hi + d else e.hi
            if (!(x <= y)) throw NotAffine()
            Interval(x, y)
        }
        return Region.of(IntervalSet.of(ivs)) or fixed or (if (moves) tail.shift(d) else tail)
    }
}

/** True if the end moved by s, false if it stayed, null otherwise. */
private fun moved(e1: Pos, e2: Pos, s: Pos): Boolean? = when {
    e1 == e2 -> false
    e1.isFinite && e2.isFinite && e2 == e1 + s -> true
    else -> null
}

fun sameLine(l1: Line, l2: Line): Boolean {
    if (l1.vals.keys != l2.vals.keys) return false
    return try {
        l1.vals.all { (k, d) -> d.keys == l2.vals.getValue(k).keys && d.all { (v, r) -> r == l2.vals.getValue(k).getValue(v) } }
    } catch (e: IncompatiblePeriods) {
        false
    }
}

/** The same line and claims. */
fun sameState(a: LineState, b: LineState): Boolean {
    if (a === b) return true
    return try {
        sameLine(a.line, b.line) && a.claims.same(b.claims)
    } catch (e: IncompatiblePeriods) {
        false
    }
}

/** (kind, positive): the claims at x the edit removed, besides those of the kind it places. */
private fun gone(before: LineState, after: LineState, x: Pos, placed: String): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    for (k in (before.claims.pos.keys + before.claims.neg.keys).sorted()) {
        if (k == placed) continue
        if (before.claims.positive(k).contains(x) && !after.claims.positive(k).contains(x)) out.add(k to true)
        if (before.claims.negative(k).contains(x) && !after.claims.negative(k).contains(x)) out.add(k to false)
    }
    return out
}

private fun holds(o: Lit, s: LineState, x: Pos): Boolean = when {
    o.negated -> !s.line.presence(o.kind).contains(x)
    o.value != null -> s.line.vals[o.kind]?.get(o.value)?.contains(x) ?: false
    else -> s.line.presence(o.kind).contains(x)
}
