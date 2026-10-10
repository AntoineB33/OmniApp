package org.example.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.domain.ChronoDomain
import org.example.project.scheduler.domain.CalendarLockDomain
import org.example.project.scheduler.ui.CalendarGoTo
import org.example.project.scheduler.ui.LocalCalendarGoTo
import org.example.project.ui.EditMenuItem
import org.example.project.ui.LocalTaskIdentityRow
import org.example.project.ui.TaskIdentityRow
import org.example.project.scheduler.domain.TaskPathsDomain
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.domain.CalendarElements
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TimerDomain
import org.example.project.scheduler.engine.AppSchedulerHost
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.persistence.ActiveSessionStore
import org.example.project.scheduler.persistence.DeviceSleepGapStore
import org.example.project.scheduler.persistence.DeclaredAwayStore
import org.example.project.scheduler.persistence.SleepScanCheckpointStore
import org.example.project.scheduler.persistence.SyncMetaStore
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.persistence.WindowPlacement
import org.example.project.scheduler.persistence.WindowPlacementStore
import org.example.project.scheduler.debug.TimeLink
import org.example.project.scheduler.debug.startTimeLink
import org.example.project.scheduler.persistence.createDefaultSchedulerStore
import org.example.project.scheduler.platform.Diagnostics
import org.example.project.scheduler.platform.currentDeviceKind
import org.example.project.scheduler.platform.deviceLockedIntervals
import org.example.project.scheduler.platform.GlobalHotkeys
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.installGlobalHotkeys
import org.example.project.scheduler.platform.installPauseCuePushBridge
import org.example.project.scheduler.platform.installPlatformActivityListener
import org.example.project.scheduler.platform.localPauseCueDeliveryPlatform
import org.example.project.scheduler.platform.ringAlarmPlatform
import org.example.project.scheduler.platform.scheduleLocalPauseCuePlatform
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.ui.undoRedoIntentFor
import org.example.project.ui.CustomMenuButton
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.CustomMenuSection
import org.example.project.ui.LocalMenuButtonHost
import org.example.project.ui.MenuButtonHost
import androidx.compose.ui.input.key.onKeyEvent
import org.example.project.scheduler.state.defaultSubtreePriorities
import org.example.project.scheduler.state.projectDefaultSubtree
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.PriorityWeightWindow
import org.example.project.scheduler.ui.RelativePriorityWindow
import org.example.project.scheduler.ui.OnlineWindow
import org.example.project.scheduler.ui.syncStatusLabel
import org.example.project.scheduler.ui.TaskSchedulerScreen
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.scheduler.ui.TaskEditWindow
import org.example.project.scheduler.ui.DeepCopyWindow
import org.example.project.scheduler.platform.writeSystemClipboardText
import org.example.project.time.AppClock
import org.example.project.time.SimAppClock
import org.example.project.time.SystemAppClock
import org.example.project.ui.AlarmWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import org.example.project.ui.LocalHeadObstacle
import org.example.project.ui.REMINDER_EDIT_FRAME_ID
import org.example.project.ui.TASK_TREE_WINDOW_ID
import org.example.project.ui.TaskTreeWindow
import org.example.project.ui.TASK_TREE_DEFAULT_CHROME
import org.example.project.ui.windowInstanceId
import org.example.project.ui.WindowInstance
import org.example.project.ui.WindowCopy
import org.example.project.ui.LocalWindowInstance
import org.example.project.ui.ObjectWindowKey
import org.example.project.ui.ALARM_DEFAULTS_FRAME_ID
import org.example.project.ui.CATEGORY_EDIT_FRAME_ID
import org.example.project.scheduler.ui.TASK_EDIT_FRAME_ID
import org.example.project.scheduler.ui.PRIORITY_WEIGHTS_FRAME_ID
import org.example.project.scheduler.ui.RELATIVE_PRIORITY_FRAME_ID
import org.example.project.scheduler.ui.DEEP_COPY_FRAME_ID
import org.example.project.ui.DEFAULT_CONFIGURATION_ROW_ID
import org.example.project.ui.REMINDER_DEFAULTS_FRAME_ID
import org.example.project.scheduler.domain.NewElementDefaults
import org.example.project.ui.ObjectWindowMemory
import org.example.project.ui.ObjectWindows
import org.example.project.ui.ObjectWindowsHost
import org.example.project.ui.TreeObject
import org.example.project.ui.COPY_CASCADE_PX
import org.example.project.ui.AlarmWindowSubject
import org.example.project.ui.CONFIGURATION_SEARCH_FRAME_ID
import org.example.project.ui.ADDED_CONFIGURATION_FRAME_ID
import org.example.project.ui.AddedElementsConfigurationWindow
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.ReminderConstraintEditWindow
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.ConfigurationSearchWindow
import org.example.project.ui.TransientPopupLayer
import org.example.project.ui.CalendarFloatingWindow
import org.example.project.ui.ReminderEditSeed
import org.example.project.ui.EDIT_LABEL_ALARM
import org.example.project.ui.EDIT_LABEL_REMINDER
import org.example.project.ui.EDIT_LABEL_SLEEP_SCHEDULE
import org.example.project.ui.EDIT_LABEL_TASK
import org.example.project.ui.EDIT_LABEL_TIMER
import org.example.project.ui.LocalPeriodRefusal
import org.example.project.ui.CalendarRecord
import org.example.project.ui.LINE_MOTION_PROBE_MILLIS
import org.example.project.ui.displayBoundsOf
import org.example.project.ui.withLineMotion
import org.example.project.ui.ChoresManagerWindow
import org.example.project.ui.HistoryManagerWindow
import org.example.project.ui.IconMenuButton
import org.example.project.ui.raiseOnPress
import org.example.project.ui.LateralMenu
import org.example.project.ui.TaskRelationsWindow
import org.example.project.ui.CalendarElementsMode
import org.example.project.ui.CalendarElementsWindow
import org.example.project.ui.calendarElementDrafts
import org.example.project.ui.ManualEntryEditWindow
import org.example.project.ui.CategoriesWindow
import org.example.project.ui.CategoryEditWindow
import org.example.project.ui.MessagePopup
import org.example.project.ui.PeriodEditWindow
import org.example.project.ui.LocalPeriodKindConfig
import org.example.project.ui.PlacedRecord
import org.example.project.ui.ReminderEditWindow
import org.example.project.ui.SimPauseScope
import org.example.project.ui.ShortcutsWindow
import org.example.project.ui.SleepWindow
import org.example.project.ui.DefaultSubtreeWindow
import org.example.project.ui.SearchWindow
import org.example.project.ui.ScreenPoint
import org.example.project.ui.TaskPickerMenu
import org.example.project.ui.TaskPickerOverlay
import org.example.project.ui.globalPointerLocation
import org.example.project.ui.TaskPalette
import org.example.project.ui.rememberTaskHues
import org.example.project.ui.TaskTreesWindow
import org.example.project.perf.Perf
import org.example.project.ui.PerfOverlay
import org.example.project.ui.TimeSimPanel
import org.example.project.ui.LocalTransientMenuHost
import org.example.project.ui.LocalWindowChromeMemory
import org.example.project.ui.LocalWindowFrameHost
import org.example.project.ui.WindowChrome
import org.example.project.ui.WindowChromeMemory
import org.example.project.ui.WindowFill
import org.example.project.ui.MINIMIZED_BAR_HEIGHT
import org.example.project.ui.WindowBar
import org.example.project.ui.TransientMenuHost
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.transientMenuDismissRoot

enum class OmniPage(val label: String) {
    TaskScheduler("Task Scheduler"),
}

/**
 * The lateral-menu windows. Where each sits in the app's one stacking order is
 * [WindowFrameHost.stackOrder], keyed by the enum's own name — which is also that window's frame id.
 *
 * [title] is what the window's head reads, for the one place that names a window while it is NOT open: the
 * Search window's "window" rows ([SearchDomain.WindowEntry]). An open window is named by its head itself.
 */
/** The calendar's layer bands as last drawn ([kindsAt]: the kinds of those covering an instant). */
private class CalendarLayersHolder {
    var records: List<CalendarRecord> = emptyList()

    /** The account's rules, the task panels the user placed and its tasks — what the bands are read through. */
    var config: PeriodKindConfig = PeriodKindConfig.DEFAULT
    var placed: List<TaskPanel> = emptyList()
    var tasks: Map<TaskId, Task> = emptyMap()

    /** The screen breaks as drawn (banked behind the line, predicted ahead): each span and the kind it is. */
    var breaks: List<Pair<TaskTimeRange, String>> = emptyList()

    /**
     * Every PERIOD the calendar draws — the Sleep bands, the hours before bed, the period boxes, the breaks — each
     * over the span it is DRAWN over: a period that gave way to a line at a screen is cut where it gave way, behind
     * the line and at it. [span] is what the calendar shows; outside it nothing was derived.
     */
    var periods: List<Pair<TaskTimeRange, String>> = emptyList()
    var span: TaskTimeRange? = null

    /**
     * The kinds of the periods the calendar draws at [atMillis] — or null outside what it shows, where only the stored
     * panels can answer ([SearchDomain.calendarKindsAt]).
     */
    fun periodKindsAt(atMillis: Long): Set<String>? {
        val shown = span ?: return null
        if (atMillis < shown.startEpochMillis || atMillis >= shown.endEpochMillis) return null
        return periods.filter { it.first.startEpochMillis <= atMillis && atMillis < it.first.endEpochMillis }.mapTo(HashSet()) { it.second }
    }

    /**
     * The layer kinds at [atMillis] with what the rules derive from them ("no screen" under both layers) — or none
     * where a task panel placed by hand lies that a derived kind refuses: the layers give way to it there
     * ([SchedulerDomain.layerRetractionCuts], asked at the one instant).
     */
    fun kindsAt(atMillis: Long): Set<String> {
        // A screen break there: its kind, what it carries and "no screen" — and the layers it lays, read where the
        // break is rather than at the minute the right-click was rounded to.
        val hit = SearchDomain.calendarBreaksAt(breaks, atMillis)
        val breakKinds = hit.flatMapTo(HashSet()) { SearchDomain.calendarBreakKinds(it.second, config) }
        val instants = (listOf(atMillis) + hit.map { maxOf(atMillis, it.first.startEpochMillis) }).distinct()
        return breakKinds + instants.flatMapTo(HashSet()) { layerKindsAt(it) }
    }

    private fun layerKindsAt(atMillis: Long): Set<String> {
        val layers =
            records
                .filter { it.range.startEpochMillis <= atMillis && atMillis < it.range.endEpochMillis }
                .mapNotNullTo(HashSet()) { record ->
                    record.layer?.let { if (record.layerFake) PeriodKinds.fakeLayerKind(it) else PeriodKinds.layerKind(it) }
                }
        if (layers.isEmpty()) return layers
        val here = listOf(TaskTimeRange(atMillis, atMillis + 1))
        val regions = layers.associateWith { here }
        val cuts =
            SchedulerDomain.layerRetractionCuts(
                regions,
                placed.filter { it.startEpochMillis <= atMillis && atMillis < it.endEpochMillis }
                    .map { it.taskId to TaskTimeRange(it.startEpochMillis, it.endEpochMillis) },
                config,
            ) { taskId, kind -> SchedulerDomain.periodRefuses(tasks, taskId, kind) }
        if (cuts.isNotEmpty()) return emptySet()
        return layers + SchedulerDomain.kindsDerivedFromLayers(regions, config).keys
    }
}

/** The placement rows that are not a window's layout: they are recorded by what they hold, or not at all. */
private val VIEW_ROWS: Set<String> =
    setOf(
        CustomMenuButtons.PLACEMENT_ID, CustomMenuButtons.TAB_TITLES_PLACEMENT_ID, CustomMenuButtons.WINDOW_COLORS_PLACEMENT_ID,
        CustomMenuButtons.WINDOW_SECTIONS_PLACEMENT_ID, "AppWindow",
    )

private const val MENU_KEY: String = org.example.project.scheduler.state.ExternalKeys.MENU
private const val SEARCH_KEY: String = org.example.project.scheduler.state.ExternalKeys.SEARCH
private const val WINDOW_KEY: String = org.example.project.scheduler.state.ExternalKeys.WINDOW

/** A window's layout as its unit holds it — its row without the configuration, which has a unit of its own. */
private fun layoutText(p: WindowPlacement): String =
    listOf(p.x, p.y, p.width, p.height, p.visible, p.fillWidth, p.fillHeight, p.minimized).joinToString(";")

private fun layoutOf(text: String): WindowPlacement? {
    val parts = text.split(';')
    if (parts.size != 8) return null
    return runCatching {
        WindowPlacement(
            x = parts[0].toFloat(), y = parts[1].toFloat(), width = parts[2].toFloat(), height = parts[3].toFloat(),
            visible = parts[4].toBooleanStrict(), fillWidth = parts[5].toBooleanStrict(), fillHeight = parts[6].toBooleanStrict(),
            minimized = parts[7].toBooleanStrict(),
        )
    }.getOrNull()
}

/** What a window's layout change was, as the History window names it. */
private fun layoutChangeLabel(before: WindowPlacement, after: WindowPlacement): String =
    when {
        !before.visible && after.visible -> "Open window"
        before.visible && !after.visible -> "Close window"
        before.minimized != after.minimized -> if (after.minimized) "Reduce window" else "Bring window back"
        before.fillWidth != after.fillWidth || before.fillHeight != after.fillHeight -> "Fill window"
        before.width != after.width || before.height != after.height -> "Resize window"
        else -> "Move window"
    }

/** What a Search window's configuration change was, as the History window names it. */
private fun searchChangeLabel(before: SearchDomain.Config, after: SearchDomain.Config): String =
    when {
        before.added != after.added ->
            when {
                after.added.isEmpty() -> "Clear added elements"
                after.added.size > before.added.size -> "Add to the added elements"
                else -> "Remove from the added elements"
            }
        before.query != after.query -> "Search text"
        before.kinds != after.kinds -> "Search types"
        before.filters != after.filters -> "Search filter"
        before.sorts != after.sorts -> "Search sorting"
        before.actionQuery != after.actionQuery -> "Actions filter"
        else -> "Search configuration"
    }

/**
 * PRD §6 (user rule 2026-10-01): the History Units of what `App` keeps outside the state. [record] dispatches one
 * ([SchedulerIntent.RecordExternal]) unless the start-up is still settling ([enabled]) or an undo is being put back
 * ([applying]); [onLayout] is a window row's change, and a close first hands the focus on ([focusAfterClose]) so the
 * unit is stamped with the window the close can be undone from.
 */
private class ViewHistoryRecorder {
    var enabled: Boolean = false
    private var applyingDepth: Int = 0
    var dispatch: (SchedulerIntent) -> Unit = {}
    var focusAfterClose: (String) -> Unit = {}

    fun applying(block: () -> Unit) {
        applyingDepth++
        try {
            block()
        } finally {
            applyingDepth--
        }
    }

    fun record(key: String, before: String?, after: String?, label: String, coalesceKey: String? = null) {
        if (!enabled || applyingDepth > 0 || before == after) return
        dispatch(SchedulerIntent.RecordExternal(key, before, after, label, coalesceKey))
    }

    fun onLayout(id: String, previous: WindowPlacement, next: WindowPlacement) {
        if (!enabled || applyingDepth > 0) return
        val before = layoutText(previous)
        val after = layoutText(next)
        if (before == after) return
        if (previous.visible && !next.visible) focusAfterClose(id)
        record(WINDOW_KEY + id, before, after, layoutChangeLabel(previous, next))
    }
}

/** The calendar's chrome after the window bar's Reset: maximized, not reduced (user rule 2026-10-01). */
private val CALENDAR_RESET_CHROME: WindowChrome = WindowChrome(WindowFill.Both, minimized = false)

/** How many frames a ☆ button's click waits for the window it creates to register, to give its tab the button's name. */
private const val TAB_TITLE_FRAMES: Int = 10

/**
 * **Whether the app has the focus**, as the PLATFORM says it — injected by the entry point (`docs/PLATFORMS.md`: a
 * platform difference is an injected seam, never a question about which build this is). Null where the entry point
 * injects nothing: the window's own `isWindowFocused` is then read, which also turns false while a menu or a
 * drop-down inside the app holds the keyboard.
 */
val LocalAppInFocus = androidx.compose.runtime.compositionLocalOf<Boolean?> { null }

private enum class FloatingWindow(val title: String) {
    Calendar("Calendar"),
    History("History"),
    Sleep("Sleep"),
    Alarms("Alarms"),
    TaskTrees("All task trees"),
    TaskRelations("Task relations"),
    Categories("Categories"),
    DefaultSubtree("Default sub-tree"),
    Shortcuts("Keyboard shortcuts"),
    Search("Search"),
    /** PRD §5: status, work offline and the account — what the top-right chip and button used to be. */
    Online("Online"),
    /** PRD §4: the task tree, a window like the others ([TASK_TREE_WINDOW_ID]). */
    TaskTree("Task tree"),
    /** Opened from the Search window; its name is its frame id ([CONFIGURATION_SEARCH_FRAME_ID]). */
    ConfigSearch("Search configurations"),
    /** Opened from the Search window's actions; its name is its frame id ([ADDED_CONFIGURATION_FRAME_ID]). */
    AddedConfig("Added elements configurations"),
    TimeSim("Time simulation"),
}

/** The lateral-menu window a frame id names: its own name, or a copy's (`Search#2`); null for anything else. */
/**
 * What a tree drawing's `onSet…Window(id)` asks of the windows of one kind, from the tree [template] names: open
 * the window on [id] (or bring it back), or — `null`, which the tree sends when one of its cells enters Edit
 * Mode — close every window of that kind open on an object of that tree.
 */
private fun <T> ObjectWindows<TreeObject<T>>.setFrom(template: Boolean, id: T?) {
    if (id == null) closeAll { it.template == template } else open(TreeObject(id, template))
}

private fun lateralWindowOf(id: String): FloatingWindow? {
    val base = id.substringBefore('#')
    val suffix = id.substringAfter('#', "")
    if (id.contains('#') && suffix.toIntOrNull() == null) return null
    return FloatingWindow.entries.firstOrNull { it.name == base && it != FloatingWindow.TimeSim }
}

// Debug "simulate pause + leap": pressing a break chip INSTANTLY jumps the sim clock forward by the whole
// break ([SimAppClock.leap]) — the now-line leaps to the break's end rather than gliding there over ~1 real
// second. This mirrors the real logic: a break is time in which the device's heartbeat (which runs on a
// fixed REAL cadence, so it never fires during an instant sim jump) simply observes no activity — i.e. pure
// inactivity — exactly the second inactivity path (the first being a heartbeat gap the running app detects).
// The engine's own release loops then live the jumped-over window as they would in production: the selected
// device(s) read as screen-inactive across it (finalizing their active session at the walk-away instant),
// and the post-leap teardown talks to the server to publish those sessions and re-derive the Inactivity bands.

// Real ms to let the forced-inactivity state settle around the instant jump: (1) before the leap, long enough
// for a linked phone to receive one pre-leap inactive frame (> the 250 ms time-link frame interval) and
// finalize its session at the walk-away instant, not after the jump; (2) after the leap, long enough for the
// engine's cue sweep to scan the jumped-over window while the screen still reads inactive (suppressing the
// look-away cues the user "slept through") before the session reopens. The clock's `reconfigured` bump wakes
// those loops within a display frame, so this is a comfortable margin, not a tight race.
private const val SIM_PAUSE_LEAP_SETTLE_MILLIS: Long = 350

// The DISPLAY now-line is not resampled on a timer: it is resampled when the SET OF RULES says the picture
// changes ([SchedulerDomain.displayResampleDelayMillis]) — the next boundary any derived panel names, or, for
// the one thing that follows the line affinely (a pose the line drags, the panel growing behind it, a live
// band ending at it), the moment it would have moved by one pixel. Between those the app sleeps: nothing in
// the past is a function of the line, and nothing in the future is either until a boundary is crossed.
//
// These two only bound that answer. The FLOOR keeps a boundary one millisecond away (a dragged pose starts at
// `t_p + 1`) from becoming a busy loop, and is finer under acceleration because the sim clock covers a
// boundary's worth of ground in a fraction of the real time. The CEILING is the safety net: it is the engine's
// own production cadence, so however wrong the boundary set turns out to be, no derivation here can go staler
// than it did when this was driven by the engine's tick — and one that is wrong costs a late redraw, never a
// wrong answer.
private const val DISPLAY_RESAMPLE_FLOOR_MILLIS: Long = 250
private const val DISPLAY_RESAMPLE_FLOOR_ACCEL_MILLIS: Long = 50
private const val DISPLAY_RESAMPLE_CEILING_MILLIS: Long = 30_000

// What the calendar's temporal resolution falls back to with no calendar open to report one: a whole minute
// per pixel, i.e. coarser than the ceiling, so nothing on screen is pinned to the line and the boundary alone
// governs.
private const val DEFAULT_NOW_LINE_MILLIS_PER_PIXEL: Long = 60_000

/**
 * The REAL milliseconds to sleep before the display is re-derived, from the answer
 * [SchedulerDomain.displayResampleDelayMillis] gave in the (possibly accelerated) clock's own time base.
 */
private fun displaySleepMillis(clock: AppClock, simDelayMillis: Long): Long {
    val speed = (clock as? SimAppClock)?.speed ?: 1.0
    val floor = if (speed != 1.0) DISPLAY_RESAMPLE_FLOOR_ACCEL_MILLIS else DISPLAY_RESAMPLE_FLOOR_MILLIS
    val real = if (speed > 0.0) (simDelayMillis / speed).toLong() else DISPLAY_RESAMPLE_CEILING_MILLIS
    return real.coerceIn(floor, DISPLAY_RESAMPLE_CEILING_MILLIS)
}

@Composable
@Preview
fun App(store: SchedulerStore? = createDefaultSchedulerStore(), host: AppSchedulerHost? = null) {
    MaterialTheme {
        // Perf: every pass through this body re-runs the whole display derivation below, so how often it runs
        // is half of what the derivation costs. The counter is what turns "the app feels heavy" into a number
        // — a display resample is ~1/s at the default zoom, so anything materially above that is a state read
        // in THIS scope being written by something that is not the clock (ADR 0009).
        Perf.count("recompose.App")
        var page by remember { mutableStateOf(OmniPage.TaskScheduler) }
        // Lateral-menu collapse: when true the whole menu (the page-nav dropdown and all) is not rendered — it
        // has slid fully off to the left; only the bookmark toggle remains, at the far-left edge, to pull it back.
        var menuCollapsed by remember { mutableStateOf(false) }

        // PRD §5 cross-device sync: when the local store can also hold sync bookkeeping (the SQLite store
        // implements SyncMetaStore), build the Supabase-backed engine. Web's localStorage store does not yet,
        // so sync is simply disabled there.
        val syncEngine =
            remember(store) {
                (store as? SyncMetaStore)?.let {
                    SchedulerSyncEngine(
                        RemoteSnapshotClient(),
                        it,
                        activeSessionStore = store as? ActiveSessionStore,
                        networkModeStore = store as? org.example.project.scheduler.persistence.NetworkModeStore,
                        startOffline = org.example.project.scheduler.sync.startOfflineRequested(),
                    )
                }
            }

        // The scheduler view-model is hoisted here so the floating calendar can read the Task Tree's
        // records (PRD §8) while the Task Scheduler screen drives the same state.
        val vm: TaskSchedulerViewModel =
            host?.vm ?: viewModel { TaskSchedulerViewModel(store = store, syncEngine = syncEngine) }
        val schedulerState by vm.state.collectAsState()
        // PRD §6/§9: the History window's scheduler-engine rows — this session's runs of the scheduler and
        // the rule set each one read. RAM-only (see [TaskSchedulerViewModel.schedulerRuns]).
        val schedulerRuns by vm.schedulerRuns.collectAsState()

        // Floating-window geometry/visibility, persisted LOCALLY ONLY (never synced, never a History Unit).
        // Absent on stores without the capability (e.g. web's localStorage) — placement then stays in-memory.
        // Loaded once; the open flags below seed their initial visibility from it, offsets persist on drag-end.
        val placementStore = remember(store) { store as? WindowPlacementStore }
        val initialPlacements = remember(placementStore) { placementStore?.loadPlacements().orEmpty() }
        fun savedOffset(id: FloatingWindow, default: Offset): Offset =
            initialPlacements[id.name]?.let { Offset(it.x, it.y) } ?: default
        // The size the window was last left at. `Size.Zero` on either axis means "never resized", which is
        // what makes the window open at its own default size ([AppWindowFrame]).
        fun savedSize(id: FloatingWindow): Size =
            initialPlacements[id.name]?.let { Size(it.width, it.height) } ?: Size.Zero
        fun savedVisible(id: FloatingWindow): Boolean = initialPlacements[id.name]?.visible == true
        // Every window's row as last written, so a write about ONE thing (the geometry, the chrome state, the
        // Search window's configuration) keeps the others: the store's upsert replaces the whole row. The one
        // funnel every placement write goes through. Plain, not Compose state — nothing is drawn from it.
        val placements = remember(placementStore) { initialPlacements.toMutableMap() }
        // PRD §6 (user rule 2026-10-01): what `App` keeps — a window's layout, a Search window's configuration, the
        // menu's buttons — recorded as History Units ([SchedulerIntent.RecordExternal]) and put back when one is undone.
        val viewHistory = remember { ViewHistoryRecorder() }
        // The calendar's layer bands as last drawn — the hatch read off the lock history, which is not in the state —
        // for the Search window's "is on the calendar at" filter.
        val calendarLayers = remember { CalendarLayersHolder() }
        // Every framed WINDOW registers here, which is what draws the bar of reduced windows along the
        // bottom of the app and what answers "does the tree still own the keyboard?" (see `WindowFrame.kt`).
        // Until the start-up has put the windows back, none of them claims the focus by opening (below).
        val windowFrames = remember { WindowFrameHost().also { it.claimOnOpen = false } }
        // Keyed by the window's FRAME id: the lateral-menu window's name, or a copy's (`Search#2`).
        // User rule 2026-10-07, a menu button's "Update": the window each button (by id) last opened or brought back
        // (by frame id), the layout that window had then — the reference for a button that kept none — and the lines
        // between the sections of the windows that have some, which those windows tell here. Compose-only.
        val menuButtonWindows = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
        val menuButtonOpenedLayouts = remember { androidx.compose.runtime.mutableStateMapOf<String, org.example.project.ui.WindowLayout>() }
        val searchSplits = remember { androidx.compose.runtime.mutableStateMapOf<String, org.example.project.ui.SearchSplits>() }
        var calendarConfigurationWidth by remember { mutableStateOf<Float?>(null) }
        var calendarSectionsHidden by remember { mutableStateOf<List<Boolean>?>(null) }
        fun updatePlacementById(id: String, change: (WindowPlacement) -> WindowPlacement) {
            val previous = placements[id] ?: WindowPlacement(x = 0f, y = 0f, visible = false)
            val next = change(previous)
            if (next == previous && id in placements) return
            placements[id] = next
            placementStore?.savePlacement(id, next)
            // A window that closes takes the windows opened from it with it (user rule 2026-10-06) — here, the one
            // write every close makes, and before the close hands the focus on (`onLayout`).
            if (previous.visible && !next.visible && id !in VIEW_ROWS) windowFrames.closeOpenedFrom(id)
            // A closed window is no button's any more: its frame id may be another window's tomorrow.
            if (previous.visible && !next.visible) {
                menuButtonWindows.entries.filter { it.value == id }.forEach {
                    menuButtonWindows.remove(it.key)
                    menuButtonOpenedLayouts.remove(it.key)
                }
                // …and its lines are not the next window's under that id.
                searchSplits.remove(id)
            }
            if (id !in VIEW_ROWS) viewHistory.onLayout(id, previous, next)
        }
        fun updatePlacement(id: FloatingWindow, change: (WindowPlacement) -> WindowPlacement) =
            updatePlacementById(id.name, change)
        fun persistPlacement(id: FloatingWindow, offset: Offset, size: Size, visible: Boolean) =
            updatePlacement(id) {
                it.copy(
                    x = offset.x,
                    y = offset.y,
                    width = size.width,
                    height = size.height,
                    visible = visible,
                    // A CLOSED window is not reduced: opening it again from the lateral menu shows it. Its
                    // filled axes are kept — the window reopens full width if it was left full width.
                    minimized = it.minimized && visible,
                )
            }
        // The lateral-menu windows come back filled and reduced as they were left, across a close and a
        // restart (popups.md, *Geometry is local-only view state*) — and so do the per-object windows kept
        // across restarts (below).
        val windowChromeMemory =
            remember(placements) {
                object : WindowChromeMemory {
                    // A lateral-menu window or one of its copies (`Search#2`), or a per-object window whose row
                    // names its object (`TaskEdit#3`) — never one that lives for the session only.
                    private fun isLateral(id: String) =
                        lateralWindowOf(id) != null || placements[id]?.config?.let(ObjectWindowKey::decode) != null

                    override fun saved(id: String): WindowChrome? =
                        if (!isLateral(id)) null
                        else placements[id]?.let { WindowChrome(WindowFill.of(it.fillWidth, it.fillHeight), it.minimized) }

                    override fun save(id: String, chrome: WindowChrome) {
                        if (!isLateral(id)) return
                        updatePlacementById(id) {
                            it.copy(
                                fillWidth = chrome.fill.fillsWidth,
                                fillHeight = chrome.fill.fillsHeight,
                                minimized = chrome.minimized,
                            )
                        }
                    }
                }
            }

        // The per-object windows about an object with a stable id are kept across restarts on a placement row of
        // their own, by frame id (`TaskEdit#3`), naming their object in `config` ([ObjectWindowKey]); the ones
        // open when the app stopped are reopened at startup, further down. Local-only, never synced.
        val objectWindowMemory =
            remember(placements) {
                object : ObjectWindowMemory {
                    override fun placement(frameId: String): Pair<Offset, Size>? =
                        placements[frameId]?.takeIf { it.visible }?.let { Offset(it.x, it.y) to Size(it.width, it.height) }

                    override fun opened(frameId: String, menuKey: String, offset: Offset) =
                        updatePlacementById(frameId) {
                            WindowPlacement(x = offset.x, y = offset.y, visible = true, config = menuKey)
                        }

                    override fun retargeted(frameId: String, menuKey: String) =
                        updatePlacementById(frameId) { it.copy(config = menuKey) }

                    override fun moved(frameId: String, offset: Offset, size: Size) =
                        updatePlacementById(frameId) {
                            it.copy(x = offset.x, y = offset.y, width = size.width, height = size.height)
                        }

                    // As a lateral-menu window's close: a closed window is not reduced.
                    override fun closed(frameId: String) =
                        updatePlacementById(frameId) { it.copy(visible = false, minimized = false) }
                }
            }

        // The head's ⧉ on a lateral-menu window (popups.md, *Duplicating a window*): the copies open now, by
        // frame id (`Search#2`). A copy is its own placement row — position, size, chrome, its own configuration
        // — so it comes back at startup exactly like the original. Closing one clears its row's `visible`.
        val windowCopies = remember(placements) {
            mutableStateListOf<String>().apply {
                addAll(
                    placements.filter { (id, row) -> '#' in id && row.visible && lateralWindowOf(id) != null }
                        .keys.sortedWith(compareBy({ it.substringBefore('#') }, { it.substringAfter('#').toIntOrNull() ?: 0 })),
                )
            }
        }

        // PRD §7: the buttons the user made at the bottom of the lateral menu, one per window (its head's ☆).
        // Local-only, on a placement row of their own — they name windows of this device, copies included, and
        // per-object windows by their object ([ObjectWindowKey]).
        var menuButtons by remember(placements) {
            mutableStateOf(CustomMenuButtons.decode(placements[CustomMenuButtons.PLACEMENT_ID]?.config))
        }
        // The ones drawn. A button whose window this build no longer has ("All tasks" and the list of reminders,
        // removed 2026-09-25) or whose object is gone is not shown — but kept, so an undo brings it back.
        // What an item that is a Search action needs to be drawn ([org.example.project.ui.MenuActionItem]): the
        // actions' handlers and the rows' openers, which are made further down, where the windows they embed are —
        // told here once made, at every pass (they close over the state of that pass).
        val menuActionContext =
            remember { mutableStateOf<Pair<AddedActionHandlers, (SearchDomain.Result) -> Unit>?>(null) }
        fun menuButtonShown(button: CustomMenuButton): Boolean =
            if (button.action != null) SearchDomain.AddedAction.entries.any { it.name == button.action }
            else if (button.control != null) org.example.project.ui.MenuControl.of(button.control) != null
            else ObjectWindowKey.decode(button.windowId)?.exists(schedulerState) ?: (lateralWindowOf(button.windowId) != null)
        // User rule 2026-10-08, the menu's "Customize": while on, a right-click on a control of the app that can stand
        // in the menu adds it there ([org.example.project.ui.MenuAddable]). Compose-only.
        val menuCustomizer = remember { org.example.project.ui.MenuCustomizer() }
        // The one whose title is being typed: a new button opens so, and so does "Rename". Compose-only.
        var editingMenuButton by remember { mutableStateOf<String?>(null) }
        // The menu's scroll, held here so a new button can be brought into view: the menu is scrolled to its
        // very end once the button is laid out (a frame after it is added — the end is not known before).
        val menuScroll = rememberScrollState()
        var menuButtonAdded by remember { mutableIntStateOf(0) }
        LaunchedEffect(menuButtonAdded) {
            if (menuButtonAdded == 0) return@LaunchedEffect
            withFrameNanos { }
            menuScroll.animateScrollTo(menuScroll.maxValue)
        }
        fun setMenuButtons(list: List<CustomMenuButton>) {
            val before = menuButtons
            menuButtons = list
            updatePlacementById(CustomMenuButtons.PLACEMENT_ID) { it.copy(config = CustomMenuButtons.encode(list)) }
            // A rename is typed: one unit for the run of its keystrokes.
            val renameOnly = before.map { it.id } == list.map { it.id } && before.map { it.copy(title = "") } == list.map { it.copy(title = "") }
            viewHistory.record(
                MENU_KEY, CustomMenuButtons.encode(before), CustomMenuButtons.encode(list),
                if (renameOnly) "Rename a menu button" else "Menu buttons",
                coalesceKey = if (renameOnly) "menu/rename" else null,
            )
        }

        // PRD §5 Persistence: flush any pending debounced write when the app/composition is torn down,
        // so a change made within the debounce window survives a normal close.
        DisposableEffect(vm) {
            onDispose { vm.flush() }
        }

        // Time source: a virtual clock when the debug time-sim flag is on (so deadlines, the calendar
        // now-line and day rollovers can be exercised in seconds), else the real wall clock.
        val simClock = remember { SimAppClock() }
        val clock: AppClock = if (DebugFlags.TIME_SIMULATION) simClock else SystemAppClock
        // Debug time-link (docs/PAUSE_CUE_DELIVERY.md "Testing C"): on the desktop under time-sim, stream this
        // accelerated clock to a plugged-in phone (adb) so both share one `now`. Null off desktop / when
        // sim is off; `linkedCount` (-1 = no server) drives the panel's "phone link" status.
        var timeLink by remember { mutableStateOf<TimeLink?>(null) }
        DisposableEffect(Unit) {
            val link = if (DebugFlags.TIME_SIMULATION) startTimeLink(simClock) else null
            timeLink = link
            onDispose { link?.close(); timeLink = null }
        }
        val timeLinkCount = timeLink?.linkedCount?.collectAsState()?.value ?: -1
        // Debug "simulate pause + leap": the in-flight leap (see the TimeSimPanel below); clicks while one is
        // running are ignored so two leaps never fight over the clock speed / forced-inactivity flags.
        var pauseLeapJob by remember { mutableStateOf<Job?>(null) }
        // PRD §6: History Units are timestamped from the same clock the rest of the app reads, so under
        // time simulation their times match the (accelerated) calendar.
        SideEffect {
            SchedulerReducer.clock = clock
            // Flag changes made while the debug clock is diverged from real time (accelerated, paused or
            // leaped) so the next app start reverts them. Production (no time-sim) is never tainted.
            SchedulerReducer.debugTainting = {
                DebugFlags.TIME_SIMULATION &&
                    (simClock.speed != 1.0 ||
                        abs(simClock.nowMillis() - SystemAppClock.nowMillis()) > 1_000L)
            }
        }
        val tz = remember { TimeZone.currentSystemDefault() }

        // The scheduling engine owns the advancing `now` and drives the §9 reschedules and the
        // §11/§13/§15 notifications / voice cues. On Android the foreground service supplies an
        // already-started engine via [host] (so the service is the single source of truth); on
        // desktop/web/iOS it is created here and started for the composition's lifetime.
        val engineScope = rememberCoroutineScope()
        val engine: SchedulerEngine = remember(host) {
            host?.engine
                ?: SchedulerEngine(
                    vm = vm,
                    clock = clock,
                    scope = engineScope,
                    // [engineScope] is the composition's, i.e. the frame loop. Everything the engine does
                    // there is a cheap edge except the fill inside a re-plan, which is 25-80 ms and used to
                    // drop four frames every time a rule change settled; that one job goes to a background
                    // dispatcher (see SchedulerEngine.planDispatcher).
                    planDispatcher = Dispatchers.Default,
                    // `docs/scheduler_score.md` § *Degradation*: spend what the progressive pace leaves on reaching
                    // the best score (SchedulerEngine.planSearch).
                    planSearch = true,
                    tz = tz,
                    sleepGapStore = store as? DeviceSleepGapStore,
                    sleepScanCheckpoint = store as? SleepScanCheckpointStore,
                    declaredAwayStore = store as? DeclaredAwayStore,
                    frozenBreakStore = store as? org.example.project.scheduler.persistence.FrozenScreenBreakStore,
                    activeSessionStore = store as? ActiveSessionStore,
                    pauseCue = vm.pauseCue,
                    // `docs/invariants/scheduler.md` § *One device plans*: the account's broadcast channel.
                    schedulerPeers = vm.schedulerPeers,
                    // PRD §15: the OS-scheduled local cue seam for the engine App() builds itself (iOS delivers
                    // via UNUserNotificationCenter; desktop/web are inert). Android does not reach here — it
                    // injects an AlarmManager seam via SchedulerHolder.
                    scheduleLocalPauseCue = ::scheduleLocalPauseCuePlatform,
                    localPauseCueDelivery = localPauseCueDeliveryPlatform,
                    // PRD §18 Alarms: ring on this device too. The desktop has no OS alarm clock, so the
                    // engine rings from its now-line sweep and this seam plays the sound (Android does not
                    // reach here — SchedulerHolder injects AlarmRingService instead).
                    ringAlarm = { armed ->
                        // PRD §11: the row's own sound channel decides whether there is a sound at all, and
                        // which one — a ring with it off is the vibration alone (nothing, on a desktop).
                        ringAlarmPlatform(
                            armed.label,
                            armed.soundSeconds,
                            armed.alert.tone.takeIf { armed.alert.sound },
                            armed.alert.vibrate,
                        )
                    },
                )
        }
        LaunchedEffect(engine) { if (host == null) engine.start() }
        // PRD §15: hook this device's platform activity signal (desktop OS session lock/unlock) so the engine
        // re-samples presence the moment it flips, instead of at the next minute beat. No-op on Android (the
        // service wires it directly) and iOS/web (no such signal).
        LaunchedEffect(engine) { installPlatformActivityListener { engine.onPlatformActivityChanged() } }
        // PRD §7 the task picker: the standing request to have the menu on screen, or null while it is not.
        // A flow rather than plain Compose state because the only thing that ever writes it is the hot-key
        // callback below, which runs on the claim's own dispatch thread and not on the frame loop.
        val taskPickerRequest = remember { MutableStateFlow<TaskPickerRequest?>(null) }
        val taskPicker by taskPickerRequest.collectAsState()
        // PRD §7/§15: the system-wide chords (Ctrl+Shift+Alt+A "I'm away", Ctrl+Shift+Alt+E "Look away now",
        // Ctrl+Shift+Alt+Z "Switch task", Ctrl+Shift+Alt+T "Choose the task to do now"), driving exactly the
        // same engine seams the left-menu buttons do.
        // Claimed from the OS rather than handled in Compose because they are pressed precisely when OmniApp
        // is NOT the focused window — the user is walking away from, resting their eyes in the middle of, or
        // deciding they want off the current task inside whatever they were working in — and a focus-scoped
        // handler would only ever fire when the button is already one click away. The
        // claim swallows the chord so no other application acts on the same press. Desktop-only; inert on
        // Android/iOS.
        // Keyed on the bindings too (PRD §7): rebinding a chord in the keyboard-shortcuts window has to reach
        // the OS claim without a restart, and the seam's later calls are exactly the re-registration path.
        LaunchedEffect(engine, schedulerState.shortcutBindings) {
            installGlobalHotkeys(schedulerState.shortcutBindings) { shortcut ->
                // The receipt FIRST, and unconditionally: the chord is struck with another window in front, so
                // without it "OmniApp never got the press" and "OmniApp got it and had nothing to do" look
                // identical from where the user is sitting. It is posted here, at the OS seam, and not inside
                // the engine seams below — the lateral-menu buttons drive those same seams, and a click the
                // user just made in a window they are looking at needs no confirming.
                engine.announceShortcutReceived(shortcut)
                when (shortcut) {
                    GlobalShortcut.ToggleAway -> engine.setUserAway(!engine.userAway.value)
                    GlobalShortcut.LookAwayNow -> engine.restartLookAway()
                    GlobalShortcut.SwitchTask -> engine.forceTaskSwitch()
                    // PRD §7: the picker. The pointer is read HERE, at the press, and not when the menu
                    // composes — the two are a frame or more apart and the hand does not stop moving in
                    // between. Re-striking the chord while the menu stands re-anchors it at the new
                    // pointer, which is why the request carries the instant as well: it is what makes the
                    // second press a different value.
                    GlobalShortcut.PickTask ->
                        taskPickerRequest.value =
                            TaskPickerRequest(globalPointerLocation(), clock.nowMillis())
                    // PRD §11: the same lever as the lateral menu's Notifications switch. Struck from
                    // whatever window the notification just interrupted, which is why it is system-wide.
                    GlobalShortcut.ToggleNotifications ->
                        engine.setNotificationsEnabled(!vm.state.value.notificationsEnabled, fromChord = true)
                }
            }
        }
        // PRD §15 / ARCHITECTURE.md §8 (iOS APNs, reqs #2/#6): give the platform's native push layer its two
        // callbacks — publish this phone's APNs token, and route a received pause-cue push into the engine.
        // A no-op off iOS (Android uses its FirebaseMessagingService instead; desktop/web have no push layer).
        LaunchedEffect(engine) {
            installPauseCuePushBridge(
                registerApnsToken = { token ->
                    engineScope.launch { vm.pauseCue?.registerPushToken("phone", "apns", token) }
                },
                onRemotePush = { action, dueAtIso, voiceCue ->
                    val dueMillis =
                        dueAtIso?.takeIf { it.isNotBlank() }
                            ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                    engine.onPauseCuePush(action, dueMillis, voiceCue)
                },
                // PRD §15 scenario #3: the phone became active — re-claim the account's last phone (the DB
                // trigger then cancels the previous phone's cue). No-op off iOS.
                onForegrounded = { engine.onAppForegrounded() },
            )
        }
        // The instant the DISPLAY is derived at. Everything below that is a function of the clock — the sleep
        // and screen-break projections, the derived grey bands, the layer regions, the reminder horizon,
        // $t_goal$ — reads THIS value and is re-derived on every new one, so how often it moves is how often
        // this composable does an O(visible window) pass (ADR 0009).
        //
        // It does not move on a timer. It moves when the set of rules says the picture changes — see the
        // sampler at the bottom of this body, which is where the next value comes from and why.
        //
        // What MOVES between two of those instants is not re-derived either. The now-line is one number, and
        // every edge that follows it (a pose the line drags, the panel growing behind it) is read ONCE as
        // following it ([withLineMotion]); the calendar moves all of them on its own frame clock, in the layout
        // and draw phases (`rememberFrameNowMillis`, `timelineSpan`), so they glide however seldom this runs.
        var nowMillis by remember(clock) { mutableLongStateOf(clock.nowMillis()) }
        // PRD §15 device-sleep gaps: past pauses drawn as greyed "Inactivity" bands (display-only; see the engine).
        val inactivityGaps by engine.inactivityGaps.collectAsState()
        // PRD §15/§17: start of this device's open active session (null while inactive) — carves the "Sleep" band
        // to the now-line as the user keeps working through a scheduled sleep window (display-only, non-syncing).
        val activeSince by engine.activeSince.collectAsState()
        // PRD §15: end of this device's last finalized session — the locally-observed start of a pause the
        // derived gaps don't cover yet. Unioned in below so the "Inactivity" band grows live behind an
        // advancing now-line (display-only, non-syncing; see SchedulerDomain.displayInactivityGaps).
        val inactiveSince by engine.inactiveSince.collectAsState()
        // The mode-1 stretch the line is walking and has not banked: taken live from the user's "no screen" periods.
        val atScreenSince by engine.atScreenSince.collectAsState()
        // PRD §15: every stored active session (this device's own + the peers' rows pulled by the Sync button) —
        // the full unclipped session history. Used both to segment past task panels by which devices were open
        // (hover bubble + dashed separators) and to re-derive the display Inactivity bands over any focused past
        // week (PRD §12/§15 — the engine's own [inactivityGaps] only reaches back 168h).
        val activeSessions by engine.activeSessions.collectAsState()
        // PRD §15: whether the user declared they are away from THIS device (left-menu "I'm away" button).
        val userAway by engine.userAway.collectAsState()
        // `docs/scheduler_requirements.md` § *$now line$ 3 modes*: and whether any OTHER device of the account
        // has it on — the mode is a quantifier over the account, not a property of this install.
        val accountAway by engine.accountAway.collectAsState()
        // PRD §8 + the same section: the stretches this device's button was ON for. The OS log cannot show
        // them (the machine stays unlocked while the user is away from it), so they are the only source there
        // is for the layer over a declared absence — and a stretch where every device of the account is either
        // locked or declared away IS mode 3, which the calendar has to draw as a stretch carrying both layers.
        val declaredAwaySpans by engine.declaredAwaySpans.collectAsState()
        val declaredAwaySince by engine.declaredAwaySince.collectAsState()
        // `side-dev/README.md` § *3 Dynamic Restrictive Period*: what the DEVICES observed about whether
        // anybody was at a screen — the engine's ONE cached reading, the same value the reducer's fills and
        // the cue sweep are given. It reaches the recurrence bars below as the restrictive periods it is
        // ([SchedulerDomain.observedNoScreenPeriods]), so a pause that has ENDED still bars the breaks the
        // README says it does. The display's own wider-window copy further down is for the panel clipping and
        // the hatching, which are asked over the whole scrolled span; the bars deliberately share the engine's,
        // or the calendar would draw a break at an instant the app does not announce one at.
        val observedNoScreenEvidence by engine.noScreenEvidence.collectAsState()
        // `docs/scheduler_requirements.md` § *frozen past*: the screen breaks the line has banked — the calendar
        // draws the past from them and every placement continues from their front.
        val frozenBreaks by engine.frozenBreaks.collectAsState()
        // PRD §7/§15: what claim the OS granted the system-wide chords — shown in the keyboard-shortcuts window,
        // since a chord another application already owns is otherwise indistinguishable from a broken app.
        val globalHotkeyClaim by GlobalHotkeys.claim.collectAsState()

        // PRD §7 calendar state, hoisted so the lateral menu (month grid) and the popup week view
        // stay in sync. "today" follows the (possibly simulated) clock so day rollovers are testable.
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).date
        var calendarOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Calendar)) }
        // Bumped by the sampler at the bottom of this body, purely so its own effect restarts: the clock can
        // legitimately hand back the same instant (a paused sim clock), and a key that did not move would
        // leave the loop with nothing to wake it.
        var displayResampleTick by remember(clock) { mutableIntStateOf(0) }
        // How many milliseconds the now-line takes to cross ONE PIXEL at the calendar's current zoom — the
        // display's own temporal resolution, reported up by the grid because the zoom is Compose-only state
        // that lives there. It is what bounds the resample rate for whatever is pinned to the line: at the
        // default zoom the line crosses a pixel every ~75 s, at the ceiling every ~0.6 s, and redrawing more
        // often than that redraws the picture already on screen.
        var nowLineMillisPerPixel by remember { mutableLongStateOf(DEFAULT_NOW_LINE_MILLIS_PER_PIXEL) }
        // Every right-click MENU registers here: the host keeps at most one open and closes it on the first
        // press outside it. Windows do not — nothing takes a window away any more (see `PopupWindows.kt`).
        val transientMenus = remember { TransientMenuHost() }
        // The window bar's tab names (user rule 2026-10-01): loaded once, keeping only the windows that come back —
        // a row the user closed since is not visible — and written whenever a button names a tab.
        remember(placementStore) {
            val visible = CustomMenuButtons.decodeTabTitles(placements[CustomMenuButtons.TAB_TITLES_PLACEMENT_ID]?.config)
                .filterKeys { placements[it]?.visible == true }
            windowFrames.tabTitles.putAll(visible)
            true
        }
        fun saveTabTitles() =
            updatePlacementById(CustomMenuButtons.TAB_TITLES_PLACEMENT_ID) {
                it.copy(config = CustomMenuButtons.encodeTabTitles(windowFrames.tabTitles.toMap()))
            }
        // The windows' colours (user rule 2026-10-06; [WindowFrameHost.colors]): loaded once, for the windows that
        // come back — so each comes back in its colour — and written whenever a window that opens is given one.
        remember(placementStore) {
            val visible = CustomMenuButtons.decodeWindowColors(placements[CustomMenuButtons.WINDOW_COLORS_PLACEMENT_ID]?.config)
                .filterKeys { placements[it]?.visible == true }
            windowFrames.colors.putAll(visible)
            windowFrames.onColorAssigned = {
                updatePlacementById(CustomMenuButtons.WINDOW_COLORS_PLACEMENT_ID) {
                    it.copy(config = CustomMenuButtons.encodeWindowColors(windowFrames.colors.toMap()))
                }
            }
            true
        }
        // The windows' sections (anomaly 2026-10-08; [org.example.project.ui.WindowSections]): the lines between them
        // and which are retracted, loaded once for the windows that come back and written whenever one is changed.
        remember(placementStore) {
            CustomMenuButtons.decodeWindowSections(placements[CustomMenuButtons.WINDOW_SECTIONS_PLACEMENT_ID]?.config)
                .forEach { (id, kept) ->
                    if (id == FloatingWindow.Calendar.name) {
                        kept.splits.firstOrNull()?.let { calendarConfigurationWidth = it }
                        if (kept.hidden.isNotEmpty()) calendarSectionsHidden = kept.hidden
                    } else if (placements[id]?.visible == true && kept.splits.size == 2) {
                        searchSplits[id] =
                            org.example.project.ui.SearchSplits(
                                kept.splits[0], kept.splits[1],
                                kept.hidden.getOrNull(0) ?: false, kept.hidden.getOrNull(1) ?: false, kept.hidden.getOrNull(2) ?: false,
                            )
                    }
                }
            true
        }
        fun saveWindowSections() =
            updatePlacementById(CustomMenuButtons.WINDOW_SECTIONS_PLACEMENT_ID) {
                val search =
                    searchSplits.mapValues { (_, s) ->
                        org.example.project.ui.WindowSections(listOf(s.left, s.topRight), listOf(s.searchHidden, s.actionsHidden, s.addedHidden))
                    }
                val calendar =
                    org.example.project.ui.WindowSections(listOfNotNull(calendarConfigurationWidth), calendarSectionsHidden.orEmpty())
                        .takeIf { it.splits.isNotEmpty() || it.hidden.isNotEmpty() }
                it.copy(
                    config = CustomMenuButtons.encodeWindowSections(search + listOfNotNull(calendar?.let { c -> FloatingWindow.Calendar.name to c })),
                )
            }
        // The per-object windows (`popups.md`): each is about ONE object, and each kind is the list of its open
        // windows ([ObjectWindows]) — opening one on another object opens a second window, and the first stays.
        // All are hoisted here so they draw on the top layer, above whichever window stands over the tree. The
        // ones a TREE cell opens carry which tree the object is in ([TreeObject]): the account's or the default
        // sub-tree's, where the same id means something else — it decides the state they read and where their
        // intents go. Each kind whose object has a stable id gives its windows a ☆ ([ObjectWindowKey]).
        fun <T> treeKey(kind: ObjectWindowKey.Kind, id: (T) -> String): (TreeObject<T>) -> String =
            { ObjectWindowKey(kind, id(it.id), it.template).encode() }
        // PRD §5: a sub-list's priority-weight table (a click on a percentage) and a cell's relative-priority
        // window (the percentage's right-click menu).
        val weightWindows = remember {
            ObjectWindows(treeKey<CellListId>(ObjectWindowKey.Kind.PriorityWeights) { it.value }, objectWindowMemory)
        }
        val relativeWindows = remember {
            ObjectWindows(treeKey<CellId>(ObjectWindowKey.Kind.RelativePriority) { it.value }, objectWindowMemory)
        }
        // PRD §13: a task's "edit task" window — the DEFAULT SUB-TREE's tasks only since 2026-10-01 (an account task is
        // edited from a Search window: `openElementSearch`) — and a cell's "deep copy" window.
        val taskEditWindows = remember {
            ObjectWindows(treeKey<TaskId>(ObjectWindowKey.Kind.TaskEdit) { it.value }, objectWindowMemory)
        }
        val deepCopyWindows = remember {
            ObjectWindows(treeKey<CellId>(ObjectWindowKey.Kind.DeepCopy) { it.value }, objectWindowMemory)
        }
        // PRD §5: a category's own window — the default sub-tree's only (its rules are scoped in the template's cells);
        // an account category is edited from a Search window.
        val categoryWindows = remember {
            ObjectWindows(treeKey<CategoryId>(ObjectWindowKey.Kind.CategoryEdit) { it.value }, objectWindowMemory)
        }
        // PRD §14 "constrained in": the picker of the reminder (by id) a Search window's reminder editor asked it for.
        val reminderConstraintWindows = remember { ObjectWindows<String>() }
        // User rule 2026-10-01: an element's own edit window is a Search window holding that element alone — its
        // actions are that window's. Set once `openNewWindow` exists (below); the ☆ buttons reach it through
        // [openObjectWindow], which is declared before it.
        var openElementSearch: (SearchDomain.Kind, String) -> Unit = { _, _ -> }
        // PRD §14/§18: the default configuration of a new alarm, timer and reminder — one window each, opened from
        // the bottom of any such element's editor. The subject is the kind ([ObjectWindowKey.Kind.AlarmDefaults]…).
        val defaultsWindows = remember {
            ObjectWindows<ObjectWindowKey.Kind>({ ObjectWindowKey(it, "").encode() }, objectWindowMemory)
        }
        // A per-object window named by its key: opened on its object (or brought back when one is open on it) — or,
        // at startup, reopened under the [number] it had. The one reading of a key, for the ☆ buttons and the
        // restore alike.
        fun openObjectWindow(key: ObjectWindowKey, number: Int? = null) {
            fun <T : Any> ObjectWindows<T>.openOn(subject: T) = if (number == null) open(subject) else restore(number, subject)
            // An element's own window is gone (user rule 2026-10-01): its ☆ opens the Search window holding it, and a
            // window of it that was open when the app last stopped does not come back.
            fun search(kind: SearchDomain.Kind) { if (number == null) openElementSearch(kind, key.id) }
            when (key.kind) {
                ObjectWindowKey.Kind.TaskEdit ->
                    if (key.template) taskEditWindows.openOn(TreeObject(TaskId(key.id), true)) else search(SearchDomain.Kind.Task)
                ObjectWindowKey.Kind.CategoryEdit ->
                    if (key.template) categoryWindows.openOn(TreeObject(CategoryId(key.id), true)) else search(SearchDomain.Kind.Category)
                ObjectWindowKey.Kind.PeriodKindEdit -> search(SearchDomain.Kind.RestrictivePeriod)
                ObjectWindowKey.Kind.PriorityWeights -> weightWindows.openOn(TreeObject(CellListId(key.id), key.template))
                ObjectWindowKey.Kind.RelativePriority -> relativeWindows.openOn(TreeObject(CellId(key.id), key.template))
                ObjectWindowKey.Kind.DeepCopy -> deepCopyWindows.openOn(TreeObject(CellId(key.id), key.template))
                ObjectWindowKey.Kind.Alarm -> search(SearchDomain.Kind.Alarm)
                ObjectWindowKey.Kind.Timer -> search(SearchDomain.Kind.Timer)
                ObjectWindowKey.Kind.Chrono -> search(SearchDomain.Kind.Chrono)
                ObjectWindowKey.Kind.Reminder -> search(SearchDomain.Kind.Reminder)
                ObjectWindowKey.Kind.AlarmDefaults, ObjectWindowKey.Kind.TimerDefaults, ObjectWindowKey.Kind.ReminderDefaults ->
                    defaultsWindows.openOn(key.kind)
            }
        }
        // Startup: the per-object windows open when the app last stopped come back, on their objects, under their
        // numbers — so where they stood and how they were left (their rows) come back with them. One whose object
        // is gone since closes itself as it draws, as it would have while open.
        remember(placements) {
            placements.entries
                .filter { (_, row) -> row.visible }
                .mapNotNull { (frameId, row) ->
                    val key = row.config?.let(ObjectWindowKey::decode) ?: return@mapNotNull null
                    val number = frameId.removePrefix(key.kind.frameBase + "#").toIntOrNull() ?: return@mapNotNull null
                    Triple(key, number, frameId)
                }
                .sortedBy { it.second }
                .forEach { (key, number, _) -> openObjectWindow(key, number) }
        }
        // The one message the app has to say back to a gesture it could not carry out — today only PRD §8's
        // "go to task tree" on a panel whose task no cell holds. One notice at a time.
        var appMessage by remember { mutableStateOf<String?>(null) }
        // PRD §5: the account tree's weight and relative-priority windows close when any of its cells enters
        // Edit Mode (their sub-list typing context is gone).
        LaunchedEffect(schedulerState.editSession) {
            if (schedulerState.editSession != null) {
                weightWindows.closeAll { !it.template }
                relativeWindows.closeAll { !it.template }
            }
        }
        // PRD §5/§6 History Manager: whether the floating history window is open (local UI state).
        var historyManagerOpen by remember { mutableStateOf(savedVisible(FloatingWindow.History)) }
        // Sleep schedule window: whether the floating sleep-settings window is open (local UI state).
        var sleepWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Sleep)) }
        // PRD §18 Alarms: whether the floating alarms window is open (local UI state; the alarms themselves
        // are authoritative synced state).
        var alarmWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Alarms)) }
        // All task trees: whether the floating task-tree timeline window is open (local UI state; the trees
        // and their dates are authoritative synced state).
        var taskTreesWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.TaskTrees)) }
        // PRD §5 Task relations: whether the list of (task, relational target) pairs is open (local UI
        // state; the pairs the user has kept or struck off are authoritative synced state).
        var taskRelationsWindowOpen by
            remember { mutableStateOf(savedVisible(FloatingWindow.TaskRelations)) }
        // PRD §5 Categories: whether the account's list of categories is open (local UI state; the
        // categories and their rules are authoritative synced state).
        var categoriesWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Categories)) }
        // PRD §7 Search: whether the search window is open (local UI state).
        var searchWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Search)) }
        // Where the lateral menu's collapse toggle stands — the one thing drawn over every window's head.
        val menuToggleBounds = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        // PRD §5: the Online window, and what it and its menu button read — the sync state, the account and the
        // device's "work offline" choice (null where this build has no sync).
        var onlineWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Online)) }
        var onlineOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Online, Offset(140f, -60f))) }
        var onlineSize by remember { mutableStateOf(savedSize(FloatingWindow.Online)) }
        LaunchedEffect(onlineWindowOpen) {
            persistPlacement(FloatingWindow.Online, onlineOffset, onlineSize, onlineWindowOpen)
        }
        val syncStateValue = vm.syncState?.collectAsState()?.value
        val accountValue = vm.account?.collectAsState()?.value
        val offlineValue = vm.offline?.collectAsState()?.value
        // PRD §4: the task tree's window. OPEN on a first run (nothing kept yet), as it was left after that.
        var taskTreeWindowOpen by remember {
            mutableStateOf(initialPlacements[FloatingWindow.TaskTree.name]?.visible ?: true)
        }
        var taskTreeOffset by remember { mutableStateOf(savedOffset(FloatingWindow.TaskTree, Offset.Zero)) }
        var taskTreeSize by remember { mutableStateOf(savedSize(FloatingWindow.TaskTree)) }
        LaunchedEffect(taskTreeWindowOpen) {
            persistPlacement(FloatingWindow.TaskTree, taskTreeOffset, taskTreeSize, taskTreeWindowOpen)
        }
        // PRD §7 Search: the query, the checked kinds and the filters, kept here rather than in the window —
        // the Configuration Search window edits the same configuration, and it outlives both windows — and on
        // this device's placement row, so they outlive the app. Local-only view state.
        // One per Search window — the original and each copy, by frame id — decoded from its row on first read.
        val searchConfigs = remember { mutableStateMapOf<String, SearchDomain.Config>() }
        // The configuration each Search window OPENED with — what its Reset goes back to before the default one
        // (`SearchDomain.resetConfig`). Compose-only: a window still open at a restart opened with what it held then.
        val searchOpenedConfigs = remember { mutableStateMapOf<String, SearchDomain.Config>() }
        fun searchConfigOf(id: String): SearchDomain.Config =
            searchConfigs[id] ?: (SearchDomain.Config.decode(placements[id]?.config) ?: SearchDomain.Config())
                .also {
                    searchConfigs[id] = it
                    searchOpenedConfigs[id] = it
                }
        fun setSearchConfig(id: String, config: SearchDomain.Config) {
            val before = searchConfigOf(id)
            if (config == before) return
            searchConfigs[id] = config
            updatePlacementById(id) { it.copy(config = config.encode()) }
            // The text fields are typed: one unit for the run of a field's keystrokes.
            val typed =
                when (config) {
                    before.copy(query = config.query) -> "query"
                    before.copy(actionQuery = config.actionQuery) -> "actions"
                    else -> null
                }
            viewHistory.record(
                SEARCH_KEY + id, before.encode(), config.encode(), searchChangeLabel(before, config),
                coalesceKey = typed?.let { SEARCH_KEY + id + "/" + it },
            )
        }
        // The Configuration Search window: open or not, and — per window, by frame id — its OWN configuration
        // (which configurations it lists, and which Search window it edits).
        var configSearchWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.ConfigSearch)) }
        // The Added elements configurations window: open or not. Its own configuration is a ConfigurationSearch
        // too (which actions it lists, and which Search window's added elements they act on), kept alike.
        var addedConfigWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.AddedConfig)) }
        val configSearches = remember { mutableStateMapOf<String, SearchDomain.ConfigurationSearch>() }
        fun configSearchOf(id: String): SearchDomain.ConfigurationSearch =
            configSearches[id] ?: (SearchDomain.ConfigurationSearch.decode(placements[id]?.config)
                ?: SearchDomain.ConfigurationSearch()).also { configSearches[id] = it }
        fun setConfigSearch(id: String, own: SearchDomain.ConfigurationSearch) {
            if (own == configSearches[id]) return
            configSearches[id] = own
            updatePlacementById(id) { it.copy(config = own.encode()) }
        }
        // PRD §4 Default sub-tree: whether the floating template window is open (local UI state; the template
        // and the "is it applied" switch are authoritative synced state).
        var defaultSubtreeWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.DefaultSubtree)) }
        // PRD §7 Keyboard shortcuts: whether the floating reference list of every chord is open (local UI state).
        var shortcutsWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Shortcuts)) }

        // The windows are siblings in one Box, so their paint order would be their declaration order. The
        // stacking order that overrides it is [WindowFrameHost.stackOrder] — ONE order for every window of
        // the app, lateral-menu and per-object alike, raised when a window opens and on every press inside
        // it. Each framed window applies its own place in it (`AppWindowFrame`), so the only thing left
        // here is the debug time-sim panel, which wears no frame.
        // PRD §6/§7: the window of the app a floating window IS — the one the focus lands on when the user
        // presses in it, and so the one every change made there is stamped with and every history chord in it
        // is relative to. The one place the mapping lives, so a window added to the app is added to both
        // enums or to neither.
        fun historyWindowOf(id: FloatingWindow): HistoryWindow? = when (id) {
            FloatingWindow.Calendar -> HistoryWindow.Calendar
            FloatingWindow.History -> HistoryWindow.History
            FloatingWindow.Sleep -> HistoryWindow.Sleep
            FloatingWindow.Alarms -> HistoryWindow.Alarms
            FloatingWindow.TaskTrees -> HistoryWindow.TaskTrees
            FloatingWindow.TaskRelations -> HistoryWindow.TaskRelations
            FloatingWindow.Categories -> HistoryWindow.Categories
            FloatingWindow.DefaultSubtree -> HistoryWindow.DefaultSubtree
            FloatingWindow.Shortcuts -> HistoryWindow.Shortcuts
            FloatingWindow.Search -> HistoryWindow.Search
            FloatingWindow.ConfigSearch -> HistoryWindow.ConfigSearch
            FloatingWindow.AddedConfig -> HistoryWindow.AddedConfig
            FloatingWindow.TaskTree -> HistoryWindow.Tree
            FloatingWindow.Online -> HistoryWindow.Online
            // The debug time-simulation panel is not a window of the app and commits nothing.
            FloatingWindow.TimeSim -> null
        }
        // PRD §6 (user rule 2026-10-01): the window a frame id IS, for the stamp — a lateral-menu window (or a copy of
        // one) by its own mapping, every other window by its frame id's base. Null for what is not a window of the app
        // (the debug panel). The instance is what follows the base (`#2`, a task tree's `/id`).
        fun historyWindowOfFrame(frameId: String): Pair<HistoryWindow, String>? {
            lateralWindowOf(frameId)?.let { lateral ->
                return historyWindowOf(lateral)?.let { it to frameId.removePrefix(lateral.name) }
            }
            if (frameId.startsWith("TaskTreeDetail/")) return HistoryWindow.TaskTreeDetail to frameId.removePrefix("TaskTreeDetail")
            val base = frameId.substringBefore('#')
            val window = when (base) {
                TASK_EDIT_FRAME_ID -> HistoryWindow.TaskEdit
                CATEGORY_EDIT_FRAME_ID -> HistoryWindow.CategoryEdit
                PRIORITY_WEIGHTS_FRAME_ID -> HistoryWindow.PriorityWeights
                RELATIVE_PRIORITY_FRAME_ID -> HistoryWindow.RelativePriority
                DEEP_COPY_FRAME_ID -> HistoryWindow.DeepCopy
                ALARM_DEFAULTS_FRAME_ID -> HistoryWindow.AlarmDefaults
                REMINDER_DEFAULTS_FRAME_ID -> HistoryWindow.ReminderDefaults
                "CalendarElements" -> HistoryWindow.CalendarElements
                "CalendarEntryEdit" -> HistoryWindow.CalendarEntry
                "CalendarPeriodEdit" -> HistoryWindow.CalendarPeriod
                "CalendarReminderEdit" -> HistoryWindow.CalendarReminder
                "ReminderConstraintEdit" -> HistoryWindow.ReminderConstraint
                "HistoryEntryInfo" -> HistoryWindow.HistoryEntryInfo
                "Notice", "AppNotice", "CategoryRuleNotice" -> HistoryWindow.Notice
                else -> return null
            }
            return window to frameId.removePrefix(base)
        }
        // PRD §6 (user rule 2026-10-01): the units of what `App` keeps — recorded once the start-up has settled (the
        // writes that restore the windows are not the user's), never while an undo or redo is being put back.
        viewHistory.dispatch = { vm.dispatch(it) }
        viewHistory.focusAfterClose = { closing ->
            // A window that closes hands the focus to the one under it, which is where its close is undone from.
            windowFrames.frontIdExcluding(closing)?.let { windowFrames.focus(it) }
        }
        LaunchedEffect(Unit) {
            repeat(3) { withFrameNanos { } }
            viewHistory.enabled = true
        }
        // A press in ANY window moves the focus to it, synchronously, before the press commits anything — the
        // lateral-menu windows' own `onRaise` already did; this is what covers every other window. A no-op when the
        // focus is already there.
        windowFrames.onFocus = { frameId ->
            historyWindowOfFrame(frameId)?.let { (window, instance) -> vm.dispatch(SchedulerIntent.FocusWindow(window, instance)) }
        }
        // PRD §6: every History Unit is stamped with the window it was made in, which is the focused one
        // ([SchedulerState.focusedWindow]) — every window claims the focus, so there is one answer to "where is
        // the user", held by the state, and not a second one here beside it.
        SideEffect { SchedulerReducer.stampsWindow = true }
        fun bringWindowToFront(id: FloatingWindow) {
            windowFrames.raise(id.name)
        }
        fun isWindowOpen(id: FloatingWindow): Boolean = when (id) {
            FloatingWindow.Calendar -> calendarOpen
            FloatingWindow.History -> historyManagerOpen
            FloatingWindow.Sleep -> sleepWindowOpen
            FloatingWindow.Alarms -> alarmWindowOpen
            FloatingWindow.TaskTrees -> taskTreesWindowOpen
            FloatingWindow.TaskRelations -> taskRelationsWindowOpen
            FloatingWindow.Categories -> categoriesWindowOpen
            FloatingWindow.DefaultSubtree -> defaultSubtreeWindowOpen
            FloatingWindow.Shortcuts -> shortcutsWindowOpen
            FloatingWindow.Search -> searchWindowOpen
            FloatingWindow.ConfigSearch -> configSearchWindowOpen
            FloatingWindow.AddedConfig -> addedConfigWindowOpen
            FloatingWindow.TaskTree -> taskTreeWindowOpen
            FloatingWindow.Online -> onlineWindowOpen
            FloatingWindow.TimeSim -> DebugFlags.TIME_SIMULATION
        }
        // [isWindowOpen]'s other half, for the one caller that opens a window by name: the focus following a
        // walked-back move (below). The debug panel is not a window of the app and has no switch here.
        fun setWindowOpen(id: FloatingWindow, open: Boolean) {
            when (id) {
                FloatingWindow.Calendar -> calendarOpen = open
                FloatingWindow.History -> historyManagerOpen = open
                FloatingWindow.Sleep -> sleepWindowOpen = open
                FloatingWindow.Alarms -> alarmWindowOpen = open
                FloatingWindow.TaskTrees -> taskTreesWindowOpen = open
                FloatingWindow.TaskRelations -> taskRelationsWindowOpen = open
                FloatingWindow.Categories -> categoriesWindowOpen = open
                FloatingWindow.DefaultSubtree -> defaultSubtreeWindowOpen = open
                FloatingWindow.Shortcuts -> shortcutsWindowOpen = open
                FloatingWindow.Search -> searchWindowOpen = open
                FloatingWindow.ConfigSearch -> configSearchWindowOpen = open
                FloatingWindow.AddedConfig -> addedConfigWindowOpen = open
                FloatingWindow.TaskTree -> taskTreeWindowOpen = open
                FloatingWindow.Online -> onlineWindowOpen = open
                FloatingWindow.TimeSim -> Unit
            }
        }
        // The FRONT window: the very top of the one stacking order, when that is a lateral-menu window.
        //
        // It has to be the top of the WHOLE stack, per-object windows included, and not the topmost
        // lateral-menu one: with the Alarms window open and the priority-weight table standing over it,
        // Alarms is still the topmost lateral-menu window, and reading it that way had the Alarms menu
        // button answer "you are already here" and CLOSE the window the user was asking to come back to.
        // The table on top means the answer is null, which is what sends that button down the "bring it
        // back to the front" branch instead.
        fun focusedWindow(): FloatingWindow? =
            windowFrames.frontId
                ?.let { id -> FloatingWindow.entries.firstOrNull { it.name == id } }
                ?.takeIf { isWindowOpen(it) }
        // PRD §7 window navigation: raise [id] to the top layer AND move scheduler focus onto it, which
        // clears the tree selection, forcibly exits tree Edit Mode, and records a WindowNav history unit.
        // A copy of [kind] made from the window [fromId] (the original's name or another copy's): the first free
        // number, the source's row with its own configuration, set off so it does not sit exactly over it, and
        // not reduced. It opens on top and takes the focus, like any window that opens.
        fun duplicateWindow(kind: FloatingWindow, fromId: String) {
            val used = windowCopies.filter { lateralWindowOf(it) == kind }.mapNotNull { it.substringAfter('#').toIntOrNull() }.toSet()
            val n = generateSequence(2) { it + 1 }.first { it !in used }
            val id = kind.name + "#" + n
            val from = placements[fromId] ?: WindowPlacement(x = 0f, y = 0f, visible = true)
            updatePlacementById(id) {
                from.copy(x = from.x + COPY_CASCADE_PX, y = from.y + COPY_CASCADE_PX, visible = true, minimized = false)
            }
            // The configurations held in memory follow the row they were decoded from.
            searchOpenedConfigs.remove(id)
            searchConfigs[fromId]?.let {
                searchConfigs[id] = it
                searchOpenedConfigs[id] = it
            }
            configSearches[fromId]?.let { configSearches[id] = it }
            windowCopies.add(id)
            windowFrames.focus(id)
        }
        fun closeWindowCopy(id: String) {
            // The row first: it closes the windows opened from this one, which name it only while it is a copy
            // (`configTargetOf`).
            updatePlacementById(id) { it.copy(visible = false, minimized = false) }
            windowCopies.remove(id)
        }
        // An undone or redone change to what `App` keeps, put back — three-way: only while it is still as the unit
        // left it.
        var restoredSeq by remember { mutableStateOf(0L) }
        LaunchedEffect(schedulerState.externalRestores) {
            for (restore in schedulerState.externalRestores) {
                if (restore.seq <= restoredSeq) continue
                restoredSeq = restore.seq
                viewHistory.applying {
                    when {
                        restore.key == MENU_KEY ->
                            if (CustomMenuButtons.encode(menuButtons) == restore.from) setMenuButtons(CustomMenuButtons.decode(restore.to))
                        restore.key.startsWith(SEARCH_KEY) -> {
                            val id = restore.key.removePrefix(SEARCH_KEY)
                            if (searchConfigOf(id).encode() == restore.from) {
                                setSearchConfig(id, SearchDomain.Config.decode(restore.to) ?: SearchDomain.Config())
                            }
                        }
                        restore.key.startsWith(WINDOW_KEY) -> {
                            val id = restore.key.removePrefix(WINDOW_KEY)
                            val current = placements[id]?.let(::layoutText)
                            val to = restore.to?.let(::layoutOf)
                            if (current == restore.from && to != null) {
                                updatePlacementById(id) {
                                    it.copy(
                                        x = to.x, y = to.y, width = to.width, height = to.height, visible = to.visible,
                                        fillWidth = to.fillWidth, fillHeight = to.fillHeight, minimized = to.minimized,
                                    )
                                }
                                val registration = windowFrames.registrations.firstOrNull { it.id == id }
                                val kind = lateralWindowOf(id)
                                when {
                                    kind != null && id == kind.name -> setWindowOpen(kind, to.visible)
                                    kind != null -> if (to.visible) { if (id !in windowCopies) windowCopies.add(id) } else closeWindowCopy(id)
                                    !to.visible -> registration?.onClose()
                                    registration == null ->
                                        placements[id]?.config?.let(ObjectWindowKey::decode)?.let { key ->
                                            openObjectWindow(key, id.substringAfter('#', "").toIntOrNull())
                                        }
                                }
                                if (to.visible) {
                                    registration?.state?.applyLayout(
                                        Offset(to.x, to.y), Size(to.width, to.height),
                                        org.example.project.ui.WindowFill.of(to.fillWidth, to.fillHeight), to.minimized,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // **THE WINDOW THE APP WAS LEFT ON HAS THE FOCUS WHEN IT COMES BACK** (anomaly 2026-10-02: a redeploy
        // with the calendar focused came back with the Search window focused instead). The state remembers the
        // window ([SchedulerState.focusedWindow]) but the frames start with no focus, and two things then took
        // it from that window before the user did anything: every restored window that answers keystrokes
        // claimed it as it registered — the last one composed won, and the claim was RECORDED as a move — and
        // the calendar claimed it merely for being open. Neither is a request: nothing was opened, the app was
        // put back. So the claims are off while the windows register ([WindowFrameHost.claimOnOpen]), and this
        // hands the frame focus to the window the state named at launch. Not a move (the state is already
        // there, so the dispatch the frame makes is a no-op) and never a restore: a window left reduced stays
        // reduced, one closed since stays closed.
        val leftOn = remember { schedulerState.focusedWindow to schedulerState.focusedInstance }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            windowFrames.registrations.firstOrNull { historyWindowOfFrame(it.id) == leftOn }
                ?.takeIf { !it.state.minimized }
                ?.let { windowFrames.focus(it.id) }
            windowFrames.claimOnOpen = true
        }
        fun focusWindow(id: FloatingWindow) {
            bringWindowToFront(id)
            // Moving to a window takes the focus, exactly as a press inside it would: this is the same
            // funnel the frame's own `raiseOnPress` uses ([WindowFrameHost.focus]). Raising without it
            // would leave the app believing the user was still in whatever window they had focused, so
            // the lateral-menu button below could never read as "you are already here".
            windowFrames.focus(id.name)
            historyWindowOf(id)?.let { vm.dispatch(SchedulerIntent.FocusWindow(it)) }
        }
        // PRD §5: `Shift+Alt+←/→` walks the moves of the focus, so the state can name a window the frames are not
        // on. Bring that window to the user — opened again if it was closed since — WITHOUT dispatching a move of
        // the focus: that would record a new one, and a new position drops the ones ahead of it. A press on a
        // window already has its frame focused by the time the state follows, so this does nothing then. The
        // first value is the one the app started on: it is not a move, and the window it names may be closed.
        var focusSeen by remember { mutableStateOf(false) }
        LaunchedEffect(schedulerState.focusedWindow, schedulerState.focusedInstance) {
            if (!focusSeen) {
                focusSeen = true
                return@LaunchedEffect
            }
            val kind = FloatingWindow.entries.firstOrNull { historyWindowOf(it) == schedulerState.focusedWindow }
                ?: return@LaunchedEffect
            val id = kind.name + schedulerState.focusedInstance
            if (windowFrames.focusedId == id) return@LaunchedEffect
            if (schedulerState.focusedInstance.isEmpty()) {
                if (!isWindowOpen(kind)) setWindowOpen(kind, true)
            } else if (id !in windowCopies) {
                // A copy closed since cannot be brought back: its configuration went with it.
                return@LaunchedEffect
            }
            windowFrames.present(id)
        }
        // A lateral-menu window and its copies (the head's ⧉). [content] is the window's ONE call site, drawn once
        // for the original (while [open]) and once per copy, each under its [WindowInstance]: a copy's frame id,
        // placement, close, geometry and raise are its own, and its view configuration is read by frame id
        // ([windowInstanceId]) — so a copy is an independent window, not a mirror.
        @Composable
        fun LateralWindow(kind: FloatingWindow, open: Boolean, content: @Composable () -> Unit) {
            if (open) {
                CompositionLocalProvider(
                    LocalWindowInstance provides WindowInstance("", { duplicateWindow(kind, kind.name) }, null),
                    content = content,
                )
            }
            for (copyId in windowCopies.filter { lateralWindowOf(it) == kind }) {
                key(copyId) {
                    val row = placements[copyId] ?: WindowPlacement(x = 0f, y = 0f, visible = true)
                    CompositionLocalProvider(
                        LocalWindowInstance provides WindowInstance(
                            suffix = copyId.removePrefix(kind.name),
                            onDuplicate = { duplicateWindow(kind, copyId) },
                            copy = WindowCopy(
                                initialOffset = Offset(row.x, row.y),
                                initialSize = Size(row.width, row.height),
                                onClose = { closeWindowCopy(copyId) },
                                onGeometryChange = { offset, size ->
                                    updatePlacementById(copyId) {
                                        it.copy(x = offset.x, y = offset.y, width = size.width, height = size.height)
                                    }
                                },
                                // The frame already raised and focused the copy itself; what is left is the
                                // app's own notion of where the user is (history stamping, keyboard routing).
                                onRaise = {
                                    historyWindowOf(kind)?.let {
                                        vm.dispatch(SchedulerIntent.FocusWindow(it, copyId.removePrefix(kind.name)))
                                    }
                                },
                            ),
                        ),
                        content = content,
                    )
                }
            }
        }
        // PRD §8/§13 "go to task tree": select the cell showing the task — [at] when the caller knows which one
        // (a Search row's path), else the first — expanding whatever hides it (RevealCell, the find bar's own
        // primitive), and bring the TREE WINDOW to the user: opened if closed, back from the window bar if
        // reduced, on top, focused. "Going to" the tree is the tree becoming the focused surface, which is also
        // what re-arms its keyboard.
        //
        // Declared once because several surfaces offer the entry under that name: a calendar task panel's menu
        // (PRD §8) and the Search window's task rows (PRD §7). A panel outlives
        // the cell that laid it (panels are not per-tree, §7), so the task may be a detached parent, deleted, or
        // another task tree's — and a panel whose title never named a task has no id at all. All of those are the
        // same answer, said once, here.
        fun goToTaskTreeAt(taskId: TaskId?, title: String, at: SchedulerDomain.TaskOccurrence?) {
            val occurrence = at ?: taskId?.let { SchedulerDomain.firstTaskOccurrence(schedulerState, it) }
            if (occurrence == null) {
                val name = taskId?.let { schedulerState.tasks[it]?.title }?.ifEmpty { null }
                    ?: title.ifEmpty { null }
                appMessage =
                    if (name == null) {
                        "This panel's task is not in the task tree."
                    } else {
                        "\"$name\" is not in the task tree."
                    }
            } else {
                taskTreeWindowOpen = true
                windowFrames.present(FloatingWindow.TaskTree.name)
                focusWindow(FloatingWindow.TaskTree)
                vm.dispatch(SchedulerIntent.RevealCell(occurrence.cellId, occurrence.ancestors))
            }
        }
        val goToTaskTree: (TaskId?, String) -> Unit = { taskId, title -> goToTaskTreeAt(taskId, title, null) }
        // PRD §7: the configuration a lateral-menu window keeps on its placement row, in its stored form — what a
        // ☆ saves on the button it makes. Only the Search window and the Configuration Search window have one.
        fun windowConfigOf(frameId: String): String? =
            when (lateralWindowOf(frameId)) {
                FloatingWindow.Search -> searchConfigOf(frameId).encode()
                FloatingWindow.ConfigSearch, FloatingWindow.AddedConfig -> configSearchOf(frameId).encode()
                else -> null
            }
        // A button made before buttons kept a configuration (2026-09-26) is given one ONCE, here at startup, from
        // its window's row as it stands — and never again read off the live window. Reading it at every click (as
        // the first version did) made the button's "exact window" whatever its window had become, so it always
        // found the window it had opened, however that window had been changed since.
        remember(placements) {
            val frozen = CustomMenuButtons.withConfigsFrozen(menuButtons) { windowConfigOf(it) }
            if (frozen != menuButtons) setMenuButtons(frozen)
        }
        // [config] as [kind]'s window would hold it once opened with it — a missing one is the kind's default — so
        // two readings of one configuration compare equal. Null for a kind that has no configuration.
        fun normalizedWindowConfig(kind: FloatingWindow, config: String?): String? =
            when (kind) {
                FloatingWindow.Search -> (SearchDomain.Config.decode(config) ?: SearchDomain.Config()).encode()
                FloatingWindow.ConfigSearch, FloatingWindow.AddedConfig ->
                    (SearchDomain.ConfigurationSearch.decode(config) ?: SearchDomain.ConfigurationSearch()).encode()
                else -> null
            }
        // Bring the open window [id] of [kind] back: out of the window bar, to the front, into the focus.
        // User rule 2026-10-07: **the state a menu button keeps of its window** — where the window stands and the
        // lines between its sections ([org.example.project.ui.WindowLayout]); its configuration is [windowConfigOf].
        // Read off the open window; null for one that is not open.
        fun windowLayoutOf(frameId: String): org.example.project.ui.WindowLayout? {
            val frame = windowFrames.registrations.firstOrNull { it.id == frameId }?.state ?: return null
            val search = searchSplits[frameId] ?: org.example.project.ui.SearchSplits()
            val (splits, hidden) =
                when (lateralWindowOf(frameId)) {
                    FloatingWindow.Search ->
                        listOf(search.left, search.topRight) to listOf(search.searchHidden, search.actionsHidden, search.addedHidden)
                    FloatingWindow.Calendar -> listOfNotNull(calendarConfigurationWidth) to calendarSectionsHidden.orEmpty()
                    else -> emptyList<Float>() to emptyList()
                }
            return org.example.project.ui.WindowLayout(frame.offset.x, frame.offset.y, frame.size.width, frame.size.height, splits, hidden)
        }
        // The window a button is about: the one it opened or brought back this session, else — a window that came back
        // at a restart — the open one of its kind that still carries the button's name on its tab
        // ([WindowFrameHost.tabTitles], kept across restarts), which is the window that button created.
        fun menuButtonWindowOf(button: CustomMenuButton): String? =
            menuButtonWindows[button.id]
                ?: windowFrames.registrations.firstOrNull { window ->
                    windowFrames.tabTitles[window.id] == button.title &&
                        (ObjectWindowKey.decode(button.windowId)?.let { window.menuKey == button.windowId }
                            ?: (lateralWindowOf(window.id) != null && lateralWindowOf(window.id) == lateralWindowOf(button.windowId)))
                }?.id
        // The button's "Update" is offered while the window it opened is open and is no longer what the button holds.
        fun menuButtonCanUpdate(button: CustomMenuButton): Boolean {
            val frameId = menuButtonWindowOf(button) ?: return false
            val kind = lateralWindowOf(frameId)
            return CustomMenuButtons.needsUpdate(
                button,
                savedConfig = kind?.let { normalizedWindowConfig(it, button.config) },
                config = kind?.let { windowConfigOf(frameId) },
                layout = windowLayoutOf(frameId),
                opened = menuButtonOpenedLayouts[button.id],
            )
        }
        fun presentWindow(kind: FloatingWindow, id: String) {
            if (id == kind.name) focusWindow(kind)
            else historyWindowOf(kind)?.let { vm.dispatch(SchedulerIntent.FocusWindow(it, id.removePrefix(kind.name))) }
            windowFrames.present(id)
        }
        // PRD §7: what EVERY window button of the lateral menu does. It asks for a window of [kind] with [config] (a
        // ☆ button's snapshot) or the kind's default configuration. **When that exact window is open already** —
        // same kind, same configuration (any window of a kind that has none) — it is brought back and focused
        // instead; otherwise a NEW one opens: the original when it is not open (where it was left), else a copy set
        // off from it (`Search#2`, the head's ⧉ mechanism). Never closes one. The task tree and the calendar exist
        // once (`popups.md`, *Not duplicable*): theirs is opened when closed and brought back when open.
        // Returns the frame id of the window it opened or brought back.
        fun openNewWindow(kind: FloatingWindow, config: String? = null): String {
            if (kind == FloatingWindow.TaskTree || kind == FloatingWindow.Calendar || kind == FloatingWindow.TimeSim) {
                if (!isWindowOpen(kind)) setWindowOpen(kind, true)
                focusWindow(kind)
                windowFrames.present(kind.name)
                return kind.name
            }
            val wanted = normalizedWindowConfig(kind, config)
            val open = listOfNotNull(kind.name.takeIf { isWindowOpen(kind) }) + windowCopies.filter { lateralWindowOf(it) == kind }
            open.firstOrNull { windowConfigOf(it) == wanted }?.let { existing ->
                presentWindow(kind, existing)
                return existing
            }
            val id =
                if (!isWindowOpen(kind)) {
                    kind.name
                } else {
                    val used = windowCopies.filter { lateralWindowOf(it) == kind }.mapNotNull { it.substringAfter('#').toIntOrNull() }.toSet()
                    kind.name + "#" + generateSequence(2) { it + 1 }.first { it !in used }
                }
            // The configuration held in memory is re-read from the row written here.
            searchConfigs.remove(id)
            searchOpenedConfigs.remove(id)
            configSearches.remove(id)
            if (id == kind.name) {
                updatePlacementById(id) { it.copy(config = config, minimized = false) }
                setWindowOpen(kind, true)
                focusWindow(kind)
                return id
            }
            val from = placements[kind.name] ?: WindowPlacement(x = 0f, y = 0f, visible = true)
            val step = COPY_CASCADE_PX * ((id.substringAfter('#').toInt() - 1) % 6)
            updatePlacementById(id) {
                from.copy(x = from.x + step, y = from.y + step, visible = true, minimized = false, config = config)
            }
            windowCopies.add(id)
            windowFrames.focus(id)
            historyWindowOf(kind)?.let { vm.dispatch(SchedulerIntent.FocusWindow(it, id.removePrefix(kind.name))) }
            return id
        }
        // The Search window a configurations window ([FloatingWindow.ConfigSearch], [FloatingWindow.AddedConfig])
        // lists and edits: the one it was opened from — or the original, once that copy is closed.
        fun configTargetOf(configId: String): String =
            configSearchOf(configId).target.takeIf { it in windowCopies } ?: FloatingWindow.Search.name
        // The window bar's tab of a window opened from another stands beside that one's, a line under the two (user
        // rule 2026-10-06, `WindowTabGroups`): a configurations window's Search window, and the calendar for a Search
        // window its right-click opened. Read off what the windows hold; a parent that is closed is none.
        windowFrames.tabParentOf = { frameId ->
            when (lateralWindowOf(frameId)) {
                FloatingWindow.ConfigSearch, FloatingWindow.AddedConfig -> configTargetOf(frameId)
                FloatingWindow.Search ->
                    FloatingWindow.Calendar.name.takeIf { searchConfigOf(frameId).calendarClickMillis != null }
                else -> null
            }
        }
        // **EACH Search window has its own configurations window** (anomaly 2026-10-06: two Search windows' buttons
        // led to one and the same window, which the last press re-pointed — the first Search window lost its own).
        // The button brings back the window of [kind] that is on [searchId]; when none is, it opens one — the
        // original where it is not open (keeping what its bar and types were left on), else a copy.
        fun openConfigurationsOf(kind: FloatingWindow, searchId: String) {
            val open = listOfNotNull(kind.name.takeIf { isWindowOpen(kind) }) + windowCopies.filter { lateralWindowOf(it) == kind }
            open.firstOrNull { configTargetOf(it) == searchId }?.let { existing ->
                presentWindow(kind, existing)
                return
            }
            val own = if (isWindowOpen(kind)) SearchDomain.ConfigurationSearch() else configSearchOf(kind.name)
            openNewWindow(kind, own.copy(target = searchId).encode())
        }
        // The calendar's "add…" ([add]) or "edit…" at [atMillis]: the Search window a previous one opened, moved to
        // this right-click with the matching filter on and the other off — its added elements and the rest of its
        // configuration kept, its types those the filter is about — or a new one ([SearchDomain.calendarAddConfig],
        // [SearchDomain.calendarAtConfig]).
        // User rule 2026-10-04: **the notifications window.** A notification — written, spoken or both — that fires
        // while the app is NOT in focus brings up a Search window on the notifications posted since the app lost the
        // focus (`SearchDomain.notificationsConfig`), in front and focused, so it is what the user comes back to. The
        // window stays and goes on gathering until the user closes it; the next one starts at the next loss of focus.
        // "That window" is the one opened here, held by its frame id (after a restart: the one still open with that
        // kind of configuration) — closing another Search window that shows the same thing ends nothing.
        // Event-driven: the log growing and the focus changing are the only things that ask (never a timer). The
        // app counts as out of focus from its start until it first has the focus.
        var notificationsWindowId by remember { mutableStateOf<String?>(null) }
        var unfocusedSinceMillis by remember { mutableStateOf<Long?>(clock.nowMillis()) }
        var notificationAnsweredAtMillis by remember { mutableStateOf<Long?>(null) }
        // **Whether the APP has the focus is the platform's to say** (anomaly 2026-10-07: the window opened while the
        // user was on the calendar). `isWindowFocused` is not that: it turns false whenever something INSIDE the app
        // takes the keyboard off the main content — a right-click menu, a drop-down — so a menu left open made every
        // notification "one that fired while the app was out of focus". The entry point injects the real answer
        // ([LocalAppInFocus]: on the desktop, whether any window of the app is the active one); only where none is
        // injected is the window's own flag read.
        val appFocused = LocalAppInFocus.current ?: LocalWindowInfo.current.isWindowFocused
        val lastNotificationAtMillis = schedulerState.notificationLog.lastOrNull()?.timeMillis
        fun openSearchFrames(): List<String> =
            listOfNotNull(FloatingWindow.Search.name.takeIf { isWindowOpen(FloatingWindow.Search) }) +
                windowCopies.filter { lateralWindowOf(it) == FloatingWindow.Search }
        val notificationsWindowOpen = notificationsWindowId?.let { it in openSearchFrames() }
        LaunchedEffect(appFocused, lastNotificationAtMillis) {
            if (!appFocused && unfocusedSinceMillis == null) unfocusedSinceMillis = clock.nowMillis()
            val since = unfocusedSinceMillis
            if (since != null && SearchDomain.notificationsWindowOwed(lastNotificationAtMillis, since, notificationAnsweredAtMillis)) {
                notificationAnsweredAtMillis = lastNotificationAtMillis
                // scripts/collect-diagnostics.bat: why the window came up — the notification it answers, and since
                // when the app says it was out of focus.
                Diagnostics.log(
                    "unfocused notif window: a notification at ${Diagnostics.formatInstant(lastNotificationAtMillis ?: since)} " +
                        "fired with the app out of focus since ${Diagnostics.formatInstant(since)}",
                )
                val open = openSearchFrames()
                // The window already gathering (held, or left open across a restart) is brought back in front; else a
                // new one opens on what fired since the focus was lost. One the user CHANGED since (its search
                // configuration or its added elements) is theirs now: it stays as it is and a new one opens beside it.
                fun gathering(id: String) = SearchDomain.isUntouchedNotificationsWindow(searchConfigOf(id))
                val standing = notificationsWindowId?.takeIf { it in open && gathering(it) }
                    ?: open.firstOrNull { gathering(it) }
                notificationsWindowId =
                    if (standing != null) standing.also { presentWindow(FloatingWindow.Search, it) }
                    else openNewWindow(FloatingWindow.Search, SearchDomain.notificationsConfig(since).encode())
            }
            // Back in focus: what fired meanwhile has been answered above, and the next stretch starts when it leaves.
            if (appFocused) unfocusedSinceMillis = null
        }
        // Closed: it is no longer held, and the next loss of focus starts a new one.
        LaunchedEffect(notificationsWindowOpen) {
            if (notificationsWindowOpen == false) notificationsWindowId = null
        }

        // The Search windows (by frame id) whose type selector opens deployed once they show — the calendar's "add…".
        // A one-shot: the window takes its id off as it deploys the drop-down. Compose-only.
        val searchKindsToDeploy = remember { mutableStateListOf<String>() }
        fun openCalendarSearch(atMillis: Long, add: Boolean) {
            val search = FloatingWindow.Search
            val open = listOfNotNull(search.name.takeIf { isWindowOpen(search) }) + windowCopies.filter { lateralWindowOf(it) == search }
            val existing = open.firstOrNull { searchConfigOf(it).calendarClickMillis != null }
            val fresh = if (add) SearchDomain.calendarAddConfig(atMillis) else SearchDomain.calendarAtConfig(atMillis)
            if (existing == null) {
                val id = openNewWindow(search, fresh.encode())
                // "add…" opens with the type selector deployed, so the types are picked right away (user rule 2026-10-01).
                if (add) searchKindsToDeploy.add(id)
                return
            }
            if (add) searchKindsToDeploy.add(existing)
            val config = searchConfigOf(existing)
            val moved =
                config.copy(
                    kinds = fresh.kinds,
                    // "edit…" lists what is there in the hover bubble's order (user rule 2026-10-02).
                    sorts = if (add) config.sorts else SearchDomain.withCalendarBubbleSort(config.sorts),
                    filters = config.filters.copy(
                        calendarAddOn = add,
                        calendarAddAtMillis = if (add) atMillis else config.filters.calendarAddAtMillis,
                        calendarAtOn = !add,
                        calendarAtMillis = if (add) config.filters.calendarAtMillis else atMillis,
                    ),
                    calendarClickMillis = atMillis,
                )
            setSearchConfig(existing, moved)
            // The window is opened anew on this right-click: what its Reset goes back to.
            searchOpenedConfigs[existing] = moved
            presentWindow(search, existing)
        }
        openElementSearch = { kind, id ->
            openNewWindow(FloatingWindow.Search, SearchDomain.elementSearchConfig(kind, id).encode())
        }
        // A window button that is NOT in the lateral menu (the default sub-tree's, atop the task tree window): open
        // it (and focus) when closed; close it when it is the window being worked in; otherwise bring it back to the
        // front and the focus without closing.
        fun onMenuWindowClicked(id: FloatingWindow, setOpen: (Boolean) -> Unit) {
            when {
                !isWindowOpen(id) -> {
                    setOpen(true)
                    focusWindow(id)
                }
                focusedWindow() == id -> setOpen(false)
                else -> focusWindow(id)
            }
        }

        // PRD §7: a button made from a window's head (☆): at the bottom of the menu, its title open for typing
        // with a default name all selected — a lateral-menu window's own title (a copy's followed by its number,
        // like its frame id), a per-object window's object and what it is ("Tea timer").
        fun addMenuButton(key: String, title: String) {
            val objectKey = ObjectWindowKey.decode(key)
            val kind = lateralWindowOf(key)
            // Named after what it opens — a new window of that kind, not this copy of it.
            val name = objectKey?.buttonTitle(schedulerState) ?: kind?.title ?: title
            val (list, id) = CustomMenuButtons.added(menuButtons, key, name, windowConfigOf(key))
            setMenuButtons(list)
            editingMenuButton = id
            // The field has to be seen to be typed in: the menu opens, scrolled to its end.
            menuCollapsed = false
            menuButtonAdded++
        }
        // "add in the left-side menu", for a control: at the bottom, the menu open and scrolled to it. One that is
        // there already is only shown.
        menuCustomizer.addAction = { action, title, config ->
            setMenuButtons(CustomMenuButtons.addedAction(menuButtons, action, title, config).first)
            menuCollapsed = false
            menuButtonAdded++
        }
        menuCustomizer.add = { control ->
            val (list, _) = CustomMenuButtons.addedControl(menuButtons, control)
            if (list != menuButtons) setMenuButtons(list)
            menuCollapsed = false
            menuButtonAdded++
        }
        // Every window the app can open by its frame id gets the ☆: the lateral-menu windows and their copies. A
        // per-object window about an object with a stable id brings its own key ([ObjectWindowKey]).
        val menuButtonHost = remember {
            MenuButtonHost(canAdd = { lateralWindowOf(it) != null }, add = { id, title -> addMenuButton(id, title) })
        }
        // A button the user made (☆) opens its window like every button of the menu ([openNewWindow]): a
        // per-object window on its object — the one open on it already, brought back, when there is one — or a
        // lateral-menu window with the configuration the ☆ saved. A button made before the snapshot existed opens
        // with what its window has now.
        //
        // The window it CREATES has the button's name on its tab (user rule 2026-10-01; [WindowFrameHost.tabTitles]):
        // whichever window registers in the frames after the click and was not there before — a window only brought
        // back keeps its tab as it was.
        fun onMenuButtonClicked(button: CustomMenuButton) {
            val before = windowFrames.registrations.mapTo(HashSet()) { it.id }
            val saved = CustomMenuButtons.decodeLayout(button.layout)
            var asked: String? = null
            ObjectWindowKey.decode(button.windowId)?.let { key ->
                openObjectWindow(key)
            } ?: run {
                val kind = lateralWindowOf(button.windowId) ?: return
                val id = openNewWindow(kind, button.config)
                asked = id
                menuButtonWindows[button.id] = id
                if (id in before) {
                    // Brought back as it stands: what it looks like now is this button's reference, where it kept none.
                    if (saved == null && button.id !in menuButtonOpenedLayouts) windowLayoutOf(id)?.let { menuButtonOpenedLayouts[button.id] = it }
                } else if (saved != null) {
                    // A new window, put back as the button kept it: its lines now, its place once it has a frame.
                    when (kind) {
                        FloatingWindow.Search ->
                            if (saved.splits.size == 2) {
                                searchSplits[id] =
                                    org.example.project.ui.SearchSplits(
                                        saved.splits[0], saved.splits[1],
                                        saved.hidden.getOrNull(0) ?: false, saved.hidden.getOrNull(1) ?: false, saved.hidden.getOrNull(2) ?: false,
                                    )
                            }
                        FloatingWindow.Calendar -> {
                            saved.splits.firstOrNull()?.let { calendarConfigurationWidth = it }
                            if (saved.hidden.isNotEmpty()) calendarSectionsHidden = saved.hidden
                        }
                        else -> Unit
                    }
                    saveWindowSections()
                    updatePlacementById(id) { it.copy(x = saved.x, y = saved.y, width = saved.width, height = saved.height) }
                }
            }
            engineScope.launch {
                // A new window registers as it is first composed: within a frame or two of the click.
                repeat(TAB_TITLE_FRAMES) {
                    withFrameNanos { }
                    val created = windowFrames.registrations.map { it.id }.filter { it !in before }
                    if (created.isNotEmpty()) {
                        created.forEach { windowFrames.tabTitles[it] = button.title }
                        saveTabTitles()
                        val id = asked?.takeIf { it in created } ?: created.first()
                        menuButtonWindows[button.id] = id
                        if (saved != null) {
                            windowFrames.registrations.firstOrNull { it.id == id }?.state?.let { frame ->
                                frame.applyLayout(
                                    Offset(saved.x, saved.y),
                                    if (saved.width > 0f && saved.height > 0f) Size(saved.width, saved.height) else frame.size,
                                    frame.fill,
                                    frame.minimized,
                                )
                            }
                        }
                        withFrameNanos { }
                        windowLayoutOf(id)?.let { menuButtonOpenedLayouts[button.id] = it }
                        return@launch
                    }
                }
            }
        }

        // PRD §7 Search, the "window" kind: every window of the app, open or not. Each open window is its own entry
        // — every copy, every per-object window, every notice — read off the frame host, which is what the window
        // bar lists too; a reduced one is "minimized" (in that bar). A lateral-menu window that is not open
        // is one entry more, so it can be found and opened from the list. Read inside the Search window's own
        // scope, so a window opening or being reduced recomposes that window and not `App`.
        // A window's TYPE ([SearchDomain.WindowEntry.type]) and what it is called: a per-object window's by its object
        // kind (the default timer's window is not a timer's), a lateral-menu window's by its kind — a copy included —
        // and anything else by its frame id's base.
        fun objectWindowType(kind: ObjectWindowKey.Kind): String = "object:" + kind.name
        fun objectWindowTypeTitle(kind: ObjectWindowKey.Kind): String = kind.noun.replaceFirstChar { it.uppercaseChar() }
        fun windowTypeOf(window: WindowFrameHost.Registration): Pair<String, String> {
            window.menuKey?.let(ObjectWindowKey::decode)?.let { return objectWindowType(it.kind) to objectWindowTypeTitle(it.kind) }
            lateralWindowOf(window.id)?.let { return it.name to it.title }
            return window.id.substringBefore('#') to window.title
        }
        fun searchWindowEntries(): List<SearchDomain.WindowEntry> {
            val open = windowFrames.registrations.map { window ->
                val status = if (window.state.minimized) SearchDomain.WindowStatus.Minimized else SearchDomain.WindowStatus.Open
                val (type, typeTitle) = windowTypeOf(window)
                SearchDomain.WindowEntry(window.id, window.title, status, type, typeTitle)
            }
            val openIds = open.mapTo(HashSet()) { it.id }
            val closed = FloatingWindow.entries
                .filter { lateralWindowOf(it.name) != null && !isWindowOpen(it) && it.name !in openIds }
                .map { SearchDomain.WindowEntry(it.name, it.title, SearchDomain.WindowStatus.NotOpen, it.name, it.title) }
            // Every per-object TYPE, for the one-per-type listing — "Timer" and "Default timer" are two types.
            val listedTypes = (open + closed).mapTo(HashSet()) { it.type }
            val types = ObjectWindowKey.Kind.entries
                .filter { it !in ObjectWindowKey.ELEMENT_KINDS && objectWindowType(it) !in listedTypes }
                .map { kind ->
                    SearchDomain.WindowEntry(
                        objectWindowType(kind), objectWindowTypeTitle(kind), SearchDomain.WindowStatus.NotOpen,
                        objectWindowType(kind), objectWindowTypeTitle(kind), placeholder = true,
                    )
                }
            return open + closed + types
        }
        // PRD §7 Search, a "creation" row (user spec 2026-09-26): a new element of [kind], made the way that kind's
        // own "+ New …" makes it — through the same intents and the account's default configuration — and opened in
        // its window. A new WINDOW is a Search window listing the window types.
        // Whoever asks — a creation row, or the actions' "New" button of any kind (user rule 2026-10-04) — the new
        // element opens in a NEW Search window as its only added element; it never joins the asking window's list.
        fun createElement(kind: SearchDomain.Kind) {
            val st = vm.state.value
            val now = clock.nowMillis()
            val minutes = Instant.fromEpochMilliseconds(now).toLocalDateTime(tz).let { it.hour * 60 + it.minute }
            fun unique(base: String, taken: Collection<String>): String =
                generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }
                    .first { name -> taken.none { it.equals(name, ignoreCase = true) } }
            when (kind) {
                // PRD §9 (user rule 2026-10-03): a new task has NO path — it is made to be given a set of tasks — and opens
                // in a new Search window as its only element, whoever asked (the actions' "New", a creation row).
                SearchDomain.Kind.Task -> {
                    vm.dispatch(SchedulerIntent.CreatePathlessTask(unique("New task", st.tasks.values.map { it.title })))
                    SearchDomain.newElementKeys(st, vm.state.value, SearchDomain.Kind.Task).firstOrNull()?.let { key ->
                        openElementSearch(SearchDomain.Kind.Task, key.removePrefix(SearchDomain.Kind.Task.name + "/"))
                    }
                }
                SearchDomain.Kind.Category -> {
                    val title = unique("New category", st.categories.map { it.title })
                    vm.dispatch(SchedulerIntent.CreateCategory(title))
                    vm.state.value.categories.firstOrNull { it.title == title }?.let {
                        openElementSearch(SearchDomain.Kind.Category, it.id.value)
                    }
                }
                SearchDomain.Kind.RestrictivePeriod -> {
                    val name = unique("New period", st.allPeriodKinds)
                    vm.dispatch(SchedulerIntent.AddPeriodKind(name))
                    if (name in vm.state.value.allPeriodKinds) openElementSearch(SearchDomain.Kind.RestrictivePeriod, name)
                }
                SearchDomain.Kind.Alarm -> {
                    val id = AlarmDomain.mintAlarmId(st.alarms.map { it.id })
                    vm.dispatch(SchedulerIntent.SetAlarms(st.alarms + NewElementDefaults.newAlarm(st.newAlarmDefaults, id, minutes)))
                    openElementSearch(SearchDomain.Kind.Alarm, id)
                }
                SearchDomain.Kind.Timer -> {
                    val id = TimerDomain.mintTimerId(st.timers.map { it.id })
                    vm.dispatch(SchedulerIntent.SetTimers(st.timers + NewElementDefaults.newTimer(st.newTimerDefaults, id)))
                    openElementSearch(SearchDomain.Kind.Timer, id)
                }
                SearchDomain.Kind.Chrono -> {
                    val id = ChronoDomain.mintChronoId(st.chronos.map { it.id })
                    vm.dispatch(SchedulerIntent.SetChronos(st.chronos + ChronoEntry(id = id)))
                    openElementSearch(SearchDomain.Kind.Chrono, id)
                }
                // User rule 2026-10-03: a new quota is a week's, from this week's Monday 00:00 (the app is Monday-first).
                SearchDomain.Kind.Quota -> {
                    val id = org.example.project.scheduler.domain.QuotaDomain.mintQuotaId(st.quotas.map { it.id })
                    val monday = today.plus(-today.dayOfWeek.ordinal, DateTimeUnit.DAY)
                    val start = monday.atStartOfDayIn(tz).toEpochMilliseconds()
                    val end = monday.plus(7, DateTimeUnit.DAY).atStartOfDayIn(tz).toEpochMilliseconds()
                    // From the account's default configuration (the quota actions of an added "New quota" row).
                    vm.dispatch(SchedulerIntent.SetQuotas(st.quotas + NewElementDefaults.newQuota(st.newQuotaDefaults, id, now, start, end)))
                    openElementSearch(SearchDomain.Kind.Quota, id)
                }
                SearchDomain.Kind.Reminder -> {
                    val todayStart = today.atStartOfDayIn(tz).toEpochMilliseconds()
                    val created = NewElementDefaults.newReminder(st.newReminderDefaults, "", minutes)
                    vm.dispatch(SchedulerIntent.SetChores(st.chores + created, todayStart, now))
                    vm.state.value.chores.lastOrNull()?.id?.takeIf { it.isNotEmpty() }?.let {
                        openElementSearch(SearchDomain.Kind.Reminder, it)
                    }
                }
                SearchDomain.Kind.TaskTree -> {
                    vm.dispatch(SchedulerIntent.CreateTaskTree(unique("New tree", st.taskTrees.map { it.title })))
                    taskTreesWindowOpen = true
                    focusWindow(FloatingWindow.TaskTrees)
                }
                SearchDomain.Kind.Window -> openNewWindow(FloatingWindow.Search, SearchDomain.WINDOW_TYPES_CONFIG.encode())
                else -> Unit
            }
        }
        // Opening a "window" row: the window is opened if it is not, and brought back — out of the system tray,
        // to the front, into the focus — if it is. Never closed by this, unlike its lateral-menu button: the row
        // is asked for from the Search window, which is the front one.
        fun showWindow(frameId: String) {
            // A one-per-type row: a window of that type brought back when one is open, else the type opened when it
            // can be without an object (a lateral-menu window, a default configuration's window).
            if (frameId.startsWith(SearchDomain.WindowEntry.TYPE_PREFIX)) {
                val type = frameId.removePrefix(SearchDomain.WindowEntry.TYPE_PREFIX)
                windowFrames.registrations.firstOrNull { windowTypeOf(it).first == type }?.let {
                    showWindow(it.id)
                    return
                }
                FloatingWindow.entries.firstOrNull { it.name == type && lateralWindowOf(it.name) != null }?.let {
                    openNewWindow(it)
                    return
                }
                ObjectWindowKey.Kind.entries.firstOrNull { objectWindowType(it) == type }
                    ?.takeIf { it == ObjectWindowKey.Kind.AlarmDefaults || it == ObjectWindowKey.Kind.TimerDefaults || it == ObjectWindowKey.Kind.ReminderDefaults }
                    ?.let { defaultsWindows.open(it) }
                return
            }
            val kind = lateralWindowOf(frameId)
            if (kind != null && '#' !in frameId) {
                if (!isWindowOpen(kind)) setWindowOpen(kind, true)
                focusWindow(kind)
            } else {
                kind?.let(::historyWindowOf)?.let { vm.dispatch(SchedulerIntent.FocusWindow(it, frameId.removePrefix(kind.name))) }
            }
            windowFrames.present(frameId)
        }

        // Local-only persisted drag positions for the managed windows. The defaults reproduce the previous
        // hard-coded cascade staggers, used until the user drags a window (which persists via onOffsetChange).
        var calendarOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Calendar, Offset.Zero)) }
        var calendarSize by remember { mutableStateOf(savedSize(FloatingWindow.Calendar)) }
        var historyOffset by remember { mutableStateOf(savedOffset(FloatingWindow.History, Offset(200f, 150f))) }
        var historySize by remember { mutableStateOf(savedSize(FloatingWindow.History)) }
        var sleepOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Sleep, Offset(120f, -120f))) }
        var sleepSize by remember { mutableStateOf(savedSize(FloatingWindow.Sleep)) }
        var alarmOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Alarms, Offset(-120f, 120f))) }
        var alarmSize by remember { mutableStateOf(savedSize(FloatingWindow.Alarms)) }
        var taskTreesOffset by remember { mutableStateOf(savedOffset(FloatingWindow.TaskTrees, Offset(-260f, -60f))) }
        var taskTreesSize by remember { mutableStateOf(savedSize(FloatingWindow.TaskTrees)) }
        var taskRelationsOffset by
            remember { mutableStateOf(savedOffset(FloatingWindow.TaskRelations, Offset(100f, -100f))) }
        var taskRelationsSize by remember { mutableStateOf(savedSize(FloatingWindow.TaskRelations)) }
        var categoriesOffset by
            remember { mutableStateOf(savedOffset(FloatingWindow.Categories, Offset(-100f, 60f))) }
        var categoriesSize by remember { mutableStateOf(savedSize(FloatingWindow.Categories)) }
        var defaultSubtreeOffset by
            remember { mutableStateOf(savedOffset(FloatingWindow.DefaultSubtree, Offset(260f, -60f))) }
        var defaultSubtreeSize by remember { mutableStateOf(savedSize(FloatingWindow.DefaultSubtree)) }
        var shortcutsOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Shortcuts, Offset(60f, 60f))) }
        var shortcutsSize by remember { mutableStateOf(savedSize(FloatingWindow.Shortcuts)) }
        var searchOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Search, Offset(-160f, -80f))) }
        var searchSize by remember { mutableStateOf(savedSize(FloatingWindow.Search)) }
        var configSearchOffset by remember { mutableStateOf(savedOffset(FloatingWindow.ConfigSearch, Offset(200f, -40f))) }
        var configSearchSize by remember { mutableStateOf(savedSize(FloatingWindow.ConfigSearch)) }
        var addedConfigOffset by remember { mutableStateOf(savedOffset(FloatingWindow.AddedConfig, Offset(240f, 0f))) }
        var addedConfigSize by remember { mutableStateOf(savedSize(FloatingWindow.AddedConfig)) }
        // Persist each window's visibility whenever it opens/closes (its offset persists separately on drag-end).
        LaunchedEffect(calendarOpen) { persistPlacement(FloatingWindow.Calendar, calendarOffset, calendarSize, calendarOpen) }
        LaunchedEffect(historyManagerOpen) { persistPlacement(FloatingWindow.History, historyOffset, historySize, historyManagerOpen) }
        LaunchedEffect(sleepWindowOpen) { persistPlacement(FloatingWindow.Sleep, sleepOffset, sleepSize, sleepWindowOpen) }
        LaunchedEffect(alarmWindowOpen) { persistPlacement(FloatingWindow.Alarms, alarmOffset, alarmSize, alarmWindowOpen) }
        LaunchedEffect(taskTreesWindowOpen) { persistPlacement(FloatingWindow.TaskTrees, taskTreesOffset, taskTreesSize, taskTreesWindowOpen) }
        LaunchedEffect(taskRelationsWindowOpen) {
            persistPlacement(FloatingWindow.TaskRelations, taskRelationsOffset, taskRelationsSize, taskRelationsWindowOpen)
        }
        LaunchedEffect(categoriesWindowOpen) {
            persistPlacement(FloatingWindow.Categories, categoriesOffset, categoriesSize, categoriesWindowOpen)
        }
        LaunchedEffect(defaultSubtreeWindowOpen) {
            persistPlacement(FloatingWindow.DefaultSubtree, defaultSubtreeOffset, defaultSubtreeSize, defaultSubtreeWindowOpen)
        }
        LaunchedEffect(shortcutsWindowOpen) {
            persistPlacement(FloatingWindow.Shortcuts, shortcutsOffset, shortcutsSize, shortcutsWindowOpen)
        }
        LaunchedEffect(searchWindowOpen) {
            persistPlacement(FloatingWindow.Search, searchOffset, searchSize, searchWindowOpen)
        }
        LaunchedEffect(configSearchWindowOpen) {
            persistPlacement(FloatingWindow.ConfigSearch, configSearchOffset, configSearchSize, configSearchWindowOpen)
        }
        LaunchedEffect(addedConfigWindowOpen) {
            persistPlacement(FloatingWindow.AddedConfig, addedConfigOffset, addedConfigSize, addedConfigWindowOpen)
        }

        var selectedDate by remember { mutableStateOf(today) }
        var monthAnchor by remember { mutableStateOf(LocalDate(today.year, today.month, 1)) }

        // PRD §8: the calendar scrolls through the days ENDLESSLY — under day d sits day d+1 — so what is
        // on screen is no longer "the week containing [selectedDate]" but a day span the scroll lands on, and
        // the calendar reports it up as it rolls. Seeded with the span the grid opens on (today's column plus
        // the six to its right, two day-rows deep) so the first frame projects what it is about to be asked
        // for; [selectedDate] now only says which day the calendar JUMPS to when picked in the month rail.
        var visibleFirstDay by remember { mutableStateOf(today) }
        var visibleDayCount by remember { mutableStateOf(8) }
        // PRD §7: a date pick in the month rail is an EVENT the calendar must act on even when it picks the
        // day already selected (the scroll has since carried the grid elsewhere), so it is counted, not read.
        var calendarJumpNonce by remember { mutableStateOf(0) }
        // PRD §8 "locked on task" (user spec 2026-09-26): the task a cell's "go to calendar" named, whether the
        // calendar is locked on it, and how many times it was asked for (each ask turns "Lock to now" off). View
        // state of this session, like the now-line lock — Compose-only, never persisted.
        var calendarLockTask by remember { mutableStateOf<TaskId?>(null) }
        var calendarLockOnTask by remember { mutableStateOf(false) }
        var calendarLockNonce by remember { mutableStateOf(0) }
        // PRD §8: the calendar's day / week display mode — a display preference of this device, stored with
        // the calendar's other two switches ([SchedulerState.calendarDayMode]) so it survives a relaunch.
        val calendarDisplayMode =
            if (schedulerState.calendarDayMode) org.example.project.ui.CalendarDisplayMode.Day
            else org.example.project.ui.CalendarDisplayMode.Week
        // PRD §6 (user rule 2026-10-06): a block dragged or resized on the calendar is a History Unit MADE IN THE
        // CALENDAR, whichever window had the focus when the press landed (a drag does not have to take it).
        fun dispatchFromCalendar(intent: SchedulerIntent) = vm.dispatch(SchedulerIntent.MadeIn(HistoryWindow.Calendar, "", intent))
        // A task cell's "go to calendar": the calendar opened (or brought back) and focused, locked on the task.
        fun goToCalendar(taskId: TaskId) {
            calendarLockTask = taskId
            calendarLockOnTask = true
            calendarLockNonce++
            if (!calendarOpen) calendarOpen = true
            focusWindow(FloatingWindow.Calendar)
            windowFrames.present(FloatingWindow.Calendar.name)
        }
        // PRD §11 (user rule 2026-10-03): a click on a notification. The platform has already brought the app to the
        // front (the desktop window, Android's activity); a notification about the schedule — the task to do now, a
        // screen break — also opens the calendar, or brings it back and focuses it, exactly as its lateral-menu button
        // does. Answered once, and on a cold Android launch as soon as the app has composed (a pending click waits).
        val notificationClick by org.example.project.scheduler.platform.NotificationClicks.pending.collectAsState()
        LaunchedEffect(notificationClick) {
            val click = notificationClick ?: return@LaunchedEffect
            if (click.target == org.example.project.scheduler.platform.NotificationTarget.Calendar) {
                openNewWindow(FloatingWindow.Calendar)
            }
            org.example.project.scheduler.platform.NotificationClicks.consume(click)
        }
        // The provisional panels the calendar last drew (the far-week plan), for the menu's question below.
        val latestProvisionalPanels = remember { mutableStateOf<List<TaskPanel>>(emptyList()) }
        val calendarGoTo = remember {
            CalendarGoTo(
                // Asked of the LIVE state as the menu opens — whichever tree drawing the menu stands in — and of
                // the provisional panels the calendar draws, which count as the task's panels too.
                reach = { taskId ->
                    CalendarLockDomain.reach(vm.state.value, taskId, clock.nowMillis(), latestProvisionalPanels.value)
                },
                go = { goToCalendar(it) },
            )
        }

        // PRD §15: screen breaks are projected from now to the END OF THE DISPLAYED SPAN. The scheduling
        // horizon is the floor, so the near term is unchanged and scrolling further out extends the
        // screen-break markers to span it. `nowMillis` is the same `now` the last schedule refresh used (the
        // tick loop sets both together), so within the schedule window this reproduces the screen-break
        // panels already in [schedulerState.panels] and only adds the tail.
        val visibleSpanStartMillis = visibleFirstDay.atStartOfDayIn(tz).toEpochMilliseconds()
        val visibleSpanEndMillis =
            visibleFirstDay.plus(visibleDayCount, DateTimeUnit.DAY).atStartOfDayIn(tz).toEpochMilliseconds()


        // `docs/scheduler_requirements.md` § *Progressive Calculation*: **$t_goal$**, the instant the
        // scheduler may stop at — the end of the timeline the calendar shows, or `now + 10 min` if further. It
        // follows the SCROLL: it is what the whole plan is computed out to.
        val goalEndMillis = SchedulerDomain.scheduleGoalEndMillis(nowMillis, visibleSpanEndMillis, tz)

        // PRD §9: tell the ENGINE which days are on screen, so its §9 refills materialize the work plan out
        // to exactly that span (capped at 168h) instead of computing schedule the user is not looking at.
        // Closing the calendar drops it back to the ten-minute floor the headless notification/cue paths
        // need. Growing it past the plan (scrolling further out) triggers one extension in the engine.
        LaunchedEffect(engine, calendarOpen, visibleSpanEndMillis) {
            engine.setCalendarHorizon(if (calendarOpen) visibleSpanEndMillis else null)
        }

        // PRD §9/§17 "schedule the whole span displayed": the engine materializes the work plan out to
        // $t_goal$, but never past the 168h CEILING ([SchedulerDomain.scheduleHorizonEndMillis]). When the
        // scroll reaches past that, compute the plan from the now-line out to the goal for DISPLAY — off the
        // UI thread (Dispatchers.Default) so a distant day "simply takes time to be displayed" instead of
        // freezing, keyed only on the displayed span so it doesn't rerun every now-tick. The result is never
        // stored in the state, so scrolling back to a near day just uses the near panels again and this far
        // fill is dropped ("erased") — no retained multi-week memory. Nearer days need none of this: the
        // engine already fills exactly to the goal (`engine.setCalendarHorizon` above), so
        // `schedulerState.panels` covers the whole displayed span.
        val nearHorizonEndMillis =
            SchedulerDomain.scheduleHorizonEndMillis(nowMillis, visibleSpanEndMillis, tz)
        val visibleSpanBeyondNearHorizon = goalEndMillis > nearHorizonEndMillis
        var farWeekPlan by remember { mutableStateOf<List<TaskPanel>?>(null) }
        var farWeekCalculating by remember { mutableStateOf(false) }
        // Keyed on the displayed END, not on `goalEndMillis`: beyond the ceiling the two are the same instant,
        // and below it the goal's ten-minute floor rolls with every now-tick.
        // Re-derived when the RULES change (a far week showing the plan of rules the user has since edited is
        // not the scheduler's answer), when the mode flips and when the environment the breaks read moves — the
        // same inputs, with the same values, the engine's own fills get.
        val farMode =
            SchedulerDomain.tpMode(
                SchedulerDomain.anyDeviceUnlockedAt(inactivityGaps, inactiveSince, activeSince, nowMillis),
                awayDeclared = userAway || accountAway,
            )
        val farSignature = remember(schedulerState) { SchedulerDomain.schedulingSignature(schedulerState) }
        LaunchedEffect(
            visibleSpanStartMillis, visibleSpanBeyondNearHorizon, visibleSpanEndMillis,
            farSignature, farMode, frozenBreaks, observedNoScreenEvidence, inactiveSince, activeSince,
        ) {
            if (!visibleSpanBeyondNearHorizon) {
                farWeekPlan = null
                farWeekCalculating = false
                return@LaunchedEffect
            }
            farWeekPlan = null
            farWeekCalculating = true
            // An EXTENSION of the materialized plan, never a re-plan: the far week is the continuation of the
            // rules already returned. When those rules repeat (`schedulerState.scheduleCycle`,
            // `docs/scheduler_score.md` § *The rules repeat*) the tail is unrolled, not searched, so a far week
            // costs a walk over the environment rather than an optimization.
            val fill =
                withContext(Dispatchers.Default) {
                    SchedulerDomain.fillSchedule(
                        schedulerState, nowMillis, timeZone = tz, horizonMillis = goalEndMillis,
                        liveRest = SchedulerDomain.liveRestGap(inactiveSince, activeSince, nowMillis),
                        noScreenEvidence = observedNoScreenEvidence,
                        keepExistingUntilMillis = SchedulerDomain.firstFreeMoment(schedulerState.panels, nowMillis),
                        tpMode = farMode,
                        frozenBreaks = frozenBreaks,
                    )
                }
            farWeekPlan = fill
            farWeekCalculating = false
        }
        // The source for the calendar's real task BLOCKS: the near panels as usual, or the async far-week fill
        // (falling back to the near panels while it is still computing, so past/pinned blocks stay visible).
        // `docs/scheduler_requirements.md`: pre-placed tasks and restrictive periods can repeat for ever, so every
        // occurrence of one over the displayed span is drawn — derived here like everywhere else it is asked for.
        val planPanels = if (visibleSpanBeyondNearHorizon) farWeekPlan ?: schedulerState.panels else schedulerState.panels
        // Held on what it reads, so the memos keyed on this list see the same instance until one of those moves.
        val workPlanPanels =
            remember(planPanels, visibleSpanStartMillis, visibleSpanEndMillis, tz) {
                org.example.project.scheduler.domain.PanelRepeats.expand(
                    planPanels, visibleSpanStartMillis - 24L * 60 * 60 * 1000, visibleSpanEndMillis, tz,
                )
            }

        // PRD §8 "locked on task": the instant held at the middle of the calendar — the task's panel closest to the
        // now-line, re-read whenever the panels or the display's now move (a panel the line drags, a new set of
        // rules), else the definitive-schedule front while no panel of it exists yet — and whether it is pending.
        // The PROVISIONAL panels count (the far-week plan past the front, by the same rules): a panel the calendar
        // draws is a panel to lock on, settled or not. One task's panels and records per reading, keyed on what
        // they are read from, never on a frame.
        val lockedTask = calendarLockTask
        val provisionalPanels = if (visibleSpanBeyondNearHorizon) farWeekPlan.orEmpty() else emptyList()
        SideEffect { latestProvisionalPanels.value = provisionalPanels }
        val calendarLockTarget: Pair<Long?, Boolean> =
            remember(lockedTask, schedulerState.panels, schedulerState.tasks, provisionalPanels, nowMillis, visibleSpanEndMillis) {
                if (lockedTask == null) {
                    null to false
                } else {
                    val panel =
                        CalendarLockDomain.closestPanelCenterMillis(schedulerState, lockedTask, nowMillis, provisionalPanels)
                    val front = SchedulerDomain.definitiveScheduleFrontMillis(nowMillis, visibleSpanEndMillis, tz)
                    (panel ?: CalendarLockDomain.lockCenterMillis(schedulerState, lockedTask, nowMillis, front)) to
                        (panel == null)
                }
            }
        // WHICH LAYER IS HATCHED WHERE IS THE DEVICE'S OWN OS HISTORY, not the app's activity heartbeats.
        // The app only knows when it was itself running and being touched; the question a layer asks is
        // whether the DEVICE was usable, so it is asked of the OS — the lock/unlock record where the platform
        // exposes one, else the sleep/awake record ([deviceLockedIntervals], which documents why Windows
        // forces the fallback). Two earlier readings were both wrong for the same reason: deriving the layer
        // from the app's active sessions painted an unbroken week of "nobody unlocked" (the app had only been
        // open ~15 minutes), and patching that by counting a banked task panel as evidence was a heuristic
        // standing in for the real source.
        //
        // Only THIS device can be asked. Every other kind gets `null` — "cannot tell" — and a device that
        // cannot tell is ASSUMED LOCKED, so running on a computer with no phone on the account hatches the
        // whole displayed past with the phone layer (the user's own example: the phone's data is not
        // available, so it is considered to have been locked all along).
        //
        // Bounded by the DISPLAYED span: there is no reason to ask about days that are not on screen, and
        // scrolling further back moves [displayFloorMillis] and asks again. Re-asked on a coarse bucket of
        // `now` as well, so a machine that goes to standby while the calendar is open is picked up without
        // spawning a query per tick — the call costs a process launch, so it never runs on the UI thread and
        // never on the display cadence.
        val ownLayer = remember { SchedulerDomain.layerForDeviceKind(currentDeviceKind()) }
        var lockedIntervals by remember { mutableStateOf<List<TaskTimeRange>?>(null) }
        // A scan that has not COME BACK yet is not the same answer as one that came back empty-handed: under
        // the assumed-LOCKED default the latter hatches the whole window, so treating "not asked yet" as
        // "cannot be asked" would flash a full-window hatch over the own layer on every launch, until the
        // first PowerShell query lands. Before that first answer the own layer draws nothing; a LATER re-scan
        // keeps showing the previous answer while it runs, so this only ever gates the first one.
        var lockHistoryScanned by remember { mutableStateOf(false) }

        // The heavy halves of the derivation below that do NOT read a task's TITLE, each held on what it
        // does read. A keystroke in the tree rewrites `tasks`, so the whole-derivation memo further down
        // misses on every letter — but the screen-break placement, the recurrence bars' environment and the
        // reminder regeneration are functions of the panels, the breaks, the §17 windows and the PLAN's view
        // of the tasks ([SchedulerDomain.planTasksOf] carries a task's priority, minimum and resilience, and
        // no title), none of which a rename moves. Held here, typing pays only what it actually changed.
        val dynamicBaseMemo = remember { CalendarDisplayMemo<SchedulerDomain.BreakEnvironment>() }
        val pastSidePanelsMemo = remember { CalendarDisplayMemo<List<TaskPanel>>() }
        val sidePanelsMemo = remember { CalendarDisplayMemo<List<TaskPanel>>() }
        val reminderPanelsMemo = remember { CalendarDisplayMemo<List<TaskPanel>>() }
        val derivedGapsMemo = remember { CalendarDisplayMemo<List<TaskTimeRange>>() }

        // The Search window's "Drag on the calendar": what its button and the calendar's day columns share
        // ([org.example.project.ui.CalendarElementDrag]). User rule 2026-10-02 / 2026-10-07: **while anything is
        // dragged the calendar is drawn the way a release would leave it** — here by running the release's OWN moves
        // ([calendarMoveOf]) on the stored state ([calendarMovePreview]), nothing saved: what the held blocks make
        // disappear disappears, what comes with them moves alongside, and all of it is back as they move on, because
        // every step starts again from the stored state.
        val calendarElementDrag = remember { org.example.project.ui.CalendarElementDrag() }
        val heldTargets = if (calendarElementDrag.blocks.isEmpty()) emptyList() else calendarElementDrag.targets()
        val heldInCalendar = calendarElementDrag.heldInCalendar
        fun previewOf(puts: List<org.example.project.ui.CalendarElementDrag.Held>): Pair<SchedulerState, org.example.project.scheduler.domain.FrozenScreenBreaks?> {
            val at = clock.nowMillis()
            return calendarMovePreview(
                schedulerState,
                frozenBreaks,
                puts.mapNotNull { held ->
                    calendarMoveOf(
                        schedulerState, held.block, held.range.startEpochMillis, held.range.endEpochMillis, held.allowOverlap, at, tz,
                    )
                },
                at,
            )
        }
        // The blocks the Search window's button holds: the calendar is the released one.
        val elementDragPreview =
            remember(schedulerState, frozenBreaks, heldTargets) {
                if (heldTargets.isEmpty()) null
                else previewOf(
                    heldTargets.map { (block, range) ->
                        org.example.project.ui.CalendarElementDrag.Held(block, range, !org.example.project.ui.isTaskPanelRecord(block))
                    },
                )
            }
        // The block a day column holds by its own gesture: the released calendar again, drawn UNDER the columns that
        // hold the press ([org.example.project.ui.LocalHeldCalendarRecords]) — their nodes must not move under it.
        val heldCalendarPreview =
            remember(schedulerState, frozenBreaks, heldInCalendar) { heldInCalendar?.let { previewOf(listOf(it)) } }
        // `docs/scheduler_requirements.md` § *Rule state input evolution*: the pre-placed tasks and periods are part of
        // the input that makes the scheduler run from scratch each time it changes — and a held block changes them.
        // So the state its release would leave is handed to the engine WHEN IT CHANGES ([SchedulerEngine.planHeld]),
        // and what the engine has found for it so far is what is drawn. The stored state is untouched: it remembers.
        val heldPreview = elementDragPreview ?: heldCalendarPreview
        androidx.compose.runtime.DisposableEffect(heldPreview) {
            engine.planHeld(heldPreview?.first)
            onDispose { }
        }
        val heldPlan by engine.heldPlan.collectAsState()
        fun plannedOf(preview: Pair<SchedulerState, org.example.project.scheduler.domain.FrozenScreenBreaks?>): SchedulerState =
            heldPlan?.takeIf { it.source === preview.first }?.planned ?: preview.first
        val shownState = elementDragPreview?.let(::plannedOf) ?: schedulerState
        val shownFrozenBreaks = if (elementDragPreview != null) elementDragPreview.second else frozenBreaks

        // ---- The calendar's derivation, as a function of the now-line -----------------------------------
        //
        // Everything the calendar draws, read out of the set of rules AT ONE INSTANT of the now-line. It is a
        // function rather than a run of vals for one reason (ADR 0009 § *Everything that follows the line
        // moves continuously*): the calendar reads it TWICE, at the line and one millisecond later, and the
        // difference between the two readings is the motion of every edge — which edges follow the line and
        // which stay put. One derivation, asked twice; never a second copy of it.
        fun deriveCalendarDisplay(
            nowMillis: Long,
            /** The state drawn: the stored one, or the one a held drag's release would leave ([calendarMovePreview]). */
            displayState: SchedulerState = schedulerState,
            displayFrozenBreaks: org.example.project.scheduler.domain.FrozenScreenBreaks? = frozenBreaks,
        ): CalendarDisplay {
            // What the Search window's filter and the reducer's edges read is the STORED calendar's, never a preview's.
            val layersHolder = if (displayState === schedulerState) calendarLayers else CalendarLayersHolder()
            // The plan's panels as drawn: the stored ones (held above), or a preview's own.
            val shownPlanPanels =
                if (displayState === schedulerState) workPlanPanels
                else org.example.project.scheduler.domain.PanelRepeats.expand(
                    displayState.panels, visibleSpanStartMillis - 24L * 60 * 60 * 1000, visibleSpanEndMillis, tz,
                )
            val declaredAwayRegions =
                SchedulerDomain.declaredAwayRegions(declaredAwaySpans, declaredAwaySince, nowMillis)
            // `side-dev/README.md` § *$t_p$ 3 modes*: mode 1 while a device of the account is unlocked; otherwise
            // mode 3 if the user pressed "I'm away" (a break is being TAKEN) and mode 2 if they did not. The SAME
            // reading the reducer's fills use (`SchedulerReducer.tpMode`, injected by the engine over these same
            // flows) — the display and the plan must not answer it differently, or the calendar would draw the
            // three dynamic periods somewhere the schedule did not put them.
            val baseTpMode =
                SchedulerDomain.tpMode(
                    SchedulerDomain.anyDeviceUnlockedAt(inactivityGaps, inactiveSince, activeSince, nowMillis),
                    // This device's own flag OR the account's, exactly as the engine reads it: mode 3 is *at least
                    // one device away and every other one locked*, so a peer holding the account away puts this
                    // one in mode 3 too, with its own button off.
                    awayDeclared = userAway || accountAway,
                )
            // …and held in mode 3 inside a 20 s break it entered at a screen, as the break machine the line carries
            // says (`docs/scheduler_requirements.md` § *Mode switching*), exactly as the engine reads it.
            val tpMode = SchedulerDomain.effectiveTpMode(displayFrozenBreaks, baseTpMode, nowMillis)
            // Every forward DISPLAY projection stops here: the end of the displayed span, floored at the horizon
            // a closed calendar still needs. Never `now + 168h` unconditionally — a grid sitting on today
            // projects ~24h of sleep bands, not a week of them (PRD §9 "the horizon follows what is displayed").
            val screenBreakHorizonMillis =
                maxOf(nowMillis + SchedulerDomain.MIN_SCHEDULE_HORIZON_MILLIS, visibleSpanEndMillis)

            // The user's sleep windows — shown as "Sleep" blocks and avoided by the regular task fill (so no task
            // is scheduled while asleep). Screen breaks, by contrast, DO project across sleep so their eye-rest / pose
            // cues still render over the "Sleep" band for a user working through the night (PRD §15). The sleep
            // SCHEDULE is projected only from `now` FORWARD (PRD §17): the past is not assumed to have been slept —
            // an emptied DB's past is Inactivity + No-screen. Past sleep is instead a recorded fact: the persisted
            // materialized "Sleep" panels the engine banks when a scheduled window elapses unattended, plus the
            // live band `[sleepingSince, now]` that grows while the Sleep toggle is on (finalized when it goes off).
            val liveSleepBand =
                displayState.sleepingSinceMillis
                    ?.takeIf { it < nowMillis }
                    ?.let {
                        listOf(
                            TaskPanel(
                                id = "sleep-live",
                                taskId = null,
                                title = "Sleep",
                                startEpochMillis = it,
                                endEpochMillis = nowMillis,
                                sleep = true,
                            ),
                        )
                    }
                    ?: emptyList()
            val displaySleepPanels =
                SchedulerDomain.sleepPanels(displayState.sleep, nowMillis, screenBreakHorizonMillis, tz) +
                    displayState.panels.filter { it.sleep && it.endEpochMillis <= nowMillis } +
                    liveSleepBand
            val displaySleepRegions =
                displaySleepPanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
            // `side-dev/README.md` § *3 Dynamic Restrictive Period*: the three are placed by the recurrence bars
            // over the environment they interrupt, so the display hands the placement that environment — the
            // standing restrictive periods (the user's own and the §17 sleep windows) and the tasks, which is
            // what decides whether a stretch is a REST (nobody can run there) or merely a period somebody is
            // resilient to.
            val displayDynamicTasks =
                Perf.measure("display.planTasks") { SchedulerDomain.planTasksOf(schedulerState, nowMillis) }
            // `side-dev/README.md` § *3 Dynamic Restrictive Period*: the three are placed by the recurrence bars
            // over the environment they interrupt, and the display asks the ONE funnel the fill, the cue sweep and
            // the banking ask ([SchedulerDomain.breakEnvironment]) — so a break drawn here is the break the plan
            // was built around, however far ahead the calendar looks. It reaches to the end of the displayed span,
            // and continues from the breaks the line has banked ([frozenBreaks]).
            val breakEnvUntil = maxOf(nowMillis, visibleSpanEndMillis)
            val displayBreakEnv =
                dynamicBaseMemo.get(
                    listOf(
                        nowMillis, breakEnvUntil, tz, baseTpMode,
                        displayState.panels, displayState.periodKindStyles, displayState.sleep,
                        inactiveSince, activeSince, observedNoScreenEvidence, displayFrozenBreaks, displayDynamicTasks,
                    ),
                ) {
                    Perf.measure("display.dynamicBase") {
                        SchedulerDomain.breakEnvironment(
                            state = schedulerState,
                            nowMillis = nowMillis,
                            untilMillis = breakEnvUntil,
                            timeZone = tz,
                            liveRest = SchedulerDomain.liveRestGap(inactiveSince, activeSince, nowMillis),
                            noScreenEvidence = observedNoScreenEvidence,
                            mode = baseTpMode,
                            frozen = displayFrozenBreaks,
                            tasks = displayDynamicTasks,
                        )
                    }
                }
            // `docs/scheduler_requirements.md` § *frozen past*: the elapsed part of the visible window — what the three
            // dynamic periods DID over a stretch the line has already crossed — is the banked record
            // ([SchedulerDomain.stepScreenBreaks]) and nothing else: never the placement re-run with the mode or the
            // environment of now, which drew breaks that never happened.
            val displayPastSidePanels =
                pastSidePanelsMemo.get(
                    listOf(
                        nowMillis, visibleSpanStartMillis, visibleSpanEndMillis,
                        displayState.screenBreaks, displayFrozenBreaks,
                    ),
                ) {
                    Perf.measure("display.pastSidePanels") {
                        if (visibleSpanStartMillis >= nowMillis) {
                            emptyList()
                        } else {
                            SchedulerDomain.takenScreenBreakPanels(
                                displayState.screenBreaks,
                                visibleSpanStartMillis,
                                minOf(nowMillis - 1, visibleSpanEndMillis),
                                frozen = displayFrozenBreaks,
                            )
                        }
                    }
                }
            // The three ahead of the line are ONE walk from the line to the end of the displayed span, whatever
            // the span: a week the calendar has navigated to is where the walk that starts at the line puts them
            // — never a grid restarted at the week's own edge, which is a different grid (it put breaks where the
            // plan had not cut its holes). Past the 168 h ceiling the far-week fill below makes that walk off the
            // UI thread and its breaks are taken from it, so the plan and the breaks drawn come out of one call.
            val displaySidePanels =
                sidePanelsMemo.get(
                    listOf(
                        nowMillis, visibleSpanStartMillis, visibleSpanEndMillis, baseTpMode,
                        displayState.screenBreaks, displayBreakEnv, displayPastSidePanels,
                        visibleSpanBeyondNearHorizon, farWeekPlan,
                    ),
                ) {
                    Perf.measure("display.sidePanels") {
                        val ahead =
                            when {
                                visibleSpanEndMillis <= nowMillis -> emptyList()
                                visibleSpanBeyondNearHorizon ->
                                    farWeekPlan.orEmpty().filter {
                                        it.screenBreak && it.startEpochMillis >= nowMillis &&
                                            it.endEpochMillis > visibleSpanStartMillis &&
                                            it.startEpochMillis < visibleSpanEndMillis
                                    }
                                else ->
                                    SchedulerDomain.screenBreakPanels(
                                        screenBreaks = displayState.screenBreaks,
                                        nowMillis = nowMillis,
                                        horizonMillis = visibleSpanEndMillis,
                                        basePeriods = displayBreakEnv.periods,
                                        // The machine is moved in the mode the devices report: the look-away hold is its own.
                                        mode = baseTpMode,
                                        frozen = displayBreakEnv.frozen,
                                    ).filter {
                                        it.startEpochMillis >= nowMillis && it.endEpochMillis > visibleSpanStartMillis
                                    }
                            }
                        displayPastSidePanels + ahead
                    }
                }

            // PRD §15: a screen break the now-line has REACHED is a period accepting no task, and in `t_p` mode 1
            // it slides right with the now-line for as long as it stays owed. The plan under it was materialized
            // by a fill that ran at a rule change (CLAUDE.md: time passing never re-plans), so the auto panels
            // have to be cut out of the break's span here, on the display side — the reference's sliding-period regime, pinned to the
            // plan's own origin (`side-dev/scheduler_logic.py` tests 10–11).
            val displayWorkPlanPanels =
                Perf.measure("display.clipPlanForBreak") {
                // `docs/scheduler_requirements.md` § *mode 1*: where a no-screen period gives way to a line at a screen,
                // the rules hold the task AT THE LINE (line-bound runs), so the panels are drawn as the rules give them
                // at the line — nothing is laid in a period still ahead, and nothing is hidden.
                SchedulerDomain.atLine(
                    shownPlanPanels, displaySidePanels, nowMillis,
                    // The break shapes + the task attributes, so a run gives way only where a break REFUSES its task:
                    // a pose's open period keeps the off-screen work it accepts, the part the band draws hollow.
                    displayState.screenBreaks, displayState.tasks,
                )
                }

            // PRD §14: reminder flags are calculated for the WHOLE displayed span — from now to the end of the
            // days the calendar is showing — so scrolling to a day shows its reminders. Like the screen-break
            // projection they are regenerated for display (anchored at today's midnight, out to the displayed
            // span's end), with each tag's checked state carried over from the stored reminder panels by
            // matching its deterministic id.
            val todayStartMillis = today.atStartOfDayIn(tz).toEpochMilliseconds()
            val reminderHorizonDays =
                ((visibleSpanEndMillis - todayStartMillis) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
            val displayReminderPanels =
                reminderPanelsMemo.get(
                    listOf(
                        nowMillis, todayStartMillis, reminderHorizonDays,
                        displayState.panels, displayState.chores,
                    ),
                ) {
                Perf.measure("display.reminderPanels") {
                    SchedulerDomain.regenerateChorePanels(
                        displayState.panels, displayState.chores, todayStartMillis, reminderHorizonDays,
                        nowMillis,
                    ).filter { SchedulerDomain.isReminder(it) }
                }
                }

            // PRD §18: every ring of every alarm that falls in the WEEK ON SCREEN — past ones included, since an
            // alarm is a fixed wall-clock boundary and a ring that already went off stays where it happened. The
            // days each alarm is triggered on are its own synced [AlarmEntry.days], so every device draws the same
            // markers. Bounded by the displayed window per the CLAUDE.md hot-path rule (cost follows the screen:
            // days-on-screen × alarms), not by the account's history — and independent of `nowMillis`, so the
            // per-tick recompute is a fixed, tiny amount of work.
            val displayAlarmOccurrences =
                AlarmDomain.occurrencesInWindow(
                    displayState.alarms, visibleSpanStartMillis, visibleSpanEndMillis, tz,
                )

            // PRD §18 Timers: the same marker for a RUNNING timer's ring, on the same window. A timer has at
            // most one instant and only while it is counting down ([TimerEntry.endsAtMillis] is stored, not
            // derived from the calendar), so an idle or paused row draws nothing and a ring that already went
            // off leaves nothing behind — the ring resets the row. Bounded by the displayed window like the
            // alarms', and independent of `nowMillis`: a running timer writes nothing on a tick, so the marker
            // is recomputed only when the timers themselves or the displayed span change.
            val displayTimerOccurrences =
                TimerDomain.occurrencesInWindow(
                    displayState.timers, visibleSpanStartMillis, visibleSpanEndMillis,
                )

            // PRD §15/§17: where the account was demonstrably ACTIVE in the past window, the "Sleep" band is carved
            // to show a gap (the user kept working through the scheduled sleep). Account-wide past activity is the
            // complement of the account-wide pauses over the derive window `[now − 168h, now]`; where there is no
            // pause the account was active. The device's own OPEN session `[activeSince, now]` is added so the band
            // retracts continuously to the now-line while the user works — a local-only, non-syncing display change.
            //
            // The complement is only trustworthy once real pause data exists: an EMPTY `inactivityGaps` means "no
            // evidence yet" (the startup transient before the first derive, or a store-less web install), NOT "the
            // account was active all week", so it must NOT carve every past night. Carving is conservative — only
            // known activity (the derived pauses' complement when present, plus this device's live session) gaps it.
            // The gaps the calendar actually draws: the derived account-wide pauses plus the live tail of the
            // pause THIS device is observing right now (from the last finalize to the now-line, capped at the
            // reopened session once the user returns) — so the band grows behind an advancing now-line instead
            // of appearing whole at the next derive. The tail also joins the complement below, so an ongoing
            // pause is never mistaken for activity that would carve the "Sleep" band.
            // PRD §12/§15 on-demand past fill: the engine's [inactivityGaps] only derives back 168h, so a week older
            // than that would render empty. Re-derive the account-wide pauses for DISPLAY from the full stored
            // session history over a floor that reaches the displayed span — any past day then fills on demand (an
            // empty DB ⇒ the whole span is one open-ended inactivity gap). Recomputed every frame from the
            // scrolled span, so nothing older than what is displayed is retained (memory). Over the near-term
            // window this reproduces the engine's value (same sessions); it only extends coverage further back.
            val displayFloorMillis =
                minOf(nowMillis - SchedulerDomain.SCHEDULE_HORIZON_MILLIS, visibleSpanStartMillis)
            val displayDerivedGaps =
                derivedGapsMemo.get(listOf(nowMillis, displayFloorMillis, activeSessions)) {
                Perf.measure("display.derivePauses") {
                    SchedulerDomain.derivePauses(
                        activeSessions.map { TaskTimeRange(it.startMillis, it.endMillis) },
                        displayFloorMillis,
                        nowMillis,
                    )
                }
                }
            val displayInactivityGaps =
                SchedulerDomain.displayInactivityGaps(displayDerivedGaps, inactiveSince, activeSince, nowMillis)
            // The account-wide NO-SCREEN periods over the displayed past — the recorded pauses, carved around
            // the §17 sleep windows. Nothing draws these as a band any more (the calendar shows the two layers
            // instead, and their overlap IS this set); they are kept for the diagnostics timeline, which is what
            // reconstructs a reported calendar anomaly without asking the user to describe the screen. Sub-minute
            // remnants are noise, not a real away-from-every-device pause — e.g. the few seconds between the §17
            // scheduled wake and a freshly-opened account's first session ([MIN_INACTIVITY_BAND_MILLIS]).
            val noScreenPeriods =
                SchedulerDomain.subtractRegions(displayInactivityGaps, displaySleepRegions)
                    .filter { it.endEpochMillis - it.startEpochMillis >= SchedulerDomain.MIN_INACTIVITY_BAND_MILLIS }
            // Diagnostics timeline (scripts/collect-diagnostics.bat): record the exact bands the calendar is
            // about to render, so an anomaly is reconstructable after the fact without describing the screen.
            // Keyed on a quantized INTERIOR-edge signature: the outermost edges track the sliding 168h window /
            // now-line every tick and would spam a line per second, but any real change — a band appearing,
            // vanishing, or a hole opening up inside the coverage — moves an interior edge or a count.
            val bandSignature = diagnosticsBandSignature(noScreenPeriods)
            // PRD §12 "∞ start": the earliest layer region is open-ended into the past when nothing precedes it
            // — no activity session, task record, or user-authored/materialized panel begins before it (an
            // emptied DB has none). Its start then renders as "∞" instead of a wall-clock time (which, clamped
            // to the 168h derive floor, would read the same hour:minute as `now`).
            val earliestEvidenceMillis =
                listOfNotNull(
                    activeSessions.minOfOrNull { it.startMillis },
                    displayState.panels.filterNot(SchedulerDomain::isRegeneratedPanel)
                        .minOfOrNull { it.startEpochMillis },
                    displayState.tasks.values.flatMap { it.record }.minOfOrNull { it.startEpochMillis },
                ).minOrNull()
            // PRD §8/§9: **a stretch carrying BOTH layers is a "no on-screen task" period** (ADR 0002) — so the
            // intersection of the two layers' evidence is exactly that period, read for the panels rather than for
            // the hatching ([SchedulerDomain.observedNoScreenRegions], the same function the record bank asks).
            // It overrides the on-screen task panels it covers, below, which is the half of the rule the app was
            // missing: §9 already refused to BANK a record over one of these stretches, and the panel that record
            // would have come from went on being drawn across the hatch anyway.
            //
            // A FAILED query is not evidence. `null` means "assumed locked throughout" to the layers, where
            // hatching a stretch nobody can vouch for is honest — but here it would erase every on-screen panel in
            // the displayed past on one PowerShell hiccup. So the OWN scan must SUCCEED to say anything, exactly as
            // `SchedulerEngine.readNoScreenEvidence` requires; a PEER's null keeps its assumed-locked meaning.
            //
            // The user's own "I'm away" stretches are the one thing here that is neither: not the OS's answer, and
            // not a rule's promise either — the user SAID they were not at this screen, which is the very question
            // the scan asks. So they hold whether or not the scan came back, and they ride the same regions the
            // layer below hatches (one record, so the hatch and the cut cannot disagree).
            val ownScannedLocked = lockedIntervals?.takeIf { lockHistoryScanned }
            val ownIsComputer = ownLayer == SchedulerDomain.ActivityLayer.NoComputerUnlocked
            // Where this device's OS log KNOWS it was unlocked — known only once the history was read; a peer's
            // layer is never known unlocked. With "I'm away" off that is the past the line crossed in MODE 1, where
            // a period carrying "no screen" retracted to the line (`scheduler.md` § *A mode-1 line retracts*): the
            // Sleep band, the wind-down hour and the layers they lay all give it up.
            val ownKnownUnlocked =
                SchedulerDomain.knownUnlockedRegions(ownScannedLocked, displayFloorMillis, nowMillis)
            val atScreenPast = SchedulerDomain.subtractRegions(ownKnownUnlocked, declaredAwayRegions)
            val placedStanding =
                SchedulerDomain.placedTasksOutsideBreaks(displayState.panels, displaySidePanels, displayState.tasks)
            // The live band of the Sleep toggle is the user's own word, like a drawn period: it stays whole.
            val retractedSleepPanels =
                SchedulerDomain.retractOverAtScreenPast(
                    displaySleepPanels.filterNot { it.id == "sleep-live" }, atScreenPast, displayState.periodKindConfig,
                    // …and to a task panel the user placed, where it stands: not under a break that refuses it — the
                    // panel is retracted there, and the band is what shows behind the break.
                    placedTasks = placedStanding, tasks = displayState.tasks,
                ) + liveSleepBand
            val observedNoScreenRegions =
                if (ownScannedLocked == null && declaredAwayRegions.isEmpty()) {
                    emptyList()
                } else {
                    val locked = ownScannedLocked ?: emptyList()
                    SchedulerDomain.observedNoScreenRegions(
                        computerLocked = if (ownIsComputer) locked else null,
                        phoneLocked = if (ownIsComputer) null else locked,
                        sinceMillis = displayFloorMillis,
                        untilMillis = nowMillis,
                        computerAway = if (ownIsComputer) declaredAwayRegions else emptyList(),
                        phoneAway = if (ownIsComputer) emptyList() else declaredAwayRegions,
                        config = displayState.periodKindConfig,
                    )
                }

            // Done periods (PRD §8 task record, green) plus every calendar panel (PRD §8/§9 — auto and
            // user-authored, uniform blocks) drawn the same way; reminders (PRD §14) and screen breaks (PRD §15)
            // span the focused week.
            val baseCalendarRecords =
                Perf.measure("display.baseCalendarRecords") {
                (
                displayState.tasks.values.flatMap { task ->
                    // BOUNDED BY THE VISIBLE WINDOW, never by the account's history (CLAUDE.md hot path,
                    // ADR 0009). A task's `record` is every stretch of it ever worked, and this ran over all
                    // of them on every reading of the line: the release account carried 2333 of them against
                    // the ~150 a displayed week holds, and each one was clipped, wrapped and handed to the
                    // calendar to cull again. Nothing outside `[displayFloor, the end of the days on screen]`
                    // can be drawn, and scrolling re-derives (the span is one of the memo's own keys).
                    SchedulerDomain.clipRecordsForObservedNoScreen(
                        task.record.filter {
                            it.endEpochMillis >= displayFloorMillis && it.startEpochMillis <= visibleSpanEndMillis
                        },
                        task,
                        observedNoScreenRegions,
                    )
                        .map { CalendarRecord(title = task.title, range = it, taskId = task.id) }
                } + mergePanelsForDisplay(
                    // PRD §8/§9: an on-screen task's panel is CUT where the devices observed nobody at a screen —
                    // the same "a stretch carrying both layers is a no-screen period" the hatching draws, applied
                    // to the panels it covers. An off-screen task is left alone (§9 lets it run in one).
                    SchedulerDomain.retractOverAtScreenPast(
                        SchedulerDomain.clipPanelsForObservedNoScreen(
                            // The user's own periods as a mode-1 line leaves them (`afterCrossings`): the one it is
                            // in reads ]line; its end], one it has left is gone.
                            SchedulerDomain.afterCrossings(
                                displayWorkPlanPanels, displayState.periodCrossings, displayState.periodKindConfig,
                                live = atScreenSince?.takeIf { it < nowMillis }?.let { TaskTimeRange(it, nowMillis) },
                            ),
                            displayState.tasks, observedNoScreenRegions,
                        ),
                        atScreenPast,
                        displayState.periodKindConfig,
                        placedTasks = placedStanding, tasks = displayState.tasks,
                    ),
                    displayReminderPanels, displaySidePanels, retractedSleepPanels,
                    displayState.showScreenBreaks, displayState.showReminders,
                    displayState.screenBreaks,
                    // § *Progressive Calculation*: the same front the derived inactivity bands stop at, below —
                    // where the plan stops being the scheduler's settled answer and starts being the far-week
                    // fill's display-only continuation of it.
                    definitiveFrontMillis =
                        SchedulerDomain.definitiveScheduleFrontMillis(nowMillis, visibleSpanEndMillis, tz),
                )
                )
                }
            // PRD §8: **the whole timeline is accounted for** — every stretch is either a TASK PANEL or a
            // restrictive period — so whatever the panels leave uncovered is drawn as a derived INACTIVITY
            // period (the user's rule: "the only stretches with no task panel and no inactivity period are the
            // ones the scheduler has no definitive schedule for yet").
            //
            // The far end is therefore the **definitive-schedule front**, not the now-line: the past's empty
            // stretches and the future's are the same statement, derived from what happened and from the plan
            // respectively, and the one place the rule may fail to hold is `[front, +∞)` where there is no answer
            // yet to give. (Bounded by the visible window like every other display derivation, ADR 0009: the
            // front is already capped at the 168 h ceiling of what is on screen.)
            //
            // "Sleep" is subtracted rather than relabelled — the §17 bands draw and label themselves — as are the
            // user's own periods, which are real panels. A screen break and a no-screen period are deliberately
            // NOT subtracted: neither is a task panel, so idle time inside one is still idle (and the break's own
            // band draws over whatever is underneath it). See [derivedInactivityBands], which also drops the
            // sub-minute seams between adjacent panels.
            //
            // Display-only: no `entryId`, so the period is neither removable nor separately draggable — until the
            // user EDITS it, which is what lays the real panel (the period editor's Save). ADR 0002: an
            // observation stays derived, a statement is stored.
            // The Inactivity the hole rule laid where a dragged block stood is drawn as part of the derived band, not as a
            // box beside it ([SchedulerDomain.isDerivedInactivityFill], anomaly 2026-10-10).
            val inactivityFillIds =
                displayState.panels.filter(SchedulerDomain::isDerivedInactivityFill).mapTo(HashSet()) { it.id }
            val drawnBaseRecords =
                if (inactivityFillIds.isEmpty()) baseCalendarRecords
                else baseCalendarRecords.filterNot { it.entryId != null && it.entryId in inactivityFillIds }
            val pastCoveredRegions =
                drawnBaseRecords
                    .filterNot { it.reminder || it.alarm || it.screenBreak || it.noScreen }
                    .map { it.range }
            val inactivityUntilMillis =
                maxOf(nowMillis, SchedulerDomain.definitiveScheduleFrontMillis(nowMillis, visibleSpanEndMillis, tz))
            val pastInactivityRecords =
                Perf.measure("display.inactivityBands") {
                SchedulerDomain.derivedInactivityBands(pastCoveredRegions, displayFloorMillis, inactivityUntilMillis)
                    .let { gaps ->
                        // PRD §12 "∞ start": the earliest band is open-ended into the past when nothing precedes it.
                        val open = SchedulerDomain.derivedBandsOpenStart(gaps, earliestEvidenceMillis)
                        gaps.map { gap ->
                            CalendarRecord(
                                title = "Inactivity",
                                range = gap,
                                inactivity = true,
                                restrictiveKind = PeriodKinds.INACTIVITY,
                                openStart = open != null && gap.startEpochMillis == open,
                            )
                        }
                    }
                }
            // PRD §8 calendar LAYERS: two decorative oblique-line layers over the timeline — one for "no computer
            // was unlocked", one (opposite slope) for "no phone was unlocked". Where BOTH fall, the stretch is a
            // NO-SCREEN period (the user's own definition), which is the same set §9 places the off-screen tasks
            // in and §15 counts as a pause.
            //
            // Each layer has two sources, answering different halves of the timeline:
            //   • the PAST is evidence — that device kind's own OS lock/standby history, or, when no device of the
            //     kind can be asked at all, the whole asked past (a device nobody can vouch for was locked).
            //   • the FUTURE is assertion — nothing has been observed yet, so only what the rules PROMISE will be
            //     unlocked-by-nobody counts: the §17 sleep windows and the §15 screen breaks, **each only when
            //     its kind carries the layer** (the period edit window's companions,
            //     [org.example.project.scheduler.domain.PeriodKindConfig.assertedLayers]). By default neither does:
            //     a sleep window carries a "no screen" period, which is its own statement with its own drawing,
            //     and a break is an inactivity period, which carries nothing.
            // The user's own periods assert a layer by their KIND and its companions (PRD §8,
            // [SchedulerDomain.assertedLayerRanges]) — a "no computer unlocked" / "no phone unlocked" period is the
            // user saying ONE of them. The derivation never overwrites either.
            val periodKindConfig = displayState.periodKindConfig
            val sleepAssertedLayers = periodKindConfig.assertedLayers(PeriodKinds.SLEEP)
            val futureSleepRegions =
                if (sleepAssertedLayers.isEmpty()) {
                    emptyList()
                } else {
                    displaySleepRegions.filter { it.endEpochMillis > nowMillis }
                        .map { TaskTimeRange(maxOf(it.startEpochMillis, nowMillis), it.endEpochMillis) }
                }
            val layerRecords =
                Perf.measure("display.layerRecords") {
                // Every kind the periods the user drew state, through the account's rules — the away spells counted
                // as this device's fake layer, so a drawn "no screen" does not lay "no computer unlocked" over an
                // away spell ("except when the 'or' condition is already verified"). And where this device's OS log
                // KNOWS it was unlocked in the past, the "or" lays "not on a computer" instead (user rule,
                // 2026-10-01).
                val stated =
                    SchedulerDomain.statedKindRegions(
                        // The layers a period lays stop where the period does: less what a mode-1 line crossed of it.
                        SchedulerDomain.afterCrossings(
                            shownPlanPanels, displayState.periodCrossings, periodKindConfig,
                            live = atScreenSince?.takeIf { it < nowMillis }?.let { TaskTimeRange(it, nowMillis) },
                        ),
                        periodKindConfig,
                        away = mapOf(PeriodKinds.fakeLayerKind(ownLayer) to declaredAwayRegions),
                        knownAbsent = mapOf(PeriodKinds.layerKind(ownLayer) to ownKnownUnlocked),
                        atScreenPast = atScreenPast,
                        // What the Sleep window gives way to is the placed panels where they STAND — as for its band.
                        placedTasks = placedStanding,
                        // The breaks drawn: each carries a "no screen" period, which lays the layers like a drawn one.
                        breaks =
                            if (displayState.showScreenBreaks) {
                                displaySidePanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
                            } else {
                                emptyList()
                            },
                    )
                SchedulerDomain.ActivityLayer.entries.flatMap { layer ->
                    val layerLocked =
                        when {
                            layer != ownLayer -> null // no channel carries a peer's lock history
                            lockHistoryScanned -> lockedIntervals
                            else -> emptyList() // not asked yet ≠ cannot be asked
                        }
                    // The "I'm away" stretches belong to THIS device's FAKE layer alone — a press on the computer says
                    // nothing about the phone, and a device declared away is unlocked (`docs/scheduler_requirements.md`
                    // § *$now line$ 3 modes*: "not on a computer", drawn as its own band below).
                    val layerAway = if (layer == ownLayer) declaredAwayRegions else emptyList()
                    // What the RULES promise for this layer: the breaks and the future sleep windows whose kind
                    // carries it.
                    val layerAssertedAll =
                        SchedulerDomain.mergeOccupied(
                            displaySidePanels
                                .filter {
                                    it.restrictiveKind.isNotEmpty() &&
                                        layer in periodKindConfig.assertedLayers(it.restrictiveKind)
                                }
                                .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) } +
                                (if (layer in sleepAssertedLayers) futureSleepRegions else emptyList()),
                        )
                    // The periods the user DREW asserting this layer (PRD §8, by their kind). Hoisted because
                    // it is the second half of what the dots below are asked about: a hand-drawn period and an
                    // away spell are the same thing said two ways — the user's own word about who was at a
                    // screen — while everything in [layerAssertedAll] is the APP promising something.
                    val layerStated = stated[PeriodKinds.layerKind(layer)].orEmpty()
                    val regions =
                        SchedulerDomain.layerRegions(
                            lockedIntervals = layerLocked,
                            assertedRegions = layerAssertedAll + layerStated,
                            sinceMillis = displayFloorMillis,
                            untilMillis = nowMillis,
                        )
                    // The FAKE layer: the away spells and the periods drawn of the fake kind, wherever the real layer
                    // is not ("'not on a computer' can't be with 'no computer unlocked'").
                    val fakeRegions =
                        SchedulerDomain.fakeLayerRegions(
                            layerAway + stated[PeriodKinds.fakeLayerKind(layer)].orEmpty(),
                            regions,
                        )
                    // `docs/scheduler_requirements.md` § *$now line$ 3 modes* + PRD §8: the sub-stretches of
                    // this hatch that a device of the layer's kind really was UNLOCKED for, the user having said
                    // otherwise — the "I'm away" button (mode 3) or a period they drew over hours already
                    // elapsed. They are drawn DOTTED, so the band is emitted as one record per stretch of each
                    // kind rather than one per merged region. Nothing else about them differs: same title, same
                    // layer, so the hover bubble names the layer once whichever piece the cursor is over (the
                    // time it reads beside it is that piece's, which is the stretch the dots are true of).
                    //
                    // The dots are NOT the blue outline again ("an outline says who put this here", 2026-09-12,
                    // which is why they were deleted that morning and restored that afternoon): an outline says
                    // a hand placed this — the away button places no period at all, and a drawn period wears one
                    // whether or not anything contradicts it — while the dots say the machine's own log
                    // disagrees. Both marks, two questions.
                    val declared =
                        SchedulerDomain.declaredLayerRegions(
                            regions = regions,
                            declaredRegions = layerStated,
                            lockedIntervals = layerLocked,
                            sinceMillis = displayFloorMillis,
                            untilMillis = nowMillis,
                        )
                    // PRD §12 "∞ start": the earliest layer region is open-ended into the past when nothing at all
                    // precedes it (an emptied DB) — its drawn start is only the display floor, so it reads "∞".
                    // Asked of the MERGED regions, so splitting a band for the dots cannot move the ∞.
                    val layerOpenStart = SchedulerDomain.derivedBandsOpenStart(regions, earliestEvidenceMillis)
                    // "Not on a computer" IS the dotted oblique lines (user, 2026-10-01): a stretch the user said nobody
                    // was at while the OS saw the device unlocked is that kind, drawn and named as its fake band — not a
                    // dotted copy of the real layer under the real layer's name.
                    val solid = SchedulerDomain.subtractRegions(regions, declared)
                    solid.map { region ->
                        CalendarRecord(
                            title = layer.calendarLabel,
                            range = region,
                            layer = layer,
                            openStart = layerOpenStart != null && region.startEpochMillis == layerOpenStart,
                        )
                    } +
                        SchedulerDomain.mergeOccupied(fakeRegions + declared).map { region ->
                            CalendarRecord(
                                title = PeriodKinds.periodTitle(PeriodKinds.fakeLayerKind(layer)),
                                range = region,
                                layer = layer,
                                layerFake = true,
                            )
                        }
                }
                }
            layersHolder.records = layerRecords
            layersHolder.config = periodKindConfig
            // Where each stands: under a break that refuses it a placed panel is not there, and takes no layer.
            layersHolder.placed =
                SchedulerDomain.placedTasksOutsideBreaks(
                    shownPlanPanels.filter { SchedulerDomain.isUserPlaced(it) && !it.isRestrictivePeriod },
                    if (displayState.showScreenBreaks) displaySidePanels else emptyList(),
                    displayState.tasks,
                )
            layersHolder.tasks = displayState.tasks
            layersHolder.breaks =
                if (displayState.showScreenBreaks) {
                    displaySidePanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) to it.restrictiveKind }
                } else {
                    emptyList()
                }
            val calendarRecords = drawnBaseRecords + pastInactivityRecords + layerRecords +
                displayAlarmOccurrences.map { occurrence ->
                    // PRD §18: a zero-duration marker at the ring instant. Named by the alarm's label, falling
                    // back to its time of day so a nameless alarm still reads as something on the calendar.
                    CalendarRecord(
                        title =
                            occurrence.entry.label.ifBlank {
                                formatAlarmClockTime(occurrence.entry.timeOfDayMinutes)
                            },
                        range = TaskTimeRange(occurrence.instant, occurrence.instant),
                        entryId = occurrence.entry.id,
                        entryIds = listOf(occurrence.entry.id),
                        alarm = true,
                        // PRD §8: ORANGE — an alarm is a RULE (a time of day on a set of weekdays), like a
                        // §17 sleep window, and this marker is one occurrence of it. The calendar's element
                        // window can now state one, which does not change what it is: what that window
                        // writes is the rule, through `SetAlarms`. BLUE for the one ring the user dragged away
                        // from its rule (an isolated row).
                        outline = SchedulerDomain.ringOutline(occurrence.entry.isolated),
                    )
                } +
                displayTimerOccurrences.map { occurrence ->
                    // PRD §18 Timers: the alarm marker unchanged, save for the bit that says which it is. Named
                    // by the timer's label, falling back to its DURATION (an alarm falls back to its time of day,
                    // but that is the thing a timer is not — what it says about itself is how long it runs).
                    CalendarRecord(
                        title =
                            occurrence.entry.label.ifBlank {
                                TimerDomain.formatDuration(occurrence.entry.durationSeconds)
                            },
                        range = TaskTimeRange(occurrence.instant, occurrence.instant),
                        entryId = occurrence.entry.id,
                        entryIds = listOf(occurrence.entry.id),
                        alarm = true,
                        timer = true,
                        // PRD §8: ORANGE for the same reason as the alarm's — the §18 window owns both.
                        outline = SchedulerDomain.ringOutline(occurrence.entry.calendarPlaced),
                    )
                }
            // The periods as drawn, for "what can be added there" ([SearchDomain.calendarKindsAt]).
            layersHolder.periods =
                calendarRecords.mapNotNull { record ->
                    val kind =
                        when {
                            record.layer != null || record.alarm || record.reminder -> null
                            record.sleep -> PeriodKinds.SLEEP
                            record.screenBreak -> record.breakKind.ifBlank { PeriodKinds.INACTIVITY }
                            else -> record.restrictiveKind.takeIf { it.isNotBlank() }
                        }
                    kind?.let { record.range to it }
                }
            layersHolder.span = TaskTimeRange(visibleSpanStartMillis, visibleSpanEndMillis)
            return CalendarDisplay(
                records = calendarRecords,
                displayFloorMillis = displayFloorMillis,
                bandSignature = bandSignature,
                noScreenPeriods = noScreenPeriods,
                sidePanelCount = displaySidePanels.size,
                workPlanPanelCount = displayWorkPlanPanels.size,
            )
        }
        // …and it is asked through a memo on EVERY value it reads (see [CalendarDisplayMemo]). `App`'s body
        // re-runs for every state change there is, and all but a few of them change nothing this derivation
        // looks at — an appended diagnostic row, a moved selection, a navigated window. Each entry below is
        // a value the function above reads; nothing it reads may be left out (a missing one would freeze the
        // calendar on the reading taken before that value moved), and nothing it does not read belongs here
        // (an extra one only costs a miss).
        val displayCache = remember { CalendarDisplayCache() }
        fun calendarDisplayAt(instantMillis: Long): CalendarDisplay =
            displayCache.get(
                key =
                    listOf(
                        instantMillis, visibleSpanStartMillis, visibleSpanEndMillis, today, tz, ownLayer,
                        // What `workPlanPanels` IS, rather than the list itself: past the 168 h ceiling it is
                        // the far-week fill, and below it `shownState.panels` — which is one of the two
                        // held apart below, so naming the list here would defeat the re-label (a rename
                        // rewrites it, and the key would miss before the shortcut was ever asked).
                        visibleSpanBeyondNearHorizon, farWeekPlan,
                        shownState.screenBreaks,
                        shownState.periodKindStyles, shownState.timers, shownState.alarms,
                        shownState.chores, shownState.sleep, shownState.sleepingSinceMillis,
                        shownState.showScreenBreaks, shownState.showReminders,
                        inactivityGaps, activeSince, inactiveSince, activeSessions, userAway, accountAway,
                        declaredAwaySpans, declaredAwaySince, observedNoScreenEvidence,
                        lockedIntervals, lockHistoryScanned, shownFrozenBreaks,
                    ),
                // The two the RENAME moves, held apart from the rest so a title-only change can be
                // re-labelled instead of re-derived (see [CalendarDisplayCache]).
                tasks = shownState.tasks,
                panels = shownState.panels,
            ) { deriveCalendarDisplay(instantMillis, shownState, shownFrozenBreaks) }
        val calendarDisplay = calendarDisplayAt(nowMillis)
        // ADR 0009: the motion of every edge, read off a second reading one millisecond later — only while the
        // calendar is open (nothing else draws these), and only when the first reading or the instant changed,
        // so a recomposition for an unrelated reason (a keystroke in the tree) pays for one derivation, not two.
        val calendarRecords =
            remember(calendarOpen, nowMillis, calendarDisplay.records) {
                if (!calendarOpen) {
                    calendarDisplay.records
                } else {
                    withLineMotion(
                        calendarDisplay.records,
                        calendarDisplayAt(nowMillis + LINE_MOTION_PROBE_MILLIS).records,
                    )
                }
            }
        // A block held in the calendar: every record of the calendar its release would leave — what it makes
        // disappear gone, what comes with it (the "no screen" a sleep period carries) alongside it, the place it left
        // as the edges choose. Asked of the STORED state at every minute the hand moves it by, so what it removed is
        // back as soon as it moves on.
        val heldCalendarRecords =
            remember(heldCalendarPreview, heldPlan, nowMillis) {
                heldCalendarPreview?.let { deriveCalendarDisplay(nowMillis, plannedOf(it), it.second).records }
            }
        // Diagnostics timeline: the bands the calendar is about to render, logged once per change of their
        // interior-edge signature (see where [CalendarDisplay.bandSignature] is built).
        val bandSignature = calendarDisplay.bandSignature
        LaunchedEffect(bandSignature) {
            Diagnostics.log("calendar no-screen periods: ${Diagnostics.formatRanges(calendarDisplay.noScreenPeriods)}")
        }
        // The OS lock history is read again **only when it can hold something new** — the engine's screen edges
        // (a lock, an unlock, a wake, an "I'm away" press: `SchedulerEngine.screenEdges`) — or when the calendar
        // shows further back than was asked. It was re-read every ten minutes until 2026-10-02: a PowerShell
        // process on a timer, between two edges, for a history that had not moved.
        //
        // The floor is QUANTIZED (rounded DOWN to a day), or the effect would relaunch on every display tick:
        // [CalendarDisplay.displayFloorMillis] is `now − 168h` whenever the calendar is not scrolled past that,
        // so it slides with the now-line. Rounding down also means the window only ever grows between scans.
        val lockScanSince = (calendarDisplay.displayFloorMillis / LOCK_HISTORY_FLOOR_MILLIS) * LOCK_HISTORY_FLOOR_MILLIS
        val screenEdges by engine.screenEdges.collectAsState()
        // Read where the effect runs, never a key of it: the line moving on adds nothing to the history.
        val lockScanNow by rememberUpdatedState(nowMillis)
        LaunchedEffect(lockScanSince, screenEdges) {
            val since = lockScanSince
            val until = lockScanNow
            val scanned =
                withContext(Dispatchers.Default) {
                    deviceLockedIntervals(since, until)?.map { TaskTimeRange(it.startMillis, it.endMillis) }
                }
            lockedIntervals = scanned
            lockHistoryScanned = true
            // scripts/collect-diagnostics.bat: the layers are read from the OS, so an anomaly in them is an
            // anomaly in THIS answer — record it rather than asking the user to describe the hatching. One
            // line per scan (one per screen edge), not per frame.
            Diagnostics.log(
                if (scanned == null) {
                    "device lock history unavailable — ${ownLayer.name} assumed locked over the whole window"
                } else {
                    val hatchedMillis = scanned.sumOf { it.endEpochMillis - it.startEpochMillis }
                    "device lock history: ${scanned.size} locked span(s), " +
                        "${hatchedMillis / 3_600_000}h of ${(until - since) / 3_600_000}h, layer ${ownLayer.name}"
                },
            )
        }
        // Perf: the sizes every derivation above is O(). Recorded here rather than sampled from outside
        // because these are the exact lists the frame was built from — and a size that only grows is a
        // leak signal no heap reading can give (see [PerfLeakWatch]).
        if (Perf.enabled) {
            Perf.gauge("state.tasks", schedulerState.tasks.size.toLong())
            Perf.gauge("state.panels", schedulerState.panels.size.toLong())
            Perf.gauge("state.cells", schedulerState.cells.size.toLong())
            Perf.gauge("engine.activeSessions", activeSessions.size.toLong())
            Perf.gauge("engine.inactivityGaps", inactivityGaps.size.toLong())
            Perf.gauge("display.calendarRecords", calendarRecords.size.toLong())
            Perf.gauge("display.sidePanels", calendarDisplay.sidePanelCount.toLong())
            Perf.gauge("display.workPlanPanels", calendarDisplay.workPlanPanelCount.toLong())
            // The four bounded collections, gauged precisely BECAUSE they are bounded: each has a cap
            // (MAX_HISTORY_UNITS and the two log caps), so a value that keeps climbing past it is a cap
            // that stopped being applied, which is the shape every leak in this state has taken.
            Perf.gauge("state.historyUnits", schedulerState.histories.all().sumOf { it.second.units.size }.toLong())
            Perf.gauge("state.notificationLog", schedulerState.notificationLog.size.toLong())
            Perf.gauge("state.supabaseUsageLog", schedulerState.supabaseUsageLog.size.toLong())
            Perf.gauge("state.taskTrees", schedulerState.taskTrees.size.toLong())
        }

        // ---- The display's own clock ------------------------------------------------------------------
        //
        // `docs/scheduler_requirements.md`: the scheduler returns a SET OF RULES, and everything above is read
        // out of it. So the display is a piecewise function of the now-line and there is nothing to poll:
        // nothing in the past is a function of the line (the past is frozen — only an event changes it), and
        // nothing in the future is either until the line crosses a boundary the rules themselves name. What
        // does follow the line — a pose the line drags, the panel growing behind it, a live band ending at it
        // — follows it AFFINELY, and [withLineMotion] has already read how: the calendar draws that motion
        // continuously on its own frame clock without asking this body again (ADR 0009).
        //
        // The FIXED bounds of everything just derived are the model's boundaries, and so are the instants a
        // bound that follows the line meets one of them or crosses a midnight: the model cannot change shape
        // before the first of those still ahead of the line. Hand them over and sleep until then (see
        // [SchedulerDomain.displayResampleDelayMillis]; [displaySleepMillis] converts the answer out of the
        // possibly-accelerated clock's time base and bounds it at both ends).
        val (displayBounds, displayLineOffsets) = displayBoundsOf(calendarRecords, nowMillis)
        val displayResampleDelay =
            Perf.measure("display.resampleDelay") {
            SchedulerDomain.displayResampleDelayMillis(
                displayBounds, nowMillis, tz,
                millisPerPixel =
                    if (calendarOpen) nowLineMillisPerPixel else DEFAULT_NOW_LINE_MILLIS_PER_PIXEL,
                lineOffsets = displayLineOffsets,
            )
            }
        // The sim clock's [SimAppClock.reconfigured] bump restarts the sleep at once when acceleration is
        // turned on or off, so a speed change is never held up behind a sleep taken at the old speed.
        val simReconfigured by simClock.reconfigured.collectAsState()
        // Keyed on the TICK, never on the delay itself: this body recomposes for plenty of reasons that have
        // nothing to do with the clock (a peer's sync landing, an edit), and a key that moved with the answer
        // would restart the sleep each time — a busy enough app would then never reach the end of one and the
        // now-line would simply stop. The delay is read through [rememberUpdatedState] instead, so each
        // iteration still sleeps on the freshest answer.
        val resampleDelay = rememberUpdatedState(displayResampleDelay)
        LaunchedEffect(clock, displayResampleTick, simReconfigured) {
            delay(displaySleepMillis(clock, resampleDelay.value))
            nowMillis = clock.nowMillis()
            displayResampleTick++
        }

        // PRD §8: each task's own colour, so a task panel is drawn in the same colour as the task tree's
        // cell for that task. Both read the ACCOUNT's one [TaskHueMemo] — it holds the previous solution the
        // colour rule's ties are settled against, caches the answer per tree (so the two surfaces get the
        // identical map, not two derivations of it) and carries the debounce.
        val taskHues = rememberTaskHues(schedulerState)
        val taskPanelColors = remember(taskHues) { TaskPalette.accentColors(taskHues) }
        // The same hues read the other way, for the windows that NAME a task on an ordinary light surface
        // rather than drawing it on the timeline ([org.example.project.ui.TaskTitleLabel]).
        val taskSheetColors = remember(taskHues) { TaskPalette.sheetColors(taskHues) }
        // The calendar's per-object windows — about something transient (a draft, a spot), so no ☆.
        // PRD §8 edit window: the calendar blocks being edited.
        val blockEditWindows = remember { ObjectWindows<PlacedRecord>() }
        // PRD §14: the reminder tags the "edit…" chooser's `reminder` row is open on. A tag had no editor
        // reachable from the calendar at all before the chooser — only "add reminder" did.
        val reminderTagWindows = remember { ObjectWindows<PlacedRecord>() }
        // PRD §8: the periods the period editor is open on. A period's own row of the "edit…" chooser opens it,
        // and it is the one-element case of the element window's period section — nothing is laid on the
        // calendar until Save, which is what lets a period be given an open ("∞") bound the grid could never be
        // dragged to.
        val periodDraftWindows = remember { ObjectWindows<PeriodDraft>() }
        // PRD §8 contextual menu **"add…" / "edit…"**: what the element window is open on — the instant it is
        // anchored at, and, for "edit…", the elements at the mouse it is confined to. Both entries open the one
        // window ([org.example.project.ui.CalendarElementsWindow]).
        val elementsWindows = remember { ObjectWindows<CalendarElementsDraftSet>() }

        // PRD §8 focus: the floating calendar window is the focused surface while it is open — so the
        // tree stops hijacking letter typing into Edit Mode and Ctrl+Z/Y route to the calendar history.
        // Opening only: a CLOSE hands the focus on like every window's ([ViewHistoryRecorder.focusAfterClose], to the
        // window under it). The old "closed ⇒ the tree" named the tree even when it was closed too, and the focus
        // walk's effect then reopened it — closing every window left one open (anomaly 2026-10-01).
        // And only an OPENING: a calendar the launch found open was not opened by anyone, and claiming the focus
        // for it took the focus from whichever window the app had been left on (see `leftOn`).
        val calendarOpenAtLaunch = remember { booleanArrayOf(calendarOpen) }
        LaunchedEffect(calendarOpen) {
            val restored = calendarOpenAtLaunch[0]
            calendarOpenAtLaunch[0] = false
            if (calendarOpen && !restored) vm.dispatch(SchedulerIntent.SetCalendarFocus(true))
        }

        // PRD §7: switching focus to another window leaves Edit Mode in any window — close the calendar's
        // edit surface so it doesn't linger over the newly focused window.
        LaunchedEffect(schedulerState.focusedWindow) {
            blockEditWindows.closeAll()
        }

        // PRD §8: which period kinds refuse which task — what the calendar's drag preview retracts by, the
        // reducer's own question ([SchedulerDomain.periodRefuses]) asked of the same tasks.
        val periodRefusalTasks = schedulerState.tasks
        val periodRefusal = remember(periodRefusalTasks) {
            { taskId: TaskId?, kind: String -> SchedulerDomain.periodRefuses(periodRefusalTasks, taskId, kind) }
        }
        // User rule 2026-10-08: **a held block remembers its length** — a period put across the line, in whatever
        // mode, is `[…, line[ ∪ ]line, …]`: the line takes its own instant and no more, so the period stands where the
        // hand has it. (2026-10-05 → 10-08 a period that is or carries "no screen" was cut to `]line; its end]` under a
        // mode-1 line and shortened as it was carried across.) What the line then crosses of it gives way as it is
        // crossed, which is the stated period's own rule (`scheduler.md` § *A period the user stated gives way*).
        val periodAtLine = remember { { _: String, range: TaskTimeRange -> range as TaskTimeRange? } }
        SideEffect {
            calendarElementDrag.atLine = periodAtLine
            calendarElementDrag.refuses = periodRefusal
            // What stands where a dragged period was is chosen from its edges — the layers the calendar draws there
            // included ([SchedulerDomain.vacatedPastFill]).
            SchedulerReducer.layerKindsAt = { calendarLayers.kindsAt(it) }
            // "What can be added there" is asked of the periods as the calendar DRAWS them (anomaly 2026-10-07).
            SearchDomain.drawnPeriodKindsAt = { calendarLayers.periodKindsAt(it) }
        }
        // User rule 2026-10-03: every id suggestion list's task row is the Search window's task result row, configured
        // ([TaskIdentityRow]). Its paths are one walk of every tree, measured when the trees change — never per row.
        val identityPaths =
            remember(schedulerState.cells, schedulerState.lists, schedulerState.tasks, schedulerState.taskTrees) {
                SearchDomain.allPathsInAnyTree(schedulerState)
            }
        val taskIdentityRow: @Composable (EditMenuItem) -> Unit = { item ->
            TaskIdentityRow(schedulerState, identityPaths, item, onIntent = { vm.dispatch(it) })
        }
        CompositionLocalProvider(
            LocalTaskIdentityRow provides taskIdentityRow,
            LocalPeriodRefusal provides periodRefusal,
            org.example.project.ui.LocalPeriodAtLine provides periodAtLine,
            org.example.project.ui.LocalCalendarElementDrag provides calendarElementDrag,
            org.example.project.ui.LocalHeldCalendarRecords provides heldCalendarRecords,
            LocalTransientMenuHost provides transientMenus,
            LocalWindowFrameHost provides windowFrames,
            LocalHeadObstacle provides menuToggleBounds,
            LocalWindowChromeMemory provides windowChromeMemory,
            LocalMenuButtonHost provides menuButtonHost,
            org.example.project.ui.LocalMenuCustomizer provides menuCustomizer,
            // PRD §8: a task cell's "go to calendar", for every surface that draws the cell's menu.
            LocalCalendarGoTo provides calendarGoTo,
            // The period edit window's companions + drawings, for everything that draws a period.
            LocalPeriodKindConfig provides schedulerState.periodKindConfig,
        ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
                // The one observer that closes a right-click MENU. It sits above the lateral menu too — a
                // menu button opens or focuses a window, which is exactly "the user asked for something
                // else". Windows are not dismissed by it: nothing takes a window away (`PopupWindows.kt`).
                .transientMenuDismissRoot(transientMenus)
                // PRD §5: the history chords in EVERY window. A key event bubbles from the focused element up
                // to here, so this only sees a chord no window answered itself (the tree, the calendar and the
                // Alarms window do; a text field keeps its own Ctrl+Z) — and the reducer makes it relative to
                // the focused window, whichever that is.
                .onKeyEvent { event ->
                    val intent = undoRedoIntentFor(event) ?: return@onKeyEvent false
                    vm.dispatch(intent)
                    true
                }
        ) {
            // The reduce bar is drawn OVER the lateral menu, so the app is inset by its height only where
            // the windows live — the content area below.
            val minimizedInset = if (windowFrames.registrations.isNotEmpty()) MINIMIZED_BAR_HEIGHT else 0.dp
            Row(modifier = Modifier.fillMaxSize()) {
                // User rule 2026-10-08: the whole menu under the page button is the user's list — windows' buttons and
                // controls of the app ([MenuControl]), drawn by the one code each has ([MenuControlItem]).
                val menuControlHost =
                    org.example.project.ui.MenuControlHost(
                        state = schedulerState,
                        sleeping = schedulerState.isSleeping(nowMillis),
                        away = userAway,
                        onlineStatus = if (vm.syncState == null) null else syncStatusLabel(syncStateValue, accountValue),
                        displayMode = calendarDisplayMode,
                        onSetVoice = { vm.dispatch(SchedulerIntent.SetNotificationVoice(it)) },
                        // PRD §11: the Notifications switch. Driven through the ENGINE, not straight to the reducer,
                        // because switching off also withdraws the notifications the OS is already showing — see
                        // [SchedulerEngine.setNotificationsEnabled].
                        onSetNotifications = { engine.setNotificationsEnabled(it) },
                        onLookAwayNow = { engine.restartLookAway() },
                        onSwitchTask = { engine.forceTaskSwitch() },
                        onToggleSleepWork = {
                            if (schedulerState.isSleeping(clock.nowMillis())) {
                                vm.setSleepMode(null)
                            } else {
                                vm.setSleepMode(
                                    SchedulerDomain.nextWakeInstantMillis(schedulerState.sleep, clock.nowMillis(), tz),
                                )
                            }
                        },
                        onToggleAway = { engine.setUserAway(!userAway) },
                        onOpenOnline = { openNewWindow(FloatingWindow.Online) },
                        onSetAutoSchedule = { vm.dispatch(SchedulerIntent.SetAutomaticSchedule(it)) },
                        onSetReminders = { vm.dispatch(SchedulerIntent.SetShowReminders(it)) },
                        onSetScreenBreaks = { vm.dispatch(SchedulerIntent.SetShowScreenBreaks(it)) },
                        onSetDisplayMode = {
                            vm.dispatch(SchedulerIntent.SetCalendarDayMode(it == org.example.project.ui.CalendarDisplayMode.Day))
                        },
                        onSetPlanTimeLimit = { vm.dispatch(SchedulerIntent.SetPlanCalculationLimit(it)) },
                        onSetMinimumTimeWeight = { vm.dispatch(SchedulerIntent.SetMinimumTimeWeight(it)) },
                        onSetSleepSchedule = { vm.dispatch(SchedulerIntent.SetSleepSchedule(it, today.toEpochDays().toLong())) },
                        onSetSoundVolume = { vm.dispatch(SchedulerIntent.SetSoundVolume(it)) },
                    )
                // The lateral menu is omitted entirely while collapsed, so the content takes the full width
                // ("completely disappear to the left"). The collapse toggle lives outside it (see below).
                if (!menuCollapsed) LateralMenu(
                    page = page,
                    onPageSelected = { page = it },
                    scrollState = menuScroll,
                    customizing = menuCustomizer.active,
                    onCustomize = { menuCustomizer.active = it },
                    items = {
                        CustomMenuSection(
                            buttons = menuButtons.filter(::menuButtonShown),
                            // PRD §7: every window button of the menu opens a NEW window ([openNewWindow]).
                            onClick = { onMenuButtonClicked(it) },
                            editingId = editingMenuButton,
                            onStartRename = { editingMenuButton = it },
                            onRename = { id, title -> setMenuButtons(CustomMenuButtons.renamed(menuButtons, id, title)) },
                            onEditDone = { editingMenuButton = null },
                            onRemove = { setMenuButtons(CustomMenuButtons.removed(menuButtons, it)) },
                            canUpdate = { menuButtonCanUpdate(it) },
                            onUpdate = { button ->
                                menuButtonWindowOf(button)?.let { frameId ->
                                    val layout = windowLayoutOf(frameId)
                                    val config = lateralWindowOf(frameId)?.let { windowConfigOf(frameId) }
                                    setMenuButtons(CustomMenuButtons.updated(menuButtons, button.id, config, layout))
                                }
                            },
                            customizing = menuCustomizer.active,
                            onCustomize = { menuCustomizer.active = it },
                            controlContent = { control, title -> org.example.project.ui.MenuControlItem(control, title, menuControlHost) },
                            onMove = { id, beforeId -> setMenuButtons(CustomMenuButtons.moved(menuButtons, id, beforeId)) },
                            actionContent = { button ->
                                menuActionContext.value?.let { (handlers, open) ->
                                    org.example.project.ui.MenuActionItem(
                                        state = schedulerState,
                                        actionName = button.action.orEmpty(),
                                        title = button.title,
                                        configText = button.config,
                                        // What the item's editor keeps of its own (a start, an end): the item's.
                                        onConfigChange = { text ->
                                            setMenuButtons(menuButtons.map { if (it.id == button.id) it.copy(config = text) else it })
                                        },
                                        handlers = handlers,
                                        onIntent = { vm.dispatch(it) },
                                        nowMillis = clock::nowMillis,
                                        onOpen = open,
                                    )
                                }
                            },
                        )
                    },
                )

                // PRD §4: a pop-up opened from the default-sub-tree window reads the TEMPLATE and writes
                // back into it; one opened from the tree reads and writes the live state as it always did.
                // Projected once per state, and only if a window asks for it.
                val templateState = remember(schedulerState) { lazy { schedulerState.projectDefaultSubtree() } }
                fun popupStateOf(template: Boolean): SchedulerState =
                    if (template) templateState.value else schedulerState
                fun popupDispatchOf(template: Boolean): (SchedulerIntent) -> Unit =
                    if (template) {
                        { intent -> vm.dispatch(SchedulerIntent.InDefaultSubtree(intent)) }
                    } else {
                        { intent -> vm.dispatch(intent) }
                    }

                // The content area is clipped so the floating calendar window can overlap the tree
                // but never spill onto the lateral menu (PRD §7).
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // Keep the windows clear of the reduce bar along the bottom: a maximized window
                        // fills what is left, not what the bar is covering.
                        .padding(bottom = minimizedInset)
                        .clipToBounds()
                        // A press on the background drops the frame focus; a press in a window (the task tree's
                        // included) takes it back on the way in ([WindowFrameHost]). It moves no SCHEDULER focus:
                        // this Box is every window's ancestor, so claiming one here would record a move to the
                        // tree and back on every press in another window (PRD §7 — each is a WindowNav unit).
                        .raiseOnPress { windowFrames.blur() },
                ) {
                    // PRD §4: the task tree, a window among the others — drawn FIRST, so the windows that were
                    // open with it come back over it rather than under a maximized tree.
                    if (taskTreeWindowOpen) TaskTreeWindow(
                        onDismiss = { taskTreeWindowOpen = false },
                        modifier = Modifier.align(Alignment.Center),
                        initialOffset = taskTreeOffset,
                        initialSize = taskTreeSize,
                        onGeometryChange = { windowOffset, windowSize ->
                            taskTreeOffset = windowOffset
                            taskTreeSize = windowSize
                            persistPlacement(FloatingWindow.TaskTree, windowOffset, windowSize, true)
                        },
                        onRaise = { focusWindow(FloatingWindow.TaskTree) },
                    ) { treeKeyboardEnabled ->
                    when (page) {
                        OmniPage.TaskScheduler ->
                            Perf.measure("compose.TaskSchedulerScreen") {
                            TaskSchedulerScreen(
                                modifier = Modifier.fillMaxSize(),
                                keyboardEnabled = treeKeyboardEnabled,
                                store = store,
                                vm = vm,
                                // PRD §4: the default sub-tree's window, opened from the top of this window's
                                // configuration section — what the lateral menu's button did.
                                defaultSubtreeWindowOpen = defaultSubtreeWindowOpen,
                                onToggleDefaultSubtree = {
                                    onMenuWindowClicked(FloatingWindow.DefaultSubtree) { defaultSubtreeWindowOpen = it }
                                },
                                onSetWeightWindow = { weightWindows.setFrom(template = false, it) },
                                onSetRelativeWindow = { relativeWindows.setFrom(template = false, it) },
                                onSetEditTask = { it?.let { id -> openElementSearch(SearchDomain.Kind.Task, id.value) } },
                                onSetEditCategory = { it?.let { id -> openElementSearch(SearchDomain.Kind.Category, id.value) } },
                                onSetDeepCopyCell = { deepCopyWindows.setFrom(template = false, it) },
                            )
                            }
                    }
                    }

                    // PRD §5: the priority-weight window — about ONE sub-list; opening it on another opens a
                    // second. It opens on top like every window and, like every window, goes UNDER the next one
                    // the user presses in: it is in the same stacking order as all the rest.
                    ObjectWindowsHost(weightWindows) { w ->
                        val (listId, template) = w.subject
                        val close = w::close
                        val popupState = popupStateOf(template)
                        val popupDispatch = popupDispatchOf(template)
                        if (popupState.lists[listId] == null) {
                            close()
                        } else {
                            PriorityWeightWindow(
                                state = popupState,
                                listId = listId,
                                // The template's shares are its own (defaultSubtreePriorities), so the chart
                                // beside the table reads the tree the window was opened from.
                                priorities =
                                    if (template) schedulerState.defaultSubtreePriorities()
                                    else SchedulerDomain.absoluteTaskPriorities(schedulerState),
                                onIntent = popupDispatch,
                                onDismiss = { close() },
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }

                    // PRD §5: the relative-priority window, the same sort of window. Opened from the
                    // percentage's right-click menu; also cleared when the cell goes away under it (an undo).
                    ObjectWindowsHost(relativeWindows) { w ->
                        val (cellId, template) = w.subject
                        val close = w::close
                        val popupState = popupStateOf(template)
                        val popupDispatch = popupDispatchOf(template)
                        if (popupState.cells[cellId]?.taskId == null) {
                            close()
                        } else {
                            RelativePriorityWindow(
                                state = popupState,
                                cellId = cellId,
                                onIntent = popupDispatch,
                                onDismiss = { close() },
                                // PRD §5: its chain cells are task cells, so their percentage column does
                                // what the tree's does — a click opens that sub-list's weight window, a
                                // right-click opens this window on the chain cell. Both in the same tree as
                                // this one: the chain cell belongs to it.
                                onOpenWeightWindow = { weightWindows.open(TreeObject(it, template)) },
                                onOpenRelativePriority = { relativeWindows.open(TreeObject(it, template)) },
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }

                    // PRD §13: the "edit task" window — the tree cell's and (PRD §8) the calendar task
                    // panel's. Raised out of TaskSchedulerScreen so it is a window among the others: inside
                    // the tree it could never be drawn over a floating window stacked above the tree, whatever
                    // the stacking order said.
                    ObjectWindowsHost(taskEditWindows) { w ->
                        val (taskId, template) = w.subject
                        val close = w::close
                        val popupState = popupStateOf(template)
                        val popupDispatch = popupDispatchOf(template)
                        val task = popupState.tasks[taskId]
                        if (task == null) {
                            close()
                        } else {
                            TaskEditWindow(
                                task = task,
                                // PRD §13: the screen switch and the schedule unit only exist for a
                                // schedulable leaf task — a parent is a grouping and is never placed,
                                // so its window is text only.
                                isLeaf = SchedulerDomain.isLeafTask(popupState, taskId),
                                periodKinds = popupState.allPeriodKinds,
                                onAddPeriodKind = { popupDispatch(SchedulerIntent.AddPeriodKind(it)) },
                                onEditPeriodKind = { openElementSearch(SearchDomain.Kind.RestrictivePeriod, it) },
                                onSave = { resilience, entries, text ->
                                    // One intent per section, and only for what actually changed — so
                                    // Save on an untouched window adds nothing to the Undo/Redo history
                                    // (PRD §6). The resilience map goes one KIND at a time, because that
                                    // is the grain the intent (and the history unit) has. "no task
                                    // allowed" is not among them: the window offers no field for it
                                    // ([PeriodKinds.isResilienceEditable]), so there is nothing there
                                    // that could have moved.
                                    for (kind in popupState.allPeriodKinds.filter(PeriodKinds::isResilienceEditable)) {
                                        val next = PeriodKinds.resilienceFor(resilience, kind)
                                        if (next != task.resilienceFor(kind)) {
                                            popupDispatch(SchedulerIntent.SetTaskResilience(taskId, kind, next))
                                        }
                                    }
                                    if (entries != task.scheduleUnit) {
                                        popupDispatch(SchedulerIntent.SetScheduleUnit(taskId, entries))
                                    }
                                    if (text != task.text) {
                                        popupDispatch(SchedulerIntent.SetTaskText(taskId, text))
                                    }
                                    close()
                                },
                                onDismiss = { close() },
                                // PRD §13 Paths: where the task sits in the LIVE tree, and where else it may go —
                                // not offered for a default sub-tree's task, whose places are the template's.
                                // Held on the tree fields, never re-walked on a tick (display-hot-path.md).
                                paths = if (template) null else remember(taskId, popupState.cells, popupState.lists, popupState.tasks) {
                                    TaskPathsDomain.occurrences(popupState, taskId)
                                },
                                pathCandidates = { query -> TaskPathsDomain.candidates(vm.state.value, taskId, query) },
                                pathTitleSuggestions = { draft -> SchedulerDomain.titleSuggestions(vm.state.value, draft) },
                                onAddPath = { parent -> vm.dispatch(SchedulerIntent.AddTaskPath(taskId, parent)) },
                                onRemovePath = { cellId -> vm.dispatch(SchedulerIntent.RemoveTaskPath(cellId)) },
                            )
                        }
                    }

                    // PRD §5: the CATEGORY edit window — one category, its rules and everything carrying
                    // it. The task cell's categories drop-down opens it from its ✎, exactly as the task
                    // edit window's resilience row opens the period's. The same sort of window.
                    ObjectWindowsHost(categoryWindows) { w ->
                        val (categoryId, template) = w.subject
                        val close = w::close
                        val popupState = popupStateOf(template)
                        val popupDispatch = popupDispatchOf(template)
                        if (popupState.categoryById(categoryId) == null) {
                            // Deleted under it (from here, from a peer's sync, or by an undo).
                            close()
                        } else {
                            CategoryEditWindow(
                                state = popupState,
                                categoryId = categoryId,
                                onIntent = popupDispatch,
                                onDismiss = { close() },
                            )
                        }
                    }

                    // PRD §5: the app's answer to an edit it REFUSED because it would have broken a category
                    // rule. The same one notice the calendar uses, raised from the state rather than from a
                    // gesture handler — the refusal happens in the reducer, which is the only place that can
                    // tell that the edit could not be scaled back onto the rules.
                    schedulerState.categoryRuleError?.let { message ->
                        MessagePopup(
                            message = message,
                            onDismiss = { vm.dispatch(SchedulerIntent.DismissCategoryRuleError) },
                            // Its own id: the calendar's notice can stand at the same time, and two
                            // windows sharing one id would fight over the host's single row for it.
                            id = "CategoryRuleNotice",
                        )
                    }

                    // The app's one notice (PRD §8 "go to task tree" on a task no cell holds). A window like
                    // the ones above: it opens on top, and stays until its OK or its ✕.
                    appMessage?.let { message ->
                        MessagePopup(
                            message = message,
                            onDismiss = { appMessage = null },
                            id = "AppNotice",
                        )
                    }

                    // PRD §13: "deep copy" asks for its maximum depth here, then copies (DeepCopyWindow).
                    // Raised for the same reason as the edit window above.
                    ObjectWindowsHost(deepCopyWindows) { w ->
                        val (cellId, template) = w.subject
                        val close = w::close
                        val popupState = popupStateOf(template)
                        val popupDispatch = popupDispatchOf(template)
                        if (popupState.cells[cellId] == null) {
                            close()
                        } else {
                            DeepCopyWindow(
                                state = popupState,
                                // The same block "copy" takes — a deep copy of a multi-selection is
                                // every selected cell down to the chosen depth, not just the one under
                                // the cursor.
                                cellIds = SchedulerDomain.contextMenuCopyTargets(
                                    popupState,
                                    popupState.selection,
                                    cellId,
                                ),
                                onCopy = { targets, maxDepth, unlimited, options ->
                                    // The depth and the three switches are the ACCOUNT's, not this
                                    // copy's: what the window is asked here is what every later copy
                                    // carries — the menu's "copy" and §4's Ctrl+C / Ctrl+X included
                                    // (the chord still takes the whole sub-tree).
                                    vm.dispatch(SchedulerIntent.SetDeepCopyMaxDepth(maxDepth))
                                    vm.dispatch(SchedulerIntent.SetDeepCopyUnlimited(unlimited))
                                    vm.dispatch(SchedulerIntent.SetCopyOptions(options))
                                    // Explicit options: the dispatches above have not reached this
                                    // composition's state.
                                    val text = SchedulerDomain.copyCellsText(
                                        popupState,
                                        targets,
                                        maxDepth,
                                        options,
                                    )
                                    if (text.isNotEmpty()) writeSystemClipboardText(text)
                                    close()
                                },
                                onDismiss = { close() },
                            )
                        }
                    }

                    if (calendarOpen) {
                        CalendarFloatingWindow(
                            selectedDate = selectedDate,
                            today = today,
                            // PRD §8 "locked on task", set by a task cell's "go to calendar".
                            lockedTaskTitle = calendarLockTask?.let { schedulerState.tasks[it]?.title?.ifBlank { null } },
                            lockOnTask = calendarLockOnTask,
                            lockTaskMillis = calendarLockTarget.first,
                            lockTaskPending = calendarLockTarget.second,
                            onLockOnTaskChange = { calendarLockOnTask = it },
                            lockOnTaskNonce = calendarLockNonce,
                            displayMode = calendarDisplayMode,
                            onDisplayModeChange = {
                                vm.dispatch(
                                    SchedulerIntent.SetCalendarDayMode(it == org.example.project.ui.CalendarDisplayMode.Day),
                                )
                            },
                            // The day selector, in the window's configuration section (it left the lateral menu).
                            monthAnchor = monthAnchor,
                            onMonthAnchorChange = { monthAnchor = it },
                            onSelectDate = { selectedDate = it; calendarJumpNonce++ },
                            nowMillis = nowMillis,
                            // The now-line's own instant: read afresh on every frame it is placed on, so it
                            // glides however seldom [nowMillis] above is re-derived (see the display's own
                            // clock, further up — it sleeps between the rule set's boundaries).
                            nowExactMillis = { clock.nowMillis() },
                            onDismiss = { calendarOpen = false },
                            modifier = Modifier
                                .align(Alignment.Center),
                            records = calendarRecords,
                            taskColors = taskPanelColors,
                            taskSheetColors = taskSheetColors,
                            // PRD §9/§17: a future week beyond the near horizon is still computing its plan
                            // off the UI thread — surface a "Calculating…" hint instead of a frozen window.
                            calculating = farWeekCalculating,
                            // PRD §8 focus: pressing in the calendar makes it the focused surface again
                            // (e.g. after a click into the tree had handed focus back) and raises it to the
                            // top of the window layers. (onFocus fires inside the window, after its offset.)
                            onFocus = { focusWindow(FloatingWindow.Calendar) },
                            // PRD §8 contextual menu "add…": the ONE add entry, opening the one element
                            // window on an empty list. What is being put here, of which kind, and with
                            // which configuration is all answered there, and its Save is what lays it.
                            // User rule 2026-10-01: "add…" is the Search window of what can be added AT that instant (the
                            // calendar filter on, its position the right-click). One such window: a later right-click
                            // moves the one already open rather than opening another.
                            onAddAt = { atMillis -> openCalendarSearch(atMillis, add = true) },
                            // PRD §8 "edit…" with two or more elements at the cursor: the SAME window,
                            // seeded with them and confined to them. Seeding is where `App` adds what only
                            // it holds — a panel's task resilience, an alarm's weekdays and ring length —
                            // so [calendarElementDrafts] stays a pure reading of what is drawn.
                            // User rule 2026-10-01: "edit…" (and "edit [element]") is the Search window of what is on
                            // the timeline at that instant — the same one window "add…" uses, its filter switched over.
                            onEditElementsAt = { atMillis, _ -> openCalendarSearch(atMillis, add = false) },
                            // PRD §8 (uniform blocks): committing a drag/resize updates the panel
                            // (auto blocks become user-authored), or pins a record into a panel. The gesture
                            // itself sets the EXISTENCE pin ([SchedulerDomain.pinsAfterHandPlacement]): the
                            // user has just said "this occurrence, here", and a panel the fill may still wipe
                            // cannot say that — without it the next re-plan quietly undid the drag.
                            onCommitBounds = { block, newStart, newEnd, allowOverlap ->
                                // What the release does is said ONCE ([calendarMoveOf]) — the held preview runs the
                                // same moves on a copy of the state.
                                when (
                                    val move =
                                        calendarMoveOf(schedulerState, block, newStart, newEnd, allowOverlap, nowMillis, tz)
                                ) {
                                    is CalendarMove.Dispatch -> dispatchFromCalendar(move.intent)
                                    // A screen break is no panel but this device's own break history and the
                                    // machine the line carries, which are the engine's to rewrite.
                                    is CalendarMove.Break ->
                                        engine.placeBreakByHand(move.kind, move.fromStartMillis, move.fromEndMillis, move.newStartMillis)
                                    null -> Unit
                                }
                            },
                            // PRD §8 "edit…": the chooser's pick, routed to the editor that already OWNS the
                            // thing the row names — and that is the whole of the routing, for EVERY edit the
                            // calendar offers. A "task" row's editor is the §13 window, the very window the
                            // tree cell's own "edit task" opens (that row is what that entry became, so the
                            // calendar has no second way to the task editor); a sleep band's editable object
                            // is the §17 schedule under the row "sleep schedule" — the band itself is now an
                            // ordinary period of kind `sleep`, so it has a period row too; an alarm's and a timer's is the §18 window; a restrictive
                            // period's is the one period editor, WHATEVER ITS KIND (read off the row, not
                            // guessed from which paint the block wears) — `sleep` included, since a sleep
                            // window is a period of that kind now, and "sleep schedule" is the separate row
                            // that reaches the RULE behind it; a reminder's is the §14 editor; a
                            // task panel's is the calendar edit window.
                            //
                            // The period row carries EVERY record behind it, which is the user's "no screen"
                            // rule: a stretch spelt as a computer period overlapping a phone period is one
                            // statement, so one edit moves both. And a row whose record is DERIVED (a past
                            // Inactivity stretch, the §17 wind-down hour) carries no panel id — editing it is
                            // what MATERIALIZES it, in the period editor's Save, which is the one place that
                            // happens.
                            onEditChoice = { choice ->
                                val head = choice.records.firstOrNull()
                                when {
                                    choice.label == EDIT_LABEL_TASK ->
                                        head?.taskId?.let { taskId -> openElementSearch(SearchDomain.Kind.Task, taskId.value) }
                                    choice.label == EDIT_LABEL_SLEEP_SCHEDULE -> sleepWindowOpen = true
                                    choice.label == EDIT_LABEL_ALARM ||
                                        choice.label == EDIT_LABEL_TIMER -> alarmWindowOpen = true
                                    choice.label == EDIT_LABEL_REMINDER -> head?.let(reminderTagWindows::open)
                                    choice.periodKind.isNotBlank() && head != null ->
                                        periodDraftWindows.open(
                                            PeriodDraft(
                                                kind = choice.periodKind,
                                                blocks = choice.records,
                                                startMillis = head.fullStartMillis,
                                                endMillis = head.fullEndMillis,
                                            ),
                                        )
                                    head != null -> blockEditWindows.open(head)
                                }
                            },
                            // PRD §8 "go to task tree" — the app's one handler, shared with the "All
                            // tasks" window's rows (declared beside [appMessage]).
                            onGoToTaskTree = goToTaskTree,
                            // PRD §14 Reminders: clicking a reminder tag toggles its checked (done) state.
                            onToggleReminder = { block ->
                                block.entryId?.let { id ->
                                    vm.dispatch(
                                        SchedulerIntent.SetReminderChecked(
                                            id, !block.checked, nowMillis,
                                            // The same funnel the calendar drew the tag through, over the
                                            // days it shows, so a tag the store does not hold yet is stored.
                                            tag = SchedulerDomain.regenerateChorePanels(
                                                schedulerState.panels, schedulerState.chores,
                                                today.atStartOfDayIn(tz).toEpochMilliseconds(),
                                                ((visibleSpanEndMillis - today.atStartOfDayIn(tz).toEpochMilliseconds()) /
                                                    (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0),
                                                nowMillis,
                                            ).firstOrNull { it.id == id },
                                        ),
                                    )
                                }
                            },
                            // PRD §8 Overlap Mode: commit re-divided panel widths from a dragged edge.
                            onAdjustWeights = { weights ->
                                if (weights.isNotEmpty()) vm.dispatch(SchedulerIntent.SetPanelWeights(weights))
                            },
                            overlapArmed = schedulerState.overlapArmed,
                            onToggleOverlap = { vm.dispatch(SchedulerIntent.ToggleCalendarOverlap) },
                            // PRD §14/§15: the calendar's "Reminders" / "Screen breaks" display switches (cosmetic;
                            // notifications stay on).
                            showScreenBreaks = schedulerState.showScreenBreaks,
                            onToggleScreenBreaks = { vm.dispatch(SchedulerIntent.SetShowScreenBreaks(it)) },
                            planCalculationLimitSeconds = schedulerState.planCalculationLimitSeconds,
                            minimumTimeWeight = schedulerState.minimumTimeWeight,
                            onMinimumTimeWeightChange = { vm.dispatch(SchedulerIntent.SetMinimumTimeWeight(it)) },
                            sleepSchedule = schedulerState.sleep,
                            configurationWidthDp = calendarConfigurationWidth,
                            onConfigurationWidthChange = { calendarConfigurationWidth = it; saveWindowSections() },
                            sectionsHidden = calendarSectionsHidden,
                            onSectionsHiddenChange = { calendarSectionsHidden = it; saveWindowSections() },
                            onSleepScheduleChange = {
                                vm.dispatch(SchedulerIntent.SetSleepSchedule(it, today.toEpochDays().toLong()))
                            },
                            onPlanCalculationLimitChange = { vm.dispatch(SchedulerIntent.SetPlanCalculationLimit(it)) },
                            automaticSchedule = schedulerState.automaticSchedule,
                            onToggleAutomaticSchedule = { vm.dispatch(SchedulerIntent.SetAutomaticSchedule(it)) },
                            showReminders = schedulerState.showReminders,
                            onToggleReminders = { vm.dispatch(SchedulerIntent.SetShowReminders(it)) },
                            onUndo = { vm.dispatch(SchedulerIntent.Undo) },
                            onRedo = { vm.dispatch(SchedulerIntent.Redo) },
                            initialOffset = calendarOffset,
                            initialSize = calendarSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                calendarOffset = windowOffset
                                calendarSize = windowSize
                                persistPlacement(FloatingWindow.Calendar, windowOffset, windowSize, true)
                            },
                            // PRD §8/§9: the endless scroll says which days are on screen; the horizon and
                            // every display projection above are computed from exactly that span.
                            onVisibleDaysChanged = { firstDay, dayCount ->
                                visibleFirstDay = firstDay
                                visibleDayCount = dayCount
                            },
                            // ADR 0009: the grid's own temporal resolution, which is what bounds how
                            // often anything pinned to the now-line is re-derived.
                            onNowLineResolutionChanged = { nowLineMillisPerPixel = it },
                            jumpNonce = calendarJumpNonce,
                        )

                        // PRD §8 edit window, opened over the calendar window and the tree — the
                        // one-element case of a TASK PANEL, reached from the "edit…" chooser and from a
                        // double-click. Adding one goes through [CalendarElementsWindow] now, so this
                        // window only ever edits something that exists and always carries its bin. A window
                        // like any other since ADR 0014: no scrim, and it takes its turn in the one
                        // stacking order.
                        ObjectWindowsHost(blockEditWindows) { w ->
                            val block = w.subject
                            val close = w::close
                            ManualEntryEditWindow(
                                initialTitle = block.title,
                                initialTaskId = block.taskId,
                                startMillis = block.fullStartMillis,
                                endMillis = block.fullEndMillis,
                                tz = tz,
                                taskMenuEntries = { draft, exclude ->
                                    SchedulerDomain.calendarTaskMenuEntries(schedulerState, draft, exclude)
                                },
                                titleSuggestions = { SchedulerDomain.placeableTaskTitleSuggestions(schedulerState, it) },
                                taskIdForTitle = { SchedulerDomain.placeableTaskIdForTitle(schedulerState, it) },
                                titleForTaskId = { schedulerState.tasks[it]?.title },
                                initialPins = block.pins,
                                noScreenResilienceForTaskId = { id ->
                                    schedulerState.tasks[id]?.resilienceFor(PeriodKinds.NO_SCREEN)
                                },
                                // The pattern of the panel this block is (an occurrence reads its pattern's).
                                initialRepeatDays = repeatDaysOf(schedulerState.panels, block.entryId),
                                onDismiss = { close() },
                                // PRD §8: the bin — the menu's "Remove" now lives in the window that names
                                // what it deletes.
                                onRemove = {
                                    removeBlockIntent(block)?.let(vm::dispatch)
                                    close()
                                },
                                onSave = { taskId, title, startMillis, endMillis, pins, noScreenResilience, repeatDays ->
                                    commitBoundsIntent(block, taskId, title, startMillis, endMillis, pins, repeatEveryDays = repeatDays)
                                        ?.let(vm::dispatch)
                                    // `side-dev/README.md`: the task's resilience to "no on-screen task",
                                    // saved alongside the panel — the one thing the old pair of switches
                                    // really said. Only dispatched when it actually changed (no no-op unit).
                                    val task = taskId?.let { schedulerState.tasks[it] }
                                    if (task != null && task.resilienceFor(PeriodKinds.NO_SCREEN) != noScreenResilience) {
                                        vm.dispatch(
                                            SchedulerIntent.SetTaskResilience(
                                                taskId, PeriodKinds.NO_SCREEN, noScreenResilience,
                                            ),
                                        )
                                    }
                                    close()
                                },
                                )
                        }

                        // PRD §8: the period editor — one window for every kind, reached from the "add…"
                        // chooser and from a period's own row of the "edit…" chooser.
                        ObjectWindowsHost(periodDraftWindows) { w ->
                            val draft = w.subject
                            val close = w::close
                            PeriodEditWindow(
                                kind = draft.kind,
                                isNew = draft.blocks.isEmpty(),
                                startMillis = draft.startMillis,
                                endMillis = draft.endMillis,
                                nowMillis = nowMillis,
                                tz = tz,
                                onDismiss = { close() },
                                // PRD §8: the bin — the ONE way to get rid of a period now that the menu has
                                // no "Remove". Offered only where there is something stored to delete: a
                                // derived band has no panel behind it, so there is nothing to bin (the way to
                                // be rid of one is to stop whatever derives it).
                                onRemove =
                                    draft.blocks.takeIf { blocks -> blocks.any { it.entryId != null } }
                                        ?.let { blocks ->
                                            {
                                                blocks.forEach { removeBlockIntent(it)?.let(vm::dispatch) }
                                                close()
                                            }
                                        },
                                initialRepeatDays = repeatDaysOf(schedulerState.panels, draft.blocks.firstOrNull { it.entryId != null }?.entryId),
                                onSave = { start, end, repeatDays ->
                                    if (draft.blocks.isEmpty()) {
                                        // `side-dev/README.md`: one intent lays a period of any kind.
                                        vm.dispatch(SchedulerIntent.AddRestrictivePeriod(draft.kind, start, end, repeatDays))
                                    } else {
                                        // Every period the row stands for takes the new bounds — one for an
                                        // ordinary period, two where a "no screen" stretch is spelt as the
                                        // computer + phone pair.
                                        draft.blocks.forEach { block ->
                                            val intent =
                                                if (block.entryId != null) {
                                                    // The ordinary panel-bounds commit, which re-applies the
                                                    // period's own override rule over its new span.
                                                    commitBoundsIntent(
                                                        block, null, block.title, start, end, block.pins,
                                                        repeatEveryDays = repeatDays,
                                                    )
                                                } else {
                                                    // A DERIVED band: the app was reporting this stretch and
                                                    // the user has just stated it, so the edit lays the
                                                    // period the band was standing in for. Its own kind, not
                                                    // the row's: a wind-down hour materializes as
                                                    // `before bed`, never as plain inactivity.
                                                    SchedulerIntent.AddRestrictivePeriod(
                                                        block.restrictiveKind.ifBlank { draft.kind }, start, end, repeatDays,
                                                    )
                                                }
                                            intent?.let(vm::dispatch)
                                        }
                                    }
                                    close()
                                },
                            )
                        }

                        // PRD §8 "add…" / "edit…": the ONE window both entries open. It holds a LIST of
                        // elements and their configuration grouped by who shares it, and — unlike the
                        // chooser it replaced — it is the placement path itself, so its Save is where
                        // "nothing is placed until Save" and "one Save is one Ctrl+Z" are both owed.
                        ObjectWindowsHost(elementsWindows) { w ->
                            val open = w.subject
                            val close = w::close
                            CalendarElementsWindow(
                                mode = open.mode,
                                atMillis = open.atMillis,
                                nowMillis = nowMillis,
                                tz = tz,
                                candidates = open.candidates,
                                periodKinds = schedulerState.allPeriodKinds,
                                // The same intent the task edit window's `+` sends — defining a kind is an
                                // account setting, and this is a second door onto it, not a second rule.
                                onCreatePeriodKind = { vm.dispatch(SchedulerIntent.AddPeriodKind(it)) },
                                taskMenuEntries = { draft, exclude ->
                                    SchedulerDomain.calendarTaskMenuEntries(schedulerState, draft, exclude)
                                },
                                taskTitleSuggestions = {
                                    SchedulerDomain.placeableTaskTitleSuggestions(schedulerState, it)
                                },
                                taskIdForTitle = { SchedulerDomain.placeableTaskIdForTitle(schedulerState, it) },
                                titleForTaskId = { schedulerState.tasks[it]?.title },
                                noScreenResilienceForTaskId = {
                                    schedulerState.tasks[it]?.resilienceFor(PeriodKinds.NO_SCREEN)
                                },
                                // PRD §8 Manual add: the default task a blank search means.
                                defaultTaskId = { SchedulerDomain.manualAddTaskId(schedulerState) },
                                // PRD §8 Manual add: a fresh panel is the task's own minimum time long.
                                panelSpanMillisFor = { taskId ->
                                    (schedulerState.tasks[taskId]?.minimumMinutes?.toLong() ?: 45L) * 60_000L
                                },
                                reminderMenuEntries = { SchedulerDomain.reminderMenuEntries(schedulerState, it) },
                                reminderTitleSuggestions = {
                                    SchedulerDomain.reminderTitleSuggestions(schedulerState, it)
                                },
                                reminderIdForTitle = { SchedulerDomain.reminderIdForTitle(schedulerState, it) },
                                alarms = schedulerState.alarms,
                                // PRD §18: what an alarm added here starts with — the account's defaults.
                                newAlarm = schedulerState.newAlarmDefaults,
                                // PRD §8: the bin of one row. A row the window is only ADDING has nothing
                                // stored behind it, so the window never calls this for one.
                                onRemove = { draft ->
                                    removeCalendarElementIntents(draft, schedulerState).forEach(vm::dispatch)
                                },
                                onSave = { drafts ->
                                    saveCalendarElementIntents(drafts, schedulerState, tz).forEach(vm::dispatch)
                                    close()
                                },
                                onDismiss = { close() },
                            )
                        }

                        // PRD §14: the same editor, opened on an EXISTING tag by the "edit…" chooser's
                        // `reminder` row. A tag had no way of being edited from the calendar before the
                        // chooser — only added, and only checked off — so the row is what made this window
                        // need a seed and a bin.
                        ObjectWindowsHost(reminderTagWindows) { w ->
                            val tag = w.subject
                            val close = w::close
                            ReminderEditWindow(
                                initialMillis = tag.fullStartMillis,
                                tz = tz,
                                initial = ReminderEditSeed(tag.title, tag.checked, tag.pinned),
                                reminderMenuEntries = { SchedulerDomain.reminderMenuEntries(schedulerState, it) },
                                titleSuggestions = { SchedulerDomain.reminderTitleSuggestions(schedulerState, it) },
                                reminderIdForTitle = { SchedulerDomain.reminderIdForTitle(schedulerState, it) },
                                titleForReminderId = { SchedulerDomain.reminderTitleForId(schedulerState, it) },
                                onDismiss = { close() },
                                onRemove = {
                                    removeBlockIntent(tag)?.let(vm::dispatch)
                                    close()
                                },
                                // A tag's identity is its panel, and §14 lays one through AddReminder — so an
                                // edit is the old tag struck off and the new one laid, in that order. (Two
                                // intents, therefore two history units: an edited tag takes two Ctrl+Z. The
                                // alternative is a third way of writing a reminder panel, which is the kind
                                // of second funnel this whole reshape exists to remove.)
                                onSave = { reminderId, title, at, checked, pinned ->
                                    removeBlockIntent(tag)?.let(vm::dispatch)
                                    vm.dispatch(SchedulerIntent.AddReminder(reminderId, title, at, checked, pinned))
                                    close()
                                },
                            )
                        }

                    }

                    // PRD §14 / PRD §7 Search: the per-object window of one reminder — opened from its Search row.
                    // It closes itself when its row is gone (ChoresManagerWindow). No existence check here: the
                    // window follows its row even when the id menu makes it adopt another reminder's id (and its
                    // "+ New reminder" moves it on), and closes itself once the row is gone — one rule, in one place.
                    // PRD §14 "constrained in": the picker a Search window's reminder editor opened, on one reminder. It writes
                    // that reminder's constraint into the account's list, the editor's own write.
                    ObjectWindowsHost(reminderConstraintWindows) { w ->
                        // One reminder's id, or several joined by a line break (the Search window's "Constrained in",
                        // over every added reminder at once): what is saved, all of them get.
                        val reminderIds = w.subject.split('\n').toSet()
                        val reminderId = w.subject.substringBefore('\n')
                        val reminder = schedulerState.chores.firstOrNull { it.id == reminderId }
                        if (reminder == null) {
                            w.close()
                        } else {
                            ReminderConstraintEditWindow(
                                initialReminderId =
                                    schedulerState.chores.filter { it.id in reminderIds }
                                        .map { it.constrainedToReminderId }.distinct().singleOrNull().orEmpty(),
                                // A reminder can't be constrained to itself, so hide its own identity from the picker
                                // (with several, the save below skips the one that is the constraint).
                                excludeReminderId = if (reminderIds.size == 1) reminderId else "",
                                reminderMenuEntries = { SchedulerDomain.reminderMenuEntries(schedulerState, it) },
                                titleSuggestions = { SchedulerDomain.reminderTitleSuggestions(schedulerState, it) },
                                reminderIdForTitle = { SchedulerDomain.reminderIdForTitle(schedulerState, it) },
                                titleForReminderId = { SchedulerDomain.reminderTitleForId(schedulerState, it) },
                                onDismiss = w::close,
                                onSave = { constraint ->
                                    val todayStartMillis = today.atStartOfDayIn(tz).toEpochMilliseconds()
                                    vm.dispatch(
                                        SchedulerIntent.SetChores(
                                            schedulerState.chores.map {
                                                if (it.id in reminderIds && it.id != constraint) it.copy(constrainedToReminderId = constraint) else it
                                            },
                                            todayStartMillis,
                                            nowMillis,
                                        ),
                                    )
                                    w.close()
                                },
                            )
                        }
                    }

                    // PRD §5/§6 History Manager: floating window listing every category's history units.
                    LateralWindow(FloatingWindow.History, historyManagerOpen) {
                        HistoryManagerWindow(
                            histories = schedulerState.histories,
                            notificationLog = schedulerState.notificationLog,
                            supabaseUsageLog = schedulerState.supabaseUsageLog,
                            schedulerRuns = schedulerRuns,
                            onDismiss = { historyManagerOpen = false },
                            // Cascade: open down-right of center so the calendar window stays reachable.
                            initialOffset = historyOffset,
                            initialSize = historySize,
                            onGeometryChange = { windowOffset, windowSize ->
                                historyOffset = windowOffset
                                historySize = windowSize
                                persistPlacement(FloatingWindow.History, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.History) },
                            modifier = Modifier
                                .align(Alignment.Center),
                        )
                    }

                    // Sleep schedule: floating window to configure the nightly sleep window the scheduler avoids.
                    LateralWindow(FloatingWindow.Sleep, sleepWindowOpen) {
                        SleepWindow(
                            sleep = schedulerState.sleep ?: SchedulerDomain.DEFAULT_SLEEP,
                            onSave = { vm.dispatch(SchedulerIntent.SetSleepSchedule(it, today.toEpochDays().toLong())) },
                            onDismiss = { sleepWindowOpen = false },
                            // Cascade: open up-right of center so the other windows stay reachable.
                            initialOffset = sleepOffset,
                            initialSize = sleepSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                sleepOffset = windowOffset
                                sleepSize = windowSize
                                persistPlacement(FloatingWindow.Sleep, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.Sleep) },
                            modifier = Modifier
                                .align(Alignment.Center),
                        )
                    }

                    // PRD §18 Alarms and timers: floating window listing the account's alarms (time, sound
                    // length, vibration) and, in its second section, its timers. Both lists are authoritative
                    // synced state, so saving one here arms every phone — and starting a timer starts it for
                    // the account, not for this device.
                    // The Alarms window and the per-object window of ONE alarm or timer (PRD §7 Search) are the
                    // same component over the same callbacks, so they are wired once, here.
                    @Composable
                    fun AccountAlarmWindow(
                        subject: AlarmWindowSubject?,
                        onDismiss: () -> Unit,
                        modifier: Modifier,
                        onShownChange: (AlarmWindowSubject) -> Unit = {},
                        // PRD §18: the window of the default configuration of a new alarm or timer ([subject]
                        // names its one row): it edits the account's defaults, not its alarms.
                        defaults: Boolean = false,
                        // The Search window's actions: the rows of exactly these elements, embedded (AlarmWindow).
                        embeddedSubjects: Set<AlarmWindowSubject>? = null,
                        embeddedRunOnly: Boolean = false,
                        initialOffset: Offset = Offset.Zero,
                        initialSize: Size = Size.Zero,
                        onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
                        onRaise: () -> Unit = {},
                    ) {
                        AlarmWindow(
                            alarms =
                                if (defaults) listOf(schedulerState.newAlarmDefaults.copy(id = DEFAULT_CONFIGURATION_ROW_ID))
                                else schedulerState.alarms,
                            onChange = { entries, editKey ->
                                if (defaults) {
                                    entries.firstOrNull()?.let { vm.dispatch(SchedulerIntent.SetNewAlarmDefaults(it)) }
                                } else {
                                    vm.dispatch(SchedulerIntent.SetAlarms(entries, editKey))
                                }
                            },
                            timers =
                                if (defaults) listOf(schedulerState.newTimerDefaults.copy(id = DEFAULT_CONFIGURATION_ROW_ID))
                                else schedulerState.timers,
                            onTimersChange = { entries, editKey ->
                                if (defaults) {
                                    entries.firstOrNull()?.let { vm.dispatch(SchedulerIntent.SetNewTimerDefaults(it)) }
                                } else {
                                    vm.dispatch(SchedulerIntent.SetTimers(entries, editKey))
                                }
                            },
                            // The run-state writes are dispatched with the clock's instant, not the display
                            // now-line: a countdown started at 17:00:00.4 must end 5 minutes after that, not
                            // after the quantized tick the calendar is drawn against — and the same holds for
                            // a minute typed into a running timer's countdown, or a -10s press.
                            onStartTimer = { vm.dispatch(SchedulerIntent.StartTimer(it, clock.nowMillis())) },
                            onPauseTimer = { vm.dispatch(SchedulerIntent.PauseTimer(it, clock.nowMillis())) },
                            onResetTimer = { vm.dispatch(SchedulerIntent.ResetTimer(it)) },
                            onSetTimerCountdownField = { id, field, value, held ->
                                vm.dispatch(
                                    SchedulerIntent.SetTimerCountdownField(id, field, value, clock.nowMillis(), held),
                                )
                            },
                            onNudgeTimerRemaining = { id, delta ->
                                vm.dispatch(SchedulerIntent.NudgeTimerRemaining(id, delta, clock.nowMillis()))
                            },
                            // PRD §18 Chronos: none in a default configuration's window — a chrono has none.
                            chronos = if (defaults) emptyList() else schedulerState.chronos,
                            onChronosChange = { entries, editKey ->
                                if (!defaults) vm.dispatch(SchedulerIntent.SetChronos(entries, editKey))
                            },
                            onStartChrono = { vm.dispatch(SchedulerIntent.StartChrono(it, clock.nowMillis())) },
                            onPauseChrono = { vm.dispatch(SchedulerIntent.PauseChrono(it, clock.nowMillis())) },
                            onResetChrono = { vm.dispatch(SchedulerIntent.ResetChrono(it)) },
                            // Read straight off the clock (simulated under §16), and polled by the window
                            // itself while a timer runs — the engine's now-line only advances once per
                            // production tick, which is far too coarse for a countdown.
                            nowMillis = { clock.nowMillis() },
                            // PRD §5: the window's own lists are undoable, so the chord has to work from
                            // inside it — the tree's and the calendar's handlers never see a keystroke aimed
                            // at a floating window.
                            onUndo = { vm.dispatch(SchedulerIntent.Undo) },
                            onRedo = { vm.dispatch(SchedulerIntent.Redo) },
                            // Pre-fill a newly added alarm's Time field with the current clock time.
                            newRowTimeOfDayMinutes = {
                                val t = Instant.fromEpochMilliseconds(clock.nowMillis()).toLocalDateTime(tz)
                                t.hour * 60 + t.minute
                            },
                            onDismiss = onDismiss,
                            initialOffset = initialOffset,
                            initialSize = initialSize,
                            onGeometryChange = onGeometryChange,
                            onRaise = onRaise,
                            modifier = modifier,
                            subject = subject,
                            onShownChange = onShownChange,
                            newAlarm = schedulerState.newAlarmDefaults,
                            newTimer = schedulerState.newTimerDefaults,
                            onOpenDefaults = { isAlarm ->
                                defaultsWindows.open(
                                    if (isAlarm) ObjectWindowKey.Kind.AlarmDefaults else ObjectWindowKey.Kind.TimerDefaults,
                                )
                            },
                            defaults = defaults,
                            embeddedSubjects = embeddedSubjects,
                            embeddedRunOnly = embeddedRunOnly,
                        )
                    }

                    LateralWindow(FloatingWindow.Alarms, alarmWindowOpen) {
                        AccountAlarmWindow(
                            subject = null,
                            onDismiss = { alarmWindowOpen = false },
                            // Cascade: open down-left of center so the other windows stay reachable.
                            initialOffset = alarmOffset,
                            initialSize = alarmSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                alarmOffset = windowOffset
                                alarmSize = windowSize
                                persistPlacement(FloatingWindow.Alarms, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.Alarms) },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    // PRD §14/§18: the default configuration of a new alarm / timer / reminder — the element's own
                    // editor, settings only, writing the account's defaults (NewElementDefaults).
                    ObjectWindowsHost(defaultsWindows) { w ->
                        if (w.subject == ObjectWindowKey.Kind.ReminderDefaults) {
                            TransientPopupLayer(windowInstanceId(REMINDER_DEFAULTS_FRAME_ID)) {
                                ChoresManagerWindow(
                                    chores = listOf(schedulerState.newReminderDefaults.copy(id = DEFAULT_CONFIGURATION_ROW_ID)),
                                    onChange = { entries ->
                                        entries.firstOrNull()?.let { vm.dispatch(SchedulerIntent.SetNewReminderDefaults(it)) }
                                    },
                                    onDismiss = w::close,
                                    subject = DEFAULT_CONFIGURATION_ROW_ID,
                                    defaults = true,
                                    // "constrained in" is a setting too: its picker names the account's reminders.
                                    reminderMenuEntries = { SchedulerDomain.reminderMenuEntries(schedulerState, it) },
                                    titleSuggestions = { SchedulerDomain.reminderTitleSuggestions(schedulerState, it) },
                                    reminderIdForTitle = { SchedulerDomain.reminderIdForTitle(schedulerState, it) },
                                    titleForReminderId = { SchedulerDomain.reminderTitleForId(schedulerState, it) },
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        } else {
                            TransientPopupLayer(windowInstanceId(ALARM_DEFAULTS_FRAME_ID)) {
                                AccountAlarmWindow(
                                    subject = AlarmWindowSubject(
                                        DEFAULT_CONFIGURATION_ROW_ID,
                                        if (w.subject == ObjectWindowKey.Kind.AlarmDefaults) AlarmWindowSubject.Kind.Alarm
                                        else AlarmWindowSubject.Kind.Timer,
                                    ),
                                    onDismiss = w::close,
                                    modifier = Modifier.align(Alignment.Center),
                                    defaults = true,
                                )
                            }
                        }
                    }

                    // All task trees: the account's named task trees over a timeline of the dated ones.
                    // A date makes a tree a keyframe the scheduler blends its priorities between
                    // (SchedulerDomain.blendedTaskPriorities), so both edits here are authoritative.
                    LateralWindow(FloatingWindow.TaskTrees, taskTreesWindowOpen) {
                        TaskTreesWindow(
                            trees = schedulerState.taskTrees,
                            activeId = schedulerState.activeTaskTreeId,
                            // The quantized display now-line, like every other per-tick read here: the
                            // marker only has to say which two trees `now` sits between.
                            nowMillis = nowMillis,
                            timeZone = tz,
                            onSetDate = { id, date ->
                                vm.dispatch(SchedulerIntent.SetTaskTreeDate(id, date))
                            },
                            onDelete = { vm.dispatch(SchedulerIntent.DeleteTaskTree(it)) },
                            onDismiss = { taskTreesWindowOpen = false },
                            windowSelections = schedulerState.windowSelections,
                            onSelectInWindow = { vm.dispatch(it) },
                            initialOffset = taskTreesOffset,
                            initialSize = taskTreesSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                taskTreesOffset = windowOffset
                                taskTreesSize = windowSize
                                persistPlacement(FloatingWindow.TaskTrees, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.TaskTrees) },
                            modifier = Modifier
                                .align(Alignment.Center),
                        )
                    }

                    // PRD §5 Task relations: every (task, relational target) pair the priority machinery
                    // has raised, in four sections. It reads the LIVE state — a relation is a fact about the
                    // account's own tree — and its two buttons are the only writers of
                    // `SchedulerState.taskRelations` beside the relative-priority window's own recording.
                    LateralWindow(FloatingWindow.TaskRelations, taskRelationsWindowOpen) {
                        TaskRelationsWindow(
                            state = schedulerState,
                            onIntent = { vm.dispatch(it) },
                            onDismiss = { taskRelationsWindowOpen = false },
                            initialOffset = taskRelationsOffset,
                            initialSize = taskRelationsSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                taskRelationsOffset = windowOffset
                                taskRelationsSize = windowSize
                                persistPlacement(FloatingWindow.TaskRelations, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.TaskRelations) },
                            modifier = Modifier
                                .align(Alignment.Center),
                        )
                    }

                    // PRD §5 Categories: every category the account holds — the question neither the task
                    // cell's field (one task, every category) nor the category edit window (one category,
                    // every task) asks. It reads the LIVE state, and a row's ✎ opens that category's own
                    // category window, which stays the one place a category is renamed, ruled or deleted.
                    LateralWindow(FloatingWindow.Categories, categoriesWindowOpen) {
                        CategoriesWindow(
                            state = schedulerState,
                            onIntent = { vm.dispatch(it) },
                            // The account's own categories, never the template's projection — this window is
                            // about the live state, so the pop-up it opens must be too.
                            onOpenCategoryEdit = { openElementSearch(SearchDomain.Kind.Category, it.value) },
                            onDismiss = { categoriesWindowOpen = false },
                            initialOffset = categoriesOffset,
                            initialSize = categoriesSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                categoriesOffset = windowOffset
                                categoriesSize = windowSize
                                persistPlacement(FloatingWindow.Categories, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.Categories) },
                            modifier = Modifier
                                .align(Alignment.Center),
                        )
                    }

                    // PRD §7 Search: a task, a task category, a restrictive-period kind, an alarm, a timer or a
                    // reminder, found by name. It reads the LIVE state, and every gesture on a row goes through
                    // the handler the rest of the app already uses for it — the §13 windows hoisted above, the
                    // one "go to task tree", the lateral-menu windows that own an alarm or a reminder.
                    // How a Search row is opened — ONE mapping, for the result list and for the added elements'
                    // "Open each" in both windows that offer it.
                    val searchRowOpeners =
                        SearchRowOpeners(
                            // An element's row opens the Search window holding it alone (user rule 2026-10-01).
                            onOpenTaskEdit = { openElementSearch(SearchDomain.Kind.Task, it.value) },
                            onOpenCategory = { openElementSearch(SearchDomain.Kind.Category, it.value) },
                            onOpenPeriodKind = { openElementSearch(SearchDomain.Kind.RestrictivePeriod, it) },
                            onEditAlarmOrTimer = { subject ->
                                val kind = when (subject.kind) {
                                    AlarmWindowSubject.Kind.Alarm -> SearchDomain.Kind.Alarm
                                    AlarmWindowSubject.Kind.Timer -> SearchDomain.Kind.Timer
                                    AlarmWindowSubject.Kind.Chrono -> SearchDomain.Kind.Chrono
                                }
                                openElementSearch(kind, subject.id)
                            },
                            onEditReminder = { openElementSearch(SearchDomain.Kind.Reminder, it) },
                            onOpenAppSetting = { openElementSearch(SearchDomain.Kind.AppSetting, it) },
                            onOpenCalendarAt = { openNewWindow(FloatingWindow.Search, SearchDomain.calendarAtConfig(it).encode()) },
                            // A history unit's own window is the Search window holding it alone, whose "Information"
                            // action shows all of it (user rule 2026-10-03) — not the History window any more.
                            onOpenHistoryUnit = { openElementSearch(SearchDomain.Kind.HistoryUnit, it) },
                            onOpenQuota = { openElementSearch(SearchDomain.Kind.Quota, it) },
                            // The windows that own a task tree, a task relation or a shortcut — opened if closed and
                            // brought to the front either way, never closed by this.
                            onOpenTaskTrees = {
                                taskTreesWindowOpen = true
                                focusWindow(FloatingWindow.TaskTrees)
                            },
                            onOpenTaskRelations = {
                                taskRelationsWindowOpen = true
                                focusWindow(FloatingWindow.TaskRelations)
                            },
                            onOpenShortcuts = {
                                shortcutsWindowOpen = true
                                focusWindow(FloatingWindow.Shortcuts)
                            },
                            onOpenWindow = ::showWindow,
                            onCreate = ::createElement,
                        )
                    // What the actions on the added elements open or draw — the cell menu's handlers, and the Alarms
                    // window's and the reminder editor's components embedded over the same callbacks.
                    val addedActionHandlers =
                        AddedActionHandlers(
                            onStartNow = { vm.dispatch(SchedulerIntent.ForceTaskStart(it)) },
                            onEdit = { openElementSearch(SearchDomain.Kind.Task, it.value) },
                            onGoToTaskTree = { taskId ->
                                goToTaskTreeAt(taskId, schedulerState.tasks[taskId]?.title.orEmpty(), null)
                            },
                            onDeepCopyCell = { deepCopyWindows.open(TreeObject(it)) },
                            onOpenResilienceSearch = { kind ->
                                openNewWindow(FloatingWindow.Search, SearchDomain.resilienceSearchConfig(kind).encode())
                            },
                            onOpenKindSearch = { kind ->
                                openNewWindow(FloatingWindow.Search, SearchDomain.kindSearchConfig(kind).encode())
                            },
                            schedulerRuns = { schedulerRuns },
                            onDragOnCalendar = { presentWindow(FloatingWindow.Calendar, FloatingWindow.Calendar.name) },
                            onSetNotificationsEnabled = { engine.setNotificationsEnabled(it) },
                            onOpenBlocksSearch = { owners ->
                                openNewWindow(FloatingWindow.Search, SearchDomain.blocksSearchConfig(owners).encode())
                            },
                            timerRun = { subjects ->
                                AccountAlarmWindow(
                                    subject = null,
                                    onDismiss = {},
                                    modifier = Modifier,
                                    embeddedSubjects = subjects,
                                    embeddedRunOnly = true,
                                )
                            },
                            onCreate = ::createElement,
                            onPlaceOnCalendar = { drafts ->
                                saveCalendarElementIntents(drafts, vm.state.value, tz).forEach(vm::dispatch)
                            },
                            // One picker over every added reminder: their ids, joined (the host below splits them).
                            onEditReminderConstraint = { ids ->
                                if (ids.isNotEmpty()) reminderConstraintWindows.open(ids.joinToString("\n"))
                            },
                            onDuplicate = { intents, kind ->
                                val before = vm.state.value
                                intents.forEach(vm::dispatch)
                                SearchDomain.newElementKeys(before, vm.state.value, kind)
                            },
                            reminderEditor = { subjects ->
                                val todayStartMillis = today.atStartOfDayIn(tz).toEpochMilliseconds()
                                ChoresManagerWindow(
                                    chores = schedulerState.chores,
                                    onChange = { vm.dispatch(SchedulerIntent.SetChores(it, todayStartMillis, nowMillis)) },
                                    onDismiss = {},
                                    subject = "",
                                    embeddedSubjects = subjects,
                                    onEditConstraint = { reminderConstraintWindows.open(it) },
                                    newReminder = schedulerState.newReminderDefaults,
                                    onOpenDefaults = { defaultsWindows.open(ObjectWindowKey.Kind.ReminderDefaults) },
                                    reminderMenuEntries = { SchedulerDomain.reminderMenuEntries(schedulerState, it) },
                                    titleSuggestions = { SchedulerDomain.reminderTitleSuggestions(schedulerState, it) },
                                    knownReminderIds = {
                                        SchedulerDomain.allReminderEntries(schedulerState).mapTo(mutableSetOf()) { it.id }
                                    },
                                    referencedReminderIds = { SchedulerDomain.referencedReminderIds(schedulerState) },
                                    reminderIdForTitle = { SchedulerDomain.reminderIdForTitle(schedulerState, it) },
                                    titleForReminderId = { SchedulerDomain.reminderTitleForId(schedulerState, it) },
                                )
                            },
                        )
                    androidx.compose.runtime.SideEffect {
                        menuActionContext.value = addedActionHandlers to { row -> searchRowOpeners.open(schedulerState, row) }
                    }
                    LateralWindow(FloatingWindow.Search, searchWindowOpen) {
                        val searchId = windowInstanceId(FloatingWindow.Search.name)
                        val searchConfig = searchConfigOf(searchId)
                        SearchWindow(
                            state = schedulerState,
                            openers = searchRowOpeners,
                            onStartTaskNow = { vm.dispatch(SchedulerIntent.ForceTaskStart(it)) },
                            onGoToTaskTree = { taskId, at ->
                                goToTaskTreeAt(taskId, schedulerState.tasks[taskId]?.title.orEmpty(), at)
                            },
                            onDeepCopyCell = { deepCopyWindows.open(TreeObject(it)) },
                            actionHandlers = addedActionHandlers,
                            calendarLayerKindsAt = calendarLayers::kindsAt,
                            // What this window commits is its own, even when it lands after the press that left it
                            // (a row's rename is committed on blur, once the focus has already moved).
                            onIntent = {
                                vm.dispatch(SchedulerIntent.MadeIn(HistoryWindow.Search, searchId.removePrefix(FloatingWindow.Search.name), it))
                            },
                            // A row's percentage, as a tree cell's: the weight table and the relative priority.
                            onSetWeightWindow = { weightWindows.setFrom(template = false, it) },
                            onSetRelativeWindow = { relativeWindows.setFrom(template = false, it) },
                            windows = if (searchConfig.readsWindows) searchWindowEntries() else emptyList(),
                            schedulerRuns = schedulerRuns,
                            nowMillis = clock::nowMillis,
                            onDismiss = { searchWindowOpen = false },
                            config = searchConfig,
                            onConfigChange = { setSearchConfig(searchId, it) },
                            openedConfig = searchOpenedConfigs[searchId],
                            deployKinds = searchId in searchKindsToDeploy,
                            onKindsDeployed = { searchKindsToDeploy.remove(searchId) },
                            // THIS Search window's own configurations window: brought back, else opened.
                            onOpenConfigurations = { openConfigurationsOf(FloatingWindow.ConfigSearch, searchId) },
                            // The same, for the actions on the added elements.
                            onOpenAddedConfigurations = { openConfigurationsOf(FloatingWindow.AddedConfig, searchId) },
                            splits = searchSplits[searchId],
                            onSplitsChange = {
                                if (searchSplits[searchId] != it) {
                                    searchSplits[searchId] = it
                                    saveWindowSections()
                                }
                            },
                            initialOffset = searchOffset,
                            initialSize = searchSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                searchOffset = windowOffset
                                searchSize = windowSize
                                persistPlacement(FloatingWindow.Search, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.Search) },
                            modifier = Modifier
                                .align(Alignment.Center),
                        )
                    }

                    // PRD §5: every online configuration of this device — status, work offline, the account.
                    LateralWindow(FloatingWindow.Online, onlineWindowOpen) {
                        OnlineWindow(
                            state = syncStateValue,
                            account = accountValue,
                            offline = offlineValue,
                            onSetOffline = vm::setOffline,
                            onSignIn = { e, p -> vm.signIn(e, p) },
                            onCreateAccount = { e, p -> vm.createAccount(e, p) },
                            onSignOut = { vm.signOut() },
                            // PRD §15: manual server check. The reconcile emits a unified sync moment, which also
                            // runs the side channels (active sessions, derived pauses, exact pause gaps) — see
                            // SchedulerEngine.launchSyncMomentSideChannels.
                            onFetch = { vm.syncNow() },
                            onDismiss = { onlineWindowOpen = false },
                            initialOffset = onlineOffset,
                            initialSize = onlineSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                onlineOffset = windowOffset
                                onlineSize = windowSize
                                persistPlacement(FloatingWindow.Online, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.Online) },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    // PRD §7 Search: every configuration of the Search window, in sections per kind. It edits
                    // the same `searchConfig` the Search window reads, so its filters narrow that list at once.
                    LateralWindow(FloatingWindow.ConfigSearch, configSearchWindowOpen) {
                        val configId = windowInstanceId(FloatingWindow.ConfigSearch.name)
                        val own = configSearchOf(configId)
                        val target = configTargetOf(configId)
                        ConfigurationSearchWindow(
                            state = schedulerState,
                            config = searchConfigOf(target),
                            onConfigChange = { setSearchConfig(target, it) },
                            openedConfig = searchOpenedConfigs[target],
                            own = own,
                            onOwnChange = { setConfigSearch(configId, it) },
                            onDismiss = { configSearchWindowOpen = false },
                            windows = if (own.onlyResultKinds) searchWindowEntries() else emptyList(),
                            initialOffset = configSearchOffset,
                            initialSize = configSearchSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                configSearchOffset = windowOffset
                                configSearchSize = windowSize
                                persistPlacement(FloatingWindow.ConfigSearch, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.ConfigSearch) },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    // PRD §7 Search: every action on a Search window's added elements, in sections per kind — the
                    // Configuration Search window's twin. It acts on the `added` list of the Search window it was
                    // opened from, which it reads off the same `searchConfig`.
                    LateralWindow(FloatingWindow.AddedConfig, addedConfigWindowOpen) {
                        val configId = windowInstanceId(FloatingWindow.AddedConfig.name)
                        val own = configSearchOf(configId)
                        val target = configTargetOf(configId)
                        val targetConfig = searchConfigOf(target)
                        val windows = if (targetConfig.readsWindows) searchWindowEntries() else emptyList()
                        val added =
                            // Keyed on what the rows read, never on the whole state, which every tick replaces.
                            remember(
                                targetConfig.added, schedulerState.tasks, schedulerState.taskTrees,
                                schedulerState.categories, schedulerState.periodKinds, schedulerState.panels,
                                schedulerState.alarms, schedulerState.timers, schedulerState.chronos, schedulerState.quotas, schedulerState.quotas,
                                schedulerState.chores, schedulerState.histories, schedulerState.taskRelations,
                                schedulerState.shortcutBindings, schedulerState.activeTaskTreeId,
                                schedulerState.cells, schedulerState.lists, windows, schedulerState.notificationLog, schedulerRuns,
                            ) {
                                SearchDomain.resolve(schedulerState, targetConfig.added, { emptyMap() }, windows, schedulerRuns)
                            }
                        AddedElementsConfigurationWindow(
                            state = schedulerState,
                            added = added,
                            config = targetConfig,
                            onConfigChange = { setSearchConfig(target, it) },
                            handlers = addedActionHandlers,
                            own = own,
                            onOwnChange = { setConfigSearch(configId, it) },
                            onIntent = {
                                vm.dispatch(
                                    SchedulerIntent.MadeIn(HistoryWindow.AddedConfig, configId.removePrefix(FloatingWindow.AddedConfig.name), it),
                                )
                            },
                            nowMillis = clock::nowMillis,
                            onOpenEach = { elements -> elements.forEach { searchRowOpeners.open(schedulerState, it) } },
                            onClear = { elements ->
                                val gone = elements.mapTo(HashSet(), SearchDomain::keyOf)
                                setSearchConfig(target, targetConfig.copy(added = targetConfig.added.filterNot { it in gone }))
                            },
                            onDismiss = { addedConfigWindowOpen = false },
                            initialOffset = addedConfigOffset,
                            initialSize = addedConfigSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                addedConfigOffset = windowOffset
                                addedConfigSize = windowSize
                                persistPlacement(FloatingWindow.AddedConfig, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.AddedConfig) },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    // PRD §4 Default sub-tree: the template grafted under every task the user creates. It
                    // draws the SAME task-tree component the account's own tree does, over the state
                    // projectDefaultSubtree() makes of the template — so it has the §13 contextual menu and
                    // every tree gesture, with one switch per row added. The template and the switch beside
                    // its menu button are authoritative synced state.
                    LateralWindow(FloatingWindow.DefaultSubtree, defaultSubtreeWindowOpen) {
                        Perf.measure("compose.DefaultSubtreeWindow") {
                        DefaultSubtreeWindow(
                            state = schedulerState,
                            enabled = schedulerState.defaultSubtreeEnabled,
                            onIntent = { vm.dispatch(it) },
                            // The tree inside owns the keyboard only while this window is the front one.
                            focused = windowFrames.frontId == windowInstanceId(FloatingWindow.DefaultSubtree.name),
                            // PRD §5/§13: the same four per-object windows the account's tree opens, drawn by
                            // the app on the top layer — a template row's "edit task" is the ordinary §13 window.
                            onSetWeightWindow = { weightWindows.setFrom(template = true, it) },
                            onSetRelativeWindow = { relativeWindows.setFrom(template = true, it) },
                            onSetEditTask = { taskEditWindows.setFrom(template = true, it) },
                            onSetEditCategory = { categoryWindows.setFrom(template = true, it) },
                            onSetDeepCopyCell = { deepCopyWindows.setFrom(template = true, it) },
                            onDismiss = { defaultSubtreeWindowOpen = false },
                            initialOffset = defaultSubtreeOffset,
                            initialSize = defaultSubtreeSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                defaultSubtreeOffset = windowOffset
                                defaultSubtreeSize = windowSize
                                persistPlacement(FloatingWindow.DefaultSubtree, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.DefaultSubtree) },
                            modifier = Modifier
                                .align(Alignment.Center),
                        )
                        }
                    }

                    // PRD §7 Keyboard shortcuts: the reference list of every chord, plus what claim the OS
                    // granted the system-wide ones (the only shortcuts another application can take —
                    // and so the only ones the window lets the user rebind).
                    LateralWindow(FloatingWindow.Shortcuts, shortcutsWindowOpen) {
                        ShortcutsWindow(
                            claim = globalHotkeyClaim,
                            bindings = schedulerState.shortcutBindings,
                            onRebind = { shortcut, binding ->
                                vm.dispatch(SchedulerIntent.SetGlobalShortcutBinding(shortcut, binding))
                            },
                            onDismiss = { shortcutsWindowOpen = false },
                            initialOffset = shortcutsOffset,
                            initialSize = shortcutsSize,
                            onGeometryChange = { windowOffset, windowSize ->
                                shortcutsOffset = windowOffset
                                shortcutsSize = windowSize
                                persistPlacement(FloatingWindow.Shortcuts, windowOffset, windowSize, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.Shortcuts) },
                            modifier = Modifier
                                .align(Alignment.Center),
                        )
                    }

                    // Debug-only time-acceleration control (gated by DebugFlags.TIME_SIMULATION).
                    if (DebugFlags.TIME_SIMULATION) {
                        TimeSimPanel(
                            clock = simClock,
                            nowMillis = nowMillis,
                            linkedCount = timeLinkCount,
                            // Debug: simulate taking a pause by INSTANTLY jumping the sim clock forward by the
                            // whole break (the now-line leaps to its end) — the engine's own loops (active-session
                            // beat, schedule advance, derived-pause seeding) then live the jumped-over window
                            // exactly as the release logic would, with the dropdown-selected device(s) forced to
                            // read as screen-inactive across it. On the phone the forced-inactivity flag rides the
                            // same time-link frames as the leaped clock, so it adopts both atomically.
                            onSimulatePause = { durationMillis, pauseScope ->
                                if (pauseLeapJob?.isActive != true) {
                                    pauseLeapJob = engineScope.launch {
                                        // Force inactivity FIRST, at the pre-leap (walk-away) instant, so the
                                        // selected device(s) finalize their active session there and not after
                                        // the jump. The desktop finalize is synchronous ([setDebugForcedInactive]);
                                        // a linked phone only finalizes once it receives the inactive frame, so
                                        // give it one frame to arrive before the clock jumps out from under it.
                                        if (pauseScope != SimPauseScope.PhoneOnly) {
                                            engine.setDebugForcedInactive(true)
                                        }
                                        if (pauseScope != SimPauseScope.ComputerOnly) {
                                            timeLink?.setPhoneForcedInactive(true)
                                            if (timeLinkCount > 0) delay(SIM_PAUSE_LEAP_SETTLE_MILLIS)
                                        }
                                        // Instantly jump the now-line forward by the whole break (no acceleration
                                        // ramp). The clock's `reconfigured` bump wakes the engine loops within a
                                        // frame: the schedule advance banks the elapsed records in one step, and
                                        // the cue sweep scans the jumped-over window.
                                        simClock.leap(durationMillis)
                                        // Let that sweep run while the screen still reads inactive, so the
                                        // look-away cues inside the jumped window are suppressed (the user "slept
                                        // through" them) instead of firing in a burst when the session reopens.
                                        delay(SIM_PAUSE_LEAP_SETTLE_MILLIS)
                                        engine.setDebugForcedInactive(false)
                                        // A linked phone lives the leap via the time-link frames and derives its
                                        // own LOCAL bands; activity is no longer synced, so there is nothing to
                                        // push or ack — just clear the phone flag and re-derive this device's
                                        // own "Inactivity" bands / reseed the rest poses from the simulated pause.
                                        timeLink?.setPhoneForcedInactive(false)
                                        engine.refreshDerivedPauses()
                                    }
                                }
                            },
                            pendingRollback = schedulerState.histories.hasPendingDebugRollback,
                            modifier = Modifier
                                // Bottom-left of the content area — just to the right of the lateral menu.
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                                // The one z left in `App`: the debug panel wears no window frame.
                                .zIndex(windowFrames.zOf(FloatingWindow.TimeSim.name))
                                .raiseOnPress { focusWindow(FloatingWindow.TimeSim) },
                        )
                    }

                    // Debug-only performance overlay (gated by DebugFlags.PERF, independent of the time sim).
                    // Top-right of the content area, above everything: it has to stay readable while a window
                    // is being dragged over the calendar, since that gesture is one of the things it measures.
                    if (DebugFlags.PERF) {
                        PerfOverlay(
                            nowMillis = nowMillis,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .zIndex(Float.MAX_VALUE),
                        )
                    }
                }
            }

            // The bar of REDUCED windows, along the bottom of the app. Drawn at the app root and over the
            // lateral menu — a window reduced while the menu is open must not be filed behind it — and
            // above the floating windows' own z-stack, which is why it is not inside the content Box.
            WindowBar(
                host = windowFrames,
                modifier = Modifier.align(Alignment.BottomStart).zIndex(135f),
                // Reset is the DEFAULT layout (user rule 2026-10-01): every window closed, then the calendar alone,
                // open and maximized. The task tree's placement goes back to a first run's too (centred, default
                // size, maximized), for when it is next opened.
                onReset = {
                    taskTreeOffset = Offset.Zero
                    taskTreeSize = Size.Zero
                    persistPlacement(FloatingWindow.TaskTree, Offset.Zero, Size.Zero, visible = false)
                    windowChromeMemory.save(FloatingWindow.TaskTree.name, TASK_TREE_DEFAULT_CHROME)
                    calendarOffset = Offset.Zero
                    calendarSize = Size.Zero
                    engineScope.launch {
                        // The closes take the windows out of composition first. Reopened in the same frame, the
                        // calendar would never leave it and would keep its old frame — and its own close would
                        // never be seen.
                        repeat(2) { withFrameNanos { } }
                        windowChromeMemory.save(FloatingWindow.Calendar.name, CALENDAR_RESET_CHROME)
                        openNewWindow(FloatingWindow.Calendar)
                    }
                },
                onUndo = { vm.dispatch(SchedulerIntent.Undo) },
                onRedo = { vm.dispatch(SchedulerIntent.Redo) },
            )

            // The menu's collapse toggle: a bookmark/tab sticking out of the menu's top-right border,
            // straddling into the content (offset by the menu's own fixed width — 188dp). Points « to push
            // the whole menu off-screen; when collapsed the menu is gone and only this bookmark remains,
            // at the far-left edge, now pointing » to pull it back.
            // At the CEILING and shorter than a window head: all it can stand over is a head at the top of the
            // content area, whose title moves out from under it (LocalHeadObstacle, published here).
            val menuWidth = 188.dp
            IconMenuButton(
                label = if (menuCollapsed) "»" else "«",
                onClick = { menuCollapsed = !menuCollapsed },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = if (menuCollapsed) 0.dp else menuWidth, y = 0.dp)
                    .zIndex(130f)
                    .onGloballyPositioned { menuToggleBounds.value = it.boundsInRoot() },
            )

            // PRD §7 the task picker (`Ctrl+Shift+Alt+T`). It is drawn at the app root like every other
            // overlay, but it is not IN the app: the actual puts it in an OS window of its own at the
            // pointer, over whatever application the chord was struck from (`ui/TaskPickerOverlay.kt`).
            taskPicker?.let { request ->
                TaskPickerOverlay(
                    anchor = request.anchor,
                    onDismiss = { taskPickerRequest.value = null },
                ) {
                    TaskPickerMenu(
                        state = schedulerState,
                        nowMillis = request.openedAtMillis,
                        // The display's own instant, not the press's: what the timeline forbids is a fact
                        // about NOW, and the menu stands open across period boundaries. It is resampled at
                        // exactly those boundaries (see the sampler above), so the rows recolour when the
                        // line enters a new period and never on a timer of their own.
                        restrictionNowMillis = nowMillis,
                        // The picker's one effect, and the very intent PRD §13's "start this task now"
                        // dispatches: the plan places this task at the now-line. The menu then leaves —
                        // it is a question, and it has been answered.
                        onPick = { taskId ->
                            vm.dispatch(SchedulerIntent.ForceTaskStart(taskId))
                            taskPickerRequest.value = null
                        },
                        onDismiss = { taskPickerRequest.value = null },
                    )
                }
            }
        }
        }
    }
}

/**
 * PRD §7: one press of the task-picker chord — where the pointer was, and when it was struck.
 *
 * The instant is part of the request and not read off the clock by the menu, so that the list the user is
 * looking at is the one that was true when they asked for it (and so that a second press re-opens the menu
 * even with the pointer in the same pixel).
 */
private data class TaskPickerRequest(val anchor: ScreenPoint?, val openedAtMillis: Long)

/**
 * `docs/scheduler_requirements.md`: how often the panel [entryId] recurs, in days (0: once) — an occurrence of a
 * repeating panel reads its pattern's.
 */
private fun repeatDaysOf(panels: List<TaskPanel>, entryId: String?): Int {
    val id = entryId?.let { org.example.project.scheduler.domain.PanelRepeats.baseIdOf(it) ?: it } ?: return 0
    return panels.firstOrNull { it.id == id }?.repeat?.everyDays ?: 0
}

/**
 * PRD §8 (uniform blocks): the intent that commits new bounds/title/pinned for any calendar [block].
 * A panel (it has an [PlacedRecord.entryId]) is updated in place; a green task-record block is pinned
 * into a new panel. Returns null when the block has no usable identity (defensive).
 */
/**
 * PRD §8, user rule 2026-10-07: **what releasing [block] over `[newStart, newEnd)` does** — said once, for the release
 * (`onCommitBounds`) and for the preview a held drag draws ([calendarMovePreview]). Null where it does nothing.
 */
internal sealed interface CalendarMove {
    /** A change of the state. */
    data class Dispatch(val intent: SchedulerIntent) : CalendarMove

    /** A screen break put by hand: the engine's (`SchedulerEngine.placeBreakByHand`). */
    data class Break(val kind: String, val fromStartMillis: Long, val fromEndMillis: Long, val newStartMillis: Long) : CalendarMove
}

internal fun calendarMoveOf(
    state: SchedulerState,
    block: PlacedRecord,
    newStart: Long,
    newEnd: Long,
    allowOverlap: Boolean,
    nowMillis: Long,
    tz: TimeZone,
): CalendarMove? =
    when {
        // PRD §8/§18: a ring dragged on the calendar restates its alarm's time (or the instant its timer ends at) —
        // a ring has no panel for the block path to write.
        block.alarm ->
            block.entryId?.let { id ->
                SearchDomain.calendarRingMoveIntent(state, id, block.timer, block.fullStartMillis, newStart, nowMillis, tz)
            }?.let(CalendarMove::Dispatch)
        // PRD §14 / user rule 2026-10-07: a reminder tag dragged is the tag its edit window would save at that
        // instant — pinned there, as a drag pins a block.
        block.reminder ->
            calendarElementDrafts(listOf(block)).singleOrNull()?.let { draft ->
                CalendarMove.Dispatch(
                    SchedulerIntent.AddCalendarElements(
                        listOf(draft.copy(startMillis = newStart, endMillis = newStart, reminderPinned = true)),
                    ),
                )
            }
        // `docs/scheduler_input_requirements.md`: *"All blocks can be dragged, except screen breaks at t > now line"*.
        block.screenBreak ->
            if (block.fullEndMillis > nowMillis) null
            else CalendarMove.Break(block.breakKind, block.fullStartMillis, block.fullEndMillis, newStart)
        else ->
            commitBoundsIntent(
                block, block.taskId, block.title, newStart, newEnd, SchedulerDomain.pinsAfterHandPlacement(block.pins), allowOverlap,
            )?.let(CalendarMove::Dispatch)
    }

/**
 * User rule 2026-10-02 / 2026-10-07: **the calendar as releasing [moves] would leave it** — the stored [state] and
 * break record put through the release's own reducers, and nothing saved. What a held block makes disappear is gone
 * from the answer, what comes with it stands where it does; and since every step of a drag asks again of the stored
 * state, all of it is back the moment the block moves on. The plan is not asked again here (no fill): the release is.
 */
internal fun calendarMovePreview(
    state: SchedulerState,
    frozen: org.example.project.scheduler.domain.FrozenScreenBreaks?,
    moves: List<CalendarMove>,
    nowMillis: Long,
): Pair<SchedulerState, org.example.project.scheduler.domain.FrozenScreenBreaks?> {
    var shown = state.copy(automaticSchedule = false)
    var breaks = frozen
    for (move in moves) {
        when (move) {
            is CalendarMove.Dispatch -> shown = SchedulerReducer.reduce(shown, move.intent)
            is CalendarMove.Break -> {
                val label = SchedulerDomain.breakLabelOfKind(move.kind) ?: continue
                val step =
                    SchedulerDomain.placeBreakByHand(breaks, label, move.fromStartMillis, move.fromEndMillis, move.newStartMillis, nowMillis)
                        ?: continue
                breaks = step.record
                step.added.singleOrNull()?.let {
                    shown = SchedulerReducer.reduce(shown, SchedulerIntent.ClearWorkUnderBankedBreak(it.label, it.startMillis, it.endMillis))
                }
                step.removed.forEach {
                    shown =
                        SchedulerReducer.reduce(
                            shown,
                            SchedulerIntent.FillVacatedBreak(
                                org.example.project.scheduler.domain.DynamicPeriods.breakKind(it.label), it.startMillis, it.endMillis,
                            ),
                        )
                }
            }
        }
    }
    // Nothing is PLANNED here: this state is an input, and the scheduler runs on it ([SchedulerEngine.planHeld]).
    return shown.copy(automaticSchedule = state.automaticSchedule) to breaks
}

/**
 * The kind of the occurrence [block] is of a RULE that lays periods — the sleep schedule's window (`sleep/{wake day}`)
 * or its hour before bed (drawn with no object of its own) — or null for a block with an object behind it.
 */
private fun derivedPeriodKind(block: PlacedRecord): String? =
    when {
        block.sleep && block.entryId?.startsWith("sleep/") == true -> PeriodKinds.SLEEP
        block.entryId == null && !block.sleep && !block.screenBreak && block.restrictiveKind == PeriodKinds.BEFORE_BED ->
            PeriodKinds.BEFORE_BED
        else -> null
    }

private fun commitBoundsIntent(
    block: PlacedRecord,
    taskId: TaskId?,
    title: String,
    startMillis: Long,
    endMillis: Long,
    pins: PanelPins,
    allowOverlap: Boolean = false,
    /** How often the panel recurs, in days (0: once); null keeps whatever it does — a drag never changes it. */
    repeatEveryDays: Int? = null,
): SchedulerIntent? {
    return when {
    // User rule 2026-10-07 (*"Any block can be dragged"*): an occurrence a RULE lays — a Sleep window of the sleep
    // schedule, its hour before bed — has no panel of its own to move: it leaves its rule and becomes a period the
    // user placed. (A screen break is the engine's: this device's break history, not the state.)
    derivedPeriodKind(block) != null ->
        SchedulerIntent.PlaceDerivedPeriod(
            derivedPeriodKind(block)!!, block.fullStartMillis, block.fullEndMillis, startMillis, endMillis,
        )
    // A merged block (several same-task panels shown as one): replace the whole group with one panel.
    block.entryIds.size > 1 ->
        SchedulerIntent.ReplaceTaskPanels(block.entryIds, taskId, title, startMillis, endMillis, pins, allowOverlap)
    block.entryId != null ->
        SchedulerIntent.UpdateTaskPanel(block.entryId, taskId, title, startMillis, endMillis, pins, allowOverlap, repeatEveryDays)
    block.taskId != null ->
        SchedulerIntent.PinRecordAsPanel(
            recordTaskId = block.taskId,
            recordStartEpochMillis = block.fullStartMillis,
            recordEndEpochMillis = block.fullEndMillis,
            taskId = taskId,
            title = title,
            startEpochMillis = startMillis,
            endEpochMillis = endMillis,
            pins = pins,
            allowOverlap = allowOverlap,
        )
    else -> null
    }
}

/**
 * PRD §8: **what the one add/edit element window is open on** — which of the two menu entries opened it,
 * the instant it is anchored at, and (for "edit…") the elements at the mouse it is confined to.
 *
 * One slot for both entries because it is one window: "at most one window per subject" (`popups.md`) is held
 * by the state that opens it, and "add here" and "edit what is here" are one subject — the point of the
 * calendar that was right-clicked.
 */
private data class CalendarElementsDraftSet(
    val mode: CalendarElementsMode,
    val atMillis: Long,
    val candidates: List<CalendarElements.Draft>,
)

/**
 * PRD §8: **the elements at the cursor with what only `App` knows added to them.**
 *
 * [calendarElementDrafts] reads the drawn records and nothing else, which is what keeps it pure and
 * testable; two of the four kinds need a value that is not on the record:
 *  - a **task panel**'s screen switch is the TASK's resilience to `no screen`, not the panel's;
 *  - an **alarm** marker is one occurrence of a rule, so its weekdays, its alert, whether it is armed, and
 *    the ring length that makes its `Ends` come off the [AlarmEntry] the marker's id names.
 */
private fun seedCalendarElementDrafts(
    drafts: List<CalendarElements.Draft>,
    state: SchedulerState,
): List<CalendarElements.Draft> =
    drafts.map { draft ->
        when (draft.kind) {
            CalendarElements.Kind.TaskPanel ->
                draft.copy(
                    noScreenResilience =
                        draft.taskId?.let { state.tasks[it]?.resilienceFor(PeriodKinds.NO_SCREEN) } ?: 0.0,
                )
            CalendarElements.Kind.Alarm ->
                state.alarms.firstOrNull { it.id == draft.existingId }?.let { alarm ->
                    draft.copy(
                        endMillis = draft.startMillis + alarm.soundSeconds * 1000L,
                        alarmDays = alarm.days,
                        alert = alarm.alert,
                        alarmArmed = alarm.enabled,
                    )
                } ?: draft
            CalendarElements.Kind.RestrictivePeriod, CalendarElements.Kind.Reminder -> draft
        }
    }

/**
 * PRD §8: **what the element window's Save dispatches** — and the one place the "one Save costs one Ctrl+Z
 * per undo stack it touched" rule is spelt out.
 *
 * Three writes at most, and each is the funnel that already owns what it writes:
 *  - everything that is a PANEL (task panels, periods, reminder tags) goes as ONE
 *    [SchedulerIntent.AddCalendarElements] — one Calendar history unit, laid against a calendar each element
 *    has already changed for the next;
 *  - a panel's **screen switch is a TASK setting**, so it rides [SchedulerIntent.SetTaskResilience] exactly
 *    as the single-panel editor sent it, once per task and only where the value really changed (no empty
 *    history unit for a switch put back where it was). Two panels of one task in the list are one write —
 *    the value is the task's, and dispatching it twice would be two units saying the same thing;
 *  - the alarms go as ONE [SchedulerIntent.SetAlarms] — a Main history unit, which is where an alarm's undo
 *    has always lived.
 */
private fun saveCalendarElementIntents(
    drafts: List<CalendarElements.Draft>,
    state: SchedulerState,
    tz: TimeZone,
): List<SchedulerIntent> {
    val out = mutableListOf<SchedulerIntent>()
    val panels = drafts.filter { it.kind != CalendarElements.Kind.Alarm }
    if (panels.isNotEmpty()) out += SchedulerIntent.AddCalendarElements(panels)
    panels
        .filter { it.kind == CalendarElements.Kind.TaskPanel }
        .mapNotNull { draft -> draft.taskId?.let { it to draft.noScreenResilience } }
        .distinctBy { it.first }
        .forEach { (taskId, resilience) ->
            val task = state.tasks[taskId] ?: return@forEach
            if (task.resilienceFor(PeriodKinds.NO_SCREEN) != resilience) {
                out += SchedulerIntent.SetTaskResilience(taskId, PeriodKinds.NO_SCREEN, resilience)
            }
        }
    val alarmDrafts = drafts.filter { it.kind == CalendarElements.Kind.Alarm }
    if (alarmDrafts.isNotEmpty()) {
        val next = applyAlarmDrafts(state, alarmDrafts, tz)
        if (next != state.alarms) out += SchedulerIntent.SetAlarms(next)
    }
    return out
}

/**
 * PRD §18: **the account's alarms with the window's alarm drafts written into them.**
 *
 * The start/end the window asked for reach an alarm as a TIME OF DAY and a RING LENGTH
 * ([CalendarElements.alarmTimeOfDayMinutes], [CalendarElements.alarmSoundSeconds]) — an alarm has no date,
 * so the day the user right-clicked is only where the occurrence was shown, never something written back.
 * Which days it rings on is the `Rings on` field beside the start, asked and answered in its own right; a
 * brand-new alarm keeps [AlarmEntry]'s own default (every day), the same one the §18 window's `+` gives a
 * row, with all seven chips lit in front of the user before Save.
 *
 * A blank id on a new row is filled in by [SchedulerIntent.SetAlarms]' own reducer, which is the single
 * place `alarm-{n}` is minted.
 */
private fun applyAlarmDrafts(
    state: SchedulerState,
    drafts: List<CalendarElements.Draft>,
    tz: TimeZone,
): List<AlarmEntry> {
    var out = state.alarms
    drafts.forEach { draft ->
        val dayStart =
            Instant.fromEpochMilliseconds(draft.startMillis)
                .toLocalDateTime(tz).date.atStartOfDayIn(tz).toEpochMilliseconds()
        val index = out.indexOfFirst { it.id == draft.existingId }
        val base = out.getOrNull(index) ?: NewElementDefaults.newAlarm(state.newAlarmDefaults, id = "", timeOfDayMinutes = 0)
        val updated =
            base.copy(
                label = draft.name,
                timeOfDayMinutes = CalendarElements.alarmTimeOfDayMinutes(draft.startMillis, dayStart),
                soundSeconds = CalendarElements.alarmSoundSeconds(draft.startMillis, draft.endMillis),
                alert = draft.alert,
                days = draft.alarmDays,
                enabled = draft.alarmArmed,
            )
        out = if (index >= 0) out.toMutableList().also { it[index] = updated } else out + updated
    }
    return out
}

/**
 * PRD §8: **the bin of one row of the element window's list.** Empty for a row that is only being ADDED —
 * there is nothing stored behind it, which is the same rule [EditorBinButton] has always had; the row is
 * simply dropped from the list.
 */
private fun removeCalendarElementIntents(
    draft: CalendarElements.Draft,
    state: SchedulerState,
): List<SchedulerIntent> {
    val id = draft.existingId ?: return emptyList()
    return when (draft.kind) {
        CalendarElements.Kind.Alarm ->
            listOf(SchedulerIntent.SetAlarms(state.alarms.filterNot { it.id == id }))
        // A task panel, a period and a reminder tag are all panels, and one intent removes any of them.
        else -> listOf(SchedulerIntent.RemoveTaskPanel(id))
    }
}

/**
 * PRD §8: what the period editor ([PeriodEditWindow]) is open on — the period's KIND (a name off
 * `allPeriodKinds`, read off the panel when editing), the block
 * being edited (null while ADDING one), and the bounds the window opens with. [startMillis]/[endMillis] are
 * the pre-fill only: what is laid comes back from the window's Save, which is where "∞"/"now" are resolved.
 */
private data class PeriodDraft(
    val kind: String,
    /**
     * The periods being edited — EMPTY while adding one.
     *
     * One period per chooser row (2026-09-19: a "no screen" period no longer stands for the `no computer
     * unlocked` + `no phone unlocked` pair, so no row edits several objects at once). Kept a list so Save's
     * write path does not care.
     *
     * A member with no [PlacedRecord.entryId] is a DERIVED band (a past Inactivity stretch, the §17
     * wind-down hour). Saving MATERIALIZES it — the app was reporting that stretch, and the user is now
     * stating it — which is the one place a derived band becomes a real period, and why the outline it then
     * wears is the accent blue.
     */
    val blocks: List<PlacedRecord>,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * PRD §8: the intent that deletes a calendar [block] from its source — a panel is removed, a green
 * task-record period is dropped from the task record. Returns null when the block has no removable identity,
 * which a DERIVED band has by definition: nothing is stored, so there is nothing to delete.
 *
 * This is what the **bin button** in each editor sends. The contextual menu has no "Remove" of its own any
 * more: it named no thing in particular, so on a stretch carrying several it deleted whichever happened to
 * be top-most. Deleting is now one funnel with the editing — you get rid of a thing from the window that
 * names it.
 */
private fun removeBlockIntent(block: PlacedRecord): SchedulerIntent? = when {
    block.entryIds.size > 1 -> SchedulerIntent.RemoveTaskPanels(block.entryIds)
    block.entryId != null -> SchedulerIntent.RemoveTaskPanel(block.entryId)
    block.taskId != null ->
        SchedulerIntent.RemoveRecordPeriod(block.taskId, block.fullStartMillis, block.fullEndMillis)
    else -> null
}

/**
 * PRD §8 same-task merge (display): collapse the schedulable [panels] into the blocks the calendar
 * shows — consecutive panels of the same (non-null) task with the same pin state, that touch or
 * overlap, render as one block spanning the run. Each block carries every backing panel id (see
 * [CalendarRecord.entryIds]) so an edit/drag/resize/remove acts on the whole group. The underlying
 * panels stay separate in state — auto panels are distinct scheduling sessions the reschedule must be
 * able to reshape — so this fusing is purely visual. A null-task ("New task") panel never merges.
 */

/**
 * Change-detection key for the diagnostics band log: the quantized (per-minute) INTERIOR edges of the
 * derived account-wide no-screen periods + carved-sleep holes, plus their counts. The single outermost start and end
 * are dropped because they track the sliding derive window and the advancing now-line — logging on those
 * would emit a line per tick. Any real shape change (a band added/removed, a hole opening inside the
 * coverage) moves an interior edge or a count and re-logs.
 */
/**
 * ADR 0009: **the calendar's derivation read at one instant of the now-line** — what `App`'s
 * `deriveCalendarDisplay` returns. [records] is what the calendar draws; the rest is what the effects around
 * the derivation (the diagnostics timeline, the lock-history scan) and the perf gauges read off the reading
 * taken AT the line.
 */
internal data class CalendarDisplay(
    val records: List<CalendarRecord>,
    val displayFloorMillis: Long,
    val bandSignature: String,
    val noScreenPeriods: List<TaskTimeRange>,
    val sidePanelCount: Int,
    val workPlanPanelCount: Int,
)

/**
 * ADR 0009 / CLAUDE.md hot path: **the derivation is a pure function of the values listed at its call site,
 * so a state change that touches none of them cannot have changed the picture** — and re-deriving for one is
 * ~25 ms of the frame it lands in, on top of the recomposition every equal-but-new record list forces the
 * calendar into. `App`'s body recomposes for every state change there is (a log row appended, a selection
 * moved, a window navigated, a keystroke in the tree), so without this the display was re-derived for all of
 * them: a state change that drew nothing cost a 79 ms frame on the release account (2026-09-21).
 *
 * TWO slots, because the calendar reads the derivation TWICE — at the line and one millisecond later, which
 * is how the motion of each edge is read ([withLineMotion]). One slot would make the two readings evict each
 * other and the memo would never hit.
 *
 * Keyed by VALUE (`==`), not by identity: a reducer that rewrites the state leaves the collections it did not
 * touch as the same instances, and `AbstractMap`/`AbstractList` equality short-circuits on identity, so an
 * unchanged key costs a handful of reference comparisons.
 */
/**
 * The top-level hold of [CalendarDisplay], with the one shortcut a RENAME needs.
 *
 * `docs/invariants/display-hot-path.md`: the derivation is a pure function of its inputs, so an unchanged key
 * returns the same instance. A rename moves two of those inputs — the task's title, and the titles of the
 * panels the rename rewrites ([SchedulerReducer.applySetCellTitle]) — and NOTHING else: not one edge moves,
 * not one band changes shape. So when the only difference is titles, the held reading is **re-labelled**
 * rather than derived again: what a block NAMES is its task's title, which is the one thing a rename says.
 *
 * That is what lets the user's two rules hold at once — *"each time a title is renamed with a new keystroke,
 * the titles in the calendar must update at the same time"* and *"it shouldn't affect the writing in the task
 * cell"*. The titles update in the very frame the letter lands in, and they cost a walk over the records
 * instead of the ~15 ms the placement costs.
 *
 * The shortcut is refused the moment a title that is NOT a task's moved (a renamed screen break, a
 * hand-drawn period): those are named by the panel itself, and only the derivation knows where they come
 * from. Two slots, for the two readings of the line ([withLineMotion]).
 */
internal class CalendarDisplayCache {
    private class Slot(
        var key: List<Any?>,
        var tasks: Map<TaskId, Task>,
        var panels: List<TaskPanel>,
        var value: CalendarDisplay,
    )

    private val slots = arrayOfNulls<Slot>(2)
    private var next = 0

    fun get(
        key: List<Any?>,
        tasks: Map<TaskId, Task>,
        panels: List<TaskPanel>,
        derive: () -> CalendarDisplay,
    ): CalendarDisplay {
        for (slot in slots) {
            if (slot == null || slot.key != key) continue
            if (slot.tasks == tasks && slot.panels == panels) return slot.value
            if (!onlyTaskTitlesMoved(slot.tasks, tasks) || !onlyTaskPanelTitlesMoved(slot.panels, panels)) continue
            val relabelled =
                slot.value.copy(
                    records = slot.value.records.map { record ->
                        val title = record.taskId?.let { tasks[it]?.title }
                        if (title != null && title != record.title) record.copy(title = title) else record
                    },
                )
            slot.tasks = tasks
            slot.panels = panels
            slot.value = relabelled
            return relabelled
        }
        val derived = derive()
        slots[next] = Slot(key, tasks, panels, derived)
        next = (next + 1) % slots.size
        return derived
    }

    /** True when [now] is [before] with nothing but task TITLES changed. */
    private fun onlyTaskTitlesMoved(before: Map<TaskId, Task>, now: Map<TaskId, Task>): Boolean {
        if (before.size != now.size) return false
        for ((id, previous) in before) {
            val current = now[id] ?: return false
            if (current !== previous && current.copy(title = previous.title) != previous) return false
        }
        return true
    }

    /**
     * The same question about the panels — and a title that moved on a panel holding NO task fails it: a
     * screen break and a hand-drawn period name themselves, so re-labelling from the tasks would leave them
     * reading what they were called before.
     */
    private fun onlyTaskPanelTitlesMoved(before: List<TaskPanel>, now: List<TaskPanel>): Boolean {
        if (before.size != now.size) return false
        for (i in before.indices) {
            val previous = before[i]
            val current = now[i]
            if (current === previous) continue
            if (current.copy(title = previous.title) != previous) return false
            if (current.title != previous.title && current.taskId == null) return false
        }
        return true
    }
}

private class CalendarDisplayMemo<T> {
    private val keys = arrayOfNulls<List<Any?>>(2)
    private val values = arrayOfNulls<Any?>(2)
    private var next = 0

    @Suppress("UNCHECKED_CAST")
    fun get(key: List<Any?>, derive: () -> T): T {
        for (slot in keys.indices) {
            if (keys[slot] == key) return values[slot] as T
        }
        val derived = derive()
        keys[next] = key
        values[next] = derived
        next = (next + 1) % keys.size
        return derived
    }
}

private fun diagnosticsBandSignature(noScreenPeriods: List<TaskTimeRange>): String {
    val edges = noScreenPeriods.flatMap { listOf(it.startEpochMillis, it.endEpochMillis) }.sorted()
    val interior = if (edges.size > 2) edges.subList(1, edges.size - 1) else emptyList()
    return "${noScreenPeriods.size}:" +
        interior.joinToString(",") { (it / 60_000).toString() }
}

/** PRD §18: `HH:MM` for an alarm's time of day — the calendar marker's label when the alarm has none. */
/**
 * PRD §8 calendar layers: the grain of the FLOOR of the window this device's OS lock/standby history is asked
 * over. The query spawns a process, so the floor — which slides with the now-line — must not re-key it; a day
 * is coarse enough that the sliding costs one read a day. When it is read at all is the engine's screen edges.
 */
private const val LOCK_HISTORY_FLOOR_MILLIS: Long = 24L * 60 * 60 * 1000

private fun formatAlarmClockTime(minutes: Int): String {
    val m = ((minutes % AlarmEntry.MINUTES_PER_DAY) + AlarmEntry.MINUTES_PER_DAY) % AlarmEntry.MINUTES_PER_DAY
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

private fun mergePanelsForDisplay(
    panels: List<TaskPanel>,
    reminderPanels: List<TaskPanel>,
    sidePanels: List<TaskPanel>,
    sleepPanels: List<TaskPanel>,
    showScreenBreaks: Boolean,
    showReminders: Boolean,
    // PRD §15: the break definitions the [sidePanels] were projected from — what each band's SHAPE is, so the
    // calendar can draw the part of a 5-/15-min break that accepts off-screen tasks hollow rather than
    // covering it with a solid band.
    screenBreaks: List<ScreenBreak> = emptyList(),
    // `docs/scheduler_requirements.md` § *Progressive Calculation*: the DEFINITIVE-SCHEDULE FRONT
    // ([SchedulerDomain.definitiveScheduleFrontMillis]). An auto panel reaching past it is the far-week display
    // fill's, not a schedule the scheduler has settled, and is marked [CalendarRecord.provisional] so the
    // calendar draws its edges blurred. The default says "everything shown is definitive", which is what a
    // caller that materialized the whole span it draws (the tests) is stating.
    definitiveFrontMillis: Long = Long.MAX_VALUE,
): List<CalendarRecord> {
    // PRD §14/§15: reminder tags (zero-duration) and screen breaks (very short real durations, e.g. a 20-second
    // look-away) are NOT height-proportional blocks — drawn at scale they'd be invisible. They render on
    // their own fixed-height marker paths (CalendarRecord.reminder / .screenBreak) and never merge with panels.
    // PRD §14/§15: reminder tags ([reminderPanels]) and screen breaks ([sidePanels]) are both projected across
    // the focused week (which may run past the schedule's fixed obstacle window in [panels]) — not taken from
    // [panels]; the regular `blocks` still come from [panels]. Within the schedule window the projections are
    // identical (same `now`, same chores/screen breaks), so the blocks stay split around the screen breaks exactly
    // as scheduled and the checked state of each reminder is carried over by matching its deterministic id.
    val reminders = if (showReminders) reminderPanels else emptyList()
    val sides = sidePanels
    // A DERIVED wind-down hour is split off here because it has no object of its own behind it (below).
    // Asked by the panel's ID, not by its kind: `before bed` is a kind like any other since PRD §8's one
    // "add" entry, so a period the user drew of that kind is a real, removable block like every other
    // hand-drawn period and must stay in `blocks` — only the fill's own `before-bed/{wake day}` panels are
    // the decorative ones.
    val blocks =
        panels.filter {
            !SchedulerDomain.isReminder(it) && !it.screenBreak && !it.sleep &&
                !it.id.startsWith(SchedulerDomain.BEFORE_BED_PANEL_ID_PREFIX)
        }
    // PRD §17: the hour before bed, drawn as the empty outlined box every period is. DERIVED, like the "Sleep" window it
    // precedes and unlike the user's own periods, so it carries no `entryId`: there is no object of its own
    // behind it to edit or remove — the sleep schedule is the object, and the Sleep band an hour later is
    // where its menu leads. Its KIND is still `before bed` in the scheduler; the ORANGE outline is only what
    // the calendar says "a repeating rule put this here" with.
    val beforeBedRecords =
        panels.filter { it.id.startsWith(SchedulerDomain.BEFORE_BED_PANEL_ID_PREFIX) }.map { panel ->
            CalendarRecord(
                title = panel.title,
                range = TaskTimeRange(panel.startEpochMillis, panel.endEpochMillis),
                inactivity = true,
                restrictiveKind = panel.restrictiveKind,
                // PRD §8: the sleep schedule is a REPEATING rule, so the hour it lays is orange-outlined —
                // the same answer its "Sleep" window an hour later gets, through the same one funnel.
                outline = SchedulerDomain.panelOutline(panel),
            )
        }
    val reminderRecords =
        reminders.map { tag ->
            CalendarRecord(
                title = tag.title,
                range = TaskTimeRange(tag.startEpochMillis, tag.endEpochMillis),
                entryId = tag.id,
                entryIds = listOf(tag.id),
                reminder = true,
                checked = tag.checked,
                checkedAtMillis = tag.checkedAtMillis,
                // PRD §8: BLUE — a reminder is added from the calendar's own right-click menu. A tag is not
                // a panel, so this is its own answer rather than [SchedulerDomain.panelOutline]'s.
                outline = SchedulerDomain.reminderTagOutline(),
            )
        }
    // PRD §15 toggle: when screen breaks are hidden, draw none, and let same-task panels separated only by a
    // (now-hidden) screen break fuse into one block (cosmetic — the panels and the schedule are untouched).
    val sideRecords =
        if (!showScreenBreaks) {
            emptyList()
        } else {
            sides.map { side ->
                CalendarRecord(
                    title = side.title,
                    range = TaskTimeRange(side.startEpochMillis, side.endEpochMillis),
                    entryId = side.id,
                    entryIds = listOf(side.id),
                    screenBreak = true,
                    // Which of the break kinds it is — what decides whose panel it cuts a hole in.
                    breakKind = side.restrictiveKind,
                    // PRD §8/§15: a dynamic restrictive period — the grey outline, through the same one
                    // funnel every other block's outline comes from.
                    outline = SchedulerDomain.panelOutline(side),
                )
            }
        }
    // PRD §15: when screen breaks are hidden, fuse same-task panels across the gaps the (now-hidden) screen-break
    // pauses left — structurally, so the fused block doesn't flicker as `now` advances (a moving screen-break
    // projection would keep drifting out of alignment with the already-scheduled gaps).
    val sleepRanges = sleepPanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
    val blockRecords =
        SchedulerDomain.groupSameTaskPanelsForDisplay(blocks, bridgeGaps = !showScreenBreaks, sleepRegions = sleepRanges).map { group ->
            val head = group.first()
            CalendarRecord(
                title = head.title,
                range = TaskTimeRange(head.startEpochMillis, group.maxOf { it.endEpochMillis }),
                manual = true,
                entryId = head.id,
                entryIds = group.map { it.id },
                taskId = head.taskId,
                pinned = head.pinned,
                pins = head.pins,
                // PRD §8: WHO put this block here, which is the whole of what its outline says (blue for the
                // user, orange for a repeating rule). Read off the head panel, which is what every other
                // per-block attribute here is read off — a run only groups panels of one pin state anyway.
                outline = SchedulerDomain.panelOutline(head),
                layoutWeight = head.layoutWeight,
                // PRD §8/§9/§12: a period the user drew stays a real, removable block (drawn as an empty
                // blue-outlined box) rather than a task panel.
                //
                // **THE DRAWING IS DERIVED FROM THE KIND, HERE, ONCE.** `noScreen` and `inactivity` on a
                // CalendarRecord are the two paints the calendar has for a period — no fill + both hatches,
                // or grey — and reading them off the panel's two legacy flags meant a period of any OTHER
                // kind (`before bed`, one the account defined) carried neither and was drawn as a task
                // panel. A kind is grey unless it is, BY ITS OWN NAME, a sentence about screens
                // ([PeriodKinds.isLayerKind]) — a `before bed` period carries a no-screen one, and that
                // companion is drawn by its own pattern over the box ([PeriodKindConfig.boxDrawings]), not by
                // repainting the wind-down box as a no-screen one.
                noScreen = PeriodKinds.isLayerKind(head.restrictiveKind),
                inactivity = head.isRestrictivePeriod && !PeriodKinds.isLayerKind(head.restrictiveKind),
                restrictiveKind = head.restrictiveKind,
                // PRD §8/§12: a hand-added period saved with an open ("∞") start reads as one in the hover
                // bubble, exactly like a derived band that nothing precedes.
                openStart = SchedulerDomain.isOpenPast(head.startEpochMillis),
                // § *Progressive Calculation*: asked of EVERY panel of the merged block, not of its head — a
                // block merges consecutive same-task panels, so one may straddle the front, and a run whose
                // length the front did not bound is unsettled as a whole.
                provisional = group.any { SchedulerDomain.isProvisionalPanel(it, definitiveFrontMillis) },
            )
        }
    // The sleep windows render as their own labeled band behind the task blocks (drawn first). The caller has already
    // cut out of them the past the line crossed in mode 1 (`SchedulerDomain.retractOverAtScreenPast`): a line at a
    // screen retracts the window itself (`docs/invariants/scheduler.md` § *A mode-1 line retracts*), and the plan is
    // materialized across the retracted span, so the hole is the tasks' to fill.
    val sleepRecords =
        // Its "No screen" hover line is the sleep kind's companion, named by the calendar over the band's own
        // span (`companionBubbleSections`) — not read off any evidence here.
        sleepPanels.map { sleepPanel ->
            CalendarRecord(
                title = sleepPanel.title,
                range = TaskTimeRange(sleepPanel.startEpochMillis, sleepPanel.endEpochMillis),
                entryId = sleepPanel.id,
                entryIds = listOf(sleepPanel.id),
                sleep = true,
                // PRD §8: a §17 sleep window is a restrictive period a repeating rule lays — orange.
                outline = SchedulerDomain.panelOutline(sleepPanel),
            )
        }
    return sleepRecords + beforeBedRecords + blockRecords + reminderRecords + sideRecords
}
