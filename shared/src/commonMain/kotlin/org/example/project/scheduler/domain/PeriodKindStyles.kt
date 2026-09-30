package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TaskTimeRange

/**
 * PRD §8: **the drawing a period of one kind wears on the calendar** — one of a fixed set, chosen in the period
 * edit window.
 *
 * The set is closed on purpose: every member is a pattern of thin lines at 35 % alpha drawn BEHIND whatever
 * the stretch contains, and each one differs from every other by its GEOMETRY (orientation or curvature), never
 * by a shade. That is what keeps two, three or four periods in force over one stretch readable at once — they
 * cross rather than blend, which a colour or a density would not do. A period has no fill at any depth: a task
 * resilient to its kind works straight through it and must stay readable (ADR 0002).
 *
 * Stored by [name]; a name this build does not know decodes to the kind's default drawing.
 */
enum class PeriodDrawing(val label: String) {
    /** `|` — the inactivity default. */
    VerticalLines("Vertical lines"),
    /** `—` — the sleep default. */
    HorizontalLines("Horizontal lines"),
    /** `/` (bottom-left → top-right) — the "no computer unlocked" default. */
    RisingObliques("Oblique lines /"),
    /** `\` (top-left → bottom-right) — the "no phone unlocked" default. */
    FallingObliques("Oblique lines \\"),
    /** `(` — vertical half-circles opening to the right; the "no screen" default. */
    HalfCirclesLeft("Half-circles ("),
    /** `)` — the same half-circles facing the other way, set beside `(` so the two read as `( )` together. */
    HalfCirclesRight("Half-circles )"),
    /** Small `+` marks on a sparse grid. */
    Crosses("Crosses +"),
    /** Horizontal zig-zag lines — the "before bed" default. */
    Zigzags("Zig-zags"),
    /** `/` in dashes — the "not on a computer" default: the real layer's slope, the user's word. */
    DottedRisingObliques("Dotted oblique lines /"),
    /** `\` in dashes — the "not on a phone" default. */
    DottedFallingObliques("Dotted oblique lines \\"),
}

/**
 * One token of a combination rule's **"when" formula** (user rule, 2026-10-01: *"add the buttons 'or', 'and', '(' and
 * ')' to make a formula with period selector fields"*). A [Kinds] token is one period selector field — the kinds checked
 * in its drop-down, ALL of which must be present (a field of one kind is that kind; an empty field matches nothing);
 * the other four are the formula's operators and brackets.
 */
sealed interface PeriodFormulaToken {
    data class Kinds(val kinds: Set<String>) : PeriodFormulaToken
    data object And : PeriodFormulaToken
    data object Or : PeriodFormulaToken
    data object Open : PeriodFormulaToken
    data object Close : PeriodFormulaToken
}

/**
 * The period edit window's **combination rule**: *"select a combination of periods, and select which periods appear when
 * this combination is present"* (user rule, 2026-09-30). Wherever the [condition] formula holds — `and` is the stretch
 * where both sides are present, `or` where either is — a period of each kind in [then] is present too, over exactly that
 * stretch. An implication and never a laid panel. [id] names the rule for the editor and the store.
 *
 * [then] is its fields joined by "and" only (user rule, 2026-10-01): a "then A or B" would not say which period to put
 * there. What used to be a kind's "always present with it" set is the rule `when <kind> then <companions>`
 * ([PeriodKinds.companionRule]) — one mechanism, not two.
 */
data class PeriodCombination(
    val id: String,
    val condition: List<PeriodFormulaToken>,
    val then: List<Set<String>>,
) {
    /** Every kind present wherever [condition] holds: the union of [then]'s fields. */
    val implies: Set<String> get() = then.flatMapTo(LinkedHashSet()) { it }

    /** Every kind the rule names, on either side. */
    val named: Set<String>
        get() = condition.filterIsInstance<PeriodFormulaToken.Kinds>().flatMapTo(LinkedHashSet()) { it.kinds } + implies

    /** The same rule with [kind] taken out of every field on both sides — the kind was removed. */
    fun without(kind: String): PeriodCombination =
        PeriodCombination(
            id,
            condition.map { if (it is PeriodFormulaToken.Kinds && kind in it.kinds) PeriodFormulaToken.Kinds(it.kinds - kind) else it },
            then.map { it - kind },
        )
}

/**
 * **The one reading and the one editing of a combination rule's "when" formula** ([PeriodFormulaToken]).
 *
 * The editor only ever APPENDS whole steps (an operator with the field after it, a `(` in place of an empty field, a
 * `)`) or undoes the last one ([removeLast]), so a formula is always `field (op field)*` with brackets around runs of it;
 * the one thing it can leave open is a `(` with no `)`, which [parse] closes at the end. `and` binds tighter than `or`.
 */
object PeriodFormula {
    /** A parsed "when" formula. */
    sealed interface Expr {
        data class Field(val kinds: Set<String>) : Expr
        data class And(val left: Expr, val right: Expr) : Expr
        data class Or(val left: Expr, val right: Expr) : Expr
    }

    /** A formula of one field: the editor's starting point, and what every rule was before formulas. */
    fun of(kinds: Set<String>): List<PeriodFormulaToken> = listOf(PeriodFormulaToken.Kinds(kinds))

    /** [tokens] as an expression, or `null` when they do not form one (empty, a dangling operator, a stray `)`). */
    fun parse(tokens: List<PeriodFormulaToken>): Expr? {
        val parser = Parser(tokens)
        val expr = parser.or() ?: return null
        return if (parser.i == tokens.size) expr else null
    }

    /** Recursive descent: `or` over `and` over a field or a bracketed formula. */
    private class Parser(val tokens: List<PeriodFormulaToken>) {
        var i = 0

        fun or(): Expr? {
            var left = and() ?: return null
            while (tokens.getOrNull(i) == PeriodFormulaToken.Or) {
                i++
                left = Expr.Or(left, and() ?: return null)
            }
            return left
        }

        fun and(): Expr? {
            var left = primary() ?: return null
            while (tokens.getOrNull(i) == PeriodFormulaToken.And) {
                i++
                left = Expr.And(left, primary() ?: return null)
            }
            return left
        }

        fun primary(): Expr? =
            when (val t = tokens.getOrNull(i)) {
                is PeriodFormulaToken.Kinds -> { i++; Expr.Field(t.kinds) }
                PeriodFormulaToken.Open -> {
                    i++
                    val inner = or()
                    if (tokens.getOrNull(i) == PeriodFormulaToken.Close) i++ // an unclosed `(` closes at the end
                    inner
                }
                else -> null
            }
    }

    /** Whether [expr] holds where exactly the kinds in [present] are present. */
    fun holds(expr: Expr, present: Set<String>): Boolean =
        when (expr) {
            is Expr.Field -> expr.kinds.isNotEmpty() && present.containsAll(expr.kinds)
            is Expr.And -> holds(expr.left, present) && holds(expr.right, present)
            is Expr.Or -> holds(expr.left, present) || holds(expr.right, present)
        }

    /** The stretches where [expr] holds, given where each kind is ([at]; merged, sorted stretches). */
    fun regions(expr: Expr, at: (String) -> List<TaskTimeRange>): List<TaskTimeRange> =
        when (expr) {
            is Expr.Field -> {
                var overlap: List<TaskTimeRange>? = null
                for (k in expr.kinds) {
                    overlap = if (overlap == null) at(k) else SchedulerDomain.intersectRegions(overlap, at(k))
                    if (overlap.isEmpty()) break
                }
                overlap.orEmpty()
            }
            is Expr.And -> {
                val left = regions(expr.left, at)
                if (left.isEmpty()) left else SchedulerDomain.intersectRegions(left, regions(expr.right, at))
            }
            is Expr.Or -> SchedulerDomain.mergeOccupied(regions(expr.left, at) + regions(expr.right, at))
        }

    private fun openCount(tokens: List<PeriodFormulaToken>): Int =
        tokens.count { it == PeriodFormulaToken.Open } - tokens.count { it == PeriodFormulaToken.Close }

    private fun endsOperand(tokens: List<PeriodFormulaToken>): Boolean =
        tokens.lastOrNull().let { it is PeriodFormulaToken.Kinds || it == PeriodFormulaToken.Close }

    /** "and" / "or" may follow a field or a `)`. */
    fun canAppendOperator(tokens: List<PeriodFormulaToken>): Boolean = endsOperand(tokens)

    /** [op] and the empty field it asks for. */
    fun appendOperator(tokens: List<PeriodFormulaToken>, op: PeriodFormulaToken): List<PeriodFormulaToken> =
        if (!canAppendOperator(tokens)) tokens else tokens + op + PeriodFormulaToken.Kinds(emptySet())

    /** `(` takes the place of a field still empty — the one spot an operand is about to start. */
    fun canOpen(tokens: List<PeriodFormulaToken>): Boolean =
        tokens.isEmpty() || tokens.last().let { it is PeriodFormulaToken.Kinds && it.kinds.isEmpty() }

    fun open(tokens: List<PeriodFormulaToken>): List<PeriodFormulaToken> =
        if (!canOpen(tokens)) tokens
        else tokens.dropLast(if (tokens.isEmpty()) 0 else 1) + PeriodFormulaToken.Open + PeriodFormulaToken.Kinds(emptySet())

    /** `)` closes an open `(` after a finished operand. */
    fun canClose(tokens: List<PeriodFormulaToken>): Boolean = endsOperand(tokens) && openCount(tokens) > 0

    fun close(tokens: List<PeriodFormulaToken>): List<PeriodFormulaToken> =
        if (!canClose(tokens)) tokens else tokens + PeriodFormulaToken.Close

    /** Whether there is a step to undo: anything beyond the one starting field. */
    fun canRemoveLast(tokens: List<PeriodFormulaToken>): Boolean = tokens.size > 1

    /**
     * Undo the last step: a `)`; an operator with the field after it; a `(` with the field after it (an empty field
     * again in its place).
     */
    fun removeLast(tokens: List<PeriodFormulaToken>): List<PeriodFormulaToken> {
        if (!canRemoveLast(tokens)) return tokens
        val last = tokens.last()
        val before = tokens[tokens.size - 2]
        return when {
            last == PeriodFormulaToken.Close -> tokens.dropLast(1)
            before == PeriodFormulaToken.And || before == PeriodFormulaToken.Or -> tokens.dropLast(2)
            before == PeriodFormulaToken.Open -> tokens.dropLast(2) + PeriodFormulaToken.Kinds(emptySet())
            else -> tokens.dropLast(1)
        }
    }

    /** How a formula reads in one line, for tests and logs: `(a & b) | c` spelled with the words. */
    fun describe(tokens: List<PeriodFormulaToken>): String =
        tokens.joinToString(" ") {
            when (it) {
                is PeriodFormulaToken.Kinds -> it.kinds.sorted().joinToString("+").ifEmpty { "?" }
                PeriodFormulaToken.And -> "and"
                PeriodFormulaToken.Or -> "or"
                PeriodFormulaToken.Open -> "("
                PeriodFormulaToken.Close -> ")"
            }
        }
}

/**
 * `side-dev/README.md` § *Restrictive Period*, as the period edit window states it: **how a kind of period is drawn**.
 * What a kind carries WITH it is a combination rule ([PeriodCombination]); until 2026-10-01 it was a companion set here.
 */
data class PeriodKindStyle(
    val drawing: PeriodDrawing,
)

/**
 * **The account's answer, per kind, to "which kinds come with it and how is it drawn"** — the drawing overrides the
 * account holds ([org.example.project.scheduler.state.SchedulerState.periodKindStyles]) over the built-in
 * defaults ([PeriodKinds.defaultStyle]), and its combination rules.
 *
 * The one reading of the rules in the whole app: the scheduler's companion periods
 * ([SchedulerDomain.companionPeriods]), the mode-1 retraction, the layers a period hatches
 * ([assertedLayers]), the record bank's no-screen ranges and the calendar's drawings all ask through here, so
 * none of them can hold a second list.
 *
 * What a kind carries ([kindsOf]) is TRANSITIVE: every rule whose formula a kind's period satisfies ON ITS OWN (a
 * `when A`, a `when A or B`) brings its kinds, and those may satisfy further rules. A cycle is harmless (A and B simply
 * always come together).
 *
 * Immutable and precomputed, so it may be read from the reducer's off-thread re-plans and the frame loop alike.
 */
class PeriodKindConfig(
    val styles: Map<String, PeriodKindStyle> = emptyMap(),
    /** The account's combination rules ([PeriodCombination]); [PeriodKinds.DEFAULT_COMBINATIONS] until it edits them. */
    val combinations: List<PeriodCombination> = PeriodKinds.DEFAULT_COMBINATIONS,
) {

    /** The rules that can bring anything: a formula that parses, and at least one kind to bring. */
    private val rules: List<Pair<PeriodFormula.Expr, Set<String>>> =
        combinations.mapNotNull { rule ->
            val implies = rule.implies
            if (implies.isEmpty()) null else PeriodFormula.parse(rule.condition)?.let { it to implies }
        }

    private val closures: Map<String, Set<String>> =
        (PeriodKinds.BUILT_IN + PeriodKinds.BREAK_KINDS + styles.keys + combinations.flatMap { it.named })
            .distinct().associateWith(::walk)

    private fun walk(kind: String): Set<String> {
        val seen = linkedSetOf(kind)
        do {
            var grew = false
            for ((expr, implies) in rules) {
                if (seen.containsAll(implies) || !PeriodFormula.holds(expr, seen)) continue
                if (seen.addAll(implies)) grew = true
            }
        } while (grew)
        return seen
    }

    /** The style of [kind]: the account's override if it holds one, else the kind's default. */
    fun style(kind: String): PeriodKindStyle = styles[kind] ?: PeriodKinds.defaultStyle(kind)

    fun drawing(kind: String): PeriodDrawing = style(kind).drawing

    /**
     * **Every kind in force wherever a period of [kind] is** — [kind] itself first, then what the rules its period
     * satisfies alone bring, transitively.
     */
    fun kindsOf(kind: String): Set<String> = closures[kind] ?: walk(kind)

    /** [kindsOf] without [kind] itself: the companion periods a period of [kind] carries. */
    fun impliedKinds(kind: String): Set<String> = kindsOf(kind) - kind

    /**
     * Whether a period of [kind] is, or carries, a [PeriodKinds.NO_SCREEN] period — the one predicate
     * `docs/scheduler_requirements.md` § *$now line$ 3 modes* is written against ("covered by the period 'no
     * on-screen task'"). NOT [PeriodKinds.coversNoScreen], the bars' question, which answers true for
     * [PeriodKinds.INACTIVITY] as well.
     */
    fun isOrImpliesNoScreen(kind: String): Boolean = PeriodKinds.NO_SCREEN in kindsOf(kind)

    /**
     * PRD §8: **which calendar LAYERS a period of [kind] asserts** — the layer kinds
     * ([PeriodKinds.NO_COMPUTER_UNLOCKED] / [PeriodKinds.NO_PHONE_UNLOCKED]) among [kindsOf]. A "no screen" period
     * asserts none unless a rule of the account makes it bring the layers: the reverse implication (both layers ⇒ no
     * screen) is a two-kind rule, which no single kind satisfies, and is taken by [closeRegions], not here.
     */
    fun assertedLayers(kind: String): Set<SchedulerDomain.ActivityLayer> {
        val kinds = kindsOf(kind)
        return SchedulerDomain.ActivityLayer.entries.filterTo(LinkedHashSet()) { PeriodKinds.layerKind(it) in kinds }
    }

    /**
     * PRD §8: which calendar layers a period of [kind] asserts were FAKED ([PeriodKinds.fakeLayerKind] among [kindsOf]).
     */
    fun assertedFakeLayers(kind: String): Set<SchedulerDomain.ActivityLayer> {
        val kinds = kindsOf(kind)
        return SchedulerDomain.ActivityLayer.entries.filterTo(LinkedHashSet()) { PeriodKinds.fakeLayerKind(it) in kinds }
    }

    /**
     * **Every kind in force over the timeline, given where some kinds are** — the one closure of both settings the
     * period edit window holds: each kind brings what it carries alone ([kindsOf]), and wherever the formula of a
     * [PeriodCombination] holds, its implied kinds (with what THEY carry) are present over that stretch. Repeated
     * until nothing grows, so a combination may feed another (bounded by the number of rules plus one).
     *
     * [present] maps a kind to the stretches a period of it covers; the answer maps every kind in force to its merged
     * stretches, [present]'s own included. The scheduler's companion periods, the record bank's no-screen stretches
     * and the devices' observed no-screen time all read it ([SchedulerDomain.companionPeriods],
     * [SchedulerDomain.assertedNoScreenRanges], [SchedulerDomain.observedNoScreenRegions]).
     */
    fun closeRegions(present: Map<String, List<TaskTimeRange>>): Map<String, List<TaskTimeRange>> {
        val out = HashMap<String, List<TaskTimeRange>>()
        fun add(kind: String, spans: List<TaskTimeRange>): Boolean {
            var grew = false
            for (k in kindsOf(kind)) {
                val before = out[k].orEmpty()
                val merged = SchedulerDomain.mergeOccupied(before + spans)
                if (merged != before) {
                    out[k] = merged
                    grew = true
                }
            }
            return grew
        }
        for ((kind, spans) in present) if (spans.isNotEmpty()) add(kind, spans)
        repeat(rules.size + 1) {
            var grew = false
            for ((expr, implies) in rules) {
                val where = PeriodFormula.regions(expr) { out[it].orEmpty() }
                if (where.isEmpty()) continue
                for (implied in implies) if (add(implied, where)) grew = true
            }
            if (!grew) return out
        }
        return out
    }

    /**
     * The drawings a period BOX of [kind] paints: its own and its companions', minus the layer kinds' —
     * those are painted by the layer band, which unions a period's assertion with the OS evidence, so drawing
     * them on the box as well would paint one statement twice.
     */
    fun boxDrawings(kind: String): List<PeriodDrawing> =
        kindsOf(kind).filterNot { it in PeriodKinds.LAYER_KINDS }.map(::drawing).distinct()

    override fun equals(other: Any?): Boolean =
        other is PeriodKindConfig && other.styles == styles && other.combinations == combinations

    override fun hashCode(): Int = styles.hashCode() * 31 + combinations.hashCode()

    override fun toString(): String = "PeriodKindConfig($styles, $combinations)"

    companion object {
        /** Every kind at its default — an account that never opened a period edit window. */
        val DEFAULT: PeriodKindConfig = PeriodKindConfig()
    }
}
