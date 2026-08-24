package org.example.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.AppSchedulerHost
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.persistence.ActiveSessionStore
import org.example.project.scheduler.persistence.DeviceSleepGapStore
import org.example.project.scheduler.persistence.SleepScanCheckpointStore
import org.example.project.scheduler.persistence.SyncMetaStore
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
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.ui.PriorityWeightWindow
import org.example.project.scheduler.ui.RelativePriorityWindow
import org.example.project.scheduler.ui.SignInDialog
import org.example.project.scheduler.ui.SyncStatusChip
import org.example.project.scheduler.ui.TaskSchedulerScreen
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import org.example.project.time.SimAppClock
import org.example.project.time.SystemAppClock
import org.example.project.ui.AlarmWindow
import org.example.project.ui.CalendarFloatingWindow
import org.example.project.ui.CalendarRecord
import org.example.project.ui.ChoresManagerWindow
import org.example.project.ui.DeviceActivityIndex
import org.example.project.ui.HistoryManagerWindow
import org.example.project.ui.IconMenuButton
import org.example.project.ui.raiseOnPress
import org.example.project.ui.LateralMenu
import org.example.project.ui.CalendarPeriodKind
import org.example.project.ui.ManualEntryEditWindow
import org.example.project.ui.PeriodEditWindow
import org.example.project.ui.PlacedRecord
import org.example.project.ui.ReminderEditWindow
import org.example.project.ui.SimPauseScope
import org.example.project.ui.ShortcutsWindow
import org.example.project.ui.SleepWindow
import org.example.project.ui.DefaultSubtreeWindow
import org.example.project.ui.TaskTreesWindow
import org.example.project.ui.TimeSimPanel

enum class OmniPage(val label: String) {
    TaskScheduler("Task Scheduler"),
}

/** The z-stackable floating windows; the currently focused one is drawn on top (see [App]'s windowStack). */
private enum class FloatingWindow {
    Calendar, Reminders, History, Sleep, Alarms, TaskTrees, DefaultSubtree, Shortcuts, TimeSim
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

// Display quantum for the observed now-line UNDER TIME SIMULATION. The engine advances `now` every
// [ADVANCE_DISPLAY_MILLIS_ACCEL] (50 ms) whenever the debug clock is accelerated — and DebugFlags.TIME_SIMULATION
// forces that fine cadence on even at 1×. The whole calendar/band/record derivation below is a pure function of
// `nowMillis` and re-runs on every emission, at O(account history) each time. On an empty account that is cheap,
// but on a real (large) account it pegs the AWT/Compose UI thread ~20×/s and Compose never presents a first
// frame — the window is created but stays hidden (the "time simulation shows no window" bug). Snapping the
// OBSERVED now-line to this step and reading it through a derivedStateOf gates recomposition to ~this cadence
// (≈4 fps of now-line motion) instead of 20×/s, keeping a big account responsive while fast-forwarding. Only the
// on-screen now-line granularity is affected; the engine's cues/records/schedule still use its exact clock.
// Production (real clock) already emits `now` only every 30 s (ADVANCE_TICK_MILLIS_PROD), so it is left exact.
private const val DISPLAY_NOW_QUANTUM_MILLIS: Long = 250

@Composable
@Preview
fun App(store: SchedulerStore? = createDefaultSchedulerStore(), host: AppSchedulerHost? = null) {
    MaterialTheme {
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
                    SchedulerSyncEngine(RemoteSnapshotClient(), it, activeSessionStore = store as? ActiveSessionStore)
                }
            }

        // The scheduler view-model is hoisted here so the floating calendar can read the Task Tree's
        // records (PRD §8) while the Task Scheduler screen drives the same state.
        val vm: TaskSchedulerViewModel =
            host?.vm ?: viewModel { TaskSchedulerViewModel(store = store, syncEngine = syncEngine) }
        val schedulerState by vm.state.collectAsState()

        // Floating-window geometry/visibility, persisted LOCALLY ONLY (never synced, never a History Unit).
        // Absent on stores without the capability (e.g. web's localStorage) — placement then stays in-memory.
        // Loaded once; the open flags below seed their initial visibility from it, offsets persist on drag-end.
        val placementStore = remember(store) { store as? WindowPlacementStore }
        val initialPlacements = remember(placementStore) { placementStore?.loadPlacements().orEmpty() }
        fun savedOffset(id: FloatingWindow, default: Offset): Offset =
            initialPlacements[id.name]?.let { Offset(it.x, it.y) } ?: default
        fun savedVisible(id: FloatingWindow): Boolean = initialPlacements[id.name]?.visible == true
        fun persistPlacement(id: FloatingWindow, offset: Offset, visible: Boolean) =
            placementStore?.savePlacement(id.name, WindowPlacement(x = offset.x, y = offset.y, visible = visible))

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
                    tz = tz,
                    sleepGapStore = store as? DeviceSleepGapStore,
                    sleepScanCheckpoint = store as? SleepScanCheckpointStore,
                    activeSessionStore = store as? ActiveSessionStore,
                    pauseCue = vm.pauseCue,
                    // PRD §15: the OS-scheduled local cue seam for the engine App() builds itself (iOS delivers
                    // via UNUserNotificationCenter; desktop/web are inert). Android does not reach here — it
                    // injects an AlarmManager seam via SchedulerHolder.
                    scheduleLocalPauseCue = ::scheduleLocalPauseCuePlatform,
                    localPauseCueDelivery = localPauseCueDeliveryPlatform,
                    // PRD §18 Alarms: ring on this device too. The desktop has no OS alarm clock, so the
                    // engine rings from its now-line sweep and this seam plays the sound (Android does not
                    // reach here — SchedulerHolder injects AlarmRingService instead).
                    ringAlarm = { armed ->
                        ringAlarmPlatform(armed.label, armed.soundSeconds, armed.vibrate)
                    },
                )
        }
        LaunchedEffect(engine) { if (host == null) engine.start() }
        // PRD §15: hook this device's platform activity signal (desktop OS session lock/unlock) so the engine
        // re-samples presence the moment it flips, instead of at the next minute beat. No-op on Android (the
        // service wires it directly) and iOS/web (no such signal).
        LaunchedEffect(engine) { installPlatformActivityListener { engine.onPlatformActivityChanged() } }
        // PRD §15: the system-wide chords (Ctrl+Shift+Alt+A "I'm away", Ctrl+Shift+Alt+E "Look away now"),
        // driving exactly the same engine seams the left-menu buttons do. Claimed from the OS rather than
        // handled in Compose because they are pressed precisely when OmniApp is NOT the focused window — the
        // user is walking away from, or resting their eyes in the middle of, whatever they were working in —
        // and a focus-scoped handler would only ever fire when the button is already one click away. The
        // claim swallows the chord so no other application acts on the same press. Desktop-only; inert on
        // Android/iOS.
        LaunchedEffect(engine) {
            installGlobalHotkeys { shortcut ->
                when (shortcut) {
                    GlobalShortcut.ToggleAway -> engine.setUserAway(!engine.userAway.value)
                    GlobalShortcut.LookAwayNow -> engine.restartLookAway()
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
        // Observed now-line. Under time simulation it is snapped to [DISPLAY_NOW_QUANTUM_MILLIS] and read through
        // a derivedStateOf so this composable recomposes only when the quantized value changes (a few times a
        // second) rather than on every 50 ms engine emission — otherwise the O(history) derivation below pegs the
        // UI thread on a real account and the window never presents. Production advances only every 30 s, so it is
        // observed exactly (the branch is a no-op there).
        val nowMillisState = engine.nowMillis.collectAsState()
        val nowMillis by remember {
            derivedStateOf {
                val raw = nowMillisState.value
                if (DebugFlags.TIME_SIMULATION) raw - (raw % DISPLAY_NOW_QUANTUM_MILLIS) else raw
            }
        }
        // PRD §15 device-sleep gaps: past pauses drawn as greyed "Inactivity" bands (display-only; see the engine).
        val inactivityGaps by engine.inactivityGaps.collectAsState()
        // PRD §15/§17: start of this device's open active session (null while inactive) — carves the "Sleep" band
        // to the now-line as the user keeps working through a scheduled sleep window (display-only, non-syncing).
        val activeSince by engine.activeSince.collectAsState()
        // PRD §15: end of this device's last finalized session — the locally-observed start of a pause the
        // derived gaps don't cover yet. Unioned in below so the "Inactivity" band grows live behind an
        // advancing now-line (display-only, non-syncing; see SchedulerDomain.displayInactivityGaps).
        val inactiveSince by engine.inactiveSince.collectAsState()
        // PRD §15: every stored active session (this device's own + the peers' rows pulled by the Sync button) —
        // the full unclipped session history. Used both to segment past task panels by which devices were open
        // (hover bubble + dashed separators) and to re-derive the display Inactivity bands over any focused past
        // week (PRD §12/§15 — the engine's own [inactivityGaps] only reaches back 168h).
        val activeSessions by engine.activeSessions.collectAsState()
        // PRD §15: whether the user declared they are away from THIS device (left-menu "I'm away" button).
        val userAway by engine.userAway.collectAsState()
        // PRD §7/§15: what claim the OS granted the system-wide chords — shown in the keyboard-shortcuts window,
        // since a chord another application already owns is otherwise indistinguishable from a broken app.
        val globalHotkeyClaim by GlobalHotkeys.claim.collectAsState()

        // PRD §7 calendar state, hoisted so the lateral menu (month grid) and the popup week view
        // stay in sync. "today" follows the (possibly simulated) clock so day rollovers are testable.
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).date
        var calendarOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Calendar)) }
        // PRD §5: the sub-list whose priority-weight window is open (opened by clicking a percentage in the
        // tree), or null when closed. Drawn on the top floating-window layer below; [weightWindowBounds] is
        // its window-space rect, used to ignore presses inside it when dismissing on outside clicks.
        var weightWindowListId by remember { mutableStateOf<CellListId?>(null) }
        var weightWindowBounds by remember { mutableStateOf<Rect?>(null) }
        // PRD §5: the cell whose relative-priority window is open (the percentage's right-click menu), or
        // null when closed. Same top layer and same outside-press dismissal as the weight window; the two
        // are mutually exclusive (opening either closes the other, in TaskSchedulerScreen).
        var relativeWindowCellId by remember { mutableStateOf<CellId?>(null) }
        var relativeWindowBounds by remember { mutableStateOf<Rect?>(null) }
        // Layout of the content area, so a press position (content-local) can be mapped to window space
        // and compared against the windows' bounds.
        var contentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        // PRD §5: the window closes when any cell enters Edit Mode (its sub-list typing context is gone).
        LaunchedEffect(schedulerState.editSession) {
            if (schedulerState.editSession != null) {
                weightWindowListId = null
                relativeWindowCellId = null
            }
        }
        // PRD §7/§14 Chores Manager: whether the floating chores window is open (local UI state, like the
        // calendar window; the chores data itself lives in the persisted scheduler state).
        var choresManagerOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Reminders)) }
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
        // PRD §4 Default sub-tree: whether the floating template window is open (local UI state; the template
        // and the "is it applied" switch are authoritative synced state).
        var defaultSubtreeWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.DefaultSubtree)) }
        // PRD §7 Keyboard shortcuts: whether the floating reference list of every chord is open (local UI state).
        var shortcutsWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Shortcuts)) }

        // The floating windows are siblings in one Box, so their paint order is their declaration order.
        // To put the *currently focused* window on top of the layers, we keep an explicit stacking order
        // (last == top) and drive each window's zIndex from it. A window is raised when it is opened and on
        // every press inside it (see raiseOnPress). Unmanaged: the modal edit window (its own scrim already
        // sits above everything).
        var windowStack by remember {
            mutableStateOf(
                listOf(
                    FloatingWindow.Calendar,
                    FloatingWindow.Reminders,
                    FloatingWindow.History,
                    FloatingWindow.Sleep,
                    FloatingWindow.Alarms,
                    FloatingWindow.TaskTrees,
                    FloatingWindow.DefaultSubtree,
                    FloatingWindow.Shortcuts,
                    FloatingWindow.TimeSim,
                ),
            )
        }
        fun bringWindowToFront(id: FloatingWindow) {
            if (windowStack.lastOrNull() != id) windowStack = windowStack.filterNot { it == id } + id
        }
        fun windowZ(id: FloatingWindow): Float = windowStack.indexOf(id).toFloat()
        fun isWindowOpen(id: FloatingWindow): Boolean = when (id) {
            FloatingWindow.Calendar -> calendarOpen
            FloatingWindow.Reminders -> choresManagerOpen
            FloatingWindow.History -> historyManagerOpen
            FloatingWindow.Sleep -> sleepWindowOpen
            FloatingWindow.Alarms -> alarmWindowOpen
            FloatingWindow.TaskTrees -> taskTreesWindowOpen
            FloatingWindow.DefaultSubtree -> defaultSubtreeWindowOpen
            FloatingWindow.Shortcuts -> shortcutsWindowOpen
            FloatingWindow.TimeSim -> DebugFlags.TIME_SIMULATION
        }
        // The focused window is the topmost open one in the stack.
        fun focusedWindow(): FloatingWindow? = windowStack.lastOrNull { isWindowOpen(it) }
        // PRD §7: the scheduler-state focus target for a floating window (null for the debug TimeSim panel,
        // which is not a navigable app window).
        fun appWindowOf(id: FloatingWindow): AppWindow? = when (id) {
            FloatingWindow.Calendar -> AppWindow.Calendar
            FloatingWindow.Reminders -> AppWindow.Reminders
            FloatingWindow.History -> AppWindow.History
            FloatingWindow.Sleep -> null
            FloatingWindow.Alarms -> null
            FloatingWindow.TaskTrees -> null
            FloatingWindow.DefaultSubtree -> null
            FloatingWindow.Shortcuts -> null
            FloatingWindow.TimeSim -> null
        }
        // PRD §7 window navigation: raise [id] to the top layer AND move scheduler focus onto it, which
        // clears the tree selection, forcibly exits tree Edit Mode, and records a WindowNav history unit.
        fun focusWindow(id: FloatingWindow) {
            bringWindowToFront(id)
            appWindowOf(id)?.let { vm.dispatch(SchedulerIntent.FocusWindow(it)) }
        }
        // Lateral-menu click on a window button: open it (and focus) when closed; close it when it is the
        // focused (front) window; otherwise just bring it to focus without closing.
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

        // Local-only persisted drag positions for the managed windows. The defaults reproduce the previous
        // hard-coded cascade staggers, used until the user drags a window (which persists via onOffsetChange).
        var calendarOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Calendar, Offset.Zero)) }
        var remindersOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Reminders, Offset(-200f, -150f))) }
        var historyOffset by remember { mutableStateOf(savedOffset(FloatingWindow.History, Offset(200f, 150f))) }
        var sleepOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Sleep, Offset(120f, -120f))) }
        var alarmOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Alarms, Offset(-120f, 120f))) }
        var taskTreesOffset by remember { mutableStateOf(savedOffset(FloatingWindow.TaskTrees, Offset(-260f, -60f))) }
        var defaultSubtreeOffset by
            remember { mutableStateOf(savedOffset(FloatingWindow.DefaultSubtree, Offset(260f, -60f))) }
        var shortcutsOffset by remember { mutableStateOf(savedOffset(FloatingWindow.Shortcuts, Offset(60f, 60f))) }
        // Persist each window's visibility whenever it opens/closes (its offset persists separately on drag-end).
        LaunchedEffect(calendarOpen) { persistPlacement(FloatingWindow.Calendar, calendarOffset, calendarOpen) }
        LaunchedEffect(choresManagerOpen) { persistPlacement(FloatingWindow.Reminders, remindersOffset, choresManagerOpen) }
        LaunchedEffect(historyManagerOpen) { persistPlacement(FloatingWindow.History, historyOffset, historyManagerOpen) }
        LaunchedEffect(sleepWindowOpen) { persistPlacement(FloatingWindow.Sleep, sleepOffset, sleepWindowOpen) }
        LaunchedEffect(alarmWindowOpen) { persistPlacement(FloatingWindow.Alarms, alarmOffset, alarmWindowOpen) }
        LaunchedEffect(taskTreesWindowOpen) { persistPlacement(FloatingWindow.TaskTrees, taskTreesOffset, taskTreesWindowOpen) }
        LaunchedEffect(defaultSubtreeWindowOpen) {
            persistPlacement(FloatingWindow.DefaultSubtree, defaultSubtreeOffset, defaultSubtreeWindowOpen)
        }
        LaunchedEffect(shortcutsWindowOpen) {
            persistPlacement(FloatingWindow.Shortcuts, shortcutsOffset, shortcutsWindowOpen)
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

        // PRD §15: screen breaks are projected from now to the END OF THE DISPLAYED SPAN. The scheduling
        // horizon is the floor, so the near term is unchanged and scrolling further out extends the
        // screen-break markers to span it. `nowMillis` is the same `now` the last schedule refresh used (the
        // tick loop sets both together), so within the schedule window this reproduces the screen-break
        // panels already in [schedulerState.panels] and only adds the tail.
        val visibleSpanStartMillis = visibleFirstDay.atStartOfDayIn(tz).toEpochMilliseconds()
        val visibleSpanEndMillis =
            visibleFirstDay.plus(visibleDayCount, DateTimeUnit.DAY).atStartOfDayIn(tz).toEpochMilliseconds()
        // Every forward DISPLAY projection stops here: the end of the displayed span, floored at the horizon a
        // closed calendar still needs. Never `now + 168h` unconditionally — a grid sitting on today projects
        // ~24h of sleep bands, not a week of them (PRD §9 "the horizon follows what is displayed").
        val screenBreakHorizonMillis =
            maxOf(nowMillis + SchedulerDomain.MIN_SCHEDULE_HORIZON_MILLIS, visibleSpanEndMillis)

        // PRD §9: tell the ENGINE which days are on screen, so its §9 refills materialize the work plan out
        // to exactly that span (clamped to [24h, 168h]) instead of unconditionally computing 168h of schedule
        // the user is not looking at. Closing the calendar drops it back to the 24h floor the headless
        // notification/cue paths need. Growing it (scrolling further out) triggers one refill in the engine.
        LaunchedEffect(engine, calendarOpen, visibleSpanEndMillis) {
            engine.setCalendarHorizon(if (calendarOpen) visibleSpanEndMillis else null)
        }

        // PRD §9/§17 "schedule the whole span displayed": the engine materializes the work plan out to the
        // displayed days, but never past its 168h CEILING. When the scroll reaches past that, compute the plan
        // from the now-line out to there for DISPLAY — off the UI thread (Dispatchers.Default) so a distant
        // day "simply takes time to be displayed" instead of freezing, keyed only on the displayed span so it
        // doesn't rerun every now-tick. The result is never stored in the state, so scrolling back to a near
        // day just uses the near panels again and this far fill is dropped ("erased") — no retained
        // multi-week memory. Nearer days need none of this: the engine already fills exactly to them
        // (`engine.setCalendarHorizon` above), so `schedulerState.panels` covers the whole displayed span.
        val nearHorizonEndMillis = nowMillis + SchedulerDomain.SCHEDULE_HORIZON_MILLIS
        val visibleSpanBeyondNearHorizon = visibleSpanEndMillis > nearHorizonEndMillis
        var farWeekPlan by remember { mutableStateOf<List<TaskPanel>?>(null) }
        var farWeekCalculating by remember { mutableStateOf(false) }
        LaunchedEffect(visibleSpanStartMillis, visibleSpanBeyondNearHorizon) {
            if (!visibleSpanBeyondNearHorizon) {
                farWeekPlan = null
                farWeekCalculating = false
                return@LaunchedEffect
            }
            farWeekPlan = null
            farWeekCalculating = true
            val fill =
                withContext(Dispatchers.Default) {
                    SchedulerDomain.fillSchedule(
                        schedulerState, nowMillis, timeZone = tz, horizonMillis = visibleSpanEndMillis,
                    )
                }
            farWeekPlan = fill
            farWeekCalculating = false
        }
        // The source for the calendar's real task BLOCKS: the near panels as usual, or the async far-week fill
        // (falling back to the near panels while it is still computing, so past/pinned blocks stay visible).
        val workPlanPanels =
            if (visibleSpanBeyondNearHorizon) farWeekPlan ?: schedulerState.panels else schedulerState.panels
        // The user's sleep windows — shown as "Sleep" blocks and avoided by the regular task fill (so no task
        // is scheduled while asleep). Screen breaks, by contrast, DO project across sleep so their eye-rest / pose
        // cues still render over the "Sleep" band for a user working through the night (PRD §15). The sleep
        // SCHEDULE is projected only from `now` FORWARD (PRD §17): the past is not assumed to have been slept —
        // an emptied DB's past is Inactivity + No-screen. Past sleep is instead a recorded fact: the persisted
        // materialized "Sleep" panels the engine banks when a scheduled window elapses unattended, plus the
        // live band `[sleepingSince, now]` that grows while the Sleep toggle is on (finalized when it goes off).
        val liveSleepBand =
            schedulerState.sleepingSinceMillis
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
            SchedulerDomain.sleepPanels(schedulerState.sleep, nowMillis, screenBreakHorizonMillis, tz) +
                schedulerState.panels.filter { it.sleep && it.endEpochMillis <= nowMillis } +
                liveSleepBand
        val displaySleepRegions =
            displaySleepPanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        // The same live-pause placement overlay the reducer refill applies (SchedulerReducer.liveRestGap):
        // the display projection folds this device's ongoing/held pause into the screen-break grid, so the
        // rendered markers match the engine's placement and move with the pause (an ongoing pause is
        // presumed to serve each task — the whole grid re-places at walk-away and stays fluid under an
        // accelerated leap) instead of letting the now-line cross a stale slot or freezing everything
        // downstream of a not-yet-served pose. Placement-only — stored screen-break state is untouched.
        //
        // Bound to the VISIBLE days, not `[now, visibleSpanEnd]` (CLAUDE.md: hot-path display derivations
        // scale with the screen, not with total history). When the displayed span contains the present, the
        // forward projection from `now` — which carries the overdue-slide + live-rest semantics near the
        // now-line — is already bounded to `≤ 1` week (ends at `visibleSpanEnd`). When the displayed span is
        // entirely in the FUTURE, projecting from `now` would generate every occurrence between `now` and
        // that week; at a shrunk 5-min-break interval ([DebugFlags.breakIntervalMillisOverride]) that is tens of thousands of markers pushed
        // through the O(n²) placement scan, which froze the app when a distant day was opened. Reconstruct
        // just that week's window from the fixed grid instead (no walk from `now`, no live-rest overlay
        // needed — the present isn't in view).
        // PRD §15: the breaks that ALREADY HAPPENED stay on the calendar — a look-away is drawn where it was
        // taken, not erased the moment the now-line passes it. The forward projection below starts at `now`, so
        // the elapsed part of the focused week is reconstructed from the same fixed grid, bounded to the
        // VISIBLE window (CLAUDE.md: display derivations scale with the screen, not with history) and stopping
        // one millisecond short of `now` so the two sources can never draw the same occurrence twice.
        //
        // It is a reconstruction rather than a record because the anchors make it exact where it matters: every
        // break now recurs a fixed (duration + interval) cycle after the previous one ends, and each rest that
        // served it — a conducted look-away, a pose break, a long pause — moves the anchor to its own end. So
        // walking the grid back from the live anchor reproduces the occurrences actually taken since the last
        // rest, and no state has to be persisted to show them. Only the TAKEN ones are drawn here: the break
        // that is still owed slides to the now-line and is the forward projection's to draw.
        val displayPastSidePanels =
            SchedulerDomain.takenScreenBreakPanels(
                schedulerState.screenBreaks,
                visibleSpanStartMillis,
                minOf(nowMillis - 1, visibleSpanEndMillis),
            )
        val displaySidePanels =
            if (visibleSpanStartMillis <= nowMillis) {
                displayPastSidePanels +
                    SchedulerDomain.screenBreakPanels(
                        SchedulerDomain.screenBreaksForPlacement(
                            schedulerState.screenBreaks,
                            SchedulerDomain.liveRestGap(inactiveSince, activeSince, nowMillis),
                        ),
                        nowMillis,
                        visibleSpanEndMillis,
                        // A decoupled 5-min pose (account1 fast-break) appears an interval after each qualifying
                        // pause; the future ones are the scheduled sleep windows (PRD §15).
                        qualifyingPauseWindows = SchedulerDomain.sleepRegions(
                            schedulerState.sleep, nowMillis, visibleSpanEndMillis, tz,
                        ),
                    )
            } else {
                SchedulerDomain.screenBreakPanelsInWindow(
                    schedulerState.screenBreaks,
                    visibleSpanStartMillis,
                    visibleSpanEndMillis,
                    qualifyingPauseWindows = SchedulerDomain.sleepRegions(
                        schedulerState.sleep, visibleSpanStartMillis, visibleSpanEndMillis, tz,
                    ),
                )
            }

        // PRD §15: a screen break the now-line has REACHED is a period accepting no task, and it slides right
        // with the now-line for as long as it stays owed. The plan under it was materialized by a fill that ran
        // at a rule change (CLAUDE.md: time passing never re-plans), so the auto panels have to be cut out of
        // the break's span here, on the display side — the reference's sliding-period regime, pinned to the
        // plan's own origin (`side-dev/scheduler_logic.py` tests 10–11).
        val displayWorkPlanPanels =
            SchedulerDomain.clipPlanForPinnedScreenBreak(
                workPlanPanels, displaySidePanels, nowMillis,
                // The break shapes + the task attributes, so only what a break REFUSES is cut: a pose's open
                // period keeps the off-screen work it accepts, which is the part the band draws hollow.
                schedulerState.screenBreaks, schedulerState.tasks,
            )

        // PRD §14: reminder flags are calculated for the WHOLE displayed span — from now to the end of the
        // days the calendar is showing — so scrolling to a day shows its reminders. Like the screen-break
        // projection they are regenerated for display (anchored at today's midnight, out to the displayed
        // span's end), with each tag's checked state carried over from the stored reminder panels by
        // matching its deterministic id.
        val todayStartMillis = today.atStartOfDayIn(tz).toEpochMilliseconds()
        val reminderHorizonDays =
            ((visibleSpanEndMillis - todayStartMillis) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
        val displayReminderPanels =
            SchedulerDomain.regenerateChorePanels(
                schedulerState.panels, schedulerState.chores, todayStartMillis, reminderHorizonDays, nowMillis,
            ).filter { SchedulerDomain.isReminder(it) }

        // PRD §18: every ring of every alarm that falls in the WEEK ON SCREEN — past ones included, since an
        // alarm is a fixed wall-clock boundary and a ring that already went off stays where it happened. The
        // days each alarm is triggered on are its own synced [AlarmEntry.days], so every device draws the same
        // markers. Bounded by the displayed window per the CLAUDE.md hot-path rule (cost follows the screen:
        // days-on-screen × alarms), not by the account's history — and independent of `nowMillis`, so the
        // per-tick recompute is a fixed, tiny amount of work.
        val displayAlarmOccurrences =
            AlarmDomain.occurrencesInWindow(
                schedulerState.alarms, visibleSpanStartMillis, visibleSpanEndMillis, tz,
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
            SchedulerDomain.derivePauses(
                activeSessions.map { TaskTimeRange(it.startMillis, it.endMillis) },
                displayFloorMillis,
                nowMillis,
            )
        val displayInactivityGaps =
            SchedulerDomain.displayInactivityGaps(displayDerivedGaps, inactiveSince, activeSince, nowMillis)
        val pastActivityWindow = TaskTimeRange(displayFloorMillis, nowMillis)
        val accountActiveRegions =
            if (activeSessions.isEmpty()) {
                emptyList()
            } else {
                SchedulerDomain.subtractRegions(listOf(pastActivityWindow), displayInactivityGaps)
            }
        val activeRegions =
            accountActiveRegions +
                (activeSince?.takeIf { it < nowMillis }?.let { listOf(TaskTimeRange(it, nowMillis)) } ?: emptyList())
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
        val carvedSleepHoles =
            SchedulerDomain.subtractRegions(
                displaySleepRegions.filter { it.startEpochMillis < nowMillis },
                SchedulerDomain.carveSleepPanels(displaySleepPanels, activeRegions)
                    .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) },
            )
        val bandSignature = diagnosticsBandSignature(noScreenPeriods, carvedSleepHoles)
        LaunchedEffect(bandSignature) {
            Diagnostics.log("calendar no-screen periods: ${Diagnostics.formatRanges(noScreenPeriods)}")
            if (carvedSleepHoles.isNotEmpty()) {
                Diagnostics.log(
                    "calendar Sleep bands carved by activity at: ${Diagnostics.formatRanges(carvedSleepHoles)}",
                )
            }
        }
        // PRD §12 "∞ start": the earliest layer region is open-ended into the past when nothing precedes it
        // — no activity session, task record, or user-authored/materialized panel begins before it (an
        // emptied DB has none). Its start then renders as "∞" instead of a wall-clock time (which, clamped
        // to the 168h derive floor, would read the same hour:minute as `now`).
        val earliestEvidenceMillis =
            listOfNotNull(
                activeSessions.minOfOrNull { it.startMillis },
                schedulerState.panels.filterNot(SchedulerDomain::isRegeneratedPanel)
                    .minOfOrNull { it.startEpochMillis },
                schedulerState.tasks.values.flatMap { it.record }.minOfOrNull { it.startEpochMillis },
            ).minOrNull()
        // ADR 0009 hot path: the session history is indexed ONCE per change instead of being re-labelled for
        // every panel on every observed now-line (the segmentation below runs over every record there is).
        val deviceActivityIndex = remember(activeSessions) { DeviceActivityIndex(activeSessions) }
        // Done periods (PRD §8 task record, green) plus every calendar panel (PRD §8/§9 — auto and
        // user-authored, uniform blocks) drawn the same way; reminders (PRD §14) and screen breaks (PRD §15)
        // span the focused week.
        val baseCalendarRecords = (
            schedulerState.tasks.values.flatMap { task ->
                task.record.map { CalendarRecord(title = task.title, range = it, taskId = task.id) }
            } + mergePanelsForDisplay(
                displayWorkPlanPanels, displayReminderPanels, displaySidePanels, displaySleepPanels,
                schedulerState.showScreenBreaks, schedulerState.showReminders,
                schedulerState.screenBreaks, activeRegions, displayInactivityGaps,
            )
            ).map { record ->
            // Only real task blocks (records + auto/manual panels) carry the device-set segmentation; the
            // reminder/screen-break/sleep bands keep their own rendering. The helper itself clips to the
            // elapsed part, so a future panel simply gets no segments.
            if (record.reminder || record.screenBreak || record.alarm || record.sleep || record.noScreen ||
                record.inactivity
            ) {
                record
            } else {
                record.copy(deviceSegments = deviceActivityIndex.segmentsFor(record.range, nowMillis))
            }
        }
        // PRD §8: the elapsed timeline is fully accounted for — every past stretch is either a TASK PANEL or a
        // GREY period. So whatever the panels leave uncovered in the past is drawn as a derived "Inactivity"
        // band (the user's rule: "the areas in the past that don't have a task panel should have a grey panel
        // either labelled inactivity or sleep"). "Sleep" is the other label, and the §17 sleep bands already
        // draw and label themselves, so they are subtracted rather than relabelled — as are the hand-added
        // inactivity panels, which are real panels and already grey. A screen break and a no-screen period
        // are deliberately NOT subtracted: neither is a task panel, so idle time inside one is still idle
        // (and the break's own band draws over whatever is underneath it). See [derivedInactivityBands],
        // which also drops the sub-minute seams between adjacent panels. Display-only: no `entryId`, so it
        // is neither removable nor draggable.
        val pastCoveredRegions =
            baseCalendarRecords
                .filterNot { it.reminder || it.alarm || it.screenBreak || it.noScreen }
                .map { it.range }
        val pastInactivityRecords =
            SchedulerDomain.derivedInactivityBands(pastCoveredRegions, displayFloorMillis, nowMillis)
                .let { gaps ->
                    // PRD §12 "∞ start": the earliest band is open-ended into the past when nothing precedes it.
                    val open = SchedulerDomain.derivedBandsOpenStart(gaps, earliestEvidenceMillis)
                    gaps.map { gap ->
                        CalendarRecord(
                            title = "Inactivity",
                            range = gap,
                            inactivity = true,
                            openStart = open != null && gap.startEpochMillis == open,
                        )
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
        //     unlocked-by-nobody counts: the §17 sleep windows and the §15 screen breaks (a break is by
        //     definition time away from every screen). Screen breaks are asserted in the past too: a 20-second
        //     look-away never stops the heartbeat, so evidence alone would never show it.
        // The user's own no-screen periods (PRD §8) assert both layers across their whole span — a hand-added
        // no-screen period IS "a period carrying both layers", and the derivation never overwrites it.
        val layerAsserted =
            SchedulerDomain.mergeOccupied(
                schedulerState.panels.filter { it.noScreen }
                    .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) } +
                    displaySidePanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) } +
                    displaySleepRegions.filter { it.endEpochMillis > nowMillis }
                        .map { TaskTimeRange(maxOf(it.startEpochMillis, nowMillis), it.endEpochMillis) },
            )
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
        // Both ends of the asked window are QUANTIZED to the refresh period, or the effect would relaunch on
        // every display tick: [displayFloorMillis] is `now − 168h` whenever the calendar is not scrolled past
        // that, so it slides with the now-line and would re-key the scan ~every 30 s (observed: a PowerShell
        // process per tick). Rounding the floor DOWN and the ceiling UP also means the window only ever grows
        // between scans, so nothing in view is left unasked.
        val lockScanSince = (displayFloorMillis / LOCK_HISTORY_REFRESH_MILLIS) * LOCK_HISTORY_REFRESH_MILLIS
        val lockScanUntil =
            ((nowMillis / LOCK_HISTORY_REFRESH_MILLIS) + 1) * LOCK_HISTORY_REFRESH_MILLIS
        LaunchedEffect(lockScanSince, lockScanUntil) {
            val since = lockScanSince
            val until = lockScanUntil
            val scanned =
                withContext(Dispatchers.Default) {
                    deviceLockedIntervals(since, until)?.map { TaskTimeRange(it.startMillis, it.endMillis) }
                }
            lockedIntervals = scanned
            lockHistoryScanned = true
            // scripts/collect-diagnostics.bat: the layers are read from the OS, so an anomaly in them is an
            // anomaly in THIS answer — record it rather than asking the user to describe the hatching. One
            // line per scan (at most one per LOCK_HISTORY_REFRESH_MILLIS), not per frame.
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
        val layerRecords =
            SchedulerDomain.ActivityLayer.entries.flatMap { layer ->
                val regions =
                    SchedulerDomain.layerRegions(
                        lockedIntervals =
                            when {
                                layer != ownLayer -> null // no channel carries a peer's lock history
                                lockHistoryScanned -> lockedIntervals
                                else -> emptyList() // not asked yet ≠ cannot be asked
                            },
                        assertedRegions = layerAsserted,
                        sinceMillis = displayFloorMillis,
                        untilMillis = nowMillis,
                    )
                // PRD §12 "∞ start": the earliest layer region is open-ended into the past when nothing at all
                // precedes it (an emptied DB) — its drawn start is only the display floor, so it reads "∞".
                val layerOpenStart = SchedulerDomain.derivedBandsOpenStart(regions, earliestEvidenceMillis)
                regions.map { region ->
                    CalendarRecord(
                        title = layer.calendarLabel,
                        range = region,
                        layer = layer,
                        openStart = layerOpenStart != null && region.startEpochMillis == layerOpenStart,
                    )
                }
            }
        val calendarRecords = baseCalendarRecords + pastInactivityRecords + layerRecords +
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
                )
            }
        // PRD §8 edit window: the calendar block currently being edited (null = closed).
        var editingBlock by remember { mutableStateOf<PlacedRecord?>(null) }
        // PRD §8 Manual add: a not-yet-committed default panel shown in the edit window with a Save
        // button (null = not adding). Distinct from [editingBlock] so Save knows to add vs. update.
        var addingBlock by remember { mutableStateOf<PlacedRecord?>(null) }
        // PRD §14 "add a checked reminder": the right-click epoch-millis at which to open the reminder-check
        // window (null = closed).
        var addingReminderAtMillis by remember { mutableStateOf<Long?>(null) }
        // PRD §8: the no-screen / inactivity period the period editor is open on (null = closed). Both
        // "add a … period" menu entries and a period's own "Edit" open it — nothing is laid on the
        // calendar until Save, which is what lets a period be given an open ("∞") bound the grid could
        // never be dragged to.
        var editingPeriod by remember { mutableStateOf<PeriodDraft?>(null) }

        // PRD §8 focus: the floating calendar window is the focused surface while it is open — so the
        // tree stops hijacking letter typing into Edit Mode and Ctrl+Z/Y route to the calendar history.
        LaunchedEffect(calendarOpen) {
            vm.dispatch(SchedulerIntent.SetCalendarFocus(calendarOpen))
        }

        // PRD §7: switching focus to another window leaves Edit Mode in any window — close the calendar's
        // edit / add-reminder surfaces so they don't linger over the newly focused window.
        LaunchedEffect(schedulerState.focusedWindow) {
            editingBlock = null
            addingReminderAtMillis = null
        }

        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // The lateral menu is omitted entirely while collapsed, so the content takes the full width
                // ("completely disappear to the left"). The collapse toggle lives outside it (see below).
                if (!menuCollapsed) LateralMenu(
                    page = page,
                    onPageSelected = { page = it },
                    calendarOpen = calendarOpen,
                    onToggleCalendar = { onMenuWindowClicked(FloatingWindow.Calendar) { calendarOpen = it } },
                    monthAnchor = monthAnchor,
                    onMonthAnchorChange = { monthAnchor = it },
                    selectedDate = selectedDate,
                    today = today,
                    onSelectDate = { selectedDate = it; calendarJumpNonce++ },
                    automaticSchedule = schedulerState.automaticSchedule,
                    onToggleAutomaticSchedule = { vm.dispatch(SchedulerIntent.SetAutomaticSchedule(it)) },
                    choresManagerOpen = choresManagerOpen,
                    onToggleChoresManager = { onMenuWindowClicked(FloatingWindow.Reminders) { choresManagerOpen = it } },
                    historyManagerOpen = historyManagerOpen,
                    onToggleHistoryManager = { onMenuWindowClicked(FloatingWindow.History) { historyManagerOpen = it } },
                    lookAwayVoiceEnabled = schedulerState.lookAwayVoiceEnabled,
                    onToggleLookAwayVoice = { vm.dispatch(SchedulerIntent.SetLookAwayVoice(it)) },
                    onLookAwayNow = { engine.restartLookAway() },
                    sleepWindowOpen = sleepWindowOpen,
                    onToggleSleep = { onMenuWindowClicked(FloatingWindow.Sleep) { sleepWindowOpen = it } },
                    alarmWindowOpen = alarmWindowOpen,
                    onToggleAlarms = { onMenuWindowClicked(FloatingWindow.Alarms) { alarmWindowOpen = it } },
                    taskTreesWindowOpen = taskTreesWindowOpen,
                    onToggleTaskTrees = {
                        onMenuWindowClicked(FloatingWindow.TaskTrees) { taskTreesWindowOpen = it }
                    },
                    defaultSubtreeWindowOpen = defaultSubtreeWindowOpen,
                    onToggleDefaultSubtree = {
                        onMenuWindowClicked(FloatingWindow.DefaultSubtree) { defaultSubtreeWindowOpen = it }
                    },
                    shortcutsWindowOpen = shortcutsWindowOpen,
                    onToggleShortcuts = {
                        onMenuWindowClicked(FloatingWindow.Shortcuts) { shortcutsWindowOpen = it }
                    },
                    defaultSubtreeEnabled = schedulerState.defaultSubtreeEnabled,
                    onToggleDefaultSubtreeEnabled = {
                        vm.dispatch(SchedulerIntent.SetDefaultSubtreeEnabled(it))
                    },
                    sleeping = schedulerState.isSleeping(nowMillis),
                    onToggleSleepWork = {
                        if (schedulerState.isSleeping(clock.nowMillis())) {
                            vm.setSleepMode(null)
                        } else {
                            vm.setSleepMode(
                                SchedulerDomain.nextWakeInstantMillis(schedulerState.sleep, clock.nowMillis(), tz),
                            )
                        }
                    },
                    away = userAway,
                    onToggleAway = { engine.setUserAway(!userAway) },
                    anyWindowOpen = calendarOpen || choresManagerOpen || historyManagerOpen || sleepWindowOpen ||
                        alarmWindowOpen || taskTreesWindowOpen || defaultSubtreeWindowOpen || shortcutsWindowOpen,
                    onCloseAllWindows = {
                        calendarOpen = false
                        choresManagerOpen = false
                        historyManagerOpen = false
                        sleepWindowOpen = false
                        alarmWindowOpen = false
                        taskTreesWindowOpen = false
                        defaultSubtreeWindowOpen = false
                        shortcutsWindowOpen = false
                    },
                )

                // The content area is clipped so the floating calendar window can overlap the tree
                // but never spill onto the lateral menu (PRD §7).
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .onGloballyPositioned { contentCoords = it }
                        // PRD §5: the priority-weight window closes when a press lands anywhere outside it,
                        // and that press still does its normal job (selecting a cell, focusing the calendar,
                        // …). We observe presses in the Initial pass without consuming them — an ancestor of
                        // every window, so it catches clicks on the tree and on other floating windows alike.
                        // A press inside the window's own bounds is ignored.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type != PointerEventType.Press) continue
                                    if (weightWindowListId == null && relativeWindowCellId == null) continue
                                    val pos = event.changes.firstOrNull()?.position ?: continue
                                    val win = contentCoords?.localToWindow(pos) ?: continue
                                    if (weightWindowListId != null && weightWindowBounds?.contains(win) != true) {
                                        weightWindowListId = null
                                    }
                                    if (relativeWindowCellId != null && relativeWindowBounds?.contains(win) != true) {
                                        relativeWindowCellId = null
                                    }
                                }
                            }
                        },
                ) {
                    when (page) {
                        OmniPage.TaskScheduler ->
                            TaskSchedulerScreen(
                                modifier = Modifier.fillMaxSize(),
                                store = store,
                                vm = vm,
                                onSetWeightWindow = { weightWindowListId = it },
                                onSetRelativeWindow = { relativeWindowCellId = it },
                            )
                    }

                    // PRD §5: the priority-weight window, on the top floating-window layer (zIndex above the
                    // managed windows' 0..n stack, below the modal edit window's 100). Opened from a tree
                    // percentage; closed by the outside-press interceptor above.
                    weightWindowListId?.let { listId ->
                        if (schedulerState.lists[listId] == null) {
                            weightWindowListId = null
                        } else {
                            PriorityWeightWindow(
                                state = schedulerState,
                                listId = listId,
                                priorities = SchedulerDomain.absoluteTaskPriorities(schedulerState),
                                onIntent = { vm.dispatch(it) },
                                onBoundsChange = { weightWindowBounds = it },
                                modifier = Modifier.align(Alignment.Center).zIndex(50f),
                            )
                        }
                    }

                    // PRD §5: the relative-priority window, on the same top layer as the weight window.
                    // Opened from the percentage's right-click menu; closed by the interceptor above (or by
                    // the cell going away under it, e.g. an undo that deleted it).
                    relativeWindowCellId?.let { cellId ->
                        if (schedulerState.cells[cellId]?.taskId == null) {
                            relativeWindowCellId = null
                        } else {
                            RelativePriorityWindow(
                                state = schedulerState,
                                cellId = cellId,
                                onIntent = { vm.dispatch(it) },
                                onBoundsChange = { relativeWindowBounds = it },
                                modifier = Modifier.align(Alignment.Center).zIndex(50f),
                            )
                        }
                    }

                    if (calendarOpen) {
                        CalendarFloatingWindow(
                            selectedDate = selectedDate,
                            today = today,
                            nowMillis = nowMillis,
                            onDismiss = { calendarOpen = false },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.Calendar)),
                            records = calendarRecords,
                            // PRD §9/§17: a future week beyond the near horizon is still computing its plan
                            // off the UI thread — surface a "Calculating…" hint instead of a frozen window.
                            calculating = farWeekCalculating,
                            // PRD §8 focus: pressing in the calendar makes it the focused surface again
                            // (e.g. after a click into the tree had handed focus back) and raises it to the
                            // top of the window layers. (onFocus fires inside the window, after its offset.)
                            onFocus = { focusWindow(FloatingWindow.Calendar) },
                            // PRD §8 Manual add: open the edit window pre-filled with the default task
                            // (highest absolute priority, min-time span) and a Save button.
                            onAddTaskAt = { startMillis ->
                                val taskId = SchedulerDomain.manualAddTaskId(schedulerState)
                                val task = taskId?.let { schedulerState.tasks[it] }
                                val span = (task?.minimumMinutes?.toLong() ?: 45L) * 60_000L
                                addingBlock = PlacedRecord(
                                    title = task?.title.orEmpty(),
                                    startHour = 0f,
                                    endHour = 0f,
                                    scheduled = false,
                                    manual = true,
                                    entryId = null,
                                    taskId = taskId,
                                    pinned = true, // PRD §8: the "pin" button is on by default for a new panel
                                    // PRD §8: seeds the edit window's switches — Existence on by default, the rest off.
                                    pins = PanelPins(existence = true),
                                    fullStartMillis = startMillis,
                                    fullEndMillis = startMillis + span,
                                )
                            },
                            // PRD §14 "add reminder": open the reminder editor at the click.
                            onAddReminderAt = { atMillis -> addingReminderAtMillis = atMillis },
                            // PRD §8: open the period editor pre-filled with one hour from the right-click
                            // time. The user picks the real bounds there (including "∞" and "now") and Save
                            // lays the period; it stays adjustable by drag/resize like any block afterwards.
                            onAddNoScreenAt = { atMillis ->
                                editingPeriod =
                                    PeriodDraft(CalendarPeriodKind.NoScreen, null, atMillis, atMillis + 3_600_000L)
                            },
                            onAddInactivityAt = { atMillis ->
                                editingPeriod =
                                    PeriodDraft(CalendarPeriodKind.Inactivity, null, atMillis, atMillis + 3_600_000L)
                            },
                            // PRD §8 (uniform blocks): committing a drag/resize updates the panel
                            // (auto blocks become user-authored), or pins a record into a panel.
                            onCommitBounds = { block, newStart, newEnd, allowOverlap ->
                                commitBoundsIntent(
                                    block, block.taskId, block.title, newStart, newEnd, block.pins, allowOverlap,
                                )?.let(vm::dispatch)
                            },
                            // PRD §8 "Edit": a sleep band's editable object is the §17 sleep schedule, so
                            // its Edit opens the sleep window; a no-screen / inactivity period has no task
                            // behind it and opens the shared period editor; every other panel opens the
                            // calendar edit window.
                            onEditEntry = { block ->
                                when {
                                    block.sleep -> sleepWindowOpen = true
                                    block.noScreen || block.inactivity ->
                                        editingPeriod =
                                            PeriodDraft(
                                                kind =
                                                    if (block.noScreen) CalendarPeriodKind.NoScreen
                                                    else CalendarPeriodKind.Inactivity,
                                                block = block,
                                                startMillis = block.fullStartMillis,
                                                endMillis = block.fullEndMillis,
                                            )
                                    else -> editingBlock = block
                                }
                            },
                            // PRD §8 task contextual menu "Remove": delete the block by its source.
                            onRemoveEntry = { block -> removeBlockIntent(block)?.let(vm::dispatch) },
                            // PRD §14 Reminders: clicking a reminder tag toggles its checked (done) state.
                            onToggleReminder = { block ->
                                block.entryId?.let { vm.dispatch(SchedulerIntent.SetReminderChecked(it, !block.checked, nowMillis)) }
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
                            showReminders = schedulerState.showReminders,
                            onToggleReminders = { vm.dispatch(SchedulerIntent.SetShowReminders(it)) },
                            onUndo = { vm.dispatch(SchedulerIntent.Undo) },
                            onRedo = { vm.dispatch(SchedulerIntent.Redo) },
                            initialOffset = calendarOffset,
                            onOffsetChange = {
                                calendarOffset = it
                                persistPlacement(FloatingWindow.Calendar, it, true)
                            },
                            // PRD §8/§9: the endless scroll says which days are on screen; the horizon and
                            // every display projection above are computed from exactly that span.
                            onVisibleDaysChanged = { firstDay, dayCount ->
                                visibleFirstDay = firstDay
                                visibleDayCount = dayCount
                            },
                            jumpNonce = calendarJumpNonce,
                        )

                        // PRD §8 edit window, drawn over the calendar window and the tree — used for
                        // both editing an existing block and the Manual-add default panel. It is a modal
                        // (full-screen scrim), so it is pinned above every floating window's z-layer.
                        (editingBlock ?: addingBlock)?.let { block ->
                            val isNew = editingBlock == null
                            Box(Modifier.fillMaxSize().zIndex(100f)) {
                            ManualEntryEditWindow(
                                initialTitle = block.title,
                                initialTaskId = block.taskId,
                                startMillis = block.fullStartMillis,
                                endMillis = block.fullEndMillis,
                                tz = tz,
                                taskMenuEntries = { draft, exclude ->
                                    SchedulerDomain.calendarTaskMenuEntries(schedulerState, draft, exclude)
                                },
                                titleSuggestions = { SchedulerDomain.calendarTitleSuggestions(schedulerState, it) },
                                taskIdForTitle = { SchedulerDomain.calendarTaskIdForTitle(schedulerState, it) },
                                titleForTaskId = { schedulerState.tasks[it]?.title },
                                initialPins = block.pins,
                                screenFlagsForTaskId = { id ->
                                    schedulerState.tasks[id]?.let { it.onScreen to it.doableDuringBreak }
                                },
                                onDismiss = { editingBlock = null; addingBlock = null },
                                onSave = { taskId, title, startMillis, endMillis, pins, onScreen, doableDuringBreak ->
                                    val intent =
                                        if (isNew) {
                                            SchedulerIntent.AddTaskPanel(taskId, title, startMillis, endMillis, pins)
                                        } else {
                                            commitBoundsIntent(block, taskId, title, startMillis, endMillis, pins)
                                        }
                                    intent?.let(vm::dispatch)
                                    // PRD §8 screen switches: task-level flags saved alongside the panel;
                                    // only dispatched when they actually changed (no no-op history unit).
                                    val task = taskId?.let { schedulerState.tasks[it] }
                                    if (task != null && (task.onScreen != onScreen || task.doableDuringBreak != doableDuringBreak)) {
                                        vm.dispatch(SchedulerIntent.SetTaskScreenFlags(taskId, onScreen, doableDuringBreak))
                                    }
                                    editingBlock = null
                                    addingBlock = null
                                },
                            )
                            }
                        }

                        // PRD §8: the period editor for both hand-added periods — add (no [block]) and
                        // edit alike. Same modal z-layer as the calendar edit window.
                        editingPeriod?.let { draft ->
                            Box(Modifier.fillMaxSize().zIndex(100f)) {
                                PeriodEditWindow(
                                    kind = draft.kind,
                                    isNew = draft.block == null,
                                    startMillis = draft.startMillis,
                                    endMillis = draft.endMillis,
                                    nowMillis = nowMillis,
                                    tz = tz,
                                    onDismiss = { editingPeriod = null },
                                    onSave = { start, end ->
                                        val block = draft.block
                                        val intent =
                                            when {
                                                // An existing period: the ordinary panel-bounds commit, which
                                                // re-applies the period's own override rule over its new span.
                                                block != null ->
                                                    commitBoundsIntent(block, null, block.title, start, end, block.pins)
                                                draft.kind == CalendarPeriodKind.NoScreen ->
                                                    SchedulerIntent.AddNoScreenPeriod(start, end)
                                                else -> SchedulerIntent.AddInactivityPeriod(start, end)
                                            }
                                        intent?.let(vm::dispatch)
                                        editingPeriod = null
                                    },
                                )
                            }
                        }

                        // PRD §14 "add reminder": the floating reminder editor, above every floating window
                        // (same z-layer as the manual edit window).
                        addingReminderAtMillis?.let { atMillis ->
                            Box(Modifier.fillMaxSize().zIndex(100f)) {
                                ReminderEditWindow(
                                    initialMillis = atMillis,
                                    tz = tz,
                                    reminderMenuEntries = { SchedulerDomain.reminderMenuEntries(schedulerState, it) },
                                    titleSuggestions = { SchedulerDomain.reminderTitleSuggestions(schedulerState, it) },
                                    reminderIdForTitle = { SchedulerDomain.reminderIdForTitle(schedulerState, it) },
                                    titleForReminderId = { SchedulerDomain.reminderTitleForId(schedulerState, it) },
                                    onDismiss = { addingReminderAtMillis = null },
                                    onSave = { reminderId, title, at, checked, pinned ->
                                        vm.dispatch(SchedulerIntent.AddReminder(reminderId, title, at, checked, pinned))
                                        addingReminderAtMillis = null
                                    },
                                )
                            }
                        }
                    }

                    // PRD §14 Chores Manager: floating window over the tree (not the lateral menu).
                    if (choresManagerOpen) {
                        // PRD §14: anchor the chore scheduler at local midnight of today, in the user's tz.
                        val todayStartMillis = today.atStartOfDayIn(tz).toEpochMilliseconds()
                        ChoresManagerWindow(
                            chores = schedulerState.chores,
                            // PRD §14: pass `now` too so a reminder with no time-of-day lands at the current time.
                            onChange = { vm.dispatch(SchedulerIntent.SetChores(it, todayStartMillis, nowMillis)) },
                            onDismiss = { choresManagerOpen = false },
                            // PRD §14: pre-fill a newly added reminder's Time field with the clock time at the click.
                            newRowTimeOfDayMinutes = {
                                val t = Instant.fromEpochMilliseconds(clock.nowMillis()).toLocalDateTime(tz)
                                t.hour * 60 + t.minute
                            },
                            // PRD §14: title/id suggestion menus under the focused reminder name field —
                            // existing reminders matching the draft, and distinct reminder titles.
                            reminderMenuEntries = { SchedulerDomain.reminderMenuEntries(schedulerState, it) },
                            titleSuggestions = { SchedulerDomain.reminderTitleSuggestions(schedulerState, it) },
                            // A new row's id must avoid every known reminder id (including calendar-only ones).
                            knownReminderIds = { SchedulerDomain.allReminderEntries(schedulerState).mapTo(mutableSetOf()) { it.id } },
                            // PRD §14: reminder ids kept alive by a checked or pinned tag — the focused row
                            // shows its own id in the menu only when it is one of these (independently referenced).
                            referencedReminderIds = { SchedulerDomain.referencedReminderIds(schedulerState) },
                            // PRD §14 "constrained in": resolve a reminder name ↔ id for the constraint picker.
                            reminderIdForTitle = { SchedulerDomain.reminderIdForTitle(schedulerState, it) },
                            titleForReminderId = { SchedulerDomain.reminderTitleForId(schedulerState, it) },
                            // Cascade: open up-left of center so it isn't fully hidden behind a wider window.
                            initialOffset = remindersOffset,
                            onOffsetChange = {
                                remindersOffset = it
                                persistPlacement(FloatingWindow.Reminders, it, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.Reminders) },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.Reminders)),
                        )
                    }

                    // PRD §5/§6 History Manager: floating window listing every category's history units.
                    if (historyManagerOpen) {
                        HistoryManagerWindow(
                            histories = schedulerState.histories,
                            notificationLog = schedulerState.notificationLog,
                            supabaseUsageLog = schedulerState.supabaseUsageLog,
                            onDismiss = { historyManagerOpen = false },
                            // Cascade: open down-right of center so the Reminders / calendar windows stay reachable.
                            initialOffset = historyOffset,
                            onOffsetChange = {
                                historyOffset = it
                                persistPlacement(FloatingWindow.History, it, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.History) },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.History)),
                        )
                    }

                    // Sleep schedule: floating window to configure the nightly sleep window the scheduler avoids.
                    if (sleepWindowOpen) {
                        SleepWindow(
                            sleep = schedulerState.sleep ?: SchedulerDomain.DEFAULT_SLEEP,
                            onSave = { vm.dispatch(SchedulerIntent.SetSleepSchedule(it, today.toEpochDays().toLong())) },
                            onDismiss = { sleepWindowOpen = false },
                            // Cascade: open up-right of center so the other windows stay reachable.
                            initialOffset = sleepOffset,
                            onOffsetChange = {
                                sleepOffset = it
                                persistPlacement(FloatingWindow.Sleep, it, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.Sleep) },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.Sleep)),
                        )
                    }

                    // PRD §18 Alarms: floating window listing the account's alarms (time, sound length,
                    // vibration). The list is authoritative synced state, so saving it here arms every phone.
                    if (alarmWindowOpen) {
                        AlarmWindow(
                            alarms = schedulerState.alarms,
                            onChange = { vm.dispatch(SchedulerIntent.SetAlarms(it)) },
                            onDismiss = { alarmWindowOpen = false },
                            // Pre-fill a newly added alarm's Time field with the current clock time.
                            newRowTimeOfDayMinutes = {
                                val t = Instant.fromEpochMilliseconds(clock.nowMillis()).toLocalDateTime(tz)
                                t.hour * 60 + t.minute
                            },
                            // Cascade: open down-left of center so the other windows stay reachable.
                            initialOffset = alarmOffset,
                            onOffsetChange = {
                                alarmOffset = it
                                persistPlacement(FloatingWindow.Alarms, it, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.Alarms) },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.Alarms)),
                        )
                    }

                    // All task trees: the account's named task trees over a timeline of the dated ones.
                    // A date makes a tree a keyframe the scheduler blends its priorities between
                    // (SchedulerDomain.blendedTaskPriorities), so both edits here are authoritative.
                    if (taskTreesWindowOpen) {
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
                            initialOffset = taskTreesOffset,
                            onOffsetChange = {
                                taskTreesOffset = it
                                persistPlacement(FloatingWindow.TaskTrees, it, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.TaskTrees) },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.TaskTrees)),
                        )
                    }

                    // PRD §4 Default sub-tree: the template grafted under every task the user creates. The
                    // template and the switch beside its menu button are authoritative synced state; the
                    // menus it shows are the ordinary §4 naming menus, read off the live tree.
                    if (defaultSubtreeWindowOpen) {
                        DefaultSubtreeWindow(
                            nodes = schedulerState.defaultSubtree,
                            enabled = schedulerState.defaultSubtreeEnabled,
                            onChange = { vm.dispatch(SchedulerIntent.SetDefaultSubtree(it)) },
                            taskMenuEntries = {
                                SchedulerDomain.defaultSubtreeTaskMenuEntries(schedulerState, it)
                            },
                            titleSuggestions = { SchedulerDomain.titleSuggestions(schedulerState, it) },
                            boundSubtree = { SchedulerDomain.taskSubtreeOutline(schedulerState, it) },
                            onDismiss = { defaultSubtreeWindowOpen = false },
                            initialOffset = defaultSubtreeOffset,
                            onOffsetChange = {
                                defaultSubtreeOffset = it
                                persistPlacement(FloatingWindow.DefaultSubtree, it, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.DefaultSubtree) },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.DefaultSubtree)),
                        )
                    }

                    // PRD §7 Keyboard shortcuts: the reference list of every chord, plus what claim the OS
                    // granted the two system-wide ones (the only shortcuts another application can take).
                    if (shortcutsWindowOpen) {
                        ShortcutsWindow(
                            claim = globalHotkeyClaim,
                            onDismiss = { shortcutsWindowOpen = false },
                            initialOffset = shortcutsOffset,
                            onOffsetChange = {
                                shortcutsOffset = it
                                persistPlacement(FloatingWindow.Shortcuts, it, true)
                            },
                            onRaise = { focusWindow(FloatingWindow.Shortcuts) },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(windowZ(FloatingWindow.Shortcuts)),
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
                                .zIndex(windowZ(FloatingWindow.TimeSim))
                                .raiseOnPress { focusWindow(FloatingWindow.TimeSim) },
                        )
                    }
                }
            }

            // The menu's collapse toggle: a bookmark/tab sticking out of the menu's top-right border,
            // straddling into the content (offset by the menu's own fixed width — 188dp). Points « to push
            // the whole menu off-screen; when collapsed the menu is gone and only this bookmark remains,
            // at the far-left edge, now pointing » to pull it back.
            val menuWidth = 188.dp
            IconMenuButton(
                label = if (menuCollapsed) "»" else "«",
                onClick = { menuCollapsed = !menuCollapsed },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = if (menuCollapsed) 0.dp else menuWidth, y = 12.dp)
                    .zIndex(130f),
            )

            // PRD §5 cross-device sync: account/status chip + sign-in dialog (top-right overlay, above the
            // floating windows). Renders nothing when sync is disabled (chip hides on a null state).
            val syncStateValue = vm.syncState?.collectAsState()?.value
            val accountValue = vm.account?.collectAsState()?.value
            var showSignIn by remember { mutableStateOf(false) }
            SyncStatusChip(
                state = syncStateValue,
                onClick = { showSignIn = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).zIndex(120f),
                account = accountValue,
            )
            if (showSignIn) {
                SignInDialog(
                    state = syncStateValue,
                    onSignIn = { e, p -> vm.signIn(e, p) },
                    onCreateAccount = { e, p -> vm.createAccount(e, p) },
                    onSignOut = { vm.signOut() },
                    onDismiss = { showSignIn = false },
                    account = accountValue,
                    // PRD §15: manual server check. The reconcile emits a unified sync moment, which also
                    // runs the side channels (active sessions, derived pauses, exact pause gaps) — see
                    // SchedulerEngine.launchSyncMomentSideChannels.
                    onFetch = { vm.syncNow() },
                )
            }
        }
    }
}

/**
 * PRD §8 (uniform blocks): the intent that commits new bounds/title/pinned for any calendar [block].
 * A panel (it has an [PlacedRecord.entryId]) is updated in place; a green task-record block is pinned
 * into a new panel. Returns null when the block has no usable identity (defensive).
 */
private fun commitBoundsIntent(
    block: PlacedRecord,
    taskId: TaskId?,
    title: String,
    startMillis: Long,
    endMillis: Long,
    pins: PanelPins,
    allowOverlap: Boolean = false,
): SchedulerIntent? {
    return when {
    // A merged block (several same-task panels shown as one): replace the whole group with one panel.
    block.entryIds.size > 1 ->
        SchedulerIntent.ReplaceTaskPanels(block.entryIds, taskId, title, startMillis, endMillis, pins, allowOverlap)
    block.entryId != null ->
        SchedulerIntent.UpdateTaskPanel(block.entryId, taskId, title, startMillis, endMillis, pins, allowOverlap)
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
 * PRD §8: what the period editor ([PeriodEditWindow]) is open on — which of the two periods, the block being
 * edited (null while ADDING one), and the bounds the window opens with. [startMillis]/[endMillis] are the
 * pre-fill only: what is laid comes back from the window's Save, which is where "∞"/"now" are resolved.
 */
private data class PeriodDraft(
    val kind: CalendarPeriodKind,
    val block: PlacedRecord?,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * PRD §8 task contextual menu "Remove": the intent that deletes a calendar [block] from its source —
 * a panel is removed, a green task-record period is dropped from the task record. Returns null when
 * the block has no removable identity (defensive).
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
private fun diagnosticsBandSignature(
    noScreenPeriods: List<TaskTimeRange>,
    carvedSleepHoles: List<TaskTimeRange>,
): String {
    val edges =
        buildList {
            noScreenPeriods.forEach { add(it.startEpochMillis); add(it.endEpochMillis) }
            carvedSleepHoles.forEach { add(it.startEpochMillis); add(it.endEpochMillis) }
        }.sorted()
    val interior = if (edges.size > 2) edges.subList(1, edges.size - 1) else emptyList()
    return "${noScreenPeriods.size}/${carvedSleepHoles.size}:" +
        interior.joinToString(",") { (it / 60_000).toString() }
}

/** PRD §18: `HH:MM` for an alarm's time of day — the calendar marker's label when the alarm has none. */
/**
 * PRD §8 calendar layers: how coarsely this device's OS lock/standby history is re-read while the calendar
 * stays on the same days. The query spawns a process, so it must not run on the display cadence; ten minutes
 * is fine for a band whose whole job is to show where the device was not in use.
 */
private const val LOCK_HISTORY_REFRESH_MILLIS: Long = 10L * 60 * 1000

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
    // covering it with a solid band (see [SchedulerDomain.screenBreakOpenStartMillis]).
    screenBreaks: List<ScreenBreak> = emptyList(),
    // PRD §15/§17: intervals the device/account was ACTIVE — the visible "Sleep" bands are carved here so a
    // window the user worked through shows a gap. Bridging still uses the UNCARVED sleep windows (below), so
    // hiding screen breaks doesn't fuse task blocks across a night just because part of it was carved.
    activeRegions: List<TaskTimeRange> = emptyList(),
    // PRD §8: the account-offline "No screen" windows (un-subtracted, so each spans any sleep it contains
    // plus the awake-offline stretch around it). A sleep window is by definition a no-screen period, so each
    // sleep band records the enclosing offline window here to drive its "No screen" hover line — which is
    // therefore >= the sleep's own span when the sleep is directly followed/preceded by more offline time.
    noScreenRegions: List<TaskTimeRange> = emptyList(),
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
    val blocks = panels.filter { !SchedulerDomain.isReminder(it) && !it.screenBreak && !it.sleep }
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
                    // PRD §15: where this break stops accepting nobody — the hollow part of the band.
                    screenBreakOpenFromMillis =
                        SchedulerDomain.screenBreakOpenStartMillis(screenBreaks, side),
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
                layoutWeight = head.layoutWeight,
                // PRD §8/§9/§12: user-authored no-screen / inactivity periods stay real, removable blocks
                // (drawn as decorative pattern / muted band) rather than task panels.
                noScreen = head.noScreen,
                inactivity = head.inactivity,
                // PRD §8/§12: a hand-added period saved with an open ("∞") start reads as one in the hover
                // bubble, exactly like a derived band that nothing precedes.
                openStart = SchedulerDomain.isOpenPast(head.startEpochMillis),
            )
        }
    // The sleep windows render as their own labeled band behind the task blocks (drawn first), carved wherever
    // the device/account was active so a night the user worked through shows a gap rather than a solid block.
    val sleepRecords =
        SchedulerDomain.carveSleepPanels(sleepPanels, activeRegions).map { sleepPanel ->
            // The enclosing offline window (contains this carved sleep sub-panel's midpoint) — a sleep is
            // always inactive, so it sits inside an offline window that may extend past it. Null when no
            // pause evidence exists yet (conservative empty case): the "No screen" line then falls back to
            // the sleep's own span.
            val mid = (sleepPanel.startEpochMillis + sleepPanel.endEpochMillis) / 2
            val enclosing =
                noScreenRegions.firstOrNull { it.startEpochMillis <= mid && it.endEpochMillis >= mid }
            CalendarRecord(
                title = sleepPanel.title,
                range = TaskTimeRange(sleepPanel.startEpochMillis, sleepPanel.endEpochMillis),
                entryId = sleepPanel.id,
                entryIds = listOf(sleepPanel.id),
                sleep = true,
                noScreenRange = enclosing,
            )
        }
    return sleepRecords + blockRecords + reminderRecords + sideRecords
}
