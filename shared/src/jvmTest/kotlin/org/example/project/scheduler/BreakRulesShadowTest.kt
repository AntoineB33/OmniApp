package org.example.project.scheduler

import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.ruleline.Interval
import org.example.project.scheduler.ruleline.IntervalSet
import org.example.project.scheduler.ruleline.LineState
import org.example.project.scheduler.ruleline.Op
import org.example.project.scheduler.ruleline.Owner
import org.example.project.scheduler.ruleline.Pos
import org.example.project.scheduler.ruleline.Rational
import org.example.project.scheduler.ruleline.Region
import org.example.project.scheduler.ruleline.ScreenBreaks
import org.example.project.scheduler.ruleline.applyEdit
import org.example.project.scheduler.ruleline.constraintOps
import org.example.project.scheduler.ruleline.ruleSetOf
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.time.measureTimedValue

/**
 * ADR 0018 step 7, the breaks: OmniApp's `BreakMachine` and the rule engine's `ScreenBreaks` placing the account's three
 * screen breaks over the same environment — its real known "no screen" periods ahead, from the same rested start (a
 * 15-minute rest ending now, which is the machine's [BreakMachine.initial]). Skipped unless `OMNIAPP_SHADOW_DB` names a
 * COPY of a release DB. Prints where the two differ; asserts nothing yet.
 */
class BreakRulesShadowTest {

    @Test
    fun bothPlaceTheBreaksOnARealAccount() {
        val path = System.getenv("OMNIAPP_SHADOW_DB") ?: return
        require(!path.replace('\\', '/').contains(".omniapp-release/")) { "a copy, never the live DB" }
        val payload = DriverManager.getConnection("jdbc:sqlite:" + File(path).absolutePath).use { c ->
            c.createStatement().executeQuery("SELECT payload FROM app_state").use { r -> r.next(); r.getString(1) }
        }
        val state = SchedulerStateCodec.decode(payload)!!
        val now0 = System.currentTimeMillis()
        val now = now0 - now0.mod(1000L)
        val hours = (System.getenv("OMNIAPP_SHADOW_HOURS") ?: "24").toLong()
        val until = now + hours * 3_600_000
        val specs = SchedulerDomain.dynamicPeriodSpecs(state.screenBreaks.ifEmpty { SchedulerDomain.DEFAULT_SCREEN_BREAKS })
        println("breaks: " + specs.joinToString { "${it.label} ${it.durationMillis / 1000} s every ${it.cadenceMillis / 60_000} min" })
        val env = SchedulerDomain.breakEnvironment(state, now, until, TimeZone.currentSystemDefault())
        val chains = BreakMachine.chainsOf(env.periods).filter { it.endMillis > now && it.startMillis < until }
        println("known no-screen ahead: " + chains.joinToString { "${(it.startMillis - now) / 60_000}..${(it.endMillis - now) / 60_000} min" })

        // The app's machine, as the calendar draws ahead ([SchedulerDomain.screenBreakPanels]).
        val app = measureTimedValue { BreakMachine.predictExpected(BreakMachine.initial(now, specs), until + 1, BreakMachine.chainsOf(env.periods), specs) }
        val appBreaks = app.value.filter { it.endMillis > now && it.startMillis < until }.map { Triple(it.label, it.startMillis - now, it.endMillis - now) }

        // The rule engine's ScreenBreaks: each break is "no screen", positions in seconds after now.
        val kindOf = mapOf(DynamicPeriods.LABEL_20S to "Break20s", DynamicPeriods.LABEL_5MIN to "Break5", DynamicPeriods.LABEL_15MIN to "Break15")
        val rules = ruleSetOf(specs.map { "${kindOf.getValue(it.label)} → NoScreen" })
        fun sec(ms: Long) = Rational.of(ms, 1000)
        val noScreen = (chains.map { sec(maxOf(it.startMillis, now - 900_000) - now) to sec(minOf(it.endMillis, until) - now) } + (sec(-900_000) to Rational.ZERO))
        val region = Region.of(IntervalSet.of(noScreen.filter { it.second > it.first }.map { (a, b) -> Interval.of(Pos(a), Pos(b), false, true) }))
        val base = applyEdit(rules, LineState.empty(), listOf(Op.put("NoScreen", region)), Owner.USER)
        val sb = ScreenBreaks(specs.map { Triple(kindOf.getValue(it.label), sec(it.durationMillis), sec(it.cadenceMillis)) }, "NoScreen",
            listOf("Break5", "Break15"))
        sb.bind(rules)
        val engine = measureTimedValue {
            val ops = constraintOps(rules, base, Pos(Rational.ZERO), listOf(sb), Pos(sec(until - now)))
            if (ops.isEmpty()) base else applyEdit(rules, base, ops, Owner.GEN)
        }
        val labelOf = kindOf.entries.associate { (k, v) -> v to k }
        val engineBreaks = kindOf.values.flatMap { k ->
            engine.value.line.presence(k).on(Pos(Rational.ZERO), Pos(sec(until - now))).intervals.map {
                Triple(labelOf.getValue(k), (it.lo.x * Rational.of(1000)).floor(), (it.hi.x * Rational.of(1000)).floor())
            }
        }
        println("app: ${appBreaks.size} breaks in ${app.duration}; rule engine: ${engineBreaks.size} in ${engine.duration}")
        for (label in specs.map { it.label }) {
            val a = appBreaks.filter { it.first == label }.map { it.second to it.third }.sortedBy { it.first }
            val e = engineBreaks.filter { it.first == label }.map { it.second to it.third }.sortedBy { it.first }
            val onlyApp = a.filter { x -> e.none { kotlin.math.abs(it.first - x.first) <= 1000 } }
            val onlyEngine = e.filter { x -> a.none { kotlin.math.abs(it.first - x.first) <= 1000 } }
            fun fmt(l: List<Pair<Long, Long>>) = l.take(12).joinToString { "%.1f+%ds".format(it.first / 60_000.0, (it.second - it.first) / 1000) }
            println("  $label: app ${a.size}, rule engine ${e.size}, same start ${a.size - onlyApp.size}")
            if (onlyApp.isNotEmpty()) println("    only the app (min after now): ${fmt(onlyApp)}")
            if (onlyEngine.isNotEmpty()) println("    only the rule engine: ${fmt(onlyEngine)}")
        }
    }
}
