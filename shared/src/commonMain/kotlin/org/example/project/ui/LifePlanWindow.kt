package org.example.project.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import org.example.project.scheduler.domain.LifePlanDomain
import org.example.project.scheduler.domain.LifePlanEntry
import org.example.project.scheduler.domain.LifePlanSection
import org.example.project.scheduler.domain.LifePlanStepRow
import org.example.project.scheduler.domain.LifePlanView

/** The window's frame id: [org.example.project.FloatingWindow]'s name for it. */
const val LIFE_PLAN_WINDOW_ID: String = "LifePlan"

/**
 * **What a field being typed in shows**, against what the state holds. Typing is told to the state, which answers a frame
 * later: read straight, that answer would put an older text back under the hand, so a text this field sent itself is
 * ignored when it comes back, and any other one — an undo, a sync — is what the field shows next (`popups.md`: a field
 * re-reads a value written from another place, never under the hand typing it).
 */
internal class LiveFieldText(initial: String) {
    var value: TextFieldValue by mutableStateOf(TextFieldValue(initial))
        private set

    private val sent = ArrayDeque<String>()

    /** The user's edit. [onChange] hears the text, not a move of the caret. */
    fun typed(next: TextFieldValue, onChange: (String) -> Unit) {
        val changed = next.text != value.text
        value = next
        if (!changed) return
        sent.addLast(next.text)
        if (sent.size > MAX_UNANSWERED) sent.removeFirst()
        onChange(next.text)
    }

    /** What the state holds now. */
    fun stored(text: String) {
        val answered = sent.indexOf(text)
        if (answered >= 0) {
            repeat(answered + 1) { sent.removeFirst() }
        } else if (text != value.text) {
            sent.clear()
            value = TextFieldValue(text, TextRange(minOf(value.selection.start, text.length)))
        }
    }

    private companion object {
        const val MAX_UNANSWERED = 64
    }
}

@Composable
private fun rememberLiveFieldText(key: Any, stored: String): LiveFieldText {
    val live = remember(key) { LiveFieldText(stored) }
    LaunchedEffect(live, stored) { live.stored(stored) }
    return live
}

/**
 * User rule 2026-11-02: **the life plan**, a window of the Schedule page's lateral menu. A drop-down picks what it shows
 * ([LifePlanSection]): "Steps", a list like the Search window's result list whose rows can hold sub-elements under an
 * expansion arrow; "Problems", a place to write, in tabs chosen, deleted and added from a field with a drop-down. What is
 * written is the account's ([LifePlanEntry]); how the window is looked at is this device's ([LifePlanView]).
 */
@Composable
internal fun LifePlanWindow(
    entries: List<LifePlanEntry>,
    view: LifePlanView,
    onViewChange: (LifePlanView) -> Unit,
    /** The plan as it now stands; [editKey] names the field typed into, so a run of keys is one History Unit. */
    onEntriesChange: (entries: List<LifePlanEntry>, editKey: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
) {
    val frame = rememberWindowFrameState(LIFE_PLAN_WINDOW_ID, initialOffset, initialSize)
    val section = LifePlanSection.of(view.section)
    AppWindowFrame(
        title = "Life plan",
        state = frame,
        onClose = onDismiss,
        defaultWidth = 560.dp,
        defaultHeight = 620.dp,
        modifier = modifier,
        onRaise = onRaise,
        onGeometryChange = onGeometryChange,
        // Its fields are typed into: the tree behind must not take the keys.
        claimsKeyboard = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionField(section) { onViewChange(view.copy(section = it.name)) }
            when (section) {
                LifePlanSection.Steps -> StepsSection(entries, view, onViewChange, onEntriesChange)
                LifePlanSection.Problems -> ProblemsSection(entries, view, onViewChange, onEntriesChange)
                // Named and not yet defined: nothing is stored for them.
                LifePlanSection.CareerEvolution, LifePlanSection.CrisisResilience ->
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Hint("Nothing here yet.") }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The field choosing what the window shows. */
@Composable
private fun SectionField(section: LifePlanSection, onPick: (LifePlanSection) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Text(
            text = section.label + "  ▾",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .menuToggleClickable(open) { open = it }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        transientMenuDismissal(open) { open = false }
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            for (choice in LifePlanSection.entries) {
                DropdownMenuItem(text = { Text(choice.label) }, onClick = { open = false; onPick(choice) })
            }
        }
    }
}

@Composable
private fun ColumnScope.StepsSection(
    entries: List<LifePlanEntry>,
    view: LifePlanView,
    onViewChange: (LifePlanView) -> Unit,
    onEntriesChange: (List<LifePlanEntry>, String?) -> Unit,
) {
    val expanded = remember(view.expanded) { view.expanded.toSet() }
    val rows = remember(entries, expanded) { LifePlanDomain.stepRows(entries, expanded) }
    val list = rememberLazyListState()
    // The step just added: it is brought into view and its title takes the keyboard.
    var focusId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusId, rows) {
        val at = rows.indexOfFirst { it.step.id == focusId }
        if (at >= 0) list.scrollToItem(at)
    }
    fun add(parent: String?) {
        val (next, id) = LifePlanDomain.addedStep(entries, parent)
        if (parent != null && parent !in expanded) onViewChange(view.copy(expanded = view.expanded + parent))
        onEntriesChange(next, null)
        focusId = id
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
        if (rows.isEmpty()) Hint("No step yet.")
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.step.id }) { row ->
                StepRow(
                    row = row,
                    focused = row.step.id == focusId,
                    onFocused = { focusId = null },
                    onToggle = {
                        val id = row.step.id
                        onViewChange(view.copy(expanded = if (row.expanded) view.expanded - id else view.expanded + id))
                    },
                    onTitle = { onEntriesChange(LifePlanDomain.titled(entries, row.step.id, it), "life-plan/" + row.step.id) },
                    onAddChild = { add(row.step.id) },
                    onRemove = { onEntriesChange(LifePlanDomain.removed(entries, row.step.id), null) },
                )
            }
        }
        ListScrollbar(list, Modifier.align(Alignment.CenterEnd))
    }
    TextButton(onClick = { add(null) }) { Text("+ New step") }
}

/** A row of the list, as tall and as wide as a row of the Search window's. Its title is typed straight into. */
@Composable
private fun StepRow(
    row: LifePlanStepRow,
    focused: Boolean,
    onFocused: () -> Unit,
    onToggle: () -> Unit,
    onTitle: (String) -> Unit,
    onAddChild: () -> Unit,
    onRemove: () -> Unit,
) {
    val live = rememberLiveFieldText(row.step.id, row.step.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(focused) {
        if (focused) {
            runCatching { focus.requestFocus() }
            onFocused()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = resultRowModifier(selected = false).padding(start = STEP_INDENT * row.depth),
    ) {
        // The box is there with or without sub-elements, so the titles of one level line up.
        TaskSheetExpandArrow(row.hasChildren, row.expanded, onToggle, MaterialTheme.colorScheme.primary)
        Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            if (live.value.text.isEmpty()) Hint("Step")
            BasicTextField(
                value = live.value,
                onValueChange = { live.typed(it, onTitle) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(CalColors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).leavesEditOnEnter(),
            )
        }
        RowButton("+", onAddChild)
        RowButton("🗑", onRemove)
    }
}

/** A button a row of the list carries: on every platform, a phone having no right-click. */
@Composable
private fun RowButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ColumnScope.ProblemsSection(
    entries: List<LifePlanEntry>,
    view: LifePlanView,
    onViewChange: (LifePlanView) -> Unit,
    onEntriesChange: (List<LifePlanEntry>, String?) -> Unit,
) {
    val tabs = remember(entries) { LifePlanDomain.tabs(entries) }
    val tab = remember(entries, view.tab) { LifePlanDomain.tabOf(entries, view.tab) }
    TabField(
        tab = tab,
        tabs = tabs,
        onPick = { onViewChange(view.copy(tab = it)) },
        onTitle = { id, title -> onEntriesChange(LifePlanDomain.titled(entries, id, title), "life-plan/$id") },
        onRemove = { onEntriesChange(LifePlanDomain.removed(entries, it), null) },
        onAdd = {
            val (next, id) = LifePlanDomain.addedTab(entries)
            onEntriesChange(next, null)
            onViewChange(view.copy(tab = id))
        },
    )
    if (tab == null) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Hint("No tab yet: add one from the field above.") }
    } else {
        val stored = remember(entries, tab.id) { LifePlanDomain.textOf(entries, tab.id) }
        val live = rememberLiveFieldText(tab.id, stored)
        // One History Unit per stay in the field.
        var stay by remember(tab.id) { mutableIntStateOf(0) }
        OutlinedTextField(
            value = live.value,
            onValueChange = { typed -> live.typed(typed) { onEntriesChange(LifePlanDomain.withText(entries, tab.id, it), "life-plan/${tab.id}/$stay") } },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onFocusChanged { if (it.isFocused) stay++ },
        )
    }
}

/**
 * The field of the open tab: its title, typed into, over a drop-down of every tab with its bin and, last, the one that adds
 * one. A bin deletes the tab and what is written in it, one History Unit.
 */
@Composable
private fun TabField(
    tab: LifePlanEntry?,
    tabs: List<LifePlanEntry>,
    onPick: (String) -> Unit,
    onTitle: (id: String, title: String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val live = rememberLiveFieldText(tab?.id ?: "", tab?.text.orEmpty())
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = live.value,
            onValueChange = { typed -> tab?.let { t -> live.typed(typed) { onTitle(t.id, it) } } },
            readOnly = tab == null,
            singleLine = true,
            label = { Text("Tab") },
            trailingIcon = { Text("▾", Modifier.menuToggleClickable(open) { open = it }.padding(10.dp)) },
            modifier = Modifier.fillMaxWidth(),
        )
        transientMenuDismissal(open) { open = false }
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            Column(Modifier.width(280.dp)) {
                for (each in tabs) {
                    DropdownMenuItem(
                        text = { Text(each.text.ifBlank { "(untitled)" }) },
                        onClick = { open = false; onPick(each.id) },
                        trailingIcon = { TextButton(onClick = { open = false; onRemove(each.id) }) { Text("🗑") } },
                    )
                }
                if (tabs.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(text = { Text("+ New tab") }, onClick = { open = false; onAdd() })
            }
        }
    }
}

/** How far each level of sub-elements is indented. */
private val STEP_INDENT = 18.dp
