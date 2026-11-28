package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational

/**
 * A generated script read from its text ([Script.text]), changed by the user — a port of
 * `C:\code\line_manager\scripts\script_text.py`.
 *
 * [parseScript] gives the script the text says, or raises [ScriptError] saying which lines are not correctly written:
 * every line can be changed, added or deleted, and what is written is what the script does. The lines at the top,
 * down to the first empty line after a "Generated script at time" header, are not read.
 */
class ScriptError(val errors: List<Pair<Int, String>>) : IllegalArgumentException(errors.joinToString("\n") { (n, why) -> "line $n: $why" })

private val POS_RE = Regex("""(-?\d+(?:/\d+)?)?(?:([+-])?(\d*)ε)?""")
private val BREAKS_RE = Regex("""keep (\S+) on \(chip, chip \+ (\S+)\] with (\S+), as early as possible, none within (\S+) after as much (\S+)""")
private val APPLY_RE = Regex("""if the chip is in \[([^,]+), ([^,]+)\](?: and mode is (\w+))?, then $MODE_RULES_TEXT\.?""")
private val LOOP_RE = Regex("""every (\S+) s:""")
private val CHANGE_RE = Regex("""(add to|remove from) (every \S+ s|the user edit script|the view script): "(.+)"""")
private const val OUTSIDE_QUOTES = """(?=(?:[^"]*"[^"]*")*[^"]*$)"""
private val OP_RE = Regex(
    """put no (\S+) on (.+)|put (\S+) ([^ %]+)% on (.+)|take no (\S+) off (.+)|take (\S+) off (.+)""",
)

/** x, x+kε, x-kε, kε, -kε, +inf or -inf. */
fun position(text: String): Pos {
    val t = text.trim().replace("−", "-")
    if (t == "+inf" || t == "inf") return Pos.INF
    if (t == "-inf") return Pos.NEG_INF
    val m = POS_RE.matchEntire(t)
    if (t.isEmpty() || m == null) throw IllegalArgumentException("a position is expected, not ${pyRepr(t)}")
    var x: String? = m.groups[1]?.value
    var k = 0L
    if ("ε" in t) {
        val s = m.groups[2]?.value
        k = (m.groups[3]?.value?.ifEmpty { null }?.toLong() ?: 1L) * (if (s == "-") -1 else 1)
        if (x != null && s == null) { // 2ε
            k = x.toLong()
            x = null
        }
    }
    return try {
        Pos(parseFraction(x ?: "0"), k)
    } catch (e: ArithmeticException) {
        throw IllegalArgumentException("a position is expected, not ${pyRepr(t)}")
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("a position is expected, not ${pyRepr(t)}")
    }
}

/** [a, b], [a, b), (a, b], (a, b) or {a}. */
fun intervalOf(text: String): Interval {
    val t = text.trim()
    if (t.length > 2 && t[0] == '{' && t.last() == '}') return Interval.point(position(t.substring(1, t.length - 1)))
    if (t.length < 2 || t[0] !in "[(" || t.last() !in "])" || t.count { it == ',' } != 1) {
        throw IllegalArgumentException("an interval is expected, not ${pyRepr(t)}")
    }
    val (a, b) = t.substring(1, t.length - 1).split(",")
    return Interval.make(position(a), position(b), t[0] == '[', t.last() == ']')
        ?: throw IllegalArgumentException("empty or invalid interval ${position(a)} ${position(b)}")
}

private fun intervalsOf(text: String): IntervalSet {
    val t = text.trim()
    return if (t == "" || t == "{}") IntervalSet.EMPTY else IntervalSet.of(t.split(Regex("""\s+U\s+""")).map { intervalOf(it) })
}

private fun isGroup(text: String): Boolean = text.take(2) in setOf("((", "([", "({") && text.takeLast(2) in setOf("))", "])", "})")

private fun ungrouped(text: String): String = if (isGroup(text)) text.substring(1, text.length - 1) else text

/** These intervals repeating every [period] to the right. */
private fun rightOf(text: String, period: String): Region {
    val s = intervalsOf(ungrouped(text))
    val p = position(period)
    if (s.isEmpty || s.intervals.last().hi.isInfinite || s.intervals[0].lo.isInfinite || p.isInfinite || p.x.sign <= 0) {
        throw IllegalArgumentException("what repeats is bounded, and its period > 0: ${pyRepr(text)} every $period")
    }
    val h = s.intervals[0].lo
    if (s.intervals.last().hi >= h + p) throw IllegalArgumentException("what repeats is shorter than its period: ${pyRepr(text)} every $period")
    return Region.make(h, h, IntervalSet.EMPTY, Pos.ONE, IntervalSet.EMPTY, p, s.shift(-h))
}

/** These intervals repeating every [period] to the left. */
private fun leftOf(text: String, period: String): Region {
    val s = intervalsOf(ungrouped(text))
    val p = position(period)
    if (s.isEmpty || s.intervals.last().hi.isInfinite || s.intervals[0].lo.isInfinite || p.isInfinite || p.x.sign <= 0) {
        throw IllegalArgumentException("what repeats is bounded, and its period > 0: ${pyRepr(text)} every $period")
    }
    val h = s.intervals.last().hi + Pos.EPS
    if (s.intervals[0].lo < h - p) throw IllegalArgumentException("what repeats is shorter than its period: ${pyRepr(text)} every $period")
    return Region.make(h, h, IntervalSet.EMPTY, p, s.shift(p - h))
}

/** A region, as [Region.toString] writes it. */
fun regionOf(text: String): Region {
    var t = text.trim()
    var out = Region.EMPTY
    Regex("""\.\.\. (.+?) repeating every (\S+) to the left(?: U (.*))?""").matchEntire(t)?.let { m ->
        out = leftOf(m.groupValues[1], m.groupValues[2])
        t = m.groups[3]?.value ?: ""
    }
    Regex("""(.*) repeating every (\S+) to the right \.\.\.""").matchEntire(t)?.let { m ->
        val body = m.groupValues[1]
        val grouped = body.takeLast(2) in setOf("))", "])", "})")
        val at = if (grouped) listOf("((", "([", "({").maxOf { body.lastIndexOf(it) } else if (" U " in body) body.lastIndexOf(" U ") + 3 else 0
        out = out or rightOf(body.substring(at), m.groupValues[2])
        t = body.substring(0, at).removeSuffix(" U ")
    }
    return out or Region.of(intervalsOf(t))
}

private fun opOf(text: String): Op {
    val m = OP_RE.matchEntire(text)
        ?: throw IllegalArgumentException("an edit is expected (put K 100% on ..., put no K on ..., take K off ..., take no K off ...), not ${pyRepr(text)}")
    val g = m.groups
    if (g[1] != null) return Op.putNo(g[1]!!.value, regionOf(g[2]!!.value))
    if (g[3] != null) {
        val v = g[4]!!.value
        val value = try {
            parseFraction(v)
        } catch (e: Exception) {
            throw IllegalArgumentException("a percentage is expected, not ${pyRepr(v)}")
        }
        if (!(value.sign > 0 && value <= Rational.of(100))) throw IllegalArgumentException("a percentage is in (0, 100]")
        return Op.put(g[3]!!.value, regionOf(g[5]!!.value), value)
    }
    if (g[6] != null) return Op.takeNo(g[6]!!.value, regionOf(g[7]!!.value))
    return Op.take(g[8]!!.value, regionOf(g[9]!!.value))
}

private fun actionOf(text: String): Action {
    if (text == "run the generator again") return RerunAction()
    if (text == "run the generator once, as soon as possible") return RerunAction(first = true)
    if (text == ConstraintEditAction.toString()) return ConstraintEditAction
    if (text == ShowLine.toString()) return ShowLine
    CHANGE_RE.matchEntire(text)?.let { m -> return ChangeAction(m.groupValues[1] == "add to", m.groupValues[2], eventOf(m.groupValues[3])) }
    return EditAction(text.split(Regex(""", (?=put |take )""")).map { opOf(it) })
}

private val SCREEN_RE = Regex("""keep (.+) with (\S+), as early as possible(?:; a chain of (.+) is its longest break)?""")
private val SCREEN_KIND_RE = Regex("""(\S+) of (\S+) \(none within (\S+) after as much (\S+)\)""")

/** keep K of L (none within P after as much NS), ... with NS, as early as possible[; a chain of K, ... is its longest break]. */
private fun screenBreaksOf(m: MatchResult, rules: RuleSet?): ScreenBreaks {
    val ns = m.groupValues[2]
    val breaks = ArrayList<Triple<String, Rational, Rational>>()
    for (part in m.groupValues[1].split("), ")) {
        val k = SCREEN_KIND_RE.matchEntire(if (part.endsWith(")")) part else "$part)")
            ?: throw IllegalArgumentException("a break like 'Break5 of 300 (none within 3600 after as much $ns)' is expected, not ${pyRepr(part)}")
        if (k.groupValues[4] != ns) throw IllegalArgumentException("the same kind is expected, not ${k.groupValues[4]} and $ns")
        breaks.add(Triple(k.groupValues[1], number(k.groupValues[2]), number(k.groupValues[3])))
    }
    val chain = m.groups[3]?.value?.split(",")?.map { it.trim() } ?: emptyList()
    for (k in breaks.map { it.first } + ns + chain) if (rules != null && k !in rules.order) throw IllegalArgumentException("$k is not in the rules")
    if (!breaks.map { it.first }.containsAll(chain)) throw IllegalArgumentException("a chain is of the breaks kept")
    return ScreenBreaks(breaks, ns, chain, rules = rules)
}

private const val PULL = """(?:, a gap ending inside (\S+) ending at its start)?"""
private val GAP_RE = Regex("""keep (\S+) at least (\S+) apart$PULL""")
private val GAP_AFTER_RE = Regex("""keep (\S+) at least (\S+) after (\S+) or more of (\S+?)$PULL""")
private val EARLY_RE = Regex("""keep (\S+) as early as possible(?:, (\S+) each)?""")
private const val EACH = """(?:, (\S+) each)?"""
private val AT_MOST_RE = Regex("""keep (\S+) at most (\S+) apart$EACH""")
private val AT_MOST_AFTER_RE = Regex("""keep (\S+) at most (\S+) after (\S+) or more of (\S+?)$EACH""")

private fun inRules(rules: RuleSet?, k: String) {
    if (rules != null && k !in rules.order) throw IllegalArgumentException("$k is not in the rules")
}

private fun constraintOf(line: String, rules: RuleSet?): Constraint {
    EARLY_RE.matchEntire(line)?.let { m ->
        inRules(rules, m.groupValues[1])
        return AsEarlyAsPossible(m.groupValues[1], m.groups[2]?.value?.let { number(it) })
    }
    (AT_MOST_RE.matchEntire(line))?.let { m ->
        inRules(rules, m.groupValues[1])
        return MaximumGap(m.groupValues[1], number(m.groupValues[2]), length = m.groups[3]?.value?.let { number(it) })
    }
    (AT_MOST_AFTER_RE.matchEntire(line))?.let { m ->
        inRules(rules, m.groupValues[1])
        inRules(rules, m.groupValues[4])
        return MaximumGap(m.groupValues[1], number(m.groupValues[2]), after = m.groupValues[4], atLeast = number(m.groupValues[3]), length = m.groups[5]?.value?.let { number(it) })
    }
    GAP_RE.matchEntire(line)?.let { m ->
        val pull = m.groups[3]?.value
        for (k in listOfNotNull(m.groupValues[1], pull)) inRules(rules, k)
        return MinimumGap(m.groupValues[1], number(m.groupValues[2]), pull = pull)
    }
    GAP_AFTER_RE.matchEntire(line)?.let { m ->
        val pull = m.groups[5]?.value
        for (k in listOfNotNull(m.groupValues[1], m.groupValues[4], pull)) inRules(rules, k)
        return MinimumGap(m.groupValues[1], number(m.groupValues[2]), after = m.groupValues[4], atLeast = number(m.groupValues[3]), pull = pull)
    }
    SCREEN_RE.matchEntire(line)?.let { m -> if (" (none within " in line) return screenBreaksOf(m, rules) }
    BREAKS_RE.matchEntire(line)?.let { m ->
        val (kind, length0, ns, pause0, ns2) = m.destructured
        if (ns != ns2) throw IllegalArgumentException("the same kind is expected, not $ns and $ns2")
        for (k in listOf(kind, ns)) inRules(rules, k)
        val length = number(length0)
        val pause = number(pause0)
        if (length.sign <= 0 || pause.sign < 0) throw IllegalArgumentException("a length > 0, and a pause >= 0")
        return Breaks(kind, ns, length, pause)
    }
    return KeepOn(opOf(line.substring("keep ".length).replaceFirst(" on chip + ", " on ")))
}

private fun number(text: String): Rational {
    val p = position(text)
    if (p.isInfinite || p.k != 0L) throw IllegalArgumentException("a number is expected, not ${pyRepr(text)}")
    return p.x
}

private fun triggerOf(text: String): Trigger {
    if (text == Start.toString()) return Start
    if (text == Edited().toString()) return Edited()
    if (text == Edited(true).toString()) return Edited(true)
    if (text == Viewed.toString()) return Viewed
    if (text == Uncovered.toString()) return Uncovered
    Regex("""(\d+) mode transitions? happened""").matchEntire(text)?.let { m ->
        val n = m.groupValues[1].toInt()
        if (n < 1) throw IllegalArgumentException("at least 1 mode transition")
        return TransitionCount(n)
    }
    Regex("""the optimization score becomes less than (\S+)""").matchEntire(text)?.let { return ScoreBelow(number(it.groupValues[1])) }
    Regex("""(\S+) ([^ %]+)% exceeds a presence ratio of (\S+) over the next (\S+) when the chip is in \[([^,]+), ([^,]+)\)(?: and mode is (\w+))?""").matchEntire(text)?.let { m ->
        val y = number(m.groupValues[3])
        val h = number(m.groupValues[4])
        val lo = number(m.groupValues[5])
        val hi = number(m.groupValues[6])
        if (h.sign <= 0 || !(lo < hi)) throw IllegalArgumentException("a length > 0, and positions in [$lo, $hi), are expected: ${pyRepr(text)}")
        return PresenceAbove(m.groupValues[1], number(m.groupValues[2]), y, h, Pos(lo), Pos(hi), m.groups[7]?.value)
    }
    val m = Regex("""the chip is (?:in \[([^,]+), ([^,]+)\)|at or after ([^,]+?))(?: and mode is (\w+))?""").matchEntire(text)
        ?: throw IllegalArgumentException("this condition is not understood: ${pyRepr(text)}")
    val at = m.groups[3]?.value
    val lo = number(at ?: m.groupValues[1])
    val hi = if (at != null) Pos.INF else Pos(number(m.groupValues[2]))
    if (!(Pos(lo) < hi)) throw IllegalArgumentException("no position is in [$lo, ${if (hi == Pos.INF) "inf" else hi.toString()})")
    return ChipIn(Pos(lo), hi, m.groups[4]?.value)
}

private fun eventOf(line: String): Event {
    val m = Regex("""if (.+?), then (.+)""").matchEntire(line)
        ?: throw IllegalArgumentException("an instruction is written \"if <condition>, then <what to do>.\"")
    var body = m.groupValues[2]
    if (body.endsWith("....") || (body.endsWith(".") && !body.endsWith("..."))) body = body.dropLast(1) // the end of the sentence
    return Event(triggerOf(m.groupValues[1]), body.split(Regex("; $OUTSIDE_QUOTES")).map { actionOf(it.trim()) })
}

fun parseScript(text: String, previous: Script, rules: RuleSet? = null): Script {
    val lines = text.lines()
    var first = 0
    if (lines.isNotEmpty() && lines[0].startsWith("Generated script at time")) {
        first = lines.indexOfFirst { it.isBlank() }.let { if (it < 0) lines.size else it }
    }
    val was = LinkedHashMap<String, Constraint>()
    for (k in previous.constraints) was[k.toString()] = k
    val parts = LinkedHashMap<Rational, MutableList<Event>>()
    parts[STEP] = ArrayList()
    val covered = ArrayList<Triple<Pos, Pos, String?>>()
    val constraints = ArrayList<Constraint>()
    var live = false
    val errors = ArrayList<Pair<Int, String>>()
    val onEdit = ArrayList<Event>()
    val onView = ArrayList<Event>()
    var period = STEP
    for (i in first until lines.size) {
        val line = lines[i].trim()
        if (line.isEmpty() || line == "(empty script)") continue
        try {
            val lm = LOOP_RE.matchEntire(line)
            if (lm != null) {
                period = number(lm.groupValues[1])
                if (period.sign <= 0) throw IllegalArgumentException("a loop's period is > 0")
                parts.getOrPut(period) { ArrayList() }
                continue
            }
            val am = APPLY_RE.matchEntire(line)
            if (am != null) {
                val a = position(am.groupValues[1])
                val b = position(am.groupValues[2])
                if (a.isInfinite || b.isInfinite || b < a) throw IllegalArgumentException("no position is in [${am.groupValues[1]}, ${am.groupValues[2]}]")
                covered.add(Triple(a, b, am.groups[3]?.value))
            } else if (line.trimEnd('.') == "elsewhere, $MODE_RULES_TEXT") {
                live = true
            } else if (line.startsWith("keep ")) {
                constraints.add(was[line] ?: constraintOf(line, rules))
            } else {
                val e = eventOf(line)
                val (part, name) = when (e.trigger) {
                    is Edited -> onEdit to ON_EDIT
                    Viewed -> onView to ON_VIEW
                    else -> parts.getValue(period) to loopName(period)
                }
                if (e.actions.any { it is ChangeAction && it.target == name }) throw IllegalArgumentException("a script does not change itself: $name")
                part.add(e)
            }
        } catch (e: IllegalArgumentException) {
            errors.add((i + 1) to (e.message ?: ""))
        }
    }
    if (errors.isNotEmpty()) throw ScriptError(errors)
    for (c in constraints) { // (as the generator binds them)
        if (rules != null) c.bind(rules)
        c.bindAll(constraints)
    }
    val same = constraints.map { it.toString() } == was.keys.toList()
    val kept = previous.stretches.filter { st -> same && covered.any { (a, b, m) -> (m == null || m == st.mode) && a <= st.a && st.b <= b } }
    val loops = parts.filter { (p, evs) -> evs.isNotEmpty() || p == STEP }.map { (p, evs) -> Loop(p, evs) }
    return previous.copy(loops = loops, onEdit = onEdit, onView = onView, stretches = kept, covered = covered, live = live, constraints = constraints)
        .carrying(previous).also { it.frame = emptyList() }
}
