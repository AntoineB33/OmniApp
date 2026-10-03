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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
                    value = config.actionQuery,
                    onValueChange = { onConfigChange(config.copy(actionQuery = it)) },
                    singleLine = true,
                    label = { Text("Search a configuration") },
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (sections.isEmpty()) {
                    Text(
                        text = "No configuration matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AddedActionSections(state, sections, added, config, onConfigChange, handlers, onIntent, nowMillis, onOpenEach, onClear)
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
 * [alarmEditor] and [reminderEditor] draw the editors of the given elements as the Alarms window and the reminder
 * editor draw them — embedded, the same component over the same callbacks (user rule 2026-10-01: the actions on the
 * added elements replaced each element's own window).
 */
class AddedActionHandlers(
    val onStartNow: (TaskId) -> Unit,
    val onEdit: (TaskId) -> Unit,
    val onGoToTaskTree: (TaskId) -> Unit,
    val onDeepCopyCell: (CellId) -> Unit,
    /** A period's "Search its tasks": the Search window of its tasks' resilience ([SearchDomain.resilienceSearchConfig]). */
    val onOpenResilienceSearch: (String) -> Unit,
    val alarmEditor: @Composable (Set<AlarmWindowSubject>) -> Unit,
    val reminderEditor: @Composable (Set<String>) -> Unit,
    /**
     * "New": one element of the kind, made the way its "creation" row makes it (`App.createElement`, without opening a
     * window for it); the keys of what it made, which join the window's added elements.
     */
    val onCreate: (SearchDomain.Kind) -> List<String>,
    /** "Duplicate": these intents ([SearchDomain.duplicateIntents]) dispatched; the keys of the kind's elements they made. */
    val onDuplicate: (List<SchedulerIntent>, SearchDomain.Kind) -> List<String>,
    /** "Add to the calendar": these drafts saved as the calendar's element window saves its own. */
    val onPlaceOnCalendar: (List<org.example.project.scheduler.domain.CalendarElements.Draft>) -> Unit,
    /** "Constrained in": the constraint picker over these reminders (by id) at once; what it saves, all of them get. */
    val onEditReminderConstraint: (List<String>) -> Unit = {},
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
        // The filter is set in the configurations window; said here, with its way off, so a quarter showing one
        // action is never a mystery.
        if (config.actionQuery.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Only the actions matching “${config.actionQuery.trim()}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FrameButton("Show all") { onConfigChange(config.copy(actionQuery = "")) }
            }
        }
        if (added.isEmpty()) {
            Text(
                text = "Nothing is added yet: the actions act on every added element at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val sections =
            SearchDomain.addedActions(config.actionQuery, SearchDomain.Kind.entries.toSet(), added.mapTo(HashSet()) { it.kind })
        // A vertical scrollbar on its right (user rule 2026-10-03), the same the lists of the window have.
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().weight(1f)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (sections.isEmpty()) {
                Text(
                    text = "No action matches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AddedActionSections(state, sections, added, config, onConfigChange, handlers, onIntent, nowMillis, onOpenEach, onClear)
        }
        ColumnScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
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
    onOpenEach: () -> Unit,
    onClear: () -> Unit,
) {
    // Every action goes through the app's own intents ([SearchDomain.addedIntents]).
    val run = { command: SearchDomain.AddedCommand ->
        SearchDomain.addedIntents(state, added, command, nowMillis()).forEach(onIntent)
    }
    // User rule 2026-10-02: the group whose actions reach the most added elements first.
    for ((kind, actions) in SearchDomain.sortedByReach(sections, added)) {
        val count = SearchDomain.reachOf(kind, added)
        Text(
            text = (kind?.label?.replaceFirstChar { it.uppercase() } ?: "Every element") + "  ·  $count",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        for (action in actions) {
            if (action in STACKED_ACTIONS) {
                // An editor too tall for the label's row: under its label, at the section's width.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(action.label, style = MaterialTheme.typography.bodyMedium)
                    AddedActionEditor(state, action, added, config, onConfigChange, handlers, run, nowMillis, onOpenEach, onClear)
                }
            } else {
                SettingRow(action.label) {
                    AddedActionEditor(state, action, added, config, onConfigChange, handlers, run, nowMillis, onOpenEach, onClear)
                }
            }
        }
    }
}

/** The actions whose editor is a block of its own (a list of steps, a document) rather than a control in a row. */
private val STACKED_ACTIONS: Set<SearchDomain.AddedAction> =
    setOf(
        SearchDomain.AddedAction.TaskScheduleUnit, SearchDomain.AddedAction.TaskText, SearchDomain.AddedAction.TaskAddUnder,
        SearchDomain.AddedAction.TaskPaths, SearchDomain.AddedAction.CategoryRules, SearchDomain.AddedAction.CategoryAddRule,
        SearchDomain.AddedAction.AlarmAlert,
        SearchDomain.AddedAction.TimerAlert, SearchDomain.AddedAction.ReminderAlert,
        SearchDomain.AddedAction.PeriodCombinations, SearchDomain.AddedAction.HistoryInformation,
        SearchDomain.AddedAction.TaskFulfilment, SearchDomain.AddedAction.TaskFulfilledBy,
    )

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
        // The calendar's "add…": at the calendar filter's instant, whether its switch is on or not.
        SearchDomain.AddedAction.PlaceOnCalendar -> {
            val at = config.filters.calendarAddAtMillis
            if (at == null) {
                Text(
                    "Give the calendar filter a position first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val drafts = SearchDomain.calendarDrafts(state, added, at)
                // A timer is put on the clock to end there (it has no start on the calendar to give).
                val timers = SearchDomain.calendarTimerIntents(state, added, at, nowMillis())
                val timerCount = (timers.firstOrNull() as? SchedulerIntent.SetTimers)?.let { set ->
                    set.entries.count { entry -> state.timers.none { it == entry } }
                } ?: 0
                val count = drafts.size + timerCount
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FrameButton(if (count == 1) "Add here" else "Add $count here", enabled = count > 0) {
                        if (drafts.isNotEmpty()) handlers.onPlaceOnCalendar(drafts)
                        timers.forEach { run(SearchDomain.AddedCommand.Raw(it)) }
                    }
                    // An alarm is added as a new one (an existing one's occurrences are its weekdays').
                    FrameButton("New alarm here") { handlers.onPlaceOnCalendar(listOf(SearchDomain.calendarAlarmDraft(state, at))) }
                }
            }
        }
        SearchDomain.AddedAction.TaskAddCategory ->
            CategoryChooser(
                cellId = "search/task-add-category",
                options = state.categories.sortedBy { it.title.lowercase() }.map { it.id to it.title },
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
                    Text("No panel of it can come", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("No default sub-tree", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        SearchDomain.AddedAction.ChronoTitle, SearchDomain.AddedAction.ReminderTitle ->
            SharedTitleField(state, added, SearchDomain.TITLED_KINDS.getValue(action), run)
        // The sound setting's control: the app's global volume. Written on release, so a drag is one write.
        SearchDomain.AddedAction.SoundVolume -> {
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
        // --- A new element of the kind, and a copy of each added one — both join the added elements -------------
        SearchDomain.AddedAction.TaskNew, SearchDomain.AddedAction.CategoryNew, SearchDomain.AddedAction.PeriodNew,
        SearchDomain.AddedAction.AlarmNew, SearchDomain.AddedAction.TimerNew, SearchDomain.AddedAction.ChronoNew,
        SearchDomain.AddedAction.ReminderNew -> {
            val kind = action.section ?: return
            FrameButton("New " + kind.label) {
                val made = handlers.onCreate(kind)
                if (made.isNotEmpty()) onConfigChange(config.copy(added = SearchDomain.withAdded(config.added, made)))
            }
        }
        SearchDomain.AddedAction.TaskDuplicate, SearchDomain.AddedAction.CategoryDuplicate,
        SearchDomain.AddedAction.PeriodDuplicate, SearchDomain.AddedAction.AlarmDuplicate,
        SearchDomain.AddedAction.TimerDuplicate, SearchDomain.AddedAction.ChronoDuplicate,
        SearchDomain.AddedAction.ReminderDuplicate -> {
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
        SearchDomain.AddedAction.HistoryInformation -> {
            val units = added.filterIsInstance<SearchDomain.ItemResult>()
                .filter { it.kind == SearchDomain.Kind.HistoryUnit }
                .mapNotNull { historyUnitEntryOfSearchId(state, it.id) }
            if (units.isEmpty()) {
                Text("No history unit is added.", style = MaterialTheme.typography.bodySmall)
            }
            for (entry in units) {
                val infos = historyEntryInfos(entry)
                ElementHeading(entry.unit.delta.label, units.size)
                infos.forEach { HistoryInfoLine(it) }
                HistoryCopyButton(label = "Copy all", value = infos.joinToString("\n") { "${it.label}: ${it.value}" })
            }
        }
        SearchDomain.AddedAction.TaskFulfilment -> FulfilmentEditor(state, added, run, handlers.onEdit)
        SearchDomain.AddedAction.TaskFulfilledBy -> FulfilledByEditor(state, taskIds, run, handlers.onEdit)
        SearchDomain.AddedAction.TaskPaths -> {
            for (taskId in taskIds) {
                val places = remember(taskId, state.cells, state.lists, state.tasks) { TaskPathsDomain.occurrences(state, taskId) }
                ElementHeading(state.tasks[taskId]?.title.orEmpty(), taskIds.size)
                if (places.isEmpty()) {
                    Text("In no place of the open task tree.", style = MaterialTheme.typography.bodySmall)
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
        // User rule 2026-10-02: every task carrying an added category given ONE share of its own sub-list — its row
        // of that sub-list's weight table adjusted (a common factor, an added term as the last resort).
        SearchDomain.AddedAction.CategorySubListShare -> {
            val categoryIds = SearchDomain.addedIds(added, SearchDomain.Kind.Category).mapTo(LinkedHashSet()) { CategoryId(it) }
            val carriers = CategoryRules.carrierCells(state, categoryIds)
            // What they all hold now, when they do — to the precision the field prints.
            val current = carriers.map { formatShareNumber(RelativePriorityDomain.cellShare(state, it)) }.distinct().singleOrNull()
            var draft by remember(categoryIds) { mutableStateOf<String?>(null) }
            val text = draft ?: current.orEmpty()
            val share = parsePercent(text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { draft = it },
                    enabled = carriers.isNotEmpty(),
                    isError = draft != null && share == null,
                    singleLine = true,
                    suffix = { Text("%") },
                    modifier = Modifier.width(110.dp).leaveFocusOnOutsidePress(),
                )
                FrameButton(
                    if (carriers.size == 1) "Force on 1 task" else "Force on ${carriers.size} tasks",
                    enabled = carriers.isNotEmpty() && draft != null && share != null,
                ) {
                    share?.let { run(SearchDomain.AddedCommand.CategoryShare(it)) }
                    draft = null
                }
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
                Text("s", style = MaterialTheme.typography.bodySmall)
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
                Text("s", style = MaterialTheme.typography.bodySmall)
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
    val editKey = "added/${kind.name}/title@$session"
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
) {
    var before by remember(field) { mutableStateOf<Map<String, T>?>(null) }
    var draft by remember(field) { mutableStateOf("") }
    var session by remember(field) { mutableStateOf(0) }
    val editKey = "added/$field@$session"
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
            Text("one task at a time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(
                "Only the schedulable tasks (${leaves.size}): a parent task is never placed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Text("Only a schedulable task has a set of tasks.", style = MaterialTheme.typography.bodySmall)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (leaf in leaves) {
            val task = state.tasks[leaf] ?: continue
            ElementHeading(task.title.ifBlank { SchedulerDomain.UNTITLED_LABEL }, leaves.size)
            val entries = task.fulfilment.entries.sortedBy { state.tasks[it.key]?.title.orEmpty().lowercase() }
            if (entries.isEmpty()) {
                Text("It fulfils no other task.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        .map { (id, label) -> NamingRow(SearchDomain.taskKey(id), label, taskColors[id]) }
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
                Text("No task's set holds it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        cellId = org.example.project.scheduler.model.CellId("search/add-under"),
        candidates = { query -> TaskPathsDomain.candidatesForAll(state, taskIds, query) },
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
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (enabled) MaterialTheme.colorScheme.outlineVariant else color, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
