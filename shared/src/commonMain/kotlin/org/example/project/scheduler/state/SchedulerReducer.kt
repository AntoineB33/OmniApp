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
            is SchedulerIntent.SetPriorityWeight ->
                commitDelta(state, priorityTreeDelta(state) { applySetPriorityWeight(it, intent.cellId, intent.column, intent.value) })
            is SchedulerIntent.SetPriorityColumnWeight ->
                commitDelta(state, priorityTreeDelta(state) { applySetPriorityColumnWeight(it, intent.listId, intent.column, intent.weight) })
            is SchedulerIntent.AddPriorityColumn ->
                commitDelta(state, priorityTreeDelta(state) { applyAddPriorityColumn(it, intent.listId, intent.index) })
            is SchedulerIntent.ResetPriorityColumn ->
                commitDelta(state, priorityTreeDelta(state) { applyResetPriorityColumn(it, intent.listId, intent.column) })
            is SchedulerIntent.DeletePriorityColumn ->
                commitDelta(state, priorityTreeDelta(state) { applyDeletePriorityColumn(it, intent.listId, intent.column) })
            is SchedulerIntent.MovePriorityColumn ->
                commitDelta(state, priorityTreeDelta(state) { applyMovePriorityColumn(it, intent.listId, intent.from, intent.to) })
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
            SchedulerIntent.Undo -> undo(state, contentCategory(state))
            SchedulerIntent.Redo -> redo(state, contentCategory(state))
            SchedulerIntent.UndoSelection -> undo(state, HistoryCategory.Selection)
            SchedulerIntent.RedoSelection -> redo(state, HistoryCategory.Selection)
        }
    }

    /** Ctrl+Z/Y target the Edit Mode stack while editing, otherwise "the rest" (PRD §5). */
    private fun contentCategory(state: SchedulerState): HistoryCategory =
        if (state.editSession != null) HistoryCategory.Edit else HistoryCategory.Main

    private fun reduceBeginEdit(state: SchedulerState, intent: SchedulerIntent.BeginEdit): SchedulerState {
        if (!SchedulerDomain.isSelectableCell(state, intent.cellId)) return state
        val cell = state.cells[intent.cellId] ?: return state
        val currentTitle = cell.taskId?.let { state.tasks[it]?.title }.orEmpty()
        val draft = intent.initialText ?: currentTitle
        val typingToEdit = intent.initialText != null
        val selection = selectionFor(state, main = intent.cellId)
        val withSession =
            state.copy(
                editSession =
                    SchedulerEditSession(
                        cellId = intent.cellId,
                        renderVia = selection.renderVia,
                        draftText = draft,
                        selectedAssignTaskId = if (typingToEdit) null else cell.taskId,
                        newTaskDraftId = null,
                        treeBefore = state.captureTree(),
                    ),
                selection = selection,
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
        return commitDelta(withSession, editTextDelta(base, text), HistoryCategory.Edit)
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
        val assigned = commitDelta(state, assignTaskIdDelta(state, cellId, taskId), HistoryCategory.Edit)
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
            commitDelta(withDraft, editTextDelta(withDraft, title), HistoryCategory.Edit)
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
        // A canceled edit reverts to the pre-session tree and leaves no trace: the ephemeral Edit
        // Mode history is discarded and no "rest" unit is recorded (PRD §4 Cancel, §5 categories).
        return state.applyTree(session.treeBefore).copy(
            editSession = null,
            histories = state.histories.copy(edit = SchedulerHistory()),
        )
    }

    private fun reduceNavigateSelection(
        state: SchedulerState,
        direction: SelectionNavigate,
        shift: Boolean,
    ): SchedulerState {
        if (state.editSession != null) return state
        val main = state.selection.main ?: return state
        val delta = if (direction == SelectionNavigate.Next) 1 else -1
        // Resolve the neighbor by the selected *occurrence* (main + renderVia), so a mirrored
        // cell moves relative to the row actually displayed beneath it, not its first copy.
        val neighborOccurrence =
            SchedulerDomain.neighborSelectableOccurrence(state, main, state.selection.renderVia, delta)
                ?: return state
        val neighbor = neighborOccurrence.cellId
        if (!shift) {
            return commitDelta(
                state,
                SetSelectionDelta(
                    before = state.selection,
                    // Pin the new main to the exact occurrence we stepped onto.
                    after =
                        SchedulerSelection(
                            main = neighbor,
                            renderVia = neighborOccurrence.renderVia,
                        ),
                ),
                HistoryCategory.Selection,
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
                    selectionFor(
                        state,
                        main = neighbor,
                        selected = range,
                        rangeAnchor = anchor,
                        explicitVia = neighborOccurrence.renderVia,
                        prior = base,
                    ),
            ),
            HistoryCategory.Selection,
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
                        renderVia =
                            SchedulerDomain.resolveSelectionRenderVia(
                                state,
                                ordered[nextIndex],
                                prior = state.selection,
                            ),
                    ),
            ),
            HistoryCategory.Selection,
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
                after = selectionFor(next, main = child, explicitVia = main),
            ),
            HistoryCategory.Selection,
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

    // PRD §4 Post-Edit Tree Evaluation: exiting Edit Mode removes empty cells (except the absolute
    // bottom cell of each sublist). PRD §5 categories: the whole session (keystrokes + cleanup) is
    // collapsed from the pre-session tree into a single "rest" unit so post-exit Ctrl+Z undoes the
    // edit as one step, and the ephemeral Edit Mode stack is discarded.
    private fun endEditSession(state: SchedulerState): SchedulerState {
        val session = state.editSession
        val cleaned = evaluatePostEditCleanup(state)
        val before = session?.treeBefore ?: cleaned.captureTree()
        val after = cleaned.captureTree()
        val committed =
            if (after != before) {
                commitDelta(cleaned, TreeMutationDelta(before = before, after = after))
            } else {
                cleaned
            }
        return committed.copy(
            editSession = null,
            histories = committed.histories.copy(edit = SchedulerHistory()),
        )
    }

    private fun reduceClick(state: SchedulerState, intent: SchedulerIntent.ClickCell): SchedulerState {
        if (!SchedulerDomain.isSelectableCell(state, intent.cellId)) return state

        // The deferred single-click reset (forceClearMulti) fires after the double-tap timeout to
        // collapse a still-intact multi-selection down to the clicked cell. If the user has since
        // clicked another cell, the previous cell's timer can still be alive and fire this stale
        // reset, momentarily re-selecting the old cell before its own deferred click re-asserts the
        // new one. Ignore it unless the clicked cell is still the main selection.
        if (intent.forceClearMulti && !intent.ctrl && !intent.shift &&
            state.selection.main != intent.cellId
        ) {
            return state
        }

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
                    selectionFor(
                        state,
                        main = intent.cellId,
                        selected = range,
                        rangeAnchor = currentMain,
                        explicitVia = intent.renderVia,
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
                    selectionFor(
                        state,
                        main = intent.cellId,
                        selected = toggled,
                        explicitVia = intent.renderVia,
                    )
                }
                intent.forceClearMulti ->
                    selectionFor(state, main = intent.cellId, explicitVia = intent.renderVia)
                else -> {
                    // Keep a contiguous multi-selection when clicking an already-selected
                    // cell so double-click & drag move can activate (PRD §3).
                    val preserveRange =
                        intent.cellId in state.selection.selected &&
                            state.selection.selected.size > 1
                    if (preserveRange) {
                        selectionFor(
                            state,
                            main = intent.cellId,
                            selected = state.selection.selected,
                            rangeAnchor = state.selection.rangeAnchor,
                            explicitVia = intent.renderVia ?: state.selection.renderVia,
                        )
                    } else {
                        selectionFor(state, main = intent.cellId, explicitVia = intent.renderVia)
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
            selectionFor(
                state,
                main = intent.anchorCellId,
                selected = range,
                rangeAnchor = intent.anchorCellId,
                explicitVia = intent.renderVia,
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
        val (sourceListId, movingOrdered) = block
        val moving = movingOrdered.toSet()
        val targetListId = state.cells[intent.targetCellId]?.parentListId ?: return state
        val targetList = state.lists[targetListId] ?: return state
        // A cross-list drop relocates the block into another layer of the tree. Reject it up front
        // when any moved task would break a PRD constraint at the destination (duplicate in the
        // list, or a cycle with its new ancestors); same-list reorders never can.
        if (targetListId != sourceListId) {
            val valid =
                movingOrdered.all { cellId ->
                    SchedulerDomain.canMoveTaskIntoList(
                        state,
                        state.cells[cellId]?.taskId,
                        targetListId,
                        intent.targetCellId,
                        moving,
                    )
                }
            if (!valid) return state
        }

        val insertIndex =
            SchedulerDomain.moveInsertIndex(
                targetList.cellIds,
                moving,
                intent.targetCellId,
                intent.insertBefore,
            )
        val before = state.captureTree()
        val moved =
            SchedulerDomain.applyMoveCellsToList(
                state,
                sourceListId,
                movingOrdered,
                targetListId,
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
            HistoryCategory.Selection,
        )
    }

    private fun reduceExitEdit(
        state: SchedulerState,
        navigation: EditExitNavigation,
    ): SchedulerState {
        if (state.editSession == null) return state
        val editingCellId = state.editSession.cellId
        val editingVia = state.editSession.renderVia
        var next = endEditSession(state)

        val newMain =
            when (navigation) {
                EditExitNavigation.Down ->
                    SchedulerDomain.neighborSelectableOccurrence(next, editingCellId, editingVia, 1)
                        ?.cellId ?: editingCellId
                EditExitNavigation.Up ->
                    SchedulerDomain.neighborSelectableOccurrence(next, editingCellId, editingVia, -1)
                        ?.cellId ?: editingCellId
                EditExitNavigation.Stay -> editingCellId
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
        // Only Tab-into-child renders the new main via the cell we were editing (its parent).
        // Sibling moves (Up/Down) must resolve their own render-via, otherwise the highlight
        // is pinned to the former cell and never appears on the moved selection.
        val explicitVia =
            if (navigation == EditExitNavigation.TabToChild && newMain != editingCellId) {
                editingCellId
            } else {
                null
            }
        return commitDelta(
            next,
            SetSelectionDelta(
                before = next.selection,
                after = selectionFor(next, main = newMain, explicitVia = explicitVia),
            ),
            HistoryCategory.Selection,
        )
    }

    private fun applySelectionChange(
        state: SchedulerState,
        newSelection: SchedulerSelection,
        clickedCellId: CellId,
    ): SchedulerState {
        // Skip recording a no-op selection change so re-clicking an already-selected cell (or the
        // single-click reset that collapses a still-intact multi-selection) doesn't push an empty
        // undo step onto the history.
        var next =
            if (newSelection == state.selection) {
                state
            } else {
                commitDelta(
                    state,
                    SetSelectionDelta(
                        before = state.selection,
                        after = newSelection,
                    ),
                    HistoryCategory.Selection,
                )
            }
        val editing = state.editSession
        if (editing != null && clickedCellId != editing.cellId) {
            next = endEditSession(next)
        }
        return next
    }

    private fun undo(state: SchedulerState, category: HistoryCategory): SchedulerState {
        val history = state.histories.forCategory(category)
        val pointer = history.pointer
        if (pointer < 0) return state
        val unit = history.units[pointer]
        val undone = unit.delta.undo(state)
        val moved =
            undone.copy(
                histories = state.histories.withCategory(category, history.copy(pointer = pointer - 1)),
            )
        return if (category == HistoryCategory.Edit) syncEditDraft(moved) else moved
    }

    private fun redo(state: SchedulerState, category: HistoryCategory): SchedulerState {
        val history = state.histories.forCategory(category)
        val next = history.pointer + 1
        if (next >= history.units.size) return state
        val unit = history.units[next]
        val redone = unit.delta.redo(state)
        val moved =
            redone.copy(
                histories = state.histories.withCategory(category, history.copy(pointer = next)),
            )
        return if (category == HistoryCategory.Edit) syncEditDraft(moved) else moved
    }

    /**
     * In-session Edit Mode undo/redo replays tree deltas, so the live [SchedulerEditSession.draftText]
     * (and therefore the text field) must be re-pulled from the edited cell's current title.
     */
    private fun syncEditDraft(state: SchedulerState): SchedulerState {
        val session = state.editSession ?: return state
        val title = state.cells[session.cellId]?.taskId?.let { state.tasks[it]?.title }.orEmpty()
        return if (title == session.draftText) {
            state
        } else {
            state.copy(editSession = session.copy(draftText = title))
        }
    }

    private fun commitDelta(
        state: SchedulerState,
        forward: Delta,
        category: HistoryCategory = HistoryCategory.Main,
    ): SchedulerState {
        val newState = forward.redo(state)
        val history = state.histories.forCategory(category)

        val newUnits =
            if (history.pointer == history.units.lastIndex) {
                history.units + HistoryUnit(
                    chronoId = history.units.size.toLong(),
                    delta = forward,
                )
            } else {
                history.units.take(history.pointer + 1) + HistoryUnit(
                    chronoId = (history.pointer + 1).toLong(),
                    delta = forward,
                )
            }

        return newState.copy(
            histories =
                state.histories.withCategory(
                    category,
                    history.copy(pointer = history.pointer + 1, units = newUnits),
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

    val (newCellId, withId) = state.allocateCellId(list.id)
    val newCell =
        Cell(
            id = newCellId,
            parentListId = list.id,
            taskId = null,
        )
    val newCellIds = list.cellIds.toMutableList()
    newCellIds.add(index + 1, newCellId)
    return withId.copy(
        cells = withId.cells + (newCellId to newCell),
        lists = withId.lists + (list.id to list.copy(cellIds = newCellIds)),
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
    val renderVia =
        when {
            newMain == null -> null
            selection.renderVia != null &&
                SchedulerDomain.isInVisualSubtree(afterCleanup, newMain, selection.renderVia) ->
                selection.renderVia
            else -> SchedulerDomain.resolveSelectionRenderVia(afterCleanup, newMain, prior = selection)
        }
    return SchedulerSelection(
        main = newMain,
        selected = newSelected,
        rangeAnchor = newAnchor,
        renderVia = renderVia,
    )
}

private fun selectionFor(
    state: SchedulerState,
    main: CellId?,
    selected: Set<CellId> = emptySet(),
    rangeAnchor: CellId? = null,
    explicitVia: CellId? = null,
    prior: SchedulerSelection = state.selection,
): SchedulerSelection {
    val renderVia =
        main?.let {
            SchedulerDomain.resolveSelectionRenderVia(state, it, explicitVia, prior)
        }
    return SchedulerSelection(
        main = main,
        selected = selected,
        rangeAnchor = rangeAnchor,
        renderVia = renderVia,
    )
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

/** Wraps a priority-table mutation as an undoable [TreeMutationDelta] (PRD §6). */
private fun priorityTreeDelta(
    state: SchedulerState,
    mutate: (SchedulerState) -> SchedulerState,
): Delta {
    val before = state.captureTree()
    val after = mutate(state).captureTree()
    return TreeMutationDelta(before = before, after = after)
}

/**
 * PRD §5: the default value of a weight field by column — the first column's fields default to 1,
 * every added column's fields default to 0. Used to fill gaps when a value vector is shorter than
 * the list's column count.
 */
private fun defaultWeightAt(column: Int): Double = if (column == 0) 1.0 else 0.0

/** Pad [weights] to at least [size] entries, filling missing columns with their default. */
private fun normalizedWeights(weights: List<Double>, size: Int): MutableList<Double> =
    MutableList(maxOf(size, weights.size)) { weights.getOrElse(it) { defaultWeightAt(it) } }

private fun applySetPriorityWeight(
    state: SchedulerState,
    cellId: CellId,
    column: Int,
    value: Double,
): SchedulerState {
    if (column < 0) return state
    val cell = state.cells[cellId] ?: return state
    // PRD §5: cell values span 0..infinity.
    val clamped = value.coerceAtLeast(0.0)
    val weights = normalizedWeights(cell.priorityWeights, column + 1)
    if (weights[column] == clamped) return state
    weights[column] = clamped
    return state.copy(cells = state.cells + (cellId to cell.copy(priorityWeights = weights)))
}

private fun applySetPriorityColumnWeight(
    state: SchedulerState,
    listId: CellListId,
    column: Int,
    weight: Double,
): SchedulerState {
    if (column < 0) return state
    val list = state.lists[listId] ?: return state
    // PRD §5: a column's header weight can only span 0..1.
    val clamped = weight.coerceIn(0.0, 1.0)
    val columns = MutableList(maxOf(column + 1, list.weightColumns.size)) {
        list.weightColumns.getOrElse(it) { defaultWeightAt(it) }
    }
    if (columns[column] == clamped) return state
    columns[column] = clamped
    return state.copy(lists = state.lists + (listId to list.copy(weightColumns = columns)))
}

private fun applyAddPriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    index: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    val at = index.coerceIn(0, list.weightColumns.size)
    // PRD §5: an added column has every field (header and cells) set to 0.
    val cells = state.cells.toMutableMap()
    for (cellId in list.cellIds) {
        val cell = cells[cellId] ?: continue
        val padded = normalizedWeights(cell.priorityWeights, list.weightColumns.size)
        padded.add(at, 0.0)
        cells[cellId] = cell.copy(priorityWeights = padded)
    }
    val columns = list.weightColumns.toMutableList().also { it.add(at, 0.0) }
    val lists = state.lists + (listId to list.copy(weightColumns = columns))
    return state.copy(cells = cells, lists = lists)
}

private fun applyResetPriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    column: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    if (column < 0 || column >= list.weightColumns.size) return state
    val default = defaultWeightAt(column)
    val cells = state.cells.toMutableMap()
    for (cellId in list.cellIds) {
        val cell = cells[cellId] ?: continue
        val weights = normalizedWeights(cell.priorityWeights, list.weightColumns.size)
        weights[column] = default
        cells[cellId] = cell.copy(priorityWeights = weights)
    }
    val columns = list.weightColumns.toMutableList().also { it[column] = default }
    return state.copy(cells = cells, lists = state.lists + (listId to list.copy(weightColumns = columns)))
}

private fun applyMovePriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    from: Int,
    to: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    val size = list.weightColumns.size
    if (from < 0 || from >= size) return state
    // [to] is an insertion index across all columns; account for removing [from] first.
    val target = (if (to > from) to - 1 else to).coerceIn(0, size - 1)
    if (target == from) return state
    fun <T> reorder(items: MutableList<T>) {
        val moved = items.removeAt(from)
        items.add(target, moved)
    }
    val cells = state.cells.toMutableMap()
    for (cellId in list.cellIds) {
        val cell = cells[cellId] ?: continue
        val weights = normalizedWeights(cell.priorityWeights, size)
        reorder(weights)
        cells[cellId] = cell.copy(priorityWeights = weights)
    }
    val columns = list.weightColumns.toMutableList().also { reorder(it) }
    return state.copy(cells = cells, lists = state.lists + (listId to list.copy(weightColumns = columns)))
}

private fun applyDeletePriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    column: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    // Keep at least one column so priority distribution stays well-defined.
    if (column < 0 || column >= list.weightColumns.size || list.weightColumns.size <= 1) return state
    val cells = state.cells.toMutableMap()
    for (cellId in list.cellIds) {
        val cell = cells[cellId] ?: continue
        val padded = normalizedWeights(cell.priorityWeights, list.weightColumns.size)
        padded.removeAt(column)
        cells[cellId] = cell.copy(priorityWeights = padded)
    }
    val columns = list.weightColumns.toMutableList().also { it.removeAt(column) }
    val lists = state.lists + (listId to list.copy(weightColumns = columns))
    return state.copy(cells = cells, lists = lists)
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
            val (placeholderId, withPlaceholderId) = working.allocateCellId(currentList.id)
            working = withPlaceholderId
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

private object NoOpDelta : Delta {
    override fun undo(state: SchedulerState): SchedulerState = state

    override fun redo(state: SchedulerState): SchedulerState = state
}
