package org.example.project.scheduler.sync

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings
import org.example.project.scheduler.platform.ShortcutBinding
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.TaskTreeEntry

/**
 * **Three-way merge of two concurrently edited snapshots** — what [SchedulerSyncEngine] does when a reconcile
 * finds the remote revision ahead of this device's baseline *and* this device has unpushed edits of its own.
 *
 * Before this existed the policy was whole-document last-write-wins: the remote won and every local edit made
 * since the last push was silently dropped. That is only acceptable while a conflict is rare, and it stopped
 * being rare once the snapshot started syncing automatically in both directions — two devices left open on the
 * same account diverge on nothing more than a phone edit landing between a desktop edit and its 500 ms
 * auto-push debounce.
 *
 * **The merge needs a common ancestor**, and that is the whole reason [SyncMeta.baseSnapshot] exists: with
 * only the two current sides, "this task is absent locally" cannot be told from "the peer added it". With the
 * ancestor every difference is attributable, and the classic rules apply per entry — changed on one side only
 * ⇒ take that side; changed on both ⇒ resolve (below); deleted on one side ⇒ delete unless the other side
 * edited it. No ancestor (a DB upgraded from schema v9, an account never yet synced here) ⇒ [merge] is not
 * called at all and the engine keeps the old pull.
 *
 * **Two resolution rules carry the cases the ancestor cannot decide**, and both are chosen so a merge never
 * destroys work that a plain pull would have kept:
 *  - *Both sides changed the same value differently* ⇒ **the remote wins**. It is the value already agreed by
 *    the account (it is what every other device will converge on), and it is exactly what the old
 *    last-write-wins policy did — so the merge is a strict improvement over it, never a regression.
 *  - *One side deleted an entry the other side edited* ⇒ **the edit wins** (the entry survives). A deletion
 *    can be repeated in one gesture; a lost edit cannot be recovered. For tree cells this is weaker than it
 *    sounds: a cell resurrected in [SchedulerState.cells] but dropped from its list's `cellIds` is
 *    unreachable, and [repair] prunes it — so a genuine "delete this row" still deletes.
 *
 * **The result is repaired, not trusted.** Merging per entry can produce references the two sides never
 * jointly agreed on (a list naming a cell the peer deleted, a cell pointing at a purged task), so [repair]
 * drops dangling ids and runs the same [SchedulerDomain.pruneDetachedTree] reachability pass the editor uses.
 * A merged tree is therefore always as well-formed as an edited one.
 *
 * **What is deliberately NOT merged: the Undo/Redo history — the local one is kept.** A history unit carries
 * whole before/after tree snapshots, so interleaving two devices' units would build a timeline whose Ctrl+Z
 * restores a tree that never existed and silently discards the merge. Keeping this device's own stack is the
 * one choice that leaves Ctrl+Z meaning what its user just did. (Under the old pull the *remote's* stack was
 * adopted wholesale, which was strictly more surprising.) The per-device view state is not merged either, for
 * the ordinary reason — it is neutralized in all three snapshots anyway (CLAUDE.md reconstructibility rule).
 *
 * Everything here is pure: three [PersistedSnapshot]s in, one out, no clock and no I/O, which is what lets
 * `SnapshotMergeTest` assert the rules directly.
 */
object SnapshotMerge {
    /**
     * Merges the local and remote snapshots over their common ancestor [base], or returns `null` when any of
     * the three cannot be decoded — the caller must then fall back to the plain pull rather than guess.
     */
    fun merge(base: PersistedSnapshot, local: PersistedSnapshot, remote: PersistedSnapshot): PersistedSnapshot? {
        val b = SchedulerStateCodec.decodeSnapshot(base) ?: return null
        val l = SchedulerStateCodec.decodeSnapshot(local) ?: return null
        val r = SchedulerStateCodec.decodeSnapshot(remote) ?: return null
        return SchedulerStateCodec.encodeSnapshot(mergeStates(b, l, r))
    }

    /** The merge itself, on decoded states (also the unit-test seam). */
    fun mergeStates(base: SchedulerState, local: SchedulerState, remote: SchedulerState): SchedulerState {
        val tasks =
            mergeKeyed(base.tasks, local.tasks, remote.tasks) { b, l, r -> mergeTask(b, l, r) }
        val cells =
            mergeKeyed(base.cells, local.cells, remote.cells) { b, l, r -> mergeCell(b, l, r) }
        val lists =
            mergeKeyed(base.lists, local.lists, remote.lists) { b, l, r -> mergeList(b, l, r) }
        val panels =
            mergeKeyedList(base.panels, local.panels, remote.panels, TaskPanel::id) { b, l, r -> pick(b, l, r) }
        val chores =
            mergeKeyedList(base.chores, local.chores, remote.chores, ChoreEntry::id) { b, l, r -> pick(b, l, r) }
        val alarms =
            mergeKeyedList(base.alarms, local.alarms, remote.alarms, AlarmEntry::id) { b, l, r -> pick(b, l, r) }
        // PRD §18 Timers: whole objects, like the alarms. A timer's settings and its run state are not
        // independent fields to interleave — taking the duration from one side and the end instant from the
        // other would produce a countdown neither device started (and TimerDomain.healed is what catches the
        // one shape a field-wise merge could still forge).
        val timers =
            mergeKeyedList(base.timers, local.timers, remote.timers, TimerEntry::id) { b, l, r -> pick(b, l, r) }
        // Task trees resolve as WHOLE objects: an entry's title and its stored tree are not independent
        // fields to interleave — a tree captured on one device is one consistent thing. Adding a tree on each
        // device keeps both (they carry different ids); the live tree of whichever entry is active is merged
        // by the ordinary cell/task rules above, since that is where it actually lives.
        val taskTrees =
            mergeKeyedList(base.taskTrees, local.taskTrees, remote.taskTrees, TaskTreeEntry::id) { b, l, r ->
                pick(b, l, r)
            }

        val merged =
            local.copy(
                // A merged tree must stay anchored: the root only ever differs if one side re-rooted, which is
                // not an edit the UI can make, so this is a formality kept for total correctness.
                rootListId = pick(base.rootListId, local.rootListId, remote.rootListId),
                lists = lists,
                cells = cells,
                tasks = tasks,
                // Derived from `tasks` — rebuilt by [repair], never merged.
                titleToTaskIds = local.titleToTaskIds,
                expanded = mergeSet(base.expanded, local.expanded, remote.expanded),
                // Ids are never reused, so a counter must clear BOTH sides' allocations or the next id
                // collides with one the peer already handed out.
                nextTaskCounter = maxOf(local.nextTaskCounter, remote.nextTaskCounter),
                nextCellCounter = maxOf(local.nextCellCounter, remote.nextCellCounter),
                nextPanelCounter = maxOf(local.nextPanelCounter, remote.nextPanelCounter),
                nextTaskTreeCounter = maxOf(local.nextTaskTreeCounter, remote.nextTaskTreeCounter),
                taskTrees = taskTrees,
                // Which tree is live goes with the live tree itself (which the remote wins on conflict), so
                // the two can never end up naming different trees.
                activeTaskTreeId =
                    pickNullable(base.activeTaskTreeId, local.activeTaskTreeId, remote.activeTaskTreeId),
                panels = panels,
                chores = chores,
                alarms = alarms,
                timers = timers.map(org.example.project.scheduler.domain.TimerDomain::healed),
                automaticSchedule = pick(base.automaticSchedule, local.automaticSchedule, remote.automaticSchedule),
                // PRD §4 Default sub-tree: the template resolves as ONE value, like a task tree — and it IS
                // a tree, a shape the user drew, not independent rows to interleave (two devices each
                // inserting a row would otherwise produce a template neither of them authored). The switch
                // is an ordinary scalar beside it.
                defaultSubtree = pick(base.defaultSubtree, local.defaultSubtree, remote.defaultSubtree),
                defaultSubtreeEnabled =
                    pick(base.defaultSubtreeEnabled, local.defaultSubtreeEnabled, remote.defaultSubtreeEnabled),
                // PRD §13: the account-wide deep-copy depth is an ordinary scalar setting.
                deepCopyMaxDepth = pick(base.deepCopyMaxDepth, local.deepCopyMaxDepth, remote.deepCopyMaxDepth),
                // ...and so is each of the three "what does a copy carry" switches beside it: they move
                // independently in the window, so they merge independently.
                copyIncludeIds = pick(base.copyIncludeIds, local.copyIncludeIds, remote.copyIncludeIds),
                copyPriorityTables =
                    pick(base.copyPriorityTables, local.copyPriorityTables, remote.copyPriorityTables),
                copyIncludeText = pick(base.copyIncludeText, local.copyIncludeText, remote.copyIncludeText),
                // PRD §7 Keyboard shortcuts: the chord overrides merge PER SHORTCUT — rebinding "I'm away" on
                // the desktop while the laptop rebinds "Switch task" keeps both — but one shortcut's chord is
                // a whole value, never a key from one device wearing the other's modifiers. Resetting is a
                // removal, and mergeKeyed treats it as one: deleted on one side and untouched on the other
                // stays deleted, i.e. back to the default.
                shortcutBindings =
                    mergeKeyed(
                        base.shortcutBindings,
                        local.shortcutBindings,
                        remote.shortcutBindings,
                    ) { bb, ll, rr -> pick(bb, ll, rr) },
                lookAwayVoiceEnabled =
                    pick(base.lookAwayVoiceEnabled, local.lookAwayVoiceEnabled, remote.lookAwayVoiceEnabled),
                // PRD §11: the notifications switch is the account's, and an ordinary scalar beside the voice
                // one — the notification LOG it governs is per-device and stays local either way.
                notificationsEnabled =
                    pick(base.notificationsEnabled, local.notificationsEnabled, remote.notificationsEnabled),
                // The sleep schedule's four fields drift together (a wake time implies its bedtime), so it is
                // resolved as one value rather than field by field.
                sleep = pickNullable(base.sleep, local.sleep, remote.sleep),
                sleepingUntilMillis =
                    pickNullable(base.sleepingUntilMillis, local.sleepingUntilMillis, remote.sleepingUntilMillis),
                sleepingSinceMillis =
                    pickNullable(base.sleepingSinceMillis, local.sleepingSinceMillis, remote.sleepingSinceMillis),
                // PRD §7 "Switch task": the refusal is one indivisible statement (which task, refused when),
                // so it resolves as a whole value — never a task from one device paired with an instant from
                // the other, which would refuse a task at a moment nobody asked about.
                forcedSwitch = pickNullable(base.forcedSwitch, local.forcedSwitch, remote.forcedSwitch),
                // PRD §13 "start this task now": the request is the same indivisible statement.
                forcedStart = pickNullable(base.forcedStart, local.forcedStart, remote.forcedStart),
                // PRD §5: the relative-priority pins merge per (task, ancestor) key — pinning on one device
                // and on another keeps both keys — but one key's set resolves as a WHOLE value: it is one
                // choice about how one edit distributes, not a bag of independent flags.
                relativePriorityPins =
                    mergeKeyed(
                        base.relativePriorityPins,
                        local.relativePriorityPins,
                        remote.relativePriorityPins,
                    ) { bb, ll, rr -> pick(bb, ll, rr) },
                // histories / focusedWindow / selection / the display switches / the diagnostic logs stay
                // LOCAL — see the class KDoc.
            )
        return repair(merged)
    }

    // ---- entity merges -------------------------------------------------------------------------

    /**
     * Field-wise, because a task's fields are genuinely independent: renaming it on the phone while raising
     * its minimum time on the desktop must keep both, which resolving the whole [Task] as one value could not.
     */
    private fun mergeTask(base: Task?, local: Task, remote: Task): Task =
        local.copy(
            title = pick(base?.title, local.title, remote.title),
            childTaskIds = mergeOrdered(base?.childTaskIds, local.childTaskIds, remote.childTaskIds),
            occurrences = mergeOrdered(base?.occurrences, local.occurrences, remote.occurrences),
            childListId = pickNullable(base?.childListId, local.childListId, remote.childListId),
            minimumMinutes = pick(base?.minimumMinutes, local.minimumMinutes, remote.minimumMinutes),
            // Completed-work periods are banked facts, not a value one side "owns": each device banks the ones
            // its own now-line crossed, so the union (less whatever either side deliberately removed via
            // RemoveRecordPeriod) is the only answer that loses no history.
            record = mergeRecords(base?.record, local.record, remote.record),
            scheduleUnit = pick(base?.scheduleUnit, local.scheduleUnit, remote.scheduleUnit),
            text = pick(base?.text, local.text, remote.text),
            // `side-dev/README.md`: the resilience map is one value PER KIND, so it merges per kind for the
            // same reason the shortcut bindings do — lowering a task's resilience to "no on-screen task" on
            // the phone while giving it one to a kind the desktop just defined must keep both. A kind absent
            // from a side is the DEFAULT there, not "unchanged", so a removal on one side is a real edit.
            resilience = mergeResilience(base?.resilience, local.resilience, remote.resilience),
        )

    /**
     * Per-kind three-way merge of [Task.resilience]. Added on one side is kept, removed on one side and
     * untouched on the other is removed, and where both moved the same kind the remote wins — the same policy
     * every other field follows, applied one kind at a time.
     */
    private fun mergeResilience(
        base: Map<String, Double>?,
        local: Map<String, Double>,
        remote: Map<String, Double>,
    ): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (kind in local.keys + remote.keys + (base?.keys ?: emptySet())) {
            val b = base?.get(kind)
            val l = local[kind]
            val r = remote[kind]
            val winner = if (r != b) r else l
            if (winner != null) out[kind] = winner
        }
        return out
    }

    private fun mergeCell(base: Cell?, local: Cell, remote: Cell): Cell =
        local.copy(
            parentListId = pick(base?.parentListId, local.parentListId, remote.parentListId),
            taskId = pickNullable(base?.taskId, local.taskId, remote.taskId),
            priorityWeights = pick(base?.priorityWeights, local.priorityWeights, remote.priorityWeights),
        )

    /**
     * `cellIds` is the case that matters most: two devices each adding a row to the same sub-list is the
     * everyday concurrent edit, and both rows must survive.
     */
    private fun mergeList(base: CellList?, local: CellList, remote: CellList): CellList =
        local.copy(
            parentCellId = pickNullable(base?.parentCellId, local.parentCellId, remote.parentCellId),
            cellIds = mergeOrdered(base?.cellIds, local.cellIds, remote.cellIds),
            weightColumns = pick(base?.weightColumns, local.weightColumns, remote.weightColumns),
        )

    private fun mergeRecords(
        base: List<TaskTimeRange>?,
        local: List<TaskTimeRange>,
        remote: List<TaskTimeRange>,
    ): List<TaskTimeRange> =
        mergeOrdered(base, local, remote).sortedWith(
            compareBy({ it.startEpochMillis }, { it.endEpochMillis }),
        )

    // ---- generic three-way primitives ----------------------------------------------------------

    /**
     * The scalar rule. `null` [base] means the entry exists on both sides but in neither ancestor (both added
     * it under the same id), which no ancestor can adjudicate — the remote wins there too, for the same reason
     * it wins a genuine conflict.
     */
    private fun <T : Any> pick(base: T?, local: T, remote: T): T =
        when {
            local == remote -> local
            base != null && base == local -> remote
            base != null && base == remote -> local
            else -> remote
        }

    /** [pick] for a value that may legitimately BE null (an unset sleep schedule, a cell with no task). */
    private fun <T : Any> pickNullable(base: T?, local: T?, remote: T?): T? =
        when {
            local == remote -> local
            base == local -> remote
            base == remote -> local
            else -> remote
        }

    /**
     * Keyed three-way merge over the union of both sides' keys. Add/delete are attributed against [base]:
     * an entry present on one side only was either just added (keep it) or just deleted (drop it) — unless
     * the surviving side also EDITED it, in which case the edit wins and the entry stays (see the class KDoc).
     */
    private fun <K, V> mergeKeyed(
        base: Map<K, V>,
        local: Map<K, V>,
        remote: Map<K, V>,
        mergeEntry: (base: V?, local: V, remote: V) -> V,
    ): Map<K, V> {
        val out = LinkedHashMap<K, V>(local.size + remote.size)
        for (key in local.keys + remote.keys) {
            val b = base[key]
            val l = local[key]
            val r = remote[key]
            val value =
                when {
                    l != null && r != null -> mergeEntry(b, l, r)
                    // Present on one side only: added there (no ancestor) ⇒ keep; else deleted on the other
                    // side ⇒ keep only if this side edited it since the ancestor.
                    l != null -> l.takeIf { b == null || b != l }
                    r != null -> r.takeIf { b == null || b != r }
                    else -> null
                }
            if (value != null) out[key] = value
        }
        return out
    }

    /**
     * [mergeKeyed] for a list whose entries carry their own identity, preserving a merged order.
     *
     * Membership is the keyed merge's answer, never the order merge's: the two disagree exactly when one side
     * deleted an entry the other edited, where [mergeKeyed] keeps the edit but [mergeOrdered] — which only
     * sees ids and so cannot tell an edit from an untouched entry — drops it. Such an entry is appended in the
     * position it holds on whichever side still lists it, so nothing the merge decided to keep is lost to the
     * ordering pass.
     */
    private fun <T : Any, K> mergeKeyedList(
        base: List<T>,
        local: List<T>,
        remote: List<T>,
        keyOf: (T) -> K,
        mergeEntry: (base: T?, local: T, remote: T) -> T,
    ): List<T> {
        val merged = mergeKeyed(base.associateBy(keyOf), local.associateBy(keyOf), remote.associateBy(keyOf), mergeEntry)
        val order = mergeOrdered(base.map(keyOf), local.map(keyOf), remote.map(keyOf)).filterTo(mutableListOf()) { it in merged }
        val ordered = order.toMutableSet()
        for (entry in local + remote) {
            val key = keyOf(entry)
            if (key in merged && ordered.add(key)) order.add(key)
        }
        return order.mapNotNull(merged::get)
    }

    private fun <T> mergeSet(base: Set<T>, local: Set<T>, remote: Set<T>): Set<T> =
        mergeOrdered(base.toList(), local.toList(), remote.toList()).toSet()

    /**
     * Three-way merge of an ordered list of distinct elements (ids, and the record periods, which are equally
     * order-insensitive values).
     *
     * Membership is decided first, by attribution against [base]: an element is dropped iff some side deleted
     * it, and added iff some side added it. Order then follows the **remote**, with each element only one side
     * added spliced in after the neighbour it followed there — so a row inserted in the middle on the phone
     * does not jump to the end just because the desktop pushed first.
     */
    private fun <T> mergeOrdered(base: List<T>?, local: List<T>, remote: List<T>): List<T> {
        if (local == remote) return local
        if (base == local) return remote
        if (base == remote) return local
        val baseSet = base?.toSet().orEmpty()
        val localSet = local.toSet()
        val remoteSet = remote.toSet()
        // Deleted by whoever had it in the ancestor and does not have it now.
        val deleted = baseSet.filterTo(mutableSetOf()) { it !in localSet || it !in remoteSet }
        val out = remote.filterTo(mutableListOf()) { it !in deleted }
        val placed = out.toMutableSet()
        for ((index, element) in local.withIndex()) {
            if (element in deleted || !placed.add(element)) continue
            // Splice after the nearest preceding element that is already in the result; nothing precedes it
            // (or none of its predecessors survived) ⇒ it belongs at the front.
            val anchor = local.subList(0, index).lastOrNull { it in placed && it != element }
            out.add(anchor?.let { out.indexOf(it) + 1 } ?: 0, element)
        }
        return out
    }

    // ---- repair --------------------------------------------------------------------------------

    /**
     * Restores the tree invariants a per-entry merge can break. Neither side is inconsistent on its own, but
     * their combination can be: a list keeps a cell the peer deleted, a cell points at a task the peer purged,
     * a task's `occurrences` name cells that no longer exist.
     *
     * Dangling references are dropped first (a cell whose task is gone becomes an empty cell — a state the
     * model already allows — rather than a broken one), then the editor's own reachability pass
     * ([SchedulerDomain.pruneDetachedTree]) removes whatever the merged tree no longer reaches from the root
     * and purges the tasks left cell-less, exactly as it does after an ordinary edit. Its `expanded` filter
     * and the title index come along for free.
     */
    private fun repair(state: SchedulerState): SchedulerState {
        val lists =
            state.lists.mapValues { (_, list) ->
                list.copy(
                    parentCellId = list.parentCellId?.takeIf { it in state.cells },
                    cellIds = list.cellIds.filter { it in state.cells },
                )
            }
        val cells =
            state.cells.mapValues { (_, cell) ->
                cell.copy(taskId = cell.taskId?.takeIf { it in state.tasks })
            }
        val tasks =
            state.tasks.mapValues { (_, task) ->
                task.copy(
                    childTaskIds = task.childTaskIds.filter { it in state.tasks },
                    occurrences = task.occurrences.filter { it in cells },
                    childListId = task.childListId?.takeIf { it in lists },
                )
            }
        val rooted =
            state.copy(
                lists = lists,
                cells = cells,
                tasks = tasks,
                // If the merged root somehow names a list neither side kept, the tree would be empty; fall
                // back to a root that exists so the walk below has somewhere to start.
                rootListId = state.rootListId.takeIf { it in lists } ?: lists.keys.firstOrNull() ?: state.rootListId,
                expanded = state.expanded.filterTo(mutableSetOf()) { it in cells },
                // A selection naming a task tree the merge did not keep would leave the live tree unable to
                // flush itself; reading as "never named" is the state that loses nothing.
                activeTaskTreeId =
                    state.activeTaskTreeId?.takeIf { id -> state.taskTrees.any { it.id == id } },
                // PRD §7 Keyboard shortcuts: merging per shortcut can produce something neither device ever
                // had — two shortcuts landing on ONE chord, because each rebound a different shortcut onto
                // it. The claim cannot honour that (one press, two actions), so the collision is dropped back
                // to the default: the loser's own device shows it back on its shipped chord, which is visible
                // in the window, where a chord silently answering the wrong action would not be.
                shortcutBindings = repairShortcutBindings(state.shortcutBindings),
            )
        return SchedulerDomain.pruneDetachedTree(rooted)
    }

    /**
     * Keep only the overrides that leave the **resolved** table honourable: every chord bound to at most one
     * action, and none below [GlobalShortcutBindings.MIN_MODIFIERS].
     *
     * The resolved table is what matters, not the overrides alone: dropping one puts its shortcut back on the
     * chord it ships with, and that could collide in turn — so the check is re-run until nothing collides. A
     * collision is always broken by dropping an **override**, never a default (the defaults are distinct by
     * construction, and each is what its own dropped override falls back to), so the loop can only shrink the
     * map and always terminates. Everything is walked in [GlobalShortcut] order, so two devices merging the
     * same pair reach the same table whatever order their maps iterate in.
     */
    private fun repairShortcutBindings(
        bindings: Map<GlobalShortcut, ShortcutBinding>,
    ): Map<GlobalShortcut, ShortcutBinding> {
        val kept = LinkedHashMap<GlobalShortcut, ShortcutBinding>()
        GlobalShortcut.entries.forEach { shortcut ->
            val binding = bindings[shortcut] ?: return@forEach
            if (binding.modifierCount >= GlobalShortcutBindings.MIN_MODIFIERS) kept[shortcut] = binding
        }
        while (true) {
            val resolved = GlobalShortcutBindings.resolve(kept)
            val firstHolder = HashMap<ShortcutBinding, GlobalShortcut>()
            val victim =
                GlobalShortcut.entries.firstNotNullOfOrNull { shortcut ->
                    val held = firstHolder.put(resolved.getValue(shortcut), shortcut) ?: return@firstNotNullOfOrNull null
                    if (shortcut in kept) shortcut else held
                }
            if (victim == null || kept.remove(victim) == null) return kept
        }
    }
}
