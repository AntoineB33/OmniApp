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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val sections = SearchDomain.addedActions(config.actionQuery, own.kinds, addedKinds.takeIf { own.onlyResultKinds })

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
                    modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
                )
                KindsDropDown(kinds = own.kinds, onKindsChange = { onOwnChange(own.copy(kinds = it)) })
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
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP),
            ) {
                if (sections.isEmpty()) NoteText("No configuration matches.")
                AddedActionSections(state, sections, added, config, onConfigChange, handlers, onIntent, nowMillis, onOpenEach, onClear)
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
class AddedActionHandlers(
    val onStartNow: (TaskId) -> Unit,
    val onEdit: (TaskId) -> Unit,
    val onGoToTaskTree: (TaskId) -> Unit,
    val onDeepCopyCell: (CellId) -> Unit,
    /** A period's "Search its tasks": the Search window of its tasks' resilience ([SearchDomain.resilienceSearchConfig]). */
    val onOpenResilienceSearch: (String) -> Unit,
    /** A creation row's "Search every element of the kind": the Search window on that kind ([SearchDomain.kindSearchConfig]). */
    val onOpenKindSearch: (SearchDomain.Kind) -> Unit,
    val timerRun: @Composable (Set<AlarmWindowSubject>) -> Unit,
    val reminderEditor: @Composable (Set<String>) -> Unit,
    /**
     * "New": one element of the kind, made the way its "creation" row makes it (`App.createElement`) — and opened in a
     * NEW Search window as its only added element (user rule 2026-10-04, every kind). It does not join this window's.
     */
    val onCreate: (SearchDomain.Kind) -> Unit,
    /** "Duplicate": these intents ([SearchDomain.duplicateIntents]) dispatched; the keys of the kind's elements they made. */
    val onDuplicate: (List<SchedulerIntent>, SearchDomain.Kind) -> List<String>,
    /** "Add to the calendar": these drafts saved as the calendar's element window saves its own. */
    val onPlaceOnCalendar: (List<org.example.project.scheduler.domain.CalendarElements.Draft>) -> Unit,
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
)

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
    modifier: Modifier = Modifier,
) {
    CompactFields {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // No "All configurations" button (removed 2026-10-04): every action is listed right here.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionArrow(collapsed, onToggleCollapsed)
            Text(
                text = "Actions on the added elements",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
        }
        if (collapsed) return@Column
        // User rule 2026-10-11: the section's search bar. It SORTS the actions inside each group of elements — the
        // ones whose title answers it first — and hides none, nor moves a group ([SearchDomain.sortedByActionSearch]).
        OutlinedTextField(
            value = config.actionSearch,
            onValueChange = { onConfigChange(config.copy(actionSearch = it)) },
            singleLine = true,
            label = { Text("Search an action") },
            trailingIcon = { SearchBarCross(config.actionSearch) { onConfigChange(config.copy(actionSearch = "")) } },
            modifier = Modifier.fillMaxWidth().leaveFocusOnOutsidePress(),
        )
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
        // A vertical scrollbar on its right (user rule 2026-10-03), the same the lists of the window have.
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().weight(1f)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 12.dp)
                .verticalScroll(scroll)
                .keepsWidthAbove(COMPACT_SECTION_MIN_WIDTH),
            verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP),
        ) {
            if (sections.isEmpty()) NoteText("No action matches.")
            AddedActionSections(state, sections, added, config, onConfigChange, handlers, onIntent, nowMillis, onOpenEach, onClear)
        }
        ColumnScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
        }
    }
    }
}

/** The sections of actions, each under its kind's title — one drawing for the quarter and for the window. */
@Composable
private fun AddedActionSections(
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
) {
    // User rule 2026-10-10: the groups from the widest reach to the narrowest — every element, each kind holding
    // several, then each element's own ([SearchDomain.actionGroups]). A group's actions act on ITS elements.
    // …and, inside each, the actions that answer the section's search bar first (user rule 2026-10-11).
    val groups = SearchDomain.actionGroups(sections, added, config.actionSearch)
    val listed = groups.flatMapTo(HashSet()) { group -> listOf(group.id) + group.inner.map { it.id } }
    val toggle = { id: String -> onConfigChange(SearchDomain.withActionGroupToggled(config, id, listed)) }
    for (group in groups) {
        ActionGroupBlock(group, state, config, onConfigChange, handlers, onIntent, nowMillis, onOpenEach, onClear, toggle) {
            // What the group holds inside it (the default configuration of its kind), each with its own arrow.
            for (inner in group.inner) {
                ActionGroupBlock(inner, state, config, onConfigChange, handlers, onIntent, nowMillis, onOpenEach, onClear, toggle) {}
            }
        }
    }
}

/**
 * One group of the actions section: its heading with **its expansion arrow** (user rule 2026-10-10: *"add an expansion
 * arrow button to each group"* — the sections' own, [SectionArrow]; what is retracted is the window's configuration,
 * [SearchDomain.Config.collapsedActionGroups]), and, while it is open, its actions drawn over its elements, then
 * [inside].
 */
@Composable
private fun ActionGroupBlock(
    group: SearchDomain.ActionGroup,
    state: SchedulerState,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    handlers: AddedActionHandlers,
    onIntent: (SchedulerIntent) -> Unit,
    nowMillis: () -> Long,
    onOpenEach: (List<SearchDomain.Result>) -> Unit,
    onClear: (List<SearchDomain.Result>) -> Unit,
    onToggle: (String) -> Unit,
    inside: @Composable () -> Unit,
) {
    val added = group.members
    val collapsed = group.id in config.collapsedActionGroups
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SectionArrow(collapsed) { onToggle(group.id) }
        if (group.heading == null) SectionTitle(group.title)
        else Text(group.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
    if (collapsed) return
    // Every action goes through the app's own intents ([SearchDomain.addedIntents]).
    val run = { command: SearchDomain.AddedCommand ->
        SearchDomain.addedIntents(state, added, command, nowMillis()).forEach(onIntent)
    }
    val openEach = { onOpenEach(added) }
    val clear = { onClear(added) }
    // What a field remembers (a draft being typed) belongs to the group's elements, not to its place in the list.
    androidx.compose.runtime.key(group.id, added.map(SearchDomain::keyOf)) {
        for (action in group.actions) {
            // User rule 2026-10-08: while the lateral menu is being customized, a right-click on an action offers "add
            // in the left-side menu" — the action, with the elements it acts on here ([AddableAction]).
            AddableAction(action, added, config) {
                if (action in STACKED_ACTIONS) {
                    // An editor too tall for the label's row: under its label, at the section's width.
                    Column(verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP)) {
                        Text(
                            action.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        AddedActionEditor(state, action, added, config, onConfigChange, handlers, run, nowMillis, openEach, clear)
                    }
                } else {
                    SettingRow(action.label) {
                        AddedActionEditor(state, action, added, config, onConfigChange, handlers, run, nowMillis, openEach, clear)
                    }
                }
            }
        }
    }
    inside()
}

/** The actions whose editor is a block of its own (a list of steps, a document) rather than a control in a row. */
private val STACKED_ACTIONS: Set<SearchDomain.AddedAction> =
    setOf(
        SearchDomain.AddedAction.TaskScheduleUnit, SearchDomain.AddedAction.TaskText, SearchDomain.AddedAction.TaskAddUnder,
        SearchDomain.AddedAction.TaskPaths, SearchDomain.AddedAction.CategoryRules, SearchDomain.AddedAction.CategoryAddRule,
        SearchDomain.AddedAction.AlarmAlert,
        SearchDomain.AddedAction.TimerAlert, SearchDomain.AddedAction.ReminderAlert,
        SearchDomain.AddedAction.PeriodCombinations, SearchDomain.AddedAction.HistoryInformation,
        SearchDomain.AddedAction.HistoryRules,
        SearchDomain.AddedAction.TaskFulfilment, SearchDomain.AddedAction.TaskFulfilledBy, SearchDomain.AddedAction.TaskCellCategories,
        SearchDomain.AddedAction.QuotaProgress, SearchDomain.AddedAction.QuotaLookTimes,
        SearchDomain.AddedAction.QuotaLoop, SearchDomain.AddedAction.QuotaResilience,
        SearchDomain.AddedAction.QuotaLoops, SearchDomain.AddedAction.QuotaAmount,
        SearchDomain.AddedAction.PlaceOnCalendar, SearchDomain.AddedAction.DragOnCalendar,
    )

/** The actions that ARE a control of the app the menu already knows ([MenuControl]): added as that control. */
private val ACTION_MENU_CONTROLS: Map<SearchDomain.AddedAction, MenuControl> =
    mapOf(
        SearchDomain.AddedAction.VoiceSwitch to MenuControl.Voice,
        SearchDomain.AddedAction.NotificationsSwitch to MenuControl.Notifications,
        SearchDomain.AddedAction.SoundVolume to MenuControl.SoundVolume,
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
            val control = ACTION_MENU_CONTROLS[action]
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
            SearchDomain.resolve(state, config.added)
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
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        // The editor is the Search window's: compact, and cut at the menu's width rather than squeezed.
        Box(Modifier.fillMaxWidth().cutAtBounds()) {
            CompactFields {
                AddedActionEditor(
                    state, action, added, config, { onConfigChange(it.encode()) }, handlers, run, nowMillis,
                    onOpenEach = { added.forEach(onOpen) }, onClear = {},
                )
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
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    handlers: AddedActionHandlers,
    run: (SearchDomain.AddedCommand) -> Unit,
    nowMillis: () -> Long,
    onOpenEach: () -> Unit,
    onClear: () -> Unit,
) {
    val taskIds = SearchDomain.addedTaskIds(state, added)
    val tasks = taskIds.mapNotNull { state.tasks[it] }
    fun idsOf(kind: SearchDomain.Kind) =
        added.filterIsInstance<SearchDomain.ItemResult>().filter { it.kind == kind }.mapTo(HashSet()) { it.id }
    when (action) {
        SearchDomain.AddedAction.OpenEach -> FrameButton("Open ${added.size}", enabled = added.isNotEmpty(), onClick = onOpenEach)
        SearchDomain.AddedAction.ClearList -> FrameButton("Remove all", enabled = added.isNotEmpty(), onClick = onClear)
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
        SearchDomain.AddedAction.CalendarBlocks -> {
            val owners = SearchDomain.blockOwners(added)
            FrameButton("Search the blocks", enabled = owners.isNotEmpty()) { handlers.onOpenBlocksSearch(owners) }
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
        SearchDomain.AddedAction.AlarmOnOff -> {
            val ids = idsOf(SearchDomain.Kind.Alarm)
            // Lit when every added alarm is in that state; neither chip when they differ.
            val shared = state.alarms.filter { it.id in ids }.map { it.enabled }.distinct().singleOrNull()
            Choices(listOf(true, false), shared, { if (it == true) "on" else "off" }) { run(SearchDomain.AddedCommand.AlarmsOn(it == true)) }
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
        SearchDomain.AddedAction.ChronoTitle, SearchDomain.AddedAction.ReminderTitle, SearchDomain.AddedAction.QuotaTitle ->
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
        SearchDomain.AddedAction.TimerRun -> {
            // PRD §18 / `alarms-and-timers.md`: a timer's run IS its countdown — the three fields reading down (and
            // editable, each by its own unit) and Elapsed, the countdown in reverse. Each added timer's own, drawn by
            // the Alarms window's row over the same callbacks; the buttons below them act on every added timer at once.
            val timers = SearchDomain.addedTimers(state, added)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (timer in timers) {
                    ElementHeading(timer.label.ifBlank { "Timer" }, timers.size)
                    handlers.timerRun(setOf(AlarmWindowSubject(timer.id, AlarmWindowSubject.Kind.Timer)))
                }
                if (timers.size != 1) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SearchDomain.RunStep.entries.forEach { step ->
                        FrameButton(step.label + if (timers.size > 1) " all" else "") { run(SearchDomain.AddedCommand.TimersRun(step)) }
                    }
                }
            }
        }
        SearchDomain.AddedAction.ChronoRun ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SearchDomain.RunStep.entries.forEach { step ->
                    FrameButton(step.label) { run(SearchDomain.AddedCommand.ChronosRun(step)) }
                }
            }
        // --- A new element of the kind (in a Search window of its own), and a copy of each added one (joining these) ---
        SearchDomain.AddedAction.TaskNew, SearchDomain.AddedAction.CategoryNew, SearchDomain.AddedAction.PeriodNew,
        SearchDomain.AddedAction.AlarmNew, SearchDomain.AddedAction.TimerNew, SearchDomain.AddedAction.ChronoNew,
        SearchDomain.AddedAction.ReminderNew, SearchDomain.AddedAction.QuotaNew -> {
            val kind = action.section ?: return
            FrameButton("New " + kind.label) { handlers.onCreate(kind) }
        }
        SearchDomain.AddedAction.TaskDuplicate, SearchDomain.AddedAction.CategoryDuplicate,
        SearchDomain.AddedAction.PeriodDuplicate, SearchDomain.AddedAction.AlarmDuplicate,
        SearchDomain.AddedAction.TimerDuplicate, SearchDomain.AddedAction.ChronoDuplicate,
        SearchDomain.AddedAction.ReminderDuplicate, SearchDomain.AddedAction.QuotaDuplicate -> {
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
            SharedTextField(
                items = SearchDomain.addedAlarms(state, added),
                idOf = { it.id },
                field = "Alarm/time",
                read = { formatAlarmTime(it.timeOfDayMinutes) },
                parse = { text -> parseAlarmTime(text)?.let { minutes -> { a: AlarmEntry -> a.copy(timeOfDayMinutes = minutes) } } },
                restore = { alarm, before -> alarm.copy(timeOfDayMinutes = before.timeOfDayMinutes) },
                write = { key, change -> run(SearchDomain.AddedCommand.AlarmsEdit(key, change)) },
                timeNudge = { alarm, delta -> alarm.copy(timeOfDayMinutes = nudgedTimeOfDay(alarm.timeOfDayMinutes, delta)) },
            )
        SearchDomain.AddedAction.AlarmRingsFor ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SharedTextField(
                    items = SearchDomain.addedAlarms(state, added),
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
            val alarms = SearchDomain.addedAlarms(state, added)
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
        SearchDomain.AddedAction.QuotaDelete,
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
                items = SearchDomain.addedTimers(state, added),
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
                    items = SearchDomain.addedTimers(state, added),
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
            val shared = SearchDomain.sharedValue(SearchDomain.addedTimers(state, added)) { it.goesNegative }
            Choices(listOf(true, false), shared, { if (it == true) "on" else "off" }) { on ->
                run(SearchDomain.AddedCommand.TimersEdit { it.copy(goesNegative = on == true) })
            }
        }
        SearchDomain.AddedAction.TimerAlert -> {
            val shown = SearchDomain.sharedAlert(SearchDomain.addedTimers(state, added).map { it.alert })
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
            val reminders = SearchDomain.addedReminders(state, added)
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
        }
        SearchDomain.AddedAction.ReminderTime ->
            SharedTextField(
                items = SearchDomain.addedReminders(state, added),
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
            val shown = SearchDomain.sharedAlert(SearchDomain.addedReminders(state, added).map { it.alert })
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
            val shared = SearchDomain.sharedValue(SearchDomain.addedAlarms(state, added)) { it.repeats }
            Choices(listOf(true, false), shared, { if (it == true) "on" else "off" }) { on ->
                run(SearchDomain.AddedCommand.AlarmsEdit { it.copy(repeats = on == true) })
            }
        }
        SearchDomain.AddedAction.AlarmAlert -> {
            val shown = SearchDomain.sharedAlert(SearchDomain.addedAlarms(state, added).map { it.alert })
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
        SearchDomain.AddedAction.PeriodDrawing -> {
            val periods = SearchDomain.addedPeriodKinds(state, added)
            val shared = periods.map { state.periodKindConfig.drawing(it) }.distinct().singleOrNull()
            ChoiceDropDown(
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

/** The name of the element a block of a per-element action is about — said only when there are several. */
@Composable
private fun ElementHeading(name: String, count: Int) {
    if (count > 1) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
            ChoiceDropDown(
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
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
        // Squeezed, it is cut — never wrapped under itself.
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (enabled) MaterialTheme.colorScheme.outlineVariant else color, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = buttonVerticalPadding(6.dp)),
    )
}
