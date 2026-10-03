package org.example.project.scheduler.domain

import org.example.project.scheduler.model.Category
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CategoryKind
import org.example.project.scheduler.model.CategoryRule
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PRD §5 **category rules**: *the tasks carrying this category, inside that task's sub-tree, are worth this
 * much of it* — and the app HOLDS that, rather than merely recording it.
 *
 * A [CategoryRule] is the relative-priority window's number said once and then kept: it names the same
 * quantity ([RelativePriorityDomain.relativePriority] of a set instead of a task) and it is re-established
 * the same way (one common factor over the cells on the chains, the rest of the sub-tree keeping its own
 * proportions). The difference is *when*: the window answers a question the moment it is asked, a rule
 * answers it after **every** edit, for ever.
 *
 * Three sentences are the whole of it, and everything below is one of them:
 *
 *  1. **A rule is relative to a task cell, chosen or at a distance** (user rule 2026-10-03): a carrier — a cell
 *     whose task carries a task id category, or a cell carrying a task cell category itself — is measured
 *     against that cell by the product of the shares along the chain between them. A task cell rule counts every
 *     top-most carrier under its cell together; a parent distance rule measures each carrier against the cell
 *     that many levels above it, along every path, the carriers of one ancestor sub-list together ([ruleGroups]).
 *  2. **The rule is re-established, never recorded.** [settle] runs after every intent
 *     ([org.example.project.scheduler.state.SchedulerReducer.reduce]) and scales the tree back onto the
 *     rules. Nothing about the adjustment is stored: the weights ARE the storage, so a rule and the tree can
 *     never say two different things, and there is no second mechanism to keep in step.
 *  3. **A contradiction is refused, out loud.** An edit whose result no scaling can satisfy is not applied
 *     at all — the state is returned untouched with the reason in
 *     [SchedulerState.categoryRuleError]. The check is both structural (the plain impossibilities, which can
 *     be named precisely) and empirical (the pass ran and did not land), because rules at nested scopes
 *     interact in ways no closed form answers.
 *
 * The one deliberate softness: a rule whose relative-to cell is gone, or that no carrier reaches any more, is
 * **dormant** rather than contradictory ([Status.ScopeGone] / [Status.NoCarrier]). Deleting the last
 * carrier of a category is an ordinary edit, not an attempt to break a promise, and refusing it would leave
 * the user unable to undo their way out. The window says so; the tree is left alone.
 */
object CategoryRules {

    /** How close an achieved share must be to its target to count as met. A share is a fraction of 1. */
    const val TOLERANCE: Double = 1e-6

    /**
     * How many times the whole set of rules is re-applied before the pass gives up and reports a
     * contradiction. One rule lands in one pass; rules at nested scopes pull on each other (an outer rule
     * scales cells that lie inside an inner rule's scope), so the pass is iterated to a fixed point. Small
     * on purpose — this runs after every intent, and a set of rules that has not settled by now is one the
     * user needs to be told about rather than one more iteration would fix.
     */
    private const val MAX_PASSES = 12

    // ----- Reading the tree ---------------------------------------------------------------------

    /** What [scopeKey] answers for the whole tree, which is the one scope that is not a cell. */
    private const val ROOT_KEY = "scope/root"

    /**
     * The sub-list a scope names: the root list for the whole tree (`null`), otherwise the list the scope
     * CELL's task owns.
     *
     * The scope the user points at is a cell, because a task can appear several times in the tree and only a
     * cell is a place; what that cell then names is its task's own sub-list, because a sub-list belongs to
     * the task id. Both halves are load-bearing, and [scopeKey] is where they meet.
     */
    fun scopeListId(state: SchedulerState, scope: CellId?): CellListId? =
        if (scope == null) state.rootListId
        else state.cells[scope]?.taskId?.let { state.tasks[it]?.childListId }

    /**
     * What makes two scopes **the same scope** — the invariant "at most one rule per sub-tree" is keyed on
     * this, and so is the grouping the structural contradictions are checked over.
     *
     * It is the sub-LIST, not the cell: two cells of one mirrored task show the same sub-tree, so a rule
     * about each of them would be two statements about one thing, which no scaling could tell apart. A
     * scope whose list cannot be resolved (a leaf, or a cell that is gone) stands only for itself.
     */
    fun scopeKey(state: SchedulerState, scope: CellId?): String =
        scopeListId(state, scope)?.value ?: scope?.value ?: ROOT_KEY

    /**
     * Whether [cellId] carries [category] — through its TASK for a [CategoryKind.TaskId] category, through ITSELF
     * for a [CategoryKind.TaskCell] one (user rule 2026-10-03). The one reading of "carries".
     */
    fun carries(state: SchedulerState, cellId: CellId, category: Category): Boolean {
        val cell = state.cells[cellId] ?: return false
        return when (category.kind) {
            CategoryKind.TaskId -> cell.taskId?.let { state.tasks[it] }?.categoryIds?.contains(category.id) == true
            CategoryKind.TaskCell -> category.id in cell.categoryIds
        }
    }

    /** Every populated cell carrying [category], in the tree's stable order (shallowest first). */
    fun carrierCells(state: SchedulerState, category: Category): List<CellId> {
        val cells =
            state.cells.keys.filter { SchedulerDomain.isPopulatedCell(state, it) && carries(state, it, category) }
        return SchedulerDomain.sortOccurrences(state, cells)
    }

    /** The most upward paths one carrier is followed along — a guard against a tree mirrored into itself many times. */
    private const val MAX_PATHS = 64

    /**
     * Every PATH from the root list down to [cellId]'s parent — the ancestor cells, outermost first. A sub-list belongs
     * to the task id, so a cell in a mirrored task's sub-list sits under EVERY occurrence of that task: one path per
     * occurrence, which is what lets a rule be relative to either ("the same task id once per path", user rule
     * 2026-10-03). [occurrences] is the populated cells of each task, measured once by the caller.
     */
    private fun ancestorPaths(
        state: SchedulerState,
        cellId: CellId,
        occurrences: Map<TaskId, List<CellId>>,
        seen: Set<CellId> = emptySet(),
    ): List<List<CellId>> {
        val listId = state.cells[cellId]?.parentListId ?: return emptyList()
        if (listId == state.rootListId) return listOf(emptyList())
        val owner = state.lists[listId]?.parentCellId ?: return emptyList()
        val parents = state.cells[owner]?.taskId?.let { occurrences[it] } ?: listOf(owner)
        val out = mutableListOf<List<CellId>>()
        for (parent in parents) {
            if (parent in seen || parent == cellId) continue
            for (path in ancestorPaths(state, parent, occurrences, seen + cellId)) {
                out += path + parent
                if (out.size >= MAX_PATHS) return out
            }
        }
        return out
    }

    /** The populated cells of every task — [ancestorPaths]' index, built once per question. */
    private fun occurrencesByTask(state: SchedulerState): Map<TaskId, List<CellId>> =
        state.cells.values
            .filter { it.taskId != null && SchedulerDomain.isPopulatedCell(state, it.id) }
            .groupBy({ it.taskId!! }, { it.id })

    /**
     * Where [cellId] reaches at [distance], once per path it is reached by: the ancestor cell that many levels above
     * it (`null` = the whole tree, when the distance is exactly that path's depth) and the chain of cells from the one
     * sitting in that ancestor's sub-list down to [cellId] — the shape [RelativePriorityDomain.chainsProduct] measures.
     * Empty where no path is that deep.
     */
    fun reach(
        state: SchedulerState,
        cellId: CellId,
        distance: Int,
        occurrences: Map<TaskId, List<CellId>> = occurrencesByTask(state),
    ): List<Pair<CellId?, List<CellId>>> {
        if (distance < 1) return emptyList()
        return ancestorPaths(state, cellId, occurrences).mapNotNull { ancestors ->
            val path = ancestors + cellId
            if (distance > path.size) null
            else (if (distance == path.size) null else path[path.size - distance - 1]) to path.takeLast(distance)
        }
    }

    /** One sub-list a rule governs: the ancestor whose sub-list it is, and the carriers' chains inside it. */
    data class RuleGroup(val ancestor: CellId?, val chains: List<List<CellId>>)

    /**
     * What [rule] of [category] governs right now. A **task cell rule** ([CategoryRule.distance] null) governs one
     * group: its cell's sub-list, with a chain per top-most carrier under it ([chainsUnder]). A **parent distance
     * rule** governs one group per ancestor SUB-LIST at its distance (two cells of one mirrored task name one sub-list,
     * [scopeKey]); every chain of it has the rule's length, so two in one group never nest — a carrier inside another
     * carrier is measured against its OWN ancestor at that distance, in its own group.
     */
    fun ruleGroups(state: SchedulerState, category: Category, rule: CategoryRule): List<RuleGroup> {
        val distance = rule.distance
        if (distance == null) {
            val chains = chainsUnder(state, category, rule.relativeToCellId)
            return if (chains.isEmpty()) emptyList() else listOf(RuleGroup(rule.relativeToCellId, chains))
        }
        val carriers = carrierCells(state, category)
        if (carriers.isEmpty()) return emptyList()
        val occurrences = occurrencesByTask(state)
        val groups = LinkedHashMap<String, Pair<CellId?, MutableList<List<CellId>>>>()
        for (cellId in carriers) {
            for ((ancestor, chain) in reach(state, cellId, distance, occurrences)) {
                groups.getOrPut(scopeKey(state, ancestor)) { ancestor to ArrayList() }.second += chain
            }
        }
        return groups.values.map { (ancestor, chains) -> RuleGroup(ancestor, chains.distinct()) }
    }

    /**
     * A task cell rule's chains: one per **top-most** carrier under [scope] (`null` = the whole tree), from the cell
     * sitting directly in the scope's own list down to the carrier — the shape
     * [RelativePriorityDomain.occurrenceChains] produces. The walk stops at a carrier (its whole sub-tree is already
     * counted by its own chain) and descends into a task's sub-list only from the cell that list names as its parent,
     * so a mirrored sub-list is walked once and a mirror cell that carries the category is still a chain of its own.
     */
    fun chainsUnder(state: SchedulerState, category: Category, scope: CellId?): List<List<CellId>> {
        if (!scopeExists(state, scope)) return emptyList()
        val rootList = scopeListId(state, scope) ?: return emptyList()
        val out = mutableListOf<List<CellId>>()
        val guard = HashSet<CellListId>()

        fun walk(listId: CellListId, prefix: List<CellId>) {
            if (!guard.add(listId)) return
            val list = state.lists[listId] ?: return
            for (cellId in list.cellIds) {
                if (!SchedulerDomain.isPopulatedCell(state, cellId)) continue
                val task = state.cells[cellId]?.taskId?.let { state.tasks[it] } ?: continue
                val chain = prefix + cellId
                if (carries(state, cellId, category)) {
                    out += chain
                    continue
                }
                val childList = task.childListId ?: continue
                if (state.lists[childList]?.parentCellId != cellId) continue
                walk(childList, chain)
            }
        }

        walk(rootList, emptyList())
        return out
    }

    /**
     * What makes two rules **the same rule** — at most one per key, which `SetCategoryRule` keeps by replacing: a task
     * cell rule by the SUB-LIST its cell names ([scopeKey]), a parent distance rule by its distance.
     */
    fun ruleKey(state: SchedulerState, relativeToCellId: CellId?, distance: Int?): String =
        if (distance == null) "cell:" + scopeKey(state, relativeToCellId) else "distance:$distance"

    /** How many tasks (a task id category) or task cells (a task cell category) carry [categoryId]. */
    fun carrierCount(state: SchedulerState, categoryId: CategoryId): Int {
        val category = state.categoryById(categoryId) ?: return 0
        return when (category.kind) {
            CategoryKind.TaskId -> tasksWith(state, categoryId).size
            CategoryKind.TaskCell -> carrierCells(state, category).size
        }
    }

    /** Every task carrying [categoryId] by its id, in the account's task order. */
    fun tasksWith(state: SchedulerState, categoryId: CategoryId): List<TaskId> =
        state.tasks.values.filter { it.title.isNotBlank() && categoryId in it.categoryIds }.map { it.id }

    /**
     * The **task cell picker** of the "add a rule" form (its task cell side): the whole tree first, then every task
     * cell some carrier of [categoryIds] sits under — along every path it is reached by, so one task id appears once
     * per path — each by its own PATH, and nothing else: a rule relative to a cell no carrier sits under would govern
     * nothing (the anomaly of 2026-10-03). Narrowed on the path by [input]; the whole tree answers to "root".
     */
    fun taskCellEntries(state: SchedulerState, categoryIds: Collection<CategoryId>, input: String): List<ScopeEntry> {
        val typed = input.trim()
        val seen = LinkedHashMap<CellId?, ScopeEntry>()
        val occurrences = occurrencesByTask(state)
        for (category in categoryIds.mapNotNull(state::categoryById)) {
            for (cellId in carrierCells(state, category)) {
                for (path in ancestorPaths(state, cellId, occurrences)) {
                    if (null !in seen) seen[null] = scopeEntry(state, null)
                    for (ancestor in path) if (ancestor !in seen) seen[ancestor] = scopeEntry(state, ancestor)
                }
            }
        }
        val rows = seen.values.sortedWith(compareBy({ it.cellId != null }, { it.label.lowercase() }))
        return rows.filter { typed.isEmpty() || it.label.contains(typed, ignoreCase = true) }
    }

    /** Whether some carrier of [categoryIds] sits [distance] levels under a task cell — a parent distance rule's check. */
    fun distanceReached(state: SchedulerState, categoryIds: Collection<CategoryId>, distance: Int): Boolean {
        val occurrences = occurrencesByTask(state)
        return categoryIds.mapNotNull(state::categoryById).any { category ->
            carrierCells(state, category).any { reach(state, it, distance, occurrences).isNotEmpty() }
        }
    }

    // ----- What a rule is doing (the edit window's readout) --------------------------------------

    /** Why a rule is or is not currently governing anything. */
    enum class Status {
        /** It is being held: the carriers are worth exactly what it says of their ancestor at its distance. */
        Held,

        /** The relative-to CELL is gone (deleted, or never in this tree), so there is no sub-tree to divide. */
        ScopeGone,

        /** No carrier reaches an ancestor at its distance (under the relative-to cell) — nothing to give it to. */
        NoCarrier,
    }

    /** One rule as the editors show it: what it asks, whether it is live, and what each sub-list it governs gets. */
    data class RuleRow(
        val rule: CategoryRule,
        /** What the rule is relative to, as [ruleLabel] says it. */
        val label: String,
        val status: Status,
        /** Each governed sub-list's ancestor path and the share the category holds of it now. */
        val achieved: List<Pair<String, Double>>,
    )

    /** How a rule reads: the task cell it is relative to by its path, or the parent distance. */
    fun ruleLabel(state: SchedulerState, relativeToCellId: CellId?, distance: Int?): String =
        if (distance == null) "of “${scopeLabel(state, relativeToCellId)}”"
        else "of the parent $distance level${if (distance == 1) "" else "s"} up"

    /** The rules of [categoryId] as rows, in the order they were added. */
    fun ruleRows(state: SchedulerState, categoryId: CategoryId): List<RuleRow> {
        val category = state.categoryById(categoryId) ?: return emptyList()
        return category.rules.map { rule ->
            val groups = ruleGroups(state, category, rule)
            val status = when {
                rule.distance == null && !scopeExists(state, rule.relativeToCellId) -> Status.ScopeGone
                groups.isEmpty() -> Status.NoCarrier
                else -> Status.Held
            }
            RuleRow(
                rule = rule,
                label = ruleLabel(state, rule.relativeToCellId, rule.distance),
                status = status,
                achieved = groups.map { scopeLabel(state, it.ancestor) to RelativePriorityDomain.chainsProduct(state, it.chains) },
            )
        }
    }

    /**
     * One row of the rules of SEVERAL categories read together (user rule 2026-10-02: one field for all the added
     * ones): a (relative-to, distance) at least one of them has a rule about, the [share] they ALL give it — null when
     * one has no rule there or they differ — and how many of them have one ([holders]).
     */
    data class SharedRuleRow(
        val relativeToCellId: CellId?,
        val distance: Int?,
        val label: String,
        val share: Double?,
        val holders: Int,
    )

    /**
     * The rules of [categoryIds] as one list, a row per rule key ([ruleKey]), in the order they first appear. A share
     * typed on a row is every category's rule there (`SetCategoryRule` each); its bin takes it off every one that has it.
     */
    fun sharedRuleRows(state: SchedulerState, categoryIds: List<CategoryId>): List<SharedRuleRow> {
        val categories = categoryIds.distinct().mapNotNull(state::categoryById)
        val byKey = LinkedHashMap<String, MutableList<CategoryRule>>()
        for (category in categories) {
            for (rule in category.rules) byKey.getOrPut(ruleKey(state, rule.relativeToCellId, rule.distance)) { ArrayList() } += rule
        }
        return byKey.values.map { rules ->
            val first = rules.first()
            val shares = rules.map { it.share }.distinct()
            SharedRuleRow(
                relativeToCellId = first.relativeToCellId,
                distance = first.distance,
                label = ruleLabel(state, first.relativeToCellId, first.distance),
                share = shares.singleOrNull()?.takeIf { rules.size == categories.size },
                holders = rules.size,
            )
        }
    }

    // ----- The account's own list (the categories window) ---------------------------------------

    /** One row of the categories window: a category, what carries it, and what its rules are doing. */
    data class OverviewRow(
        val category: Category,
        /** How many tasks (or task cells, for a task cell category) carry it — [carrierCount]. */
        val carriers: Int,
        /** Its rules as [ruleRows] draws them, so the list and the category's own window cannot disagree. */
        val rules: List<RuleRow>,
    ) {
        /** How many of those rules are asleep — the task cell is gone, or no carrier is reached. */
        val dormant: Int get() = rules.count { it.status != Status.Held }
    }

    /**
     * PRD §5/§7 **the categories window**: every category the account holds, each with the two figures that say
     * whether it is doing anything — how many carry it, and what its rules are up to. In **title order**: the
     * categories are stored in the order they were minted, which the user cannot predict. A walk of the tree per
     * rule ([ruleRows]), so this is asked once per change to the tree and never on a tick (ADR 0009).
     */
    fun overview(state: SchedulerState): List<OverviewRow> =
        state.categories
            .map { category ->
                OverviewRow(
                    category = category,
                    carriers = carrierCount(state, category.id),
                    rules = ruleRows(state, category.id),
                )
            }
            .sortedWith(compareBy({ it.category.title.lowercase() }, { it.category.id.value }))

    /**
     * PRD §4: the blank title is what deletes, so a populated cell is a live one; the root is always there.
     *
     * A cell, not a task: a rule written about one occurrence sleeps when THAT occurrence goes, even where
     * the task still appears elsewhere — the user pointed at a place, and the place is gone.
     */
    private fun scopeExists(state: SchedulerState, scope: CellId?): Boolean =
        scope == null || SchedulerDomain.isPopulatedCell(state, scope)

    /**
     * How a scope is named: `root` for the whole tree, else the scope cell's own PATH — the titles from
     * the root down to it. A bare title would not name a scope at all here, which is the whole reason the
     * window asks for a cell: two occurrences of one task, and two tasks sharing a title, both read the same.
     */
    fun scopeLabel(state: SchedulerState, scope: CellId?): String {
        if (scope == null) return SchedulerDomain.ROOT_LABEL
        val path = RelativePriorityDomain.ancestorCells(state, scope) + scope
        return path.joinToString(" / ") { cellId ->
            SchedulerDomain.taskTitleLabel(state.cells[cellId]?.taskId?.let { state.tasks[it]?.title })
        }
    }

    // ----- Holding the rules --------------------------------------------------------------------

    /** What [enforce] answers: the tree scaled back onto the rules, or the reason it could not be. */
    sealed interface Outcome {
        data class Applied(val state: SchedulerState) : Outcome

        data class Contradiction(val message: String) : Outcome
    }

    /** One rule that is actually governing something, with its chains measured once. */
    private data class Claim(
        val categoryId: CategoryId,
        val title: String,
        val scope: CellId?,
        val scopeLabel: String,
        val target: Double,
        val chains: List<List<CellId>>,
    )

    /**
     * The one place a rule reaches the tree: re-establish every live rule on [state], or say why it cannot
     * be done.
     *
     * Returns [state] itself — the same instance — when every rule is already met, which is the case after
     * an edit that touched nothing a rule cares about and is what keeps this off the save debounce and off
     * the wire (and off the per-tick budget, ADR 0009).
     */
    fun enforce(state: SchedulerState): Outcome {
        val claims = claimsOf(state)
        if (claims.isEmpty()) return Outcome.Applied(state)
        structuralContradiction(state, claims)?.let { return Outcome.Contradiction(it) }
        if (claims.all { abs(RelativePriorityDomain.chainsProduct(state, it.chains) - it.target) <= TOLERANCE }) {
            return Outcome.Applied(state)
        }
        // Deepest scope first: an outer rule scales cells that lie inside an inner scope, so the outer one
        // must have the later word — and the pass is repeated anyway, because they pull on each other.
        val ordered = claims.sortedByDescending { scopeDepth(state, it.scope) }
        var working = state
        repeat(MAX_PASSES) {
            for (claim in ordered) {
                working = RelativePriorityDomain.setChainsShare(working, claim.chains, claim.target)
            }
            val worst = ordered.maxOf {
                abs(RelativePriorityDomain.chainsProduct(working, it.chains) - it.target)
            }
            if (worst <= TOLERANCE) return Outcome.Applied(working)
        }
        val missed = ordered.filter {
            abs(RelativePriorityDomain.chainsProduct(working, it.chains) - it.target) > TOLERANCE
        }
        return Outcome.Contradiction(unsatisfiableMessage(missed))
    }

    /**
     * The rule invariant, applied after every intent: [after] with its rules re-established, or [before]
     * untouched and carrying the reason when [after] would break one.
     *
     * Two guards make refusing safe rather than a way to wedge the app:
     *  - it never refuses what was **already** broken. A state that arrives contradictory — merged from a
     *    peer, decoded from an older payload, or reached by an edit an earlier build allowed — must still be
     *    editable, or the user could not even undo their way out of it. So a contradiction is only reported
     *    when [before] was satisfiable;
     *  - a state with no rule at all returns instantly, which is every account that has never used one.
     */
    fun settle(before: SchedulerState, after: SchedulerState): SchedulerState {
        if (after === before) return after
        if (after.categories.none { it.rules.isNotEmpty() }) return after
        // Nothing a rule reads has moved — a selection, a window, a panel. Cheaper than measuring, and it is
        // what keeps the pass off every intent that is not about the tree at all (ADR 0009).
        if (
            after.cells === before.cells &&
            after.lists === before.lists &&
            after.tasks === before.tasks &&
            after.categories === before.categories
        ) {
            return after
        }
        return when (val outcome = enforce(after)) {
            is Outcome.Applied -> outcome.state
            is Outcome.Contradiction ->
                if (enforce(before) is Outcome.Contradiction) after
                else before.copy(categoryRuleError = outcome.message)
        }
    }

    /** The rules that currently govern something: one claim per sub-list a rule's carriers reach ([ruleGroups]). */
    private fun claimsOf(state: SchedulerState): List<Claim> {
        val out = mutableListOf<Claim>()
        for (category in state.categories) {
            for (rule in category.rules) {
                for (group in ruleGroups(state, category, rule)) {
                    if (scopeListId(state, group.ancestor) == null) continue
                    out += Claim(
                        categoryId = category.id,
                        title = category.title,
                        scope = group.ancestor,
                        scopeLabel = scopeLabel(state, group.ancestor),
                        target = rule.share.coerceIn(0.0, 1.0),
                        chains = group.chains,
                    )
                }
            }
        }
        return out
    }

    /**
     * User rule 2026-10-03: a cell a standing rule holds whose weight a **priority weights table pins** — the two say
     * different things about one share, and the rule, re-established after every edit, moves the pinned value anyway.
     * What the task tree window's configuration section warns about, one line per (category, cell).
     */
    data class PinnedRuleCell(val categoryTitle: String, val cellLabel: String, val ancestorLabel: String)

    /** Every [PinnedRuleCell] of the account's rules — empty at once where nothing is pinned or no rule exists. */
    fun pinnedRuleCells(state: SchedulerState): List<PinnedRuleCell> {
        if (state.priorityWeightPins.isEmpty() || state.categories.none { it.rules.isNotEmpty() }) return emptyList()
        val out = LinkedHashSet<PinnedRuleCell>()
        for (claim in claimsOf(state)) {
            for (cellId in claim.chains.flatten().distinct()) {
                val listId = state.cells[cellId]?.parentListId ?: continue
                if (state.priorityWeightPins[listId].orEmpty().none { it.cellId == cellId }) continue
                out += PinnedRuleCell(claim.title, scopeLabel(state, cellId), claim.scopeLabel)
            }
        }
        return out.toList()
    }

    /** How deep a scope sits, so the deepest rule is applied first. The whole tree is 0. */
    private fun scopeDepth(state: SchedulerState, scope: CellId?): Int {
        if (scope == null) return 0
        return RelativePriorityDomain.ancestorCells(state, scope).size + 1
    }

    // ----- Contradictions -----------------------------------------------------------------------

    /**
     * The impossibilities that can be named exactly, checked before anything is scaled so the user is told
     * *which* two rules disagree rather than "it did not converge".
     *
     * All four are about the rules sharing ONE scope, because that is where the arithmetic is closed: the
     * shares of one sub-tree sum to 1. A share the weight COLUMNS put out of reach is deliberately not one
     * of them: it bounds the factor, not the tree, and [RelativePriorityDomain.setChainsShare] answers it
     * by adding instead of multiplying.
     */
    private fun structuralContradiction(state: SchedulerState, claims: List<Claim>): String? {
        // Grouped by the SUB-LIST each scope names, not by the cell: two cells of one mirrored task are
        // one scope, so rules written about each of them are rules sharing a scope and must be checked as
        // such.
        for ((_, group) in claims.groupBy { scopeKey(state, it.scope) }) {
            val scopeName = group.first().scopeLabel
            // 1. Two categories covering the same task, or one covering a sub-tree the other sits inside.
            //    Their two shares are then claims about overlapping mass, and no scaling can honour both.
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    if (overlap(group[i].chains, group[j].chains)) {
                        return "“${group[i].title}” and “${group[j].title}” both cover " +
                            "the same task under “$scopeName”, so they cannot each be given a " +
                            "share of it."
                    }
                }
            }
            val asked = group.sumOf { it.target }
            // 2. More than the whole sub-tree.
            if (asked > 1.0 + TOLERANCE) {
                return "The rules under “$scopeName” ask for ${percent(asked)} of it " +
                    "altogether, which is more than there is."
            }
            val covered = group.sumOf { RelativePriorityDomain.chainsProduct(state, it.chains) }
            val hasRest = covered < 1.0 - TOLERANCE
            // 3. Everything claimed, but something else is under the scope that would be left with nothing.
            if (hasRest && asked >= 1.0 - TOLERANCE) {
                return "The rules under “$scopeName” ask for ${percent(asked)} of it, which " +
                    "would leave nothing for the tasks there that carry none of those categories."
            }
            // 4. Nothing else under the scope, yet the rules ask for less than all of it.
            if (!hasRest && asked < 1.0 - TOLERANCE) {
                return "Every task under “$scopeName” carries one of these categories, so their " +
                    "rules cannot add up to ${percent(asked)} — they have to account for all of it."
            }
        }
        return null
    }

    /** Whether two sets of chains meet: an identical chain, or one running through the other's carrier. */
    private fun overlap(a: List<List<CellId>>, b: List<List<CellId>>): Boolean =
        a.any { left -> b.any { right -> isPrefix(left, right) || isPrefix(right, left) } }

    private fun isPrefix(shorter: List<CellId>, longer: List<CellId>): Boolean =
        shorter.size <= longer.size && shorter.indices.all { shorter[it] == longer[it] }

    /** The message for rules the pass could not land — the case no structural check can name in advance. */
    private fun unsatisfiableMessage(missed: List<Claim>): String {
        val named = missed.joinToString(", ") {
            "“${it.title}” at ${percent(it.target)} of “${it.scopeLabel}”"
        }
        return "That change cannot be made without breaking a category rule ($named). The priorities were " +
            "left as they were."
    }

    /** A fraction as a whole-ish percentage, the way the tree prints one. */
    private fun percent(value: Double): String {
        val pct = value * 100.0
        val rounded = (pct * 10.0).roundToInt() / 10.0
        return if (rounded == rounded.toInt().toDouble()) "${rounded.toInt()} %" else "$rounded %"
    }

    // ----- The naming field ---------------------------------------------------------------------

    /**
     * The **identity rows** of the "add a category" field — the account's categories whose title matches what
     * is typed, best match first, so a name already taken attaches THAT category instead of minting a second
     * one under the same spelling. The task's own categories are left out: they are already on it, and the
     * rows above the field are how they are removed. [kind] narrows them to the categories a field can attach
     * (a task's field the task id ones, an occurrence's the task cell ones); null is every category.
     */
    fun menuEntries(
        state: SchedulerState,
        input: String,
        exclude: Collection<CategoryId>,
        kind: CategoryKind? = null,
    ): List<Category> {
        val taken = exclude.toSet()
        val typed = input.trim()
        return state.categories
            .filter { it.id !in taken && (kind == null || it.kind == kind) }
            .filter { typed.isEmpty() || it.title.contains(typed, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Category> { SchedulerDomain.titleSimilarity(it.title, typed) }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id.value },
            )
    }

    /** The **title suggestions** of that same field: the category titles the typed text appears in. */
    fun titleSuggestions(state: SchedulerState, input: String, kind: CategoryKind? = null): List<String> =
        menuEntries(state, input, emptyList(), kind)
            .map { it.title }
            .filter { it.isNotBlank() && !it.equals(input.trim(), ignoreCase = true) }
            .distinct()

    /**
     * One row of the task cell picker: the cell (`null` = the whole tree), its [label] (its own path), the task it
     * holds and the [parentPath] above it — what the row shows as the Search window's task row shows a task and its
     * path, the same task id once per path it is reached by.
     */
    data class ScopeEntry(
        val cellId: CellId?,
        val label: String,
        val taskId: TaskId? = null,
        val parentPath: List<String> = emptyList(),
    )

    /** The picker row of [cellId]: its task, and the titles from the root down to its parent. */
    private fun scopeEntry(state: SchedulerState, cellId: CellId?): ScopeEntry {
        if (cellId == null) return ScopeEntry(null, scopeLabel(state, null))
        val above = RelativePriorityDomain.ancestorCells(state, cellId).map { ancestor ->
            SchedulerDomain.taskTitleLabel(state.cells[ancestor]?.taskId?.let { state.tasks[it]?.title })
        }
        return ScopeEntry(cellId, scopeLabel(state, cellId), state.cells[cellId]?.taskId, listOf(SchedulerDomain.ROOT_LABEL) + above)
    }
}
