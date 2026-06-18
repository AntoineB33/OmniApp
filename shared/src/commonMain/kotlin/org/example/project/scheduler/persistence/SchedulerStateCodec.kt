package org.example.project.scheduler.persistence

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
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.SchedulerEditSession
import org.example.project.scheduler.state.SchedulerSelection
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.TreeSnapshot

/**
 * Serializes the durable part of [SchedulerState] (the Task Tree, occurrences, expansion,
 * and selection) to/from JSON for [SchedulerStore].
 *
 * The Undo/Redo history is intentionally not persisted: it holds polymorphic in-memory
 * [org.example.project.scheduler.state.Delta]s and, per PRD §5, a reloaded session starts
 * a fresh history. An in-flight [SchedulerEditSession] is persisted so a crash mid-edit can
 * be detected; [org.example.project.scheduler.ui.TaskSchedulerViewModel] cancels it on load.
 * The `titleToTaskIds` index is derived, so it is rebuilt on load rather than stored.
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
                    )
                },
            nextPanelCounter = nextPanelCounter,
            automaticSchedule = automaticSchedule,
            chores = chores.map { PersistedChoreEntry(it.title, it.spanDays, it.timeOfDayMinutes, it.daysFormula, it.recurrenceUnit, it.id) },
            showSideTasks = showSideTasks,
            showReminders = showReminders,
            lookAwayVoiceEnabled = lookAwayVoiceEnabled,
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
                    )
                },
            ),
            showSideTasks = showSideTasks,
            showReminders = showReminders,
            lookAwayVoiceEnabled = lookAwayVoiceEnabled,
        )
    }

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
)

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
