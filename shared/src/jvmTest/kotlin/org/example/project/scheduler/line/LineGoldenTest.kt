package org.example.project.scheduler.line

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin port against the Python reference itself: `resources/line/reverse-logic-golden.json` holds rule sets and
 * step sequences (apply, reverse, reverse_all, drag) run through `C:\code\line_manager\reverse_logic`, with every
 * outcome and the line after every step. The port must reach the same outcomes and the same lines, region for
 * region — regions are canonical, so a divergence in a tie-break or a periodic tail shows as an inequality.
 *
 * Regenerate after a change to the reference: `python scripts/line-reverse-logic-golden.py`.
 */
class LineGoldenTest {

    private fun num(e: JsonElement) = Rational.parse(e.jsonPrimitive.content)

    private fun parts(e: JsonElement) = e.jsonArray.map {
        val p = it.jsonArray
        Region.Part(num(p[0]), p[1].jsonPrimitive.boolean, num(p[2]), p[3].jsonPrimitive.boolean)
    }

    private fun tail(e: JsonElement?) = e?.jsonObject?.let { Region.Tail(num(it.getValue("at")), num(it.getValue("period")), parts(it.getValue("pattern"))) }

    private fun region(e: JsonElement): Region {
        val o = e.jsonObject
        return Region(parts(o.getValue("parts")), tail(o["left"]), tail(o["right"]))
    }

    private fun state(e: JsonElement): Claims = e.jsonObject.mapValues { region(it.value) }

    private fun atom(e: JsonElement) = e.jsonArray.let { Atom(it[0].jsonPrimitive.content, num(it[1])) }

    private fun rule(e: JsonElement): LineRule {
        val o = e.jsonObject
        val premises = o.getValue("premises").jsonArray.map { atom(it) }
        return when (o.getValue("t").jsonPrimitive.content) {
            "implies" -> LineRule.Implies(premises, atom(o.getValue("head")))
            "forbids" -> LineRule.Forbids(premises, atom(o.getValue("head")))
            else -> LineRule.ImpliesAny(premises, o.getValue("options").jsonArray.map { atom(it) })
        }
    }

    private fun op(e: JsonElement): LineOp {
        val o = e.jsonObject
        val kind = o.getValue("kind").jsonPrimitive.content
        val reg = region(o.getValue("region"))
        return when (o.getValue("t").jsonPrimitive.content) {
            "add" -> LineOp.Add(kind, reg)
            "remove" -> LineOp.Remove(kind, reg)
            "move" -> LineOp.Move(kind, reg, num(o.getValue("delta")))
            "extend" -> LineOp.Extend(kind, reg)
            else -> LineOp.Retract(kind, reg)
        }
    }

    /** Runs [fn]; the outcome as the reference names it, and its value. */
    private fun <T> outcome(fn: () -> T): Pair<String, T?> = try {
        "ok" to fn()
    } catch (e: ContradictionError) {
        "contradiction" to null
    } catch (e: MissingElementError) {
        "missing" to null
    } catch (e: DependencyError) {
        "dependency" to null
    }

    private fun intOrNull(e: JsonElement?) = if (e == null || e is JsonNull) null else e.jsonPrimitive.int

    @Test
    fun everyStepOfTheReferenceIsReproduced() {
        val text = checkNotNull(javaClass.getResource("/line/reverse-logic-golden.json")) { "golden file missing" }.readText()
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size > 100, "only ${cases.size} cases")
        var steps = 0
        for ((ci, c) in cases.withIndex()) {
            val case = c.jsonObject
            val rules = case.getValue("rules").jsonArray.map { rule(it) }
            val line = LineSystem(rules)
            for ((si, s) in case.getValue("steps").jsonArray.withIndex()) {
                val step = s.jsonObject
                val where = "case $ci step $si (${step.getValue("do").jsonPrimitive.content}), rules $rules"
                when (step.getValue("do").jsonPrimitive.content) {
                    "apply" -> {
                        val (res, id) = outcome { line.apply(step.getValue("ops").jsonArray.map { op(it) }) }
                        assertEquals(step.getValue("result").jsonPrimitive.content, res, where)
                        assertEquals(intOrNull(step["id"]), id, where)
                    }
                    "reverse" -> {
                        val (res, _) = outcome { line.reverse(step.getValue("id").jsonPrimitive.int) }
                        assertEquals(step.getValue("result").jsonPrimitive.content, res, where)
                    }
                    "reverse_all" -> {
                        val (res, gone) = outcome { line.reverseAll(step.getValue("ids").jsonArray.map { it.jsonPrimitive.int }) }
                        assertEquals(step.getValue("result").jsonPrimitive.content, res, where)
                        val expected = step["reversed"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.int }
                        assertEquals(expected, gone, where)
                    }
                    "drag" -> {
                        val block = step.getValue("block").jsonArray.map { it.jsonArray.let { p -> p[0].jsonPrimitive.content to region(p[1]) } }
                        val drag = line.drag(block)
                        for ((di, d) in step.getValue("steps").jsonArray.withIndex()) {
                            val ds = d.jsonObject
                            val (res, _) = outcome { drag.step(num(ds.getValue("delta"))) }
                            assertEquals(ds.getValue("result").jsonPrimitive.content, res, "$where drag $di")
                            assertEquals(state(ds.getValue("state")), line.state(), "$where drag $di")
                        }
                        val id = if (step.getValue("drop").jsonPrimitive.boolean) drag.drop() else drag.cancel().let { null }
                        assertEquals(intOrNull(step["id"]), id, where)
                    }
                }
                assertEquals(state(step.getValue("state")), line.state(), where)
                steps++
            }
        }
        println("LineGoldenTest: ${cases.size} cases, $steps steps reproduced")
    }
}
