package org.example.project.scheduler.ruleline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port of `C:\code\line_manager\scripts\line.py` against the reference: `resources/ruleline/line-state-golden.json`
 * holds random sequences of claim writes (elements, "no" elements, the user's removals, by owner) and what the
 * reference's `Claims` and `LineState` answer after them.
 *
 * Regenerate after a change to the reference: `python -I scripts/ruleline-golden.py`.
 */
class LineStateGoldenTest {

    private val data: JsonObject by lazy {
        val text = javaClass.classLoader.getResource("ruleline/line-state-golden.json")!!.readText()
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

    private fun py(b: Boolean) = if (b) "True" else "False"

    @Test
    fun claimsAnswerAsTheReferenceDoes() {
        val cases = data.getValue("cases").jsonArray
        assertTrue(cases.size >= 100)
        val owners = listOf(Owner.USER, Owner.GEN, Owner.MODE)
        val kinds = listOf("A", "B", "C")
        for ((n, c0) in cases.withIndex()) {
            val c = c0.jsonObject
            val claims = Claims()
            for (s0 in c.getValue("steps").jsonArray) {
                val s = s0.jsonObject
                val k = s.getValue("k").jsonPrimitive.content
                val g = region(s.getValue("g"))
                val o = s.getValue("o").jsonPrimitive.content
                val v = parseFraction(s.getValue("v").jsonPrimitive.content)
                when (s.getValue("a").jsonPrimitive.content) {
                    "pos" -> claims.addPos(k, g, v, o)
                    "neg" -> claims.addNeg(k, g, o)
                    "taken" -> claims.addTaken(k, g, o)
                    "rpos" -> claims.removePos(k, g)
                    "rneg" -> claims.removeNeg(k, g)
                    else -> claims.removeTaken(k, g)
                }
            }
            val other = Claims()
            other.addPos("A", region(c.getValue("other")), Rational.of(100), Owner.USER)
            val where = region(c.getValue("where"))
            val st = LineState(claims, Line(emptyMap()))
            val cut = pos(c.getValue("cut"))
            val got = mapOf(
                "str" to claims.toString(), "on" to claims.on(where).toString(), "kinds" to claims.kinds().joinToString(" "),
                "empty" to py(claims.isEmpty()), "merged" to claims.merged(other).toString(),
                "same" to py(claims.same(claims.copy())), "same_other" to py(claims.same(other)),
                "owned" to kinds.flatMap { k -> owners.map { o -> "$k/$o: ${claims.owned(k, o)}" } }.joinToString(" | "),
                "by_value" to kinds.joinToString(" | ") { k ->
                    "$k: " + claims.byValue(k).entries.sortedBy { it.key }.joinToString(", ") { (v, r) -> "$v=$r" }
                },
                "memory" to st.memory().toString(), "starts_before" to st.startsBefore(cut).joinToString(" "),
                "op" to Op.put("A", where, Rational.of(50)).toString() + " ; " + Op.takeNo("B", where).toString(),
            )
            val want = c.getValue("results").jsonObject
            for ((name, v) in got) assertEquals(want.getValue(name).jsonPrimitive.content, v, "case $n, $name")
        }
    }

    private class Rec(override val footprint: Region) : Footprinted

    @Test
    fun editsFindWhatTouchesAStretch() {
        val recs = (0 until 40).map { i -> Rec(Region.of(Interval.of(Pos.of(i * 30), Pos.of(i * 30 + 10)))) }
        val wide = Rec(Region.of(Interval.of(Pos.of(-10000), Pos.of(10000))))
        val e = Edits(recs.take(20)) + (recs.drop(20) + wide)
        val where = Region.of(Interval.of(Pos.of(95), Pos.of(305)))
        assertEquals(e.filter { (it.footprint and where).isNotEmpty() }, e.touching(where))
        val forked = Edits(recs.take(5)) + listOf(wide)
        assertEquals(listOf(recs[3], recs[4], wide), forked.touching(where))
    }
}
