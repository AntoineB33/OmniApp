package org.example.project.scheduler.state

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId

object SchedulerReducer {
    fun reduce(state: SchedulerState, intent: SchedulerIntent): SchedulerState {
        return when (intent) {
            is SchedulerIntent.ClickCell -> reduceClick(state, intent)
            is SchedulerIntent.DragSelectCells -> reduceDragSelect(state, intent)
            is SchedulerIntent.MoveSelectedCells -> reduceMoveSelected(state, intent)
            SchedulerIntent.ClearSelection -> reduceClearSelection(state)
            SchedulerIntent.EmptySelectedCells -> reduceEmptySelected(state)
            is SchedulerIntent.ExitEdit -> reduceExitEdit(state, intent.navigation)
            is SchedulerIntent.ToggleExpand -> commitDelta(state, ToggleExpandDelta(intent.cellId))
            is SchedulerIntent.SetCellTitle -> commitDelta(state, setCellTitleDelta(state, intent.cellId, intent.title))
            is SchedulerIntent.AssignTaskId -> commitDelta(state, assignTaskIdDelta(state, intent.cellId, intent.taskId))
            is SchedulerIntent.BeginEdit -> reduceBeginEdit(state, intent)
            is SchedulerIntent.UpdateEditText -> reduceUpdateEditText(state, intent.text)
            is SchedulerIntent.SetEditMode -> reduceSetEditMode(state, intent.mode)
            is SchedulerIntent.PickTaskFromMenu -> reducePickTaskFromMenu(state, intent.taskId)
            SchedulerIntent.SelectCreateAssignTask -> reduceSelectCreateAssignTask(state)
            is SchedulerIntent.PickTitleSuggestion -> reducePickTitleSuggestion(state, intent.title)
            SchedulerIntent.CancelEdit -> reduceCancelEdit(state)
            is SchedulerIntent.NavigateSelection -> reduceNavigateSelection(state, intent.direction, intent.shift)
            is SchedulerIntent.CycleMainSelection -> reduceCycleMainSelection(state, intent.forward)
            SchedulerIntent.SelectFirstChild -> reduceSelectFirstChild(state)
            SchedulerIntent.CopySelection -> reduceCopySelection(state)
            is SchedulerIntent.PasteTitles -> reducePasteTitles(state, intent.titles)
            SchedulerIntent.Undo -> undo(state)
            SchedulerIntent.Redo -> redo(state)
        }
    }

    private fun reduceBeginEdit(state: SchedulerState, intent: SchedulerIntent.BeginEdit): SchedulerState {
        if (!SchedulerDomain.isSelectableCell(state, intent.cellId)) return state
        val cell = state.cells[intent.cellId] ?: return state
        val currentTitle = cell.taskId?.let { state.tasks[it]?.title }.orEmpty()
        val draft = intent.initialText ?: currentTitle
        val typingToEdit = intent.initialText != null
        val withSession =
            state.copy(
                editSession =
                    SchedulerEditSession(
                        cellId = intent.cellId,
                        draftText = draft,
                        selectedAssignTaskId = if (typingToEdit) null else cell.taskId,
                        newTaskDraftId = null,
                        treeBefore = state.captureTree(),
                    ),
                selection = SchedulerSelection(main = intent.cellId, selected = emptySet()),
            )
        return if (typingToEdit) {
            commitEditText(withSession, draft)
        } else {
            withSession
        }
    }

    private fun reduceUpdateEditText(state: SchedulerState, text: String): SchedulerState {
        val session = state.editSession ?: return state
        if (text == session.draftText) return state
        val typingSwitchesToNewTask =
            session.mode == CellEditMode.ChangeTask && text != session.draftText
        val withDraft =
            state.copy(
                editSession =
                    session.copy(
                        draftText = text,
                        selectedAssignTaskId =
                            if (typingSwitchesToNewTask) null else session.selectedAssignTaskId,
                        newTaskDraftId =
                            if (typingSwitchesToNewTask && session.selectedAssignTaskId != null) {
                                null
                            } else {
                                session.newTaskDraftId
                            },
                    ),
            )
        return commitEditText(withDraft, text)
    }

    private fun commitEditText(base: SchedulerState, text: String): SchedulerState {
        val session = base.editSession ?: return base
        val applied = applyEditText(base, session, text)
        val withSession =
            applied.copy(
                editSession =
                    applied.editSession?.copy(draftText = text)
                        ?: session.copy(draftText = text),
            )
        return commitDelta(withSession, editTextDelta(base, text))
    }

    private fun reduceSetEditMode(state: SchedulerState, mode: CellEditMode): SchedulerState {
        val session = state.editSession ?: return state
        if (session.mode == mode) return state
        return when {
            session.mode == CellEditMode.Rename && mode == CellEditMode.ChangeTask -> {
                val baseline = session.renameTreeBefore ?: session.treeBefore
                state.applyTree(baseline).copy(
                    editSession =
                        session.copy(
                            mode = CellEditMode.ChangeTask,
                            renameTreeBefore = null,
                        ),
                )
            }
            mode == CellEditMode.Rename ->
                state.copy(
                    editSession =
                        session.copy(
                            mode = CellEditMode.Rename,
                            renameTreeBefore = state.captureTree(),
                        ),
                )
            else -> state.copy(editSession = session.copy(mode = mode))
        }
    }

    private fun reducePickTaskFromMenu(state: SchedulerState, taskId: TaskId): SchedulerState {
        val session = state.editSession ?: return state
        val cellId = session.cellId
        if (!SchedulerDomain.canAssignTaskId(state, cellId, taskId)) return state
        val title = state.tasks[taskId]?.title.orEmpty()
        val assigned = commitDelta(state, assignTaskIdDelta(state, cellId, taskId))
        val withDraft =
            assigned.copy(
                editSession =
                    session.copy(
                        draftText = title,
                        selectedAssignTaskId = taskId,
                        newTaskDraftId = null,
                    ),
            )
        return if (title != session.draftText) {
            commitDelta(withDraft, editTextDelta(withDraft, title))
        } else {
            withDraft
        }
    }

    private fun reduceSelectCreateAssignTask(state: SchedulerState): SchedulerState {
        val session = state.editSession ?: return state
        if (session.mode != CellEditMode.ChangeTask || session.selectedAssignTaskId == null) return state
        val (newTaskId, allocated) = state.allocateTaskId()
        val withSession =
            allocated.copy(
                editSession =
                    session.copy(
                        selectedAssignTaskId = null,
                        newTaskDraftId = newTaskId,
                    ),
            )
        return commitEditText(withSession, session.draftText)
    }

    private fun reducePickTitleSuggestion(state: SchedulerState, title: String): SchedulerState {
        return reduceUpdateEditText(state, title)
    }

    private fun reduceCancelEdit(state: SchedulerState): SchedulerState {
        val session = state.editSession ?: return state
        val delta = CancelEditDelta(before = state.captureTree(), after = session.treeBefore)
        return commitDelta(state, delta).copy(editSession = null)
    }

    private fun reduceNavigateSelection(
        state: SchedulerState,
        direction: SelectionNavigate,
        shift: Boolean,
    ): SchedulerState {
        if (state.editSession != null) return state
        val main = state.selection.main ?: return state
        val delta = if (direction == SelectionNavigate.Next) 1 else -1
        val neighbor =
            SchedulerDomain.neighborSelectableCell(state, main, delta)
                ?: return state
        if (!shift) {
            return commitDelta(
                state,
                SetSelectionDelta(
                    before = state.selection,
                    after = SchedulerSelection(main = neighbor, selected = emptySet()),
                ),
            )
        }

        // PRD §3 Shift+Direction: extend a sequential range; reset disjoint Ctrl multi-select.
        var base = state.selection
        if (base.selected.size > 1 && base.rangeAnchor == null) {
            base = base.copy(selected = emptySet())
        }
        val anchor = base.rangeAnchor ?: main
        val range =
            SchedulerDomain.visibleSelectionRange(
                SchedulerDomain.selectableVisibleOrder(state),
                anchor,
                neighbor,
            )
        return commitDelta(
            state,
            SetSelectionDelta(
                before = state.selection,
                after =
                    SchedulerSelection(
                        main = neighbor,
                        selected = range,
                        rangeAnchor = anchor,
                    ),
            ),
        )
    }

    private fun reduceCycleMainSelection(state: SchedulerState, forward: Boolean): SchedulerState {
        if (state.editSession != null) return state
        val selected = state.selection.selected
        if (selected.size <= 1) return state
        val main = state.selection.main ?: return state
        val ordered =
            SchedulerDomain.selectableVisibleOrder(state).filter { it in selected }
        if (ordered.isEmpty()) return state
        val currentIndex = ordered.indexOf(main).let { if (it < 0) 0 else it }
        val nextIndex =
            if (forward) {
                (currentIndex + 1) % ordered.size
            } else {
                (currentIndex - 1 + ordered.size) % ordered.size
            }
        return commitDelta(
            state,
            SetSelectionDelta(
                before = state.selection,
                after =
                    state.selection.copy(
                        main = ordered[nextIndex],
                    ),
            ),
        )
    }

    private fun reduceSelectFirstChild(state: SchedulerState): SchedulerState {
        if (state.editSession != null) return state
        val main = state.selection.main ?: return state
        if (!SchedulerDomain.isSelectableCell(state, main)) return state
        val cell = state.cells[main] ?: return state
        val taskId = cell.taskId
        val childListId = taskId?.let { state.tasks[it]?.childListId }
        if (childListId == null) {
            return reduceNavigateSelection(state, SelectionNavigate.Next, shift = false)
        }
        var next = state
        if (main !in next.expanded) {
            next = commitDelta(next, ToggleExpandDelta(main))
        }
        val child = SchedulerDomain.firstSelectableChild(next, main) ?: return next
        return commitDelta(
            next,
            SetSelectionDelta(
                before = next.selection,
                after = SchedulerSelection(main = child, selected = emptySet()),
            ),
        )
    }

    private fun reduceCopySelection(state: SchedulerState): SchedulerState {
        if (state.editSession != null) return state
        val titles = SchedulerDomain.copyTitlesFromSelection(state, state.selection)
        if (titles.isEmpty() && state.selection.main == null) return state
        return state.copy(clipboard = titles)
    }

    private fun reducePasteTitles(state: SchedulerState, titles: List<String>): SchedulerState {
        if (state.editSession != null) return state
        if (titles.isEmpty()) return state
        val main = state.selection.main ?: return state
        if (!SchedulerDomain.isSelectableCell(state, main)) return state
        val before = state.captureTree()
        val pasted = pasteTitlesAtCell(state.copy(clipboard = titles), main, titles)
        val after = pasted.captureTree()
        if (before == after) return state
        return commitDelta(pasted, TreeMutationDelta(before = before, after = after))
    }

    private fun editTextDelta(state: SchedulerState, text: String): Delta {
        val session = state.editSession ?: return NoOpDelta
        val before = state.captureTree()
        val after = applyEditText(state, session, text).captureTree()
        return TreeMutationDelta(before = before, after = after)
    }

    // PRD §4 Post-Edit Tree Evaluation: exiting Edit Mode removes empty cells (except the
    // absolute bottom cell of each sublist). The removal is committed as a TreeMutationDelta
    // so it round-trips with undo, then the session is cleared.
    private fun endEditSession(state: SchedulerState): SchedulerState {
        val before = state.captureTree()
        val cleaned = evaluatePostEditCleanup(state)
        val after = cleaned.captureTree()
        val committed =
            if (after != before) {
                commitDelta(state, TreeMutationDelta(before = before, after = after))
            } else {
                state
            }
        return committed.copy(editSession = null)
    }

    private fun reduceClick(state: SchedulerState, intent: SchedulerIntent.ClickCell): SchedulerState {
        if (!SchedulerDomain.isSelectableCell(state, intent.cellId)) return state

        val visibleOrder =
            intent.visibleOrder.ifEmpty { SchedulerDomain.selectableVisibleOrder(state) }
        val currentMain = state.selection.main
        val newSelection =
            when {
                intent.shift && currentMain != null -> {
                    val range =
                        SchedulerDomain.visibleSelectionRange(
                            visibleOrder,
                            currentMain,
                            intent.cellId,
                        )
                    SchedulerSelection(
                        main = intent.cellId,
                        selected = range,
                        rangeAnchor = currentMain,
                    )
                }
                intent.ctrl -> {
                    val base = state.selection.selected.toMutableSet()
                    state.selection.main?.let { base.add(it) }
                    val toggled =
                        if (intent.cellId in base) {
                            base - intent.cellId
                        } else {
                            base + intent.cellId
                        }
                    SchedulerSelection(
                        main = intent.cellId,
                        selected = toggled,
                        rangeAnchor = null,
                    )
                }
                intent.forceClearMulti ->
                    SchedulerSelection(main = intent.cellId, selected = emptySet())
                else -> {
                    // Keep a contiguous multi-selection when clicking an already-selected
                    // cell so double-click & drag move can activate (PRD §3).
                    val preserveRange =
                        intent.cellId in state.selection.selected &&
                            state.selection.selected.size > 1
                    if (preserveRange) {
                        SchedulerSelection(main = intent.cellId, selected = state.selection.selected)
                    } else {
                        SchedulerSelection(main = intent.cellId, selected = emptySet())
                    }
                }
            }

        return applySelectionChange(state, newSelection, intent.cellId)
    }

    private fun reduceDragSelect(
        state: SchedulerState,
        intent: SchedulerIntent.DragSelectCells,
    ): SchedulerState {
        if (!SchedulerDomain.isSelectableCell(state, intent.anchorCellId)) return state
        if (!SchedulerDomain.isSelectableCell(state, intent.hoverCellId)) return state
        val visibleOrder =
            intent.visibleOrder.ifEmpty { SchedulerDomain.selectableVisibleOrder(state) }
        val range =
            SchedulerDomain.visibleSelectionRange(
                visibleOrder,
                intent.anchorCellId,
                intent.hoverCellId,
            )
        val newSelection =
            SchedulerSelection(
                main = intent.anchorCellId,
                selected = range,
                rangeAnchor = intent.anchorCellId,
            )
        return applySelectionChange(state, newSelection, intent.hoverCellId)
    }

    private fun reduceMoveSelected(
        state: SchedulerState,
        intent: SchedulerIntent.MoveSelectedCells,
    ): SchedulerState {
        val block =
            SchedulerDomain.orderedActiveSelectionInList(state, state.selection)
                ?: return state
        val (listId, movingOrdered) = block
        val moving = movingOrdered.toSet()
        val list = state.lists[listId] ?: return state
        if (intent.targetCellId !in list.cellIds) return state

        val insertIndex =
            SchedulerDomain.moveInsertIndex(
                list.cellIds,
                moving,
                intent.targetCellId,
                intent.insertBefore,
            )
        val before = state.captureTree()
        val moved =
            SchedulerDomain.applyMoveCellsInList(
                state,
                listId,
                movingOrdered,
                insertIndex,
            )
        val after = moved.captureTree()
        if (before == after) return state
        return commitDelta(moved, TreeMutationDelta(before = before, after = after))
    }

    private fun reduceEmptySelected(state: SchedulerState): SchedulerState {
        if (state.editSession != null) return state
        val targets =
            SchedulerDomain.activeSelectionCells(state.selection)
                .filter { SchedulerDomain.isSelectableCell(state, it) }
        if (targets.isEmpty()) return state
        val before = state.captureTree()
        var next = state
        for (cellId in targets) {
            next = applySetCellTitle(next, cellId, "")
        }
        // PRD §4 Empty cells management: remove emptied cells except the absolute bottom
        // cell of each sublist (same cleanup as exiting Edit Mode).
        val cleaned = evaluatePostEditCleanup(next)
        val after = cleaned.captureTree()
        val selectionAfter =
            adjustSelectionAfterRemovedCells(
                beforeCleanup = next,
                afterCleanup = cleaned,
                selection = state.selection,
            )
        if (before == after && selectionAfter == state.selection) return state
        return commitDelta(
            state,
            EmptyCellsDelta(
                treeBefore = before,
                treeAfter = after,
                selectionBefore = state.selection,
                selectionAfter = selectionAfter,
            ),
        )
    }

    private fun reduceClearSelection(state: SchedulerState): SchedulerState {
        if (state.selection.main == null &&
            state.selection.selected.isEmpty() &&
            state.editSession == null
        ) {
            return state
        }
        var next = state
        if (state.editSession != null) {
            next = endEditSession(state)
        }
        if (next.selection.main == null && next.selection.selected.isEmpty()) return next
        return commitDelta(
            next,
            SetSelectionDelta(before = next.selection, after = SchedulerSelection()),
        )
    }

    private fun reduceExitEdit(
        state: SchedulerState,
        navigation: EditExitNavigation,
    ): SchedulerState {
        if (state.editSession == null) return state
        val editingCellId = state.editSession.cellId
        var next = endEditSession(state)

        val newMain =
            when (navigation) {
                EditExitNavigation.Down ->
                    SchedulerDomain.neighborSelectableCell(next, editingCellId, 1) ?: editingCellId
                EditExitNavigation.Up ->
                    SchedulerDomain.neighborSelectableCell(next, editingCellId, -1) ?: editingCellId
                EditExitNavigation.TabToChild -> {
                    val cell = next.cells[editingCellId]
                    val taskId = cell?.taskId
                    val childListId = taskId?.let { next.tasks[it]?.childListId }
                    if (childListId == null) {
                        editingCellId
                    } else {
                        if (editingCellId !in next.expanded) {
                            next = commitDelta(next, ToggleExpandDelta(editingCellId))
                        }
                        SchedulerDomain.firstSelectableChild(next, editingCellId) ?: editingCellId
                    }
                }
            }

        if (newMain == next.selection.main && next.selection.selected.isEmpty()) return next
        return commitDelta(
            next,
            SetSelectionDelta(
                before = next.selection,
                after = SchedulerSelection(main = newMain, selected = emptySet()),
            ),
        )
    }

    private fun applySelectionChange(
        state: SchedulerState,
        newSelection: SchedulerSelection,
        clickedCellId: CellId,
    ): SchedulerState {
        var next =
            commitDelta(
                state,
                SetSelectionDelta(
                    before = state.selection,
                    after = newSelection,
                ),
            )
        val editing = state.editSession
        if (editing != null && clickedCellId != editing.cellId) {
            next = endEditSession(next)
        }
        return next
    }

    private fun undo(state: SchedulerState): SchedulerState {
        val pointer = state.history.pointer
        if (pointer < 0) return state
        val unit = state.history.units[pointer]
        val undone = unit.delta.undo(state)
        return undone.copy(history = state.history.copy(pointer = pointer - 1))
    }

    private fun redo(state: SchedulerState): SchedulerState {
        val next = state.history.pointer + 1
        if (next >= state.history.units.size) return state
        val unit = state.history.units[next]
        val redone = unit.delta.redo(state)
        return redone.copy(history = state.history.copy(pointer = next))
    }

    private fun commitDelta(state: SchedulerState, forward: Delta): SchedulerState {
        val newState = forward.redo(state)

        val newUnits =
            if (state.history.pointer == state.history.units.lastIndex) {
                state.history.units + HistoryUnit(
                    chronoId = state.history.units.size.toLong(),
                    delta = forward,
                )
            } else {
                state.history.units.take(state.history.pointer + 1) + HistoryUnit(
                    chronoId = (state.history.pointer + 1).toLong(),
                    delta = forward,
                )
            }

        return newState.copy(
            history = state.history.copy(
                pointer = state.history.pointer + 1,
                units = newUnits,
            ),
        )
    }
}

/**
 * PRD §4 Post-Edit Tree Evaluation: drop every textually empty cell from each list, keeping
 * only the *absolute bottom cell* of each (sub)list as the trailing placeholder. Occurrences
 * of any removed cell are pruned and orphan tasks are purged.
 */
private fun pasteTitlesAtCell(
    state: SchedulerState,
    targetCellId: CellId,
    titles: List<String>,
): SchedulerState {
    if (titles.isEmpty()) return state
    var working = applySetCellTitle(state, targetCellId, titles.first())
    var afterId = targetCellId
    for (title in titles.drop(1)) {
        val (withCell, newId) = insertEmptyCellAfter(working, afterId)
        working = applySetCellTitle(withCell, newId, title)
        afterId = newId
    }
    return working
}

private fun insertEmptyCellAfter(
    state: SchedulerState,
    afterCellId: CellId,
): Pair<SchedulerState, CellId> {
    val cell = state.cells[afterCellId] ?: return state to afterCellId
    val list = state.lists[cell.parentListId] ?: return state to afterCellId
    val index = list.cellIds.indexOf(afterCellId)
    if (index < 0) return state to afterCellId

    val newCellId = CellId("cell/${list.id.value}/${state.cells.size}")
    val newCell =
        Cell(
            id = newCellId,
            parentListId = list.id,
            taskId = null,
        )
    val newCellIds = list.cellIds.toMutableList()
    newCellIds.add(index + 1, newCellId)
    return state.copy(
        cells = state.cells + (newCellId to newCell),
        lists = state.lists + (list.id to list.copy(cellIds = newCellIds)),
    ) to newCellId
}

/**
 * When cleanup removes cells, keep selection on the cell that slid into the removed cell's
 * index (typically the next sibling below), or clear it when nothing remains selectable.
 */
private fun adjustSelectionAfterRemovedCells(
    beforeCleanup: SchedulerState,
    afterCleanup: SchedulerState,
    selection: SchedulerSelection,
): SchedulerSelection {
    fun resolveMain(oldMain: CellId?): CellId? {
        if (oldMain == null) return null
        if (oldMain in afterCleanup.cells) return oldMain
        val cell = beforeCleanup.cells[oldMain] ?: return null
        val list = beforeCleanup.lists[cell.parentListId] ?: return null
        val index = list.cellIds.indexOf(oldMain)
        if (index < 0) return null
        val afterList = afterCleanup.lists[cell.parentListId] ?: return null
        return afterList.cellIds
            .getOrNull(index.coerceAtMost(afterList.cellIds.lastIndex))
            ?.takeIf { SchedulerDomain.isSelectableCell(afterCleanup, it) }
    }

    val newSelected = selection.selected.filter { it in afterCleanup.cells }.toSet()
    val newMain = resolveMain(selection.main)
    val newAnchor = selection.rangeAnchor?.takeIf { it in afterCleanup.cells }
    return SchedulerSelection(main = newMain, selected = newSelected, rangeAnchor = newAnchor)
}

private fun evaluatePostEditCleanup(state: SchedulerState): SchedulerState {
    val cells = state.cells.toMutableMap()
    val lists = state.lists.toMutableMap()
    val tasks = state.tasks.toMutableMap()
    var changed = false

    for ((listId, list) in state.lists) {
        if (list.cellIds.size <= 1) continue
        val lastId = list.cellIds.last()
        val retained =
            list.cellIds.filter { cellId ->
                val removable = cellId != lastId && isTextuallyEmptyCell(state, cellId)
                if (removable) {
                    val removed = cells.remove(cellId)
                    removed?.taskId?.let { taskId ->
                        tasks[taskId]?.let { task ->
                            tasks[taskId] = task.copy(occurrences = task.occurrences - cellId)
                        }
                    }
                    changed = true
                }
                !removable
            }
        if (retained.size != list.cellIds.size) {
            lists[listId] = list.copy(cellIds = retained)
        }
    }

    if (!changed) return state
    return SchedulerDomain.purgeOrphanTasks(
        state.copy(cells = cells, lists = lists, tasks = tasks),
    )
}

private fun isTextuallyEmptyCell(state: SchedulerState, cellId: CellId): Boolean =
    SchedulerDomain.isTextuallyEmptyCell(state, cellId)

private fun applyEditText(
    state: SchedulerState,
    session: SchedulerEditSession,
    text: String,
): SchedulerState {
    val cellId = session.cellId
    return when (session.mode) {
        CellEditMode.Rename -> applySetCellTitle(state, cellId, text)
        CellEditMode.ChangeTask ->
            if (session.selectedAssignTaskId == null) {
                applyChangeTaskNewDraft(state, session, text)
            } else {
                applySetCellTitle(state, cellId, text, forceTaskId = session.selectedAssignTaskId)
            }
    }
}

private fun applyChangeTaskNewDraft(
    state: SchedulerState,
    session: SchedulerEditSession,
    text: String,
): SchedulerState {
    val cellId = session.cellId
    val (draftTaskId, afterAlloc) =
        session.newTaskDraftId?.let { it to state }
            ?: state.allocateTaskId().let { (id, next) -> id to next }
    var working = afterAlloc
    if (working.cells[cellId]?.taskId != draftTaskId) {
        working = applyAssignTaskId(working, cellId, draftTaskId)
    }
    working = applySetCellTitle(working, cellId, text, forceTaskId = draftTaskId)
    return working.copy(editSession = session.copy(newTaskDraftId = draftTaskId))
}

private fun assignTaskIdDelta(
    state: SchedulerState,
    cellId: CellId,
    taskId: TaskId,
): Delta {
    val before = state.captureTree()
    val after =
        if (!SchedulerDomain.canAssignTaskId(state, cellId, taskId)) {
            state
        } else {
            applyAssignTaskId(state, cellId, taskId)
        }.captureTree()
    return TreeMutationDelta(before = before, after = after)
}

private fun applyAssignTaskId(state: SchedulerState, cellId: CellId, taskId: TaskId): SchedulerState {
    val cell = state.cells[cellId] ?: return state
    val targetTask = state.tasks[taskId] ?: return state

    var tasks = state.tasks.toMutableMap()
    val oldTaskId = cell.taskId

    if (oldTaskId != null && oldTaskId != taskId) {
        val oldTask = tasks[oldTaskId] ?: return state
        tasks[oldTaskId] = oldTask.copy(occurrences = oldTask.occurrences - cellId)
    }

    val cells = state.cells.toMutableMap()
    cells[cellId] = cell.copy(taskId = taskId)

    var working = state.copy(cells = cells, tasks = tasks)
    val mergedOccurrences = (targetTask.occurrences + cellId).distinct()
    tasks[taskId] = targetTask.copy(occurrences = SchedulerDomain.sortOccurrences(working, mergedOccurrences))
    working = working.copy(tasks = tasks)

    SchedulerDomain.parentTaskId(working, cellId)?.let { parentId ->
        working = working.copy(tasks = SchedulerDomain.linkChildUnderParent(working.tasks, parentId, taskId))
    }

    return SchedulerDomain.purgeOrphanTasks(working)
}

private fun setCellTitleDelta(
    state: SchedulerState,
    cellId: CellId,
    title: String,
): Delta {
    val before = state.captureTree()
    val after = applySetCellTitle(state, cellId, title).captureTree()
    return TreeMutationDelta(before = before, after = after)
}

private fun applySetCellTitle(
    state: SchedulerState,
    cellId: CellId,
    title: String,
    forceTaskId: TaskId? = null,
): SchedulerState {
    if (!SchedulerDomain.isSelectableCell(state, cellId)) return state
    val cell = state.cells[cellId] ?: return state

    var working = state
    val list = working.lists[cell.parentListId] ?: return state

    val isNewTask = forceTaskId == null && cell.taskId == null
    val (taskId, afterAllocate) =
        when {
            forceTaskId != null -> forceTaskId to working
            cell.taskId != null -> cell.taskId to working
            else -> working.allocateTaskId().let { (id, next) -> id to next }
        }
    working = afterAllocate

    val previousTask = working.tasks[taskId]
    val previousTitle = previousTask?.title

    val tasks = working.tasks.toMutableMap()
    val task =
        (previousTask ?: Task(id = taskId, title = title)).let { existing ->
            existing.copy(
                title = title,
                occurrences = SchedulerDomain.sortOccurrences(
                    working,
                    (existing.occurrences + cellId).distinct(),
                ),
            )
        }
    tasks[taskId] = task

    if (isNewTask) {
        SchedulerDomain.parentTaskId(working, cellId)?.let { parentId ->
            val linked = SchedulerDomain.linkChildUnderParent(tasks, parentId, taskId)
            tasks.clear()
            tasks.putAll(linked)
        }
    }

    var titleToTaskIds = working.titleToTaskIds
    if (previousTitle != null && previousTitle != title) {
        titleToTaskIds = SchedulerDomain.removeTitleMapping(titleToTaskIds, previousTitle, taskId)
    }
    if (title.isNotEmpty()) {
        titleToTaskIds = SchedulerDomain.addTitleMapping(titleToTaskIds, title, taskId)
    }

    val cells = working.cells.toMutableMap()
    cells[cellId] = cell.copy(taskId = taskId)

    var lists = working.lists.toMutableMap()
    var currentList = lists[cell.parentListId] ?: return state

    if (title.isNotEmpty()) {
        val updatedTask = tasks[taskId]!!
        if (updatedTask.childListId == null) {
            val subListId = CellListId("${taskId.value}/children")
            val subPlaceholderId = CellId("cell/${subListId.value}/0")
            val subPlaceholder =
                Cell(
                    id = subPlaceholderId,
                    parentListId = subListId,
                    taskId = null,
                )
            val subList =
                CellList(
                    id = subListId,
                    parentCellId = cellId,
                    cellIds = listOf(subPlaceholderId),
                )
            cells[subPlaceholderId] = subPlaceholder
            lists[subListId] = subList
            tasks[taskId] = updatedTask.copy(childListId = subListId)
        }

        // PRD §4 Auto-Expansion: trailing sibling placeholder only for the list bottom cell.
        if (currentList.cellIds.lastOrNull() == cellId) {
            val placeholderId = CellId("cell/${currentList.id.value}/${currentList.cellIds.size}")
            val placeholder =
                Cell(
                    id = placeholderId,
                    parentListId = currentList.id,
                    taskId = null,
                )
            cells[placeholderId] = placeholder
            currentList = currentList.copy(cellIds = currentList.cellIds + placeholderId)
            lists[currentList.id] = currentList
        }
    }

    // PRD §4 Cleanup (inverse of Auto-Expansion): when the cell directly above the
    // trailing empty placeholder is emptied while editing, drop that placeholder so the
    // now-empty cell becomes the list's bottom cell again.
    if (title.isEmpty()) {
        val ids = currentList.cellIds
        val index = ids.indexOf(cellId)
        if (index >= 0 && index == ids.size - 2 && cells[ids.last()]?.taskId == null) {
            cells.remove(ids.last())
            currentList = currentList.copy(cellIds = ids.dropLast(1))
            lists[currentList.id] = currentList
        }
    }

    var result =
        working.copy(
            cells = cells,
            lists = lists,
            tasks = tasks,
            titleToTaskIds = titleToTaskIds,
        )

    return SchedulerDomain.purgeOrphanTasks(result)
}

private data class EmptyCellsDelta(
    val treeBefore: TreeSnapshot,
    val treeAfter: TreeSnapshot,
    val selectionBefore: SchedulerSelection,
    val selectionAfter: SchedulerSelection,
) : Delta {
    override fun undo(state: SchedulerState): SchedulerState =
        state.applyTree(treeBefore).copy(selection = selectionBefore)

    override fun redo(state: SchedulerState): SchedulerState =
        state.applyTree(treeAfter).copy(selection = selectionAfter)
}

private data class SetSelectionDelta(
    val before: SchedulerSelection,
    val after: SchedulerSelection,
) : Delta {
    override fun undo(state: SchedulerState): SchedulerState = state.copy(selection = before)

    override fun redo(state: SchedulerState): SchedulerState = state.copy(selection = after)
}

private data class ToggleExpandDelta(
    val cellId: CellId,
) : Delta {
    override fun undo(state: SchedulerState): SchedulerState = applyToggle(state)

    override fun redo(state: SchedulerState): SchedulerState = applyToggle(state)

    private fun applyToggle(state: SchedulerState): SchedulerState {
        val cell = state.cells[cellId] ?: return state
        if (cellId in state.expanded) {
            return state.copy(expanded = state.expanded - cellId)
        }
        if (cell.taskId == null || SchedulerDomain.isTextuallyEmptyCell(state, cellId)) return state
        val childListId = state.tasks[cell.taskId]?.childListId ?: return state
        if (state.lists[childListId] == null) return state
        return state.copy(expanded = state.expanded + cellId)
    }
}

private data class TreeMutationDelta(
    val before: TreeSnapshot,
    val after: TreeSnapshot,
) : Delta {
    override fun undo(state: SchedulerState): SchedulerState = state.applyTree(before)

    override fun redo(state: SchedulerState): SchedulerState = state.applyTree(after)
}

private data class CancelEditDelta(
    val before: TreeSnapshot,
    val after: TreeSnapshot,
) : Delta {
    override fun undo(state: SchedulerState): SchedulerState = state.applyTree(before)

    override fun redo(state: SchedulerState): SchedulerState = state.applyTree(after)
}

private object NoOpDelta : Delta {
    override fun undo(state: SchedulerState): SchedulerState = state

    override fun redo(state: SchedulerState): SchedulerState = state
}
