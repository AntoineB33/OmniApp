package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The scheduler's steps — a port of `C:\code\line_manager\scripts\pace.py` (`docs/scheduler/scheduler_requirements.md`:
 * "Big scheduler. When it runs, it has a minimum pace to schedule the future", t_def growing by 10 minutes every 10
 * seconds).
 *
 * From the chip c, step n has a budget (10 s) to deliver the best schedule it can for its slice
 * [c + 10min (n + 1), c + 10min (n + 2)]; the first 10 minutes are the instant schedule's. A step goes from the big
 * picture (the instant schedule of the hours ahead) to the hours ahead (seeds improved and shaken while their part of
 * the budget lasts) to the slice (finer moves), then proves the slice (no plan of up to 3 runs on whole minutes scores
 * lower). What a step finds depends on its budget, so on the machine: only the slices, the committing and the first
 * 10 minutes are held to the reference exactly.
 */
class Pace(val rules: RuleSet, val score: Score) {
    val instant = Instant(rules, score)

    fun slice(chip: Rational, n: Int): Pair<Rational, Rational> {
        val a = chip + Rational.of(STEP * (n + 1))
        return a to (a + Rational.of(STEP))
    }

    /** (the edit committing step n's slice, what the step did). */
    fun step(state: LineState, chip: Rational, n: Int, budget: Double? = null): Pair<List<Op>, Map<String, Any?>> {
        val began = TimeSource.Monotonic.markNow()
        val b0 = budget ?: BUDGET
        val (a, b) = slice(chip, n)
        val end = b + Rational.of(AHEAD)
        val big = instant.schedule(state, a, end)
        val problem = ScoreProblem(score, end - a)
        val ctx = object : ProblemContext {
            override val c = Pos(a)
            override val state = state
            override val rules = this@Pace.rules
            override fun expected(s: LineState, kind: String, weights: Any?, w: IntervalSet) = s.line.integral(kind, w)
        }
        val m = ScoreModel(problem, ctx, state, b0 * HOURS)
        val info = LinkedHashMap<String, Any?>()
        info["slice"] = a to b
        if (m.bigU <= 0) return commit(state, emptyMap(), a, b) to info
        val plan = LinkedHashMap<String, List<Pair<Double, Double>>>()
        for (i in score.tasks.keys) {
            val v = state.claims.owned(i, Owner.GEN).on(Pos(a), Pos(end)).intervals.map { maxOf(it.lo.x, a).toDoubleValue() to minOf(it.hi.x, end).toDoubleValue() }
            if (v.isNotEmpty()) plan[i] = v
        }
        val shown = instant.schedule(state, a, end, start = a)
        fun wall(x: Map<String, List<Pair<Rational, Rational>>>) = x.mapValues { (_, v) -> v.map { (p, q) -> p.toDoubleValue() to q.toDoubleValue() } }
        val seeds = listOf(m.fromWall(wall(big)), m.fromWall(wall(shown))) + (if (plan.isNotEmpty()) listOf(m.fromWall(plan)) else emptyList()) +
            ScoreSearch.SEEDS.take(2).map { m.stride(it) }
        val costs = seeds.map { m.cost(it) }
        info["big picture"] = costs[0]
        var runs: List<ScoreRun>? = null
        var key: Pair<Double, Double>? = null
        val order = costs.indices.sortedWith(compareBy<Int>({ costs[it].first }, { costs[it].second }, { it }))
        for (k in order) {
            val got = m.improve(seeds[k])
            val c = m.cost(got)
            if (key == null || compareValuesBy(c, key, { it.first }, { it.second }) < 0) {
                runs = got
                key = c
            }
            if (m.outOfTime()) break
        }
        val rnd = PyRandom(n.toLong()) // (the same step decides the same: no chance in what it publishes)
        runs = m.deepen(runs!!, rnd)
        info["hours ahead"] = m.cost(runs)
        m.deadline = began + (b0 * SLICE * 1000).toLong().milliseconds
        val focus = m.uOf(a.toDoubleValue()) to m.uOf(b.toDoubleValue())
        runs = m.improve(runs, focus = focus, fraction = 8)
        runs = m.deepen(runs, rnd, focus = focus, fraction = 8)
        m.deadline = began + (b0 * 1000).toLong().milliseconds
        val (certified, proved, looked) = m.certify(runs, focus.second, grid = 60.0, maxRuns = 3)
        info["slice done"] = m.cost(certified)
        info["proved"] = proved
        info["plans looked at"] = looked
        info["seconds"] = began.elapsedNow().inWholeMilliseconds / 1000.0
        info["planned"] = end
        return commit(state, m.toWall(certified), a, end) to info
    }

    /** The edit: on [a, b], the generator's tasks off, the step's put. */
    fun commit(state: LineState, wall: Map<String, List<Pair<Rational, Rational>>>, a: Rational, b: Rational): List<Op> {
        val w = Region.of(Interval.of(Pos(a), Pos(b), false, true))
        val ops = ArrayList<Op>()
        for (i in score.tasks.keys.sorted()) {
            val mine = state.claims.owned(i, Owner.GEN) and w
            if (mine.isNotEmpty()) ops.add(Op.take(i, mine))
            val got = wall[i].orEmpty().filter { (x, y) -> y > a && x < b }.map { (x, y) -> snap(maxOf(x, a), a, b) to snap(minOf(y, b), a, b) }.filter { (x, y) -> y > x }
            if (got.isNotEmpty()) ops.add(Op.put(i, Region.of(IntervalSet.of(got.map { (x, y) -> Interval.of(Pos(x), Pos(y), false, true) }))))
        }
        return ops
    }

    companion object {
        const val STEP = 600L
        const val BUDGET = 10.0
        const val AHEAD = 4L * 3600
        const val HOURS = 0.4
        const val SLICE = 0.7

        /** A position the model computed in floats, on the hundredth of a second, and on the slice's ends when that close. */
        fun snap(x: Rational, a: Rational, b: Rational): Rational {
            val y = Rational.of(pyRound(x.toDoubleValue() * 100), 100)
            for (e in listOf(a, b)) if ((y - e).abs() <= Rational.of(1, 100)) return e
            return y
        }
    }
}

/**
 * A rules generator whose run is the scheduler's steps, for problems that are OmniApp's score: its first script is an
 * ordinary generator's first stage without the score (the constraints' edits: the breaks) with the instant schedule of
 * the first 10 minutes; then each step adds its slice, [steps] of them (3 hours), the last final.
 */
class Stepped(rules: RuleSet, problems: List<Pair<OptProblem, Rational>>, q: Double, constraints: List<Constraint> = emptyList(), budget: Double? = null, steps: Int? = null) : Generating {
    val base: RulesGenerator
    val score: Score
    override val problems: List<Pair<OptProblem, Rational>> = problems.toList()
    val budget: Double = budget ?: Pace.BUDGET
    val steps: Int = steps ?: STEPS

    init {
        val sc = problems.map { it.first }.filterIsInstance<ScoreProblem>()
        if (sc.isEmpty()) throw IllegalArgumentException("the steps are those of OmniApp's score: a ScoreProblem")
        base = RulesGenerator(rules, problems.filter { it.first !is ScoreProblem }, q, constraints = constraints)
        score = sc[0].score
    }

    override val rules: RuleSet get() = base.rules
    override val constraints: List<Constraint> get() = base.constraints
    override val window: Rational get() = base.window
    override val q: Double get() = base.q
    override fun margin(): Rational? = base.margin()
    override fun kindsRead(): Set<String>? = base.kindsRead()
    override fun constraintEvents(state: LineState, c: Rational, until: Pos?): List<Event> = base.constraintEvents(state, c, until)

    /** What the script at the start of a run puts at once: the instant schedule of the first 10 minutes. */
    override fun firstEdits(state: LineState, c: Rational): List<List<Op>> {
        val pace = Pace(rules, score)
        val quick = pace.instant.schedule(state, c, c + Rational.of(Pace.STEP))
        val ops = pace.commit(state, quick, c, c + Rational.of(Pace.STEP))
        return if (ops.isNotEmpty()) listOf(ops) else emptyList()
    }

    override fun run(state: LineState, chip: Rational, known: ModeKnowledge): Pair<Script, RunStats> = stages(state, chip, known, null).last()

    override fun stages(state: LineState, chip: Rational, known: ModeKnowledge, previous: Script?): Sequence<Pair<Script, RunStats>> = sequence {
        val c = chip
        val (script, stats) = base.stages(state, c, known, previous).first()
        var st = script.base ?: state
        val pace = Pace(rules, score)
        var events = script.events
        fun publish(ops: List<Op>, st: LineState, label: String, definitive: Pos?, final: Boolean): Pair<Script, RunStats> {
            if (ops.isNotEmpty()) events = events + Event(ChipIn(Pos(c), Pos.INF), listOf(EditAction(ops)))
            return script.copy(base = st).carrying(script).withEvents(events) to stats.copy(chosen = label, definitive = definitive, final = final)
        }
        val quick = pace.instant.schedule(st, c, c + Rational.of(Pace.STEP))
        var ops = pace.commit(st, quick, c, c + Rational.of(Pace.STEP))
        try {
            if (ops.isNotEmpty()) st = applyEdit(rules, st, ops, Owner.GEN)
        } catch (e: Refused) {
            ops = emptyList()
        }
        yield(publish(ops, st, "the instant schedule of the first 10 minutes", Pos(c + Rational.of(Pace.STEP)), steps == 0))
        for (n in 0 until steps) {
            val (o, info) = pace.step(st, c, n, budget)
            ops = o
            try {
                if (ops.isNotEmpty()) st = applyEdit(rules, st, ops, Owner.GEN)
            } catch (e: Refused) {
                ops = emptyList()
            }
            @Suppress("UNCHECKED_CAST")
            val sl = info["slice"] as Pair<Rational, Rational>
            yield(publish(ops, st, "step $n", Pos(sl.second), n == steps - 1))
        }
    }

    companion object {
        const val STEPS = 18
    }
}
