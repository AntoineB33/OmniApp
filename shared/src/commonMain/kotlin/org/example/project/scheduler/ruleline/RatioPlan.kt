package org.example.project.scheduler.ruleline


/**
 * The best line of the presence ratio targets and minimum lengths, found at once when the definitions are simple
 * enough — a port of `C:\code\line_manager\scripts\ratio_plan.py` (its module text states when and how).
 *
 * The rules only exclusions "B → no A"; no constraint; presence ratios and minimum lengths. The kinds that exclude
 * each other form cliques; a kind alone is at its ratio everywhere after the chip; a group takes its turns in a cycle
 * of period P = max L_K / r_K, the largest target first (at random between equal targets, once per generator, by its
 * seed), a kind at the chip last. With no minimum length there is no best line ([NoBestScript]).
 */
class NoBestScript(message: String) : IllegalArgumentException(message)

/** The pairs of kinds of a rule "B → no A" or "B → no A & no C", else null. */
internal fun exclusionsOf(r: Rule): Set<Set<String>>? {
    val lhs = r.lhs
    if (r.isModeRule || r.internal.isNotEmpty() || !(lhs is Atom && lhs.op == "present")) return null
    val out = LinkedHashSet<Set<String>>()
    for (it in r.rhs) {
        if (it.options.size != 1) return null
        val o = it.options[0]
        if (!(o.negated && o.anchor == "p" && o.value == null && o.where.isPoint && o.where.lo == Pos.ZERO)) return null
        out.add(setOf(lhs.kind, o.kind))
    }
    return out.ifEmpty { null }
}

/** The pairs of two kinds that exclude each other, in the rules that only do that. */
internal fun pairsOf(rules: List<Rule>): Set<Set<String>> = rules.flatMap { exclusionsOf(it).orEmpty() }.filter { it.size == 2 }.toSet()

/** The pairs of kinds that exclude each other, if the rules are only "B → no A"; null otherwise. */
fun exclusions(rs: RuleSet): Set<Set<String>>? {
    if (rs.modeRules.isNotEmpty() || rs.percent.isNotEmpty() || rs.rules.any { exclusionsOf(it) == null }) return null
    return pairsOf(rs.rules)
}

/** Raises [NoBestScript] when two kinds with a target exclude each other and neither has a minimum length. */
fun checkBestExists(rs: RuleSet, problems: List<Pair<OptProblem, Rational>>) {
    val (targets, lengths) = targetsOf(problems) ?: return
    val kinds = targets.filter { it.value.sign > 0 }.keys
    val free = pairsOf(rs.rules).filter { p -> kinds.containsAll(p) && p.none { (lengths[it] ?: Rational.ZERO).sign != 0 } }
        .map { it.sorted() }.toSet().sortedWith { a, b -> if (a[0] != b[0]) a[0].compareTo(b[0]) else a[1].compareTo(b[1]) }
    if (free.isNotEmpty()) {
        val (a, b) = free[0]
        throw NoBestScript("$a, $b exclude each other and neither has a minimum length: every alternation of them is beaten by a faster one, so no script is the best")
    }
}

internal fun targetsOf(problems: List<Pair<OptProblem, Rational>>): Pair<Map<String, Rational>, Map<String, Rational>>? {
    val targets = LinkedHashMap<String, Rational>()
    val lengths = LinkedHashMap<String, Rational>()
    for ((p, _) in problems) {
        when (p) {
            is PresenceRatioProblem -> for ((k, r) in p.targets) {
                val had = targets.getOrPut(k) { r }
                if (had != r) return null
            }
            is MinimumLengthProblem -> for ((k, l) in p.lengths) lengths[k] = maxOf(lengths[k] ?: Rational.ZERO, l)
            else -> return null
        }
    }
    return targets to lengths
}

/** Where the element rules among [rules] forbid [kind] (problems.forbidden over a list of rules). */
internal fun forbiddenByRules(rules: List<Rule>, kind: String, vals: Vals): Region {
    var out = Region.EMPTY
    for (r in rules) for (it in r.rhs) {
        val o = it.options[0]
        if (!it.isOr && o.negated && o.kind == kind) out = out or target(o, lhsRegion(r.lhs, vals))
    }
    return out
}

/**
 * The kinds of [order] taking their turns, one after the other, in the stretches of [free] from [start], each for its
 * length (less at the end of a stretch if that is still its minimum), a kind's turn waiting where [forbid] says it
 * alone cannot be. {kind: region}; null if [free] repeats with no position outside it, or what is forbidden has no end.
 */
fun turns(free: Region, start: Rational, order: List<String>, lengths: Map<String, Rational>, minimum: Map<String, Rational>, forbid: Map<String, Region>? = null): Map<String, Region>? {
    val fb = forbid.orEmpty().filter { it.value.isNotEmpty() }
    val out = turnsOf(free, start, order, lengths, minimum, fb) ?: return null
    val src = "turns(..., ${pyNum(start)}, $order, ...)"
    return out.mapValues { (k, r) -> Built(r, "$src['$k']") }
}

private fun turnsOf(free0: Region, start: Rational, order: List<String>, lengths: Map<String, Rational>, minimum: Map<String, Rational>, forbid: Map<String, Region>): Map<String, Region>? {
    val n = order.size
    val pieces = order.associateWith { ArrayList<Interval>() }
    if (forbid.values.any { !it.isBounded() }) return null
    val fend = (listOf(Pos(start)) + forbid.values.map { it.bounded().sup() }).max()

    fun lay(ivs: List<Interval>, i0: Int, until: Pos? = null): Pair<Int, Pos?> {
        var i = i0
        for (iv in ivs) {
            var x = iv.lo
            val end = iv.hi
            var closed = true
            while (true) {
                if (until != null && x >= until && !closed) return i to x
                val k = order[i.mod(n)]
                val length = lengths.getValue(k)
                var room = if (end != Pos.INF) (end - x).x else length + Rational.ONE
                val f = forbid[k]
                var ahead = when {
                    f != null && end != Pos.INF -> f.on(x, end + 1).intervals
                    f != null -> f.on(x, f.bounded().sup() + 1).intervals
                    else -> emptyList()
                }
                ahead = ahead.filter { it.hi >= x }
                if (ahead.isNotEmpty() && ahead[0].lo <= x) { // k cannot be here: the kind before it goes on
                    val y = if (end != Pos.INF) min(ahead[0].hi, end) else ahead[0].hi
                    val before = order[(i - 1).mod(n)]
                    pieces.getValue(before).add(Interval.of(x, y, closed, true))
                    x = y
                    closed = false
                    if (y == end) break
                    continue
                }
                val stop: Pos
                if (ahead.isNotEmpty() && (ahead[0].lo - x).x < room) {
                    room = (ahead[0].lo - x).x
                    stop = ahead[0].lo - Pos.EPS
                } else {
                    stop = end
                }
                if (room < length && !(room.sign > 0 && room >= (minimum[k] ?: Rational.ZERO))) {
                    if (ahead.isNotEmpty() && stop != end) { // too short before what forbids it: the one before goes on
                        val before = order[(i - 1).mod(n)]
                        pieces.getValue(before).add(Interval.of(x, stop, closed, true))
                        x = stop
                        closed = false
                        continue
                    }
                    break // too short for its turn: nothing there
                }
                val b = if (room <= length) stop else x + length
                pieces.getValue(k).add(Interval.of(x, b, closed, room > length))
                x = b
                i++
                closed = false
                if (room <= length && b == end) break
            }
        }
        return i to null
    }

    val free = free0 and Region.of(Interval.of(Pos(start), Pos.INF, false, false))
    val pr = free.pr
    val right = free.right
    if (right.isEmpty || right == IntervalSet.of(Interval.of(Pos.ZERO, Pos(pr.x), true, false))) {
        val hi = max(free.hi, Pos(start))
        val ivs = if (hi > Pos(start)) free.on(Pos(start), hi).intervals.toMutableList() else ArrayList()
        var x0: Pos? = null
        if (right.isNotEmpty()) {
            x0 = if (ivs.isNotEmpty() && ivs.last().hi + Pos.EPS >= hi) ivs.removeAt(ivs.size - 1).lo else hi
        }
        var (i, _) = lay(ivs, 0)
        if (x0 != null && x0 < fend) {
            val (i2, x2) = lay(listOf(Interval.of(x0, Pos.INF, true, false)), i, until = fend)
            i = i2
            x0 = x2
        }
        val out = LinkedHashMap<String, Region>()
        for ((k, v) in pieces) out[k] = Region.of(IntervalSet.of(v))
        if (x0 != null) {
            val p = lengths.values.fold(Rational.ZERO) { a, b -> a + b }
            var at = Rational.ZERO
            for (j in 0 until n) {
                val k = order[(i + j).mod(n)]
                out[k] = out.getValue(k) or cycleOf(x0.x, p, at, at + lengths.getValue(k))
                at += lengths.getValue(k)
            }
        }
        return out
    }
    val gaps = IntervalSet.of(Interval.of(Pos.ZERO, Pos(pr.x), true, false)) - right
    if (gaps.isEmpty) return null
    var q = free.hi + gaps.intervals[0].lo
    while (q < Pos(start)) q += pr
    var (i, _) = lay(free.on(Pos(start), q).intervals, 0)
    var j = 0
    while (q + pr * j < fend) {
        i = lay(free.on(q + pr * j, q + pr * (j + 1)).intervals, i).first
        j++
    }
    val seen = HashMap<Int, Int>()
    while (i.mod(n) !in seen) {
        seen[i.mod(n)] = j
        i = lay(free.on(q + pr * j, q + pr * (j + 1)).intervals, i).first
        j++
    }
    val j1 = seen.getValue(i.mod(n))
    val lo = q + pr * j1
    val hi = q + pr * j
    val out = LinkedHashMap<String, Region>()
    for (k in order) {
        val ivs = IntervalSet.of(pieces.getValue(k))
        val core = ivs and IntervalSet.of(Interval.of(Pos(start), lo, false, false))
        val tail = (ivs and IntervalSet.of(Interval.of(lo, hi, true, false))).shift(-lo)
        out[k] = Region.make(Pos(start), lo, core, Pos.ONE, IntervalSet.EMPTY, hi - lo, tail)
    }
    return out
}

/** (c + lo + nP, c + hi + nP] for every n >= 0 (the reference's `_cycle`, a plain region). */
internal fun cycleOf(c: Rational, p: Rational, lo: Rational, hi: Rational): Region {
    val pattern = if (hi < p) {
        IntervalSet.of(Interval.of(Pos(lo), Pos(hi), false, true))
    } else {
        IntervalSet.of(Interval.point(Pos.ZERO)) or IntervalSet.of(Interval.of(Pos(lo), Pos(p), false, false))
    }
    return Region.make(Pos(c), Pos(c), IntervalSet.EMPTY, Pos.ONE, IntervalSet.EMPTY, Pos(p), pattern) and Region.of(Interval.of(Pos(c), Pos.INF, false, false))
}

/** The perfect script of these definitions, if they are simple enough ([ok]). */
class Plan(rs: RuleSet, problems: List<Pair<OptProblem, Rational>>, constraints: List<Constraint> = emptyList(), seed: Long = 0, strict: Boolean = true) {
    var ok = false
    val grouped = LinkedHashSet<String>()
    var targets: Map<String, Rational> = emptyMap()
    var lengths: Map<String, Rational> = emptyMap()
    var groups: List<Pair<List<String>, Rational?>> = emptyList()
    var kinds: List<String> = emptyList()
    var pairs: Set<Set<String>> = emptySet()

    init {
        build(rs, problems, constraints, seed, strict)
    }

    private fun build(rs: RuleSet, problems: List<Pair<OptProblem, Rational>>, constraints: List<Constraint>, seed: Long, strict: Boolean) {
        val found: Pair<Map<String, Rational>, Map<String, Rational>>?
        val ps: Set<Set<String>>?
        if (strict) {
            found = if (constraints.isNotEmpty()) null else targetsOf(problems)
            ps = if (found != null) exclusions(rs) else null
        } else {
            found = targetsOf(problems)
            ps = pairsOf(rs.rules)
        }
        if (ps == null || found == null) return
        targets = found.first
        lengths = found.second
        val ks = targets.filter { it.value.sign > 0 }.keys.sorted()
        if (ks.isEmpty() || lengths.keys.any { it !in ks }) return
        val near = ks.associateWith { k -> ps.filter { k in it }.flatMap { it }.filter { it != k && it in ks }.toSet() }
        val gs = ArrayList<List<String>>()
        val seen = HashSet<String>()
        for (k in ks) {
            if (k in seen) continue
            val g = LinkedHashSet<String>()
            val todo = arrayListOf(k)
            while (todo.isNotEmpty()) {
                val a = todo.removeAt(todo.size - 1)
                if (g.add(a)) todo.addAll(near.getValue(a) - g)
            }
            seen.addAll(g)
            if (g.any { a -> near.getValue(a) != g - a }) return // not every two of them exclude each other
            gs.add(g.sorted())
        }
        val rng = PyRandom(seed)
        val out = ArrayList<Pair<List<String>, Rational?>>()
        val early = constraints.filterIsInstance<AsEarlyAsPossible>().map { it.kind }.toSet()
        for (g in gs) {
            if (g.size == 1) {
                out.add(g to null)
                continue
            }
            if (g.fold(Rational.ZERO) { a, k -> a + targets.getValue(k) } > Rational.ONE) return // the targets cannot all be met
            val p = g.maxOf { (lengths[it] ?: Rational.ZERO) / targets.getValue(it) }
            if (p.sign == 0) {
                throw NoBestScript("${g.joinToString(", ")} exclude each other and none has a minimum length: every alternation of them is beaten by a faster one, so no script is the best")
            }
            val drawn = rng.sample(g, g.size)
            out.add(drawn.sortedWith(compareBy<String>({ it !in early }, { -targets.getValue(it) })) to p)
            grouped.addAll(g)
        }
        groups = out
        kinds = targets.keys.sorted()
        pairs = ps
        ok = true
    }

    /** The edit of the cycles only (not strict); null if it cannot be made here. */
    fun groupOps(rules: RuleSet, state: LineState, chip: Pos, constraints: List<Constraint> = emptyList()): List<Op>? {
        val c = chip.x
        val after = Region.of(Interval.of(Pos(c), Pos.INF, false, false))
        val inside = pairs.filter { grouped.containsAll(it) }.toSet()
        val others = rules.element.filter { r -> !(exclusionsOf(r) ?: setOf(setOf<String>("\u0000"))).all { it in inside } }
        val ops = ArrayList<Op>()
        val no = LinkedHashMap<String, Region>()
        val theres = LinkedHashMap<String, Region>()
        val vals = state.line.vals.mapValues { (k, d) -> if (k in grouped) d.mapValues { it.value - after } else d }
        for (k in grouped.sorted()) {
            val there = state.line.presence(k) and after
            if ((there - state.claims.owned(k, Owner.GEN)).isNotEmpty()) return null
            var x = forbiddenByRules(others, k, vals) and after
            for (gap in constraints.filterIsInstance<MinimumGap>().filter { it.kind == k }) {
                val source = state.line.presence(gap.after) - (if (gap.after != k) there else after)
                x = x or (gap.apart(source, gap.pullsOf(state, Pos(c))) and after)
            }
            no[k] = x
            theres[k] = there
        }
        for ((g, p) in groups) {
            if (p == null) continue
            var common = no.getValue(g[0])
            for (k in g.drop(1)) common = common and no.getValue(k)
            val alone = g.associateWith { no.getValue(it) - common }
            if (common.isEmpty() && alone.values.all { it.isEmpty() }) {
                ops.addAll(cycleOps(state, c, g, p))
            } else {
                ops.addAll(laid(state, c, g, p, after - common, alone) ?: return null)
            }
        }
        val put = LinkedHashMap<String, Region>()
        for (o in ops) if (o.action == "put") put[o.kind] = (put[o.kind] ?: Region.EMPTY) or o.region
        val takes = theres.entries.sortedBy { it.key }.mapNotNull { (k, r) ->
            val left = r - (put[k] ?: Region.EMPTY)
            if (left.isNotEmpty()) Op.take(k, left) else null
        }
        return takes + ops
    }

    /** The kinds of g in their turn (the one at the chip last), the edit keeping its interval to its minimum, the start. */
    private fun orderOf(state: LineState, c: Rational, g: List<String>): Triple<List<String>, List<Op>, Rational> {
        val ops = ArrayList<Op>()
        var start = c
        val last = g.filter { state.line.presence(it).contains(Pos(c)) }
        for (k in last) {
            val l = lengths[k] ?: Rational.ZERO
            val rest = l - runAt(state.line.presence(k), c, l)
            if (rest.sign > 0) {
                ops.add(Op.put(k, Region.of(Interval.of(Pos(c), Pos(c + rest), false, true))))
                start = c + rest
            }
        }
        return Triple(g.filter { it !in last } + last, ops, start)
    }

    private fun laid(state: LineState, c: Rational, g: List<String>, p: Rational, free: Region, alone: Map<String, Region>?): List<Op>? {
        val (order, ops, start) = orderOf(state, c, g)
        val out = turns(free, start, order, order.associateWith { targets.getValue(it) * p }, order.associateWith { lengths[it] ?: Rational.ZERO }, alone) ?: return null
        return ops + out.filter { it.value.isNotEmpty() }.map { (k, r) -> Op.put(k, r) }
    }

    private fun cycleOps(state: LineState, c: Rational, g: List<String>, p: Rational): List<Op> {
        val (order, ops0, start) = orderOf(state, c, g)
        val ops = ops0.toMutableList()
        var at = Rational.ZERO
        for (k in order) {
            val length = targets.getValue(k) * p
            ops.add(Op.put(k, cycleRegion(start, p, at, at + length)))
            at += length
        }
        return ops
    }

    /** The edit of the perfect script from the chip; null if the line has target kinds after it the generator did not put. */
    fun ops(state: LineState, chip: Pos): List<Op>? {
        val c = chip.x
        val after = Region.of(Interval.of(Pos(c), Pos.INF, false, false))
        val ops = ArrayList<Op>()
        for (k in kinds) {
            val there = state.line.presence(k) and after
            if ((there - state.claims.owned(k, Owner.GEN)).isNotEmpty()) return null
            if (there.isNotEmpty()) ops.add(Op.take(k, after))
        }
        for ((g, p) in groups) {
            if (p == null) ops.add(Op.put(g[0], after, Rational.of(100) * targets.getValue(g[0]))) else ops.addAll(cycleOps(state, c, g, p))
        }
        return ops
    }
}

/** How long the interval of [presence] at c has been there at c, up to L. */
private fun runAt(presence: Region, c: Rational, l: Rational): Rational {
    if (l.sign <= 0) return Rational.ZERO
    val here = presence.on(Pos(c - l), Pos(c + Rational.ONE)).intervals.filter { it.contains(Pos(c)) }
    if (here.isEmpty()) return Rational.ZERO
    return c - here[0].lo.x
}

/** The best line of targets that nothing makes compete ([ok]). */
class Fill(problems: List<Pair<OptProblem, Rational>>) {
    var ok = false
    var targets: Map<String, Rational> = emptyMap()
    var lengths: Map<String, Rational> = emptyMap()
    var kinds: List<String> = emptyList()

    init {
        val found = targetsOf(problems)
        if (found != null) {
            targets = found.first
            lengths = found.second
            kinds = targets.filter { it.value.sign > 0 }.keys.sorted()
            ok = kinds.isNotEmpty() && lengths.keys.all { it in kinds }
        }
    }

    /** The edit on this line; null if there is none. */
    fun ops(rules: RuleSet, state: LineState, chip: Pos, window: Rational, skip: Set<String> = emptySet()): List<Op>? {
        val c = chip.x
        val after = Region.of(Interval.of(Pos(c), Pos.INF, false, false))
        val ops = ArrayList<Op>()
        for (k in kinds) {
            if (k in skip) continue
            val pres = state.line.presence(k)
            val no = forbiddenBy(rules, k, state.line.vals) and after
            if (targets.getValue(k) < Rational.ONE) {
                val there = pres and after
                if (no.isNotEmpty() || (there - state.claims.owned(k, Owner.GEN)).isNotEmpty()) return null
                if (there.isNotEmpty()) ops.add(Op.take(k, after))
                ops.add(Op.put(k, after, Rational.of(100) * targets.getValue(k)))
                continue
            }
            val free = after - pres - no
            if (free.isEmpty()) continue
            val l = lengths[k] ?: Rational.ZERO
            if (l.sign != 0) {
                for (iv in free.on(Pos(c), Pos(c + window)).intervals) {
                    val touches = iv.hi.x >= c + window || pres.contains(iv.lo) || pres.contains(iv.hi + Pos.EPS)
                    if (iv.length < l && !touches) return null
                }
            }
            ops.add(Op.put(k, free))
        }
        return ops
    }
}
