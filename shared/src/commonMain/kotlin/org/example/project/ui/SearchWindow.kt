package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.platform.writeSystemClipboardText
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.defaultSubtreeIsEmpty
import org.example.project.scheduler.ui.contextMenuModifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.state.projectSearchSubtree
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.ui.formatPriorityPercent
import org.example.project.scheduler.ui.TaskTreeView
import org.example.project.scheduler.ui.TaskRow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalDensity
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.windowSelectionKey
import org.example.project.scheduler.ui.TaskCellMenuItems
import org.example.project.scheduler.ui.TaskCellMenuActions

/** Every row of the result list has this one height, whatever it holds (PRD §7 *Search*). */
private val RESULT_ROW_HEIGHT: Dp = 34.dp

/** Every row's kind section is this one width, so the names line up whatever the kinds (fits "restrictive period"). */
private val KIND_SECTION_WIDTH: Dp = 104.dp

/** The narrowest the path box may be squeezed to by a long title — room for its arrow and a sliver of text. */
private val MIN_PATH_BOX_WIDTH: Dp = 34.dp

private const val NOT_IN_TREE_HINT: String =
    "Not in any task tree. The task is still referenced — by its past on the timeline, for example — so it " +
        "is kept, and its path is the last one it had in a task tree."

/**
 * PRD §7 **Search**: a floating window that finds a thing of the account by name. Two sections, top to
 * bottom — the **configuration** (a search bar, and a drop-down with a check box for each kind of thing to
 * look for — every checked kind is searched at once) and the **result list**.
 *
 * **Every row is the same height and the full width of the list** (an expanded task row's sub-tree hangs
 * under it), and **opens on a section naming its kind**. A **task row is the task tree's own cell**
 * ([SearchTaskRow] → [TaskRow]): its colour, its expand arrow, its title and Edit Mode, its percentage, minimum
 * time and categories, its outline, its menu — with the path box between the title and the percentage (the
 * shortest path; an arrow lists them all when there are several) and, for a task no task tree holds any more,
 * a **logo** saying so, last. A task no tree holds shows the path it had when it left
 * ([org.example.project.scheduler.model.Task.lastTreePath]). Every other kind is its name and one detail
 * ([SearchDomain.itemResults]).
 *
 * **While the user is in the search bar, no row is selected** — the task tree's rule for its selector's field:
 * ↓ or Enter there moves into the list, a click on a row does too, and ↑ on the first row goes back. **Typing on
 * a selected task row enters Edit Mode, stuck to Rename** (no mode selector): it commits
 * [SchedulerIntent.RenameTask], which a task no cell holds takes too.
 *
 * **A row answers the gestures a task cell does** — the fixed-height form of them, since a row here is not
 * a cell and cannot grow into an editor: a click selects it, `↑`/`↓` walk the selection, a double-click or
 * `Enter` opens it (a task's "edit task" window, a category's or a period kind's own window, the Alarms or the
 * Reminders window that owns an alarm, a timer or a reminder), `Ctrl + C` on a task copies its id, and a
 * right-click selects it and opens its contextual menu. A task's menu is the TREE CELL's own
 * ([TaskCellMenuItems]) — "go to task tree" included — on the row, on its path box and on each line of its list
 * of paths, each speaking for that path's occurrence ([SearchDomain.occurrenceAtPath]). The selection looks
 * as it does in the tree (the outline, [taskCellOutline]), and moving it scrolls the list only when it would
 * leave what is shown.
 *
 * The query and the checked kinds are **local-only view state** ([SearchDomain.Config]): `App` keeps them on this
 * device, so the window comes back with them after a close or a restart, and never syncs them — how the user is
 * looking for something is not a fact about the account. The selection is Compose-only. Nothing here writes the
 * state except through the gestures' own intents.
 */
@Composable
fun SearchWindow(
    /** The live state — the search is about the account's own things. */
    state: SchedulerState,
    onOpenTaskEdit: (TaskId) -> Unit,
    onStartTaskNow: (TaskId) -> Unit,
    /**
     * PRD §8 "go to task tree" — the app's one handler, shared with the calendar: the task's cell
     * at the given occurrence (a path the user right-clicked), else its first one.
     */
    onGoToTaskTree: (TaskId, SchedulerDomain.TaskOccurrence?) -> Unit,
    onDeepCopyCell: (CellId) -> Unit,
    /**
     * The tree's own intents — the cell menu's entries that act on a cell, a row's minimum time and categories,
     * [SchedulerIntent.RenameTask] from a row's Edit Mode, and an expanded row's sub-tree
     * ([SchedulerIntent.InSearchSubtree]).
     */
    onIntent: (SchedulerIntent) -> Unit,
    /** PRD §5: a row's percentage opens its sub-list's weight table, as a tree cell's does. */
    onSetWeightWindow: (CellListId?) -> Unit = {},
    /** PRD §5: the percentage's right-click opens the cell's relative-priority window. */
    onSetRelativeWindow: (CellId?) -> Unit = {},
    onOpenCategory: (CategoryId) -> Unit,
    onOpenPeriodKind: (String) -> Unit,
    /**
     * The per-object window of ONE alarm, timer or chrono, with every setting it has — what opening its row does
     * (a double-click, Enter or a right-click).
     */
    onEditAlarmOrTimer: (AlarmWindowSubject) -> Unit,
    /** The per-object window of ONE reminder (by id), with every setting it has — opened like an alarm's. */
    onEditReminder: (String) -> Unit,
    /** The lateral-menu windows that own a history unit, a task tree, a task relation and a keyboard shortcut. */
    onOpenHistory: () -> Unit = {},
    onOpenTaskTrees: () -> Unit = {},
    onOpenTaskRelations: () -> Unit = {},
    onOpenShortcuts: () -> Unit = {},
    /**
     * Every window of the app, open or not, one entry per instance ([SearchDomain.WindowEntry]) — the rows of the
     * "window" kind. `App` holds them, not the state.
     */
    windows: List<SearchDomain.WindowEntry> = emptyList(),
    /** Opens the window a window row names (by frame id), or brings it back — a minimized one included. */
    onOpenWindow: (String) -> Unit = {},
    /**
     * A "creation" row opened: make a new element of this kind and open its window — what that kind's own
     * "+ New …" does ([SearchDomain.Kind.Creation]).
     */
    onCreate: (SearchDomain.Kind) -> Unit = {},
    onDismiss: () -> Unit,
    /**
     * The configuration — query, checked kinds, filters. Held by `App`, not here: the Configuration Search
     * window edits the same one, and it outlives this window (local-only view state, kept across restarts).
     */
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    /** Opens the Configuration Search window, which lists every configuration of this window. */
    onOpenConfigurations: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
) {
    val frame = rememberWindowFrameState("Search", initialOffset, initialSize)
    val query = config.query
    val kinds = config.kinds
    val filters = config.filters
    val sorts = config.sorts
    // Which copy of the window this is: its selection is its own (PRD §5, `Alt+←` walks it back).
    val instance = LocalWindowInstance.current?.suffix ?: ""
    val listState = rememberLazyListState()
    val fieldFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    // The bar holds the focus from the moment the window opens: it is where the user types.
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    // The task tree's rule (its selector's name field above it): while the user is in the search BAR, no row is
    // selected. ↓ or Enter there moves into the list, a click on a row does too, and ↑ on the first row goes back.
    var fieldFocused by remember { mutableStateOf(true) }
    // A task row in its rename-only Edit Mode (the tree's own field, TaskRow's): the task, and what is typed.
    var editingTaskId by remember { mutableStateOf<TaskId?>(null) }
    var editDraft by remember { mutableStateOf("") }
    // The rows whose sub-tree is open, and the one whose sub-tree holds the keyboard. Compose-only: which rows
    // are open is a way of looking at the list, like the query itself.
    var expandedTasks by remember { mutableStateOf(emptySet<TaskId>()) }
    var subtreeFocusOwner by remember { mutableStateOf<TaskId?>(null) }
    // PRD §10: the row whose minimum time is open as an input — it closes when the selection moves, as in the tree.
    var minTimeEditTaskId by remember { mutableStateOf<TaskId?>(null) }

    // Every path of every task is a walk of every task tree, so it is held on the trees alone — a keystroke
    // in the search bar filters it, it does not redo it. Keyed on the tree fields rather than on the whole
    // state, which every engine tick replaces (records live on the tasks) — ADR 0009.
    val searchesTasks = SearchDomain.Kind.Task in kinds
    val allPaths =
        remember(searchesTasks, state.cells, state.lists, state.tasks, state.taskTrees, state.activeTaskTreeId) {
            if (searchesTasks) SearchDomain.allPathsInAnyTree(state) else emptyMap()
        }
    val results =
        remember(
            kinds, query, filters, sorts, allPaths, state.tasks, state.taskTrees, state.categories, state.periodKinds,
            state.panels, state.alarms, state.timers, state.chronos, state.chores, state.histories, state.taskRelations,
            state.shortcutBindings, state.activeTaskTreeId, state.cells, state.lists, windows,
        ) {
            SearchDomain.results(state, kinds, query, { allPaths }, filters, sorts, windows)
        }
    val count = results.size
    // PRD §5: the selected row lives in the state, by its result key, so `Alt+←` can put it back. A key no longer
    // among the results (the question changed, the thing was deleted) reads as the first row.
    val selectedKey = state.windowSelections[windowSelectionKey(HistoryWindow.Search, instance)]
    val selected = results.indexOfFirst { resultKey(it) == selectedKey }.coerceAtLeast(0)
    /** Select the row at [index]; [record] false is the window's own reset, not a position the user took. */
    fun select(index: Int, record: Boolean = true) {
        val key = results.getOrNull(index)?.let(::resultKey)
        if (key != selectedKey) onIntent(SchedulerIntent.SelectInWindow(HistoryWindow.Search, instance, key, record))
    }
    // A new question starts at its best answer.
    LaunchedEffect(kinds, query, filters, sorts) { select(0, record = false) }
    // A list read at its top stays at its top when rows arrive above it. The rows are keyed, and a keyed lazy
    // list anchors its scroll on the first VISIBLE row: a new history unit sorted first (a move of the focus,
    // newest on top) landed just above the view, so the list looked frozen while it was growing (anomaly,
    // 2026-09-25). Whether it was at the top is read before the new rows are laid out — here, in composition —
    // and only as a boolean, so scrolling does not recompose the window.
    val atTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    val stayAtTop = atTop
    LaunchedEffect(results.firstOrNull()?.let(::resultKey)) { if (stayAtTop) listState.scrollToItem(0) }
    LaunchedEffect(selected) { minTimeEditTaskId = null }
    // The task tree's rule: the list scrolls only when the selection would leave what is on screen — by just
    // enough to bring it to the nearer edge. Scrolling the selected row to the top on every move made the list
    // jump under a selection that was already in view.
    val rowHeightPx = with(LocalDensity.current) { RESULT_ROW_HEIGHT.toPx() }
    LaunchedEffect(selected, count) {
        if (count == 0) return@LaunchedEffect
        val index = selected.coerceIn(0, count - 1)
        val info = listState.layoutInfo
        val top = info.viewportStartOffset
        val bottom = info.viewportEndOffset
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        when {
            item == null ->
                if (index < (info.visibleItemsInfo.firstOrNull()?.index ?: 0)) {
                    listState.scrollToItem(index)
                } else {
                    // Below what is shown: land it on the bottom edge, as a step down would.
                    listState.scrollToItem(index)
                    listState.scrollBy(-((bottom - top) - rowHeightPx).coerceAtLeast(0f))
                }
            item.offset < top -> listState.animateScrollBy((item.offset - top).toFloat())
            item.offset + item.size > bottom -> listState.animateScrollBy((item.offset + item.size - bottom).toFloat())
        }
    }

    val currentState by rememberUpdatedState(state)
    /**
     * A task row's menu — the TREE CELL's own ([TaskCellMenuActions], drawn by [TaskCellMenuItems]), so the two
     * offer the same entries. [path] is the one the right-click landed on (the row's shown path, or a line of its
     * list of paths): "go to task tree" goes to THAT occurrence, and the entries that act on a cell (deep copy,
     * collapse, add the default sub-tree) act on it. Built on demand, so it never closes over a stale state.
     */
    fun taskActions(result: SearchDomain.TaskResult, path: List<String>?): TaskCellMenuActions {
        val state = currentState
        val taskId = result.taskId
        val live = state.tasks[taskId]
        val atPath = path?.let { SearchDomain.occurrenceAtPath(state, taskId, it) }
        val occurrence = atPath ?: SchedulerDomain.firstTaskOccurrence(state, taskId)
        val hasChildren = live?.childListId?.let { state.lists[it]?.cellIds?.isNotEmpty() } == true
        return TaskCellMenuActions(
            onStartNow =
                if (live != null && SchedulerDomain.isPlaceableTask(state, taskId)) {
                    { onStartTaskNow(taskId) }
                } else {
                    null
                },
            onEdit = if (live != null) ({ onOpenTaskEdit(taskId) }) else null,
            // Always offered, like the calendar panel's: the app's handler says so when no cell holds the task.
            onGoToTaskTree = { onGoToTaskTree(taskId, atPath) },
            onCopyTaskId = { writeSystemClipboardText(SchedulerDomain.TASK_ID_REFERENCE_PREFIX + taskId.value) },
            onDeepCopy = occurrence?.let { { onDeepCopyCell(it.cellId) } },
            onCollapseSubtrees =
                occurrence?.takeIf { hasChildren }?.let { { onIntent(SchedulerIntent.CollapseSubtrees(it.cellId)) } },
            onAddDefaultSubtree =
                occurrence?.takeIf { !state.defaultSubtreeIsEmpty }
                    ?.let { { onIntent(SchedulerIntent.AddDefaultSubtree(listOf(it.cellId))) } },
        )
    }
    fun openItem(item: SearchDomain.ItemResult) {
        when (item.kind) {
            SearchDomain.Kind.Task -> Unit
            SearchDomain.Kind.Category -> onOpenCategory(CategoryId(item.id))
            SearchDomain.Kind.RestrictivePeriod -> onOpenPeriodKind(item.id)
            SearchDomain.Kind.Alarm -> onEditAlarmOrTimer(AlarmWindowSubject(item.id, AlarmWindowSubject.Kind.Alarm))
            SearchDomain.Kind.Timer -> onEditAlarmOrTimer(AlarmWindowSubject(item.id, AlarmWindowSubject.Kind.Timer))
            SearchDomain.Kind.Chrono -> onEditAlarmOrTimer(AlarmWindowSubject(item.id, AlarmWindowSubject.Kind.Chrono))
            SearchDomain.Kind.Reminder -> onEditReminder(item.id)
            SearchDomain.Kind.HistoryUnit -> onOpenHistory()
            SearchDomain.Kind.TaskTree -> onOpenTaskTrees()
            SearchDomain.Kind.TaskRelation -> onOpenTaskRelations()
            SearchDomain.Kind.Shortcut -> onOpenShortcuts()
            SearchDomain.Kind.Window -> onOpenWindow(item.id)
            SearchDomain.Kind.Creation ->
                SearchDomain.Kind.entries.firstOrNull { it.name == item.id }?.let(onCreate)
        }
    }
    fun openSelected() {
        when (val result = results.getOrNull(selected)) {
            is SearchDomain.TaskResult -> taskActions(result, null).onEdit?.invoke()
            is SearchDomain.ItemResult -> openItem(result)
            null -> Unit
        }
    }
    /** PRD §4 Edit Mode, stuck to Rename: [initial] is the draft to start from (a typed letter, or the title). */
    fun beginEdit(result: SearchDomain.TaskResult, initial: String) {
        editingTaskId = result.taskId
        editDraft = initial
    }
    /**
     * Leaving Edit Mode: the rename is committed (a blank one is refused by the reducer) unless [cancel], and the
     * selection takes [step] — Enter's step down, Shift+Enter's up, as in the tree.
     */
    fun endEdit(result: SearchDomain.TaskResult, cancel: Boolean, step: Int = 0, refocusList: Boolean = true) {
        if (!cancel && editDraft != result.title) onIntent(SchedulerIntent.RenameTask(result.taskId, editDraft))
        editingTaskId = null
        if (count > 0) select((selected + step).coerceIn(0, count - 1))
        if (refocusList) runCatching { listFocus.requestFocus() }
    }
    /** The row being renamed, if any — what a press elsewhere commits first, as leaving a tree cell does. */
    fun commitOpenEdit(refocusList: Boolean) {
        val id = editingTaskId ?: return
        val row = results.firstOrNull { it is SearchDomain.TaskResult && it.taskId == id } as SearchDomain.TaskResult?
        if (row == null) editingTaskId = null else endEdit(row, cancel = false, refocusList = refocusList)
    }
    /** A press on a row: it becomes the selection, and the list — not the bar — holds the keyboard. */
    fun selectRow(index: Int) {
        if (editingTaskId != null && (results.getOrNull(index) as? SearchDomain.TaskResult)?.taskId != editingTaskId) {
            commitOpenEdit(refocusList = false)
        }
        select(index)
        subtreeFocusOwner = null
        runCatching { listFocus.requestFocus() }
    }
    // Back in the search bar: a rename left open is committed, as it is when a tree cell is left.
    LaunchedEffect(fieldFocused) { if (fieldFocused) commitOpenEdit(refocusList = false) }
    // The live tree's figures, read as the tree reads them: the absolute priorities and the task colours.
    val priorities = remember(state.cells, state.lists, state.tasks) { SchedulerDomain.absoluteTaskPriorities(state) }
    val taskColors = TaskPalette.sheetColors(rememberTaskHues(state))

    AppWindowFrame(
        title = "Search",
        state = frame,
        onClose = onDismiss,
        defaultWidth = 520.dp,
        defaultHeight = 560.dp,
        modifier = modifier,
        onRaise = onRaise,
        onGeometryChange = onGeometryChange,
        claimsKeyboard = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // --- The configuration ------------------------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { onConfigChange(config.copy(query = it)) },
                    singleLine = true,
                    label = { Text("Search") },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(fieldFocus)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        // ↓ or Enter leave the bar for the list, onto its first row; every other key types.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val intoList =
                                event.key == Key.DirectionDown || event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (!intoList || count == 0) return@onPreviewKeyEvent false
                            selectRow(0)
                            true
                        },
                )
                KindsDropDown(kinds = kinds, onKindsChange = { onConfigChange(config.copy(kinds = it)) })
                // Clears the bar and unticks every type. The filters are left alone: they have their own window,
                // and the button beside it says how many are on.
                ResetButton(enabled = query.isNotEmpty() || kinds.isNotEmpty()) {
                    onConfigChange(config.copy(query = "", kinds = emptySet()))
                }
            }
            // Every configuration of this window, in a window of its own — the filters per kind among them. The
            // count says how many filters are narrowing the list right now, which nothing else here shows.
            val active = filters.activeCount
            Text(
                text = "⚙ All configurations" + if (active > 0) "  ·  $active filter" + (if (active == 1) "" else "s") + " on" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenConfigurations)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )

            HorizontalDivider()

            // --- The result list --------------------------------------------------------------------
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (count == 0) {
                    Text(
                        text =
                            when {
                                kinds.isEmpty() -> "Tick a kind to look for."
                                filters.activeCount > 0 -> "Nothing passes the filters."
                                query.isBlank() -> "Nothing of these kinds yet."
                                else -> "Nothing matches."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // The list holds the keyboard once the user leaves the bar: the tree's keys, for its rows.
                    val rowSelected = { index: Int -> index == selected && !fieldFocused && subtreeFocusOwner == null }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 12.dp)
                            .focusRequester(listFocus)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                // A row's sub-tree reads its own keys (it is the tree's own view).
                                if (subtreeFocusOwner != null) return@onPreviewKeyEvent false
                                val current = results.getOrNull(selected)
                                // In Edit Mode the row's field owns the keys; Escape abandons the rename.
                                if (editingTaskId != null) {
                                    if (event.key == Key.Escape && current is SearchDomain.TaskResult) {
                                        endEdit(current, cancel = true)
                                        return@onPreviewKeyEvent true
                                    }
                                    return@onPreviewKeyEvent false
                                }
                                val ctrl = event.isCtrlPressed || event.isMetaPressed
                                when {
                                    event.key == Key.DirectionDown -> {
                                        if (count > 0) select((selected + 1).coerceAtMost(count - 1))
                                        true
                                    }
                                    event.key == Key.DirectionUp -> {
                                        if (selected == 0) runCatching { fieldFocus.requestFocus() }
                                        else select(selected - 1)
                                        true
                                    }
                                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                        openSelected()
                                        true
                                    }
                                    ctrl && event.key == Key.C && current is SearchDomain.TaskResult -> {
                                        taskActions(current, null).onCopyTaskId()
                                        true
                                    }
                                    // PRD §4: typing on a selected task row enters Edit Mode — renaming, always.
                                    current is SearchDomain.TaskResult && !ctrl && !event.isAltPressed -> {
                                        val code = event.utf16CodePoint
                                        if (code < 32 || code == 127) return@onPreviewKeyEvent false
                                        beginEdit(current, code.toChar().toString())
                                        true
                                    }
                                    else -> false
                                }
                            },
                    ) {
                        itemsIndexed(results, key = { _, r -> resultKey(r) }) { index, result ->
                            when (result) {
                                is SearchDomain.TaskResult ->
                                    SearchTaskRow(
                                        state = state,
                                        result = result,
                                        query = query,
                                        selected = rowSelected(index),
                                        editing = editingTaskId == result.taskId,
                                        editDraft = editDraft,
                                        expanded = result.taskId in expandedTasks,
                                        priorities = priorities,
                                        taskColor = taskColors[result.taskId],
                                        minTimeEditing = minTimeEditTaskId == result.taskId,
                                        actions = { path -> taskActions(result, path) },
                                        onSelect = { selectRow(index) },
                                        onBeginEdit = { beginEdit(result, result.title) },
                                        onDraftChange = { editDraft = it },
                                        onEndEdit = { step -> endEdit(result, cancel = false, step = step) },
                                        onToggleExpand = {
                                            expandedTasks =
                                                if (result.taskId in expandedTasks) expandedTasks - result.taskId
                                                else expandedTasks + result.taskId
                                        },
                                        onActivateMinTime = {
                                            selectRow(index)
                                            minTimeEditTaskId = result.taskId
                                        },
                                        onIntent = onIntent,
                                        onSetWeightWindow = onSetWeightWindow,
                                        onSetRelativeWindow = onSetRelativeWindow,
                                        onOpenTaskEdit = onOpenTaskEdit,
                                        onOpenCategory = onOpenCategory,
                                        onDeepCopyCell = onDeepCopyCell,
                                        onGoToTaskTree = { taskId -> onGoToTaskTree(taskId, null) },
                                        subtreeFocused = subtreeFocusOwner == result.taskId,
                                        onSubtreeFocus = { focused ->
                                            if (focused) subtreeFocusOwner = result.taskId
                                            else if (subtreeFocusOwner == result.taskId) subtreeFocusOwner = null
                                        },
                                    )
                                is SearchDomain.ItemResult ->
                                    ItemResultRow(
                                        item = result,
                                        selected = rowSelected(index),
                                        onSelect = { selectRow(index) },
                                        onOpen = { openItem(result) },
                                        // An alarm, a timer or a reminder has its own window, and the
                                        // right-click opens it straight away, as opening the row does: its
                                        // settings are what the user is asking about.
                                        opensOnRightClick =
                                            result.kind == SearchDomain.Kind.Alarm ||
                                                result.kind == SearchDomain.Kind.Timer ||
                                                result.kind == SearchDomain.Kind.Chrono ||
                                                result.kind == SearchDomain.Kind.Creation ||
                                                result.kind == SearchDomain.Kind.Reminder,
                                    )
                            }
                        }
                    }
                }
                if (count > 0) ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/** A row's leftmost section: which kind of thing it is. One fixed width, so every row's name starts alike. */
@Composable
private fun KindSection(kind: SearchDomain.Kind) {
    Text(
        text = kind.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(KIND_SECTION_WIDTH),
    )
}

/**
 * The configuration section's **Reset**: clears the search field and unticks every type. One button for the
 * Search window and the Configuration Search window; greyed while there is nothing to clear.
 */
@Composable
internal fun ResetButton(enabled: Boolean, onReset: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = "Reset",
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onReset) else Modifier)
            .padding(horizontal = 10.dp, vertical = 14.dp),
    )
}

/**
 * The kind drop-down of a search's configuration — a check box per kind; ticking one leaves the menu open, so
 * several can be ticked in turn. One drop-down for the Search window and the Configuration Search window.
 */
@Composable
internal fun KindsDropDown(
    kinds: Set<SearchDomain.Kind>,
    onKindsChange: (Set<SearchDomain.Kind>) -> Unit,
    modifier: Modifier = Modifier.width(170.dp),
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            text = kindsLabel(kinds) + "  ▾",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .menuToggleClickable(open) { open = it }
                .padding(horizontal = 10.dp, vertical = 14.dp),
        )
        transientMenuDismissal(open) { open = false }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = false),
        ) {
            SelectAllMenuItem(
                allChecked = kinds.containsAll(SearchDomain.Kind.entries),
                onSelectAll = { onKindsChange(SearchDomain.Kind.entries.toSet()) },
                onDeselectAll = { onKindsChange(emptySet()) },
            )
            SearchDomain.Kind.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { Checkbox(checked = option in kinds, onCheckedChange = null) },
                    onClick = { onKindsChange(if (option in kinds) kinds - option else kinds + option) },
                )
            }
        }
    }
}

/**
 * The first entry of every drop-down whose entries carry a check box: no box of its own, it reads **Select all**
 * until every box is checked and checks them all, then **Deselect all**, which unchecks them all. Driven by the
 * boxes rather than by its own last press, so it says the right thing whatever was ticked by hand in between.
 * The menu stays open, as it does for a box.
 */
@Composable
internal fun SelectAllMenuItem(allChecked: Boolean, onSelectAll: () -> Unit, onDeselectAll: () -> Unit) {
    DropdownMenuItem(
        text = { Text(if (allChecked) "Deselect all" else "Select all") },
        onClick = { if (allChecked) onDeselectAll() else onSelectAll() },
    )
}

/** The drop-down's face:the checked kinds by name, or "every kind" / "no kind". */
private fun kindsLabel(kinds: Set<SearchDomain.Kind>): String =
    when (kinds.size) {
        0 -> "no kind"
        SearchDomain.Kind.entries.size -> "every kind"
        else -> SearchDomain.Kind.entries.filter { it in kinds }.joinToString(", ") { it.label }
    }

private fun resultKey(result: SearchDomain.Result): String =
    when (result) {
        is SearchDomain.TaskResult -> SearchDomain.Kind.Task.name + "/" + result.taskId.value
        is SearchDomain.ItemResult -> result.kind.name + "/" + result.id
    }

/** The press gestures every result row shares: press selects, double-click opens, right-click menus. */
private fun Modifier.resultRowGestures(
    key: Any,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onOpenMenu: () -> Unit,
): Modifier =
    this
        .pointerInput(key) {
            // `onPress` rather than `onTap`: a tap waits out the double-click window before it fires, and a
            // row that selects a third of a second after the click reads as a row that missed it.
            detectTapGestures(onPress = { onSelect() }, onDoubleTap = { onOpen() })
        }
        // Innermost, so it sees the press first and consumes a right-click before the tap detector does.
        .then(contextMenuModifier(enabled = true, key = key, onSelect = onSelect, onOpen = onOpenMenu))

@Composable
private fun resultRowModifier(selected: Boolean): Modifier =
    Modifier
        .fillMaxWidth()
        .height(RESULT_ROW_HEIGHT)
        // The TASK TREE's selection, not a look of its own: the cell's background, and the selection said by
        // the OUTLINE alone — the main selection's thick active border ([taskCellOutline], the tree's one rule).
        .background(SheetColors.cellBackground)
        .border(
            taskCellOutline(isEditing = false, isMainSelection = selected, isInSelectionRange = false).borderWidth,
            taskCellOutline(isEditing = false, isMainSelection = selected, isInSelectionRange = false).borderColor,
        )
        .padding(horizontal = 6.dp)

/**
 * A task row: the TASK TREE's own cell ([TaskRow]) — its task colour, its expand arrow, its title and Edit
 * Mode, its percentage, minimum time and categories, its selection outline, its right-click menu — with the
 * Search window's sections set into it: the kind before the arrow, the path box between the title and the
 * percentage, the "in no task tree" logo last. The differences from a tree cell are the window's:
 *  - **Edit Mode is stuck to Rename** — no mode selector — and commits [SchedulerIntent.RenameTask]: the row IS
 *    the task, it may have no cell at all, and renaming never touches its sub-tree;
 *  - **nothing is dragged**, and the multi-selection is the list's single row;
 *  - **expanding** shows the task's sub-tree under the row as the tree's own cells ([SearchSubtree]).
 */
@Composable
private fun SearchTaskRow(
    state: SchedulerState,
    result: SearchDomain.TaskResult,
    query: String,
    selected: Boolean,
    editing: Boolean,
    editDraft: String,
    expanded: Boolean,
    priorities: Map<TaskId, Double>,
    taskColor: Color?,
    minTimeEditing: Boolean,
    /** The cell menu for the path the right-click landed on — built on demand, never over a stale state. */
    actions: (path: List<String>?) -> TaskCellMenuActions,
    onSelect: () -> Unit,
    onBeginEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    /** Leaves Edit Mode, committing; the argument is the step the selection takes (Enter: +1, Shift+Enter: -1). */
    onEndEdit: (step: Int) -> Unit,
    onToggleExpand: () -> Unit,
    onActivateMinTime: () -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
    onSetWeightWindow: (CellListId?) -> Unit,
    onSetRelativeWindow: (CellId?) -> Unit,
    onOpenTaskEdit: (TaskId) -> Unit,
    onOpenCategory: (CategoryId) -> Unit,
    onDeepCopyCell: (CellId) -> Unit,
    onGoToTaskTree: (TaskId) -> Unit,
    subtreeFocused: Boolean,
    onSubtreeFocus: (Boolean) -> Unit,
) {
    val taskId = result.taskId
    val live = state.tasks[taskId]
    // Where the row's figures and gestures reach the tree: its first cell, when the live tree has one. Walked
    // when the tree changes, never per keystroke or per tick.
    val occurrence =
        remember(taskId, state.cells, state.lists, state.tasks) { SchedulerDomain.firstTaskOccurrence(state, taskId) }
    // A task cut from the tree and kept by the timeline: its sub-tree can be looked through, not modified.
    val readOnlySubtree = occurrence == null
    val childListId = live?.childListId
    val hasChildren = childListId?.let { state.lists[it]?.cellIds?.isNotEmpty() } == true
    val menu = remember(taskId, result.shownPath, state.cells, state.lists, state.tasks) { actions(result.shownPath.takeIf { it.isNotEmpty() }) }
    // The title column is this row's own text, clamped like the tree's (a row has no sub-list to line up with).
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val shownTitle = if (editing) editDraft else result.title
    val titlePx = if (shownTitle.isEmpty()) 0 else textMeasurer.measure(shownTitle, bodyStyle).size.width
    val columnWidth = with(density) { titlePx.toDp() }.coerceIn(PRIORITY_COLUMN_MIN, PRIORITY_COLUMN_MAX)
    val columnPx = with(density) { columnWidth.toPx() }
    // The query's hits in the title, drawn the way the tree's Ctrl+F draws its own.
    val hits =
        remember(result.title, query) {
            val q = query.trim()
            if (q.isEmpty()) emptyList()
            else Regex(Regex.escape(q), RegexOption.IGNORE_CASE).findAll(result.title).map { it.range }.toList()
        }

    Column(Modifier.fillMaxWidth()) {
        // The cell IS the row: it takes the list's row height itself (no slot around it, whose bare band
        // above and below showed as a white gap between two task rows).
        TaskRow(
            minHeight = RESULT_ROW_HEIGHT,
            depth = 0,
            // A task no live cell holds is still a row: its id stands in for the cell the tree would key on.
            cellId = occurrence?.cellId ?: CellId("search-row/" + taskId.value),
            renderVia = null,
            displayTitle = shownTitle,
            isMainSelection = selected,
            isInSelectionRange = false,
            selectable = true,
            isEditing = editing,
            hasChildren = hasChildren,
            expanded = expanded,
            moveDropBefore = false,
            moveDropAfter = false,
            canMoveFromCell = false,
            isBeingMoved = false,
            priorityLabel = formatPriorityPercent(priorities[taskId] ?: 0.0),
            priorityColumnWidth = columnWidth,
            taskColor = taskColor,
            searchRanges = if (editing) emptyList() else hits,
            currentSearchRange = null,
            textOverflow = titlePx > columnPx,
            minMinutes = live?.minimumMinutes ?: 0,
            minTimeEditing = minTimeEditing && live != null,
            cellMenu = menu,
            onTogglePriorityWeights = {
                occurrence?.let { occ -> state.cells[occ.cellId]?.parentListId?.let(onSetWeightWindow) }
            },
            onOpenRelativePriority = { occurrence?.let { onSetRelativeWindow(it.cellId) } },
            onSetMinTime = { minutes -> onIntent(SchedulerIntent.SetTaskMinimumTime(taskId, minutes)) },
            onActivateMinTime = { if (live != null) onActivateMinTime() },
            onClick = { _, _, _, _ -> onSelect() },
            onDragSelect = { _, _ -> },
            moveDragActive = false,
            resolveRowAt = { null },
            onRowBounds = { _, _, _ -> },
            onMoveDragStart = {},
            onMoveDropHover = { _, _, _ -> },
            onMoveDragEnd = {},
            // PRD §4: a double-click on the title opens Edit Mode — renaming, always.
            onDoubleClick = {
                onSelect()
                onBeginEdit()
            },
            onTextChange = onDraftChange,
            onExitEdit = { nav ->
                onEndEdit(
                    when (nav) {
                        EditExitNavigation.Down -> 1
                        EditExitNavigation.Up -> -1
                        else -> 0
                    },
                )
            },
            onToggleExpand = { if (hasChildren) onToggleExpand() },
            // Stuck to Rename: no mode selector, no id menu.
            editMenus = null,
            categoryCell = live?.let { { TaskCategoryCell(state, taskId, onIntent, onOpenCategory) } },
            rowLeading = { KindSection(result.kind) },
            // The title prevails; the path box is squeezed to a thin box behind a long one, never dropped.
            afterTitle = {
                Box(Modifier.fillMaxWidth().height(24.dp).padding(start = 8.dp)) {
                    TaskPathBox(result, onSelect = onSelect, actions = actions)
                }
            },
            afterTitleMinWidth = MIN_PATH_BOX_WIDTH + 8.dp,
            rowTrailing = { if (!result.inTaskTree) Box(Modifier.padding(start = 6.dp)) { NotInTreeLogo() } },
        )
        if (expanded && hasChildren) {
            SearchSubtree(
                state = state,
                listId = childListId,
                readOnly = readOnlySubtree,
                priorities = priorities,
                focused = subtreeFocused,
                onFocus = onSubtreeFocus,
                onIntent = onIntent,
                onSetWeightWindow = onSetWeightWindow,
                onSetRelativeWindow = onSetRelativeWindow,
                onOpenTaskEdit = onOpenTaskEdit,
                onOpenCategory = onOpenCategory,
                onDeepCopyCell = onDeepCopyCell,
                onGoToTaskTree = onGoToTaskTree,
            )
        }
    }
}

/**
 * An expanded task row's sub-tree: the TASK TREE's own view ([TaskTreeView]) over the live tree re-rooted at
 * the task's sub-list, with the Search window's own expansion, selection and edit session
 * ([projectSearchSubtree]) — so its cells are real cells, edited and selected as in the tree. Bounded in height:
 * it scrolls inside the list rather than stretching it. [readOnly] for a task cut from the tree: looked
 * through, never modified (the reducer refuses it — [SchedulerIntent.InSearchSubtree]).
 */
@Composable
private fun SearchSubtree(
    state: SchedulerState,
    listId: CellListId,
    readOnly: Boolean,
    priorities: Map<TaskId, Double>,
    focused: Boolean,
    onFocus: (Boolean) -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
    onSetWeightWindow: (CellListId?) -> Unit,
    onSetRelativeWindow: (CellId?) -> Unit,
    onOpenTaskEdit: (TaskId) -> Unit,
    onOpenCategory: (CategoryId) -> Unit,
    onDeepCopyCell: (CellId) -> Unit,
    onGoToTaskTree: (TaskId) -> Unit,
) {
    val projected =
        remember(state.cells, state.lists, state.tasks, state.searchExpanded, state.searchSelection, state.searchEditSession, listId) {
            state.projectSearchSubtree(listId)
        }
    TaskTreeView(
        state = projected,
        priorities = priorities,
        onIntent = { intent ->
            when (intent) {
                // App-wide: the history stacks and the window focus are no tree's.
                is SchedulerIntent.Undo, is SchedulerIntent.Redo,
                is SchedulerIntent.UndoSelection, is SchedulerIntent.RedoSelection,
                is SchedulerIntent.UndoPosition, is SchedulerIntent.RedoPosition,
                is SchedulerIntent.FocusWindow,
                -> onIntent(intent)
                else -> onIntent(SchedulerIntent.InSearchSubtree(intent, listId, readOnly))
            }
        },
        keyboardActive = focused,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = SUBTREE_MAX_HEIGHT)
            .padding(start = 24.dp)
            .onFocusChanged { onFocus(it.hasFocus) },
        onSetWeightWindow = onSetWeightWindow,
        onSetRelativeWindow = onSetRelativeWindow,
        onSetEditTask = { it?.let(onOpenTaskEdit) },
        onSetEditCategory = { it?.let(onOpenCategory) },
        onSetDeepCopyCell = { it?.let(onDeepCopyCell) },
        onGoToTaskTree = onGoToTaskTree,
        refocusWindow = null,
        // A task is the same colour here, in the tree and on the calendar, and a Change Task row is named from
        // the tree — both read off the LIVE state, not off the sub-tree this row re-roots it at.
        colorSource = state,
        namingSource = state,
    )
}

@Composable
private fun MenuEntry(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = onClick)
}

/** The most an expanded row's sub-tree takes of the list before it scrolls inside itself. */
private val SUBTREE_MAX_HEIGHT: Dp = 320.dp

/**
 * The path, in its rectangle — with the arrow that lists every path when the task has several. A right-click on
 * the box, or on one line of the list, opens the cell menu for THAT path ([actions]): its own gesture and its
 * own menu, so "go to task tree" there goes to that path's cell.
 */
@Composable
private fun TaskPathBox(
    result: SearchDomain.TaskResult,
    onSelect: () -> Unit,
    actions: (path: List<String>?) -> TaskCellMenuActions,
) {
    var listOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<TaskCellMenuActions?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val onOpenMenu: (List<String>?) -> Unit = { path ->
        menu = actions(path)
        menuOpen = true
    }
    menu?.let { shown ->
        transientMenuDismissal(menuOpen) { menuOpen = false }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, properties = PopupProperties(focusable = false)) {
            TaskCellMenuItems(shown) { menuOpen = false }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .then(
                contextMenuModifier(enabled = true, key = result.taskId to "path", onSelect = onSelect) {
                    onOpenMenu(result.shownPath.takeIf { it.isNotEmpty() })
                },
            )
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (result.shownPath.isEmpty()) "no path" else SearchDomain.pathLabel(result.shownPath),
            style = MaterialTheme.typography.labelMedium,
            fontStyle = if (result.shownPath.isEmpty()) FontStyle.Italic else FontStyle.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (result.hasSeveralPaths) {
            Box {
                Text(
                    text = if (listOpen) "▴" else "▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxHeight()
                        .menuToggleClickable(listOpen) { listOpen = it }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                transientMenuDismissal(listOpen) { listOpen = false }
                DropdownMenu(
                    expanded = listOpen,
                    onDismissRequest = { listOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            text = "${result.paths.size} paths",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (path in result.paths) {
                            Text(
                                text = SearchDomain.pathLabel(path),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    // Opening the row's menu closes this list (one menu at a time), and the
                                    // menu then speaks for this line's occurrence.
                                    .then(
                                        contextMenuModifier(enabled = true, key = path, onSelect = onSelect) {
                                            onOpenMenu(path)
                                        },
                                    )
                                    .padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        } else {
            Box(Modifier.width(6.dp))
        }
    }
}

/** The "in no task tree" logo: a crossed circle, which explains itself on hover. */
@Composable
private fun NotInTreeLogo() {
    InfoHint(text = NOT_IN_TREE_HINT) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⊘",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ItemResultRow(
    item: SearchDomain.ItemResult,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    /** The right-click opens the row ([onOpen]) instead of the contextual menu: an alarm, a timer, a reminder. */
    opensOnRightClick: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = resultRowModifier(selected)
            .resultRowGestures(
                key = item.kind.name + "/" + item.id,
                onSelect = onSelect,
                onOpen = onOpen,
                onOpenMenu = { if (opensOnRightClick) onOpen() else menuOpen = true },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KindSection(item.kind)
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = item.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        transientMenuDismissal(menuOpen) { menuOpen = false }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            properties = PopupProperties(focusable = false),
        ) {
            MenuEntry(
                when (item.kind) {
                    SearchDomain.Kind.HistoryUnit -> "open in History"
                    SearchDomain.Kind.TaskTree -> "open in All task trees"
                    SearchDomain.Kind.TaskRelation -> "open in Task relations"
                    SearchDomain.Kind.Shortcut -> "open in Keyboard shortcuts"
                    SearchDomain.Kind.Window -> "show window"
                    else -> "edit " + item.kind.label
                },
            ) {
                menuOpen = false
                onOpen()
            }
        }
    }
}
