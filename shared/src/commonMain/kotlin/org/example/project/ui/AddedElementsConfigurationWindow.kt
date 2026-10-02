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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.key
import org.example.project.scheduler.domain.CalendarLockDomain
import org.example.project.scheduler.domain.PeriodDrawing
import org.example.project.scheduler.ui.DrawingSwatch
import org.example.project.scheduler.ui.PeriodCombinationsSection
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TaskPathsDomain
import org.example.project.scheduler.model.CellId
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
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
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
    for ((kind, actions) in sections) {
        val count = added.count { kind == null || it.kind == kind }
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
        SearchDomain.AddedAction.TaskPaths, SearchDomain.AddedAction.CategoryEdit, SearchDomain.AddedAction.AlarmEdit,
        SearchDomain.AddedAction.TimerEdit, SearchDomain.AddedAction.ChronoEdit, SearchDomain.AddedAction.ReminderEdit,
        SearchDomain.AddedAction.PeriodCombinations,
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
        SearchDomain.AddedAction.CategoryEdit -> {
            for (id in SearchDomain.addedIds(added, SearchDomain.Kind.Category)) {
                val categoryId = CategoryId(id)
                if (state.categoryById(categoryId) == null) continue
                key(id) { CategoryEditor(state, categoryId, run.asIntentSink()) }
                HorizontalDivider()
            }
        }
        SearchDomain.AddedAction.AlarmEdit ->
            handlers.alarmEditor(SearchDomain.addedIds(added, SearchDomain.Kind.Alarm).mapTo(LinkedHashSet()) { AlarmWindowSubject(it, AlarmWindowSubject.Kind.Alarm) })
        SearchDomain.AddedAction.TimerEdit ->
            handlers.alarmEditor(SearchDomain.addedIds(added, SearchDomain.Kind.Timer).mapTo(LinkedHashSet()) { AlarmWindowSubject(it, AlarmWindowSubject.Kind.Timer) })
        SearchDomain.AddedAction.ChronoEdit ->
            handlers.alarmEditor(SearchDomain.addedIds(added, SearchDomain.Kind.Chrono).mapTo(LinkedHashSet()) { AlarmWindowSubject(it, AlarmWindowSubject.Kind.Chrono) })
        SearchDomain.AddedAction.ReminderEdit ->
            handlers.reminderEditor(SearchDomain.addedIds(added, SearchDomain.Kind.Reminder).toSet())
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
            val periods = SearchDomain.addedPeriodKinds(state, added)
            for (kind in periods) {
                ElementHeading(kind, periods.size)
                key(kind) {
                    PeriodCombinationsSection(kind, state.allPeriodKinds, state.periodCombinations) { rules ->
                        run.asIntentSink()(SchedulerIntent.SetPeriodCombinations(rules))
                    }
                }
                HorizontalDivider()
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
 * The task edit window's "Add under…" over every added task: the places at least one of them may go
 * ([TaskPathsDomain.candidatesForAll]), each a button that puts every one that may go there at once.
 */
@Composable
private fun AddUnderEditor(state: SchedulerState, taskIds: List<TaskId>, run: (SearchDomain.AddedCommand) -> Unit) {
    var query by remember { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        enabled = taskIds.isNotEmpty(),
        label = { Text("Add under…") },
        modifier = Modifier.fillMaxWidth().leaveFocusOnOutsidePress(),
    )
    if (query.isNotBlank() && taskIds.isNotEmpty()) {
        val candidates = remember(query, state.cells, state.lists, state.tasks, taskIds) {
            TaskPathsDomain.candidatesForAll(state, taskIds, query)
        }
        if (candidates.isEmpty()) {
            Text("No place matches.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (candidate in candidates) {
            Text(
                text = candidate.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        run(SearchDomain.AddedCommand.AddUnder(candidate.parentTaskId))
                        query = ""
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
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
