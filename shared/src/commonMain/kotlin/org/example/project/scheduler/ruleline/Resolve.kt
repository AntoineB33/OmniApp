package org.example.project.scheduler.ruleline


/**
 * Edits: the result of every edit is decided by fixed rules — a port of `C:\code\line_manager\scripts\resolve.py`
 * (its module text is the whole statement; `docs/scheduler/scheduler_requirements.md`: "Edits from the user win over
 * what is on the line. The fewest older elements are changed").
 *
 * The user's edits are all kept, in order, and the claims are computed from all of them at each edit, the newest
 * first: the newest applies whole (refused if it has no result alone), each older one applies except where it
 * contradicts the newer ones as they apply, directly or through the rules. Where older elements conflict, parts of
 * them are removed — the fewest elements over all the conflicts, the leftmost kept on a tie ([Resolution]). The
 * elements of the generated script and of the mode rules come last, below every edit of the user.
 */
class Refused(message: String) : Exception(message)

/** Combinations of one requirement: beyond, the edit is refused. */
const val MAX_OPTIONS = 4096

/** Rules followed back from one requirement; beyond: whole elements. */
const val MAX_DEPTH = 400

/** Times a window around an edit grows (each time twice as far). */
const val MAX_WINDOWS = 40

/** Steps of the search for the fewest elements lost: beyond, the edit is refused. */
const val MAX_SEARCH = 4096

/** Stretches of conflicts searched together: beyond, refused. */
const val MAX_SPLIT = 256

private val HUNDRED = Rational.of(100)

/** An edit of the user, as kept: its claims (owner user) and where it takes the "no K" elements off ([clear]). */
class Record(val claims: Claims, val clear: Map<String, Region>, override val footprint: Region) : Footprinted {
    companion object {
        fun of(ops: List<Op>): Record {
            var c = Claims()
            val clear = LinkedHashMap<String, Region>()
            for (op in ops.asReversed()) {
                val piece = Claims()
                val pclear = LinkedHashMap<String, Region>()
                when (op.action) {
                    "put" -> piece.addPos(op.kind, op.region, op.value, Owner.USER)
                    "put_no" -> piece.addNeg(op.kind, op.region, Owner.USER)
                    "take" -> piece.addTaken(op.kind, op.region, Owner.USER)
                    "take_no" -> pclear[op.kind] = op.region
                    else -> throw IllegalArgumentException(op.action)
                }
                val (p2, c2) = below(piece, pclear, c, clear)
                c = c.merged(p2)
                addTo(clear, c2)
            }
            return Record(c, clear, union(ops.map { it.region }))
        }
    }
}

/** A state from (kind, region, percentage) entries, one edit. */
fun initialState(rs: RuleSet, entries: List<Triple<String, Region, Rational>>, owner: String = Owner.USER): LineState {
    val ops = ArrayList<Op>()
    val seen = HashMap<String, Region>()
    for ((kind, r, pct) in entries) {
        val both = (seen[kind] ?: Region.EMPTY) and r
        if (both.isNotEmpty()) throw IllegalArgumentException("$kind is present several times on $both")
        seen[kind] = (seen[kind] ?: Region.EMPTY) or r
        ops.add(Op.put(kind, r, pct))
    }
    return applyEdit(rs, LineState.empty(), ops, owner)
}

/** The last few (rules, state, ops, after) of the generated script's edits. Process-wide, as in the reference. */
private val genMemo = ArrayList<Pair<Triple<RuleSet, LineState, List<Op>>, LineState>>()

/** The state after the edit; raises [Refused]. */
fun applyEdit(rs: RuleSet, state: LineState, ops: List<Op>, owner: String): LineState {
    if (owner == Owner.MODE) return applyOthers(rs, state, ops, owner)
    if (owner != Owner.USER) {
        for ((k, after) in genMemo) {
            val (r0, s0, o0) = k
            if (r0 === rs && s0 === state && o0.size == ops.size && o0.indices.all { o0[it] === ops[it] }) return after
        }
        val after = applyOthers(rs, state, ops, owner)
        if (!sameDict(userClaims(state.claims), userClaims(after.claims))) throw Refused("N2: would remove or change an element added by the user")
        genMemo.add(0, Triple(rs, state, ops.toList()) to after)
        while (genMemo.size > 4) genMemo.removeAt(genMemo.size - 1)
        return after
    }
    val rec = Record.of(ops)
    val kinds = ops.map { it.kind }.toSet()
    if ((kinds intersect rs.ruled).isEmpty() && rs.percent.isEmpty()) return apart(state, rec, kinds)
    alone(rs, rec.claims.problem())
    if (rs.local && state.claims.clean) {
        val claims = claimsOf(state, rec, kinds)
        val line = localLine(rs, state.line, claims, rec.footprint)
        if (line != null) return LineState(claims, line, more(state, rec))
    }
    val after = if (rs.pointwise) pointwise(rs, state, rec) else (near(rs, state, rec, kinds) ?: nearEdits(rs, state, rec, kinds))
    if (after != null) return after
    return rebuild(rs, more(state, rec), ownedByOthers(state.claims), state.line)
}

/** [after], a change of the mode rules on [before], with the user's edits less what the change took from them. */
fun modeWon(before: LineState, after: LineState): LineState {
    val now = userClaims(after.claims)
    val lost = LinkedHashMap<String, Region>()
    for ((key, r) in userClaims(before.claims)) {
        if (now[key] !== r) {
            val gone = if (key in now) r - now.getValue(key) else r
            if (gone.isNotEmpty()) lost[key.kind] = (lost[key.kind] ?: Region.EMPTY) or gone
        }
    }
    for ((k, d) in after.claims.pos) for ((key, r) in d) {
        val old = before.claims.pos[k]?.get(key)
        if (key.second == Owner.MODE && old !== r) lost[k] = (lost[k] ?: Region.EMPTY) or (r - (old ?: Region.EMPTY))
    }
    for ((k, d) in after.claims.neg) {
        val r = d[Owner.MODE] ?: continue
        val old = before.claims.neg[k]?.get(Owner.MODE)
        if (old !== r) lost[k] = (lost[k] ?: Region.EMPTY) or (r - (old ?: Region.EMPTY))
    }
    val kept = lost.filter { (k, r) -> r.isNotEmpty() && edits(before.history).touching(r).any { has(it, k, r) } }
    val history = if (kept.isNotEmpty()) cut(before.history, kept) else before.history
    return if (after.history === history) after else LineState(after.claims, after.line, history)
}

private fun edits(history: List<Record>): Edits<Record> = history as? Edits<Record> ?: Edits(history)

private fun has(rec: Record, k: String, r: Region): Boolean =
    ((rec.claims.positive(k) or rec.claims.negative(k) or rec.claims.off(k)) and r).isNotEmpty()

/** The edits without any claim of these kinds on these regions. */
private fun cut(history: List<Record>, lost: Map<String, Region>): Edits<Record> = Edits(
    history.map { rec ->
        val c = rec.claims.copy()
        for ((k, r) in lost) {
            c.removePos(k, r)
            c.removeNeg(k, r)
            c.removeTaken(k, r)
        }
        Record(c, rec.clear, rec.footprint)
    },
)

/** The state when no position acts on another one: the edits are computed again on the positions of this one only. */
private fun pointwise(rs: RuleSet, state: LineState, rec: Record): LineState {
    val where = rec.footprint
    val part = rebuild(rs, clipped(more(state, rec), where), ownedByOthers(state.claims).on(where), state.line)
    val claims = outside(state.claims, where).merged(part.claims)
    claims.clean = part.claims.clean && (state.claims.clean || state.history.isEmpty())
    return LineState(claims, spliced(state.line, part.line, where), more(state, rec))
}

/** The edits of the state, then [rec]. */
private fun more(state: LineState, rec: Record): Edits<Record> = edits(state.history) + listOf(rec)

/** The edits that touch [where], on [where] only. */
private fun clipped(history: Edits<Record>, where: Region): List<Record> = history.touching(where).map { r ->
    val clear = LinkedHashMap<String, Region>()
    for ((k, v) in r.clear) {
        val x = v and where
        if (x.isNotEmpty()) clear[k] = x
    }
    Record(r.claims.on(where), clear, r.footprint and where)
}

/** The state when the rules have distances, solved on a window around the edit that grows (see the reference). */
private fun near(rs: RuleSet, state: LineState, rec: Record, kinds: Set<String>): LineState? {
    if (!rs.monotone || !(state.claims.clean || state.history.isEmpty())) return null
    val claims = claimsOf(state, rec, kinds)
    val line = nearLine(rs, state.claims, claims, state.line, rec.footprint) ?: return null
    claims.clean = true
    return LineState(claims, line, more(state, rec))
}

private fun nearLine(rs: RuleSet, before: Claims, claims: Claims, line: Line, where: Region, temp: Map<String, Region>? = null): Line? {
    if (where.left.isNotEmpty() || where.right.isNotEmpty() || where.core.isEmpty) return null
    val adds = onlyAdds(before.on(where), claims.on(where))
    if (!adds && !(rs.oneWay && neverStuck(rs, before, claims))) return null
    val solved = { w: Region ->
        try {
            solve(rs, claims.on(w).problem(temp.orEmpty().mapValues { it.value and w })) to null
        } catch (e: Conflict) {
            null
        } catch (e: Undetermined) {
            null
        } catch (e: IncompatiblePeriods) {
            null
        }
    }
    val found = grown(rs, line, where, solved, null, if (adds) null else Rational.ZERO) ?: return null
    return spliced(line, found.first.first, found.second)
}

/** As [near], for what cannot be read from the claims alone: the edits are computed again on the window. */
private fun nearEdits(rs: RuleSet, state: LineState, rec: Record, kinds: Set<String>): LineState? {
    val where = rec.footprint
    if (!rs.finite || where.left.isNotEmpty() || where.right.isNotEmpty() || where.core.isEmpty) return null
    if (!onlyAdds(state.claims.on(where), claimsOf(state, rec, kinds).on(where))) return null
    val history = more(state, rec)
    val others = ownedByOthers(state.claims)
    val computed = { w: Region ->
        val part = rebuild(rs, clipped(history, w), others.on(w), state.line)
        part.line to part.claims
    }
    val found = grown(rs, state.line, where, computed, state.claims) ?: return null
    val (lineAndPart, inner) = found
    val (line, part) = lineAndPart
    val claims = outside(state.claims, inner).merged(part!!.on(inner))
    claims.clean = (state.claims.clean || state.history.isEmpty()) && part.clean
    return LineState(claims, spliced(state.line, line, inner), history)
}

/**
 * What [compute] gives on a window around [where] that grows until it gives the old line (and claims) on a zone on
 * each side, with the stretch it can be kept on. Null if it has the whole line first, or goes left of [floor].
 */
private fun grown(
    rs: RuleSet, line: Line, where: Region, compute: (Region) -> Pair<Line, Claims?>?, claims: Claims? = null, floor: Rational? = null,
): Pair<Pair<Line, Claims?>, Region>? {
    val step = Rational.of(rs.order.size + 2) * maxOf(rs.reach, Rational.ONE)
    val ends = line.vals.values.flatMap { it.values }
    val first = ends.minOfOrNull { it.lo.x } ?: Rational.ZERO
    val last = ends.maxOfOrNull { it.hi.x } ?: Rational.ZERO
    var a = where.core.intervals.first().lo.x - step * 4
    var b = where.core.intervals.last().hi.x + step * 4
    var growA = step * 8
    var growB = step * 8
    repeat(MAX_WINDOWS) {
        if (floor != null && a < floor && first < floor) return null
        val out = compute(Region.of(Interval.closed(Pos(a), Pos(b)))) ?: return null
        val left = Region.of(Interval.closed(Pos(a + step), Pos(a + step * 2)))
        val right = Region.of(Interval.closed(Pos(b - step * 2), Pos(b - step)))
        fun same(zone: Region) = onLine(line, zone) == onLine(out.first, zone) && (claims == null || claims.on(zone).same(out.second!!.on(zone)))
        val sl = same(left)
        val sr = same(right)
        if (sl && sr) return out to Region.of(Interval.closed(Pos(a + step), Pos(b - step)))
        if ((!sl && a + step * 2 < first) || (!sr && b - step * 2 > last)) return null // it still differs past all there is
        if (!sl) {
            a -= growA
            growA *= 2
        }
        if (!sr) {
            b += growB
            growB *= 2
        }
    }
    return null
}

/** With these claims, an operand of each "or" can always be chosen ([RuleSet.oneWay]). */
private fun neverStuck(rs: RuleSet, before: Claims, after: Claims): Boolean {
    for (c in listOf(before, after)) {
        for ((k, d) in c.pos) if (k in rs.blocked || (k in rs.free && d.keys.any { it.first != HUNDRED })) return false
        if (c.neg.keys.any { it in rs.free } || c.taken.keys.any { it in rs.free }) return false
    }
    return true
}

/**
 * The state with the line on the left of x as a simple line, and nothing on the left of [gone], when the memory is
 * short: there what is on the line is all that is kept. The absent kinds are kept too.
 */
fun flattened(rs: RuleSet, state: LineState, x: Pos, gone: Pos? = null): LineState {
    val left = Region.of(Interval.of(Pos.NEG_INF, x, false, false))
    var claims = outside(state.claims, left)
    claims.clean = state.claims.clean
    val history = ArrayList<Record>()
    for (r in state.history) {
        val fp = r.footprint - left
        if (fp.isNotEmpty()) {
            val clear = LinkedHashMap<String, Region>()
            for ((k, v) in r.clear) {
                val y = v - left
                if (y.isNotEmpty()) clear[k] = y
            }
            history.add(Record(outside(r.claims, left), clear, fp))
        }
    }
    val first = state.line.vals.values.flatMap { d -> d.values.map { it.inf() } }.minOrNull() ?: x
    val known = when {
        first == Pos.NEG_INF -> left
        first < x -> Region.of(Interval.closedOpen(first, x))
        else -> Region.EMPTY
    }
    for ((k, d) in state.line.vals) for ((v, reg) in d) {
        val y = reg and left
        if (y.isNotEmpty()) claims.addPos(k, y, v, Owner.FLAT)
    }
    for (k in rs.ruled.sorted()) {
        val absent = known - state.line.presence(k)
        if (absent.isNotEmpty()) claims.addNeg(k, absent, Owner.FLAT)
    }
    var line = state.line
    if (gone != null) {
        val off = Region.of(Interval.of(Pos.NEG_INF, gone, false, false))
        claims = outside(claims, off)
        claims.clean = state.claims.clean
        line = spliced(line, Line(emptyMap()), off)
    }
    return LineState(claims, line, Edits(history))
}

/** All that was claimed is still claimed, the same. */
private fun onlyAdds(before: Claims, after: Claims): Boolean {
    fun <K> ok(mine: Map<String, Map<K, Region>>, theirs: Map<String, Map<K, Region>>) =
        mine.all { (k, d) -> d.all { (key, r) -> (r - (theirs[k]?.get(key) ?: Region.EMPTY)).isEmpty() } }
    return ok(before.pos, after.pos) && ok(before.neg, after.neg) && ok(before.taken, after.taken)
}

/** The line on [where], to compare. */
internal fun onLine(line: Line, where: Region): Map<String, Map<Rational, Region>> {
    val out = LinkedHashMap<String, Map<Rational, Region>>()
    for ((k, d) in line.vals) {
        val kept = LinkedHashMap<Rational, Region>()
        for ((v, r) in d) {
            val x = r and where
            if (x.isNotEmpty()) kept[v] = x
        }
        if (kept.isNotEmpty()) out[k] = kept
    }
    return out
}

internal fun outside(c: Claims, where: Region): Claims {
    val out = Claims()
    for ((k, d) in c.pos) keep(out.pos, k, d, where)
    for ((k, d) in c.neg) keep(out.neg, k, d, where)
    for ((k, d) in c.taken) keep(out.taken, k, d, where)
    return out
}

/** The line with [part] on [where]. */
internal fun spliced(line: Line, part: Line, where: Region): Line {
    val vals = LinkedHashMap<String, Map<Rational, Region>>()
    for (k in (line.vals.keys + part.vals.keys)) {
        val d = LinkedHashMap<Rational, Region>()
        for ((v, r0) in line.vals[k].orEmpty()) {
            val r = r0 - where
            if (r.isNotEmpty()) d[v] = r
        }
        for ((v, r0) in part.vals[k].orEmpty()) {
            val r = r0 and where
            if (r.isNotEmpty()) d[v] = d[v]?.let { it or r } ?: r
        }
        if (d.isNotEmpty()) vals[k] = d
    }
    return Line(vals)
}

/** An edit of kinds no rule reads or places: only their intervals are computed, from the edits that have them. */
private fun apart(state: LineState, rec: Record, kinds: Set<String>): LineState {
    val claims = claimsOf(state, rec, kinds)
    val vals = LinkedHashMap(state.line.vals)
    for (k in kinds) {
        vals.remove(k)
        for ((key, r) in claims.pos[k].orEmpty()) {
            val d = LinkedHashMap(vals[k].orEmpty())
            d[key.first] = d[key.first]?.let { it or r } ?: r
            vals[k] = d
        }
    }
    return LineState(claims, Line(vals), more(state, rec))
}

/** The line when only the claims on [where] changed and a position only acts on itself; null if something must give way. */
private fun localLine(rs: RuleSet, line: Line, claims: Claims, where: Region, temp: Map<String, Region>? = null): Line? {
    val part = try {
        solve(rs, claims.on(where).problem(temp.orEmpty().mapValues { it.value and where }))
    } catch (e: Conflict) {
        return null
    } catch (e: Undetermined) {
        return null
    } catch (e: IncompatiblePeriods) {
        return null
    }
    return spliced(line, part, where)
}

/** The claims with the edit [rec], newest, of these kinds: the others stay as they are, and these outside the edit too. */
private fun claimsOf(state: LineState, rec: Record, kinds: Set<String>): Claims {
    val where = rec.footprint
    var above = Claims()
    val clear = LinkedHashMap<String, Region>()
    for (r in more(state, rec).touching(where).asReversed()) {
        if (kinds.any { k -> k in r.claims.pos || k in r.claims.neg || k in r.claims.taken || k in r.clear }) {
            val here = LinkedHashMap<String, Region>()
            for ((k, v) in r.clear) {
                val x = v and where
                if (k in kinds && x.isNotEmpty()) here[k] = x
            }
            val (c, cl) = below(only(r.claims, kinds).on(where), here, above, clear)
            addTo(clear, cl)
            above = above.merged(c)
        }
    }
    above = above.merged(below(only(ownedByOthers(state.claims), kinds).on(where), emptyMap(), above, clear).first)
    val claims = state.claims.copy()
    for (k in kinds) {
        fun <K> swap(mine: MutableMap<String, MutableMap<K, Region>>, theirs: Map<String, Map<K, Region>>) {
            val d = LinkedHashMap<String, MutableMap<K, Region>>()
            keep(d, k, mine[k].orEmpty(), where)
            for ((key, r) in theirs[k].orEmpty()) {
                val got = d.getOrPut(k) { LinkedHashMap() }
                got[key] = got[key]?.let { it or r } ?: r
            }
            mine.remove(k)
            d[k]?.let { mine[k] = it }
        }
        swap(claims.pos, above.pos)
        swap(claims.neg, above.neg)
        swap(claims.taken, above.taken)
    }
    return claims
}

private fun only(c: Claims, kinds: Set<String>): Claims {
    val out = Claims()
    out.pos = c.pos.filterKeys { it in kinds }.toMutableMap()
    out.neg = c.neg.filterKeys { it in kinds }.toMutableMap()
    out.taken = c.taken.filterKeys { it in kinds }.toMutableMap()
    return out
}

/**
 * The state of these edits of the user (oldest first): the newest first, each older one giving way to the newer ones,
 * then the elements of the other owners ([layer]), which give way to all of them.
 */
fun rebuild(rs: RuleSet, history: List<Record>, layer: Claims, oldLine: Line): LineState {
    noContradiction(rs, history, layer)?.let { return it }
    var above = Claims()
    val clear = LinkedHashMap<String, Region>()
    var line: Line? = null
    val footprint = history.lastOrNull()?.footprint ?: Region.EMPTY
    for (rec in history.asReversed()) {
        val (c, cl) = below(rec.claims, rec.clear, above, clear)
        addTo(clear, cl)
        if (!c.isEmpty()) {
            val (a, l) = giveWay(rs, above, c, lineOrEmpty(rs, c), footprint)
            above = a
            line = l
        }
    }
    val (c, _) = below(layer, emptyMap(), above, clear)
    if (!c.isEmpty()) {
        val (a, l) = giveWay(rs, above, c, oldLine, footprint)
        above = a
        line = l
    }
    return LineState(above, line ?: solve(rs, above.problem()), history)
}

/** The state when nothing has to give way through the rules, with one solve; null if something has to. */
private fun noContradiction(rs: RuleSet, history: List<Record>, layer: Claims): LineState? {
    if (!rs.monotone) return null
    var above = Claims()
    val clear = LinkedHashMap<String, Region>()
    for (rec in history.asReversed()) {
        val (c, cl) = below(rec.claims, rec.clear, above, clear)
        addTo(clear, cl)
        above = above.merged(c)
    }
    above = above.merged(below(layer, emptyMap(), above, clear).first)
    val line = try {
        solve(rs, above.problem())
    } catch (e: Conflict) {
        return null
    } catch (e: Undetermined) {
        return null
    } catch (e: IncompatiblePeriods) {
        return null
    }
    above.clean = true
    return LineState(above, line, history)
}

/** What applies of the claims c and the take_no [clear] under newer ones, by direct contradiction. */
internal fun below(c: Claims, clear: Map<String, Region>, above: Claims, aboveClear: Map<String, Region>): Pair<Claims, Map<String, Region>> {
    val out = Claims()
    for ((k, d) in c.pos) keep(out.pos, k, d, above.positive(k) or above.off(k) or above.negative(k))
    for ((k, d) in c.taken) keep(out.taken, k, d, above.positive(k) or above.off(k))
    for ((k, d) in c.neg) keep(out.neg, k, d, above.negative(k) or (aboveClear[k] ?: Region.EMPTY) or above.positive(k))
    val outClear = LinkedHashMap<String, Region>()
    for ((k, r0) in clear) {
        val r = r0 - (above.negative(k) or (aboveClear[k] ?: Region.EMPTY))
        if (r.isNotEmpty()) outClear[k] = r
    }
    return out to outClear
}

private fun <K> keep(out: MutableMap<String, MutableMap<K, Region>>, k: String, d: Map<K, Region>, block: Region) {
    for ((key, r0) in d) {
        val r = r0 - block
        if (r.isNotEmpty()) out.getOrPut(k) { LinkedHashMap() }[key] = r
    }
}

private fun addTo(acc: MutableMap<String, Region>, more: Map<String, Region>) {
    for ((k, r) in more) acc[k] = (acc[k] ?: Region.EMPTY) or r
}

/** The claims [above] with what of [older] does not contradict them through the rules. */
private fun giveWay(rs: RuleSet, above: Claims, older: Claims, oldLine: Line, footprint: Region): Pair<Claims, Line> {
    val c1 = above.merged(older)
    try {
        return c1 to solve(rs, c1.problem())
    } catch (e: Conflict) {
    } catch (e: Undetermined) {
    } catch (e: IncompatiblePeriods) {
    }
    val mandPos = above.pos.keys.associateWith { above.byValue(it) }
    val mandNeg = above.neg.keys.associateWith { above.negative(it) }
    val mandOff = above.taken.keys.associateWith { above.off(it) }
    val s = Resolution(rs, oldLine, c1, mandPos, mandNeg, emptyMap(), footprint, mandOff).run()
    return s.claims to s.line
}

/** The line of an edit alone; raises [Refused] if it has none (the only reason to refuse an edit). */
private fun alone(rs: RuleSet, prob: Problem): Line = try {
    solve(rs, prob)
} catch (e: Conflict) {
    throw Refused("the edit contradicts the rules: ${e.message}")
} catch (e: Undetermined) {
    throw Refused("the rules do not decide the result of the edit: ${e.message}")
} catch (e: IncompatiblePeriods) {
    throw Refused("two results of the edit repeat with periods that cannot be compared: ${e.message}")
}

private fun lineOrEmpty(rs: RuleSet, c: Claims): Line = try {
    solve(rs, c.problem())
} catch (e: Conflict) {
    Line(emptyMap())
} catch (e: Undetermined) {
    Line(emptyMap())
} catch (e: IncompatiblePeriods) {
    Line(emptyMap())
}

/** The claims that are not the user's. */
fun ownedByOthers(c: Claims): Claims {
    val out = Claims()
    for ((k, d) in c.pos) keep(out.pos, k, d.filterKeys { it.second != Owner.USER }, Region.EMPTY)
    for ((k, d) in c.neg) keep(out.neg, k, d.filterKeys { it != Owner.USER }, Region.EMPTY)
    for ((k, d) in c.taken) keep(out.taken, k, d.filterKeys { it != Owner.USER }, Region.EMPTY)
    return out
}

/** An edit of the generated script or of a mode rule, on the claims as they are. */
private fun applyOthers(rs: RuleSet, state: LineState, ops: List<Op>, owner: String): LineState {
    val c1 = state.claims.copy()
    val mandPos = LinkedHashMap<String, MutableMap<Rational, Region>>()
    val mandNeg = LinkedHashMap<String, Region>()
    val temp = LinkedHashMap<String, Region>()
    fun cutPos(kind: String, region: Region) {
        val d = mandPos[kind] ?: return
        for (v in d.keys.toList()) d[v] = d.getValue(v) - region
    }
    for (op in ops) { // (a later operation of the edit wins where an earlier one acts too)
        when (op.action) {
            "put" -> {
                c1.addPos(op.kind, op.region, op.value, owner)
                cutPos(op.kind, op.region)
                val d = mandPos.getOrPut(op.kind) { LinkedHashMap() }
                d[op.value] = (d[op.value] ?: Region.EMPTY) or op.region
                mandNeg[op.kind] = (mandNeg[op.kind] ?: Region.EMPTY) - op.region
                temp[op.kind]?.let { temp[op.kind] = it - op.region } // (taken off before: put again here)
            }
            "put_no" -> {
                c1.addNeg(op.kind, op.region, owner)
                mandNeg[op.kind] = (mandNeg[op.kind] ?: Region.EMPTY) or op.region
                cutPos(op.kind, op.region)
            }
            "take" -> {
                c1.removePos(op.kind, op.region)
                temp[op.kind] = (temp[op.kind] ?: Region.EMPTY) or op.region
                cutPos(op.kind, op.region) // (put before: taken off here)
            }
            "take_no" -> c1.removeNeg(op.kind, op.region)
            else -> throw IllegalArgumentException(op.action)
        }
    }
    val ownNeg = (mandNeg.keys + temp.keys).associateWith { (mandNeg[it] ?: Region.EMPTY) or (temp[it] ?: Region.EMPTY) }
    alone(rs, Problem(mandPos, ownNeg))
    val footprint = union(ops.map { it.region })
    if (rs.local) {
        localLine(rs, state.line, c1, footprint, temp)?.let { return LineState(c1, it, state.history) }
    } else if (rs.monotone) {
        nearLine(rs, state.claims, c1, state.line, footprint, temp)?.let { return LineState(c1, it, state.history) }
    }
    try {
        return LineState(c1, solve(rs, c1.problem(temp)), state.history)
    } catch (e: Conflict) {
    } catch (e: Undetermined) {
    } catch (e: IncompatiblePeriods) {
    } // with the line, no result: older elements give way
    val after = Resolution(rs, state.line, c1, mandPos, mandNeg, temp, footprint).run()
    return LineState(after.claims, after.line, state.history)
}

// ---------------------------------------------------------------------------------

/** An older element: "K", "no K" or "off K", a largest interval of a claim, with its percentage. */
private class Older(val name: String, val region: Region, val value: Rational?)

private fun olderElements(c1: Claims, mandPos: Map<String, Map<Rational, Region>>, mandNeg: Map<String, Region>, mandOff: Map<String, Region>): List<Older> {
    val out = ArrayList<Older>()
    for ((k, d) in c1.pos) {
        val new = union(mandPos[k].orEmpty().values)
        for ((key, reg) in d) for (e in (reg - new).components()) out.add(Older(k, e, key.first))
    }
    for ((k, d) in c1.neg) for (reg in d.values) for (e in (reg - (mandNeg[k] ?: Region.EMPTY)).components()) out.add(Older("no $k", e, null))
    for ((k, d) in c1.taken) for (reg in d.values) for (e in (reg - (mandOff[k] ?: Region.EMPTY)).components()) out.add(Older("off $k", e, null))
    return out
}

private fun kindOfName(name: String): String = name.removePrefix("no ").removePrefix("off ")

/** One requirement behind a conflict. */
private data class Side(val tag: String, val i: Int = -1, val lit: Lit? = null, val kind: String = "", val v: Rational? = null)

private class Conflicting(val where: Region, val a: Side, val b: Side)

/** A combination: (element index, part) pairs, one per index after [norm]. */
private typealias Combo = Set<Pair<Int, Region>>

/**
 * The line of some claims as far as region algebra computes it (the kinds no "or" changes), with each conflict and the
 * two requirements behind it.
 */
private class Lines(val rs: RuleSet, c: Claims, temp: Map<String, Region>) {
    val kindsIn: Set<String> = if (rs.orItems.isNotEmpty()) rs.choiceDependent else emptySet()
    val static: List<Int> = rs.element.indices.filter { (rs.element[it].lhs.kinds().toSet() intersect kindsIn).isEmpty() }
    private val metMap = HashMap<Int, Region>()
    val conflicts = ArrayList<Conflicting>()
    val vals: Map<String, Map<Rational, Region>>

    init {
        val prob = c.problem(temp)
        val out = kindsOf(rs, prob).filter { it !in kindsIn }
        val lits = out.flatMap { placing(it) }
        vals = close(rs, out, lits, prob.pos, emptyMap(), emptyMap(), check = false, met = metMap)
        for (k in out) {
            val claims = prob.pos[k].orEmpty()
            val srcs = placing(k).map { (i, o) -> Triple(i, o, target(o, met(i))) }
            for ((n, s) in srcs.withIndex()) {
                val (i, o, t) = s
                if (o.value == null) continue
                for ((v, reg) in claims) if (v != o.value) add(t and reg, Side("rule", i, o), Side("claim", kind = k, v = v))
                for ((j, p, u) in srcs.subList(n + 1, srcs.size)) {
                    if (p.value != null && p.value != o.value) add(t and u, Side("rule", i, o), Side("rule", j, p))
                }
            }
        }
        val pres = vals.mapValues { union(it.value.values) }
        for (k in (c.neg.keys + temp.keys + c.taken.keys).sorted()) {
            if (k in kindsIn) continue
            val p = pres[k] ?: Region.EMPTY
            add(p and c.negative(k), Side("no", kind = k), Side("present", kind = k))
            add(p and c.off(k), Side("off", kind = k), Side("present", kind = k))
            add(p and (temp[k] ?: Region.EMPTY), Side("taken", kind = k), Side("present", kind = k))
        }
        for (i in static) for (it in rs.element[i].rhs) {
            val o = it.options[0]
            if (!it.isOr && o.negated && o.kind !in kindsIn) add((pres[o.kind] ?: Region.EMPTY) and target(o, met(i)), Side("rule", i, o), Side("present", kind = o.kind))
        }
    }

    private fun add(where: Region, a: Side, b: Side) {
        if (where.isNotEmpty()) conflicts.add(Conflicting(where, a, b))
    }

    /** Where the left side of element rule i is met. */
    fun met(i: Int): Region = metMap.getOrPut(i) { lhsRegion(rs.element[i].lhs, vals) }

    /** Where the left side of rule i must stop being met so that its literal o no longer applies on [where]. */
    fun stop(i: Int, o: Lit, where: Region): Region {
        val m = met(i)
        val reach = m and where.dilate(-o.where)
        if (o.anchor == "p") return reach
        val edges = m.leftEdges()
        var out = union(reach.components().filter { (it and edges).isNotEmpty() })
        if (o.where.hi == Pos.INF) out = out or union(m.components().filter { it.inf() == Pos.NEG_INF })
        return out
    }

    /** (i, literal): the rules that place K present, always (no "or"). */
    fun placing(k: String): List<Pair<Int, Lit>> = static.flatMap { i ->
        rs.element[i].rhs.filter { !it.isOr && !it.options[0].negated && it.options[0].kind == k }.map { i to it.options[0] }
    }

    fun line(): Line = Line(vals)
}

/** Remove parts of older elements, one conflict after the other, so that the claims are consistent again. */
private class Resolution(
    val rs: RuleSet,
    val oldLine: Line,
    val c1: Claims,
    val mandPos: Map<String, Map<Rational, Region>>,
    val mandNeg: Map<String, Region>,
    val temp: Map<String, Region>,
    footprint: Region,
    val mandOff: Map<String, Region> = emptyMap(),
) {
    val older = olderElements(c1, mandPos, mandNeg, mandOff)
    val near: Region
    val maxSteps = 4 * older.size + 16
    private val lines = HashMap<Map<Int, Region>, Line?>()
    var steps = 0

    init {
        val r = Rational.of(rs.order.size + 1) * rs.reach + Rational.ONE
        near = footprint.dilate(Interval.closed(Pos(-r), Pos(r)))
    }

    fun tick() {
        steps++
        if (steps > MAX_SEARCH) throw Refused("it takes too long to find the fewest elements to change")
    }

    fun run(): LineState {
        var removed: Map<Int, Region> = emptyMap()
        var n = 0
        while (!consistent(removed)) {
            var step: Combo? = null
            if (n < maxSteps) {
                step = try {
                    val ls = Lines(rs, claims(removed), temp)
                    if (ls.conflicts.isNotEmpty()) search(ls, removed) else null
                } catch (e: IncompatiblePeriods) {
                    null
                } catch (e: Undetermined) {
                    null
                }
            }
            if (step.isNullOrEmpty()) {
                removed = fallback(removed)
                break
            }
            removed = merge(removed, step)
            n++
        }
        consistent(removed)
        return LineState(claims(removed), lines.getValue(removed)!!)
    }

    fun claims(removed: Map<Int, Region>): Claims = without(c1, removed.map { (i, reg) -> older[i].name to reg })

    fun consistent(removed: Map<Int, Region>): Boolean {
        if (removed !in lines) {
            lines[removed] = try {
                solve(rs, claims(removed).problem(temp))
            } catch (e: Conflict) {
                null
            } catch (e: Undetermined) {
                null
            } catch (e: IncompatiblePeriods) {
                null
            }
        }
        return lines[removed] != null
    }

    /** The parts on [where] of the older elements of this name still there. */
    fun pieces(removed: Map<Int, Region>, name: String, where: Region, value: Rational? = null): Combo {
        val idx = older.indices.filter { older[it].name == name && (value == null || older[it].value == value) }
        if (idx.isEmpty()) return emptySet()
        val meets = Meets(where, idx.map { older[it].region })
        val out = LinkedHashSet<Pair<Int, Region>>()
        for (i in idx) {
            if (meets(older[i].region)) {
                val part = (older[i].region and where) - (removed[i] ?: Region.EMPTY)
                if (part.isNotEmpty()) out.add(i to part)
            }
        }
        return out
    }

    // ------------------------------------------------------------ one conflict

    /** The combinations that resolve this conflict: (combination, lines after it, what is lost after it). */
    fun works(ls: Lines, conflict: Conflicting, removed: Map<Int, Region>): List<Triple<Combo, Lines, Set<Any>>> {
        val x = conflict.where
        val opts = LinkedHashSet<Combo>()
        opts.addAll(giveWay(ls, conflict.a, x, removed))
        opts.addAll(giveWay(ls, conflict.b, x, removed))
        opts.remove(emptySet<Pair<Int, Region>>())
        val out = ArrayList<Triple<Combo, Lines, Set<Any>>>()
        for (opt in cap(opts)) {
            if (out.any { within(it.first, opt) }) continue // only what is necessary
            tick()
            val after = try {
                Lines(rs, claims(merge(removed, opt)), temp)
            } catch (e: IncompatiblePeriods) {
                continue
            } catch (e: Undetermined) {
                continue
            }
            if (after.conflicts.any { (it.where and x).isNotEmpty() && setOf(it.a, it.b) == setOf(conflict.a, conflict.b) }) continue
            out.add(Triple(opt, after, lost(after, merge(removed, opt))))
        }
        return out
    }

    /** The elements lost: of the line, and the "no K" and "off K" that lose a part. */
    fun lost(ls: Lines, removed: Map<Int, Region>): Set<Any> =
        lostOnes(oldLine, ls.line()) + removed.keys.filter { kindOfName(older[it].name) != older[it].name }

    /** a, b = (the elements lost, removed). */
    fun better(a: Pair<Set<Any>, Map<Int, Region>>, b: Pair<Set<Any>, Map<Int, Region>>): Boolean {
        fun names(removed: Map<Int, Region>) = dropped(removed.map { (i, reg) -> older[i].name to reg })
        return betterOf(a.first.size to names(a.second), b.first.size to names(b.second), rs)
    }

    /** The removal that resolves the conflicts of these lines with the fewest elements lost, the leftmost kept on a tie. */
    fun search(ls: Lines, removed: Map<Int, Region>): Combo? {
        val todo = stretches(ls.conflicts).sortedBy { it.where.inf() }
        if (todo.size > MAX_SPLIT) throw Refused("too many conflicts to find the fewest elements to change")
        val base = lost(ls, removed)
        val found = todo.map { it to works(ls, it, removed) }.filter { it.second.isNotEmpty() }
        var out = removed
        for (group in groups(found, base)) out = bestOf(group, out, base)
        return if (out !== removed) out.entries.map { it.key to it.value }.toSet() else null
    }

    /** [removed] with the removal that resolves these conflicts with the fewest lost. */
    fun bestOf(group: List<Pair<Conflicting, List<Triple<Combo, Lines, Set<Any>>>>>, removed: Map<Int, Region>, base: Set<Any>): Map<Int, Region> {
        if (group.size == 1) {
            var best: Pair<Set<Any>, Map<Int, Region>>? = null
            for ((opt, _, lost) in group[0].second) {
                val cand = lost to merge(removed, opt)
                if (best == null || better(cand, best)) best = cand
            }
            return best!!.second
        }
        val must = HashSet(base) // lost whatever is chosen
        val count = HashMap<Any, Int>()
        for ((_, w) in group) {
            must.addAll(w.map { it.third }.reduce { a, b -> a intersect b })
            for ((_, _, lost) in w) for (e in lost - base) count[e] = (count[e] ?: 0) + 1
        }
        var best: Pair<Set<Any>, Map<Int, Region>>? = null
        fun go(n0: Int, cur: Map<Int, Region>, ls: Lines?, lost: Set<Any>) {
            var n = n0
            while (n < group.size && ls != null) {
                val c = group[n].first
                if (ls.conflicts.any { (it.where and c.where).isNotEmpty() && setOf(it.a, it.b) == setOf(c.a, c.b) }) break
                n++
            }
            if (n == group.size || ls == null) {
                val b = best
                if (b == null || better(lost to cur, b)) best = lost to cur
                return
            }
            val opts = works(ls, group[n].first, cur).sortedWith(
                compareBy({ (it.third - lost).size }, { -(it.third - base).sumOf { e -> count[e] ?: 0 } }),
            )
            for ((opt, after, now) in opts) {
                val b = best
                if (b == null || (now + must).size <= b.first.size) go(n + 1, merge(cur, opt), after, now)
            }
        }
        go(0, removed, Lines(rs, claims(removed), temp), base)
        return best?.second ?: removed
    }

    /** The combinations of parts of older elements whose removal drops this requirement on X, and nothing more. */
    fun giveWay(ls: Lines, side: Side, x: Region, removed: Map<Int, Region>): List<Combo> = when (side.tag) {
        "rule" -> {
            val s = ls.stop(side.i, side.lit!!, x)
            if (s.isNotEmpty()) falsify(ls, rs.element[side.i].lhs, s, removed) else emptyList()
        }
        "claim" -> {
            if (((mandPos[side.kind]?.get(side.v!!) ?: Region.EMPTY) and x).isNotEmpty()) {
                emptyList() // the edit's own
            } else {
                val s = pieces(removed, side.kind, x, side.v)
                if (s.isNotEmpty()) listOf(s) else emptyList()
            }
        }
        "no" -> if (((mandNeg[side.kind] ?: Region.EMPTY) and x).isNotEmpty()) emptyList() else pieces(removed, "no ${side.kind}", x).let { if (it.isNotEmpty()) listOf(it) else emptyList() }
        "off" -> if (((mandOff[side.kind] ?: Region.EMPTY) and x).isNotEmpty()) emptyList() else pieces(removed, "off ${side.kind}", x).let { if (it.isNotEmpty()) listOf(it) else emptyList() }
        "present" -> absent(ls, side.kind, x, removed)
        else -> emptyList() // "taken": the edit's own
    }

    /** Combinations that make K absent on Q: its older claims on Q go, and each rule that places K there stops applying. */
    fun absent(ls: Lines, k: String, q0: Region, removed: Map<Int, Region>, going0: Map<String, Region>? = null, depth: Int = 0): List<Combo> {
        if (k in ls.kindsIn || mandPos[k].orEmpty().values.any { (it and q0).isNotEmpty() }) return emptyList()
        val going = LinkedHashMap(going0.orEmpty())
        val q = q0 - (going[k] ?: Region.EMPTY)
        if (q.isEmpty()) return listOf(emptySet())
        if (depth > MAX_DEPTH) return emptyList() // too deep: whole elements instead (the fallback)
        going[k] = (going[k] ?: Region.EMPTY) or q
        val choices = arrayListOf(listOf(pieces(removed, k, q)))
        for ((i, o) in ls.placing(k)) {
            val s = ls.stop(i, o, q)
            if (s.isNotEmpty()) {
                val sub = falsify(ls, rs.element[i].lhs, s, removed, going, depth + 1)
                if (sub.isEmpty()) return emptyList()
                choices.add(sub)
            }
        }
        return combine(choices)
    }

    /** Combinations that make the left side f false on Q, by removing elements only. */
    fun falsify(ls: Lines, f: Formula, q: Region, removed: Map<Int, Region>, going: Map<String, Region>? = null, depth: Int = 0): List<Combo> = when (f) {
        is Atom -> {
            if (f.test(Rational.ZERO) || f.kind in ls.kindsIn) {
                emptyList()
            } else {
                val t = q and f.region(ls.vals, Region.UNIVERSE, Region.EMPTY)
                if (t.isNotEmpty()) absent(ls, f.kind, t, removed, going, depth) else listOf(emptySet())
            }
        }
        is Not -> emptyList()
        is Or -> combine(f.parts.map { falsify(ls, it, q, removed, going, depth) })
        is And -> {
            val out = ArrayList<Combo>()
            for (n in 1..f.parts.size) for (some in combinations(f.parts, n)) out.addAll(combine(some.map { falsify(ls, it, q, removed, going, depth) }))
            cap(out)
        }
    }

    // ------------------------------------------------------------ otherwise

    /** The rest of older elements removed whole, the fewest, near the edit first, then from left to right. */
    fun fallback(removed: Map<Int, Region>): Map<Int, Region> {
        fun rank(i: Int) = rs.rank[kindOfName(older[i].name)] ?: 1_000_000
        val order = older.indices.filter { (older[it].region - (removed[it] ?: Region.EMPTY)).isNotEmpty() }
            .sortedWith(compareBy({ (older[it].region and near).isEmpty() }, { older[it].region.inf() }, { rank(it) }))
        for (size in 1..order.size) {
            var best: Pair<Set<Any>, Map<Int, Region>>? = null
            for (some in combinations(order, size)) {
                tick()
                val cur = LinkedHashMap(removed)
                for (i in some) cur[i] = older[i].region
                if (consistent(cur)) {
                    val cand = lostOnes(oldLine, lines.getValue(cur)!!) to (cur as Map<Int, Region>)
                    val b = best
                    if (b == null || better(cand, b)) best = cand
                }
            }
            best?.let { return it.second }
        }
        throw Refused("no removal of the fewest older elements resolves the conflict in reasonable time")
    }
}

/** Each stretch of each conflict, a conflict; those with no bounds stay whole. */
private fun stretches(conflicts: List<Conflicting>): List<Conflicting> = conflicts.flatMap { c ->
    (if (c.where.isBounded()) c.where.components() else listOf(c.where)).map { Conflicting(it, c.a, c.b) }
}

/** (conflict, its combinations) by groups that can lose an element in common. */
private fun groups(
    found: List<Pair<Conflicting, List<Triple<Combo, Lines, Set<Any>>>>>, base: Set<Any>,
): List<List<Pair<Conflicting, List<Triple<Combo, Lines, Set<Any>>>>>> {
    var gs = listOf<Pair<Set<Any>, List<Pair<Conflicting, List<Triple<Combo, Lines, Set<Any>>>>>>>()
    for (item in found) {
        var lost: Set<Any> = item.second.flatMap { it.third }.toSet() - base
        var members = listOf(item)
        val rest = ArrayList<Pair<Set<Any>, List<Pair<Conflicting, List<Triple<Combo, Lines, Set<Any>>>>>>>()
        for (g in gs) {
            if ((g.first intersect lost).isNotEmpty()) {
                lost = lost + g.first
                members = g.second + members
            } else {
                rest.add(g)
            }
        }
        gs = rest + listOf(lost to members)
    }
    return gs.map { it.second }
}

/** reg & where != {} for many bounded elements reg, by binary search on the intervals of [where]. */
private class Meets(val where: Region, regs: List<Region>) {
    private var ivs: List<Interval> = emptyList()
    private var los: List<Pos> = emptyList()

    init {
        val ends = regs.filter { it.isBounded() }.flatMap { r -> r.bounded().intervals.flatMap { listOf(it.lo, it.hi) } }
        if (ends.isNotEmpty()) {
            ivs = where.on(ends.min(), ends.max() + 1).intervals
            los = ivs.map { it.lo }
        }
    }

    operator fun invoke(reg: Region): Boolean {
        if (!reg.isBounded()) return (reg and where).isNotEmpty()
        for (iv in reg.bounded().intervals) {
            var lo = 0
            var hi = los.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (iv.hi < los[mid]) hi = mid else lo = mid + 1
            }
            val j = lo - 1
            if (j >= 0 && ivs[j].hi >= iv.lo) return true
        }
        return false
    }
}

/** (index, part) pairs, one per index. */
private fun norm(pairs: Iterable<Pair<Int, Region>>): Combo {
    val d = LinkedHashMap<Int, Region>()
    for ((i, reg) in pairs) d[i] = d[i]?.let { it or reg } ?: reg
    return d.entries.map { it.key to it.value }.toSet()
}

private fun merge(removed: Map<Int, Region>, opt: Iterable<Pair<Int, Region>>): Map<Int, Region> {
    val out = LinkedHashMap(removed)
    for ((i, reg) in opt) out[i] = out[i]?.let { it or reg } ?: reg
    return out
}

/** Combination a removes nothing that b does not. */
private fun within(a: Combo, b: Combo): Boolean {
    val d = b.toMap()
    return a.all { (i, reg) -> i in d && reg.isSubsetOf(d.getValue(i)) }
}

/** One combination from each list, together; none if a list has none. */
private fun combine(choices: List<List<Combo>>): List<Combo> {
    if (choices.any { it.isEmpty() }) return emptyList()
    var out: List<Combo> = listOf(emptySet())
    for (c in choices) out = cap(out.flatMap { a -> c.map { b -> norm(a + b) } })
    return out
}

/** The combinations, the smallest first; more than [MAX_OPTIONS]: the edit is refused. */
private fun cap(opts: Iterable<Combo>): List<Combo> {
    val s = LinkedHashSet<Combo>()
    for (o in opts) s.add(o)
    if (s.size > MAX_OPTIONS) throw Refused("too many combinations to find the fewest elements to change")
    val cmp = Comparator<List<Int>> { a, b ->
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return@Comparator a[i].compareTo(b[i])
        a.size.compareTo(b.size)
    }
    return s.sortedWith(compareBy<Combo> { it.size }.thenComparator { a, b -> cmp.compare(a.map { it.first }.sorted(), b.map { it.first }.sorted()) })
}

private fun without(c1: Claims, drop: List<Pair<String, Region>>): Claims {
    val c = c1.copy()
    for ((name, reg) in dropped(drop)) {
        when {
            name.startsWith("no ") -> c.removeNeg(name.substring(3), reg)
            name.startsWith("off ") -> c.removeTaken(name.substring(4), reg)
            else -> c.removePos(name, reg)
        }
    }
    return c
}

/** {name: the union of its elements} (bounded ones in one step: they are many). */
private fun dropped(drop: List<Pair<String, Region>>): Map<String, Region> {
    val bounded = LinkedHashMap<String, MutableList<Interval>>()
    val out = LinkedHashMap<String, Region>()
    for ((name, reg) in drop) {
        if (reg.isBounded()) bounded.getOrPut(name) { ArrayList() }.addAll(reg.bounded().intervals)
        else out[name] = (out[name] ?: Region.EMPTY) or reg
    }
    for ((name, ivs) in bounded) out[name] = (out[name] ?: Region.EMPTY) or Region.of(IntervalSet.of(ivs))
    return out
}

/** The elements of the old line that do not stay whole: (kind, percentage, stretch). */
private fun lostOnes(old: Line, new: Line): Set<Any> {
    val out = HashSet<Any>()
    for ((k, d) in old.vals) {
        if (isHidden(k)) continue
        for ((v, reg) in d) {
            val diff = reg - (new.vals[k]?.get(v) ?: Region.EMPTY)
            if (diff.isNotEmpty()) for (c in reg.components()) if ((c and diff).isNotEmpty()) out.add(Triple(k, v, c.inf().toString()))
        }
    }
    return out
}

/** a, b = (loss, dropped {name: Region}); fewer lost, then leftmost kept. */
private fun betterOf(a: Pair<Int, Map<String, Region>>, b: Pair<Int, Map<String, Region>>, rs: RuleSet): Boolean {
    if (a.first != b.first) return a.first < b.first
    return keepsLeftmost(a.second, b.second, rs)
}

/** True if dropping da keeps the leftmost element that only one of da, db keeps. */
private fun keepsLeftmost(da: Map<String, Region>, db: Map<String, Region>, rs: RuleSet): Boolean {
    var first: Pair<Pair<Pos, Int>, Boolean>? = null
    for (k in (da.keys + db.keys).sorted()) {
        for ((diff, aDrops) in listOf((da[k] ?: Region.EMPTY) - (db[k] ?: Region.EMPTY) to true, (db[k] ?: Region.EMPTY) - (da[k] ?: Region.EMPTY) to false)) {
            if (diff.isNotEmpty()) {
                val key = diff.inf() to (rs.rank[kindOfName(k)] ?: 1_000_000)
                val f = first
                if (f == null || key.first < f.first.first || (key.first == f.first.first && key.second < f.first.second)) first = key to aDrops
            }
        }
    }
    return first != null && !first.second
}

private fun <T> combinations(items: List<T>, n: Int): Sequence<List<T>> = sequence {
    if (n > items.size) return@sequence
    val idx = IntArray(n) { it }
    while (true) {
        yield(idx.map { items[it] })
        var i = n - 1
        while (i >= 0 && idx[i] == items.size - n + i) i--
        if (i < 0) return@sequence
        idx[i]++
        for (j in i + 1 until n) idx[j] = idx[j - 1] + 1
    }
}
