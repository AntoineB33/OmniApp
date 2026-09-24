package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
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
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §7 *Search*: the **Configuration Search** window, opened from the Search window's configuration. It lists
 * **every configuration of the Search window** — the two the Search window shows (its search text and its
 * types) and the per-kind filters ([SearchDomain.Filters]) — in sections, one for the search as a whole and
 * one per kind of element.
 *
 * Like the Search window it is a **configuration section** above a **result section**. Its configuration has
 * a search bar that finds configurations by name, the same kind drop-down ([KindsDropDown]) that keeps only
 * the checked kinds' sections, and a button that keeps only the kinds the Search window's results actually
 * hold ([SearchDomain.kindsInResults]). Its results are the configurations themselves, each edited in place:
 * they are the Search window's own ([config]), held by `App`, so a change here narrows that window's list at
 * once.
 *
 * Its own configuration ([own]) is local-only view state, kept by `App` like the Search window's.
 */
@Composable
fun ConfigurationSearchWindow(
    state: SchedulerState,
    /** The Search window's configuration — what this window edits. */
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    /** This window's own configuration: which configurations it lists. */
    own: SearchDomain.ConfigurationSearch,
    onOwnChange: (SearchDomain.ConfigurationSearch) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
) {
    val frame = rememberWindowFrameState(CONFIGURATION_SEARCH_FRAME_ID, initialOffset, initialSize)
    // Only read when the button is on: it is a pass over the Search window's results (paths skipped).
    val resultKinds =
        if (!own.onlyResultKinds) {
            null
        } else {
            remember(
                config, state.tasks, state.taskTrees, state.cells, state.lists, state.categories, state.periodKinds,
                state.alarms, state.timers, state.chores,
            ) { SearchDomain.kindsInResults(state, config) }
        }
    val sections = SearchDomain.configurations(own.query, own.kinds, resultKinds)

    AppWindowFrame(
        title = "Search configurations",
        state = frame,
        onClose = onDismiss,
        defaultWidth = 520.dp,
        defaultHeight = 600.dp,
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
                    modifier = Modifier.weight(1f),
                )
                KindsDropDown(kinds = own.kinds, onKindsChange = { onOwnChange(own.copy(kinds = it)) })
                // This window's OWN search field and types; the Search window's configuration it lists is
                // untouched (that window has its own Reset).
                ResetButton(enabled = own.query.isNotEmpty() || own.kinds.isNotEmpty()) {
                    onOwnChange(own.copy(query = "", kinds = emptySet()))
                }
            }
            ToggleChip(
                text = "Only the types in the Search results",
                on = own.onlyResultKinds,
                onToggle = { onOwnChange(own.copy(onlyResultKinds = it)) },
            )

            HorizontalDivider()

            // --- The result section: the configurations -----------------------------------------------
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
                for ((kind, settings) in sections) {
                    Text(
                        text = kind?.label?.replaceFirstChar { it.uppercase() } ?: "General",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    for (setting in settings) {
                        SettingRow(setting.label) { SettingEditor(state, setting, config, onConfigChange) }
                    }
                }
            }
        }
    }
}

/** The frame id of the Configuration Search window — also its `FloatingWindow` name in `App`. */
const val CONFIGURATION_SEARCH_FRAME_ID: String = "ConfigSearch"

@Composable
private fun SettingRow(label: String, editor: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(130.dp),
        )
        Box(Modifier.weight(1f)) { editor() }
    }
}

/** The control of one configuration — every one of them writes the Search window's [config]. */
@Composable
private fun SettingEditor(
    state: SchedulerState,
    setting: SearchDomain.Setting,
    config: SearchDomain.Config,
    onChange: (SearchDomain.Config) -> Unit,
) {
    val f = config.filters
    fun filters(next: SearchDomain.Filters) = onChange(config.copy(filters = next))
    when (setting) {
        SearchDomain.Setting.SearchText ->
            OutlinedTextField(
                value = config.query,
                onValueChange = { onChange(config.copy(query = it)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        SearchDomain.Setting.Types ->
            Column {
                SearchDomain.Kind.entries.forEach { kind ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                onChange(config.copy(kinds = if (kind in config.kinds) config.kinds - kind else config.kinds + kind))
                            },
                    ) {
                        Checkbox(checked = kind in config.kinds, onCheckedChange = null)
                        Text(kind.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        SearchDomain.Setting.TaskInTree ->
            Choices(SearchDomain.Tri.entries, f.taskInTree, { it.label }) { filters(f.copy(taskInTree = it)) }
        SearchDomain.Setting.TaskCategory ->
            CategoryPicker(state, f.taskCategory?.let { id -> state.categoryById(id)?.title ?: "(deleted)" }) {
                filters(f.copy(taskCategory = it))
            }
        SearchDomain.Setting.CategoryHasRules ->
            Choices(SearchDomain.Tri.entries, f.categoryHasRules, { it.label }) { filters(f.copy(categoryHasRules = it)) }
        SearchDomain.Setting.PeriodOriginSetting ->
            Choices(SearchDomain.PeriodOrigin.entries, f.periodOrigin, { it.label }) { filters(f.copy(periodOrigin = it)) }
        SearchDomain.Setting.AlarmStateSetting ->
            Choices(SearchDomain.AlarmState.entries, f.alarmState, { it.label }) { filters(f.copy(alarmState = it)) }
        SearchDomain.Setting.AlarmDays ->
            DayChips(f.alarmDays) { filters(f.copy(alarmDays = it)) }
        SearchDomain.Setting.TimerStateSetting ->
            Choices(SearchDomain.TimerState.entries, f.timerState, { it.label }) { filters(f.copy(timerState = it)) }
        SearchDomain.Setting.ReminderRepeatsSetting ->
            Choices(SearchDomain.ReminderRepeats.entries, f.reminderRepeats, { it.label }) {
                filters(f.copy(reminderRepeats = it))
            }
    }
}

/** One choice among a few, as a row of chips — the first is always "any", which filters nothing. */
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option -> ToggleChip(label(option), option == selected, onToggle = { onSelect(option) }) }
    }
}

@Composable
private fun ToggleChip(text: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(shape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.primary, shape)
            .clickable { onToggle(!on) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** "any", or one of the account's categories. */
@Composable
private fun CategoryPicker(
    state: SchedulerState,
    selectedTitle: String?,
    onSelect: (org.example.project.scheduler.model.CategoryId?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = (selectedTitle ?: "any") + "  ▾",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .menuToggleClickable(open) { open = it }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        transientMenuDismissal(open) { open = false }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            DropdownMenuItem(text = { Text("any") }, onClick = { open = false; onSelect(null) })
            state.categories.sortedBy { it.title.lowercase() }.forEach { category ->
                DropdownMenuItem(text = { Text(category.title) }, onClick = { open = false; onSelect(category.id) })
            }
        }
    }
}

/** The alarm's "Rings on" filter: no day ticked = any day; else it rings on at least one ticked day. */
@Composable
private fun DayChips(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        DayOfWeek.entries.sortedBy { it.isoDayNumber }.forEach { day ->
            val on = day in days
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onChange(if (on) days - day else days + day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = day.name.take(2).lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (days.isEmpty()) {
            Text(
                "any day",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
