package org.example.project.scheduler.domain

import org.example.project.scheduler.model.Category
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CategoryRule
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
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
 *  1. **The measure is the top-most carriers.** A category's share of a scope is the sum, over the cells
 *     under that scope that carry it and are not themselves under another such cell, of the product of the
 *     shares along the chain that reaches them ([chainsFor]). A carrier's WHOLE sub-tree is its own — a
 *     categorized task nested inside another categorized task is not counted twice, which is what makes the
 *     figure a share of the scope rather than an arbitrary sum that can exceed it.
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
 * The one deliberate softness: a rule whose scope task is gone, or that no task under the scope carries any
 * more, is **dormant** rather than contradictory ([Status.ScopeGone] / [Status.NoCarrier]). Deleting the last
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

    /** The sub-list a scope task names: the root list for `MAIN`, the task's own children otherwise. */
    private fun scopeListId(state: SchedulerState, scope: TaskId): CellListId? =
        if (scope == WellKnownIds.MAIN_TASK) state.rootListId else state.tasks[scope]?.childListId

    /**
     * The chains that carry [categoryId] under [scope]: one per **top-most** carrying cell, running from the
     * cell sitting directly in the scope's own list down to that carrier — the very shape
     * [RelativePriorityDomain.occurrenceChains] produces, so the same solve reads them.
     *
     * The walk stops at a carrier (its whole sub-tree is already counted by its own chain) and descends into
     * a task's sub-list only from the cell that list names as its parent. That second rule is what keeps the
     * downward walk the exact inverse of the upward climb the priority machinery does: a mirrored sub-list
     * belongs to the task, is reached once, and a mirror cell that carries the category is still a chain of
     * its own. Without it a mirrored branch would be measured once per occurrence — the exponential walk
     * CLAUDE.md forbids, arriving as a wrong number first.
     */
    fun chainsFor(state: SchedulerState, categoryId: CategoryId, scope: TaskId): List<List<CellId>> {
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
                if (categoryId in task.categoryIds) {
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

    /** What [categoryId] is worth inside [scope]'s sub-tree right now, as a fraction in `[0, 1]`. */
    fun shareOf(state: SchedulerState, categoryId: CategoryId, scope: TaskId): Double =
        RelativePriorityDomain.chainsProduct(state, chainsFor(state, categoryId, scope))

    /** Every task carrying [categoryId], in the account's task order. */
    fun tasksWith(state: SchedulerState, categoryId: CategoryId): List<TaskId> =
        state.tasks.values.filter { it.title.isNotBlank() && categoryId in it.categoryIds }.map { it.id }

    // ----- What a rule is doing (the edit window's readout) --------------------------------------

    /** Why a rule is or is not currently governing anything. */
    enum class Status {
        /** It is being held: the carriers under the scope are worth exactly what it says. */
        Held,

        /** The scope task is gone (deleted, or never in this tree), so there is no sub-tree to divide. */
        ScopeGone,

        /** The scope is there, but no task under it carries the category — nothing to give the share to. */
        NoCarrier,
    }

    /** One rule as the category edit window shows it: what it asks, whether it is live, and what it gets. */
    data class RuleRow(
        val rule: CategoryRule,
        /** The scope's title, or `root` for the whole tree — the relative-priority window's own naming. */
        val scopeTitle: String,
        val status: Status,
        /** The share the category actually holds of the scope now; `null` when the scope is gone. */
        val achieved: Double?,
    )

    /** The rules of [categoryId] as rows, in the order they were added. */
    fun ruleRows(state: SchedulerState, categoryId: CategoryId): List<RuleRow> {
        val category = state.categoryById(categoryId) ?: return emptyList()
        return category.rules.map { rule ->
            val chains = chainsFor(state, categoryId, rule.scopeTaskId)
            val status = when {
                !scopeExists(state, rule.scopeTaskId) || scopeListId(state, rule.scopeTaskId) == null ->
                    Status.ScopeGone
                chains.isEmpty() -> Status.NoCarrier
                else -> Status.Held
            }
            RuleRow(
                rule = rule,
                scopeTitle = scopeTitle(state, rule.scopeTaskId),
                status = status,
                achieved =
                    if (status == Status.ScopeGone) null
                    else RelativePriorityDomain.chainsProduct(state, chains),
            )
        }
    }

    /** PRD §4: the blank title is what deletes, so a titled task is a live one; the root is always there. */
    private fun scopeExists(state: SchedulerState, scope: TaskId): Boolean =
        scope == WellKnownIds.MAIN_TASK || state.tasks[scope]?.title?.isNotBlank() == true

    /** How a scope is named, the same two answers the relative-priority drop-down gives. */
    fun scopeTitle(state: SchedulerState, scope: TaskId): String =
        if (scope == WellKnownIds.MAIN_TASK) TaskRelationsDomain.ROOT_LABEL
        else state.tasks[scope]?.title.orEmpty().ifBlank { TaskRelationsDomain.UNTITLED_LABEL }

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
        val scope: TaskId,
        val scopeTitle: String,
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

    /** The rules that currently govern something: their scope resolves and somebody under it carries them. */
    private fun claimsOf(state: SchedulerState): List<Claim> {
        val out = mutableListOf<Claim>()
        for (category in state.categories) {
            for (rule in category.rules) {
                if (!scopeExists(state, rule.scopeTaskId)) continue
                if (scopeListId(state, rule.scopeTaskId) == null) continue
                val chains = chainsFor(state, category.id, rule.scopeTaskId)
                if (chains.isEmpty()) continue
                out += Claim(
                    categoryId = category.id,
                    title = category.title,
                    scope = rule.scopeTaskId,
                    scopeTitle = scopeTitle(state, rule.scopeTaskId),
                    target = rule.share.coerceIn(0.0, 1.0),
                    chains = chains,
                )
            }
        }
        return out
    }

    /** How deep a scope sits, so the deepest rule is applied first. `MAIN` is 0. */
    private fun scopeDepth(state: SchedulerState, scope: TaskId): Int {
        if (scope == WellKnownIds.MAIN_TASK) return 0
        val occurrence = SchedulerDomain.firstTaskOccurrence(state, scope) ?: return 0
        return RelativePriorityDomain.ancestorCells(state, occurrence.cellId).size + 1
    }

    // ----- Contradictions -----------------------------------------------------------------------

    /**
     * The impossibilities that can be named exactly, checked before anything is scaled so the user is told
     * *which* two rules disagree rather than "it did not converge".
     *
     * All four are about the rules sharing ONE scope, because that is where the arithmetic is closed: the
     * shares of one sub-tree sum to 1.
     */
    private fun structuralContradiction(state: SchedulerState, claims: List<Claim>): String? {
        for ((_, group) in claims.groupBy { it.scope }) {
            val scopeName = group.first().scopeTitle
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
            "“${it.title}” at ${percent(it.target)} of “${it.scopeTitle}”"
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
     * rows above the field are how they are removed.
     */
    fun menuEntries(state: SchedulerState, input: String, exclude: Collection<CategoryId>): List<Category> {
        val taken = exclude.toSet()
        val typed = input.trim()
        return state.categories
            .filter { it.id !in taken }
            .filter { typed.isEmpty() || it.title.contains(typed, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Category> { SchedulerDomain.titleSimilarity(it.title, typed) }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id.value },
            )
    }

    /** The **title suggestions** of that same field: the category titles the typed text appears in. */
    fun titleSuggestions(state: SchedulerState, input: String): List<String> =
        menuEntries(state, input, emptyList())
            .map { it.title }
            .filter { it.isNotBlank() && !it.equals(input.trim(), ignoreCase = true) }
            .distinct()

    /**
     * The **scope** picker of the category edit window: every task the tree holds, plus the root, ordered
     * like the "All tasks" list so the same account reads the same way in both. A rule's scope is a task
     * because a task is what names a sub-list.
     */
    fun scopeEntries(state: SchedulerState, input: String): List<Pair<TaskId, String>> {
        val typed = input.trim()
        val rows = mutableListOf(WellKnownIds.MAIN_TASK to TaskRelationsDomain.ROOT_LABEL)
        for (entry in SchedulerDomain.taskListEntries(state)) {
            rows += entry.taskId to entry.title
        }
        return rows.filter { (_, title) -> typed.isEmpty() || title.contains(typed, ignoreCase = true) }
    }
}
