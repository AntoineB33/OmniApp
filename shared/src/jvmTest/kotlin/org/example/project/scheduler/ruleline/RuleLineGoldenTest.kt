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
 * The port of `C:\code\line_manager\scripts\intervals.py` and `region.py` against the reference itself:
 * `resources/ruleline/positions-regions-golden.json` holds random interval sets and regions (periodic tails, ε
 * periods, tails that only repeat together after a long period, which the reference keeps lazy) with the text of
 * every operation the reference ran on them. The port must print the same text: canonical forms print equal.
 *
 * Regenerate after a change to the reference: `python -I scripts/ruleline-golden.py`.
 */
class RuleLineGoldenTest {

    private val data: JsonObject by lazy {
        val text = javaClass.classLoader.getResource("ruleline/positions-regions-golden.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    private fun pos(e: JsonElement): Pos {
        val a = e.jsonArray
        val x = a[0].jsonPrimitive.content
        return when (x) {
            "inf" -> Pos.INF
            "-inf" -> Pos.NEG_INF
            else -> Pos(Rational.parse(x), a[1].jsonPrimitive.long)
        }
    }

    private fun iv(e: JsonElement): Interval = e.jsonArray.let { Interval(pos(it[0]), pos(it[1])) }

    private fun set(e: JsonElement): IntervalSet = IntervalSet.of(e.jsonArray.map { iv(it) })

    private fun region(e: JsonElement): Region {
        val o = e.jsonObject
        return Region.make(
            pos(o.getValue("lo")), pos(o.getValue("hi")), set(o.getValue("core")),
            pos(o.getValue("pl")), set(o.getValue("left")), pos(o.getValue("pr")), set(o.getValue("right")),
        )
    }

    private fun safe(f: () -> Any): String = try {
        when (val v = f()) {
            is Rational -> if (v.isInfinite) (if (v.sign > 0) "+inf" else "-inf") else v.toString()
            is Long -> if (v == INFINITELY_MANY) "+inf" else v.toString()
            else -> v.toString()
        }
    } catch (e: IncompatiblePeriods) {
        "IncompatiblePeriods"
    }

    private fun check(kind: String, i: Int, expected: JsonObject, actual: Map<String, () -> Any>) {
        for ((name, f) in actual) {
            val want = expected.getValue(name).jsonPrimitive.content
            assertEquals(want, safe(f), "$kind case $i, $name")
        }
    }

    @Test
    fun intervalSetsMatchTheReference() {
        val cases = data.getValue("intervals").jsonArray
        assertTrue(cases.size >= 100)
        cases.forEachIndexed { i, c0 ->
            val c = c0.jsonObject
            val a = set(c.getValue("a"))
            val b = set(c.getValue("b"))
            val many = set(c.getValue("many"))
            val p = pos(c.getValue("p"))
            val d = pos(c.getValue("d"))
            val amount = Rational.parse(c.getValue("amount").jsonPrimitive.content)
            check(
                "interval", i, c.getValue("results").jsonObject,
                mapOf(
                    "a" to { a }, "b" to { b },
                    "or" to { a or b }, "and" to { a and b }, "sub" to { a - b }, "comp" to { a.complement() },
                    "many_or" to { many or a }, "many_and" to { many and a }, "many_sub" to { many - a }, "sub_many" to { a - many },
                    "sum" to { a + b }, "neg" to { -a }, "shift" to { a.shift(d) },
                    "contains" to { a.contains(p) }, "subset" to { a.isSubsetOf(b) },
                    "measure" to { a.measure() }, "points" to { a.points() },
                    "inf" to { a.inf() }, "sup" to { a.sup() }, "bounded" to { a.isBounded() },
                    "take" to { if (a.isBounded()) a.take(amount) else "" },
                    "take_right" to { if (a.isBounded()) a.take(amount, true) else "" },
                    "p_plus_d" to { p + d }, "p_minus_d" to { p - d },
                ),
            )
        }
    }

    @Test
    fun regionsMatchTheReference() {
        val cases = data.getValue("regions").jsonArray
        assertTrue(cases.size >= 100)
        cases.forEachIndexed { i, c0 ->
            val c = c0.jsonObject
            val a = region(c.getValue("a"))
            val b = region(c.getValue("b"))
            val p = pos(c.getValue("p"))
            val d = pos(c.getValue("d"))
            val w = c.getValue("w").jsonArray.let { pos(it[0]) to pos(it[1]) }
            val iv = iv(c.getValue("iv"))
            check(
                "region", i, c.getValue("results").jsonObject,
                mapOf(
                    "a" to { a }, "b" to { b },
                    "or" to { a or b }, "and" to { a and b }, "sub" to { a - b },
                    "comp" to { a.complement() }, "shift" to { a.shift(d) },
                    "edges" to { a.leftEdges() }, "dilate" to { a.dilate(iv) },
                    "contains" to { a.contains(p) }, "eq" to { a == b }, "subset" to { a.isSubsetOf(b) },
                    "on" to { a.on(w.first, w.second) }, "inf" to { a.inf() }, "sup" to { a.sup() },
                    "everything" to { a.isEverything() },
                    "components" to { a.components().joinToString(" ; ") },
                    "meeting" to { a.componentsMeeting(b) },
                    "lazy_chain" to { ((a or b) - a.shift(d)) and b.complement() },
                    "lazy_on" to { ((a or b) - a.shift(d)).on(w.first, w.second) },
                ),
            )
        }
    }
}
