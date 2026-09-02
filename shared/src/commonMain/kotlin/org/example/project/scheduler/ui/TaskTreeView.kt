package org.example.project.scheduler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerDomain.VisibleOccurrence
import org.example.project.scheduler.domain.TaskTreeSearch
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.platform.isDeadKey
import org.example.project.scheduler.platform.readSystemClipboardText
import org.example.project.scheduler.platform.writeSystemClipboardText
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.SelectionNavigate
import org.example.project.ui.TaskHueMemo
import org.example.project.ui.TaskPalette
import org.example.project.ui.rememberTaskHues
import org.example.project.ui.TaskTreeFindBar
import org.example.project.ui.isModifierKey
import org.example.project.ui.printableChar

/**
 * **The task tree.** Everything the tree *is* — the rows and their chrome, Edit Mode and its naming menus,
 * the selection and its keyboard, drag-move, the §13 contextual menu, Ctrl+C/X/V, the min-time field and the
 * Ctrl+F find bar — lives here, and only here.
 *
 * It exists as its own composable because the app draws the task tree **three times**, each over a different
 * [SchedulerState] with its intents wrapped differently, and nothing else:
 *
 *  - the account's own tree ([TaskSchedulerScreen]) — the live state, unwrapped;
 *  - the PRD §4 *default sub-tree* template ([org.example.project.ui.DefaultSubtreeWindow]). The template is
 *    a real tree ([org.example.project.scheduler.state.DefaultSubtreeTemplate]), so this is the same code
 *    over what [org.example.project.scheduler.state.projectDefaultSubtree] projects, wrapped in
 *    [SchedulerIntent.InDefaultSubtree];
 *  - PRD §7's **All tasks** window ([org.example.project.ui.TaskListWindow]) — the *live* tree re-rooted at a
 *    synthetic list holding one cell per task in the sorter's order
 *    ([org.example.project.scheduler.state.projectTaskList]), wrapped in [SchedulerIntent.InTaskList]. Its
 *    rows are the tree's own cells, so an edit there is an edit to the tree.
 *
 * A tree drawn by a second implementation is a tree that silently drifts from the first, which is exactly
 * what this replaced.
 *
 * Everything it needs is a parameter, so it holds no opinion about which tree it is showing:
 *
 * - [state] is the tree to draw, [priorities] the percentages its rows show (the template's are its own
 *   shares — see `defaultSubtreePriorities`), and [onIntent] where its intents go.
 * - the four `onSet…` callbacks hoist the sort-2 pop-ups it opens up to the app, so they land on the top
 *   layer above every floating window rather than under one (CLAUDE.md *Pop-up windows*).
 * - [keyboardActive] says whether this tree currently owns the keyboard; [aboveTreeKeyHandler] lets whatever
 *   sits above it claim a key first (returning null to decline).
 * - [rowTrailing] draws one extra cell at the end of every row — the template's switch, the "All tasks"
 *   window's occurrence count, and nothing in the account's own tree.
 * - [onGoToTaskTree], [rootRenameOnly], [allowRootDrop] and [colorSource] are the four things that follow
 *   from a drawing whose ROOT is not the tree's own root; only the "All tasks" window passes them.
 */
@Composable
internal fun TaskTreeView(
    state: SchedulerState,
    priorities: Map<TaskId, Double>,
    onIntent: (SchedulerIntent) -> Unit,
    keyboardActive: Boolean,
    modifier: Modifier = Modifier,
    onSetWeightWindow: (CellListId?) -> Unit = {},
    onSetRelativeWindow: (CellId?) -> Unit = {},
    onSetEditTask: (TaskId?) -> Unit = {},
    /**
     * PRD §5: opens a **category's** own window ([org.example.project.ui.CategoryEditWindow]) — hoisted to
     * the app for the same reason the four `onSet…` above are: it is a sort-2 pop-up and must draw on the
     * top layer, not under whatever floating window this tree is inside.
     */
    onSetEditCategory: (org.example.project.scheduler.model.CategoryId?) -> Unit = {},
    onSetDeepCopyCell: (CellId?) -> Unit = {},
    /** Focus handle of the tree itself, so a caller's own field can hand the keyboard back. */
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** First refusal on a key press: true/false to claim it, null to let the tree have it. */
    aboveTreeKeyHandler: (KeyEvent) -> Boolean? = { null },
    rowTrailing: (@Composable (CellId) -> Unit)? = null,
    /** The app-wide focus a click into this tree claims, or null for a tree inside a floating window. */
    refocusWindow: AppWindow? = null,
    /**
     * PRD §8/§13 "go to task tree" on a row's contextual menu, or null where the entry has no meaning —
     * the account's own tree (you are already there) and the §4 template. PRD §7's "All tasks" window is
     * what passes it: its rows are the tree's cells listed in the sorter's order, so "where is this in the
     * tree" is exactly the question its rows raise. Same entry, same name and the same
     * [org.example.project.scheduler.state.SchedulerIntent.RevealCell] primitive as the calendar panel's.
     */
    onGoToTaskTree: ((TaskId) -> Unit)? = null,
    /**
     * PRD §7 "All tasks": the rows of the ROOT list are always in renaming mode, so no Mode selector is
     * drawn for them. The row IS the task there and the order is the window's sorter, so "change task" could
     * only re-point a cell the user is not looking at. False in the tree, where the root is an ordinary
     * level.
     */
    rootRenameOnly: Boolean = false,
    /**
     * PRD §7 "All tasks": whether a drag-move may drop into the ROOT list. False there — the root's order is
     * the sorter's, so a drop would be a reordering the next re-sort silently undoes — and the blue line
     * simply never appears at root level. (The reducer refuses such a move too; this is what the user sees.)
     */
    allowRootDrop: Boolean = true,
    /**
     * The state the task COLOURS are solved over, when that is not the state being drawn.
     *
     * PRD §7's "All tasks" window draws a projection re-rooted at its own list
     * ([org.example.project.scheduler.state.projectTaskList]), and a colour is a function of the tree's
     * depth-first order (ADR 0013) — solved over that projection the ring would be ordered by the sorter and
     * a task would be one colour in the list and another in the tree. Passing the live state keeps the one
     * identity the palette exists for: the tree's cell, this window's row and the calendar's panel read the
     * same hue.
     */
    colorSource: SchedulerState = state,
    /**
     * Which tree's colour solution this drawing belongs to — see [org.example.project.ui.TaskHueMemo].
     * The account's tree shares one memo with the calendar so the two cannot disagree about a task's colour;
     * the PRD §4 template is a different tree and gets its own.
     */
    hueMemo: TaskHueMemo = TaskHueMemo.account,
) {
    val visibleOrder = SchedulerDomain.selectableVisibleOrder(state)
    val visibleOccurrences = SchedulerDomain.selectableVisibleOccurrences(state)
    // Each task's own colour (see [org.example.project.scheduler.domain.TaskColorSpace]). Derived here
    // rather than passed in, so BOTH trees this composable draws are coloured by the one rule over the very
    // state they are showing — the account's tree over the live state, the PRD §4 template over the
    // projection whose root list is the template's own — but through the [hueMemo] the caller names, which
    // is what holds the previous solution the ties are settled against, caches the answer per tree, and
    // debounces: re-walking the whole tree per keystroke is the O(everything) cost ADR 0009 forbids.
    val taskHues = rememberTaskHues(colorSource, hueMemo)
    val taskColors = remember(taskHues) { TaskPalette.sheetColors(taskHues) }
    var moveDragActive by remember { mutableStateOf(false) }
    var moveDropTarget by remember { mutableStateOf<MoveDropTarget?>(null) }
    // PRD §10: the cell whose minimum-time field is currently expanded into an input (clicking its
    // simple display opens it), or null when every min-time field shows as a plain label.
    var minTimeEditCellId by remember { mutableStateOf<CellId?>(null) }
    // PRD §10: the minimum-time value the open input started with, so Escape can restore it (mirroring
    // how Edit Mode's Escape reverts a cell to its pre-edit text). Null when no input is open.
    var minTimeEditOriginal by remember { mutableStateOf<Int?>(null) }

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
            onIntent(SchedulerIntent.RevealCell(match.cellId, match.ancestors))
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
            onIntent(
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
        if (titles.isNotEmpty()) onIntent(SchedulerIntent.ReplaceTaskTitles(titles))
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

    // Bring the SELECTED row into view. It is keyed on the selection rather than on the find bar's current
    // match because a match is not the only thing that reveals a row: PRD §8's "go to task tree" reaches the
    // tree through the very same RevealCell, from a surface that cannot reach into this composable at all.
    // (Ordinary keyboard navigation lands here too, and wants exactly the same thing.) The find match stays
    // a key so stepping onto a second hit inside a row already on screen still re-runs — it then measures a
    // zero delta and scrolls nothing.
    //
    // The rows of a freshly expanded ancestor are not positioned yet on the frame the reveal is dispatched,
    // so wait for their bounds to be reported (bounded, so a selection on a row that never lands — an
    // unexpandable ancestor — does not spin).
    LaunchedEffect(state.selection.main, state.selection.renderVia, findCurrentMatch, findMatches.size) {
        val selected = state.selection.main ?: return@LaunchedEffect
        val occurrence = VisibleOccurrence(selected, state.selection.renderVia)
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
        // auto-focuses itself, and grabbing focus here would steal its caret. Same while anything above
        // the tree holds the keyboard (the task-tree selector's field, whose menus close the moment it
        // loses focus) — [keyboardActive] is what says so.
        // ... nor while the find bar holds it: a match navigation moves selection.main, which is exactly
        // what re-runs this effect.
        if (state.editSession == null && minTimeEditCellId == null && keyboardActive &&
            !findFieldFocused
        ) {
            focusRequester.requestFocus()
        }
    }

    Box(modifier = modifier) {
    Column(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = event.isCtrlPressed || event.isMetaPressed
                if (mod && event.key == Key.Z) {
                    onIntent(SchedulerIntent.Undo)
                    return@onPreviewKeyEvent true
                }
                if (mod && event.key == Key.Y) {
                    onIntent(SchedulerIntent.Redo)
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
                    onIntent(SchedulerIntent.UndoSelection)
                    return@onPreviewKeyEvent true
                }
                if (event.isAltPressed && event.key == Key.DirectionRight) {
                    onIntent(SchedulerIntent.RedoSelection)
                    return@onPreviewKeyEvent true
                }
                // Anything sitting above the tree that can hold the keyboard — the task-tree selector's
                // name field — gets first refusal, so the tree never turns a letter typed there into a cell
                // Edit Mode nor Ctrl+A into "select every cell". Global Ctrl+Z/Y and Alt+arrow above still
                // apply either way.
                aboveTreeKeyHandler(event)?.let { return@onPreviewKeyEvent it }
                if (state.editSession != null) {
                    // PRD §4 Cancel: Escape abandons the session, reverting affected cells to their
                    // pre-edit text. Everything else — including Delete (forward-delete) and Ctrl+C/V/A
                    // (the field's usual copy/paste/select-all, PRD §4) — falls through to the edit field.
                    if (event.key == Key.Escape) {
                        onIntent(SchedulerIntent.CancelEdit)
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
                                onIntent(SchedulerIntent.SetTaskMinimumTime(taskId, original))
                            }
                            minTimeEditCellId = null
                            focusRequester.requestFocus()
                            return@onPreviewKeyEvent true
                        }
                        // Enter / Shift+Tab — commit (the value is already applied live) and move up;
                        // Enter alone moves down; Tab moves into the first child (expanding it if needed).
                        event.key == Key.Enter && event.isShiftPressed -> {
                            minTimeEditCellId = null
                            onIntent(SchedulerIntent.NavigateSelection(SelectionNavigate.Previous, shift = false))
                            return@onPreviewKeyEvent true
                        }
                        event.key == Key.Enter -> {
                            minTimeEditCellId = null
                            onIntent(SchedulerIntent.NavigateSelection(SelectionNavigate.Next, shift = false))
                            return@onPreviewKeyEvent true
                        }
                        event.key == Key.Tab && event.isShiftPressed -> {
                            minTimeEditCellId = null
                            onIntent(SchedulerIntent.NavigateSelection(SelectionNavigate.Previous, shift = false))
                            return@onPreviewKeyEvent true
                        }
                        event.key == Key.Tab -> {
                            minTimeEditCellId = null
                            onIntent(SchedulerIntent.SelectFirstChild)
                            return@onPreviewKeyEvent true
                        }
                    }
                    return@onPreviewKeyEvent false
                }
                // PRD §3/§4 (not in Edit Mode): select-all and tree copy/paste.
                if (mod && event.key == Key.A) {
                    onIntent(SchedulerIntent.SelectAllVisibleCells)
                    return@onPreviewKeyEvent true
                }
                // PRD §4: Ctrl+C copies the ENTIRE sub-tree under the selection and asks nothing — the
                // account's deep-copy depth belongs to the window, not to the chord. What each task
                // carries is still the window's three switches. Ctrl+X copies the same text and then
                // empties those very cells.
                if (mod && (event.key == Key.C || event.key == Key.X)) {
                    val text = SchedulerDomain.copyTreeText(state, state.selection)
                    if (text.isNotEmpty()) {
                        onIntent(
                            if (event.key == Key.X) SchedulerIntent.CutSelection
                            else SchedulerIntent.CopySelection,
                        )
                        writeSystemClipboardText(text)
                    }
                    return@onPreviewKeyEvent true
                }
                if (mod && event.key == Key.V) {
                    val text = readSystemClipboardText() ?: return@onPreviewKeyEvent false
                    onIntent(SchedulerIntent.PasteTree(text))
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionUp, Key.DirectionLeft -> {
                        onIntent(
                            SchedulerIntent.NavigateSelection(
                                direction = SelectionNavigate.Previous,
                                shift = event.isShiftPressed,
                            ),
                        )
                        return@onPreviewKeyEvent true
                    }
                    Key.DirectionDown, Key.DirectionRight -> {
                        onIntent(
                            SchedulerIntent.NavigateSelection(
                                direction = SelectionNavigate.Next,
                                shift = event.isShiftPressed,
                            ),
                        )
                        return@onPreviewKeyEvent true
                    }
                    // PRD §4: Backspace or Delete empties the selected cells when not editing.
                    Key.Delete, Key.Backspace -> {
                        onIntent(SchedulerIntent.EmptySelectedCells)
                        return@onPreviewKeyEvent true
                    }
                    Key.Enter -> {
                        val multi = state.selection.selected.size > 1
                        if (multi) {
                            onIntent(
                                SchedulerIntent.CycleMainSelection(forward = !event.isShiftPressed),
                            )
                        } else {
                            val main = state.selection.main
                            if (main != null && SchedulerDomain.isSelectableCell(state, main)) {
                                onIntent(SchedulerIntent.BeginEdit(main))
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                    Key.Tab -> {
                        val multi = state.selection.selected.size > 1
                        if (multi) {
                            onIntent(
                                SchedulerIntent.CycleMainSelection(forward = !event.isShiftPressed),
                            )
                        } else if (event.isShiftPressed) {
                            onIntent(SchedulerIntent.NavigateSelection(SelectionNavigate.Previous))
                        } else {
                            onIntent(SchedulerIntent.SelectFirstChild)
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
                // PRD §7/§8 focus: while something else is focused, this tree must not hijack letter
                // typing into Edit Mode — whatever holds focus owns the keyboard then.
                if (!keyboardActive) return@onPreviewKeyEvent false
                onIntent(SchedulerIntent.BeginEdit(main, typed))
                true
            }
    ) {
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
                        onIntent(SchedulerIntent.ClearSelection)
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
                taskColors = taskColors,
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
                onOpenTaskEdit = { taskId -> onSetEditTask(taskId) },
                onOpenCategoryEdit = { categoryId -> onSetEditCategory(categoryId) },
                // PRD §13 "copy": the cell's own task, with no children, in the same readable format
                // Ctrl+V pastes back. Right-clicking inside a multi-selection copies the whole block, so
                // the menu and Ctrl+C never disagree about what "the cell" means.
                onCopyCell = { cellId ->
                    val targets = SchedulerDomain.contextMenuCopyTargets(state, state.selection, cellId)
                    val text = SchedulerDomain.copyCellsText(state, targets, maxDepth = 1)
                    if (text.isNotEmpty()) writeSystemClipboardText(text)
                },
                // PRD §13 "deep copy": asks for the maximum depth first — the copy happens from its window.
                onDeepCopyCell = { cellId -> onSetDeepCopyCell(cellId) },
                moveDragActive = moveDragActive,
                moveDropTarget = moveDropTarget,
                resolveRowAt = resolveRowAt,
                onRowBounds = { occurrence, top, bottom -> rowBounds[occurrence] = top..bottom },
                onMoveDragStart = { moveDragActive = true },
                onMoveDropHover = { target, insertBefore, via ->
                    // PRD §7: no blue line in the "All tasks" root — a row rendered there is a root row
                    // exactly when nothing rendered it (`via == null`), which is the same test the tree's
                    // own occurrences use. Cleared rather than kept at the last valid target, so releasing
                    // over the root commits nothing at all.
                    moveDropTarget =
                        if (!allowRootDrop && via == null) null
                        else MoveDropTarget(target, insertBefore, via)
                },
                onMoveDragEnd = {
                    val target = moveDropTarget
                    if (moveDragActive && target != null) {
                        onIntent(
                            SchedulerIntent.MoveSelectedCells(
                                targetCellId = target.cellId,
                                insertBefore = target.insertBefore,
                            ),
                        )
                    }
                    moveDragActive = false
                    moveDropTarget = null
                },
                rowTrailing = rowTrailing,
                onGoToTaskTree = onGoToTaskTree,
                rootRenameOnly = rootRenameOnly,
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
                        // Null for a tree drawn inside a floating window — that window's own raise-on-press
                        // is what focuses it, and the app-wide focus never leaves the surface behind it.
                        if (refocusWindow != null && state.focusedWindow != refocusWindow) {
                            onIntent(SchedulerIntent.FocusWindow(refocusWindow))
                        }
                    }
                    onIntent(intent)
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


        // PRD §5: the priority-weight window is drawn by the app (App.kt) on the top floating-window
        // layer, above the calendar — not here — so it sits over every other window and dismisses on a
        // click anywhere else (which still does its normal job).
    }
}
