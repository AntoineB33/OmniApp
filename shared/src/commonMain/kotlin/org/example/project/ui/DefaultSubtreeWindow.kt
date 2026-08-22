package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.DefaultSubtreeNode
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.platform.isDeadKey

/**
 * PRD §4/§7 **Default sub-tree**: the floating window where the user draws the sub-tree that appears under
 * every task they create — i.e. under every cell they type a title into while the lateral-menu switch beside
 * this window's button is on.
 *
 * **It IS the task tree, drawn the same way, plus one little switch per cell.** Rows carry the task sheet's
 * own chrome ([SheetColors], [INDENT_STEP_DP], [taskSheetGuideLines], [TaskSheetExpandArrow]) and the same
 * gestures: a click selects, a double-click — or simply typing on the selected row — opens Edit Mode in place
 * with the ordinary [EditModeMenuBlock] beneath it, `Enter`/`Shift+Enter` step down/up, `Tab` steps into the
 * child, `Ctrl+Enter` breaks the line and `Delete`/`Backspace` empties the row. There is no bin button and no
 * always-on text field: **the blank title is what deletes**, here as in the tree.
 *
 * Two columns of the task tree are deliberately absent, because a template has nothing to put in them: the
 * **priority percentage** (a template sits in no tree, so it has no absolute priority — the grafted cells get
 * theirs from where they land) and the **minimum time** (a property of a real task, editable once the row has
 * produced one). The switch takes the percentage's column, at the same width, so both trees line up.
 *
 * **The switch on each non-empty row and that row's identity menu are two views of one value** (the node's
 * task id, see [DefaultSubtreeNode]):
 *  - the switch is **on** iff "New id" is the selected row, i.e. the node mints a brand-new task each time the
 *    template is applied;
 *  - picking an existing task in the menu turns the switch **off** — the node then points at that one task, so
 *    every cell built from it mirrors it;
 *  - turning the switch back **on** selects "New id" again (visibly, when the row is still in edit mode);
 *  - the switch **cannot be turned off** while "New id" is selected: leaving it needs a task to point at, which
 *    only the menu can supply — so a press on an already-on switch does nothing.
 *
 * A row bound to an existing task shows **that task's own sub-tree** beneath it — a sub-list belongs to the
 * task id, not to the cell — drawn greyed and uneditable ([SheetColors.nonSelectableFill]), exactly as the
 * tree draws a cell nothing may be done to. Its template children are hidden while it is bound; they are kept,
 * not deleted, so turning the switch back on brings them back, just as re-assigning a task id restores a
 * detached parent's sub-list.
 *
 * Unlike the task tree, template rows open **expanded**: the template is small by design and the window has to
 * show what will actually be grafted (and the trailing empty child row is how the next level is typed). A
 * bound row's borrowed sub-tree opens **collapsed**, because that one can be arbitrarily large.
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun DefaultSubtreeWindow(
    nodes: List<DefaultSubtreeNode>,
    /** Whether the policy is currently applied (the lateral-menu switch) — shown here, toggled there. */
    enabled: Boolean,
    /** Every edit pushes the whole template up; the reducer normalizes it (drops the empty rows). */
    onChange: (List<DefaultSubtreeNode>) -> Unit,
    /** The edited row's identity menu — "New id" plus the tasks whose title is exactly the typed text. */
    taskMenuEntries: (draftText: String) -> List<SchedulerDomain.ChangeTaskMenuEntry>,
    /** PRD §4 Title suggestions: existing task titles similar to what is typed. */
    titleSuggestions: (String) -> List<String>,
    /** The sub-tree a bound row borrows — the bound task's own children (PRD §4). */
    boundSubtree: (TaskId) -> List<SchedulerDomain.TaskOutlineNode>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(initialOffset) }
    // Mints the row handles. Seeded past every suffix already in the stored template so a reopened window
    // cannot hand out an id one of its own rows is already keyed by.
    val minter = remember { NodeIdMinter(maxNodeIdSuffix(nodes) + 1) }
    // The edited template. Seeded once (like the reminders manager's rows) so the empty rows this window adds
    // — which the reducer strips before storing — are not yanked out from under the row being typed into.
    var tree by remember { mutableStateOf(withTrailingBlanks(nodes, minter)) }
    // The tree's own three per-row states (PRD §3/§4), local to this window: the selected row, the row in
    // Edit Mode, and the rows the user collapsed (template rows open expanded, hence a *collapsed* set).
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }

    fun update(transform: (List<DefaultSubtreeNode>) -> List<DefaultSubtreeNode>) {
        val next = withTrailingBlanks(transform(tree), minter)
        tree = next
        onChange(next)
    }

    // The displayed rows, depth-first, exactly like the tree's visible order — what Enter and the arrow keys
    // step through. Recomputed with the tree, so a row a keystroke removed can never be navigated to.
    val visible = visibleRows(tree, collapsed)
    val visibleIds = visible.map { it.first }

    fun beginEdit(id: String, seed: String?) {
        selectedId = id
        editingId = id
        if (seed != null) {
            pathOf(visible, id)?.let { path -> update { it.mutate(path) { node -> node.copy(title = seed) } } }
        }
    }

    /** Ends Edit Mode, moves the selection where the exit key says, then drops the row if it went blank. */
    fun endEdit(navigation: TemplateEditExit) {
        val id = editingId ?: return
        val index = visibleIds.indexOf(id)
        val target =
            when (navigation) {
                TemplateEditExit.Stay -> id
                TemplateEditExit.Down -> visibleIds.getOrNull(index + 1) ?: id
                TemplateEditExit.Up -> visibleIds.getOrNull(index - 1) ?: id
                // Tab steps into the row's own sub-list, expanding it first — the reducer's rule for the
                // tree (TabToChild), applied to the template.
                TemplateEditExit.Child -> {
                    collapsed = collapsed - id
                    nodeAt(tree, pathOf(visible, id))?.children?.firstOrNull()?.id ?: id
                }
            }
        editingId = null
        selectedId = target
        // PRD §4 Deletion / Empty cells management: an emptied row goes, with its children, as soon as the
        // edit that emptied it ends — except the bottom one of its list, which the tree keeps too.
        update { pruneBlanksExceptTrailing(it) }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredWidth (not width) so the window keeps its fixed width and does not adapt to the app's
            // width when the content area is narrower than it.
            .requiredWidth(520.dp)
            // Raise on press AFTER the offset so the hit region tracks the (possibly dragged) window.
            .raiseOnPress(onRaise),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Title bar doubles as the drag handle for moving the window.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { onOffsetChange(offset) }) { change, dragAmount ->
                            change.consume()
                            offset += dragAmount
                        }
                    }
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Default sub-tree",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "✕",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            Text(
                text =
                    "This tree appears under every task you create — whenever you type a title into an empty " +
                        "cell. Edit it like the task tree: click a row, type to name it, Enter or Tab to move " +
                        "on. A row's switch is ON when it brings a brand new task id, OFF when it points at " +
                        "one existing task (pick it in the row's Tasks menu), which then brings that task's " +
                        "own sub-tree.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Text(
                text =
                    if (enabled) {
                        "Currently applied."
                    } else {
                        "Not applied — turn on the switch beside the lateral-menu button to use it."
                    },
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Grows with the template up to a cap, then scrolls — an edited row's menus are tall.
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp, horizontal = 12.dp),
            ) {
                TemplateRows(
                    nodes = tree,
                    path = emptyList(),
                    depth = 0,
                    scope = TemplateTreeScope(
                        selectedId = selectedId,
                        editingId = editingId,
                        collapsed = collapsed,
                        taskMenuEntries = taskMenuEntries,
                        titleSuggestions = titleSuggestions,
                        boundSubtree = boundSubtree,
                        onSelect = { id ->
                            // Clicking another row ends the edit in progress, exactly as it does in the tree.
                            if (editingId != null && editingId != id) endEdit(TemplateEditExit.Stay)
                            selectedId = id
                        },
                        onBeginEdit = ::beginEdit,
                        onEndEdit = ::endEdit,
                        onToggleCollapse = { id ->
                            collapsed = if (id in collapsed) collapsed - id else collapsed + id
                        },
                        onNavigate = { id, delta ->
                            val index = visibleIds.indexOf(id)
                            visibleIds.getOrNull(index + delta)?.let { selectedId = it }
                        },
                        onMutate = { rowPath, transform -> update { it.mutate(rowPath, transform) } },
                    ),
                )
            }
        }
    }
}

/** Where the key that left Edit Mode puts the selection (PRD §4) — the template's `EditExitNavigation`. */
private enum class TemplateEditExit { Stay, Down, Up, Child }

/**
 * Everything a template row needs that is not the node itself. Bundled because the rows recurse and the whole
 * tree is drawn from one set of callbacks — threading them one by one down every level is how they drift.
 */
private class TemplateTreeScope(
    val selectedId: String?,
    val editingId: String?,
    val collapsed: Set<String>,
    val taskMenuEntries: (String) -> List<SchedulerDomain.ChangeTaskMenuEntry>,
    val titleSuggestions: (String) -> List<String>,
    val boundSubtree: (TaskId) -> List<SchedulerDomain.TaskOutlineNode>,
    val onSelect: (String) -> Unit,
    /** Opens Edit Mode on a row; a non-null seed is the character that opened it, replacing the title. */
    val onBeginEdit: (String, String?) -> Unit,
    val onEndEdit: (TemplateEditExit) -> Unit,
    val onToggleCollapse: (String) -> Unit,
    /** Moves the selection [delta] rows through the visible order (the tree's Up/Down arrows). */
    val onNavigate: (String, Int) -> Unit,
    val onMutate: (List<Int>, (DefaultSubtreeNode) -> DefaultSubtreeNode?) -> Unit,
)

/**
 * One level of the template, and the level beneath each of its rows: the template's own children for an
 * unbound row, the **bound task's** children for a bound one.
 */
@Composable
private fun TemplateRows(
    nodes: List<DefaultSubtreeNode>,
    path: List<Int>,
    depth: Int,
    scope: TemplateTreeScope,
) {
    // PRD §2 Priority Display: one shared text-column width per sub-list, so what follows the titles (the
    // percentage in the tree, the switch here) lines up down the whole list.
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val titleWidthPx: Map<String, Int> =
        nodes.associate { node ->
            node.id to if (node.title.isEmpty()) 0 else textMeasurer.measure(node.title, bodyStyle).size.width
        }
    val titleColumnWidth: Dp =
        with(density) { (titleWidthPx.values.maxOrNull() ?: 0).toDp() }
            .coerceIn(PRIORITY_COLUMN_MIN, PRIORITY_COLUMN_MAX)
    val titleColumnPx = with(density) { titleColumnWidth.toPx() }

    nodes.forEachIndexed { index, node ->
        val rowPath = path + index
        val expanded = node.id !in scope.collapsed
        TemplateRow(
            node = node,
            path = rowPath,
            depth = depth,
            titleColumnWidth = titleColumnWidth,
            textOverflow = (titleWidthPx[node.id] ?: 0) > titleColumnPx,
            expanded = expanded,
            scope = scope,
        )
        if (node.title.isBlank() || !expanded) return@forEachIndexed
        val boundTaskId = node.taskId
        if (boundTaskId == null) {
            TemplateRows(nodes = node.children, path = rowPath, depth = depth + 1, scope = scope)
        } else {
            // A bound row shows the task's OWN sub-tree, so the template has nothing to draw for it — its
            // stored children wait for the switch to go back on.
            BorrowedRows(nodes = scope.boundSubtree(boundTaskId), depth = depth + 1)
        }
    }
}

@Composable
private fun TemplateRow(
    node: DefaultSubtreeNode,
    path: List<Int>,
    depth: Int,
    titleColumnWidth: Dp,
    textOverflow: Boolean,
    expanded: Boolean,
    scope: TemplateTreeScope,
) {
    val titled = node.title.isNotBlank()
    val selected = scope.selectedId == node.id
    val editing = scope.editingId == node.id
    // A titled row always has a level under it: its own template children while it mints an id, the bound
    // task's while it points at one. Exactly the tree's rule (a titled cell always owns a sub-list).
    val hasChildren = titled
    val rowFocus = remember(node.id) { FocusRequester() }
    val editFocus = remember(node.id) { FocusRequester() }
    // Only a selected, non-editing row takes the keyboard — while Edit Mode is on, the field owns it.
    val keyboardArmed = selected && !editing
    LaunchedEffect(keyboardArmed) { if (keyboardArmed) rowFocus.requestFocus() }
    LaunchedEffect(editing) { if (editing) editFocus.requestFocus() }

    fun mutate(transform: (DefaultSubtreeNode) -> DefaultSubtreeNode?) = scope.onMutate(path, transform)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // PRD §2: guide-lines on the left illustrate the parent-child hierarchy.
                .taskSheetGuideLines(depth)
                .padding(start = (depth * INDENT_STEP_DP).dp)
                .defaultMinSize(minHeight = 28.dp)
                .background(
                    if (selected || editing) SheetColors.selectionFill else SheetColors.cellBackground
                )
                .then(
                    if (selected || editing) Modifier.border(2.dp, SheetColors.activeBorder)
                    else Modifier.border(1.dp, SheetColors.grid)
                )
                .then(
                    if (keyboardArmed) {
                        Modifier
                            .focusRequester(rowFocus)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    event.key == Key.Enter -> {
                                        scope.onBeginEdit(node.id, null)
                                        true
                                    }
                                    // PRD §4 Edition without Edition Mode: Delete/Backspace empties the
                                    // selected cell — and the blank title is what deletes.
                                    event.key == Key.Delete || event.key == Key.Backspace -> {
                                        if (titled) mutate { null }
                                        true
                                    }
                                    event.key == Key.DirectionDown -> {
                                        scope.onNavigate(node.id, 1)
                                        true
                                    }
                                    event.key == Key.DirectionUp -> {
                                        scope.onNavigate(node.id, -1)
                                        true
                                    }
                                    else -> {
                                        // A dead key opens Edit Mode empty so the accent can compose in the
                                        // field (^ then e → ê) instead of being swallowed by a fresh edit.
                                        val typed =
                                            if (event.isDeadKey()) {
                                                ""
                                            } else {
                                                event.printableChar() ?: return@onPreviewKeyEvent false
                                            }
                                        scope.onBeginEdit(node.id, typed)
                                        true
                                    }
                                }
                            }
                    } else {
                        Modifier
                    }
                )
                .pointerInput(node.id) {
                    detectTapGestures(
                        onTap = { scope.onSelect(node.id) },
                        onDoubleTap = { scope.onBeginEdit(node.id, null) },
                    )
                }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskSheetExpandArrow(
                hasChildren = hasChildren,
                expanded = expanded,
                onToggle = { scope.onToggleCollapse(node.id) },
            )
            Box(
                modifier = Modifier.width(titleColumnWidth).defaultMinSize(minHeight = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (editing) {
                    TemplateTitleField(
                        node = node,
                        focusRequester = editFocus,
                        onTextChange = { text -> mutate { it.copy(title = text) } },
                        onExit = scope.onEndEdit,
                    )
                } else {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = node.title.ifEmpty { " " },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                    if (textOverflow) {
                        Text(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .background(
                                    if (selected) SheetColors.selectionFill else SheetColors.cellBackground
                                ),
                            text = "▸",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheetColors.overflowArrow,
                        )
                    }
                }
            }
            // The switch takes the column the task tree gives the priority percentage, so the two trees keep
            // the same shape. Only a non-empty row has one (PRD §4) — there is no task behind an empty cell.
            Box(
                modifier = Modifier.width(PERCENT_COLUMN_WIDTH).padding(start = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (titled) {
                    // The switch IS the node's task binding: on = a brand new id, off = the task it points
                    // at. Turning it off is not a gesture the switch can perform on its own (there would be
                    // no task to point at), so a press on an already-on switch is deliberately ignored —
                    // the Tasks menu is what turns it off.
                    TemplateRowSwitch(
                        checked = node.taskId == null,
                        onTurnOn = { mutate { it.copy(taskId = null) } },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }

        // PRD §4 Edit Mode: the naming menus render directly under the row being edited and nowhere else, so
        // the rows below are pushed down only while a menu is actually visible. focusPreserving, or clicking
        // a menu row would blur the field before the pick registered.
        if (!editing) return@Column
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * INDENT_STEP_DP).dp + 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EditModeMenuBlock(
                identityLabel = "Tasks",
                identityRows = scope.taskMenuEntries(node.title).map { entry ->
                    EditMenuItem(label = entry.label, selected = entry.taskId == node.taskId) {
                        mutate { it.copy(taskId = entry.taskId) }
                    }
                },
                suggestions = scope.titleSuggestions(node.title).map { suggestion ->
                    EditMenuItem(label = suggestion) { mutate { it.copy(title = suggestion) } }
                },
                focusPreserving = true,
            )
        }
    }
}

/**
 * The in-place title field of a row in Edit Mode — the task tree's own field, key for key: `Ctrl+Enter`
 * inserts a line break, the vertical arrows only leave the field at the text's first/last line, `Enter` and
 * `Tab` commit and move on.
 */
@Composable
private fun TemplateTitleField(
    node: DefaultSubtreeNode,
    focusRequester: FocusRequester,
    onTextChange: (String) -> Unit,
    onExit: (TemplateEditExit) -> Unit,
) {
    var textFieldValue by remember(node.id) { mutableStateOf(TextFieldValue()) }
    SideEffect {
        if (textFieldValue.text != node.title) {
            textFieldValue = TextFieldValue(text = node.title, selection = TextRange(node.title.length))
        }
    }
    BasicTextField(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 20.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed) -> {
                        val selection = textFieldValue.selection
                        val insertAt = selection.min
                        val newText =
                            textFieldValue.text.substring(0, insertAt) + "\n" +
                                textFieldValue.text.substring(selection.max)
                        textFieldValue = TextFieldValue(text = newText, selection = TextRange(insertAt + 1))
                        onTextChange(newText)
                        true
                    }
                    event.key == Key.DirectionUp -> {
                        if (textFieldValue.text.lastIndexOf('\n', textFieldValue.selection.min - 1) < 0) {
                            textFieldValue = textFieldValue.copy(selection = TextRange(0))
                            true
                        } else {
                            false
                        }
                    }
                    event.key == Key.DirectionDown -> {
                        if (textFieldValue.text.indexOf('\n', textFieldValue.selection.max) < 0) {
                            textFieldValue =
                                textFieldValue.copy(selection = TextRange(textFieldValue.text.length))
                            true
                        } else {
                            false
                        }
                    }
                    event.key == Key.Escape -> {
                        onExit(TemplateEditExit.Stay)
                        true
                    }
                    event.key == Key.Enter && event.isShiftPressed -> {
                        onExit(TemplateEditExit.Up)
                        true
                    }
                    event.key == Key.Enter -> {
                        onExit(TemplateEditExit.Down)
                        true
                    }
                    event.key == Key.Tab && event.isShiftPressed -> {
                        onExit(TemplateEditExit.Up)
                        true
                    }
                    event.key == Key.Tab -> {
                        onExit(TemplateEditExit.Child)
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
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(SheetColors.activeBorder),
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                innerTextField()
            }
        },
    )
}

/**
 * PRD §4: the little switch a non-empty template row carries, in the column the tree gives the percentage.
 * Compact on purpose — a Material `Switch` measures taller than a task-sheet row and would make the
 * template's rows a different height from the tree's, which is the one thing this window must not do.
 *
 * On means "New id"; it can only ever be pressed **on**, because turning it off needs a task to point at and
 * only the row's Tasks menu can supply one.
 */
@Composable
private fun TemplateRowSwitch(checked: Boolean, onTurnOn: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 26.dp, height = 14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (checked) SheetColors.activeBorder else SheetColors.guideLine)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (!checked) onTurnOn() },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * The sub-tree a bound row borrows from its task, drawn read-only: same chrome, same indentation, but the
 * greyed background the tree gives a cell nothing may be done to. Nothing here is a template node, so nothing
 * here carries a switch. Opens collapsed at every level — a real task's sub-tree can be arbitrarily deep.
 */
@Composable
private fun BorrowedRows(nodes: List<SchedulerDomain.TaskOutlineNode>, depth: Int) {
    nodes.forEach { node ->
        var expanded by remember(node) { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .taskSheetGuideLines(depth)
                .padding(start = (depth * INDENT_STEP_DP).dp)
                .defaultMinSize(minHeight = 28.dp)
                .background(SheetColors.nonSelectableFill)
                .border(1.dp, SheetColors.grid)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskSheetExpandArrow(
                hasChildren = node.children.isNotEmpty(),
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
            Text(
                text = node.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) BorrowedRows(nodes = node.children, depth = depth + 1)
    }
}

/** Hands out the row handles (`dst/{n}`) of one open editor session. */
private class NodeIdMinter(private var next: Int) {
    fun mint(): String = "dst/" + next++
}

/** The highest `dst/{n}` suffix anywhere in [nodes], or -1 when the template holds none. */
private fun maxNodeIdSuffix(nodes: List<DefaultSubtreeNode>): Int =
    nodes.maxOfOrNull { node ->
        maxOf(node.id.substringAfterLast('/').toIntOrNull() ?: -1, maxNodeIdSuffix(node.children))
    } ?: -1

/**
 * The displayed rows in depth-first order, each with the path that reaches it — the template's counterpart of
 * [SchedulerDomain.visibleOccurrences]. A collapsed row hides its level, and a **bound** row's level belongs
 * to its task, so neither contributes template rows to navigate through.
 */
private fun visibleRows(
    nodes: List<DefaultSubtreeNode>,
    collapsed: Set<String>,
    path: List<Int> = emptyList(),
): List<Pair<String, List<Int>>> =
    nodes.flatMapIndexed { index, node ->
        val rowPath = path + index
        val descend = node.title.isNotBlank() && node.taskId == null && node.id !in collapsed
        listOf(node.id to rowPath) +
            if (descend) visibleRows(node.children, collapsed, rowPath) else emptyList()
    }

private fun pathOf(visible: List<Pair<String, List<Int>>>, id: String): List<Int>? =
    visible.firstOrNull { it.first == id }?.second

private fun nodeAt(nodes: List<DefaultSubtreeNode>, path: List<Int>?): DefaultSubtreeNode? {
    if (path.isNullOrEmpty()) return null
    val node = nodes.getOrNull(path.first()) ?: return null
    return if (path.size == 1) node else nodeAt(node.children, path.drop(1))
}

/**
 * PRD §4 *Auto-Expansion*, applied to the template: every list ends with one empty row, and every titled row
 * that mints its own id owns a list of its own. A row bound to an existing task gets none — its children come
 * from the task, so an empty row there would promise an edit the template cannot make.
 */
private fun withTrailingBlanks(
    nodes: List<DefaultSubtreeNode>,
    minter: NodeIdMinter,
): List<DefaultSubtreeNode> {
    val expanded =
        nodes.map { node ->
            if (node.title.isNotBlank() && node.taskId == null) {
                node.copy(children = withTrailingBlanks(node.children, minter))
            } else {
                node
            }
        }
    return if (expanded.isNotEmpty() && expanded.last().title.isBlank()) {
        expanded
    } else {
        expanded + DefaultSubtreeNode(id = minter.mint())
    }
}

/**
 * PRD §4 *Empty cells management*: an emptied row is removed unless it is the bottom one of its list — the
 * tree's own cleanup rule. Same effect as [SchedulerDomain.normalizeDefaultSubtree] (which is what the reducer
 * stores), except that it keeps the trailing blank **with its handle**, so the row the user is looking at is
 * not re-minted under them.
 */
private fun pruneBlanksExceptTrailing(nodes: List<DefaultSubtreeNode>): List<DefaultSubtreeNode> =
    nodes
        .filterIndexed { index, node -> node.title.isNotBlank() || index == nodes.lastIndex }
        .map { node ->
            if (node.title.isBlank()) node else node.copy(children = pruneBlanksExceptTrailing(node.children))
        }

/**
 * Replaces (or, on a null result, removes) the node at [path]. The template is small and immutable, so a
 * rebuild down one path is both the simplest and the cheapest edit.
 */
private fun List<DefaultSubtreeNode>.mutate(
    path: List<Int>,
    transform: (DefaultSubtreeNode) -> DefaultSubtreeNode?,
): List<DefaultSubtreeNode> {
    val index = path.firstOrNull() ?: return this
    if (index !in indices) return this
    val node = this[index]
    val updated =
        if (path.size == 1) transform(node)
        else node.copy(children = node.children.mutate(path.drop(1), transform))
    return if (updated == null) {
        filterIndexed { i, _ -> i != index }
    } else {
        mapIndexed { i, n -> if (i == index) updated else n }
    }
}
