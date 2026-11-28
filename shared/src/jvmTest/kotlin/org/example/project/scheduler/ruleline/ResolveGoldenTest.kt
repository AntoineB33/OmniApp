package org.example.project.scheduler.ruleline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port of `C:\code\line_manager\scripts\resolve.py` and `guards.py` against the reference:
 * `resources/ruleline/resolve-golden.json` holds random sequences of edits — the user's, the generated script's (with
 * the N1/N2 check) and the mode rules' (with `mode_won` and the M1 check) — on random rule sets, with the outcome of
 * every step (the claims and the line after it, or the refusal and its reason), then the state flattened at a point.
 * Only cases the reference answers the same under two hash seeds are kept: no expected answer rests on Python's set
 * order. Where the reference itself raises (a `TypeError` on `-inf // period`, a bug of the reference), any exception
 * other than a refusal is accepted.
 *
 * Regenerate after a change to the reference: `python -I scripts/ruleline-golden.py`.
 */
class ResolveGoldenTest {

    private val data: JsonObject by lazy {
        val text = javaClass.classLoader.getResource("ruleline/resolve-golden.json")!!.readText()
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

    @Test
    fun editsResolveAsTheReferenceResolvesThem() {
        val cases = data.getValue("cases").jsonArray
        assertTrue(cases.size >= 100)
        val messages = ArrayList<String>()
        var failures = 0
        var steps = 0
        for ((n, c0) in cases.withIndex()) {
            val c = c0.jsonObject
            val texts = c.getValue("texts").jsonArray.map { it.jsonPrimitive.content }
            val rs = ruleSetOf(texts)
            var state = LineState.empty()
            val expected = c.getValue("expected").jsonObject
            val wants = expected.getValue("steps").jsonArray
            for ((s, st0) in c.getValue("steps").jsonArray.withIndex()) {
                steps++
                val st = st0.jsonObject
                val owner = st.getValue("owner").jsonPrimitive.content
                val chip = Pos(parseFraction(st.getValue("chip").jsonPrimitive.content))
                val ops = st.getValue("ops").jsonArray.map {
                    val o = it.jsonObject
                    Op(o.getValue("a").jsonPrimitive.content, o.getValue("k").jsonPrimitive.content, region(o.getValue("g")), parseFraction(o.getValue("v").jsonPrimitive.content))
                }
                val want = wants[s].jsonObject
                var got: String
                try {
                    var after = applyEdit(rs, state, ops, owner)
                    var check: String? = null
                    if (owner == Owner.GEN) {
                        check = checkScriptEdit(state, after, chip, ops, rs)
                    } else if (owner == Owner.MODE) {
                        after = modeWon(state, after)
                        check = checkModeEdit(state, after, Region.point(chip), chip)
                    }
                    state = after
                    got = "ok\n${state.claims}\n--\n${state.line}\n--\n${state.history.size} ${if (state.claims.clean) "True" else "False"} $check"
                } catch (e: Refused) {
                    got = "refused: ${e.message}"
                } catch (e: Exception) {
                    got = "error"
                }
                val wantText = when {
                    want.getValue("ok").jsonPrimitive.boolean -> {
                        val check = want.getValue("check").let { if (it is JsonNull) "null" else it.jsonPrimitive.content }
                        "ok\n${want.getValue("claims").jsonPrimitive.content}\n--\n${want.getValue("line").jsonPrimitive.content}\n--\n" +
                            "${want.getValue("history").jsonPrimitive.int} ${if (want.getValue("clean").jsonPrimitive.boolean) "True" else "False"} $check"
                    }
                    "refused" in want -> "refused: ${want.getValue("refused").jsonPrimitive.content}"
                    else -> "error"
                }
                if (wantText != got) {
                    failures++
                    if (messages.size < 5) messages.add("case $n step $s $texts ($owner)\n--- want\n$wantText\n--- got\n$got")
                    break // the rest of the case starts from a different state
                }
            }
            if (messages.size >= 5) continue
            val flat = try {
                val fl = flattened(rs, state, Pos(parseFraction(c.getValue("flat").jsonPrimitive.content)))
                "${fl.claims}\n--\n${fl.line}\n--\n${fl.history.size}"
            } catch (e: Exception) {
                "error"
            }
            val wantFlat = expected.getValue("flat").jsonPrimitive.content.let { if (it.startsWith("error ")) "error" else it }
            if (wantFlat != flat) {
                failures++
                if (messages.size < 5) messages.add("case $n flattened $texts\n--- want\n$wantFlat\n--- got\n$flat")
            }
        }
        assertTrue(steps > 300)
        assertEquals(0, failures, messages.joinToString("\n\n"))
    }
}
