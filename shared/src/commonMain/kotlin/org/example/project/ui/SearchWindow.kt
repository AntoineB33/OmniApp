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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalDensity
import org.example.project.scheduler.state.SchedulerIntent
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
 * **Every row is the same height and the full width of the list**, whatever it holds, and **opens on a
 * section naming its kind** (task, restrictive period, …) — one fixed width, so the names line up across
 * kinds ([KindSection]). After it, a task row is three sections, left to right: its **title**, its **path** in a rectangle, and — for a task no task tree holds
 * any more — a **logo** saying so, which explains itself on hover. The path is the shortest one the task
 * has; a task with several carries an arrow at the right of its path box that lists them all. The title and
 * the path share the width, **the title first**: it takes what it needs and the path box the rest, down to a
 * thin box when the title is long — but neither is ever dropped ([TaskResultRowLayout]). A task no tree
 * holds shows the path it had when it left ([org.example.project.scheduler.model.Task.lastTreePath]). Every
 * other kind is its name and one detail ([SearchDomain.itemResults]).
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
     * PRD §8 "go to task tree" — the app's one handler, shared with the calendar and "All tasks": the task's cell
     * at the given occurrence (a path the user right-clicked), else its first one.
     */
    onGoToTaskTree: (TaskId, SchedulerDomain.TaskOccurrence?) -> Unit,
    onDeepCopyCell: (CellId) -> Unit,
    /** The tree's own intents, for the cell menu's entries that act on a cell (collapse, add default sub-tree). */
    onIntent: (SchedulerIntent) -> Unit,
    onOpenCategory: (CategoryId) -> Unit,
    onOpenPeriodKind: (String) -> Unit,
    /** Alarms AND timers live in the one Alarms window (PRD §18). */
    onOpenAlarms: () -> Unit,
    /** The per-object window of ONE alarm or timer, with every setting it has — a right-click on its row. */
    onEditAlarmOrTimer: (AlarmWindowSubject) -> Unit,
    /** The per-object window of ONE reminder (by id), with every setting it has — a right-click on its row. */
    onEditReminder: (String) -> Unit,
    onOpenReminders: () -> Unit,
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
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val fieldFocus = remember { FocusRequester() }
    // The bar holds the focus from the moment the window opens: it is where the user types, and it is what
    // puts the list's arrow keys (read in the preview pass below) on the path a keystroke takes.
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

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
            kinds, query, filters, allPaths, state.tasks, state.taskTrees, state.categories, state.periodKinds,
            state.panels, state.alarms, state.timers, state.chores,
        ) {
            SearchDomain.results(state, kinds, query, { allPaths }, filters)
        }
    val count = results.size
    // A new question starts at its best answer.
    LaunchedEffect(kinds, query, filters) { selected = 0 }
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
            SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer -> onOpenAlarms()
            SearchDomain.Kind.Reminder -> onOpenReminders()
        }
    }
    fun openSelected() {
        when (val result = results.getOrNull(selected)) {
            is SearchDomain.TaskResult -> taskActions(result, null).onEdit?.invoke()
            is SearchDomain.ItemResult -> openItem(result)
            null -> Unit
        }
    }

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
                // The list's keys are read BEFORE the search bar, which holds the focus: the arrows and Enter
                // mean the list, as in the task picker (PRD §7), and every other key types.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.DirectionDown -> {
                            if (count > 0) selected = (selected + 1).coerceAtMost(count - 1)
                            true
                        }
                        event.key == Key.DirectionUp -> {
                            selected = (selected - 1).coerceAtLeast(0)
                            true
                        }
                        event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                            openSelected()
                            true
                        }
                        // A task cell's Ctrl+C — but only when the bar holds nothing to copy itself.
                        event.isCtrlPressed && event.key == Key.C && query.isEmpty() &&
                            results.getOrNull(selected) is SearchDomain.TaskResult -> {
                            taskActions(results[selected] as SearchDomain.TaskResult, null).onCopyTaskId()
                            true
                        }
                        else -> false
                    }
                }
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
                    modifier = Modifier.weight(1f).focusRequester(fieldFocus),
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
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                        itemsIndexed(results, key = { _, r -> resultKey(r) }) { index, result ->
                            when (result) {
                                is SearchDomain.TaskResult ->
                                    TaskResultRow(
                                        result = result,
                                        selected = index == selected,
                                        onSelect = { selected = index },
                                        actions = { path -> taskActions(result, path) },
                                    )
                                is SearchDomain.ItemResult ->
                                    ItemResultRow(
                                        item = result,
                                        selected = index == selected,
                                        onSelect = { selected = index },
                                        onOpen = { openItem(result) },
                                        // An alarm or a timer has its own window, and the right-click opens
                                        // it straight away: its settings are what the user is asking about.
                                        onRightClick =
                                            when (result.kind) {
                                                SearchDomain.Kind.Alarm -> {
                                                    { onEditAlarmOrTimer(AlarmWindowSubject(result.id, isAlarm = true)) }
                                                }
                                                SearchDomain.Kind.Timer -> {
                                                    { onEditAlarmOrTimer(AlarmWindowSubject(result.id, isAlarm = false)) }
                                                }
                                                SearchDomain.Kind.Reminder -> {
                                                    { onEditReminder(result.id) }
                                                }
                                                else -> null
                                            },
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

@Composable
private fun TaskResultRow(
    result: SearchDomain.TaskResult,
    selected: Boolean,
    onSelect: () -> Unit,
    /** The cell menu for the path the right-click landed on — built on demand, never over a stale state. */
    actions: (path: List<String>?) -> TaskCellMenuActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuActions by remember { mutableStateOf<TaskCellMenuActions?>(null) }
    // One menu per row, whichever part was right-clicked: the row, its path box, or a line of its list of paths.
    val openMenu: (List<String>?) -> Unit = { path ->
        menuActions = actions(path)
        menuOpen = true
    }
    Box(
        modifier = resultRowModifier(selected)
            .resultRowGestures(
                key = result.taskId,
                onSelect = onSelect,
                onOpen = { actions(null).onEdit?.invoke() },
                onOpenMenu = { openMenu(result.shownPath.takeIf { it.isNotEmpty() }) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            KindSection(result.kind)
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp)) {
                TaskResultRowLayout(
                    title = {
                        Text(
                            text = result.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    path = { TaskPathBox(result, onSelect = onSelect, onOpenMenu = openMenu) },
                    logo = if (result.inTaskTree) null else ({ NotInTreeLogo() }),
                )
            }
        }
        val shown = menuActions
        if (shown != null) {
            transientMenuDismissal(menuOpen) { menuOpen = false }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                TaskCellMenuItems(shown) { menuOpen = false }
            }
        }
    }
}

@Composable
private fun MenuEntry(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = onClick)
}

/**
 * The title / path / logo split of a task row. **The title prevails**: it is measured first, allowed
 * everything but the logo and the thinnest path box, and the path box takes whatever is left — so a long
 * title squeezes the path to a sliver at the right of the row, left of the logo, and neither is ever dropped.
 * A [Row] cannot say this: it measures its unweighted children first, which is the path taking what it needs
 * and the title the rest — the opposite priority.
 */
@Composable
private fun TaskResultRowLayout(
    title: @Composable () -> Unit,
    path: @Composable () -> Unit,
    logo: (@Composable () -> Unit)?,
) {
    Layout(
        contents = listOf(title, path, logo ?: {}),
        modifier = Modifier.fillMaxSize(),
    ) { (titleM, pathM, logoM), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = 8.dp.roundToPx()
        val loose = Constraints(maxWidth = width, maxHeight = height)
        val logoP = logoM.firstOrNull()?.measure(loose)
        val logoW = logoP?.let { it.width + gap } ?: 0
        val forBoth = (width - logoW - gap).coerceAtLeast(0)
        val minPath = MIN_PATH_BOX_WIDTH.roundToPx().coerceAtMost(forBoth)
        val titleP = titleM.first().measure(Constraints(maxWidth = forBoth - minPath, maxHeight = height))
        val pathW = (forBoth - titleP.width).coerceAtLeast(minPath)
        val pathP = pathM.first().measure(Constraints.fixed(pathW, (height - 8.dp.roundToPx()).coerceAtLeast(0)))
        layout(width, height) {
            titleP.place(0, (height - titleP.height) / 2)
            pathP.place(titleP.width + gap, (height - pathP.height) / 2)
            logoP?.place(width - logoP.width, (height - logoP.height) / 2)
        }
    }
}

/**
 * The path, in its rectangle — with the arrow that lists every path when the task has several. A right-click on
 * the box, or on one line of the list, opens the row's cell menu for THAT path ([onOpenMenu]): its own gesture,
 * so it works however the row's is laid out around it.
 */
@Composable
private fun TaskPathBox(
    result: SearchDomain.TaskResult,
    onSelect: () -> Unit,
    onOpenMenu: (List<String>?) -> Unit,
) {
    var listOpen by remember { mutableStateOf(false) }
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
    /** What a right-click does instead of the contextual menu, for a row whose kind has one (alarm, timer). */
    onRightClick: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = resultRowModifier(selected)
            .resultRowGestures(
                key = item.kind.name + "/" + item.id,
                onSelect = onSelect,
                onOpen = onOpen,
                onOpenMenu = { if (onRightClick != null) onRightClick() else menuOpen = true },
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
                    SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer -> "open in Alarms"
                    SearchDomain.Kind.Reminder -> "open in Reminders"
                    else -> "edit " + item.kind.label
                },
            ) {
                menuOpen = false
                onOpen()
            }
        }
    }
}
