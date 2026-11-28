package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.CategoryRules
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.TaskRelationsDomain
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TASK_ROW_MIN_HEIGHT
import org.example.project.scheduler.ui.TaskRow

/**
 * A filter of the Claude Code logs ([SearchDomain.Filters.claudeProject], [SearchDomain.Filters.claudeFrom],
 * [SearchDomain.Filters.claudeUntil]) — the one editor of each, whichever of the three kinds lists it.
 */
@Composable
private fun ClaudeFilterEditor(
    setting: SearchDomain.Setting,
    f: SearchDomain.Filters,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
    filters: (SearchDomain.Filters) -> Unit,
) {
    when (setting) {
        SearchDomain.Setting.PromptProject, SearchDomain.Setting.AnswerProject, SearchDomain.Setting.ReasoningProject ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceDropDown("ClaudeFilterEditor",
                    options = claudeLogs.projects,
                    selected = f.claudeProject,
                    // By the last folder, as a row names it: whole paths all read the same in the field's width.
                    label = SearchDomain::projectName,
                    onSelect = { filters(f.copy(claudeProject = it)) },
                    placeholder = "any project",
                )
                if (f.claudeProject != null) ClearMark { filters(f.copy(claudeProject = null)) }
            }
        SearchDomain.Setting.PromptFrom, SearchDomain.Setting.AnswerFrom, SearchDomain.Setting.ReasoningFrom ->
            BoxBoundField(f.claudeFrom, { filters(f.copy(claudeFrom = it)) }, onPick = null)
        SearchDomain.Setting.PromptUntil, SearchDomain.Setting.AnswerUntil, SearchDomain.Setting.ReasoningUntil ->
            BoxBoundField(f.claudeUntil, { filters(f.copy(claudeUntil = it)) }, onPick = null)
        else -> Unit
    }
}

/** The frame id of the Configuration Search window — also its `FloatingWindow` name in `App`. */
const val CONFIGURATION_SEARCH_FRAME_ID: String = "ConfigSearch"

/** How far below a row "Where to search › Children" may look: 1 (its children) to this. */
private const val CHILD_DISTANCE_CHOICES = 5

/** How wide a configuration's naming cell is (a category, a task, a changed element). */
private val SETTING_CELL_WIDTH = 280.dp

/** How wide a day-and-time field of a configuration is: `YYYY-MM-DD HH:MM`, and no more. */
private val SETTING_INSTANT_WIDTH = 150.dp

/** The control of one configuration — every one of them writes the Search window's [config]. */
@Composable
internal fun SettingEditor(
    state: SchedulerState,
    setting: SearchDomain.Setting,
    config: SearchDomain.Config,
    openedConfig: SearchDomain.Config?,
    onChange: (SearchDomain.Config) -> Unit,
    /** The app's windows: the "Changed element" field names a window by them. */
    windows: List<SearchDomain.WindowEntry> = emptyList(),
    /** A bound of the boxes' window is to be picked by a click on the calendar ([BoxBoundField]). */
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit = {},
    /** The Claude Code logs as last read: the projects their filter offers. */
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog = org.example.project.claudelogs.ClaudeLogCatalog.EMPTY,
) {
    val f = config.filters
    fun filters(next: SearchDomain.Filters) = onChange(config.copy(filters = next))
    when (setting) {
        SearchDomain.Setting.PromptProject, SearchDomain.Setting.PromptFrom, SearchDomain.Setting.PromptUntil,
        SearchDomain.Setting.AnswerProject, SearchDomain.Setting.AnswerFrom, SearchDomain.Setting.AnswerUntil,
        SearchDomain.Setting.ReasoningProject, SearchDomain.Setting.ReasoningFrom, SearchDomain.Setting.ReasoningUntil,
        -> ClaudeFilterEditor(setting, f, claudeLogs, ::filters)
        // The window's own bar, here too: the one search bar of the app ([SearchBar]).
        SearchDomain.Setting.SearchText ->
            SearchBar(config.query, { onChange(config.copy(query = it)) }, "Search", modifier = Modifier.fillMaxWidth().leaveFocusOnOutsidePress())
        // The Search window's own type selector — a field with a drop-down — not a second drawing of it.
        SearchDomain.Setting.Types ->
            KindsDropDown(setting.name, kinds = config.kinds, onKindsChange = { onChange(config.copy(kinds = it)) }, options = config.offeredKinds)
        // User rule 2026-11-04: the search bar's two filters, also in its right-click menu.
        SearchDomain.Setting.MatchCase ->
            CompactSwitch(checked = f.matchCase, onCheckedChange = { filters(f.copy(matchCase = it)) })
        SearchDomain.Setting.WholeWord ->
            CompactSwitch(checked = f.wholeWord, onCheckedChange = { filters(f.copy(wholeWord = it)) })
        // User rule 2026-11-16, "Where to search": what of a row, and of its children, the bars are matched against.
        SearchDomain.Setting.SearchInId, SearchDomain.Setting.SearchInName, SearchDomain.Setting.SearchInDescription,
        SearchDomain.Setting.SearchInButtons, SearchDomain.Setting.SearchInChildren, SearchDomain.Setting.ChildSearchInId,
        SearchDomain.Setting.ChildSearchInName, SearchDomain.Setting.ChildSearchInDescription, SearchDomain.Setting.ChildSearchInButtons,
        SearchDomain.Setting.SearchInFacts, SearchDomain.Setting.ChildSearchInFacts,
        -> CompactSwitch(checked = f.searches(setting) == true, onCheckedChange = { filters(f.withSearches(setting, it)) })
        SearchDomain.Setting.ChildDistance ->
            Choices(
                listOf(SearchDomain.CHILD_DISTANCE_ANY) + (1..CHILD_DISTANCE_CHOICES),
                f.childDistance,
                { if (it == SearchDomain.CHILD_DISTANCE_ANY) "any" else it.toString() },
            ) { filters(f.copy(childDistance = it)) }
        // User rule 2026-11-06: the bars hold regular expressions.
        SearchDomain.Setting.Regex ->
            CompactSwitch(checked = f.regex, onCheckedChange = { filters(f.copy(regex = it)) })
        // User rule 2026-11-06: the search bars added to the window's own, and the formula putting them together.
        SearchDomain.Setting.SearchBars -> SearchBarsEditor(config, onChange)
        // User rule 2026-11-04: on, the type selector offers the window's page's types alone ([SearchDomain.Config.pageTypesOnly]).
        SearchDomain.Setting.PageTypes ->
            CompactSwitch(checked = config.pageTypesOnly, onCheckedChange = { onChange(config.copy(pageTypesOnly = it)) })
        // User rule 2026-11-12: how the results are laid out — today the actions section's ([SearchDomain.Config.actionsGrouped]…).
        SearchDomain.Setting.GroupByAddedElements ->
            CompactSwitch(checked = config.actionsGrouped, onCheckedChange = { onChange(config.copy(actionsGrouped = it)) })
        SearchDomain.Setting.ExpandResults ->
            CompactSwitch(checked = config.actionsExpanded, onCheckedChange = { onChange(config.copy(actionsExpanded = it)) })
        // User rule 2026-11-27: the rows laid out as a tree to navigate ([SearchDomain.Config.navigationTree]).
        SearchDomain.Setting.NavigationTree ->
            CompactSwitch(checked = config.navigationTree, onCheckedChange = { onChange(config.copy(navigationTree = it)) })
        // User rule 2026-11-12: a button on every element of the list to drop it.
        SearchDomain.Setting.DropButtons ->
            CompactSwitch(checked = config.dropButtons, onCheckedChange = { onChange(config.copy(dropButtons = it)) })
        SearchDomain.Setting.Entries ->
            EntriesEditor(config.entries) { onChange(config.copy(entries = it)) }
        SearchDomain.Setting.RightClickChecksAlone ->
            CompactSwitch(checked = config.rightClickChecksAlone, onCheckedChange = { onChange(config.copy(rightClickChecksAlone = it)) })
        // User rule 2026-11-27: the size a window of the context opens at.
        SearchDomain.Setting.Size ->
            SizeEditor(config.size) { onChange(config.copy(size = it)) }
        SearchDomain.Setting.Separators ->
            CompactSwitch(checked = config.separators, onCheckedChange = { onChange(config.copy(separators = it)) })
        SearchDomain.Setting.ButtonFollowsExpansion ->
            CompactSwitch(checked = config.buttonFollowsExpansion, onCheckedChange = { onChange(config.copy(buttonFollowsExpansion = it)) })
        SearchDomain.Setting.SearchSortsOnly ->
            CompactSwitch(checked = config.actionSearchSortsOnly, onCheckedChange = { onChange(config.copy(actionSearchSortsOnly = it)) })
        SearchDomain.Setting.MenuCustomizing ->
            CompactSwitch(checked = config.menuCustomizing, onCheckedChange = { onChange(config.copy(menuCustomizing = it)) })
        // User rule 2026-11-22: what a task row shows, and in which order.
        SearchDomain.Setting.TaskRowLayout ->
            TaskRowLayoutEditor(config.taskRowLayout) { onChange(config.copy(taskRowLayout = it)) }
        // The Search window's Reset, the same button with the same rule; this window's own Reset (top section)
        // clears this window's search instead.
        SearchDomain.Setting.ResetSearch -> {
            val reset = SearchDomain.resetConfig(config, openedConfig)
            ResetButton(enabled = reset != config) { onChange(reset) }
        }
        // "any" is no list: it does not appear by default (user rule 2026-11-02).
        SearchDomain.Setting.CompletionList ->
            EnumPicker(setting.name, SearchDomain.Completion.entries, config.completion, { it.label }) { onChange(config.copy(completion = it)) }
        // The calendar filter: its three states (user rule 2026-10-07 — what can be added without removing anything
        // where the user right-clicked, what can be added, or no filter), the instant, and — only for a window the
        // calendar's "add…" opened — the button that puts the instant back on that right-click.
        SearchDomain.Setting.CalendarAdd ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                var statesOpen by remember { mutableStateOf(false) }
                androidx.compose.foundation.layout.Box {
                    FrameButton(f.calendarAddFilter.label + " ▾") { statesOpen = true }
                    // User rule 2026-11-12: the list is a Search window ([OptionSearchMenu]).
                    OptionSearchMenu(setting.name,
                        open = statesOpen,
                        onDismiss = { statesOpen = false },
                        options = choiceOptions(SearchDomain.CalendarAddFilter.entries, { (if (it == f.calendarAddFilter) "● " else "○ ") + it.label }) { choice ->
                            statesOpen = false
                            filters(f.withCalendarAddFilter(choice))
                        },
                    )
                }
                CalendarInstantField(f.calendarAddAtMillis) { filters(f.copy(calendarAddAtMillis = it)) }
                InstantButtons(rightClickEnabled = f.calendarAddAtMillis != config.calendarClickMillis) { filters(f.copy(calendarAddAtMillis = it)) }
            }
        // The calendar's "edit…": the same three controls, about what IS there.
        SearchDomain.Setting.CalendarAt ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactSwitch(
                    checked = f.calendarAtOn,
                    onCheckedChange = { filters(f.copy(calendarAtOn = it)) },
                )
                CalendarInstantField(f.calendarAtMillis) { filters(f.copy(calendarAtMillis = it)) }
                InstantButtons(rightClickEnabled = f.calendarAtMillis != config.calendarClickMillis) { filters(f.copy(calendarAtMillis = it)) }
            }
        SearchDomain.Setting.TaskInTree ->
            Choices(SearchDomain.Tri.entries, f.taskInTree, SearchDomain::taskInTreeLabel) { filters(f.copy(taskInTree = it)) }
        SearchDomain.Setting.TaskTreeRoot ->
            Choices(SearchDomain.Tri.entries, f.taskTreeRoot, { it.label }) { filters(f.copy(taskTreeRoot = it)) }
        SearchDomain.Setting.QuotaRepeatsSetting ->
            Choices(SearchDomain.Tri.entries, f.quotaRepeats, { it.label }) { filters(f.copy(quotaRepeats = it)) }
        SearchDomain.Setting.TaskSchedulable ->
            Choices(SearchDomain.Tri.entries, f.taskSchedulable, { it.label }) { filters(f.copy(taskSchedulable = it)) }
        SearchDomain.Setting.TaskCategory ->
            CategoryPicker(state, f.taskCategory) {
                filters(f.copy(taskCategory = it))
            }
        SearchDomain.Setting.TaskOnCalendar ->
            Choices(SearchDomain.Tri.entries, f.taskOnCalendar, { it.label }) { filters(f.copy(taskOnCalendar = it)) }
        SearchDomain.Setting.TaskBoxesFrom ->
            BoxBoundField(f.taskBoxesFrom, { filters(f.copy(taskBoxesFrom = it)) }) { onPickOnCalendar(SearchDomain.BoxBound.TaskFrom) }
        SearchDomain.Setting.TaskBoxesUntil ->
            BoxBoundField(f.taskBoxesUntil, { filters(f.copy(taskBoxesUntil = it)) }) { onPickOnCalendar(SearchDomain.BoxBound.TaskUntil) }
        SearchDomain.Setting.PeriodOnCalendar ->
            Choices(SearchDomain.Tri.entries, f.periodOnCalendar, { it.label }) { filters(f.copy(periodOnCalendar = it)) }
        SearchDomain.Setting.PeriodBoxesFrom ->
            BoxBoundField(f.periodBoxesFrom, { filters(f.copy(periodBoxesFrom = it)) }) { onPickOnCalendar(SearchDomain.BoxBound.PeriodFrom) }
        SearchDomain.Setting.PeriodBoxesUntil ->
            BoxBoundField(f.periodBoxesUntil, { filters(f.copy(periodBoxesUntil = it)) }) { onPickOnCalendar(SearchDomain.BoxBound.PeriodUntil) }
        // User rule 2026-11-21: where the rows its caller gives come from — the caller's, said and not set here; the rest of
        // the configuration narrows them ([SearchDomain.ListedFrom]).
        SearchDomain.Setting.ListedFrom ->
            Text(
                // None given: the window searches the account, which is where its rows come from.
                text = f.listedFrom?.let { from -> from.source + if (from.titles.isEmpty()) "" else ": " + from.titles.joinToString(", ") }
                    ?: "the account, searched",
                style = MaterialTheme.typography.bodyMedium,
            )
        // Set by "Blocks on the calendar": said here, with its way off (every block of the calendar).
        SearchDomain.Setting.BlockElements ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (f.blocksOf.isEmpty()) "every element"
                    else f.blocksOf.map { SearchDomain.changedElementTitle(state, it, windows) }.sorted().joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    // User rule 2026-11-04: wrapped, never cut by "…".
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (f.blocksOf.isNotEmpty()) CrossButton { filters(f.copy(blocksOf = emptySet())) }
            }
        SearchDomain.Setting.ExcludedElements ->
            ElementsDropDown(setting.name, state, f.excludedElements, windows) { filters(f.copy(excludedElements = it)) }
        SearchDomain.Setting.IncludedElements ->
            ElementsDropDown(setting.name, state, f.includedElements, windows) { filters(f.copy(includedElements = it)) }
        // User rule 2026-11-12: only the parents of these elements (what the "List of parents" asks).
        SearchDomain.Setting.ParentsOf ->
            ElementsDropDown(setting.name, state, f.parentsOf, windows) { filters(f.copy(parentsOf = it)) }
        // User rule 2026-11-20: the tasks the evolutions' "involvement" order is measured against.
        SearchDomain.Setting.InvolvementOf ->
            ElementsDropDown(setting.name, state, f.involvementOf, windows) { filters(f.copy(involvementOf = it)) }
        // User rule 2026-11-13: how far above them — an integer, picked in a list of integers with no end.
        SearchDomain.Setting.ParentDistance -> ParentDistanceField(state, f) { filters(f.copy(parentDistance = it)) }
        // User rule 2026-11-12: the kinds no parent of a listed element may be — the app's one check-box drop-down.
        SearchDomain.Setting.NoParentOfKinds ->
            CheckBoxDropDown(setting.name,
                options = SearchDomain.NO_PARENT_OF_KIND_OPTIONS,
                checked = f.noParentOfKinds,
                face = if (f.noParentOfKinds.isEmpty()) "any parent" else SearchDomain.Kind.entries.filter { it in f.noParentOfKinds }.joinToString(", ") { it.label },
                label = { it.label },
                onChange = { filters(f.copy(noParentOfKinds = it)) },
            )
        SearchDomain.Setting.CategoryHasRules ->
            Choices(SearchDomain.Tri.entries, f.categoryHasRules, { it.label }) { filters(f.copy(categoryHasRules = it)) }
        SearchDomain.Setting.PeriodOriginSetting ->
            Choices(SearchDomain.PeriodOrigin.entries, f.periodOrigin, { it.label }) { filters(f.copy(periodOrigin = it)) }
        // User rule 2026-10-11: only the periods drawn one way — the period's own drawing field, as a filter.
        SearchDomain.Setting.PeriodDrawingSetting ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceDropDown(setting.name,
                    options = org.example.project.scheduler.domain.PeriodDrawing.entries,
                    selected = f.periodDrawing,
                    label = { it.label },
                    onSelect = { filters(f.copy(periodDrawing = it)) },
                    placeholder = "any drawing",
                )
                if (f.periodDrawing != null) ClearMark { filters(f.copy(periodDrawing = null)) }
            }
        // User rule 2026-10-11: only the periods a task has so much resilience to — how much, then the task.
        SearchDomain.Setting.PeriodResilienceSetting ->
            Column(verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP)) {
                // The levels by their word; what each one means, in the hover bubble.
                InfoHint(SearchDomain.ResilienceLevel.entries.drop(1).joinToString(", ") { it.label }) {
                    Choices(SearchDomain.ResilienceLevel.entries, f.periodResilience, { it.label.substringBefore(" (") }) { filters(f.copy(periodResilience = it)) }
                }
                // User rule 2026-11-14: one row as it opens — the task's row comes with a level to ask of it ("any"
                // asks nothing of any task), a row of the same height; a task already named stays in view.
                if (f.periodResilience != SearchDomain.ResilienceLevel.Any || f.periodResilienceTask != null) NamingCell(
                    cellId = PERIOD_RESILIENCE_TASK_CELL,
                    shown = f.periodResilienceTask?.let { state.tasks[it]?.title ?: "(deleted)" }.orEmpty(),
                    identityLabel = "Tasks",
                    identity = { draft ->
                        state.tasks.values.asSequence()
                            .filter { it.title.isNotBlank() && it.title.contains(draft.trim(), ignoreCase = true) }
                            .sortedBy { it.title.lowercase() }
                            .take(30)
                            .map { NamingRow(it.id.value, it.title, taskId = it.id) }
                            .toList()
                    },
                    suggestions = { draft -> org.example.project.scheduler.domain.SchedulerDomain.titleSuggestions(state, draft) },
                    onPick = { key -> filters(f.copy(periodResilienceTask = org.example.project.scheduler.model.TaskId(key))) },
                    selectedKey = f.periodResilienceTask?.value,
                    onCleared = { filters(f.copy(periodResilienceTask = null)) },
                    width = SETTING_CELL_WIDTH,
                )
            }
        SearchDomain.Setting.AlarmStateSetting ->
            Choices(SearchDomain.AlarmState.entries, f.alarmState, { it.label }) { filters(f.copy(alarmState = it)) }
        SearchDomain.Setting.AlarmDays ->
            DayChips(f.alarmDays) { filters(f.copy(alarmDays = it)) }
        SearchDomain.Setting.DatedOnDays ->
            DayChips(f.datedOnDays) { filters(f.copy(datedOnDays = it)) }
        SearchDomain.Setting.CheckedOnDays ->
            DayChips(f.checkedOnDays) { filters(f.copy(checkedOnDays = it)) }
        SearchDomain.Setting.LettersAt ->
            LettersAtEditor(setting.name, f) { filters(it) }
        SearchDomain.Setting.TimerStateSetting ->
            Choices(SearchDomain.TimerState.entries, f.timerState, { it.label }) { filters(f.copy(timerState = it)) }
        SearchDomain.Setting.ChronoStateSetting ->
            Choices(SearchDomain.TimerState.entries, f.chronoState, { it.label }) { filters(f.copy(chronoState = it)) }
        SearchDomain.Setting.ReminderRepeatsSetting ->
            Choices(SearchDomain.ReminderRepeats.entries, f.reminderRepeats, { it.label }) {
                filters(f.copy(reminderRepeats = it))
            }
        SearchDomain.Setting.HistoryCategorySetting ->
            EnumPicker(setting.name, HistoryCategory.entries, f.historyCategory, { historyCategoryLabel(it) }) {
                filters(f.copy(historyCategory = it))
            }
        SearchDomain.Setting.HistoryWindowSetting ->
            // The windows — and the scheduler engine, where the sets of rules were made ([SearchDomain.MadeIn]).
            EnumPicker(setting.name, SearchDomain.MADE_IN_CHOICES, f.madeIn, { it.label }) { filters(f.withMadeIn(it)) }
        // User rule 2026-11-22: one window, open or closed, by its id — picked among the Search window's own window rows.
        SearchDomain.Setting.HistoryMadeInWindowSetting ->
            ChangedElementField(
                state, setOf(SearchDomain.Kind.Window), f.historyMadeInWindow?.let { SearchDomain.Kind.Window.name + "/" + it }, windows,
                cellId = MADE_IN_WINDOW_CELL,
            ) { key -> filters(f.copy(historyMadeInWindow = key?.removePrefix(SearchDomain.Kind.Window.name + "/"))) }
        SearchDomain.Setting.HistoryUndoneSetting ->
            Choices(SearchDomain.Tri.entries, f.historyUndone, { it.label }) { filters(f.copy(historyUndone = it)) }
        // The kinds of element the unit changed: the app's one check-box drop-down, so several can be ticked.
        SearchDomain.Setting.HistoryChangedKindsSetting ->
            CheckBoxDropDown(setting.name,
                options = SearchDomain.HISTORY_CHANGED_KINDS,
                checked = f.historyChangedKinds,
                face = changedKindsFace(f.historyChangedKinds),
                label = { it.label },
                onChange = { filters(f.copy(historyChangedKinds = it)) },
            )
        // User request 2026-11-28: what the unit did to a window — opened, closed… several can be ticked.
        SearchDomain.Setting.HistoryWindowChangeSetting ->
            CheckBoxDropDown(setting.name,
                options = org.example.project.scheduler.persistence.WindowChange.entries,
                checked = f.windowChanges,
                face = if (f.windowChanges.isEmpty()) "anything"
                else org.example.project.scheduler.persistence.WindowChange.entries.filter { it in f.windowChanges }.joinToString(", ") { it.label },
                label = { it.label },
                onChange = { filters(f.copy(windowChanges = it)) },
            )
        SearchDomain.Setting.NotificationSourceSetting ->
            CheckBoxDropDown(setting.name,
                options = org.example.project.scheduler.state.NotificationSource.entries,
                checked = f.notificationSources,
                face = if (f.notificationSources.isEmpty()) "anything"
                else org.example.project.scheduler.state.NotificationSource.entries
                    .filter { it in f.notificationSources }.joinToString(", ") { it.label },
                label = { it.label },
                onChange = { filters(f.copy(notificationSources = it)) },
            )
        SearchDomain.Setting.HistoryChangedElementSetting ->
            ChangedElementField(state, f.historyChangedKinds, f.historyChangedElement, windows) {
                filters(f.copy(historyChangedElement = it))
            }
        SearchDomain.Setting.RelationSectionSetting ->
            EnumPicker(setting.name, TaskRelationsDomain.Section.entries, f.relationSection, { SearchDomain.relationSectionLabel(it) }) {
                filters(f.copy(relationSection = it))
            }
        SearchDomain.Setting.ShortcutReboundSetting ->
            Choices(SearchDomain.Tri.entries, f.shortcutRebound, { it.label }) { filters(f.copy(shortcutRebound = it)) }
        // Hidden: one row per window TYPE — the default timer's window is a type of its own, not a timer's.
        SearchDomain.Setting.WindowDuplicatesSetting ->
            Choices(SearchDomain.WindowDuplicates.entries, f.windowDuplicates, { it.label }) {
                filters(f.copy(windowDuplicates = it))
            }
        SearchDomain.Setting.WindowListedKindsSetting ->
            CheckBoxDropDown(setting.name,
                options = SearchDomain.WINDOW_LISTED_KIND_OPTIONS,
                checked = f.windowListedKinds,
                face = if (f.windowListedKinds.isEmpty()) "any type"
                else SearchDomain.Kind.entries.filter { it in f.windowListedKinds }.joinToString(", ") { it.label },
                label = { it.label },
                onChange = { filters(f.copy(windowListedKinds = it)) },
            )
        SearchDomain.Setting.WindowStatusSetting ->
            EnumPicker(setting.name, SearchDomain.WindowStatus.entries, f.windowStatus, { it.label }) { filters(f.copy(windowStatus = it)) }
        // User rule 2026-10-30: the whole list's Sort by has the configuration's default sorting a click away —
        // grey where the configuration has none (`SearchDomain.defaultSortsOf`).
        SearchDomain.Setting.SortResults ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SortMethodPicker(null, config.sorts) { onChange(config.copy(sorts = it)) }
                val applied = SearchDomain.defaultSortApplied(config)
                OutlinedTextButton("Default", enabled = applied != null) { applied?.let(onChange) }
            }
        SearchDomain.Setting.TaskSort, SearchDomain.Setting.CategorySort, SearchDomain.Setting.PeriodSort,
        SearchDomain.Setting.AlarmSort, SearchDomain.Setting.TimerSort, SearchDomain.Setting.ChronoSort, SearchDomain.Setting.QuotaSort,
        SearchDomain.Setting.ReminderSort, SearchDomain.Setting.BlockSort,
        SearchDomain.Setting.HistorySort, SearchDomain.Setting.NotificationSort,
        SearchDomain.Setting.RelationSort,
        SearchDomain.Setting.ShortcutSort, SearchDomain.Setting.WorkplaceSetupSort, SearchDomain.Setting.MdSort, SearchDomain.Setting.PresenceEvolutionSort, SearchDomain.Setting.MinimumEvolutionSort, SearchDomain.Setting.AppSettingSort, SearchDomain.Setting.CalendarSort,
        SearchDomain.Setting.WindowSort,
        SearchDomain.Setting.CreationSort,
        SearchDomain.Setting.PromptSort, SearchDomain.Setting.AnswerSort, SearchDomain.Setting.ReasoningSort,
        SearchDomain.Setting.SearchConfigurationSort,
        SearchDomain.Setting.OptionSort,
        SearchDomain.Setting.IntegerSort,
        SearchDomain.Setting.ActionSort,
        -> SortMethodPicker(setting.section, config.sorts) { onChange(config.copy(sorts = it)) }
    }
}

/** How many integers a list of them is given at a time ([ParentDistanceField]); more come as its end nears. */
private const val INTEGER_PAGE: Int = 50

/**
 * User rule 2026-11-13: **the parent distance** ([SearchDomain.Filters.parentDistance]) — a field reading it, which opens a
 * Search window of integers ([SearchDomain.Kind.Integer]): first *"highest parent distance (5)"* — the highest there is
 * above the elements asked about ([org.example.project.scheduler.domain.ElementTree.highestParentDistance]), every parent
 * at any distance — then 1, 2, 3… with no end, the one in force marked.
 */
@Composable
private fun ParentDistanceField(state: SchedulerState, f: SearchDomain.Filters, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val highest = remember(f.parentsOf, state.elementLinks, state.lists, state.cells, state.tasks) {
        org.example.project.scheduler.domain.ElementTree.highestParentDistance(state, f.parentsOf)
    }
    // Opened afresh, the list is as long as what means something, or a page.
    var count by remember(open, highest) { mutableStateOf(maxOf(highest, INTEGER_PAGE)) }
    Box {
        FrameButton(SearchDomain.parentDistanceLabel(f.parentDistance, highest) + " ▾") { open = true }
        OptionSearchMenu("ParentDistanceField",
            open = open,
            onDismiss = { open = false },
            options = SearchOptions(
                options = emptyList(),
                checked = null,
                onClick = { id ->
                    SearchDomain.parentDistanceOf(id)?.let {
                        open = false
                        onChange(it)
                    }
                },
                leading = { id -> Text(if (SearchDomain.parentDistanceOf(id) == f.parentDistance) "●" else "○") },
                onNearEnd = { count += INTEGER_PAGE },
                integers = IntegerRows("highest parent distance", highest, count),
            ),
        )
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
    val range = rememberCheckRange<SearchDomain.SortMethod>()
    val popupOpener = rememberPopupOpener()
    Box { OpenedBy(popupOpener) {
        Text(
            text = methods.filter(::inList).joinToString(", ") { it.key.label }.ifEmpty { "none" } + "  ▾",
            style = MaterialTheme.typography.bodyMedium,
            // User rule 2026-11-04: wrapped, never cut by "…".
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .menuToggleClickable(open, popupOpener) { open = it }
                .padding(horizontal = 10.dp, vertical = buttonVerticalPadding(8.dp)),
        )
        // User rule 2026-11-12: the list is a Search window ([OptionSearchMenu]), "Select all" its first element.
        OptionSearchMenu("sort by",
            open = open,
            onDismiss = { open = false },
            modifier = Modifier.checkRangeShift(range),
            options = SearchOptions(
                options = methods.mapIndexed { i, method -> i.toString() to method.key.label },
                checked = methods.indices.filter { inList(methods[it]) }.mapTo(HashSet()) { it.toString() },
                // A shift-click range lands at the bottom in the menu's order, as checking each in turn would.
                onClick = { id ->
                    val method = id.toIntOrNull()?.let(methods::getOrNull)
                    if (method != null) {
                        val checked = inList(method)
                        val keys = range.press(methods, method)
                        onChange(keys.fold(sorts) { list, m -> if (inList(m) != checked) list else SearchDomain.withSortMethod(list, m, on = !checked) })
                    }
                },
                // Checking them all adds the unchecked ones at the bottom, in the menu's order — what checking each in
                // turn would do; unchecking them all takes them out and leaves the others'.
                onSelectAll = { ids ->
                    val listed = ids.mapNotNull { it.toIntOrNull()?.let(methods::getOrNull) }
                    onChange(
                        if (listed.all(::inList)) listed.fold(sorts) { list, m -> SearchDomain.withSortMethod(list, m, on = false) }
                        else listed.fold(sorts) { list, m -> if (inList(m)) list else SearchDomain.withSortMethod(list, m, on = true) },
                    )
                },
            ),
        )
    }}
}

/**
 * User rule 2026-11-22, "Row layout" ([SearchDomain.Config.taskRowLayout]): rows of ONE height, as every configuration's
 * (`SettingRowHeightTest`) — the buttons' row, then a row per part in the layout, in its order, each moved up or down
 * and taken out by its ✕. The buttons: a part to add at the end, and one layout at a click (the arrow, the share of the
 * parent and the whole title; or, once set, back to the default row).
 */
@Composable
private fun TaskRowLayoutEditor(
    layout: List<SearchDomain.TaskRowPart>,
    onChange: (List<SearchDomain.TaskRowPart>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val left = SearchDomain.TaskRowPart.entries.filter { it !in layout }
            var open by remember { mutableStateOf(false) }
            val popupOpener = rememberPopupOpener()
            Box { OpenedBy(popupOpener) {
                FrameButton("Add a part", enabled = left.isNotEmpty()) { open = !open }
                OptionSearchMenu("task row parts",
                    open = open,
                    onDismiss = { open = false },
                    options = choiceOptions(left, { it.label }) { part ->
                        open = false
                        onChange(SearchDomain.withTaskRowPart(layout, part, on = true))
                    },
                )
            }}
            val example = SearchDomain.TaskRowPart.ARROW_RELATIVE_TITLE
            if (layout.isEmpty()) FrameButton("Arrow, relative priority, title") { onChange(example) }
            else FrameButton("Default row") { onChange(emptyList()) }
            InfoMark(
                "Empty: the row as the tree draws it. Set: the row shows these parts in this order, a title whole; " +
                    "the list scrolls sideways where a row is wider than it.",
            )
        }
        layout.forEachIndexed { index, part ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                // A task cell's height, as every configuration row (`SettingRowHeightTest`).
                modifier = Modifier.defaultMinSize(minHeight = org.example.project.scheduler.ui.TASK_ROW_MIN_HEIGHT),
            ) {
                Text(
                    text = "${index + 1}. ${part.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    // User rule 2026-11-04: wrapped, never cut by "…".
                    modifier = Modifier.weight(1f),
                )
                MoveMark("↑", enabled = index > 0) { onChange(SearchDomain.movedTaskRowPart(layout, index, index - 1)) }
                MoveMark("↓", enabled = index < layout.lastIndex) { onChange(SearchDomain.movedTaskRowPart(layout, index, index + 1)) }
                CrossButton { onChange(SearchDomain.withTaskRowPart(layout, part, on = false)) }
            }
        }
    }
}

/**
 * User rule 2026-11-25: **"Entries, in order"** — the entries a menu lists, each moved up or down or taken out, and one
 * added from every entry a row's menu can have ([org.example.project.scheduler.domain.ContextDomain.ROW_MENU_ENTRIES]).
 */
@Composable
private fun EntriesEditor(entries: List<String>, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val left = org.example.project.scheduler.domain.ContextDomain.ROW_MENU_ENTRIES.filter { it !in entries }
            var open by remember { mutableStateOf(false) }
            val popupOpener = rememberPopupOpener()
            Box { OpenedBy(popupOpener) {
                FrameButton("Add an entry", enabled = left.isNotEmpty()) { open = !open }
                OptionSearchMenu("menu entries",
                    open = open,
                    onDismiss = { open = false },
                    options = choiceOptions(left, { it }) { entry ->
                        open = false
                        onChange(entries + entry)
                    },
                )
            }}
            if (entries.isNotEmpty()) FrameButton("Every entry") { onChange(emptyList()) }
            InfoMark("Empty: every entry the menu has, in its own order. Set: these, in this order; the others are not listed.")
        }
        entries.forEachIndexed { index, entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.defaultMinSize(minHeight = org.example.project.scheduler.ui.TASK_ROW_MIN_HEIGHT),
            ) {
                Text(text = "${index + 1}. $entry", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                MoveMark("↑", enabled = index > 0) { onChange(entries.toMutableList().apply { add(index - 1, removeAt(index)) }) }
                MoveMark("↓", enabled = index < entries.lastIndex) { onChange(entries.toMutableList().apply { add(index + 1, removeAt(index)) }) }
                CrossButton { onChange(entries - entry) }
            }
        }
    }
}

/** A one-character button of a list row ("↑" / "↓"), greyed where it can do nothing. */
@Composable
private fun MoveMark(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp),
    )
}

/** One choice among a few, as a row of chips — the first is always "any", which filters nothing. */
@Composable
internal fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option -> ToggleChip(label(option), option == selected, onToggle = { onSelect(option) }) }
    }
}

@Composable
internal fun ToggleChip(text: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        maxLines = 1,
        // Squeezed, it is cut — never wrapped under itself.
        softWrap = false,
        modifier = Modifier
            .clip(shape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.primary, shape)
            .clickable { onToggle(!on) }
            .padding(horizontal = 10.dp, vertical = buttonVerticalPadding(5.dp)),
    )
}

/** A history stack as the History window names it. */
private fun historyCategoryLabel(category: HistoryCategory): String =
    when (category) {
        HistoryCategory.Edit -> "edit"
        HistoryCategory.Selection -> "selection"
        HistoryCategory.Calendar -> "calendar"
        HistoryCategory.Main -> "main"
        HistoryCategory.WindowNav -> "focus moves between windows"
        HistoryCategory.SearchText -> "search text"
    }

/** "any", or one of [options] — a drop-down, for the choices too many to lay out as chips. */
@Composable
private fun <T> EnumPicker(name: String, options: List<T>, selected: T?, label: (T) -> String, onSelect: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val popupOpener = rememberPopupOpener()
    Box { OpenedBy(popupOpener) {
        Text(
            text = (selected?.let(label) ?: "any") + "  ▾",
            style = MaterialTheme.typography.bodyMedium,
            // User rule 2026-11-04: wrapped, never cut by "…".
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .menuToggleClickable(open, popupOpener) { open = it }
                .padding(horizontal = 10.dp, vertical = buttonVerticalPadding(8.dp)),
        )
        // User rule 2026-11-12: the list is a Search window ([OptionSearchMenu]), "any" first.
        OptionSearchMenu(name,
            open = open,
            onDismiss = { open = false },
            options = choiceOptions(listOf<T?>(null) + options, { it?.let(label) ?: "any" }) {
                open = false
                onSelect(it)
            },
        )
    }}
}

/**
 * The task filter's category: a [NamingCell] (user rule 2026-10-03 — every field naming an element is one, the
 * "Changed element" filter's twin). Empty is "any"; emptying it clears the filter.
 */
@Composable
private fun CategoryPicker(
    state: SchedulerState,
    selected: org.example.project.scheduler.model.CategoryId?,
    onSelect: (org.example.project.scheduler.model.CategoryId?) -> Unit,
) {
    NamingCell(
        cellId = CATEGORY_FILTER_CELL,
        shown = selected?.let { state.categoryById(it)?.title ?: "(deleted)" }.orEmpty(),
        identityLabel = "Categories",
        identity = { draft -> CategoryRules.menuEntries(state, draft, emptyList()).map { NamingRow(it.id.value, it.title) } },
        suggestions = { draft -> CategoryRules.titleSuggestions(state, draft) },
        onPick = { key -> onSelect(org.example.project.scheduler.model.CategoryId(key)) },
        selectedKey = selected?.value,
        onCleared = { onSelect(null) },
        width = SETTING_CELL_WIDTH,
    )
}

/** The resilience filter's task cell id: a cell of no tree. */
private val PERIOD_RESILIENCE_TASK_CELL = org.example.project.scheduler.model.CellId("search/period-resilience-task-filter")

/** The ✕ beside a filter that is set: back to "any" — the app's one cross ([CrossButton]). */
@Composable
private fun ClearMark(onClear: () -> Unit) = CrossButton(onClick = onClear)

/** The category filter's cell id: a cell of no tree. */
private val CATEGORY_FILTER_CELL = org.example.project.scheduler.model.CellId("search/task-category-filter")

/** The "Changed element types" drop-down's face: "any type" when none is ticked, else the ticked ones. */
private fun changedKindsFace(kinds: Set<SearchDomain.Kind>): String =
    if (kinds.isEmpty()) "any type"
    else SearchDomain.HISTORY_CHANGED_KINDS.filter { it in kinds }.joinToString(", ") { it.label }

/**
 * The "Changed element" filter: **a task cell, configured** — the tree's own [TaskRow], the way the priority-weight
 * table's rows are one (`OptionalTaskEditMenus`). ONE press enters Edit Mode (user rule 2026-10-03), Enter / Tab /
 * Escape leave it, and so does a press anywhere else (the §4 Forced Exit — here the outside press, since it has no
 * sibling cells to be pressed). ONLY while it is in Edit Mode does it draw the one edit-mode menu block of the app
 * ([EditModeMenuBlock]): the elements whose title or id IS the draft (picking one is the commit) and the title
 * suggestions (picking one only fills the field), for the kinds ticked in "Changed element types" — every kind a unit
 * can change when none is ([SearchDomain.changedElementMenus]).
 *
 * The configured differences from a tree cell follow from what the field is: no expand arrow, no minimum time, no
 * percentage, no Mode selector (naming an element IS pointing at it, as in the category field), no "New task" row (it
 * names an element that exists, or existed). Emptying the title clears the filter — the cell's own "emptying deletes";
 * a draft that names nothing is dropped as the editor closes, like a weight-table row's. A task is drawn in its own
 * colour, like every named task. The chosen element's key is drawn beside it.
 */
/**
 * User rule 2026-11-02: **a check-box drop-down of the account's elements** ([ElementTree.pickable]). An account has thousands:
 * they are listed as the list is scrolled ([CheckBoxDropDown]'s `onNearEnd`), and one [checked] and not reached yet heads the
 * list, so what is checked can always be unchecked. An element gone stands there under its key.
 */
@Composable
private fun ElementsDropDown(name: String,
    state: SchedulerState,
    checked: Set<String>,
    windows: List<SearchDomain.WindowEntry>,
    onChange: (Set<String>) -> Unit,
) {
    val elements =
        remember(state.tasks, state.categories, state.periodKinds, state.alarms, state.timers, state.chronos, state.quotas, state.chores, state.workplaceSetups) {
            org.example.project.scheduler.domain.ElementTree.pickable(state)
        }
    val names = remember(elements) { elements.toMap() }
    var shown by remember { mutableStateOf(ELEMENTS_LISTED_AT_ONCE) }
    val reached = elements.take(shown).map { it.first }
    fun nameOf(key: String) = names[key] ?: SearchDomain.changedElementTitle(state, key, windows)
    val options = checked.filterNot { it in reached }.sortedBy(::nameOf) + reached
    CheckBoxDropDown(name,
        options = options,
        checked = checked,
        face = if (checked.isEmpty()) "none" else checked.map(::nameOf).sorted().joinToString(", "),
        label = ::nameOf,
        onChange = onChange,
        onNearEnd = { if (shown < elements.size) shown += ELEMENTS_LISTED_AT_ONCE },
    )
}

/** How many elements [ElementsDropDown] adds to its list at a time. */
private const val ELEMENTS_LISTED_AT_ONCE: Int = 100

@Composable
private fun ChangedElementField(
    state: SchedulerState,
    kinds: Set<SearchDomain.Kind>,
    elementKey: String?,
    windows: List<SearchDomain.WindowEntry>,
    cellId: org.example.project.scheduler.model.CellId = CHANGED_ELEMENT_CELL,
    onChange: (String?) -> Unit,
) {
    val taskColors = TaskPalette.sheetColors(rememberTaskHues(state))
    val taskId =
        elementKey?.takeIf { it.startsWith(SearchDomain.Kind.Task.name + "/") }
            ?.let { org.example.project.scheduler.model.TaskId(it.substringAfter('/')) }
    NamingCell(
        cellId = cellId,
        shown = elementKey?.let { SearchDomain.changedElementTitle(state, it, windows) }.orEmpty(),
        identityLabel = "Elements",
        identity = { draft ->
            SearchDomain.changedElementMenus(state, kinds, draft, windows).identity.map { row ->
                // A task among the elements is drawn as the Search window's task row (user rule 2026-10-03).
                val task = row.key.takeIf { it.startsWith(SearchDomain.Kind.Task.name + "/") }
                    ?.let { org.example.project.scheduler.model.TaskId(it.substringAfter('/')) }
                NamingRow(row.key, row.label, taskColor = task?.let { taskColors[it] }, taskId = task)
            }
        },
        suggestions = { draft -> SearchDomain.changedElementMenus(state, kinds, draft, windows).titles },
        onPick = { onChange(it) },
        selectedKey = elementKey,
        onCleared = { onChange(null) },
        width = SETTING_CELL_WIDTH,
        taskColor = taskId?.let { taskColors[it] },
        trailing = elementKey?.let { key -> { NamingKey(key) } },
    )
}

/** The "Changed element" field's cell id: a cell of no tree, so it can never be confused with one. */
private val CHANGED_ELEMENT_CELL = org.example.project.scheduler.model.CellId("search/history-changed-element")

/** The "Made in window" field's cell id (user rule 2026-11-22), the same kind of field as "Changed element". */
private val MADE_IN_WINDOW_CELL = org.example.project.scheduler.model.CellId("search/history-made-in-window")

/**
 * User rule 2026-10-11: **one bound of the boxes' window** — a date and a time, `YYYY-MM-DD HH:MM` on this device's
 * clock; empty is "no bound". The bound changes only when what is typed is an instant (or is cleared) — a half-typed
 * one leaves it as it was, and the field says so. Its button hands the pick to the calendar ([onPick]): the next click
 * there is the instant, and the focus comes back. While the calendar is waiting, the button says so.
 */
@Composable
private fun BoxBoundField(millis: Long?, onChange: (Long?) -> Unit, onPick: (() -> Unit)?) {
    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
    fun format(at: Long): String {
        val t = kotlin.time.Instant.fromEpochMilliseconds(at).toLocalDateTime(tz)
        return t.date.toString() + " " + t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
    }
    fun parse(text: String): Long? =
        runCatching {
            kotlinx.datetime.LocalDateTime.parse(text.trim().replace(' ', 'T')).toInstant(tz).toEpochMilliseconds()
        }.getOrNull()
    var draft by remember(millis) { mutableStateOf(millis?.let(::format).orEmpty()) }
    val picking = LocalCalendarElementDrag.current.instantPick != null
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // User rule 2026-11-14: the format is in the field's hover bubble — written in grey in the empty field, it
        // wrapped under itself and made the row taller than its neighbours.
        InfoHint("A day and a time, YYYY-MM-DD HH:MM, on this device's clock. Empty: no bound.") {
            OutlinedTextField(
                value = draft,
                onValueChange = { typed ->
                    draft = typed
                    if (typed.isBlank()) onChange(null) else parse(typed)?.let(onChange)
                },
                singleLine = true,
                isError = draft.isNotBlank() && parse(draft) == null,
                placeholder = { Text("any") },
                modifier = Modifier.width(SETTING_INSTANT_WIDTH),
            )
        }
        // A bound with nothing to pick on the calendar (when a log entry was written) has the field alone.
        if (onPick != null) {
            // User rule 2026-11-14: a short word, what it does in its hover bubble — "Pick on the calendar" was a
            // third of the row.
            InfoHint(if (picking) "Click on the calendar: that instant is the bound." else "Pick the instant by a click on the calendar.") {
                FrameButton(if (picking) "Click…" else "Pick", enabled = !picking, onClick = onPick)
            }
            // User rule 2026-10-18: where the calendar was right-clicked, for a window that came from there.
            InstantButtons(rightClickEnabled = millis != LocalCalendarClickMillis.current) { onChange(it) }
        }
        if (millis != null) {
            CrossButton { onChange(null) }
        }
    }
}

/**
 * The calendar filter's position: a day and a time of day, `YYYY-MM-DD HH:MM` on this device's clock. A text that does
 * not parse leaves the position where it was (and shows the error state).
 */
@Composable
private fun CalendarInstantField(millis: Long?, onChange: (Long) -> Unit) {
    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
    fun format(at: Long): String {
        val t = kotlin.time.Instant.fromEpochMilliseconds(at).toLocalDateTime(tz)
        return t.date.toString() + " " + t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
    }
    fun parse(text: String): Long? =
        runCatching {
            kotlinx.datetime.LocalDateTime.parse(text.trim().replace(' ', 'T')).toInstant(tz).toEpochMilliseconds()
        }.getOrNull()
    var draft by remember(millis) { mutableStateOf(millis?.let(::format).orEmpty()) }
    // The format in the hover bubble, as a bound's is ([BoxBoundField]): in grey in the field it wrapped under itself.
    InfoHint("A day and a time, YYYY-MM-DD HH:MM, on this device's clock.") {
        OutlinedTextField(
            value = draft,
            onValueChange = { typed ->
                draft = typed
                parse(typed)?.let(onChange)
            },
            singleLine = true,
            isError = draft.isNotBlank() && parse(draft) == null,
            placeholder = { Text("day and time") },
            modifier = Modifier.width(SETTING_INSTANT_WIDTH),
        )
    }
}

/**
 * User rule 2026-11-27: **the size a window opens at** ([SearchDomain.WindowSize]) — two fields on one row, as every
 * configuration's control is one row (`SettingRowHeightTest`): the width and the height in dp ("440 x 560"), and the
 * share of the room each may take at most ("100 x 100", in %). What does not read as two numbers in range is not
 * written; the cross clears the size back to the code's.
 */
@Composable
private fun SizeEditor(size: SearchDomain.WindowSize?, onChange: (SearchDomain.WindowSize?) -> Unit) {
    val shown = size ?: SearchDomain.WindowSize(480, 480)
    @Composable
    fun Pair(hint: String, first: Int, second: Int, range: IntRange, write: (Int, Int) -> SearchDomain.WindowSize) {
        fun parse(text: String): kotlin.Pair<Int, Int>? =
            SIZE_PAIR.matchEntire(text.trim())?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
                ?.takeIf { (a, b) -> a in range && b in range }
        var draft by remember(first, second) { mutableStateOf("$first x $second") }
        OutlinedTextField(
            value = draft,
            onValueChange = { typed ->
                draft = typed
                parse(typed)?.let { (a, b) -> onChange(write(a, b)) }
            },
            singleLine = true,
            isError = parse(draft) == null,
            placeholder = { Text(hint) },
            modifier = Modifier.width(SETTING_SIZE_FIELD_WIDTH),
        )
    }
    InfoHint("The width x the height, in dp; then the share of the room each may take at most, in %.") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val sides = SearchDomain.WindowSize.MIN_DP..SearchDomain.WindowSize.MAX_DP
            Pair("width x height", shown.widthDp, shown.heightDp, sides) { w, h -> shown.copy(widthDp = w, heightDp = h) }
            Pair("max % x %", shown.maxWidthPercent, shown.maxHeightPercent, 1..100) { w, h ->
                shown.copy(maxWidthPercent = w, maxHeightPercent = h)
            }
            if (size != null) ClearMark { onChange(null) }
        }
    }
}

/** Two whole numbers, by "x", "×" or "*". */
private val SIZE_PAIR = Regex("""(\d{1,5})\s*[xX×*]\s*(\d{1,5})""")

/** How wide one field of [SizeEditor] is: "10000 x 10000". */
private val SETTING_SIZE_FIELD_WIDTH = 104.dp

/**
 * User request 2026-11-28 (*"a title that starts with the letter d and the 5th letter is h"*): **"Letters at places"** —
 * which text is read ("its name", "its id", "row 3 of its list"), then numbered boxes, one letter each: box 1 holds the
 * first letter, box 5 the fifth; an empty box asks nothing. A slow reader sees the places, no pattern to write.
 */
@Composable
private fun LettersAtEditor(name: String, f: SearchDomain.Filters, onChange: (SearchDomain.Filters) -> Unit) {
    // One row, a task cell's height (`SettingRowHeightTest`): an empty box shows its place's number.
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("In", style = MaterialTheme.typography.labelSmall)
        ChoiceDropDown(name,
            options = SearchDomain.LETTERS_IN_OPTIONS,
            selected = f.lettersIn,
            label = SearchDomain::lettersInLabel,
            onSelect = { onChange(f.copy(lettersIn = it)) },
        )
        for (at in 1..SearchDomain.LETTER_PLACES) {
            OutlinedTextField(
                value = f.letters[at]?.toString().orEmpty(),
                onValueChange = { typed ->
                    // The last letter typed is the box's: typing over a letter replaces it.
                    val letter = typed.lastOrNull { !it.isWhitespace() }
                    onChange(f.copy(letters = if (letter == null) f.letters - at else f.letters + (at to letter)))
                },
                singleLine = true,
                placeholder = { Text(at.toString()) },
                modifier = Modifier.width(LETTER_BOX_WIDTH),
            )
        }
        if (f.letters.isNotEmpty()) ClearMark { onChange(f.copy(letters = emptyMap())) }
    }
}

/** How wide one box of [LettersAtEditor] is: one letter. */
private val LETTER_BOX_WIDTH = 34.dp

/** The alarm's "Rings on" filter: no day ticked = any day; else it rings on at least one ticked day. */
@Composable
internal fun DayChips(days: Set<DayOfWeek>, emptyLabel: String = "any day", onChange: (Set<DayOfWeek>) -> Unit) {
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
                emptyLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}


/**
 * User rule 2026-11-06: **the window's other search bars and their formula** ([SearchDomain.Config.extraQueries],
 * [SearchDomain.Config.queryFormula], read by [org.example.project.scheduler.domain.SearchBarFormula]) — each added bar
 * with its ✕, a button adding one, and the formula, with what is wrong with it said under it.
 */
@Composable
private fun SearchBarsEditor(config: SearchDomain.Config, onChange: (SearchDomain.Config) -> Unit) {
    val bars = config.extraQueries
    // User rule 2026-11-14: rows of ONE height — the button, a row per added bar, the formula — and what was written
    // under them (which bar is bar 1, how a formula reads, what is wrong with this one) in hover bubbles ([InfoMark]).
    Column(verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FrameButton("Add a search bar") { onChange(config.copy(extraQueries = bars + "")) }
            InfoMark("Search bar 1 is the window's own bar. Each bar added here is numbered after it.")
        }
        bars.forEachIndexed { i, text ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // A search bar like the window's own ([SearchBar]): its cross empties it, the ✕ beside it takes it away.
                SearchBar(
                    text, { next -> onChange(config.copy(extraQueries = bars.toMutableList().also { it[i] = next })) }, "Search bar ${i + 2}",
                    modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
                )
                CrossButton { onChange(config.copy(extraQueries = bars.filterIndexed { j, _ -> j != i })) }
            }
        }
        if (bars.isNotEmpty()) {
            val problem = org.example.project.scheduler.domain.SearchBarFormula.problem(config.queryFormula, bars.size + 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = config.queryFormula,
                    onValueChange = { onChange(config.copy(queryFormula = it)) },
                    singleLine = true,
                    // Not understood: the field says so by its colour, the bubble beside it says why.
                    isError = config.queryFormula.isNotBlank() && problem != null,
                    label = { Text("Formula") },
                    modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
                )
                InfoMark(
                    when {
                        config.queryFormula.isBlank() ->
                            "Blank: every bar at once. Bars by number or name, with AND, OR, NOT and parentheses: " +
                                "\u201Cmain search bar\u201D AND (\u201Csearch bar 2\u201D OR \u201Csearch bar 3\u201D)."
                        problem != null -> "Not understood ($problem): every bar at once."
                        else -> "Bars by number or name, with AND, OR, NOT and parentheses."
                    },
                )
            }
        }
    }
}
