package org.example.project.scheduler.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed as pointerCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed as pointerShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.example.project.scheduler.domain.RelativePriorityDomain
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerDomain.VisibleOccurrence
import org.example.project.scheduler.domain.TaskTreeSearch
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.RelativePriorityPinKey
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import kotlin.math.roundToInt
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.platform.isDeadKey
import org.example.project.scheduler.platform.readSystemClipboardText
import org.example.project.scheduler.platform.writeSystemClipboardText
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.defaultSubtreeIsEmpty
import org.example.project.scheduler.state.SelectionNavigate
import org.example.project.ui.ControlChords
import org.example.project.ui.INDENT_STEP_DP
import org.example.project.ui.PERCENT_COLUMN_WIDTH
import org.example.project.ui.PRIORITY_COLUMN_MAX
import org.example.project.ui.PRIORITY_COLUMN_MIN
import org.example.project.ui.SheetColors
import org.example.project.ui.ShortcutHint
import org.example.project.ui.TransientPopupLayer
import org.example.project.ui.TaskPalette
import org.example.project.ui.TaskHueMemo
import org.example.project.ui.rememberTaskHues
import org.example.project.ui.transientPopupCard
import org.example.project.ui.windowDragHandle
import org.example.project.ui.TaskTreeFindBar
import org.example.project.ui.TaskSheetExpandArrow
import org.example.project.ui.TaskSheetTitleBounds
import org.example.project.ui.taskSheetTitleBounds
import org.example.project.ui.taskSheetGuideLines
import org.example.project.ui.isModifierKey
import org.example.project.ui.printableChar
import org.example.project.ui.EditMenuItem
import org.example.project.ui.EditMenuRowActions
import org.example.project.ui.EditModeMenuBlock
import org.example.project.ui.EditModeOption
import kotlinx.coroutines.withTimeoutOrNull

/** Width of one weight-table column (text field + stacked +/- buttons + pin button). */
private val WEIGHT_COLUMN_WIDTH = 130.dp

/** PRD §10: width of the per-task minimum-time field (minutes input + stacked +/- buttons + unit). */
private val MIN_TIME_COLUMN_WIDTH = 72.dp

/**
 * Renders a priority fraction (0..1) as a percentage with at most one decimal: 50%, 33.3%, 0.4%.
 *
 * `internal`, not private: the "All tasks" window prints the very same absolute priority, and a second
 * copy of this rounding is how two readouts of one number start disagreeing at the first decimal.
 */
internal fun formatPriorityPercent(fraction: Double): String {
    val tenths = (fraction * 1000).roundToInt()
    val whole = tenths / 10
    val decimal = tenths % 10
    return if (decimal == 0) "$whole%" else "$whole.$decimal%"
}

/** Renders a weight value with a decimal comma, dropping a redundant ",0" (PRD §5 "numbers and comma"). */
private fun formatWeight(value: Double): String {
    val text = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    return text.replace('.', ',')
}

/**
 * Pending drop location during a double-click & drag move. Keyed by [renderVia] as well as
 * [cellId] so the blue drop line is shown only on the dragged occurrence and not on mirrored
 * copies of the same cell expanded elsewhere (PRD §3: "the blue line and blur aren't mirrored").
 */
internal data class MoveDropTarget(
    val cellId: CellId,
    val insertBefore: Boolean,
    val renderVia: CellId?,
)

/**
 * PRD §4 Find & replace: what the Ctrl+F bar asks the tree to paint over its titles. Every hit of [query]
 * is shaded, and the one the bar is sitting on ([current]) is shaded more strongly — so the row the ↑/↓
 * arrows just revealed is told apart from its neighbours at a glance.
 *
 * Passed down whole rather than as a per-cell map: the ranges are cheap to recompute from a title, and a
 * map would have to be rebuilt on every keystroke for a tree the user is mostly not looking at.
 */
internal data class TreeSearchHighlight(
    val query: String,
    val options: TaskTreeSearch.Options,
    val current: TaskTreeSearch.Match?,
)

/** Every hit of [highlight] inside [title]; empty when nothing is being searched for. */
private fun searchRangesIn(title: String, highlight: TreeSearchHighlight?): List<IntRange> {
    if (highlight == null || highlight.query.isEmpty() || title.isEmpty()) return emptyList()
    return TaskTreeSearch.ranges(title, highlight.query, highlight.options)
}

/**
 * PRD §4 Find & replace: [title] with every hit shaded, and [current] — the one the find bar is sitting on
 * — shaded more strongly. Falls back to the plain string when nothing matches, so a row outside the search
 * allocates no annotations at all.
 *
 * The empty title still renders as a single space, as it did before: a zero-width row would collapse the
 * cell and misalign the sub-list's percentage column.
 */
private fun highlightedTitle(
    title: String,
    ranges: List<IntRange>,
    current: IntRange?,
): AnnotatedString {
    val text = title.ifEmpty { " " }
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (range in ranges) {
            if (range.first < 0 || range.last >= text.length) continue
            val isCurrent = current != null && range.first == current.first && range.last == current.last
            addStyle(
                SpanStyle(
                    background =
                        if (isCurrent) SheetColors.searchCurrentFill else SheetColors.searchMatchFill,
                ),
                range.first,
                range.last + 1,
            )
        }
    }
}

@Composable
fun TaskSchedulerScreen(
    modifier: Modifier = Modifier,
    store: SchedulerStore? = null,
    vm: TaskSchedulerViewModel = viewModel { TaskSchedulerViewModel(store = store) },
    // PRD §5: opening/closing the priority-weight window is hoisted to the app so the window can be drawn
    // on the top floating-window layer (above the calendar) and dismissed by clicks anywhere. Pass the
    // sub-list id to open it, or null to close. Defaults make the screen usable standalone (previews/tests).
    onSetWeightWindow: (CellListId?) -> Unit = {},
    // PRD §5: same hoisting for the relative-priority window (the percentage's right-click menu). Pass the
    // clicked cell's id to open it, or null to close.
    onSetRelativeWindow: (CellId?) -> Unit = {},
    // PRD §13: the same hoisting for the two sort-2 pop-ups the tree opens — the "edit task" window and the
    // "deep copy" depth window. Drawn by the app so they land on the top layer, above every floating window.
    onSetEditTask: (TaskId?) -> Unit = {},
    onSetDeepCopyCell: (CellId?) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    // PRD §5: absolute priority percentage per task, displayed at the right of each populated cell.
    val priorities = SchedulerDomain.absoluteTaskPriorities(state)
    val focusRequester = remember { FocusRequester() }

    // The task-tree selector above the tree: its draft name, edit mode, and whether its field holds focus
    // (which is what reveals its menus and hands it the keyboard). Hoisted here so this screen's key
    // handler can commit on Enter and revert on Escape.
    var treeFieldFocused by remember { mutableStateOf(false) }
    var treeNameDraft by remember { mutableStateOf("") }
    var treeEditMode by remember { mutableStateOf(TaskTreeEditMode.Change) }
    val activeTreeTitle = state.activeTaskTree?.title.orEmpty()

    // The field shows the selected tree's name whenever the user is not typing in it — so a switch, a rename,
    // an undo or a remote pull are all reflected without the field ever fighting the user's caret.
    LaunchedEffect(activeTreeTitle, treeFieldFocused) {
        if (!treeFieldFocused) treeNameDraft = activeTreeTitle
    }
    // Rename is meaningless with no tree selected (and the selector hides the mode there), so a state that
    // loses its tree — undo of the first creation, a pull — must not leave the field renaming nothing.
    LaunchedEffect(state.activeTaskTreeId) {
        if (state.activeTaskTreeId == null) treeEditMode = TaskTreeEditMode.Change
    }

    // Enter / the selector's own button: rename the selected tree, switch to the tree of that exact name, or
    // create one. Blank commits nothing (the reducers no-op on it anyway).
    val commitTaskTreeName = {
        val name = treeNameDraft.trim()
        val activeId = state.activeTaskTreeId
        when {
            name.isEmpty() -> Unit
            treeEditMode == TaskTreeEditMode.Rename && activeId != null ->
                vm.dispatch(SchedulerIntent.RenameTaskTree(activeId, name))
            else -> {
                val existing = SchedulerDomain.taskTreeIdForTitle(state, name)
                if (existing != null) vm.dispatch(SchedulerIntent.SelectTaskTree(existing))
                else vm.dispatch(SchedulerIntent.CreateTaskTree(name))
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(
            text = "Task Scheduler",
            style = MaterialTheme.typography.titleLarge,
            // Shifted right so the lateral-menu collapse bookmark («/»), which straddles the content's
            // left edge, doesn't cover the start of the title.
            modifier = Modifier
                .padding(start = 40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { vm.dispatch(SchedulerIntent.ClearSelection) },
                ),
        )
        Spacer(Modifier.height(8.dp))

        // Right above the task tree: which named task tree is on screen (and how to pick another).
        TaskTreeSelector(
            state = state,
            draft = treeNameDraft,
            mode = treeEditMode,
            focused = treeFieldFocused,
            onDraftChange = { treeNameDraft = it },
            onModeChange = { treeEditMode = it },
            onFocusChange = { treeFieldFocused = it },
            onCommit = {
                commitTaskTreeName()
                focusRequester.requestFocus()
            },
            onIntent = { intent -> vm.dispatch(intent) },
        )

        Spacer(Modifier.height(8.dp))

        TaskTreeView(
            state = state,
            priorities = priorities,
            onIntent = { intent -> vm.dispatch(intent) },
            // PRD §7/§8: the tree only owns the keyboard while it is the focused surface AND the selector's
            // name field above it does not hold it — that field's menus close the moment it loses focus, so
            // the tree's refocus effect must not pull focus back out of it.
            keyboardActive = state.focusedWindow == AppWindow.Tree && !treeFieldFocused,
            refocusWindow = AppWindow.Tree,
            modifier = Modifier.fillMaxSize(),
            onSetWeightWindow = onSetWeightWindow,
            onSetRelativeWindow = onSetRelativeWindow,
            onSetEditTask = onSetEditTask,
            onSetDeepCopyCell = onSetDeepCopyCell,
            focusRequester = focusRequester,
            // While the selector's field holds focus it owns the keyboard: Enter commits the typed name and
            // Escape restores the selected tree's, both handing focus back to the tree; everything else
            // (arrows, Backspace, its own Ctrl+A/C/V) reaches the field.
            aboveTreeKeyHandler = { event ->
                if (!treeFieldFocused) {
                    null
                } else {
                    when (event.key) {
                        Key.Escape -> {
                            treeNameDraft = activeTreeTitle
                            focusRequester.requestFocus()
                            true
                        }
                        Key.Enter -> {
                            commitTaskTreeName()
                            focusRequester.requestFocus()
                            true
                        }
                        else -> false
                    }
                }
            },
        )
    }
}


@Composable
internal fun CellListSection(
    state: SchedulerState,
    listId: CellListId,
    renderVia: CellId?,
    depth: Int,
    visibleOrder: List<CellId>,
    priorities: Map<TaskId, Double>,
    /**
     * Each task's own colour — its place in the one colour space the tree partitions
     * ([org.example.project.scheduler.domain.TaskColorSpace]). Keyed by task, not by cell, so every
     * occurrence of a mirrored task is painted the same.
     */
    taskColors: Map<TaskId, Color>,
    /** PRD §4 Find & replace: what the Ctrl+F bar wants shaded, or null while the bar is closed. */
    searchHighlight: TreeSearchHighlight?,
    onTogglePriorityWeights: (CellListId) -> Unit,
    onOpenRelativePriority: (CellId) -> Unit,
    minTimeEditCellId: CellId?,
    onToggleMinTimeEdit: (CellId) -> Unit,
    onOpenTaskEdit: (TaskId) -> Unit,
    onCopyCell: (CellId) -> Unit,
    onDeepCopyCell: (CellId) -> Unit,
    moveDragActive: Boolean,
    moveDropTarget: MoveDropTarget?,
    resolveRowAt: (Float) -> Pair<VisibleOccurrence, Boolean>?,
    onRowBounds: (VisibleOccurrence, Float, Float) -> Unit,
    onMoveDragStart: () -> Unit,
    onMoveDropHover: (CellId, Boolean, CellId?) -> Unit,
    onMoveDragEnd: () -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
    /** PRD §4: one extra cell at the end of every row — the default sub-tree's switch. Null in the tree. */
    rowTrailing: (@Composable (CellId) -> Unit)? = null,
    /** PRD §7/§8: "go to task tree" on the row's §13 menu, or null where the entry has no meaning. */
    onGoToTaskTree: ((TaskId) -> Unit)? = null,
    /** PRD §7 "All tasks": [depth] 0 rows carry no Mode selector — they are always renaming. */
    rootRenameOnly: Boolean = false,
) {
    val list = state.lists[listId] ?: return

    // PRD §2 Priority Display: align this sublist's percentages at one horizontal position — the
    // widest cell text in the list, clamped to [MIN, MAX].
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val cellTextPx: Map<CellId, Int> =
        list.cellIds.associateWith { id ->
            val title = state.cells[id]?.taskId?.let { state.tasks[it]?.title }.orEmpty()
            if (title.isEmpty()) 0 else textMeasurer.measure(title, bodyStyle).size.width
        }
    val priorityColumnWidth: Dp =
        with(density) { (cellTextPx.values.maxOrNull() ?: 0).toDp() }
            .coerceIn(PRIORITY_COLUMN_MIN, PRIORITY_COLUMN_MAX)
    val priorityColumnPx = with(density) { priorityColumnWidth.toPx() }

    list.cellIds.forEach { cellId ->
        val cell = state.cells[cellId] ?: return@forEach
        val title = cell.taskId?.let { state.tasks[it]?.title }.orEmpty()
        val selectable = SchedulerDomain.isSelectableCell(state, cellId)
        val showHighlight =
            SchedulerDomain.shouldShowSelectionHighlight(state.selection, cellId, renderVia)
        val isMainSelection = selectable && showHighlight && state.selection.main == cellId
        val isInSelectionRange = selectable && showHighlight
        val isEditing =
            state.editSession?.let { it.cellId == cellId && it.renderVia == renderVia } ?: false
        val editDraft = if (isEditing) state.editSession!!.draftText else title
        val hasChildren = SchedulerDomain.hasExpandableSubTree(state, cellId)
        val expanded = cellId in state.expanded

        val priorityLabel =
            cell.taskId?.let { priorities[it] }?.let(::formatPriorityPercent)

        val isInActiveSelection = SchedulerDomain.isInActiveSelection(state.selection, cellId)
        val canMoveFromCell =
            isInActiveSelection &&
                SchedulerDomain.canDragMoveSelection(state, state.selection)
        // Blur the cells being dragged. Scope it to this occurrence via the render-via–aware
        // highlight so mirrored copies of the same cell stay sharp (PRD §3: blur isn't mirrored).
        val isBeingMoved =
            moveDragActive && isInSelectionRange &&
                SchedulerDomain.canDragMoveSelection(state, state.selection)

        TaskRow(
            depth = depth,
            cellId = cellId,
            renderVia = renderVia,
            displayTitle = if (isEditing) editDraft else title,
            isMainSelection = isMainSelection,
            isInSelectionRange = isInSelectionRange,
            selectable = selectable,
            isEditing = isEditing,
            hasChildren = hasChildren,
            expanded = expanded,
            moveDropBefore =
                moveDropTarget?.cellId == cellId &&
                    moveDropTarget.renderVia == renderVia &&
                    moveDropTarget.insertBefore,
            moveDropAfter =
                moveDropTarget?.cellId == cellId &&
                    moveDropTarget.renderVia == renderVia &&
                    !moveDropTarget.insertBefore,
            canMoveFromCell = canMoveFromCell,
            isBeingMoved = isBeingMoved,
            priorityLabel = priorityLabel,
            priorityColumnWidth = priorityColumnWidth,
            taskColor = cell.taskId?.let { taskColors[it] },
            // The hits are shaded on the DISPLAYED title, so a cell being edited shows none: its draft is
            // not what was searched, and the caret is the user's business.
            searchRanges = if (isEditing) emptyList() else searchRangesIn(title, searchHighlight),
            currentSearchRange =
                searchHighlight?.current
                    ?.takeIf { !isEditing && it.cellId == cellId && it.renderVia == renderVia }
                    ?.let { it.start until it.end },
            textOverflow = (cellTextPx[cellId] ?: 0) > priorityColumnPx,
            minMinutes = cell.taskId?.let { state.tasks[it]?.minimumMinutes } ?: 0,
            minTimeEditing = minTimeEditCellId == cellId,
            // PRD §13: the contextual menu appears for any populated cell (leaf or parent); null for
            // empty cells and the root/main cell.
            cellMenu =
                cell.taskId
                    ?.takeIf { selectable }
                    ?.let { taskId ->
                        TaskCellMenuActions(
                            // PRD §13 "start this task now": the plan puts this task at the now-line. It names
                            // ONE task however many cells are selected — unlike "copy", "start *this* task"
                            // has no meaning for a block — and only a schedulable leaf can be asked for.
                            onStartNow =
                                if (SchedulerDomain.isLeafTask(state, taskId)) {
                                    { onIntent(SchedulerIntent.ForceTaskStart(taskId)) }
                                } else {
                                    null
                                },
                            onEdit = { onOpenTaskEdit(taskId) },
                            // PRD §7/§8: offered only where the surface is NOT the tree — the "All tasks"
                            // window today. Same entry, same name and the same RevealCell primitive the
                            // calendar panel's menu uses.
                            onGoToTaskTree = onGoToTaskTree?.let { go -> { go(taskId) } },
                            onCopy = { onCopyCell(cellId) },
                            onDeepCopy = { onDeepCopyCell(cellId) },
                            onCollapseSubtree =
                                if (hasChildren) {
                                    { onIntent(SchedulerIntent.CollapseSubtree(cellId)) }
                                } else {
                                    null
                                },
                            // PRD §7/§13: the template on demand. Like "copy", it acts on the whole block
                            // when the right-click lands inside a multi-selection.
                            onAddDefaultSubtree =
                                if (state.defaultSubtreeIsEmpty) {
                                    null
                                } else {
                                    {
                                        onIntent(
                                            SchedulerIntent.AddDefaultSubtree(
                                                SchedulerDomain.contextMenuCopyTargets(
                                                    state,
                                                    state.selection,
                                                    cellId,
                                                ),
                                            ),
                                        )
                                    }
                                },
                        )
                    },
            onTogglePriorityWeights = { onTogglePriorityWeights(listId) },
            onOpenRelativePriority = { onOpenRelativePriority(cellId) },
            onSetMinTime = { minutes ->
                cell.taskId?.let { onIntent(SchedulerIntent.SetTaskMinimumTime(it, minutes)) }
            },
            onActivateMinTime = {
                // Select this cell so the input persists (PRD §10: it reverts when another cell is
                // selected) and typing is routed to the field instead of entering Edit Mode.
                onIntent(
                    SchedulerIntent.ClickCell(
                        cellId = cellId,
                        ctrl = false,
                        shift = false,
                        visibleOrder = visibleOrder,
                        renderVia = renderVia,
                        forceClearMulti = true,
                    ),
                )
                onToggleMinTimeEdit(cellId)
            },
            onClick = { clicked, ctrl, shift, forceClearMulti ->
                if (!selectable) return@TaskRow
                onIntent(
                    SchedulerIntent.ClickCell(
                        cellId = clicked,
                        ctrl = ctrl,
                        shift = shift,
                        visibleOrder = visibleOrder,
                        renderVia = renderVia,
                        forceClearMulti = forceClearMulti,
                    ),
                )
            },
            onDragSelect = { anchor, hover ->
                onIntent(
                    SchedulerIntent.DragSelectCells(
                        anchorCellId = anchor,
                        hoverCellId = hover,
                        visibleOrder = visibleOrder,
                        renderVia = renderVia,
                    ),
                )
            },
            moveDragActive = moveDragActive,
            resolveRowAt = resolveRowAt,
            onRowBounds = onRowBounds,
            onMoveDragStart = onMoveDragStart,
            onMoveDropHover = { target, insertBefore, via ->
                onMoveDropHover(target, insertBefore, via)
            },
            onMoveDragEnd = onMoveDragEnd,
            onDoubleClick = {
                if (selectable && !isEditing) {
                    onIntent(SchedulerIntent.BeginEdit(cellId))
                }
            },
            onTextChange = { newText ->
                onIntent(SchedulerIntent.UpdateEditText(newText))
            },
            onExitEdit = { navigation ->
                onIntent(SchedulerIntent.ExitEdit(navigation))
            },
            onToggleExpand = {
                if (hasChildren) onIntent(SchedulerIntent.ToggleExpand(cellId))
            },
            editMenus =
                if (isEditing) {
                    {
                        EditModeMenus(
                            state = state,
                            cellId = cellId,
                            draftText = editDraft,
                            onIntent = onIntent,
                            // PRD §7: a root row of the "All tasks" window is always renaming, so it is
                            // offered no choice (the reducer opens its session in Rename mode to match).
                            hideModeSelector = rootRenameOnly && depth == 0,
                        )
                    }
                } else {
                    null
                },
            rowTrailing = rowTrailing,
        )

        if (expanded && hasChildren) {
            val childListId = state.tasks[cell.taskId]!!.childListId!!
            CellListSection(
                state = state,
                listId = childListId,
                renderVia = cellId,
                depth = depth + 1,
                visibleOrder = visibleOrder,
                priorities = priorities,
                taskColors = taskColors,
                searchHighlight = searchHighlight,
                onTogglePriorityWeights = onTogglePriorityWeights,
                onOpenRelativePriority = onOpenRelativePriority,
                minTimeEditCellId = minTimeEditCellId,
                onToggleMinTimeEdit = onToggleMinTimeEdit,
                onOpenTaskEdit = onOpenTaskEdit,
                onCopyCell = onCopyCell,
                onDeepCopyCell = onDeepCopyCell,
                moveDragActive = moveDragActive,
                moveDropTarget = moveDropTarget,
                resolveRowAt = resolveRowAt,
                onRowBounds = onRowBounds,
                onMoveDragStart = onMoveDragStart,
                onMoveDropHover = onMoveDropHover,
                onMoveDragEnd = onMoveDragEnd,
                onIntent = onIntent,
                rowTrailing = rowTrailing,
                onGoToTaskTree = onGoToTaskTree,
                rootRenameOnly = rootRenameOnly,
            )
        }
    }
}

/**
 * The two edit modes of the task-tree selector, mirroring a cell's Edit Mode (PRD §4): [Change] picks
 * *which* task tree the app shows (the tree menu is shown), [Rename] renames the selected one in place.
 */
internal enum class TaskTreeEditMode { Change, Rename }

/**
 * The **task tree selector** sitting right above the task tree: a name field that says which of the
 * account's named task trees ([org.example.project.scheduler.state.TaskTreeEntry]) is on screen, and picks
 * another one — built exactly like a cell's task selection (PRD §4), because it is the same gesture applied
 * to the whole tree:
 *  - a **Mode** selector (Change task tree / Rename), shown only once a tree is selected — there is nothing
 *    to rename before that, mirroring how a cell being *created* hides the selector;
 *  - a **Task trees** menu — the identity rows, shown in [TaskTreeEditMode.Change] only: `tree-<today>`
 *    first and always, then "New task tree", then the trees whose titles are similar to what is typed (see
 *    [SchedulerDomain.taskTreeMenuEntries]). A row here *acts*: it opens a tree, or creates one;
 *  - a **Title suggestions** menu of every tree title containing what is typed — empty text lists them all,
 *    which is how the trees are browsed. Picking one only fills the field (as in a cell); Enter commits it.
 *
 * The three sections are the shared [EditModeMenuBlock], so they render in the one order PRD §4 fixes and
 * look identical to every other naming field. The menus are focus-gated like the reminders manager's editor:
 * they appear only while the field holds focus, so the tree below is not permanently pushed down — hence
 * `focusPreserving = true`, so clicking a row cannot blur the field and collapse the block mid-pick.
 */
@Composable
private fun TaskTreeSelector(
    state: SchedulerState,
    draft: String,
    mode: TaskTreeEditMode,
    focused: Boolean,
    onDraftChange: (String) -> Unit,
    onModeChange: (TaskTreeEditMode) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onCommit: () -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
) {
    val activeId = state.activeTaskTreeId
    // `tree-YYYY-MM-DD` for today, read off the same clock the first-startup seed used so the row the menu
    // leads with and the tree that seeding created carry the identical name. O(1), so recomputing it per
    // recomposition rather than remembering it is what keeps the row correct across midnight.
    val todayTitle = SchedulerDomain.defaultTaskTreeTitle(SchedulerReducer.clock.nowMillis())
    val entries = SchedulerDomain.taskTreeMenuEntries(state, draft, todayTitle)
    val suggestions = SchedulerDomain.taskTreeTitleSuggestions(state, draft)
    // The tree the field currently designates: the one whose title IS the typed text. Highlighted in the
    // menu, so the user can see whether Enter would switch to an existing tree or create one.
    val matchedId = SchedulerDomain.taskTreeIdForTitle(state, draft)

    Column(
        modifier = Modifier.padding(start = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text("Task tree") },
            placeholder = { Text("No task tree selected") },
            singleLine = true,
            modifier = Modifier
                .widthIn(min = 220.dp, max = 320.dp)
                .onFocusChanged { onFocusChange(it.isFocused) },
        )

        if (!focused) return@Column

        EditModeMenuBlock(
            // Mode — only once a tree is selected: with none there is nothing to rename, and the field can
            // only mean "create or pick one" (the same reason a cell being created hides its selector).
            modeOptions =
                if (activeId != null) {
                    listOf(
                        EditModeOption(
                            label = "Change task tree",
                            selected = mode == TaskTreeEditMode.Change,
                            onSelect = { onModeChange(TaskTreeEditMode.Change) },
                        ),
                        EditModeOption(
                            label = "Rename",
                            selected = mode == TaskTreeEditMode.Rename,
                            onSelect = { onModeChange(TaskTreeEditMode.Rename) },
                        ),
                    )
                } else {
                    emptyList()
                },
            identityLabel = "Task trees",
            // Task trees (the identity menu) — Change mode only, by the user's rule: in Rename mode the field
            // is naming the selected tree, so there is no other tree for a row to act on. Shown whatever is
            // typed, empty field included, because its first row — today's tree — is offered unconditionally.
            identityRows =
                if (mode == TaskTreeEditMode.Change) {
                    entries.map { entry ->
                        EditMenuItem(
                            label = entry.label,
                            selected = when (entry.kind) {
                                // "New task tree" is what Enter would do exactly when nothing matches the
                                // typed name.
                                SchedulerDomain.TaskTreeMenuEntry.Kind.New ->
                                    draft.isNotBlank() && matchedId == null
                                else -> entry.id != null && entry.id == matchedId
                            },
                        ) {
                            // The field follows the row, so it can never sit on the half-typed text that
                            // merely *matched* the tree now open ("Wor" left over from picking "Workshop").
                            when (entry.kind) {
                                SchedulerDomain.TaskTreeMenuEntry.Kind.New ->
                                    onIntent(SchedulerIntent.CreateTaskTree(draft))
                                else -> {
                                    onDraftChange(entry.label)
                                    if (entry.id != null) onIntent(SchedulerIntent.SelectTaskTree(entry.id))
                                    // Today's tree, on a day that has none yet.
                                    else onIntent(SchedulerIntent.CreateTaskTree(entry.label))
                                }
                            }
                        }
                    }
                } else {
                    emptyList()
                },
            // Title suggestions — in both modes, as in a cell: picking one fills the field (Rename then
            // renames the selected tree to it, Change switches to it) and Enter commits.
            suggestions = suggestions.map { suggestion ->
                EditMenuItem(
                    label = suggestion,
                    selected = suggestion.equals(draft.trim(), ignoreCase = true),
                ) { onDraftChange(suggestion) }
            },
            focusPreserving = true,
        )

        // The field is not a form: nothing is applied until it is committed, so the commit needs a control of
        // its own for pointer-only use (Enter does the same thing from the keyboard).
        if (draft.isNotBlank()) {
            TextButton(onClick = onCommit) {
                Text(
                    when {
                        mode == TaskTreeEditMode.Rename -> "Rename"
                        matchedId != null -> "Open"
                        else -> "Create"
                    },
                )
            }
        }
    }
}

/**
 * PRD §4 Edit Mode: the menus under a task cell being edited — the **Mode** selector, the **Tasks** id menu
 * and the **Title suggestions** menu, in the one order [EditModeMenuBlock] fixes for every naming field.
 *
 * The id rows carry one thing the other two menus do not: a **right-click contextual menu** holding
 * "go to task" (see [EditMenuRowActions]). An id row names one task, so there is one place to go; a title
 * suggestion names a string several tasks may share, and the Mode selector names no task at all.
 */
@Composable
internal fun EditModeMenus(
    state: SchedulerState,
    cellId: CellId,
    draftText: String,
    onIntent: (SchedulerIntent) -> Unit,
    /**
     * PRD §7 "All tasks": hide the Change Task / Rename selector, because in that window's root the answer
     * is fixed — the row IS the task, so it is always renaming. Hiding it is only half the rule: the session
     * is *opened* in Rename mode by `SchedulerReducer.reduceInTaskList`, which is the one place that knows
     * which cells are that window's roots.
     */
    hideModeSelector: Boolean = false,
) {
    val session = state.editSession ?: return
    // A cell that had no task before this edit began is being *created* — it is always in Change Task mode
    // (there is no existing title to Rename), so the Mode selector is hidden, mirroring the reminders manager.
    val isBeingCreated = session.treeBefore.cells[cellId]?.taskId == null

    // Only the in-progress "New task" draft is hidden (it's already the "New task" row itself). A picked
    // existing task must stay listed so it can render as selected (purple) — excluding it here would drop it
    // from the entries and leave [changeTaskMenuSelectedIndex] unable to match.
    val taskEntries =
        if (session.mode == CellEditMode.ChangeTask) {
            SchedulerDomain.changeTaskMenuEntries(
                state,
                cellId,
                draftText,
                excludeTaskId = session.newTaskDraftId,
            )
        } else {
            emptyList()
        }

    val modeOptions =
        if (isBeingCreated || hideModeSelector) {
            emptyList()
        } else {
            listOf(
                EditModeOption(
                    label = "Change Task",
                    selected = session.mode == CellEditMode.ChangeTask,
                    onSelect = { onIntent(SchedulerIntent.SetEditMode(CellEditMode.ChangeTask)) },
                ),
                EditModeOption(
                    label = "Rename",
                    selected = session.mode == CellEditMode.Rename,
                    onSelect = { onIntent(SchedulerIntent.SetEditMode(CellEditMode.Rename)) },
                ),
            )
        }
    // The Tasks menu is worth showing only beyond the lone "New task" row (the reminders manager applies the
    // same rule to its "New Reminder" row).
    val identityRows =
        if (taskEntries.size > 1) {
            val selectedIndex =
                SchedulerDomain.changeTaskMenuSelectedIndex(taskEntries, session.selectedAssignTaskId)
            taskEntries.mapIndexed { index, entry ->
                // PRD §4: an id row's right-click menu — "go to task", which reveals that task where it
                // lives. It goes through [SchedulerIntent.RevealCell], the find bar's own primitive and the
                // very one PRD §8's "go to task tree" uses, so the way in is expanded as ONE history unit
                // and the reveal ends this edit session first (a §4 Forced Exit, like clicking another cell).
                //
                // Nothing is focused: the surface being edited IS the tree the row is revealed in, whichever
                // of the three drawings of it this is — the reveal follows `state`/`onIntent`, so in the
                // "All tasks" window and the §4 template it lands on that window's own rows, not the
                // account tree's, which is exactly what "go to task" can mean there.
                //
                // Greyed (a null handler) wherever no cell shows the task: the "New task" row, a detached
                // parent, a tombstone kept alive only for its records. That is the same `null` answer
                // [SchedulerDomain.firstTaskOccurrence] gives the calendar panel's entry, said as a disabled
                // row rather than as a notice, because here the user is looking straight at the row.
                val occurrence = entry.taskId?.let { SchedulerDomain.firstTaskOccurrence(state, it) }
                EditMenuItem(
                    label = entry.label,
                    selected = index == selectedIndex,
                    actions = EditMenuRowActions(
                        onGoToTask = occurrence?.let { found ->
                            { onIntent(SchedulerIntent.RevealCell(found.cellId, found.ancestors)) }
                        },
                    ),
                ) {
                    if (entry.taskId == null) {
                        onIntent(SchedulerIntent.SelectCreateAssignTask)
                    } else {
                        onIntent(SchedulerIntent.PickTaskFromMenu(entry.taskId))
                    }
                }
            }
        } else {
            emptyList()
        }
    val suggestions = SchedulerDomain.titleSuggestions(state, draftText).map { suggestion ->
        EditMenuItem(suggestion) { onIntent(SchedulerIntent.PickTitleSuggestion(suggestion)) }
    }

    // Render nothing when there is no menu to show, so the empty container takes no vertical space and the
    // row below is not pushed down by a blank gap. The row only lowers while a menu is actually visible.
    if (modeOptions.isEmpty() && identityRows.isEmpty() && suggestions.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EditModeMenuBlock(
            modeOptions = modeOptions,
            identityLabel = "Tasks",
            identityRows = identityRows,
            suggestions = suggestions,
        )
    }
}

/** Change-task-only menus for the priority window's local, empty placeholder editor. */
@Composable
private fun OptionalTaskEditMenus(
    state: SchedulerState,
    cellId: CellId,
    draftText: String,
    onDraftChange: (String) -> Unit,
    onPickTask: (TaskId) -> Unit,
) {
    val taskRows = SchedulerDomain.eligibleAssignTaskIds(state, cellId, draftText).map { taskId ->
        EditMenuItem(
            label = state.tasks[taskId]?.title.orEmpty(),
            onClick = { onPickTask(taskId) },
        )
    }
    val suggestionRows = SchedulerDomain.titleSuggestions(state, draftText).map { suggestion ->
        EditMenuItem(suggestion) { onDraftChange(suggestion) }
    }
    if (taskRows.isEmpty() && suggestionRows.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EditModeMenuBlock(
            modeOptions = emptyList(),
            identityLabel = "Tasks",
            identityRows = taskRows,
            suggestions = suggestionRows,
        )
    }
}

/** Steps [value] by [delta], clamps to [0, maxValue], and rounds off binary-float noise. */
private fun stepWeight(value: Double, delta: Double, maxValue: Double): Double {
    val next = (value + delta).coerceIn(0.0, maxValue)
    return (next * 10000).roundToInt() / 10000.0
}

/**
 * PRD §5 priority weight: one weight-table cell — a number input (digits and a decimal comma) with
 * the increment/decrement buttons stacked vertically to its right. Each step adds/removes [step]
 * (1 for cells, 0.1 for the header row); the value is clamped to `[0, maxValue]`.
 */
@Composable
private fun WeightInputCell(
    value: Double,
    onSet: (Double) -> Unit,
    pinned: Boolean,
    onTogglePinned: () -> Unit,
    modifier: Modifier = Modifier,
    maxValue: Double = Double.POSITIVE_INFINITY,
    step: Double = 1.0,
) {
    var text by remember(value) { mutableStateOf(formatWeight(value)) }
    Row(
        modifier = modifier.width(WEIGHT_COLUMN_WIDTH).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() || it == ',' || it == '.' }
                text = cleaned
                cleaned.replace(',', '.').toDoubleOrNull()?.let { onSet(it.coerceIn(0.0, maxValue)) }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(SheetColors.activeBorder),
            modifier = Modifier
                .weight(1f)
                .border(1.dp, SheetColors.grid)
                .padding(horizontal = 4.dp, vertical = 3.dp),
        )
        Column {
            WeightStepButton(label = "▲", onClick = { onSet(stepWeight(value, step, maxValue)) })
            WeightStepButton(label = "▼", onClick = { onSet(stepWeight(value, -step, maxValue)) })
        }
        WeightPinButton(pinned = pinned, onClick = onTogglePinned)
    }
}

@Composable
private fun WeightPinButton(pinned: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 22.dp)
            .border(1.dp, if (pinned) SheetColors.activeBorder else SheetColors.grid)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (pinned) "pinned" else "pin",
            style = MaterialTheme.typography.labelSmall,
            color = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeightStepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 16.dp, height = 11.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * PRD §10 Minimum time: a task's minimum time (in minutes) shown to the right of the priority-weight
 * display — and to the right of the weight table when it is open, so it shifts as columns change. An
 * integer input field with the increment/decrement buttons stacked to its right, mirroring the weight
 * fields; the value is clamped to ≥ 0.
 */
@Composable
private fun MinTimeInputCell(
    minutes: Int,
    onSet: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    // Initialised once when the field opens, with the caret at the right of the written time (PRD §10).
    // Not keyed on `minutes`, so live edits and the ▲/▼ buttons don't reset the caret every keystroke;
    // external changes (the buttons) are synced back in via the SideEffect below.
    var value by remember {
        mutableStateOf(TextFieldValue(minutes.toString(), TextRange(minutes.toString().length)))
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    SideEffect {
        val current = minutes.toString()
        if (value.text != current) {
            value = TextFieldValue(current, TextRange(current.length))
        }
    }
    Row(
        modifier = modifier.width(MIN_TIME_COLUMN_WIDTH).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { raw ->
                val cleaned = raw.text.filter { it.isDigit() }
                // Keep the caret the user placed when nothing was stripped; only when a non-digit is
                // filtered out do we fall back to a safe caret at the end of the cleaned text.
                value = if (cleaned == raw.text) raw else TextFieldValue(cleaned, TextRange(cleaned.length))
                onSet(cleaned.toIntOrNull()?.coerceAtLeast(0) ?: 0)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(SheetColors.activeBorder),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .border(1.dp, SheetColors.grid)
                .padding(horizontal = 4.dp, vertical = 3.dp),
        )
        Text(
            text = "m",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Column {
            WeightStepButton(label = "▲", onClick = { onSet(minutes + 1) })
            WeightStepButton(label = "▼", onClick = { onSet((minutes - 1).coerceAtLeast(0)) })
        }
    }
}

/**
 * PRD §10 Minimum time (resting state): a plain "{n}m" label occupying the same column as
 * [MinTimeInputCell]. Clicking it expands the field into the editable input (mirroring how clicking
 * the absolute-priority percentage reveals the weight table).
 */
@Composable
private fun MinTimeDisplayCell(
    minutes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(MIN_TIME_COLUMN_WIDTH)
            .padding(horizontal = 2.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 3.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = "${minutes}m",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Vertical blue line marking where a dragged column will be dropped (PRD §5). It fills the height
 * of whichever row it sits in (with a minimum) so the per-row segments stack into one continuous
 * line running all the way down the column.
 */
@Composable
private fun ColumnDropLine() {
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .fillMaxHeight()
            .heightIn(min = 22.dp)
            .width(2.dp)
            .background(SheetColors.activeBorder),
    )
}

/**
 * PRD §5: grab handle sitting above a weight-table column. It is a thick, raised bar with a grip
 * pattern so the user can tell it can be grabbed (drag to reorder) and right-clicked (column menu).
 */
@Composable
private fun ColumnDragHandle(active: Boolean) {
    val accent = if (active) SheetColors.activeBorder else SheetColors.guideLine
    Box(
        modifier = Modifier
            .width(WEIGHT_COLUMN_WIDTH)
            .padding(horizontal = 2.dp)
            .height(20.dp)
            .background(
                if (active) SheetColors.moveDragFill else SheetColors.nonSelectableFill,
                RoundedCornerShape(4.dp),
            )
            .border(1.dp, accent, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // Grip pattern (three short bars) — the conventional "draggable" affordance.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .background(accent, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

/**
 * PRD §5 priority weight table header: a row of grab handles sits right above the header row of
 * editable column weights (each weight clamped 0..1, step 0.1). Grabbing a handle and dragging
 * reorders that column — it gets a grey background and a vertical blue line shows the drop
 * position, with the move committed on release. Right-clicking a handle reveals "Add column to the
 * right", "Reset to default" and (unless it is the only column) "Delete column".
 */
@Composable
private fun WeightTableHeader(
    depth: Int,
    leadingWidth: Dp,
    weightColumns: List<Double>,
    draggedColumn: Int?,
    dropIndex: Int?,
    onDraggedColumnChange: (Int?) -> Unit,
    onDropIndexChange: (Int?) -> Unit,
    onSetColumnWeight: (Int, Double) -> Unit,
    pinnedColumns: Set<Int>,
    onTogglePinnedColumn: (Int) -> Unit,
    onAddColumn: (Int) -> Unit,
    onResetColumn: (Int) -> Unit,
    onDeleteColumn: (Int) -> Unit,
    onMoveColumn: (Int, Int) -> Unit,
) {
    val columnBounds = remember { mutableStateMapOf<Int, ClosedFloatingPointRange<Float>>() }

    fun resolveDrop(windowX: Float): Int {
        for (c in weightColumns.indices) {
            val bounds = columnBounds[c] ?: continue
            val mid = (bounds.start + bounds.endInclusive) / 2f
            if (windowX < mid) return c
        }
        return weightColumns.size
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * INDENT_STEP_DP).dp + 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        // PRD §5: handle row — grab a handle to drag-reorder its column, right-click for the menu.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(leadingWidth))
            weightColumns.forEachIndexed { column, _ ->
                if (draggedColumn != null && dropIndex == column) ColumnDropLine()
                var menuOpen by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .background(
                            if (draggedColumn == column) SheetColors.moveDragFill else Color.Transparent,
                        )
                        .pointerInput(column, weightColumns.size) {
                            val slop = viewConfiguration.touchSlop
                            awaitPointerEventScope {
                                while (true) {
                                    // Wait for a button press. Inspecting the Press event's
                                    // `buttons` directly is the commonMain-safe way to tell a
                                    // right-click from a left-click (awaitFirstDown can't).
                                    var press = awaitPointerEvent()
                                    while (press.type != PointerEventType.Press) {
                                        press = awaitPointerEvent()
                                    }
                                    if (press.buttons.isSecondaryPressed) {
                                        // PRD §5: right-click opens the column menu. Consume so the
                                        // freshly opened popup isn't dismissed by the same click.
                                        press.changes.forEach { it.consume() }
                                        menuOpen = true
                                        continue
                                    }
                                    // Left press → drag-reorder this column. Track the live drop
                                    // target locally (the hoisted state, captured at launch, would
                                    // be stale inside this long-running gesture) and mirror it out
                                    // through the callbacks so the whole column re-renders.
                                    val down = press.changes.first()
                                    down.consume()
                                    var started = false
                                    var traveled = 0f
                                    var localDrop: Int? = null
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (!event.changes.any { it.pressed }) {
                                            if (started) localDrop?.let { onMoveColumn(column, it) }
                                            onDraggedColumnChange(null)
                                            onDropIndexChange(null)
                                            break
                                        }
                                        val change =
                                            event.changes.firstOrNull { it.id == down.id }
                                                ?: event.changes.first()
                                        traveled += change.positionChange().getDistance()
                                        if (!started && traveled > slop) {
                                            started = true
                                            onDraggedColumnChange(column)
                                        }
                                        if (started) {
                                            change.consume()
                                            val windowX =
                                                (columnBounds[column]?.start ?: 0f) + change.position.x
                                            localDrop = resolveDrop(windowX)
                                            onDropIndexChange(localDrop)
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    ColumnDragHandle(active = draggedColumn == column)
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Add column to the right") },
                            onClick = {
                                menuOpen = false
                                onAddColumn(column + 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Reset to default") },
                            onClick = {
                                menuOpen = false
                                onResetColumn(column)
                            },
                        )
                        if (weightColumns.size > 1) {
                            DropdownMenuItem(
                                text = { Text("Delete column") },
                                onClick = {
                                    menuOpen = false
                                    onDeleteColumn(column)
                                },
                            )
                        }
                    }
                }
            }
            if (draggedColumn != null && dropIndex == weightColumns.size) ColumnDropLine()
        }
        // PRD §5: header row of editable column weights, aligned above the cell rows.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(leadingWidth))
            weightColumns.forEachIndexed { column, weight ->
                if (draggedColumn != null && dropIndex == column) ColumnDropLine()
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            val x = coords.positionInWindow().x
                            columnBounds[column] = x..(x + coords.size.width)
                        }
                        .background(
                            if (draggedColumn == column) SheetColors.moveDragFill else Color.Transparent,
                        ),
                ) {
                    // PRD §5: a column header weight can only span 0..1 and steps by 0.1.
                    WeightInputCell(
                        value = weight,
                        onSet = { onSetColumnWeight(column, it) },
                        pinned = column in pinnedColumns,
                        onTogglePinned = { onTogglePinnedColumn(column) },
                        maxValue = 1.0,
                        step = 0.1,
                    )
                }
            }
            if (draggedColumn != null && dropIndex == weightColumns.size) ColumnDropLine()
        }
    }
}

/** Width of the task-title column inside the priority-weight window. */
private val WEIGHT_WINDOW_TITLE_WIDTH = 160.dp

private data class PriorityWeightFieldKey(val cellId: CellId?, val column: Int)

/**
 * PRD §5: a row in the priority-weight window. Existing cells are real members of the sub-list; optional
 * rows are tasks that live in the parent task's subtree but are not currently in this sub-list yet.
 */
internal data class PriorityWeightTableRow(
    val cellId: CellId? = null,
    val taskId: TaskId? = null,
    val title: String = "",
    val isOptional: Boolean = false,
)

internal fun priorityWeightTableRows(
    state: SchedulerState,
    listId: CellListId,
    optionalTaskIds: Set<TaskId> = emptySet(),
): List<PriorityWeightTableRow> {
    val list = state.lists[listId] ?: return emptyList()
    val currentMembers = list.cellIds.mapNotNull { cellId ->
        val cell = state.cells[cellId] ?: return@mapNotNull null
        val taskId = cell.taskId ?: return@mapNotNull null
        if (taskId in optionalTaskIds) return@mapNotNull null
        val title = state.tasks[taskId]?.title.orEmpty()
        if (title.isBlank()) return@mapNotNull null
        PriorityWeightTableRow(cellId = cellId, taskId = taskId, title = title, isOptional = false)
    }

    val parentTaskId = SchedulerDomain.parentTaskIdOfList(state, listId)
    val optional = optionalTaskIds
        .mapNotNull { taskId ->
            val title = state.tasks[taskId]?.title.orEmpty()
            if (title.isBlank()) return@mapNotNull null
            PriorityWeightTableRow(taskId = taskId, title = title, isOptional = true)
        }
        .sortedWith(compareBy<PriorityWeightTableRow> { it.title.lowercase() }.thenBy { it.taskId?.value ?: "" })

    val rows = currentMembers + optional
    val emptyCell = list.cellIds.firstOrNull { state.cells[it]?.taskId == null }
    return if (parentTaskId != null && rows.any { it.taskId != null } && emptyCell != null) {
        rows + PriorityWeightTableRow(cellId = emptyCell, isOptional = true)
    } else {
        rows
    }
}

internal fun priorityWeightTableValue(
    state: SchedulerState,
    listId: CellListId,
    row: PriorityWeightTableRow,
    column: Int,
    optionalTaskIds: Set<TaskId> = emptySet(),
): Double {
    val list = state.lists[listId] ?: return 0.0
    val cellId = row.cellId
    val cell = cellId?.let { state.cells[it] }
    val taskId = row.taskId ?: cell?.taskId
    if (row.isOptional && taskId != null) {
        return list.optionalTaskValues[taskId]?.getOrElse(column) { 0.0 } ?: 0.0
    }
    return cell?.priorityWeights?.getOrElse(column) { 1.0 } ?: 1.0
}

/**
 * PRD §5: everything the priority-weight window can edit about one sub-list — the column headers and each
 * listed cell's weight row. Captured when the window opens so **Cancel** can put it all back.
 */
private data class WeightTableSnapshot(
    val weightColumns: List<Double>,
    val cellWeights: Map<CellId, List<Double>>,
)

private fun weightTableSnapshot(state: SchedulerState, listId: CellListId): WeightTableSnapshot {
    val list = state.lists[listId]
    return WeightTableSnapshot(
        weightColumns = list?.weightColumns.orEmpty(),
        cellWeights = list?.cellIds.orEmpty()
            .mapNotNull { id -> state.cells[id]?.let { id to it.priorityWeights } }
            .toMap(),
    )
}

/**
 * PRD §5 priority-weight window: a floating window opened by clicking a sub-list's absolute priority
 * percentage. Its left side is the editable weight table (a draggable/reorderable column header plus a
 * weight input per cell per column); its right side is a circular (pie) chart of each task's percentage
 * **within this sub-list** — the share the table itself hands out, not the task's absolute priority, so
 * the chart reads as the table's own output. Drawn by the app on the top floating-window layer; the app
 * dismisses it when a press lands outside [onBoundsChange]'s reported bounds.
 *
 * **Cancel** puts the whole table back to what it was when the window opened, as one content delta —
 * so Ctrl+Z undoes the cancel like any other weight edit.
 */
@Composable
internal fun PriorityWeightWindow(
    state: SchedulerState,
    listId: CellListId,
    priorities: Map<TaskId, Double>,
    onIntent: (SchedulerIntent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = state.lists[listId] ?: return
    val taskHues = rememberTaskHues(state, TaskHueMemo.account)
    val taskColors = remember(taskHues) { TaskPalette.sheetColors(taskHues) }
    val optionalTaskIds = list.optionalTaskIds
    var emptyRowEditing by remember(listId) { mutableStateOf(false) }
    var emptyRowDraft by remember(listId) { mutableStateOf("") }
    val tableRows = priorityWeightTableRows(state, listId, optionalTaskIds)
    var offset by remember(listId) { mutableStateOf(Offset.Zero) }
    var draggedColumn by remember(listId) { mutableStateOf<Int?>(null) }
    var columnDropIndex by remember(listId) { mutableStateOf<Int?>(null) }
    var pinnedWeightFields by remember(listId) { mutableStateOf(emptySet<PriorityWeightFieldKey>()) }
    val pinnedWeightColumns = pinnedWeightFields.filter { it.cellId == null }.map { it.column }.toSet()
    // The table as this window found it: captured on the composition that opened it, and kept across
    // every edit made since — Cancel always goes back to the start, never one step.
    val openedTable = remember(listId) { weightTableSnapshot(state, listId) }
    val tableEdited = weightTableSnapshot(state, listId) != openedTable

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, SheetColors.grid),
        // Bound the window to the screen (so the chart on the right is never pushed off-screen).
        // [transientPopupCard] does the rest: it is a sort-2 pop-up.
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .transientPopupCard(onDismiss)
            .widthIn(max = 760.dp)
            .heightIn(max = 600.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .windowDragHandle(onDragEnd = {}) { dragAmount -> offset += dragAmount }
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Priority weights",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.padding(16.dp)) {
                // `fill = false` so a short table keeps the window short; the Cancel bar below always shows.
                Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Top) {
                    // The table takes the remaining width and scrolls if it is wider/taller than the window,
                    // leaving the fixed-width chart column always visible on the right.
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        WeightTableHeader(
                            depth = 0,
                            leadingWidth = WEIGHT_WINDOW_TITLE_WIDTH,
                            weightColumns = list.weightColumns,
                            draggedColumn = draggedColumn,
                            dropIndex = columnDropIndex,
                            onDraggedColumnChange = { draggedColumn = it },
                            onDropIndexChange = { columnDropIndex = it },
                            onSetColumnWeight = { c, w -> onIntent(SchedulerIntent.SetPriorityColumnWeight(listId, c, w)) },
                            pinnedColumns = pinnedWeightColumns,
                            onTogglePinnedColumn = { column ->
                                val field = PriorityWeightFieldKey(cellId = null, column = column)
                                pinnedWeightFields = if (field in pinnedWeightFields) {
                                    pinnedWeightFields - field
                                } else {
                                    pinnedWeightFields + field
                                }
                            },
                            onAddColumn = { i -> onIntent(SchedulerIntent.AddPriorityColumn(listId, i)) },
                            onResetColumn = { c -> onIntent(SchedulerIntent.ResetPriorityColumn(listId, c)) },
                            onDeleteColumn = { c -> onIntent(SchedulerIntent.DeletePriorityColumn(listId, c)) },
                            onMoveColumn = { f, t -> onIntent(SchedulerIntent.MovePriorityColumn(listId, f, t)) },
                        )
                        tableRows.forEach { row ->
                            key(row.taskId ?: row.cellId ?: "empty") {
                            val cellId = row.cellId
                            val cell = cellId?.let { state.cells[it] }
                            val taskId = row.taskId ?: cell?.taskId
                            val isEditing = state.editSession?.cellId == cellId ||
                                (row.isOptional && taskId == null && emptyRowEditing)
                            val title = taskId?.let { state.tasks[it]?.title }.orEmpty()
                            val displayedTitle =
                                if (row.isOptional && taskId == null && emptyRowEditing) emptyRowDraft else title
                            val path =
                                if (row.isOptional && taskId != null) {
                                    RelativePriorityDomain.optionalTaskPath(state, listId, taskId)
                                } else {
                                    emptyList()
                                }
                            val representative = path.lastOrNull()?.let { state.cells[it] }
                            TaskRow(
                                depth = 0,
                                cellId = cellId ?: CellId("priority-weight-$listId-${row.taskId?.value ?: "empty"}"),
                                renderVia = null,
                                displayTitle = displayedTitle,
                                isMainSelection = false,
                                isInSelectionRange = false,
                                // Optional rows with a task id are virtual readouts; the optional row without
                                // one is the real trailing placeholder and must remain editable.
                                selectable = cellId != null && (taskId == null || !row.isOptional),
                                isEditing = isEditing,
                                hasChildren = false,
                                expanded = false,
                                moveDropBefore = false,
                                moveDropAfter = false,
                                canMoveFromCell = false,
                                isBeingMoved = false,
                                priorityLabel = null,
                                priorityColumnWidth = WEIGHT_WINDOW_TITLE_WIDTH,
                                taskColor = taskId?.let { taskColors[it] },
                                searchRanges = emptyList(),
                                currentSearchRange = null,
                                textOverflow = false,
                                minMinutes = taskId?.let { state.tasks[it]?.minimumMinutes } ?: 0,
                                minTimeEditing = false,
                                cellMenu = null,
                                onTogglePriorityWeights = {},
                                onOpenRelativePriority = {},
                                onSetMinTime = {},
                                onActivateMinTime = {},
                                onClick = { clicked, _, _, _ ->
                                    if (row.isOptional && taskId == null) {
                                        emptyRowDraft = ""
                                        emptyRowEditing = true
                                    }
                                },
                                onDragSelect = { _, _ -> },
                                moveDragActive = false,
                                resolveRowAt = { null },
                                onRowBounds = { _, _, _ -> },
                                onMoveDragStart = {},
                                onMoveDropHover = { _, _, _ -> },
                                onMoveDragEnd = {},
                                onDoubleClick = {
                                    if (row.isOptional && taskId == null) {
                                        emptyRowDraft = ""
                                        emptyRowEditing = true
                                    }
                                },
                                onTextChange = { draft ->
                                    if (row.isOptional && taskId == null && cellId != null) {
                                        emptyRowDraft = draft
                                    }
                                },
                                onExitEdit = {
                                    emptyRowEditing = false
                                    emptyRowDraft = ""
                                },
                                onToggleExpand = {},
                                editMenus =
                                    if (row.isOptional && taskId == null && cellId != null && emptyRowEditing) {
                                        {
                                            OptionalTaskEditMenus(
                                                state = state,
                                                cellId = cellId,
                                                draftText = emptyRowDraft,
                                                onDraftChange = { emptyRowDraft = it },
                                                onPickTask = {
                                                    onIntent(SchedulerIntent.AddPriorityWeightTableTask(listId, it))
                                                    emptyRowEditing = false
                                                    emptyRowDraft = ""
                                                },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                rowContent =
                                    if (taskId != null) {
                                        {
                                            for (column in list.weightColumns.indices) {
                                                if (draggedColumn != null && columnDropIndex == column) ColumnDropLine()
                                                Box(
                                                    modifier = Modifier.background(
                                                        if (draggedColumn == column) SheetColors.moveDragFill else Color.Transparent,
                                                    ),
                                                ) {
                                                    val value =
                                                        if (row.isOptional) {
                                                            val taskValue = list.optionalTaskValues[taskId]
                                                            taskValue?.getOrElse(column) { 0.0 } ?: 0.0
                                                        } else {
                                                            cell?.priorityWeights?.getOrElse(column) { 1.0 } ?: 1.0
                                                        }
                                                    val fieldKey = PriorityWeightFieldKey(
                                                        cellId = representative?.id ?: cellId,
                                                        column = column,
                                                    )
                                                    WeightInputCell(
                                                        value = value,
                                                        pinned = fieldKey in pinnedWeightFields,
                                                        onTogglePinned = {
                                                            pinnedWeightFields = if (fieldKey in pinnedWeightFields) {
                                                                pinnedWeightFields - fieldKey
                                                            } else {
                                                                pinnedWeightFields + fieldKey
                                                            }
                                                        },
                                                        onSet = {
                                                            when {
                                                                row.isOptional -> {
                                                                    val old = value
                                                                    val factor = if (old > 0.0) it / old else it
                                                                    onIntent(
                                                                        SchedulerIntent.SetOptionalTaskPathWeight(
                                                                            listId,
                                                                            taskId,
                                                                            column,
                                                                            factor,
                                                                            pinnedCells = pinnedWeightFields
                                                                                .filter { it.column == column }
                                                                                .mapNotNull { it.cellId }
                                                                                .toSet(),
                                                                        ),
                                                                    )
                                                                }
                                                                cellId != null -> {
                                                                    onIntent(SchedulerIntent.SetPriorityWeight(cellId, column, it))
                                                                }
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                            if (draggedColumn != null && columnDropIndex == list.weightColumns.size) ColumnDropLine()
                                        }
                                    } else {
                                        null
                                    },
                            )
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    val chartRows = tableRows.filter { it.taskId != null }
                    PriorityChart(
                        titles = chartRows.map { it.title },
                        // PRD §5: each row's share of THIS sub-list — the number the table on the left sets —
                        // rather than the task's absolute priority (its share of the whole tree).
                        fractions = chartRows.map { row ->
                            row.cellId?.let { RelativePriorityDomain.cellShare(state, it) } ?: 0.0
                        },
                        modifier = Modifier.width(220.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // PRD §5 Cancel: back to the table this window opened on, as one undoable content delta.
                    TextButton(
                        onClick = {
                            onIntent(
                                SchedulerIntent.RestorePriorityWeights(
                                    listId = listId,
                                    weightColumns = openedTable.weightColumns,
                                    cellWeights = openedTable.cellWeights,
                                )
                            )
                        },
                        enabled = tableEdited,
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

/** Distinct slice color for the [index]-th task in a priority pie chart, spread around the hue wheel. */
/**
 * PRD §5 the **relative priority** window, opened from the "relative priority" entry of the percentage's
 * right-click menu. At the top, the priority of this cell's task relative to the ancestor picked in the
 * drop-down (root by default, which is the absolute percentage the cell already shows). Below, one
 * horizontal chain per occurrence of the task under that ancestor, reading from the cell just under the
 * ancestor on the left to the occurrence itself on the right — each showing its share of its own sub-list
 * and a **pin**, which holds that share while the number at the top is retargeted.
 *
 * The pins are per (task, ancestor) pair (see [RelativePriorityPinKey]), so pinning here never affects
 * the window opened for another task or against another ancestor.
 */
@Composable
internal fun RelativePriorityWindow(
    state: SchedulerState,
    cellId: CellId,
    onIntent: (SchedulerIntent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val taskId = state.cells[cellId]?.taskId ?: return
    val options = RelativePriorityDomain.relativeToOptions(state, cellId)
    var relativeTo by remember(cellId) { mutableStateOf(WellKnownIds.MAIN_TASK) }
    // An ancestor can disappear under the window (the tree is live); fall back to the root then.
    if (relativeTo !in options) relativeTo = WellKnownIds.MAIN_TASK
    var relativeToMenuOpen by remember(cellId) { mutableStateOf(false) }

    val chains = RelativePriorityDomain.occurrenceChains(state, taskId, relativeTo)
    val value = RelativePriorityDomain.relativePriority(state, taskId, relativeTo)
    val pinned = state.relativePriorityPins[RelativePriorityPinKey(taskId, relativeTo)].orEmpty()
    var offset by remember(cellId) { mutableStateOf(Offset.Zero) }

    // PRD §5 the **task relations** window: opening this window on a (task, `t_r`) pair is what puts that
    // pair on the account's relations list, and whether it ends up in that window's "edited" or "opened"
    // section is decided HERE — the rule is about where the number ends up, so it is judged against the
    // percentage the pair read when this window settled on it, not against "an edit happened". Switching
    // the drop-down is settling on a new pair and takes a baseline of its own.
    //
    // The field commits every keystroke (see [PercentInputField]), so the verdict is re-reported on every
    // one of them: typing a value and putting it back reports "unchanged" and demotes the pair again. The
    // reducer returns the state untouched when the verdict has not moved, so the keystrokes that change
    // nothing cost neither a save nor a push.
    val shownPercent = percentFieldText(value)
    val baseline = remember(cellId, taskId, relativeTo) { shownPercent }
    LaunchedEffect(cellId, taskId, relativeTo, shownPercent) {
        onIntent(SchedulerIntent.RecordTaskRelation(taskId, relativeTo, changed = shownPercent != baseline))
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, SheetColors.grid),
        // Same contract as the weight window — a sort-2 pop-up.
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .transientPopupCard(onDismiss)
            .widthIn(max = 760.dp)
            .heightIn(max = 600.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .windowDragHandle(onDragEnd = {}) { dragAmount -> offset += dragAmount }
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Relative priority of " + quoted(state.tasks[taskId]?.title.orEmpty()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.padding(16.dp)) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PercentInputField(
                        fraction = value,
                        onSet = { onIntent(SchedulerIntent.SetRelativePriority(taskId, relativeTo, it)) },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "Relative to:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box {
                        TextButton(onClick = { relativeToMenuOpen = true }) {
                            Text(relativeToLabel(state, relativeTo))
                        }
                        DropdownMenu(
                            expanded = relativeToMenuOpen,
                            onDismissRequest = { relativeToMenuOpen = false },
                        ) {
                            // Root first, then this cell's ancestors from the root-most down to its parent.
                            options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(relativeToLabel(state, option)) },
                                    onClick = {
                                        relativeToMenuOpen = false
                                        relativeTo = option
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onIntent(SchedulerIntent.ClearRelativePriorityPins(taskId, relativeTo)) },
                        enabled = pinned.isNotEmpty(),
                    ) {
                        Text("Clear pins")
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = SheetColors.grid)
                Spacer(Modifier.height(8.dp))
                if (chains.isEmpty()) {
                    Text(
                        text = "No occurrence of this task under " + relativeToLabel(state, relativeTo) + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        chains.forEach { chain ->
                            Row(
                                modifier = Modifier.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                chain.forEachIndexed { index, chainCellId ->
                                    if (index > 0) {
                                        Text(
                                            text = " > ",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    RelativePriorityChainCell(
                                        state = state,
                                        cellId = chainCellId,
                                        pinned = chainCellId in pinned,
                                        onTogglePin = {
                                            onIntent(
                                                SchedulerIntent.ToggleRelativePriorityPin(
                                                    taskId,
                                                    relativeTo,
                                                    chainCellId,
                                                )
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The window's title quotes the task, and a task with no title reads as the tree's own placeholder. */
private fun quoted(title: String): String = "\"" + title.ifBlank { "(untitled)" } + "\""

/** The drop-down's label for a `t_r` choice: the conceptual root is named, every other task is its title. */
private fun relativeToLabel(state: SchedulerState, taskId: TaskId): String =
    if (taskId == WellKnownIds.MAIN_TASK) {
        "root"
    } else {
        state.tasks[taskId]?.title.orEmpty().ifBlank { "(untitled)" }
    }

/**
 * One cell of an occurrence chain: its title, its share of its own sub-list, and the pin that holds that
 * share while the window's number is retargeted. A pinned cell is drawn with the active border so the set
 * is readable at a glance.
 */
@Composable
private fun RelativePriorityChainCell(
    state: SchedulerState,
    cellId: CellId,
    pinned: Boolean,
    onTogglePin: () -> Unit,
) {
    val title = state.cells[cellId]?.taskId?.let { state.tasks[it]?.title }.orEmpty()
    Row(
        modifier = Modifier
            .border(if (pinned) 2.dp else 1.dp, if (pinned) SheetColors.activeBorder else SheetColors.grid)
            .background(if (pinned) SheetColors.selectionFill else SheetColors.cellBackground)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.ifBlank { "(untitled)" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatPriorityPercent(RelativePriorityDomain.cellShare(state, cellId)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .border(1.dp, if (pinned) SheetColors.activeBorder else SheetColors.grid)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTogglePin,
                )
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                text = if (pinned) "pinned" else "pin",
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

/**
 * What the relative-priority window's percentage field shows for a fraction — rounded to the hundredth of a
 * percent, the resolution the field itself edits at. Spelled once because the window's task-relations
 * recording compares against it: judging "did this window session change the number" against the raw
 * `Double` would call a bisection landing one ulp away a change the user never made.
 */
private fun percentFieldText(fraction: Double): String =
    formatWeight((fraction * 10_000).roundToInt() / 100.0)

/**
 * The window's percentage field: the same "numbers and comma" input as the weight table (PRD §5), reading
 * and writing a fraction as a percentage. Like a weight field it commits every valid keystroke — which is
 * safe here because the edit targets an ABSOLUTE value: retargeting 5% and then 12% lands exactly where
 * going straight to 12% would (the scale factors compose), so a half-typed number leaves nothing skewed.
 */
@Composable
private fun PercentInputField(fraction: Double, onSet: (Double) -> Unit) {
    var text by remember(fraction) { mutableStateOf(percentFieldText(fraction)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() || it == ',' || it == '.' }
                text = cleaned
                cleaned.replace(',', '.').toDoubleOrNull()?.let { onSet((it / 100.0).coerceIn(0.0, 1.0)) }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(SheetColors.activeBorder),
            modifier = Modifier
                .width(72.dp)
                .border(1.dp, SheetColors.grid)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Text(
            text = " %",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun priorityChartColor(index: Int, count: Int): Color {
    val hue = if (count <= 0) 0f else (index.toFloat() / count) * 360f
    return Color.hsv(hue, 0.55f, 0.85f)
}

/**
 * PRD §5: a circular (pie) chart of the priority percentages [fractions] (0..1) of the tasks [titles]
 * **within one sub-list** — each task's share of that list, not of the whole tree. Each slice's sweep is
 * proportional to its fraction relative to the sub-list total, followed by a colour-keyed legend giving
 * each task's title and percentage.
 */
@Composable
private fun PriorityChart(
    titles: List<String>,
    fractions: List<Double>,
    modifier: Modifier = Modifier,
) {
    val total = fractions.sum().coerceAtLeast(1e-9)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Priorities in this list",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (titles.isEmpty()) {
            Text(
                text = "(no tasks)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Canvas(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(140.dp),
        ) {
            val diameter = size.minDimension
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            var startAngle = -90f
            fractions.forEachIndexed { i, fraction ->
                val sweep = (fraction / total).toFloat() * 360f
                drawArc(
                    color = priorityChartColor(i, fractions.size),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                )
                startAngle += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            titles.forEachIndexed { i, title ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(priorityChartColor(i, titles.size), RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = title.ifEmpty { "(untitled)" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formatPriorityPercent(fractions[i]),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TaskRow(
    depth: Int,
    cellId: CellId,
    renderVia: CellId?,
    displayTitle: String,
    isMainSelection: Boolean,
    isInSelectionRange: Boolean,
    selectable: Boolean,
    isEditing: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    moveDropBefore: Boolean,
    moveDropAfter: Boolean,
    canMoveFromCell: Boolean,
    isBeingMoved: Boolean,
    priorityLabel: String?,
    priorityColumnWidth: Dp,
    /** This row's task's own colour, or null for a cell holding no coloured task (an empty placeholder). */
    taskColor: Color?,
    /** PRD §4 Find & replace: the hits of the current query inside this row's title. */
    searchRanges: List<IntRange>,
    /** The one hit the find bar is sitting on, when it is in this row. */
    currentSearchRange: IntRange?,
    textOverflow: Boolean,
    minMinutes: Int,
    minTimeEditing: Boolean,
    /** PRD §13: the right-click contextual menu's actions; null for a cell that has no menu. */
    cellMenu: TaskCellMenuActions?,
    /** PRD §5: clicking the percentage opens the sub-list's priority-weight window. */
    onTogglePriorityWeights: () -> Unit,
    /** PRD §5: the percentage's own right-click menu opens this cell's relative-priority window. */
    onOpenRelativePriority: () -> Unit,
    onSetMinTime: (Int) -> Unit,
    onActivateMinTime: () -> Unit,
    onClick: (CellId, ctrl: Boolean, shift: Boolean, forceClearMulti: Boolean) -> Unit,
    onDragSelect: (anchor: CellId, hover: CellId) -> Unit,
    moveDragActive: Boolean,
    resolveRowAt: (Float) -> Pair<VisibleOccurrence, Boolean>?,
    onRowBounds: (VisibleOccurrence, Float, Float) -> Unit,
    onMoveDragStart: () -> Unit,
    onMoveDropHover: (CellId, Boolean, CellId?) -> Unit,
    onMoveDragEnd: () -> Unit,
    onDoubleClick: () -> Unit,
    onTextChange: (String) -> Unit,
    onExitEdit: (EditExitNavigation) -> Unit,
    onToggleExpand: () -> Unit,
    editMenus: (@Composable () -> Unit)?,
    /** Additional controls rendered after the title and before the task row's trailing spacer. */
    rowContent: (@Composable () -> Unit)? = null,
    /** PRD §4: one extra cell at the end of the row — the default sub-tree's switch. Null in the tree. */
    rowTrailing: (@Composable (CellId) -> Unit)? = null,
) {
    val editFocusRequester = remember { FocusRequester() }
    // Whether this cell's right-click contextual menu ("edit task" / "copy" / "deep copy" / "add default
    // sub-tree") is showing.
    var contextMenuOpen by remember(cellId) { mutableStateOf(false) }
    // PRD §5: the percentage column's own right-click menu ("relative priority" / "priority weights").
    var priorityMenuOpen by remember(cellId) { mutableStateOf(false) }
    val hasContextMenu = cellMenu != null
    // Layout coordinates of this row, used to convert in-row pointer positions to window space so
    // the originating row can map an ongoing drag to the cell currently under the cursor.
    val rowCoordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    // PRD §4: only a double-click on the TITLE opens Edit Mode. The row-wide gesture handler below reads
    // the title column's own band out of this to tell a title press from one on the percentage, the
    // minimum time or the empty tail of the row.
    val titleBounds = remember(cellId) { TaskSheetTitleBounds() }
    val currentResolveRowAt by rememberUpdatedState(resolveRowAt)
    LaunchedEffect(isEditing) {
        if (isEditing) editFocusRequester.requestFocus()
    }

    val cellBackground =
        when {
            // PRD §3: a cell being drag-moved gets a grey background (not mirrored elsewhere).
            isBeingMoved -> SheetColors.moveDragFill
            isInSelectionRange || isEditing -> SheetColors.selectionFill
            !selectable -> SheetColors.nonSelectableFill
            // The task's own colour is the row's RESTING background only: the three states above are the
            // ones the user is being told about, and a tint under each of them would be one more thing to
            // read them against. Falling back to plain white is itself the strongest possible marker on a
            // coloured tree, so nothing is lost by letting them win outright.
            taskColor != null -> taskColor
            else -> SheetColors.cellBackground
        }
    val cellBorder =
        if (isMainSelection || isEditing) {
            Modifier.border(2.dp, SheetColors.activeBorder)
        } else {
            Modifier.border(1.dp, SheetColors.grid)
        }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)

    val currentCanMoveFromCell by rememberUpdatedState(canMoveFromCell)

    @OptIn(ExperimentalComposeUiApi::class)
    fun selectionPointerModifier(): Modifier {
        if (!selectable || isEditing) return Modifier
        return Modifier
            .onGloballyPositioned { coords ->
                rowCoordinates.value = coords
                val top = coords.positionInWindow().y
                onRowBounds(VisibleOccurrence(cellId, renderVia), top, top + coords.size.height)
            }
            // Keyed only by cellId so selection-driven flags (which change during the gesture's own
            // clicks) never restart and cancel an in-progress drag; freshness comes from the
            // rememberUpdatedState snapshots above.
            .pointerInput(cellId) {
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                val touchSlop = viewConfiguration.touchSlop

                fun windowPositionOf(change: PointerInputChange): Offset? =
                    rowCoordinates.value
                        ?.takeIf { it.isAttached }
                        ?.localToWindow(change.position)

                fun windowYOf(change: PointerInputChange): Float? = windowPositionOf(change)?.y

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    // PRD §4: a double-click only opens Edit Mode when it lands on the title — pressing the
                    // percentage, the minimum time or the row's empty tail still selects and still
                    // drag-moves, but never starts an edit.
                    val onTitle =
                        windowPositionOf(down)?.let { titleBounds.containsWindowX(it.x) } ?: true
                    val modifiers = currentEvent.keyboardModifiers
                    val ctrl = modifiers.pointerCtrlPressed
                    val shift = modifiers.pointerShiftPressed

                    onClick(cellId, ctrl, shift, false)

                    // Ctrl / Shift clicks never begin a drag — just wait for release.
                    if (ctrl || shift) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }

                    // First press: dragging past the touch slop selects a range from this cell
                    // (the anchor) to the cell under the cursor (PRD §3 Single Click & Drag).
                    var dragged = false
                    var traveled = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        if (!event.changes.any { it.pressed }) break
                        val change =
                            event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                        traveled += change.positionChange().getDistance()
                        if (traveled > touchSlop) {
                            dragged = true
                            change.consume()
                            windowYOf(change)?.let { currentResolveRowAt(it) }?.let { (occ, _) ->
                                onDragSelect(cellId, occ.cellId)
                            }
                        }
                    }
                    if (dragged) return@awaitEachGesture

                    // No drag: a second press within the timeout makes it a double-click. The first
                    // press kept any existing multi-selection intact (so a double-click & drag can
                    // still move it); now that this resolves as a plain single click, reset the
                    // Selected Cells List down to the clicked cell (PRD §3 Single Click).
                    val secondDown =
                        withTimeoutOrNull(doubleTapTimeout) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                    if (secondDown == null) {
                        onClick(cellId, false, false, true)
                        return@awaitEachGesture
                    }
                    secondDown.consume()

                    // Double-click on a non-movable selection (e.g. a disjoint Ctrl multi-select)
                    // can't be dragged anywhere, so it just enters Edit Mode (PRD §4).
                    if (!currentCanMoveFromCell) {
                        onClick(cellId, false, false, true)
                        waitForUpOrCancellation()
                        if (onTitle) onDoubleClick()
                        return@awaitEachGesture
                    }

                    // Double-click & drag on a movable selection (one cell or a contiguous block):
                    // dragging past the slop blurs the cells and tracks the blue drop line, and
                    // release commits the move. Without a drag it falls through to Edit Mode (PRD §3).
                    var moveStarted = false
                    var moveTraveled = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        if (!event.changes.any { it.pressed }) {
                            if (moveStarted) {
                                onMoveDragEnd()
                            } else {
                                onClick(cellId, false, false, true)
                                if (onTitle) onDoubleClick()
                            }
                            break
                        }
                        val change =
                            event.changes.firstOrNull { it.id == secondDown.id }
                                ?: event.changes.first()
                        moveTraveled += change.positionChange().getDistance()
                        if (!moveStarted && moveTraveled > touchSlop) {
                            moveStarted = true
                            onMoveDragStart()
                        }
                        if (moveStarted) {
                            change.consume()
                            windowYOf(change)?.let { currentResolveRowAt(it) }
                                ?.let { (occ, before) ->
                                    onMoveDropHover(occ.cellId, before, occ.renderVia)
                                }
                        }
                    }
                }
            }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (moveDropBefore) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * INDENT_STEP_DP).dp)
                    .height(2.dp)
                    .background(SheetColors.activeBorder),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // PRD §2: guide-lines on the left illustrate the parent-child hierarchy.
                .taskSheetGuideLines(depth)
                .padding(start = (depth * INDENT_STEP_DP).dp)
                .defaultMinSize(minHeight = 28.dp)
                .background(cellBackground)
                .then(cellBorder)
                .then(selectionPointerModifier())
                .then(contextMenuModifier(hasContextMenu) { contextMenuOpen = true })
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // PRD §13 right-click contextual menu on a populated cell.
            if (cellMenu != null) {
                DropdownMenu(
                    expanded = contextMenuOpen,
                    onDismissRequest = { contextMenuOpen = false },
                ) {
                    // PRD §13: only offered on a schedulable leaf — a parent task is never placed.
                    cellMenu.onStartNow?.let { startNow ->
                        DropdownMenuItem(
                            text = { Text("start this task now") },
                            onClick = {
                                contextMenuOpen = false
                                startNow()
                            },
                        )
                    }
                    // PRD §13: named "edit task" — the calendar's panel menu offers the very same window
                    // beside its own panel "Edit" (PRD §8), so the two surfaces must not call it two things.
                    DropdownMenuItem(
                        text = { Text("edit task") },
                        onClick = {
                            contextMenuOpen = false
                            cellMenu.onEdit()
                        },
                    )
                    // PRD §7/§8: the calendar panel's entry under its own name, offered by any surface that
                    // is not the tree itself. Only the "All tasks" window passes it today.
                    cellMenu.onGoToTaskTree?.let { goToTaskTree ->
                        DropdownMenuItem(
                            text = { Text("go to task tree") },
                            onClick = {
                                contextMenuOpen = false
                                goToTaskTree()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("copy") },
                        onClick = {
                            contextMenuOpen = false
                            cellMenu.onCopy()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("deep copy") },
                        onClick = {
                            contextMenuOpen = false
                            cellMenu.onDeepCopy()
                        },
                    )
                    cellMenu.onCollapseSubtree?.let { collapseSubtree ->
                        DropdownMenuItem(
                            text = { Text("collapse subtree") },
                            onClick = {
                                contextMenuOpen = false
                                collapseSubtree()
                            },
                        )
                    }
                    // PRD §7: only offered where there is a template to add.
                    cellMenu.onAddDefaultSubtree?.let { addDefaultSubtree ->
                        DropdownMenuItem(
                            text = { Text("add default sub-tree") },
                            onClick = {
                                contextMenuOpen = false
                                addDefaultSubtree()
                            },
                        )
                    }
                }
            }
            TaskSheetExpandArrow(
                hasChildren = hasChildren,
                expanded = expanded,
                onToggle = onToggleExpand,
            )
            if (isEditing) {
                var textFieldValue by remember(cellId) { mutableStateOf(TextFieldValue()) }
                SideEffect {
                    if (!isEditing) {
                        textFieldValue = TextFieldValue()
                        return@SideEffect
                    }
                    if (textFieldValue.text != displayTitle) {
                        textFieldValue =
                            TextFieldValue(
                                text = displayTitle,
                                selection = TextRange(displayTitle.length),
                            )
                    }
                }
                // PRD §2: the same priority text column and red overflow arrow apply in Edit Mode.
                Box(
                    modifier = Modifier
                        .width(priorityColumnWidth)
                        .defaultMinSize(minHeight = 20.dp)
                        .taskSheetTitleBounds(titleBounds),
                    contentAlignment = Alignment.CenterStart,
                ) {
                BasicTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 20.dp)
                        .focusRequester(editFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.key == Key.Delete && !event.isCtrlPressed && !event.isMetaPressed) {
                                return@onPreviewKeyEvent false
                            }
                            when {
                                event.key == Key.Enter &&
                                    (event.isCtrlPressed || event.isMetaPressed) -> {
                                    val selection = textFieldValue.selection
                                    val insertAt = selection.min
                                    val newText =
                                        buildString {
                                            append(textFieldValue.text.substring(0, insertAt))
                                            append('\n')
                                            append(textFieldValue.text.substring(selection.max))
                                        }
                                    textFieldValue =
                                        TextFieldValue(
                                            text = newText,
                                            selection = TextRange(insertAt + 1),
                                        )
                                    onTextChange(newText)
                                    true
                                }
                                event.key == Key.DirectionUp -> {
                                    val lineStart = textFieldValue.text.lastIndexOf('\n', textFieldValue.selection.min - 1)
                                    if (lineStart < 0) {
                                        textFieldValue =
                                            textFieldValue.copy(
                                                selection = TextRange(0),
                                            )
                                        true
                                    } else {
                                        false
                                    }
                                }
                                event.key == Key.DirectionDown -> {
                                    val text = textFieldValue.text
                                    val cursor = textFieldValue.selection.max
                                    val nextBreak = text.indexOf('\n', cursor)
                                    if (nextBreak < 0) {
                                        textFieldValue =
                                            textFieldValue.copy(
                                                selection = TextRange(text.length),
                                            )
                                        true
                                    } else {
                                        false
                                    }
                                }
                                event.key == Key.Enter && event.isShiftPressed -> {
                                    onExitEdit(EditExitNavigation.Up)
                                    true
                                }
                                event.key == Key.Enter -> {
                                    onExitEdit(EditExitNavigation.Down)
                                    true
                                }
                                event.key == Key.Tab && event.isShiftPressed -> {
                                    onExitEdit(EditExitNavigation.Up)
                                    true
                                }
                                event.key == Key.Tab -> {
                                    onExitEdit(EditExitNavigation.TabToChild)
                                    true
                                }
                                else -> false
                            }
                        },
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onTextChange(newValue.text)
                    },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(SheetColors.activeBorder),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            innerTextField()
                        }
                    },
                )
                    if (textOverflow) {
                        Text(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .background(cellBackground),
                            text = "▸",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheetColors.overflowArrow,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                // PRD §2 Priority Display: the text occupies a column whose width is shared by the
                // whole sublist (so percentages line up); the percentage sits just after it. When
                // the text exceeds the column it is clipped and a little red arrow marks the
                // hidden overflow on the right.
                Box(
                    modifier = Modifier
                        .width(priorityColumnWidth)
                        .defaultMinSize(minHeight = 20.dp)
                        .taskSheetTitleBounds(titleBounds),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = highlightedTitle(displayTitle, searchRanges, currentSearchRange),
                        style = textStyle,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                    if (textOverflow) {
                        Text(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .background(cellBackground),
                            text = "▸",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheetColors.overflowArrow,
                        )
                    }
                }
                // PRD §5: the percentage occupies a fixed-width column; clicking it opens the sub-list's
                // priority-weight window (the editable table plus a chart of the sub-list's priorities),
                // and RIGHT-clicking it opens the percentage's own two-option menu instead of the cell's
                // "edit / copy / deep copy" one — the press is consumed here so the row never sees it.
                Box(
                    modifier = Modifier
                        .width(PERCENT_COLUMN_WIDTH)
                        .then(
                            if (priorityLabel != null) {
                                Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onTogglePriorityWeights,
                                    )
                                    .then(contextMenuModifier(true) { priorityMenuOpen = true })
                            } else {
                                Modifier
                            }
                        )
                        .padding(start = 8.dp),
                ) {
                    if (priorityLabel != null) {
                        Text(
                            text = priorityLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DropdownMenu(
                            expanded = priorityMenuOpen,
                            onDismissRequest = { priorityMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("relative priority") },
                                onClick = {
                                    priorityMenuOpen = false
                                    onOpenRelativePriority()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("priority weights") },
                                onClick = {
                                    priorityMenuOpen = false
                                    onTogglePriorityWeights()
                                },
                            )
                        }
                    }
                }
                // PRD §10: the task's minimum-time field sits just to the right of the percentage. It
                // shows as a plain label until clicked, then expands into an input field with
                // increment/decrement arrows.
                if (priorityLabel != null) {
                    if (minTimeEditing) {
                        MinTimeInputCell(minutes = minMinutes, onSet = onSetMinTime)
                    } else {
                        MinTimeDisplayCell(minutes = minMinutes, onClick = onActivateMinTime)
                    }
                }
                // PRD §4: the default sub-tree's switch, in its own column after the minimum time so both
                // trees line up down every column they share. Nothing in the account's own tree.
                rowTrailing?.invoke(cellId)
                Spacer(Modifier.weight(1f))
            }
            rowContent?.invoke()
        }
        if (moveDropAfter) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * INDENT_STEP_DP).dp)
                    .height(2.dp)
                    .background(SheetColors.activeBorder),
            )
        }
        if (isEditing) {
            // The menu (mode selector / task list / title suggestions) renders directly below the cell
            // without its own white background or border, so it no longer appears as a stark white bar
            // under the (light-blue) editing row. The box only supplies indentation; its own padding
            // comes from [EditModeMenus], which renders nothing at all when there is no menu to show —
            // so the row below is pushed down only while a menu is actually visible.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * INDENT_STEP_DP).dp),
            ) {
                editMenus?.invoke()
            }
        }
    }
}

/**
 * A right-click (secondary button) opens a contextual menu — the cell's own ("edit task" / "copy" / "deep copy" /
 * "add default sub-tree")
 * on the row, the two-option one on the priority-percentage column (PRD §5), and the "go to task" one on an
 * **id row of Edit Mode's Tasks menu** (PRD §4, via `org.example.project.ui.EditMenuRowActions`). Returns a
 * no-op modifier
 * when [enabled] is false (cells with no menu — empty / root-main; rows with no menu), so only eligible
 * targets react. [onOpen]
 * flips the local menu-visible flag. The press is consumed, which both keeps the freshly opened menu from
 * being dismissed by its own click and stops the row's handler re-opening the cell menu underneath the
 * percentage's (children are dispatched to first, so the inner menu wins that column) — and, on a menu row,
 * is what keeps a right-click from also *picking* the id it opened the menu on.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun contextMenuModifier(
    enabled: Boolean,
    onOpen: () -> Unit,
): Modifier {
    if (!enabled) return Modifier
    return Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                var press = awaitPointerEvent()
                while (press.type != PointerEventType.Press) {
                    press = awaitPointerEvent()
                }
                // A right-click the percentage column already answered arrives here with its changes
                // consumed: that column's menu REPLACES the cell's, so the row must let it pass.
                if (press.buttons.isSecondaryPressed && press.changes.none { it.isConsumed }) {
                    // Consume so the freshly opened menu isn't dismissed by this same click.
                    press.changes.forEach { it.consume() }
                    onOpen()
                }
            }
        }
    }
}

/**
 * PRD §13 the actions of a populated cell's right-click contextual menu. Bundled so a cell either has the
 * whole menu or none of it (empty cells and the root/main cell get null).
 *
 * [onAddDefaultSubtree] is null when there is no §7 template to add, and [onStartNow] when the cell's task is
 * not a schedulable leaf — the two entries that come and go: an account that never defined a default sub-tree
 * is not offered it, and a parent task is a grouping the scheduler never places, so there is nothing to start.
 */
internal class TaskCellMenuActions(
    val onStartNow: (() -> Unit)?,
    val onEdit: () -> Unit,
    /**
     * PRD §7/§8 **"go to task tree"** — null in the account's own tree (you are already there) and in the §4
     * template, non-null in the "All tasks" window, whose rows are the tree's cells shown in the sorter's
     * order rather than the tree's.
     */
    val onGoToTaskTree: (() -> Unit)?,
    val onCopy: () -> Unit,
    val onDeepCopy: () -> Unit,
    val onCollapseSubtree: (() -> Unit)?,
    val onAddDefaultSubtree: (() -> Unit)?,
)

/**
 * PRD §13 Edition Window: the floating "edit task" editor opened from a cell's contextual menu (and from a
 * calendar task panel's, PRD §8), in three
 * sections — the no-screen switch, the schedule unit, and the task's text document. The first two only
 * exist for a schedulable leaf task ([isLeaf]); a parent task gets the text section alone.
 *
 * Schedule unit: the entries listed vertically — each a title field plus a spanning-time field with
 * increment/decrement buttons, a bin (remove) and a plus (insert above); a single trailing plus appends.
 * The Save button is disabled while the summed spanning times exceed the task's minimum time
 * ([SchedulerDomain.canSaveScheduleUnit]).
 */
/**
 * `side-dev/README.md`'s resilience, as a field: a multiplier in `[0, 1]` typed as a **percentage**, which
 * is what it means — 0 % forbids the task inside a period of that kind, 100 % leaves it untouched.
 *
 * The text is local while it is being typed so a half-typed "1" does not snap to 1 %, and only a value that
 * parses is reported; anything else leaves the stored multiplier where it was.
 */
@Composable
private fun PercentField(value: Double, onValueChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(formatPercent(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            raw.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()?.let {
                onValueChange(PeriodKinds.clamp(it / 100.0))
            }
        },
        singleLine = true,
        suffix = { Text("%") },
        modifier = Modifier.width(88.dp),
    )
}

/** A multiplier as a percentage with no trailing zeros — `0`, `50`, `12.5`. */
private fun formatPercent(value: Double): String {
    val pct = value * 100.0
    val rounded = kotlin.math.round(pct * 10.0) / 10.0
    return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
}

@Composable
internal fun TaskEditWindow(
    task: Task,
    isLeaf: Boolean,
    /**
     * `side-dev/README.md` § *Restrictive Period*: every kind of restrictive period the account knows —
     * the two the README names plus the ones the user has defined
     * ([org.example.project.scheduler.state.SchedulerState.periodKinds]).
     *
     * All but one of them gets a row: **"no task allowed" has no resilience to choose**, because by its own
     * name it accepts nobody and its multiplier is always `0` ([PeriodKinds.isResilienceEditable]). The list
     * arrives whole so the *filter* lives here, next to the rows it governs, rather than at the call site.
     */
    periodKinds: List<String>,
    /**
     * The `+` beside the name field: define a new kind here and now. It is added to **every** task at the
     * default value `0` ([PeriodKinds.defaultResilience]) — a restrictive period restricts — so the new
     * period turns everybody away until its own edit window hands somebody a value above zero.
     */
    onAddPeriodKind: (String) -> Unit,
    /**
     * The ✎ on a period's row: open **that period's** edit window, which is where it is deleted and where
     * every task's resilience to it is handed out at once. This row shows one TASK's value for every kind;
     * that window shows one KIND's value for every task. It is a sort-2 pop-up like this one, so opening it
     * dismisses this window — the price of the sort (see `ui/PopupWindows.kt`).
     */
    onEditPeriodKind: (String) -> Unit,
    onSave: (resilience: Map<String, Double>, entries: List<ScheduleUnitEntry>, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val minimumMinutes = task.minimumMinutes
    // The whole resilience map is edited here, kind by kind, and handed back on Save — one intent per kind
    // that actually moved, so an untouched window records nothing (PRD §6).
    var resilience by remember(task.id) { mutableStateOf(task.resilience) }
    var newKind by remember(task.id) { mutableStateOf("") }
    var entries by remember(task.id) { mutableStateOf(task.scheduleUnit) }
    var text by remember(task.id) { mutableStateOf(task.text) }
    val sum = SchedulerDomain.scheduleUnitSumMinutes(entries)
    // A parent task's schedule unit is not editable here, so it can never block its own Save.
    val canSave = !isLeaf || SchedulerDomain.canSaveScheduleUnit(entries, minimumMinutes)

        // A sort-2 pop-up: it draws on the top layer, blocks nothing behind it, and the host
        // dismisses it as soon as a press lands anywhere else (see TransientPopupHost).
    TransientPopupLayer {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, SheetColors.grid),
            modifier = Modifier.transientPopupCard(onDismiss).width(360.dp),
        ) {
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(task.title.ifBlank { "Task" }, style = MaterialTheme.typography.titleSmall)

                // Section 1 (leaf only): `side-dev/README.md` § *Restrictive Period* — this task's
                // RESILIENCE to each kind of restrictive period, and the place new kinds are defined.
                //
                // One row per kind, showing the multiplier as a percentage because that is what it means:
                // 0 % forbids the task inside such a period, 100 % leaves it untouched, and anything between
                // scales its priority percentage for as long as the period lasts. A kind the task has never
                // been given a value for shows that KIND's default, which is exactly what the absent override
                // means — nothing is written until the user moves it. That default is 0 % for a kind the user
                // defined (a restrictive period restricts) and 100 % for "no on-screen task", which is the
                // one kind an on-screen task is the one to override.
                //
                // Each row also carries a ✎ onto the PERIOD's own window: this section is one task and every
                // kind, that window is one kind and every task — which is the question you have the moment a
                // period is defined, since defining one turns everybody away. It is where a period is
                // deleted, too, the row's old × having said "remove this from this task".
                //
                // "no task allowed" is NOT among them: it is the one kind whose value is not the task's to
                // pick — it accepts nobody, always, which is what its name says — so offering a field there
                // would be offering to write a number the app then ignores.
                //
                // The old pair of switches is gone: "on screen" is a 0 % against "no on-screen task", and
                // "doable during a screen break" has nothing left to say now that all three dynamic periods
                // are "no task allowed" end to end.
                if (isLeaf) {
                    HorizontalDivider()
                    Text("Resilience to restrictive periods", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "A multiplier on this task\u2019s priority inside a period of that kind: " +
                            "0 % forbids it there, 100 % leaves it untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (kind in periodKinds.filter(PeriodKinds::isResilienceEditable)) {
                        val current = PeriodKinds.resilienceFor(resilience, kind)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = kind,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            PercentField(
                                value = current,
                                onValueChange = { next ->
                                    // Written as an OVERRIDE only while it differs from the kind\u2019s own
                                    // default, so an untouched kind stays absent and a changed default reaches
                                    // every task that never moved it.
                                    resilience =
                                        if (next == PeriodKinds.defaultResilience(kind)) resilience - kind
                                        else resilience + (kind to next)
                                },
                            )
                            // The period itself is an object: this opens its own window, where it is
                            // deleted and where every task’s value for it is handed out together. Offered
                            // for the built-in "no on-screen task" too — it cannot be deleted, but "which
                            // tasks need a screen" is exactly the question that window answers.
                            TextButton(onClick = { onEditPeriodKind(kind) }) { Text("✎") }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = newKind,
                            onValueChange = { newKind = it },
                            singleLine = true,
                            label = { Text("New kind of period") },
                            modifier = Modifier.weight(1f),
                        )
                        // The `+`: define the named period. It is added to every task at the default 0.
                        TextButton(
                            enabled = PeriodKinds.isUserDefined(PeriodKinds.normalize(newKind)) &&
                                periodKinds.none { it.equals(PeriodKinds.normalize(newKind), ignoreCase = true) },
                            onClick = {
                                onAddPeriodKind(newKind)
                                newKind = ""
                            },
                        ) { Text("+") }
                    }
                }

                // Section 2 (leaf only): the schedule unit.
                if (isLeaf) {
                    HorizontalDivider()
                    Text("Schedule unit", style = MaterialTheme.typography.labelMedium)
                }

                if (isLeaf) entries.forEachIndexed { index, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedTextField(
                            value = entry.title,
                            onValueChange = { newTitle ->
                                entries = entries.toMutableList().also {
                                    it[index] = entry.copy(title = newTitle)
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = entry.spanMinutes.toString(),
                            onValueChange = { raw ->
                                val parsed = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                                entries = entries.toMutableList().also {
                                    it[index] = entry.copy(spanMinutes = parsed)
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.width(72.dp),
                        )
                        Column {
                            WeightStepButton("+") {
                                entries = entries.toMutableList().also {
                                    it[index] = entry.copy(spanMinutes = entry.spanMinutes + 1)
                                }
                            }
                            WeightStepButton("−") {
                                entries = entries.toMutableList().also {
                                    it[index] = entry.copy(spanMinutes = (entry.spanMinutes - 1).coerceAtLeast(0))
                                }
                            }
                        }
                        // Bin: remove this pair.
                        TextButton(onClick = {
                            entries = entries.toMutableList().also { it.removeAt(index) }
                        }) { Text("🗑") }
                        // Plus: insert a new pair above this one.
                        TextButton(onClick = {
                            entries = entries.toMutableList().also { it.add(index, ScheduleUnitEntry("", 0)) }
                        }) { Text("+") }
                    }
                }

                if (isLeaf) {
                    // Trailing single plus: append a new pair at the end of the list.
                    TextButton(onClick = { entries = entries + ScheduleUnitEntry("", 0) }) {
                        Text("+ add step")
                    }

                    Text(
                        text = "Total: $sum min (max $minimumMinutes)",
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (canSave) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                    )
                }

                // Section 3: the free-form text document attached to the task (any populated cell).
                HorizontalDivider()
                Text("Text", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    // PRD §13: Save is not clickable while the spans exceed the task's minimum time.
                    TextButton(
                        enabled = canSave,
                        onClick = { onSave(resilience, entries, text) },
                    ) { Text("Save") }
                }
            }
        }
    }
}

/**
 * `side-dev/README.md` § *Restrictive Period*: the **period edit window** — one KIND of restrictive period,
 * and every task's resilience to it.
 *
 * It is the task edit window's resilience section read the other way round. That section is *one task, every
 * kind*; this is *one kind, every task* — which is the question you actually have when you have just defined
 * a period ("who may work through it?"), because defining one adds it to every task at the default `0`
 * ([PeriodKinds.defaultResilience]) and somebody has to be let back in.
 *
 * Three things it holds, and nothing else:
 * - **Delete**, offered only for a user-defined kind: this is the one place a period is deleted, because it
 *   is the one place a period is an object in its own right. It takes every task's value for it and every
 *   panel laid with it ([org.example.project.scheduler.state.SchedulerIntent.RemovePeriodKind]). The two
 *   built-in kinds cannot be removed.
 * - **The list of tasks**, each with a check box and its own percentage field. The rows are the schedulable
 *   leaves ([SchedulerDomain.periodKindTaskRows]) — a parent task is a grouping the scheduler never places,
 *   so a resilience on one would be a number nothing reads.
 * - **The bulk field**, which appears as soon as anything is checked. It shows the value the checked tasks
 *   share, or **blank** where they do not agree ([SchedulerDomain.commonResilience]), and typing in it gives
 *   all of them that value as ONE history unit
 *   ([org.example.project.scheduler.state.SchedulerIntent.SetPeriodResilience]) — checking twenty tasks and
 *   typing one percentage is one gesture. "Select all" is the shortcut to the whole list, and reads "select
 *   none" once everything is checked, since that is the only thing left it can usefully do.
 *
 * A **sort-2** pop-up (`ui/PopupWindows.kt`): it is about ONE object — this period — so "the window of period
 * A" and "the window of period B" are two different windows and only the one just asked for is ever meant.
 * Opening it therefore dismisses the task edit window it was opened from, discarding whatever was half-typed
 * there; that is the sort's price, not an oversight. Unlike the task window it has no Save: every field
 * writes as it is typed, exactly like the row-level `×` it replaces.
 */
@Composable
internal fun PeriodKindEditWindow(
    kind: String,
    rows: List<SchedulerDomain.PeriodKindTaskRow>,
    /** False for the two built-in kinds — the README names them, so the account cannot drop them. */
    canDelete: Boolean,
    onSetResilience: (List<TaskId>, Double) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Which tasks the bulk field acts on. Compose-only state, like the calendar's zoom and the find bar's
    // query: a selection is a way of looking at the list, never a fact about the account. Rows that leave
    // under it (a task deleted while the window is open) are dropped rather than kept as phantom targets.
    var selected by remember(kind) { mutableStateOf(emptySet<TaskId>()) }
    val present = rows.map { it.taskId }.toSet()
    val checked = selected.intersect(present)
    val common = SchedulerDomain.commonResilience(rows, checked)
    val allChecked = rows.isNotEmpty() && checked.size == rows.size

        // A sort-2 pop-up: it draws on the top layer, blocks nothing behind it, and the host
        // dismisses it as soon as a press lands anywhere else (see TransientPopupHost).
    TransientPopupLayer {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, SheetColors.grid),
            modifier = Modifier.transientPopupCard(onDismiss).width(400.dp),
        ) {
            Column(
                Modifier.padding(16.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(kind, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    // The one place a period is deleted. A built-in kind has no button at all rather than a
                    // disabled one: it is not a thing the account could ever do.
                    if (canDelete) TextButton(onClick = onDelete) { Text("Delete period") }
                }
                Text(
                    "Each task’s resilience to a period of this kind: 0 % forbids it there, " +
                        "100 % leaves it untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // The bulk field, and it exists only while something is checked — with nothing selected
                // there is nothing for it to say. Blank means "the checked tasks disagree"; typing a value
                // ends that disagreement in one history unit.
                if (checked.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${checked.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        BulkPercentField(
                            value = common,
                            onValueChange = { next -> onSetResilience(checked.toList(), next) },
                        )
                    }
                    HorizontalDivider()
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        enabled = rows.isNotEmpty(),
                        onClick = { selected = if (allChecked) emptySet() else present },
                    ) { Text(if (allChecked) "Select none" else "Select all") }
                }

                if (rows.isEmpty()) {
                    Text(
                        "No schedulable task yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                for (row in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Checkbox(
                            checked = row.taskId in checked,
                            onCheckedChange = { on ->
                                selected = if (on) checked + row.taskId else checked - row.taskId
                            },
                        )
                        Text(
                            text = row.title.ifBlank { "(untitled)" },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        // The row's own field. It goes through the same bulk intent with a one-element list,
                        // so there is one write path and not two.
                        PercentField(
                            value = row.resilience,
                            onValueChange = { next -> onSetResilience(listOf(row.taskId), next) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/**
 * [PercentField]'s two-state sibling: the period edit window's bulk field, which must be able to show
 * **nothing**. `null` is "the checked tasks do not agree", and it is a real answer rather than a missing one
 * — the field is blank until the user types the value that ends the disagreement.
 */
@Composable
private fun BulkPercentField(value: Double?, onValueChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let(::formatPercent) ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            raw.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()?.let {
                onValueChange(PeriodKinds.clamp(it / 100.0))
            }
        },
        singleLine = true,
        suffix = { Text("%") },
        modifier = Modifier.width(88.dp),
    )
}

/**
 * PRD §13 "deep copy": the floating window that asks **how deep** the copy goes before making it.
 *
 * [cellIds] is what the menu resolved to — the right-clicked cell, or the whole selection block when the
 * right-click landed inside one. The number alone would say nothing about the tree, so under it the window
 * prints ONE path down to the deepest level that depth reaches (the deepest branch under whichever copied
 * cell reaches furthest, cut to the asked-for number of levels). A path too long for the window scrolls
 * horizontally and is **held at its deep end** —
 * the newly-reached levels are what the user is choosing between — so the parents are hidden off to the
 * left and the scrollbar under it is how they are brought back.
 *
 * Under the depth sit three switches saying **what** each copied task carries — its **id**, its sub-list's
 * **priority table** (off: the cell's percentage of that sub-list instead), and its **text**.
 *
 * **Enter** and the **copy** button both copy and close; **reset** puts the depth back to
 * [SchedulerDomain.DEEP_COPY_DEFAULT_DEPTH] and turns all three switches back on.
 *
 * The depth and the switches are **the account's settings** ([SchedulerState.deepCopyMaxDepth] and the
 * three `copy*` flags), not this copy's: the window opens on them and copying writes them back. §4's
 * Ctrl+C / Ctrl+X take the whole sub-tree (the depth is this window's), but they carry what these switches
 * say. Cancelling leaves all four alone.
 */
@Composable
internal fun DeepCopyWindow(
    state: SchedulerState,
    cellIds: List<CellId>,
    onCopy: (List<CellId>, Int, Boolean, SchedulerDomain.CopyOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val key = cellIds.firstOrNull()
    // The raw text, so the field can be emptied while typing; a blank/0 depth simply copies nothing.
    var depthText by remember(key) { mutableStateOf(state.deepCopyMaxDepth.toString()) }
    var unlimited by remember(key) { mutableStateOf(state.deepCopyUnlimited) }
    var options by remember(key) { mutableStateOf(SchedulerDomain.CopyOptions.from(state)) }
    val depth = if (unlimited) SchedulerDomain.FULL_SUBTREE_DEPTH else depthText.toIntOrNull() ?: 0
    val path = SchedulerDomain.deepCopyPathTitles(state, cellIds, depth)
    val canCopy = depth >= 1 && cellIds.isNotEmpty()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(key) { focusRequester.requestFocus() }
    fun setDepth(value: Int) {
        depthText = value.coerceIn(SchedulerDomain.DEEP_COPY_DEPTH_RANGE).toString()
    }

        // A sort-2 pop-up: it draws on the top layer, blocks nothing behind it, and the host
        // dismisses it as soon as a press lands anywhere else (see TransientPopupHost).
    TransientPopupLayer {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, SheetColors.grid),
            modifier = Modifier
                .transientPopupCard(onDismiss)
                .width(420.dp)
                // The depth field holds the focus, so this ancestor sees its keys first: Enter is the
                // window's own accept, not a character the field should ever receive.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        if (canCopy) onCopy(cellIds, depth, unlimited, options)
                        true
                    } else {
                        false
                    }
                },
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    // Say how many cells are going, since the menu was opened on one of them.
                    text = if (cellIds.size > 1) "Deep copy — ${cellIds.size} cells" else "Deep copy",
                    style = MaterialTheme.typography.titleSmall,
                )
                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Maximum depth",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = if (unlimited) "∞" else depthText,
                        onValueChange = { raw -> depthText = raw.filter { it.isDigit() }.take(3) },
                        singleLine = true,
                        enabled = !unlimited,
                        modifier = Modifier.width(88.dp).focusRequester(focusRequester),
                    )
                    Column {
                        WeightStepButton("+") { setDepth(depth + 1) }
                        WeightStepButton("−") { setDepth(depth - 1) }
                    }
                }

                DeepCopySwitchRow(
                    label = "Copy the entire sub-tree",
                    checked = unlimited,
                    onCheckedChange = { unlimited = it },
                )

                HorizontalDivider()
                // What each copied task carries. The percentage row says which of the two forms the
                // priority travels in, so the switch reads as a choice rather than as a loss.
                DeepCopySwitchRow(
                    label = "Copy the task ids",
                    checked = options.includeIds,
                    onCheckedChange = { options = options.copy(includeIds = it) },
                )
                DeepCopySwitchRow(
                    label = "Copy the priority weight tables",
                    checked = options.priorityTables,
                    onCheckedChange = { options = options.copy(priorityTables = it) },
                )
                DeepCopySwitchRow(
                    label = "Copy priority percentages",
                    checked = options.includePriorityPercentages,
                    onCheckedChange = { options = options.copy(includePriorityPercentages = it) },
                )
                DeepCopySwitchRow(
                    label = "Copy minimum time",
                    checked = options.includeMinimumTime,
                    onCheckedChange = { options = options.copy(includeMinimumTime = it) },
                )
                DeepCopySwitchRow(
                    label = "Copy the task text",
                    checked = options.includeText,
                    onCheckedChange = { options = options.copy(includeText = it) },
                )
                OutlinedTextField(
                    value = options.excludeTitle,
                    onValueChange = { options = options.copy(excludeTitle = it) },
                    label = { Text("Exclude tasks with title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()
                Text(
                    text = if (cellIds.size > 1) "Deepest of them copied down to" else "Copied down to",
                    style = MaterialTheme.typography.labelMedium,
                )
                DeepCopyPathRow(path)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            setDepth(SchedulerDomain.DEEP_COPY_DEFAULT_DEPTH)
                            unlimited = false
                            options = SchedulerDomain.CopyOptions()
                        },
                    ) {
                        Text("reset")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    // The window's own Enter does exactly this (the ancestor handler above), so the button
                    // names that chord on hover like every other control that duplicates one.
                    ShortcutHint(ControlChords.ENTER) {
                        TextButton(enabled = canCopy, onClick = { onCopy(cellIds, depth, unlimited, options) }) {
                            Text("copy")
                        }
                    }
                }
            }
        }
    }
}

/** One of the deep-copy window's "what does the copy carry" switches (see [DeepCopyWindow]). */
@Composable
private fun DeepCopySwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * One path down the copied sub-tree, on a single scrolled line held at its deep end (see [DeepCopyWindow]),
 * with a draggable scrollbar under it for the parents that fall off the left.
 */
@Composable
private fun DeepCopyPathRow(path: List<String>) {
    val scroll = rememberScrollState()
    // Before the row is measured `maxValue` is Int.MAX_VALUE, which is not a length to divide by.
    val maxScroll = scroll.maxValue.takeIf { it != Int.MAX_VALUE } ?: 0
    // Re-run on the measured length too: the path changes with every keystroke, and the effect must land
    // after the new width is known. Scrolling back to the parents changes neither key, so a deliberate
    // scroll left is never yanked back.
    LaunchedEffect(path, maxScroll) { scroll.scrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SheetColors.nonSelectableFill, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .horizontalScroll(scroll),
        ) {
            Text(
                text =
                    if (path.isEmpty()) "—"
                    else path.joinToString("  ›  ") { it.ifBlank { "(untitled)" } },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (maxScroll > 0) {
            val scope = rememberCoroutineScope()
            BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp)) {
                val trackWidth = maxWidth
                val trackPx = with(LocalDensity.current) { trackWidth.toPx() }
                // The thumb covers the visible share of the whole line.
                val thumbWidth = trackWidth * (trackPx / (trackPx + maxScroll))
                val travel = trackWidth - thumbWidth
                // The drag maps thumb travel to an ABSOLUTE scroll position rather than a raw delta, so
                // there is no direction convention to get backwards.
                val perPixel = rememberUpdatedState(
                    with(LocalDensity.current) { travel.toPx() }.let { if (it > 0f) maxScroll / it else 0f },
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(SheetColors.grid, RoundedCornerShape(3.dp)),
                )
                Box(
                    Modifier
                        .offset(x = travel * (scroll.value.toFloat() / maxScroll))
                        .width(thumbWidth)
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                val target = scroll.value + delta * perPixel.value
                                scope.launch { scroll.scrollTo(target.roundToInt()) }
                            },
                        ),
                )
            }
        }
    }
}
