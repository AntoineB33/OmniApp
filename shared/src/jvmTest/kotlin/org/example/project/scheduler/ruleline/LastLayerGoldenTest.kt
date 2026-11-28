package org.example.project.scheduler.ruleline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port of the last layer of `C:\code\line_manager\scripts` against the reference: `random` (CPython's Mersenne
 * Twister), `probability.py` (the model and its scenarios), `ratio_plan.py` (`turns`, the exclusions, `Plan` and
 * `Fill`), `score.py` (the score, within 1e-9, and the targets) with `instant.py`'s schedule, `generator.py` (whole
 * runs, on a budget counted in candidates) and `simulation.py` (chips, mode timelines and the user's edits, every
 * change of the line). Only cases the reference answers the same under two hash seeds, and fast, are kept: what
 * depends on a time budget (the score search, the pace's steps) is not held to the reference here.
 *
 * Regenerate after a change to the reference: `python -I scripts/ruleline-golden.py`.
 */
class LastLayerGoldenTest {

    private val data: JsonObject by lazy {
        val text = javaClass.classLoader.getResource("ruleline/last-layer-golden.json")!!.readText()
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

    private fun py(x: Any?): String = when (x) {
        null -> "None"
        Pos.INF -> "inf" // (the reference keeps an endless definitive as its bare INF)
        true -> "True"
        false -> "False"
        else -> x.toString()
    }

    private fun opsText(ops: List<Op>) = ops.joinToString(" ; ")

    private fun schedText(d: Map<String, List<Pair<Rational, Rational>>>) =
        d.toSortedMap().filter { it.value.isNotEmpty() }.entries.joinToString(" ; ") { (k, v) -> "$k: " + v.joinToString(",") { (a, b) -> "$a-$b" } }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun fracs(e: JsonElement) = e.jsonObject.mapValues { f(it.value.jsonPrimitive.content) }

    private fun timeline(c: JsonObject) = ModeTimeline(Rational.ZERO, "1", c.getValue("modes").jsonArray.map { m ->
        Rational.of(m.jsonArray[0].jsonPrimitive.long) to m.jsonArray[1].jsonPrimitive.content
    })

    private fun problems(c: JsonObject): List<Pair<OptProblem, Rational>> {
        val probs = c.getValue("probs").jsonArray.map { it.jsonPrimitive.content }
        val out = ArrayList<Pair<OptProblem, Rational>>()
        if ("ratio" in probs) out.add(PresenceRatioProblem(fracs(c.getValue("ratio"))) to Rational.ONE)
        if ("length" in probs) out.add(MinimumLengthProblem(fracs(c.getValue("length"))) to Rational.of(1, 2))
        return out
    }

    private fun score(c: JsonObject): Score {
        val tasks = LinkedHashMap<String, ScoreTask>()
        for (t in c.getValue("tasks").jsonArray) {
            val a = t.jsonArray
            val mx = a[3]
            tasks[a[0].jsonPrimitive.content] = ScoreTask(
                Const(f(a[1].jsonPrimitive.content).toDoubleValue()),
                Const(a[2].jsonPrimitive.double),
                Const(if (mx is JsonNull) Double.POSITIVE_INFINITY else mx.jsonPrimitive.double),
                a[4].jsonObject.mapValues { it.value.jsonPrimitive.double },
            )
        }
        val lam = c.getValue("lam").jsonPrimitive.double
        return Score(tasks, ScoreSettings(compensation = lam))
    }

    /** Each answer: a text, or a list of numbers (compared within 1e-9). */
    private fun eval(c: JsonObject): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        fun put(name: String, fn: () -> Any) {
            out[name] = try {
                SolverLimits.limited(3.0) { fn() }
            } catch (e: Refused) {
                "error Refused: ${e.message}"
            } catch (e: NoBestScript) {
                "error NoBestScript: ${e.message}"
            } catch (e: Exception) {
                "error"
            }
        }
        when (c.str("k")) {
            "random" -> {
                val r = PyRandom(c.getValue("seed").jsonPrimitive.long)
                val n = c.getValue("n").jsonPrimitive.int
                out["floats"] = (0 until 5).map { r.random() }
                out["ints"] = (0 until 5).map { r.randint(0, n).toLong() } + (0 until 3).map { r.randbelow(n + 1).toLong() } + (0 until 2).map { r.getrandbits(31) }
                return out
            }
            "probability" -> {
                val kn = timeline(c).known(f(c.str("frontier")))
                val m = ProbabilityFinder().fit(kn)
                out["rules"] = m.rules()
                put("scenarios") {
                    val (done, res) = m.scenarios(kn, f(c.str("until")), c.getValue("max").jsonPrimitive.int)
                    done.joinToString("\n") { s ->
                        "${s.probability}: " + s.cover.keys.sorted().joinToString(" ; ") { "$it ${s.cover.getValue(it)}" } + " / ${s.uncovered}"
                    } + "\nleft $res"
                }
                return out
            }
            "turns" -> {
                val free = region(c.getValue("free"))
                put("turns") {
                    val r = turns(free, f(c.str("start")), c.getValue("order").jsonArray.map { it.jsonPrimitive.content }, fracs(c.getValue("lengths")), fracs(c.getValue("minimum")))
                    if (r == null) "None" else r.toSortedMap().entries.joinToString(" ; ") { (a, g) -> "$a: $g" }
                }
                return out
            }
        }
        val rs = ruleSetOf(c.getValue("texts").jsonArray.map { it.jsonPrimitive.content })
        var st = LineState.empty()
        try {
            for (cl in c.getValue("claims").jsonArray) {
                val a = cl.jsonArray
                st = applyEdit(rs, st, listOf(Op.put(a[0].jsonPrimitive.content, region(a[2]))), a[1].jsonPrimitive.content)
            }
        } catch (e: Refused) {
            return mapOf("state" to "error Refused")
        }
        out["state"] = "${st.claims}\n--\n${st.line}"
        val chip = f(c.str("chip"))
        when (c.str("k")) {
            "plan" -> {
                val probs = problems(c)
                val h = f(c.str("H"))
                put("exclusions") { exclusions(rs)?.map { it.sorted().joinToString(",") }?.sorted()?.joinToString(" | ") ?: "None" }
                put("best") {
                    checkBestExists(rs, probs)
                    "None"
                }
                fun bound() = probs.map { (q, w) -> q.withWindow(h) to w }
                put("plan") {
                    val p = Plan(rs, bound(), emptyList(), c.getValue("seed").jsonPrimitive.long)
                    if (!p.ok) "False" else "True " + (p.ops(st, Pos(chip))?.let { opsText(it) } ?: "None")
                }
                put("fill") {
                    val fl = Fill(bound())
                    if (!fl.ok) "False" else "True " + (fl.ops(rs, st, Pos(chip), h)?.let { opsText(it) } ?: "None")
                }
            }
            "score" -> {
                val sc = score(c)
                val end = chip + f(c.str("H"))
                val pre = sc.tasks.keys.associateWith { st.claims.owned(it, Owner.USER) }
                put("evaluate") {
                    val r = sc.evaluate(st.line, chip, end, pre)
                    listOf(r.d, r.j, r.j1, r.j2, r.j3)
                }
                put("target") { sc.target(st.line, chip, pre).toSortedMap().entries.map { listOf(it.key, it.value) } }
                val inst = Instant(rs, sc)
                put("schedule") { schedText(inst.schedule(st, chip, end)) }
                put("schedule_start") { schedText(inst.schedule(st, chip, end, start = chip + Rational.ONE)) }
            }
            "generator", "simulation" -> {
                val probs = problems(c)
                val tl = timeline(c)
                fun gen() = RulesGenerator(
                    rs, probs, c.getValue("q").jsonPrimitive.double, seed = c.getValue("seed").jsonPrimitive.long,
                    window = f(c.str("H")), shortcuts = c.getValue("shortcuts").jsonPrimitive.boolean,
                )
                if (c.str("k") == "generator") {
                    put("run") {
                        val (s, stats) = gen().run(st, chip, tl.known(chip))
                        s.text() + "\n--\n${stats.chosen} | ${stats.valid} | ${py(stats.definitive)} | ${py(stats.final)} | ${stats.constraints}"
                    }
                } else {
                    put("sim") {
                        val edits = c.getValue("edits").jsonArray.map { e ->
                            val a = e.jsonArray
                            UserEdit(f(a[0].jsonPrimitive.content), listOf(Op.put(a[1].jsonPrimitive.content, region(a[2]))))
                        }
                        val path = c.getValue("chip_path").jsonArray.map { p -> f(p.jsonArray[0].jsonPrimitive.content) to f(p.jsonArray[1].jsonPrimitive.content) }
                        val sm = Simulation(st, gen(), Chip(path), tl, edits)
                        val log = sm.run(f(c.str("end")))
                        log.changes.joinToString("\n") { "${it.time} ${it.chip} ${it.source}: ${it.after.line}" } + "\n####\n" +
                            log.refused.joinToString("\n") { (t, s, w) -> "$t $s: $w" } + "\n####\n" +
                            log.runs.joinToString("\n") { "${it.time} ${it.chip} ${it.why}" } + "\n${sm.script.text()}"
                    }
                }
            }
        }
        return out
    }

    private fun same(want: JsonElement, got: Any?): Boolean {
        if (want is JsonPrimitive) {
            val w = want.content
            if (!want.isString) return got is Number && close(want.double, got.toDouble())
            if (got is Double) return close(w.toDouble(), got) && w.toDouble() == got // (a float's repr: exact)
            if (got is Long) return w == got.toString()
            if (w.startsWith("error") && !w.startsWith("error Refused") && !w.startsWith("error NoBestScript")) return got == "error"
            return got == w
        }
        val l = (want as JsonArray)
        if (got !is List<*> || got.size != l.size) return false
        return l.indices.all { same(l[it], got[it]) }
    }

    private fun close(a: Double, b: Double) = a == b || abs(a - b) <= 1e-9 * maxOf(1.0, abs(a), abs(b))

    @Test
    fun theLastLayerAnswersAsTheReferenceDoes() {
        val cases = data.getValue("cases").jsonArray
        assertTrue(cases.size >= 100)
        val messages = ArrayList<String>()
        var failures = 0
        val byKind = LinkedHashMap<String, Int>()
        for ((n, c0) in cases.withIndex()) {
            val c = c0.jsonObject
            val want = c.getValue("expected").jsonObject
            val got = eval(c)
            for ((k, w) in want) {
                if (!same(w, got[k])) {
                    failures++
                    byKind.merge("${c.str("k")}.$k", 1, Int::plus)
                    if (messages.size < 6) messages.add("case $n (${c.str("k")}) $k\n--- want\n$w\n--- got\n${got[k]}")
                }
            }
        }
        assertEquals(0, failures, "$byKind\n" + messages.joinToString("\n\n"))
    }
}
