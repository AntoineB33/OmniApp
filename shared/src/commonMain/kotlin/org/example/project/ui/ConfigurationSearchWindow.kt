package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
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
    /** The configuration that Search window opened with — what its Reset, listed here, goes back to first. */
    openedConfig: SearchDomain.Config? = null,
    /** This window's own configuration: which configurations it lists. */
    own: SearchDomain.ConfigurationSearch,
    onOwnChange: (SearchDomain.ConfigurationSearch) -> Unit,
    onDismiss: () -> Unit,
    /**
     * User rule 2026-10-11: a bound of the boxes' window is to be picked by a click on the calendar — `App` gives the
     * calendar the focus, writes the click's instant to that bound and brings the focus back here.
     */
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit = {},
    /**
     * What the Search window reads its result list with besides its configuration — the clock, the calendar's layer
     * bands at an instant, the scheduler engine's runs: the groups here are counted off THAT list
     * ([SearchDomain.kindCountsInResults]), so they are given the same.
     */
    nowMillis: () -> Long = { 0L },
    calendarLayerKindsAt: (Long) -> Set<String> = { emptySet() },
    schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry> = emptyList(),
    /** The app's windows, for the window rows of the Search results ([SearchDomain.WindowEntry]). */
    windows: List<SearchDomain.WindowEntry> = emptyList(),
    /** The Claude Code logs as last read: the rows of the logs' kinds, and the projects their filter offers. */
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog = org.example.project.claudelogs.ClaudeLogCatalog.EMPTY,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
) {
    val frame = rememberWindowFrameState(CONFIGURATION_SEARCH_FRAME_ID, initialOffset, initialSize)
    // How many rows of each kind the Search window lists: what orders the groups (user rule 2026-10-11) and what the
    // "only what applies" button keeps. One pass over that window's results (paths skipped), when they can change.
    val counts =
        remember(
            config, state.tasks, state.taskTrees, state.cells, state.lists, state.categories, state.periodKinds,
            state.alarms, state.timers, state.chronos, state.quotas, state.chores, state.panels, windows,
            calendarLayerKindsAt, schedulerRuns,
            claudeLogs,
        ) { SearchDomain.kindCountsInResults(state, config, windows, nowMillis(), calendarLayerKindsAt, schedulerRuns, claudeLogs) }
    val groups = SearchDomain.configurationGroups(own.query, own.shownKinds, counts, own.onlyResultKinds, own.frozenKinds)

    // The calendar right-click the Search window came from, for the time fields drawn here ([RightClickTimeButton]).
    androidx.compose.runtime.CompositionLocalProvider(LocalCalendarClickMillis provides config.calendarClickMillis) {
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
        CompactFields {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .keepsWidthAbove(COMPACT_SECTION_MIN_WIDTH),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // --- The configuration ------------------------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = own.query,
                    onValueChange = { onOwnChange(own.copy(query = it)) },
                    singleLine = true,
                    label = { Text("Search a configuration") },
                    trailingIcon = { SearchBarCross(own.query) { onOwnChange(own.copy(query = "")) } },
                    modifier = Modifier.weight(1f).leaveFocusOnOutsidePress()
                        .revertsOnEscape(own.query) { onOwnChange(own.copy(query = it)) },
                )
                // This window's OWN search field, pages and types; the Search window's configuration it lists is
                // untouched (that window has its own Reset).
                val everyPage = SearchDomain.FEATURE_PAGES.mapTo(HashSet()) { it.id }
                ResetButton(enabled = own.query.isNotEmpty() || own.kinds.isNotEmpty() || own.pages != everyPage) {
                    onOwnChange(own.copy(query = "", kinds = emptySet(), pages = everyPage))
                }
            }
            // User rule 2026-11-01: which feature pages' elements the type selector beside it offers.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pages = SearchDomain.FEATURE_PAGES
                val picked = pages.filter { it.id in own.pages }
                CheckBoxDropDown(
                    options = pages,
                    checked = picked.toSet(),
                    face =
                        when (picked.size) {
                            pages.size -> "every page"
                            0 -> "no page"
                            else -> picked.joinToString(", ") { it.label }
                        },
                    label = { it.label },
                    onChange = { checked -> onOwnChange(own.copy(pages = checked.mapTo(HashSet()) { it.id })) },
                    modifier = Modifier.weight(1f),
                )
                KindsDropDown(
                    kinds = own.kinds,
                    onKindsChange = { onOwnChange(own.copy(kinds = it)) },
                    modifier = Modifier.weight(1f),
                    options = own.offeredKinds,
                )
            }
            // User rule 2026-10-11: one button — only the configurations that apply to at least one row of the
            // result list — and its switch, which stops the list from following the results: a filter that empties
            // its own type out of them then stays in view, where it can be set off again.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleChip(
                    text = "Only what applies to the results",
                    on = own.onlyResultKinds,
                    onToggle = { onOwnChange(own.copy(onlyResultKinds = it, frozenKinds = null)) },
                )
                androidx.compose.material3.Switch(
                    checked = own.frozenKinds != null,
                    enabled = own.onlyResultKinds,
                    onCheckedChange = { keep ->
                        onOwnChange(
                            own.copy(frozenKinds = if (keep) SearchDomain.applicableKinds(own.shownKinds, counts, onlyApplicable = true) else null),
                        )
                    },
                )
                NoteText("Keep this list as the results change")
            }

            HorizontalDivider()

            // --- The result section: the sorting methods in force, then the configurations -------------
            // User rule 2026-11-04: each change is stamped with its time, for the configurations tree's "Recently changed".
            fun changed(settings: List<SearchDomain.Setting>): (SearchDomain.Config) -> Unit =
                { next -> onConfigChange(SearchDomain.withChanged(config, next, settings, nowMillis())) }
            val sortChanged = changed(listOf(SearchDomain.Setting.SortResults))
            SortMethodList(config.sorts, SearchDomain.defaultSortApplied(config)?.let { applied -> { sortChanged(applied) } }) {
                sortChanged(config.copy(sorts = it))
            }
            // User rule 2026-10-14: typing in the bar brings the list back to its top.
            val resultScroll = rememberScrollState()
            ScrollsToTopWhenTyped(own.query, resultScroll)
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(resultScroll),
                verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP),
            ) {
                if (groups.isEmpty()) NoteText("No configuration matches.")
                // The groups whose configurations reach the most rows of the result list first, each with its
                // expansion arrow (user rule 2026-10-11: "just like in the action section").
                val listed = groups.mapTo(HashSet()) { it.id }
                // User rule 2026-11-02: what the bar holds is highlighted in what it searches.
                HighlightsSearch(own.query) {
                    for (group in groups) {
                        val collapsed = group.id in own.collapsedGroups
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            val toggle = { onOwnChange(SearchDomain.withSettingGroupToggled(own, group.id, listed)) }
                            SectionArrow(collapsed, toggle)
                            Box(Modifier.togglesSection(toggle)) { SectionTitle(group.title) }
                        }
                        if (collapsed) continue
                        // What every kind of the group has under one name: one field, written to all of them.
                        for (family in group.shared) {
                            SettingRow(family.first().label) { SharedSettingEditor(family, config, changed(family), onPickOnCalendar, claudeLogs) }
                        }
                        for (setting in group.settings) {
                            SettingRow(setting.label) { SettingEditor(state, setting, config, openedConfig, changed(listOf(setting)), windows, onPickOnCalendar, claudeLogs) }
                        }
                    }
                }
            }
        }
        }
    }
    }
}

/**
 * A configuration several kinds share ([SearchDomain.SHARED_SETTINGS]), as ONE control: it shows the value the kinds
 * have in common — nothing where they differ — and what is set is set for all of them.
 */
@Composable
private fun SharedSettingEditor(
    family: List<SearchDomain.Setting>,
    config: SearchDomain.Config,
    onChange: (SearchDomain.Config) -> Unit,
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
) {
    val f = config.filters
    fun filters(next: SearchDomain.Filters) = onChange(config.copy(filters = next))
    when (family.first()) {
        SearchDomain.Setting.TaskOnCalendar -> {
            val common: SearchDomain.Tri? = f.taskOnCalendar.takeIf { it == f.periodOnCalendar }
            Choices<SearchDomain.Tri?>(SearchDomain.Tri.entries, common, { it?.label.orEmpty() }) { picked ->
                if (picked != null) filters(f.copy(taskOnCalendar = picked, periodOnCalendar = picked))
            }
        }
        SearchDomain.Setting.TaskBoxesFrom ->
            BoxBoundField(
                f.taskBoxesFrom.takeIf { it == f.periodBoxesFrom },
                { filters(SearchDomain.withBoxBound(f, SearchDomain.BoxBound.BothFrom, it)) },
            ) { onPickOnCalendar(SearchDomain.BoxBound.BothFrom) }
        SearchDomain.Setting.TaskBoxesUntil ->
            BoxBoundField(
                f.taskBoxesUntil.takeIf { it == f.periodBoxesUntil },
                { filters(SearchDomain.withBoxBound(f, SearchDomain.BoxBound.BothUntil, it)) },
            ) { onPickOnCalendar(SearchDomain.BoxBound.BothUntil) }
        // The Claude Code logs' filters are one each for the three kinds: the same editor as each kind's own.
        SearchDomain.Setting.PromptProject, SearchDomain.Setting.PromptFrom, SearchDomain.Setting.PromptUntil ->
            ClaudeFilterEditor(family.first(), f, claudeLogs, ::filters)
        else -> Unit
    }
}

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
                ChoiceDropDown(
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

/** A group's title in a compact section: one line, a little room above it to part it from the group before. */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = searchHighlighted(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        // User rule 2026-11-04: no text of the configurations is cut by "…": it wraps.
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** One setting: its name, on ONE line, then its control — a row one task cell tall unless the control is taller. */
@Composable
internal fun SettingRow(label: String, editor: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = TASK_ROW_MIN_HEIGHT),
    ) {
        Text(
            text = searchHighlighted(label),
            style = MaterialTheme.typography.bodyMedium,
            // User rule 2026-11-04: wrapped in its column, never cut by "…" ("Only this page's types").
            modifier = Modifier.width(130.dp).padding(end = 6.dp),
        )
        Box(Modifier.weight(1f)) { editor() }
    }
}

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
        SearchDomain.Setting.SearchText ->
            OutlinedTextField(
                value = config.query,
                onValueChange = { onChange(config.copy(query = it)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().leaveFocusOnOutsidePress(),
            )
        // The Search window's own type selector — a field with a drop-down — not a second drawing of it.
        SearchDomain.Setting.Types ->
            KindsDropDown(kinds = config.kinds, onKindsChange = { onChange(config.copy(kinds = it)) }, options = config.offeredKinds)
        // User rule 2026-11-04: the search bar's two filters, also in its right-click menu.
        SearchDomain.Setting.MatchCase ->
            androidx.compose.material3.Switch(checked = f.matchCase, onCheckedChange = { filters(f.copy(matchCase = it)) })
        SearchDomain.Setting.WholeWord ->
            androidx.compose.material3.Switch(checked = f.wholeWord, onCheckedChange = { filters(f.copy(wholeWord = it)) })
        // User rule 2026-11-06: the bars hold regular expressions.
        SearchDomain.Setting.Regex ->
            androidx.compose.material3.Switch(checked = f.regex, onCheckedChange = { filters(f.copy(regex = it)) })
        // User rule 2026-11-06: the search bars added to the window's own, and the formula putting them together.
        SearchDomain.Setting.SearchBars -> SearchBarsEditor(config, onChange)
        // User rule 2026-11-04: on, the type selector offers the window's page's types alone ([SearchDomain.Config.pageTypesOnly]).
        SearchDomain.Setting.PageTypes ->
            androidx.compose.material3.Switch(checked = config.pageTypesOnly, onCheckedChange = { onChange(config.copy(pageTypesOnly = it)) })
        // User rule 2026-11-12: how the results are laid out — today the actions section's ([SearchDomain.Config.actionsGrouped]…).
        SearchDomain.Setting.GroupByAddedElements ->
            androidx.compose.material3.Switch(checked = config.actionsGrouped, onCheckedChange = { onChange(config.copy(actionsGrouped = it)) })
        SearchDomain.Setting.ExpandResults ->
            androidx.compose.material3.Switch(checked = config.actionsExpanded, onCheckedChange = { onChange(config.copy(actionsExpanded = it)) })
        // User rule 2026-11-12: a button on every element of the list to drop it.
        SearchDomain.Setting.DropButtons ->
            androidx.compose.material3.Switch(checked = config.dropButtons, onCheckedChange = { onChange(config.copy(dropButtons = it)) })
        SearchDomain.Setting.SearchSortsOnly ->
            androidx.compose.material3.Switch(checked = config.actionSearchSortsOnly, onCheckedChange = { onChange(config.copy(actionSearchSortsOnly = it)) })
        // The Search window's Reset, the same button with the same rule; this window's own Reset (top section)
        // clears this window's search instead.
        SearchDomain.Setting.ResetSearch -> {
            val reset = SearchDomain.resetConfig(config, openedConfig)
            ResetButton(enabled = reset != config) { onChange(reset) }
        }
        // "any" is no list: it does not appear by default (user rule 2026-11-02).
        SearchDomain.Setting.CompletionList ->
            EnumPicker(SearchDomain.Completion.entries, config.completion, { it.label }) { onChange(config.copy(completion = it)) }
        // The calendar filter: its three states (user rule 2026-10-07 — what can be added without removing anything
        // where the user right-clicked, what can be added, or no filter), the instant, and — only for a window the
        // calendar's "add…" opened — the button that puts the instant back on that right-click.
        SearchDomain.Setting.CalendarAdd ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                var statesOpen by remember { mutableStateOf(false) }
                androidx.compose.foundation.layout.Box {
                    FrameButton(f.calendarAddFilter.label + " ▾") { statesOpen = true }
                    // User rule 2026-11-12: the list is a Search window ([OptionSearchMenu]).
                    OptionSearchMenu(
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
                androidx.compose.material3.Switch(
                    checked = f.calendarAtOn,
                    onCheckedChange = { filters(f.copy(calendarAtOn = it)) },
                )
                CalendarInstantField(f.calendarAtMillis) { filters(f.copy(calendarAtMillis = it)) }
                InstantButtons(rightClickEnabled = f.calendarAtMillis != config.calendarClickMillis) { filters(f.copy(calendarAtMillis = it)) }
            }
        SearchDomain.Setting.TaskInTree ->
            Choices(SearchDomain.Tri.entries, f.taskInTree, { it.label }) { filters(f.copy(taskInTree = it)) }
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
                if (f.blocksOf.isNotEmpty()) FrameButton("✕") { filters(f.copy(blocksOf = emptySet())) }
            }
        SearchDomain.Setting.ExcludedElements ->
            ElementsDropDown(state, f.excludedElements, windows) { filters(f.copy(excludedElements = it)) }
        SearchDomain.Setting.IncludedElements ->
            ElementsDropDown(state, f.includedElements, windows) { filters(f.copy(includedElements = it)) }
        // User rule 2026-11-12: only the parents of these elements (what the "List of parents" asks).
        SearchDomain.Setting.ParentsOf ->
            ElementsDropDown(state, f.parentsOf, windows) { filters(f.copy(parentsOf = it)) }
        // User rule 2026-11-12: the kinds no parent of a listed element may be — the app's one check-box drop-down.
        SearchDomain.Setting.NoParentOfKinds ->
            CheckBoxDropDown(
                options = SearchDomain.Kind.entries.filter(org.example.project.scheduler.domain.ElementTree::isElement),
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
                ChoiceDropDown(
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
                Choices(SearchDomain.ResilienceLevel.entries, f.periodResilience, { it.label }) { filters(f.copy(periodResilience = it)) }
                NamingCell(
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
                )
            }
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
            // The windows — and the scheduler engine, where the sets of rules were made ([SearchDomain.MadeIn]).
            EnumPicker(SearchDomain.MADE_IN_CHOICES, f.madeIn, { it.label }) { filters(f.withMadeIn(it)) }
        SearchDomain.Setting.HistoryUndoneSetting ->
            Choices(SearchDomain.Tri.entries, f.historyUndone, { it.label }) { filters(f.copy(historyUndone = it)) }
        // The kinds of element the unit changed: the app's one check-box drop-down, so several can be ticked.
        SearchDomain.Setting.HistoryChangedKindsSetting ->
            CheckBoxDropDown(
                options = SearchDomain.HISTORY_CHANGED_KINDS,
                checked = f.historyChangedKinds,
                face = changedKindsFace(f.historyChangedKinds),
                label = { it.label },
                onChange = { filters(f.copy(historyChangedKinds = it)) },
            )
        SearchDomain.Setting.NotificationSourceSetting ->
            CheckBoxDropDown(
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
        // Hidden: one row per window TYPE — the default timer's window is a type of its own, not a timer's.
        SearchDomain.Setting.WindowDuplicatesSetting ->
            Choices(SearchDomain.WindowDuplicates.entries, f.windowDuplicates, { it.label }) {
                filters(f.copy(windowDuplicates = it))
            }
        SearchDomain.Setting.WindowStatusSetting ->
            EnumPicker(SearchDomain.WindowStatus.entries, f.windowStatus, { it.label }) { filters(f.copy(windowStatus = it)) }
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
        SearchDomain.Setting.HistorySort, SearchDomain.Setting.NotificationSort, SearchDomain.Setting.TaskTreeSort,
        SearchDomain.Setting.RelationSort,
        SearchDomain.Setting.ShortcutSort, SearchDomain.Setting.WorkplaceSetupSort, SearchDomain.Setting.AppSettingSort, SearchDomain.Setting.CalendarSort,
        SearchDomain.Setting.WindowSort,
        SearchDomain.Setting.CreationSort,
        SearchDomain.Setting.PromptSort, SearchDomain.Setting.AnswerSort, SearchDomain.Setting.ReasoningSort,
        SearchDomain.Setting.SearchConfigurationSort,
        SearchDomain.Setting.OptionSort,
        SearchDomain.Setting.ActionSort,
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
    val range = rememberCheckRange<SearchDomain.SortMethod>()
    Box {
        Text(
            text = methods.filter(::inList).joinToString(", ") { it.key.label }.ifEmpty { "none" } + "  ▾",
            style = MaterialTheme.typography.bodyMedium,
            // User rule 2026-11-04: wrapped, never cut by "…".
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .menuToggleClickable(open) { open = it }
                .padding(horizontal = 10.dp, vertical = buttonVerticalPadding(8.dp)),
        )
        // User rule 2026-11-12: the list is a Search window ([OptionSearchMenu]), "Select all" its first element.
        OptionSearchMenu(
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
    }
}

/**
 * The sorting methods in force, dominant first: each row can be dragged to another place, its direction
 * flipped, and removed with its ✕. The dragged row follows the pointer and lands where it is released, the rows
 * it passed over each moving one place (`SearchDomain.movedSortMethod`). Its heading has the configuration's
 * "Default" button (user rule 2026-10-30) — [onDefault], null and grey where the configuration has no default.
 */
@Composable
private fun SortMethodList(
    sorts: List<SearchDomain.SortMethod>,
    onDefault: (() -> Unit)?,
    onChange: (List<SearchDomain.SortMethod>) -> Unit,
) {
    val current by rememberUpdatedState(sorts)
    val change by rememberUpdatedState(onChange)
    var dragged by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    // One row's height plus the gap between rows: what a drag must cover to pass one row.
    var pitch by remember { mutableStateOf(1f) }
    val gap = COMPACT_ROW_GAP
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Sort by — most dominant first")
            OutlinedTextButton("Default", enabled = onDefault != null) { onDefault?.invoke() }
        }
        if (sorts.isEmpty()) NoteText("No sorting method: the results keep their default order. Check one in a Sort by below.")
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
                        .padding(horizontal = 8.dp),
                ) {
                    Text("⠿", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${index + 1}. ${method.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        // User rule 2026-11-04: wrapped, never cut by "…".
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
            // User rule 2026-11-04: wrapped, never cut by "…".
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .menuToggleClickable(open) { open = it }
                .padding(horizontal = 10.dp, vertical = buttonVerticalPadding(8.dp)),
        )
        // User rule 2026-11-12: the list is a Search window ([OptionSearchMenu]), "any" first.
        OptionSearchMenu(
            open = open,
            onDismiss = { open = false },
            options = choiceOptions(listOf<T?>(null) + options, { it?.let(label) ?: "any" }) {
                open = false
                onSelect(it)
            },
        )
    }
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
    )
}

/** The resilience filter's task cell id: a cell of no tree. */
private val PERIOD_RESILIENCE_TASK_CELL = org.example.project.scheduler.model.CellId("search/period-resilience-task-filter")

/** The ✕ beside a filter that is set: back to "any". */
@Composable
private fun ClearMark(onClear: () -> Unit) {
    Text(
        text = "✕",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClear).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

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
private fun ElementsDropDown(
    state: SchedulerState,
    checked: Set<String>,
    windows: List<SearchDomain.WindowEntry>,
    onChange: (Set<String>) -> Unit,
) {
    val elements =
        remember(state.tasks, state.categories, state.periodKinds, state.alarms, state.timers, state.chronos, state.quotas, state.chores, state.taskTrees, state.workplaceSetups) {
            org.example.project.scheduler.domain.ElementTree.pickable(state)
        }
    val names = remember(elements) { elements.toMap() }
    var shown by remember { mutableStateOf(ELEMENTS_LISTED_AT_ONCE) }
    val reached = elements.take(shown).map { it.first }
    fun nameOf(key: String) = names[key] ?: SearchDomain.changedElementTitle(state, key, windows)
    val options = checked.filterNot { it in reached }.sortedBy(::nameOf) + reached
    CheckBoxDropDown(
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
    onChange: (String?) -> Unit,
) {
    val taskColors = TaskPalette.sheetColors(rememberTaskHues(state))
    val taskId =
        elementKey?.takeIf { it.startsWith(SearchDomain.Kind.Task.name + "/") }
            ?.let { org.example.project.scheduler.model.TaskId(it.substringAfter('/')) }
    NamingCell(
        cellId = CHANGED_ELEMENT_CELL,
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
        taskColor = taskId?.let { taskColors[it] },
        trailing = elementKey?.let { key -> { NamingKey(key) } },
    )
}

/** The "Changed element" field's cell id: a cell of no tree, so it can never be confused with one. */
private val CHANGED_ELEMENT_CELL = org.example.project.scheduler.model.CellId("search/history-changed-element")

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
        OutlinedTextField(
            value = draft,
            onValueChange = { typed ->
                draft = typed
                if (typed.isBlank()) onChange(null) else parse(typed)?.let(onChange)
            },
            singleLine = true,
            isError = draft.isNotBlank() && parse(draft) == null,
            placeholder = { Text("any — YYYY-MM-DD HH:MM") },
            modifier = Modifier.width(190.dp),
        )
        // A bound with nothing to pick on the calendar (when a log entry was written) has the field alone.
        if (onPick != null) {
            FrameButton(if (picking) "Click on the calendar…" else "Pick on the calendar", enabled = !picking, onClick = onPick)
            // User rule 2026-10-18: where the calendar was right-clicked, for a window that came from there.
            InstantButtons(rightClickEnabled = millis != LocalCalendarClickMillis.current) { onChange(it) }
        }
        if (millis != null) {
            Text(
                text = "✕",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onChange(null) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
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
    OutlinedTextField(
        value = draft,
        onValueChange = { typed ->
            draft = typed
            parse(typed)?.let(onChange)
        },
        singleLine = true,
        isError = draft.isNotBlank() && parse(draft) == null,
        placeholder = { Text("YYYY-MM-DD HH:MM") },
        modifier = Modifier.width(190.dp),
    )
}

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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        NoteText("Search bar 1 is the window's own bar.")
        bars.forEachIndexed { i, text ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { next -> onChange(config.copy(extraQueries = bars.toMutableList().also { it[i] = next })) },
                    singleLine = true,
                    label = { Text("Search bar ${i + 2}") },
                    modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
                )
                Text(
                    "✕",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onChange(config.copy(extraQueries = bars.filterIndexed { j, _ -> j != i })) }.padding(6.dp),
                )
            }
        }
        FrameButton("Add a search bar") { onChange(config.copy(extraQueries = bars + "")) }
        if (bars.isNotEmpty()) {
            OutlinedTextField(
                value = config.queryFormula,
                onValueChange = { onChange(config.copy(queryFormula = it)) },
                singleLine = false,
                label = { Text("Formula") },
                placeholder = { Text("\u201Cmain search bar\u201D AND (\u201Csearch bar 2\u201D OR \u201Csearch bar 3\u201D)") },
                modifier = Modifier.fillMaxWidth().leaveFocusOnOutsidePress(),
            )
            val problem = org.example.project.scheduler.domain.SearchBarFormula.problem(config.queryFormula, bars.size + 1)
            NoteText(
                when {
                    config.queryFormula.isBlank() -> "Blank: every bar at once."
                    problem != null -> "Not understood ($problem): every bar at once."
                    else -> "Bars by number or name, with AND, OR, NOT and parentheses."
                },
            )
        }
    }
}
