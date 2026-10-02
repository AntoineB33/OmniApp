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
 * [None] is the one member that is no pattern: an account may define more kinds than there are drawings to tell
 * apart, so a kind may be left to its outline and its label alone. It is only ever CHOSEN — a new kind is never
 * given it ([PeriodDrawing.patterns]).
 *
 * Stored by [name]; a name this build does not know decodes to the kind's default drawing.
 */
enum class PeriodDrawing(val label: String) {
    /** Nothing is drawn: the period keeps its outline and its label. */
    None("No drawing"),
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
    ;

    companion object {
        /** Every drawing that IS one — what a new kind is given the least-worn of. */
        val patterns: List<PeriodDrawing> = entries.filter { it != None }
    }
}

/**
 * One token of a combination rule's formulas (user rules, 2026-10-01: *"add the buttons 'or', 'and', '(' and ')' to
 * make a formula with period selector fields"*, then *"add the 'not' button in 'When'"* and *"add 'or' in 'then'"*). A
 * [Kinds] token is one period selector field — the kinds checked in its drop-down, ALL of which must be present (a field
 * of one kind is that kind; an empty field matches nothing); the others are the operators and brackets. [Not] is
 * offered in the "When" formula only.
 */
sealed interface PeriodFormulaToken {
    data class Kinds(val kinds: Set<String>) : PeriodFormulaToken
    data object And : PeriodFormulaToken
    data object Or : PeriodFormulaToken
    data object Not : PeriodFormulaToken
    data object Open : PeriodFormulaToken
    data object Close : PeriodFormulaToken
}

/**
 * The period edit window's **combination rule**: *"select a combination of periods, and select which periods appear when
 * this combination is present"* (user rule, 2026-09-30). Wherever the [condition] formula holds — `and` is the stretch
 * where both sides are present, `or` where either is, `not X` wherever no X is on the whole timeline — the [then]
 * formula is made true over that stretch. **Always derived, never stored** (user rule, 2026-10-01): an implication and
 * never a laid panel. [id] names the rule for the editor and the store.
 *
 * [then] reads (user rule, 2026-10-01): a field is present over the stretch, `and` makes both sides true; for `A or B`,
 * *"the period to the left of 'or' is placed automatically when the user manually adds the period in the calendar,
 * except when the 'or' condition is already verified on the timeline"* — so a [then] holding an `or` ([isPlacement])
 * fires only from the periods the user stated — drawn, or their Sleep schedule ([PeriodKindConfig.closeRegions]'s `manual`), and lays A only where neither
 * side is there already. What used to be a kind's "always present with it" set is the rule `when <kind> then
 * <companions>` ([PeriodKinds.companionRule]) — one mechanism, not two.
 */
data class PeriodCombination(
    val id: String,
    val condition: List<PeriodFormulaToken>,
    val then: List<PeriodFormulaToken>,
) {
    /** Every kind [then] names — the kinds the rule can bring. */
    val implies: Set<String> get() = PeriodFormula.kindsNamed(then)

    /** Every kind the rule names, on either side. */
    val named: Set<String> get() = PeriodFormula.kindsNamed(condition) + implies

    /** Whether [then] picks between kinds (holds an `or`): it fires from the user's own periods only. */
    val isPlacement: Boolean get() = PeriodFormulaToken.Or in then

    /** The same rule with [kind] taken out of every field on both sides — the kind was removed. */
    fun without(kind: String): PeriodCombination =
        PeriodCombination(id, PeriodFormula.without(condition, kind), PeriodFormula.without(then, kind))
}

/**
 * **The one reading and the one editing of a combination rule's formulas** ([PeriodFormulaToken]).
 *
 * The editor only ever APPENDS whole steps (an operator with the field after it, a `(` or a `not` in place of an empty
 * field, a `)`) or undoes the last one ([removeLast]), so a formula is always operands joined by operators with brackets
 * around runs of them; the one thing it can leave open is a `(` with no `)`, which [parse] closes at the end. `not` binds
 * tightest, then `and`, then `or`.
 */
object PeriodFormula {
    /**
     * The whole timeline, for `not`: *"not 'sleep' is verified whenever there is no 'sleep' period in the whole infinite
     * timeline"* (user rule, 2026-10-01). A quarter of the Long range each way, so a length taken across it never
     * overflows.
     */
    val TIMELINE: TaskTimeRange = TaskTimeRange(Long.MIN_VALUE / 4, Long.MAX_VALUE / 4)

    /** A parsed formula. */
    sealed interface Expr {
        data class Field(val kinds: Set<String>) : Expr
        data class And(val left: Expr, val right: Expr) : Expr
        data class Or(val left: Expr, val right: Expr) : Expr
        data class Not(val inner: Expr) : Expr
    }

    /** A formula of one field: the editor's starting point, and what every rule was before formulas. */
    fun of(kinds: Set<String>): List<PeriodFormulaToken> = listOf(PeriodFormulaToken.Kinds(kinds))

    /** Fields joined by `and` — the shape a "then" had before it could hold an `or`. */
    fun allOf(fields: List<Set<String>>): List<PeriodFormulaToken> =
        fields.flatMapIndexed { i, f ->
            if (i == 0) listOf(PeriodFormulaToken.Kinds(f)) else listOf(PeriodFormulaToken.And, PeriodFormulaToken.Kinds(f))
        }

    /** Every kind checked in any field of [tokens]. */
    fun kindsNamed(tokens: List<PeriodFormulaToken>): Set<String> =
        tokens.filterIsInstance<PeriodFormulaToken.Kinds>().flatMapTo(LinkedHashSet()) { it.kinds }

    /** [tokens] with [kind] unchecked in every field. */
    fun without(tokens: List<PeriodFormulaToken>, kind: String): List<PeriodFormulaToken> =
        tokens.map { if (it is PeriodFormulaToken.Kinds && kind in it.kinds) PeriodFormulaToken.Kinds(it.kinds - kind) else it }

    /** Whether [tokens] hold a `not` — a formula no kind "carries", since adding a period can make it false. */
    fun hasNot(tokens: List<PeriodFormulaToken>): Boolean = PeriodFormulaToken.Not in tokens

    /** [tokens] as an expression, or `null` when they do not form one (empty, a dangling operator, a stray `)`). */
    fun parse(tokens: List<PeriodFormulaToken>): Expr? {
        val parser = Parser(tokens)
        val expr = parser.or() ?: return null
        return if (parser.i == tokens.size) expr else null
    }

    /** Recursive descent: `or` over `and` over `not` over a field or a bracketed formula. */
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
                PeriodFormulaToken.Not -> { i++; primary()?.let(Expr::Not) }
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
            is Expr.Not -> !holds(expr.inner, present)
        }

    /**
     * The stretches where [expr] holds, given where each kind is ([at]; merged, sorted stretches). A `not` is the rest of
     * [TIMELINE].
     */
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
            is Expr.Not -> SchedulerDomain.subtractRegions(listOf(TIMELINE), regions(expr.inner, at))
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

    /** `(` and `not` take the place of a field still empty — the one spot an operand is about to start. */
    fun canOpen(tokens: List<PeriodFormulaToken>): Boolean =
        tokens.isEmpty() || tokens.last().let { it is PeriodFormulaToken.Kinds && it.kinds.isEmpty() }

    private fun startOperand(tokens: List<PeriodFormulaToken>, token: PeriodFormulaToken): List<PeriodFormulaToken> =
        if (!canOpen(tokens)) tokens
        else tokens.dropLast(if (tokens.isEmpty()) 0 else 1) + token + PeriodFormulaToken.Kinds(emptySet())

    fun open(tokens: List<PeriodFormulaToken>): List<PeriodFormulaToken> = startOperand(tokens, PeriodFormulaToken.Open)

    /** `not` before the operand about to start ([canOpen]). */
    fun negate(tokens: List<PeriodFormulaToken>): List<PeriodFormulaToken> = startOperand(tokens, PeriodFormulaToken.Not)

    /** `)` closes an open `(` after a finished operand. */
    fun canClose(tokens: List<PeriodFormulaToken>): Boolean = endsOperand(tokens) && openCount(tokens) > 0

    fun close(tokens: List<PeriodFormulaToken>): List<PeriodFormulaToken> =
        if (!canClose(tokens)) tokens else tokens + PeriodFormulaToken.Close

    /** Whether there is a step to undo: anything beyond the one starting field. */
    fun canRemoveLast(tokens: List<PeriodFormulaToken>): Boolean = tokens.size > 1

    /**
     * Undo the last step: a `)`; an operator with the field after it; a `(` or a `not` with the field after it (an empty
     * field again in its place).
     */
    fun removeLast(tokens: List<PeriodFormulaToken>): List<PeriodFormulaToken> {
        if (!canRemoveLast(tokens)) return tokens
        val last = tokens.last()
        val before = tokens[tokens.size - 2]
        return when {
            last == PeriodFormulaToken.Close -> tokens.dropLast(1)
            before == PeriodFormulaToken.And || before == PeriodFormulaToken.Or -> tokens.dropLast(2)
            before == PeriodFormulaToken.Open || before == PeriodFormulaToken.Not ->
                tokens.dropLast(2) + PeriodFormulaToken.Kinds(emptySet())
            else -> tokens.dropLast(1)
        }
    }

    /** How a formula reads in one line, for tests, logs and the scheduling signature. */
    fun describe(tokens: List<PeriodFormulaToken>): String =
        tokens.joinToString(" ") {
            when (it) {
                is PeriodFormulaToken.Kinds -> it.kinds.sorted().joinToString("+").ifEmpty { "?" }
                PeriodFormulaToken.And -> "and"
                PeriodFormulaToken.Or -> "or"
                PeriodFormulaToken.Not -> "not"
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
 * What a kind carries ([kindsOf]) is TRANSITIVE: every PLAIN rule whose formula a kind's period satisfies ON ITS OWN (a
 * `when A`, a `when A or B`) brings its kinds, and those may satisfy further rules. A cycle is harmless (A and B simply
 * always come together). A rule is plain when its "When" has no `not` (adding a period can make a `not` false, so no
 * kind "carries" it) and its "then" no `or` (which fires from the user's own periods only, never from a kind as such).
 *
 * Immutable and precomputed, so it may be read from the reducer's off-thread re-plans and the frame loop alike.
 */
class PeriodKindConfig(
    val styles: Map<String, PeriodKindStyle> = emptyMap(),
    /** The account's combination rules ([PeriodCombination]); [PeriodKinds.DEFAULT_COMBINATIONS] until it edits them. */
    val combinations: List<PeriodCombination> = PeriodKinds.DEFAULT_COMBINATIONS,
) {

    /** A rule that can bring anything: both formulas parse, and the "then" names a kind. */
    private class Compiled(val condition: PeriodFormula.Expr, val then: PeriodFormula.Expr, val implies: Set<String>, val rule: PeriodCombination) {
        val plain: Boolean = !PeriodFormula.hasNot(rule.condition) && !rule.isPlacement
    }

    private val compiled: List<Compiled> =
        combinations.mapNotNull { rule ->
            val implies = rule.implies
            if (implies.isEmpty()) return@mapNotNull null
            val condition = PeriodFormula.parse(rule.condition) ?: return@mapNotNull null
            val then = PeriodFormula.parse(rule.then) ?: return@mapNotNull null
            Compiled(condition, then, implies, rule)
        }

    /** The plain rules ([kindsOf]'s, and the closure's monotone part), as `condition → kinds`. */
    private val rules: List<Pair<PeriodFormula.Expr, Set<String>>> =
        compiled.filter { it.plain }.map { it.condition to it.implies }

    /** The rest, applied once each after the plain closure, in the account's order ([closeRegions]). */
    private val special: List<Compiled> = compiled.filterNot { it.plain }

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
     *
     * The rules that are not plain run AFTER the plain closure, once each in the account's order, each followed by the
     * plain closure again (a stratified reading, so the answer never depends on how often a rule was tried):
     * - a "When" with a `not` reads the timeline as the rules before it left it — a later rule's output does not
     *   retract it;
     * - a "then" with an `or` ([PeriodCombination.isPlacement]) fires only over the stretches where its "When" holds
     *   among [manual] — the periods the user stated (drawn on the calendar, *"when the user manually adds the period"*, or a sleep window of their Sleep schedule) — and
     *   there makes the "then" true: a field is laid, `and` lays both sides, `A or B` lays A only where neither is
     *   already on the timeline (*"except when the 'or' condition is already verified"*) — and B instead of A where
     *   [knownAbsent] says the timeline KNOWS there is no A. That is the OS evidence (user rule, 2026-10-01: *"if
     *   'then' derives somewhere in the past a ('no computer unlocked' or 'not on a computer') where there is no 'no
     *   computer unlocked', then 'not on a computer' is placed because the OS knows that one computer was unlocked"*):
     *   the past stretches a device of a layer's kind was seen unlocked ([SchedulerDomain.knownUnlockedRegions]),
     *   under the real layer kind's name.
     */
    fun closeRegions(
        present: Map<String, List<TaskTimeRange>>,
        manual: Map<String, List<TaskTimeRange>> = emptyMap(),
        knownAbsent: Map<String, List<TaskTimeRange>> = emptyMap(),
    ): Map<String, List<TaskTimeRange>> {
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
        fun closePlain() {
            repeat(rules.size + 1) {
                var grew = false
                for ((expr, implies) in rules) {
                    val where = PeriodFormula.regions(expr) { out[it].orEmpty() }
                    if (where.isEmpty()) continue
                    for (implied in implies) if (add(implied, where)) grew = true
                }
                if (!grew) return
            }
        }
        fun place(then: PeriodFormula.Expr, where: List<TaskTimeRange>) {
            if (where.isEmpty()) return
            when (then) {
                is PeriodFormula.Expr.Field -> for (k in then.kinds) add(k, where)
                is PeriodFormula.Expr.And -> {
                    place(then.left, where)
                    place(then.right, where)
                }
                is PeriodFormula.Expr.Or -> {
                    val verified = PeriodFormula.regions(then) { out[it].orEmpty() }
                    val rest = SchedulerDomain.subtractRegions(where, verified)
                    // The left is the default pick — unless the timeline knows it is not there (a field is not
                    // there wherever any kind of it is known absent).
                    val left = then.left
                    val absent =
                        if (left is PeriodFormula.Expr.Field) {
                            SchedulerDomain.mergeOccupied(left.kinds.flatMap { knownAbsent[it].orEmpty() })
                        } else {
                            emptyList()
                        }
                    if (absent.isEmpty()) {
                        place(left, rest)
                    } else {
                        place(left, SchedulerDomain.subtractRegions(rest, absent))
                        place(then.right, SchedulerDomain.intersectRegions(rest, absent))
                    }
                }
                // Never offered in a "then"; a hand-edited payload's is read as nothing to lay.
                is PeriodFormula.Expr.Not -> Unit
            }
        }
        for ((kind, spans) in present) if (spans.isNotEmpty()) add(kind, spans)
        closePlain()
        if (special.isEmpty()) return out
        // The stated periods WITH what each carries ([kindsOf]): a sleep window states a "no screen" period too, so a
        // "when no screen" placement fires from it.
        val drawn =
            HashMap<String, MutableList<TaskTimeRange>>().also { acc ->
                for ((kind, spans) in manual) for (k in kindsOf(kind)) acc.getOrPut(k) { ArrayList() } += spans
            }.mapValues { (_, spans) -> SchedulerDomain.mergeOccupied(spans) }
        for (rule in special) {
            val where =
                if (rule.rule.isPlacement) PeriodFormula.regions(rule.condition) { drawn[it].orEmpty() }
                else PeriodFormula.regions(rule.condition) { out[it].orEmpty() }
            place(rule.then, where)
            closePlain()
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
