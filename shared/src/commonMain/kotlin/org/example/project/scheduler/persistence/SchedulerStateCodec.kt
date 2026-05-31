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
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.SchedulerEditSession
import org.example.project.scheduler.state.SchedulerHistory
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
                    PersistedList(it.id.value, it.parentCellId?.value, it.cellIds.map(CellId::value))
                },
            cells =
                cells.values.map {
                    PersistedCell(it.id.value, it.parentListId.value, it.taskId?.value)
                },
            tasks =
                tasks.values.map {
                    PersistedTask(
                        id = it.id.value,
                        title = it.title,
                        childTaskIds = it.childTaskIds.map(TaskId::value),
                        occurrences = it.occurrences.map(CellId::value),
                        childListId = it.childListId?.value,
                    )
                },
            expanded = expanded.map(CellId::value),
            selectionMain = selection.main?.value,
            selectionSelected = selection.selected.map(CellId::value),
            selectionRangeAnchor = selection.rangeAnchor?.value,
            nextTaskCounter = nextTaskCounter,
            nextCellCounter = nextCellCounter,
            editSession = editSession?.toPersisted(),
        )

    private fun SchedulerEditSession.toPersisted(): PersistedEditSession =
        PersistedEditSession(
            cellId = cellId.value,
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
                    PersistedList(it.id.value, it.parentCellId?.value, it.cellIds.map(CellId::value))
                },
            cells =
                cells.values.map {
                    PersistedCell(it.id.value, it.parentListId.value, it.taskId?.value)
                },
            tasks =
                tasks.values.map {
                    PersistedTask(
                        id = it.id.value,
                        title = it.title,
                        childTaskIds = it.childTaskIds.map(TaskId::value),
                        occurrences = it.occurrences.map(CellId::value),
                        childListId = it.childListId?.value,
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
                    )
            }
        val cells =
            cells.associate { p ->
                CellId(p.id) to
                    Cell(
                        id = CellId(p.id),
                        parentListId = CellListId(p.parentListId),
                        taskId = p.taskId?.let(::TaskId),
                    )
            }
        val lists =
            lists.associate { p ->
                CellListId(p.id) to
                    CellList(
                        id = CellListId(p.id),
                        parentCellId = p.parentCellId?.let(::CellId),
                        cellIds = p.cellIds.map(::CellId),
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
                ),
            history = SchedulerHistory(),
            nextTaskCounter = nextTaskCounter,
            nextCellCounter =
                nextCellCounter
                    ?: SchedulerState.deriveNextCellCounter(cells.keys),
            editSession = editSession?.toSession(),
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
                    )
            }
        val cells =
            cells.associate { p ->
                CellId(p.id) to
                    Cell(
                        id = CellId(p.id),
                        parentListId = CellListId(p.parentListId),
                        taskId = p.taskId?.let(::TaskId),
                    )
            }
        val lists =
            lists.associate { p ->
                CellListId(p.id) to
                    CellList(
                        id = CellListId(p.id),
                        parentCellId = p.parentCellId?.let(::CellId),
                        cellIds = p.cellIds.map(::CellId),
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
    val nextTaskCounter: Int = 0,
    val nextCellCounter: Int? = null,
    val editSession: PersistedEditSession? = null,
)

@Serializable
private data class PersistedEditSession(
    val cellId: String,
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
)

@Serializable
private data class PersistedCell(
    val id: String,
    val parentListId: String,
    val taskId: String?,
)

@Serializable
private data class PersistedTask(
    val id: String,
    val title: String,
    val childTaskIds: List<String> = emptyList(),
    val occurrences: List<String> = emptyList(),
    val childListId: String? = null,
)
