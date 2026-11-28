package org.example.project.scheduler.ruleline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.example.project.scheduler.line.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port of `C:\code\line_manager\scripts\solver.py` and `sweep.py` against the reference:
 * `resources/ruleline/solve-golden.json` holds random rule sets (the reference's own texts and random ones) with random
 * claims, and the line the reference computed — or the conflict or undetermined case it raised, message included —
 * plus whether it found the set satisfiable. Cases the reference took close to a second over are left out, so no
 * answer here depends on a time limit.
 *
 * Regenerate after a change to the reference: `python -I scripts/ruleline-golden.py`.
 */
class SolverGoldenTest {

    private val data: JsonObject by lazy {
        val text = javaClass.classLoader.getResource("ruleline/solve-golden.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    private fun pos(e: JsonElement): Pos {
        val a = e.jsonArray
        return when (val x = a[0].jsonPrimitive.content) {
            "inf" -> Pos.INF
            "-inf" -> Pos.NEG_INF
            else -> Pos(Rational.parse(x), a[1].jsonPrimitive.long)
        }
    }

    private fun set(e: JsonElement) = IntervalSet.of(e.jsonArray.map { it.jsonArray.let { p -> Interval(pos(p[0]), pos(p[1])) } })

    private fun region(e: JsonElement): Region {
        val o = e.jsonObject
        return Region.make(
            pos(o.getValue("lo")), pos(o.getValue("hi")), set(o.getValue("core")),
            pos(o.getValue("pl")), set(o.getValue("left")), pos(o.getValue("pr")), set(o.getValue("right")),
        )
    }

    private fun outcome(rs: RuleSet, prob: Problem): String = try {
        "line\n" + solve(rs, prob)
    } catch (e: Conflict) {
        "Conflict: " + e.message
    } catch (e: Undetermined) {
        "Undetermined: " + e.message
    } catch (e: IncompatiblePeriods) {
        "IncompatiblePeriods"
    } catch (e: RuleError) {
        "error RuleError"
    } catch (e: IllegalArgumentException) {
        "error ValueError"
    }

    @Test
    fun linesAreSolvedAsTheReferenceSolvesThem() {
        val cases = data.getValue("cases").jsonArray
        assertTrue(cases.size >= 100)
        val messages = ArrayList<String>()
        var failures = 0
        for ((n, c0) in cases.withIndex()) {
            val c = c0.jsonObject
            val texts = c.getValue("texts").jsonArray.map { it.jsonPrimitive.content }
            val rs = ruleSetOf(texts)
            val p = c.getValue("pos").jsonObject.mapValues { (_, by) ->
                by.jsonObject.entries.associate { (v, g) -> parseFraction(v) to region(g) }
            }
            val ng = c.getValue("neg").jsonObject.mapValues { region(it.value) }
            val want = c.getValue("out").jsonPrimitive.content
            val got = outcome(rs, Problem(p, ng))
            if (want != got) {
                failures++
                if (messages.size < 6) messages.add("case $n $texts\n--- want\n$want\n--- got\n$got")
            }
        }
        assertEquals(0, failures, messages.joinToString("\n\n"))
    }

    @Test
    fun ruleSetsAreFoundSatisfiableAsTheReferenceFindsThem() {
        val cases = data.getValue("cases").jsonArray
        val messages = ArrayList<String>()
        var failures = 0
        val seen = HashSet<List<String>>()
        for (c0 in cases) {
            val c = c0.jsonObject
            val want = c.getValue("satisfiable").jsonPrimitive.content
            if (want == "skip") continue // (the reference's answer was too close to its own time limit)
            val texts = c.getValue("texts").jsonArray.map { it.jsonPrimitive.content }
            if (!seen.add(texts)) continue
            val rs = ruleSetOf(texts)
            val got = satisfiable(rs) ?: "satisfiable"
            if (want != got) {
                failures++
                if (messages.size < 6) messages.add("$texts: want $want, got $got")
            }
        }
        assertEquals(0, failures, messages.joinToString("\n"))
    }
}
