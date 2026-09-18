package org.example.project.scheduler.domain

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
}

/**
 * `side-dev/README.md` § *Restrictive Period*, as the period edit window states it: **what a kind of period
 * carries with it** — the kinds that are ALWAYS present wherever a period of it is ([companions]), and the
 * drawing it wears ([drawing]).
 *
 * A companion is an implication of the kind, not a second panel: nothing lays a companion period, so there is
 * nothing to drift from its host, to edit apart from it or to sync. [PeriodKindConfig] folds the companions in
 * wherever the question is "which kinds are in force here".
 */
data class PeriodKindStyle(
    val companions: Set<String> = emptySet(),
    val drawing: PeriodDrawing,
)

/**
 * **The account's answer, per kind, to "which kinds come with it and how is it drawn"** — the overrides the
 * account holds ([org.example.project.scheduler.state.SchedulerState.periodKindStyles]) over the built-in
 * defaults ([PeriodKinds.defaultStyle]).
 *
 * The one reading of a companion set in the whole app: the scheduler's companion periods
 * ([SchedulerDomain.companionPeriods]), the mode-1 retraction, the layers a period hatches
 * ([assertedLayers]), the record bank's no-screen ranges and the calendar's drawings all ask through here, so
 * none of them can hold a second list.
 *
 * Companions are TRANSITIVE ([kindsOf]): a period of A accompanied by B, itself accompanied by C, has all three
 * in force — "always present when B is present" is true of C wherever B is, however B got there. A cycle is
 * harmless (A and B simply always come together).
 *
 * Immutable and precomputed, so it may be read from the reducer's off-thread re-plans and the frame loop alike.
 */
class PeriodKindConfig(val styles: Map<String, PeriodKindStyle> = emptyMap()) {

    private val closures: Map<String, Set<String>> =
        (PeriodKinds.BUILT_IN + styles.keys).distinct().associateWith(::walk)

    private fun walk(kind: String): Set<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(kind))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            queue.addAll(style(next).companions)
        }
        return seen
    }

    /** The style of [kind]: the account's override if it holds one, else the kind's default. */
    fun style(kind: String): PeriodKindStyle = styles[kind] ?: PeriodKinds.defaultStyle(kind)

    fun drawing(kind: String): PeriodDrawing = style(kind).drawing

    /** **Every kind in force wherever a period of [kind] is** — [kind] itself first, then its companions, transitively. */
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
     * asserts none unless the account made the layers its companions: the reverse implication (both layers ⇒ no
     * screen) is the layers' own definition and is taken by [SchedulerDomain.companionPeriods], not here.
     */
    fun assertedLayers(kind: String): Set<SchedulerDomain.ActivityLayer> {
        val kinds = kindsOf(kind)
        return SchedulerDomain.ActivityLayer.entries.filterTo(LinkedHashSet()) { PeriodKinds.layerKind(it) in kinds }
    }

    /**
     * The drawings a period BOX of [kind] paints: its own and its companions', minus the two layer kinds' —
     * those are painted by the layer band, which unions a period's assertion with the OS evidence, so drawing
     * them on the box as well would paint one statement twice.
     */
    fun boxDrawings(kind: String): List<PeriodDrawing> =
        kindsOf(kind).filterNot { it in PeriodKinds.LAYER_KINDS }.map(::drawing).distinct()

    override fun equals(other: Any?): Boolean = other is PeriodKindConfig && other.styles == styles

    override fun hashCode(): Int = styles.hashCode()

    override fun toString(): String = "PeriodKindConfig($styles)"

    companion object {
        /** Every kind at its default — an account that never opened a period edit window. */
        val DEFAULT: PeriodKindConfig = PeriodKindConfig()
    }
}
