package org.example.project.scheduler.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.ChoreRecurrenceUnit
import org.example.project.scheduler.model.DEFAULT_MINIMUM_MINUTES
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.Delta
import org.example.project.scheduler.state.EmptyCellsDelta
import org.example.project.scheduler.state.FocusDelta
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.NoOpDelta
import org.example.project.scheduler.state.PanelDelta
import org.example.project.scheduler.state.RecordDelta
import org.example.project.scheduler.state.SchedulerEditSession
import org.example.project.scheduler.state.SchedulerHistories
import org.example.project.scheduler.state.SchedulerHistory
import org.example.project.scheduler.state.SchedulerSelection
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.SetSelectionDelta
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
                    )
                },
            expanded = expanded.map(CellId::value),
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
                        sideTask = it.sideTask,
                        sleep = it.sleep,
                    )
                },
            nextPanelCounter = nextPanelCounter,
            automaticSchedule = automaticSchedule,
            chores = chores.map { PersistedChoreEntry(it.title, it.spanDays, it.timeOfDayMinutes, it.daysFormula, it.recurrenceUnit, it.id, it.constrainedToReminderId) },
            showSideTasks = showSideTasks,
            showReminders = showReminders,
            lookAwayVoiceEnabled = lookAwayVoiceEnabled,
            focusedWindow = focusedWindow.name,
            histories = histories.toPersisted(),
            sleep = sleep?.let { PersistedSleep(it.wakeMinutes, it.goalWakeMinutes, it.sleepDurationMinutes, it.anchorEpochDay) },
        )

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
            is RecordDelta ->
                PersistedDelta.Record(
                    before.mapKeys { it.key.value }.mapValues { e -> e.value.map { PersistedTimeRange(it.startEpochMillis, it.endEpochMillis) } },
                    after.mapKeys { it.key.value }.mapValues { e -> e.value.map { PersistedTimeRange(it.startEpochMillis, it.endEpochMillis) } },
                )
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
            sideTask = sideTask,
            sleep = sleep,
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
                    )
                },
            nextTaskCounter = nextTaskCounter,
            nextCellCounter = nextCellCounter,
        )

    private fun PersistedState.toState(): SchedulerState {
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
            selection =
                SchedulerSelection(
                    main = selectionMain?.let(::CellId),
                    selected = selectionSelected.map(::CellId).toSet(),
                    rangeAnchor = selectionRangeAnchor?.let(::CellId),
                    renderVia = selectionRenderVia?.let(::CellId),
                ),
            nextTaskCounter = nextTaskCounter,
            nextCellCounter =
                nextCellCounter
                    ?: SchedulerState.deriveNextCellCounter(cells.keys),
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
                        auto = it.auto,
                        layoutWeight = it.layoutWeight,
                        chore = it.chore,
                        checked = it.checked,
                        sideTask = it.sideTask,
                        sleep = it.sleep,
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
            showSideTasks = showSideTasks,
            showReminders = showReminders,
            lookAwayVoiceEnabled = lookAwayVoiceEnabled,
            focusedWindow = runCatching { AppWindow.valueOf(focusedWindow) }.getOrDefault(AppWindow.Tree),
            histories = histories?.toHistories() ?: SchedulerHistories(),
            sleep = sleep?.let { SleepSchedule(it.wakeMinutes, it.goalWakeMinutes, it.sleepDurationMinutes, it.anchorEpochDay) },
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
            is PersistedDelta.Record ->
                RecordDelta(
                    before.mapKeys { TaskId(it.key) }.mapValues { e -> e.value.map { TaskTimeRange(it.start, it.end) } },
                    after.mapKeys { TaskId(it.key) }.mapValues { e -> e.value.map { TaskTimeRange(it.start, it.end) } },
                )
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
            auto = auto,
            layoutWeight = layoutWeight,
            chore = chore,
            checked = checked,
            sideTask = sideTask,
            sleep = sleep,
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
    // PRD §15: side tasks are hidden by default, so payloads written before the display toggle existed
    // (and any that omit the field) decode with the switch off.
    val showSideTasks: Boolean = false,
    // PRD §14: default on keeps reminders visible for payloads written before the display toggle existed.
    val showReminders: Boolean = true,
    // PRD §15: the 20s look-away voice cue; default on (payloads written before the toggle existed get the voice).
    val lookAwayVoiceEnabled: Boolean = true,
    // PRD §7: the focused window; a missing value decodes to the task tree (payloads written before window
    // focus was persisted).
    val focusedWindow: String = "Tree",
    // PRD §5/§6: the Undo/Redo history, now persisted so debug-time changes can be reverted at restart.
    // A missing value decodes to empty (payloads written before history was persisted start fresh).
    val histories: PersistedHistories? = null,
    // The user's sleep schedule; a missing value decodes to null (payloads written before the sleep
    // window existed) and the ViewModel then seeds the default.
    val sleep: PersistedSleep? = null,
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

    @Serializable
    @SerialName("record")
    data class Record(
        val before: Map<String, List<PersistedTimeRange>>,
        val after: Map<String, List<PersistedTimeRange>>,
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
    // PRD §15: a missing sideTask flag decodes to false (payloads written before side tasks existed).
    val sideTask: Boolean = false,
    // A missing sleep flag decodes to false (payloads written before the sleep window existed).
    val sleep: Boolean = false,
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
    // "See text": a missing text document decodes to empty (a task with no notes).
    val text: String = "",
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
