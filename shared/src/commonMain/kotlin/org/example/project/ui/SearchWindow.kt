package org.example.project.ui

import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as isPointerMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.platform.writeSystemClipboardText
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.defaultSubtreeIsEmpty
import org.example.project.scheduler.ui.contextMenuModifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.state.projectSearchSubtree
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.ui.formatPriorityPercent
import org.example.project.scheduler.ui.TaskTreeView
import org.example.project.scheduler.ui.TaskRow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalDensity
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.windowSelectionKey
import org.example.project.scheduler.ui.TaskCellMenuItems
import org.example.project.scheduler.ui.TaskCellMenuActions
import org.example.project.scheduler.ui.ADD_REPLACING_LABEL

/** Every row of the result list has this one height, whatever it holds (PRD §7 *Search*). */
private val RESULT_ROW_HEIGHT: Dp = 34.dp

/** Every row's kind section is this one width, so the names line up whatever the kinds (fits "restrictive period"). */
private val KIND_SECTION_WIDTH: Dp = 104.dp


/** The narrowest the path box may be squeezed to by a long title — room for its arrow and a sliver of text. */
private val MIN_PATH_BOX_WIDTH: Dp = 34.dp

private const val NOT_IN_TREE_HINT: String =
    "Not in any task tree. The task is still referenced — by its past on the timeline, for example — so it " +
        "is kept, and its path is the last one it had in a task tree."

/**
 * PRD §7 **Search**: a floating window that finds a thing of the account by name. Two sections, top to
 * bottom — the **configuration** (a search bar, and a drop-down with a check box for each kind of thing to
 * look for — every checked kind is searched at once) and the **result list**.
 *
 * **Every row is the same height and the full width of the list** (an expanded task row's sub-tree hangs
 * under it), and **opens on a section naming its kind**. A **task row is the task tree's own cell**
 * ([SearchTaskRow] → [TaskRow]): its colour, its expand arrow, its title and Edit Mode, its percentage, minimum
 * time and categories, its outline, its menu — with the path box between the title and the percentage (the
 * shortest path; an arrow lists them all when there are several) and, for a task no task tree holds any more,
 * a **logo** saying so, last. A task no tree holds shows the path it had when it left
 * ([org.example.project.scheduler.model.Task.lastTreePath]). Every other kind is its name and one detail
 * ([SearchDomain.itemResults]).
 *
 * **While the user is in the search bar, no row is selected** — the task tree's rule for its selector's field:
 * ↓ or Enter there moves into the list, a click on a row does too, and ↑ on the first row goes back. **Typing on
 * a selected task row enters Edit Mode, stuck to Rename** (no mode selector): it commits
 * [SchedulerIntent.RenameTask], which a task no cell holds takes too.
 *
 * **A row answers the gestures a task cell does** — the fixed-height form of them, since a row here is not
 * a cell and cannot grow into an editor: a click selects it, `↑`/`↓` walk the selection, a double-click or
 * `Enter` opens it (a task's "edit task" window, a category's or a period kind's own window, the Alarms or the
 * Reminders window that owns an alarm, a timer or a reminder), `Ctrl + C` on a task copies its id, and a
 * right-click selects it and opens its contextual menu. A task's menu is the TREE CELL's own
 * ([TaskCellMenuItems]) — "go to task tree" included — on the row, on its path box and on each line of its list
 * of paths, each speaking for that path's occurrence ([SearchDomain.occurrenceAtPath]). The selection looks
 * as it does in the tree (the grey fill, [taskCellOutline]), and moving it scrolls the list only when it would
 * leave what is shown.
 *
 * The query and the checked kinds are **local-only view state** ([SearchDomain.Config]): `App` keeps them on this
 * device, so the window comes back with them after a close or a restart, and never syncs them — how the user is
 * looking for something is not a fact about the account. The selection is Compose-only. Nothing here writes the
 * state except through the gestures' own intents.
 */
/**
 * The Search window's sections as a way of looking at it: its two lines — the search section's share of the width,
 * the actions' share of the right side's height — and which of the three are retracted to their arrow.
 */
data class SearchSplits(
    val left: Float = 0.5f,
    val topRight: Float = 0.5f,
    val searchHidden: Boolean = false,
    val actionsHidden: Boolean = false,
    val addedHidden: Boolean = false,
    /**
     * User rule 2026-10-31: the task rows whose sub-tree is open, of the result list and of the added elements, by task
     * id. Here and not in the configuration: opening a row is no History Unit, and no other window than the one the
     * menu button that made it finds.
     */
    val expandedResults: Set<String> = emptySet(),
    val expandedAdded: Set<String> = emptySet(),
    /** User rule 2026-11-02: the rows of other kinds that are open, of both lists: [RESULT_SURFACE] or [ADDED_SURFACE] + the row's key. */
    val expandedElements: Set<String> = emptySet(),
    /**
     * User rule 2026-11-05: where the result list and the added elements are scrolled, each its first row shown and how
     * far under the top ([WindowLayout.scrolls]'s order): a menu button keeps them with the window, and puts them back.
     */
    val scrolls: List<Int> = emptyList(),
    /**
     * User rule 2026-11-11: what the window's background menu took off it — not retracted to an arrow, not there: the
     * window's header (its head: the title and the buttons, [AppWindowFrame]'s `showHead`) and each of the three sections.
     */
    val headerRemoved: Boolean = false,
    val searchRemoved: Boolean = false,
    val actionsRemoved: Boolean = false,
    val addedRemoved: Boolean = false,
    /**
     * User rule 2026-11-12: the search bar taken off by the background menu — the bar and its configurations arrow (▾) —
     * the rest of the search section staying ([SearchPart.Bar]).
     */
    val barRemoved: Boolean = false,
) {
    /** The flags as [WindowSections.hidden] keeps them: the three retracted, then the four removed, then the bar. */
    val hiddenFlags: List<Boolean>
        get() = listOf(searchHidden, actionsHidden, addedHidden, headerRemoved, searchRemoved, actionsRemoved, addedRemoved, barRemoved)

    companion object {
        /** Read back from [hiddenFlags]; a flag an older build did not write is off. */
        fun of(left: Float, topRight: Float, hidden: List<Boolean>): SearchSplits =
            SearchSplits(
                left, topRight,
                searchHidden = hidden.getOrNull(0) ?: false,
                actionsHidden = hidden.getOrNull(1) ?: false,
                addedHidden = hidden.getOrNull(2) ?: false,
                headerRemoved = hidden.getOrNull(3) ?: false,
                searchRemoved = hidden.getOrNull(4) ?: false,
                actionsRemoved = hidden.getOrNull(5) ?: false,
                addedRemoved = hidden.getOrNull(6) ?: false,
                barRemoved = hidden.getOrNull(7) ?: false,
            )
    }
}

/**
 * User rule 2026-11-02: **the Search window a task cell's Edit Mode shows** ([CellEditPopupLayer]): no head, no added
 * elements and no actions, nor anything that serves them (the check boxes, Add, the configurations) — not retracted, not
 * there. It looks through the tasks the cell may take ([eligible]), its rows open no sub-tree, and one click on a row is
 * the answer: [onPick], while [canPick] (off in Rename, whose field names the cell's own task: no row has anything to act
 * on). The selection is its own, never the state's, and it leaves the caret where the Edit Mode put it.
 */
class SearchPicker(
    val eligible: Set<TaskId>,
    val canPick: Boolean,
    val onPick: (TaskId) -> Unit,
    /**
     * What the creation row, first of the list, does at one click (user rule 2026-11-04): the cell's task id replaced
     * by a new one of the same title. Null where the cell cannot change task (Rename): no creation row.
     */
    val onNewTask: (() -> Unit)?,
    /** Leaves the Edit Mode, the draft abandoned: the ✕ of its head, once shown (user rule 2026-11-11). */
    val onCancel: () -> Unit = {},
)

/** A press and release on a row of a [SearchPicker]'s list, taken before the row: nothing in the row answers it. */
private fun Modifier.pickOnClick(onClick: () -> Unit): Modifier =
    composed {
        val latest by rememberUpdatedState(onClick)
        pointerInput(Unit) {
            awaitEachGesture {
                val press = awaitPointerEvent(PointerEventPass.Initial)
                if (press.type != PointerEventType.Press || !press.buttons.isPrimaryPressed) return@awaitEachGesture
                val down = press.changes.first()
                down.consume()
                var traveled = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    traveled += (change.position - change.previousPosition).getDistance()
                    // A finger scrolling the list: no click.
                    if (traveled > viewConfiguration.touchSlop) return@awaitEachGesture
                    if (!change.pressed) {
                        latest()
                        return@awaitEachGesture
                    }
                }
            }
        }
    }

/**
 * User rule 2026-11-02: the search bar's **completion list** ([SearchDomain.completionOptions]), between the bar and the
 * result list, which it moves down. A click writes the option in the bar and the list stays, showing what now completes.
 */
@Composable
private fun CompletionList(options: List<String>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    if (options.isEmpty()) return
    LazyColumn(
        modifier = modifier.fillMaxWidth().heightIn(max = COMPLETION_LIST_MAX_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(options.size, key = { options[it] }) { index ->
            val option = options[index]
            EditMenuRow(label = option, focusPreserving = true) { onPick(option) }
        }
    }
}

/** Five rows: the list must leave the results room. */
private val COMPLETION_LIST_MAX_HEIGHT: Dp = 140.dp

/**
 * How a row of the Search window is OPENED, whatever its kind — the one mapping from a row to the handler the
 * rest of the app already has for it (a task's "edit task" window, a category's or a period kind's own window,
 * the one element's window of an alarm, a timer, a chrono, a reminder or a history unit, the lateral-menu window that
 * owns a task tree, a task relation or a shortcut, a window brought back, a creation made). The result
 * list and the added elements' "Open each" (both windows that list it) go through [open]: never a second copy.
 */
data class SearchRowOpeners(
    val onOpenTaskEdit: (TaskId) -> Unit,
    val onOpenCategory: (CategoryId) -> Unit,
    val onOpenPeriodKind: (String) -> Unit,
    /**
     * The per-object window of ONE alarm, timer or chrono, with every setting it has — what opening its row does
     * (Enter, or the menu; a double-click adds the row instead — user rule 2026-10-01).
     */
    val onEditAlarmOrTimer: (AlarmWindowSubject) -> Unit,
    /** The per-object window of ONE reminder (by id), with every setting it has — opened like an alarm's. */
    val onEditReminder: (String) -> Unit,
    /** A history unit's own window (by its row id): the Search window holding it alone, its "Information" action. */
    val onOpenHistoryUnit: (String) -> Unit = {},
    /** Opens the Search window holding that quota alone — its actions are its editor (user rule 2026-10-03). */
    val onOpenQuota: (String) -> Unit = {},
    /** The lateral-menu windows that own a task tree, a task relation and a keyboard shortcut. */
    val onOpenTaskTrees: () -> Unit = {},
    val onOpenTaskRelations: () -> Unit = {},
    val onOpenShortcuts: () -> Unit = {},
    /** Opens the window a window row names (by frame id), or brings it back — a minimized one included. */
    val onOpenWindow: (String) -> Unit = {},
    /**
     * A "creation" row opened: make a new element of this kind and open its window — what that kind's own
     * "+ New …" does ([SearchDomain.Kind.Creation]).
     */
    val onCreate: (SearchDomain.Kind) -> Unit = {},
    /** An app setting's row (by [SearchDomain.AppSettingEntry] name): the Search window holding it alone. */
    val onOpenAppSetting: (String) -> Unit = {},
    /** A calendar block's row: what is on the calendar at its start — the calendar's own "edit…" there. */
    val onOpenCalendarAt: (Long) -> Unit = {},
    /** The calendar element's row (user rule 2026-11-12): a Search window showing the calendar. */
    val onOpenCalendar: () -> Unit = {},
    /** A workplace setup's row, by id: it is applied (user rule 2026-11-01), a command like a "creation" row. */
    val onApplyWorkplaceSetup: (String) -> Unit = {},
    /** The blank field under an element's children, double-clicked: a Search window coming from it ([ElementTree.childSearchConfig]). */
    val onOpenChildSearch: (parent: String) -> Unit = {},
) {
    companion object {
        /** The kinds a double-click on a result row opens: commands. Every other is added ([doubleClickOpens] false). */
        fun doubleClickOpens(kind: SearchDomain.Kind): Boolean =
            kind == SearchDomain.Kind.Creation || kind == SearchDomain.Kind.WorkplaceSetup
    }

    /**
     * These openers, each run inside [around] (user rule 2026-10-18): what a Search window is handed, so `App` knows
     * which window a row was opened from and links the window it opens to it ([SearchDomain.linkedConfig]). The
     * lateral menu keeps the plain ones: nothing it opens is any Search window's.
     */
    fun askedThrough(around: (() -> Unit) -> Unit): SearchRowOpeners =
        copy(
            onOpenTaskEdit = { around { onOpenTaskEdit(it) } },
            onOpenCategory = { around { onOpenCategory(it) } },
            onOpenPeriodKind = { around { onOpenPeriodKind(it) } },
            onEditAlarmOrTimer = { around { onEditAlarmOrTimer(it) } },
            onEditReminder = { around { onEditReminder(it) } },
            onOpenHistoryUnit = { around { onOpenHistoryUnit(it) } },
            onOpenQuota = { around { onOpenQuota(it) } },
            onOpenTaskTrees = { around { onOpenTaskTrees() } },
            onOpenTaskRelations = { around { onOpenTaskRelations() } },
            onOpenShortcuts = { around { onOpenShortcuts() } },
            onOpenWindow = { around { onOpenWindow(it) } },
            onCreate = { around { onCreate(it) } },
            onOpenAppSetting = { around { onOpenAppSetting(it) } },
            onOpenCalendarAt = { around { onOpenCalendarAt(it) } },
            onOpenCalendar = { around { onOpenCalendar() } },
            onApplyWorkplaceSetup = { around { onApplyWorkplaceSetup(it) } },
            onOpenChildSearch = { around { onOpenChildSearch(it) } },
        )

    /** Open [result]: a task only while the account still holds it (a task only a stored tree holds has no editor). */
    fun open(state: SchedulerState, result: SearchDomain.Result) {
        when (result) {
            is SearchDomain.TaskResult -> if (result.taskId in state.tasks) onOpenTaskEdit(result.taskId)
            is SearchDomain.ItemResult -> openItem(state, result)
        }
    }

    private fun openItem(state: SchedulerState, item: SearchDomain.ItemResult) {
        when (item.kind) {
            SearchDomain.Kind.Task -> Unit
            SearchDomain.Kind.Category -> onOpenCategory(CategoryId(item.id))
            SearchDomain.Kind.RestrictivePeriod -> onOpenPeriodKind(item.id)
            SearchDomain.Kind.Alarm -> onEditAlarmOrTimer(AlarmWindowSubject(item.id, AlarmWindowSubject.Kind.Alarm))
            SearchDomain.Kind.Timer -> onEditAlarmOrTimer(AlarmWindowSubject(item.id, AlarmWindowSubject.Kind.Timer))
            SearchDomain.Kind.Chrono -> onEditAlarmOrTimer(AlarmWindowSubject(item.id, AlarmWindowSubject.Kind.Chrono))
            SearchDomain.Kind.Reminder -> onEditReminder(item.id)
            SearchDomain.Kind.Quota -> onOpenQuota(item.id)
            SearchDomain.Kind.HistoryUnit -> onOpenHistoryUnit(item.id)
            // A notification is a fact that happened: it has nothing to open. Nor has a log entry — its actions show it.
            // A search configuration is set in the configurations tree, where it stands with its control.
            SearchDomain.Kind.Notification, SearchDomain.Kind.ClaudePrompt, SearchDomain.Kind.ClaudeAnswer,
            SearchDomain.Kind.ClaudeReasoning, SearchDomain.Kind.SearchConfiguration, SearchDomain.Kind.Option -> Unit
            SearchDomain.Kind.WorkplaceSetup -> onApplyWorkplaceSetup(item.id)
            SearchDomain.Kind.CalendarBlock ->
                SearchDomain.calendarBlockOf(state, item.id)?.let { onOpenCalendarAt(it.startMillis) }
            SearchDomain.Kind.TaskTree -> onOpenTaskTrees()
            SearchDomain.Kind.TaskRelation -> onOpenTaskRelations()
            SearchDomain.Kind.Shortcut -> onOpenShortcuts()
            SearchDomain.Kind.Window -> onOpenWindow(item.id)
            SearchDomain.Kind.AppSetting -> onOpenAppSetting(item.id)
            SearchDomain.Kind.Calendar -> onOpenCalendar()
            SearchDomain.Kind.Creation ->
                SearchDomain.Kind.entries.firstOrNull { it.name == item.id }?.let(onCreate)
        }
    }
}

@Composable
fun SearchWindow(
    /** The live state — the search is about the account's own things. */
    state: SchedulerState,
    /** How a row is opened ([SearchRowOpeners]) — shared with the Added elements configurations window. */
    openers: SearchRowOpeners,
    onStartTaskNow: (TaskId) -> Unit,
    /**
     * PRD §8 "go to task tree" — the app's one handler, shared with the calendar: the task's cell
     * at the given occurrence (a path the user right-clicked), else its first one.
     */
    onGoToTaskTree: (TaskId, SchedulerDomain.TaskOccurrence?) -> Unit,
    onDeepCopyCell: (CellId) -> Unit,
    /** The group of actions `App` asks this window to show: a new element joined it. [onActionRevealTaken] once it has. */
    actionRevealAsked: String? = null,
    onActionRevealTaken: () -> Unit = {},
    /** What the actions on the added elements open or draw ([AddedActionHandlers]): `App`'s, shared with the window of them all. */
    actionHandlers: AddedActionHandlers,
    /** The calendar's layer bands' kinds at an instant, for the "is on the calendar at" filter (`App` draws them). */
    calendarLayerKindsAt: (Long) -> Set<String> = { emptySet() },
    /**
     * The tree's own intents — the cell menu's entries that act on a cell, a row's minimum time and categories,
     * [SchedulerIntent.RenameTask] from a row's Edit Mode, and an expanded row's sub-tree
     * ([SchedulerIntent.InSearchSubtree]).
     */
    onIntent: (SchedulerIntent) -> Unit,
    /** PRD §5: a row's percentage opens its sub-list's weight table, as a tree cell's does. */
    onSetWeightWindow: (CellListId?) -> Unit = {},
    /** PRD §5: the percentage's right-click opens the cell's relative-priority window. */
    onSetRelativeWindow: (CellId?) -> Unit = {},
    /**
     * Every window of the app, open or not, one entry per instance ([SearchDomain.WindowEntry]) — the rows of the
     * "window" kind. `App` holds them, not the state.
     */
    windows: List<SearchDomain.WindowEntry> = emptyList(),
    /** The scheduler engine's runs (`TaskSchedulerViewModel.schedulerRuns`): its entries among the notification rows. */
    schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry> = emptyList(),
    /** The app's clock — what a timer's or a chrono's run, started from the added elements' actions, is read at. */
    nowMillis: () -> Long = { 0L },
    /** A box bound of the configurations tree picked by a click on the calendar ([SearchDomain.BoxBound]). */
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit = {},
    /** The Claude Code logs as last read (`App` reads them): the rows of the logs' kinds ([SearchDomain.CLAUDE_KINDS]). */
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog = org.example.project.claudelogs.ClaudeLogCatalog.EMPTY,
    onDismiss: () -> Unit,
    /**
     * The configuration — query, checked kinds, filters. Held by `App`, not here: the Configuration Search
     * window edits the same one, and it outlives this window (local-only view state, kept across restarts).
     */
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    /** The configuration this window opened with — what Reset goes back to first ([SearchDomain.resetConfig]). */
    openedConfig: SearchDomain.Config? = null,
    /**
     * Open with the type selector deployed (the calendar's "add…", user rule 2026-10-01): a one-shot — the drop-down
     * opens once and [onKindsDeployed] takes the request back.
     */
    deployKinds: Boolean = false,
    onKindsDeployed: () -> Unit = {},
    /** Opens the Configuration Search window, which lists every configuration of this window. */
    onOpenConfigurations: () -> Unit,
    /** Opens the Added elements configurations window, which lists every action on the added elements. */
    onOpenAddedConfigurations: () -> Unit = {},
    /**
     * Where the two lines between the three sections stand ([SearchSplits]), when the caller has a word on it (a
     * menu button's window, put back as the button kept it), and each drag of one told back. Null: half and half.
     */
    splits: SearchSplits? = null,
    onSplitsChange: (SearchSplits) -> Unit = {},
    /** Set: this is the window of a cell in Edit Mode, its search section alone ([SearchPicker]). */
    picker: SearchPicker? = null,
    /**
     * User rule 2026-11-12: **a Search window drawn inside another one** — the "List of parents" of the actions section
     * ([LocalEmbeddedSearch]): no frame, no section title, its selection its own (not the app window's), and the bar takes
     * no focus on appearing. Which parts it shows is [splits]' word, as for any Search window.
     */
    embedded: Boolean = false,
    /**
     * What a row's drop button does, while [SearchDomain.Config.dropButtons] is on: the "List of parents" takes the parent
     * off. Null: the row leaves the list, an excluded element of this window's configuration ([SearchDomain.Filters.excludedElements]).
     */
    onDrop: ((SearchDomain.Result) -> Unit)? = null,
    /** Whether [onDrop] can drop the row: a task's last place in the tree cannot be taken ([ElementTree.canUnlink]). */
    canDrop: (SearchDomain.Result) -> Boolean = { true },
    /**
     * User rule 2026-11-12: **its search bar is a field outside it** ([PopupSearchField], as a picker's is the cell): no bar
     * of its own; the query is the field's, through [config].
     */
    barOutside: Boolean = false,
    /**
     * User rule 2026-11-12: **a drop-down's list** ([OptionSearchMenu]): its rows are these options ([SearchDomain.Kind.Option]),
     * not the account's elements, a click on one ticking or picking it.
     */
    options: SearchOptions? = null,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    initialSize: Size = Size.Zero,
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    onRaise: () -> Unit = {},
) {
    val frame = if (picker == null && !embedded) rememberWindowFrameState("Search", initialOffset, initialSize) else null
    val query = config.query
    // User rule 2026-11-04: the kinds searched are the checked ones among those offered ([SearchDomain.Config.searchedKinds]).
    val kinds = config.searchedKinds
    val filters = config.filters
    val sorts = config.sorts
    // Which copy of the window this is: its selection is its own (PRD §5, `Alt+←` walks it back).
    val instance = LocalWindowInstance.current?.suffix ?: ""
    val listState = rememberLazyListState(splits?.scrolls?.getOrNull(0) ?: 0, splits?.scrolls?.getOrNull(1) ?: 0)
    // User rule 2026-10-14: typing in the bar brings the result list back to its top.
    ScrollsToTopWhenTyped(query, listState)
    val fieldFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    // The bar holds the focus from the moment the window opens: it is where the user types.
    // A picker's is not: the caret is in the cell (`task-tree.md`: the first letters typed are never lost).
    // Nor an embedded one's: it appears inside a window the user is working in.
    LaunchedEffect(Unit) { if (picker == null && !embedded) runCatching { fieldFocus.requestFocus() } }
    // The task tree's rule (its selector's name field above it): while the user is in the search BAR, no row is
    // selected. ↓ or Enter there moves into the list, a click on a row does too, and ↑ on the first row goes back.
    var fieldFocused by remember { mutableStateOf(!embedded) }
    // The picker's list shows a selection only once the keyboard went there, the caret being in the cell until then.
    var listFocused by remember { mutableStateOf(false) }
    val rowsSelectable = picker == null || listFocused
    // A task row in its rename-only Edit Mode (the tree's own field, TaskRow's): the task, and what is typed.
    var editingTaskId by remember { mutableStateOf<TaskId?>(null) }
    var editDraft by remember { mutableStateOf("") }
    // User rule 2026-11-11: the head and the sections the background menu took off ([SearchPart]). Here and not in the
    // content: the frame draws the head, and the row menu's "add" is offered while the added elements are there.
    // A picker given nothing opens as the cell's Edit Mode shows it: its search section alone.
    val shownSplits = splits ?: CellEditPopupHost.PICKER_SPLITS.takeIf { picker != null }
    var headerRemoved by remember { mutableStateOf(shownSplits?.headerRemoved == true) }
    var barRemoved by remember { mutableStateOf(shownSplits?.barRemoved == true) }
    var searchRemoved by remember { mutableStateOf(shownSplits?.searchRemoved == true) }
    var actionsRemoved by remember { mutableStateOf(shownSplits?.actionsRemoved == true) }
    var addedRemoved by remember { mutableStateOf(shownSplits?.addedRemoved == true) }
    // The rows whose sub-tree is open, kept with the window's sections ([SearchSplits]), and the one whose sub-tree
    // holds the keyboard, Compose-only.
    var expandedTasks by remember { mutableStateOf(splits?.expandedResults.orEmpty().map { TaskId(it) }.toSet()) }
    var expandedAdded by remember { mutableStateOf(splits?.expandedAdded.orEmpty().map { TaskId(it) }.toSet()) }
    var expandedElements by remember { mutableStateOf(splits?.expandedElements.orEmpty()) }
    val toggleElement: (String) -> Unit = { key -> expandedElements = if (key in expandedElements) expandedElements - key else expandedElements + key }
    // Anomaly 2026-11-05: the children under an element's arrow are selected as the rows are ([ElementChildSelection]),
    // on either surface; a press on anything else leaves none selected.
    val childSelection = remember { ElementChildSelection() }
    childSelection.editor = rememberOutsidePressEditor(active = childSelection.selected.isNotEmpty()) { childSelection.clear() }
    val elementCategories = remember(state.elementLinks, state.categories) { ElementTree.categoryTitles(state) }
    var subtreeFocusOwner by remember { mutableStateOf<TaskId?>(null) }
    // Anomaly 2026-10-07 ("I click on a task cell in the sub-tree, then on the root task cell: both are selected"):
    // **ONE surface of the window shows a selection of cells at a time.** A sub-tree's selection is the state's
    // (`searchSelection`, shared by every sub-tree), a row's is the window's own; a press on either left the other
    // drawn. This names the sub-tree the last press went to — "result/<task>" or "added/<task>" — and is null once
    // a row took the selection back. Sticky, unlike [subtreeFocusOwner]: a press in another window changes nothing.
    var selectionSurface by remember { mutableStateOf<String?>(null) }
    // The "Children" action's sub-tree holding the keyboard (user rule 2026-11-12), apart from the rows' own.
    var actionsSubtreeFocus by remember { mutableStateOf<TaskId?>(null) }
    // The pinned parent row of the result list ([pinnedItemIndex]): the list's top in the window, which an expanded
    // row's sub-tree reckons its own pinned row from, and the row whose sub-tree is pinning a deeper parent there.
    var resultListTop by remember { mutableStateOf<Float?>(null) }
    var resultNestedPin by remember { mutableStateOf<TaskId?>(null) }
    // PRD §10: the row whose minimum time is open as an input — it closes when the selection moves, as in the tree.
    var minTimeEditTaskId by remember { mutableStateOf<TaskId?>(null) }

    // Every path of every task is a walk of every task tree, so it is held on the trees alone — a keystroke
    // in the search bar filters it, it does not redo it. Keyed on the tree fields rather than on the whole
    // state, which every engine tick replaces (records live on the tasks) — ADR 0009.
    // The added elements (the right half), read off the live state the way the result list reads its rows.
    val addedRows =
        remember(
            config.added, state.tasks, state.taskTrees, state.categories, state.periodKinds, state.panels, state.alarms,
            state.timers, state.chronos, state.quotas, state.chores, state.histories, state.taskRelations, state.shortcutBindings,
            state.activeTaskTreeId, state.cells, state.lists, windows, state.notificationLog, schedulerRuns, claudeLogs,
        ) {
            // An added task shows its shortest path; listing every path is the result list's walk, not needed here.
            SearchDomain.resolve(state, config.added, { emptyMap() }, windows, schedulerRuns, claudeLogs)
        }
    val searchesTasks = SearchDomain.Kind.Task in kinds || filters.includedElements.any { it.startsWith(SearchDomain.Kind.Task.name + "/") }
    val allPaths =
        remember(searchesTasks, state.cells, state.lists, state.tasks, state.taskTrees, state.activeTaskTreeId) {
            if (searchesTasks) SearchDomain.allPathsInAnyTree(state) else emptyMap()
        }
    val results: List<SearchDomain.Result> =
        if (options != null) {
            remember(options.options, options.checked, query) { SearchDomain.optionResults(options.options, query, options.checked) }
        } else
        remember(
            kinds, query, filters, sorts, allPaths, state.tasks, state.taskTrees, state.categories, state.periodKinds,
            state.panels, state.alarms, state.timers, state.chronos, state.quotas, state.chores, state.histories, state.taskRelations,
            state.shortcutBindings, state.activeTaskTreeId, state.cells, state.lists, windows, state.notificationLog, schedulerRuns,
            // The calendar filter's "without removing anything" lays what "Add to the calendar" would, as long as it would.
            config.placement.takeIf { filters.calendarAddKeeping },
            addedRows.takeIf { filters.calendarAddKeeping },
            claudeLogs,
            picker?.eligible,
            config.filters.queryOptions,
            config.extraQueries,
            config.queryFormula,
        ) {
            val found =
                SearchDomain.results(
                    state, kinds, query, { allPaths }, filters, sorts, windows, nowMillis = nowMillis(), layerKindsAt = calendarLayerKindsAt,
                    schedulerRuns = schedulerRuns, placement = config.placement,
                    placementOwnLength = if (filters.calendarAddKeeping) SearchDomain.placementDefaultLength(state, addedRows) else null,
                    claudeLogs = claudeLogs,
                    match = config.filters.queryOptions,
                    extraQueries = config.extraQueries,
                    queryFormula = config.queryFormula,
                )
            if (picker != null) {
                SearchDomain.pickerRows(state, found, picker.eligible, creation = picker.onNewTask != null)
            } else {
                // One kind checked and nothing of it found: its "creation" row stands in the list.
                SearchDomain.withCreationWhenEmpty(state, found, kinds)
            }
        }
    val count = results.size
    // PRD §5: the selected row lives in the state, by its result key, so `Alt+←` can put it back. A key no longer
    // among the results (the question changed, the thing was deleted) reads as the first row.
    var pickerSelectedKey by remember { mutableStateOf<String?>(null) }
    val selectedKey = if (picker != null || embedded) pickerSelectedKey else state.windowSelections[windowSelectionKey(HistoryWindow.Search, instance)]
    val selected = results.indexOfFirst { resultKey(it) == selectedKey }.coerceAtLeast(0)
    // The rows selected with Ctrl+click and Shift+click (user rule 2026-10-01), by result key — what the row menu's
    // "add" adds. Null: the selection is the selected row alone. Compose-only, like the check boxes; the selected row
    // above (the outline the keys move) stays the state's, so `Alt+←` still walks it.
    var multiSelection by remember { mutableStateOf<Set<String>?>(null) }
    // Anomaly 2026-11-03 (*"When the user clicks elsewhere, the selected element should stop being selected."*): a press
    // anywhere but on a row of the list (its own menus included) leaves no row selected, until a row is selected again.
    var deselected by remember { mutableStateOf(false) }
    // Where a Shift+click's range starts ([ClickSelection]): the last row clicked, or moved to with the keys.
    var selectionAnchor by remember { mutableStateOf<String?>(null) }
    /**
     * Select the row at [index] — alone: the keys and every other way of selecting one row drop a multi-selection
     * ([clickRow] puts its own back). [record] false is the window's own reset, not a position the user took.
     */
    fun select(index: Int, record: Boolean = true) {
        val key = results.getOrNull(index)?.let(::resultKey)
        multiSelection = null
        deselected = false
        childSelection.clear()
        selectionAnchor = key
        if (picker != null || embedded) {
            pickerSelectedKey = key
            return
        }
        // No "already selected?" test here: this runs from row gestures started long before, whose `selectedKey`
        // would be that old composition's — the reducer answers it against the live state instead.
        onIntent(SchedulerIntent.SelectInWindow(HistoryWindow.Search, instance, key, record))
    }
    // A new question starts at its best answer.
    LaunchedEffect(kinds, query, filters, sorts) {
        select(0, record = false)
        selectionSurface = null
    }
    // A list read at its top stays at its top when rows arrive above it. The rows are keyed, and a keyed lazy
    // list anchors its scroll on the first VISIBLE row: a new history unit sorted first (a move of the focus,
    // newest on top) landed just above the view, so the list looked frozen while it was growing (anomaly,
    // 2026-09-25). Whether it was at the top is read before the new rows are laid out — here, in composition —
    // and only as a boolean, so scrolling does not recompose the window.
    val atTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    val stayAtTop = atTop
    LaunchedEffect(results.firstOrNull()?.let(::resultKey)) { if (stayAtTop) listState.scrollToItem(0) }
    LaunchedEffect(selected) { minTimeEditTaskId = null }
    // A drop-down's list with no end ([SearchOptions.onNearEnd]): more is asked as its last rows come in view.
    val latestOnNearEnd by rememberUpdatedState(options?.onNearEnd)
    LaunchedEffect(listState, options?.onNearEnd != null) {
        if (options?.onNearEnd == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.let { info -> (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 3 } }
            .distinctUntilChanged()
            .collect { near -> if (near) latestOnNearEnd?.invoke() }
    }
    // The task tree's rule: the list scrolls only when the selection would leave what is on screen — by just
    // enough to bring it to the nearer edge. Scrolling the selected row to the top on every move made the list
    // jump under a selection that was already in view.
    val rowHeightPx = with(LocalDensity.current) { RESULT_ROW_HEIGHT.toPx() }
    LaunchedEffect(selected, count) {
        if (count == 0) return@LaunchedEffect
        val index = selected.coerceIn(0, count - 1)
        val info = listState.layoutInfo
        val top = info.viewportStartOffset
        val bottom = info.viewportEndOffset
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        when {
            item == null ->
                if (index < (info.visibleItemsInfo.firstOrNull()?.index ?: 0)) {
                    listState.scrollToItem(index)
                } else {
                    // Below what is shown: land it on the bottom edge, as a step down would.
                    listState.scrollToItem(index)
                    listState.scrollBy(-((bottom - top) - rowHeightPx).coerceAtLeast(0f))
                }
            item.offset < top -> listState.animateScrollBy((item.offset - top).toFloat())
            item.offset + item.size > bottom -> listState.animateScrollBy((item.offset + item.size - bottom).toFloat())
        }
    }

    val currentState by rememberUpdatedState(state)
    val latestConfig by rememberUpdatedState(config)
    /** A drop-down's option clicked ([options]): ticked or picked, or "Select all" over the options listed. */
    fun actOnOption(id: String) {
        val given = options ?: return
        if (id == SearchDomain.SELECT_ALL_OPTION) {
            given.onSelectAll(results.mapNotNull { (it as? SearchDomain.ItemResult)?.id?.takeIf { row -> row != SearchDomain.SELECT_ALL_OPTION } })
        } else {
            given.onClick(id)
        }
    }
    fun openSelected() {
        val row = results.getOrNull(selected) ?: return
        if (options != null) {
            (row as? SearchDomain.ItemResult)?.let { actOnOption(it.id) }
            return
        }
        if (picker == null) openers.open(state, row)
        else if (picker.canPick && row is SearchDomain.TaskResult) picker.onPick(row.taskId)
        else if (row.kind == SearchDomain.Kind.Creation) picker.onNewTask?.invoke()
    }
    /** PRD §4 Edit Mode, stuck to Rename: [initial] is the draft to start from (a typed letter, or the title). */
    fun beginEdit(result: SearchDomain.TaskResult, initial: String) {
        if (picker != null) return
        editingTaskId = result.taskId
        editDraft = initial
    }
    /**
     * Leaving Edit Mode: the rename is committed (a blank one is refused by the reducer) unless [cancel], and the
     * selection takes [step] — Enter's step down, Shift+Enter's up, as in the tree.
     */
    fun endEdit(result: SearchDomain.TaskResult, cancel: Boolean, step: Int = 0, refocusList: Boolean = true) {
        if (!cancel && editDraft != result.title) onIntent(SchedulerIntent.RenameTask(result.taskId, editDraft))
        editingTaskId = null
        if (count > 0) select((selected + step).coerceIn(0, count - 1))
        if (refocusList) runCatching { listFocus.requestFocus() }
    }
    /** The row being renamed, if any — what a press elsewhere commits first, as leaving a tree cell does. */
    fun commitOpenEdit(refocusList: Boolean) {
        val id = editingTaskId ?: return
        val row = results.firstOrNull { it is SearchDomain.TaskResult && it.taskId == id } as SearchDomain.TaskResult?
        if (row == null) editingTaskId = null else endEdit(row, cancel = false, refocusList = refocusList)
    }
    /** A press on a row: it becomes the selection, and the list — not the bar — holds the keyboard. */
    fun selectRow(index: Int) {
        if (editingTaskId != null && (results.getOrNull(index) as? SearchDomain.TaskResult)?.taskId != editingTaskId) {
            commitOpenEdit(refocusList = false)
        }
        select(index)
        subtreeFocusOwner = null
        selectionSurface = null
        runCatching { listFocus.requestFocus() }
    }
    // Back in the search bar: a rename left open is committed, as it is when a tree cell is left.
    LaunchedEffect(fieldFocused) { if (fieldFocused) commitOpenEdit(refocusList = false) }
    // The live tree's figures, read as the tree reads them: the absolute priorities and the task colours.
    val priorities = remember(state.cells, state.lists, state.tasks) { SchedulerDomain.absoluteTaskPriorities(state) }
    val taskColors = TaskPalette.sheetColors(rememberTaskHues(state))

    // Ctrl and Shift as held on the last press in the list, for the rows whose own gesture does not say (a tree cell's
    // does: [TaskRow] passes them).
    val pressModifiers = remember { PressModifiers() }
    val resultKeys = remember(results) { results.map(::resultKey) }
    // "A row is selected" is the outline's own rule: not while the bar holds the focus, nor while a row's
    // sub-tree does.
    val hasSelection = count > 0 && !fieldFocused && subtreeFocusOwner == null && rowsSelectable && !deselected
    // The rows of the list: a press outside every one of them takes the selection away (the app's one observer).
    val resultRows = rememberOutsidePressEditor(active = hasSelection) {
        deselected = true
        multiSelection = null
    }
    // User rule 2026-11-03: the rows are added from the row menu's "add" alone, on the SELECTED rows — Ctrl+A selects them
    // all. The check boxes, "Select all" and "Add" were the same thing a second way.
    // The selected rows, in the list's order: the Ctrl/Shift multi-selection, else the selected row; none while the
    // bar or a sub-tree holds the focus (the outline's own rule).
    val selectedShown =
        if (!hasSelection) {
            emptyList()
        } else {
            multiSelection?.let { picked -> resultKeys.filter { it in picked } } ?: listOfNotNull(resultKeys.getOrNull(selected))
        }
    val latestSelectedShown by rememberUpdatedState(selectedShown)
    /**
     * A press on the row at [index]: [ClickSelection]'s rule over the result list — Ctrl adds or takes it, Shift takes
     * the range from the anchor, a plain press selects it alone. [keepIfSelected]: a press that must not collapse the
     * selection it lands in — a right-click (its menu acts on the selection) and the first press of a tree cell's
     * plain click, which the cell resolves a moment later. The row becomes the selected row either way.
     */
    fun clickRow(index: Int, ctrl: Boolean, shift: Boolean, keepIfSelected: Boolean) {
        val key = resultKeys.getOrNull(index) ?: return
        val current = latestSelectedShown.toSet()
        val kept = multiSelection
        val anchor = selectionAnchor
        selectRow(index)
        if (keepIfSelected && !ctrl && !shift && key in current) {
            multiSelection = kept
            selectionAnchor = anchor
            return
        }
        val next = ClickSelection.click(resultKeys, current, anchor, key, shift = shift, ctrl = ctrl)
        multiSelection = next.selected
        selectionAnchor = next.anchor
    }
    /**
     * [keys] into the added elements, each once ([SearchDomain.withAdded]); [replacing]: every element added before
     * leaves the list first, so it holds exactly these.
     */
    fun addKeys(keys: List<String>, replacing: Boolean = false) {
        val current = latestConfig
        val next = SearchDomain.withAdded(if (replacing) emptyList() else current.added, keys)
        if (next != current.added) onConfigChange(current.copy(added = next))
    }
    /**
     * The row menu's "add": every SELECTED row into the added elements, in the list's order (not the checked ones);
     * [replacing] is its "add and remove the others" (user rule 2026-10-01).
     */
    fun addSelected(replacing: Boolean = false) = addKeys(latestSelectedShown, replacing)
    // A child selected: the result list's rows are not any more (the added list's leave on the press outside them).
    childSelection.onTake = { rowKey ->
        deselected = true
        multiSelection = null
        // A child of the result list holds the list's keyboard, as a row does: the arrows go on from it.
        if (rowKey.startsWith(RESULT_SURFACE)) {
            subtreeFocusOwner = null
            selectionSurface = null
            runCatching { listFocus.requestFocus() }
        }
    }
    childSelection.onAdd = { keys, replacing -> addKeys(keys, replacing) }
    /**
     * User rule 2026-10-07: the menu of a CHILD cell — one of an expanded row's sub-tree, in the result list or in the
     * added elements — offers "add" and "add and remove the others" too: those cells' tasks (a placeholder holds none).
     */
    val addTasks = { taskIds: List<TaskId>, replacing: Boolean ->
        addKeys(taskIds.filter { currentState.tasks[it]?.title?.isNotBlank() == true }.map { SearchDomain.taskKey(it) }.distinct(), replacing)
    }
    /**
     * A task row's menu — the TREE CELL's own ([TaskCellMenuActions], drawn by [TaskCellMenuItems]), so the two
     * offer the same entries. [path] is the one the right-click landed on (the row's shown path, or a line of its
     * list of paths): "go to task tree" goes to THAT occurrence, and the entries that act on a cell (deep copy,
     * collapse, add the default sub-tree) act on it. Built on demand, so it never closes over a stale state.
     */
    fun taskActions(result: SearchDomain.TaskResult, path: List<String>?): TaskCellMenuActions {
        val state = currentState
        val taskId = result.taskId
        val live = state.tasks[taskId]
        val atPath = path?.let { SearchDomain.occurrenceAtPath(state, taskId, it) }
        val occurrence = atPath ?: SchedulerDomain.firstTaskOccurrence(state, taskId)
        val hasChildren = live?.childListId?.let { state.lists[it]?.cellIds?.isNotEmpty() } == true
        return TaskCellMenuActions(
            onStartNow =
                if (live != null && SchedulerDomain.isPlaceableTask(state, taskId)) {
                    { onStartTaskNow(taskId) }
                } else {
                    null
                },
            // User rule 2026-10-01: a result row's menu ADDS the task to this window's added elements instead of
            // opening its edit window — the window that edits it is this one.
            onEdit = null,
            // User rule 2026-10-01: the menu's "add" adds every SELECTED row — the right-click selected this one first
            // when it was not among them.
            // A picker's rows add once its added elements are shown (user rule 2026-11-11).
            onAdd = if (picker == null || !addedRemoved) { { addSelected() } } else null,
            onAddReplacing = if (picker == null || !addedRemoved) { { addSelected(replacing = true) } } else null,
            calendarTaskId = taskId,
            // Always offered, like the calendar panel's: the app's handler says so when no cell holds the task.
            onGoToTaskTree = { onGoToTaskTree(taskId, atPath) },
            onCopyTaskId = { writeSystemClipboardText(SchedulerDomain.TASK_ID_REFERENCE_PREFIX + taskId.value) },
            onDeepCopy = occurrence?.let { { onDeepCopyCell(it.cellId) } },
            onCollapseSubtrees =
                occurrence?.takeIf { hasChildren }?.let { { onIntent(SchedulerIntent.CollapseSubtrees(it.cellId)) } },
            onAddDefaultSubtree =
                occurrence?.takeIf { !state.defaultSubtreeIsEmpty }
                    ?.let { { onIntent(SchedulerIntent.AddDefaultSubtree(listOf(it.cellId))) } },
        )
    }

    /**
     * The drop button of [row] while [SearchDomain.Config.dropButtons] is on — [onDrop], else the row leaves the list as an
     * excluded element. A creation row is a command, not an element of the list: it has none.
     */
    fun dropOf(row: SearchDomain.Result): (() -> Unit)? {
        if (!config.dropButtons || row.kind == SearchDomain.Kind.Creation || !canDrop(row)) return null
        val drop = onDrop
        return if (drop != null) {
            { drop(row) }
        } else {
            {
                val current = latestConfig
                onConfigChange(current.copy(filters = current.filters.copy(excludedElements = current.filters.excludedElements + resultKey(row))))
            }
        }
    }

    val windowContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
      // Three sections: the search on the left; on the right, the actions on the added elements above the list
      // of the added elements. Both separators are dragged to share the room (Compose-only, like the zoom):
      // each split starts at half and neither section shrinks below its minimum. Their joint moves both at once.
      val density = LocalDensity.current
      val separatorPx = with(density) { SECTION_SEPARATOR_THICKNESS.toPx() }
      var leftShare by remember { mutableStateOf(splits?.left ?: 0.5f) }
      var topRightShare by remember { mutableStateOf(splits?.topRight ?: 0.5f) }
      // Given from outside (a menu button putting its window back, user rule 2026-10-07): taken; a drag tells it back.
      // User rule 2026-10-04: each of the three sections is retracted to its head and expanded again by the little
      // arrow in it ([SectionArrow]). Compose-only, like the splits: a way of looking at the window. A retracted
      // section gives its room to its neighbour, and the separator between the two is gone with it.
      var searchCollapsed by remember { mutableStateOf(splits?.searchHidden ?: false) }
      var actionsCollapsed by remember { mutableStateOf(splits?.actionsHidden ?: false) }
      // The group of actions the added elements' "show only its actions" last asked for, and the count of the asks.
      var actionReveal by remember { mutableStateOf<Pair<String, Int>?>(null) }
      // …or `App` did, for a new element that joined this window: the section opens to show its group.
      LaunchedEffect(actionRevealAsked) {
          if (actionRevealAsked != null) {
              actionsCollapsed = false
              actionReveal = actionRevealAsked to ((actionReveal?.second ?: 0) + 1)
              onActionRevealTaken()
          }
      }
      var addedCollapsed by remember { mutableStateOf(splits?.addedHidden ?: false) }
      // User rule 2026-11-11: the background's menu takes the header and each section off the window, and puts it back
      // expanded ([SearchSplits.headerRemoved]). A picker too: it is the Search window with those parts taken off.
      LaunchedEffect(splits) {
          splits?.let {
              leftShare = it.left
              topRightShare = it.topRight
              searchCollapsed = it.searchHidden
              actionsCollapsed = it.actionsHidden
              addedCollapsed = it.addedHidden
              headerRemoved = it.headerRemoved
              barRemoved = it.barRemoved
              searchRemoved = it.searchRemoved
              actionsRemoved = it.actionsRemoved
              addedRemoved = it.addedRemoved
          }
      }
      // The added elements' scroll, told by their list ([AddedElementsList]).
      var addedScroll by remember { mutableStateOf((splits?.scrolls?.getOrNull(2) ?: 0) to (splits?.scrolls?.getOrNull(3) ?: 0)) }
      fun splitsNow() =
          SearchSplits(
              leftShare, topRightShare, searchCollapsed, actionsCollapsed, addedCollapsed,
              expandedTasks.mapTo(HashSet()) { it.value }, expandedAdded.mapTo(HashSet()) { it.value }, expandedElements,
              scrolls = listOf(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, addedScroll.first, addedScroll.second),
              headerRemoved = headerRemoved, searchRemoved = searchRemoved, actionsRemoved = actionsRemoved, addedRemoved = addedRemoved,
              barRemoved = barRemoved,
          )
      // A section retracted or brought back, a row opened or closed, a list come to rest after a scroll, is told as a
      // dragged line is (user rule 2026-11-05: the scrolls are the window's state too). Event-driven: on the scroll's end.
      LaunchedEffect(
          searchCollapsed, actionsCollapsed, addedCollapsed, expandedTasks, expandedAdded, expandedElements, addedScroll,
          headerRemoved, searchRemoved, actionsRemoved, addedRemoved, barRemoved,
      ) { onSplitsChange(splitsNow()) }
      LaunchedEffect(listState) {
          androidx.compose.runtime.snapshotFlow {
              if (listState.isScrollInProgress) null else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
          }
              .filterNotNull()
              .distinctUntilChanged()
              .drop(1)
              .collect { onSplitsChange(splitsNow()) }
      }
      var rowWidthPx by remember { mutableStateOf(0f) }
      var rightHeightPx by remember { mutableStateOf(0f) }
      // The left section's width and the top right one's height as laid out (a retracted one: its head's), which a
      // drag out of a retracted section starts from.
      var leftShownPx by remember { mutableStateOf(0f) }
      var topRightShownPx by remember { mutableStateOf(0f) }
      // User rule 2026-10-07: each section is resized up to the edges of the window — no least width or height.
      // Anomaly 2026-10-21 (*"the line must be draggable even if its section got minimized"*): a drag of the line beside
      // a retracted section brings the section back at the size the drag gives it, from where the line stands.
      val dragLeftShare = { delta: Float ->
          val room = rowWidthPx - separatorPx
          if (searchCollapsed && room > 0f) {
              leftShare = (leftShownPx / room).coerceIn(0f, 1f)
              searchCollapsed = false
          }
          leftShare = draggedSplit(leftShare, delta, room, minPx = 0f)
      }
      val dragTopRightShare = { delta: Float ->
          val room = rightHeightPx - separatorPx
          if ((actionsCollapsed || addedCollapsed) && room > 0f) {
              // The line stands under the actions as laid out: their head, or all the room the added elements' head leaves.
              topRightShare = (topRightShownPx / room).coerceIn(0f, 1f)
              actionsCollapsed = false
              addedCollapsed = false
          }
          topRightShare = draggedSplit(topRightShare, delta, room, minPx = 0f)
      }
      val tellSplits = { onSplitsChange(splitsNow()) }
      // User rule 2026-10-13: a row's right-click menu opens at the pointer in this window ([LocalRowMenusAtPointer]).
      // User rule 2026-10-18: the right-click this window came from, for every time field of its actions ([RightClickTimeButton]).
      androidx.compose.runtime.CompositionLocalProvider(
          LocalRowMenusAtPointer provides true,
          LocalCalendarClickMillis provides config.calendarClickMillis,
      ) {
      // What is left on screen: the left section, and the right side while one of its two is there.
      val searchShown = !searchRemoved
      val rightShown = !(actionsRemoved && addedRemoved)
      var backgroundMenu by remember { mutableStateOf(false) }
      Box(
          Modifier.fillMaxWidth().weight(1f)
              // A right-click no row, field or control answered (they consume theirs): the window's own menu.
              .then(contextMenuModifier(enabled = true, key = "search-background") { backgroundMenu = true }),
      ) {
      SearchPartsMenu(
          open = backgroundMenu,
          onDismiss = { backgroundMenu = false },
          removed = SearchSplits(headerRemoved = headerRemoved, searchRemoved = searchRemoved, actionsRemoved = actionsRemoved, addedRemoved = addedRemoved, barRemoved = barRemoved),
      ) { part ->
          val next = part.toggled(splitsNow())
          headerRemoved = next.headerRemoved
          barRemoved = next.barRemoved
          searchRemoved = next.searchRemoved
          actionsRemoved = next.actionsRemoved
          addedRemoved = next.addedRemoved
          searchCollapsed = next.searchHidden
          actionsCollapsed = next.actionsHidden
          addedCollapsed = next.addedHidden
      }
      Row(Modifier.fillMaxSize().onSizeChanged { rowWidthPx = it.width.toFloat() }) {
        if (!searchShown) {
            // Taken off: nothing of it, not even its arrow.
        } else if (searchCollapsed && picker == null) {
            // Retracted: a strip as wide as its arrow, the whole height.
            Column(Modifier.fillMaxHeight().onSizeChanged { leftShownPx = it.width.toFloat() }.padding(horizontal = 6.dp, vertical = 10.dp)) {
                SectionArrow(collapsed = true, onToggle = { searchCollapsed = false })
            }
        } else
        Column(
            modifier = Modifier
                // The share is read at layout: a dragged line re-lays the window out, it does not recompose it.
                .then(
                    if (!rightShown) Modifier.fillMaxWidth()
                    else Modifier.sectionShare(horizontal = true, reserved = SECTION_SEPARATOR_THICKNESS) { leftShare },
                )
                .fillMaxHeight()
                // A section squeezed to an edge shows nothing: cut, never spilled over its neighbour.
                .clipToBounds()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (picker == null && !embedded) Row(verticalAlignment = Alignment.CenterVertically) {
                SectionArrow(collapsed = false, onToggle = { searchCollapsed = true })
                // One line, never wrapped: a section narrowed to an edge cuts its title, it does not squeeze it.
                Text(
                    "Search",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.togglesSection { searchCollapsed = true },
                )
            }
            // User rule 2026-10-07: the search header is as compact as the actions section ([CompactFields]: the bar
            // one task cell tall). Since 2026-11-04 it is the bar and the configurations arrow alone, and it takes the
            // section's own width: laid out wider and cut at the edge (`keepsWidthAbove`), the arrow at its right was
            // cut off in a window of usual size — the bar narrows instead, and the arrow stays in view.
            CompactFields {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(COMPACT_ROW_GAP),
            ) {
            // --- The configuration ------------------------------------------------------------------
            // Leaves the bar on a press outside it ([leaveFocusOnOutsidePress]): the completion list is part of it.
            val barEditor = rememberFocusOutsidePressEditor(fieldFocused)
            // User rule 2026-11-04: a picker's bar IS the task cell being edited (its cross included, the same
            // `SearchBarCross`): no second bar here, so the completion list comes first, under the cell's switch.
            if (picker == null && !barOutside && !barRemoved)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // User rule 2026-11-06: the bar's right-click menu is the field's own (match case and whole word, which it
                // held from 2026-11-04, are in the configurations tree alone).
                Box(Modifier.weight(1f)) {
                run {
                OutlinedTextField(
                    value = query,
                    onValueChange = { onConfigChange(SearchDomain.withChanged(config, config.copy(query = it), listOf(SearchDomain.Setting.SearchText), nowMillis())) },
                    singleLine = true,
                    label = { Text("Search") },
                    trailingIcon = { SearchBarCross(query) { onConfigChange(config.copy(query = "")) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .outsidePressPart(barEditor)
                        .revertsOnEscape(query) { onConfigChange(config.copy(query = it)) }
                        // ↓ or Enter leave the bar for the list, onto its first row; every other key types.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val intoList =
                                event.key == Key.DirectionDown || event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (!intoList || count == 0) return@onPreviewKeyEvent false
                            selectRow(0)
                            true
                        },
                )
                }
                }
                // User rule 2026-11-04: the header is the bar alone, and at its right the arrow that opens the tree of
                // every configuration (the types, Reset, the filters, the sorting — what the header's buttons were).
                ConfigurationTreeArrow(state, config, openedConfig, onConfigChange, nowMillis, windows, claudeLogs, onPickOnCalendar)
            }
            val completionOptions =
                remember(config.completion, query, state.titleToTaskIds) { SearchDomain.completionOptions(state, config.completion, query) }
            CompletionList(completionOptions, Modifier.outsidePressPart(barEditor)) { onConfigChange(config.copy(query = it)) }
            }
            }

            // --- The result list --------------------------------------------------------------------
            // Narrowed, the list is cut — laid out at the sections' least width, never squeezed row by row.
            Box(
                Modifier.fillMaxWidth().weight(1f).keepsWidthAbove(COMPACT_SECTION_MIN_WIDTH)
                    .onGloballyPositioned { resultListTop = it.positionInWindow().y },
            ) {
                if (count == 0) {
                    Text(
                        text =
                            when {
                                kinds.isEmpty() -> "Tick a kind to look for."
                                filters.activeCount > 0 -> "Nothing passes the filters."
                                query.isBlank() -> "Nothing of these kinds yet."
                                else -> "Nothing matches."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // The list holds the keyboard once the user leaves the bar: the tree's keys, for its rows.
                    val rowSelected = { index: Int ->
                        index == selected && !fieldFocused && subtreeFocusOwner == null && rowsSelectable && !deselected &&
                            selectionSurface?.startsWith(RESULT_SURFACE) != true
                    }
                    // A row of the multi-selection other than the selected one: the tree's lighter selection grey.
                    val rowInSelection = { index: Int ->
                        index != selected && resultKeys.getOrNull(index)?.let { it in selectedShown } == true
                    }
                    // The pinned parent row ([pinnedItemIndex]): which item, and the sub-tree that pins a deeper one.
                    val bandPx = with(LocalDensity.current) { RESULT_ROW_HEIGHT.toPx() }
                    val pinnedIndex by remember(listState, bandPx) { derivedStateOf { pinnedItemIndex(listState, bandPx) } }
                    val pinScope = rememberCoroutineScope()
                    val resultKindWidth = rememberKindSectionWidth(results)
                    // A task row, for the list and for its pinned copy ([headOnly]): one drawing, so the two cannot
                    // look different. The copy is never in Edit Mode or in the min-time input — one field a session.
                    val taskRow: @Composable (Int, SearchDomain.TaskResult, Boolean) -> Unit = { index, result, headOnly ->
                        val pick = picker?.let { p -> Modifier.pickOnClick { if (p.canPick) p.onPick(result.taskId) } } ?: Modifier
                        Box(pick.outsidePressPart(resultRows)) {
                        SearchTaskRow(
                            state = state,
                            result = result,
                            picking = picker != null,
                            query = query,
                            selected = rowSelected(index),
                            inSelection = rowInSelection(index),
                            editing = !headOnly && editingTaskId == result.taskId,
                            editDraft = editDraft,
                            expanded = result.taskId in expandedTasks,
                            priorities = priorities,
                            taskColor = taskColors[result.taskId],
                            minTimeEditing = !headOnly && minTimeEditTaskId == result.taskId,
                            actions = { path -> taskActions(result, path) },
                            onSelect = { selectRow(index) },
                            onClickRow = { ctrl, shift, keep ->
                                clickRow(index, ctrl, shift, keep)
                                // Selecting the pinned copy brings its real row into view, as the tree's does.
                                if (headOnly) pinScope.launch { listState.animateScrollToItem(index) }
                            },
                            onBeginEdit = { beginEdit(result, result.title) },
                            onDraftChange = { editDraft = it },
                            onEndEdit = { step -> endEdit(result, cancel = false, step = step) },
                            onCancelEdit = { endEdit(result, cancel = true) },
                            onToggleExpand = {
                                expandedTasks =
                                    if (result.taskId in expandedTasks) expandedTasks - result.taskId
                                    else expandedTasks + result.taskId
                            },
                            onActivateMinTime = {
                                selectRow(index)
                                minTimeEditTaskId = result.taskId
                            },
                            onIntent = onIntent,
                            onSetWeightWindow = onSetWeightWindow,
                            onSetRelativeWindow = onSetRelativeWindow,
                            onOpenTaskEdit = openers.onOpenTaskEdit,
                            onOpenCategory = openers.onOpenCategory,
                            onDeepCopyCell = onDeepCopyCell,
                            onGoToTaskTree = { taskId -> onGoToTaskTree(taskId, null) },
                            subtreeFocused = subtreeFocusOwner == result.taskId,
                            onSubtreeFocus = { focused ->
                                if (focused) {
                                    subtreeFocusOwner = result.taskId
                                    selectionSurface = RESULT_SURFACE + result.taskId.value
                                } else if (subtreeFocusOwner == result.taskId) {
                                    subtreeFocusOwner = null
                                }
                            },
                            subtreeShowsSelection = selectionSurface == RESULT_SURFACE + result.taskId.value,
                            headOnly = headOnly,
                            kindWidth = resultKindWidth,
                            onAddTasks = addTasks,
                            clipTop = { resultListTop },
                            scrollListBy = { px -> listState.animateScrollBy(px) },
                            onNestedPin = { on ->
                                if (on) resultNestedPin = result.taskId
                                else if (resultNestedPin == result.taskId) resultNestedPin = null
                            },
                        )
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 12.dp)
                            .focusRequester(listFocus)
                            .onFocusChanged { listFocused = it.hasFocus }
                            .focusable()
                            .recordPressModifiers(pressModifiers)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                // A row's sub-tree reads its own keys (it is the tree's own view).
                                if (subtreeFocusOwner != null) return@onPreviewKeyEvent false
                                val current = results.getOrNull(selected)
                                // In Edit Mode the row's field owns the keys; Escape abandons the rename.
                                if (editingTaskId != null) {
                                    if (event.key == Key.Escape && current is SearchDomain.TaskResult) {
                                        endEdit(current, cancel = true)
                                        return@onPreviewKeyEvent true
                                    }
                                    return@onPreviewKeyEvent false
                                }
                                val ctrl = event.isCtrlPressed || event.isMetaPressed
                                when {
                                    // The rows as drawn, the open elements' children included ([shownRowOrder]).
                                    event.key == Key.DirectionDown || event.key == Key.DirectionUp -> {
                                        if (count == 0) return@onPreviewKeyEvent true
                                        val order = shownRowOrder(state, results, expandedElements, windows, schedulerRuns, claudeLogs)
                                        val at = childSelection.main?.let { main -> order.indexOfFirst { it.child?.rowKey == main } }?.takeIf { it >= 0 }
                                            ?: order.indexOfFirst { it.child == null && it.index == selected }
                                        val next = at + if (event.key == Key.DirectionDown) 1 else -1
                                        val target = order.getOrNull(next)
                                        when {
                                            next < 0 -> runCatching { fieldFocus.requestFocus() }
                                            target == null -> Unit
                                            target.child != null -> childSelection.selectOnly(target.child.rowKey, target.child.key)
                                            else -> select(target.index)
                                        }
                                        true
                                    }
                                    // User rule 2026-11-03: Ctrl+A selects every row, which the menu's "add" then adds.
                                    ctrl && event.key == Key.A && picker == null -> {
                                        if (count > 0) {
                                            deselected = false
                                            multiSelection = resultKeys.toSet()
                                        }
                                        true
                                    }
                                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                        val child = childSelection.main?.let { main ->
                                            shownRowOrder(state, results, expandedElements, windows, schedulerRuns, claudeLogs)
                                                .firstOrNull { it.child?.rowKey == main }?.child
                                        }
                                        if (child != null) openers.open(state, child.row) else openSelected()
                                        true
                                    }
                                    ctrl && event.key == Key.C && current is SearchDomain.TaskResult -> {
                                        taskActions(current, null).onCopyTaskId()
                                        true
                                    }
                                    // PRD §4: typing on a selected task row enters Edit Mode — renaming, always. What
                                    // counts as typing is the tree's own rule ([printableChar]): a bare Shift (held
                                    // for a shift-click) is no text, though desktop reports it as U+FFFF.
                                    current is SearchDomain.TaskResult && picker == null && !ctrl && !event.isAltPressed -> {
                                        val typed = event.printableChar() ?: return@onPreviewKeyEvent false
                                        beginEdit(current, typed)
                                        true
                                    }
                                    else -> false
                                }
                            },
                    ) {
                        itemsIndexed(results, key = { _, r -> resultKey(r) }) { index, result ->
                            // User rule 2026-11-12: with the drop buttons on, each element has one at its right.
                            DropButtonRow(drop = dropOf(result)) {
                            when (result) {
                                is SearchDomain.TaskResult -> taskRow(index, result, false)
                                // A picker's creation row makes the cell's new task at one click, as a task row picks.
                                is SearchDomain.ItemResult -> Column(
                                    Modifier.fillMaxWidth().outsidePressPart(resultRows)
                                        .then(picker?.onNewTask?.let { make -> Modifier.pickOnClick(make) } ?: Modifier),
                                ) {
                                    val elementKey = RESULT_SURFACE + resultKey(result)
                                    // User rule 2026-11-12: a drop-down's option — ticked or picked at a click.
                                    val option = options?.takeIf { result.kind == SearchDomain.Kind.Option }
                                    val selectAll = result.id == SearchDomain.SELECT_ALL_OPTION
                                    ItemResultRow(
                                        kindWidth = resultKindWidth,
                                        item = result,
                                        categories = elementCategories[resultKey(result)].orEmpty(),
                                        expanded = elementKey in expandedElements,
                                        onToggleExpand = { toggleElement(elementKey) },
                                        query = query,
                                        selected = rowSelected(index),
                                        inSelection = rowInSelection(index),
                                        onSelect = {
                                            if (option != null) {
                                                select(index)
                                                actOnOption(result.id)
                                            } else {
                                                clickRow(index, pressModifiers.ctrl, pressModifiers.shift, keepIfSelected = false)
                                            }
                                        },
                                        onSecondarySelect = { clickRow(index, ctrl = false, shift = false, keepIfSelected = true) },
                                        // An option's right-click: that one alone, the list closed — on the release.
                                        onSecondaryRelease = option?.takeIf { !selectAll }?.let { given -> { given.onSolo(result.id) } },
                                        onOpen = { openers.open(state, result) },
                                        // User rule 2026-10-01: a double-click ADDS the element (a task row's enters
                                        // Edit Mode, the tree's rule) — never opens another window on it. A "creation"
                                        // row and a workplace setup are the exceptions: commands, not elements to add.
                                        onDoubleClick = {
                                            if (option != null) Unit
                                            else if (SearchRowOpeners.doubleClickOpens(result.kind)) openers.open(state, result)
                                            else addKeys(listOf(resultKey(result)))
                                        },
                                        checkBox = option?.checked?.takeIf { !selectAll }?.let { result.id in it },
                                        leading = option?.leading?.takeIf { !selectAll }?.let { draw -> { draw(result.id) } },
                                        hasMenu = option == null,
                                        onAdd = { addSelected() },
                                        onAddReplacing = { addSelected(replacing = true) },
                                        timerRun = if (result.kind == SearchDomain.Kind.Timer) {
                                            {
                                                actionHandlers.timerRun(
                                                    setOf(AlarmWindowSubject(result.id, AlarmWindowSubject.Kind.Timer)),
                                                    true,
                                                )
                                            }
                                        } else null,
                                    )
                                    if (elementKey in expandedElements) {
                                        ElementChildren(
                                            resultKey(result), RESULT_SURFACE, 1, setOf(resultKey(result)), state, windows, schedulerRuns, claudeLogs,
                                            expandedElements, toggleElement, openers, onIntent, resultKindWidth, childSelection,
                                        )
                                    }
                                }
                            }
                            }
                        }
                    }
                    // Drawn after the list so it covers the rows scrolled under it; opaque, one row tall.
                    val pinnedTask =
                        pinnedIndex?.let { index ->
                            (results.getOrNull(index) as? SearchDomain.TaskResult)
                                ?.takeIf { it.taskId in expandedTasks && it.taskId != resultNestedPin }
                                ?.let { index to it }
                        }
                    if (pinnedTask != null) {
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .padding(end = 12.dp)
                                .height(RESULT_ROW_HEIGHT)
                                .clipToBounds()
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            taskRow(pinnedTask.first, pinnedTask.second, true)
                        }
                    }
                }
                if (count > 0) ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
            }
        }

        if (searchShown && rightShown) SectionSeparator(vertical = true, onDrag = dragLeftShare, onDragEnd = tellSplits)

        if (rightShown) Column(
            Modifier.weight(1f).fillMaxHeight().clipToBounds().onSizeChanged { rightHeightPx = it.height.toFloat() },
        ) {
            // A retracted section is as tall as its head; the other takes the room, or — both retracted — neither.
            val bothOpen = !actionsCollapsed && !addedCollapsed && !actionsRemoved && !addedRemoved
            // --- The actions on every added element (top right) ------------------------------------
            // User rule 2026-11-12: the "Children" action draws a task's children as an expanded row does — tree cells,
            // with their own keyboard and selection surface ([ACTIONS_SURFACE]).
            val taskChildren: @Composable (TaskId, CellListId) -> Unit = { taskId, listId ->
                SearchSubtree(
                    state = state,
                    listId = listId,
                    readOnly = remember(taskId, state.cells, state.lists, state.tasks) {
                        SchedulerDomain.firstTaskOccurrence(state, taskId) == null
                    },
                    priorities = priorities,
                    focused = actionsSubtreeFocus == taskId,
                    onFocus = { focused ->
                        if (focused) {
                            actionsSubtreeFocus = taskId
                            selectionSurface = ACTIONS_SURFACE + taskId.value
                        } else if (actionsSubtreeFocus == taskId) {
                            actionsSubtreeFocus = null
                        }
                    },
                    showSelection = selectionSurface == ACTIONS_SURFACE + taskId.value,
                    onTakeSelection = { selectionSurface = ACTIONS_SURFACE + taskId.value },
                    onIntent = onIntent,
                    onSetWeightWindow = onSetWeightWindow,
                    onSetRelativeWindow = onSetRelativeWindow,
                    onOpenTaskEdit = openers.onOpenTaskEdit,
                    onOpenCategory = openers.onOpenCategory,
                    onDeepCopyCell = onDeepCopyCell,
                    onGoToTaskTree = { id -> onGoToTaskTree(id, null) },
                    onAddTasks = addTasks,
                )
            }
            if (!actionsRemoved) androidx.compose.runtime.CompositionLocalProvider(
                LocalTaskChildren provides taskChildren,
                // User rule 2026-11-12: the calendar element's lock on the notifications among these rows.
                LocalSearchResults provides results,
            ) {
            AddedActionsSection(
                state = state,
                added = addedRows,
                config = config,
                onConfigChange = onConfigChange,
                // User rule 2026-11-06: the section's bar has the arrow to every search configuration, as the search's has.
                configurationArrow = {
                    ConfigurationTreeArrow(state, config, openedConfig, onConfigChange, nowMillis, windows, claudeLogs, onPickOnCalendar)
                },
                handlers = actionHandlers,
                onIntent = onIntent,
                nowMillis = nowMillis,
                onOpenEach = { elements -> elements.forEach { openers.open(state, it) } },
                // The elements of the group the button is in: all of them, or the ones of a kind.
                onClear = { elements ->
                    val gone = elements.mapTo(HashSet(), SearchDomain::keyOf)
                    onConfigChange(config.copy(added = config.added.filterNot { it in gone }))
                },
                collapsed = actionsCollapsed,
                onToggleCollapsed = { actionsCollapsed = !actionsCollapsed },
                reveal = actionReveal,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            actionsCollapsed && !addedRemoved -> Modifier
                            bothOpen -> Modifier.sectionShare(horizontal = false, reserved = SECTION_SEPARATOR_THICKNESS) { topRightShare }
                            else -> Modifier.weight(1f)
                        },
                    )
                    .onSizeChanged { topRightShownPx = it.height.toFloat() }
                    .clipToBounds(),
                // Inside the section, so a scroll over that gap scrolls it too (user rule 2026-11-12).
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            )
            }
            // One of the two taken off: no line between them.
            if (actionsRemoved || addedRemoved) Unit
            else if (!(actionsCollapsed && addedCollapsed)) SectionSeparator(vertical = false, onDrag = dragTopRightShare, onDragEnd = tellSplits)
            else HorizontalDivider()
            // --- The added elements (bottom right) ---------------------------------------------------
            if (!addedRemoved) AddedElementsList(
                initialScroll = addedScroll,
                onScrolled = { addedScroll = it },
                expandedTasks = expandedAdded,
                onExpandedTasksChange = { expandedAdded = it },
                rows = addedRows,
                state = state,
                priorities = priorities,
                onIntent = onIntent,
                onSetWeightWindow = onSetWeightWindow,
                onSetRelativeWindow = onSetRelativeWindow,
                onOpenTaskEdit = openers.onOpenTaskEdit,
                onOpenCategory = openers.onOpenCategory,
                onDeepCopyCell = onDeepCopyCell,
                onGoToTaskTree = { taskId -> onGoToTaskTree(taskId, null) },
                onAddTasks = addTasks,
                taskColors = taskColors,
                selectionSurface = selectionSurface,
                onSelectionSurface = { selectionSurface = it },
                onOpen = { openers.open(state, it) },
                elementCategories = elementCategories,
                expandedElements = expandedElements,
                onToggleElement = toggleElement,
                elementChildren = { key, width ->
                    ElementChildren(
                        key, ADDED_SURFACE, 1, setOf(key), state, windows, schedulerRuns, claudeLogs,
                        expandedElements, toggleElement, openers, onIntent, width, childSelection,
                    )
                },
                onRemove = { key -> onConfigChange(config.copy(added = config.added - key)) },
                onKeepOnly = { keys -> onConfigChange(config.copy(added = SearchDomain.keepingOnly(config.added, keys))) },
                onRemoveAll = { keys -> onConfigChange(config.copy(added = SearchDomain.removing(config.added, keys))) },
                // User rule 2026-11-02: only the groups of actions that can apply to this element open, as for a new one.
                onShowOnlyActions = { row ->
                    SearchDomain.withOnlyGroupsOf(config, addedRows, row)?.let { (next, groupId) ->
                        onConfigChange(next)
                        actionsCollapsed = false
                        actionReveal = groupId to ((actionReveal?.second ?: 0) + 1)
                    }
                },
                collapsed = addedCollapsed,
                onToggleCollapsed = { addedCollapsed = !addedCollapsed },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (addedCollapsed && !actionsRemoved) Modifier else Modifier.weight(1f))
                    .clipToBounds()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
      }
      // Where the two lines cross: drawn over both strips, it drags the three sections at once. Placed only once
      // the row has been measured, since the crossing is read off the two splits and the room they share.
      if (rowWidthPx > 0f && rightHeightPx > 0f && !searchCollapsed && !actionsCollapsed && !addedCollapsed &&
          !searchRemoved && !actionsRemoved && !addedRemoved
      ) {
          val jointHalfPx = with(density) { SECTION_JOINT_SIZE.toPx() } / 2f
          SectionJoint(
              onDrag = { delta ->
                  dragLeftShare(delta.x)
                  dragTopRightShare(delta.y)
              },
              onDragEnd = tellSplits,
              modifier = Modifier.offset {
                  IntOffset(
                      (leftShare * (rowWidthPx - separatorPx) + separatorPx / 2f - jointHalfPx).roundToInt(),
                      (topRightShare * (rightHeightPx - separatorPx) + separatorPx / 2f - jointHalfPx).roundToInt(),
                  )
              },
          )
      }
      }
      }
    }
    // User rule 2026-11-04: with the bar's match case or whole word on, only what matches with them is highlighted.
    androidx.compose.runtime.CompositionLocalProvider(LocalSearchMatch provides filters.queryOptions) {
    if (frame == null) {
        Column(modifier) {
            // User rule 2026-11-11: shown by its background menu, the head of a Search window drawn in no frame.
            if (!headerRemoved) SearchPopupHead(SearchDomain.windowTitle(config), onClose = picker?.onCancel ?: onDismiss)
            windowContent()
        }
    } else {
        AppWindowFrame(
            title = SearchDomain.windowTitle(config),
            // The notifications window is a default window of its own: its colours are not the Search windows'.
            colorKind = org.example.project.scheduler.domain.WindowColorSpace.UNFOCUSED_NOTIF.takeIf { SearchDomain.isNotificationsWindow(config) },
            state = frame,
            onClose = onDismiss,
            defaultWidth = 1040.dp,
            defaultHeight = 600.dp,
            modifier = modifier,
            onRaise = onRaise,
            onGeometryChange = onGeometryChange,
            claimsKeyboard = true,
            showHead = !headerRemoved,
            content = windowContent,
        )
    }
    }
}

/**
 * User rule 2026-11-12: **what a drop-down gives the Search window that is its list** ([OptionSearchMenu]): its [options]
 * (id and name, in order), which are ticked ([checked]; null: a one-choice list, no check boxes), what a click on one does
 * ([onClick]), "Select all" over those listed ([onSelectAll]), a right-click ([onSolo]: that one alone, the list closed),
 * a mark drawn before a name ([leading]) and, for a list with no end, [onNearEnd].
 */
class SearchOptions(
    val options: List<Pair<String, String>>,
    val checked: Set<String>?,
    val onClick: (String) -> Unit,
    val onSelectAll: (List<String>) -> Unit = {},
    val onSolo: (String) -> Unit = onClick,
    val leading: (@Composable (String) -> Unit)? = null,
    val onNearEnd: (() -> Unit)? = null,
)

/** What a drop-down's Search window opens on nothing: it opens no element ([OptionSearchMenu]). */
private val OPTION_OPENERS =
    SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {})

private val OPTION_HANDLERS =
    AddedActionHandlers(
        onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {}, onOpenKindSearch = {},
        timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {}, onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
    )

/** The widest and tallest a drop-down's Search window is; it is as tall as its rows below that. */
private val OPTION_MENU_WIDTH = 320.dp
private val OPTION_MENU_MAX_HEIGHT = 420.dp
private val OPTION_MENU_BAR_HEIGHT = 56.dp

/**
 * User rule 2026-11-12: *"All the drop-down menus of the app must be Search windows as well."* **The list a drop-down opens
 * is a Search window** — the very [SearchWindow], its search section alone ([EMBEDDED_SEARCH_SPLITS]: no head, no actions,
 * no added elements), its rows the drop-down's [options] ([SearchDomain.optionResults]), the bar finding among them, its
 * background menu showing its other parts. In the drop-down's own pop-up, focusable so the bar takes typing, as the
 * configurations tree's are; a fixed size, which the menu measures without asking the list.
 */
@Composable
internal fun OptionSearchMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    options: SearchOptions,
    modifier: Modifier = Modifier,
    width: Dp = OPTION_MENU_WIDTH,
) {
    transientMenuDismissal(open, onDismiss)
    AppDropdownMenu(expanded = open, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true), modifier = modifier) {
        var config by remember { mutableStateOf(SearchDomain.OPTION_SEARCH) }
        var splits by remember { mutableStateOf(EMBEDDED_SEARCH_SPLITS) }
        // No account is searched: the options are the rows.
        val nothing = remember { SchedulerState.empty() }
        val rows = options.options.size + if (options.checked != null) 1 else 0
        val height = (OPTION_MENU_BAR_HEIGHT + RESULT_ROW_HEIGHT * rows.coerceAtLeast(1)).coerceAtMost(OPTION_MENU_MAX_HEIGHT)
        SearchWindow(
            state = nothing,
            openers = OPTION_OPENERS,
            onStartTaskNow = {},
            onGoToTaskTree = { _, _ -> },
            onDeepCopyCell = {},
            actionHandlers = OPTION_HANDLERS,
            onIntent = {},
            onDismiss = onDismiss,
            config = config,
            onConfigChange = { config = it },
            onOpenConfigurations = {},
            splits = splits,
            onSplitsChange = { splits = it },
            embedded = true,
            options = options,
            modifier = Modifier.width(width).height(height),
        )
    }
}

/**
 * User rule 2026-11-12: **what a Search window drawn inside another asks of `App`** ([LocalEmbeddedSearch]): its
 * configuration, held by the caller, and what its drop buttons do.
 */
class EmbeddedSearchRequest(
    val config: SearchDomain.Config,
    val onConfigChange: (SearchDomain.Config) -> Unit,
    val onDrop: ((SearchDomain.Result) -> Unit)?,
    val canDrop: (SearchDomain.Result) -> Boolean = { true },
    /** Rows picked at a click, as a cell's Edit Mode picks ([SearchPicker]); null: rows as a Search window has them. */
    val picker: SearchPicker? = null,
    /** Its bar is a field outside it ([PopupSearchField]). */
    val barOutside: Boolean = false,
)

/**
 * User rule 2026-11-12: **a Search window inside the actions section** (the "List of parents"), drawn by `App` with the
 * handlers of the window it stands in ([SearchWindow]'s `embedded`), its search section alone ([EMBEDDED_SEARCH_SPLITS]).
 * Null where no `App` provides one (a test): nothing is drawn.
 */
val LocalEmbeddedSearch = androidx.compose.runtime.compositionLocalOf<(@Composable (EmbeddedSearchRequest, Modifier) -> Unit)?> { null }

/** An embedded Search window's parts: no head, no actions, no added elements — the search section alone. */
val EMBEDDED_SEARCH_SPLITS: SearchSplits = SearchSplits(headerRemoved = true, actionsRemoved = true, addedRemoved = true)

/**
 * User rule 2026-11-12: **a row of a Search window's list with its drop button** at its right ([drop]; none drawn while it
 * is null), the app's framed "✕" — the row keeps the rest of the width.
 */
@Composable
internal fun DropButtonRow(drop: (() -> Unit)?, content: @Composable () -> Unit) {
    if (drop == null) {
        content()
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { content() }
        FrameButton("✕", onClick = drop)
    }
}

/**
 * User rule 2026-10-04: **the little arrow that retracts a section of the Search window to its head and expands it
 * again** — the tree's own expansion arrow ([TaskSheetExpandArrow]: ▾ open, ▸ retracted), so it reads as the one the
 * user already knows.
 */
@Composable
internal fun SectionArrow(collapsed: Boolean, onToggle: () -> Unit) {
    TaskSheetExpandArrow(hasChildren = true, expanded = !collapsed, onToggle = onToggle, color = MaterialTheme.colorScheme.primary)
}

/**
 * User rule 2026-11-11: **the background menu of every Search window** — the app's, the cell's Edit Mode picker, the
 * configurations pop-up: one entry for the header and one per section ([SearchPart]), each "Hide …" or "Show …" by
 * [removed]. [onToggle] applies [SearchPart.toggled]. At the pointer; a part of the pop-up it is opened in, if any
 * ([LocalMenuHover]), so going to it does not close that pop-up.
 */
@Composable
internal fun SearchPartsMenu(open: Boolean, onDismiss: () -> Unit, removed: SearchSplits, onToggle: (SearchPart) -> Unit) {
    transientMenuDismissal(open, onDismiss)
    AppDropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
        atPointer = true,
    ) {
        Column(Modifier.reportsHover(LocalMenuHover.current)) {
            SearchPart.entries.forEach { part ->
                DropdownMenuItem(
                    text = { Text(part.menuLabel(removed = part.isRemoved(removed))) },
                    onClick = {
                        onDismiss()
                        onToggle(part)
                    },
                )
            }
        }
    }
}

/**
 * User rule 2026-11-11: **the head of a Search window that is no window of its own** — the cell's Edit Mode picker, the
 * configurations pop-up — once its background menu shows it ([SearchPart.Header]): the window's title and its ✕, on the
 * windows' head colour. A pop-up has nothing to move, reduce or keep, so the frame's other buttons are not there.
 */
@Composable
internal fun SearchPopupHead(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            "✕",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClose).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * User rule 2026-11-11: **what the Search window's background menu takes off the window and puts back** — the
 * window's header (its head: the title and the buttons to keep, reduce, resize and close it) and each of the three
 * sections. Taken off, nothing of it is drawn, not even its arrow; put back, a section is EXPANDED, never back as its
 * arrow alone.
 */
internal enum class SearchPart(private val label: String) {
    Header("the header"),
    /** User rule 2026-11-12: the search bar with its down arrow ([SearchSplits.barRemoved]). */
    Bar("the search bar"),
    Search("the search"),
    Actions("the actions"),
    Added("the added elements");

    fun isRemoved(splits: SearchSplits): Boolean =
        when (this) {
            Header -> splits.headerRemoved
            Bar -> splits.barRemoved
            Search -> splits.searchRemoved
            Actions -> splits.actionsRemoved
            Added -> splits.addedRemoved
        }

    /** "Hide …" while it is there, "Show …" once taken off. */
    fun menuLabel(removed: Boolean): String = (if (removed) "Show " else "Hide ") + label

    /** [splits] with this part taken off, or put back expanded. */
    fun toggled(splits: SearchSplits): SearchSplits {
        val show = isRemoved(splits)
        return when (this) {
            Header -> splits.copy(headerRemoved = !show)
            Bar -> splits.copy(barRemoved = !show)
            Search -> if (show) splits.copy(searchRemoved = false, searchHidden = false) else splits.copy(searchRemoved = true)
            Actions -> if (show) splits.copy(actionsRemoved = false, actionsHidden = false) else splits.copy(actionsRemoved = true)
            Added -> if (show) splits.copy(addedRemoved = false, addedHidden = false) else splits.copy(addedRemoved = true)
        }
    }
}

/**
 * User rule 2026-10-13: **a press on the TITLE of a section or of a group is a press on its arrow** ([SectionArrow]) —
 * *"Clicking in the title of a macro group in the action section must be the same as clicking its expansion arrow
 * button. Same thing for the title of the sections of the Search window and the calendar window."* Put on the title
 * beside every [SectionArrow], with the arrow's own handler: one answer to the press, never a second one.
 */
internal fun Modifier.togglesSection(onToggle: () -> Unit): Modifier = clickable(onClick = onToggle)

/** The window's `selectionSurface`: a sub-tree of the result list's row, or of the added elements' — then the task's id. */
private const val RESULT_SURFACE: String = "result/"
private const val ADDED_SURFACE: String = "added/"
/** The "Children" action's sub-trees, by task ([SearchDomain.AddedAction.TaskChildren]). */
private const val ACTIONS_SURFACE: String = "actions/"

/** The added elements' row menu: every other element off the list (never off the account). */
private const val REMOVE_OTHERS_LABEL: String = "remove the others"

/**
 * The added elements' row menu (user rule 2026-11-02): every group of the actions section retracted but those holding this
 * element, the section starting at the first of them ([SearchDomain.withOnlyGroupsOf]).
 */
private const val SHOW_ONLY_ACTIONS_LABEL: String = "show only its actions"

/**
 * The added elements, in the order they were added: each row its kind, its name and its detail (a task's
 * shortest path), a double-click opens it as the result list does ([SearchRowOpeners]), and its ✕ takes it off
 * the list — never off the account.
 */
@Composable
private fun AddedElementsList(
    /** Where the list is scrolled when it is first drawn, and its scroll once it comes to rest ([SearchSplits.scrolls]). */
    initialScroll: Pair<Int, Int>,
    onScrolled: (Pair<Int, Int>) -> Unit,
    rows: List<SearchDomain.Result>,
    /** What an expanded task element's sub-tree needs — the result list's own ([SearchSubtree]). */
    state: SchedulerState,
    priorities: Map<TaskId, Double>,
    onIntent: (SchedulerIntent) -> Unit,
    onSetWeightWindow: (CellListId?) -> Unit,
    onSetRelativeWindow: (CellId?) -> Unit,
    onOpenTaskEdit: (TaskId) -> Unit,
    onOpenCategory: (CategoryId) -> Unit,
    onDeepCopyCell: (CellId) -> Unit,
    onGoToTaskTree: (TaskId) -> Unit,
    onAddTasks: (taskIds: List<TaskId>, replacing: Boolean) -> Unit,
    /** The tasks' colours, the tree's own: the arrow of a task element sits on its task's, as a tree row's does. */
    taskColors: Map<TaskId, Color>,
    /** The surface showing a selection of cells (the window's), and a press here taking it or giving it back. */
    selectionSurface: String?,
    onSelectionSurface: (String?) -> Unit,
    onOpen: (SearchDomain.Result) -> Unit,
    /** The categories a row of another kind than a task carries, the rows open, a row's arrow, and what shows under one ([ElementChildren]). */
    elementCategories: Map<String, List<String>>,
    expandedElements: Set<String>,
    onToggleElement: (String) -> Unit,
    elementChildren: @Composable (key: String, kindWidth: Dp) -> Unit,
    onRemove: (String) -> Unit,
    /** The row menu's "remove the others": the list holds the selected elements alone. */
    onKeepOnly: (Set<String>) -> Unit,
    /** The row menu's "remove": the selected elements leave the list. */
    onRemoveAll: (Set<String>) -> Unit,
    /** The row menu's "show only its actions": the actions section shows the row's groups alone ([SHOW_ONLY_ACTIONS_LABEL]). */
    onShowOnlyActions: (SearchDomain.Result) -> Unit,
    /** The task elements whose sub-tree is open ([SearchSplits.expandedAdded]). */
    expandedTasks: Set<TaskId>,
    onExpandedTasksChange: (Set<TaskId>) -> Unit,
    /** Whether the section is retracted to its head, and its arrow's press ([SectionArrow], user rule 2026-10-04). */
    collapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // User rule 2026-10-03: the rows are selected the way the result list's are ([ClickSelection]: a click selects one
    // row, Ctrl+click adds or takes one, Shift+click the range from the anchor), and a right-click selects its row
    // alone unless it is already selected — the menu acts on the selection. Compose-only, like the result list's
    // multi-selection: a way of picking some, not a fact about them.
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var selectionAnchor by remember { mutableStateOf<String?>(null) }
    var selectionMain by remember { mutableStateOf<String?>(null) }
    val pressModifiers = remember { PressModifiers() }
    val order = rows.map(::resultKey)
    val currentOrder by rememberUpdatedState(order)
    // A row that left the list is not selected any more.
    val selected = selection.filterTo(LinkedHashSet()) { it in order }
    // Anomaly 2026-11-03: a press anywhere but on a row (its menu included) leaves none selected, as in the result list.
    val selectedRows = rememberOutsidePressEditor(active = selected.isNotEmpty()) {
        selection = emptySet()
        selectionMain = null
    }
    val currentSelected by rememberUpdatedState(selected)
    // User rule 2026-10-07: a task element has its expansion arrow here too, and shows its sub-tree as a result row
    // does ([SearchSubtree]) — with the list's pinned parent row ([pinnedItemIndex]).
    var subtreeFocusOwner by remember { mutableStateOf<TaskId?>(null) }
    var listTop by remember { mutableStateOf<Float?>(null) }
    var nestedPin by remember { mutableStateOf<TaskId?>(null) }
    val kindWidth = rememberKindSectionWidth(rows)
    // A sub-tree of this list shows the cells' selection: the rows' own is not drawn beside it.
    val rowsShowSelection = selectionSurface?.startsWith(ADDED_SURFACE) != true
    fun rowTakesSelection() {
        if (!rowsShowSelection) onSelectionSurface(null)
    }
    fun childListOf(row: SearchDomain.Result): CellListId? =
        (row as? SearchDomain.TaskResult)?.let { state.tasks[it.taskId]?.childListId }
            ?.takeIf { state.lists[it]?.cellIds?.isNotEmpty() == true }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionArrow(collapsed, onToggleCollapsed)
            Text(
                text = "Added elements" + if (rows.isEmpty()) "" else "  ·  ${rows.size}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.togglesSection(onToggleCollapsed),
            )
        }
        if (collapsed) return@Column
        if (rows.isEmpty()) {
            Text(
                text = "Right-click selected rows of the results and choose add (Ctrl+A selects them all).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val listState = rememberLazyListState(initialScroll.first, initialScroll.second)
        val currentOnScrolled by rememberUpdatedState(onScrolled)
        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow {
                if (listState.isScrollInProgress) null else listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
                .filterNotNull()
                .distinctUntilChanged()
                .drop(1)
                .collect { currentOnScrolled(it) }
        }
        val bandPx = with(LocalDensity.current) { RESULT_ROW_HEIGHT.toPx() }
        val pinnedIndex by remember(listState, bandPx) { derivedStateOf { pinnedItemIndex(listState, bandPx) } }
        val pinScope = rememberCoroutineScope()
        Box(
            Modifier.fillMaxWidth().weight(1f).keepsWidthAbove(COMPACT_SECTION_MIN_WIDTH)
                .onGloballyPositioned { listTop = it.positionInWindow().y },
        ) {
            // An element's row, for the list and for its pinned copy ([pinnedAt]: the item it is the copy of — a
            // press on the copy scrolls the list up to its real row, the tree's rule): one drawing.
            val head: @Composable (SearchDomain.Result, Int?) -> Unit = { row, pinnedAt ->
                    val key = resultKey(row)
                    // The right-click menu, on the selection: "remove", and "remove the others" (user rule
                    // 2026-10-02) where there are others.
                    var menuOpen by remember(key) { mutableStateOf(false) }
                    val currentOnOpen by rememberUpdatedState(onOpen)
                    val currentRow by rememberUpdatedState(row)
                    val workplaceRuns = LocalWorkplaceRuns.current
                    Box(Modifier.outsidePressPart(selectedRows)) {
                    Row(
                        modifier = resultRowModifier(
                            selected = rowsShowSelection && key in selected && key == selectionMain,
                            inSelection = rowsShowSelection && key in selected && key != selectionMain,
                        )
                            .resultRowGestures(
                                key = key,
                                onSelect = {
                                    val next =
                                        ClickSelection.click(
                                            currentOrder, currentSelected, selectionAnchor, key,
                                            shift = pressModifiers.shift, ctrl = pressModifiers.ctrl,
                                        )
                                    selection = next.selected
                                    selectionAnchor = next.anchor
                                    selectionMain = key
                                    rowTakesSelection()
                                    if (pinnedAt != null) pinScope.launch { listState.animateScrollToItem(pinnedAt) }
                                },
                                onSecondarySelect = {
                                    if (key !in currentSelected) {
                                        selection = setOf(key)
                                        selectionAnchor = key
                                    }
                                    selectionMain = key
                                    rowTakesSelection()
                                },
                                onOpen = { currentOnOpen(currentRow) },
                                onOpenMenu = { menuOpen = true },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        KindSection(row.kind, kindWidth)
                        if (row is SearchDomain.TaskResult) {
                            val taskId = row.taskId
                            // On the task's colour, as every tree row's arrow is (anomaly 2026-10-07: it had none here).
                            val taskColor = taskColors[taskId]
                            TaskSheetExpandArrow(
                                hasChildren = childListOf(row) != null,
                                expanded = taskId in expandedTasks,
                                onToggle = {
                                    onExpandedTasksChange(if (taskId in expandedTasks) expandedTasks - taskId else expandedTasks + taskId)
                                },
                                color = taskColor?.let(TaskPalette::foreground) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                background = taskColor,
                            )
                        } else if (row.kind != SearchDomain.Kind.Creation) {
                            SectionArrow(ADDED_SURFACE + key !in expandedElements) { onToggleElement(ADDED_SURFACE + key) }
                        }
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = logEntryColor(row.kind) ?: Color.Unspecified,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = when (row) {
                                is SearchDomain.TaskResult ->
                                    if (row.shownPath.isEmpty()) "no path" else SearchDomain.pathLabel(row.shownPath)
                                is SearchDomain.ItemResult ->
                                    withCategories(SearchDomain.shownDetail(row) { workplaceRuns?.isRunning(it) == true }, elementCategories[key].orEmpty())
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = logEntryColor(row.kind) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "✕",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onRemove(key) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    transientMenuDismissal(menuOpen) { menuOpen = false }
                    AppDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        properties = PopupProperties(focusable = false),
                        atPointer = LocalRowMenusAtPointer.current,
                    ) {
                        val picked = selected.ifEmpty { setOf(key) }
                        MenuEntry(if (picked.size == 1) "remove" else "remove ${picked.size}") {
                            menuOpen = false
                            onRemoveAll(picked)
                        }
                        // Nothing to offer once the selection is all the list holds.
                        if (rows.size > picked.size) {
                            MenuEntry(REMOVE_OTHERS_LABEL) {
                                menuOpen = false
                                onKeepOnly(picked)
                            }
                        }
                        // About the row under the pointer alone, whatever is selected: a group is ONE element's.
                        MenuEntry(SHOW_ONLY_ACTIONS_LABEL) {
                            menuOpen = false
                            onShowOnlyActions(currentRow)
                        }
                    }
                    }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp).recordPressModifiers(pressModifiers),
            ) {
                itemsIndexed(rows, key = { _, r -> resultKey(r) }) { _, row ->
                    Column(Modifier.fillMaxWidth()) {
                        head(row, null)
                        val childListId = childListOf(row)
                        if (row is SearchDomain.TaskResult && childListId != null && row.taskId in expandedTasks) {
                            val taskId = row.taskId
                            SearchSubtree(
                                state = state,
                                listId = childListId,
                                // A task cut from the tree: looked through, never modified.
                                readOnly = remember(taskId, state.cells, state.lists, state.tasks) {
                                    SchedulerDomain.firstTaskOccurrence(state, taskId) == null
                                },
                                priorities = priorities,
                                focused = subtreeFocusOwner == taskId,
                                onFocus = { focused ->
                                    if (focused) {
                                        subtreeFocusOwner = taskId
                                        onSelectionSurface(ADDED_SURFACE + taskId.value)
                                    } else if (subtreeFocusOwner == taskId) {
                                        subtreeFocusOwner = null
                                    }
                                },
                                showSelection = selectionSurface == ADDED_SURFACE + taskId.value,
                                onTakeSelection = { onSelectionSurface(ADDED_SURFACE + taskId.value) },
                                onIntent = onIntent,
                                onSetWeightWindow = onSetWeightWindow,
                                onSetRelativeWindow = onSetRelativeWindow,
                                onOpenTaskEdit = onOpenTaskEdit,
                                onOpenCategory = onOpenCategory,
                                onDeepCopyCell = onDeepCopyCell,
                                onGoToTaskTree = onGoToTaskTree,
                                onAddTasks = onAddTasks,
                                clipTop = { listTop },
                                scrollListBy = { px -> listState.animateScrollBy(px) },
                                onNestedPin = { on -> if (on) nestedPin = taskId else if (nestedPin == taskId) nestedPin = null },
                            )
                        }
                        if (row is SearchDomain.ItemResult && ADDED_SURFACE + resultKey(row) in expandedElements) {
                            elementChildren(resultKey(row), kindWidth)
                        }
                    }
                }
            }
            // The pinned parent row: the element whose sub-tree shows under the top band, once its row left the top.
            val pinnedAt = pinnedIndex
            val pinnedRow =
                pinnedAt?.let(rows::getOrNull)
                    ?.takeIf { it is SearchDomain.TaskResult && it.taskId in expandedTasks && it.taskId != nestedPin }
            if (pinnedRow != null) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(end = 12.dp)
                        .height(RESULT_ROW_HEIGHT)
                        .clipToBounds()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    head(pinnedRow, pinnedAt)
                }
            }
            ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/**
 * A row's leftmost section: which kind of thing it is. One width for the whole list, so every row's name starts
 * alike — [width], the widest label among the kinds the list HOLDS ([rememberKindSectionWidth]).
 */
@Composable
private fun KindSection(kind: SearchDomain.Kind, width: Dp = KIND_SECTION_WIDTH) {
    Text(
        text = kind.label,
        style = MaterialTheme.typography.labelSmall,
        color = logEntryColor(kind) ?: onTaskCell(MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.width(width),
    )
}

/**
 * User rule 2026-10-07: **the kind section is as wide as the widest kind LISTED**, not as the widest there is — a list
 * of tasks alone wrote "task" and left a hundred pixels blank before the arrow, beside a path squeezed for lack of
 * room. Measured on the labels, when the kinds listed change; never above the old fixed width.
 */
@Composable
private fun rememberKindSectionWidth(rows: List<SearchDomain.Result>): Dp {
    val kinds = rows.mapTo(LinkedHashSet()) { it.kind }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    return remember(kinds, style, density) {
        val widest = kinds.maxOfOrNull { measurer.measure(it.label, style).size.width } ?: 0
        (with(density) { widest.toDp() } + 8.dp).coerceAtMost(KIND_SECTION_WIDTH)
    }
}

/**
 * The configuration section's **Reset**. One button for the Search window (back to the configuration it opened
 * with, else the default one) and the configuration windows (their own search field and types cleared); greyed
 * while there is nothing to reset.
 */
@Composable
internal fun ResetButton(enabled: Boolean, onReset: () -> Unit) = OutlinedTextButton("Reset", enabled, onReset)

/** A word in a thin outline — [ResetButton]'s face, grey and inert while not [enabled]. */
@Composable
internal fun OutlinedTextButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = fieldFaceVerticalPadding()),
    )
}

/**
 * The kind drop-down of a search's configuration — a check box per kind; ticking one leaves the menu open, so
 * several can be ticked in turn. One drop-down for the Search window and the Configuration Search window.
 */
@Composable
internal fun KindsDropDown(
    kinds: Set<SearchDomain.Kind>,
    onKindsChange: (Set<SearchDomain.Kind>) -> Unit,
    modifier: Modifier = Modifier.width(170.dp),
    /** Open the list now, once — see [CheckBoxDropDown]'s `deploy`. */
    deploy: Boolean = false,
    onDeployed: () -> Unit = {},
    /** The kinds listed; the checks of the others stay as they are. */
    options: List<SearchDomain.Kind> = SearchDomain.Kind.entries,
) {
    CheckBoxDropDown(
        options = options,
        checked = options.filterTo(HashSet()) { it in kinds },
        face = kindsLabel(kinds, options),
        label = { it.label },
        onChange = { picked -> onKindsChange((kinds - options.toSet()) + picked) },
        modifier = modifier,
        deploy = deploy,
        onDeployed = onDeployed,
    )
}

/**
 * **The check-box drop-down**: a field whose face is [face] and which opens a list of [options], each with a check box
 * (headed by [SelectAllMenuItem]); ticking one leaves the menu open, so several can be ticked in turn, and Shift ticks a
 * range; a right-click on an option checks it alone and closes the list. The one drop-down of its kind — the search
 * kinds and the period edit window's period selector fields.
 */
@Composable
internal fun <T> CheckBoxDropDown(
    options: List<T>,
    checked: Set<T>,
    face: String,
    label: (T) -> String,
    onChange: (Set<T>) -> Unit,
    modifier: Modifier = Modifier.width(170.dp),
    /**
     * A request to open the list without a click — a one-shot: it opens once [deploy] turns true and [onDeployed]
     * takes the request back, so the list closes like any other afterwards and never reopens on recomposition.
     */
    deploy: Boolean = false,
    onDeployed: () -> Unit = {},
    /**
     * User rule 2026-10-11, for a list with no end (the occurrences of a pattern that repeats for ever): called as the
     * list is scrolled to its bottom, so the caller lists more — *"they must not all show in the drop-down list, but
     * more must show as the user scrolls down in it"*. Null: the list is whole.
     */
    onNearEnd: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val range = rememberCheckRange<T>()
    val latestOnDeployed by rememberUpdatedState(onDeployed)
    LaunchedEffect(deploy) {
        if (!deploy) return@LaunchedEffect
        // One frame later: the field is laid out, so the list hangs from it rather than from the window's corner.
        withFrameNanos { }
        open = true
        latestOnDeployed()
    }
    Box(modifier) {
        Text(
            text = "$face  ▾",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .menuToggleClickable(open) { open = it }
                .padding(horizontal = 10.dp, vertical = fieldFaceVerticalPadding()),
        )
        // User rule 2026-11-12: the list is a Search window ([OptionSearchMenu]); the options by their place in [options].
        val latestOnChange by rememberUpdatedState(onChange)
        val latestChecked by rememberUpdatedState(checked)
        val latestOptions by rememberUpdatedState(options)
        fun optionAt(id: String): T? = id.toIntOrNull()?.let { latestOptions.getOrNull(it) }
        OptionSearchMenu(
            open = open,
            onDismiss = { open = false },
            modifier = Modifier.checkRangeShift(range),
            options = SearchOptions(
                options = options.mapIndexed { i, option -> i.toString() to label(option) },
                checked = options.indices.filterTo(HashSet()) { options[it] in checked }.mapTo(HashSet()) { it.toString() },
                // Ticking one leaves the list open, so several are ticked in turn; Shift ticks a range.
                onClick = { id -> optionAt(id)?.let { latestOnChange(range.toggle(latestOptions, latestChecked, it)) } },
                onSelectAll = { ids ->
                    val listed = ids.mapNotNull(::optionAt)
                    val current = latestChecked
                    latestOnChange(if (current.containsAll(listed)) current - listed.toSet() else current + listed)
                },
                // A right-click picks this option ALONE and closes the list — the one-choice shortcut of a list that
                // otherwise stays open to be ticked in turn.
                onSolo = { id ->
                    optionAt(id)?.let { latestOnChange(setOf(it)) }
                    open = false
                },
                onNearEnd = onNearEnd,
            ),
        )
    }
}

/** The tallest a drop-down's list is before it scrolls — and shows its scroll bar ([DropDownScrollbar]). */
private val DROP_DOWN_LIST_MAX_HEIGHT = 360.dp
private val DROP_DOWN_SCROLLBAR_WIDTH = 10.dp

/**
 * User rule 2026-10-11: **the vertical scroll bar of a drop-down's list**, at its right edge while the list is long
 * enough to scroll ([state]'s `maxValue`), gone while everything fits. The thumb is as tall as the part shown is of
 * the whole and stands where the list is scrolled to; dragged anywhere along the bar, it scrolls the list.
 *
 * Its own drawing rather than [ColumnScrollbar]: a menu measures its entries' INTRINSIC width, and that one is a
 * `BoxWithConstraints`, which cannot be asked for it.
 */
@Composable
private fun DropDownScrollbar(state: androidx.compose.foundation.ScrollState, modifier: Modifier = Modifier) {
    if (state.maxValue <= 0) return
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Box(
        modifier
            .width(DROP_DOWN_SCROLLBAR_WIDTH)
            .fillMaxHeight()
            .pointerInput(state) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val track = size.height.toFloat()
                    // One pixel along the bar is this many pixels of the list.
                    if (track > 0f) state.dispatchRawDelta(dragAmount * (track + state.maxValue) / track)
                }
            }
            .drawBehind {
                val track = size.height
                val whole = track + state.maxValue
                val thumb = (track * track / whole).coerceAtLeast(minOf(24.dp.toPx(), track))
                val top = (track - thumb) * state.value / state.maxValue
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                    size = androidx.compose.ui.geometry.Size(size.width, thumb),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                )
            },
    )
}

/** How close to its bottom a list is "at its end" ([CheckBoxDropDown]'s `onNearEnd`): about two rows. */
private const val NEAR_END_PX: Int = 96

/**
 * [CheckBoxDropDown]'s one-choice sibling: the same field (its face [selected]'s [label], drawn as that one is), whose
 * list of [options] closes on the one picked. [leading] draws something before an option's name, on the face too (the
 * period edit window's drawing swatches).
 */
@Composable
internal fun <T> ChoiceDropDown(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier.width(170.dp),
    placeholder: String = "choose…",
    enabled: Boolean = true,
    leading: (@Composable (T) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val usable = enabled && options.isNotEmpty()
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .then(if (usable) Modifier.menuToggleClickable(open) { open = it } else Modifier)
                .padding(horizontal = 10.dp, vertical = fieldFaceVerticalPadding()),
        ) {
            if (selected != null && leading != null) leading(selected)
            Text(
                text = (selected?.let(label) ?: placeholder) + "  ▾",
                style = MaterialTheme.typography.bodyMedium,
                color = if (usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // User rule 2026-11-12: the list is a Search window ([OptionSearchMenu]), closing on the one picked.
        OptionSearchMenu(
            open = open,
            onDismiss = { open = false },
            options = SearchOptions(
                options = options.mapIndexed { i, option -> i.toString() to label(option) },
                checked = null,
                onClick = { id ->
                    open = false
                    id.toIntOrNull()?.let(options::getOrNull)?.let(onSelect)
                },
                leading = leading?.let { draw -> { id -> id.toIntOrNull()?.let(options::getOrNull)?.let { draw(it) } } },
            ),
        )
    }
}

/**
 * The first entry of every drop-down whose entries carry a check box: no box of its own, it reads **Select all**
 * until every box is checked and checks them all, then **Deselect all**, which unchecks them all. Driven by the
 * boxes rather than by its own last press, so it says the right thing whatever was ticked by hand in between.
 * The menu stays open, as it does for a box.
 */
@Composable
internal fun SelectAllMenuItem(allChecked: Boolean, onSelectAll: () -> Unit, onDeselectAll: () -> Unit) {
    DropdownMenuItem(
        text = { Text(if (allChecked) "Deselect all" else "Select all") },
        onClick = { if (allChecked) onDeselectAll() else onSelectAll() },
    )
}

/**
 * The drop-down's face: the checked kinds by name, or "every kind" / "no kind". "Every kind" only of the whole list: of
 * a list narrowed to a feature page's kinds it would read as it did before the page was picked (anomaly 2026-11-01).
 */
internal fun kindsLabel(kinds: Set<SearchDomain.Kind>, options: List<SearchDomain.Kind>): String =
    when {
        kinds.none { it in options } -> "no kind"
        options.size == SearchDomain.Kind.entries.size && options.all { it in kinds } -> "every kind"
        else -> options.filter { it in kinds }.joinToString(", ") { it.label }
    }

private fun resultKey(result: SearchDomain.Result): String = SearchDomain.keyOf(result)

/** When a row was last pressed, by the event's own clock, outliving a restart of the row's gesture. */
private class LastPress {
    var atMillis: Long? = null
}

/**
 * The press gestures every result row shares: press selects, double-click opens, right-click menus. **The double-click is
 * read off the two presses' own times** (anomaly 2026-11-01: *"it doesn't work only when there is already something in the
 * added elements list"*): the press that follows a right-click menu, or another application, gives the window back the
 * focus, and a detector that keeps its first tap inside its gesture could lose it there.
 */
private fun Modifier.resultRowGestures(
    key: Any,
    onSelect: () -> Unit,
    onSecondarySelect: () -> Unit,
    onOpen: () -> Unit,
    onOpenMenu: () -> Unit,
): Modifier = composed {
    val last = remember { LastPress() }
    Modifier
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                // On the press rather than a tap: a tap waits out the double-click window before it fires, and a
                // row that selects a third of a second after the click reads as a row that missed it.
                onSelect()
                val before = last.atMillis
                val second = currentEvent.buttons.isPrimaryPressed && before != null &&
                    down.uptimeMillis - before <= viewConfiguration.doubleTapTimeoutMillis
                last.atMillis = down.uptimeMillis.takeUnless { second }
                if (second) onOpen()
                waitForUpOrCancellation()?.consume()
            }
        }
        // Innermost, so it sees the press first and consumes a right-click before the tap detector does.
        .then(contextMenuModifier(enabled = true, key = key, onSelect = onSecondarySelect, onOpen = onOpenMenu))
}

/**
 * Anomaly 2026-10-15 (*"I right-clicked on the element type drop-down menu in the Search window, and it opened a menu as
 * if I right-clicked on the element in the result list"*): **a drop-down option's right-click acts on the RELEASE**, the
 * whole click consumed here, before the row's own gestures see it. On the desktop the list is a window of its own: closed
 * on the press, it vanished under a button still held, and the rest of the click reached the row under it.
 */
private fun Modifier.pickOnSecondaryRelease(key: Any, onRelease: () -> Unit): Modifier =
    composed {
        val latest by rememberUpdatedState(onRelease)
        pointerInput(key) {
            awaitPointerEventScope {
                while (true) {
                    val press = awaitPointerEvent(PointerEventPass.Initial)
                    if (press.type != PointerEventType.Press || !press.buttons.isSecondaryPressed) continue
                    press.changes.forEach { it.consume() }
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    latest()
                }
            }
        }
    }

/**
 * The colour of ALL the text of a Claude Code log entry's row, the results' and the added elements' (user rule
 * 2026-10-31): a prompt, an answer and a reasoning chain each have theirs, so a timeline of the three reads at a glance.
 * Dark enough to read on the sheet and on the selection's greys. Null for every other kind: the text's own colours.
 */
internal fun logEntryColor(kind: SearchDomain.Kind): Color? =
    when (kind) {
        SearchDomain.Kind.ClaudePrompt -> Color(0xFF1A5FD0)
        SearchDomain.Kind.ClaudeAnswer -> Color(0xFF1E7E34)
        SearchDomain.Kind.ClaudeReasoning -> Color(0xFFB26A00)
        else -> null
    }

@Composable
internal fun resultRowModifier(selected: Boolean, inSelection: Boolean = false): Modifier =
    Modifier
        .fillMaxWidth()
        .height(RESULT_ROW_HEIGHT)
        // The TASK TREE's selection, not a look of its own: the selection said by the cell's grey background,
        // darker for the main selection ([taskCellOutline], the tree's one rule).
        .background(taskCellOutline(isEditing = false, isMainSelection = selected, isInSelectionRange = inSelection).fill)
        .border(
            taskCellOutline(isEditing = false, isMainSelection = selected, isInSelectionRange = inSelection).borderWidth,
            taskCellOutline(isEditing = false, isMainSelection = selected, isInSelectionRange = inSelection).borderColor,
        )
        .padding(horizontal = 6.dp)

/**
 * A task row: the TASK TREE's own cell ([TaskRow]) — its task colour, its expand arrow, its title and Edit
 * Mode, its percentage, minimum time and categories, its selection grey, its right-click menu — with the
 * Search window's sections set into it: the kind before the arrow, the path box between the title and the
 * percentage, the "in no task tree" logo last. The differences from a tree cell are the window's:
 *  - **Edit Mode is stuck to Rename** — no mode selector — and commits [SchedulerIntent.RenameTask]: the row IS
 *    the task, it may have no cell at all, and renaming never touches its sub-tree;
 *  - **nothing is dragged**, and the multi-selection is the list's single row;
 *  - **expanding** shows the task's sub-tree under the row as the tree's own cells ([SearchSubtree]).
 */
@Composable
private fun SearchTaskRow(
    state: SchedulerState,
    result: SearchDomain.TaskResult,
    /** A row of a [SearchPicker]: a click picks it, so it has no check box and opens no sub-tree. */
    picking: Boolean = false,
    query: String,
    selected: Boolean,
    /** Among the Ctrl/Shift-selected rows, without being the selected one: the tree's lighter selection grey. */
    inSelection: Boolean,
    editing: Boolean,
    editDraft: String,
    expanded: Boolean,
    priorities: Map<TaskId, Double>,
    taskColor: Color?,
    minTimeEditing: Boolean,
    /** The cell menu for the path the right-click landed on — built on demand, never over a stale state. */
    actions: (path: List<String>?) -> TaskCellMenuActions,
    onSelect: () -> Unit,
    /** A press on the row with what was held — [ctrl], [shift], and whether it keeps a selection it lands in. */
    onClickRow: (ctrl: Boolean, shift: Boolean, keepIfSelected: Boolean) -> Unit,
    onBeginEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    /** Leaves Edit Mode, committing; the argument is the step the selection takes (Enter: +1, Shift+Enter: -1). */
    onEndEdit: (step: Int) -> Unit,
    /** Leaves Edit Mode, the draft abandoned (Escape in its rectangle). */
    onCancelEdit: () -> Unit = {},
    onToggleExpand: () -> Unit,
    onActivateMinTime: () -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
    onSetWeightWindow: (CellListId?) -> Unit,
    onSetRelativeWindow: (CellId?) -> Unit,
    onOpenTaskEdit: (TaskId) -> Unit,
    onOpenCategory: (CategoryId) -> Unit,
    onDeepCopyCell: (CellId) -> Unit,
    onGoToTaskTree: (TaskId) -> Unit,
    subtreeFocused: Boolean,
    onSubtreeFocus: (Boolean) -> Unit,
    /** Whether this row's sub-tree is the surface showing the cells' selection (the window's `selectionSurface`). */
    subtreeShowsSelection: Boolean = true,
    /** The list's PINNED copy of this row ([pinnedItemIndex]): the head alone, its sub-tree is under the real one. */
    headOnly: Boolean = false,
    /** The list's kind section's width ([rememberKindSectionWidth]). */
    kindWidth: Dp = KIND_SECTION_WIDTH,
    /** The sub-tree cells' menu "add" / "add and remove the others" ([SearchSubtree]). */
    onAddTasks: ((taskIds: List<TaskId>, replacing: Boolean) -> Unit)? = null,
    /** The window Y of the list's top, and whether the sub-tree pins a row of its own there ([SearchSubtree]). */
    clipTop: (() -> Float?)? = null,
    onNestedPin: ((Boolean) -> Unit)? = null,
    scrollListBy: (suspend (Float) -> Unit)? = null,
) {
    val taskId = result.taskId
    val live = state.tasks[taskId]
    // Where the row's figures and gestures reach the tree: its first cell, when the live tree has one. Walked
    // when the tree changes, never per keystroke or per tick.
    val occurrence =
        remember(taskId, state.cells, state.lists, state.tasks) { SchedulerDomain.firstTaskOccurrence(state, taskId) }
    // A task cut from the tree and kept by the timeline: its sub-tree can be looked through, not modified.
    val readOnlySubtree = occurrence == null
    val childListId = live?.childListId
    val hasChildren = !picking && childListId?.let { state.lists[it]?.cellIds?.isNotEmpty() } == true
    val menu = remember(taskId, result.shownPath, state.cells, state.lists, state.tasks) { actions(result.shownPath.takeIf { it.isNotEmpty() }) }
    // The title column is this row's own text, clamped like the tree's (a row has no sub-list to line up with).
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val shownTitle = if (editing) editDraft else result.title
    val titlePx = if (shownTitle.isEmpty()) 0 else textMeasurer.measure(shownTitle, bodyStyle).size.width
    val columnWidth = with(density) { titlePx.toDp() }.coerceIn(PRIORITY_COLUMN_MIN, PRIORITY_COLUMN_MAX)
    val columnPx = with(density) { columnWidth.toPx() }
    // The query's hits in the title, drawn the way the tree's Ctrl+F draws its own.
    val match = LocalSearchMatch.current
    val hits = remember(result.title, query, match) { SearchDomain.matchRanges(result.title, query, match) }

    Column(Modifier.fillMaxWidth()) {
        // The cell IS the row: it takes the list's row height itself (no slot around it, whose bare band
        // above and below showed as a white gap between two task rows). Its minimum time and categories are only as
        // wide as what they show ([LocalTightRowColumns]): the path has what they leave. The sub-tree under it is
        // the tree's, columns and all.
        androidx.compose.runtime.CompositionLocalProvider(LocalTightRowColumns provides true) {
        TaskRow(
            minHeight = RESULT_ROW_HEIGHT,
            depth = 0,
            // A task no live cell holds is still a row: its id stands in for the cell the tree would key on.
            cellId = occurrence?.cellId ?: CellId("search-row/" + taskId.value),
            renderVia = null,
            displayTitle = shownTitle,
            isMainSelection = selected,
            isInSelectionRange = inSelection,
            selectable = true,
            isEditing = editing,
            hasChildren = hasChildren,
            expanded = expanded,
            moveDropBefore = false,
            moveDropAfter = false,
            canMoveFromCell = false,
            isBeingMoved = false,
            priorityLabel = formatPriorityPercent(priorities[taskId] ?: 0.0),
            priorityColumnWidth = columnWidth,
            taskColor = taskColor,
            searchRanges = if (editing) emptyList() else hits,
            currentSearchRange = null,
            textOverflow = titlePx > columnPx,
            minMinutes = live?.minimumMinutes ?: 0,
            minTimeEditing = minTimeEditing && live != null,
            cellMenu = menu,
            onTogglePriorityWeights = {
                occurrence?.let { occ -> state.cells[occ.cellId]?.parentListId?.let(onSetWeightWindow) }
            },
            onOpenRelativePriority = { occurrence?.let { onSetRelativeWindow(it.cellId) } },
            onSetMinTime = { minutes -> onIntent(SchedulerIntent.SetTaskMinimumTime(taskId, minutes)) },
            onActivateMinTime = { if (live != null) onActivateMinTime() },
            // The cell's own reading of the press: Ctrl / Shift as held; a plain click's first press (and a
            // right-click's) keeps a selection it lands in, and its resolution a moment later (forceClear) collapses it.
            onClick = { _, ctrl, shift, forceClear -> onClickRow(ctrl, shift, !forceClear) },
            onDragSelect = { _, _ -> },
            moveDragActive = false,
            resolveRowAt = { null },
            onRowBounds = { _, _, _ -> },
            onMoveDragStart = {},
            onMoveDropHover = { _, _, _ -> },
            onMoveDragEnd = {},
            // PRD §4: a double-click on the title opens Edit Mode — renaming, always.
            onDoubleClick = {
                onSelect()
                onBeginEdit()
            },
            onTextChange = onDraftChange,
            onExitEdit = { nav ->
                onEndEdit(
                    when (nav) {
                        EditExitNavigation.Down -> 1
                        EditExitNavigation.Up -> -1
                        else -> 0
                    },
                )
            },
            onToggleExpand = { if (hasChildren) onToggleExpand() },
            // Stuck to Rename: no mode selector, no id menu.
            editMenus = null,
            // Anomaly 2026-11-11: in Edit Mode the row shows the tree cell's rectangle, its Search window ([RowRename]).
            editPopup =
                if (editing && !picking && !headOnly) {
                    { refocusField, anchor, region ->
                        PublishCellEditPopup(
                            CellEditPopupRequest(
                                state = state,
                                namingSource = state,
                                cellId = null,
                                onIntent = onIntent,
                                refocusField = refocusField,
                                anchor = anchor,
                                region = region,
                                rename = RowRename(editDraft, onDraftChange, onCancelEdit),
                            ),
                        )
                    }
                } else {
                    null
                },
            categoryCell = live?.let { { TaskCategoryCell(state, taskId, onIntent, onOpenCategory) } },
            rowLeading = { KindSection(result.kind, kindWidth) },
            // The title prevails; the path box is squeezed to a thin box behind a long one, never dropped.
            afterTitle = {
                Box(Modifier.fillMaxWidth().height(24.dp).padding(start = 8.dp)) {
                    // A right-click on the path selects as one on the row does: the row alone unless it is selected.
                    TaskPathBox(result, onSelect = { onClickRow(false, false, true) }, actions = actions)
                }
            },
            afterTitleMinWidth = MIN_PATH_BOX_WIDTH + 8.dp,
            rowTrailing = { if (!result.inTaskTree) Box(Modifier.padding(start = 6.dp)) { NotInTreeLogo() } },
        )
        }
        if (expanded && hasChildren && !headOnly) {
            SearchSubtree(
                state = state,
                listId = childListId,
                readOnly = readOnlySubtree,
                priorities = priorities,
                focused = subtreeFocused,
                onFocus = onSubtreeFocus,
                onIntent = onIntent,
                onSetWeightWindow = onSetWeightWindow,
                onSetRelativeWindow = onSetRelativeWindow,
                onOpenTaskEdit = onOpenTaskEdit,
                onOpenCategory = onOpenCategory,
                onDeepCopyCell = onDeepCopyCell,
                onGoToTaskTree = onGoToTaskTree,
                onAddTasks = onAddTasks,
                clipTop = clipTop,
                onNestedPin = onNestedPin,
                scrollListBy = scrollListBy,
                showSelection = subtreeShowsSelection,
                onTakeSelection = { onSubtreeFocus(true) },
            )
        }
    }
}

/**
 * User rule 2026-10-07: **the task tree's pinned parent row, in a list of the Search window** — *"when a task element
 * is expanded, the logic of the top task cell showing up in the task tree window must also be applied there"*. The
 * tree's rule ([TaskTreeView]'s `pinnedRow`), read off the list's own layout: over a band of one row height at the
 * top, the item whose content appears RIGHT BELOW the band (the first whose bottom lies past it) is pinned when its
 * own row has started to leave the top. Only an expanded row is taller than the band, so only one can be the answer;
 * the caller draws its head there — unless its sub-tree is pinning a deeper parent in that same band, which is the
 * direct parent of what shows and so the one the rule names.
 */
private fun pinnedItemIndex(listState: LazyListState, bandPx: Float): Int? {
    val info = listState.layoutInfo
    val top = info.viewportStartOffset
    val below = info.visibleItemsInfo.firstOrNull { it.offset + it.size > top + bandPx + 0.5f } ?: return null
    return below.index.takeIf { below.offset < top }
}

/**
 * An expanded task row's sub-tree: the TASK TREE's own view ([TaskTreeView]) over the live tree re-rooted at
 * the task's sub-list, with the Search window's own expansion, selection and edit session
 * ([projectSearchSubtree]) — so its cells are real cells, edited and selected as in the tree. Bounded in height:
 * it scrolls inside the list rather than stretching it. [readOnly] for a task cut from the tree: looked
 * through, never modified (the reducer refuses it — [SchedulerIntent.InSearchSubtree]).
 */
@Composable
private fun SearchSubtree(
    state: SchedulerState,
    listId: CellListId,
    readOnly: Boolean,
    priorities: Map<TaskId, Double>,
    focused: Boolean,
    onFocus: (Boolean) -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
    onSetWeightWindow: (CellListId?) -> Unit,
    onSetRelativeWindow: (CellId?) -> Unit,
    onOpenTaskEdit: (TaskId) -> Unit,
    onOpenCategory: (CategoryId) -> Unit,
    onDeepCopyCell: (CellId) -> Unit,
    onGoToTaskTree: (TaskId) -> Unit,
    /** User rule 2026-10-07: the cells' menu offers "add" and "add and remove the others", as a result row's does. */
    onAddTasks: ((taskIds: List<TaskId>, replacing: Boolean) -> Unit)? = null,
    /** The window Y of the top of the list this sub-tree scrolls in — where its pinned parent row is drawn from. */
    clipTop: (() -> Float?)? = null,
    /** Whether this sub-tree is pinning a parent row of its own: the list then pins nothing over it. */
    onNestedPin: ((Boolean) -> Unit)? = null,
    /** Scrolls the list (pixels, negative = up): a pinned copy pressed brings its real row back, as in the tree. */
    scrollListBy: (suspend (Float) -> Unit)? = null,
    /**
     * Whether THIS sub-tree draws the cells' selection: the sub-trees share one (`searchSelection`), and the window
     * shows it on the surface the last press went to — not here once a row, or another sub-tree, took it.
     */
    showSelection: Boolean = true,
    /** A gesture went to this sub-tree: it is the surface that shows the selection from now on. */
    onTakeSelection: () -> Unit = {},
) {
    val projected =
        remember(
            state.cells, state.lists, state.tasks, state.searchExpanded, state.searchSelection, state.searchEditSession, listId,
            showSelection,
        ) {
            state.projectSearchSubtree(listId).let { shown ->
                if (showSelection) shown else shown.copy(selection = org.example.project.scheduler.state.SchedulerSelection())
            }
        }
    TaskTreeView(
        state = projected,
        priorities = priorities,
        onIntent = { intent ->
            when (intent) {
                // App-wide: the history stacks and the window focus are no tree's.
                is SchedulerIntent.Undo, is SchedulerIntent.Redo,
                is SchedulerIntent.UndoAll, is SchedulerIntent.RedoAll,
                is SchedulerIntent.UndoSelection, is SchedulerIntent.RedoSelection,
                is SchedulerIntent.UndoPosition, is SchedulerIntent.RedoPosition,
                is SchedulerIntent.UndoFocus, is SchedulerIntent.RedoFocus,
                is SchedulerIntent.FocusWindow,
                -> onIntent(intent)
                else -> {
                    // By the gesture, not by the focus: a sub-tree that already holds the keyboard gains none.
                    onTakeSelection()
                    onIntent(SchedulerIntent.InSearchSubtree(intent, listId, readOnly))
                }
            }
        },
        keyboardActive = focused,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = SUBTREE_MAX_HEIGHT)
            .padding(start = 24.dp)
            .onFocusChanged { onFocus(it.hasFocus) },
        onSetWeightWindow = onSetWeightWindow,
        onSetRelativeWindow = onSetRelativeWindow,
        onSetEditTask = { it?.let(onOpenTaskEdit) },
        onSetEditCategory = { it?.let(onOpenCategory) },
        onSetDeepCopyCell = { it?.let(onDeepCopyCell) },
        onGoToTaskTree = onGoToTaskTree,
        onAddTasks = onAddTasks,
        clipTopWindowY = clipTop,
        onPinnedParent = onNestedPin,
        scrollOuterBy = scrollListBy,
        outerBandPx = with(LocalDensity.current) { RESULT_ROW_HEIGHT.toPx() },
        refocusWindow = null,
        // A task is the same colour here, in the tree and on the calendar, and a Change Task row is named from
        // the tree — both read off the LIVE state, not off the sub-tree this row re-roots it at.
        colorSource = state,
        namingSource = state,
    )
}

@Composable
private fun MenuEntry(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = onClick)
}

/** The most an expanded row's sub-tree takes of the list before it scrolls inside itself. */
private val SUBTREE_MAX_HEIGHT: Dp = 320.dp

/**
 * The path, in its rectangle — with the arrow that lists every path when the task has several. A right-click on
 * the box, or on one line of the list, opens the cell menu for THAT path ([actions]): its own gesture and its
 * own menu, so "go to task tree" there goes to that path's cell.
 */
@Composable
private fun TaskPathBox(
    result: SearchDomain.TaskResult,
    onSelect: () -> Unit,
    /** The cell menu of a right-clicked path; null for a box with no menu (an id suggestion list's row). */
    actions: ((path: List<String>?) -> TaskCellMenuActions)?,
    /** Whether a task with several paths offers the arrow that lists them all; an id row shows its first only. */
    listsPaths: Boolean = true,
) {
    var listOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<TaskCellMenuActions?>(null) }
    // The right-click gestures below are keyed and started once: they select through the LATEST handler.
    val latestOnSelect by rememberUpdatedState(onSelect)
    val selectNow = { latestOnSelect() }
    var menuOpen by remember { mutableStateOf(false) }
    val onOpenMenu: (List<String>?) -> Unit = { path ->
        menu = actions?.invoke(path)
        menuOpen = menu != null
    }
    menu?.let { shown ->
        transientMenuDismissal(menuOpen) { menuOpen = false }
        AppDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            properties = PopupProperties(focusable = false),
            atPointer = LocalRowMenusAtPointer.current,
        ) {
            TaskCellMenuItems(shown) { menuOpen = false }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, onTaskCell(MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(4.dp))
            .then(
                contextMenuModifier(enabled = actions != null, key = result.taskId to "path", onSelect = selectNow) {
                    onOpenMenu(result.shownPath.takeIf { it.isNotEmpty() })
                },
            )
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (result.shownPath.isEmpty()) "no path" else SearchDomain.pathLabel(result.shownPath),
            style = MaterialTheme.typography.labelMedium,
            fontStyle = if (result.shownPath.isEmpty()) FontStyle.Italic else FontStyle.Normal,
            color = onTaskCell(MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.weight(1f),
        )
        if (result.hasSeveralPaths && listsPaths) {
            Box {
                Text(
                    text = if (listOpen) "▴" else "▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = onTaskCell(MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier
                        .fillMaxHeight()
                        .menuToggleClickable(listOpen) { listOpen = it }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                transientMenuDismissal(listOpen) { listOpen = false }
                AppDropdownMenu(
                    expanded = listOpen,
                    onDismissRequest = { listOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            text = "${result.paths.size} paths",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (path in result.paths) {
                            Text(
                                text = SearchDomain.pathLabel(path),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    // Opening the row's menu closes this list (one menu at a time), and the
                                    // menu then speaks for this line's occurrence.
                                    .then(
                                        contextMenuModifier(enabled = true, key = path, onSelect = selectNow) {
                                            onOpenMenu(path)
                                        },
                                    )
                                    .padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        } else {
            Box(Modifier.width(6.dp))
        }
    }
}

/**
 * User rule 2026-10-03: **a task row of an id suggestion list** (the identity menu of every edit-mode menu that names
 * a task — a tree cell's, the weight table's, the calendar's, the task picker's, the Search window's naming fields) is
 * the Search window's own task result row — the tree's [TaskRow] with the same [TaskPathBox] and the same
 * [SearchSubtree] — configured down to three sections: the **expansion arrow**, the **title** and the **path**. The
 * arrow opens the task's sub-tree under the row, read-only; a click anywhere else is the PICK ([EditMenuItem.onClick]):
 * no Edit Mode, no list of the other paths, no percentage, minimum time, categories, check box or kind. A right-click
 * keeps the id row's own "go to task" ([EditMenuItem.actions]).
 *
 * [allPaths] is [SearchDomain.allPathsInAnyTree], measured once per change of the trees by the caller
 * ([LocalTaskIdentityRow]'s provider), never per row.
 */
@Composable
internal fun TaskIdentityRow(
    state: SchedulerState,
    allPaths: Map<TaskId, List<List<String>>>,
    item: EditMenuItem,
    onIntent: (SchedulerIntent) -> Unit,
) {
    val taskId = item.taskId ?: return
    val task = state.tasks[taskId]
    val title = task?.title?.takeIf { it.isNotBlank() } ?: item.label
    // The row's own path when it stands for one place of the task (a task cell picker), else the task's first.
    val paths = item.taskPath?.let { listOf(it) } ?: allPaths[taskId].orEmpty().take(1)
    val result = SearchDomain.TaskResult(taskId, title, paths, inTaskTree = paths.isNotEmpty())
    // One task id may be listed several times, once per path: the row's own state is keyed on both.
    val rowKey = taskId.value + "@" + item.taskPath?.joinToString("/").orEmpty()
    val childListId = task?.childListId
    val hasChildren = childListId?.let { state.lists[it]?.cellIds?.isNotEmpty() } == true
    var expanded by remember(rowKey) { mutableStateOf(false) }
    var menuOpen by remember(rowKey) { mutableStateOf(false) }
    val pick by rememberUpdatedState(item.onClick)
    // BOUNDED in width, whatever the parent offers. A tree cell's edit menus sit in a tree that scrolls sideways, so
    // they are measured with NO maximum width; the row shares its free width between the title and the path box, and
    // an unbounded one is the "Can't represent a width of 2147483563 … in Constraints" crash (anomaly 2026-10-03,
    // typing in a cell and picking from its menus). A narrower parent (a drop-down) still wins.
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().then(contextMenuModifier(item.actions != null, key = rowKey) { menuOpen = true })) {
            TaskRow(
                depth = 0,
                cellId = CellId("identity-row/$rowKey"),
                renderVia = null,
                displayTitle = title,
                isMainSelection = item.selected,
                isInSelectionRange = false,
                selectable = true,
                isEditing = false,
                hasChildren = hasChildren,
                expanded = expanded,
                moveDropBefore = false,
                moveDropAfter = false,
                canMoveFromCell = false,
                isBeingMoved = false,
                priorityLabel = null,
                priorityColumnWidth = PRIORITY_COLUMN_MIN,
                taskColor = item.taskColor,
                searchRanges = emptyList(),
                currentSearchRange = null,
                textOverflow = false,
                minMinutes = 0,
                minTimeEditing = false,
                cellMenu = null,
                onTogglePriorityWeights = {},
                onOpenRelativePriority = {},
                onSetMinTime = {},
                onActivateMinTime = {},
                // One click anywhere but the arrow selects the task id: the pick — on the PRESS only. The row reports a
                // plain click twice (the press, then its resolution with `forceClear`), and a pick must happen once.
                onClick = { _, _, _, forceClear -> if (!forceClear) pick() },
                onDragSelect = { _, _ -> },
                moveDragActive = false,
                resolveRowAt = { null },
                onRowBounds = { _, _, _ -> },
                onMoveDragStart = {},
                onMoveDropHover = { _, _, _ -> },
                onMoveDragEnd = {},
                onDoubleClick = {},
                onTextChange = {},
                onExitEdit = {},
                onToggleExpand = { if (hasChildren) expanded = !expanded },
                editMenus = null,
                showMinTime = false,
                afterTitle = {
                    Box(Modifier.fillMaxWidth().height(24.dp).padding(start = 8.dp)) {
                        TaskPathBox(result, onSelect = {}, actions = null, listsPaths = false)
                    }
                },
                afterTitleMinWidth = MIN_PATH_BOX_WIDTH + 8.dp,
            )
            item.actions?.let { actions ->
                AppDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("go to task") },
                        enabled = actions.onGoToTask != null,
                        onClick = {
                            menuOpen = false
                            actions.onGoToTask?.invoke()
                        },
                    )
                }
            }
        }
        if (expanded && hasChildren && childListId != null) {
            SearchSubtree(
                state = state,
                listId = childListId,
                // Looked through, never modified: the row only names the task.
                readOnly = true,
                priorities = emptyMap(),
                focused = false,
                onFocus = {},
                onIntent = onIntent,
                onSetWeightWindow = {},
                onSetRelativeWindow = {},
                onOpenTaskEdit = {},
                onOpenCategory = {},
                onDeepCopyCell = {},
                onGoToTaskTree = {},
            )
        }
    }
}

/** The "in no task tree" logo: a crossed circle, which explains itself on hover. */
@Composable
private fun NotInTreeLogo() {
    InfoHint(text = NOT_IN_TREE_HINT) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(1.dp, onTaskCell(MaterialTheme.colorScheme.outline), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⊘",
                style = MaterialTheme.typography.labelMedium,
                color = onTaskCell(MaterialTheme.colorScheme.outline),
            )
        }
    }
}

@Composable
private fun ItemResultRow(
    /** The list's kind section's width ([rememberKindSectionWidth]). */
    kindWidth: Dp,
    item: SearchDomain.ItemResult,
    /** The titles of the categories it carries (user rule 2026-11-02). */
    categories: List<String>,
    /** Whether what stands under it is shown, and its arrow's press. */
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    /** What the Search window's bar holds: highlighted in the row's texts ([searchHighlighted]). */
    query: String,
    selected: Boolean,
    /** Among the Ctrl/Shift-selected rows, without being the selected one: the thin outline. */
    inSelection: Boolean,
    onSelect: () -> Unit,
    /** A right-click's selection: the row alone unless it is already selected. */
    onSecondarySelect: () -> Unit,
    /** What the row's own window is — the menu's "open in …" entries and a "creation" row's right-click. */
    onOpen: () -> Unit,
    /** A double-click on the row: it adds the element (or makes it, for a "creation" row). */
    onDoubleClick: () -> Unit,
    /** The menu's "add": every selected row into the window's added elements. */
    onAdd: () -> Unit,
    /** The menu's "add and remove the others": [onAdd], the added elements emptied first. */
    onAddReplacing: () -> Unit,
    /** The timer's live countdown and controls, rendered in the same compact row as timer action runs. */
    timerRun: (@Composable () -> Unit)? = null,
    /** A drop-down's option with a check box: whether it is ticked ([SearchOptions.checked]); null: no box. */
    checkBox: Boolean? = null,
    /** Drawn before the name: a drop-down's own mark of an option (a period drawing's swatch). */
    leading: (@Composable () -> Unit)? = null,
    /** False for a drop-down's option: its right-click is its own ([SearchOptions.onSolo]), no menu. */
    hasMenu: Boolean = true,
    /** A drop-down's option: what its right-click does, on the RELEASE ([pickOnSecondaryRelease]). */
    onSecondaryRelease: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val workplaceRuns = LocalWorkplaceRuns.current
    // The gestures are keyed by the row and started once: they call the handlers of the LATEST composition.
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnSecondarySelect by rememberUpdatedState(onSecondarySelect)
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)
    Box(
        modifier = resultRowModifier(selected, inSelection)
            .then(onSecondaryRelease?.let { Modifier.pickOnSecondaryRelease(item.id, it) } ?: Modifier)
            .resultRowGestures(
                key = item.kind.name + "/" + item.id,
                onSelect = { currentOnSelect() },
                onSecondarySelect = { currentOnSecondarySelect() },
                onOpen = { currentOnDoubleClick() },
                // Every row's right-click opens its menu — a "creation" row's too (anomaly 2026-10-03: it made its
                // element straight away, so the row could not be ADDED, which is how a default configuration is edited).
                onOpenMenu = { if (hasMenu) menuOpen = true },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // A drop-down lists options alone: no column saying so on each row.
            if (item.kind != SearchDomain.Kind.Option) KindSection(item.kind, kindWidth)
            // User rule 2026-11-02: every element has its arrow. A "creation" row is a command, and an option a choice:
            // neither is an element.
            if (item.kind != SearchDomain.Kind.Creation && item.kind != SearchDomain.Kind.Option) SectionArrow(!expanded, onToggleExpand)
            if (checkBox != null) Checkbox(checked = checkBox, onCheckedChange = null)
            leading?.invoke()
            if (timerRun != null) {
                timerRun()
            } else {
                Text(
                    text = searchHighlighted(item.name, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = logEntryColor(item.kind) ?: Color.Unspecified,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = searchHighlighted(withCategories(SearchDomain.shownDetail(item) { workplaceRuns?.isRunning(it) == true }, categories), query),
                    style = MaterialTheme.typography.labelSmall,
                    color = logEntryColor(item.kind) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        transientMenuDismissal(menuOpen) { menuOpen = false }
        AppDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            properties = PopupProperties(focusable = false),
            atPointer = LocalRowMenusAtPointer.current,
        ) {
            // EVERY row adds (user rule 2026-10-03): this Search window is every element's own window (2026-10-01),
            // so "add" and "add and remove others" are never replaced. A history unit's opening went to the History
            // window, which its "Information" action replaced, so it has nothing else. The rows a lateral window of the
            // app still owns keep a way there, UNDER the two.
            MenuEntry("add") {
                menuOpen = false
                onAdd()
            }
            MenuEntry(ADD_REPLACING_LABEL) {
                menuOpen = false
                onAddReplacing()
            }
            val elsewhere =
                when (item.kind) {
                    SearchDomain.Kind.TaskTree -> "open in All task trees"
                    SearchDomain.Kind.TaskRelation -> "open in Task relations"
                    SearchDomain.Kind.Shortcut -> "open in Keyboard shortcuts"
                    SearchDomain.Kind.Window -> "show window"
                    // What the right-click did on its own until 2026-10-03: making a new element of the row's kind.
                    SearchDomain.Kind.Creation -> "create"
                    SearchDomain.Kind.WorkplaceSetup -> "apply"
                    else -> null
                }
            if (elsewhere != null) {
                MenuEntry(elsewhere) {
                    menuOpen = false
                    onOpen()
                }
            }
        }
    }
}

/** What a row says beside an element's name, with the categories it carries. */
private fun withCategories(detail: String, categories: List<String>): String =
    (listOf(detail).filter { it.isNotEmpty() } + categories).joinToString("  ·  ")

/** How far each level of an element's children is indented. */
private val ELEMENT_CHILD_INDENT: Dp = 24.dp


/**
 * User rule 2026-11-02: **what stands under an element's row, open by its arrow** — its children, each a row with its own
 * arrow, then a blank field like the one a task's sub-tree ends on. A double-click on the blank field opens a Search window
 * coming from the element ([SearchRowOpeners.onOpenChildSearch]), where another is found and made its child by "Add a parent".
 * A child's ✕ takes it out of this parent, never off the account. [path] is the elements above: a loop two devices made
 * between them is cut here.
 */
@Composable
private fun ElementChildren(
    parent: String,
    surface: String,
    depth: Int,
    path: Set<String>,
    state: SchedulerState,
    windows: List<SearchDomain.WindowEntry>,
    schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    openers: SearchRowOpeners,
    onIntent: (SchedulerIntent) -> Unit,
    kindWidth: Dp,
    selection: ElementChildSelection,
) {
    val children = elementChildRows(state, parent, surface, path, windows, schedulerRuns, claudeLogs)
    val indent = Modifier.padding(start = ELEMENT_CHILD_INDENT * depth)
    // A Shift+click's range runs over these siblings.
    val siblings by rememberUpdatedState(children.map { it.rowKey })
    for (child in children) {
        val row = child.row
        val key = child.key
        androidx.compose.runtime.key(key) {
            val rowKey = child.rowKey
            selection.elements[rowKey] = key
            val currentRow by rememberUpdatedState(row)
            var menuOpen by remember { mutableStateOf(false) }
            // Moved onto by the arrows: the list scrolls just enough to show it, as it does for a row.
            val isMain = rowKey == selection.main
            val bringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            LaunchedEffect(isMain) { if (isMain) runCatching { bringIntoView.bringIntoView() } }
            Box(Modifier.outsidePressPart(selection.editor).bringIntoViewRequester(bringIntoView)) {
            Row(
                modifier = resultRowModifier(
                    selected = rowKey in selection.selected && isMain,
                    inSelection = rowKey in selection.selected && !isMain,
                )
                    .then(indent)
                    .recordPressModifiers(selection.pressModifiers)
                    .resultRowGestures(
                        rowKey,
                        onSelect = { selection.click(siblings, rowKey) },
                        onSecondarySelect = { selection.secondaryClick(rowKey) },
                        onOpen = { openers.open(state, currentRow) },
                        onOpenMenu = { menuOpen = true },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KindSection(row.kind, kindWidth)
                SectionArrow(rowKey !in expanded) { onToggle(rowKey) }
                Text(row.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (ElementTree.canUnlink(state, parent, key, claudeLogs)) {
                    Text(
                        text = "✕",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { ElementTree.parentIntents(state, listOf(key), parent, on = false).forEach(onIntent) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            // The menu, on the selected children: added to the window's elements, as a result row's "add" adds.
            transientMenuDismissal(menuOpen) { menuOpen = false }
            AppDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = false),
                atPointer = LocalRowMenusAtPointer.current,
            ) {
                MenuEntry("add") {
                    menuOpen = false
                    selection.add(replacing = false)
                }
                MenuEntry(ADD_REPLACING_LABEL) {
                    menuOpen = false
                    selection.add(replacing = true)
                }
            }
            }
            if (rowKey in expanded) {
                ElementChildren(key, childSurface(rowKey), depth + 1, path + key, state, windows, schedulerRuns, claudeLogs, expanded, onToggle, openers, onIntent, kindWidth, selection)
            }
        }
    }
    // User rule 2026-11-02: the blank field ends the list of children, as a task's sub-tree ends on its placeholder. It is
    // a ROW of that list, drawn as its siblings are: their full-width box, nothing in it (anomaly 2026-11-03: a box of its
    // own inside the indent stood one level deeper, so it read as the last child's, which was not expanded).
    Box(
        modifier = resultRowModifier(selected = false)
            .resultRowGestures(surface + parent + "/blank", onSelect = {}, onSecondarySelect = {}, onOpen = { openers.onOpenChildSearch(parent) }, onOpenMenu = {}),
    )
}

/** One row under an element's arrow: where it is drawn ([rowKey]: surface and path), the element, its row. */
internal class ElementChildRow(val rowKey: String, val key: String, val row: SearchDomain.Result)

/** The surface the children of the child row [rowKey] are drawn on: open rows are told apart by it. */
private fun childSurface(rowKey: String): String = "$rowKey>"

/**
 * **The children drawn under [parent]** ([ElementTree.childrenOf], [path] — the elements above — cut out, a loop two
 * devices made), the one reading of them: what [ElementChildren] draws and what the arrows walk ([shownRowOrder]).
 */
internal fun elementChildRows(
    state: SchedulerState,
    parent: String,
    surface: String,
    path: Set<String>,
    windows: List<SearchDomain.WindowEntry>,
    schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
): List<ElementChildRow> {
    val keys = ElementTree.childrenOf(state, parent, claudeLogs).filterNot { it in path }
    if (keys.isEmpty()) return emptyList()
    return SearchDomain.resolve(state, keys, { emptyMap() }, windows, schedulerRuns, claudeLogs).map { row ->
        val key = resultKey(row)
        ElementChildRow(surface + path.joinToString(">") + ">" + key, key, row)
    }
}

/** A row of the result list as drawn: a result ([index]), or a child under an open element ([child]). */
internal data class ShownRow(val index: Int, val child: ElementChildRow? = null)

/**
 * Anomaly 2026-11-05 (*"the up and down arrow don't work"* on the children): **the result list's rows as drawn, top to
 * bottom** — each result, then, under an open element, its children and theirs, depth first. What ↑ and ↓ walk.
 */
internal fun shownRowOrder(
    state: SchedulerState,
    results: List<SearchDomain.Result>,
    expanded: Set<String>,
    windows: List<SearchDomain.WindowEntry> = emptyList(),
    schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry> = emptyList(),
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog = org.example.project.claudelogs.ClaudeLogCatalog.EMPTY,
): List<ShownRow> {
    fun under(parent: String, surface: String, path: Set<String>, index: Int): List<ShownRow> =
        elementChildRows(state, parent, surface, path, windows, schedulerRuns, claudeLogs).flatMap { child ->
            listOf(ShownRow(index, child)) +
                if (child.rowKey in expanded) under(child.key, childSurface(child.rowKey), path + child.key, index) else emptyList()
        }
    return results.flatMapIndexed { index, result ->
        val key = resultKey(result)
        val open = result is SearchDomain.ItemResult && RESULT_SURFACE + key in expanded
        listOf(ShownRow(index)) + if (open) under(key, RESULT_SURFACE, setOf(key), index) else emptyList()
    }
}

/**
 * Anomaly 2026-11-05 (*"the children are not selectable"*): **which children under an element's arrow are selected**, on
 * both surfaces of one window, by row key (surface and path: one element under two parents is two rows). The rows' own
 * rule ([ClickSelection]: a click one, Ctrl one more, Shift the range among its siblings; a right-click keeps a selection
 * it lands in), the row menu's "add" on them, and a press anywhere else ([editor]) leaves none. Compose-only, as the
 * rows' selection is. Selecting a child takes the result list's selection away ([onTake]).
 */
internal class ElementChildSelection {
    var selected: Set<String> by mutableStateOf(emptySet())
        private set
    /** The row pressed last: the full outline; the others of [selected] the thin one. */
    var main: String? by mutableStateOf(null)
        private set
    private var anchor: String? = null
    /** The element each drawn row stands for. */
    val elements: HashMap<String, String> = HashMap()
    val pressModifiers = PressModifiers()
    var editor: OutsidePressEditor = OutsidePressEditor()
    var onTake: (rowKey: String) -> Unit = {}
    var onAdd: (keys: List<String>, replacing: Boolean) -> Unit = { _, _ -> }

    fun click(siblings: List<String>, rowKey: String) {
        val next = ClickSelection.click(siblings, selected, anchor, rowKey, shift = pressModifiers.shift, ctrl = pressModifiers.ctrl)
        selected = next.selected
        anchor = next.anchor
        main = rowKey
        onTake(rowKey)
    }

    /** [rowKey] selected alone, as an arrow moves onto it. */
    fun selectOnly(rowKey: String, element: String) {
        elements[rowKey] = element
        selected = setOf(rowKey)
        anchor = rowKey
        main = rowKey
        onTake(rowKey)
    }

    fun secondaryClick(rowKey: String) {
        if (rowKey !in selected) {
            selected = setOf(rowKey)
            anchor = rowKey
        }
        main = rowKey
        onTake(rowKey)
    }

    /** The selected children's elements, each once, into the window's added elements. */
    fun add(replacing: Boolean) = onAdd(selected.mapNotNull { elements[it] }.distinct(), replacing)

    fun clear() {
        if (selected.isEmpty() && main == null) return
        selected = emptySet()
        main = null
        anchor = null
    }
}

/** Ctrl and Shift as held on the last press in a list — read by a row whose gesture does not report them. */
internal class PressModifiers {
    var ctrl: Boolean = false
    var shift: Boolean = false
}

/**
 * Put on the list: records Ctrl (or Cmd) and Shift on every press, on the Initial pass and without consuming, so the
 * row's own press handler that follows reads them (`checkRangeShift` does the same for the boxes).
 */
private fun Modifier.recordPressModifiers(holder: PressModifiers): Modifier =
    this.pointerInput(holder) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press) continue
                val modifiers = event.keyboardModifiers
                holder.ctrl = modifiers.isPointerCtrlPressed || modifiers.isPointerMetaPressed
                holder.shift = modifiers.isPointerShiftPressed
            }
        }
    }
