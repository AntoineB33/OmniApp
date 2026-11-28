package org.example.project.scheduler.ruleline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.example.project.scheduler.line.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port of `constraints.py`, `problems.py`, `script.py`, `watch.py`, `modes.py` and `mode_rules.py` of
 * `C:\code\line_manager\scripts` against the reference: `resources/ruleline/generator-inputs-golden.json` holds random
 * break-like lines with the constraints' targets, edits and placements; random lines with the problems' errors,
 * proposals and the triggers' next positions; scripts' text and pushes with mode timelines; and the mode rules
 * compiled along random segments, with constraints and pushes, read inside each compiled stretch. Only cases the
 * reference answers the same under two hash seeds are kept. Where the reference raised, the port must raise too (a
 * refusal with the same reason).
 *
 * Regenerate after a change to the reference: `python -I scripts/ruleline-golden.py`.
 */
class GeneratorInputsGoldenTest {

    private val data: JsonObject by lazy {
        val text = javaClass.classLoader.getResource("ruleline/generator-inputs-golden.json")!!.readText()
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

    private fun f(s: String) = parseFraction(s)

    private fun py(b: Boolean) = if (b) "True" else "False"

    private fun opsText(ops: List<Op>) = ops.joinToString(" ; ")

    private fun eval(c: JsonObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(name: String, fn: () -> String) {
            out[name] = try {
                fn()
            } catch (e: Refused) {
                "error Refused: ${e.message}"
            } catch (e: Exception) {
                "error"
            }
        }
        val texts = c.getValue("texts").jsonArray.map { it.jsonPrimitive.content }
        val rs = ruleSetOf(texts)
        var st = LineState.empty()
        try {
            for (cl in c.getValue("claims").jsonArray) {
                val a = cl.jsonArray
                st = applyEdit(rs, st, listOf(Op.put(a[0].jsonPrimitive.content, region(a[2]))), a[1].jsonPrimitive.content)
            }
        } catch (e: Refused) {
            return mapOf("state" to "error Refused")
        }
        val chipR = f(c.getValue("chip").jsonPrimitive.content)
        val chip = Pos(chipR)
        out["state"] = "${st.claims}\n--\n${st.line}"
        when (c.getValue("k").jsonPrimitive.content) {
            "constraints" -> {
                val l = f(c.getValue("L").jsonPrimitive.content)
                val p = f(c.getValue("P").jsonPrimitive.content)
                val pull = c.getValue("pull").jsonPrimitive.boolean
                val made = c.getValue("cons").jsonArray.map { n ->
                    when (n.jsonPrimitive.content) {
                        "min" -> MinimumGap("B1", p, after = "N", atLeast = l, pull = if (pull) "N" else null)
                        "early" -> AsEarlyAsPossible("B1", length = l)
                        "max" -> MaximumGap("B2", p, after = "N", atLeast = l)
                        "breaks" -> Breaks("B3", "N", l, p)
                        "screen" -> ScreenBreaks(listOf(Triple("B1", l, p), Triple("B2", l * 2, p * 3), Triple("B3", l * 3, p * 6)), "N", listOf("B2", "B3"))
                        else -> KeepOn(Op.put("B1", Region.of(Interval.of(Pos.ONE, Pos(Rational.ONE + l), false, true))))
                    }
                }
                val ks = expand(made)
                for (x in ks) {
                    x.bind(rs)
                    x.bindAll(ks)
                }
                out["constraints"] = ks.joinToString(" | ")
                for ((n, x) in ks.withIndex()) {
                    put("targets$n") { x.targets(st, chip).entries.sortedBy { it.key }.joinToString(" ; ") { "${it.key}: ${it.value}" } }
                    put("ops$n") { opsText(x.ops(st, chip)) }
                    if (x is AsEarlyAsPossible && x.length != null) {
                        put("place$n") { x.place(st, chip).toString() }
                        put("until$n") { x.place(st, chip, until = chip + 20).toString() }
                        put("obst$n") { x.obstacles(st, chip).first.toString() }
                        put("pushed$n") { x.pushedIn(rs).joinToString(",") + "/" + x.putsAtChip(rs, "N").joinToString(",") }
                    }
                    if (x is MinimumGap) put("apart$n") { x.apart(st.line.presence(x.after), x.pullsOf(st, chip)).toString() }
                }
                put("all") { opsText(constraintOps(rs, st, chip, ks)) }
                put("all_until") { opsText(constraintOps(rs, st, chip, ks, until = Pos(chipR + Rational.of(15)))) }
                put("pulled") {
                    pulled(st.line.presence("N").dilate(Interval.of(Pos.ZERO, Pos.of(2), true, false)), st.line.presence("N"), Rational.of(3)).toString()
                }
            }
            "problems" -> {
                val h = f(c.getValue("H").jsonPrimitive.content)
                val state = st
                val ctx = object : ProblemContext {
                    override val c = chip
                    override val state = state
                    override val rules = rs
                    override fun expected(s: LineState, kind: String, weights: Any?, w: IntervalSet) = s.line.integral(kind, w)
                }
                val pr = PresenceRatioProblem(mapOf("A" to Rational.of(1, 2), "B" to Rational.of(1, 4), "C" to Rational.of(1, 4))).withWindow(h)
                val ml = MinimumLengthProblem(mapOf("A" to Rational.of(3), "B" to Rational.of(2))).withWindow(h)
                for ((name, p) in listOf("ratio" to pr, "length" to ml)) {
                    put("${name}_error") { p.error(ctx, st).toString() }
                    for (strat in listOf("left", "right", "spread3")) put("${name}_$strat") { opsText(p.proposals(ctx, "none", strat)) }
                    put("${name}_extra") { p.extra(ctx).joinToString(" | ") { (a, o) -> "$a: ${opsText(o)}" } }
                }
                put("stretch") { pr.stretchError(ctx, st).toString() }
                put("short") { shortfalls(st.line.presence("A"), Rational.of(3), chip, h).joinToString(" ; ") { (iv, d) -> "$iv $d" } }
                put("score") { score(listOf(pr to Rational.ONE, ml to Rational.of(1, 2)), st, chip).toString() }
                val probs = listOf<Pair<OptProblem, Rational>>(pr to Rational.ONE)
                for ((name, trig) in listOf(
                    "chipin" to ChipIn(Pos.of(2), Pos.of(9), "1"),
                    "above" to PresenceAbove("A", Rational.of(100), Rational.of(1, 3), Rational.of(6), Pos.of(-5), Pos.of(30)),
                    "scorebelow" to ScoreBelow(Num.Exact(Rational.of(1, 2))),
                )) {
                    put("look_$name") { Watch.look(trig, st, chip, "1", probs)!!.first.let { if (it == Pos.INF) "inf" else it.toString() } }
                }
                put("holds") {
                    py(ChipOn("A", within = Rational.of(2)).holds(st, chip, "1")) + py(ChipOn("A", lasted = Rational.ONE).holds(st, chip, "1")) +
                        py(PresenceAbove("A", Rational.of(100), Rational.of(1, 3), Rational.of(6), Pos.of(-5), Pos.of(30)).holds(st, chip, "1"))
                }
            }
            "script" -> {
                val l = f(c.getValue("L").jsonPrimitive.content)
                val e1 = Event(ChipOn("B1", "1"), listOf(EditAction(listOf(Op.take("B1", Region.of(Interval.of(Pos.ZERO, Pos.INF, false, false))), Op.put("B1", Region.of(Interval.of(Pos.ZERO, Pos(l), false, true)))), true)))
                val e2 = Event(ChipIn(Pos.ZERO, Pos.INF, "2"), listOf(EditAction(listOf(Op.put("A", Region.point(Pos.ONE))))))
                val sc = Script.of(listOf(e1, e2), live = true)
                out["text"] = sc.text()
                out["push"] = pushOf(e1).toString()
                out["push2"] = pushOf(e2)?.toString() ?: "None"
                out["pushes"] = sc.pushes().entries.joinToString(" | ") { "${it.key}: ${it.value}" }
                val tl = ModeTimeline(Rational.ZERO, "1", c.getValue("modes").jsonArray.map { m -> m.jsonArray.let { f(it[0].jsonPrimitive.content) to it[1].jsonPrimitive.content } })
                out["modes"] = (0 until 20 step 3).joinToString(",") { tl.modeAt(Pos.of(it)) }
                val kn = tl.known(chipR + Rational.of(5))
                out["known"] = listOf("1", "2", "3").joinToString(" | ") { m -> "$m: ${kn.region(m)} / ${kn.possible(m)}" } + " ${kn.currentMode()} ${kn.currentStart()}"
            }
            "script_text" -> {
                out["parsed"] = try {
                    val sc = parseScript(c.getValue("text").jsonPrimitive.content, Script(), rs)
                    sc.text() + "\n--\n" + sc.covered.joinToString(" | ") { (lo, hi, m) -> "$lo:$hi:${m ?: "None"}" } + " live=${py(sc.live)}"
                } catch (e: ScriptError) {
                    "ScriptError: ${e.message}"
                } catch (e: Exception) {
                    "error"
                }
            }
            "mode_rules" -> {
                val l = f(c.getValue("L").jsonPrimitive.content)
                val p = f(c.getValue("P").jsonPrimitive.content)
                var cons = listOf<Constraint>()
                if (c.getValue("early").jsonPrimitive.boolean) {
                    cons = listOf(AsEarlyAsPossible("B1", length = l), MinimumGap("B1", p, after = "N", atLeast = l))
                    for (x in cons) {
                        x.bind(rs)
                        x.bindAll(cons)
                    }
                }
                var pushes = listOf<ChipPush>()
                if (c.getValue("push").jsonPrimitive.boolean) {
                    val e1 = Event(ChipOn("B1", "1"), listOf(EditAction(listOf(Op.take("B1", Region.of(Interval.of(Pos.ZERO, Pos.INF, false, false))), Op.put("B1", Region.of(Interval.of(Pos.ZERO, Pos(l), false, true)))), true)))
                    pushes = listOf(pushOf(e1)!!)
                }
                val segs = c.getValue("segments").jsonArray.map { s -> s.jsonArray.let { Triple(Pos(f(it[0].jsonPrimitive.content)), Pos(f(it[1].jsonPrimitive.content)), it[2].jsonPrimitive.content) } }
                put("runs") {
                    val (stretches, refused) = compileRuns(rs, st, segs, cons, pushes)
                    stretches.joinToString("\n====\n") { s ->
                        "${s.mode} [${s.a}, ${s.b}]\n${s.after.claims}\n--\n${s.after.line}" +
                            (if (s.plan != null) mid(s.a, s.b).joinToString("") { q -> "\n@$q: ${s.stateAt(q).line}" } else "")
                    } + "\n####\n" + refused.joinToString("\n") { (x, w) -> "$x: $w" }
                }
            }
        }
        return out
    }

    private fun mid(a: Pos, b: Pos): List<Pos> = if (a == b || a.x == b.x) emptyList() else listOf(Pos((a.x + b.x) / 2), Pos(a.x + (b.x - a.x) / 4))

    @Test
    fun generatorInputsAnswerAsTheReferenceDoes() {
        val cases = data.getValue("cases").jsonArray
        assertTrue(cases.size >= 100)
        val messages = ArrayList<String>()
        var failures = 0
        for ((n, c0) in cases.withIndex()) {
            val c = c0.jsonObject
            val want = c.getValue("expected").jsonObject.mapValues { it.value.jsonPrimitive.content }
            val got = eval(c)
            for ((k, w) in want) {
                val g = got[k]
                val ok = if (w.startsWith("error") && !w.startsWith("error Refused")) g == "error" else g == w
                if (!ok) {
                    failures++
                    if (messages.size < 6) messages.add("case $n (${c.getValue("k").jsonPrimitive.content}) $k\n--- want\n$w\n--- got\n$g")
                }
            }
        }
        assertEquals(0, failures, messages.joinToString("\n\n"))
    }
}
