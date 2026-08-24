package org.example.project.scheduler.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.boundsInWindow
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.example.project.scheduler.domain.RelativePriorityDomain
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
import org.example.project.scheduler.state.SelectionNavigate
import org.example.project.ui.INDENT_STEP_DP
import org.example.project.ui.PERCENT_COLUMN_WIDTH
import org.example.project.ui.PRIORITY_COLUMN_MAX
import org.example.project.ui.PRIORITY_COLUMN_MIN
import org.example.project.ui.SheetColors
import org.example.project.ui.TaskTreeFindBar
import org.example.project.ui.TaskSheetExpandArrow
import org.example.project.ui.TaskSheetTitleBounds
import org.example.project.ui.taskSheetTitleBounds
import org.example.project.ui.taskSheetGuideLines
import org.example.project.ui.isModifierKey
import org.example.project.ui.printableChar
import org.example.project.ui.EditMenuItem
import org.example.project.ui.EditModeMenuBlock
import org.example.project.ui.EditModeOption
import kotlinx.coroutines.withTimeoutOrNull

/** Width of one weight-table column (number field + stacked +/- buttons). */
private val WEIGHT_COLUMN_WIDTH = 60.dp

/** PRD §10: width of the per-task minimum-time field (minutes input + stacked +/- buttons + unit). */
private val MIN_TIME_COLUMN_WIDTH = 72.dp

/** Renders a priority fraction (0..1) as a percentage with at most one decimal: 50%, 33.3%, 0.4%. */
private fun formatPriorityPercent(fraction: Double): String {
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
private data class MoveDropTarget(
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
private data class TreeSearchHighlight(
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
) {
    val state by vm.state.collectAsState()
    val visibleOrder = SchedulerDomain.selectableVisibleOrder(state)
    val visibleOccurrences = SchedulerDomain.selectableVisibleOccurrences(state)
    // PRD §5: absolute priority percentage per task, displayed at the right of each populated cell.
    val priorities = SchedulerDomain.absoluteTaskPriorities(state)
    val focusRequester = remember { FocusRequester() }
    var moveDragActive by remember { mutableStateOf(false) }
    var moveDropTarget by remember { mutableStateOf<MoveDropTarget?>(null) }
    // PRD §10: the cell whose minimum-time field is currently expanded into an input (clicking its
    // simple display opens it), or null when every min-time field shows as a plain label.
    var minTimeEditCellId by remember { mutableStateOf<CellId?>(null) }
    // PRD §10: the minimum-time value the open input started with, so Escape can restore it (mirroring
    // how Edit Mode's Escape reverts a cell to its pre-edit text). Null when no input is open.
    var minTimeEditOriginal by remember { mutableStateOf<Int?>(null) }
    // PRD §13: the task whose "edit" window is open, or null when it is closed. Opened from a cell's
    // right-click contextual menu.
    var editTaskId by remember { mutableStateOf<TaskId?>(null) }
    // PRD §13: the cell whose "deep copy" depth window is open, or null when it is closed. The depth it
    // asks for lives in the window, which is where the copy is made.
    var deepCopyCellId by remember { mutableStateOf<CellId?>(null) }
    // The task-tree selector above the tree: its draft name, edit mode, and whether its field holds focus
    // (which is what reveals its menus and hands it the keyboard). Hoisted here so the screen's key handler
    // can commit on Enter and revert on Escape, exactly as it does for the min-time input.
    var treeFieldFocused by remember { mutableStateOf(false) }
    var treeNameDraft by remember { mutableStateOf("") }
    var treeEditMode by remember { mutableStateOf(TaskTreeEditMode.Change) }
    val activeTreeTitle = state.activeTaskTree?.title.orEmpty()

    // PRD §4 Find & replace: the Ctrl+F bar's own state. Compose-only, like the calendar's zoom — a search
    // is a way of looking at the tree, not a fact about it, so none of this is persisted or synced.
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf(TextFieldValue()) }
    var findReplacement by remember { mutableStateOf(TextFieldValue()) }
    var findOptions by remember { mutableStateOf(TaskTreeSearch.Options()) }
    var findReplaceExpanded by remember { mutableStateOf(false) }
    var findFieldFocused by remember { mutableStateOf(false) }
    var findMatchIndex by remember { mutableStateOf(0) }
    // A query that has not been navigated yet: the first ↓ lands on the FIRST hit, not the second (and the
    // first ↑ on the last). Typing resets it, which is what makes Ctrl+F, type, Enter behave as expected.
    var findNavigated by remember { mutableStateOf(false) }
    // Bumped by Ctrl+F so pressing it again re-focuses the field and re-selects it, even while it is open.
    var findFocusTick by remember { mutableStateOf(0) }
    val findFocusRequester = remember { FocusRequester() }
    // The tree's scroll, hoisted so a revealed match can be brought into view, plus the viewport's own
    // window band (recorded OUTSIDE the scroll modifier, so it is the viewport and not the scrolled
    // content) to compare the row's band against.
    val treeScroll = rememberScrollState()
    var treeViewport by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }

    // Only computed while the bar is open: it walks the whole tree, and the tree's state object is replaced
    // by every advance tick (records live on the tasks), so an always-on memo would re-walk on every tick.
    val findMatches =
        remember(findOpen, findQuery.text, findOptions, state.cells, state.lists, state.tasks) {
            if (!findOpen) emptyList() else TaskTreeSearch.matches(state, findQuery.text, findOptions)
        }
    val findCurrentIndex = if (findMatches.isEmpty()) -1 else findMatchIndex.coerceIn(0, findMatches.lastIndex)
    val findCurrentMatch = findMatches.getOrNull(findCurrentIndex)
    val searchHighlight =
        if (findOpen && findQuery.text.isNotEmpty()) {
            TreeSearchHighlight(findQuery.text, findOptions, findCurrentMatch)
        } else {
            null
        }

    val goToFindMatch: (Int) -> Unit = { index ->
        if (findMatches.isNotEmpty()) {
            val size = findMatches.size
            val wrapped = ((index % size) + size) % size
            findMatchIndex = wrapped
            findNavigated = true
            val match = findMatches[wrapped]
            vm.dispatch(SchedulerIntent.RevealCell(match.cellId, match.ancestors))
        }
    }
    val findStep: (Int) -> Unit = { delta ->
        if (findMatches.isNotEmpty()) {
            val target =
                if (findNavigated) findMatchIndex + delta
                else if (delta >= 0) 0 else findMatches.lastIndex
            goToFindMatch(target)
        }
    }
    // Replace and Replace All both go through ReplaceTaskTitles — one path, one history label. Replace
    // rewrites the current hit's range alone; Replace All rewrites every hit of every matched TASK, once
    // per task, because renaming is per task and a mirrored task must not be rewritten once per occurrence.
    val findReplaceCurrent: () -> Unit = {
        val match = findCurrentMatch
        val title = match?.let { state.tasks[it.taskId]?.title }
        if (match != null && title != null && match.end <= title.length) {
            vm.dispatch(
                SchedulerIntent.ReplaceTaskTitles(
                    mapOf(
                        match.taskId to
                            TaskTreeSearch.replaceRange(title, match.start, match.end, findReplacement.text),
                    ),
                ),
            )
        }
    }
    val findReplaceAll: () -> Unit = {
        val titles =
            TaskTreeSearch.replaceAllTitles(state, findQuery.text, findOptions, findReplacement.text)
        if (titles.isNotEmpty()) vm.dispatch(SchedulerIntent.ReplaceTaskTitles(titles))
    }
    val closeFind: () -> Unit = {
        findOpen = false
        findFieldFocused = false
        focusRequester.requestFocus()
    }

    // A new query starts its navigation over. Deliberately does NOT jump to the first hit as the user
    // types: every jump is a selection history unit, and Alt+← would then have to walk back over one per
    // keystroke. The tree shades every hit live, so typing still shows where they are.
    LaunchedEffect(findQuery.text, findOptions) {
        findMatchIndex = 0
        findNavigated = false
    }

    LaunchedEffect(findOpen, findFocusTick) {
        if (findOpen) findFocusRequester.requestFocus()
    }

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

    // PRD §5: the weight-table window closes if any cell enters Edit Mode. (A vanished sub-list — e.g.
    // via undo — is handled where the window is rendered.)
    LaunchedEffect(state.editSession) {
        if (state.editSession != null) {
            onSetWeightWindow(null)
            onSetRelativeWindow(null)
        }
    }

    // PRD §10: the min-time input reverts to a simple display when another cell is selected or any
    // cell enters Edit Mode (mirroring the weight table).
    LaunchedEffect(state.selection.main, state.editSession) {
        val current = minTimeEditCellId
        if (current != null && (state.editSession != null || state.selection.main != current)) {
            minTimeEditCellId = null
        }
    }

    // Vertical window bounds of each visible row, reported via onGloballyPositioned. A press-drag
    // only delivers move events to the row where the pointer went down (Compose retains the hit
    // path while a button is held), so the originating row resolves the cell under the cursor from
    // these shared bounds rather than relying on per-cell hover events that never fire mid-drag.
    // Keyed by occurrence (cellId + renderVia) so a cell mirrored under several expanded parents
    // keeps a distinct band per row and the resolved drop target carries the target row's own
    // renderVia — letting the blue line land in any layer of the tree (PRD §3).
    val rowBounds =
        remember { mutableStateMapOf<VisibleOccurrence, ClosedFloatingPointRange<Float>>() }
    val resolveRowAt: (Float) -> Pair<VisibleOccurrence, Boolean>? = resolve@{ windowY ->
        var last: Pair<VisibleOccurrence, Boolean>? = null
        for (occurrence in visibleOccurrences) {
            val bounds = rowBounds[occurrence] ?: continue
            if (windowY < bounds.start) return@resolve last ?: (occurrence to true)
            val mid = (bounds.start + bounds.endInclusive) / 2f
            last = occurrence to (windowY < mid)
            if (windowY <= bounds.endInclusive) return@resolve last
        }
        last
    }

    // Bring the revealed row into view. The rows of a freshly expanded ancestor are not positioned yet on
    // the frame the reveal is dispatched, so wait for their bounds to be reported (bounded, so a match on a
    // row that never lands — an unexpandable ancestor — does not spin).
    LaunchedEffect(findCurrentMatch, findMatches.size) {
        val match = findCurrentMatch ?: return@LaunchedEffect
        val occurrence = VisibleOccurrence(match.cellId, match.renderVia)
        var bounds = rowBounds[occurrence]
        var frames = 0
        while (bounds == null && frames < 10) {
            withFrameNanos { }
            bounds = rowBounds[occurrence]
            frames++
        }
        val row = bounds ?: return@LaunchedEffect
        val viewport = treeViewport ?: return@LaunchedEffect
        val margin = 24f
        val delta =
            when {
                row.start < viewport.start + margin -> row.start - viewport.start - margin
                row.endInclusive > viewport.endInclusive - margin ->
                    row.endInclusive - viewport.endInclusive + margin
                else -> 0f
            }
        if (delta != 0f) {
            treeScroll.animateScrollTo((treeScroll.value + delta).roundToInt().coerceAtLeast(0))
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(state.editSession, state.selection.main) {
        // PRD §10: don't pull focus to the tree root while a min-time input is open — that field
        // auto-focuses itself, and grabbing focus here would steal its caret. Same for the task-tree
        // selector's field, whose menus close the moment it loses focus.
        // ... nor while the find bar holds it: a match navigation moves selection.main, which is exactly
        // what re-runs this effect.
        if (state.editSession == null && minTimeEditCellId == null && !treeFieldFocused &&
            !findFieldFocused
        ) {
            focusRequester.requestFocus()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = event.isCtrlPressed || event.isMetaPressed
                if (mod && event.key == Key.Z) {
                    vm.dispatch(SchedulerIntent.Undo)
                    return@onPreviewKeyEvent true
                }
                if (mod && event.key == Key.Y) {
                    vm.dispatch(SchedulerIntent.Redo)
                    return@onPreviewKeyEvent true
                }
                // PRD §4 Find & replace. Above the Edit-Mode and min-time gates on purpose: Ctrl+F opens
                // the bar from wherever the tree's keyboard is. Pressed again while open, it re-focuses
                // the field and selects what is in it, so a new query simply overtypes the old one.
                if (mod && event.key == Key.F) {
                    findOpen = true
                    findQuery = findQuery.copy(selection = TextRange(0, findQuery.text.length))
                    findFocusTick++
                    return@onPreviewKeyEvent true
                }
                // PRD §5: selection history is undone/redone independently from content history.
                if (event.isAltPressed && event.key == Key.DirectionLeft) {
                    vm.dispatch(SchedulerIntent.UndoSelection)
                    return@onPreviewKeyEvent true
                }
                if (event.isAltPressed && event.key == Key.DirectionRight) {
                    vm.dispatch(SchedulerIntent.RedoSelection)
                    return@onPreviewKeyEvent true
                }
                // While the task-tree selector's field holds focus it owns the keyboard — the tree must not
                // turn a letter into a cell Edit Mode, nor Ctrl+A into "select every cell". Enter commits the
                // typed name and Escape restores the selected tree's name, both then handing focus back to
                // the tree; everything else (arrows, Backspace, the field's own Ctrl+A/C/V) reaches the
                // field. Global Ctrl+Z/Y and Alt+arrow above still apply.
                if (treeFieldFocused) {
                    when (event.key) {
                        Key.Escape -> {
                            treeNameDraft = activeTreeTitle
                            focusRequester.requestFocus()
                            return@onPreviewKeyEvent true
                        }
                        Key.Enter -> {
                            commitTaskTreeName()
                            focusRequester.requestFocus()
                            return@onPreviewKeyEvent true
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                }
                if (state.editSession != null) {
                    // PRD §4 Cancel: Escape abandons the session, reverting affected cells to their
                    // pre-edit text. Everything else — including Delete (forward-delete) and Ctrl+C/V/A
                    // (the field's usual copy/paste/select-all, PRD §4) — falls through to the edit field.
                    if (event.key == Key.Escape) {
                        vm.dispatch(SchedulerIntent.CancelEdit)
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                // PRD §10: while a min-time input is open it owns the keyboard. Enter/Tab/Escape act the
                // same as in a cell's Edit Mode (commit + navigate, or cancel); everything else — arrow
                // keys, Home/End, Backspace/Delete, digit entry and the field's own Ctrl+A/C/V — reaches
                // the focused BasicTextField. (Global Ctrl+Z/Y and Alt+arrow selection history above
                // still apply.)
                val minTimeCell = minTimeEditCellId
                if (minTimeCell != null) {
                    when {
                        event.key == Key.Escape -> {
                            // Cancel: restore the value the field opened with, then refocus the tree.
                            val taskId = state.cells[minTimeCell]?.taskId
                            val original = minTimeEditOriginal
                            if (taskId != null && original != null) {
                                vm.dispatch(SchedulerIntent.SetTaskMinimumTime(taskId, original))
                            }
                            minTimeEditCellId = null
                            focusRequester.requestFocus()
                            return@onPreviewKeyEvent true
                        }
                        // Enter / Shift+Tab — commit (the value is already applied live) and move up;
                        // Enter alone moves down; Tab moves into the first child (expanding it if needed).
                        event.key == Key.Enter && event.isShiftPressed -> {
                            minTimeEditCellId = null
                            vm.dispatch(SchedulerIntent.NavigateSelection(SelectionNavigate.Previous, shift = false))
                            return@onPreviewKeyEvent true
                        }
                        event.key == Key.Enter -> {
                            minTimeEditCellId = null
                            vm.dispatch(SchedulerIntent.NavigateSelection(SelectionNavigate.Next, shift = false))
                            return@onPreviewKeyEvent true
                        }
                        event.key == Key.Tab && event.isShiftPressed -> {
                            minTimeEditCellId = null
                            vm.dispatch(SchedulerIntent.NavigateSelection(SelectionNavigate.Previous, shift = false))
                            return@onPreviewKeyEvent true
                        }
                        event.key == Key.Tab -> {
                            minTimeEditCellId = null
                            vm.dispatch(SchedulerIntent.SelectFirstChild)
                            return@onPreviewKeyEvent true
                        }
                    }
                    return@onPreviewKeyEvent false
                }
                // PRD §3/§4 (not in Edit Mode): select-all and tree copy/paste.
                if (mod && event.key == Key.A) {
                    vm.dispatch(SchedulerIntent.SelectAllVisibleCells)
                    return@onPreviewKeyEvent true
                }
                // PRD §4: Ctrl+C copies the ENTIRE sub-tree under the selection and asks nothing — the
                // account's deep-copy depth belongs to the window, not to the chord. What each task
                // carries is still the window's three switches. Ctrl+X copies the same text and then
                // empties those very cells.
                if (mod && (event.key == Key.C || event.key == Key.X)) {
                    val text = SchedulerDomain.copyTreeText(state, state.selection)
                    if (text.isNotEmpty()) {
                        vm.dispatch(
                            if (event.key == Key.X) SchedulerIntent.CutSelection
                            else SchedulerIntent.CopySelection,
                        )
                        writeSystemClipboardText(text)
                    }
                    return@onPreviewKeyEvent true
                }
                if (mod && event.key == Key.V) {
                    val text = readSystemClipboardText() ?: return@onPreviewKeyEvent false
                    vm.dispatch(SchedulerIntent.PasteTree(text))
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionUp, Key.DirectionLeft -> {
                        vm.dispatch(
                            SchedulerIntent.NavigateSelection(
                                direction = SelectionNavigate.Previous,
                                shift = event.isShiftPressed,
                            ),
                        )
                        return@onPreviewKeyEvent true
                    }
                    Key.DirectionDown, Key.DirectionRight -> {
                        vm.dispatch(
                            SchedulerIntent.NavigateSelection(
                                direction = SelectionNavigate.Next,
                                shift = event.isShiftPressed,
                            ),
                        )
                        return@onPreviewKeyEvent true
                    }
                    // PRD §4: Backspace or Delete empties the selected cells when not editing.
                    Key.Delete, Key.Backspace -> {
                        vm.dispatch(SchedulerIntent.EmptySelectedCells)
                        return@onPreviewKeyEvent true
                    }
                    Key.Enter -> {
                        val multi = state.selection.selected.size > 1
                        if (multi) {
                            vm.dispatch(
                                SchedulerIntent.CycleMainSelection(forward = !event.isShiftPressed),
                            )
                        } else {
                            val main = state.selection.main
                            if (main != null && SchedulerDomain.isSelectableCell(state, main)) {
                                vm.dispatch(SchedulerIntent.BeginEdit(main))
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                    Key.Tab -> {
                        val multi = state.selection.selected.size > 1
                        if (multi) {
                            vm.dispatch(
                                SchedulerIntent.CycleMainSelection(forward = !event.isShiftPressed),
                            )
                        } else if (event.isShiftPressed) {
                            vm.dispatch(SchedulerIntent.NavigateSelection(SelectionNavigate.Previous))
                        } else {
                            vm.dispatch(SchedulerIntent.SelectFirstChild)
                        }
                        return@onPreviewKeyEvent true
                    }
                    else -> Unit
                }
                if (event.key.isModifierKey()) return@onPreviewKeyEvent true
                val main = state.selection.main ?: return@onPreviewKeyEvent false
                if (!SchedulerDomain.isSelectableCell(state, main)) return@onPreviewKeyEvent false
                // A dead key (^, ¨, ~ …) carries no character of its own — the composed letter is
                // only delivered to a focused field. So open Edit Mode immediately with empty text;
                // the cell becomes the focused field and the following letter composes into it (e.g.
                // ^ then e → ê), instead of the bare letter being swallowed into a fresh edit.
                val typed =
                    if (event.isDeadKey()) {
                        ""
                    } else {
                        event.printableChar() ?: return@onPreviewKeyEvent false
                    }
                // PRD §7/§8 focus: while a floating window is focused, the tree must not hijack letter
                // typing into Edit Mode — the focused window owns the keyboard then.
                if (state.focusedWindow != AppWindow.Tree) return@onPreviewKeyEvent false
                vm.dispatch(SchedulerIntent.BeginEdit(main, typed))
                true
            }
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

        Column(
            modifier = Modifier
                // Before verticalScroll, so these are the VIEWPORT's bounds and not the scrolled content's
                // — the band a revealed match is scrolled into.
                .onGloballyPositioned { coords ->
                    val top = coords.positionInWindow().y
                    treeViewport = top..(top + coords.size.height)
                }
                .verticalScroll(treeScroll)
                // The tree keeps its natural width and scrolls horizontally when the content is wider than
                // the viewport — it does NOT stretch to fill (or shrink to) the app's width, mirroring the
                // vertical scroll above. width(IntrinsicSize.Max) sizes the column to its widest row so the
                // rows' fillMaxWidth resolves against that natural width instead of the (infinite) scroll
                // constraint, and every cell border stays aligned to the same right edge.
                .horizontalScroll(rememberScrollState())
                .width(IntrinsicSize.Max)
                .pointerInput(Unit) {
                    detectTapGestures {
                        vm.dispatch(SchedulerIntent.ClearSelection)
                    }
                },
        ) {
            CellListSection(
                state = state,
                listId = state.rootListId,
                renderVia = null,
                depth = 0,
                visibleOrder = visibleOrder,
                priorities = priorities,
                searchHighlight = searchHighlight,
                onTogglePriorityWeights = { listId ->
                    // PRD §5: clicking a percentage opens that sub-list's window. Closing is by clicking
                    // anywhere else (the app's outside-press interceptor), not by re-clicking here — so
                    // this is deterministic regardless of when the interceptor runs during the gesture.
                    onSetRelativeWindow(null)
                    onSetWeightWindow(listId)
                },
                // PRD §5: the percentage's right-click menu opens the relative-priority window instead.
                // The two windows share the top layer, so opening one closes the other.
                onOpenRelativePriority = { clickedCellId ->
                    onSetWeightWindow(null)
                    onSetRelativeWindow(clickedCellId)
                },
                minTimeEditCellId = minTimeEditCellId,
                onToggleMinTimeEdit = { cellId ->
                    if (minTimeEditCellId == cellId) {
                        minTimeEditCellId = null
                    } else {
                        // Snapshot the value the field opens with so Escape can revert to it (PRD §10).
                        minTimeEditOriginal =
                            state.cells[cellId]?.taskId?.let { state.tasks[it]?.minimumMinutes } ?: 0
                        minTimeEditCellId = cellId
                    }
                },
                onOpenTaskEdit = { taskId -> editTaskId = taskId },
                // PRD §13 "copy": the cell's own task, with no children, in the same readable format
                // Ctrl+V pastes back. Right-clicking inside a multi-selection copies the whole block, so
                // the menu and Ctrl+C never disagree about what "the cell" means.
                onCopyCell = { cellId ->
                    val targets = SchedulerDomain.contextMenuCopyTargets(state, state.selection, cellId)
                    val text = SchedulerDomain.copyCellsText(state, targets, maxDepth = 1)
                    if (text.isNotEmpty()) writeSystemClipboardText(text)
                },
                // PRD §13 "deep copy": asks for the maximum depth first — the copy happens from its window.
                onDeepCopyCell = { cellId -> deepCopyCellId = cellId },
                moveDragActive = moveDragActive,
                moveDropTarget = moveDropTarget,
                resolveRowAt = resolveRowAt,
                onRowBounds = { occurrence, top, bottom -> rowBounds[occurrence] = top..bottom },
                onMoveDragStart = { moveDragActive = true },
                onMoveDropHover = { target, insertBefore, via ->
                    moveDropTarget = MoveDropTarget(target, insertBefore, via)
                },
                onMoveDragEnd = {
                    val target = moveDropTarget
                    if (moveDragActive && target != null) {
                        vm.dispatch(
                            SchedulerIntent.MoveSelectedCells(
                                targetCellId = target.cellId,
                                insertBefore = target.insertBefore,
                            ),
                        )
                    }
                    moveDragActive = false
                    moveDropTarget = null
                },
                onIntent = { intent ->
                    // PRD §8 focus: a click into the tree hands focus back from the calendar, so typing
                    // resumes entering Edit Mode — even on an already-selected cell (whose selection
                    // doesn't change, so the selection-keyed refocus effect wouldn't fire) and even
                    // while the calendar window stays open.
                    if (intent is SchedulerIntent.ClickCell) {
                        // PRD §10: but when the click lands on the cell whose min-time input is open, the
                        // BasicTextField needs to keep the focus it just took — yanking it back to the root
                        // focusable here is what made the caret vanish right after clicking the field.
                        if (intent.cellId != minTimeEditCellId) {
                            focusRequester.requestFocus()
                        }
                        // PRD §7: clicking into the tree returns focus to it from whichever window held it.
                        if (state.focusedWindow != AppWindow.Tree) {
                            vm.dispatch(SchedulerIntent.FocusWindow(AppWindow.Tree))
                        }
                    }
                    vm.dispatch(intent)
                },
            )
        }
    }

        // PRD §4: the find & replace bar, in the tree's top-right corner (VS Code's placement). A sibling
        // of the tree rather than a child, so the tree's own key handler never sees what is typed in it.
        if (findOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 16.dp),
            ) {
                TaskTreeFindBar(
                    query = findQuery,
                    replacement = findReplacement,
                    options = findOptions,
                    replaceExpanded = findReplaceExpanded,
                    matchCount = findMatches.size,
                    currentIndex = findCurrentIndex,
                    focusRequester = findFocusRequester,
                    onQueryChange = { findQuery = it },
                    onReplacementChange = { findReplacement = it },
                    onOptionsChange = { findOptions = it },
                    onToggleReplace = { findReplaceExpanded = !findReplaceExpanded },
                    onFindNext = { findStep(1) },
                    onFindPrevious = { findStep(-1) },
                    onReplace = findReplaceCurrent,
                    onReplaceAll = findReplaceAll,
                    onClose = closeFind,
                    onFocusChange = { findFieldFocused = it },
                )
            }
        }

        // PRD §13: the floating "edit" window, overlaying the tree.
        editTaskId?.let { taskId ->
            val task = state.tasks[taskId]
            if (task == null) {
                editTaskId = null
            } else {
                TaskEditWindow(
                    task = task,
                    // PRD §13: the screen switch and the schedule unit only exist for a schedulable leaf
                    // task — a parent task is a grouping and is never placed, so its window is text only.
                    isLeaf = SchedulerDomain.isLeafTask(state, taskId),
                    onSave = { noScreenDoable, entries, text ->
                        // One intent per section, and only for the sections that actually changed — so
                        // Save on an untouched window adds nothing to the Undo/Redo history (PRD §6).
                        val onScreen = !noScreenDoable
                        if (onScreen != task.onScreen) {
                            vm.dispatch(
                                SchedulerIntent.SetTaskScreenFlags(
                                    taskId = taskId,
                                    onScreen = onScreen,
                                    doableDuringBreak = task.doableDuringBreak,
                                ),
                            )
                        }
                        if (entries != task.scheduleUnit) {
                            vm.dispatch(SchedulerIntent.SetScheduleUnit(taskId, entries))
                        }
                        if (text != task.text) {
                            vm.dispatch(SchedulerIntent.SetTaskText(taskId, text))
                        }
                        editTaskId = null
                    },
                    onDismiss = { editTaskId = null },
                )
            }
        }

        // PRD §13: "deep copy" asks for its maximum depth here, then copies (see DeepCopyWindow).
        deepCopyCellId?.let { cellId ->
            if (state.cells[cellId] == null) {
                deepCopyCellId = null
            } else {
                DeepCopyWindow(
                    state = state,
                    // The same block "copy" takes — a deep copy of a multi-selection is every selected
                    // cell down to the chosen depth, not just the one under the cursor.
                    cellIds = SchedulerDomain.contextMenuCopyTargets(state, state.selection, cellId),
                    onCopy = { targets, maxDepth, options ->
                        // The depth and the three switches are the ACCOUNT's, not this copy's: what the
                        // window is asked here is what every later copy carries — the menu's "copy" and
                        // §4's Ctrl+C / Ctrl+X included (the chord still takes the whole sub-tree).
                        vm.dispatch(SchedulerIntent.SetDeepCopyMaxDepth(maxDepth))
                        vm.dispatch(SchedulerIntent.SetCopyOptions(options))
                        // Explicit options: the dispatches above have not reached this composition's state.
                        val text = SchedulerDomain.copyCellsText(state, targets, maxDepth, options)
                        if (text.isNotEmpty()) writeSystemClipboardText(text)
                        deepCopyCellId = null
                    },
                    onDismiss = { deepCopyCellId = null },
                )
            }
        }

        // PRD §5: the priority-weight window is drawn by the app (App.kt) on the top floating-window
        // layer, above the calendar — not here — so it sits over every other window and dismisses on a
        // click anywhere else (which still does its normal job).
    }
}

@Composable
private fun CellListSection(
    state: SchedulerState,
    listId: CellListId,
    renderVia: CellId?,
    depth: Int,
    visibleOrder: List<CellId>,
    priorities: Map<TaskId, Double>,
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
                            onEdit = { onOpenTaskEdit(taskId) },
                            onCopy = { onCopyCell(cellId) },
                            onDeepCopy = { onDeepCopyCell(cellId) },
                            // PRD §7/§13: the template on demand. Like "copy", it acts on the whole block
                            // when the right-click lands inside a multi-selection.
                            onAddDefaultSubtree =
                                if (state.defaultSubtree.isEmpty()) {
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
                        )
                    }
                } else {
                    null
                },
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
            )
        }
    }
}

/**
 * The two edit modes of the task-tree selector, mirroring a cell's Edit Mode (PRD §4): [Change] picks
 * *which* task tree the app shows (the tree menu is shown), [Rename] renames the selected one in place.
 */
private enum class TaskTreeEditMode { Change, Rename }

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

@Composable
private fun EditModeMenus(
    state: SchedulerState,
    cellId: CellId,
    draftText: String,
    onIntent: (SchedulerIntent) -> Unit,
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
        if (isBeingCreated) {
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
                EditMenuItem(label = entry.label, selected = index == selectedIndex) {
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
    onBoundsChange: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = state.lists[listId] ?: return
    // The rows of the table / chart are the populated cells of the sub-list (those with a priority).
    val populated =
        list.cellIds.filter { id -> state.cells[id]?.taskId?.let { priorities[it] != null } == true }
    var draggedColumn by remember(listId) { mutableStateOf<Int?>(null) }
    var columnDropIndex by remember(listId) { mutableStateOf<Int?>(null) }
    // The table as this window found it: captured on the composition that opened it, and kept across
    // every edit made since — Cancel always goes back to the start, never one step.
    val openedTable = remember(listId) { weightTableSnapshot(state, listId) }
    val tableEdited = weightTableSnapshot(state, listId) != openedTable

    // Stop reporting bounds once the window goes away, so the app's outside-press check has no stale rect.
    DisposableEffect(Unit) { onDispose { onBoundsChange(null) } }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, SheetColors.grid),
        // Bound the window to the screen (so the chart on the right is never pushed off-screen), publish
        // its window-space bounds (the app ignores presses inside them) and swallow taps that land inside.
        modifier = modifier
            .widthIn(max = 760.dp)
            .heightIn(max = 600.dp)
            .onGloballyPositioned { onBoundsChange(it.boundsInWindow()) }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
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
                        onAddColumn = { i -> onIntent(SchedulerIntent.AddPriorityColumn(listId, i)) },
                        onResetColumn = { c -> onIntent(SchedulerIntent.ResetPriorityColumn(listId, c)) },
                        onDeleteColumn = { c -> onIntent(SchedulerIntent.DeletePriorityColumn(listId, c)) },
                        onMoveColumn = { f, t -> onIntent(SchedulerIntent.MovePriorityColumn(listId, f, t)) },
                    )
                    populated.forEach { cellId ->
                        val cell = state.cells[cellId] ?: return@forEach
                        val title = cell.taskId?.let { state.tasks[it]?.title }.orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(WEIGHT_WINDOW_TITLE_WIDTH).padding(horizontal = 4.dp)) {
                                Text(
                                    text = title.ifEmpty { "(untitled)" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            for (column in list.weightColumns.indices) {
                                if (draggedColumn != null && columnDropIndex == column) ColumnDropLine()
                                Box(
                                    modifier = Modifier.background(
                                        if (draggedColumn == column) SheetColors.moveDragFill else Color.Transparent,
                                    ),
                                ) {
                                    WeightInputCell(
                                        value = cell.priorityWeights.getOrElse(column) { 1.0 },
                                        onSet = { value -> onIntent(SchedulerIntent.SetPriorityWeight(cellId, column, value)) },
                                    )
                                }
                            }
                            if (draggedColumn != null && columnDropIndex == list.weightColumns.size) ColumnDropLine()
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                PriorityChart(
                    titles = populated.map { id -> state.cells[id]?.taskId?.let { state.tasks[it]?.title }.orEmpty() },
                    // PRD §5: each row's share of THIS sub-list — the number the table on the left sets —
                    // rather than the task's absolute priority (its share of the whole tree).
                    fractions = populated.map { id -> RelativePriorityDomain.cellShare(state, id) },
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
    onBoundsChange: (Rect?) -> Unit,
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

    DisposableEffect(Unit) { onDispose { onBoundsChange(null) } }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, SheetColors.grid),
        // Same contract as the weight window: publish the bounds the app's outside-press check uses, and
        // swallow the taps that land inside so pressing in the window never closes it.
        modifier = modifier
            .widthIn(max = 760.dp)
            .heightIn(max = 600.dp)
            .onGloballyPositioned { onBoundsChange(it.boundsInWindow()) }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Relative priority of " + quoted(state.tasks[taskId]?.title.orEmpty()),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
 * The window's percentage field: the same "numbers and comma" input as the weight table (PRD §5), reading
 * and writing a fraction as a percentage. Like a weight field it commits every valid keystroke — which is
 * safe here because the edit targets an ABSOLUTE value: retargeting 5% and then 12% lands exactly where
 * going straight to 12% would (the scale factors compose), so a half-typed number leaves nothing skewed.
 */
@Composable
private fun PercentInputField(fraction: Double, onSet: (Double) -> Unit) {
    var text by remember(fraction) { mutableStateOf(formatWeight((fraction * 10_000).roundToInt() / 100.0)) }
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
private fun TaskRow(
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
) {
    val editFocusRequester = remember { FocusRequester() }
    // Whether this cell's right-click contextual menu ("edit" / "copy" / "deep copy" / "add default
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
                    DropdownMenuItem(
                        text = { Text("edit") },
                        onClick = {
                            contextMenuOpen = false
                            cellMenu.onEdit()
                        },
                    )
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
                Spacer(Modifier.weight(1f))
            }
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
 * A right-click (secondary button) opens a contextual menu — the cell's own ("edit" / "copy" / "deep copy" /
 * "add default sub-tree")
 * on the row, and the two-option one on the priority-percentage column (PRD §5). Returns a no-op modifier
 * when [enabled] is false (cells with no menu — empty / root-main), so only eligible cells react. [onOpen]
 * flips the local menu-visible flag. The press is consumed, which both keeps the freshly opened menu from
 * being dismissed by its own click and stops the row's handler re-opening the cell menu underneath the
 * percentage's (children are dispatched to first, so the inner menu wins that column).
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun contextMenuModifier(
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
 * [onAddDefaultSubtree] is null when there is no §7 template to add, which is the one entry that comes and
 * goes: an account that never defined a default sub-tree is not offered it.
 */
private class TaskCellMenuActions(
    val onEdit: () -> Unit,
    val onCopy: () -> Unit,
    val onDeepCopy: () -> Unit,
    val onAddDefaultSubtree: (() -> Unit)?,
)

/**
 * PRD §13 Edition Window: the floating "edit" editor opened from a cell's contextual menu, in three
 * sections — the no-screen switch, the schedule unit, and the task's text document. The first two only
 * exist for a schedulable leaf task ([isLeaf]); a parent task gets the text section alone.
 *
 * Schedule unit: the entries listed vertically — each a title field plus a spanning-time field with
 * increment/decrement buttons, a bin (remove) and a plus (insert above); a single trailing plus appends.
 * The Save button is disabled while the summed spanning times exceed the task's minimum time
 * ([SchedulerDomain.canSaveScheduleUnit]).
 */
@Composable
private fun TaskEditWindow(
    task: Task,
    isLeaf: Boolean,
    onSave: (noScreenDoable: Boolean, entries: List<ScheduleUnitEntry>, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val minimumMinutes = task.minimumMinutes
    var noScreenDoable by remember(task.id) { mutableStateOf(!task.onScreen) }
    var entries by remember(task.id) { mutableStateOf(task.scheduleUnit) }
    var text by remember(task.id) { mutableStateOf(task.text) }
    val sum = SchedulerDomain.scheduleUnitSumMinutes(entries)
    // A parent task's schedule unit is not editable here, so it can never block its own Save.
    val canSave = !isLeaf || SchedulerDomain.canSaveScheduleUnit(entries, minimumMinutes)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            // Tap (not clickable) to dismiss: a focused clickable also fires on Space/Enter, which would
            // close the window while typing in a field.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, SheetColors.grid),
            // Swallow taps so clicking inside the window doesn't reach the dismissing scrim.
            modifier = Modifier.width(360.dp).pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(task.title.ifBlank { "Task" }, style = MaterialTheme.typography.titleSmall)

                // Section 1 (leaf only): the screen switch. "On" means the task is done away from a
                // screen, so the §9 fill may only place it inside a no-screen period.
                if (isLeaf) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Can be done during a no-screen period",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = noScreenDoable,
                            onCheckedChange = { noScreenDoable = it },
                        )
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
                        onClick = { onSave(noScreenDoable, entries, text) },
                    ) { Text("Save") }
                }
            }
        }
    }
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
private fun DeepCopyWindow(
    state: SchedulerState,
    cellIds: List<CellId>,
    onCopy: (List<CellId>, Int, SchedulerDomain.CopyOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val key = cellIds.firstOrNull()
    // The raw text, so the field can be emptied while typing; a blank/0 depth simply copies nothing.
    var depthText by remember(key) { mutableStateOf(state.deepCopyMaxDepth.toString()) }
    var options by remember(key) { mutableStateOf(SchedulerDomain.CopyOptions.from(state)) }
    val depth = depthText.toIntOrNull() ?: 0
    val path = SchedulerDomain.deepCopyPathTitles(state, cellIds, depth)
    val canCopy = depth >= 1 && cellIds.isNotEmpty()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(key) { focusRequester.requestFocus() }
    fun setDepth(value: Int) {
        depthText = value.coerceIn(SchedulerDomain.DEEP_COPY_DEPTH_RANGE).toString()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            // Tap (not clickable) to dismiss: a focused clickable also fires on Space/Enter, which would
            // close the window while typing in the depth field.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, SheetColors.grid),
            modifier = Modifier
                .width(420.dp)
                // Swallow taps so clicking inside the window doesn't reach the dismissing scrim.
                .pointerInput(Unit) { detectTapGestures { } }
                // The depth field holds the focus, so this ancestor sees its keys first: Enter is the
                // window's own accept, not a character the field should ever receive.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        if (canCopy) onCopy(cellIds, depth, options)
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
                        value = depthText,
                        onValueChange = { raw -> depthText = raw.filter { it.isDigit() }.take(3) },
                        singleLine = true,
                        modifier = Modifier.width(88.dp).focusRequester(focusRequester),
                    )
                    Column {
                        WeightStepButton("+") { setDepth(depth + 1) }
                        WeightStepButton("−") { setDepth(depth - 1) }
                    }
                }

                HorizontalDivider()
                // What each copied task carries. The percentage row says which of the two forms the
                // priority travels in, so the switch reads as a choice rather than as a loss.
                DeepCopySwitchRow(
                    label = "Copy the task ids",
                    checked = options.includeIds,
                    onCheckedChange = { options = options.copy(includeIds = it) },
                )
                DeepCopySwitchRow(
                    label =
                        if (options.priorityTables) "Copy the priority weight tables"
                        else "Copy the priority percentage only",
                    checked = options.priorityTables,
                    onCheckedChange = { options = options.copy(priorityTables = it) },
                )
                DeepCopySwitchRow(
                    label = "Copy the task text",
                    checked = options.includeText,
                    onCheckedChange = { options = options.copy(includeText = it) },
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
                            options = SchedulerDomain.CopyOptions()
                        },
                    ) {
                        Text("reset")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(enabled = canCopy, onClick = { onCopy(cellIds, depth, options) }) { Text("copy") }
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
