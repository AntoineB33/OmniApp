package org.example.project.scheduler.ruleline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.example.project.scheduler.line.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port of `C:\code\line_manager\scripts\rules.py` against the reference: `resources/ruleline/rules-golden.json`
 * holds every rule text of the reference's tests and examples plus random ones, how the reference parsed each (or the
 * error it raised), what it worked out of random sets of them ([RuleSet]: order, cycles, which solver the choices
 * need…), and left sides read on random values. The port must give the same answers.
 *
 * Regenerate after a change to the reference: `python -I scripts/ruleline-golden.py`.
 */
class RuleLanguageGoldenTest {

    private val data: JsonObject by lazy {
        val text = javaClass.classLoader.getResource("ruleline/rules-golden.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    private fun strs(v: List<Any?>) = JsonArray(v.map { if (it == null) JsonNull else JsonPrimitive(it.toString()) })

    private fun err(e: Exception): JsonObject = buildJsonObject {
        put("error", JsonPrimitive(if (e is RuleError) "RuleError: " + e.message else "ValueError"))
    }

    private fun ruleResult(text: String): JsonObject = try {
        val pr = parsePercentRule(text)
        if (pr != null) {
            buildJsonObject {
                put("percent", JsonPrimitive(pr.toString()))
                put("options", strs(pr.options))
            }
        } else {
            val r = parseRule(text)
            buildJsonObject {
                put("rule", JsonPrimitive(r.toString()))
                put("mode", if (r.mode == null) JsonNull else JsonPrimitive(r.mode))
                put("lhs", JsonPrimitive(r.lhs.toString()))
                put("items", strs(r.rhs))
                put("lits", strs(r.lits()))
                put("written", strs(r.writtenLits()))
                put("internal", strs(r.internal))
                put("kinds", strs(r.lhs.kinds()))
            }
        }
    } catch (e: IllegalArgumentException) {
        err(e)
    }

    private fun setResult(texts: List<String>): JsonObject = try {
        val rs = ruleSetOf(texts)
        fun b(v: Boolean) = JsonPrimitive(v)
        buildJsonObject {
            put("rank", strs(rs.rank.keys.toList()))
            put("order", strs(rs.order))
            put("blocks", JsonArray(rs.blocks.map { strs(it) }))
            put("linked", JsonObject(rs.linked.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("dep", strs(rs.choiceDependent.sorted()))
            put("mixed", b(rs.mixed)); put("lattice", b(rs.lattice)); put("searched", b(rs.searched))
            put("settled", b(rs.settled)); put("causal", b(rs.causal))
            put("or_items", JsonArray(rs.orItems.map { buildJsonArray { add(JsonPrimitive(it.first)); add(JsonPrimitive(it.second)) } }))
            put("reach", JsonPrimitive(rs.reach.toString()))
            put("finite", b(rs.finite)); put("ruled", strs(rs.ruled.sorted()))
            put("monotone", b(rs.monotone)); put("pointwise", b(rs.pointwise)); put("local", b(rs.local))
            put("one_way", b(rs.oneWay)); put("free", strs(rs.free.sorted())); put("blocked", strs(rs.blocked.sorted()))
            put("eps_reach", JsonPrimitive(rs.epsReach))
            put("smallest", rs.smallestOffset?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("split", JsonArray(rs.split.map { (a, n, f) -> buildJsonArray { add(strs(a)); add(strs(n)); add(JsonPrimitive(f.toString())) } }))
            put("edges", JsonObject(rs.edges.toSortedMap().mapValues { strs(it.value.sorted()) }))
        }
    } catch (e: IllegalArgumentException) {
        err(e)
    }

    /** Python's JSON of a number is an int; ours a string or an int: compare them as text. */
    private fun norm(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { it.key + ":" + norm(it.value) }
        is JsonArray -> e.joinToString(",", "[", "]") { norm(it) }
        is JsonNull -> "null"
        is JsonPrimitive -> e.content
    }

    @Test
    fun ruleTextsParseAsTheReferenceDoes() {
        val cases = data.getValue("singles").jsonArray
        assertTrue(cases.size >= 300)
        var failures = 0
        val messages = ArrayList<String>()
        for (c in cases) {
            val text = c.jsonObject.getValue("text").jsonPrimitive.content
            val want = norm(c.jsonObject.getValue("result"))
            val got = norm(ruleResult(text))
            if (want != got) {
                failures++
                if (messages.size < 8) messages.add("$text\n  want $want\n  got  $got")
            }
        }
        assertEquals(0, failures, messages.joinToString("\n"))
    }

    @Test
    fun ruleSetsAreAnalysedAsTheReferenceDoes() {
        val cases = data.getValue("sets").jsonArray
        assertTrue(cases.size >= 100)
        var failures = 0
        val messages = ArrayList<String>()
        for (c in cases) {
            val texts = c.jsonObject.getValue("texts").jsonArray.map { it.jsonPrimitive.content }
            val want = norm(c.jsonObject.getValue("result"))
            val got = norm(setResult(texts))
            if (want != got) {
                failures++
                if (messages.size < 8) messages.add("$texts\n  want $want\n  got  $got")
            }
        }
        assertEquals(0, failures, messages.joinToString("\n"))
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

    @Test
    fun leftSidesReadAsTheReferenceReadsThem() {
        val cases = data.getValue("lhs").jsonArray
        assertTrue(cases.size >= 100)
        for (c0 in cases) {
            val c = c0.jsonObject
            val text = c.getValue("text").jsonPrimitive.content
            val r = parseRule(text)
            val vals = c.getValue("vals").jsonObject.mapValues { (_, by) ->
                by.jsonObject.entries.associate { (v, g) -> parseFraction(v) to region(g) }
            }
            val pres = vals.mapValues { (_, by) -> by.values.fold(Region.EMPTY) { a, g -> a or g } }
            val u = Region.UNIVERSE
            val e = Region.EMPTY
            assertEquals(c.getValue("region").jsonPrimitive.content, r.lhs.region(vals, u, e).toString(), "$text: region")
            assertEquals(
                c.getValue("region2").jsonPrimitive.content,
                r.lhs.region2(View(vals, pres), View(emptyMap(), emptyMap()), u, e).toString(),
                "$text: region2",
            )
            val holds = listOf(Rational.ZERO, Rational.of(20), Rational.of(100)).map { v -> r.lhs.holds { v } }
            assertEquals(c.getValue("holds").jsonArray.map { it.jsonPrimitive.content.toBoolean() }, holds, "$text: holds")
        }
    }
}
