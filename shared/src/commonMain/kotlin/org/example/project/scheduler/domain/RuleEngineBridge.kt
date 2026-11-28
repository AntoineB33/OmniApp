package org.example.project.scheduler.domain

import org.example.project.scheduler.model.RulePlacement
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.ruleline.Const
import org.example.project.scheduler.ruleline.Interval
import org.example.project.scheduler.ruleline.IntervalSet
import org.example.project.scheduler.ruleline.LineState
import org.example.project.scheduler.ruleline.Op
import org.example.project.scheduler.ruleline.Owner
import org.example.project.scheduler.ruleline.Pos
import org.example.project.scheduler.ruleline.ProblemContext
import org.example.project.scheduler.ruleline.Rational
import org.example.project.scheduler.ruleline.Region
import org.example.project.scheduler.ruleline.RuleSet
import org.example.project.scheduler.ruleline.Score
import org.example.project.scheduler.ruleline.ScoreProblem
import org.example.project.scheduler.ruleline.ScoreSearch
import org.example.project.scheduler.ruleline.ScoreTask
import org.example.project.scheduler.ruleline.applyEdit

/**
 * ADR 0018 step 7, its first stage: OmniApp's scheduling world ([ScheduleFill.Input]) as the rule engine's
 * (`scheduler/ruleline/`), so the two schedulers can be run on the same account and judged by the same score
 * (`RuleEngineShadowTest`). A fill can ask [search] for a competing seed ([ScheduleFill.Input.ruleEngine], off by
 * default), which the app's score keeps only where it is better.
 *
 * - The line's positions are **seconds after [Translated.originMillis]** (the fill's start, floored to the second),
 *   exact: a millisecond is 1/1000.
 * - Each task is a kind ([taskKind]), each restrictive period kind a kind ([periodKind]); a pre-placed block owned by
 *   nobody is the kind [NOBODY], which every task has a resilience of 0 to.
 * - The restrictive periods and the pre-placed blocks are the **user's** claims (the score reads a task the user put
 *   as that task's time alone); the frozen past is the generator's (served, not pre-placed).
 * - A task's resilience is written for **every** period kind, since the rule engine's default is 1 where the app's is
 *   [PeriodKinds.defaultResilience].
 *
 * Not carried, because the rule engine's score has no such input yet: a task's set of tasks ([PlanTask.credits]),
 * a rule state that changes over the fill ([ScheduleFill.Input.ruleStates]: `R` at the start is used), PRD §7 / §13
 * ([ScheduleFill.Input.refusedFirst], [ScheduleFill.Input.forcedFirst]), the minimum-time weight.
 */
internal object RuleEngineBridge {
    const val NOBODY = "nobody"

    /**
     * The user's rules about the screen (2026-11-28), in the rule language (its kinds are one word each): any screen
     * break is "no screen", and "no screen" is *("no computer unlocked" or "not on computer") and ("no phone unlocked"
     * or "not on phone")*. The four device kinds are what the modes stage will read from the devices; the breaks
     * themselves are kept by `ScreenBreaks` over [NO_SCREEN] (`BreakRulesShadowTest`).
     */
    val BREAK_RULES: List<String> = listOf(
        "(NoComputerUnlocked or NotOnComputer) and (NoPhoneUnlocked or NotOnPhone) → NoScreen",
        "Break20s → NoScreen",
        "Break5 → NoScreen",
        "Break15 → NoScreen",
    )

    const val NO_SCREEN = "NoScreen"

    fun taskKind(id: TaskId) = "task:" + id.value

    fun periodKind(kind: String) = "period:$kind"

    class Translated(
        val originMillis: Long,
        val rules: RuleSet,
        val state: LineState,
        val score: Score,
        val chip: Rational,
        val end: Rational,
        val taskOf: Map<String, TaskId>,
    ) {
        fun pos(millis: Long): Rational = Rational.of(millis - originMillis, 1000)

        fun millis(x: Rational): Long = originMillis + (x * Rational.of(1000)).floor()

        /** What the score reads as the user's: each task's own pre-placed time. */
        fun preplaced(s: LineState = state): Map<String, Region> = score.tasks.keys.associateWith { s.claims.owned(it, Owner.USER) }

        /** The line with these runs put on it by the generator. */
        fun with(runs: Map<String, List<Pair<Rational, Rational>>>): LineState {
            val ops = runs.filter { it.value.isNotEmpty() }.entries.sortedBy { it.key }.map { (k, v) -> Op.put(k, regionOf(v) - state.claims.owned(k, Owner.USER)) }.filter { it.region.isNotEmpty() }
            return if (ops.isEmpty()) state else applyEdit(rules, state, ops, Owner.GEN)
        }

        fun withPlacements(placements: List<ScheduleFill.Placement>): LineState =
            with(placements.groupBy { taskKind(it.taskId) }.mapValues { (_, v) -> v.map { pos(it.startMillis) to pos(it.endMillis) } })
    }

    fun regionOf(spans: List<Pair<Rational, Rational>>): Region =
        Region.of(IntervalSet.of(spans.filter { it.second > it.first }.map { (a, b) -> Interval.of(Pos(a), Pos(b), false, true) }))

    fun translate(input: ScheduleFill.Input): Translated {
        val origin = input.startMillis - input.startMillis.mod(1000L)
        fun pos(ms: Long) = Rational.of(ms - origin, 1000)
        val tasks = input.ruleState
        val periodKinds = input.periods.map { it.kind }.distinct().sorted()
        val scoreTasks = LinkedHashMap<String, ScoreTask>()
        for (t in tasks) {
            val res = LinkedHashMap<String, Double>()
            for (k in periodKinds) {
                val r = t.resilienceFor(k)
                if (r != 1.0) res[periodKind(k)] = r
            }
            res[NOBODY] = 0.0
            scoreTasks[taskKind(t.id)] = ScoreTask(Const(t.priority), Const(t.minimumMillis / 1000.0), resilience = res)
        }
        val rules = RuleSet(emptyList())
        val user = LinkedHashMap<String, MutableList<Pair<Rational, Rational>>>()
        for (p in input.periods) if (p.endMillis > p.startMillis) user.getOrPut(periodKind(p.kind)) { ArrayList() }.add(pos(p.startMillis) to pos(p.endMillis))
        for (b in input.blocks) user.getOrPut(b.taskId?.let { taskKind(it) } ?: NOBODY) { ArrayList() }.add(pos(b.startMillis) to pos(b.endMillis))
        val past = LinkedHashMap<String, MutableList<Pair<Rational, Rational>>>()
        for (h in input.history) {
            val id = h.taskId ?: continue
            past.getOrPut(taskKind(id)) { ArrayList() }.add(pos(h.startMillis) to pos(h.endMillis))
        }
        var state = LineState.empty()
        val userOps = user.entries.sortedBy { it.key }.map { (k, v) -> Op.put(k, regionOf(v)) }.filter { it.region.isNotEmpty() }
        if (userOps.isNotEmpty()) state = applyEdit(rules, state, userOps, Owner.USER)
        // (where the user's own block is, the time is already the task's: the past adds only what is outside it)
        val pastOps = past.entries.sortedBy { it.key }.map { (k, v) -> Op.put(k, regionOf(v) - state.claims.owned(k, Owner.USER)) }.filter { it.region.isNotEmpty() }
        if (pastOps.isNotEmpty()) state = applyEdit(rules, state, pastOps, Owner.GEN)
        return Translated(
            origin, rules, state, Score(scoreTasks), pos(input.startMillis), pos(input.horizonMillis),
            tasks.associate { taskKind(it.id) to it.id },
        )
    }

    /** The rule engine's best continuation of [input] found in [seconds], as the app's placements. */
    fun search(input: ScheduleFill.Input, seconds: Double): List<RulePlacement> {
        val t = translate(input)
        val ctx = object : ProblemContext {
            override val c = Pos(t.chip)
            override val state = t.state
            override val rules = t.rules
            override fun expected(s: LineState, kind: String, weights: Any?, w: IntervalSet) = s.line.integral(kind, w)
        }
        val found = ScoreSearch.search(ScoreProblem(t.score, t.end - t.chip), ctx, t.state, seconds)
        return runsOf(t, found).map { (id, a, b) -> RulePlacement(id, a, b, null) }
    }

    /** Wall-clock runs of the rule engine's answer, as the app's placements' shape (task, start, end). */
    fun runsOf(t: Translated, runs: Map<String, List<Pair<Rational, Rational>>>): List<Triple<TaskId, Long, Long>> =
        runs.flatMap { (k, v) -> v.map { (a, b) -> Triple(t.taskOf.getValue(k), t.millis(a), t.millis(b)) } }.sortedBy { it.second }
}
