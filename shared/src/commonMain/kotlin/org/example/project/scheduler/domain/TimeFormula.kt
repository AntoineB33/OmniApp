package org.example.project.scheduler.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * User rule 2026-11-16: *"In a task element, the user can define the evolution of its presence ratio target and its
 * minimum length target with any mathematical formula."* **A formula over time** — the replacement of the dated task
 * trees' timeline: a task's weight under a parent ([org.example.project.scheduler.model.Cell.weightFormula]) and its
 * minimum length ([org.example.project.scheduler.model.Task.minimumFormula]) are numbers of the instant.
 *
 * - `t` is the instant, in **days** since 1970-01-01 00:00 UTC (fractional). It is absolute: time passing never changes
 *   what a formula says about an instant, so it never re-plans (`scheduler.md` § *Time passing must never re-plan*).
 * - `date(2026-12-01)` and `date(2026-12-01 08:30)` are instants in the same unit, read in the device's zone.
 * - `+ - * / ^`, parentheses, numbers; `min`, `max` (any count), `clamp(x, lo, hi)`, `abs`, `sqrt`, `exp`, `ln`, `sin`,
 *   `cos`, `floor`; `ramp(t0, t1, a, b)` — `a` until `t0`, `b` from `t1`, the straight line between; `step(t0, a, b)` —
 *   `a` before `t0`, `b` from it.
 * - `periods(a1, b1, e1, a2, b2, e2, …)` (user rule 2026-11-19, the time periods of a formula): `e1` from `a1` until
 *   `b1`, `e2` from `a2` until `b2`… — the first period holding the instant; outside every period, no value (so the
 *   constant applies there). A bound is an instant (`date(…)`), `-inf` or `inf` for an open one.
 *
 * - `priority` (user rule 2026-11-20, a presence ratio target's formula alone, [parse]'s `allowPriority`): the task's
 *   priority relative to what its evolution is relative to, as the weights give it — so `priority` alone is the
 *   evolution that changes nothing, the default one ([PresenceEvolutions.defaults]).
 *
 * A formula that does not read `t` is a constant ([Parsed.constant]). A value that is not a finite number is no value.
 */
object TimeFormula {
    const val MILLIS_PER_DAY: Double = 86_400_000.0

    /** [millis] as the formulas' `t`. */
    fun dayOf(millis: Long): Double = millis / MILLIS_PER_DAY

    /** The formulas' `t` back in millis. */
    fun millisOf(day: Double): Long = (day * MILLIS_PER_DAY).toLong()

    /** A formula read once, evaluated at any instant. */
    class Parsed internal constructor(private val root: Node, val text: String) {
        /** Whether it reads no `t`: the same value at every instant. */
        val constant: Boolean = !root.readsT()

        /** Whether it reads `priority`: a value per task ([at]'s `priority`). */
        val readsPriority: Boolean = root.readsPriority()

        /**
         * The instants (in millis) where it may stop or start moving or jump: every `date(…)` and every bound of a
         * `ramp` or `step` that is a constant. Between two of them a formula is smooth.
         */
        val breakpoints: List<Long> by lazy {
            val out = HashSet<Double>()
            root.collectBreakpoints(out)
            out.sorted().map(::millisOf)
        }

        /** Its value at [millis] for a task of that [priority] (none: `priority` is no number), or null where it is none. */
        fun at(millis: Long, priority: Double = Double.NaN): Double? = root.eval(dayOf(millis), priority).takeIf { it.isFinite() }
    }

    /** Read formulas by zone and text, an immutable value swapped whole: more than one thread asks, and a race only reads twice. */
    private var cache: Map<String, Parsed?> = emptyMap()

    /**
     * [text] read as a formula, or null where it is not one (blank included). Memoized: the fill asks at every decision.
     * [allowPriority]: a presence ratio target's formula, which may read `priority`; no other may.
     */
    fun parse(text: String, zone: TimeZone = TimeZone.currentSystemDefault(), allowPriority: Boolean = false): Parsed? {
        if (text.isBlank()) return null
        val key = zone.id + "|" + allowPriority + "|" + text
        val known = cache
        if (key in known) return known[key]
        val parsed = runCatching { Parser(text, zone, allowPriority).parse() }.getOrNull()?.let { Parsed(it, text) }
        cache = (if (known.size > 2_000) emptyMap() else known) + (key to parsed)
        return parsed
    }

    /** What is wrong with [text] as a formula, said to the user; null when it is one (or blank: no formula). */
    fun problem(text: String, zone: TimeZone = TimeZone.currentSystemDefault(), allowPriority: Boolean = false): String? {
        if (text.isBlank()) return null
        return try {
            Parser(text, zone, allowPriority).parse()
            null
        } catch (e: FormulaError) {
            e.message
        } catch (e: Exception) {
            "not a formula"
        }
    }

    /** One period of a formula ([FormulaPeriods]): [from] / [until] a date as `date(…)` reads it, blank for an open bound. */
    data class Period(val from: String, val until: String, val formula: String)

    /**
     * User rule 2026-11-19: a formula as the time periods the editor shows — `periods(…)` read back into its periods, any
     * other formula as ONE period open on both sides. [build] writes them back; `read(build(x)) == x`.
     */
    object FormulaPeriods {
        fun read(text: String): List<Period> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            val inner = trimmed.takeIf { it.lowercase().startsWith("periods(") && it.endsWith(")") }
                ?.substring("periods(".length, trimmed.length - 1)
                ?: return listOf(Period("", "", trimmed))
            val args = splitTopLevel(inner)
            if (args.size % 3 != 0 || args.isEmpty()) return listOf(Period("", "", trimmed))
            return args.chunked(3).map { (a, b, e) -> Period(boundText(a) ?: return listOf(Period("", "", trimmed)), boundText(b) ?: return listOf(Period("", "", trimmed)), unwrap(e)) }
        }

        fun build(periods: List<Period>): String {
            val kept = periods.filter { it.formula.isNotBlank() }
            if (kept.isEmpty()) return ""
            val single = kept.singleOrNull()
            if (single != null && single.from.isBlank() && single.until.isBlank()) return single.formula.trim()
            return "periods(" + kept.joinToString(", ") { p ->
                "${p.from.trim().let { if (it.isEmpty()) "-inf" else "date($it)" }}, ${p.until.trim().let { if (it.isEmpty()) "inf" else "date($it)" }}, (${p.formula.trim()})"
            } + ")"
        }

        /** [bound] as millis: blank is open (null). Throws nothing: an unreadable date is null too — [problem] says why. */
        fun millisOf(bound: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long? =
            if (bound.isBlank()) null else parse("date(${bound.trim()})", zone)?.at(0)?.let(::millisOf)

        private fun boundText(arg: String): String? {
            val a = arg.trim()
            return when {
                a == "-inf" || a == "inf" -> ""
                a.lowercase().startsWith("date(") && a.endsWith(")") -> a.substring(5, a.length - 1).trim()
                else -> null
            }
        }

        private fun unwrap(e: String): String {
            val x = e.trim()
            if (!(x.startsWith("(") && x.endsWith(")"))) return x
            var depth = 0
            for ((i, c) in x.withIndex()) {
                if (c == '(') depth++
                if (c == ')') depth--
                if (depth == 0 && i < x.length - 1) return x
            }
            return x.substring(1, x.length - 1).trim()
        }

        private fun splitTopLevel(s: String): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            for ((i, c) in s.withIndex()) {
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 0) {
                        out += s.substring(start, i)
                        start = i + 1
                    }
                }
            }
            out += s.substring(start)
            return out
        }
    }

    internal class FormulaError(message: String) : Exception(message)

    internal sealed class Node {
        abstract fun eval(t: Double, p: Double): Double

        open fun readsT(): Boolean = children().any { it.readsT() }

        open fun readsPriority(): Boolean = children().any { it.readsPriority() }

        open fun children(): List<Node> = emptyList()

        open fun collectBreakpoints(out: MutableSet<Double>) {
            children().forEach { it.collectBreakpoints(out) }
        }

        class Num(val value: Double) : Node() {
            override fun eval(t: Double, p: Double) = value
        }

        object T : Node() {
            override fun eval(t: Double, p: Double) = t

            override fun readsT() = true
        }

        /** `priority`: the task's priority relative to what its evolution is relative to. */
        object Priority : Node() {
            override fun eval(t: Double, p: Double) = p

            override fun readsPriority() = true
        }

        class Date(val day: Double) : Node() {
            override fun eval(t: Double, p: Double) = day

            override fun collectBreakpoints(out: MutableSet<Double>) {
                out += day
            }
        }

        class Unary(val op: Char, val x: Node) : Node() {
            override fun eval(t: Double, p: Double) = if (op == '-') -x.eval(t, p) else x.eval(t, p)

            override fun children() = listOf(x)
        }

        class Binary(val op: Char, val a: Node, val b: Node) : Node() {
            override fun eval(t: Double, p: Double): Double {
                val x = a.eval(t, p)
                val y = b.eval(t, p)
                return when (op) {
                    '+' -> x + y
                    '-' -> x - y
                    '*' -> x * y
                    '/' -> x / y
                    else -> x.pow(y)
                }
            }

            override fun children() = listOf(a, b)
        }

        class Call(val name: String, val args: List<Node>) : Node() {
            override fun eval(t: Double, p: Double): Double {
                if (name == "periods") {
                    // Lazy: only the formula of the period holding [t] is read.
                    for (i in args.indices step 3) {
                        if (t >= args[i].eval(t, p) && t < args[i + 1].eval(t, p)) return args[i + 2].eval(t, p)
                    }
                    return Double.NaN
                }
                val v = args.map { it.eval(t, p) }
                return when (name) {
                    "min" -> v.min()
                    "max" -> v.max()
                    "clamp" -> v[0].coerceIn(minOf(v[1], v[2]), maxOf(v[1], v[2]))
                    "abs" -> abs(v[0])
                    "sqrt" -> sqrt(v[0])
                    "exp" -> exp(v[0])
                    "ln" -> ln(v[0])
                    "sin" -> sin(v[0])
                    "cos" -> cos(v[0])
                    "floor" -> floor(v[0])
                    "ramp" -> {
                        val (t0, t1, from, to) = v
                        when {
                            t <= t0 -> from
                            t >= t1 -> to
                            else -> from + (to - from) * (t - t0) / (t1 - t0)
                        }
                    }
                    "step" -> if (t < v[0]) v[1] else v[2]
                    else -> Double.NaN
                }
            }

            override fun readsT() = name == "ramp" || name == "step" || name == "periods" || super.readsT()

            override fun children() = args

            override fun collectBreakpoints(out: MutableSet<Double>) {
                super.collectBreakpoints(out)
                val bounds = when (name) {
                    "ramp" -> args.take(2)
                    "step" -> args.take(1)
                    "periods" -> args.filterIndexed { i, _ -> i % 3 != 2 }
                    else -> emptyList()
                }
                for (b in bounds) if (!b.readsT()) b.eval(0.0, Double.NaN).takeIf { it.isFinite() }?.let { out += it }
            }
        }
    }

    private val ARITY = mapOf(
        "clamp" to 3..3, "abs" to 1..1, "sqrt" to 1..1, "exp" to 1..1, "ln" to 1..1, "sin" to 1..1, "cos" to 1..1,
        "floor" to 1..1, "ramp" to 4..4, "step" to 3..3, "min" to 1..64, "max" to 1..64, "periods" to 3..300,
    )

    private class Parser(private val s: String, private val zone: TimeZone, private val allowPriority: Boolean) {
        private var pos = 0

        fun parse(): Node {
            val node = expr()
            ws()
            if (pos != s.length) throw FormulaError("unexpected \"${s.substring(pos)}\"")
            return node
        }

        private fun ws() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            ws()
            return s.getOrNull(pos)
        }

        private fun expr(): Node {
            var node = term()
            while (true) {
                val c = peek()
                if (c == '+' || c == '-') {
                    pos++
                    node = Node.Binary(c, node, term())
                } else {
                    return node
                }
            }
        }

        private fun term(): Node {
            var node = power()
            while (true) {
                val c = peek()
                if (c == '*' || c == '/') {
                    pos++
                    node = Node.Binary(c, node, power())
                } else {
                    return node
                }
            }
        }

        private fun power(): Node {
            val base = unary()
            if (peek() == '^') {
                pos++
                return Node.Binary('^', base, power())
            }
            return base
        }

        private fun unary(): Node {
            val c = peek()
            if (c == '-' || c == '+') {
                pos++
                return Node.Unary(c, unary())
            }
            return primary()
        }

        private fun primary(): Node {
            val c = peek() ?: throw FormulaError("a value is missing at the end")
            if (c == '(') {
                pos++
                val node = expr()
                if (peek() != ')') throw FormulaError("a \")\" is missing")
                pos++
                return node
            }
            if (c.isDigit() || c == '.') {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                return Node.Num(s.substring(start, pos).toDoubleOrNull() ?: throw FormulaError("\"${s.substring(start, pos)}\" is no number"))
            }
            if (c.isLetter()) {
                val start = pos
                while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
                val name = s.substring(start, pos).lowercase()
                if (name == "t") return Node.T
                if (name == "priority") {
                    if (!allowPriority) throw FormulaError("\"priority\" is read only in a presence ratio target's formula")
                    return Node.Priority
                }
                if (name == "inf") return Node.Num(Double.POSITIVE_INFINITY)
                if (peek() != '(') throw FormulaError("\"$name\" is not known: t, ${if (allowPriority) "priority, " else ""}date(…) or a function")
                pos++
                if (name == "date") return date()
                val arity = ARITY[name] ?: throw FormulaError("\"$name\" is not a function")
                val args = ArrayList<Node>()
                if (peek() != ')') {
                    args += expr()
                    while (peek() == ',') {
                        pos++
                        args += expr()
                    }
                }
                if (peek() != ')') throw FormulaError("a \")\" is missing after $name(…")
                pos++
                if (name == "periods" && args.size % 3 != 0) throw FormulaError("periods takes a start, an end and a formula for each period")
                if (args.size !in arity) throw FormulaError("$name takes ${if (arity.first == arity.last) arity.first else "${arity.first} or more"} values")
                return Node.Call(name, args)
            }
            throw FormulaError("unexpected \"$c\"")
        }

        /** `date(2026-12-01)` or `date(2026-12-01 08:30)`, after its "(". */
        private fun date(): Node {
            val close = s.indexOf(')', pos)
            if (close < 0) throw FormulaError("a \")\" is missing after date(…")
            val raw = s.substring(pos, close).trim()
            pos = close + 1
            val parts = raw.split(' ', 'T').filter { it.isNotBlank() }
            val day = runCatching { LocalDate.parse(parts[0]) }.getOrNull()
                ?: throw FormulaError("\"$raw\" is no date: write date(2026-12-01) or date(2026-12-01 08:30)")
            val time = if (parts.size > 1) runCatching { LocalTime.parse(parts[1]) }.getOrNull()
                ?: throw FormulaError("\"${parts[1]}\" is no time of day: write 08:30") else LocalTime(0, 0)
            val millis = LocalDateTime(day, time).toInstant(zone).toEpochMilliseconds()
            return Node.Date(dayOf(millis))
        }
    }
}
