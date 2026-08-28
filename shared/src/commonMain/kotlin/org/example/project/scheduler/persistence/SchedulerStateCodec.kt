@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.example.project.scheduler.persistence

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.ChoreRecurrenceUnit
import org.example.project.scheduler.model.DEFAULT_MINIMUM_MINUTES
import org.example.project.scheduler.model.ForcedTaskStart
import org.example.project.scheduler.model.ForcedTaskSwitch
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.RelativePriorityPinKey
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.model.TaskTreeId
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.Delta
import org.example.project.scheduler.state.EmptyCellsDelta
import org.example.project.scheduler.state.FocusDelta
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.NoOpDelta
import org.example.project.scheduler.state.NotificationLogEntry
import org.example.project.scheduler.state.SupabaseUsageEntry
import org.example.project.scheduler.state.PanelDelta
import org.example.project.scheduler.state.RecordDelta
import org.example.project.scheduler.state.SchedulerEditSession
import org.example.project.scheduler.state.SchedulerHistories
import org.example.project.scheduler.state.SchedulerHistory
import org.example.project.scheduler.state.SchedulerSelection
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.SetSelectionDelta
import org.example.project.scheduler.state.SleepDelta
import org.example.project.scheduler.state.DefaultSubtreeDelta
import org.example.project.scheduler.state.DefaultSubtreeTemplate
import org.example.project.scheduler.state.defaultSubtreeIsEmpty
import org.example.project.scheduler.state.TaskTreeDelta
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings
import org.example.project.scheduler.platform.ShortcutBinding
import org.example.project.scheduler.platform.ShortcutKey
import org.example.project.scheduler.state.ShortcutBindingDelta
import org.example.project.scheduler.state.TaskTreeEntry
import org.example.project.scheduler.state.TaskTreeStateSnapshot
import org.example.project.scheduler.state.SetExpandedDelta
import org.example.project.scheduler.state.ToggleExpandDelta
import org.example.project.scheduler.state.TreeMutationDelta
import org.example.project.scheduler.state.TreeSnapshot

/**
 * Serializes the durable part of [SchedulerState] for [SchedulerStore]: the Task Tree, occurrences,
 * expansion, selection, calendar panels, chores, sleep schedule, settings, and the Undo/Redo history.
 *
 * The Undo/Redo history IS persisted (PRD §6): each unit's polymorphic
 * [org.example.project.scheduler.state.Delta] round-trips through its serializable mirror, so a
 * reloaded session keeps its undo/redo timeline. (Units committed under the diverged debug clock are
 * reverted on load — see [org.example.project.scheduler.state.SchedulerReducer.rollbackDebugTainted], §16.)
 * [encodeSnapshot]/[decodeSnapshot] split this into the SQLite shape — the non-history state as one JSON
 * row plus one row per history unit — while [encode]/[decode] keep the whole-blob JSON for legacy migration.
 *
 * An in-flight [SchedulerEditSession] is persisted so a crash mid-edit can be detected;
 * [org.example.project.scheduler.ui.TaskSchedulerViewModel] cancels it on load. The `titleToTaskIds`
 * index is derived, so it is rebuilt on load rather than stored.
 */
object SchedulerStateCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encode(state: SchedulerState): String = json.encodeToString(state.toPersisted())

    /** Returns the decoded state, or `null` if the payload is missing/corrupt. */
    fun decode(text: String): SchedulerState? =
        runCatching { json.decodeFromString<PersistedState>(text).toState() }.getOrNull()

    /**
     * Splits a [SchedulerState] into the structured [PersistedSnapshot] the SQLite store persists: the
     * non-history state as one JSON [PersistedSnapshot.statePayload] row, plus one [HistoryRow] per
     * History Unit and one [HistoryPointerRow] per category. The history is intentionally *omitted*
     * from the payload blob (it lives in its own per-row table instead).
     */
    fun encodeSnapshot(state: SchedulerState): PersistedSnapshot {
        val statePayload = json.encodeToString(state.toPersisted().copy(histories = null))
        val rows = mutableListOf<HistoryRow>()
        val pointers = mutableListOf<HistoryPointerRow>()
        for ((category, history) in state.histories.all()) {
            pointers.add(HistoryPointerRow(category.name, history.pointer))
            history.units.forEachIndexed { index, unit ->
                rows.add(
                    HistoryRow(
                        category = category.name,
                        ordinal = index,
                        timeMillis = unit.timeMillis,
                        chronoId = unit.chronoId,
                        debugTainted = unit.debugTainted,
                        deltaJson = json.encodeToString(unit.delta.toPersisted()),
                    ),
                )
            }
        }
        return PersistedSnapshot(statePayload, rows, pointers)
    }

    /**
     * The **authoritative projection** of [state] for cross-device sync (CLAUDE.md reconstructibility rule):
     * the same [PersistedSnapshot] [encodeSnapshot] produces, but with everything that another device can
     * re-derive or that is purely local removed — namely the derived (regenerated) panels (see
     * [SchedulerDomain.isRegeneratedPanel]) and the per-device **view state** (focused window, tree
     * selection, calendar display switches, and their WindowNav/Selection history; see
     * [SchedulerState.withLocalViewStateNeutralized]). Two states with an equal fingerprint carry identical
     * authoritative data — task tree, records, pinned/user panels, reminders, sleep, settings, history — so
     * there is nothing to sync between them. An engine-tick reschedule that only re-derives the auto/side/
     * sleep panels, or a UI change that only navigates windows / zooms / toggles a calendar switch, leaves
     * the fingerprint unchanged, which is how the ViewModel avoids marking state dirty / pushing (the "known
     * deviation"). Transient, non-persisted fields (clipboard, edit session…) are already absent from the
     * encoded snapshot, so they never count as an authoritative change either.
     *
     * This projection is also **the payload that goes over the wire**: the ViewModel binds it as the sync
     * engine's `localSnapshot`, so a push ships only the irreducible data — never the regenerated panels or
     * the per-device view state, which every device recomputes/keeps locally (bandwidth rule, ARCHITECTURE.md
     * §8). A puller regenerates the panels on its next reschedule and carries its own view state across the
     * pull (`withLocalViewStateFrom`), so nothing is lost by the stripping.
     */
    fun syncFingerprint(state: SchedulerState): PersistedSnapshot =
        encodeSnapshot(
            state.withLocalViewStateNeutralized()
                .copy(panels = state.panels.filterNot(SchedulerDomain::isRegeneratedPanel)),
        )

    /** Rebuilds a [SchedulerState] from a [PersistedSnapshot], or `null` if the payload is corrupt. */
    fun decodeSnapshot(snapshot: PersistedSnapshot): SchedulerState? =
        runCatching {
            json.decodeFromString<PersistedState>(snapshot.statePayload).toState()
                .copy(histories = buildHistories(snapshot.history, snapshot.pointers))
        }.getOrNull()

    private fun buildHistories(
        rows: List<HistoryRow>,
        pointers: List<HistoryPointerRow>,
    ): SchedulerHistories {
        val pointerByCategory = pointers.associate { it.category to it.pointer }
        val rowsByCategory = rows.groupBy { it.category }
        var histories = SchedulerHistories()
        for (category in HistoryCategory.entries) {
            val units =
                (rowsByCategory[category.name] ?: emptyList())
                    .sortedBy { it.ordinal }
                    .map { row ->
                        HistoryUnit(
                            timeMillis = row.timeMillis,
                            chronoId = row.chronoId,
                            delta = json.decodeFromString<PersistedDelta>(row.deltaJson).toDelta(),
                            debugTainted = row.debugTainted,
                        )
                    }
            histories =
                histories.withCategory(
                    category,
                    SchedulerHistory(pointer = pointerByCategory[category.name] ?: -1, units = units),
                )
        }
        return histories
    }

    private fun SchedulerState.toPersisted(): PersistedState =
        PersistedState(
            rootListId = rootListId.value,
            lists =
                lists.values.map {
                    PersistedList(
                        it.id.value,
                        it.parentCellId?.value,
                        it.cellIds.map(CellId::value),
                        it.weightColumns,
                    )
                },
            cells =
                cells.values.map {
                    PersistedCell(it.id.value, it.parentListId.value, it.taskId?.value, it.priorityWeights)
                },
            tasks =
                tasks.values.map {
                    PersistedTask(
                        id = it.id.value,
                        title = it.title,
                        childTaskIds = it.childTaskIds.map(TaskId::value),
                        occurrences = it.occurrences.map(CellId::value),
                        childListId = it.childListId?.value,
                        minimumMinutes = it.minimumMinutes,
                        record = it.record.map { r -> PersistedTimeRange(r.startEpochMillis, r.endEpochMillis) },
                        scheduleUnit = it.scheduleUnit.map { e -> PersistedScheduleUnitEntry(e.title, e.spanMinutes) },
                        text = it.text,
                        // Written for an OLDER build's benefit: it has no `resilience` field, so the derived
                        // on-screen reading is the only thing that still tells it where the task may run.
                        onScreen = it.onScreen,
                        doableDuringBreak = false,
                        resilience = it.resilience,
                    )
                },
            expanded = expanded.map(CellId::value),
            taskTrees = taskTrees.map { it.toPersisted() },
            activeTaskTreeId = activeTaskTreeId?.value,
            nextTaskTreeCounter = nextTaskTreeCounter,
            selectionMain = selection.main?.value,
            selectionSelected = selection.selected.map(CellId::value),
            selectionRangeAnchor = selection.rangeAnchor?.value,
            selectionRenderVia = selection.renderVia?.value,
            nextTaskCounter = nextTaskCounter,
            nextCellCounter = nextCellCounter,
            editSession = editSession?.toPersisted(),
            panels =
                panels.map {
                    PersistedPanel(
                        id = it.id,
                        taskId = it.taskId?.value,
                        title = it.title,
                        start = it.startEpochMillis,
                        end = it.endEpochMillis,
                        pinned = it.pinned,
                        auto = it.auto,
                        layoutWeight = it.layoutWeight,
                        chore = it.chore,
                        checked = it.checked,
                        screenBreak = it.screenBreak,
                        sleep = it.sleep,
                        noScreen = it.noScreen,
                        inactivity = it.inactivity,
                    )
                },
            nextPanelCounter = nextPanelCounter,
            automaticSchedule = automaticSchedule,
            chores = chores.map { PersistedChoreEntry(it.title, it.spanDays, it.timeOfDayMinutes, it.daysFormula, it.recurrenceUnit, it.id, it.constrainedToReminderId) },
            alarms =
                alarms.map {
                    PersistedAlarm(
                        it.id, it.label, it.timeOfDayMinutes, it.soundSeconds, it.vibrate,
                        // Sorted ISO day numbers, so the encoded payload (and therefore the sync fingerprint)
                        // is stable whatever order the set iterates in.
                        it.days.map { day -> day.isoDayNumber }.sorted(),
                        it.repeats, it.enabled,
                    )
                },
            // PRD §5: the relative-priority window's pinned cells, sorted so the encoded payload (and the
            // sync fingerprint with it) does not depend on the map's iteration order.
            relativePriorityPins =
                relativePriorityPins.entries
                    .sortedWith(compareBy({ it.key.taskId.value }, { it.key.relativeTo.value }))
                    .map { (key, cellIds) ->
                        PersistedRelativePriorityPins(
                            taskId = key.taskId.value,
                            relativeTo = key.relativeTo.value,
                            cellIds = cellIds.map(CellId::value).sorted(),
                        )
                    },
            showScreenBreaks = showScreenBreaks,
            showReminders = showReminders,
            lookAwayVoiceEnabled = lookAwayVoiceEnabled,
            // PRD §4 Default sub-tree: the template — a real tree, in the same shape a task tree is stored
            // in — and whether the policy is currently applied. The pre-1.6.0 `defaultSubtree` node list is
            // still READ (below) but never written again.
            // An empty template is written as *nothing*, so an account that never opened the window keeps a
            // payload free of it — which is also what makes "written before the feature existed" and "empty"
            // the same thing on the way back in.
            defaultSubtreeTree = defaultSubtree.takeIf { !defaultSubtreeIsEmpty }?.tree?.toPersisted(),
            defaultSubtreeExpanded =
                if (defaultSubtreeIsEmpty) emptyList()
                else defaultSubtree.expanded.map(CellId::value).sorted(),
            defaultSubtreeBoundCells =
                if (defaultSubtreeIsEmpty) emptyList()
                else defaultSubtree.boundCells.map(CellId::value).sorted(),
            defaultSubtreeEnabled = defaultSubtreeEnabled,
            // PRD §13: the account's one deep-copy depth, and its three "what does a copy carry" switches.
            deepCopyMaxDepth = deepCopyMaxDepth,
            copyIncludeIds = copyIncludeIds,
            copyPriorityTables = copyPriorityTables,
            copyIncludeText = copyIncludeText,
            // PRD §7 Keyboard shortcuts: the account's system-wide chord OVERRIDES, sorted by shortcut so
            // one binding table has exactly one encoding (the fingerprint is this payload, byte for byte).
            shortcutBindings = shortcutBindings.toPersistedRows(),
            // `side-dev/README.md`: the kinds of restrictive period this account has defined.
            periodKinds = periodKinds,
            focusedWindow = focusedWindow.name,
            histories = histories.toPersisted(),
            sleep = sleep?.let { PersistedSleep(it.wakeMinutes, it.goalWakeMinutes, it.sleepDurationMinutes, it.anchorEpochDay) },
            sleepingUntilMillis = sleepingUntilMillis,
            sleepingSinceMillis = sleepingSinceMillis,
            // PRD §7 "Switch task": the outstanding refusal, flattened to its two scalars.
            forcedSwitchTaskId = forcedSwitch?.taskId?.value,
            forcedSwitchAtMillis = forcedSwitch?.atMillis,
            // PRD §13 "start this task now": the outstanding request, flattened the same way.
            forcedStartTaskId = forcedStart?.taskId?.value,
            forcedStartAtMillis = forcedStart?.atMillis,
            notificationLog = notificationLog.map { PersistedNotificationEntry(it.timeMillis, it.title, it.message) },
            supabaseUsageLog =
                supabaseUsageLog.map {
                    PersistedSupabaseUsageEntry(it.timeMillis, it.resource, it.operation, it.requestBytes, it.responseBytes, it.status)
                },
        )

    /**
     * PRD §4 **Default sub-tree**, load-time migration: builds the pre-1.6.0 template — a tree of *titles*
     * ([PersistedDefaultSubtreeNode]) — into the real tree the app now stores.
     *
     * Each node becomes a cell plus a task carrying its title, under the ordinary root every tree has. A node
     * that was **bound** to an existing task keeps that binding: its cell points straight at the live task id
     * and joins `boundCells` (the switch, off), so it still mirrors what it always mirrored. Such a node
     * builds no template task of its own — the title lives on the task it points at — and so its stored
     * children go, exactly as they were already ignored: a sub-list belongs to the task id, and the old shape
     * never applied them either.
     *
     * Ids are minted from [startTaskCounter] / [startCellCounter], i.e. the account's own counters, so the
     * migrated template can never be handed an id the live tree already uses. The caller pushes the counters
     * past what this took.
     */
    private fun migrateDefaultSubtreeNodes(
        nodes: List<PersistedDefaultSubtreeNode>,
        startTaskCounter: Int,
        startCellCounter: Int,
    ): DefaultSubtreeTemplate {
        val cells = LinkedHashMap<CellId, Cell>()
        val lists = LinkedHashMap<CellListId, CellList>()
        val tasks = LinkedHashMap<TaskId, Task>()
        val bound = LinkedHashSet<CellId>()
        val expanded = LinkedHashSet<CellId>()
        var nextTask = startTaskCounter
        var nextCell = startCellCounter

        tasks[WellKnownIds.ROOT_TASK] =
            Task(id = WellKnownIds.ROOT_TASK, title = "root", childTaskIds = listOf(WellKnownIds.MAIN_TASK))
        tasks[WellKnownIds.MAIN_TASK] =
            Task(id = WellKnownIds.MAIN_TASK, title = "main", childListId = WellKnownIds.MAIN_LIST)

        // Returns the ids of the cells it placed, in order, so the caller can wire up its list.
        fun buildList(listId: CellListId, parentCellId: CellId?, source: List<PersistedDefaultSubtreeNode>) {
            val placed = mutableListOf<CellId>()
            for (node in source) {
                // The blank title is what deletes, here as in the tree: such a node was never grafted.
                if (node.title.isBlank()) continue
                val cellId = CellId("cell/${listId.value}/${nextCell++}")
                val liveTaskId = node.taskId?.let(::TaskId)
                if (liveTaskId != null) {
                    cells[cellId] = Cell(id = cellId, parentListId = listId, taskId = liveTaskId)
                    bound += cellId
                } else {
                    val taskId = TaskId("task/user/${nextTask++}")
                    val childListId = CellListId("${taskId.value}/children")
                    cells[cellId] = Cell(id = cellId, parentListId = listId, taskId = taskId)
                    tasks[taskId] =
                        Task(
                            id = taskId,
                            title = node.title,
                            occurrences = listOf(cellId),
                            childListId = childListId,
                        )
                    expanded += cellId
                    buildList(childListId, cellId, node.children)
                }
                placed += cellId
            }
            // Every list ends with the empty placeholder PRD §4 Auto-Expansion keeps.
            val placeholderId = CellId("cell/${listId.value}/${nextCell++}")
            cells[placeholderId] = Cell(id = placeholderId, parentListId = listId, taskId = null)
            placed += placeholderId
            lists[listId] = CellList(id = listId, parentCellId = parentCellId, cellIds = placed)
        }

        buildList(WellKnownIds.MAIN_LIST, null, nodes)

        // childTaskIds is denormalized off the cells that were just placed.
        for ((listId, list) in lists) {
            val parentTaskId =
                if (listId == WellKnownIds.MAIN_LIST) WellKnownIds.MAIN_TASK
                else list.parentCellId?.let { cells[it]?.taskId } ?: continue
            val childIds = list.cellIds.mapNotNull { cells[it]?.taskId }
            tasks[parentTaskId]?.let { tasks[parentTaskId] = it.copy(childTaskIds = childIds) }
        }

        return DefaultSubtreeTemplate(
            tree =
                TreeSnapshot(
                    cells = cells,
                    lists = lists,
                    tasks = tasks,
                    titleToTaskIds = SchedulerDomain.buildTitleIndex(tasks),
                    nextTaskCounter = nextTask,
                    nextCellCounter = nextCell,
                ),
            expanded = expanded,
            boundCells = bound,
        )
    }

    private fun SchedulerHistories.toPersisted(): PersistedHistories =
        PersistedHistories(
            edit = forCategory(HistoryCategory.Edit).toPersisted(),
            selection = forCategory(HistoryCategory.Selection).toPersisted(),
            calendar = forCategory(HistoryCategory.Calendar).toPersisted(),
            main = forCategory(HistoryCategory.Main).toPersisted(),
            windowNav = forCategory(HistoryCategory.WindowNav).toPersisted(),
        )

    private fun SchedulerHistory.toPersisted(): PersistedHistory =
        PersistedHistory(
            pointer = pointer,
            units =
                units.map { unit ->
                    PersistedHistoryUnit(
                        timeMillis = unit.timeMillis,
                        chronoId = unit.chronoId,
                        debugTainted = unit.debugTainted,
                        delta = unit.delta.toPersisted(),
                    )
                },
        )

    private fun SchedulerSelection.toPersisted(): PersistedSelection =
        PersistedSelection(
            main = main?.value,
            selected = selected.map(CellId::value),
            rangeAnchor = rangeAnchor?.value,
            renderVia = renderVia?.value,
        )

    /** Maps each [Delta] subtype to its serializable mirror. Exhaustive over the sealed hierarchy. */
    private fun Delta.toPersisted(): PersistedDelta =
        when (this) {
            is TreeMutationDelta -> PersistedDelta.TreeMutation(before.toPersisted(), after.toPersisted(), label)
            is EmptyCellsDelta ->
                PersistedDelta.EmptyCells(
                    treeBefore.toPersisted(),
                    treeAfter.toPersisted(),
                    selectionBefore.toPersisted(),
                    selectionAfter.toPersisted(),
                )
            is SetSelectionDelta -> PersistedDelta.SetSelection(before.toPersisted(), after.toPersisted())
            is FocusDelta -> PersistedDelta.Focus(before.name, after.name)
            is PanelDelta ->
                PersistedDelta.Panels(
                    before.map { it.toPersistedPanel() },
                    after.map { it.toPersistedPanel() },
                    label,
                )
            is ToggleExpandDelta -> PersistedDelta.ToggleExpand(cellId.value)
            is SetExpandedDelta ->
                PersistedDelta.SetExpanded(before.map { it.value }, after.map { it.value })
            is TaskTreeDelta -> PersistedDelta.TaskTrees(before.toPersisted(), after.toPersisted(), label)
            is DefaultSubtreeDelta ->
                PersistedDelta.DefaultSubtreeUnit(before.toPersisted(), after.toPersisted(), label)
            is RecordDelta ->
                PersistedDelta.Record(
                    before.mapKeys { it.key.value }.mapValues { e -> e.value.map { PersistedTimeRange(it.startEpochMillis, it.endEpochMillis) } },
                    after.mapKeys { it.key.value }.mapValues { e -> e.value.map { PersistedTimeRange(it.startEpochMillis, it.endEpochMillis) } },
                )
            is SleepDelta ->
                PersistedDelta.Sleep(
                    before?.let { PersistedSleep(it.wakeMinutes, it.goalWakeMinutes, it.sleepDurationMinutes, it.anchorEpochDay) },
                    PersistedSleep(after.wakeMinutes, after.goalWakeMinutes, after.sleepDurationMinutes, after.anchorEpochDay),
                )
            is ShortcutBindingDelta ->
                PersistedDelta.ShortcutBindings(before.toPersistedRows(), after.toPersistedRows())
            NoOpDelta -> PersistedDelta.NoOp
        }

    private fun TaskPanel.toPersistedPanel(): PersistedPanel =
        PersistedPanel(
            id = id,
            taskId = taskId?.value,
            title = title,
            start = startEpochMillis,
            end = endEpochMillis,
            pinned = pinned,
            auto = auto,
            layoutWeight = layoutWeight,
            chore = chore,
            checked = checked,
            screenBreak = screenBreak,
            sleep = sleep,
            noScreen = noScreen,
            inactivity = inactivity,
        )

    private fun SchedulerEditSession.toPersisted(): PersistedEditSession =
        PersistedEditSession(
            cellId = cellId.value,
            renderVia = renderVia?.value,
            draftText = draftText,
            mode = mode.name,
            selectedAssignTaskId = selectedAssignTaskId?.value,
            newTaskDraftId = newTaskDraftId?.value,
            treeBefore = treeBefore.toPersisted(),
            renameTreeBefore = renameTreeBefore?.toPersisted(),
        )

    private fun TaskTreeEntry.toPersisted(): PersistedTaskTree =
        PersistedTaskTree(
            id = id.value,
            title = title,
            tree = tree.toPersisted(),
            expanded = expanded.map(CellId::value),
            date = dateMillis,
        )

    private fun TaskTreeStateSnapshot.toPersisted(): PersistedTaskTreeState =
        PersistedTaskTreeState(
            trees = trees.map { it.toPersisted() },
            activeId = activeId?.value,
            nextCounter = nextCounter,
            tree = tree.toPersisted(),
            expanded = expanded.map(CellId::value),
        )

    private fun DefaultSubtreeTemplate.toPersisted(): PersistedDefaultSubtree =
        PersistedDefaultSubtree(
            tree = tree.toPersisted(),
            expanded = expanded.map(CellId::value).sorted(),
            boundCells = boundCells.map(CellId::value).sorted(),
        )

    private fun PersistedDefaultSubtree.toTemplate(): DefaultSubtreeTemplate =
        DefaultSubtreeTemplate(
            tree = tree.toSnapshot(),
            expanded = expanded.map(::CellId).toSet(),
            boundCells = boundCells.map(::CellId).toSet(),
        )

    private fun TreeSnapshot.toPersisted(): PersistedTreeSnapshot =
        PersistedTreeSnapshot(
            lists =
                lists.values.map {
                    PersistedList(
                        it.id.value,
                        it.parentCellId?.value,
                        it.cellIds.map(CellId::value),
                        it.weightColumns,
                    )
                },
            cells =
                cells.values.map {
                    PersistedCell(it.id.value, it.parentListId.value, it.taskId?.value, it.priorityWeights)
                },
            tasks =
                tasks.values.map {
                    PersistedTask(
                        id = it.id.value,
                        title = it.title,
                        childTaskIds = it.childTaskIds.map(TaskId::value),
                        occurrences = it.occurrences.map(CellId::value),
                        childListId = it.childListId?.value,
                        minimumMinutes = it.minimumMinutes,
                        record = it.record.map { r -> PersistedTimeRange(r.startEpochMillis, r.endEpochMillis) },
                        scheduleUnit = it.scheduleUnit.map { e -> PersistedScheduleUnitEntry(e.title, e.spanMinutes) },
                        text = it.text,
                        // Written for an OLDER build's benefit: it has no `resilience` field, so the derived
                        // on-screen reading is the only thing that still tells it where the task may run.
                        onScreen = it.onScreen,
                        doableDuringBreak = false,
                        resilience = it.resilience,
                    )
                },
            nextTaskCounter = nextTaskCounter,
            nextCellCounter = nextCellCounter,
        )

    private fun PersistedState.toState(): SchedulerState {
        val resolvedNextCellCounter =
            nextCellCounter ?: SchedulerState.deriveNextCellCounter(cells.map { CellId(it.id) })
        // PRD §4 Default sub-tree — resolved before the state is built because migrating the pre-1.6.0
        // shape mints ids, which the account's counters then have to be pushed past (below).
        val migratedDefaultSubtree =
            defaultSubtreeTree?.let {
                DefaultSubtreeTemplate(
                    tree = it.toSnapshot(),
                    expanded = defaultSubtreeExpanded.map(::CellId).toSet(),
                    boundCells = defaultSubtreeBoundCells.map(::CellId).toSet(),
                )
            }
                ?: if (defaultSubtree.isEmpty()) {
                    DefaultSubtreeTemplate.empty()
                } else {
                    migrateDefaultSubtreeNodes(defaultSubtree, nextTaskCounter, resolvedNextCellCounter)
                }
        val tasks =
            tasks.associate { p ->
                TaskId(p.id) to
                    Task(
                        id = TaskId(p.id),
                        title = p.title,
                        childTaskIds = p.childTaskIds.map(::TaskId),
                        occurrences = p.occurrences.map(::CellId),
                        childListId = p.childListId?.let(::CellListId),
                        minimumMinutes = p.minimumMinutes,
                        record = p.record.map { TaskTimeRange(it.start, it.end) },
                        scheduleUnit = p.scheduleUnit.map { ScheduleUnitEntry(it.title, it.spanMinutes) },
                        text = p.text,
                        resilience = decodeResilience(p),
                    )
            }
        val cells =
            cells.associate { p ->
                CellId(p.id) to
                    Cell(
                        id = CellId(p.id),
                        parentListId = CellListId(p.parentListId),
                        taskId = p.taskId?.let(::TaskId),
                        priorityWeights = p.priorityWeights,
                    )
            }
        val lists =
            lists.associate { p ->
                CellListId(p.id) to
                    CellList(
                        id = CellListId(p.id),
                        parentCellId = p.parentCellId?.let(::CellId),
                        cellIds = p.cellIds.map(::CellId),
                        weightColumns = p.weightColumns,
                    )
            }
        return SchedulerState(
            rootListId = CellListId(rootListId),
            lists = lists,
            cells = cells,
            tasks = tasks,
            titleToTaskIds = SchedulerDomain.buildTitleIndex(tasks),
            expanded = expanded.map(::CellId).toSet(),
            // A payload written before the task-tree selector existed decodes to no trees and an unnamed
            // live tree — exactly the state a fresh account starts in, so no migration is needed.
            taskTrees = taskTrees.map { it.toEntry() },
            activeTaskTreeId = activeTaskTreeId?.let(::TaskTreeId),
            nextTaskTreeCounter = nextTaskTreeCounter,
            selection =
                SchedulerSelection(
                    main = selectionMain?.let(::CellId),
                    selected = selectionSelected.map(::CellId).toSet(),
                    rangeAnchor = selectionRangeAnchor?.let(::CellId),
                    renderVia = selectionRenderVia?.let(::CellId),
                ),
            // Ids are handed out from one counter but live in every tree, the template included — so the
            // counters clear whatever the template holds, or the live tree could re-mint an id it uses.
            nextTaskCounter = maxOf(nextTaskCounter, migratedDefaultSubtree.tree.nextTaskCounter),
            nextCellCounter =
                maxOf(resolvedNextCellCounter, migratedDefaultSubtree.tree.nextCellCounter),
            editSession = editSession?.toSession(),
            panels =
                panels.map {
                    TaskPanel(
                        id = it.id,
                        taskId = it.taskId?.let(::TaskId),
                        title = it.title,
                        startEpochMillis = it.start,
                        endEpochMillis = it.end,
                        pinned = it.pinned,
                        // PRD §8: reconstruct [pins] from the persisted [pinned] (the only wired pin).
                        pins = PanelPins(existence = it.pinned),
                        auto = it.auto,
                        layoutWeight = it.layoutWeight,
                        chore = it.chore,
                        checked = it.checked,
                        screenBreak = it.screenBreak,
                        sleep = it.sleep,
                        noScreen = it.noScreen,
                        inactivity = it.inactivity,
                    )
                },
            nextPanelCounter = nextPanelCounter,
            automaticSchedule = automaticSchedule,
            chores = SchedulerDomain.assignReminderIds(
                chores.map {
                    ChoreEntry(
                        title = it.title,
                        spanDays = it.spanDays,
                        timeOfDayMinutes = it.timeOfDayMinutes,
                        daysFormula = it.daysFormula,
                        recurrenceUnit = it.recurrenceUnit,
                        id = it.id,
                        constrainedToReminderId = it.constrainedToReminderId,
                    )
                },
            ),
            // PRD §18 Alarms: a payload written before alarms existed decodes to an empty list; a row whose
            // id was somehow blank gets one minted here (the same healing the reminders get above).
            alarms = AlarmDomain.assignAlarmIds(
                alarms.map {
                    AlarmEntry(
                        id = it.id,
                        label = it.label,
                        timeOfDayMinutes = it.timeOfDayMinutes,
                        soundSeconds = it.soundSeconds,
                        vibrate = it.vibrate,
                        // A payload written before the days existed (null) rings every day — what it did.
                        days = it.days?.mapNotNullTo(mutableSetOf(), ::dayOfWeekOrNull) ?: AlarmEntry.EVERY_DAY,
                        repeats = it.repeats,
                        enabled = it.enabled,
                    )
                },
            ),
            // PRD §5: a payload written before the relative-priority window existed decodes to no pins.
            relativePriorityPins =
                relativePriorityPins
                    .filter { it.cellIds.isNotEmpty() }
                    .associate { entry ->
                        RelativePriorityPinKey(TaskId(entry.taskId), TaskId(entry.relativeTo)) to
                            entry.cellIds.map(::CellId).toSet()
                    },
            showScreenBreaks = showScreenBreaks,
            showReminders = showReminders,
            lookAwayVoiceEnabled = lookAwayVoiceEnabled,
            // PRD §4: three generations, all readable. A payload written before the "Default sub-tree"
            // window existed decodes to the empty template and the policy off — exactly the behaviour it
            // had. One written while the template was a tree of titles is MIGRATED into the real tree the
            // app now stores (ids minted past the account's own counters). One written since simply
            // decodes.
            defaultSubtree = migratedDefaultSubtree,
            defaultSubtreeEnabled = defaultSubtreeEnabled,
            // PRD §13: a payload written before the account-wide deep-copy depth existed decodes to the
            // default the window used to open on, and a hand-edited out-of-range one is healed into range.
            deepCopyMaxDepth = deepCopyMaxDepth.coerceIn(SchedulerDomain.DEEP_COPY_DEPTH_RANGE),
            copyIncludeIds = copyIncludeIds,
            copyPriorityTables = copyPriorityTables,
            copyIncludeText = copyIncludeText,
            // PRD §7 Keyboard shortcuts: a payload written before the window could rebind anything has no
            // entries at all, which is exactly "every chord is the one it ships with". A row naming a
            // shortcut or a key this build does not have is DROPPED rather than surfaced (that shortcut
            // falls back to its default), and so is one the rules would refuse — decode heals what an older
            // or hand-edited payload holds, it never hands the claim a chord the app would not accept.
            shortcutBindings = shortcutBindings.toShortcutBindings(),
            // A blank or built-in name is not a kind; duplicates collapse. A payload written
            // before kinds existed decodes to the two built-ins alone, which is right — a kind
            // nobody defined restricts nobody.
            periodKinds =
                periodKinds.map(PeriodKinds::normalize).filter(PeriodKinds::isUserDefined).distinct(),
            focusedWindow = runCatching { AppWindow.valueOf(focusedWindow) }.getOrDefault(AppWindow.Tree),
            histories = histories?.toHistories() ?: SchedulerHistories(),
            sleep = sleep?.let { SleepSchedule(it.wakeMinutes, it.goalWakeMinutes, it.sleepDurationMinutes, it.anchorEpochDay) },
            sleepingUntilMillis = sleepingUntilMillis,
            sleepingSinceMillis = sleepingSinceMillis,
            // PRD §7 "Switch task": both halves are needed for a refusal to mean anything, so a payload
            // written before the button existed — or a half-written one — decodes to "nothing outstanding".
            forcedSwitch =
                forcedSwitchTaskId?.let { id ->
                    forcedSwitchAtMillis?.let { at -> ForcedTaskSwitch(TaskId(id), at) }
                },
            // PRD §13 "start this task now": same two-halves rule as the refusal above.
            forcedStart =
                forcedStartTaskId?.let { id ->
                    forcedStartAtMillis?.let { at -> ForcedTaskStart(TaskId(id), at) }
                },
            notificationLog = notificationLog.map { NotificationLogEntry(it.timeMillis, it.title, it.message) },
            supabaseUsageLog =
                supabaseUsageLog.map {
                    SupabaseUsageEntry(it.timeMillis, it.resource, it.operation, it.requestBytes, it.responseBytes, it.status)
                },
        )
    }

    private fun PersistedHistories.toHistories(): SchedulerHistories =
        SchedulerHistories(
            edit = edit.toHistory(),
            selection = selection.toHistory(),
            calendar = calendar.toHistory(),
            main = main.toHistory(),
            windowNav = windowNav.toHistory(),
        )

    private fun PersistedHistory.toHistory(): SchedulerHistory =
        SchedulerHistory(
            pointer = pointer,
            units =
                units.map { u ->
                    HistoryUnit(
                        timeMillis = u.timeMillis,
                        chronoId = u.chronoId,
                        delta = u.delta.toDelta(),
                        debugTainted = u.debugTainted,
                    )
                },
        )

    private fun PersistedSelection.toSelection(): SchedulerSelection =
        SchedulerSelection(
            main = main?.let(::CellId),
            selected = selected.map(::CellId).toSet(),
            rangeAnchor = rangeAnchor?.let(::CellId),
            renderVia = renderVia?.let(::CellId),
        )

    private fun PersistedDelta.toDelta(): Delta =
        when (this) {
            is PersistedDelta.TreeMutation -> TreeMutationDelta(before.toSnapshot(), after.toSnapshot(), label)
            is PersistedDelta.EmptyCells ->
                EmptyCellsDelta(
                    treeBefore.toSnapshot(),
                    treeAfter.toSnapshot(),
                    selectionBefore.toSelection(),
                    selectionAfter.toSelection(),
                )
            is PersistedDelta.SetSelection -> SetSelectionDelta(before.toSelection(), after.toSelection())
            is PersistedDelta.Focus ->
                FocusDelta(
                    runCatching { AppWindow.valueOf(before) }.getOrDefault(AppWindow.Tree),
                    runCatching { AppWindow.valueOf(after) }.getOrDefault(AppWindow.Tree),
                )
            is PersistedDelta.Panels -> PanelDelta(before.map { it.toPanel() }, after.map { it.toPanel() }, label)
            is PersistedDelta.ToggleExpand -> ToggleExpandDelta(CellId(cellId))
            is PersistedDelta.SetExpanded ->
                SetExpandedDelta(before.map { CellId(it) }.toSet(), after.map { CellId(it) }.toSet())
            is PersistedDelta.TaskTrees -> TaskTreeDelta(before.toTaskTreeState(), after.toTaskTreeState(), label)
            is PersistedDelta.DefaultSubtreeUnit ->
                DefaultSubtreeDelta(before.toTemplate(), after.toTemplate(), label)
            is PersistedDelta.Record ->
                RecordDelta(
                    before.mapKeys { TaskId(it.key) }.mapValues { e -> e.value.map { TaskTimeRange(it.start, it.end) } },
                    after.mapKeys { TaskId(it.key) }.mapValues { e -> e.value.map { TaskTimeRange(it.start, it.end) } },
                )
            is PersistedDelta.Sleep ->
                SleepDelta(
                    before?.let { SleepSchedule(it.wakeMinutes, it.goalWakeMinutes, it.sleepDurationMinutes, it.anchorEpochDay) },
                    SleepSchedule(after.wakeMinutes, after.goalWakeMinutes, after.sleepDurationMinutes, after.anchorEpochDay),
                )
            is PersistedDelta.ShortcutBindings ->
                ShortcutBindingDelta(before.toShortcutBindings(), after.toShortcutBindings())
            PersistedDelta.NoOp -> NoOpDelta
        }

    private fun PersistedPanel.toPanel(): TaskPanel =
        TaskPanel(
            id = id,
            taskId = taskId?.let(::TaskId),
            title = title,
            startEpochMillis = start,
            endEpochMillis = end,
            pinned = pinned,
            // PRD §8: reconstruct [pins] from the persisted [pinned] (the only wired pin).
            pins = PanelPins(existence = pinned),
            auto = auto,
            layoutWeight = layoutWeight,
            chore = chore,
            checked = checked,
            screenBreak = screenBreak,
            sleep = sleep,
            noScreen = noScreen,
            inactivity = inactivity,
        )

    private fun PersistedTaskTree.toEntry(): TaskTreeEntry =
        TaskTreeEntry(
            id = TaskTreeId(id),
            title = title,
            tree = tree.toSnapshot(),
            expanded = expanded.map(::CellId).toSet(),
            dateMillis = date,
        )

    private fun PersistedTaskTreeState.toTaskTreeState(): TaskTreeStateSnapshot =
        TaskTreeStateSnapshot(
            trees = trees.map { it.toEntry() },
            activeId = activeId?.let(::TaskTreeId),
            nextCounter = nextCounter,
            tree = tree.toSnapshot(),
            expanded = expanded.map(::CellId).toSet(),
        )

    private fun PersistedTreeSnapshot.toSnapshot(): TreeSnapshot {
        val tasks =
            tasks.associate { p ->
                TaskId(p.id) to
                    Task(
                        id = TaskId(p.id),
                        title = p.title,
                        childTaskIds = p.childTaskIds.map(::TaskId),
                        occurrences = p.occurrences.map(::CellId),
                        childListId = p.childListId?.let(::CellListId),
                        minimumMinutes = p.minimumMinutes,
                        record = p.record.map { TaskTimeRange(it.start, it.end) },
                        scheduleUnit = p.scheduleUnit.map { ScheduleUnitEntry(it.title, it.spanMinutes) },
                        text = p.text,
                        resilience = decodeResilience(p),
                    )
            }
        val cells =
            cells.associate { p ->
                CellId(p.id) to
                    Cell(
                        id = CellId(p.id),
                        parentListId = CellListId(p.parentListId),
                        taskId = p.taskId?.let(::TaskId),
                        priorityWeights = p.priorityWeights,
                    )
            }
        val lists =
            lists.associate { p ->
                CellListId(p.id) to
                    CellList(
                        id = CellListId(p.id),
                        parentCellId = p.parentCellId?.let(::CellId),
                        cellIds = p.cellIds.map(::CellId),
                        weightColumns = p.weightColumns,
                    )
            }
        return TreeSnapshot(
            cells = cells,
            lists = lists,
            tasks = tasks,
            titleToTaskIds = SchedulerDomain.buildTitleIndex(tasks),
            nextTaskCounter = nextTaskCounter,
            nextCellCounter =
                nextCellCounter
                    ?: SchedulerState.deriveNextCellCounter(cells.keys),
        )
    }

    private fun PersistedEditSession.toSession(): SchedulerEditSession =
        SchedulerEditSession(
            cellId = CellId(cellId),
            renderVia = renderVia?.let(::CellId),
            draftText = draftText,
            mode = CellEditMode.valueOf(mode),
            selectedAssignTaskId = selectedAssignTaskId?.let(::TaskId),
            newTaskDraftId = newTaskDraftId?.let(::TaskId),
            treeBefore = treeBefore.toSnapshot(),
            renameTreeBefore = renameTreeBefore?.toSnapshot(),
        )
}

@Serializable
private data class PersistedState(
    val version: Int = 1,
    val rootListId: String,
    val lists: List<PersistedList>,
    val cells: List<PersistedCell>,
    val tasks: List<PersistedTask>,
    val expanded: List<String> = emptyList(),
    // The named alternative task trees and which one the live tree is; missing values decode to "no named
    // trees / unnamed live tree" (payloads written before the task-tree selector existed).
    val taskTrees: List<PersistedTaskTree> = emptyList(),
    val activeTaskTreeId: String? = null,
    val nextTaskTreeCounter: Int = 0,
    val selectionMain: String? = null,
    val selectionSelected: List<String> = emptyList(),
    val selectionRangeAnchor: String? = null,
    val selectionRenderVia: String? = null,
    val nextTaskCounter: Int = 0,
    val nextCellCounter: Int? = null,
    val editSession: PersistedEditSession? = null,
    // PRD §8/§9: defaults keep payloads written before task panels existed loadable. (A pre-1.2.0
    // payload's `scheduled`/`manualEntries` fields are ignored on load; the next refresh refills.)
    val panels: List<PersistedPanel> = emptyList(),
    val nextPanelCounter: Int = 0,
    // PRD §7: default on keeps auto-scheduling running for payloads written before the switch existed.
    val automaticSchedule: Boolean = true,
    // PRD §14: a missing chores list decodes to empty (payloads written before the chores manager existed).
    val chores: List<PersistedChoreEntry> = emptyList(),
    // PRD §18: a missing alarm list decodes to empty (payloads written before the Alarms window existed).
    val alarms: List<PersistedAlarm> = emptyList(),
    // PRD §5: the relative-priority window's pinned cells; a missing list decodes to no pins (payloads
    // written before the window existed).
    val relativePriorityPins: List<PersistedRelativePriorityPins> = emptyList(),
    // PRD §15: screen breaks are hidden by default, so payloads written before the display toggle existed
    // (and any that omit the field) decode with the switch off. Migration: DBs written under the old name
    // (the legacy "side tasks") stored this as `showSideTasks`; [JsonNames] lets those still decode into this
    // field while new writes use `showScreenBreaks`.
    @JsonNames("showSideTasks")
    val showScreenBreaks: Boolean = false,
    // PRD §14: default on keeps reminders visible for payloads written before the display toggle existed.
    val showReminders: Boolean = true,
    // PRD §15: the 20s look-away voice cue; default on (payloads written before the toggle existed get the voice).
    val lookAwayVoiceEnabled: Boolean = true,
    // PRD §4 Default sub-tree: the template grafted under a newly created task, and whether the policy is
    // applied. Missing values decode to "empty template, switch off" — payloads written before the window
    // existed, for which nothing was ever grafted.
    //
    // The template is a real tree, stored exactly as a task tree is. [defaultSubtree] is the **pre-1.6.0**
    // shape — a tree of titles — kept because a payload outlives a rebuild: it is still read (and migrated
    // on load, see `migrateDefaultSubtreeNodes`) and never written again. A payload holding
    // [defaultSubtreeTree] ignores it.
    val defaultSubtreeTree: PersistedTreeSnapshot? = null,
    val defaultSubtreeExpanded: List<String> = emptyList(),
    val defaultSubtreeBoundCells: List<String> = emptyList(),
    val defaultSubtree: List<PersistedDefaultSubtreeNode> = emptyList(),
    val defaultSubtreeEnabled: Boolean = false,
    // PRD §13 deep copy: the account-wide maximum depth. A missing value decodes to the depth the window
    // used to open on, which is exactly what a payload written before the setting existed behaved as.
    val deepCopyMaxDepth: Int = SchedulerDomain.DEEP_COPY_DEFAULT_DEPTH,
    // PRD §13 deep copy: the account's three "what does a copy carry" switches. Missing values decode to
    // ON — a payload written before the switches existed came from a build that always carried all three.
    val copyIncludeIds: Boolean = true,
    val copyPriorityTables: Boolean = true,
    val copyIncludeText: Boolean = true,
    // PRD §7 Keyboard shortcuts: the account's system-wide chord overrides. A missing value decodes to none,
    // i.e. every chord is the one it ships with — which is what every payload written before the window could
    // rebind anything behaved as.
    val shortcutBindings: List<PersistedShortcutBinding> = emptyList(),
    // `side-dev/README.md` § Restrictive Period: the account's own period kinds; absent ⇒ none.
    val periodKinds: List<String> = emptyList(),
    // PRD §7: the focused window; a missing value decodes to the task tree (payloads written before window
    // focus was persisted).
    val focusedWindow: String = "Tree",
    // PRD §5/§6: the Undo/Redo history, now persisted so debug-time changes can be reverted at restart.
    // A missing value decodes to empty (payloads written before history was persisted start fresh).
    val histories: PersistedHistories? = null,
    // The user's sleep schedule; a missing value decodes to null (payloads written before the sleep
    // window existed) and the ViewModel then seeds the default.
    val sleep: PersistedSleep? = null,
    // Sleep/Work toggle: the next wake instant while the user is deliberately away, or null when working. A
    // missing value decodes to null (payloads written before the toggle existed decode as "working").
    val sleepingUntilMillis: Long? = null,
    // The instant the current sleep session began (see [SchedulerState.sleepingSinceMillis]); a missing value
    // decodes to null (payloads written before past-sleep materialization existed decode with no live band).
    val sleepingSinceMillis: Long? = null,
    // PRD §7 "Switch task" (see [org.example.project.scheduler.model.ForcedTaskSwitch]): the task the user
    // refused and the instant they refused it. Missing values decode to no outstanding refusal — which is
    // what every payload written before the button existed says.
    val forcedSwitchTaskId: String? = null,
    val forcedSwitchAtMillis: Long? = null,
    // PRD §13 "start this task now" (see [org.example.project.scheduler.model.ForcedTaskStart]): the task the
    // user asked for and the instant they asked for it. Missing values decode to no outstanding request —
    // which is what every payload written before the menu entry existed says.
    val forcedStartTaskId: String? = null,
    val forcedStartAtMillis: Long? = null,
    // The local-only diagnostic notification log; a missing value decodes to empty (payloads written before
    // the History Manager's Notifications column existed). Local-only — stripped from the sync fingerprint.
    val notificationLog: List<PersistedNotificationEntry> = emptyList(),
    // The local-only Supabase-usage diagnostic log; a missing value decodes to empty (payloads written before
    // the History Manager's "Supabase usage" column existed). Local-only — stripped from the sync fingerprint.
    val supabaseUsageLog: List<PersistedSupabaseUsageEntry> = emptyList(),
)

/**
 * PRD §4 Default sub-tree, **pre-1.6.0 only**: one node of the template back when it was a tree of titles.
 * Read so an older payload still loads (and is migrated by `migrateDefaultSubtreeNodes`); never written.
 * Recursive, and every field carries a default so a payload written by an older shape decodes cleanly.
 * `taskId == null` was the node's switch in its **on** position ("new id").
 */
@Serializable
private data class PersistedDefaultSubtreeNode(
    val id: String = "",
    val title: String = "",
    val taskId: String? = null,
    val children: List<PersistedDefaultSubtreeNode> = emptyList(),
)

@Serializable
private data class PersistedNotificationEntry(
    val timeMillis: Long,
    val title: String,
    val message: String,
)

@Serializable
private data class PersistedSupabaseUsageEntry(
    val timeMillis: Long,
    val resource: String,
    val operation: String,
    val requestBytes: Long,
    val responseBytes: Long,
    val status: Int,
)

/**
 * PRD §18 Alarms: an ISO day number (1 = Monday … 7 = Sunday) back to its [DayOfWeek], or null when the
 * payload holds something that is not one — a corrupt/hand-edited DB drops that day rather than failing the
 * whole decode.
 */
private fun dayOfWeekOrNull(isoDayNumber: Int): DayOfWeek? =
    DayOfWeek.entries.firstOrNull { it.isoDayNumber == isoDayNumber }

/**
 * PRD §7 Keyboard shortcuts: the stored override rows, back as the map
 * [org.example.project.scheduler.state.SchedulerState.shortcutBindings] holds.
 *
 * **Decode heals** (CLAUDE.md): a row this build cannot name — a shortcut or a key that has since gone, a
 * duplicate, or a chord [GlobalShortcutBindings] would refuse today because the rules tightened — is dropped,
 * and that shortcut falls back to the chord it ships with. The alternative is an account whose claim silently
 * holds a chord the app would never let the user set, which is undiagnosable from the window.
 */
/**
 * PRD §7 Keyboard shortcuts: the override map as stored rows, **sorted by shortcut** so one binding table has
 * exactly one encoding — the sync fingerprint is this payload byte for byte, and a map's iteration order must
 * never be able to look like an edit.
 */
private fun Map<GlobalShortcut, ShortcutBinding>.toPersistedRows(): List<PersistedShortcutBinding> =
    entries.sortedBy { it.key.name }
        .map { (shortcut, b) -> PersistedShortcutBinding(shortcut.name, b.key.name, b.ctrl, b.shift, b.alt) }

private fun List<PersistedShortcutBinding>.toShortcutBindings(): Map<GlobalShortcut, ShortcutBinding> {
    val resolved = LinkedHashMap<GlobalShortcut, ShortcutBinding>()
    for (row in this) {
        val shortcut = GlobalShortcut.entries.firstOrNull { it.name == row.shortcut } ?: continue
        val key = ShortcutKey.entries.firstOrNull { it.name == row.key } ?: continue
        if (shortcut in resolved) continue
        val binding = ShortcutBinding(key, ctrl = row.ctrl, shift = row.shift, alt = row.alt)
        if (GlobalShortcutBindings.rejection(resolved, shortcut, binding) != null) continue
        resolved[shortcut] = binding
    }
    return resolved
}

/**
 * PRD §7 Keyboard shortcuts: one system-wide chord the user has rebound (defaults are simply absent).
 *
 * Both enums are stored by **entry name**, which is why [org.example.project.scheduler.platform.ShortcutKey]
 * is a closed set that must not be renamed. Every field carries a default so a hand-edited or older payload
 * decodes rather than failing — a row that decodes to something this build cannot name is then dropped by
 * [toShortcutBindings], leaving that shortcut on the chord it ships with.
 */
@Serializable
private data class PersistedShortcutBinding(
    val shortcut: String = "",
    val key: String = "",
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/**
 * PRD §5 the relative-priority window: the cells pinned for one (task, ancestor) pair. Every field carries
 * a default so a payload written before the window existed decodes cleanly (as no pins at all).
 */
@Serializable
private data class PersistedRelativePriorityPins(
    val taskId: String = "",
    val relativeTo: String = "",
    val cellIds: List<String> = emptyList(),
)

/**
 * PRD §18 Alarms: one persisted alarm. Every field carries a default so a payload written by an older shape
 * (or by a build before a field existed) decodes cleanly.
 */
@Serializable
private data class PersistedAlarm(
    val id: String = "",
    val label: String = "",
    val timeOfDayMinutes: Int = 0,
    val soundSeconds: Int = AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS,
    val vibrate: Boolean = true,
    /**
     * PRD §18: the days the alarm is triggered on, as ISO day numbers (1 = Monday … 7 = Sunday). **null**
     * means every day — which is both the default and what a payload written before the field existed says,
     * so an old DB's alarms keep ringing daily exactly as they did. An explicit empty list is a set the user
     * emptied and is not the same thing (it never rings).
     */
    val days: List<Int>? = null,
    // Migration: DBs written before the days existed stored the repeat flag as `repeatDaily` (it then meant
    // "every day"); [JsonNames] lets those still decode into this field while new writes use `repeats`.
    @JsonNames("repeatDaily")
    val repeats: Boolean = true,
    val enabled: Boolean = true,
)

@Serializable
private data class PersistedSleep(
    val wakeMinutes: Int = 450,
    val goalWakeMinutes: Int = 450,
    val sleepDurationMinutes: Int = 510,
    val anchorEpochDay: Long? = null,
)

@Serializable
private data class PersistedHistories(
    val edit: PersistedHistory = PersistedHistory(),
    val selection: PersistedHistory = PersistedHistory(),
    val calendar: PersistedHistory = PersistedHistory(),
    val main: PersistedHistory = PersistedHistory(),
    val windowNav: PersistedHistory = PersistedHistory(),
)

@Serializable
private data class PersistedHistory(
    val pointer: Int = -1,
    val units: List<PersistedHistoryUnit> = emptyList(),
)

@Serializable
private data class PersistedHistoryUnit(
    val timeMillis: Long,
    val chronoId: Long = 0,
    val debugTainted: Boolean = false,
    val delta: PersistedDelta,
)

@Serializable
private data class PersistedSelection(
    val main: String? = null,
    val selected: List<String> = emptyList(),
    val rangeAnchor: String? = null,
    val renderVia: String? = null,
)

/**
 * Serializable mirror of the in-memory [Delta] sealed hierarchy. Closed polymorphism — kotlinx writes a
 * `type` discriminator from each variant's [SerialName], so units round-trip without a serializers module.
 */
@Serializable
private sealed interface PersistedDelta {
    @Serializable
    @SerialName("treeMutation")
    data class TreeMutation(
        val before: PersistedTreeSnapshot,
        val after: PersistedTreeSnapshot,
        val label: String,
    ) : PersistedDelta

    @Serializable
    @SerialName("emptyCells")
    data class EmptyCells(
        val treeBefore: PersistedTreeSnapshot,
        val treeAfter: PersistedTreeSnapshot,
        val selectionBefore: PersistedSelection,
        val selectionAfter: PersistedSelection,
    ) : PersistedDelta

    @Serializable
    @SerialName("setSelection")
    data class SetSelection(val before: PersistedSelection, val after: PersistedSelection) : PersistedDelta

    @Serializable
    @SerialName("focus")
    data class Focus(val before: String, val after: String) : PersistedDelta

    @Serializable
    @SerialName("panels")
    data class Panels(
        val before: List<PersistedPanel>,
        val after: List<PersistedPanel>,
        val label: String,
    ) : PersistedDelta

    @Serializable
    @SerialName("toggleExpand")
    data class ToggleExpand(val cellId: String) : PersistedDelta

    /**
     * PRD §4 Find & replace: the whole expansion set on both sides of revealing a search hit. New in
     * 1.6.0 — a history written by an older build simply has none of these, and every older unit still
     * decodes, since this is a new [PersistedDelta] subtype and not a field on an existing one.
     */
    @Serializable
    @SerialName("setExpanded")
    data class SetExpanded(val before: List<String>, val after: List<String>) : PersistedDelta

    @Serializable
    @SerialName("record")
    data class Record(
        val before: Map<String, List<PersistedTimeRange>>,
        val after: Map<String, List<PersistedTimeRange>>,
    ) : PersistedDelta

    @Serializable
    @SerialName("sleep")
    data class Sleep(val before: PersistedSleep? = null, val after: PersistedSleep) : PersistedDelta

    @Serializable
    @SerialName("defaultSubtree")
    data class DefaultSubtreeUnit(
        val before: PersistedDefaultSubtree,
        val after: PersistedDefaultSubtree,
        val label: String,
    ) : PersistedDelta

    @Serializable
    @SerialName("taskTrees")
    data class TaskTrees(
        val before: PersistedTaskTreeState,
        val after: PersistedTaskTreeState,
        val label: String,
    ) : PersistedDelta

    /**
     * PRD §7 Keyboard shortcuts: one rebinding of a system-wide chord. New in 1.6.0 — a history written by
     * an older build simply holds none of these, and every older unit still decodes, since this is a new
     * [PersistedDelta] subtype and not a field on an existing one.
     */
    @Serializable
    @SerialName("shortcutBindings")
    data class ShortcutBindings(
        val before: List<PersistedShortcutBinding>,
        val after: List<PersistedShortcutBinding>,
    ) : PersistedDelta

    @Serializable
    @SerialName("noOp")
    data object NoOp : PersistedDelta
}

@Serializable
private data class PersistedChoreEntry(
    val title: String,
    val spanDays: Double,
    // PRD §14: a missing time-of-day decodes to midnight (payloads written before the field existed).
    val timeOfDayMinutes: Int = 0,
    // PRD §14: the raw "Days" formula text (e.g. "31/21") so the field round-trips; a missing value decodes
    // to "" (payloads written before formulas existed), and the UI then shows the numeric [spanDays].
    val daysFormula: String = "",
    // PRD §14: the recurrence unit chosen in the dropdown; a missing value decodes to days (payloads written
    // before the unit dropdown existed), for which [spanDays] is already a plain day cadence.
    val recurrenceUnit: ChoreRecurrenceUnit = ChoreRecurrenceUnit.Days,
    // PRD §14: the reminder's stable id; a missing value decodes to "" and is filled by assignReminderIds.
    val id: String = "",
    // PRD §14 "constrained in": the id of the reminder this one is constrained to; "" when unconstrained
    // (and for payloads written before the feature existed).
    val constrainedToReminderId: String = "",
)

@Serializable
private data class PersistedPanel(
    val id: String,
    val taskId: String? = null,
    val title: String,
    val start: Long,
    val end: Long,
    val pinned: Boolean = false,
    val auto: Boolean = false,
    val layoutWeight: Double = 1.0,
    // PRD §14: a missing chore flag decodes to false (payloads written before chore panels existed).
    val chore: Boolean = false,
    // PRD §14: a missing checked flag decodes to false (payloads written before reminders were checkable).
    val checked: Boolean = false,
    // PRD §15: a missing screenBreak flag decodes to false (payloads written before screen breaks existed).
    // Migration: DBs written under the old name stored this panel flag as `sideTask`; [JsonNames] lets those
    // still decode into this field while new writes use `screenBreak`.
    @JsonNames("sideTask")
    val screenBreak: Boolean = false,
    // A missing sleep flag decodes to false (payloads written before the sleep window existed).
    val sleep: Boolean = false,
    // PRD §8/§9: missing flags decode to false (payloads written before no-screen / inactivity panels existed).
    val noScreen: Boolean = false,
    val inactivity: Boolean = false,
)

@Serializable
private data class PersistedEditSession(
    val cellId: String,
    val renderVia: String? = null,
    val draftText: String,
    val mode: String,
    val selectedAssignTaskId: String? = null,
    val newTaskDraftId: String? = null,
    val treeBefore: PersistedTreeSnapshot,
    val renameTreeBefore: PersistedTreeSnapshot? = null,
)

/** PRD §4 the default sub-tree template on one side of a history unit (see [DefaultSubtreeTemplate]). */
@Serializable
private data class PersistedDefaultSubtree(
    val tree: PersistedTreeSnapshot,
    val expanded: List<String> = emptyList(),
    val boundCells: List<String> = emptyList(),
)

/** One named alternative task tree (see [org.example.project.scheduler.state.TaskTreeEntry]). */
@Serializable
private data class PersistedTaskTree(
    val id: String,
    val title: String,
    val tree: PersistedTreeSnapshot,
    val expanded: List<String> = emptyList(),
    // The tree's position on the "All task trees" timeline. Absent (the default) = not on the timeline,
    // which is what every payload written before the timeline existed decodes to.
    val date: Long? = null,
)

/** The whole task-tree state on one side of a task-tree History Unit. */
@Serializable
private data class PersistedTaskTreeState(
    val trees: List<PersistedTaskTree> = emptyList(),
    val activeId: String? = null,
    val nextCounter: Int = 0,
    val tree: PersistedTreeSnapshot,
    val expanded: List<String> = emptyList(),
)

@Serializable
private data class PersistedTreeSnapshot(
    val lists: List<PersistedList>,
    val cells: List<PersistedCell>,
    val tasks: List<PersistedTask>,
    val nextTaskCounter: Int,
    val nextCellCounter: Int? = null,
)

@Serializable
private data class PersistedList(
    val id: String,
    val parentCellId: String?,
    val cellIds: List<String>,
    val weightColumns: List<Double> = listOf(1.0),
)

@Serializable
private data class PersistedCell(
    val id: String,
    val parentListId: String,
    val taskId: String?,
    val priorityWeights: List<Double> = listOf(1.0),
)

/**
 * `side-dev/README.md` § *Restrictive Period*: the task's resilience map as loaded — its own if the payload
 * has one, else **migrated from the pre-resilience screen switches**.
 *
 * The migration is the identity the new model is built on: an on-screen task is exactly one forbidden inside
 * a "no on-screen task" period, so `onScreen = true` becomes a `0` against [PeriodKinds.NO_SCREEN] and
 * `onScreen = false` becomes no override at all (the default `1`). `doableDuringBreak` has no image here and
 * is deliberately dropped: the three dynamic periods are "no task allowed" end to end under the README, so
 * there is nothing left for the switch to open.
 *
 * Values are healed into `[0, 1]` on the way in ([PeriodKinds.clamp]) and an override equal to its kind's own
 * default is dropped, so a hand-edited or older payload cannot leave a task carrying a resilience the current
 * invariants forbid.
 */
private fun decodeResilience(p: PersistedTask): Map<String, Double> {
    val raw = p.resilience ?: return if (p.onScreen) mapOf(PeriodKinds.NO_SCREEN to 0.0) else emptyMap()
    val out = HashMap<String, Double>(raw.size)
    for ((kindRaw, value) in raw) {
        val kind = PeriodKinds.normalize(kindRaw)
        if (kind.isEmpty()) continue
        val clamped = PeriodKinds.clamp(value)
        if (clamped == PeriodKinds.defaultResilience(kind)) continue
        out[kind] = clamped
    }
    return out
}

@Serializable
private data class PersistedTask(
    val id: String,
    val title: String,
    val childTaskIds: List<String> = emptyList(),
    val occurrences: List<String> = emptyList(),
    val childListId: String? = null,
    // PRD §10 / §8: defaults keep payloads written before these fields existed loadable. A missing
    // minimum time decodes to the §10 default (45 min), matching a freshly created task.
    val minimumMinutes: Int = DEFAULT_MINIMUM_MINUTES,
    val record: List<PersistedTimeRange> = emptyList(),
    // PRD §13: a missing schedule unit decodes to empty (a task with no schedule unit).
    val scheduleUnit: List<PersistedScheduleUnitEntry> = emptyList(),
    // PRD §13 Edit window: a missing text document decodes to empty (a task with no notes).
    val text: String = "",
    // PRD §8 screen switches — the PRE-RESILIENCE shape, kept for one reason: a payload written by an older
    // build carries them and nothing else, and `resilience` below is migrated FROM them on load. Still
    // written, so an older build reading a newer payload keeps scheduling on-screen tasks correctly.
    val onScreen: Boolean = true,
    val doableDuringBreak: Boolean = false,
    // `side-dev/README.md` § Restrictive Period: the task's resilience per period KIND, overrides only.
    // Absent (every payload written before kinds existed) ⇒ migrated from [onScreen] on load.
    val resilience: Map<String, Double>? = null,
)

@Serializable
private data class PersistedScheduleUnitEntry(
    val title: String,
    val spanMinutes: Int,
)

@Serializable
private data class PersistedTimeRange(
    val start: Long,
    val end: Long,
)
