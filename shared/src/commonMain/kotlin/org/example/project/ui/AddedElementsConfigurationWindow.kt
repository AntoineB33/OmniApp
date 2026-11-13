package org.example.project.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onPlaced
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.key
import org.example.project.scheduler.domain.CalendarLockDomain
import org.example.project.scheduler.domain.PeriodDrawing
import org.example.project.scheduler.ui.DrawingSwatch
import org.example.project.scheduler.ui.PeriodCombinationsSection
import org.example.project.scheduler.domain.CategoryRules
import org.example.project.scheduler.domain.RelativePriorityDomain
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TaskPathsDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.Category
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.domain.TimerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.platform.writeSystemClipboardText
import org.example.project.scheduler.state.defaultSubtreeIsEmpty
import org.example.project.scheduler.ui.LocalCalendarGoTo
import org.example.project.scheduler.ui.ScheduleUnitEditor
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
 * The bar is the **actions' filter** of the Search window it acts for ([SearchDomain.Config.actionQuery], user rule
 * 2026-10-01): it is that window's, so it narrows that window's top right quarter too. The kind drop-down and "only
 * the types in the added elements" are this window's own configuration ([own], a [SearchDomain.ConfigurationSearch],
 * local-only view state kept by `App` like the Configuration Search window's).
 */
@Composable
fun AddedElementsConfigurationWindow(
    state: SchedulerState,
    /** The Search window's added elements, resolved ([SearchDomain.resolve]) — what every action acts on. */
    added: List<SearchDomain.Result>,
    /** The configuration of the Search window the actions act for: its actions' filter and their fields. */
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    own: SearchDomain.ConfigurationSearch,
    onOwnChange: (SearchDomain.ConfigurationSearch) -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
    nowMillis: () -> Long,
    handlers: AddedActionHandlers,
    onOpenEach: (List<SearchDomain.Result>) -> Unit,
    onClear: (List<SearchDomain.Result>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
) {
    val frame = rememberWindowFrameState(ADDED_CONFIGURATION_FRAME_ID, initialOffset, initialSize)
    val addedKinds = SearchDomain.actionKindsOf(added)
    val sections =
        SearchDomain.addedActions(config.actionQuery, own.kinds, addedKinds.takeIf { own.onlyResultKinds })

    // The calendar right-click the Search window came from, for the time fields drawn here ([RightClickTimeButton]).
    androidx.compose.runtime.CompositionLocalProvider(LocalCalendarClickMillis provides config.calendarClickMillis) {
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
                    value = config.actionQuery,
                    onValueChange = { onConfigChange(config.copy(actionQuery = it)) },
                    singleLine = true,
                    label = { Text("Search a configuration") },
                    trailingIcon = { SearchBarCross(config.actionQuery) { onConfigChange(config.copy(actionQuery = "")) } },
                    modifier = Modifier.weight(1f).leaveFocusOnOutsidePress()
                        .revertsOnEscape(config.actionQuery) { onConfigChange(config.copy(actionQuery = it)) },
                )
                KindsDropDown("AddedElementsConfigurationWindow", kinds = own.kinds, onKindsChange = { onOwnChange(own.copy(kinds = it)) })
                ResetButton(enabled = config.actionQuery.isNotEmpty() || own.kinds.isNotEmpty()) {
                    onConfigChange(config.copy(actionQuery = ""))
                    onOwnChange(own.copy(kinds = emptySet()))
                }
            }
            ToggleChip(
                text = "Only the types in the added elements",
                on = own.onlyResultKinds,
                onToggle = { onOwnChange(own.copy(onlyResultKinds = it)) },
            )

            HorizontalDivider()

            // --- The actions ------------------------------------------------------------------------
            // User rule 2026-11-12: a Search window, its bar the one above ("Search a configuration").
            AddedActionList(
                state, sections, added, config, onConfigChange, handlers, onIntent, nowMillis, onOpenEach, onClear,
                ownBar = false,
                highlighted = config.actionQuery,
                noneListed = "No configuration matches.",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        }
    }
    }
}

/** The frame id of the Added elements configurations window — also its `FloatingWindow` name in `App`. */
const val ADDED_CONFIGURATION_FRAME_ID: String = "AddedConfig"

/**
 * What the actions that open or reach something need from `App` — never a second path to any of them. The task
 * ones are a task cell's right-click menu's own handlers ([org.example.project.scheduler.ui.TaskCellMenuActions]);
 * "go to calendar" is read off [org.example.project.scheduler.ui.LocalCalendarGoTo] like the menu's.
 *
 * [timerRun] and [reminderEditor] draw the given elements as the Alarms window and the reminder editor draw them —
 * embedded, the same component over the same callbacks (user rule 2026-10-01: the actions on the added elements
 * replaced each element's own window). [timerRun] is a timer's RUN alone: its countdown and the countdown in reverse.
 */
data class AddedActionHandlers(
    val onStartNow: (TaskId) -> Unit,
    val onEdit: (TaskId) -> Unit,
    val onGoToTaskTree: (TaskId) -> Unit,
    /** "Default sub-tree": its window, opened or brought to the front (user rule 2026-11-11). */
    val onOpenDefaultSubtree: () -> Unit = {},
    val onDeepCopyCell: (CellId) -> Unit,
    /** A period's "Search its tasks": the Search window of its tasks' resilience ([SearchDomain.resilienceSearchConfig]). */
    val onOpenResilienceSearch: (String) -> Unit,
    /** A creation row's "Search every element of the kind": the Search window on that kind ([SearchDomain.kindSearchConfig]). */
    val onOpenKindSearch: (SearchDomain.Kind) -> Unit,
    /**
     * The run of each timer or chrono of [subjects] — the Alarms window's own row, its run alone. [compact]: each on ONE
     * row — a timer's countdown, buttons, Elapsed and when it ends side by side; a chrono's title, count and buttons.
     */
    val timerRun: @Composable (subjects: Set<AlarmWindowSubject>, compact: Boolean) -> Unit,
    val reminderEditor: @Composable (Set<String>) -> Unit,
    /**
     * "New": one element of the kind, made the way its "creation" row makes it (`App.createElement`). It joins this
     * window's added elements, its actions shown (user rule 2026-11-01, every kind).
     */
    val onCreate: (SearchDomain.Kind) -> Unit,
    /** "Duplicate": these intents ([SearchDomain.duplicateIntents]) dispatched; the keys of the kind's elements they made. */
    val onDuplicate: (List<SchedulerIntent>, SearchDomain.Kind) -> List<String>,
    /** "Add to the calendar": these drafts saved as the calendar's element window saves its own. */
    val onPlaceOnCalendar: (List<org.example.project.scheduler.domain.CalendarElements.Draft>) -> Unit,
    /** The workplace setups being applied: `App`'s, shared with a double-click on their rows. */
    val workplaceRuns: WorkplaceRuns = WorkplaceRuns(),
    /** "Constrained in": the constraint picker over these reminders (by id) at once; what it saves, all of them get. */
    val onEditReminderConstraint: (List<String>) -> Unit = {},
    /** "Blocks on the calendar": the Search window of these elements' (by key) blocks ([SearchDomain.blocksSearchConfig]). */
    val onOpenBlocksSearch: (List<String>) -> Unit = {},
    /** "Drag on the calendar": the press took hold of blocks — the calendar comes to the front and takes the focus. */
    val onDragOnCalendar: () -> Unit = {},
    /**
     * The Notifications switch's write ([SearchDomain.AddedAction.NotificationsSwitch]): through the ENGINE, which
     * also withdraws what the OS is still showing — never a plain intent (`SchedulerEngine.setNotificationsEnabled`).
     */
    val onSetNotificationsEnabled: (Boolean) -> Unit = {},
    /** The scheduler engine's runs, for the history units' "Information" (they are the view model's, kept in memory). */
    val schedulerRuns: () -> List<org.example.project.scheduler.state.SchedulerRunEntry> = { emptyList() },
    /**
     * User rule 2026-10-12, the chords mapped to a window ([WindowShortcuts]) — the device's, so `App`'s to hold and
     * to write: the mappings, the windows one can name (frame id to the name it goes by), a mapping changed, and
     * mappings deleted (by id).
     */
    val windowShortcuts: () -> List<WindowShortcut> = { emptyList() },
    val shortcutWindows: () -> List<Pair<String, String>> = { emptyList() },
    val onWindowShortcutChange: (WindowShortcut) -> Unit = {},
    val onDeleteWindowShortcuts: (List<String>) -> Unit = {},
    /** "Calendar" on notifications: a calendar window showing these (by id) as chips ([org.example.project.scheduler.domain.CalendarNotifications]). */
    val onOpenNotificationsCalendar: (List<String>) -> Unit = {},
    /**
     * The Claude Code logs as last read (`App` reads them), what their entries' actions show
     * ([SearchDomain.CLAUDE_ENTRY_ACTIONS]); the directory they are read from set, and read again.
     */
    val claudeLogs: () -> org.example.project.claudelogs.ClaudeLogCatalog = { org.example.project.claudelogs.ClaudeLogCatalog.EMPTY },
    /** User rule 2026-11-13: every Search window open or saved, by id ([SearchDomain.AddedAction.ConfigurationStates]). */
    val searchWindows: () -> List<SearchDomain.SearchWindowState> = { emptyList() },
    val onSetClaudeLogsDirectory: (String) -> Unit = {},
    val onRefreshClaudeLogs: () -> Unit = {},
    /** How loud Claude Code's own sounds are, null where nothing here sets it, and the change. */
    val claudeSoundsVolume: () -> Double? = { null },
    val onSetClaudeSoundsVolume: (Double) -> Unit = {},
    /**
     * User rule 2026-10-18: "New" pressed in the group of ONE element of the kind ([from]) — what the default
     * configuration's "the day / the time of the one it is made from" reads ([org.example.project.scheduler.domain.NewElementTimes]).
     * Null: pressed anywhere else, which is [onCreate].
     */
    val onCreateFrom: (SearchDomain.Kind, SearchDomain.Result?) -> Unit = { kind, _ -> onCreate(kind) },
) {
    /**
     * These handlers, each one that opens a Search window run inside [around] (user rule 2026-10-18): what a Search
     * window is handed, so `App` knows which window an action ("New", "Search every element of the kind", "Blocks on the
     * calendar", "Search its tasks", "Edit") was pressed in and links the window it opens to it. The lateral menu's
     * items keep the plain ones: nothing they open is any Search window's.
     */
    fun askedThrough(around: (() -> Unit) -> Unit): AddedActionHandlers =
        copy(
            onEdit = { around { onEdit(it) } },
            onCreate = { around { onCreate(it) } },
            onCreateFrom = { kind, from -> around { onCreateFrom(kind, from) } },
            onOpenResilienceSearch = { around { onOpenResilienceSearch(it) } },
            onOpenKindSearch = { around { onOpenKindSearch(it) } },
            onOpenBlocksSearch = { around { onOpenBlocksSearch(it) } },
        )
}

/**
 * The Search window's top right quarter: the actions on every added element, for the kinds the list holds, narrowed
 * by the window's actions' filter ([SearchDomain.Config.actionQuery]) — the window of them all is a button away, as the
 * Configuration Search window is from the search.
 */
@Composable
internal fun AddedActionsSection(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    handlers: AddedActionHandlers,
    onIntent: (SchedulerIntent) -> Unit,
    nowMillis: () -> Long,
    onOpenEach: (List<SearchDomain.Result>) -> Unit,
    onClear: (List<SearchDomain.Result>) -> Unit,
    /** Whether the section is retracted to its head, and its arrow's press ([SectionArrow], user rule 2026-10-04). */
    collapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    /**
     * The group to scroll to ([SearchDomain.ActionGroup.id]) and the count of the request, so that asking twice for
     * the same group scrolls twice — the added elements' "show only its actions" (user rule 2026-10-13).
     */
    reveal: Pair<String, Int>? = null,
    /** User rule 2026-11-06: what stands at the right of the section's bar — the arrow to every search configuration. */
    configurationArrow: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    /** The room between the section's edges and what it shows — inside the area whose scroll it takes. */
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(),
) {
    CompactFields {
    Column(modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // No "All configurations" button (removed 2026-10-04): every action is listed right here.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionArrow(collapsed, onToggleCollapsed)
            Text(
                text = "Actions on the added elements",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f).togglesSection(onToggleCollapsed),
            )
        }
        if (collapsed) return@Column
        // The filter is set in the configurations window; said here, with its way off, so a quarter showing one
        // action is never a mystery.
        if (config.actionQuery.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The way off first: it is the part a narrow section must keep in view.
                FrameButton("Show all") { onConfigChange(config.copy(actionQuery = "")) }
                NoteText("Only the actions matching “${config.actionQuery.trim()}”")
            }
        }
        if (added.isEmpty()) {
            NoteText("Nothing is added yet: the actions act on every added element at once.")
            return@Column
        }
        val sections =
            SearchDomain.actionsFor(
                SearchDomain.addedActions(
                    config.actionQuery, SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(added),
                ),
                added,
            )
        // User rule 2026-11-12: the section IS a Search window — its search section alone, its bar the section's
        // ("Search an action", which sorts or filters, [SearchDomain.Config.actionSearchSortsOnly]), its arrow the section's
        // configurations; typing brings its list back to the top, as in any Search window.
        AddedActionList(
            state, sections, added, config, onConfigChange, handlers, onIntent, nowMillis, onOpenEach, onClear,
            ownBar = true,
            highlighted = config.actionSearch,
            noneListed = "No action matches.",
            reveal = reveal,
            modifier = Modifier.fillMaxWidth().weight(1f).keepsWidthAbove(COMPACT_SECTION_MIN_WIDTH),
        )
    }
    }
}

/**
 * User rule 2026-11-12: *"The action section is a Search window (hiding its header and its own action and added elements
 * list sections)."* **The actions, as the Search window lists them**, one drawing for the quarter and for the window of them
 * all: the real [SearchWindow], its search section alone, its rows the section's ([SearchDomain.actionListRows]: each group
 * a row whose children are its actions, every row closed by default) — result rows like any other, each opened by its
 * arrow, an open action's controls under it ([AddedActionEditor]). A lazy list, so a keystroke over hundreds of long log
 * entries lays out the rows on screen alone (measured 2026-11-02: 2 s as a plain column).
 *
 * [ownBar]: its bar is the section's ("Search an action", [SearchDomain.Config.actionSearch]); else the caller has one (the
 * window of them all's "Search a configuration") and [highlighted] is what it holds. The configurations arrow of the bar
 * edits the section's own: grouped or not, expanded, the bar sorting or filtering.
 */
@Composable
private fun AddedActionList(
    state: SchedulerState,
    sections: List<Pair<SearchDomain.Kind?, List<SearchDomain.AddedAction>>>,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    handlers: AddedActionHandlers,
    onIntent: (SchedulerIntent) -> Unit,
    nowMillis: () -> Long,
    onOpenEach: (List<SearchDomain.Result>) -> Unit,
    onClear: (List<SearchDomain.Result>) -> Unit,
    ownBar: Boolean,
    /** What the bar over the list holds, highlighted in it (user rule 2026-11-02, [HighlightsSearch]). */
    highlighted: String,
    /** Said in place of the rows where no action is listed. */
    noneListed: String,
    modifier: Modifier = Modifier,
    /** The group the list is to start at ([AddedActionsSection]'s). */
    reveal: Pair<String, Int>? = null,
) {
    // User rule 2026-10-10: the groups from the widest reach to the narrowest — every element, each kind holding
    // several, then each element's own ([SearchDomain.actionGroups]). A group's actions act on ITS elements.
    // User rule 2026-11-12: the bar sorts and hides nothing ([SearchDomain.Config.actionSearchSortsOnly]), else filters.
    val groups = SearchDomain.shownActionGroups(sections, added, config, handlers.claudeLogs())
    val listed = SearchDomain.listedGroupIds(groups)
    val rows = SearchDomain.actionListRows(groups, config)
    val latestConfig by rememberUpdatedState(config)
    val toggle = { id: String -> onConfigChange(SearchDomain.withActionGroupToggled(latestConfig, id, listed)) }
    // Its configuration is this window's: the actions its type, its bar the section's; what it changes of it comes back.
    val inner = config.copy(kinds = setOf(SearchDomain.Kind.Action), pageTypesOnly = false, query = highlighted, added = emptyList())
    val onInner = { next: SearchDomain.Config ->
        val current = latestConfig
        onConfigChange(
            current.copy(
                actionSearch = if (ownBar) next.query else current.actionSearch,
                actionsGrouped = next.actionsGrouped,
                actionsExpanded = next.actionsExpanded,
                actionSearchSortsOnly = next.actionSearchSortsOnly,
                toggledActionRows = next.toggledActionRows,
            ),
        )
    }
    var splits by remember { mutableStateOf(if (ownBar) EMBEDDED_SEARCH_SPLITS else EMBEDDED_SEARCH_SPLITS.copy(barRemoved = true)) }
    HighlightsSearch(highlighted) {
        SearchWindow(
            state = state,
            openers = OPTION_OPENERS,
            onStartTaskNow = {},
            onGoToTaskTree = { _, _ -> },
            onDeepCopyCell = {},
            actionHandlers = handlers,
            onIntent = onIntent,
            onDismiss = {},
            config = inner,
            onConfigChange = onInner,
            onOpenConfigurations = {},
            splits = splits,
            onSplitsChange = { splits = it },
            embedded = true,
            actionRows = ActionSearchRows(
                rows = rows,
                onToggle = toggle,
                content = { row ->
                    val action = row.action
                    if (action != null) {
                        val members = row.group.members
                        // Every action goes through the app's own intents ([SearchDomain.addedIntents]), over its group's elements.
                        val run = { command: SearchDomain.AddedCommand ->
                            SearchDomain.addedIntents(state, members, command, nowMillis()).forEach(onIntent)
                        }
                        AddedActionEditor(
                            state, action, members, config, onConfigChange, handlers, run, nowMillis,
                            onOpenEach = { onOpenEach(members) }, onClear = { onClear(members) }, titled = false, rowId = row.id,
                        )
                    }
                },
                // User rule 2026-10-08: while the lateral menu is being customized, a right-click on an action offers "add in
                // the left-side menu" — the action, with the elements it acts on here ([AddableAction]).
                wrap = { row, draw ->
                    val action = row.action
                    if (action == null) draw() else AddableAction(action, row.group.members, config) { draw() }
                },
                reveal = reveal,
                noneListed = noneListed,
            ),
            modifier = modifier,
        )
    }
}

/** How far right the actions inside a micro group stand of it (user rule 2026-10-18: "set them a little to the right"). */
private val NESTED_ACTION_INDENT = 16.dp

/**
 * How far right a micro group's title stands of its group of elements' (user rule 2026-10-19: *"the micro group titles a
 * little more to the right (with sub titles even more to the right)"*). A title inside a micro group is set this far right
 * of the actions it stands among, which are [NESTED_ACTION_INDENT] in already: a level further.
 */
private val MICRO_TITLE_INDENT = 12.dp

/**
 * **The title of a micro group** of the actions section — one action, or a group of them inside a group of elements
 * (the default configuration of a kind). ONE style for all of them (user rule 2026-10-11: *"right now Configuration is
 * bold, but not Add to the calendar"*): the label's medium weight, in the text's own colour — a group of ELEMENTS
 * keeps the accent colour, so the two levels still read apart.
 *
 * [nested]: an action inside a micro group (anomaly 2026-10-18: *"in 'Default configuration of a new alarm', there are
 * sub groups that have the same title typo as a micro groups"*) — the next size down, in the muted colour, so the three
 * levels read apart: the group of elements, the micro group, what is inside it.
 */
@Composable
private fun MicroGroupTitle(text: String, nested: Boolean = false) {
    Text(
        searchHighlighted(text),
        style = if (nested) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
        color = if (nested) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The actions that ARE a control of the app the menu already knows ([MenuControl]): added as that control. User rule
 * 2026-11-04: **every control of the menu is one of them** — each item of the menu is a window, or an action found in
 * the Search window's action section (`MenuControlsAreActionsTest`).
 */
internal val ACTION_MENU_CONTROLS: Map<SearchDomain.AddedAction, MenuControl> =
    mapOf(
        SearchDomain.AddedAction.VoiceSwitch to MenuControl.Voice,
        SearchDomain.AddedAction.NotificationsSwitch to MenuControl.Notifications,
        SearchDomain.AddedAction.SoundVolume to MenuControl.SoundVolume,
        SearchDomain.AddedAction.PeriodSleepSchedule to MenuControl.SleepSchedule,
        SearchDomain.AddedAction.ClaudeLogsDirectory to MenuControl.ClaudeLogsDirectory,
        SearchDomain.AddedAction.ClaudeSoundsVolume to MenuControl.ClaudeSoundsVolume,
        SearchDomain.AddedAction.MuteSwitch to MenuControl.MuteAll,
        SearchDomain.AddedAction.LookAwayNowControl to MenuControl.LookAwayNow,
        SearchDomain.AddedAction.SwitchTaskControl to MenuControl.SwitchTask,
        SearchDomain.AddedAction.SleepWorkControl to MenuControl.SleepWork,
        SearchDomain.AddedAction.AwayControl to MenuControl.Away,
        SearchDomain.AddedAction.OnlineControl to MenuControl.Online,
        SearchDomain.AddedAction.AutoScheduleControl to MenuControl.AutoSchedule,
        SearchDomain.AddedAction.RemindersControl to MenuControl.Reminders,
        SearchDomain.AddedAction.ScreenBreaksControl to MenuControl.ScreenBreaks,
        SearchDomain.AddedAction.CalendarDisplayControl to MenuControl.CalendarDisplay,
        SearchDomain.AddedAction.PlanTimeLimitControl to MenuControl.PlanTimeLimit,
        SearchDomain.AddedAction.MinimumTimeWeightControl to MenuControl.MinimumTimeWeight,
        SearchDomain.AddedAction.SupabaseUsageControl to MenuControl.SupabaseUsage,
        SearchDomain.AddedAction.MemoryLimitsControl to MenuControl.MemoryLimits,
        SearchDomain.AddedAction.ServerControl to MenuControl.Server,
    )

/**
 * User rule 2026-11-12: the calendar element's actions that are the account's switches the calendar window held —
 * drawn by the menu's own code, as the app settings' are ([ACTION_MENU_CONTROLS]).
 */
internal val CALENDAR_ACTION_CONTROLS: Map<SearchDomain.AddedAction, MenuControl> =
    mapOf(
        SearchDomain.AddedAction.CalendarAutoSchedule to MenuControl.AutoSchedule,
        SearchDomain.AddedAction.CalendarReminders to MenuControl.Reminders,
        SearchDomain.AddedAction.CalendarScreenBreaks to MenuControl.ScreenBreaks,
        SearchDomain.AddedAction.CalendarPlanTimeLimit to MenuControl.PlanTimeLimit,
        SearchDomain.AddedAction.CalendarMinimumTimeWeight to MenuControl.MinimumTimeWeight,
        SearchDomain.AddedAction.CalendarSleepSchedule to MenuControl.SleepSchedule,
    )

/** The calendar element's actions that are parts of the calendar itself, drawn by `App` ([LocalCalendarElement]). */
private val CALENDAR_ACTION_PARTS: Map<SearchDomain.AddedAction, CalendarPart> =
    mapOf(
        SearchDomain.AddedAction.CalendarGrid to CalendarPart.Grid,
        SearchDomain.AddedAction.CalendarDay to CalendarPart.Day,
        SearchDomain.AddedAction.CalendarDisplay to CalendarPart.Display,
        SearchDomain.AddedAction.CalendarLocks to CalendarPart.Locks,
    )

/**
 * User rule 2026-10-08 (anomaly: a right-click on an action in customize mode did nothing): **an action of the Search
 * window can be added to the lateral menu** — with this window's configuration as it stands, its added elements
 * included, so the item acts on THOSE elements. Named after the action, and after the element when there is one.
 */
@Composable
private fun AddableAction(
    action: SearchDomain.AddedAction,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    content: @Composable () -> Unit,
) {
    MenuAddableBox(
        key = action,
        onAdd = { customizer ->
            val control = ACTION_MENU_CONTROLS[action] ?: CALENDAR_ACTION_CONTROLS[action]
            if (control != null) {
                customizer.add(control)
            } else {
                val reached = added.filter { action.section == null || SearchDomain.actionKindOf(it) == action.section || it.kind == action.section }
                val title = action.label + (reached.singleOrNull()?.let { "  ·  " + it.name } ?: "")
                // The item acts on the elements of the group it was taken from, not on the whole list.
                customizer.addAction(action.name, title, config.copy(added = added.map(SearchDomain::keyOf)).encode())
            }
        },
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

/**
 * User rule 2026-10-08: **an action of the Search window, as an item of the lateral menu** ([CustomMenuButton.action])
 * — its own editor ([AddedActionEditor]) over the elements the item was added with ([configText], that window's
 * configuration then), read against the account as it is NOW. *"If then the button or field can't do anything, for
 * example it is the button 'duplicate' for a task that doesn't exist anymore, then the button is grayed"*:
 * [SearchDomain.actionCanAct] — greyed, and deaf to a press.
 */
@Composable
internal fun MenuActionItem(
    state: SchedulerState,
    actionName: String,
    title: String,
    configText: String?,
    onConfigChange: (String) -> Unit,
    handlers: AddedActionHandlers,
    onIntent: (SchedulerIntent) -> Unit,
    nowMillis: () -> Long,
    onOpen: (SearchDomain.Result) -> Unit,
) {
    val action = SearchDomain.AddedAction.entries.firstOrNull { it.name == actionName } ?: return
    val config = remember(configText) { SearchDomain.Config.decode(configText) ?: SearchDomain.Config() }
    // Walked when the account changes, as the Search window's own added list is.
    val added =
        remember(state.tasks, state.cells, state.lists, state.alarms, state.timers, state.chores, state.categories, config.added) {
            SearchDomain.resolve(state, config.added, claudeLogs = handlers.claudeLogs())
        }
    val can = SearchDomain.reachOf(action.section, added) > 0
    val run = { command: SearchDomain.AddedCommand ->
        SearchDomain.addedIntents(state, added, command, nowMillis()).forEach(onIntent)
    }
    Column(
        modifier = Modifier.fillMaxWidth().greyedAndDeaf(!can),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            title.ifBlank { action.label },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
        )
        // The editor is the Search window's: compact, and cut at the menu's width rather than squeezed.
        Box(Modifier.fillMaxWidth().cutAtBounds()) {
            CompactFields {
                AddedActionEditor(
                    state, action, added, config, { onConfigChange(it.encode()) }, handlers, run, nowMillis,
                    onOpenEach = { added.forEach(onOpen) }, onClear = null,
                )
            }
        }
    }
}

/**
 * The control of one action — every one of them acts on the added elements of its kind. **Its blocks stand one under the
 * other wherever it is held** (anomaly 2026-11-02: *"the Lowest countdown group title is overlapped by the text of the
 * group"*): many actions draw several, and a caller's `Box` laid them on one another — a title line under what follows
 * it in the section, every such action as an item of the lateral menu. So the column is here, not in each caller.
 * `ActionEditorLayoutTest`.
 */
@Composable
private fun AddedActionEditor(
    state: SchedulerState,
    action: SearchDomain.AddedAction,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    handlers: AddedActionHandlers,
    run: (SearchDomain.AddedCommand) -> Unit,
    nowMillis: () -> Long,
    onOpenEach: () -> Unit,
    /** Takes the elements off the added elements list; null where there is no list to take them off (the lateral menu). */
    onClear: (() -> Unit)?,
    /** The action's title is the editor's to write, for the ones that put buttons beside it; else its caller wrote it. */
    titled: Boolean = false,
    /** The action's row ([SearchDomain.microGroupId]), which its children rows are known under; the menu's item has its own. */
    rowId: String = "menu/" + action.name,
) {
    Column(verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP)) {
        AddedActionBlocks(state, action, added, config, onConfigChange, handlers, run, nowMillis, onOpenEach, onClear, titled, rowId)
    }
}

/** What [AddedActionEditor] draws: as many blocks as the action has, so never called but from it. */
@Composable
private fun AddedActionBlocks(
    state: SchedulerState,
    action: SearchDomain.AddedAction,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    handlers: AddedActionHandlers,
    run: (SearchDomain.AddedCommand) -> Unit,
    nowMillis: () -> Long,
    onOpenEach: () -> Unit,
    onClear: (() -> Unit)?,
    titled: Boolean,
    rowId: String,
) {
    val taskIds = SearchDomain.addedTaskIds(state, added)
    val tasks = taskIds.mapNotNull { state.tasks[it] }
    fun idsOf(kind: SearchDomain.Kind) =
        added.filterIsInstance<SearchDomain.ItemResult>().filter { it.kind == kind }.mapTo(HashSet()) { it.id }
    when (action) {
        // User rule 2026-11-04: a control of the lateral menu, as its setting's action — drawn by the menu's own code.
        SearchDomain.AddedAction.LookAwayNowControl, SearchDomain.AddedAction.SwitchTaskControl,
        SearchDomain.AddedAction.SleepWorkControl, SearchDomain.AddedAction.AwayControl, SearchDomain.AddedAction.OnlineControl,
        SearchDomain.AddedAction.AutoScheduleControl, SearchDomain.AddedAction.RemindersControl,
        SearchDomain.AddedAction.ScreenBreaksControl, SearchDomain.AddedAction.CalendarDisplayControl,
        SearchDomain.AddedAction.PlanTimeLimitControl, SearchDomain.AddedAction.MinimumTimeWeightControl,
        SearchDomain.AddedAction.SupabaseUsageControl, SearchDomain.AddedAction.MemoryLimitsControl,
        SearchDomain.AddedAction.ServerControl,
        ->
            ACTION_MENU_CONTROLS[action]?.let { control ->
                LocalMenuControlHost.current?.let { host -> MenuControlItem(control, null, host) }
            }
        // User rule 2026-11-12: the calendar element — the calendar and its view, drawn by `App` for this window; and the
        // account's switches the calendar window held, by the menu's code.
        SearchDomain.AddedAction.CalendarGrid, SearchDomain.AddedAction.CalendarDay, SearchDomain.AddedAction.CalendarDisplay,
        SearchDomain.AddedAction.CalendarLocks,
        -> CALENDAR_ACTION_PARTS[action]?.let { part -> LocalCalendarElement.current?.invoke(part) }
        SearchDomain.AddedAction.CalendarAutoSchedule, SearchDomain.AddedAction.CalendarReminders,
        SearchDomain.AddedAction.CalendarScreenBreaks, SearchDomain.AddedAction.CalendarPlanTimeLimit,
        SearchDomain.AddedAction.CalendarMinimumTimeWeight, SearchDomain.AddedAction.CalendarSleepSchedule,
        ->
            CALENDAR_ACTION_CONTROLS[action]?.let { control ->
                LocalMenuControlHost.current?.let { host -> MenuControlItem(control, null, host) }
            }
        // User rule 2026-10-13: the two buttons about the group's elements as rows of the list, in ONE micro group —
        // the first of every group of elements ([SearchDomain.actionGroups]).
        SearchDomain.AddedAction.OpenEach ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FrameButton(if (added.size == 1) "Open" else "Open all", enabled = added.isNotEmpty(), onClick = onOpenEach)
                if (onClear != null) FrameButton("Remove from list", enabled = added.isNotEmpty(), onClick = onClear)
            }
        // No longer listed ([SearchDomain.RETIRED_ACTIONS]): what an item of the lateral menu made from it still draws.
        SearchDomain.AddedAction.ClearList ->
            FrameButton("Remove from list", enabled = added.isNotEmpty() && onClear != null, onClick = { onClear?.invoke() })
        // A creation row leads to every element of the kind it makes: one button per kind among the added rows.
        SearchDomain.AddedAction.CreationSearchAll -> {
            val kinds = SearchDomain.creationKinds(added)
            if (kinds.isEmpty()) {
                NoteText("No creation row is added.", color = MaterialTheme.colorScheme.onSurface)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                kinds.forEach { kind -> FrameButton("Every " + kind.label) { handlers.onOpenKindSearch(kind) } }
            }
        }
        // The calendar's "add…": where its start and end say ([CalendarPlacementEditor]).
        SearchDomain.AddedAction.PlaceOnCalendar ->
            CalendarPlacementEditor(state, added, config, onConfigChange, handlers, run, nowMillis)
        SearchDomain.AddedAction.DragOnCalendar -> CalendarDragEditor(state, added, config, handlers, nowMillis)
        // User rule 2026-10-11: the blocks themselves, picked from a check-box list and edited in place
        // ([CalendarBlocksEditor]) — it was a button opening another Search window on them.
        SearchDomain.AddedAction.CalendarBlocks -> CalendarBlocksEditor(state, added, config, onConfigChange, handlers, run, nowMillis)
        SearchDomain.AddedAction.ElementParents -> ElementParentsEditor(state, added, config, onConfigChange, run, rowId)
        // Retired ([SearchDomain.RETIRED_ACTIONS]): what an item of the lateral menu made from one before still draws.
        SearchDomain.AddedAction.ElementAddParent -> ElementParentEditor(state, added, config, run)
        SearchDomain.AddedAction.ElementRemoveParent -> {
            val children = added.map(SearchDomain::keyOf)
            val categories = state.categories.mapTo(HashSet()) { org.example.project.scheduler.domain.ElementTree.categoryKey(it.id) }
            val parents = children.flatMap { org.example.project.scheduler.domain.ElementTree.parentsOf(state, it) }.distinct().filterNot { it in categories }
            ElementChooser("search/element-remove-parent", SearchDomain.resolve(state, parents, { emptyMap() }).map { SearchDomain.keyOf(it) to it.name }) {
                run(SearchDomain.AddedCommand.ElementParent(it, on = false))
            }
        }
        SearchDomain.AddedAction.ElementAddCategory ->
            ElementChooser(
                "search/element-add-category",
                state.categories.filter { it.kind == org.example.project.scheduler.model.CategoryKind.TaskId }.sortedBy { it.title.lowercase() }
                    .map { org.example.project.scheduler.domain.ElementTree.categoryKey(it.id) to it.title },
            ) { run(SearchDomain.AddedCommand.ElementParent(it, on = true)) }
        SearchDomain.AddedAction.ElementRemoveCategory -> {
            val carried = added.flatMapTo(HashSet()) { org.example.project.scheduler.domain.ElementTree.parentsOf(state, SearchDomain.keyOf(it)) }
            ElementChooser(
                "search/element-remove-category",
                state.categories.map { org.example.project.scheduler.domain.ElementTree.categoryKey(it.id) to it.title }.filter { it.first in carried }.sortedBy { it.second.lowercase() },
            ) { run(SearchDomain.AddedCommand.ElementParent(it, on = false)) }
        }
        SearchDomain.AddedAction.TaskAddCategory ->
            CategoryChooser(
                cellId = "search/task-add-category",
                // A task cell category is given to one occurrence ("Task cell categories"), never to a task.
                options = state.categories.filter { it.kind == org.example.project.scheduler.model.CategoryKind.TaskId }
                    .sortedBy { it.title.lowercase() }.map { it.id to it.title },
                enabled = tasks.isNotEmpty(),
            ) { run(SearchDomain.AddedCommand.Category(it, carried = true)) }
        // Only the categories an added task carries: the others have nothing to take off.
        SearchDomain.AddedAction.TaskRemoveCategory -> {
            val carried = tasks.flatMapTo(HashSet()) { it.categoryIds }
            CategoryChooser(
                cellId = "search/task-remove-category",
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
        // --- A task cell's right-click menu ----------------------------------------------------------
        // Starting, revealing and locking the calendar are about ONE task: offered when the list holds exactly one.
        SearchDomain.AddedAction.TaskStartNow -> {
            val placeable = taskIds.filter { SchedulerDomain.isPlaceableTask(state, it) }
            OneTaskButton("Start", placeable) { handlers.onStartNow(it) }
        }
        SearchDomain.AddedAction.TaskEdit ->
            FrameButton(if (taskIds.size == 1) "Edit" else "Edit ${taskIds.size}", enabled = taskIds.isNotEmpty()) {
                taskIds.forEach(handlers.onEdit)
            }
        SearchDomain.AddedAction.TaskGoToTree -> OneTaskButton("Go", taskIds) { handlers.onGoToTaskTree(it) }
        SearchDomain.AddedAction.TaskDefaultSubtree -> FrameButton("Open") { handlers.onOpenDefaultSubtree() }
        SearchDomain.AddedAction.TaskGoToCalendar -> {
            val calendar = LocalCalendarGoTo.current
            val single = taskIds.singleOrNull()
            val reach = remember(single, calendar) { single?.let { calendar?.reach(it) } }
            when {
                calendar == null || reach == null -> OneTaskButton("Go", taskIds) {}
                reach == CalendarLockDomain.Reach.None ->
                    NoteText("No panel of it can come")
                else -> FrameButton(if (reach == CalendarLockDomain.Reach.Pending) "Go (being placed)" else "Go") { single?.let(calendar.go) }
            }
        }
        SearchDomain.AddedAction.TaskCopyIds -> {
            val ids = taskIds.filter(SchedulerDomain::isUserTaskId)
            FrameButton(if (ids.size == 1) "Copy" else "Copy ${ids.size}", enabled = ids.isNotEmpty()) {
                writeSystemClipboardText(ids.joinToString("\n") { SchedulerDomain.TASK_ID_REFERENCE_PREFIX + it.value })
            }
        }
        // The entries that act on a CELL act on each task's first live one; a task no cell holds has none.
        SearchDomain.AddedAction.TaskDeepCopy -> {
            val cells = SearchDomain.addedTaskCells(state, added)
            FrameButton(if (cells.size == 1) "Deep copy" else "Deep copy ${cells.size}", enabled = cells.isNotEmpty()) {
                cells.forEach(handlers.onDeepCopyCell)
            }
        }
        SearchDomain.AddedAction.TaskCollapseSubtrees ->
            FrameButton("Collapse", enabled = SearchDomain.addedTaskCells(state, added).isNotEmpty()) {
                run(SearchDomain.AddedCommand.CollapseSubtrees)
            }
        SearchDomain.AddedAction.TaskAddDefaultSubtree ->
            if (state.defaultSubtreeIsEmpty) {
                NoteText("No default sub-tree")
            } else {
                FrameButton("Add", enabled = SearchDomain.addedTaskCells(state, added).isNotEmpty()) {
                    run(SearchDomain.AddedCommand.AddDefaultSubtree)
                }
            }
        // --- The task edit window's sections ---------------------------------------------------------
        SearchDomain.AddedAction.TaskAddUnder -> AddUnderEditor(state, taskIds, run)
        SearchDomain.AddedAction.TaskResilience -> ResilienceEditor(state, added, config, onConfigChange, run)
        SearchDomain.AddedAction.TaskScheduleUnit -> ScheduleUnitActionEditor(state, added, run)
        SearchDomain.AddedAction.TaskText -> {
            val shared = tasks.map { it.text }.distinct().singleOrNull()
            var draft by remember(shared) { mutableStateOf(shared.orEmpty()) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { if (shared == null && tasks.isNotEmpty()) Text("mixed") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FrameButton("Apply", enabled = tasks.any { it.text != draft }) { run(SearchDomain.AddedCommand.Text(draft)) }
            }
        }
        // User rule 2026-11-13: the name of the tree(s) the added tasks belong to — its root's, not a task's title.
        SearchDomain.AddedAction.TaskTreeName -> {
            val roots = SearchDomain.treeRootsOf(added).mapNotNull { state.tasks[it] }
            val shared = roots.map { it.treeName }.distinct().singleOrNull()
            var draft by remember(shared) { mutableStateOf(shared.orEmpty()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("Tree name") },
                    placeholder = { if (shared == null && roots.isNotEmpty()) Text("mixed") },
                    modifier = Modifier.weight(1f),
                )
                FrameButton("Apply", enabled = roots.any { it.treeName != draft }) { run(SearchDomain.AddedCommand.TreeName(draft)) }
            }
        }
        SearchDomain.AddedAction.AlarmOnOff -> {
            // Lit when every added alarm (and the default configuration, where it is added) is in that state.
            val shared = SearchDomain.sharedValue(SearchDomain.addedAlarmsOrDefault(state, added)) { it.enabled }
            Choices(listOf(true, false), shared, { if (it == true) "on" else "off" }) { on ->
                run(SearchDomain.AddedCommand.AlarmsEdit { it.copy(enabled = on == true) })
            }
        }
        SearchDomain.AddedAction.AlarmTimeNow ->
            FrameButton("Now", enabled = added.any { it.kind == SearchDomain.Kind.Alarm }) {
                run(SearchDomain.AddedCommand.AlarmsTimeNow)
            }
        SearchDomain.AddedAction.ReminderTimeNow ->
            FrameButton("Now", enabled = added.any { it.kind == SearchDomain.Kind.Reminder }) {
                run(SearchDomain.AddedCommand.RemindersTimeNow)
            }
        SearchDomain.AddedAction.AlarmTitle, SearchDomain.AddedAction.TimerTitle,
        SearchDomain.AddedAction.ChronoTitle, SearchDomain.AddedAction.ReminderTitle, SearchDomain.AddedAction.QuotaTitle,
        SearchDomain.AddedAction.WorkplaceTitle ->
            SharedTitleField(state, added, SearchDomain.TITLED_KINDS.getValue(action), run)
        // The sound setting's control: the app's global volume. Written on release, so a drag is one write.
        // User rule 2026-10-08: the voice's and the notifications' switches, the lateral menu's own by default — here
        // too, so a menu emptied of them can be given them back ([MenuAddable], while the menu is being customized).
        SearchDomain.AddedAction.VoiceSwitch ->
            androidx.compose.material3.Switch(
                checked = state.notificationVoiceEnabled,
                onCheckedChange = { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetNotificationVoice(it))) },
                enabled = SearchDomain.appSettingAdded(added, SearchDomain.AppSettingEntry.Voice),
            )
        // User rule 2026-10-18: every sound of the app off on this device — the lateral menu's switch, here too.
        // User rule 2026-11-13: an added search configuration's state in each Search window, by window id.
        SearchDomain.AddedAction.ConfigurationStates -> ConfigurationStatesAction(state, added, handlers.searchWindows(), handlers.claudeLogs())
        SearchDomain.AddedAction.MuteSwitch ->
            androidx.compose.material3.Switch(
                checked = state.soundMuted,
                onCheckedChange = { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetSoundMuted(it))) },
                enabled = SearchDomain.appSettingAdded(added, SearchDomain.AppSettingEntry.Mute),
            )
        SearchDomain.AddedAction.NotificationsSwitch ->
            androidx.compose.material3.Switch(
                checked = state.notificationsEnabled,
                onCheckedChange = handlers.onSetNotificationsEnabled,
                enabled = SearchDomain.appSettingAdded(added, SearchDomain.AppSettingEntry.Notifications),
            )
        SearchDomain.AddedAction.SoundVolume -> run {
            val enabled = SearchDomain.appSettingAdded(added, SearchDomain.AppSettingEntry.Sound)
            var draft by remember(state.soundVolume) { mutableStateOf(state.soundVolume.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Slider(
                    value = draft,
                    onValueChange = { draft = it },
                    onValueChangeFinished = { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetSoundVolume(draft.toDouble()))) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                Text("${SearchDomain.volumePercent(draft.toDouble())} %", style = MaterialTheme.typography.labelMedium)
            }
        }
        SearchDomain.AddedAction.TimerLowestCountdown ->
            LowestCountdownEditor(state, added, config, onConfigChange, handlers, run, nowMillis, action.label.takeIf { titled })
        SearchDomain.AddedAction.TimerRun -> {
            // PRD §18 / `alarms-and-timers.md`: a timer's run IS its countdown — the three fields reading down (and
            // editable, each by its own unit) and Elapsed, the countdown in reverse. Each added timer's own, drawn by
            // the Alarms window's row over the same callbacks; the buttons below them act on every added timer at once.
            TimerRunBlocks(SearchDomain.addedTimers(state, added), handlers, onStep = { run(SearchDomain.AddedCommand.TimersRun(it)) })
        }
        // User rule 2026-10-14 (anomaly: the chrono had only three buttons where the timer has its countdown): a chrono's
        // run IS its count — the Alarms window's row, its count and its buttons, each added chrono's own.
        SearchDomain.AddedAction.ChronoRun ->
            ChronoRunBlocks(
                SearchDomain.addedChronos(state, added), handlers, compact = config.compactChronos,
                onCompactChange = { onConfigChange(config.copy(compactChronos = it)) },
                onStep = { run(SearchDomain.AddedCommand.ChronosRun(it)) },
            )
        SearchDomain.AddedAction.ChronoHighestCount ->
            HighestCountEditor(state, added, config, onConfigChange, handlers, run, nowMillis, action.label.takeIf { titled })
        // --- A new element of the kind (in a Search window of its own), and a copy of each added one (joining these) ---
        SearchDomain.AddedAction.TaskNew, SearchDomain.AddedAction.CategoryNew, SearchDomain.AddedAction.PeriodNew,
        SearchDomain.AddedAction.AlarmNew, SearchDomain.AddedAction.TimerNew, SearchDomain.AddedAction.ChronoNew,
        SearchDomain.AddedAction.ReminderNew, SearchDomain.AddedAction.QuotaNew, SearchDomain.AddedAction.ShortcutNew,
        SearchDomain.AddedAction.WorkplaceNew -> {
            val kind = action.section ?: return
            // Made from the element whose group this is, when the group is one element's.
            FrameButton("New " + kind.label) { handlers.onCreateFrom(kind, added.singleOrNull { it.kind == kind }) }
        }
        SearchDomain.AddedAction.TaskDuplicate, SearchDomain.AddedAction.CategoryDuplicate,
        SearchDomain.AddedAction.PeriodDuplicate, SearchDomain.AddedAction.AlarmDuplicate,
        SearchDomain.AddedAction.TimerDuplicate, SearchDomain.AddedAction.ChronoDuplicate,
        SearchDomain.AddedAction.ReminderDuplicate, SearchDomain.AddedAction.QuotaDuplicate,
        SearchDomain.AddedAction.WorkplaceDuplicate -> {
            val kind = action.section ?: return
            val count = added.count { it.kind == kind }
            FrameButton(if (count == 1) "Duplicate" else "Duplicate $count", enabled = count > 0) {
                val made = handlers.onDuplicate(SearchDomain.duplicateIntents(state, added, kind, nowMillis()), kind)
                if (made.isNotEmpty()) onConfigChange(config.copy(added = SearchDomain.withAdded(config.added, made)))
            }
        }
        // --- The removed edit windows' contents, one block per added element ----------------------------
        // Every fact of each added history unit — the History window's own list of them ([historyEntryInfos]), each
        // with its copy button, and "Copy all" per unit. What opening the History window on the unit used to show.
        // The set of rules each added scheduler-engine row returned: its own block, so it is not looked for under
        // the rule state. A History Unit the user made returned none.
        // --- The keyboard shortcuts (user rule 2026-10-12): the chord of each, and a window shortcut's window ------
        SearchDomain.AddedAction.ShortcutChord -> ShortcutChordEditor(state, added, handlers, run)
        SearchDomain.AddedAction.ShortcutWindow -> ShortcutWindowEditor(added, handlers)
        SearchDomain.AddedAction.ShortcutDelete -> {
            // Only a chord for a window is the user's to delete: the app's own shortcuts stay.
            val rows = added.filter { windowShortcutIdOf(it) != null }
            FrameButton(if (rows.size == 1) "🗑 Delete" else "🗑 Delete ${rows.size}", enabled = rows.isNotEmpty()) {
                handlers.onDeleteWindowShortcuts(rows.mapNotNull(::windowShortcutIdOf))
                onConfigChange(config.copy(added = config.added - rows.map(SearchDomain::keyOf).toSet()))
            }
        }
        SearchDomain.AddedAction.HistoryRules -> {
            val runs = handlers.schedulerRuns()
            val rows = added.filterIsInstance<SearchDomain.ItemResult>()
                .filter { it.kind == SearchDomain.Kind.HistoryUnit && SearchDomain.isSchedulerRunId(it.id) }
            if (rows.isEmpty()) {
                NoteText("No history unit made in the scheduler engine is added: only those return a set of rules.")
            }
            for (row in rows) {
                val found = SearchDomain.schedulerRunOf(runs, row.id)
                ElementHeading(row.name, rows.size)
                if (found == null) {
                    // Runs are kept in memory for the session: one added before a restart is no longer held.
                    NoteText("This run is no longer in memory (the scheduler engine's runs are kept for the session only).")
                    continue
                }
                val text = found.rules.joinToString("\n").ifBlank { "(the scheduler placed nothing)" }
                // A text the unit stores: one line, its arrow and its copy button ([HistoryTextLine]).
                HistoryTextLine(value = text, copyLabel = "Copy the set of rules")
            }
        }
        // --- The Claude Code logs (user rule 2026-10-18): what each added entry says, and where they are read from ---
        SearchDomain.AddedAction.PromptText, SearchDomain.AddedAction.PromptSession, SearchDomain.AddedAction.AnswerText,
        SearchDomain.AddedAction.AnswerPrompt, SearchDomain.AddedAction.AnswerSession, SearchDomain.AddedAction.ReasoningText,
        SearchDomain.AddedAction.ReasoningPrompt, SearchDomain.AddedAction.ReasoningSession ->
            ClaudeEntryAction(action, added, handlers.claudeLogs())
        SearchDomain.AddedAction.WorkplaceOsChoice -> {
            val setups = SearchDomain.addedWorkplaceSetupsOrDefault(state, added)
            val common = setups.map { it.os }.distinct().singleOrNull()
            ChoiceDropDown("workplace operating system",
                options = org.example.project.scheduler.domain.WorkplaceOs.entries,
                selected = common,
                label = { it.label },
                onSelect = { os ->
                    SearchDomain.workplaceEdits(state, setups.mapTo(HashSet()) { it.id }) { it.copy(os = os) }
                        .forEach { run(SearchDomain.AddedCommand.Raw(it)) }
                },
            )
        }
        SearchDomain.AddedAction.WorkplaceWindows -> WorkplaceWindowsEditor(state, added, run)
        SearchDomain.AddedAction.WorkplaceApply ->
            WorkplaceApplyButton(SearchDomain.addedWorkplaceSetups(state, added), handlers.workplaceRuns) {
                org.example.project.scheduler.domain.ElementTree.workplaceFamily(state, it.id)
            }
        SearchDomain.AddedAction.ClaudeSoundsVolume ->
            ClaudeSoundsVolumeSlider(
                handlers.claudeSoundsVolume(), handlers.onSetClaudeSoundsVolume, action.label,
                enabled = SearchDomain.appSettingAdded(added, SearchDomain.AppSettingEntry.ClaudeSounds),
            )
        SearchDomain.AddedAction.ClaudeLogsDirectory ->
            ClaudeLogsDirectoryField(
                handlers.claudeLogs(), handlers.onSetClaudeLogsDirectory, handlers.onRefreshClaudeLogs,
                enabled = SearchDomain.appSettingAdded(added, SearchDomain.AppSettingEntry.ClaudeLogs),
            )
        SearchDomain.AddedAction.HistoryInformation -> {
            // A row is a History Unit, or a set of rules the scheduler engine found — whose facts are the rule state it
            // read and the rules it returned ([historyEntryInfos], the one statement of what each kind of row holds).
            val runs = handlers.schedulerRuns()
            val units: List<Pair<String, FilteredHistoryEntry>> =
                added.filterIsInstance<SearchDomain.ItemResult>()
                    .filter { it.kind == SearchDomain.Kind.HistoryUnit }
                    .mapNotNull { row ->
                        if (SearchDomain.isSchedulerRunId(row.id)) {
                            SearchDomain.schedulerRunOf(runs, row.id)?.let { row.name to FilteredHistoryEntry.SchedulerRun(it) }
                        } else {
                            historyUnitEntryOfSearchId(state, row.id)?.let { it.unit.delta.label to it }
                        }
                    }
            if (units.isEmpty()) {
                NoteText("No history unit is added.", color = MaterialTheme.colorScheme.onSurface)
            }
            for ((label, entry) in units) {
                val infos = historyEntryInfos(entry)
                ElementHeading(label, units.size)
                infos.forEach { HistoryInfoLine(it) }
                HistoryCopyButton(label = "Copy all", value = infos.joinToString("\n") { "${it.label}: ${it.value}" })
            }
        }
        SearchDomain.AddedAction.TaskFulfilment -> FulfilmentEditor(state, added, run, handlers.onEdit)
        SearchDomain.AddedAction.TaskFulfilledBy -> FulfilledByEditor(state, taskIds, run, handlers.onEdit)
        // --- The quotas (user rule 2026-10-03): where each stands, and its settings ([QuotaEditors]) -------------
        SearchDomain.AddedAction.QuotaProgress -> QuotaProgressEditor(state, addedQuotas(state, added), nowMillis)
        SearchDomain.AddedAction.QuotaLookTimes -> QuotaLookTimesEditor(state, addedQuotas(state, added), run.asIntentSink(), nowMillis)
        SearchDomain.AddedAction.QuotaAmount -> QuotaAmountEditor(state, addedQuotas(state, added), run.asIntentSink())
        SearchDomain.AddedAction.QuotaLoop -> QuotaLoopEditor(state, addedQuotas(state, added), run.asIntentSink())
        SearchDomain.AddedAction.QuotaRestarts -> QuotaRestartsEditor(state, addedQuotas(state, added), run.asIntentSink())
        SearchDomain.AddedAction.QuotaResilience -> QuotaResilienceEditor(state, addedQuotas(state, added), run.asIntentSink())
        SearchDomain.AddedAction.QuotaLoops -> QuotaLoopsEditor(state, addedQuotas(state, added), run.asIntentSink(), nowMillis)
        SearchDomain.AddedAction.TaskCellCategories -> TaskCellCategoriesEditor(state, taskIds, run)
        SearchDomain.AddedAction.TaskChildren -> {
            val children = LocalTaskChildren.current
            for (taskId in taskIds) {
                ElementHeading(state.tasks[taskId]?.title.orEmpty(), taskIds.size)
                val listId = state.tasks[taskId]?.childListId?.takeIf { state.lists[it]?.cellIds?.isNotEmpty() == true }
                when {
                    listId == null -> NoteText("No child. \"Add under\" adds one.", color = MaterialTheme.colorScheme.onSurface)
                    children == null -> Unit
                    else -> children(taskId, listId)
                }
            }
        }
        SearchDomain.AddedAction.TaskPaths -> {
            for (taskId in taskIds) {
                val places = remember(taskId, state.cells, state.lists, state.tasks) { TaskPathsDomain.occurrences(state, taskId) }
                ElementHeading(state.tasks[taskId]?.title.orEmpty(), taskIds.size)
                if (places.isEmpty()) {
                    NoteText("In no place of the open task tree.", color = MaterialTheme.colorScheme.onSurface)
                }
                for (place in places) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(place.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        // Not for its last place: deleting a task is the tree's blank title.
                        if (places.size > 1) {
                            FrameButton("✕") { run(SearchDomain.AddedCommand.RemovePath(place.cellId)) }
                        }
                    }
                }
            }
        }
        // User rule 2026-10-03: the task cell category switch, over every added category — lit when every one of them
        // is carried by task cells, with "mixed" beside it when they differ.
        SearchDomain.AddedAction.CategoryKind -> {
            val categories = SearchDomain.addedIds(added, SearchDomain.Kind.Category).mapNotNull { state.categoryById(CategoryId(it)) }
            val kinds = categories.map { it.kind }.distinct()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Switch(
                    checked = kinds.singleOrNull() == org.example.project.scheduler.model.CategoryKind.TaskCell,
                    enabled = categories.isNotEmpty(),
                    onCheckedChange = { on ->
                        val kind =
                            if (on) org.example.project.scheduler.model.CategoryKind.TaskCell
                            else org.example.project.scheduler.model.CategoryKind.TaskId
                        categories.forEach { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetCategoryKind(it.id, kind))) }
                    },
                )
                Text(
                    when {
                        kinds.size > 1 -> "mixed"
                        kinds.singleOrNull() == org.example.project.scheduler.model.CategoryKind.TaskCell -> "carried by task cells"
                        else -> "carried by task ids"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // --- The categories' settings: ONE control each, over every added category (user rule 2026-10-02) --------
        SearchDomain.AddedAction.CategoryName -> {
            val categories = SearchDomain.addedIds(added, SearchDomain.Kind.Category).mapNotNull { state.categoryById(CategoryId(it)) }
            // A name is unique, so several categories cannot be given one: the field is the ONE category's.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SharedTextField(
                    items = categories.takeIf { it.size == 1 }.orEmpty(),
                    idOf = { it.id.value },
                    field = "Category/name",
                    read = { it.title },
                    parse = { text -> if (text.isBlank()) null else { c: Category -> c.copy(title = text) } },
                    restore = { category, before -> category.copy(title = before.title) },
                    write = { _, change ->
                        categories.forEach { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.RenameCategory(it.id, change(it).title))) }
                    },
                    wide = true,
                )
                if (categories.size > 1) {
                    Text(
                        "A name is unique: keep one category in the list to rename it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SearchDomain.AddedAction.CategoryRules ->
            CategoriesRulesEditor(
                state,
                SearchDomain.addedIds(added, SearchDomain.Kind.Category).map(::CategoryId).filter { state.categoryById(it) != null },
                run.asIntentSink(),
            )
        SearchDomain.AddedAction.CategoryAddRule ->
            CategoriesAddRule(
                state,
                SearchDomain.addedIds(added, SearchDomain.Kind.Category).map(::CategoryId).filter { state.categoryById(it) != null },
                run.asIntentSink(),
            )
        // --- The alarms' settings: ONE field each, over every added alarm (user rule 2026-10-02) ----------------
        SearchDomain.AddedAction.AlarmTime ->
            // The default configuration's: WHEN a new alarm is set (user rule 2026-10-18), not a date and a time.
            if (SearchDomain.addedAlarms(state, added).isEmpty() && SearchDomain.defaultConfigurationAdded(added, SearchDomain.Kind.Alarm)) {
                NewElementTimesEditor(state.newAlarmTimes, withDate = true) { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetNewAlarmTimes(it))) }
            } else
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val alarms = SearchDomain.addedAlarms(state, added)
                val tz = remember { kotlinx.datetime.TimeZone.currentSystemDefault() }
                // User rule 2026-10-18: the DATE — the one day the alarm rings on, or none (it rings on its days).
                val dates = alarms.map { it.onlyOnEpochDay }.distinct()
                DayField(
                    dates.singleOrNull()?.let { kotlinx.datetime.LocalDate.fromEpochDays(it.toInt()) },
                    blank = if (dates.size > 1) "mixed" else "on its days",
                    today = localOf(nowMillis(), tz).date,
                    enabled = alarms.isNotEmpty(),
                ) { date -> run(SearchDomain.AddedCommand.AlarmsEdit { it.copy(onlyOnEpochDay = date.toEpochDays().toLong()) }) }
                if (alarms.any { it.onlyOnEpochDay != null }) {
                    FrameButton("✕") { run(SearchDomain.AddedCommand.AlarmsEdit { it.copy(onlyOnEpochDay = null) }) }
                }
                NoteText("at", color = MaterialTheme.colorScheme.onSurface)
                SharedTextField(
                    items = alarms,
                    idOf = { it.id },
                    field = "Alarm/time",
                    read = { formatAlarmTime(it.timeOfDayMinutes) },
                    parse = { text -> parseAlarmTime(text)?.let { minutes -> { a: AlarmEntry -> a.copy(timeOfDayMinutes = minutes) } } },
                    restore = { alarm, before -> alarm.copy(timeOfDayMinutes = before.timeOfDayMinutes) },
                    write = { key, change -> run(SearchDomain.AddedCommand.AlarmsEdit(key, change)) },
                    timeNudge = { alarm, delta -> alarm.copy(timeOfDayMinutes = nudgedTimeOfDay(alarm.timeOfDayMinutes, delta)) },
                )
                // User rule 2026-10-18: where the calendar was right-clicked — its day and its time — for a window that came
                // from there.
                InstantButtons(enabled = alarms.isNotEmpty()) { at ->
                    val clicked = localOf(at, tz)
                    run(
                        SearchDomain.AddedCommand.AlarmsEdit {
                            it.copy(onlyOnEpochDay = clicked.date.toEpochDays().toLong(), timeOfDayMinutes = clicked.hour * 60 + clicked.minute)
                        },
                    )
                }
            }
        SearchDomain.AddedAction.AlarmRingsFor ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SharedTextField(
                    items = SearchDomain.addedAlarmsOrDefault(state, added),
                    idOf = { it.id },
                    field = "Alarm/sound",
                    read = { it.soundSeconds.toString() },
                    parse = { text -> parseSoundSeconds(text)?.let { seconds -> { a: AlarmEntry -> a.copy(soundSeconds = seconds) } } },
                    restore = { alarm, before -> alarm.copy(soundSeconds = before.soundSeconds) },
                    write = { key, change -> run(SearchDomain.AddedCommand.AlarmsEdit(key, change)) },
                )
                NoteText("s", color = MaterialTheme.colorScheme.onSurface)
            }
        SearchDomain.AddedAction.AlarmDays -> {
            val alarms = SearchDomain.addedAlarmsOrDefault(state, added)
            // Lit: a day EVERY added alarm rings on. A press sets it for all of them, or takes it off all of them.
            val shown = alarms.map { it.days }.reduceOrNull { all, days -> all intersect days }.orEmpty()
            DayChips(
                days = shown,
                onChange = { edited -> run(SearchDomain.AddedCommand.AlarmsEdit { it.copy(days = SearchDomain.withDaysChange(it.days, shown, edited)) }) },
                emptyLabel = if (alarms.isEmpty()) "" else "no day in common",
            )
        }
        // The bin, over every added element of the kind; what it deleted leaves the added list too.
        SearchDomain.AddedAction.AlarmDelete, SearchDomain.AddedAction.TimerDelete, SearchDomain.AddedAction.ChronoDelete,
        SearchDomain.AddedAction.QuotaDelete, SearchDomain.AddedAction.WorkplaceDelete,
        SearchDomain.AddedAction.ReminderDelete, SearchDomain.AddedAction.CategoryDelete -> {
            val kind = action.section ?: return
            val keys = added.filter { it.kind == kind }.map(SearchDomain::keyOf)
            FrameButton(if (keys.size == 1) "🗑 Delete" else "🗑 Delete ${keys.size}", enabled = keys.isNotEmpty()) {
                run(SearchDomain.AddedCommand.Delete(kind))
                onConfigChange(config.copy(added = config.added - keys.toSet()))
            }
        }
        // --- The timers' settings: ONE field each, over every added timer ----------------------------------------
        SearchDomain.AddedAction.TimerDuration ->
            SharedTextField(
                items = SearchDomain.addedTimersOrDefault(state, added),
                idOf = { it.id },
                field = "Timer/duration",
                read = { TimerDomain.formatDuration(it.durationSeconds) },
                parse = { text -> parseDurationSeconds(text)?.let { seconds -> { t: TimerEntry -> t.copy(durationSeconds = seconds) } } },
                restore = { timer, before -> timer.copy(durationSeconds = before.durationSeconds) },
                write = { key, change -> run(SearchDomain.AddedCommand.TimersEdit(key, change)) },
            )
        SearchDomain.AddedAction.TimerRingsFor ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SharedTextField(
                    items = SearchDomain.addedTimersOrDefault(state, added),
                    idOf = { it.id },
                    field = "Timer/sound",
                    read = { it.soundSeconds.toString() },
                    parse = { text -> parseSoundSeconds(text)?.let { seconds -> { t: TimerEntry -> t.copy(soundSeconds = seconds) } } },
                    restore = { timer, before -> timer.copy(soundSeconds = before.soundSeconds) },
                    write = { key, change -> run(SearchDomain.AddedCommand.TimersEdit(key, change)) },
                )
                NoteText("s", color = MaterialTheme.colorScheme.onSurface)
            }
        SearchDomain.AddedAction.TimerBelowZero -> {
            val shared = SearchDomain.sharedValue(SearchDomain.addedTimersOrDefault(state, added)) { it.goesNegative }
            Choices(listOf(true, false), shared, { if (it == true) "on" else "off" }) { on ->
                run(SearchDomain.AddedCommand.TimersEdit { it.copy(goesNegative = on == true) })
            }
        }
        SearchDomain.AddedAction.TimerAlert -> {
            val shown = SearchDomain.sharedAlert(SearchDomain.addedTimersOrDefault(state, added).map { it.alert })
            if (shown != null) {
                AlertSettingsEditor(
                    alert = shown,
                    onChange = { edited ->
                        run(SearchDomain.AddedCommand.TimersEdit { it.copy(alert = SearchDomain.withAlertChange(it.alert, shown, edited)) })
                    },
                )
            }
        }
        // --- The reminders' settings: ONE field each, over every added reminder ----------------------------------
        SearchDomain.AddedAction.ReminderEvery -> {
            val reminders = SearchDomain.addedRemindersOrDefault(state, added)
            // `docs/scheduler_input_requirements.md`: a tag moved off the cadence is its exception, said under it.
            val exceptions =
                remember(state.panels, state.chores, reminders) {
                    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
                    reminders.flatMap { org.example.project.scheduler.domain.PatternExceptions.ofReminder(state, it.id, tz) }
                }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SharedTextField(
                    items = reminders,
                    idOf = { it.id.ifEmpty { it.title } },
                    field = "Reminder/every",
                    read = SearchDomain::reminderEveryText,
                    sanitize = ::sanitizeFormula,
                    parse = { text -> { c: ChoreEntry -> SearchDomain.withReminderEvery(c, text, c.recurrenceUnit) } },
                    restore = { chore, before -> chore.copy(daysFormula = before.daysFormula, spanDays = before.spanDays) },
                    write = { _, change -> run(SearchDomain.AddedCommand.RemindersEdit(change)) },
                )
                // The unit every added reminder has; with several, the first one's — picking one gives it to all.
                reminders.firstOrNull()?.let { first ->
                    RecurrenceUnitDropdown(
                        unit = SearchDomain.sharedValue(reminders) { it.recurrenceUnit } ?: first.recurrenceUnit,
                        onSelect = { unit ->
                            run(SearchDomain.AddedCommand.RemindersEdit { SearchDomain.withReminderEvery(it, SearchDomain.reminderEveryText(it), unit) })
                        },
                    )
                }
            }
            PatternExceptionsNote(exceptions)
            }
        }
        SearchDomain.AddedAction.ReminderTime ->
            if (SearchDomain.addedReminders(state, added).isEmpty() && SearchDomain.defaultConfigurationAdded(added, SearchDomain.Kind.Reminder)) {
                NewElementTimesEditor(state.newReminderTimes, withDate = false) { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetNewReminderTimes(it))) }
            } else
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val reminders = SearchDomain.addedReminders(state, added)
                SharedTextField(
                    items = reminders,
                    idOf = { it.id.ifEmpty { it.title } },
                    field = "Reminder/time",
                    read = { formatTimeOfDay(it.timeOfDayMinutes) },
                    sanitize = ::sanitizeTimeOfDay,
                    parse = { text -> { c: ChoreEntry -> c.copy(timeOfDayMinutes = parseTimeOfDay(text)) } },
                    restore = { chore, before -> chore.copy(timeOfDayMinutes = before.timeOfDayMinutes) },
                    write = { _, change -> run(SearchDomain.AddedCommand.RemindersEdit(change)) },
                    // A time not defined (the tag goes at the current time) has nothing to step from.
                    timeNudge = { chore, delta ->
                        if (chore.timeOfDayMinutes < 0) chore else chore.copy(timeOfDayMinutes = nudgedTimeOfDay(chore.timeOfDayMinutes, delta))
                    },
                )
                // User rule 2026-10-18: where the calendar was right-clicked, for a window that came from there.
                InstantButtons(enabled = reminders.isNotEmpty()) { at ->
                    run(SearchDomain.AddedCommand.RemindersEdit { it.copy(timeOfDayMinutes = minuteOfDayOf(at)) })
                }
            }
        SearchDomain.AddedAction.ReminderConstraint -> {
            val reminders = SearchDomain.addedReminders(state, added)
            val shared = SearchDomain.sharedValue(reminders) { it.constrainedToReminderId }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FrameButton("Choose…", enabled = reminders.isNotEmpty()) {
                    handlers.onEditReminderConstraint(reminders.map { it.id })
                }
                Text(
                    text = when {
                        reminders.isEmpty() -> ""
                        shared == null -> "(they differ)"
                        shared.isBlank() -> "(none)"
                        else -> SchedulerDomain.reminderTitleForId(state, shared) ?: "(none)"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (reminders.any { it.constrainedToReminderId.isNotBlank() }) {
                    FrameButton("✕") { run(SearchDomain.AddedCommand.RemindersEdit { it.copy(constrainedToReminderId = "") }) }
                }
            }
        }
        SearchDomain.AddedAction.ReminderAlert -> {
            val shown = SearchDomain.sharedAlert(SearchDomain.addedRemindersOrDefault(state, added).map { it.alert })
            if (shown != null) {
                AlertSettingsEditor(
                    alert = shown,
                    onChange = { edited ->
                        run(SearchDomain.AddedCommand.RemindersEdit { it.copy(alert = SearchDomain.withAlertChange(it.alert, shown, edited)) })
                    },
                )
            }
        }
        SearchDomain.AddedAction.AlarmRepeat -> {
            // Lit when every added alarm is in that state; neither chip when they differ.
            val shared = SearchDomain.sharedValue(SearchDomain.addedAlarmsOrDefault(state, added)) { it.repeats }
            Choices(listOf(true, false), shared, { if (it == true) "on" else "off" }) { on ->
                run(SearchDomain.AddedCommand.AlarmsEdit { it.copy(repeats = on == true) })
            }
        }
        SearchDomain.AddedAction.AlarmAlert -> {
            val shown = SearchDomain.sharedAlert(SearchDomain.addedAlarmsOrDefault(state, added).map { it.alert })
            if (shown != null) {
                // Only what is pressed is written: the channels and the tone nobody touched stay each alarm's own.
                AlertSettingsEditor(
                    alert = shown,
                    onChange = { edited ->
                        run(SearchDomain.AddedCommand.AlarmsEdit { it.copy(alert = SearchDomain.withAlertChange(it.alert, shown, edited)) })
                    },
                )
            }
        }
        // User rule 2026-10-13: a calendar window whose chips are the group's notifications.
        SearchDomain.AddedAction.NotificationCalendar -> {
            val ids = org.example.project.scheduler.domain.CalendarNotifications.idsOf(added)
            FrameButton(
                if (ids.size == 1) "Open a calendar with it" else "Open a calendar with these ${ids.size}",
                enabled = ids.isNotEmpty(),
            ) { handlers.onOpenNotificationsCalendar(ids) }
        }
        // User rule 2026-10-13: the Sleep schedule's fields, the Sleep window's own ([SleepScheduleFields]) — written
        // through the one intent every other place that shows them writes.
        SearchDomain.AddedAction.PeriodSleepSchedule -> {
            val tz = remember { kotlinx.datetime.TimeZone.currentSystemDefault() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(320.dp)) {
                SleepScheduleFields(
                    state.sleep ?: org.example.project.scheduler.model.SleepSchedule(),
                    onSave = { edited ->
                        val today = kotlin.time.Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(tz).date.toEpochDays().toLong()
                        run.asIntentSink()(SchedulerIntent.SetSleepSchedule(edited, today))
                    },
                    fieldModifier = Modifier.leaveFocusOnOutsidePress(),
                )
            }
        }
        SearchDomain.AddedAction.PeriodDrawing -> {
            val periods = SearchDomain.addedPeriodKinds(state, added)
            val shared = periods.map { state.periodKindConfig.drawing(it) }.distinct().singleOrNull()
            ChoiceDropDown("period drawing",
                options = PeriodDrawing.entries,
                selected = shared,
                label = { it.label },
                onSelect = { run(SearchDomain.AddedCommand.Drawing(it)) },
                placeholder = if (periods.isEmpty()) "no period" else "mixed",
                enabled = periods.isNotEmpty(),
                leading = { DrawingSwatch(it) },
                modifier = Modifier.width(220.dp),
            )
        }
        // User rule 2026-10-15: how long before each period of a kind the user is told it is coming — per period, a
        // length per notification, through the one intent.
        SearchDomain.AddedAction.PeriodNotices -> {
            val periods = SearchDomain.addedPeriodKinds(state, added)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in periods) {
                    ElementHeading(PeriodKinds.periodTitle(kind), periods.size)
                    PeriodNoticesEditor(state.periodKindConfig.notices(kind)) { leads ->
                        run.asIntentSink()(SchedulerIntent.SetPeriodNotices(kind, leads))
                    }
                }
            }
        }
        // User rule 2026-10-16: whether the period's notifications list the timeline during it and right after — ONE
        // switch over every added period, "mixed" beside it when they differ.
        SearchDomain.AddedAction.PeriodTellsTimeline -> {
            val periods = SearchDomain.addedPeriodKinds(state, added)
            val values = periods.map { state.periodKindConfig.tellsTimeline(it) }.distinct()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Switch(
                    checked = values.singleOrNull() == true,
                    enabled = periods.isNotEmpty(),
                    onCheckedChange = { on ->
                        periods.forEach { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetPeriodTellsTimeline(it, on))) }
                    },
                )
                Text(
                    when {
                        values.size > 1 -> "mixed"
                        values.singleOrNull() == true -> "what the timeline holds during the period and right after"
                        else -> "the notifications say only the period"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SearchDomain.AddedAction.PeriodCombinations -> {
            // ONE list: every rule naming an added period, once (it was a section per period, a rule naming two of
            // them drawn twice).
            val periods = SearchDomain.addedPeriodKinds(state, added)
            if (periods.isNotEmpty()) {
                PeriodCombinationsSection(periods, state.allPeriodKinds, state.periodCombinations) { rules ->
                    run.asIntentSink()(SchedulerIntent.SetPeriodCombinations(rules))
                }
            }
        }
        // The tasks' resilience to a period is the Search window of its tasks: one per period.
        SearchDomain.AddedAction.PeriodTaskSearch -> {
            val periods = SearchDomain.addedPeriodKinds(state, added)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (kind in periods) {
                    FrameButton(if (periods.size == 1) "Search the tasks" else kind) { handlers.onOpenResilienceSearch(kind) }
                }
            }
        }
        // A default period cannot be deleted: only the account's own are, and the button says how many.
        SearchDomain.AddedAction.PeriodDelete -> {
            val own = SearchDomain.addedPeriodKinds(state, added).filter(PeriodKinds::isUserDefined)
            FrameButton(if (own.size == 1) "Delete" else "Delete ${own.size}", enabled = own.isNotEmpty()) {
                run(SearchDomain.AddedCommand.DeletePeriods)
            }
        }
        // Greyed while every added default period is still as the app ships it.
        SearchDomain.AddedAction.PeriodReset -> {
            val modified = SearchDomain.modifiedDefaultPeriods(state, added)
            FrameButton("Reset", enabled = modified.isNotEmpty()) { run(SearchDomain.AddedCommand.ResetDefaultPeriods) }
        }
    }
}

/**
 * One period's notices (user rule 2026-10-15): a length per notification — "this long before the period" — each with
 * its ✕, and "+ Notification" for one more. Every edit writes the whole list ([onChange]).
 */
@Composable
private fun PeriodNoticesEditor(leads: List<Long>, onChange: (List<Long>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (leads.isEmpty()) {
            Text(
                "No notification before this period.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        leads.forEachIndexed { index, lead ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LengthField(
                    millis = lead,
                    mixed = false,
                    defaultUnit = org.example.project.scheduler.domain.QuotaDomain.LengthUnit.Minutes,
                    units = org.example.project.scheduler.domain.QuotaDomain.LengthUnit.entries,
                    onNudge = { delta -> (lead + delta).takeIf { it >= 1_000L }?.let { onChange(leads.replaced(index, it)) } },
                ) { onChange(leads.replaced(index, it)) }
                Text("before", style = MaterialTheme.typography.bodyMedium)
                FrameButton("✕") { onChange(leads.filterIndexed { i, _ -> i != index }) }
            }
        }
        FrameButton("+ Notification") {
            val next = NOTICE_LEAD_CHOICES.firstOrNull { it !in leads } ?: ((leads.maxOrNull() ?: 0L) + 60_000L)
            onChange(leads + next)
        }
    }
}

/** What "+ Notification" offers first: the first of these the period does not have yet. */
private val NOTICE_LEAD_CHOICES: List<Long> = listOf(5L, 10L, 15L, 30L, 60L).map { it * 60_000L }

private fun List<Long>.replaced(index: Int, value: Long): List<Long> = mapIndexed { i, v -> if (i == index) value else v }

/** The name of the element a block of a per-element action is about — said only when there are several. */
@Composable
internal fun ElementHeading(name: String, count: Int) {
    if (count > 1) {
        Text(searchHighlighted(name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * User rule 2026-10-12, the "Lowest countdown" action of the group of several timers: **the run of the timer with the
 * lowest countdown** — the same block a timer's own "Run" draws ([TimerRunBlocks]) — under a check-box drop-down (the
 * app's one, with its "Select all") that lists every added timer from the lowest countdown to the highest
 * ([TimerDomain.byCountdown]), each as its countdown and its title. Checking timers there shows THEIR runs instead
 * ([SearchDomain.Config.pickedTimers]), in the same order. The clock is the Alarms window's own rule — re-read only
 * while one of them is running.
 *
 * **Start all / Pause all / Reset all stand on the title's line** ([title]; null where the caller wrote the title —
 * the buttons then lead the editor), act on the timers SHOWN, and each is greyed when it would do nothing (Start all
 * with every one of them running). **"Compact"** ([SearchDomain.Config.compactTimers]) draws each timer on one row.
 */
@Composable
private fun LowestCountdownEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    handlers: AddedActionHandlers,
    run: (SearchDomain.AddedCommand) -> Unit,
    nowMillis: () -> Long,
    title: String?,
) {
    val timers = SearchDomain.addedTimers(state, added)
    var now by remember { mutableStateOf(nowMillis()) }
    val anyRunning = timers.any { it.running }
    // Keyed on the timers too: a pause or a nudge of a held one moves its countdown with no tick to show it.
    LaunchedEffect(anyRunning, timers) {
        now = nowMillis()
        while (anyRunning) {
            delay(COUNTDOWN_TICK_MILLIS)
            now = nowMillis()
        }
    }
    val sorted = TimerDomain.byCountdown(timers, now)
    val byId = sorted.associateBy { it.id }
    fun line(id: String): String =
        byId[id]?.let { TimerDomain.formatCountdown(it.remainingAtMillis(now)) + "  " + it.label.ifBlank { "Timer" } } ?: id
    val checked = config.pickedTimers.filterTo(LinkedHashSet()) { it in byId }
    // None checked: the one with the lowest countdown.
    val shown = if (timers.size < 2) emptyList() else if (checked.isEmpty()) sorted.take(1) else sorted.filter { it.id in checked }
    val shownIds = shown.mapTo(HashSet()) { it.id }
    val shownRows = added.filter { it.kind == SearchDomain.Kind.Timer && (it as? SearchDomain.ItemResult)?.id in shownIds }
    // What each button would do to the timers shown: nothing, and it is greyed.
    fun intents(step: SearchDomain.RunStep) = SearchDomain.addedIntents(state, shownRows, SearchDomain.AddedCommand.TimersRun(step), nowMillis())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        title?.let {
            MicroGroupTitle(it)
            androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
        }
        SearchDomain.RunStep.entries.forEach { step ->
            FrameButton(step.label + " all", enabled = intents(step).isNotEmpty()) {
                intents(step).forEach { run(SearchDomain.AddedCommand.Raw(it)) }
            }
        }
    }
    if (timers.size < 2) {
        NoteText("Several timers have to be added.", color = MaterialTheme.colorScheme.onSurface)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckBoxDropDown("picked timers",
                options = sorted.map { it.id },
                checked = checked,
                face = if (shown.size == 1) line(shown.single().id) else "${shown.size} timers",
                label = ::line,
                onChange = { onConfigChange(config.copy(pickedTimers = it)) },
                modifier = Modifier.width(260.dp),
            )
            FrameButton(if (config.compactTimers) "Expand" else "Compact") {
                onConfigChange(config.copy(compactTimers = !config.compactTimers))
            }
        }
        TimerRunBlocks(shown, handlers, compact = config.compactTimers)
    }
}

/**
 * The run of each of [timers] — its countdown, its controls, Elapsed and when it ends, the Alarms window's own row
 * ([AddedActionHandlers.timerRun]) — and, where [onStep] is given, the buttons that act on all of them. [compact]:
 * each on one row, which then names its timer itself.
 */
@Composable
private fun TimerRunBlocks(
    timers: List<TimerEntry>,
    handlers: AddedActionHandlers,
    compact: Boolean = false,
    onStep: ((SearchDomain.RunStep) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (timer in timers) {
            key(timer.id) {
                if (!compact) ElementHeading(timer.label.ifBlank { "Timer" }, timers.size)
                handlers.timerRun(setOf(AlarmWindowSubject(timer.id, AlarmWindowSubject.Kind.Timer)), compact)
            }
        }
        if (onStep != null && timers.size != 1) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SearchDomain.RunStep.entries.forEach { step ->
                FrameButton(step.label + if (timers.size > 1) " all" else "") { onStep(step) }
            }
        }
    }
}

/**
 * User rule 2026-10-14: the run of each of [chronos] — its count and its buttons, the Alarms window's own row
 * ([AddedActionHandlers.timerRun]) — and, where [onStep] is given and there are several, the buttons that act on all of
 * them. "Compact" ([SearchDomain.Config.compactChronos], through [onCompactChange]) draws each on one row, named.
 */
@Composable
private fun ChronoRunBlocks(
    chronos: List<org.example.project.scheduler.model.ChronoEntry>,
    handlers: AddedActionHandlers,
    compact: Boolean,
    onCompactChange: ((Boolean) -> Unit)?,
    onStep: ((SearchDomain.RunStep) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onCompactChange != null && chronos.size > 1) {
            FrameButton(if (compact) "Expand" else "Compact") { onCompactChange(!compact) }
        }
        for (chrono in chronos) {
            key(chrono.id) {
                if (!compact) ElementHeading(chrono.label.ifBlank { "Chrono" }, chronos.size)
                handlers.timerRun(setOf(AlarmWindowSubject(chrono.id, AlarmWindowSubject.Kind.Chrono)), compact)
            }
        }
        if (onStep != null && chronos.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SearchDomain.RunStep.entries.forEach { step -> FrameButton(step.label + " all") { onStep(step) } }
        }
    }
}

/**
 * User rule 2026-10-14, the "Highest count" action of the group of several chronos — [LowestCountdownEditor] for the
 * chronos: **the run of the chrono with the highest count**, under a check-box drop-down listing every added chrono
 * from the highest count down ([ChronoDomain.byCount]), each as its count and its title. Checking chronos there shows
 * THEIR runs instead ([SearchDomain.Config.pickedChronos]). Start all / Pause all / Reset all stand on the title's line,
 * act on the chronos SHOWN, and are greyed where they would do nothing; "Compact" draws each on one row.
 */
@Composable
private fun HighestCountEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    handlers: AddedActionHandlers,
    run: (SearchDomain.AddedCommand) -> Unit,
    nowMillis: () -> Long,
    title: String?,
) {
    val chronos = SearchDomain.addedChronos(state, added)
    var now by remember { mutableStateOf(nowMillis()) }
    val anyRunning = chronos.any { it.running }
    // The Alarms window's clock rule: re-read only while one of them is running (the order moves with the counts).
    LaunchedEffect(anyRunning, chronos) {
        now = nowMillis()
        while (anyRunning) {
            delay(COUNTDOWN_TICK_MILLIS)
            now = nowMillis()
        }
    }
    val sorted = org.example.project.scheduler.domain.ChronoDomain.byCount(chronos, now)
    val byId = sorted.associateBy { it.id }
    fun line(id: String): String =
        byId[id]?.let {
            org.example.project.scheduler.domain.ChronoDomain.format(it.elapsedAtMillis(now)) + "  " + it.label.ifBlank { "Chrono" }
        } ?: id
    val checked = config.pickedChronos.filterTo(LinkedHashSet()) { it in byId }
    // None checked: the one with the highest count.
    val shown = if (chronos.size < 2) emptyList() else if (checked.isEmpty()) sorted.take(1) else sorted.filter { it.id in checked }
    val shownIds = shown.mapTo(HashSet()) { it.id }
    val shownRows = added.filter { it.kind == SearchDomain.Kind.Chrono && (it as? SearchDomain.ItemResult)?.id in shownIds }
    fun intents(step: SearchDomain.RunStep) = SearchDomain.addedIntents(state, shownRows, SearchDomain.AddedCommand.ChronosRun(step), nowMillis())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        title?.let {
            MicroGroupTitle(it)
            androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
        }
        SearchDomain.RunStep.entries.forEach { step ->
            FrameButton(step.label + " all", enabled = intents(step).isNotEmpty()) {
                intents(step).forEach { run(SearchDomain.AddedCommand.Raw(it)) }
            }
        }
    }
    if (chronos.size < 2) {
        NoteText("Several chronos have to be added.", color = MaterialTheme.colorScheme.onSurface)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckBoxDropDown("picked chronos",
                options = sorted.map { it.id },
                checked = checked,
                face = if (shown.size == 1) line(shown.single().id) else "${shown.size} chronos",
                label = ::line,
                onChange = { onConfigChange(config.copy(pickedChronos = it)) },
                modifier = Modifier.width(260.dp),
            )
            FrameButton(if (config.compactChronos) "Expand" else "Compact") {
                onConfigChange(config.copy(compactChronos = !config.compactChronos))
            }
        }
        ChronoRunBlocks(shown, handlers, compact = config.compactChronos, onCompactChange = null)
    }
}

/** The mapping's id an added keyboard-shortcut row names, or null for one of the app's own shortcuts. */
private fun windowShortcutIdOf(row: SearchDomain.Result): String? =
    (row as? SearchDomain.ItemResult)?.takeIf { it.kind == SearchDomain.Kind.Shortcut }
        ?.id?.takeIf { it.startsWith(WindowShortcuts.SEARCH_PREFIX) }?.removePrefix(WindowShortcuts.SEARCH_PREFIX)

/**
 * User rule 2026-10-12, a keyboard shortcut's **Chord**: what each added shortcut is on, and the capture that moves
 * it ([ChordCapture]). One of the app's own goes through its intent ([SchedulerIntent.SetGlobalShortcutBinding] —
 * "Reset" is back to the chord it ships with); a window's goes to `App` ([AddedActionHandlers.onWindowShortcutChange]).
 * One chord is one thing across both: each asks the other list before taking it.
 */
@Composable
private fun ShortcutChordEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    handlers: AddedActionHandlers,
    run: (SearchDomain.AddedCommand) -> Unit,
) {
    val rows = added.filterIsInstance<SearchDomain.ItemResult>().filter { it.kind == SearchDomain.Kind.Shortcut }
    val mappings = handlers.windowShortcuts()
    val titles = handlers.shortcutWindows().toMap()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in rows) {
            key(row.id) {
                ElementHeading(row.name, rows.size)
                val global = org.example.project.scheduler.platform.GlobalShortcut.entries.firstOrNull { it.name == row.id }
                val mapping = windowShortcutIdOf(row)?.let { id -> mappings.firstOrNull { it.id == id } }
                when {
                    global != null ->
                        ChordCapture(
                            chord = org.example.project.scheduler.platform.GlobalShortcutBindings.chordOf(state.shortcutBindings, global),
                            resetLabel = "Reset".takeIf { global in state.shortcutBindings },
                            onTake = { binding ->
                                val why =
                                    org.example.project.scheduler.platform.GlobalShortcutBindings.rejection(state.shortcutBindings, global, binding)
                                        ?: WindowShortcuts.heldByWindow(mappings, binding, { titles[it] ?: it })
                                if (why == null) run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetGlobalShortcutBinding(global, binding)))
                                why
                            },
                            onReset = { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetGlobalShortcutBinding(global, null))) },
                        )
                    mapping != null ->
                        ChordCapture(
                            chord = mapping.chord?.chord,
                            resetLabel = "Remove".takeIf { mapping.chord != null },
                            onTake = { binding ->
                                val why = WindowShortcuts.rejection(state.shortcutBindings, mappings, mapping.id, binding) { titles[it] ?: it }
                                if (why == null) handlers.onWindowShortcutChange(mapping.copy(chord = binding))
                                why
                            },
                            onReset = { handlers.onWindowShortcutChange(mapping.copy(chord = null)) },
                        )
                    else -> NoteText("This shortcut no longer exists.", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/**
 * User rule 2026-10-12, a window shortcut's **Window**: the window its chord brings back, picked in a drop-down of
 * every window that can be named ([AddedActionHandlers.shortcutWindows]). The app's own shortcuts have none.
 */
@Composable
private fun ShortcutWindowEditor(added: List<SearchDomain.Result>, handlers: AddedActionHandlers) {
    val rows = added.filterIsInstance<SearchDomain.ItemResult>().filter { it.kind == SearchDomain.Kind.Shortcut }
    val mappings = handlers.windowShortcuts()
    val windows = handlers.shortcutWindows()
    val titles = windows.toMap()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in rows) {
            key(row.id) {
                ElementHeading(row.name, rows.size)
                val mapping = windowShortcutIdOf(row)?.let { id -> mappings.firstOrNull { it.id == id } }
                if (mapping == null) {
                    NoteText("One of the app's own shortcuts: it brings no window.", color = MaterialTheme.colorScheme.onSurface)
                } else {
                    var open by remember { mutableStateOf(false) }
                    Box(Modifier.width(260.dp)) {
                        Text(
                            text = (mapping.window?.let { titles[it] ?: "$it (closed)" } ?: "choose a window…") + "  ▾",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                .menuToggleClickable(open) { open = it }
                                .padding(horizontal = 10.dp, vertical = fieldFaceVerticalPadding()),
                        )
                        // User rule 2026-11-12: the list is a Search window ([OptionSearchMenu]); the window it brings, ticked.
                        OptionSearchMenu("ShortcutWindowEditor",
                            open = open,
                            onDismiss = { open = false },
                            options = SearchOptions(
                                options = windows,
                                checked = setOfNotNull(mapping.window),
                                onClick = { id ->
                                    open = false
                                    handlers.onWindowShortcutChange(mapping.copy(window = id))
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * User rule 2026-10-02: **the one title field of a group** — empty unless every added element of [kind] has the same
 * title; typing gives them all what is typed, as it is typed; Escape gives each back the title it had when the
 * typing began. One typing session (from its first keystroke to Escape or the field losing the focus) is one History
 * Unit where the kind's list has them.
 */
@Composable
private fun SharedTitleField(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    kind: SearchDomain.Kind,
    run: (SearchDomain.AddedCommand) -> Unit,
) {
    val titles = SearchDomain.addedTitles(state, added, kind)
    // The titles as they stood when the typing began — what Escape puts back; null while nothing is being typed.
    var before by remember(kind) { mutableStateOf<Map<String, String>?>(null) }
    var draft by remember(kind) { mutableStateOf("") }
    var session by remember(kind) { mutableStateOf(0) }
    // Named after the elements it writes too (user rule 2026-10-10: each element has a group of its own, so the same
    // field stands once per element — two of them typed one after the other are two History Units).
    val editKey = "added/${kind.name}/title/${titles.keys.sorted().joinToString(",")}@$session"
    OutlinedTextField(
        value = if (before != null) draft else SearchDomain.sharedTitle(titles),
        onValueChange = { text ->
            if (before == null) before = titles
            draft = text
            run(SearchDomain.AddedCommand.Titles(kind, titles.keys.associateWith { text }, editKey))
        },
        enabled = titles.isNotEmpty(),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .leaveFocusOnOutsidePress()
            .onFocusChanged { focus ->
                if (!focus.isFocused && before != null) {
                    before = null
                    session++
                }
            }
            .onPreviewKeyEvent { event ->
                val previous = before
                if (event.type != KeyEventType.KeyDown || event.key != Key.Escape || previous == null) {
                    return@onPreviewKeyEvent false
                }
                // Only the elements still in the list get their title back.
                run(SearchDomain.AddedCommand.Titles(kind, previous.filterKeys { it in titles }, editKey))
                before = null
                session++
                true
            },
    )
}

/**
 * User rule 2026-10-02: **one text field of a group, over every added element of its kind** — what they all hold,
 * else empty; typing writes every one of them at each keystroke that [parse]s ([write], with the session's edit
 * key); Escape gives each back what it held when the typing began ([restore]). The typing session (first keystroke
 * to Escape or the focus leaving) is one History Unit where the kind's list has them.
 */
@Composable
private fun <T> SharedTextField(
    items: List<T>,
    idOf: (T) -> String,
    /** Names the field for its typing sessions: `Alarm/time`, `Timer/duration`… */
    field: String,
    read: (T) -> String,
    parse: (String) -> ((T) -> T)?,
    restore: (T, T) -> T,
    write: (editKey: String, change: (T) -> T) -> Unit,
    /** What the field lets through as it is typed (a formula's characters, a time's). */
    sanitize: (String) -> String = { it },
    /** A name rather than a number: the field takes the row's width. */
    wide: Boolean = false,
    /**
     * For a field that holds a time as `HH:MM`: one element [deltaMinutes] later — the right-click menu's step
     * ([TimeNudgeMenu]), applied to every element from its OWN time, so it works where they differ too.
     */
    timeNudge: ((T, deltaMinutes: Int) -> T)? = null,
) {
    var before by remember(field) { mutableStateOf<Map<String, T>?>(null) }
    var draft by remember(field) { mutableStateOf("") }
    var session by remember(field) { mutableStateOf(0) }
    val editKey = "added/$field/${items.map(idOf).sorted().joinToString(",")}@$session"
    val textField: @Composable () -> Unit = {
    OutlinedTextField(
        value = if (before != null) draft else SearchDomain.sharedValue(items, read).orEmpty(),
        onValueChange = { raw ->
            val text = sanitize(raw)
            if (before == null) before = items.associateBy(idOf)
            draft = text
            parse(text)?.let { write(editKey, it) }
        },
        enabled = items.isNotEmpty(),
        isError = before != null && parse(draft) == null,
        singleLine = true,
        modifier = (if (wide) Modifier.fillMaxWidth() else Modifier.width(110.dp))
            .leaveFocusOnOutsidePress()
            .onFocusChanged { focus ->
                if (!focus.isFocused && before != null) {
                    before = null
                    session++
                }
            }
            .onPreviewKeyEvent { event ->
                val previous = before
                if (event.type != KeyEventType.KeyDown || event.key != Key.Escape || previous == null) {
                    return@onPreviewKeyEvent false
                }
                write(editKey) { item -> previous[idOf(item)]?.let { restore(item, it) } ?: item }
                before = null
                session++
                true
            },
    )
    }
    if (timeNudge == null) {
        textField()
    } else {
        TimeNudgeMenu(
            enabled = items.isNotEmpty(),
            onNudge = { delta ->
                // Inside a typing session the step joins it, and the field reads the stepped time.
                if (before != null) draft = SearchDomain.sharedValue(items.map { timeNudge(it, delta) }, read).orEmpty()
                write(editKey) { timeNudge(it, delta) }
            },
            field = textField,
        )
    }
}

/** A raw intent through the actions' one write path: [SearchDomain.AddedCommand.Raw]. */
private fun ((SearchDomain.AddedCommand) -> Unit).asIntentSink(): (SchedulerIntent) -> Unit =
    { intent -> this(SearchDomain.AddedCommand.Raw(intent)) }

/** A button for an entry that is about ONE task: live when [eligible] holds exactly one, and it says so otherwise. */
@Composable
private fun OneTaskButton(text: String, eligible: List<TaskId>, onClick: (TaskId) -> Unit) {
    val single = eligible.singleOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FrameButton(text, enabled = single != null) { single?.let(onClick) }
        if (eligible.size > 1) {
            NoteText("one task at a time")
        }
    }
}

/**
 * The task edit window's resilience over every added schedulable leaf: the period field — set by
 * [SearchDomain.Config.resiliencePeriod], which the period edit window's button fills in — and the value, applied as
 * ONE history unit.
 */
@Composable
private fun ResilienceEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    run: (SearchDomain.AddedCommand) -> Unit,
) {
    val kind = SearchDomain.resiliencePeriodOf(state, config)
    val leaves = SearchDomain.addedLeafIds(state, added)
    val values = leaves.mapNotNull { state.tasks[it] }.map { task -> kind?.let { task.resilienceFor(it) } }
    // The value the leaves share for that period, when they share one, is what the field starts from.
    val shared = values.distinct().singleOrNull()
    var draft by remember(shared, kind) { mutableStateOf(shared?.let(::percentText).orEmpty()) }
    val value = draft.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()?.let { PeriodKinds.clamp(it / 100.0) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChoiceDropDown("ResilienceEditor",
                options = SearchDomain.resilienceKinds(state),
                selected = kind,
                label = { it },
                onSelect = { onConfigChange(config.copy(resiliencePeriod = it)) },
                placeholder = "period",
                modifier = Modifier.width(150.dp),
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                suffix = { Text("%") },
                placeholder = { Text(if (shared == null && leaves.isNotEmpty()) "mixed" else "") },
                modifier = Modifier.width(96.dp),
            )
            FrameButton("Apply", enabled = kind != null && value != null && values.any { it != value }) {
                if (kind != null && value != null) run(SearchDomain.AddedCommand.Resilience(kind, value))
            }
        }
        if (leaves.size < added.count { it.kind == SearchDomain.Kind.Task }) {
            NoteText("Only the schedulable tasks (${leaves.size}): a parent task is never placed.")
        }
    }
}

/**
 * User rule 2026-10-03: **the task cell categories of each added task, one occurrence at a time** — every place the task
 * sits ([TaskPathsDomain.occurrences], by its path), the task cell categories that occurrence carries (✕ takes one
 * off), and a [NamingCell] giving it another: the account's task cell categories as identity rows, a "Create" row for
 * a name nobody holds. A task id category is not offered here — it is carried by the task, from the tree's field.
 */
@Composable
private fun TaskCellCategoriesEditor(state: SchedulerState, taskIds: List<TaskId>, run: (SearchDomain.AddedCommand) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (taskId in taskIds) {
            val task = state.tasks[taskId] ?: continue
            ElementHeading(task.title.ifBlank { SchedulerDomain.UNTITLED_LABEL }, taskIds.size)
            val places = remember(taskId, state.cells, state.lists, state.tasks) { TaskPathsDomain.occurrences(state, taskId) }
            if (places.isEmpty()) {
                NoteText("In no place of the open task tree.")
            }
            for (place in places) {
                val carried = state.cells[place.cellId]?.categoryIds.orEmpty().mapNotNull { state.categoryById(it) }
                Text(place.label, style = MaterialTheme.typography.bodyMedium)
                for (category in carried) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(category.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        FrameButton("✕") {
                            run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetCellCategory(place.cellId, category.id, carried = false)))
                        }
                    }
                }
                NamingCell(
                    cellId = CellId("search/cell-categories/" + place.cellId.value),
                    shown = "",
                    identityLabel = "Task cell categories",
                    identity = { draft ->
                        val typed = draft.trim()
                        CategoryRules.menuEntries(state, draft, carried.map { it.id }, org.example.project.scheduler.model.CategoryKind.TaskCell)
                            .map { NamingRow(it.id.value, it.title) } +
                            listOfNotNull(
                                namingCreateRow(typed).takeIf {
                                    typed.isNotEmpty() && state.categories.none { it.title.equals(typed, ignoreCase = true) }
                                },
                            )
                    },
                    suggestions = { draft -> CategoryRules.titleSuggestions(state, draft, org.example.project.scheduler.model.CategoryKind.TaskCell) },
                    onPick = { key ->
                        val created = namingCreatedName(key)
                        run(
                            SearchDomain.AddedCommand.Raw(
                                if (created != null) SchedulerIntent.AddCellCategory(place.cellId, created)
                                else SchedulerIntent.SetCellCategory(place.cellId, CategoryId(key), carried = true),
                            ),
                        )
                    },
                )
            }
        }
    }
}

/**
 * PRD §9 (user rule 2026-10-03): **the set of tasks of each added schedulable task** — what it fulfils while it is on the
 * calendar. One line per task of the set: its title (a press opens the Search window holding it), its percentage with
 * the Resilience action's draft-and-Apply (one history unit per Apply, never one per keystroke) and its ✕. Under them, a
 * task cell to add one ([NamingCell]: the cell's id menu and title suggestions), which comes in at 100%.
 */
@Composable
private fun FulfilmentEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    run: (SearchDomain.AddedCommand) -> Unit,
    onOpenTask: (TaskId) -> Unit,
) {
    val leaves = SearchDomain.addedLeafIds(state, added)
    val taskColors = TaskPalette.sheetColors(rememberTaskHues(state))
    if (leaves.isEmpty()) {
        NoteText("Only a schedulable task has a set of tasks.", color = MaterialTheme.colorScheme.onSurface)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (leaf in leaves) {
            val task = state.tasks[leaf] ?: continue
            ElementHeading(task.title.ifBlank { SchedulerDomain.UNTITLED_LABEL }, leaves.size)
            val entries = task.fulfilment.entries.sortedBy { state.tasks[it.key]?.title.orEmpty().lowercase() }
            if (entries.isEmpty()) {
                NoteText("It fulfils no other task.")
            }
            for ((target, fraction) in entries) {
                FulfilmentLine(
                    title = SchedulerDomain.taskTitleLabel(state, target),
                    fraction = fraction,
                    onOpen = { onOpenTask(target) },
                    onApply = { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetTaskFulfilment(leaf, target, it))) },
                    onRemove = { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetTaskFulfilment(leaf, target, null))) },
                )
            }
            NamingCell(
                cellId = CellId("search/fulfilment-add/" + leaf.value),
                shown = "",
                identityLabel = "Tasks",
                identity = { draft ->
                    SchedulerDomain.taskIdentityMenuEntries(state, draft)
                        .mapNotNull { entry -> entry.taskId?.takeIf { it != leaf && it !in task.fulfilment }?.let { it to entry.label } }
                        .map { (id, label) -> NamingRow(SearchDomain.taskKey(id), label, taskColors[id], taskId = id) }
                },
                suggestions = { draft -> SchedulerDomain.titleSuggestions(state, draft) },
                onPick = { key ->
                    val target = TaskId(key.removePrefix(SearchDomain.Kind.Task.name + "/"))
                    run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetTaskFulfilment(leaf, target, 1.0)))
                },
                trailing = { NamingKey("add a task (100%)") },
            )
        }
    }
}

/**
 * PRD §9 (user rule 2026-10-03): **the tasks whose set holds each added task** — the reverse of [FulfilmentEditor], so a
 * task that fulfils this one is found from it even with no path ("listen to Spanish" shows "watch videos explaining
 * chemistry in Spanish"). Each line: the holder (a press opens the Search window holding it), its percentage for this
 * task (Apply), and ✕ to take this task out of its set.
 */
@Composable
private fun FulfilledByEditor(
    state: SchedulerState,
    taskIds: List<TaskId>,
    run: (SearchDomain.AddedCommand) -> Unit,
    onOpenTask: (TaskId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (id in taskIds) {
            ElementHeading(SchedulerDomain.taskTitleLabel(state, id), taskIds.size)
            val holders = state.tasks.values.filter { id in it.fulfilment }.sortedBy { it.title.lowercase() }
            if (holders.isEmpty()) {
                NoteText("No task's set holds it.")
            }
            for (holder in holders) {
                FulfilmentLine(
                    title = SchedulerDomain.taskTitleLabel(holder.title),
                    fraction = holder.fulfilment.getValue(id),
                    onOpen = { onOpenTask(holder.id) },
                    onApply = { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetTaskFulfilment(holder.id, id, it))) },
                    onRemove = { run(SearchDomain.AddedCommand.Raw(SchedulerIntent.SetTaskFulfilment(holder.id, id, null))) },
                )
            }
        }
    }
}

/** One task of a set of tasks: its title (pressed: its Search window), its percentage (draft, then Apply) and ✕. */
@Composable
private fun FulfilmentLine(
    title: String,
    fraction: Double,
    onOpen: () -> Unit,
    onApply: (Double) -> Unit,
    onRemove: () -> Unit,
) {
    var draft by remember(fraction) { mutableStateOf(percentText(fraction)) }
    val value = draft.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it > 0.0 }?.let { (it / 100.0).coerceAtMost(1.0) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).clickable(onClick = onOpen),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            isError = value == null,
            suffix = { Text("%") },
            modifier = Modifier.width(96.dp),
        )
        FrameButton("Apply", enabled = value != null && value != fraction) { value?.let(onApply) }
        FrameButton("✕", onClick = onRemove)
    }
}

/** A multiplier as a percentage with no trailing zeros — `0`, `50`, `12.5`. */
private fun percentText(value: Double): String {
    val rounded = kotlin.math.round(value * 1000.0) / 10.0
    return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
}

/**
 * The task edit window's schedule unit over every added schedulable leaf ([ScheduleUnitEditor], its one drawing). A
 * task whose minimum time the steps exceed keeps its own (the reducer's guard), and the line under the steps says how
 * many that is.
 */
@Composable
private fun ScheduleUnitActionEditor(state: SchedulerState, added: List<SearchDomain.Result>, run: (SearchDomain.AddedCommand) -> Unit) {
    val leaves = SearchDomain.addedLeafIds(state, added).mapNotNull { state.tasks[it] }
    val shared = leaves.map { it.scheduleUnit }.distinct().singleOrNull()
    var draft by remember(shared) { mutableStateOf(shared.orEmpty()) }
    val fits = leaves.count { SchedulerDomain.canSaveScheduleUnit(draft, it.minimumMinutes) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScheduleUnitEditor(draft) { draft = it }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Total: ${SchedulerDomain.scheduleUnitSumMinutes(draft)} min · fits $fits of ${leaves.size} tasks",
                style = MaterialTheme.typography.labelSmall,
                color = if (fits == leaves.size) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            FrameButton("Apply", enabled = leaves.any { it.scheduleUnit != draft && SchedulerDomain.canSaveScheduleUnit(draft, it.minimumMinutes) }) {
                run(SearchDomain.AddedCommand.ScheduleUnit(draft))
            }
        }
    }
}

/**
 * The task edit window's "Add under…" over every added task ([AddUnderField], its one drawing): the places at least one
 * of them may go ([TaskPathsDomain.candidatesForAll]), picking one puts every one that may go there at once.
 */
@Composable
private fun AddUnderEditor(state: SchedulerState, taskIds: List<TaskId>, run: (SearchDomain.AddedCommand) -> Unit) {
    if (taskIds.isEmpty()) return
    AddUnderField(
        cellId = org.example.project.scheduler.model.CellId("search/add-under/" + taskIds.joinToString(",") { it.value }),
        candidates = { query -> TaskPathsDomain.candidatesForAll(state, taskIds, query) },
        titleSuggestions = { draft -> SchedulerDomain.titleSuggestions(state, draft) },
        onAdd = { run(SearchDomain.AddedCommand.AddUnder(it)) },
    )
}

/**
 * A [NamingCell] over [options] (user rule 2026-10-03: every field naming an element is one) that acts on the category
 * picked — it holds no value of its own, so it is empty again after a pick. The identity rows are the options whose
 * title holds the draft, the title suggestions their titles.
 */
@Composable
private fun CategoryChooser(cellId: String, options: List<Pair<CategoryId, String>>, enabled: Boolean, onPick: (CategoryId) -> Unit) {
    if (!enabled || options.isEmpty()) {
        Text(if (options.isEmpty()) "No category." else "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    fun matching(draft: String) = options.filter { (_, title) -> draft.isBlank() || title.contains(draft.trim(), ignoreCase = true) }
    NamingCell(
        cellId = org.example.project.scheduler.model.CellId(cellId),
        shown = "",
        identityLabel = "Categories",
        identity = { draft -> matching(draft).map { (id, title) -> NamingRow(id.value, title) } },
        suggestions = { draft -> matching(draft).map { it.second }.filter { !it.equals(draft.trim(), ignoreCase = true) } },
        onPick = { key -> options.firstOrNull { it.first.value == key }?.let { onPick(it.first) } },
    )
}

/** Picks one of [options] (key to name), as [CategoryChooser] does a category. */
@Composable
private fun ElementChooser(cellId: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    if (options.isEmpty()) {
        NoteText("None.")
        return
    }
    fun matching(draft: String) = options.filter { (_, title) -> draft.isBlank() || title.contains(draft.trim(), ignoreCase = true) }.take(MAX_CHOOSER_ROWS)
    NamingCell(
        cellId = org.example.project.scheduler.model.CellId(cellId),
        shown = "",
        identityLabel = "Elements",
        identity = { draft -> matching(draft).map { (key, title) -> NamingRow(key, title) } },
        suggestions = { draft -> matching(draft).map { it.second }.filter { !it.equals(draft.trim(), ignoreCase = true) } },
        onPick = { key -> if (options.any { it.first == key }) onPick(key) },
    )
}

/**
 * User rule 2026-11-13: **an added search configuration shows its state for each window id** — every Search window open
 * and every one saved ([SearchDomain.SearchWindowState]): under the window's id and title, the configuration's own
 * control ([SettingEditor], the one drawing of it) holding that window's state. It writes that window's configuration
 * where the window can be written from here; a saved one (a menu button's) is shown, greyed, and takes no press.
 */
@Composable
private fun ConfigurationStatesAction(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    windows: List<SearchDomain.SearchWindowState>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
) {
    val settings = added.mapNotNull(SearchDomain::settingOf)
    for (setting in settings) {
        ElementHeading(setting.label, settings.size)
        if (windows.isEmpty()) NoteText("No Search window is open or saved.")
        for (window in windows) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    window.id + "  ·  " + window.title + if (window.open) "" else "  ·  saved",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(if (window.set == null) Modifier.graphicsLayer { alpha = 0.6f } else Modifier) {
                    SettingEditor(state, setting, window.config, null, { window.set?.invoke(it) }, claudeLogs = claudeLogs)
                    if (window.set == null) {
                        // Shown, not changed from here: every press stops on it.
                        Box(
                            Modifier.matchParentSize().pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** How many elements a selector lists: what is typed narrows the rest. */
private const val MAX_CHOOSER_ROWS: Int = 50

/**
 * User rule 2026-11-12: **"Parents", the parent action of any element** — its children are two fields, each directly
 * under it and named by its grey text ([SearchDomain.ParentsChild]), each opening a headless Search window under it
 * ([PopupSearchField]): the **List of parents** — the parents of the added
 * elements, each with the button that drops it ([SearchDomain.parentsListConfig]); and **Add a parent** — the tasks
 * ([SearchDomain.ADD_PARENT_SEARCH]), a click making one a parent.
 */
@Composable
private fun ElementParentsEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    run: (SearchDomain.AddedCommand) -> Unit,
    rowId: String,
) {
    val tree = org.example.project.scheduler.domain.ElementTree
    val children = added.map(SearchDomain::keyOf)
    // Each window's own configuration (its bar, its settings — the parent distance): kept with the Search window the
    // row stands in (user rule 2026-11-13, [rememberInsideConfig]), under the field's id ([PopupSearchField]).
    var listConfig by rememberInsideConfig(
        embeddedSearchId("field:" + rowId + "/" + SearchDomain.ParentsChild.List.placeholder), SearchDomain.parentsListConfig(children),
    )
    var addConfig by rememberInsideConfig(
        embeddedSearchId("field:" + rowId + "/" + SearchDomain.ParentsChild.Add.placeholder), SearchDomain.ADD_PARENT_SEARCH,
    )
    // The tasks one of the added elements can be put under: the tree's own rule ([ElementTree.canLink]).
    val below = remember(children, state.elementLinks, state.lists, state.tasks) { children.associateWith { tree.descendantsOf(state, it) } }
    val eligible = remember(below, state.cells) {
        state.tasks.keys.filterTo(HashSet()) { id -> children.any { tree.canLink(state, SearchDomain.taskKey(id), it, below.getValue(it)) } }
    }
    // User rule 2026-11-12: each field is a child of "Parents" directly, named by its grey text.
    for (child in SearchDomain.ParentsChild.entries) {
        Box(Modifier.fillMaxWidth().padding(start = NESTED_ACTION_INDENT)) {
            when (child) {
                SearchDomain.ParentsChild.List ->
                    PopupSearchField(
                        placeholder = child.placeholder,
                        state = state,
                        config = SearchDomain.parentsListConfig(children, listConfig),
                        onConfigChange = { listConfig = it },
                        openedConfig = SearchDomain.parentsListConfig(children),
                        fieldId = rowId + "/" + child.placeholder,
                        modifier = Modifier.fillMaxWidth(),
                        // Dropped: no longer a parent of any added element.
                        onDrop = { parent -> run(SearchDomain.AddedCommand.ElementParent(SearchDomain.keyOf(parent), on = false)) },
                        // A task's last place in the tree is not taken from it.
                        canDrop = { parent -> children.any { tree.canUnlink(state, SearchDomain.keyOf(parent), it) } },
                    )
                SearchDomain.ParentsChild.Add ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PopupSearchField(
                        placeholder = child.placeholder,
                        state = state,
                        config = addConfig,
                        onConfigChange = { addConfig = it },
                        openedConfig = SearchDomain.ADD_PARENT_SEARCH,
                        fieldId = rowId + "/" + child.placeholder,
                        modifier = Modifier.weight(1f),
                        picker = { close ->
                            SearchPicker(
                                eligible = eligible,
                                canPick = true,
                                onPick = { taskId ->
                                    val parent = SearchDomain.taskKey(taskId)
                                    // Anomaly 2026-11-13 (a pick that added no parent): what each pick asked and made.
                                    val intents = tree.parentIntents(state, children, parent, on = true)
                                    org.example.project.scheduler.platform.Diagnostics.log(
                                        "[parents] pick $parent for $children: ${intents.map { it::class.simpleName }}",
                                    )
                                    run(SearchDomain.AddedCommand.ElementParent(parent, on = true))
                                    addConfig = addConfig.copy(query = "")
                                    close()
                                },
                                onNewTask = null,
                                onCancel = close,
                            )
                        },
                    )
                    // User rule 2026-11-02: beside it, the button adding the element this window comes from.
                    ElementSourceButton(state, added, config, run)
                    }
            }
        }
    }
}

/**
 * User rule 2026-11-02, "Add a parent": the selector, of what can be a parent of an added element ([ElementTree.canLink]), and
 * beside it the button adding the element this Search window comes from ([SearchDomain.Config.elementSource]).
 */
@Composable
private fun ElementParentEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    run: (SearchDomain.AddedCommand) -> Unit,
) {
    val tree = org.example.project.scheduler.domain.ElementTree
    val children = added.map(SearchDomain::keyOf)
    val below = remember(children, state.elementLinks, state.lists, state.tasks) { children.associateWith { tree.descendantsOf(state, it) } }
    fun allowed(parent: String) = children.any { tree.canLink(state, parent, it, below.getValue(it)) }
    val options = remember(below, state.categories, state.periodKinds, state.alarms, state.timers, state.chronos, state.quotas, state.chores, state.taskTrees, state.workplaceSetups) { tree.pickable(state).filter { allowed(it.first) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f)) {
            ElementChooser("search/element-add-parent", options) { run(SearchDomain.AddedCommand.ElementParent(it, on = true)) }
        }
        ElementSourceButton(state, added, config, run)
    }
}

/**
 * User rule 2026-11-02: the button making the element this Search window comes from ([SearchDomain.Config.elementSource]) a
 * parent of the added elements, greyed where it cannot be one of any ([ElementTree.canLink]). Nothing without a source.
 */
@Composable
private fun ElementSourceButton(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    run: (SearchDomain.AddedCommand) -> Unit,
) {
    val tree = org.example.project.scheduler.domain.ElementTree
    val source = config.elementSource ?: return
    val sourceName = remember(source, state.tasks, state.timers, state.alarms, state.chronos, state.workplaceSetups) {
        SearchDomain.resolve(state, listOf(source), { emptyMap() }).singleOrNull()?.name
    } ?: return
    val allowed = remember(source, added, state.elementLinks, state.lists, state.tasks) {
        added.any { tree.canLink(state, source, SearchDomain.keyOf(it)) }
    }
    FrameButton("Add " + sourceName, enabled = allowed) {
        run(SearchDomain.AddedCommand.ElementParent(source, on = true))
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
        text = searchHighlighted(text),
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 1,
        // Squeezed, it is cut — never wrapped under itself.
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (enabled) MaterialTheme.colorScheme.outlineVariant else color, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = buttonVerticalPadding(6.dp)),
    )
}

/**
 * User rule 2026-10-18: **when a new alarm or reminder is set** — the default configuration's "Date and time" (an
 * alarm's) or "Time" ([org.example.project.scheduler.domain.NewElementTimes]): where its date comes from, and where its
 * time of day comes from — the day / the time it is made, the one of the element whose "New" made it, or a time of its own.
 */
@Composable
private fun NewElementTimesEditor(
    times: org.example.project.scheduler.domain.NewElementTimes,
    withDate: Boolean,
    onChange: (org.example.project.scheduler.domain.NewElementTimes) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP)) {
        if (withDate) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NoteText("date", color = MaterialTheme.colorScheme.onSurface)
                ChoiceDropDown("NewElementTimesEditor",
                    options = org.example.project.scheduler.domain.NewDate.entries,
                    selected = times.date,
                    label = { it.label },
                    onSelect = { onChange(times.copy(date = it)) },
                    modifier = Modifier.width(260.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NoteText("time", color = MaterialTheme.colorScheme.onSurface)
            ChoiceDropDown("NewElementTimesEditor#2",
                options = org.example.project.scheduler.domain.NewTime.entries,
                selected = times.time,
                label = { it.label },
                onSelect = { onChange(times.copy(time = it)) },
                modifier = Modifier.width(260.dp),
            )
            if (times.time == org.example.project.scheduler.domain.NewTime.Fixed) {
                TimeOfDayField(times.fixedMinutes, enabled = true) { minutes -> onChange(times.copy(fixedMinutes = minutes)) }
                InstantButtons { at -> onChange(times.copy(fixedMinutes = minuteOfDayOf(at))) }
            }
        }
    }
}

/**
 * User rule 2026-11-01: **the windows of each added workplace setup**: a file explorer, with a field per tab, or an app on
 * one folder (VS Code, Claude Code in cmd). Written as it is typed, one History Unit per field typed into.
 */
@Composable
private fun WorkplaceWindowsEditor(state: SchedulerState, added: List<SearchDomain.Result>, run: (SearchDomain.AddedCommand) -> Unit) {
    val setups = SearchDomain.addedWorkplaceSetupsOrDefault(state, added)
    fun write(id: String, windows: List<org.example.project.scheduler.domain.WorkplaceWindow>, editKey: String? = null) =
        SearchDomain.workplaceEdits(state, setOf(id), editKey) { it.copy(windows = windows) }.forEach { run(SearchDomain.AddedCommand.Raw(it)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (setup in setups) {
            ElementHeading(
                if (setup.id == SearchDomain.DEFAULT_CONFIGURATION_ID) "Default configuration" else setup.title.ifBlank { "Workplace setup" },
                setups.size,
            )
            setup.windows.forEachIndexed { windowAt, window ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(window.app.label + " " + (windowAt + 1), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        FrameButton("Remove window") { write(setup.id, setup.windows.filterIndexed { i, _ -> i != windowAt }) }
                    }
                    // User rule 2026-11-04: a file explorer window the setup opens is maximized, unless turned off here.
                    if (window.app.tabbed) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            androidx.compose.material3.Switch(
                                checked = window.maximized,
                                onCheckedChange = { on ->
                                    write(setup.id, setup.windows.mapIndexed { i, w -> if (i == windowAt) w.copy(maximized = on) else w })
                                },
                            )
                            NoteText("Maximized when opened (not when its tabs are added to a window already open)", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    window.tabs.forEachIndexed { tabAt, path ->
                        fun replaced(tabs: List<String>) =
                            setup.windows.mapIndexed { i, w -> if (i == windowAt) w.copy(tabs = tabs) else w }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            androidx.compose.runtime.key(setup.id, windowAt, tabAt) {
                                OutlinedTextField(
                                    value = path,
                                    onValueChange = { text ->
                                        write(
                                            setup.id, replaced(window.tabs.mapIndexed { i, t -> if (i == tabAt) text else t }),
                                            "workplace/" + setup.id + "/" + windowAt + "/" + tabAt,
                                        )
                                    },
                                    singleLine = true,
                                    label = {
                                        Text(if (window.app.tabbed) "Tab " + (tabAt + 1) + " path" else "Folder")
                                    },
                                    modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
                                )
                            }
                            if (window.app.tabbed) FrameButton("✕", enabled = window.tabs.size > 1) { write(setup.id, replaced(window.tabs.filterIndexed { i, _ -> i != tabAt })) }
                        }
                    }
                    if (window.app.tabbed) {
                        FrameButton("+ Tab") {
                            write(setup.id, setup.windows.mapIndexed { i, w -> if (i == windowAt) w.copy(tabs = w.tabs + "") else w })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                org.example.project.scheduler.domain.WorkplaceApp.entries.forEach { app ->
                    FrameButton("+ " + app.label + " window") {
                        write(setup.id, setup.windows + org.example.project.scheduler.domain.WorkplaceWindow(app = app))
                    }
                }
            }
        }
    }
}

/**
 * User rule 2026-11-01: **the button that applies the added workplace setups**, on the device of their OS. It opens windows
 * and types into them, a tab at a time, while the keyboard is left alone: what went wrong is said under it.
 */
@Composable
private fun WorkplaceApplyButton(setups: List<org.example.project.scheduler.domain.WorkplaceSetup>, runs: WorkplaceRuns, family: (org.example.project.scheduler.domain.WorkplaceSetup) -> List<org.example.project.scheduler.domain.WorkplaceSetup>) {
    // Applying one applies what stands under it (user rule 2026-11-02): it can be applied for them alone.
    val can = setups.flatMap(family).distinctBy { it.id }.filter { runs.canApply(it) }
    val running = setups.any { runs.isRunning(it.id) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FrameButton(if (running) "Opening…" else "Apply", enabled = can.isNotEmpty() && !running) {
            runs.applyAll(can)
        }
        val error = setups.firstNotNullOfOrNull { runs.errorOf(it.id) }
        val other = setups.firstOrNull { it.os != runs.here }
        when {
            error != null -> NoteText(error, color = MaterialTheme.colorScheme.error)
            other != null -> NoteText("For " + other.os.label + ": this device applies none of those.")
            setups.isNotEmpty() && can.isEmpty() -> NoteText("Give it a path first.")
        }
    }
}

/**
 * User rule 2026-11-12: how the "Children" action ([SearchDomain.AddedAction.TaskChildren]) draws a task's children — the
 * Search window's own sub-tree drawing, as an expanded row's ([SearchSubtree]), which only the window can build. Null
 * where no window gives it: the action then shows the task's heading alone.
 */
internal val LocalTaskChildren =
    androidx.compose.runtime.compositionLocalOf<(@Composable (TaskId, org.example.project.scheduler.model.CellListId) -> Unit)?> { null }
