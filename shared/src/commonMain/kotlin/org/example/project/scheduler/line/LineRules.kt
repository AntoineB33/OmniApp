package org.example.project.scheduler.line

import org.example.project.scheduler.line.Rational.Companion.ZERO

/**
 * Rules: pointwise, translation-invariant constraints between element kinds — a port of
 * `C:\code\line_manager\reverse_logic\rules.py`.
 *
 * Every rule is quantified over all anchors x. An [Atom] (K, o) means "element K is present at x + o".
 *
 * - [LineRule.Implies]: P1(x+o1) and ... and Pn(x+on) -> H(x+oh)
 * - [LineRule.Forbids]: P1(x+o1) and ... and Pn(x+on) -> not H(x+oh)
 * - [LineRule.ImpliesAny]: P1 ... Pn -> O1 or ... or Om, ordered: the first operand that is not blocked is used.
 *
 * The graph "premise kind -> produced kind" of the producing rules must be acyclic, whatever the offsets
 * ([RuleCycleError]). Forbids rules are negative clauses, symmetric, so they produce nothing.
 */
data class Atom(val kind: String, val offset: Rational = ZERO)

fun atom(kind: String, offset: Int = 0) = Atom(kind, offset.q)

fun atom(kind: String, offset: Rational) = Atom(kind, offset)

sealed class LineRule {
    abstract val premises: List<Atom>

    /** Premises, then the head (Implies, Forbids) or the operands (ImpliesAny). */
    abstract val atoms: List<Atom>

    data class Implies(override val premises: List<Atom>, val head: Atom) : LineRule() {
        override val atoms get() = premises + head
    }

    data class Forbids(override val premises: List<Atom>, val head: Atom) : LineRule() {
        override val atoms get() = premises + head
    }

    data class ImpliesAny(override val premises: List<Atom>, val options: List<Atom>) : LineRule() {
        override val atoms get() = premises + options
    }
}

class RuleCycleError(message: String) : IllegalArgumentException(message)

/** `implies("A", "B")`; `implies(listOf(atom("A"), atom("B")), atom("C", -3))`. */
fun implies(premises: List<Atom>, head: Atom): LineRule.Implies {
    require(premises.isNotEmpty()) { "a rule needs at least one atom here" }
    return LineRule.Implies(premises, head)
}

fun implies(premise: String, head: String) = implies(listOf(atom(premise)), atom(head))

fun implies(premise: String, head: Atom) = implies(listOf(atom(premise)), head)

/** `forbids("D", "C")`: wherever D is, C is not. */
fun forbids(premises: List<Atom>, head: Atom): LineRule.Forbids {
    require(premises.isNotEmpty()) { "a rule needs at least one atom here" }
    return LineRule.Forbids(premises, head)
}

fun forbids(premise: String, head: String) = forbids(listOf(atom(premise)), atom(head))

fun forbids(premise: String, head: Atom) = forbids(listOf(atom(premise)), head)

fun forbids(premise: Atom, head: Atom) = forbids(listOf(premise), head)

/** `impliesAny("A", listOf(atom("B"), atom("C")))`: A -> B or C, B preferred. */
fun impliesAny(premises: List<Atom>, options: List<Atom>): LineRule.ImpliesAny {
    require(premises.isNotEmpty() && options.isNotEmpty()) { "a rule needs at least one atom here" }
    return LineRule.ImpliesAny(premises, options)
}

fun impliesAny(premise: String, options: List<String>) = impliesAny(listOf(atom(premise)), options.map { atom(it) })

typealias Claims = Map<String, Region>

fun Claims.at(kind: String): Region = this[kind] ?: Region.EMPTY

/** Anchors x at which every premise of the rule holds in [state]. */
fun anchors(rule: LineRule, state: Claims): Region {
    var x = Region.FULL
    for (p in rule.premises) {
        x = x and state.at(p.kind).shift(-p.offset)
        if (x.isEmpty()) break
    }
    return x
}

class RuleSet(rules: List<LineRule> = emptyList()) {
    val rules: List<LineRule> = rules.toList()
    private val order: List<String>

    init {
        val edges = LinkedHashMap<String, MutableSet<String>>()
        for (r in this.rules) {
            val kinds = r.premises.map { it.kind }.toMutableList()
            val produced = when (r) {
                is LineRule.Implies -> listOf(r.head.kind)
                is LineRule.ImpliesAny -> r.options.map { it.kind }
                is LineRule.Forbids -> emptyList()
            }
            if (r is LineRule.Forbids) kinds.add(r.head.kind)
            for (k in kinds + produced) edges.getOrPut(k) { mutableSetOf() }
            for (p in kinds) edges.getValue(p).addAll(produced)
        }
        order = toposort(edges)
    }

    /** Deterministic topological order of all kinds (premises first), then [extraKinds] sorted. */
    fun order(extraKinds: Collection<String> = emptyList()): List<String> {
        val known = order.toSet()
        return order + extraKinds.filter { it !in known }.distinct().sorted()
    }

    fun kinds(): Set<String> = order.toSet()

    /** Anchors at which a rule fails in [state] (empty list: all rules hold). */
    fun violations(state: Claims): List<Pair<LineRule, Region>> {
        val out = mutableListOf<Pair<LineRule, Region>>()
        for (r in rules) {
            val x = anchors(r, state)
            val bad = when (r) {
                is LineRule.Implies -> x - state.at(r.head.kind).shift(-r.head.offset)
                is LineRule.Forbids -> x and state.at(r.head.kind).shift(-r.head.offset)
                is LineRule.ImpliesAny -> x - Region.unionOf(r.options.map { state.at(it.kind).shift(-it.offset) })
            }
            if (bad.isNotEmpty()) out.add(r to bad)
        }
        return out
    }

    private companion object {
        fun toposort(edges: Map<String, Set<String>>): List<String> {
            val indeg = edges.keys.associateWith { 0 }.toMutableMap()
            for (heads in edges.values) for (h in heads) indeg[h] = indeg.getValue(h) + 1
            val ready = indeg.filter { it.value == 0 }.keys.sorted().toMutableList()
            val order = mutableListOf<String>()
            while (ready.isNotEmpty()) {
                val k = ready.removeAt(0)
                order.add(k)
                for (h in edges.getValue(k).sorted()) {
                    indeg[h] = indeg.getValue(h) - 1
                    if (indeg[h] == 0) ready.add(h)
                }
                ready.sort()
            }
            if (order.size != edges.size) {
                val cyc = indeg.filter { it.value > 0 }.keys.sorted()
                throw RuleCycleError("rules form a cycle through kinds $cyc")
            }
            return order
        }
    }
}
