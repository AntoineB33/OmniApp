package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.ui.TaskRow

/** One identity row of a [NamingCell]: the key picking it commits, and what the row reads. */
internal data class NamingRow(val key: String, val label: String, val taskColor: Color? = null)

/**
 * **A field that names something, as a task cell does** — the tree's own [TaskRow], configured, the way the
 * priority-weight table's rows are one. ONE press enters Edit Mode (user rule 2026-10-03: a field outside the tree has
 * nothing to select first); Enter / Tab / Escape leave it, and so does a press anywhere else (the §4 Forced Exit, here
 * the outside press, since it has no sibling cells to be pressed). ONLY while it is in Edit Mode does it draw the one
 * edit-mode menu block of the app ([EditModeMenuBlock]): the [identity] rows for the draft (picking one is the commit,
 * [onPick]) and the [suggestions] (picking one only fills the field).
 *
 * The configured differences from a tree cell: no expand arrow, no minimum time, no percentage, no Mode selector
 * (naming IS pointing, as in the category field), and no "New task" row unless the caller gives one — a field that
 * may name something new adds a [namingCreateRow] for the draft, the tree cell's "New task" row in its own words.
 * Emptying it and leaving is [onCleared] — the cell's own "emptying deletes"; a draft that names nothing is dropped as
 * the editor closes. [trailing] is drawn after the title while not editing.
 *
 * **Every field outside the tree that names an element is this** (user rule 2026-10-03, after the Search window's
 * "Changed element" filter): that filter, the "Set of tasks" action's "add a task", a category rule's task cell,
 * "Add under…", the categories fields (a task's, an occurrence's, the Categories window's, the Search window's category
 * actions and filter) and a period's kind — so they cannot come to behave differently. A field whose menus were drawn
 * under a plain text field showed them before it was ever entered; that is the anomaly this funnel exists to prevent.
 */
@Composable
internal fun NamingCell(
    cellId: CellId,
    shown: String,
    identityLabel: String,
    identity: (draft: String) -> List<NamingRow>,
    suggestions: (draft: String) -> List<String>,
    onPick: (String) -> Unit,
    selectedKey: String? = null,
    onCleared: () -> Unit = {},
    taskColor: Color? = null,
    width: Dp = 320.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    var selected by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    fun closeEditor() {
        if (!editing) return
        editing = false
        if (draft.isBlank()) onCleared()
    }
    fun openEditor() {
        if (!editing) {
            draft = shown
            editing = true
        }
    }
    val editor = rememberOutsidePressEditor(active = editing) { closeEditor() }
    Box(Modifier.width(width).outsidePressPart(editor)) {
        TaskRow(
            depth = 0,
            cellId = cellId,
            renderVia = null,
            displayTitle = if (editing) draft else shown,
            isMainSelection = selected,
            isInSelectionRange = false,
            selectable = true,
            isEditing = editing,
            hasChildren = false,
            expanded = false,
            moveDropBefore = false,
            moveDropAfter = false,
            canMoveFromCell = false,
            isBeingMoved = false,
            priorityLabel = null,
            priorityColumnWidth = width - 120.dp,
            taskColor = if (editing) null else taskColor,
            searchRanges = emptyList(),
            currentSearchRange = null,
            textOverflow = false,
            minMinutes = 0,
            minTimeEditing = false,
            cellMenu = null,
            onTogglePriorityWeights = {},
            onOpenRelativePriority = {},
            onSetMinTime = {},
            onActivateMinTime = {},
            onClick = { _, _, _, _ ->
                selected = true
                openEditor()
            },
            onDragSelect = { _, _ -> },
            moveDragActive = false,
            resolveRowAt = { null },
            onRowBounds = { _, _, _ -> },
            onMoveDragStart = {},
            onMoveDropHover = { _, _, _ -> },
            onMoveDragEnd = {},
            onDoubleClick = { openEditor() },
            onTextChange = { if (editing) draft = it },
            onExitEdit = { closeEditor() },
            onToggleExpand = {},
            editMenus =
                if (editing) {
                    { _ ->
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            EditModeMenuBlock(
                                identityLabel = identityLabel,
                                identityRows = identity(draft).map { row ->
                                    EditMenuItem(label = row.label, selected = row.key == selectedKey, taskColor = row.taskColor) {
                                        // Picking IS the commit, as picking a task is in a cell.
                                        editing = false
                                        onPick(row.key)
                                    }
                                },
                                // A suggestion only fills the field, as it does in a cell.
                                suggestions = suggestions(draft).map { title -> EditMenuItem(title) { draft = title } },
                            )
                        }
                    }
                } else {
                    null
                },
            showExpandArrow = false,
            showMinTime = false,
            rowContent = trailing?.takeIf { !editing },
        )
    }
}

/** The key prefix of a [namingCreateRow]: no element's key can start with a NUL. */
private const val NAMING_CREATE_PREFIX = "\u0000create:"

/** A [NamingCell] row that names something NEW — [name] — the tree cell's "New task" row for another kind. */
internal fun namingCreateRow(name: String, label: String = "Create “$name”"): NamingRow = NamingRow(NAMING_CREATE_PREFIX + name, label)

/** The name a picked key creates, or null when [key] is an existing element's. */
internal fun namingCreatedName(key: String): String? = if (key.startsWith(NAMING_CREATE_PREFIX)) key.removePrefix(NAMING_CREATE_PREFIX) else null

/** A key drawn after a [NamingCell]'s title: what it names, in small muted text. */
@Composable
internal fun NamingKey(key: String) {
    Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * **"Add under…"** — the task edit window's Paths and the Search window's action over the added tasks: a [NamingCell]
 * whose identity rows are the places the tree's rules let the task(s) go ([candidates] for the draft); picking one is
 * [onAdd] with the place's parent task (null = top level). It holds no value: after a pick it is empty again.
 */
@Composable
internal fun AddUnderField(
    cellId: CellId,
    candidates: (draft: String) -> List<org.example.project.scheduler.domain.TaskPathsDomain.Candidate>,
    /** Every task title the draft appears in, as in a tree cell (PRD §4 Menu 2) — picking one fills the field. */
    titleSuggestions: (draft: String) -> List<String>,
    onAdd: (org.example.project.scheduler.model.TaskId?) -> Unit,
) {
    Text("Add under…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    NamingCell(
        cellId = cellId,
        shown = "",
        identityLabel = "Places",
        identity = { draft -> candidates(draft).map { NamingRow(it.parentTaskId?.value ?: TOP_LEVEL_KEY, it.label) } },
        suggestions = titleSuggestions,
        onPick = { key -> onAdd(if (key == TOP_LEVEL_KEY) null else org.example.project.scheduler.model.TaskId(key)) },
    )
}

/** The top level's key among "Add under…"'s places: an id no task is ever minted with. */
private const val TOP_LEVEL_KEY = "(top level)"
