package org.example.project.scheduler.ruleline

import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The rules generator — a port of `C:\code\line_manager\scripts\generator.py` (its module text describes a run and the
 * role of q). A run takes the state of the line, the chip's position and the revealed modes, and returns a script:
 * the constraints' edits first; candidate edits in a fixed order, the first B(q) evaluated, the best valid one kept
 * (the earliest on a tie); reruns asked for only where one can help; the mode rules compiled along the predicted
 * modes. When the definitions are simple enough the best line is found at once ([Plan], [Fill]).
 */
data class RunStats(
    var work: Int = 0,
    var budget: Int = 0,
    var valid: Int = 0,
    var chosen: String = "no-op",
    var key: ScoreKey? = null,
    var probabilityRules: List<String> = emptyList(),
    var refused: List<Pair<Pos, String>> = emptyList(),
    var constraints: String = "",
    var final: Boolean = true,
    var definitive: Pos? = null,
)

/** (what the problems' quantities by a date miss, the weighted error, the stretch error), compared in that order. */
data class ScoreKey(val shortfall: Num, val error: Num, val stretch: Num) : Comparable<ScoreKey> {
    override fun compareTo(other: ScoreKey): Int = compareValuesBy(this, other, { it.shortfall }, { it.error }, { it.stretch })
}

val PREDICTIONS = listOf("scenario", "uniform", "none")
val STRATEGIES = listOf("spread4", "left", "right", "spread2", "spread8", "spread16")

/** A run makes the script definitive this far further ... */
val PACE: Rational = Rational.of(10)

/** ... every this many seconds. */
val PACE_TIME: Rational = Rational.of(10)

/** The seconds a stage searches at q = 1. */
val SEARCH_TIME: Rational = Rational.of(6)

/** The search's window when nothing in the definitions is longer. */
val SMALLEST_WINDOW: Rational = PACE * 24

/** The op on [where]; the op itself where it is already within it. */
private fun within(o: Op, where: Region): Op = if (o.region.isSubsetOf(where)) o else Op(o.action, o.kind, o.region and where, o.value)

fun budgetOf(q: Double): Int = 2 + floor(36 * q).toInt()

/** The reference's `Fraction(str(q))`: the shortest decimal of the float. */
fun qFraction(q: Double): Rational = parseFraction(pyFloat(q))

fun rerunDistance(q: Double, window: Rational): Rational = window * (Rational.ONE - qFraction(q) / 2)

/** How far a run's search looks after the chip: 4 times the longest minimum length, twice the longest cycle. */
fun searchWindow(problems: List<Pair<OptProblem, Rational>>, constraints: List<Constraint> = emptyList()): Rational {
    var out = SMALLEST_WINDOW
    for ((p, _) in problems) for (l in p.lengths.values) out = maxOf(out, l * 4)
    for (k in constraints) out = maxOf(out, (k.cycle ?: Rational.ZERO) * 2)
    return out
}

/** What the simulation runs: the rules generator, or the scheduler's steps standing for one ([Stepped]). */
interface Generating {
    val rules: RuleSet
    val problems: List<Pair<OptProblem, Rational>>
    val constraints: List<Constraint>
    val window: Rational
    val q: Double
    fun margin(): Rational?
    fun kindsRead(): Set<String>?
    fun constraintEvents(state: LineState, c: Rational, until: Pos? = null): List<Event>
    fun firstEdits(state: LineState, c: Rational): List<List<Op>>
    fun run(state: LineState, chip: Rational, known: ModeKnowledge): Pair<Script, RunStats>
    fun stages(state: LineState, chip: Rational, known: ModeKnowledge, previous: Script? = null): Sequence<Pair<Script, RunStats>>
}

class RulesGenerator(
    override val rules: RuleSet,
    problems0: List<Pair<OptProblem, Rational>>,
    override val q: Double,
    var useProbability: Boolean = true,
    constraints: List<Constraint> = emptyList(),
    seed: Long? = null,
    window: Rational? = null,
    shortcuts: Boolean = true,
) : Generating {
    override val constraints: List<Constraint>
    override val window: Rational
    override val problems: List<Pair<OptProblem, Rational>>

    /** In a process of its own: the instant script holds PACE after the chip. */
    var paced = false

    /** q = 1 in a process of its own: the run goes on until it proves it is done. */
    var endless = false
    var speed: Rational = Rational.ONE
    val seed: Long
    val plan: Plan
    val fill: Fill
    val cycles: Plan

    init {
        require(q in 0.0..1.0) { "the allocated resources quantity must be in [0, 1]" }
        require(problems0.all { it.second.sign >= 0 }) { "the weights of the problems are >= 0" }
        this.constraints = expand(constraints)
        val targets = LinkedHashMap<String, Rational>()
        for ((p, _) in problems0) targets.putAll(p.targets)
        checkSum(targets)
        val bound = problems0.mapNotNull { it.first.window }
        this.window = window ?: (bound.maxOrNull() ?: searchWindow(problems0, this.constraints))
        problems = problems0.map { (p, w) -> p.withWindow(this.window) to w }
        for (k in this.constraints) {
            k.bind(rules)
            k.bindAll(this.constraints)
        }
        this.seed = seed ?: kotlin.random.Random.nextLong(0, 1L shl 32)
        checkBestExists(rules, problems)
        plan = Plan(rules, problems, this.constraints, this.seed)
        fill = Fill(problems)
        cycles = Plan(rules, problems, this.constraints, this.seed, strict = false)
        if (fill.ok && modesAddTargets()) fill.ok = false
        if (!shortcuts) {
            plan.ok = false
            fill.ok = false
            cycles.ok = false
        }
    }

    /** What the rules, the constraints and the problems read. */
    override fun kindsRead(): Set<String> {
        val out = HashSet(rules.ruled)
        for (k in constraints) out.addAll(k.kinds())
        for ((p, _) in problems) {
            out.addAll(p.targets.keys)
            out.addAll(p.lengths.keys)
        }
        return out
    }

    /** How far after a position the mode rules acting there can reach; null if that has no bound. */
    override fun margin(): Rational? {
        val reached = rules.modeRules.flatMap { r -> r.writtenLits().map { it.kind } }.toMutableSet()
        var far = Rational.ZERO
        val rs = rules.rules.filter { !it.isModeRule }
        val used = HashSet<Int>()
        var grew = reached.isNotEmpty()
        while (grew) {
            grew = false
            for ((i, r) in rs.withIndex()) {
                if (i !in used && (reached intersect r.lhs.kinds().toSet()).isNotEmpty()) {
                    used.add(i)
                    grew = true
                    reached.addAll(r.writtenLits().map { it.kind })
                    val ends = r.lits().flatMap { listOf(it.where.lo, it.where.hi) }.filter { it.isFinite }.map { it.x }
                    if (r.lits().any { it.anchor == "l" || it.where.lo.isInfinite || it.where.hi.isInfinite }) return null
                    far += ends.maxOfOrNull { it.abs() } ?: Rational.ZERO
                }
            }
        }
        return far
    }

    /** What the script at the start of a run can do at once, on the PACE after the chip. */
    override fun firstEdits(state: LineState, c: Rational): List<List<Op>> {
        val p0 = if (plan.ok) plan.ops(state, Pos(c)) else null
        if (p0 != null) return listOf(p0)
        val f = fillOf(state, c).first
        if (!f.isNullOrEmpty()) return listOf(f)
        var best: Pair<Rational, String>? = null
        val short = ArrayList<String>()
        val cp = Pos(c)
        for ((p, _) in problems) {
            for ((kind, r) in p.targets.entries.sortedBy { it.key }) {
                val w = IntervalSet.of(Interval.of(cp, cp + p.window!!, false, true))
                if (state.line.integral(kind, w) / p.window!! < r && (best == null || r > best.first)) best = r to kind
            }
            for ((kind, l) in p.lengths.entries.sortedBy { it.key }) {
                val pres = state.line.presence(kind)
                if ((pres.contains(cp) || pres.contains(cp - Pos.EPS)) && pres.measureOn(IntervalSet.of(Interval.of(Pos(c - l), cp, false, true))) < l) short.add(kind)
            }
        }
        val on = Region.of(Interval.of(cp, Pos(c + PACE), false, true))
        val b = best
        return (listOfNotNull(b?.second) + short.filter { b == null || it != b.second }).map { listOf(Op.put(it, on)) }
    }

    /** Where the stages of a run from c make the script definitive. */
    fun frontiers(c: Rational): List<Rational?> {
        val first = c + PACE
        val rest = c + window - first
        if (rest.sign <= 0) return listOf(null)
        val more = maxOf(1L, (qFraction(q) * rest / PACE).ceil()).toInt()
        return (0..more).map { first + rest * Rational.of(it) / Rational.of(more) }
    }

    override fun run(state: LineState, chip: Rational, known: ModeKnowledge): Pair<Script, RunStats> = stages(state, chip, known).last()

    /** The scripts of a run, as they are found, each with its stats. */
    override fun stages(state: LineState, chip: Rational, known: ModeKnowledge, previous: Script?): Sequence<Pair<Script, RunStats>> = sequence {
        val c = chip
        if (plan.ok) {
            val perfect = perfectOf(state, c)
            if (perfect != null) {
                yield(perfect)
                return@sequence
            }
        }
        val h = window
        val ctx = GenContext(this@RulesGenerator, state, c, known)
        val stats = RunStats(budget = budgetOf(q), probabilityRules = ctx.probabilityRules)
        var base = state
        var keep: Action? = null
        val ops0 = constraintOps(rules, state, Pos(c), constraints)
        var written = constraintEvents(state, c)
        if (ops0.isNotEmpty()) {
            keep = if (constraints.any { !it.explicit }) ConstraintEditAction else null
            var why: String?
            try {
                base = applyEdit(rules, state, ops0, Owner.GEN)
                why = checkScriptEdit(state, base, Pos(c))
            } catch (e: Refused) {
                why = e.message
            }
            if (why != null) {
                base = state
                keep = null
                written = emptyList()
                stats.constraints = why
            }
        }
        var frontiers = frontiers(c)
        ctx.forced = emptyList()
        if (fill.ok) {
            val f = atOnce(base, c)
            val valid = try {
                !f.isNullOrEmpty() && checkScriptEdit(state, applyEdit(rules, base, f, Owner.GEN), Pos(c)) == null
            } catch (e: Refused) {
                false
            }
            if (valid) {
                ctx.forced = listOf("the targets at once: each where it can be" to f!!)
                frontiers = listOf(null)
            }
        }
        val d = rerunDistance(q, h)
        val predicted = if (rules.modeRules.isNotEmpty()) ctx.predicted(c + d / 6) else emptyList()
        var covered: List<Triple<Pos, Pos, String?>> = predicted
        var carried = emptyList<Event>()
        var lo = c
        if (paced) {
            lo = c + PACE
            if (rules.modeRules.isNotEmpty()) covered = listOf(Triple(Pos(c), Pos(lo), null as String?)) + predicted
            carried = previous?.events.orEmpty().filter { e ->
                e.actions.any { it is EditAction } && (e.trigger !is ChipIn || (e.trigger as ChipIn).hi > Pos(c))
            }
            frontiers = frontiers.drop(1).ifEmpty { listOf(lo) }
            for (e in carried) for (a in e.actions) if (a is EditAction) {
                try {
                    base = applyEdit(rules, base, a.ops, Owner.GEN)
                } catch (x: Refused) {
                }
            }
        }
        val live = constraints.isNotEmpty() && rules.modeRules.isNotEmpty()
        var bestKey = ctx.score(base)
        var bestLabel = "no-op"
        var current = base
        stats.work = 1
        stats.valid = 1
        val edits = ArrayList<Event>()
        val began = TimeSource.Monotonic.markNow()
        var afterEdits: MutableList<Event>? = null
        var published: Script? = null
        for ((i, hi) in frontiers.withIndex()) {
            ctx.state = current
            val until = if (endless) TimeSource.Monotonic.markNow() + (SEARCH_TIME.toDoubleValue() * 1000).toLong().milliseconds else null
            val whole = i == 0
            ctx.openEnd = if (!whole && hi != null && hi < c + h) hi else null
            val (ops, key, label, after) = stage(ctx, state, current, lo, hi, stats, bestKey, frontiers.size - i, until, whole)
            ctx.openEnd = null
            if (ops.isNotEmpty()) {
                bestKey = key
                bestLabel = label
                current = after
                edits.add(Event(ChipIn(Pos(c), Pos.INF), listOf(EditAction(ops))))
            }
            if (afterEdits == null) {
                var nextRun = if (rules.modeRules.isNotEmpty()) d / 18 else d
                if (paced) {
                    val elapsed = limitDenominatorOf(began.elapsedNow().inWholeMicroseconds / 1e6, 1000)
                    val each = elapsed / Rational.of(maxOf(1, stats.work).toLong())
                    nextRun = maxOf(nextRun, each * 2 * budgetOf(1.0) * speed)
                }
                afterEdits = reruns(state, current, c, c + nextRun, d, known.currentMode(), ops.isNotEmpty() || frontiers.size > 1).toMutableList()
                afterEdits.add(Event(Edited(true), listOf(RerunAction())))
                if (covered.isNotEmpty() && !live) afterEdits.add(Event(Uncovered, listOf(RerunAction())))
            }
            val perfect = afterEdits.all { it.trigger is Edited }
            val final = i == frontiers.size - 1 || perfect
            var stretches = emptyList<Stretch>()
            if (final && predicted.isNotEmpty()) {
                val end = c + d / 6 * qFraction(q)
                val ahead = predicted.filter { it.first <= Pos(end) }.map { (a, b, m) -> Triple(a, min(b, Pos(end)), m!!) }
                val pushes = if (live) (written + carried).mapNotNull { pushOf(it) } else emptyList()
                val (st, refused) = compileRuns(rules, current, ahead, constraints, pushes)
                stretches = st
                stats.refused = refused
            }
            val done = final && (!endless || perfect)
            stats.chosen = bestLabel
            stats.key = bestKey
            stats.final = done
            stats.definitive = if (perfect) Pos.INF else hi?.let { Pos(it) }
            if (published == null || ops.isNotEmpty() || final) {
                val events = (if (keep != null) listOf(Event(ChipIn(Pos(c), Pos.INF), listOf(keep))) else emptyList()) + written + carried + edits + afterEdits
                published = Script(onView = SHOW, stretches = stretches, covered = covered, origin = Pos(c), base = current, live = live, constraints = constraints).withEvents(events)
            }
            yield(published!! to stats.copy())
            if (perfect) return@sequence
            lo = hi ?: lo
            if (final && !done) { // nothing proves it is the best: the run never ends
                lo = if (hi == null) c + maxOf(h, PACE) else hi
                while (true) {
                    val hi2 = lo + PACE
                    val until2 = TimeSource.Monotonic.markNow() + (SEARCH_TIME.toDoubleValue() * 1000).toLong().milliseconds
                    val further = GenContext(this@RulesGenerator, current, lo - PACE, known)
                    val (ops2, _, label2, after2) = stage(further, state, current, lo, hi2, stats, further.score(current), 1, until2)
                    if (ops2.isNotEmpty()) {
                        bestLabel = label2
                        current = after2
                        edits.add(Event(ChipIn(Pos(c), Pos.INF), listOf(EditAction(ops2))))
                    }
                    while (!until2.hasPassedNow()) { /* (the pace: one stage every SEARCH_TIME) */ }
                    stats.chosen = bestLabel
                    stats.definitive = Pos(hi2)
                    val events = (if (keep != null) listOf(Event(ChipIn(Pos(c), Pos.INF), listOf(keep))) else emptyList()) + written + carried + edits + afterEdits
                    yield(Script(onView = SHOW, stretches = stretches, covered = covered, origin = Pos(c), base = current, live = live, constraints = constraints).withEvents(events) to stats.copy())
                    lo = hi2
                }
            }
        }
    }

    /** The edits of the constraints written out in the script, and the chip's pushes of their pieces. */
    override fun constraintEvents(state: LineState, c: Rational, until: Pos?): List<Event> {
        val out = ArrayList<Event>()
        val ops = constraintOps(rules, state, Pos(c), constraints.filter { it.explicit }, until)
        if (ops.isNotEmpty()) out.add(Event(ChipIn(Pos(c), Pos.INF), listOf(EditAction(ops))))
        for (k in constraints) {
            if (!k.explicit || k !is AsEarlyAsPossible || k.length == null) continue
            val again = listOf(Op.take(k.kind, Region.of(Interval.of(Pos.ZERO, Pos.INF, false, false))), Op.put(k.kind, cycleRegion(Rational.ZERO, k.spacing(), Rational.ZERO, k.length)))
            for (m in k.pushedIn(rules)) out.add(Event(ChipOn(k.kind, m), listOf(EditAction(again, fromChip = true))))
            for (g in k.countedGaps()) {
                val later = listOf(Op.take(k.kind, Region.of(Interval.of(Pos.ZERO, Pos.INF, false, false))), Op.put(k.kind, cycleRegion(g.gap, k.spacing(), Rational.ZERO, k.length)))
                val lasted = if (g.atLeast.sign != 0) g.atLeast else null
                if (lasted == null) continue
                for (m in k.putsAtChip(rules, g.after)) out.add(Event(ChipOn(g.after, m, lasted = lasted, unless = k.kind), listOf(EditAction(later, fromChip = true))))
            }
        }
        return out
    }

    /** (the fill's edit or null, the state with the constraints' elements). */
    private fun fillOf(state: LineState, c: Rational): Pair<List<Op>?, LineState> {
        if (!fill.ok) return null to state
        val ops = constraintOps(rules, state, Pos(c), constraints)
        val base = try {
            if (ops.isNotEmpty()) applyEdit(rules, state, ops, Owner.GEN) else state
        } catch (e: Refused) {
            return null to state
        }
        return atOnce(base, c) to base
    }

    /** The best line's edit on this line, if found at once. */
    private fun atOnce(base: LineState, c: Rational): List<Op>? {
        val grouped = if (cycles.ok) cycles.grouped else emptySet()
        var ops = fill.ops(rules, base, Pos(c), window, grouped) ?: return null
        if (grouped.isNotEmpty()) ops = ops + (cycles.groupOps(rules, base, Pos(c), constraints) ?: return null)
        return ops.ifEmpty { null }
    }

    /** The perfect script and its stats, at once; null if there is none here. */
    private fun perfectOf(state: LineState, c: Rational): Pair<Script, RunStats>? {
        val ops = plan.ops(state, Pos(c)) ?: return null
        val after: LineState
        try {
            after = applyEdit(rules, state, ops, Owner.GEN)
            if (checkScriptEdit(state, after, Pos(c)) != null) return null
        } catch (e: Refused) {
            return null
        }
        val events = listOf(Event(ChipIn(Pos(c), Pos.INF), listOf(EditAction(ops))), Event(Edited(true), listOf(RerunAction())))
        val stats = RunStats(work = 1, budget = budgetOf(q), valid = 1, chosen = "perfect script", final = true, definitive = Pos.INF)
        return Script(onView = SHOW, origin = Pos(c), base = after).withEvents(events) to stats
    }

    private data class StageResult(val ops: List<Op>, val key: ScoreKey, val label: String, val after: LineState)

    /** The best edit on (lo, hi] of the candidates this stage has budget and time for. */
    private fun stage(ctx: GenContext, state: LineState, base: LineState, lo0: Rational, hi: Rational?, stats: RunStats, bestKey: ScoreKey, left: Int, until: TimeSource.Monotonic.ValueTimeMark? = null, whole: Boolean = false): StageResult {
        if (until == null && !whole) return StageResult(emptyList(), bestKey, "no-op", base)
        var best = StageResult(emptyList(), ctx.score(base), "no-op", base)
        val far = margin()
        var lo = lo0
        if (lo > ctx.cr && rules.modeRules.isNotEmpty()) lo = if (far == null || hi == null) (hi ?: lo) else minOf(lo + far, hi)
        val where: Region?
        val limit: Int
        if (whole && hi != null) {
            where = if (lo < hi) Region.of(Interval.of(Pos(lo), Pos.INF, false, false)) else Region.EMPTY
            limit = stats.budget
        } else {
            where = if (hi == null) null else if (lo < hi) Region.of(Interval.of(Pos(lo), Pos(hi), false, true)) else Region.EMPTY
            limit = stats.work + maxOf(1, (stats.budget - stats.work) / left)
        }
        val held = constraints.none { it.ops(base, ctx.c).isNotEmpty() }
        val seen = HashSet<String>()
        seen.add("")
        val source: Sequence<Pair<String, List<Op>>> = if (ctx.forced.isNotEmpty()) ctx.forced.asSequence() else ctx.candidates(until != null)
        for ((label, ops0) in source) {
            val ops = if (where != null) ops0.map { within(it, where) }.filter { it.region.isNotEmpty() } else ops0
            val key = ops.joinToString(" ; ")
            if (key in seen) continue
            seen.add(key)
            if (if (until == null) stats.work >= minOf(limit, stats.budget) else until.hasPassedNow()) break
            stats.work++
            val after = try {
                applyEdit(rules, base, ops, Owner.GEN)
            } catch (e: Refused) {
                continue
            }
            if (checkScriptEdit(state, after, ctx.c) != null) continue
            if (lo > ctx.cr && !base.line.sameOn(after.line, Region.of(Interval.of(Pos.NEG_INF, Pos(lo), false, true)))) continue
            if (held && constraints.any { it.ops(after, ctx.c).isNotEmpty() }) continue
            stats.valid++
            val k = ctx.score(after)
            if (k < best.key) best = StageResult(ops, k, label, after)
        }
        return best
    }

    /** The events that ask for a new run, only where one can help. */
    private fun reruns(state: LineState, after: LineState, c: Rational, at: Rational, d: Rational, mode: String, edits: Boolean): List<Event> {
        val rerun = listOf<Action>(RerunAction())
        val out = ArrayList<Event>()
        if (rules.modeRules.isNotEmpty() || !constraints.all { it.holdsByItself() }) out.add(Event(ChipIn(Pos(at), Pos.INF), rerun))
        if (problems.any { it.first.hasTargets() } && !futile(state, edits)) {
            val lo = c + d * (Rational.ONE - qFraction(q)) / 2
            for (m in modesThatMatter()) out.add(Event(ChipIn(Pos(lo), Pos.INF, m), rerun))
            out.addAll(watches(after, c, d, mode))
        }
        return out
    }

    /** Whether a mode rule can put a target kind on the line, directly or through the element rules. */
    private fun modesAddTargets(): Boolean {
        val targets = problems.flatMap { (p, _) -> p.targets.filter { it.value.sign > 0 }.keys }.toSet()
        val written = rules.modeRules.flatMap { it.writtenLits() }
        val put = written.filter { !it.negated }.map { it.kind }.toMutableSet()
        val reached = (put + written.map { it.kind }).toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            for (r in rules.rules) {
                if (!r.isModeRule && (reached intersect r.lhs.kinds().toSet()).isNotEmpty()) {
                    val more = r.writtenLits().filter { !it.negated }.map { it.kind }.toSet() - put
                    if (more.isNotEmpty()) {
                        put.addAll(more)
                        reached.addAll(more)
                        grew = true
                    }
                }
            }
        }
        return (put intersect targets).isNotEmpty()
    }

    /** The modes whose rules reach what the problems read. */
    private fun modesThatMatter(): List<String> {
        val read = problems.flatMap { (p, _) -> p.targets.keys + p.lengths.keys }.toSet()
        val out = ArrayList<String>()
        for (m in rules.modeRules.mapNotNull { it.mode }.toSet().sorted()) {
            val reached = rules.modeRules.filter { it.mode == m }.flatMap { r -> r.writtenLits().map { it.kind } }.toMutableSet()
            var grew = true
            while (grew) {
                grew = false
                for (r in rules.rules) {
                    val written = r.writtenLits().map { it.kind }.toSet()
                    if (!r.isModeRule && (reached intersect r.lhs.kinds().toSet()).isNotEmpty() && !reached.containsAll(written)) {
                        reached.addAll(written)
                        grew = true
                    }
                }
            }
            if ((reached intersect read).isNotEmpty()) out.add(m)
        }
        return out
    }

    /** Run again if the line goes away from what this run left, by a margin a lower q makes wider. */
    private fun watches(state: LineState, c: Rational, d: Rational, mode: String): List<Event> {
        if (problems.none { it.first.hasTargets() }) return emptyList()
        val margin = (Rational.ONE - qFraction(q)) / 2
        val out = ArrayList<Event>()
        out.add(Event(ScoreBelow(score(problems, state, Pos(c)) * (Rational.ONE - margin)), listOf(RerunAction())))
        for ((p, _) in problems) {
            for ((kind, r) in p.targets.entries.sortedBy { it.key }) {
                val w = IntervalSet.of(Interval.of(Pos(c), Pos(c + p.window!!), false, true))
                val now = state.line.integral(kind, w) / p.window!!
                val y = maxOf(now, r) + margin / 2
                if (y < Rational.ONE) out.add(Event(PresenceAbove(kind, Rational.of(100), y, p.window!!, Pos(c), Pos(c + d), mode), listOf(RerunAction())))
            }
        }
        return out
    }

    /** The line is empty, no mode rule exists and this run does nothing. */
    private fun futile(state: LineState, edits: Boolean): Boolean =
        state.claims.kinds().isEmpty() && state.line.kinds().isEmpty() && rules.modeRules.isEmpty() && !edits

    companion object {
        const val MAX_SCENARIOS = 8
    }
}

/** What a run reads: the state, the chip, the predicted modes (the reference's `_Context`). */
class GenContext(val gen: RulesGenerator, override var state: LineState, val cr: Rational, val known: ModeKnowledge) : ProblemContext {
    override val c: Pos = Pos(cr)
    override val rules: RuleSet get() = gen.rules
    override var openEnd: Rational? = null
    var forced: List<Pair<String, List<Op>>> = emptyList()
    val wSet: IntervalSet = IntervalSet.of(Interval.of(Pos(cr), Pos(cr + gen.window), false, true))
    val scenarios: List<Scenario>
    val residual: Rational
    val probabilityRules: List<String>
    val modes: List<String>
    override val weightsFull: List<Triple<String, Region, Rational>>
    override val weightsUniform: List<Triple<String, Region, Rational>>
    override val weightsBest: List<Triple<String, Region, Rational>>?

    init {
        val ms = (known.modesSeen() + gen.rules.modeRules.mapNotNull { it.mode }).toMutableSet()
        var sc = emptyList<Scenario>()
        var res = Rational.ONE
        var pr = emptyList<String>()
        if (gen.useProbability) {
            val model = ProbabilityFinder().fit(known)
            pr = model.rules()
            val (s, r) = model.scenarios(known, cr + gen.window, RulesGenerator.MAX_SCENARIOS)
            sc = s
            res = r
            ms.addAll(model.nextCounts.keys)
            ms.addAll(model.lengthCounts.keys)
        }
        scenarios = sc
        residual = res
        probabilityRules = pr
        modes = ms.sorted()
        val unknown = Region.of(IntervalSet.of(Interval.of(Pos(maxOf(cr, known.frontier)), Pos(cr + gen.window), false, true)))
        val u = if (modes.isNotEmpty()) Rational.of(1, modes.size) else Rational.ZERO
        val wf = ArrayList<Triple<String, Region, Rational>>()
        for (s in scenarios) for (m in modes) {
            wf.add(Triple(m, Region.of(s.modeRegion(m)) and unknown, s.probability))
            wf.add(Triple(m, Region.of(s.uncovered) and unknown, s.probability * u))
        }
        for (m in modes) wf.add(Triple(m, unknown, (if (gen.useProbability) residual else Rational.ONE) * u))
        weightsFull = wf
        weightsUniform = modes.map { Triple(it, unknown, u) }
        val best = scenarios.firstOrNull()
        weightsBest = if (best != null) modes.map { Triple(it, Region.of(best.modeRegion(it)) and unknown, Rational.ONE) } + modes.map { Triple(it, Region.of(best.uncovered) and unknown, u) } else null
    }

    /** The chip's positions from c to end, (lo, hi, mode) where the modes are predicted. */
    fun predicted(end: Rational): List<Triple<Pos, Pos, String?>> {
        val starts = LinkedHashMap<Rational, String>()
        starts[cr] = known.currentMode()
        if (scenarios.isNotEmpty()) {
            for (m in modes) for (iv in scenarios[0].modeRegion(m).intervals) {
                val x = iv.lo.x
                if (cr < x && x < end) starts[x] = m
            }
        }
        val points = ArrayList<Pair<Rational, String>>()
        for (x in starts.keys.sorted()) if (points.isEmpty() || points.last().second != starts.getValue(x)) points.add(x to starts.getValue(x))
        return points.mapIndexed { i, (x, m) -> Triple(Pos(x), if (i + 1 < points.size) Pos(points[i + 1].first) - Pos.EPS else Pos(end), m as String?) }
    }

    /** The expected integral of a kind on [w], with what the mode rules will do there. */
    override fun expected(s: LineState, kind: String, weights: Any?, w: IntervalSet): Rational {
        val base = s.line.integral(kind, w)
        @Suppress("UNCHECKED_CAST")
        val ws = weights as List<Triple<String, Region, Rational>>?
        if (ws.isNullOrEmpty()) return base
        val pres = s.line.presence(kind)
        var adj = Rational.ZERO
        for ((m, region, wt) in ws) {
            if (wt.sign == 0 || region.isEmpty()) continue
            for (r in gen.rules.modeRules) {
                if (r.mode != m) continue
                val met = region and lhsRegion(r.lhs, s.line.vals)
                for (it in r.rhs) {
                    val o = it.options[0]
                    if (o.kind != kind) continue
                    if (o.negated) {
                        for ((v, reg) in s.line.vals[kind].orEmpty()) adj -= wt * v / 100 * (reg and met).measureOn(w)
                    } else {
                        adj += wt * (o.value ?: Rational.of(100)) / 100 * (met - pres).measureOn(w)
                    }
                }
            }
        }
        return base + adj
    }

    fun score(s: LineState): ScoreKey {
        var shortfall: Num = Num.ZERO
        var error: Num = Num.ZERO
        var stretch: Num = Num.ZERO
        for ((p, w) in gen.problems) {
            if (w.sign == 0) continue
            p.shortfall(this, s)?.let { shortfall += it }
            error += p.error(this, s) * w
            p.stretchError(this, s)?.let { stretch += Num.Exact(w * it) }
        }
        return ScoreKey(shortfall, error, stretch)
    }

    /** The candidates in their order; [deep]: then finer and finer placements. */
    fun candidates(deep: Boolean = false): Sequence<Pair<String, List<Op>>> = sequence {
        yieldAll(candidatesOf(STRATEGIES + byLength()))
        if (deep) yieldAll(candidatesOf((5..10).map { "spread${1 shl it}" }))
    }

    /** The placement that spreads each kind in pieces of its minimum length, when finer than the other placements. */
    private fun byLength(): List<String> {
        val targets = LinkedHashMap<String, Rational>()
        val lengths = LinkedHashMap<String, Rational>()
        for ((p, w) in gen.problems) if (w.sign != 0) {
            targets.putAll(p.targets)
            lengths.putAll(p.lengths)
        }
        val parts = lengths.filter { (k, _) -> (targets[k] ?: Rational.ZERO).sign != 0 }.map { (k, l) -> (gen.window * targets.getValue(k) / l).let { q -> if (q.sign >= 0) q.floor() else -((-q).floor()) } }
        val n = parts.minOrNull() ?: 0L
        return if (n > 16) listOf("spread${minOf(n, 64L)}") else emptyList()
    }

    private fun candidatesOf(strategies: List<String>): Sequence<Pair<String, List<Op>>> = sequence {
        val preds = PREDICTIONS.filter { it != "scenario" || !weightsBest.isNullOrEmpty() }
        val problems = gen.problems.filter { it.second.sign != 0 }.map { it.first }
        for (pred in preds) {
            for (strategy in strategies) {
                val label = "prediction=$pred, placement=$strategy"
                val parts = problems.map { it.proposals(this@GenContext, pred, strategy) }
                yield(label to parts.flatten())
                if (problems.size > 1) for ((p, ops) in problems.zip(parts)) yield("${p.name} alone, $label" to ops)
            }
        }
        if (strategies.take(STRATEGIES.size) == STRATEGIES) for (p in problems) yieldAll(p.extra(this@GenContext))
    }
}
