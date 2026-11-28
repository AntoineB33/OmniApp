package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational

/**
 * The world: the chip moving, modes being revealed, the user editing, scripts running — a port of
 * `C:\code\line_manager\scripts\simulation.py` (its module text describes the order of things at one instant).
 *
 * Time t >= 0 is exact; the chip's position a continuous, non-decreasing, piecewise-linear function of it. At one
 * instant: user edits, then revealed mode transitions, then the script's triggers in script order (a rerun installs
 * the new script, whose triggers are evaluated at once). The script carries out the mode rules where its text says
 * so, through the stretches its run compiled ahead where the line is the one compiled for them. In the background
 * mode a run of the generator takes time: a call only records it, and each script of the run replaces the one in
 * place when it is found ([publish], [complete]). The reference's other processes (the user's edits computed
 * elsewhere, the mode rules checked ahead) are hooks here: [calculate], [aheadJobs].
 */
const val EDIT_LIMIT = 2.0

/** Changes of the line kept in the log, at least. */
const val LOG_KEPT = 512

/** Bytes a state can take: beyond, its far left is flattened, then removed. */
const val MEMORY_LIMIT: Long = 2L * 1024 * 1024 * 1024

/** So that an edit going on never delays the pace. */
val LAST_RESORT: Rational = PACE_TIME - Rational.of(2)

/** Its trigger, a ChipOn with no `within` taking it from the event's own edit written from the chip. */
private fun reachOf(ev: Event): Trigger {
    val trig = ev.trigger as? ChipOn ?: return ev.trigger
    if (trig.within != null) return trig
    for (a in ev.actions) {
        if (a is EditAction && a.fromChip) {
            for (o in a.ops) {
                if (o.action == "put" && o.kind == trig.kind && o.region.isNotEmpty()) {
                    val lo = o.region.inf()
                    return trig.copy(within = maxOf(Rational.ZERO, if (lo.isFinite) lo.x else Rational.ZERO))
                }
            }
        }
    }
    return trig.copy(within = Rational.ZERO)
}

/** Why the constraints the script must satisfy cannot hold on this line, if so. */
fun againstConstraints(rules: RuleSet, state: LineState, c: Rational, constraints: List<Constraint>): String? {
    val ops = constraintOps(rules, state, Pos(c), constraints)
    if (ops.isEmpty()) return null
    var why: String?
    try {
        val after = applyEdit(rules, state, ops, Owner.GEN)
        why = checkScriptEdit(state, after, Pos(c))
        val maxGaps = constraints.filterIsInstance<MaximumGap>()
        if (why == null && maxGaps.isNotEmpty() && maxGaps.any { it.ops(after, Pos(c)).isNotEmpty() }) {
            why = "an obligation (MaximumGap) cannot be met with the other constraints"
        }
    } catch (e: Refused) {
        why = e.message
    }
    return if (why != null) "it contradicts the constraints: $why" else null
}

/** (the state after an edit of the user, why it is refused, if so). */
fun userEdit(rules: RuleSet, state: LineState, ops: List<Op>, owner: String, c: Rational, constraints: List<Constraint>): Pair<LineState?, String?> = try {
    val after = applyEdit(rules, state, ops, owner)
    after to againstConstraints(rules, after, c, constraints)
} catch (e: Refused) {
    null to e.message
} catch (e: Undetermined) {
    null to e.message
}

/** Piecewise-linear trajectory through (t, x) points: t strictly increasing from 0, x non-decreasing. */
class Chip(points: List<Pair<Rational, Rational>>) {
    val points: MutableList<Pair<Rational, Rational>> = points.toMutableList()

    init {
        require(this.points.isNotEmpty() && this.points[0].first.sign == 0) { "the trajectory starts at time 0" }
        for ((a, b) in this.points.zipWithNext()) {
            require(b.first > a.first) { "times must increase" }
            require(b.second >= a.second) { "the chip never moves left" }
        }
    }

    /** The trajectory goes on to (t, x). */
    fun extend(t: Rational, x: Rational) {
        val (t1, x1) = points.last()
        require(t > t1 && x >= x1) { "the chip goes on later, and never to the left" }
        if (points.size > 1) {
            val (t0, x0) = points[points.size - 2]
            if ((x1 - x0) * (t - t1) == (x - x1) * (t1 - t0)) { // the same speed: one segment
                points[points.size - 1] = t to x
                return
            }
        }
        points.add(t to x)
    }

    fun position(t: Rational): Rational {
        if (t >= points.last().first) return points.last().second
        for ((a, b) in points.zipWithNext()) {
            val (t0, x0) = a
            val (t1, x1) = b
            if (t0 <= t && t <= t1) return x0 + (x1 - x0) * (t - t0) / (t1 - t0)
        }
        throw IllegalArgumentException("negative time")
    }

    /** min { t : position(t) >= x }, or +inf. */
    fun firstTime(x: Rational): Rational {
        if (x <= points[0].second) return Rational.ZERO
        for ((a, b) in points.zipWithNext()) {
            val (t0, x0) = a
            val (t1, x1) = b
            if (x0 < x && x <= x1) return t0 + (t1 - t0) * (x - x0) / (x1 - x0)
        }
        return Rational.INF
    }
}

data class UserEdit(val time: Rational, val ops: List<Op>)

class Change(val time: Rational, val chip: Pos, val source: String, val before: LineState, val after: LineState, val at: Region? = null)

/** A call to run the generator, in the background mode: what the run reads. */
class RunRequest(
    val id: Int,
    val time: Rational,
    val chip: Rational,
    val why: String,
    val state: LineState,
    val known: ModeKnowledge,
    val gen: Generating,
    var published: Boolean = false,
    var previous: Script? = null,
    var paceAt: Rational = Rational.ZERO,
)

class SimLog {
    val changes = ArrayList<Change>()
    val refused = ArrayList<Triple<Rational, String, String>>()
    val runs = ArrayList<RunEntry>()
    var refusedGone = 0
    var runsGone = 0

    class RunEntry(val time: Rational, val chip: Rational, val why: String, val script: Script, val stats: RunStats)

    fun changed(change: Change) {
        changes.add(change)
        if (changes.size > 2 * LOG_KEPT) changes.subList(0, LOG_KEPT).clear()
    }

    fun refuse(entry: Triple<Rational, String, String>) {
        refused.add(entry)
        if (refused.size > 2 * LOG_KEPT) {
            refused.subList(0, LOG_KEPT).clear()
            refusedGone += LOG_KEPT
        }
    }

    /** The last runs only; only the newest keeps what its script compiled. */
    fun ran(run: RunEntry) {
        if (runs.isNotEmpty()) {
            val r = runs.last()
            runs[runs.size - 1] = RunEntry(r.time, r.chip, r.why, r.script.copy(stretches = emptyList(), base = null).carrying(r.script), r.stats)
        }
        runs.add(run)
        if (runs.size > 2 * LOG_KEPT) {
            runs.subList(0, LOG_KEPT).clear()
            runsGone += LOG_KEPT
        }
    }

    val refusedCount: Int get() = refusedGone + refused.size
    val runCount: Int get() = runsGone + runs.size

    fun refusedSince(count: Int) = refused.subList(maxOf(0, count - refusedGone), refused.size).toList()

    fun runsSince(count: Int) = runs.subList(maxOf(0, count - runsGone), runs.size).toList()
}

/** The state as a function of the chip's position: [key] the same while one formula gives it. */
class Ahead(val at: (Pos) -> LineState?, val key: Any?, val until: (Pos) -> Pos?) {
    operator fun invoke(p: Pos) = at(p)
}

class Simulation(
    var state: LineState,
    var gen: Generating,
    val chip: Chip,
    val modes: ModeTimeline,
    userEdits: List<UserEdit> = emptyList(),
) {
    private var settled: List<Any?>? = null
    private var kept: Pos? = null
    private var frozen: Rational? = null
    private var looks: Pair<Script, MutableMap<String, Pair<Pos, List<Any?>>?>>? = null
    var scriptGen: Generating? = null
    var rules: RuleSet = gen.rules
    val userEdits: List<UserEdit> = userEdits.sortedBy { it.time }
    val log = SimLog()
    var script: Script = Script.initial()
    var revealed = 0
    var t: Rational = Rational.ZERO
    var started = false
    var grid: Rational? = rules.smallestOffset?.let { it / 2 }
    private var stretch: Triple<Stretch, LineState, Pos>? = null
    private var liveRules = HashMap<String, ModeRules>()
    private var shift: Rational? = null
    private var missed: Pair<Script, LineState>? = null
    var background = false
    var apart = false
    var shown = 0
    var request: RunRequest? = null
    var definitive: Rational? = null

    /** The user's edits computed elsewhere: (rules, state, ops, owner, c, constraints) -> (after, why). */
    var calculate: ((RuleSet, LineState, List<Op>, String, Rational, List<Constraint>) -> Pair<LineState?, String?>)? = null

    /** The mode rules checked ahead of the chip elsewhere; null: computed by the chip itself. */
    var aheadJobs: Any? = null
    var memoryLimit: Long = MEMORY_LIMIT
    var flatTo: Pos? = null
    var goneTo: Pos? = null
    private var edited = false
    private var readEdited = false
    var generatorOff = false
    private var requests = 0

    init {
        require(Pos(modes.start) == Pos(chip.position(Rational.ZERO))) { "modes start where the chip starts" }
    }

    fun known(t: Rational): ModeKnowledge = modes.known(chip.position(t))

    private fun install(t: Rational, why: String) {
        val c = chip.position(t)
        if (generatorOff) {
            log.refuse(Triple(t, "script", "the generator is switched off: not run ($why)"))
            return
        }
        if (background) {
            requests++
            request = RunRequest(requests, t, c, why, state, known(t), gen, previous = script)
            firstScript()
            return
        }
        val (s, stats) = gen.run(state, c, known(t))
        setScript(t, c, why, s, stats)
    }

    /** The run asked for completed now: its script replaces the one in place. False if a newer run was asked for. */
    fun complete(requestId: Int, s: Script, stats: RunStats): Boolean {
        val req = request ?: return false
        if (req.id != requestId) return false
        val fr = if (req.published) definitive else null
        setScript(t, req.chip, req.why, if (req.published) fit(s) else s, stats, keep = req.published)
        request = null
        frozen = fr
        afterEdits(t, false, false, req.why)
        frozen = null
        return true
    }

    /** At the start of a run, at once: the script in place, changed a little, definitive on the PACE after the chip. */
    fun firstScript() {
        val req = request!!
        val s = script
        val x = req.chip + PACE
        val constraints = gen.constraints.ifEmpty { s.constraints }
        val events = s.events.filter { it.trigger !is Start }.toMutableList()
        val hidden = constraints.filter { !it.explicit }
        if (hidden.isNotEmpty() && events.none { e -> e.actions.any { it is ConstraintEditAction } }) {
            events.add(0, Event(ChipIn(Pos(req.chip), Pos.INF), listOf(ConstraintEditAction)))
        }
        val until = Pos(req.chip + maxOf(PACE, gen.window))
        val written = gen.constraintEvents(state, req.chip)
        val have = events.map { it.toString() }.toSet()
        val all = (written.filter { it.toString() !in have } + events).toMutableList()
        if (all.none { e -> e.trigger is Edited && e.actions.any { it is RerunAction } }) all.add(Event(Edited(true), listOf(RerunAction())))
        all.add(Event(ChipIn(Pos(x), Pos.INF), listOf(RerunAction())))
        val covered = s.covered.filter { it.third != null }.toMutableList()
        if (rules.modeRules.isNotEmpty()) covered.add(0, Triple(Pos(req.chip), Pos(x), null))
        var base: LineState? = null
        var at = all.count { it.trigger is ChipIn && it.actions[0] is ConstraintEditAction }
        for (ops in gen.firstEdits(state, req.chip)) {
            var after: LineState? = null
            var why: String?
            try {
                if (base == null) {
                    val asked = constraintOps(rules, state, Pos(req.chip), constraints, until)
                    base = if (asked.isNotEmpty()) applyEdit(rules, state, asked, Owner.GEN) else state
                }
                after = applyEdit(rules, base!!, ops, Owner.GEN)
                why = checkScriptEdit(base!!, after, Pos(req.chip))
                    ?: (if (constraintOps(rules, after, Pos(req.chip), constraints, until).isNotEmpty()) "it goes against the constraints" else null)
            } catch (e: Refused) {
                why = e.message
            } catch (e: Undetermined) {
                why = e.message
            }
            if (why == null) {
                base = after
                all.add(at, Event(ChipIn(Pos(req.chip), Pos(req.chip + gen.window)), listOf(EditAction(ops))))
                at++
            }
        }
        val sc = s.withEvents(all).copy(onView = s.onView.ifEmpty { SHOW }, covered = covered, constraints = constraints, live = s.live || (constraints.isNotEmpty() && rules.modeRules.isNotEmpty())).carrying(s)
        req.published = true
        req.paceAt = req.time + LAST_RESORT
        req.previous = sc
        carry(sc)
        script = sc
        reset()
        definitive = x
    }

    /** A run that did not get further in time is overtaken by the script in place: definitive PACE further. */
    fun keepPace(t: Rational) {
        var req = request
        while (req != null && req.published && t >= req.paceAt) {
            val was = definitive!!
            definitive = was + PACE
            req.paceAt += LAST_RESORT
            script = moved(fit(script), was, definitive!!)
            if (definitive!! >= req.chip + gen.window && gen.q != 1.0) {
                request = null
                log.ran(SimLog.RunEntry(t, req.chip, req.why, script, RunStats(chosen = "the script in place, as a last resort")))
                return
            }
            req = request
        }
    }

    private fun moved(s: Script, was: Rational, now: Rational): Script {
        var changed = false
        val events = s.events.map { e ->
            val tr = e.trigger
            if (tr is ChipIn && tr.lo == Pos(was) && tr.hi == Pos.INF && tr.mode == null && e.actions.size == 1 && e.actions[0] is RerunAction) {
                changed = true
                Event(ChipIn(Pos(now), Pos.INF), e.actions)
            } else {
                e
            }
        }
        return if (changed) s.withEvents(events) else s
    }

    /** The script with the frontier of the run: its edits that are new after it, and the mode rules up to it. */
    private fun fit(s: Script): Script {
        val x = Pos(definitive!!)
        val now = script.events.map { it.toString() }.toSet()
        val far = gen.margin()
        val before = if (far == null) Region.UNIVERSE else Region.of(Interval.of(Pos.NEG_INF, x + far, false, true))
        val events = ArrayList<Event>()
        for (e in s.events) {
            val actions = ArrayList<Action>()
            for (a in e.actions) {
                if (a is EditAction && e.toString() !in now) {
                    val ops = a.ops.map { Op(it.action, it.kind, it.region - before, it.value) }.filter { it.region.isNotEmpty() }
                    if (ops.isNotEmpty()) actions.add(EditAction(ops))
                } else {
                    actions.add(a)
                }
            }
            if (actions.isNotEmpty()) events.add(Event(e.trigger, actions))
        }
        val covered = s.covered.filter { it.third != null }.toMutableList()
        val req = request
        if (rules.modeRules.isNotEmpty() && req != null) covered.add(0, Triple(Pos(req.chip), x, null))
        return s.withEvents(events).copy(covered = covered).carrying(s)
    }

    /** The run found a script and goes on: it replaces the one in place. False if a newer run was asked for. */
    fun publish(requestId: Int, s0: Script, stats: RunStats): Boolean {
        val req = request ?: return false
        if (req.id != requestId) return false
        var s = fit(s0)
        val fr = definitive
        val d = stats.definitive
        if (d != null && d > Pos(definitive!!)) {
            definitive = d.x
            req.paceAt = t + LAST_RESORT
            s = s.copy(covered = s.covered.map { (lo, hi, m) -> if (m == null) Triple(lo, d, m) else Triple(lo, hi, m) }).carrying(s)
        }
        carry(s)
        script = s
        reset()
        frozen = fr
        afterEdits(t, false, false, req.why)
        frozen = null
        return true
    }

    private fun reset() {
        stretch = null
        liveRules = HashMap()
        shift = null
        script.since = 0
    }

    /** The events of [s] that are in the one in place stay as they were, unless they satisfy constraints that changed. */
    private fun carry(s: Script, sameConstraints: Boolean = true) {
        val keptEvents = s.events.filter { e -> sameConstraints || e.actions.none { it is ConstraintEditAction } }.map { it.toString() }.toSet()
        s.trueEvents = script.trueEvents.filter { it in keptEvents }.toMutableSet()
        s.fired = script.fired.filter { it in keptEvents }.toMutableSet()
    }

    private fun setScript(t: Rational, c: Rational, why: String, s: Script, stats: RunStats, keep: Boolean = false) {
        if (keep) {
            carry(s)
        } else {
            s.trueEvents = HashSet()
            s.fired = HashSet()
        }
        script = s
        scriptGen = gen
        reset()
        val d = stats.definitive
        definitive = if (d == null) null else maxOf(d.x, definitive ?: d.x)
        log.ran(SimLog.RunEntry(t, c, why, s, stats))
    }

    /** The user's script replaces the one in place, and the run going on is cancelled. */
    fun replaceScript(s: Script) {
        carry(s, s.constraints.map { it.toString() } == script.constraints.map { it.toString() })
        script = s
        request = null
        definitive = null
        reset()
        if (started) afterEdits(t, false, false, "user edit")
    }

    private fun cond(trig: Trigger, t: Rational): Boolean = when (trig) {
        is Start -> true
        is Edited -> edited && (readEdited || !trig.only)
        is Uncovered -> {
            val c = chip.position(t)
            uncoveredAt(modes.modeAt(Pos(c)), Pos(c))
        }
        is ChipIn -> {
            val c = chip.position(t)
            trig.holds(Pos(c), modes.modeAt(Pos(c)))
        }
        is PresenceAbove -> {
            val c = chip.position(t)
            trig.holds(state, Pos(c), modes.modeAt(Pos(c)))
        }
        is ChipOn -> {
            val c = chip.position(t)
            trig.holds(state, Pos(c), modes.modeAt(Pos(c)))
        }
        is ScoreBelow -> score(gen.problems, state, Pos(chip.position(t))) < trig.z
        is TransitionCount -> script.since >= trig.k
        is Viewed -> false
    }

    /** [frozen]: the line up to there is definitive, the edit cannot change it. */
    private fun edit(t: Rational, ops: List<Op>, owner: String, source: String, frozen: Rational? = null): Boolean {
        val before = state
        val c = chip.position(t)
        val after: LineState?
        var why: String?
        if (source == "user") {
            val alone = alone(ops)
            val cons = if (alone) emptyList() else constraints()
            readEdited = readEdited || !alone
            val calc = calculate
            val r = when {
                alone -> userEdit(rules, before, ops, owner, c, cons)
                calc != null -> calc(rules, before, ops, owner, c, cons)
                else -> SolverLimits.limited(if (background) EDIT_LIMIT else null) { userEdit(rules, before, ops, owner, c, cons) }
            }
            after = r.first
            why = r.second
        } else {
            var a: LineState? = null
            try {
                a = applyEdit(rules, before, ops, owner)
                why = checkScriptEdit(before, a, Pos(c), ops, rules)
                if (why == null && frozen != null && !before.line.sameOn(a.line, Region.of(Interval.of(Pos.NEG_INF, Pos(frozen), false, true)))) {
                    why = "it would change the line where the script is definitive (up to $frozen)"
                }
            } catch (e: Refused) {
                why = e.message
            }
            after = a
        }
        if (why != null) {
            log.refuse(Triple(t, source, why))
            return false
        }
        log.changed(Change(t, Pos(chip.position(t)), source, before, after!!))
        state = after
        withinMemory(c)
        return true
    }

    /** Past the limit, half of what is left of the chip and not flat yet is flattened, then half of what is flat removed. */
    private fun withinMemory(c: Rational) {
        while (state.memory() > memoryLimit) {
            log.changes.clear()
            val starts = state.startsBefore(Pos(c))
            val ft = flatTo
            val gt = goneTo
            val layered = starts.filter { ft == null || it >= ft }
            val flat = starts.filter { ft != null && it < ft && (gt == null || it >= gt) }
            if (layered.size >= 2) {
                flatTo = layered[layered.size / 2]
                state = flattened(rules, state, flatTo!!, goneTo)
            } else if (flat.size >= 2) {
                goneTo = flat[flat.size / 2]
                state = flattened(rules, state, flatTo!!, goneTo)
            } else {
                break
            }
        }
    }

    private fun alone(ops: List<Op>): Boolean {
        val kinds = gen.kindsRead() ?: return false
        return ops.none { it.kind in kinds }
    }

    /** Off: no call runs the generator; on again: it runs once, from the line as it is. */
    fun switchGenerator(on: Boolean) {
        if (on == !generatorOff) return
        generatorOff = !on
        if (generatorOff) request = null else install(t, "generator on")
    }

    private fun constraints(): List<Constraint> = script.constraints.ifEmpty { gen.constraints }

    fun run(endTime: Rational): SimLog {
        advance(endTime)
        return log
    }

    /** Run the simulation from its current time up to [endTime]. */
    fun advance(endTime: Rational) {
        if (!started) start()
        while (t < endTime) {
            val t1 = minOf(nextInstant(t), endTime)
            sweep(t, t1)
            withinMemory(chip.position(t1))
            t = t1
            instant(t)
        }
    }

    /** The first instant, with these edits of the user (else those scheduled at 0). */
    fun start(edits: List<List<Op>>? = null) {
        started = true
        instant(t, first = true, edits = edits)
    }

    /** An edit of the user at the current time: null, or why it is refused. */
    fun editNow(ops: List<Op>): String? {
        val n = log.refusedCount
        if (!started) edit(t, ops, Owner.USER, "user") else instant(t, edits = listOf(ops))
        return log.refusedSince(n).firstOrNull { it.second == "user" }?.third
    }

    /** The modes changed from the chip's position on: revealed now. */
    fun modesChanged() = afterEdits(t, false, false, "user edit")

    /** New rules or problems: the line is computed again from the user's edits, and the generator runs again. */
    fun setGenerator(g: Generating) {
        gen = g
        rules = g.rules
        looks = null
        grid = rules.smallestOffset?.let { it / 2 }
        replace(state.history, why = "rules or problems changed", run = true)
    }

    private fun replace(history: List<Record>, why: String = "user edit", run: Boolean = false) {
        val before = state
        val after = rebuild(rules, history, ownedByOthers(before.claims), before.line)
        log.changed(Change(t, Pos(chip.position(t)), "user", before, after))
        state = after
        readEdited = true
        if (!started) {
            started = true
            instant(t, first = true, edits = emptyList())
        } else {
            afterEdits(t, false, true, why, run)
        }
    }

    private fun nextInstant(t: Rational): Rational {
        val c = chip.position(t)
        val cands = ArrayList<Rational>()
        for (e in userEdits) if (e.time > t) cands.add(e.time)
        val nxt = modes.transitions.map { it.first }.filter { it > c }
        if (nxt.isNotEmpty()) cands.add(chip.firstTime(nxt[0]))
        for (ev in script.events) {
            val tr = ev.trigger as? ChipIn ?: continue
            for (x in listOf(tr.lo, tr.hi)) if (x != Pos.INF && x > Pos(c)) cands.add(chip.firstTime(x.x))
        }
        val g = grid
        if (g != null && rules.modeRules.isNotEmpty()) cands.add(chip.firstTime(g * Rational.of((c / g).floor() + 1)))
        val req = request
        if (req != null && req.published) cands.add(req.paceAt)
        val lk = looks
        if (lk != null && lk.first === script) {
            for (e in lk.second.values) if (e != null && e.first != Pos.INF && e.first > Pos(c)) cands.add(chip.firstTime(e.first.x))
        }
        return cands.filter { it > t }.minOrNull() ?: Rational.INF
    }

    private fun instant(t: Rational, first: Boolean = false, edits: List<List<Op>>? = null) {
        var any = false
        readEdited = false
        for (ops in edits ?: userEdits.filter { it.time == t }.map { it.ops }) any = edit(t, ops, Owner.USER, "user") || any
        afterEdits(t, first, any, "user edit")
    }

    private fun afterEdits(t: Rational, first: Boolean, wasEdited: Boolean, why: String, run: Boolean = false) {
        started = true
        keepPace(t)
        var c = chip.position(t)
        val d = definitive
        if (background && d != null && c > d && script.rerunTriggers().any { it !is Edited }) install(t, "catch up")
        c = chip.position(t)
        val rev = modes.transitions.count { it.first <= c }
        script.since += rev - revealed
        revealed = rev
        if (run) install(t, why)
        edited = wasEdited && !first
        execute(t, null)
    }

    /** An iteration of the loop of this period, on its own thread ([apart]). */
    fun iterate(period: Rational) {
        if (started) execute(t, period)
    }

    private fun eventsOf(period: Rational?): List<Event> {
        if (period != null) return script.loops.filter { it.period == period }.flatMap { it.events }
        return if (apart) script.inStep() else script.events
    }

    /** The events of that loop (null: of what goes with the chip), each when it is true and was not. */
    private fun execute(t: Rational, period: Rational?) {
        val problems = gen.problems
        repeat(32) {
            var again = false
            var rerun: String? = null
            val sc = script
            val events = eventsOf(period)
            val lk = looks
            val lookMap: MutableMap<String, Pair<Pos, List<Any?>>?> = if (lk != null && lk.first === sc) lk.second else HashMap()
            looks = sc to lookMap
            val pushes = sc.pushes()
            for (ev in events) {
                val key = ev.toString()
                if (key in pushes) continue // (the mode rules keep it as the chip goes)
                var c = chip.position(t)
                val mode = modes.modeAt(Pos(c))
                if (!Watch.due(lookMap[key], ev.trigger, state, Pos(c), mode, problems)) continue
                val now = cond(reachOf(ev), t)
                lookMap[key] = Watch.look(ev.trigger, state, Pos(c), mode, problems)
                var rising = now && key !in sc.trueEvents
                if (now) sc.trueEvents.add(key) else sc.trueEvents.remove(key)
                if (ev.trigger is TransitionCount) rising = now && key !in sc.fired else if (ev.trigger is Edited || ev.trigger is ChipOn) rising = now
                if (!rising) continue
                sc.fired.add(key)
                if (ev.trigger is Edited) edited = false // this edit is answered
                for (a in ev.actions) {
                    when (a) {
                        is EditAction -> {
                            var ops = a.ops
                            if (a.fromChip) {
                                c = chip.position(t)
                                ops = ops.map { Op(it.action, it.kind, it.region.shift(Pos(c)), it.value) }
                            }
                            edit(t, ops, Owner.GEN, "script", frozen)
                        }
                        is ConstraintEditAction -> {
                            c = chip.position(t)
                            val hidden = sc.constraints.filter { !it.explicit }
                            edit(t, constraintOps(rules, state, Pos(c), hidden), Owner.GEN, "script")
                        }
                        is RerunAction -> rerun = when {
                            a.first -> "start"
                            ev.trigger is Edited -> "user edit"
                            ev.trigger is Uncovered -> "uncovered"
                            else -> "script"
                        }
                        is ShowLine -> shown++
                        is ChangeAction -> {
                            if (a.target == sc.partOf(ev)) {
                                log.refuse(Triple(t, "script", "a script does not change itself: ${a.target}"))
                            } else {
                                script = script.changed(a)
                                again = true
                            }
                        }
                    }
                }
                if (rerun != null || again) break
            }
            if (rerun != null) {
                install(t, rerun!!)
            } else if (!again) {
                if (period == null) settle(t)
                return
            }
        }
        throw AssertionError("reruns did not settle")
    }

    /** The mode rules at the chip's position, now; if the mode changed at this very position, what the old one did is undone. */
    private fun settle(t: Rational) {
        if (script.covered.isEmpty() && !script.live) return
        val c = chip.position(t)
        val x = Pos(c)
        val mode = modes.modeAt(x)
        val was = settled
        if (was != null && was[0] == x) {
            if (was[1] == mode && was[2] === script && was[4] === state) return
            if (was[1] != mode && was[4] === state && was[3] !== state) {
                log.changed(Change(t, x, "mode", state, was[3] as LineState, Region.point(x)))
                state = was[3] as LineState
            }
        }
        val before = state
        if (was != null && was[0] == x && was[1] == mode && chip.firstTime(c) < t) {
            kept = x // the chip stands here: what the user just did here is newer than it
            apply(t, mode, x, x)
            kept = null
        } else {
            through(t, mode, x, x)
        }
        settled = listOf(x, mode, script, before, state)
    }

    /** The script's stretches act on the chip positions of [t0, t1). */
    private fun sweep(t0: Rational, t1: Rational) {
        if (t1 <= t0 || (script.covered.isEmpty() && !script.live)) return
        val c0 = chip.position(t0)
        val c1 = chip.position(t1)
        val mode = modes.modeAt(Pos(c0))
        var x = Pos(c0)
        val hi = if (c1 == c0) x else (if (chip.firstTime(c1) < t1) Pos(c1) else Pos(c1) - Pos.EPS)
        val was = settled
        if (was != null && was[0] == x && was[1] == mode && was[2] === script && was[4] === state) x += Pos.EPS
        through(t0, mode, x, hi)
    }

    /** The chip goes through [x, hi] in this mode. */
    private fun through(t0: Rational, mode: String, x0: Pos, hi: Pos) {
        var x = x0
        while (x <= hi) {
            val st = stretchAt(mode, x, hi)
            if (st == null) {
                apply(t0, mode, x, hi)
                return
            }
            val d = Pos(shift!!)
            x = max(x, st.a + d)
            val end = if (st.b + d < hi) st.b + d else hi
            var after = movedState(st.stateAt(end - d), shift!!, state.history)
            if (after !== state) {
                val tt = maxOf(t0, chip.firstTime(x.x))
                after = modeWon(state, after)
                log.changed(Change(tt, x, "mode", state, after, Region.of(IntervalSet.sorted(listOf(Interval(x, end))))))
                state = after
            }
            x = end + Pos.EPS
            stretch = Triple(st, after, x)
        }
    }

    /** The mode rules on [x, hi], on the line as it is, where the script says so. */
    private fun apply(t0: Rational, mode: String, x: Pos, hi: Pos) {
        val s = script
        if (s.live) {
            applyOn(t0, mode, x, hi)
            return
        }
        var last: Pos? = null
        val parts = s.covered.filter { (lo, b, m) -> (m == mode || m == null) && lo <= hi && b >= x }.map { (lo, b, _) -> max(lo, x) to min(b, hi) }
            .sortedWith(compareBy({ it.first }, { it.second }))
        for ((a0, b) in parts) {
            val a = if (last == null) a0 else max(a0, last + Pos.EPS)
            if (a <= b) {
                applyOn(t0, mode, a, b)
                last = b
            }
        }
    }

    private fun applyOn(t0: Rational, mode: String, x: Pos, hi: Pos) {
        val mr = liveRules.getOrPut(mode) { ModeRules(rules, mode, script.constraints + pushesIn(script.pushes().values.toList(), mode)) }
        mr.kept = kept
        var made: LineState? = null
        for (ev in mr.act(state, x, hi)) {
            when (ev) {
                is Act.Change -> {
                    val r = ev.run
                    val tt = maxOf(t0, chip.firstTime(r.start.x))
                    made = r.after
                    val after = modeWon(state, r.after)
                    log.changed(Change(tt, r.start, "mode", state, after, r.at))
                    state = after
                }
                is Act.Refusal -> log.refuse(Triple(t0, "mode", ev.why))
            }
        }
        val pl = mr.plan
        if (made != null && pl != null && pl.last === made) pl.last = state // (the same line: the run goes on from it)
    }

    /** The state as a function of the chip's position p, from where it is; null if the script does not change the line as the chip goes. */
    fun ahead(): Ahead? {
        val c = chip.position(t)
        val mode = modes.modeAt(Pos(c))
        var x = Pos(c)
        val s = script
        if (!(s.live || s.covered.any { (lo, hi, m) -> lo <= x && x <= hi && (m == mode || m == null) })) return null
        val was = settled
        if (was != null && was[0] == x && was[1] == mode && was[4] === state) x += Pos.EPS
        val st0 = state
        val history = state.history
        val cur = stretch
        if (cur != null && cur.second === st0 && cur.third == x && cur.first.mode == mode && cur.first.plan != null) {
            val plan = cur.first.plan!!
            val d = shift!!
            return Ahead({ p ->
                val st = if (p - Pos(d) <= cur.first.b) plan.exact(p - Pos(d)) else null
                st?.let { movedState(it, d, history) }
            }, plan to d, { p -> plan.formulaUntil(p - Pos(d))?.let { min(it, cur.first.b) + Pos(d) } })
        }
        val plan = liveRules[mode]?.plan
        if (plan != null && plan.last === st0 && plan.next == x) return Ahead({ plan.exact(it) }, plan, { plan.formulaUntil(it) })
        return Ahead({ null }, null, { null })
    }

    /** How far the script's stretches are moved, decided the first time they are needed. */
    private fun align(x: Pos): Rational {
        val sh = shift
        if (sh != null) return sh
        val s = script
        val o = s.origin
        val d = if (o != null && x.k == 0L) x.x - o.x else Rational.ZERO
        val base = s.base
        shift = if (d.sign != 0 && base != null && sameStateSim(movedState(base, d, state.history), state)) d else Rational.ZERO
        return shift!!
    }

    /** The chip at x in this mode is where the script does not apply the mode rules. */
    private fun uncoveredAt(mode: String, x: Pos): Boolean {
        val covered = script.covered
        if (covered.isEmpty()) return false
        val d = align(x)
        val y = x - Pos(d)
        if (y > covered.maxOf { it.second }) return false
        return covered.none { (lo, hi, m) -> lo <= y && y <= hi && (m == mode || m == null) } && !script.live
    }

    /** The stretch of the script whose positions include the chip at x, if the line is the one it makes there. */
    private fun inside(mode: String, x: Pos): Stretch? {
        val ms = missed
        if (ms != null && ms.first === script && ms.second === state) return null
        val d = align(x)
        val y = x - Pos(d)
        for (st in script.stretches) {
            if (st.mode == mode && st.plan != null && st.a < y && y <= st.b) {
                if (sameStateSim(movedState(st.stateAt(y - Pos.EPS), d, state.history), state)) return st
                break
            }
        }
        missed = script to state
        return null
    }

    /** The stretch the chip goes through from x: the one it is in, or the first starting in [x, hi] on the line compiled for it. */
    private fun stretchAt(mode: String, x: Pos, hi: Pos): Stretch? {
        val d = align(x)
        val cur = stretch
        if (cur != null && cur.third == x && cur.second === state && cur.first.mode == mode && x - Pos(d) <= cur.first.b) return cur.first
        for (st in script.stretches) {
            if (x <= st.a + Pos(d) && st.a + Pos(d) <= hi && st.mode == mode && sameStateSim(movedState(st.before, d, state.history), state)) return st
        }
        return inside(mode, x)
    }
}

private fun sameStateSim(a: LineState, b: LineState): Boolean = a === b || (a.line == b.line && a.claims.same(b.claims))

/** The state moved by d (its claims and its line), with this history of the user. */
fun movedState(st: LineState, d: Rational, history: List<Record>): LineState {
    if (d.sign == 0) return st
    val p = Pos(d)
    val c = Claims()
    c.pos = st.claims.pos.mapValuesTo(LinkedHashMap()) { (_, dd) -> dd.mapValuesTo(LinkedHashMap()) { it.value.shift(p) } }
    c.neg = st.claims.neg.mapValuesTo(LinkedHashMap()) { (_, dd) -> dd.mapValuesTo(LinkedHashMap()) { it.value.shift(p) } }
    c.taken = st.claims.taken.mapValuesTo(LinkedHashMap()) { (_, dd) -> dd.mapValuesTo(LinkedHashMap()) { it.value.shift(p) } }
    return LineState(c, Line(st.line.vals.mapValues { (_, dd) -> dd.mapValues { it.value.shift(p) } }), history)
}
