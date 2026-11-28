package org.example.project.scheduler

import org.example.project.scheduler.domain.RuleEngineBridge
import org.example.project.scheduler.domain.ScheduleFill
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.ruleline.Instant
import org.example.project.scheduler.ruleline.IntervalSet
import org.example.project.scheduler.ruleline.LineState
import org.example.project.scheduler.ruleline.Pos
import org.example.project.scheduler.ruleline.ProblemContext
import org.example.project.scheduler.ruleline.ScoreProblem
import org.example.project.scheduler.ruleline.ScoreSearch
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.time.measureTimedValue

/**
 * ADR 0018 step 7, its measuring stage: OmniApp's scheduler and the rule engine's on the same real account, judged by
 * the rule engine's score (`docs/scheduler_score.md`, ported from the reference). Skipped unless `OMNIAPP_SHADOW_DB`
 * names a COPY of a release DB (never the live file: CLAUDE.md). Prints; asserts nothing yet.
 */
class RuleEngineShadowTest {

    @Test
    fun bothSchedulersOnARealAccount() {
        val path = System.getenv("OMNIAPP_SHADOW_DB") ?: return
        require(!path.replace('\\', '/').contains(".omniapp-release/")) { "a copy, never the live DB" }
        val payload = DriverManager.getConnection("jdbc:sqlite:" + File(path).absolutePath).use { c ->
            c.createStatement().executeQuery("SELECT payload FROM app_state").use { r -> r.next(); r.getString(1) }
        }
        val state = SchedulerStateCodec.decode(payload)!!
        val now = System.currentTimeMillis()
        val hours = (System.getenv("OMNIAPP_SHADOW_HOURS") ?: "24").toLong()
        var input: ScheduleFill.Input? = null
        ScheduleFill.observeInput = { if (input == null) input = it }
        val appFill = try {
            measureTimedValue { SchedulerDomain.fillSchedule(state, now, horizonMillis = now + hours * 3_600_000) }
        } finally {
            ScheduleFill.observeInput = null
        }
        val inp = input!!
        println("account: ${inp.ruleState.size} tasks, ${inp.periods.size} periods, ${inp.blocks.size} blocks, ${inp.history.size} past runs; " +
            "fill ${(inp.horizonMillis - inp.startMillis) / 60_000} min from ${inp.startMillis}, looking back ${inp.lookbackMillis / 3_600_000} h")
        val appRuns = measureTimedValue { ScheduleFill.run(inp) }
        println("app: fillSchedule ${appFill.duration}, ScheduleFill.run ${appRuns.duration}, ${appRuns.value.placements.size} runs")

        val t = measureTimedValue { RuleEngineBridge.translate(inp) }
        println("translate: ${t.duration}")
        val tr = t.value
        fun judge(label: String, line: org.example.project.scheduler.ruleline.LineState) {
            val r = measureTimedValue { tr.score.evaluate(line.line, tr.chip, tr.end, tr.preplaced()) }
            println("  $label: D=${r.value.d} J=${r.value.j} (J1 ${r.value.j1}, J2 ${r.value.j2}, J3 ${r.value.j3}) [scored in ${r.duration}]")
        }
        judge("app's plan", tr.withPlacements(appRuns.value.placements))
        val inst = measureTimedValue { Instant(tr.rules, tr.score).schedule(tr.state, tr.chip, tr.end) }
        println("rule engine: instant schedule ${inst.duration}, ${inst.value.values.sumOf { it.size }} runs")
        judge("instant schedule", tr.with(inst.value))
        var searched: Map<String, List<Pair<org.example.project.scheduler.ruleline.Rational, org.example.project.scheduler.ruleline.Rational>>> = emptyMap()
        for (budget in (System.getenv("OMNIAPP_SHADOW_BUDGETS") ?: "2,10,30").split(",").map { it.trim().toDouble() }) {
            val ctx = object : ProblemContext {
                override val c = Pos(tr.chip)
                override val state = tr.state
                override val rules = tr.rules
                override fun expected(s: LineState, kind: String, weights: Any?, w: IntervalSet) = s.line.integral(kind, w)
            }
            val found = measureTimedValue { ScoreSearch.search(ScoreProblem(tr.score, tr.end - tr.chip), ctx, tr.state, budget) }
            println("rule engine: score search, $budget s budget, took ${found.duration}, ${found.value.values.sumOf { it.size }} runs")
            judge("score search $budget s", tr.with(found.value))
            searched = found.value
        }
        // Judged by the APP's score: the rule engine's plan as a seed of the app's fill, kept only if it scores better there.
        val seed = RuleEngineBridge.runsOf(tr, searched).map { (id, a, b) -> org.example.project.scheduler.model.RulePlacement(id, a, b, null) }
        val withSeed = ScheduleFill.run(inp.copy(seeds = listOf(seed)))
        println("app's score: own plan ${appRuns.value.cost}, with the rule engine's plan as a seed ${withSeed.cost}; " +
            "${inp.ruleState.count { it.credits.isNotEmpty() }} tasks with a set of tasks, rule state ${if (inp.ruleStates == null) "constant" else "changing"} over the fill, " +
            "forced first ${inp.forcedFirst}, refused first ${inp.refusedFirst}, minimum weight ${inp.minimumWeight}")
        // The app's fill as the engine runs it, given search time: its own search alone, then with the rule engine's seed.
        val grant = (System.getenv("OMNIAPP_SHADOW_GRANT") ?: "20000").toLong()
        val alone = measureTimedValue { ScheduleFill.run(inp.copy(budget = org.example.project.scheduler.domain.SearchBudget.of(grant), ruleEngine = false)) }
        val both = measureTimedValue { ScheduleFill.run(inp.copy(budget = org.example.project.scheduler.domain.SearchBudget.of(grant), ruleEngine = true)) }
        for (k in 0 until 4) {
            val one = measureTimedValue { RuleEngineBridge.search(inp, 10.0) }
            println("rule-engine plan $k (${one.duration}): as a seed ${ScheduleFill.run(inp.copy(seeds = listOf(one.value), ruleEngine = false)).cost}")
        }
        val fixed = RuleEngineBridge.search(inp, 10.0)
        val plainSeeded = ScheduleFill.run(inp.copy(seeds = listOf(fixed), ruleEngine = false))
        val grantedSeeded = ScheduleFill.run(inp.copy(seeds = listOf(fixed), ruleEngine = false, budget = org.example.project.scheduler.domain.SearchBudget.of(grant)))
        println("one rule-engine plan as a seed: no search time ${plainSeeded.cost}, granted $grant ms ${grantedSeeded.cost}")
        println("app's fill granted $grant ms: its search alone ${alone.value.cost} in ${alone.duration}; with the rule engine ${both.value.cost} in ${both.duration}")
        println("first runs, app: " + appRuns.value.placements.take(6).joinToString { "${it.taskId.value.takeLast(6)} ${(it.startMillis - inp.startMillis) / 60_000}-${(it.endMillis - inp.startMillis) / 60_000}" })
        println("first runs, rule engine: " + RuleEngineBridge.runsOf(tr, inst.value).take(6).joinToString { "${it.first.value.takeLast(6)} ${(it.second - inp.startMillis) / 60_000}-${(it.third - inp.startMillis) / 60_000}" })
    }
}
