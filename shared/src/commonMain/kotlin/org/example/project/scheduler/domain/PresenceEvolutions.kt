package org.example.project.scheduler.domain

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState

/** One time period of a [PresenceEvolution]: from [fromMillis] until [untilMillis], null for an open side. */
data class EvolutionPeriod(val fromMillis: Long? = null, val untilMillis: Long? = null)

/**
 * User rule 2026-11-19: **a presence ratio target evolution** — an element of the account (`Kind.PresenceEvolution`),
 * edited from the actions of the tasks it involves:
 * - [taskIds]: the tasks that get it;
 * - what it is relative to: [relativeTask], one task element above them all, or — null — each task's own ancestor at
 *   [parentDistance] (1: its parent);
 * - [periods]: when it holds (none: always);
 * - [formula]: the presence ratio target there, a fraction of the relative task's time, a formula of the instant
 *   ([TimeFormula]);
 * - its categories are its parents that are categories ([ElementTree], as for any element).
 *
 * Authoritative, persisted and synced, one row per evolution. Read in ONE place, [adjust], from
 * [SchedulerDomain.absoluteTaskPriorities]; checked by [FormulaCoherence].
 */
data class PresenceEvolution(
    val id: String,
    val taskIds: List<TaskId> = emptyList(),
    val relativeTask: TaskId? = null,
    val parentDistance: Int = 1,
    val periods: List<EvolutionPeriod> = emptyList(),
    val formula: String = "",
)

object PresenceEvolutions {
    const val ID_PREFIX = "presence-"

    fun mintId(used: Collection<String>): String {
        val taken = used.toHashSet()
        var n = 1
        while ("$ID_PREFIX$n" in taken) n++
        return "$ID_PREFIX$n"
    }

    /**
     * The evolution as ONE formula of the instant: its formula inside its periods (`periods(…)` over days), no value
     * outside them. Blank where it has no formula.
     */
    fun effectiveText(e: PresenceEvolution): String {
        val f = e.formula.trim()
        if (f.isEmpty() || e.periods.isEmpty()) return f
        return "periods(" + e.periods.joinToString(", ") { p ->
            "${p.fromMillis?.let { TimeFormula.dayOf(it).toString() } ?: "-inf"}, ${p.untilMillis?.let { TimeFormula.dayOf(it).toString() } ?: "inf"}, ($f)"
        } + ")"
    }

    fun parsed(e: PresenceEvolution): TimeFormula.Parsed? = TimeFormula.parse(effectiveText(e))

    /** Its value at [atMillis]: null outside its periods, or where it reads as no number. Not clamped. */
    fun valueAt(e: PresenceEvolution, atMillis: Long): Double? = parsed(e)?.at(atMillis)

    /** Which evolutions involve one of [taskIds]: the evolutions pop-up's list, the defaults included ([shown]). */
    fun involving(state: SchedulerState, taskIds: Collection<TaskId>): List<PresenceEvolution> {
        val ids = taskIds.toHashSet()
        return shown(state).filter { e -> e.taskIds.any { it in ids } }
    }

    // ----- the default evolutions -----------------------------------------------------------------

    /**
     * User rule 2026-11-20: *"By default, every task in the first task tree (those that have the first child of the global
     * root as an ascendant) must have the presence ratio target evolution as a constant value in the whole timeline. The
     * value is by default associated with the absolute priority the user can define with the priority weight tables and
     * the relative priority manager."*
     *
     * So each task under the first task tree's root has a **default evolution**: its own, relative to the global root
     * ("Task trees"), always, its formula the constant its absolute priority is ([SchedulerDomain.absoluteTaskPriorities]).
     * It is DERIVED, never stored nor synced: the weights are what it reads, so it moves when they do, and the schedulers
     * read the weights, not it ([adjust] and [FormulaCoherence] read the stored evolutions only). Edited in the rows, it is
     * written as a stored evolution under the same id ([DEFAULT_ID_PREFIX]), which then holds in its place.
     */
    const val DEFAULT_ID_PREFIX = ID_PREFIX + "default-"

    fun defaultIdOf(task: TaskId): String = DEFAULT_ID_PREFIX + task.value

    fun isDefault(e: PresenceEvolution, state: SchedulerState): Boolean =
        e.id.startsWith(DEFAULT_ID_PREFIX) && state.presenceEvolutions.none { it.id == e.id }

    private class DefaultsMemo(
        val cells: Any, val lists: Any, val tasks: Any, val evolutions: Any,
        val value: List<PresenceEvolution>,
    )

    private var defaultsMemo: DefaultsMemo? = null

    /** The default evolutions not overridden by a stored one of the same id, in the first tree's order of walk. */
    fun defaults(state: SchedulerState): List<PresenceEvolution> {
        defaultsMemo?.let { m ->
            if (m.cells === state.cells && m.lists === state.lists && m.tasks === state.tasks && m.evolutions === state.presenceEvolutions) return m.value
        }
        val computed = computeDefaults(state)
        defaultsMemo = DefaultsMemo(state.cells, state.lists, state.tasks, state.presenceEvolutions, computed)
        return computed
    }

    private fun computeDefaults(state: SchedulerState): List<PresenceEvolution> {
        val first = TaskTreesAsTasks.topTaskIds(state).firstOrNull() ?: return emptyList()
        val under = TreeIndex(state).descendants(first)
        if (under.isEmpty()) return emptyList()
        val stored = state.presenceEvolutions.mapTo(HashSet()) { it.id }
        val absolute = SchedulerDomain.absoluteTaskPriorities(state)
        return under.mapNotNull { t ->
            val id = defaultIdOf(t)
            if (id in stored) null
            else PresenceEvolution(id, listOf(t), WellKnownIds.ROOT_TASK, formula = constantText(absolute[t] ?: 0.0))
        }
    }

    /** The stored evolutions, then the defaults: what the Search window lists and the rows edit. */
    fun shown(state: SchedulerState): List<PresenceEvolution> {
        val d = defaults(state)
        return if (d.isEmpty()) state.presenceEvolutions else state.presenceEvolutions + d
    }

    /** [ratio] as a plain decimal a [TimeFormula] reads (no exponent), to the billionth. */
    fun constantText(ratio: Double): String {
        if (!ratio.isFinite() || ratio <= 0.0) return "0"
        val scaled = kotlin.math.round(ratio * 1e9).toLong()
        val whole = scaled / 1_000_000_000L
        val frac = (scaled % 1_000_000_000L).toString().padStart(9, '0').trimEnd('0')
        return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
    }

    // ----- the tree, as the evolutions read it -------------------------------------------------

    /** Parents and children of every task in the tree, off its populated cells. */
    class TreeIndex(state: SchedulerState) {
        val parents = HashMap<TaskId, MutableSet<TaskId>>()
        val children = HashMap<TaskId, MutableSet<TaskId>>()

        init {
            for (cell in state.cells.values) {
                val t = cell.taskId ?: continue
                if (!SchedulerDomain.isPopulatedCell(state, cell.id)) continue
                val p = SchedulerDomain.parentTaskIdOfList(state, cell.parentListId) ?: continue
                if (p == t) continue
                parents.getOrPut(t) { LinkedHashSet() } += p
                children.getOrPut(p) { LinkedHashSet() } += t
            }
        }

        /** The ancestors of [t] at a shortest distance of exactly [distance] (1: its parents). */
        fun ancestorsAt(t: TaskId, distance: Int): Set<TaskId> {
            if (distance < 1) return emptySet()
            val seen = hashSetOf(t)
            var level: Set<TaskId> = setOf(t)
            repeat(distance) {
                level = level.flatMap { parents[it].orEmpty() }.filterTo(LinkedHashSet()) { seen.add(it) }
                if (level.isEmpty()) return emptySet()
            }
            return level
        }

        /** Every ancestor of [t], at any distance. */
        fun ancestors(t: TaskId): Set<TaskId> {
            val out = LinkedHashSet<TaskId>()
            val stack = ArrayDeque(parents[t].orEmpty().toList())
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                if (out.add(p)) stack.addAll(parents[p].orEmpty())
            }
            return out
        }

        /** Every descendant of [t], at any distance. */
        fun descendants(t: TaskId): Set<TaskId> {
            val out = LinkedHashSet<TaskId>()
            val stack = ArrayDeque(children[t].orEmpty().toList())
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                if (out.add(c)) stack.addAll(children[c].orEmpty())
            }
            return out
        }

        fun isLeaf(t: TaskId): Boolean = children[t].isNullOrEmpty()

        /** How far below the root [t] stands, by its shortest path. */
        fun depth(t: TaskId): Int {
            var d = 0
            var level: Set<TaskId> = setOf(t)
            val seen = hashSetOf(t)
            while (level.isNotEmpty() && WellKnownIds.ROOT_TASK !in level && d < 10_000) {
                level = level.flatMap { parents[it].orEmpty() }.filterTo(HashSet()) { seen.add(it) }
                d++
            }
            return d
        }
    }

    /** The tasks [e] makes [t]'s target relative to: its relative task, else [t]'s ancestors at its parent distance. */
    fun relativesOf(index: TreeIndex, e: PresenceEvolution, t: TaskId): Set<TaskId> =
        e.relativeTask?.let { setOf(it) } ?: index.ancestorsAt(t, e.parentDistance)

    /** The tasks above every one of [taskIds]: what the "relative to" pop-up offers. */
    fun commonAncestors(state: SchedulerState, taskIds: Collection<TaskId>): Set<TaskId> {
        if (taskIds.isEmpty()) return emptySet()
        val index = TreeIndex(state)
        return taskIds.map { index.ancestors(it) }.reduce { a, b -> a intersect b }
    }

    /** One target in force at an instant: [task] takes [ratio] of [relative]'s time, by evolution [evolutionId]. */
    data class Target(val task: TaskId, val relative: TaskId, val ratio: Double, val evolutionId: String)

    /** The targets in force at [atMillis]: every task of every evolution, relative to each of its relatives that is above it. */
    fun targetsAt(state: SchedulerState, index: TreeIndex, atMillis: Long): List<Target> {
        val out = ArrayList<Target>()
        for (e in state.presenceEvolutions) {
            val r = valueAt(e, atMillis) ?: continue
            for (t in e.taskIds) {
                if (t !in index.parents) continue
                val above = index.ancestors(t)
                for (rel in relativesOf(index, e, t)) if (rel in above) out += Target(t, rel, r, e.id)
            }
        }
        return out
    }

    /**
     * [absolute] (every task's share of the whole, as the weights give it) with the targets in force at [atMillis]: each
     * relative task R, the outermost first, gives each task T of its targets exactly `ratio × share(R)` — spread over
     * T's sub-tree as the weights spread it — and what is left of R's share to the rest of its sub-tree, in the
     * proportions the weights give. Incoherent targets ([FormulaCoherence]) are made whole, never refused: the
     * schedulers wait for them anyway.
     */
    fun adjust(state: SchedulerState, absolute: Map<TaskId, Double>, atMillis: Long): Map<TaskId, Double> {
        if (state.presenceEvolutions.isEmpty()) return absolute
        val index = TreeIndex(state)
        val targets = targetsAt(state, index, atMillis)
        if (targets.isEmpty()) return absolute
        val out = HashMap(absolute)
        fun share(t: TaskId) = if (t == WellKnownIds.ROOT_TASK) 1.0 else out[t] ?: 0.0
        for ((rel, list) in targets.groupBy { it.relative }.entries.sortedBy { index.depth(it.key) }) {
            val total = share(rel)
            if (total <= 0.0) continue
            // One ratio per task (the last evolution's: two at once is incoherent), the outermost tasks only.
            val byTask = list.associate { it.task to it.ratio.coerceIn(0.0, 1.0) }
            val subOf = byTask.keys.associateWith { index.descendants(it) }
            val maximal = byTask.filterKeys { t -> byTask.keys.none { other -> other != t && t in subOf.getValue(other) } }
            val inside = HashSet<TaskId>()
            for (t in maximal.keys) {
                inside += t
                inside += subOf.getValue(t)
            }
            val below = index.descendants(rel)
            val freeLeaves = below.filter { it !in inside && index.isLeaf(it) }
            val freeOld = freeLeaves.sumOf { out[it] ?: 0.0 }
            val sum = maximal.values.sum()
            val (kFree, scale) =
                if (freeOld > 0.0 && sum < 1.0) total * (1.0 - sum) / freeOld to 1.0
                else 0.0 to (if (sum > 0.0) 1.0 / sum else 0.0)
            val before = HashMap(out)
            for ((t, ratio) in maximal) {
                val goal = total * ratio * scale
                val old = before[t] ?: 0.0
                val sub = subOf.getValue(t)
                if (old > 0.0) {
                    val k = goal / old
                    out[t] = goal
                    for (d in sub) before[d]?.let { out[d] = it * k }
                } else {
                    // No share to scale: the goal spread evenly over its leaves.
                    val leaves = sub.filter { index.isLeaf(it) }.ifEmpty { listOf(t) }
                    out[t] = goal
                    for (l in leaves) out[l] = goal / leaves.size
                }
            }
            val ancestorsOfTargets = maximal.keys.flatMapTo(HashSet()) { index.ancestors(it) }
            for (x in below) {
                if (x in inside) continue
                val old = before[x] ?: continue
                if (x !in ancestorsOfTargets) {
                    out[x] = old * kFree
                } else {
                    // Between R and its targets: what it held of them moved, the rest scaled as the free part is.
                    val heldTargets = maximal.keys.filter { it in index.descendants(x) }
                    val heldOld = heldTargets.sumOf { before[it] ?: 0.0 }
                    val heldNew = heldTargets.sumOf { out[it] ?: 0.0 }
                    out[x] = heldNew + (old - heldOld).coerceAtLeast(0.0) * kFree
                }
            }
        }
        return out
    }

    /**
     * The build of the morning of 2026-11-19 wrote a place's presence ratio target on its cell
     * ([org.example.project.scheduler.model.Cell.presenceFormula]): each becomes an evolution of that task relative to the
     * cell's parent, its id a function of the cell (two devices lift alike), and the cell's field is cleared.
     */
    fun liftCellFormulas(state: SchedulerState): SchedulerState {
        val carrying = state.cells.values.filter { it.presenceFormula.isNotBlank() }
        if (carrying.isEmpty()) return state
        val known = state.presenceEvolutions.mapTo(HashSet()) { it.id }
        val lifted = carrying.mapNotNull { cell ->
            val task = cell.taskId ?: return@mapNotNull null
            val id = ID_PREFIX + "cell-" + cell.id.value
            if (id in known) return@mapNotNull null
            PresenceEvolution(id, listOf(task), SchedulerDomain.parentTaskIdOfList(state, cell.parentListId), formula = cell.presenceFormula.trim())
        }
        return state.copy(
            presenceEvolutions = state.presenceEvolutions + lifted,
            cells = state.cells + carrying.associate { it.id to it.copy(presenceFormula = "") },
        )
    }

    // ----- what a row says ----------------------------------------------------------------------

    fun formatInstant(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).let {
            val time = if (it.hour == 0 && it.minute == 0) "" else " ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
            "${it.date}$time"
        }

    fun relativeText(state: SchedulerState, e: PresenceEvolution): String =
        e.relativeTask?.let { "of “${state.tasks[it]?.title.orEmpty().ifBlank { it.value }}”" }
            ?: if (e.parentDistance == 1) "of the parent" else "of the ancestor ${e.parentDistance} levels up"

    fun periodsText(e: PresenceEvolution): String =
        if (e.periods.isEmpty()) "always"
        else e.periods.joinToString("; ") { p ->
            listOfNotNull(p.fromMillis?.let { "from " + formatInstant(it) }, p.untilMillis?.let { "until " + formatInstant(it) })
                .joinToString(" ").ifEmpty { "always" }
        }

    /** A Search row's name: the tasks it is given to and its formula. */
    fun title(state: SchedulerState, e: PresenceEvolution): String {
        val tasks = e.taskIds.take(3).joinToString(", ") { state.tasks[it]?.title.orEmpty().ifBlank { it.value } } +
            if (e.taskIds.size > 3) " and ${e.taskIds.size - 3} more" else ""
        return (if (tasks.isBlank()) "no task" else tasks) + ": " + e.formula.ifBlank { "no formula" }
    }

    /** A Search row's detail: what it is relative to, and when. */
    fun detail(state: SchedulerState, e: PresenceEvolution): String =
        relativeText(state, e) + " · " + periodsText(e) + if (isDefault(e, state)) " · default: its absolute priority" else ""
}
