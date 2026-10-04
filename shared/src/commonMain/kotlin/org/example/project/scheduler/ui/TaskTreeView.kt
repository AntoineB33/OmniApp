package org.example.project.scheduler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import org.example.project.ui.INDENT_STEP_DP
import org.example.project.ui.SheetColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.perf.Perf
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerDomain.VisibleOccurrence
import org.example.project.scheduler.domain.TaskTreeSearch
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.platform.isDeadKey
import org.example.project.scheduler.platform.readSystemClipboardText
import org.example.project.scheduler.platform.writeSystemClipboardText
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.ui.undoRedoIntentFor
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.SelectionNavigate
import org.example.project.ui.TaskHueMemo
import org.example.project.ui.TaskPalette
import org.example.project.ui.rememberTaskHues
import org.example.project.ui.TaskTreeFindBar
import org.example.project.ui.isModifierKey
import org.example.project.ui.LocalTransientMenuHost
import org.example.project.ui.LocalWindowFrameHost
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
 *  - PRD §7's **Search** window's expanded task rows ([org.example.project.ui.SearchWindow]) — the *live* tree
 *    re-rooted at the task's own sub-list ([org.example.project.scheduler.state.projectSearchSubtree]), wrapped
 *    in [SchedulerIntent.InSearchSubtree]. Its rows are the tree's own cells, so an edit there is an edit to
 *    the tree.
 *
 * A tree drawn by a second implementation is a tree that silently drifts from the first, which is exactly
 * what this replaced.
 *
 * Everything it needs is a parameter, so it holds no opinion about which tree it is showing:
 *
 * - [state] is the tree to draw, [priorities] the percentages its rows show (the template's are its own
 *   shares — see `defaultSubtreePriorities`), and [onIntent] where its intents go.
 * - the four `onSet…` callbacks hoist the windows it opens up to the app, so they land on the top
 *   layer above every floating window rather than under one (CLAUDE.md *Pop-up windows*).
 * - [keyboardActive] says whether this tree currently owns the keyboard; [aboveTreeKeyHandler] lets whatever
 *   sits above it claim a key first (returning null to decline).
 * - [rowTrailing] draws one extra cell at the end of every row — the template's switch, the Search window's
 *   "not in a task tree" mark, and nothing in the account's own tree.
 * - [onGoToTaskTree], [colorSource] and [namingSource] are the things that follow from a drawing whose ROOT is
 *   not the tree's own root; the Search window's sub-trees pass them.
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
     * the app for the same reason the four `onSet…` above are: it is a window and must draw on the
     * top layer, not under whatever floating window this tree is inside.
     */
    onSetEditCategory: (org.example.project.scheduler.model.CategoryId?) -> Unit = {},
    onSetDeepCopyCell: (CellId?) -> Unit = {},
    /** Focus handle of the tree itself, so a caller's own field can hand the keyboard back. */
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** First refusal on a key press: true/false to claim it, null to let the tree have it. */
    aboveTreeKeyHandler: (KeyEvent) -> Boolean? = { null },
    rowTrailing: (@Composable (CellId) -> Unit)? = null,
    /** One section inside every row before its expand arrow — the Search window's check box; null elsewhere. */
    rowLeading: (@Composable RowScope.(CellId) -> Unit)? = null,
    /** The app-wide focus a click into this tree claims, or null for a tree inside a floating window. */
    refocusWindow: HistoryWindow? = null,
    /**
     * PRD §8/§13 "go to task tree" on a row's contextual menu, or null where the entry has no meaning —
     * the account's own tree (you are already there) and the §4 template. PRD §7's Search window's sub-trees
     * pass it: their rows are the tree's cells shown outside it, so "where is this in the tree" is exactly the
     * question they raise. Same entry, same name and the same
     * [org.example.project.scheduler.state.SchedulerIntent.RevealCell] primitive as the calendar panel's.
     */
    onGoToTaskTree: ((TaskId) -> Unit)? = null,
    /**
     * The state the task COLOURS are solved over, when that is not the state being drawn.
     *
     * PRD §7's Search window draws a projection re-rooted at a task's own sub-list
     * ([org.example.project.scheduler.state.projectSearchSubtree]), and a colour is a function of the tree's
     * depth-first order (ADR 0013) — solved over that projection the ring would start at the sub-list and a
     * task would be one colour there and another in the tree. Passing the live state keeps the one
     * identity the palette exists for: the tree's cell, this window's row and the calendar's panel read the
     * same hue.
     */
    colorSource: SchedulerState = state,
    /**
     * The state a cell's Change Task menu NAMES its rows from, when that is not the state being drawn — the
     * same shape as [colorSource], and for the same kind of reason.
     *
     * A row's path says WHICH task of that title this row is, which is a fact about the account's tree. Both
     * projections re-root the state, so read off the drawing a live task is named by where the sub-tree
     * or the TEMPLATE puts it (PRD §7's Search, PRD §4's window) — the release account's menu offered
     * sixty-odd rows all reading "planning". See
     * [org.example.project.scheduler.domain.SchedulerDomain.changeTaskMenuEntries].
     */
    namingSource: SchedulerState = state,
    /**
     * Which tree's colour solution this drawing belongs to — see [org.example.project.ui.TaskHueMemo].
     * The account's tree shares one memo with the calendar so the two cannot disagree about a task's colour;
     * the PRD §4 template is a different tree and gets its own.
     */
    hueMemo: TaskHueMemo = TaskHueMemo.account,
) {
    Perf.count("recompose.TaskTreeView")
    // The callbacks this tree hands its rows read the state through HERE, never by capturing it — see the
    // same holder in [CellListSection]. A lambda that closes over the whole [SchedulerState] is a new
    // argument on every state change, which reaches every row and makes all of them re-compose for a change
    // none of them draws.
    val currentState by rememberUpdatedState(state)
    val visibleOrder = SchedulerDomain.selectableVisibleOrder(state)
    // Every drawn row, in order, with the row it hangs under and its PATH — the one key naming a single row
    // (a mirrored sub-list repeats its rows' occurrences; see [SchedulerDomain.VisibleRow.path]).
    val visibleRows =
        remember(state.lists, state.cells, state.tasks, state.expanded) { SchedulerDomain.visibleRows(state) }
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
    // Whether the tree's own focusable Column is the thing holding the focus — as opposed to a field
    // INSIDE it (a cell's edit field, the find bar). `isFocused` is exactly that question: it goes false
    // the moment a child takes over, while `hasFocus` would stay true. Read by the key handler to tell
    // the one window in which an open session's field has not taken the caret yet (below).
    var treeSelfFocused by remember { mutableStateOf(false) }
    // The tree's scroll, hoisted so a revealed match can be brought into view, plus the viewport's own
    // window band (recorded OUTSIDE the scroll modifier, so it is the viewport and not the scrolled
    // content) to compare the row's band against.
    val treeScroll = rememberScrollState()
    // Hoisted (not remembered inline on the column) so the whole tree AREA can drive it — see the Box below.
    val treeHorizontalScroll = rememberScrollState()
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
    // Keyed by the row's PATH, never by its occurrence: a cell mirrored under several expanded parents
    // keeps a distinct band per row even BELOW the mirror's first level, where the occurrence (cell + via)
    // repeats — keyed by occurrence, the copies overwrote each other's bands and a row was taken to be where
    // its twin further down was. The resolved drop target still carries the target row's own renderVia,
    // letting the blue line land in any layer of the tree (PRD §3).
    val rowBounds =
        remember { mutableStateMapOf<List<CellId>, ClosedFloatingPointRange<Float>>() }
    // Read through the holder for the reason the two above are: this lambda is handed to EVERY row, so a
    // new instance of it on each pass is a changed argument for every one of them.
    val currentVisibleRows by rememberUpdatedState(visibleRows)
    val resolveRowAt: (Float) -> Pair<VisibleOccurrence, Boolean>? = resolve@{ windowY ->
        var last: Pair<VisibleOccurrence, Boolean>? = null
        for (row in currentVisibleRows) {
            val occurrence = row.occurrence
            if (!SchedulerDomain.isSelectableCell(currentState, occurrence.cellId)) continue
            val bounds = rowBounds[row.path] ?: continue
            if (windowY < bounds.start) return@resolve last ?: (occurrence to true)
            val mid = (bounds.start + bounds.endInclusive) / 2f
            last = occurrence to (windowY < mid)
            if (windowY <= bounds.endInclusive) return@resolve last
        }
        last
    }

    // THE PINNED PARENT ROW. At the top of the viewport, the direct parent of the row appearing right below a
    // band of one normal row height is drawn over that band, so the user always sees what the rows under it
    // belong to. Only when the parent's own row has started to leave the top: a parent still fully
    // on screen needs no copy. A multi-line parent shows its last [TASK_ROW_MIN_HEIGHT] only, the part that
    // sits just above its children. Read off the bands the rows already report, so it follows the scroll with
    // no layout of its own, over the rows already drawn (bounded by the expanded tree), and stops at the
    // first row below the band.
    val rowHeightPx = with(LocalDensity.current) { TASK_ROW_MIN_HEIGHT.toPx() }
    val pinnedRow by remember(rowHeightPx) {
        derivedStateOf {
            val viewport = treeViewport ?: return@derivedStateOf null
            val bandBottom = viewport.start + rowHeightPx
            val rows = currentVisibleRows
            // The row appearing RIGHT BELOW the band: the first whose bottom lies past it, so a row the band
            // half covers is still that row. Never the first FULLY visible one — when a half-covered parent
            // row straddles the band, that one is its child, and the copy would name the half-covered row
            // above it instead of what that row itself hangs under.
            val first =
                rows.firstOrNull { row ->
                    rowBounds[row.path]?.let { it.endInclusive > bandBottom + 0.5f } == true
                } ?: return@derivedStateOf null
            if (first.parent == null) return@derivedStateOf null
            val parentPath = first.path.dropLast(1)
            val parentTop = rowBounds[parentPath]?.start ?: return@derivedStateOf null
            if (parentTop >= viewport.start - 0.5f) return@derivedStateOf null
            rows.firstOrNull { it.path == parentPath }
        }
    }
    // Selecting the pinned copy scrolls ITS real row into view — the one at the copy's path, not a mirrored
    // twin of it elsewhere, which the selection (a cell and a via) cannot tell apart. A press on a row that is
    // ALREADY the main selection changes no selection, so the copy bumps the counter too, which the reveal
    // below is keyed on.
    var pinnedRevealRequests by remember { mutableStateOf(0) }
    var pinnedRevealPath by remember { mutableStateOf<List<CellId>?>(null) }

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
    LaunchedEffect(
        state.selection.main, state.selection.renderVia, findCurrentMatch, findMatches.size, pinnedRevealRequests,
    ) {
        val selected = state.selection.main ?: return@LaunchedEffect
        val occurrence = VisibleOccurrence(selected, state.selection.renderVia)
        // The selected row's band: the pinned copy's own row when that is what was pressed, else the FIRST
        // row showing the selected occurrence (a mirrored twin is the same selection).
        fun selectedBounds(): ClosedFloatingPointRange<Float>? {
            val rows = currentVisibleRows
            val path =
                pinnedRevealPath?.takeIf { p -> rows.any { it.path == p && it.occurrence == occurrence } }
                    ?: rows.firstOrNull { it.occurrence == occurrence }?.path
                    ?: return null
            return rowBounds[path]
        }
        var bounds = selectedBounds()
        var frames = 0
        while (bounds == null && frames < 10) {
            withFrameNanos { }
            bounds = selectedBounds()
            frames++
        }
        val row = bounds ?: return@LaunchedEffect
        val viewport = treeViewport ?: return@LaunchedEffect
        val margin = 24f
        // Above, it leaves exactly one normal row height: the band the row's own parent is pinned in, so a row
        // revealed from above lands just under what it belongs to, never under the pinned copy.
        val topMargin = rowHeightPx
        val delta =
            when {
                row.start < viewport.start + topMargin -> row.start - viewport.start - topMargin
                row.endInclusive > viewport.endInclusive - margin ->
                    row.endInclusive - viewport.endInclusive + margin
                else -> 0f
            }
        if (delta != 0f) {
            treeScroll.animateScrollTo((treeScroll.value + delta).roundToInt().coerceAtLeast(0))
        }
    }

    // What the user is working in is what owns the keyboard, so the tree behind it goes DEAF — one rule,
    // read here rather than passed down by each of the three surfaces that draw this tree. Without it,
    // typing a letter with the priority-weight window open began a RENAME of the selected tree cell.
    //
    // Two things take it: a contextual MENU standing over a cell (which still leaves on the first press
    // outside it), and a focused window that answers keystrokes itself ([WindowFrameHost.keyboardClaimed]
    // — a window no longer leaves on an outside press, so this has to follow the FOCUS and not merely
    // "something is open", or the tree could never be typed in again without closing it).
    //
    // The tree is only deaf, never blind: nothing here is modal, and a press still reaches the tree — which
    // is exactly what hands the keyboard back.
    val keyboardOwned =
        keyboardActive &&
            LocalTransientMenuHost.current?.anyOpen != true &&
            LocalWindowFrameHost.current?.keyboardClaimed != true

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // `keyboardOwned` is a key so the tree takes the keyboard BACK the moment it is handed over.
    LaunchedEffect(state.editSession, state.selection.main, keyboardOwned) {
        // PRD §10: don't pull focus to the tree root while a min-time input is open — that field
        // auto-focuses itself, and grabbing focus here would steal its caret. Same while anything above
        // the tree holds the keyboard (the task-tree selector's field, whose menus close the moment it
        // loses focus) — [keyboardActive] is what says so.
        // ... nor while the find bar holds it: a match navigation moves selection.main, which is exactly
        // what re-runs this effect.
        if (state.editSession == null && minTimeEditCellId == null && keyboardOwned &&
            !findFieldFocused
        ) {
            focusRequester.requestFocus()
        }
    }

    CompositionLocalProvider(LocalTreeKeyboardOwned provides keyboardOwned) {
    // The wheel scrolls the tree wherever the pointer is in its area, not only over a cell. The scrolling
    // column below is exactly as wide as its widest row (it must not stretch the rows), so the area beside
    // and below the cells is outside it; these two drive the SAME scroll states from the whole area. Over a
    // cell the column takes the wheel first (the innermost scrollable consumes it), so nothing scrolls twice.
    // The directions are the ones verticalScroll/horizontalScroll themselves pass to `scrollable`.
    val layoutDirection = LocalLayoutDirection.current
    // What the rows' intents go through: the tree's own and the pinned copy's alike.
    val rowIntent: (SchedulerIntent) -> Unit = { intent ->
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
            if (refocusWindow != null && currentState.focusedWindow != refocusWindow) {
                onIntent(SchedulerIntent.FocusWindow(refocusWindow))
            }
        }
        onIntent(intent)
    }
    val toggleMinTimeEdit: (CellId) -> Unit = { cellId ->
        if (minTimeEditCellId == cellId) {
            minTimeEditCellId = null
        } else {
            // Snapshot the value the field opens with so Escape can revert to it (PRD §10).
            minTimeEditOriginal =
                currentState.cells[cellId]?.taskId
                    ?.let { currentState.tasks[it]?.minimumMinutes } ?: 0
            minTimeEditCellId = cellId
        }
    }
    val onMoveDragStart: () -> Unit = { moveDragActive = true }
    val onMoveDropHover: (CellId, Boolean, CellId?) -> Unit = { target, insertBefore, via ->
        moveDropTarget = MoveDropTarget(target, insertBefore, via)
    }
    val onMoveDragEnd: () -> Unit = {
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
    }
    // The scrolled content's width, so the pinned copy is laid out exactly as wide as the rows it stands for.
    var treeContentWidthPx by remember { mutableStateOf(0) }

    // `docs/PLATFORMS.md`: **the selection's two handles** — a finger's Shift+click. A phone has no Ctrl and no Shift,
    // but a range can still be extended or shortened: the selection wears a dot on its top-left corner and one on its
    // bottom-right, and dragging one moves that end while the other stays put — through the very `DragSelectCells` a
    // mouse drag-select sends, so a range made by the dots is a range like any other. Shown while the last press in
    // the tree was a finger's (decided per event: a touchscreen laptop's mouse brings back the desktop look).
    var touchSelecting by remember { mutableStateOf(false) }
    var treeBoxWindowTop by remember { mutableStateOf(0f) }
    var treeBoxHeightPx by remember { mutableStateOf(0) }
    var treeBoxWidthPx by remember { mutableStateOf(0) }
    val handleRadiusPx = with(LocalDensity.current) { SELECTION_HANDLE_DIAMETER.toPx() / 2f }
    val handleReachPx = with(LocalDensity.current) { SELECTION_HANDLE_REACH.toPx() }
    val indentPx = with(LocalDensity.current) { INDENT_STEP_DP.dp.toPx() }
    // The rows drawn selected — the one highlight funnel the rows themselves use — first and last in visible order.
    val selectionEnds by remember {
        derivedStateOf {
            // Nothing to work out while a mouse drives the tree: the desktop never pays for the handles.
            if (!touchSelecting) return@derivedStateOf null
            val st = currentState
            if (st.selection.selected.isEmpty() && st.selection.main == null) return@derivedStateOf null
            val otherVias = SchedulerDomain.selectionHighlightVias(st)
            val drawn = currentVisibleRows.filter { row ->
                SchedulerDomain.isSelectableCell(st, row.occurrence.cellId) &&
                    SchedulerDomain.shouldShowSelectionHighlight(st.selection, row.occurrence.cellId, row.occurrence.renderVia, otherVias)
            }
            if (drawn.isEmpty()) null else drawn.first() to drawn.last()
        }
    }
    // Where the two dots stand, in the outer box's own coordinates (it never moves under a drag, so a finger is
    // tracked there and not on a dot that the re-laid rows carry along).
    // A corner the horizontal scroll has carried out of sight keeps its dot at the visible edge, whole: a dot half
    // outside the tree is half out of a finger's reach.
    fun inside(x: Float): Float = x.coerceIn(handleRadiusPx, maxOf(handleRadiusPx, treeBoxWidthPx - handleRadiusPx))
    val handleCenters: Pair<Offset, Offset>? =
        selectionEnds?.let { (first, last) ->
            val top = rowBounds[first.path] ?: return@let null
            val bottom = rowBounds[last.path] ?: return@let null
            Offset(inside(first.depth * indentPx - treeHorizontalScroll.value), top.start - treeBoxWindowTop) to
                Offset(inside(treeContentWidthPx.toFloat() - treeHorizontalScroll.value), bottom.endInclusive - treeBoxWindowTop)
        }
    val showHandles = touchSelecting && handleCenters != null && state.editSession == null && !moveDragActive
    val currentHandleCenters by rememberUpdatedState(if (showHandles) handleCenters else null)
    val currentSelectionEnds by rememberUpdatedState(selectionEnds)
    Box(
        modifier = modifier
            .onGloballyPositioned {
                treeBoxWindowTop = it.positionInWindow().y
                treeBoxHeightPx = it.size.height
                treeBoxWidthPx = it.size.width
            }
            // Initial pass, on the parent of everything the tree draws: it sees every press first. It notes which
            // kind of pointer pressed, and takes the gesture only when a finger lands on a handle — anything else
            // goes on to the rows and the scroll untouched.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touchSelecting = down.type == PointerType.Touch
                    if (down.type != PointerType.Touch) return@awaitEachGesture
                    val (topDot, bottomDot) = currentHandleCenters ?: return@awaitEachGesture
                    val (first, last) = currentSelectionEnds ?: return@awaitEachGesture
                    val toTop = (down.position - topDot).getDistance()
                    val toBottom = (down.position - bottomDot).getDistance()
                    if (minOf(toTop, toBottom) > handleReachPx) return@awaitEachGesture
                    // The OTHER end stays where it is.
                    val grabbedTop = toTop <= toBottom
                    val fixed = if (grabbedTop) last.occurrence else first.occurrence
                    // The DOT is what is dragged, not the finger: it sits on a row boundary, and the finger took it a
                    // little off its centre.
                    val grab = (if (grabbedTop) topDot.y else bottomDot.y) - down.position.y
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        val (row, upperHalf) = resolveRowAt(treeBoxWindowTop + change.position.y + grab) ?: continue
                        // Snapped to the nearest boundary: the top dot makes the row BELOW it the first, the bottom dot
                        // the row ABOVE it the last.
                        val rows = currentVisibleRows.asSequence()
                            .map { it.occurrence }
                            .filter { SchedulerDomain.isSelectableCell(currentState, it.cellId) }
                            .toList()
                        val at = rows.indexOf(row)
                        val hover =
                            when {
                                grabbedTop && !upperHalf -> rows.getOrNull(at + 1) ?: row
                                !grabbedTop && upperHalf -> rows.getOrNull(at - 1) ?: row
                                else -> row
                            }
                        rowIntent(
                            SchedulerIntent.DragSelectCells(
                                anchorCellId = fixed.cellId,
                                hoverCellId = hover.cellId,
                                visibleOrder = visibleOrder,
                                renderVia = fixed.renderVia,
                            ),
                        )
                    }
                }
            }
            .scrollable(
                treeScroll,
                Orientation.Vertical,
                reverseDirection = ScrollableDefaults.reverseDirection(layoutDirection, Orientation.Vertical, false),
            )
            .scrollable(
                treeHorizontalScroll,
                Orientation.Horizontal,
                reverseDirection = ScrollableDefaults.reverseDirection(layoutDirection, Orientation.Horizontal, false),
            ),
    ) {
    Column(
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { treeSelfFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = event.isCtrlPressed || event.isMetaPressed
                // PRD §5: the five history chords, read by the app's ONE interpreter of them.
                undoRedoIntentFor(event)?.let {
                    onIntent(it)
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
                // Anything sitting above the tree that can hold the keyboard — the task-tree selector's
                // name field — gets first refusal, so the tree never turns a letter typed there into a cell
                // Edit Mode nor Ctrl+A into "select every cell". The history chords above still
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
                    // A session is open but the CARET may not be in it yet: the field is composed and
                    // focused by the effects that run after the state changed, and Compose delivers key
                    // events in between. Falling through there hands the letter to whatever is focused —
                    // which is still this Column, and it does not write text — so it was simply dropped.
                    // `treeSelfFocused` is the whole of that window (any child holding the focus, the edit
                    // field included, makes it false), so a printable key typed inside it is taken into the
                    // live session instead: one more keystroke of the same funnel (see
                    // [SchedulerReducer]'s reduceBeginEdit), never a second edit path.
                    if (treeSelfFocused && keyboardOwned) {
                        val late = event.printableChar()
                        if (late != null) {
                            onIntent(SchedulerIntent.BeginEdit(state.editSession.cellId, late))
                            return@onPreviewKeyEvent true
                        }
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
                // PRD §4/§13: Ctrl+C copies the selected cells' **task ids**, in the bare reference shape
                // — the very text the cell menu's "copy task id (ctrl c)" writes, the two being one gesture.
                // Pasted onto a cell it points that cell at the task, and it is deliberately unmistakable
                // for text copied from anywhere else.
                if (mod && event.key == Key.C) {
                    val text =
                        SchedulerDomain.taskIdReferenceText(
                            state,
                            SchedulerDomain.copyTreeTargets(state, state.selection),
                        )
                    if (text.isNotEmpty()) {
                        onIntent(SchedulerIntent.CopySelection)
                        writeSystemClipboardText(text)
                    }
                    return@onPreviewKeyEvent true
                }
                // PRD §4: Ctrl+X is unchanged — the ENTIRE sub-tree under the selection (the account's
                // deep-copy depth belongs to the window, not to the chord; what each task carries is still
                // the window's three switches), and then those very cells emptied. A cut has to carry back
                // everything it deleted, which an id alone cannot.
                if (mod && event.key == Key.X) {
                    val text = SchedulerDomain.copyTreeText(state, state.selection)
                    if (text.isNotEmpty()) {
                        onIntent(SchedulerIntent.CutSelection)
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
                // typing into Edit Mode — whatever holds focus owns the keyboard then. A window
                // counts as something else, which is what `keyboardOwned` adds.
                if (!keyboardOwned) return@onPreviewKeyEvent false
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
                .horizontalScroll(treeHorizontalScroll)
                // PRD §3: deliberately NO tap handler on the tree's empty space. The selection and Edit
                // Mode are moved by a press on another task CELL and by nothing else — a press beside the
                // cells (or in another window) leaves both untouched, so a rename survives reaching for
                // the calendar and coming back.
                .width(IntrinsicSize.Max)
                .onSizeChanged { treeContentWidthPx = it.width },
        ) {
            CellListSection(
                state = state,
                // PRD §2: the drawing starts one level ABOVE the tree's top-level list, at the list holding
                // the single inert root row — so the root is a row like any other, with the tree indented
                // under it and its arrow collapsing the whole thing. The projections that have no root row
                // (the §4 template, §7's Search sub-trees) fall back to their own root list here.
                listId = SchedulerDomain.displayRootListId(state),
                renderVia = null,
                depth = 0,
                visibleOrder = visibleOrder,
                priorities = priorities,
                taskColors = taskColors,
                searchHighlight = searchHighlight,
                // PRD §5: clicking a percentage opens that sub-list's window; its right-click menu opens the
                // cell's relative-priority window instead. Neither closes the other (`popups.md`).
                onTogglePriorityWeights = { listId -> onSetWeightWindow(listId) },
                onOpenRelativePriority = { clickedCellId -> onSetRelativeWindow(clickedCellId) },
                minTimeEditCellId = minTimeEditCellId,
                onToggleMinTimeEdit = toggleMinTimeEdit,
                onOpenTaskEdit = { taskId -> onSetEditTask(taskId) },
                onOpenCategoryEdit = { categoryId -> onSetEditCategory(categoryId) },
                // PRD §13 "copy task id (ctrl c)": the cell's task id alone, in the shape a Ctrl+V turns
                // back into "point that cell at this task". Right-clicking inside a multi-selection takes
                // the whole block, so the menu and Ctrl+C never disagree about what "the cell" means — and
                // they write the same text, because they are the same gesture.
                onCopyTaskIdCell = { cellId ->
                    val targets =
                        SchedulerDomain.contextMenuCopyTargets(currentState, currentState.selection, cellId)
                    val text = SchedulerDomain.taskIdReferenceText(currentState, targets)
                    if (text.isNotEmpty()) writeSystemClipboardText(text)
                },
                // PRD §13 "deep copy": asks for the maximum depth first — the copy happens from its window.
                onDeepCopyCell = { cellId -> onSetDeepCopyCell(cellId) },
                moveDragActive = moveDragActive,
                moveDropTarget = moveDropTarget,
                resolveRowAt = resolveRowAt,
                onRowBounds = { path, top, bottom -> rowBounds[path] = top..bottom },
                onMoveDragStart = onMoveDragStart,
                onMoveDropHover = onMoveDropHover,
                onMoveDragEnd = onMoveDragEnd,
                rowTrailing = rowTrailing,
                rowLeading = rowLeading,
                onGoToTaskTree = onGoToTaskTree,
                namingSource = namingSource,
                onIntent = rowIntent,
            )
        }
    }

        // The pinned parent row (see `pinnedRow`), over the top of the viewport. Drawn after the tree so it
        // covers the rows scrolled under it, and before the find bar so the bar stays on top. It is the tree's
        // own row drawing ([CellListSection] with `pinnedCellId`), laid out at the content's width and shifted
        // by the horizontal scroll so it lines up with the rows below; its band is opaque so nothing shows
        // through beside a short row, and a taller row is cut to its bottom [TASK_ROW_MIN_HEIGHT].
        val pinned = pinnedRow
        val pinnedListId = pinned?.let { state.cells[it.occurrence.cellId]?.parentListId }
        if (pinned != null && pinnedListId != null && treeContentWidthPx > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(TASK_ROW_MIN_HEIGHT)
                    .clipToBounds()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier
                        .wrapContentSize(Alignment.BottomStart, unbounded = true)
                        .offset { IntOffset(-treeHorizontalScroll.value, 0) }
                        .width(with(LocalDensity.current) { treeContentWidthPx.toDp() }),
                ) {
                    CellListSection(
                        state = state,
                        listId = pinnedListId,
                        renderVia = pinned.occurrence.renderVia,
                        depth = pinned.depth,
                        visibleOrder = visibleOrder,
                        priorities = priorities,
                        taskColors = taskColors,
                        searchHighlight = searchHighlight,
                        onTogglePriorityWeights = { listId -> onSetWeightWindow(listId) },
                        onOpenRelativePriority = { clickedCellId -> onSetRelativeWindow(clickedCellId) },
                        minTimeEditCellId = minTimeEditCellId,
                        onToggleMinTimeEdit = toggleMinTimeEdit,
                        onOpenTaskEdit = { taskId -> onSetEditTask(taskId) },
                        onOpenCategoryEdit = { categoryId -> onSetEditCategory(categoryId) },
                        // No contextual menu on the copy (see `pinnedCellId`), so these are never asked for.
                        onCopyTaskIdCell = {},
                        onDeepCopyCell = {},
                        moveDragActive = moveDragActive,
                        moveDropTarget = moveDropTarget,
                        resolveRowAt = resolveRowAt,
                        // The real row keeps its band: the copy must never report one over it.
                        onRowBounds = { _, _, _ -> },
                        onMoveDragStart = onMoveDragStart,
                        onMoveDropHover = onMoveDropHover,
                        onMoveDragEnd = onMoveDragEnd,
                        rowTrailing = rowTrailing,
                        rowLeading = rowLeading,
                        onGoToTaskTree = onGoToTaskTree,
                        namingSource = namingSource,
                        onIntent = { intent ->
                            if (intent is SchedulerIntent.ClickCell) {
                                pinnedRevealPath = pinned.path
                                pinnedRevealRequests++
                            }
                            rowIntent(intent)
                        },
                        pinnedCellId = pinned.occurrence.cellId,
                        rowPath = pinned.path.dropLast(1),
                    )
                }
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


        // The selection's two handles (see `touchSelecting`). Drawing only: the gesture is the outer box's.
        if (showHandles) {
            val (topDot, bottomDot) = handleCenters!!
            // A dot whose row is scrolled out of the tree is not drawn over whatever lies beyond it.
            for (center in listOf(topDot, bottomDot).filter { it.y in 0f..treeBoxHeightPx.toFloat() }) {
                SelectionHandleDot(
                    Modifier.offset { IntOffset((center.x - handleRadiusPx).roundToInt(), (center.y - handleRadiusPx).roundToInt()) },
                )
            }
        }

        // PRD §5: the priority-weight window is drawn by the app (App.kt) on the top floating-window
        // layer, above the calendar — not here — so it sits over every other window and dismisses on a
        // click anywhere else (which still does its normal job).
    }
    }
}

/** How big a selection handle is drawn. */
private val SELECTION_HANDLE_DIAMETER = 14.dp

/** How far from a handle's centre a finger still takes it: a finger is far less precise than the dot is wide. */
private val SELECTION_HANDLE_REACH = 24.dp

/** One selection handle: a filled dot ringed in the surface colour, so it reads over a selected and a plain cell alike. */
@Composable
private fun SelectionHandleDot(modifier: Modifier) {
    Box(
        modifier = modifier
            .size(SELECTION_HANDLE_DIAMETER)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(2.dp)
            .background(SheetColors.activeBorder, CircleShape),
    )
}

/**
 * Whether the tree drawn beneath this local currently **owns the keyboard** — `TaskTreeView`'s own
 * `keyboardOwned`, published so the cell that is in Edit Mode can read it.
 *
 * PRD §4: focusing another window no longer ends the edit session (the cell keeps its Edit-Mode outline and
 * its half-typed draft), so something else has to make sure the caret is not still sitting in a field the
 * user has walked away from: the session survives, the **focus** follows the keyboard. Read from a local
 * rather than threaded down as a parameter for the same reason `LocalTransientMenuHost` is — all three
 * drawings of the tree get it at once, and no surface can forget to pass it on.
 *
 * Defaults to `true` for a task cell drawn outside any tree (the relative-priority window's occurrence
 * chains), which never edits anything.
 */
internal val LocalTreeKeyboardOwned = compositionLocalOf { true }
