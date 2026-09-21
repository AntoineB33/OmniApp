package org.example.project.scheduler.state

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTreeId

/**
 * `docs/invariants/persistence.md` § *A History Unit records what it changed, not the whole state*: **the entries
 * of a keyed collection that one change touched** — for each touched key, its value before and after, a key
 * absent from a side meaning the entry did not exist there.
 *
 * A unit used to hold whole copies of what it touched the collection of — the whole tree twice to rename one task
 * (~400 KB on the release account), the whole panel list twice to move one block (~340 KB) — and every unit is
 * written to the device's database and to the account's server. What a change touched is what it is worth.
 *
 * Applied IN PLACE ([applyTo]): only the touched keys are rewritten, so undoing one device's unit leaves every
 * entry another device changed since exactly as it is.
 */
data class EntryChanges<K, V>(val before: Map<K, V>, val after: Map<K, V>) {
    val touched: Set<K> get() = before.keys + after.keys

    fun isEmpty(): Boolean = before.isEmpty() && after.isEmpty()

    /**
     * [current] with this change applied ([forward]) or undone, **three-way**: for every touched key, `from` is the
     * value the change leaves (undo) or finds (redo), `to` the value it puts.
     *
     * - the entry is still `from` → it becomes `to` (removed when `to` is absent);
     * - the entry was created, and nothing holds the key → it is created;
     * - the entry changed since — another device's edit, later than this unit — it is [merge]d: the change's own
     *   part replayed onto what is there now. The default keeps what is there: the later edit wins.
     *
     * `docs/invariants/persistence.md` § *One history, per-device undo*: undoing one device's unit must never take
     * back what another device did after it.
     */
    fun applyTo(
        current: Map<K, V>,
        forward: Boolean,
        exact: Boolean = false,
        merge: (current: V, from: V, to: V) -> V = { c, _, _ -> c },
    ): Map<K, V> {
        if (isEmpty()) return current
        val out = LinkedHashMap<K, V>(current.size + after.size)
        val decided = LinkedHashMap<K, V?>()
        for (k in touched) {
            val from = if (forward) before[k] else after[k]
            val to = if (forward) after[k] else before[k]
            decided[k] = if (exact) to else resolve(current[k], from, to, merge)
        }
        for ((k, v) in current) {
            if (k !in decided) out[k] = v else decided[k]?.let { out[k] = it }
        }
        for ((k, v) in decided) if (v != null && k !in out) out[k] = v
        return out
    }

    /** The value a touched key ends at: see [applyTo]. */
    internal fun <T> resolve(current: T?, from: T?, to: T?, merge: (T, T, T) -> T): T? =
        when {
            current == from -> to
            current == null -> if (from == null) to else null
            from == null || to == null -> current
            else -> merge(current, from, to)
        }

    /** This change followed by [later], as one: the earliest `before` and the latest `after` of every key. */
    fun then(later: EntryChanges<K, V>): EntryChanges<K, V> {
        val before = LinkedHashMap(this.before)
        val after = LinkedHashMap(this.after)
        for (k in later.touched) {
            if (k !in touched) later.before[k]?.let { before[k] = it }
            after.remove(k)
            later.after[k]?.let { after[k] = it }
        }
        return of(before, after, touchedHint = touched + later.touched)
    }

    companion object {
        /** The keys whose value differs between [before] and [after], and those values. */
        fun <K, V> of(before: Map<K, V>, after: Map<K, V>): EntryChanges<K, V> = of(before, after, before.keys + after.keys)

        private fun <K, V> of(before: Map<K, V>, after: Map<K, V>, touchedHint: Set<K>): EntryChanges<K, V> {
            val b = LinkedHashMap<K, V>()
            val a = LinkedHashMap<K, V>()
            for (k in touchedHint) {
                val bv = before[k]
                val av = after[k]
                if (bv == av && before.containsKey(k) == after.containsKey(k)) continue
                bv?.let { b[k] = it }
                av?.let { a[k] = it }
            }
            return EntryChanges(b, a)
        }

        /** The changes between two lists of entries identified by [id]. */
        fun <K, V> ofList(before: List<V>, after: List<V>, id: (V) -> K): EntryChanges<K, V> =
            of(before.associateBy(id), after.associateBy(id))
    }
}

/**
 * [EntryChanges] for a LIST whose order is part of its meaning (the panel list, alarms, timers): the entries are
 * rewritten in place — a touched entry keeps its position, a removed one leaves it, an added one is appended —
 * which is what the previous whole-list replacement produced for every edit the app makes (appends, in-place edits,
 * removals).
 */
fun <K, V> EntryChanges<K, V>.applyToList(current: List<V>, forward: Boolean, exact: Boolean = false, id: (V) -> K): List<V> {
    if (isEmpty()) return current
    val byKey = current.associateBy(id)
    val decided = LinkedHashMap<K, V?>()
    for (k in (if (forward) after.keys + before.keys else before.keys + after.keys)) {
        val from = if (forward) before[k] else after[k]
        val to = if (forward) after[k] else before[k]
        decided[k] = if (exact) to else resolve(byKey[k], from, to) { c, _, _ -> c }
    }
    val out = ArrayList<V>(current.size + touched.size)
    val present = HashSet<K>()
    for (v in current) {
        val k = id(v)
        if (k !in decided) {
            out += v
            present += k
        } else {
            decided[k]?.let {
                out += it
                present += k
            }
        }
    }
    for ((k, v) in decided) if (v != null && k !in present) out += v
    return out
}

/**
 * An id list with one change replayed onto it three-way: exactly [to] while [current] is still [from]; otherwise the
 * change's own insertions (each after its nearest predecessor in [to] that is there) and removals. An id is only
 * removed when [gone] says its entry really went — an entry another device changed since was kept, and so is its
 * place in the list.
 */
internal fun <T> rebaseIds(current: List<T>, from: List<T>, to: List<T>, gone: (T) -> Boolean): List<T> {
    val removed = from.toSet() - to.toSet()
    if (current == from && removed.all(gone)) return to
    val out = current.filterTo(ArrayList()) { !(it in removed && gone(it)) }
    val fromSet = from.toSet()
    for ((index, x) in to.withIndex()) {
        if (x in fromSet || x in out) continue
        val predecessor = (index - 1 downTo 0).map { to[it] }.firstOrNull { it in out }
        if (predecessor == null) out.add(0, x) else out.add(out.indexOf(predecessor) + 1, x)
    }
    return out
}

/** A single field three-way: the change's value while the field is still what the change found, else the field. */
internal fun <T> rebaseField(current: T, from: T, to: T): T = if (current == from) to else current

/** A map three-way, key by key. */
internal fun <K, T> rebaseMap(current: Map<K, T>, from: Map<K, T>, to: Map<K, T>): Map<K, T> {
    if (current == from) return to
    val out = LinkedHashMap(current)
    for (k in from.keys + to.keys) {
        if (current[k] != from[k]) continue
        val t = to[k]
        if (t == null) out.remove(k) else out[k] = t
    }
    return out
}

/** The members a change added to and removed from a set. */
data class SetChanges<T>(val added: Set<T>, val removed: Set<T>) {
    fun applyTo(current: Set<T>, forward: Boolean): Set<T> =
        if (forward) current - removed + added else current - added + removed

    fun isEmpty(): Boolean = added.isEmpty() && removed.isEmpty()

    companion object {
        fun <T> of(before: Set<T>, after: Set<T>): SetChanges<T> = SetChanges(after - before, before - after)
    }
}

/**
 * What a change did to the tree ([TreeSnapshot]): the cells, lists and tasks it touched, and the two id counters.
 * The title index is derived from the tasks, so it is rebuilt rather than recorded.
 *
 * Tasks are recorded WITHOUT their completed-work records, like [SchedulerState.captureTree]: records have their own
 * unit ([RecordDelta]), and applying keeps each task's record as the state holds it.
 */
data class TreeDiff(
    val cells: EntryChanges<CellId, Cell>,
    val lists: EntryChanges<CellListId, CellList>,
    val tasks: EntryChanges<TaskId, Task>,
    val nextTaskCounterBefore: Int,
    val nextTaskCounterAfter: Int,
    val nextCellCounterBefore: Int,
    val nextCellCounterAfter: Int,
    /**
     * True for a STORED tree (a task tree entry, the live tree a switch loads): its tasks keep their records, which
     * are then part of what changed. False — the default — for an edit of the live tree, whose records are not
     * history.
     */
    val withRecords: Boolean = false,
) {
    fun isEmpty(): Boolean =
        cells.isEmpty() && lists.isEmpty() && tasks.isEmpty() &&
            nextTaskCounterBefore == nextTaskCounterAfter && nextCellCounterBefore == nextCellCounterAfter

    /**
     * The state with this change applied [forward] or undone. The id counters never go down: an id handed out once
     * may already live elsewhere (another tree, another device's unit), so undoing a creation never re-mints it.
     */
    fun applyTo(state: SchedulerState, forward: Boolean, exact: Boolean = false): SchedulerState {
        if (isEmpty()) return state
        val (cells, lists, tasks) = applyToMaps(state.cells, state.lists, state.tasks, forward, exact)
        return state.keepingRootOf(
            state.copy(
                cells = cells,
                lists = lists,
                tasks = tasks,
                titleToTaskIds = if (this.tasks.isEmpty()) state.titleToTaskIds else SchedulerDomain.buildTitleIndex(tasks),
                nextTaskCounter = maxOf(state.nextTaskCounter, if (forward) nextTaskCounterAfter else nextTaskCounterBefore),
                nextCellCounter = maxOf(state.nextCellCounter, if (forward) nextCellCounterAfter else nextCellCounterBefore),
            ),
        )
    }

    /** [snapshot] — a stored tree (a task tree entry, the default sub-tree) — with this change applied or undone. */
    fun applyTo(snapshot: TreeSnapshot, forward: Boolean, exact: Boolean = false): TreeSnapshot {
        if (isEmpty()) return snapshot
        val (cells, lists, tasks) = applyToMaps(snapshot.cells, snapshot.lists, snapshot.tasks, forward, exact)
        return snapshot.copy(
            cells = cells,
            lists = lists,
            tasks = tasks,
            titleToTaskIds = if (this.tasks.isEmpty()) snapshot.titleToTaskIds else SchedulerDomain.buildTitleIndex(tasks),
            nextTaskCounter = maxOf(snapshot.nextTaskCounter, if (forward) nextTaskCounterAfter else nextTaskCounterBefore),
            nextCellCounter = maxOf(snapshot.nextCellCounter, if (forward) nextCellCounterAfter else nextCellCounterBefore),
        )
    }

    /**
     * The three collections with this change applied three-way ([EntryChanges.applyTo]), field by field where an
     * entry changed since: a list's cells and a task's children and occurrences keep what another device inserted,
     * and lose an id only when its entry really went; every other field keeps a later value.
     *
     * Without records ([withRecords] false) a task is compared with its record left out, and keeps the record it has.
     */
    private fun applyToMaps(
        currentCells: Map<CellId, Cell>,
        currentLists: Map<CellListId, CellList>,
        currentTasks: Map<TaskId, Task>,
        forward: Boolean,
        exact: Boolean,
    ): Triple<Map<CellId, Cell>, Map<CellListId, CellList>, Map<TaskId, Task>> {
        val cellsOut =
            cells.applyTo(currentCells, forward, exact) { c, f, t ->
                c.copy(
                    parentListId = rebaseField(c.parentListId, f.parentListId, t.parentListId),
                    taskId = rebaseField(c.taskId, f.taskId, t.taskId),
                    priorityWeights = rebaseField(c.priorityWeights, f.priorityWeights, t.priorityWeights),
                )
            }
        fun stripped(task: Task) = if (withRecords) task else task.copy(record = emptyList())
        val comparableTasks = if (tasks.isEmpty() || withRecords) currentTasks else currentTasks.mapValues { stripped(it.value) }
        // Which tasks exist once the change is applied, before any field is merged: what a child list may drop.
        val taskPresence = tasks.touched.associateWith { k ->
            tasks.resolve<Task>(comparableTasks[k], if (forward) tasks.before[k] else tasks.after[k], if (forward) tasks.after[k] else tasks.before[k]) { c, _, _ -> c } != null
        }
        fun taskGone(id: TaskId) = taskPresence[id] == false || (id !in taskPresence && id !in currentTasks)
        fun cellGone(id: CellId) = id !in cellsOut
        val tasksMerged =
            tasks.applyTo(comparableTasks, forward, exact) { c, f, t ->
                c.copy(
                    title = rebaseField(c.title, f.title, t.title),
                    childTaskIds = rebaseIds(c.childTaskIds, f.childTaskIds, t.childTaskIds, ::taskGone),
                    occurrences = rebaseIds(c.occurrences, f.occurrences, t.occurrences, ::cellGone),
                    childListId = rebaseField(c.childListId, f.childListId, t.childListId),
                    minimumMinutes = rebaseField(c.minimumMinutes, f.minimumMinutes, t.minimumMinutes),
                    record = rebaseField(c.record, f.record, t.record),
                    scheduleUnit = rebaseField(c.scheduleUnit, f.scheduleUnit, t.scheduleUnit),
                    text = rebaseField(c.text, f.text, t.text),
                    resilience = rebaseMap(c.resilience, f.resilience, t.resilience),
                    categoryIds = rebaseIds(c.categoryIds, f.categoryIds, t.categoryIds) { true },
                )
            }
        val tasksOut =
            if (tasks.isEmpty() || withRecords) tasksMerged
            else tasksMerged.mapValues { (id, task) -> if (id in tasks.touched) task.copy(record = currentTasks[id]?.record ?: emptyList()) else currentTasks[id] ?: task }
        val listsOut =
            lists.applyTo(currentLists, forward, exact) { c, f, t ->
                c.copy(
                    parentCellId = rebaseField(c.parentCellId, f.parentCellId, t.parentCellId),
                    cellIds = rebaseIds(c.cellIds, f.cellIds, t.cellIds, ::cellGone),
                    weightColumns = rebaseField(c.weightColumns, f.weightColumns, t.weightColumns),
                    defaultWeights = rebaseField(c.defaultWeights, f.defaultWeights, t.defaultWeights),
                    optionalTaskIds = SetChanges.of(f.optionalTaskIds, t.optionalTaskIds).applyTo(c.optionalTaskIds, forward = true),
                    optionalTaskValues = rebaseMap(c.optionalTaskValues, f.optionalTaskValues, t.optionalTaskValues),
                )
            }
        return Triple(cellsOut, listsOut, tasksOut)
    }

    /** This change followed by [later]. */
    fun then(later: TreeDiff): TreeDiff =
        TreeDiff(
            cells.then(later.cells), lists.then(later.lists), tasks.then(later.tasks),
            nextTaskCounterBefore, later.nextTaskCounterAfter, nextCellCounterBefore, later.nextCellCounterAfter, withRecords,
        )

    companion object {
        /** No change at all — what a unit carries for a half of the state its gesture never touched. */
        val EMPTY: TreeDiff =
            of(
                TreeSnapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0, 0),
                TreeSnapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0, 0),
            )

        fun of(before: TreeSnapshot, after: TreeSnapshot, withRecords: Boolean = false): TreeDiff =
            TreeDiff(
                cells = EntryChanges.of(before.cells, after.cells),
                lists = EntryChanges.of(before.lists, after.lists),
                tasks =
                    if (withRecords) EntryChanges.of(before.tasks, after.tasks)
                    else EntryChanges.of(
                        before.tasks.mapValues { it.value.copy(record = emptyList()) },
                        after.tasks.mapValues { it.value.copy(record = emptyList()) },
                    ),
                nextTaskCounterBefore = before.nextTaskCounter,
                nextTaskCounterAfter = after.nextTaskCounter,
                nextCellCounterBefore = before.nextCellCounter,
                nextCellCounterAfter = after.nextCellCounter,
                withRecords = withRecords,
            )
    }
}

/** What a change did to one stored task tree that existed on both sides. */
data class TaskTreeEntryChange(
    val id: TaskTreeId,
    val titleBefore: String,
    val titleAfter: String,
    val dateBefore: Long?,
    val dateAfter: Long?,
    val tree: TreeDiff,
    val expanded: SetChanges<CellId>,
) {
    fun applyTo(entry: TaskTreeEntry, forward: Boolean, exact: Boolean = false): TaskTreeEntry =
        entry.copy(
            title = if (forward) titleAfter else titleBefore,
            dateMillis = if (forward) dateAfter else dateBefore,
            tree = tree.applyTo(entry.tree, forward, exact),
            expanded = expanded.applyTo(entry.expanded, forward),
        )
}

/**
 * What a change did to the task trees ([TaskTreeStateSnapshot]): the stored entries added, removed or changed, the
 * active id, the id counter, and the LIVE tree and its expansion — which a switch rewrites. An entry that is created
 * or deleted is recorded whole (there is nothing smaller to put it back from); every other entry records what changed
 * in it.
 */
data class TaskTreesDiff(
    val added: List<TaskTreeEntry>,
    val removed: List<TaskTreeEntry>,
    val changed: List<TaskTreeEntryChange>,
    val orderBefore: List<TaskTreeId>,
    val orderAfter: List<TaskTreeId>,
    val activeBefore: TaskTreeId?,
    val activeAfter: TaskTreeId?,
    val counterBefore: Int,
    val counterAfter: Int,
    val live: TreeDiff,
    val liveExpanded: SetChanges<CellId>,
) {
    fun applyTo(state: SchedulerState, forward: Boolean, exact: Boolean = false): SchedulerState {
        val byId = LinkedHashMap(state.taskTrees.associateBy { it.id })
        val (gone, come) = if (forward) removed to added else added to removed
        for (e in gone) byId.remove(e.id)
        for (e in come) byId[e.id] = e
        for (c in changed) byId[c.id]?.let { byId[c.id] = c.applyTo(it, forward, exact) }
        val order = if (forward) orderAfter else orderBefore
        val trees = order.mapNotNull { byId.remove(it) } + byId.values
        val liveChanged = !live.isEmpty()
        return live.applyTo(state, forward, exact).copy(
            taskTrees = trees,
            activeTaskTreeId = if (forward) activeAfter else activeBefore,
            nextTaskTreeCounter = maxOf(state.nextTaskTreeCounter, if (forward) counterAfter else counterBefore),
            expanded = liveExpanded.applyTo(state.expanded, forward),
            selection = if (liveChanged) SchedulerSelection() else state.selection,
            editSession = if (liveChanged) null else state.editSession,
        )
    }

    companion object {
        fun of(before: TaskTreeStateSnapshot, after: TaskTreeStateSnapshot): TaskTreesDiff {
            val b = before.trees.associateBy { it.id }
            val a = after.trees.associateBy { it.id }
            return TaskTreesDiff(
                added = after.trees.filter { it.id !in b },
                removed = before.trees.filter { it.id !in a },
                changed =
                    after.trees.mapNotNull { next ->
                        val prev = b[next.id] ?: return@mapNotNull null
                        if (prev == next) return@mapNotNull null
                        TaskTreeEntryChange(
                            next.id, prev.title, next.title, prev.dateMillis, next.dateMillis,
                            TreeDiff.of(prev.tree, next.tree, withRecords = true),
                            SetChanges.of(prev.expanded, next.expanded),
                        )
                    },
                orderBefore = before.trees.map { it.id },
                orderAfter = after.trees.map { it.id },
                activeBefore = before.activeId,
                activeAfter = after.activeId,
                counterBefore = before.nextCounter,
                counterAfter = after.nextCounter,
                live = TreeDiff.of(before.tree, after.tree, withRecords = true),
                liveExpanded = SetChanges.of(before.expanded, after.expanded),
            )
        }
    }
}

/** The completed-work periods a change added to and removed from each task's record. */
data class RecordChanges(
    val added: Map<TaskId, List<org.example.project.scheduler.model.TaskTimeRange>>,
    val removed: Map<TaskId, List<org.example.project.scheduler.model.TaskTimeRange>>,
) {
    val tasks: Set<TaskId> get() = added.keys + removed.keys

    fun applyTo(state: SchedulerState, forward: Boolean): SchedulerState {
        var tasks = state.tasks
        for (id in this.tasks) {
            val task = tasks[id] ?: continue
            val plus = (if (forward) added[id] else removed[id]).orEmpty()
            val minus = (if (forward) removed[id] else added[id]).orEmpty().toSet()
            val record = (task.record.filterNot { it in minus } + plus.filterNot { it in task.record }).sortedBy { it.startEpochMillis }
            tasks = tasks + (id to task.copy(record = record))
        }
        return state.copy(tasks = tasks)
    }

    companion object {
        fun of(
            before: Map<TaskId, List<org.example.project.scheduler.model.TaskTimeRange>>,
            after: Map<TaskId, List<org.example.project.scheduler.model.TaskTimeRange>>,
        ): RecordChanges {
            val added = LinkedHashMap<TaskId, List<org.example.project.scheduler.model.TaskTimeRange>>()
            val removed = LinkedHashMap<TaskId, List<org.example.project.scheduler.model.TaskTimeRange>>()
            for (id in before.keys + after.keys) {
                val b = before[id].orEmpty()
                val a = after[id].orEmpty()
                val bs = b.toSet()
                val asSet = a.toSet()
                a.filter { it !in bs }.takeIf { it.isNotEmpty() }?.let { added[id] = it }
                b.filter { it !in asSet }.takeIf { it.isNotEmpty() }?.let { removed[id] = it }
            }
            return RecordChanges(added, removed)
        }
    }
}
