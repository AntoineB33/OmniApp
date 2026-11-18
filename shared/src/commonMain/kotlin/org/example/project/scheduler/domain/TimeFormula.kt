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

        /**
         * The instants (in millis) where it may stop or start moving or jump: every `date(…)` and every bound of a
         * `ramp` or `step` that is a constant. Between two of them a formula is smooth.
         */
        val breakpoints: List<Long> by lazy {
            val out = HashSet<Double>()
            root.collectBreakpoints(out)
            out.sorted().map(::millisOf)
        }

        /** Its value at [millis], or null where it is no finite number. */
        fun at(millis: Long): Double? = root.eval(dayOf(millis)).takeIf { it.isFinite() }
    }

    /** Read formulas by zone and text, an immutable value swapped whole: more than one thread asks, and a race only reads twice. */
    private var cache: Map<String, Parsed?> = emptyMap()

    /** [text] read as a formula, or null where it is not one (blank included). Memoized: the fill asks at every decision. */
    fun parse(text: String, zone: TimeZone = TimeZone.currentSystemDefault()): Parsed? {
        if (text.isBlank()) return null
        val key = zone.id + "|" + text
        val known = cache
        if (key in known) return known[key]
        val parsed = runCatching { Parser(text, zone).parse() }.getOrNull()?.let { Parsed(it, text) }
        cache = (if (known.size > 2_000) emptyMap() else known) + (key to parsed)
        return parsed
    }

    /** What is wrong with [text] as a formula, said to the user; null when it is one (or blank: no formula). */
    fun problem(text: String, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
        if (text.isBlank()) return null
        return try {
            Parser(text, zone).parse()
            null
        } catch (e: FormulaError) {
            e.message
        } catch (e: Exception) {
            "not a formula"
        }
    }

    internal class FormulaError(message: String) : Exception(message)

    internal sealed class Node {
        abstract fun eval(t: Double): Double

        open fun readsT(): Boolean = children().any { it.readsT() }

        open fun children(): List<Node> = emptyList()

        open fun collectBreakpoints(out: MutableSet<Double>) {
            children().forEach { it.collectBreakpoints(out) }
        }

        class Num(val value: Double) : Node() {
            override fun eval(t: Double) = value
        }

        object T : Node() {
            override fun eval(t: Double) = t

            override fun readsT() = true
        }

        class Date(val day: Double) : Node() {
            override fun eval(t: Double) = day

            override fun collectBreakpoints(out: MutableSet<Double>) {
                out += day
            }
        }

        class Unary(val op: Char, val x: Node) : Node() {
            override fun eval(t: Double) = if (op == '-') -x.eval(t) else x.eval(t)

            override fun children() = listOf(x)
        }

        class Binary(val op: Char, val a: Node, val b: Node) : Node() {
            override fun eval(t: Double): Double {
                val x = a.eval(t)
                val y = b.eval(t)
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
            override fun eval(t: Double): Double {
                val v = args.map { it.eval(t) }
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

            override fun readsT() = name == "ramp" || name == "step" || super.readsT()

            override fun children() = args

            override fun collectBreakpoints(out: MutableSet<Double>) {
                super.collectBreakpoints(out)
                val bounds = when (name) {
                    "ramp" -> args.take(2)
                    "step" -> args.take(1)
                    else -> emptyList()
                }
                for (b in bounds) if (!b.readsT()) out += b.eval(0.0)
            }
        }
    }

    private val ARITY = mapOf(
        "clamp" to 3..3, "abs" to 1..1, "sqrt" to 1..1, "exp" to 1..1, "ln" to 1..1, "sin" to 1..1, "cos" to 1..1,
        "floor" to 1..1, "ramp" to 4..4, "step" to 3..3, "min" to 1..64, "max" to 1..64,
    )

    private class Parser(private val s: String, private val zone: TimeZone) {
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
                if (peek() != '(') throw FormulaError("\"$name\" is not known: t, date(…) or a function")
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
