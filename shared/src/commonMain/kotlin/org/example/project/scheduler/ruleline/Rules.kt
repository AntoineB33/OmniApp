package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational

/**
 * Element rules, mode rules and percentage rules — a port of `C:\code\line_manager\scripts\rules.py`
 * (`docs/scheduler/scheduler_requirements.md` § the rules).
 *
 * An element rule is L → R:
 * - L, a boolean expression on element kinds and percentages, met at a position p or not. An atom is K (present,
 *   above 0%), K p% (exactly p; K 0% means absent), K >= p%, K > p%, K <= p%, K < p% (an absent K counts as 0%),
 *   combined with not / no, and / &, or / | and parentheses.
 * - R, items all applied. An item is a literal (K [p%] | no K at a location) or an ordered disjunction
 *   K1 or K2 or … (the leftmost operand that can be chosen). A location is relative to p (the position where L is
 *   met) or to l (the left edge of the largest interval where L is met that contains p): "at d", "d units on the
 *   left/right", "on [a, b)", then "of p" or "of l".
 *
 * A mode rule is `mode m [& L] → R`, acting only at the chip ("at 0 of p"). A percentage rule lists the percentages a
 * kind can have, each with a condition on what is at the same position. [RuleSet] refuses a cycle and works out
 * which solver the choices need.
 */
class RuleError(message: String) : IllegalArgumentException(message)

/** Python's `repr` of a string, as the reference's messages print it. */
internal fun pyRepr(s: String): String =
    if ('\'' in s && '"' !in s) "\"$s\"" else "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

/** A rational written as an integer, `a/b` or a decimal (`Fraction(s)`). */
fun parseFraction(s0: String): Rational {
    val s = s0.trim()
    if ('/' in s) {
        val (n, d) = s.split('/')
        return Rational.of(n.trim().toLong(), d.trim().toLong())
    }
    if ('.' in s) {
        val neg = s.startsWith("-")
        val body = s.removePrefix("-").removePrefix("+")
        val (ip, fp) = body.split('.')
        var den = 1L
        repeat(fp.length) { den *= 10 }
        val num = (if (ip.isEmpty()) 0L else ip.toLong()) * den + (if (fp.isEmpty()) 0L else fp.toLong())
        return Rational.of(if (neg) -num else num, den)
    }
    return Rational.of(s.removePrefix("+").toLong())
}

private fun fmt(x: Rational): String = x.toString()

// ---------------------------------------------------------------- left sides

/** What a left side reads: per kind, its regions by percentage (values) and where it is present at all (presence). */
data class View(val values: Map<String, Map<Rational, Region>>, val presence: Map<String, Region>)

sealed class Formula {
    abstract fun kinds(): List<String>
    abstract fun region(vals: Map<String, Map<Rational, Region>>, universe: Region, empty: Region): Region

    /** [region] read through two views: the present part from [pos], the absent part from [neg]. */
    abstract fun region2(pos: View, neg: View, universe: Region, empty: Region): Region
    abstract fun holds(valueOf: (String) -> Rational): Boolean
}

data class Atom(val kind: String, val op: String = "present", val value: Rational? = null) : Formula() {
    fun test(v: Rational): Boolean = when (op) {
        "present" -> v > Rational.ZERO
        "=" -> v == value
        "<" -> v < value!!
        "<=" -> v <= value!!
        ">" -> v > value!!
        ">=" -> v >= value!!
        else -> error(op)
    }

    override fun kinds() = listOf(kind)

    override fun region(vals: Map<String, Map<Rational, Region>>, universe: Region, empty: Region): Region {
        var out = empty
        val byValue = vals[kind].orEmpty()
        for ((v, r) in byValue) if (test(v)) out = out or r
        if (test(Rational.ZERO)) {
            var pres = empty
            for (r in byValue.values) pres = pres or r
            out = out or (universe - pres)
        }
        return out
    }

    override fun region2(pos: View, neg: View, universe: Region, empty: Region): Region {
        var out = empty
        for ((v, r) in pos.values[kind].orEmpty()) if (v.sign != 0 && test(v)) out = out or r
        if (test(Rational.ZERO)) out = out or (universe - (neg.presence[kind] ?: empty))
        return out
    }

    override fun holds(valueOf: (String) -> Rational) = test(valueOf(kind))

    override fun toString(): String = when (op) {
        "present" -> kind
        "=" -> "$kind ${fmt(value!!)}%"
        else -> "$kind $op ${fmt(value!!)}%"
    }
}

data class Not(val f: Formula) : Formula() {
    override fun kinds() = f.kinds()
    override fun region(vals: Map<String, Map<Rational, Region>>, universe: Region, empty: Region) = universe - f.region(vals, universe, empty)
    override fun region2(pos: View, neg: View, universe: Region, empty: Region) = universe - f.region2(neg, pos, universe, empty)
    override fun holds(valueOf: (String) -> Rational) = !f.holds(valueOf)
    override fun toString() = "not ${paren(f)}"
}

data class And(val parts: List<Formula>) : Formula() {
    override fun kinds() = parts.flatMap { it.kinds() }.distinct()

    override fun region(vals: Map<String, Map<Rational, Region>>, universe: Region, empty: Region): Region {
        var out = universe
        for (p in parts) out = out and p.region(vals, universe, empty)
        return out
    }

    override fun region2(pos: View, neg: View, universe: Region, empty: Region): Region {
        var out = universe
        for (p in parts) out = out and p.region2(pos, neg, universe, empty)
        return out
    }

    override fun holds(valueOf: (String) -> Rational) = parts.all { it.holds(valueOf) }
    override fun toString() = parts.joinToString(" & ") { paren(it) }
}

data class Or(val parts: List<Formula>) : Formula() {
    override fun kinds() = parts.flatMap { it.kinds() }.distinct()

    override fun region(vals: Map<String, Map<Rational, Region>>, universe: Region, empty: Region): Region {
        var out = empty
        for (p in parts) out = out or p.region(vals, universe, empty)
        return out
    }

    override fun region2(pos: View, neg: View, universe: Region, empty: Region): Region {
        var out = empty
        for (p in parts) out = out or p.region2(pos, neg, universe, empty)
        return out
    }

    override fun holds(valueOf: (String) -> Rational) = parts.any { it.holds(valueOf) }
    override fun toString() = parts.joinToString(" | ") { paren(it) }
}

val TRUE: Formula = And(emptyList())

private fun paren(f: Formula): String =
    if ((f is And && f.parts.size > 1) || (f is Or && f.parts.size > 1)) "($f)" else f.toString()

fun conjuncts(f: Formula): List<Formula> = if (f is And) f.parts else listOf(f)

// ---------------------------------------------------------------- right sides

data class Lit(
    val kind: String,
    /** Relative to the anchor. */
    val where: Interval,
    /** "p" or "l". */
    val anchor: String = "p",
    /** An exact percentage, or null: present (100% if added). */
    val value: Rational? = null,
    val negated: Boolean = false,
) {
    val isPoint: Boolean get() = where.isPoint

    /** The offset from the anchor, for a single position. */
    val rel: Pos? get() = if (where.isPoint) where.lo else null

    override fun toString(): String {
        var s = (if (negated) "no " else "") + kind + (if (value != null) " ${fmt(value)}%" else "")
        if (!isPoint) return "$s on $where of $anchor"
        val d = rel!!.x
        if (d.sign != 0) {
            val a = d.abs()
            s += " ${fmt(a)} unit${if (a != Rational.ONE) "s" else ""} on the ${if (d.sign > 0) "right" else "left"}"
        }
        return s + (if (d.sign != 0 || anchor != "p") " of $anchor" else "")
    }
}

data class Item(
    val options: List<Lit>,
    /** How it is written, when an operand is a hidden kind. */
    val text: String = "",
    /** For each operand, its elements relative to l when it is placed relative to p. */
    val extra: List<List<Lit>> = emptyList(),
) {
    val isOr: Boolean get() = options.size > 1

    /** The operands and the elements that go with them. */
    fun allLits(): List<Lit> = options + extra.flatten()

    override fun toString(): String = text.ifEmpty { options.joinToString(" or ") }
}

/** Prefix of the hidden kinds that stand for an operand "B & C" of an "or". */
const val HIDDEN = "§"

fun isHidden(kind: String): Boolean = kind.startsWith(HIDDEN)

data class Rule(
    /** A formula ([TRUE]: a mode rule with no condition on elements). */
    val lhs: Formula,
    val mode: String?,
    val rhs: List<Item>,
    val text: String = "",
    /** Every literal as written, in order (when hidden kinds stand for some). */
    val written: List<Lit> = emptyList(),
    /** The rules of its hidden kinds: §(B & C) → B & C. */
    val internal: List<Rule> = emptyList(),
) {
    val isModeRule: Boolean get() = mode != null

    fun lits(): List<Lit> = rhs.flatMap { it.allLits() }

    fun writtenLits(): List<Lit> = written.ifEmpty { lits() }

    override fun toString(): String {
        val left = (if (mode != null) listOf("mode $mode") else emptyList()) +
            (if (conjuncts(lhs).isNotEmpty()) listOf(lhs.toString()) else emptyList())
        return left.joinToString(" & ") + " → " + rhs.joinToString(" & ")
    }
}

/** A percentage a kind can have, and the condition on what is at the same position. */
sealed class PercentCond {
    /** The other elements there sum to exactly [sum]%. */
    data class Others(val sum: Rational) : PercentCond()

    /** Exactly these other elements are there (a percentage, or null: any). */
    data class Only(val listed: List<Pair<String, Rational?>>) : PercentCond()
}

data class PercentOption(val value: Rational, val cond: PercentCond? = null) {
    override fun toString(): String = when (cond) {
        null -> "${fmt(value)}%"
        is PercentCond.Others -> "${fmt(value)}% with others ${fmt(cond.sum)}%"
        is PercentCond.Only -> "${fmt(value)}% with only " + cond.listed.joinToString(", ") { (k, v) -> k + (if (v != null) " ${fmt(v)}%" else "") }
    }
}

/** The percentages a kind can have, each with its condition. A kind with no percentage rule can have any. */
data class PercentRule(val kind: String, val options: List<PercentOption>, val text: String = "") {
    override fun toString(): String = text.ifEmpty { "$kind can be " + options.joinToString(" | ") }
}

private const val NUM = """[-+]?\d+(?:/\d+|\.\d+)?"""
private val PERCENT = Regex("""\s*([A-Za-z_][A-Za-z0-9_]*)\s+(?:is\s+always|can\s+be)\s*:?\s*(.+?)\s*""")

/** A percentage rule, or null if the text is not one (it is then an element or mode rule). */
fun parsePercentRule(text: String): PercentRule? {
    val m = PERCENT.matchEntire(text)
    if (m == null || "→" in text || "->" in text) return null
    val kind = kindOf(m.groupValues[1])
    val opts = ArrayList<PercentOption>()
    for (part in m.groupValues[2].split(Regex("""\s*\|\s*"""))) {
        val mo = Regex("""($NUM)\s*%(?:\s+with\s+(.+))?""").matchEntire(part.trim())
            ?: throw RuleError("bad percentage ${pyRepr(part)} in ${pyRepr(text)}")
        val value = pct(mo.groupValues[1], false)
        var cond: PercentCond? = null
        if (mo.groups[2] != null) {
            val c = mo.groupValues[2].trim()
            val m1 = Regex("""(?:others|other elements(?: that have a total sum of)?)\s+($NUM)\s*%""").matchEntire(c)
            val m2 = Regex("""only\s+(.+)""").matchEntire(c)
            cond = when {
                m1 != null -> PercentCond.Others(pct(m1.groupValues[1], false))
                m2 != null -> {
                    val listed = ArrayList<Pair<String, Rational?>>()
                    for (item in m2.groupValues[1].trim().split(Regex("""\s*(?:,|\band\b)\s*"""))) {
                        val mk = Regex("""([A-Za-z_][A-Za-z0-9_]*)(?:\s+($NUM)\s*%)?""").matchEntire(item)
                        if (mk == null || mk.groupValues[1] == kind) throw RuleError("bad element ${pyRepr(item)} in ${pyRepr(text)}")
                        listed.add(kindOf(mk.groupValues[1]) to (if (mk.groups[2] != null) pct(mk.groupValues[2], false) else null))
                    }
                    PercentCond.Only(listed)
                }
                else -> throw RuleError("bad condition ${pyRepr(c)} in ${pyRepr(text)}")
            }
        }
        opts.add(PercentOption(value, cond))
    }
    return PercentRule(kind, opts, text.trim())
}

class RuleSet(rules: List<Rule>, percent: List<PercentRule> = emptyList()) {
    val percent: Map<String, PercentRule>
    val rules: List<Rule>
    val element: List<Rule>
    val modeRules: List<Rule>

    /** Kinds in order of first appearance in the text of the rules (tie-breaks). */
    val rank: Map<String, Int>
    val edges: Map<String, Set<String>>
    val linked: Map<String, Int>
    val blocks: List<List<String>>
    val order: List<String>
    val blockOf: Map<String, Int>
    val choiceDependent: Set<String>
    val mixed: Boolean
    val split: List<Triple<List<String>, List<String>, Formula>>
    val lattice: Boolean
    var searched: Boolean
        private set
    val settled: Boolean
    var causal: Boolean
        private set
    val orItems: List<Pair<Int, Int>>
    val orRank: Map<Pair<Int, Int>, Int>
    val reach: Rational
    val finite: Boolean
    val ruled: Set<String>
    val monotone: Boolean
    val pointwise: Boolean
    val local: Boolean
    var oneWay: Boolean = false
        private set
    var free: Set<String> = emptySet()
        private set
    var blocked: Set<String> = emptySet()
        private set
    val epsReach: Int
    val smallestOffset: Rational?

    init {
        val pc = LinkedHashMap<String, PercentRule>()
        for (pr in percent) {
            if (pr.kind in pc) throw RuleError("two percentage rules for ${pr.kind}")
            pc[pr.kind] = pr
        }
        this.percent = pc
        // the rules of the hidden kinds come after the written ones (one per hidden kind)
        val internal = LinkedHashMap<String, Rule>()
        for (r in rules) for (ir in r.internal) internal[(ir.lhs as Atom).kind] = ir
        this.rules = rules + internal.values
        element = this.rules.filter { !it.isModeRule }
        modeRules = this.rules.filter { it.isModeRule }
        val rk = LinkedHashMap<String, Int>()
        for (r in this.rules) for (k in r.lhs.kinds() + r.writtenLits().map { it.kind }) if (k !in rk) rk[k] = rk.size
        rank = rk
        for (r in this.rules) {
            if (r.isModeRule && (r.lits() + r.writtenLits()).any { it.anchor != "p" || it.where != Interval.point(Pos.ZERO) }) {
                throw RuleError("$r: a mode rule can only act at the position of the chip")
            }
            if (!r.isModeRule && r.lhs.kinds().isEmpty()) throw RuleError("$r: an element rule needs a kind on its left side")
        }
        val ed = LinkedHashMap<String, MutableSet<String>>()
        for (k in rk.keys) ed[k] = LinkedHashSet()
        for (r in element) for (k in r.lhs.kinds()) ed.getValue(k).addAll(r.lits().map { it.kind })
        edges = ed
        linked = linkedGroups(ed)
        blocks = groups(ed)
        order = blocks.flatten()
        blockOf = blocks.withIndex().flatMap { (n, b) -> b.map { it to n } }.toMap()
        val cycles = blocks.filter { it.size > 1 || it[0] in ed.getValue(it[0]) }
        if (cycles.isNotEmpty()) {
            throw RuleError("the rules depend on each other in a cycle: " + cycles.joinToString("; ") { it.joinToString(", ") })
        }
        // chosen kinds: those an "or" choice can change
        val chosen = element.flatMap { r -> r.rhs.filter { it.isOr }.flatMap { it.allLits() }.filter { !it.negated }.map { it.kind } }.toSet()
        val dep = LinkedHashSet<String>()
        val todo = ArrayList(chosen)
        while (todo.isNotEmpty()) {
            val k = todo.removeAt(todo.size - 1)
            if (dep.add(k)) {
                for (r in element) if (k in r.lhs.kinds()) for (o in r.lits()) if (!o.negated) todo.add(o.kind)
            }
        }
        choiceDependent = dep
        var lat = element.all { r -> r.rhs.filter { it.isOr }.all { it.options.all { o -> !o.negated && o.isPoint } } }
        mixed = element.any { r -> r.rhs.any { it.isOr && it.allLits().map { o -> o.anchor }.toSet().size > 1 } }
        lat = lat && !mixed
        val sp = ArrayList<Triple<List<String>, List<String>, Formula>>()
        val absentRead = ArrayList<Pair<Rule, Set<String>>>()
        for (r in element) {
            val read = r.lhs.kinds().toSet() intersect dep
            if (read.isNotEmpty() && !monotoneIn(r.lhs, dep)) absentRead.add(r to read)
            val cd = ArrayList<String>()
            val ncd = ArrayList<String>()
            val rest = ArrayList<Formula>()
            for (f in conjuncts(r.lhs)) {
                when {
                    f is Atom && f.kind in dep && f.op == "present" -> cd.add(f.kind)
                    f is Not && f.f is Atom && f.f.kind in dep && f.f.op == "present" -> ncd.add(f.f.kind)
                    f is Atom && f.kind in dep && f.op == "=" && f.value == Rational.ZERO -> ncd.add(f.kind)
                    (f.kinds().toSet() intersect dep).isNotEmpty() -> lat = false
                    else -> rest.add(f)
                }
            }
            if (read.isNotEmpty() && r.lits().any { it.anchor != "p" || !it.isPoint }) lat = false
            sp.add(Triple(cd.distinct(), ncd.distinct(), And(rest)))
        }
        split = sp
        // the lattices do not check the percentage rules while choosing: the sweep does
        lattice = lat && pc.isEmpty()
        searched = mixed || (absentRead.isNotEmpty() && !lat)
        settled = absentRead.isEmpty() &&
            element.none { r -> (r.lhs.kinds().toSet() intersect dep).isNotEmpty() && r.lits().any { it.anchor == "l" } }
        val readKinds = element.flatMap { it.lhs.kinds() }.toSet()
        causal = true
        for (r in element) {
            val decided = r.lhs.kinds().toSet() intersect dep
            for (it in r.rhs) for (o in it.allLits()) {
                if ((it.isOr || decided.isNotEmpty()) && !o.negated && o.kind in dep && o.kind in readKinds && o.where.lo < Pos.ZERO) {
                    causal = false
                    searched = searched || !lat
                }
            }
        }
        // the "or" items, in the order they are decided at one position
        val items = element.withIndex().flatMap { (i, r) -> r.rhs.withIndex().filter { it.value.isOr }.map { i to it.index } }
        val below = HashMap<Pair<Int, Int>, Set<String>>()
        for ((i, j) in items) {
            val out = HashSet<String>()
            val t = ArrayList(element[i].rhs[j].allLits().map { it.kind })
            while (t.isNotEmpty()) {
                val k = t.removeAt(t.size - 1)
                if (out.add(k)) t.addAll(ed[k].orEmpty())
            }
            below[i to j] = out
        }
        val oi = ArrayList<Pair<Int, Int>>()
        while (oi.size < items.size) {
            val rest = items.filter { it !in oi }
            val ready = rest.filter { a ->
                rest.none { b ->
                    b != a && (element[a.first].lhs.kinds().toSet() intersect below.getValue(b)).isNotEmpty() &&
                        (element[b.first].lhs.kinds().toSet() intersect below.getValue(a)).isEmpty()
                }
            }
            oi.add(ready.ifEmpty { rest }[0])
        }
        orItems = oi
        orRank = oi.withIndex().associate { it.value to it.index }
        val ends = this.rules.flatMap { r -> r.lits().flatMap { listOf(it.where.lo, it.where.hi) } }.filter { it.isFinite }.map { it.x.abs() }
        reach = ends.maxOrNull() ?: Rational.ZERO
        finite = element.all { r -> r.lits().all { it.where.lo.isFinite && it.where.hi.isFinite } }
        val ru = LinkedHashSet<String>(order)
        for (r in this.rules) {
            ru.addAll(r.lhs.kinds())
            ru.addAll(r.lits().map { it.kind })
        }
        ru.addAll(pc.keys)
        for (pr in pc.values) for (o in pr.options) (o.cond as? PercentCond.Only)?.listed?.forEach { ru.add(it.first) }
        ruled = ru
        monotone = element.all { r -> readsPresence(r.lhs) && r.lits().all { it.anchor == "p" } }
        pointwise = element.all { r -> r.lits().all { it.isPoint && it.rel!!.isZero && it.anchor == "p" } }
        local = monotone && pointwise
        oneWayCheck()
        val opened = this.rules.sumOf { r -> r.lits().sumOf { o -> listOf(o.where.lo, o.where.hi).count { it.isFinite && it.k != 0L } } }
        epsReach = 1 + minOf(maxOf(opened, 1), order.size + 1)
        val sizes = ends.filter { it.sign != 0 } +
            this.rules.flatMap { r -> r.lits().map { it.where.length } }.filter { it > Rational.ZERO && it.isFinite }
        smallestOffset = sizes.minOrNull()
    }

    /**
     * one_way: no choice has to look ahead. Every position acts towards the right or on itself, and each "or" has an
     * operand nothing can stop ([free]); [blocked] is what those operands forbid.
     */
    private fun oneWayCheck() {
        val lits = element.flatMap { it.lits() }
        val forbidden = lits.filter { it.negated }.map { it.kind }.toSet()
        val places = HashMap<String, MutableSet<String>>()
        val blocks = HashMap<String, MutableSet<String>>()
        for (r in element) for (k in r.lhs.kinds()) {
            places.getOrPut(k) { HashSet() }.addAll(r.lits().filter { !it.negated }.map { it.kind })
            blocks.getOrPut(k) { HashSet() }.addAll(r.lits().filter { it.negated }.map { it.kind })
        }
        fun reached(k: String): Set<String> {
            val seen = HashSet<String>()
            val todo = arrayListOf(k)
            while (todo.isNotEmpty()) {
                val x = todo.removeAt(todo.size - 1)
                if (seen.add(x)) todo.addAll(places[x].orEmpty())
            }
            return seen
        }
        val fr = HashSet<String>()
        val bl = HashSet<String>()
        var ow = monotone && lits.all { it.anchor == "p" && it.where.lo >= Pos.ZERO && (it.value == null || it.value == Rational.of(100)) }
        val sure = element.flatMap { r -> r.rhs.filter { !it.isOr }.flatMap { it.options }.filter { !it.negated }.map { it.kind } }.toSet()
        for (r in element) for (it in r.rhs) {
            if (!it.isOr) continue
            val safe = it.options.filter { o -> !o.negated && (reached(o.kind) intersect forbidden).isEmpty() }.map { o -> o.kind }
            if (safe.isEmpty()) {
                ow = false
                continue
            }
            val got = reached(safe.last())
            fr.addAll(got)
            for (k in got) bl.addAll(blocks[k].orEmpty())
        }
        if ((bl intersect sure).isNotEmpty()) ow = false
        oneWay = ow
        free = fr
        blocked = bl
    }

    fun kinds(): List<String> = order
}

/** Kind -> the number of its group of kinds linked by the edges either way. */
private fun linkedGroups(edges: Map<String, Set<String>>): Map<String, Int> {
    val near = HashMap<String, MutableSet<String>>()
    for (k in edges.keys) near[k] = HashSet()
    for ((k, out) in edges) for (o in out) {
        near.getOrPut(o) { HashSet() }
        near.getValue(k).add(o)
        near.getValue(o).add(k)
    }
    val of = HashMap<String, Int>()
    var n = 0
    for (root in near.keys.sorted()) {
        if (root in of) continue
        val todo = arrayListOf(root)
        while (todo.isNotEmpty()) {
            val k = todo.removeAt(todo.size - 1)
            if (k !in of) {
                of[k] = n
                todo.addAll(near.getValue(k).filter { it !in of })
            }
        }
        n++
    }
    return of
}

/** The strongly connected groups, each after the groups with an edge to it, alphabetical otherwise (Tarjan, Kahn). */
private fun groups(edges: Map<String, Set<String>>): List<List<String>> {
    val index = HashMap<String, Int>()
    val low = HashMap<String, Int>()
    val stack = ArrayList<String>()
    val on = HashSet<String>()
    val comps = ArrayList<List<String>>()
    for (root in edges.keys.sorted()) {
        if (root in index) continue
        val work = ArrayList<Pair<String, Iterator<String>>>()
        work.add(root to edges.getValue(root).sorted().iterator())
        index[root] = index.size
        low[root] = index.getValue(root)
        stack.add(root)
        on.add(root)
        while (work.isNotEmpty()) {
            val (v, it) = work.last()
            if (it.hasNext()) {
                val w = it.next()
                if (w !in index) {
                    index[w] = index.size
                    low[w] = index.getValue(w)
                    stack.add(w)
                    on.add(w)
                    work.add(w to edges.getValue(w).sorted().iterator())
                } else if (w in on) {
                    low[v] = minOf(low.getValue(v), index.getValue(w))
                }
                continue
            }
            work.removeAt(work.size - 1)
            if (work.isNotEmpty()) {
                val u = work.last().first
                low[u] = minOf(low.getValue(u), low.getValue(v))
            }
            if (low[v] == index[v]) {
                val comp = ArrayList<String>()
                while (true) {
                    val w = stack.removeAt(stack.size - 1)
                    on.remove(w)
                    comp.add(w)
                    if (w == v) break
                }
                comps.add(comp.sorted())
            }
        }
    }
    val of = HashMap<String, List<String>>()
    for (c in comps) for (k in c) of[k] = c
    val succ = HashMap<List<String>, Set<List<String>>>()
    for (c in comps) succ[c] = c.flatMap { k -> edges.getValue(k).map { of.getValue(it) } }.toSet() - setOf(c)
    val indeg = HashMap<List<String>, Int>()
    for (c in comps) indeg[c] = 0
    for (hs in succ.values) for (h in hs) indeg[h] = indeg.getValue(h) + 1
    val cmp = Comparator<List<String>> { a, b ->
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return@Comparator c
        }
        a.size.compareTo(b.size)
    }
    val ready = indeg.filter { it.value == 0 }.keys.sortedWith(cmp).toMutableList()
    val out = ArrayList<List<String>>()
    while (ready.isNotEmpty()) {
        val c = ready.removeAt(0)
        out.add(c)
        for (h in succ.getValue(c).sortedWith(cmp)) {
            indeg[h] = indeg.getValue(h) - 1
            if (indeg[h] == 0) ready.add(h)
        }
        ready.sortWith(cmp)
    }
    return out
}

/** f only grows when these kinds get present: they are read as present, never under not. */
private fun monotoneIn(f: Formula, kinds: Set<String>): Boolean = when (f) {
    is Atom -> f.kind !in kinds || !f.test(Rational.ZERO)
    is Not -> (f.kinds().toSet() intersect kinds).isEmpty()
    is And -> f.parts.all { monotoneIn(it, kinds) }
    is Or -> f.parts.all { monotoneIn(it, kinds) }
}

private fun readsPresence(f: Formula): Boolean = when (f) {
    is Atom -> f.op == "present"
    is And -> f.parts.all { readsPresence(it) }
    is Or -> f.parts.all { readsPresence(it) }
    is Not -> false
}

/** The gcd of the nonzero offsets of these single-position literals. */
fun latticeStep(lits: List<Lit>): Rational? {
    var g: Rational? = null
    for (o in lits) {
        val r = o.rel ?: continue
        if (r.isZero) continue
        val d = r.x.abs()
        g = if (g == null) d else fgcd(g, d)
    }
    return g
}

private fun fgcd(a: Rational, b: Rational): Rational {
    val d = Rational.lcmLong(a.den, b.den)
    return Rational.of(Rational.gcd((a * Rational.of(d)).toLongExact(), (b * Rational.of(d)).toLongExact()), d)
}

// ---------------------------------------------------------------- parsing

private val KEYWORDS = setOf("mode", "no", "not", "at", "on", "of", "units", "unit", "or", "and", "if", "then", "element", "elements")

private fun kindOf(tok: String): String {
    if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(tok) || tok in KEYWORDS) throw RuleError("bad element kind ${pyRepr(tok)}")
    return tok
}

private fun pct(s: String, allowZero: Boolean): Rational {
    val v = parseFraction(s)
    val ok = if (allowZero) v >= Rational.ZERO && v <= Rational.of(100) else v > Rational.ZERO && v <= Rational.of(100)
    if (!ok) throw RuleError("percentage $v not in ${if (allowZero) "[0" else "(0"}, 100]")
    return v
}

private val TOKEN = Regex("""\s*(>=|<=|≥|≤|[()&|!<>=]|$NUM\s*%|[A-Za-z_][A-Za-z0-9_]*|[^\s()&|!<>=]+)""")

private fun tokens(text0: String): List<String> {
    val text = text0.trim()
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        val m = TOKEN.matchAt(text, i) ?: throw RuleError("bad token at ${pyRepr(text.substring(i))}")
        out.add(m.groupValues[1])
        i = m.range.last + 1
        while (i < text.length && text[i].isWhitespace()) i++
    }
    return out.filter { it != "element" && it != "elements" }
}

private class LhsParser(text: String) {
    val t = tokens(text)
    var i = 0
    var mode: String? = null

    fun peek(): String? = t.getOrNull(i)

    fun next(): String {
        val tok = peek() ?: throw RuleError("the left side ends too early")
        i++
        return tok
    }

    fun parse(): Formula {
        val f = expr(true)
        if (peek() != null) throw RuleError("unexpected ${pyRepr(peek()!!)} in the left side")
        return f
    }

    fun expr(top: Boolean = false): Formula {
        val parts = arrayListOf(conj(top))
        while (peek() == "or" || peek() == "|") {
            next()
            parts.add(conj(false))
        }
        if (parts.size > 1 && top && mode != null) throw RuleError("'mode m' is a condition of the whole left side")
        return if (parts.size == 1) parts[0] else Or(parts)
    }

    fun conj(top: Boolean): Formula {
        val parts = ArrayList<Formula>()
        while (true) {
            unary(top)?.let { parts.add(it) }
            if (peek() != "and" && peek() != "&") break
            next()
        }
        if (parts.isEmpty()) return TRUE
        return if (parts.size == 1) parts[0] else And(parts)
    }

    fun unary(top: Boolean): Formula? {
        val tok = next()
        if (tok == "not" || tok == "no" || tok == "!") return Not(unary(false)!!)
        if (tok == "(") {
            val f = expr()
            if (next() != ")") throw RuleError("missing ')'")
            return f
        }
        if (tok == "mode") {
            if (!top || mode != null) throw RuleError("'mode m' appears once, as a condition of the whole left side")
            mode = next()
            return null
        }
        val kind = kindOf(tok)
        var op = "present"
        if (peek() in setOf(">=", "<=", ">", "<", "=", "≥", "≤")) {
            val n = next()
            op = mapOf("≥" to ">=", "≤" to "<=")[n] ?: n
        }
        val p = peek()
        if (p != null && p.endsWith("%")) {
            val v = pct(next().dropLast(1).trim(), true)
            return Atom(kind, if (op == "present") "=" else op, v)
        }
        if (op != "present") throw RuleError("$kind $op needs a percentage")
        return Atom(kind)
    }
}

private fun lit(text: String): Lit {
    var t = text.replace(Regex("""\belements?\b"""), " ").trim()
    var negated = false
    Regex("""no\s+(.*)""").matchEntire(t)?.let {
        negated = true
        t = it.groupValues[1]
    }
    val m = Regex("""(\w+)\s*(.*)""").matchEntire(t) ?: throw RuleError("bad literal ${pyRepr(text)}")
    val kind = kindOf(m.groupValues[1])
    var rest = m.groupValues[2].trim()
    var value: Rational? = null
    Regex("""($NUM)\s*%\s*(.*)""").matchEntire(rest)?.let {
        if (negated) throw RuleError("'no' takes no percentage")
        value = pct(it.groupValues[1], true)
        rest = it.groupValues[2].trim()
        if (value == Rational.ZERO) { // "B 0%" is "no B": an element with 0% is absent
            negated = true
            value = null
        }
    }
    var anchor: String? = null
    Regex("""(.*?)\s*\bof\s+(p|l)""").matchEntire(rest)?.let {
        rest = it.groupValues[1].trim()
        anchor = it.groupValues[2]
    }
    var where = Interval.point(Pos.ZERO)
    if (rest.isNotEmpty()) {
        val m1 = Regex("""at\s+($NUM)""").matchEntire(rest)
        val m2 = Regex("""on\s+([\[({].+)""").matchEntire(rest)
        val m3 = Regex("""($NUM)\s+units?\s+(?:on\s+|to\s+)?(?:the\s+)?(left|right)""").matchEntire(rest)
        where = when {
            m1 != null -> Interval.point(Pos(parseFraction(m1.groupValues[1])))
            m2 != null -> parseInterval(m2.groupValues[1])
            m3 != null -> {
                val d = parseFraction(m3.groupValues[1])
                Interval.point(Pos(if (m3.groupValues[2] == "left") -d else d))
            }
            else -> throw RuleError("bad location ${pyRepr(rest)}")
        }
    }
    return Lit(kind, where, anchor ?: "p", value, negated)
}

/** `intervals.parse_interval`: `'[a, b)'`, `'(a, b]'`, `'{a}'` or a bare number; decimals and `inf` allowed. */
fun parseInterval(text: String): Interval {
    fun conv(s: String): Pos = when (s) {
        "-inf", "-oo" -> Pos.NEG_INF
        "inf", "+inf", "oo" -> Pos.INF
        else -> Pos(parseFraction(s))
    }
    val t = text.trim()
    if (t.startsWith("{") && t.endsWith("}")) return Interval.point(Pos(parseFraction(t.substring(1, t.length - 1))))
    if (t[0] in "[(" && t[t.length - 1] in "])") {
        val parts = t.substring(1, t.length - 1).split(",").map { it.trim() }
        if (parts.size != 2) throw RuleError("bad interval ${pyRepr(text)}")
        return Interval.make(conv(parts[0]), conv(parts[1]), t[0] == '[', t[t.length - 1] == ']')
            ?: throw IllegalArgumentException("empty or invalid interval")
    }
    return Interval.point(Pos(parseFraction(t)))
}

private val INTERVAL = Regex("""\bon\s*[\[({][^\])}]*[\])}]""")

/** A node of a right side: a literal, or an "and"/"or" of nodes. */
private sealed class Node {
    data class L(val lit: Lit) : Node()
    data class Op(val op: String, val parts: List<Node>) : Node()
}

private class RhsParser(text: String) {
    val t: List<String>
    var i = 0

    init {
        val saved = ArrayList<String>()
        val s = INTERVAL.replace(text) { saved.add(it.value); "\u0000${saved.size - 1}\u0000" }
        val pieces = ArrayList<String>()
        var last = 0
        for (m in Regex("""(\(|\)|&|\band\b|\bor\b)""").findAll(s)) {
            pieces.add(s.substring(last, m.range.first))
            pieces.add(m.value)
            last = m.range.last + 1
        }
        pieces.add(s.substring(last))
        t = pieces.map { p -> Regex("\u0000(\\d+)\u0000").replace(p) { saved[it.groupValues[1].toInt()] }.trim() }.filter { it.isNotEmpty() }
    }

    fun peek(): String? = t.getOrNull(i)

    fun next(): String {
        val tok = peek() ?: throw RuleError("the right side ends too early")
        i++
        return tok
    }

    fun parse(): Node {
        val node = expr()
        if (peek() != null) throw RuleError("unexpected ${pyRepr(peek()!!)} in the right side")
        return node
    }

    fun expr(): Node {
        val parts = arrayListOf(ors())
        while (peek() == "&" || peek() == "and") {
            next()
            parts.add(ors())
        }
        return if (parts.size == 1) parts[0] else Node.Op("and", parts)
    }

    fun ors(): Node {
        val parts = arrayListOf(unary())
        while (peek() == "or") {
            next()
            parts.add(unary())
        }
        return if (parts.size == 1) parts[0] else Node.Op("or", parts)
    }

    fun unary(): Node {
        val tok = next()
        if (tok == "(") {
            val node = expr()
            if (next() != ")") throw RuleError("missing ')' in the right side")
            return node
        }
        if (tok == "no" && peek() == "(") {
            next()
            val node = expr()
            if (next() != ")") throw RuleError("missing ')' in the right side")
            return negated(node)
        }
        if (tok == ")" || tok == "&" || tok == "and" || tok == "or") throw RuleError("unexpected ${pyRepr(tok)} in the right side")
        return Node.L(lit(tok))
    }
}

/** The negation of a right side's node, made of literals (De Morgan). */
private fun negated(node: Node): Node = when (node) {
    is Node.L -> {
        val o = node.lit
        if (o.value != null) throw RuleError("'no (...)' takes no percentage: $o")
        Node.L(Lit(o.kind, o.where, o.anchor, null, !o.negated))
    }
    is Node.Op -> Node.Op(if (node.op == "and") "or" else "and", node.parts.map { negated(it) })
}

private fun conjunctNodes(node: Node): List<Node> =
    if (node is Node.Op && node.op == "and") node.parts.flatMap { conjunctNodes(it) } else listOf(node)

/** The operands of an "or", in the order they are tried. */
private fun operands(node: Node): List<List<Lit>> = when {
    node is Node.L -> listOf(listOf(node.lit))
    node is Node.Op && node.op == "or" -> node.parts.flatMap { operands(it) }
    else -> {
        var out = listOf(emptyList<Lit>())
        for (p in (node as Node.Op).parts) {
            val ops = operands(p)
            out = out.flatMap { a -> ops.map { b -> a + b } }
        }
        out
    }
}

private fun shown(node: Node, inner: Boolean = false): String = when (node) {
    is Node.L -> node.lit.toString()
    is Node.Op -> {
        val s = node.parts.joinToString(if (node.op == "or") " or " else " & ") { shown(it, true) }
        if (inner) "($s)" else s
    }
}

private fun litsOf(node: Node): List<Lit> = when (node) {
    is Node.L -> listOf(node.lit)
    is Node.Op -> node.parts.flatMap { litsOf(it) }
}

fun parseRule(text: String): Rule {
    var t = text.trim()
    t = t.replace(Regex("""^if\s+"""), "")
    if ("→" !in t && "->" !in t) t = t.replace(Regex(""",?\s*then\s+"""), " → ")
    val parts = t.split(Regex("→|->"))
    if (parts.size != 2) throw RuleError("a rule has one arrow: ${pyRepr(text)}")
    val p = LhsParser(parts[0])
    val lhs = p.parse()
    val tree = RhsParser(parts[1]).parse()
    val items = ArrayList<Item>()
    val internal = ArrayList<Rule>()
    for (node in conjunctNodes(tree)) {
        if (node is Node.L) {
            items.add(Item(listOf(node.lit)))
            continue
        }
        val opts = ArrayList<Lit>()
        val extra = ArrayList<List<Lit>>()
        var hidden = false
        for (conj0 in operands(node)) {
            var conj = conj0
            if (conj.size == 1) {
                opts.add(conj[0])
                extra.add(emptyList())
                continue
            }
            if (conj.map { it.anchor }.toSet().size > 1) {
                // the elements relative to p are a hidden kind at p; those relative to l go with it
                extra.add(conj.filter { it.anchor == "l" })
                conj = conj.filter { it.anchor == "p" }
                if (conj.size == 1) {
                    opts.add(conj[0])
                    hidden = true
                    continue
                }
            } else {
                extra.add(emptyList())
            }
            // the operand "B & C" is the hidden kind §(B & C), placed where the operand goes, whose rule places B and C
            val name = HIDDEN + "(" + conj.joinToString(" & ") + ")"
            opts.add(Lit(name, Interval.point(Pos.ZERO), conj[0].anchor))
            val inner = conj.map { Item(listOf(Lit(it.kind, it.where, "p", it.value, it.negated))) }
            internal.add(Rule(Atom(name), null, inner, "$name → " + conj.joinToString(" & ")))
            hidden = true
        }
        items.add(Item(opts, if (hidden) shown(node) else "", if (extra.any { it.isNotEmpty() }) extra else emptyList()))
    }
    val written = if (internal.isNotEmpty()) litsOf(tree) else emptyList()
    return Rule(lhs, p.mode, items, text, written, internal)
}

/** The rule set of these texts (percentage rules among them), without the satisfiability check (`solver.satisfiable`). */
fun ruleSetOf(texts: List<String>): RuleSet {
    val pct = texts.map { parsePercentRule(it) }
    return RuleSet(texts.zip(pct).filter { it.second == null }.map { parseRule(it.first) }, pct.filterNotNull())
}
