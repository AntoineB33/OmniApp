package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import org.example.project.scheduler.ui.contextMenuModifier

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
 * right-click selects it and opens its contextual menu — for a task the §13 menu's entries, with "go to task
 * tree" and "deep copy" greyed where the live tree does not hold the task.
 *
 * The query, the checked kinds and the selection are **Compose-only state**: how the user is looking for something
 * is not a fact about the account. Nothing here writes the state except through the gestures' own intents.
 */
@Composable
fun SearchWindow(
    /** The live state — the search is about the account's own things. */
    state: SchedulerState,
    onOpenTaskEdit: (TaskId) -> Unit,
    onStartTaskNow: (TaskId) -> Unit,
    /** PRD §8 "go to task tree" — the app's one handler, shared with the calendar and "All tasks". */
    onGoToTaskTree: (TaskId) -> Unit,
    onDeepCopyCell: (CellId) -> Unit,
    onOpenCategory: (CategoryId) -> Unit,
    onOpenPeriodKind: (String) -> Unit,
    /** Alarms AND timers live in the one Alarms window (PRD §18). */
    onOpenAlarms: () -> Unit,
    onOpenReminders: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
) {
    val frame = rememberWindowFrameState("Search", initialOffset, initialSize)
    var query by remember { mutableStateOf("") }
    var kinds by remember { mutableStateOf(setOf(SearchDomain.Kind.Task)) }
    var kindMenuOpen by remember { mutableStateOf(false) }
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
            kinds, query, allPaths, state.tasks, state.taskTrees, state.categories, state.periodKinds,
            state.panels, state.alarms, state.timers, state.chores,
        ) {
            SearchDomain.results(state, kinds, query) { allPaths }
        }
    val count = results.size
    // A new question starts at its best answer.
    LaunchedEffect(kinds, query) { selected = 0 }
    LaunchedEffect(selected, count) {
        if (count > 0) listState.animateScrollToItem(selected.coerceIn(0, count - 1))
    }

    val currentState by rememberUpdatedState(state)
    fun taskActions(result: SearchDomain.TaskResult): TaskResultActions {
        val live = currentState.tasks[result.taskId]
        val occurrence = SchedulerDomain.firstTaskOccurrence(currentState, result.taskId)
        return TaskResultActions(
            onStartNow =
                if (live != null && SchedulerDomain.isPlaceableTask(currentState, result.taskId)) {
                    { onStartTaskNow(result.taskId) }
                } else {
                    null
                },
            onEdit = if (live != null) ({ onOpenTaskEdit(result.taskId) }) else null,
            onGoToTaskTree = if (occurrence != null) ({ onGoToTaskTree(result.taskId) }) else null,
            onCopyTaskId =
                if (SchedulerDomain.isUserTaskId(result.taskId)) {
                    { writeSystemClipboardText(SchedulerDomain.TASK_ID_REFERENCE_PREFIX + result.taskId.value) }
                } else {
                    null
                },
            onDeepCopy = occurrence?.let { { onDeepCopyCell(it.cellId) } },
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
            is SearchDomain.TaskResult -> taskActions(result).onEdit?.invoke()
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
                            taskActions(results[selected] as SearchDomain.TaskResult).onCopyTaskId?.invoke()
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
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search") },
                    modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                )
                Box(Modifier.width(170.dp)) {
                    Text(
                        text = kindsLabel(kinds) + "  ▾",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            .menuToggleClickable(kindMenuOpen) { kindMenuOpen = it }
                            .padding(horizontal = 10.dp, vertical = 14.dp),
                    )
                    transientMenuDismissal(kindMenuOpen) { kindMenuOpen = false }
                    DropdownMenu(
                        expanded = kindMenuOpen,
                        onDismissRequest = { kindMenuOpen = false },
                        properties = PopupProperties(focusable = false),
                    ) {
                        // A check box per kind; ticking one leaves the menu open, so several can be ticked in turn.
                        SearchDomain.Kind.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = { Checkbox(checked = option in kinds, onCheckedChange = null) },
                                onClick = { kinds = if (option in kinds) kinds - option else kinds + option },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- The result list --------------------------------------------------------------------
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (count == 0) {
                    Text(
                        text =
                            when {
                                kinds.isEmpty() -> "Tick a kind to look for."
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
                                        actions = { taskActions(result) },
                                    )
                                is SearchDomain.ItemResult ->
                                    ItemResultRow(
                                        item = result,
                                        selected = index == selected,
                                        onSelect = { selected = index },
                                        onOpen = { openItem(result) },
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

/**
 * What a task row's gestures do — PRD §13's cell menu, less the entries that are about a CELL (collapse, add
 * the default sub-tree). A null entry is greyed, never hidden, where it names a place the task has not got
 * (go to task tree, deep copy); hidden where it names an action the task does not take (start now, edit).
 */
private class TaskResultActions(
    val onStartNow: (() -> Unit)?,
    val onEdit: (() -> Unit)?,
    val onGoToTaskTree: (() -> Unit)?,
    val onCopyTaskId: (() -> Unit)?,
    val onDeepCopy: (() -> Unit)?,
)

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
        .background(
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            RoundedCornerShape(4.dp),
        )
        .padding(horizontal = 6.dp)

@Composable
private fun TaskResultRow(
    result: SearchDomain.TaskResult,
    selected: Boolean,
    onSelect: () -> Unit,
    /** Built on demand, so a row does not hold a callback set that closes over a stale state. */
    actions: () -> TaskResultActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuActions by remember { mutableStateOf<TaskResultActions?>(null) }
    Box(
        modifier = resultRowModifier(selected)
            .resultRowGestures(
                key = result.taskId,
                onSelect = onSelect,
                onOpen = { actions().onEdit?.invoke() },
                onOpenMenu = {
                    menuActions = actions()
                    menuOpen = true
                },
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
                    path = { TaskPathBox(result) },
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
                shown.onStartNow?.let { MenuEntry("start this task now") { menuOpen = false; it() } }
                shown.onEdit?.let { MenuEntry("edit task") { menuOpen = false; it() } }
                MenuEntry("go to task tree", enabled = shown.onGoToTaskTree != null) {
                    menuOpen = false
                    shown.onGoToTaskTree?.invoke()
                }
                shown.onCopyTaskId?.let { MenuEntry("copy task id (ctrl c)") { menuOpen = false; it() } }
                MenuEntry("deep copy", enabled = shown.onDeepCopy != null) {
                    menuOpen = false
                    shown.onDeepCopy?.invoke()
                }
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

/** The path, in its rectangle — with the arrow that lists every path when the task has several. */
@Composable
private fun TaskPathBox(result: SearchDomain.TaskResult) {
    var listOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
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
                                modifier = Modifier.padding(vertical = 3.dp),
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = resultRowModifier(selected)
            .resultRowGestures(
                key = item.kind.name + "/" + item.id,
                onSelect = onSelect,
                onOpen = onOpen,
                onOpenMenu = { menuOpen = true },
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
