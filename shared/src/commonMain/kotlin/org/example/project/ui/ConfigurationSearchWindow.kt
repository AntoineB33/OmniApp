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
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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
import org.example.project.scheduler.domain.TaskRelationsDomain
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §7 *Search*: the **Configuration Search** window, opened from the Search window's configuration. It lists
 * **every configuration of the Search window** — the two the Search window shows (its search text and its
 * types), the per-kind filters ([SearchDomain.Filters]) and a **Sort by** per section, a drop-down with a
 * check box for each sorting method of that section's rows — in sections, one for the search as a whole and one
 * per kind of element.
 *
 * Like the Search window it is a **configuration section** above a **result section**. Its configuration has a
 * search bar that finds configurations by name, the same kind drop-down ([KindsDropDown]) that keeps only the
 * checked kinds' sections, and a button that keeps only the kinds the Search window's results actually hold
 * ([SearchDomain.kindsInResults]). The result section opens on the **list of sorting methods** in force
 * ([SearchDomain.Config.sorts], dominant first) — checking a method in a Sort by adds it at the bottom, dragging
 * one reorders the list, its ✕ removes it — held above the scrolling configurations, so it stays in view while
 * they are checked. The configurations are edited in place: they are the Search window's own ([config]), held
 * by `App`, so a change here narrows that window's list at once.
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
    /** The app's windows, for the window rows of the Search results ([SearchDomain.WindowEntry]). */
    windows: List<SearchDomain.WindowEntry> = emptyList(),
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
                state.alarms, state.timers, state.chronos, state.chores, windows,
            ) { SearchDomain.kindsInResults(state, config, windows) }
        }
    val sections = SearchDomain.configurations(own.query, own.kinds, resultKinds, config.filters.takeIf { own.showFiltersOn })

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(
                    text = "Only the types in the Search results",
                    on = own.onlyResultKinds,
                    onToggle = { onOwnChange(own.copy(onlyResultKinds = it)) },
                )
                // A filter that empties its own type out of the results would vanish with it under the button
                // beside; this keeps every filter that is on in view, so it can be set off right after.
                ToggleChip(
                    text = "Show the filters that are on",
                    on = own.showFiltersOn,
                    onToggle = { onOwnChange(own.copy(showFiltersOn = it)) },
                )
            }

            HorizontalDivider()

            // --- The result section: the sorting methods in force, then the configurations -------------
            SortMethodList(config.sorts) { onConfigChange(config.copy(sorts = it)) }
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
        // The Search window's own type selector — a field with a drop-down — not a second drawing of it.
        SearchDomain.Setting.Types ->
            KindsDropDown(kinds = config.kinds, onKindsChange = { onChange(config.copy(kinds = it)) })
        // The Search window's Reset, the same button with the same rule; this window's own Reset (top section)
        // clears this window's search instead.
        SearchDomain.Setting.ResetSearch ->
            ResetButton(enabled = config.query.isNotEmpty() || config.kinds.isNotEmpty()) {
                onChange(config.copy(query = "", kinds = emptySet()))
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
        SearchDomain.Setting.ChronoStateSetting ->
            Choices(SearchDomain.TimerState.entries, f.chronoState, { it.label }) { filters(f.copy(chronoState = it)) }
        SearchDomain.Setting.ReminderRepeatsSetting ->
            Choices(SearchDomain.ReminderRepeats.entries, f.reminderRepeats, { it.label }) {
                filters(f.copy(reminderRepeats = it))
            }
        SearchDomain.Setting.HistoryCategorySetting ->
            EnumPicker(HistoryCategory.entries, f.historyCategory, { historyCategoryLabel(it) }) {
                filters(f.copy(historyCategory = it))
            }
        SearchDomain.Setting.HistoryWindowSetting ->
            EnumPicker(HistoryWindow.entries, f.historyWindow, { it.label }) { filters(f.copy(historyWindow = it)) }
        SearchDomain.Setting.HistoryUndoneSetting ->
            Choices(SearchDomain.Tri.entries, f.historyUndone, { it.label }) { filters(f.copy(historyUndone = it)) }
        SearchDomain.Setting.TaskTreeOpenSetting ->
            Choices(SearchDomain.Tri.entries, f.taskTreeOpen, { it.label }) { filters(f.copy(taskTreeOpen = it)) }
        SearchDomain.Setting.TaskTreeDatedSetting ->
            Choices(SearchDomain.Tri.entries, f.taskTreeDated, { it.label }) { filters(f.copy(taskTreeDated = it)) }
        SearchDomain.Setting.RelationSectionSetting ->
            EnumPicker(TaskRelationsDomain.Section.entries, f.relationSection, { SearchDomain.relationSectionLabel(it) }) {
                filters(f.copy(relationSection = it))
            }
        SearchDomain.Setting.ShortcutReboundSetting ->
            Choices(SearchDomain.Tri.entries, f.shortcutRebound, { it.label }) { filters(f.copy(shortcutRebound = it)) }
        SearchDomain.Setting.WindowStatusSetting ->
            EnumPicker(SearchDomain.WindowStatus.entries, f.windowStatus, { it.label }) { filters(f.copy(windowStatus = it)) }
        SearchDomain.Setting.SortResults,
        SearchDomain.Setting.TaskSort, SearchDomain.Setting.CategorySort, SearchDomain.Setting.PeriodSort,
        SearchDomain.Setting.AlarmSort, SearchDomain.Setting.TimerSort, SearchDomain.Setting.ChronoSort,
        SearchDomain.Setting.ReminderSort,
        SearchDomain.Setting.HistorySort, SearchDomain.Setting.TaskTreeSort, SearchDomain.Setting.RelationSort,
        SearchDomain.Setting.ShortcutSort, SearchDomain.Setting.WindowSort,
        -> SortMethodPicker(setting.section, config.sorts) { onChange(config.copy(sorts = it)) }
    }
}

/**
 * A Sort by: a drop-down with a check box per sorting method of [kind]'s rows (null = every row), after a
 * [SelectAllMenuItem]. Checking one adds it at the bottom of [sorts]; unchecking removes it. The menu stays open, so several can be checked in
 * turn, each landing below the last.
 */
@Composable
private fun SortMethodPicker(
    kind: SearchDomain.Kind?,
    sorts: List<SearchDomain.SortMethod>,
    onChange: (List<SearchDomain.SortMethod>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val methods = SearchDomain.sortKeysOf(kind).map { SearchDomain.SortMethod(kind, it) }
    fun inList(method: SearchDomain.SortMethod) = sorts.any { it.sameMethod(method) }
    Box {
        Text(
            text = methods.filter(::inList).joinToString(", ") { it.key.label }.ifEmpty { "none" } + "  ▾",
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
            // Checking them all adds the unchecked ones at the bottom, in the menu's order — what checking each
            // in turn would do; unchecking them all takes this section's methods out and leaves the others'.
            SelectAllMenuItem(
                allChecked = methods.all(::inList),
                onSelectAll = { onChange(methods.fold(sorts) { list, m -> if (inList(m)) list else SearchDomain.withSortMethod(list, m, on = true) }) },
                onDeselectAll = { onChange(methods.fold(sorts) { list, m -> SearchDomain.withSortMethod(list, m, on = false) }) },
            )
            methods.forEach { method ->
                val checked = inList(method)
                DropdownMenuItem(
                    text = { Text(method.key.label) },
                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                    onClick = { onChange(SearchDomain.withSortMethod(sorts, method, on = !checked)) },
                )
            }
        }
    }
}

/**
 * The sorting methods in force, dominant first: each row can be dragged to another place, its direction
 * flipped, and removed with its ✕. The dragged row follows the pointer and lands where it is released, the rows
 * it passed over each moving one place (`SearchDomain.movedSortMethod`).
 */
@Composable
private fun SortMethodList(sorts: List<SearchDomain.SortMethod>, onChange: (List<SearchDomain.SortMethod>) -> Unit) {
    val current by rememberUpdatedState(sorts)
    val change by rememberUpdatedState(onChange)
    var dragged by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    // One row's height plus the gap between rows: what a drag must cover to pass one row.
    var pitch by remember { mutableStateOf(1f) }
    val gap = 4.dp
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
        Text("Sort by — most dominant first", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (sorts.isEmpty()) {
            Text(
                text = "No sorting method: the results keep their default order. Check one in a Sort by below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sorts.forEachIndexed { index, method ->
            val isDragged = dragged == index
            key(method.kind, method.key) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragY else 0f }
                        .onSizeChanged { pitch = it.height + gapPx }
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isDragged) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .pointerInput(method.kind, method.key) {
                            detectDragGestures(
                                onDragStart = {
                                    dragged = current.indexOfFirst { it.sameMethod(method) }
                                    dragY = 0f
                                },
                                onDragEnd = {
                                    val from = dragged
                                    if (from != null) {
                                        val to = (from + (dragY / pitch).roundToInt()).coerceIn(0, current.lastIndex)
                                        if (to != from) change(SearchDomain.movedSortMethod(current, from, to))
                                    }
                                    dragged = null
                                    dragY = 0f
                                },
                                onDragCancel = { dragged = null; dragY = 0f },
                            ) { pointer, amount ->
                                pointer.consume()
                                dragY += amount.y
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("⠿", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${index + 1}. ${method.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ToggleChip(
                        text = if (method.descending) "↓ descending" else "↑ ascending",
                        on = method.descending,
                        onToggle = { down ->
                            onChange(sorts.map { if (it.sameMethod(method)) it.copy(descending = down) else it })
                        },
                    )
                    Text(
                        text = "✕",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onChange(SearchDomain.withSortMethod(sorts, method, on = false)) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
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

/** A history stack as the History window names it. */
private fun historyCategoryLabel(category: HistoryCategory): String =
    when (category) {
        HistoryCategory.Edit -> "edit"
        HistoryCategory.Selection -> "selection"
        HistoryCategory.Calendar -> "calendar"
        HistoryCategory.Main -> "main"
        HistoryCategory.WindowNav -> "window navigation"
    }

/** "any", or one of [options] — a drop-down, for the choices too many to lay out as chips. */
@Composable
private fun <T> EnumPicker(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = (selected?.let(label) ?: "any") + "  ▾",
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
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { open = false; onSelect(option) })
            }
        }
    }
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
