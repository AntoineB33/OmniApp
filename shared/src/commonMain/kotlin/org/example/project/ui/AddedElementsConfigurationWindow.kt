package org.example.project.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §7 *Search*: the **Added elements configurations** window, opened from the Search window's top right
 * quarter. It is the Configuration Search window's twin for the Search window's **added elements**: where that
 * one lists every configuration of the search, this one lists every action on the added elements
 * ([SearchDomain.AddedAction]) — the general ones and one section per kind — with the same configuration section
 * above them: a bar that finds an action by name, the kind drop-down ([KindsDropDown]) and a button that keeps
 * only the kinds the added list holds. The actions act on the Search window's added list ([added]) at once.
 *
 * Its own configuration ([own]) is a [SearchDomain.ConfigurationSearch], local-only view state kept by `App`
 * like the Configuration Search window's ([SearchDomain.ConfigurationSearch.onlyResultKinds] reads "only the
 * types in the added elements" here).
 */
@Composable
fun AddedElementsConfigurationWindow(
    state: SchedulerState,
    /** The Search window's added elements, resolved ([SearchDomain.resolve]) — what every action acts on. */
    added: List<SearchDomain.Result>,
    own: SearchDomain.ConfigurationSearch,
    onOwnChange: (SearchDomain.ConfigurationSearch) -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
    nowMillis: () -> Long,
    onOpenEach: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
) {
    val frame = rememberWindowFrameState(ADDED_CONFIGURATION_FRAME_ID, initialOffset, initialSize)
    val addedKinds = added.mapTo(HashSet()) { it.kind }
    val sections = SearchDomain.addedActions(own.query, own.kinds, addedKinds.takeIf { own.onlyResultKinds })

    AppWindowFrame(
        title = "Added elements configurations",
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
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // --- The configuration ------------------------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = own.query,
                    onValueChange = { onOwnChange(own.copy(query = it)) },
                    singleLine = true,
                    label = { Text("Search a configuration") },
                    modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
                )
                KindsDropDown(kinds = own.kinds, onKindsChange = { onOwnChange(own.copy(kinds = it)) })
                ResetButton(enabled = own.query.isNotEmpty() || own.kinds.isNotEmpty()) {
                    onOwnChange(own.copy(query = "", kinds = emptySet()))
                }
            }
            ToggleChip(
                text = "Only the types in the added elements",
                on = own.onlyResultKinds,
                onToggle = { onOwnChange(own.copy(onlyResultKinds = it)) },
            )

            HorizontalDivider()

            // --- The actions ------------------------------------------------------------------------
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (sections.isEmpty()) {
                    Text(
                        text = "No configuration matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AddedActionSections(state, sections, added, onIntent, nowMillis, onOpenEach, onClear)
            }
        }
    }
}

/** The frame id of the Added elements configurations window — also its `FloatingWindow` name in `App`. */
const val ADDED_CONFIGURATION_FRAME_ID: String = "AddedConfig"

/**
 * The Search window's top right quarter: the actions on every added element, for the kinds the list holds (the
 * window of them all is a button away, as the Configuration Search window is from the search).
 */
@Composable
internal fun AddedActionsSection(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    onIntent: (SchedulerIntent) -> Unit,
    nowMillis: () -> Long,
    onOpenEach: () -> Unit,
    onClear: () -> Unit,
    onOpenConfigurations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Actions on the added elements",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            FrameButton("⚙ All configurations", onClick = onOpenConfigurations)
        }
        if (added.isEmpty()) {
            Text(
                text = "Nothing is added yet: the actions act on every added element at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val sections = SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), added.mapTo(HashSet()) { it.kind })
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AddedActionSections(state, sections, added, onIntent, nowMillis, onOpenEach, onClear)
        }
    }
}

/** The sections of actions, each under its kind's title — one drawing for the quarter and for the window. */
@Composable
private fun AddedActionSections(
    state: SchedulerState,
    sections: List<Pair<SearchDomain.Kind?, List<SearchDomain.AddedAction>>>,
    added: List<SearchDomain.Result>,
    onIntent: (SchedulerIntent) -> Unit,
    nowMillis: () -> Long,
    onOpenEach: () -> Unit,
    onClear: () -> Unit,
) {
    // Every action goes through the app's own intents ([SearchDomain.addedIntents]).
    val run = { command: SearchDomain.AddedCommand ->
        SearchDomain.addedIntents(state, added, command, nowMillis()).forEach(onIntent)
    }
    for ((kind, actions) in sections) {
        val count = added.count { kind == null || it.kind == kind }
        Text(
            text = (kind?.label?.replaceFirstChar { it.uppercase() } ?: "Every element") + "  ·  $count",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        for (action in actions) {
            SettingRow(action.label) {
                AddedActionEditor(state, action, added, run, onOpenEach, onClear)
            }
        }
    }
}

/** The control of one action — every one of them acts on the added elements of its kind. */
@Composable
private fun AddedActionEditor(
    state: SchedulerState,
    action: SearchDomain.AddedAction,
    added: List<SearchDomain.Result>,
    run: (SearchDomain.AddedCommand) -> Unit,
    onOpenEach: () -> Unit,
    onClear: () -> Unit,
) {
    val tasks = added.filterIsInstance<SearchDomain.TaskResult>().mapNotNull { state.tasks[it.taskId] }
    fun idsOf(kind: SearchDomain.Kind) =
        added.filterIsInstance<SearchDomain.ItemResult>().filter { it.kind == kind }.mapTo(HashSet()) { it.id }
    when (action) {
        SearchDomain.AddedAction.OpenEach -> FrameButton("Open ${added.size}", enabled = added.isNotEmpty(), onClick = onOpenEach)
        SearchDomain.AddedAction.ClearList -> FrameButton("Remove all", enabled = added.isNotEmpty(), onClick = onClear)
        SearchDomain.AddedAction.TaskAddCategory ->
            CategoryChooser(
                options = state.categories.sortedBy { it.title.lowercase() }.map { it.id to it.title },
                enabled = tasks.isNotEmpty(),
            ) { run(SearchDomain.AddedCommand.Category(it, carried = true)) }
        // Only the categories an added task carries: the others have nothing to take off.
        SearchDomain.AddedAction.TaskRemoveCategory -> {
            val carried = tasks.flatMapTo(HashSet()) { it.categoryIds }
            CategoryChooser(
                options = state.categories.filter { it.id in carried }.sortedBy { it.title.lowercase() }.map { it.id to it.title },
                enabled = carried.isNotEmpty(),
            ) { run(SearchDomain.AddedCommand.Category(it, carried = false)) }
        }
        SearchDomain.AddedAction.TaskMinimumTime -> {
            // The value the tasks share, when they share one, is what the field starts from.
            val shared = tasks.map { it.minimumMinutes }.distinct().singleOrNull()
            var draft by remember(shared) { mutableStateOf(shared?.toString().orEmpty()) }
            val minutes = draft.trim().toIntOrNull()?.takeIf { it >= 0 }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { typed -> draft = typed.filter { it.isDigit() }.take(5) },
                    singleLine = true,
                    placeholder = { Text(if (shared == null && tasks.isNotEmpty()) "mixed" else "min") },
                    modifier = Modifier.width(110.dp),
                )
                Text("min", style = MaterialTheme.typography.bodyMedium)
                FrameButton("Apply", enabled = minutes != null && tasks.any { it.minimumMinutes != minutes }) {
                    minutes?.let { run(SearchDomain.AddedCommand.MinimumTime(it)) }
                }
            }
        }
        SearchDomain.AddedAction.AlarmOnOff -> {
            val ids = idsOf(SearchDomain.Kind.Alarm)
            // Lit when every added alarm is in that state; neither chip when they differ.
            val shared = state.alarms.filter { it.id in ids }.map { it.enabled }.distinct().singleOrNull()
            Choices(listOf(true, false), shared, { if (it == true) "on" else "off" }) { run(SearchDomain.AddedCommand.AlarmsOn(it == true)) }
        }
        SearchDomain.AddedAction.TimerRun ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SearchDomain.RunStep.entries.forEach { step ->
                    FrameButton(step.label) { run(SearchDomain.AddedCommand.TimersRun(step)) }
                }
            }
        SearchDomain.AddedAction.ChronoRun ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SearchDomain.RunStep.entries.forEach { step ->
                    FrameButton(step.label) { run(SearchDomain.AddedCommand.ChronosRun(step)) }
                }
            }
    }
}

/** A drop-down of categories that acts on the one picked — it holds no value of its own ("choose…"). */
@Composable
private fun CategoryChooser(options: List<Pair<CategoryId, String>>, enabled: Boolean, onPick: (CategoryId) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val usable = enabled && options.isNotEmpty()
    val color = if (usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Text(
            text = (if (options.isEmpty()) "no category" else "choose…") + "  ▾",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .then(if (usable) Modifier.menuToggleClickable(open) { open = it } else Modifier)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        transientMenuDismissal(open) { open = false }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

/**
 * A framed text button — the look of the Search window's "⚙ All configurations" — greyed and inert while
 * [enabled] is false.
 */
@Composable
internal fun FrameButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (enabled) MaterialTheme.colorScheme.outlineVariant else color, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
