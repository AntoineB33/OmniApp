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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.AppSchedulerHost
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.PanelPins
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
import org.example.project.scheduler.platform.installPauseCuePushBridge
import org.example.project.scheduler.platform.localPauseCueDeliveryPlatform
import org.example.project.scheduler.platform.scheduleLocalPauseCuePlatform
import org.example.project.scheduler.sync.RemoteSnapshotClient
import org.example.project.scheduler.sync.SchedulerSyncEngine
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.ui.PriorityWeightWindow
import org.example.project.scheduler.ui.SignInDialog
import org.example.project.scheduler.ui.SyncStatusChip
import org.example.project.scheduler.ui.TaskSchedulerScreen
import org.example.project.scheduler.ui.TaskTextWindow
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock
import org.example.project.time.SimAppClock
import org.example.project.time.SystemAppClock
import org.example.project.ui.CalendarFloatingWindow
import org.example.project.ui.CalendarRecord
import org.example.project.ui.ChoresManagerWindow
import org.example.project.ui.HistoryManagerWindow
import org.example.project.ui.IconMenuButton
import org.example.project.ui.raiseOnPress
import org.example.project.ui.LateralMenu
import org.example.project.ui.ManualEntryEditWindow
import org.example.project.ui.PlacedRecord
import org.example.project.ui.ReminderEditWindow
import org.example.project.ui.SimPauseScope
import org.example.project.ui.SleepWindow
import org.example.project.ui.TimeSimPanel
import org.example.project.ui.startOfWeek

enum class OmniPage(val label: String) {
    TaskScheduler("Task Scheduler"),
}

/** The z-stackable floating windows; the currently focused one is drawn on top (see [App]'s windowStack). */
private enum class FloatingWindow { Calendar, Reminders, History, Sleep, TimeSim }

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

// Real ms the leap waits after ending for each linked phone to ack its leap-end session push before the
// desktop derives the pauses: the server presumes a fresh OPEN session active through the now-line, so
// deriving while a phone's stale open row is still up there would read the whole leap window as activity and
// hide the just-simulated pause. Bounded so a wedged phone can't hang the leap; on timeout the derive
// proceeds best-effort and the next sync moment heals the bands.
private const val SIM_PAUSE_LEAP_ACK_TIMEOUT_MILLIS: Long = 5_000

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
                (store as? SyncMetaStore)?.let { SchedulerSyncEngine(RemoteSnapshotClient(), it) }
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
                    presence = vm.presence,
                    sleepGapStore = store as? DeviceSleepGapStore,
                    sleepGaps = vm.sleepGaps,
                    sleepScanCheckpoint = store as? SleepScanCheckpointStore,
                    activeSessionStore = store as? ActiveSessionStore,
                    activeSessions = vm.activeSessions,
                    pauseCue = vm.pauseCue,
                    syncMoments = vm.syncMoments,
                    // PRD §15 / ARCHITECTURE.md §8: the OS-scheduled local cue seam for the engine App() builds
                    // itself (iOS delivers via UNUserNotificationCenter; desktop/web are inert). Android does not
                    // reach here — it injects an AlarmManager seam via SchedulerHolder.
                    scheduleLocalPauseCue = ::scheduleLocalPauseCuePlatform,
                    localPauseCueDelivery = localPauseCueDeliveryPlatform,
                    // ARCHITECTURE.md §8 sync moment #5: reconcile when the heartbeat finds the device went
                    // inactive (a finalized session peers can't re-derive).
                    requestSyncMoment = { vm.syncNow() },
                )
        }
        LaunchedEffect(engine) { if (host == null) engine.start() }
        // PRD §15 / ARCHITECTURE.md §8 (iOS APNs, reqs #2/#6): give the platform's native push layer its two
        // callbacks — publish this phone's APNs token, and route a received pause-cue push into the engine.
        // A no-op off iOS (Android uses its FirebaseMessagingService instead; desktop/web have no push layer).
        LaunchedEffect(engine) {
            installPauseCuePushBridge(
                registerApnsToken = { token ->
                    engineScope.launch { vm.pauseCue?.registerPushToken("phone", "apns", token) }
                },
                onRemotePush = { action, dueAtIso ->
                    val dueMillis =
                        dueAtIso?.takeIf { it.isNotBlank() }
                            ?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                    engine.onPauseCuePush(action, dueMillis)
                },
                // PRD §15 scenario #3: the phone became active — re-claim the account's last phone (the DB
                // trigger then cancels the previous phone's cue). No-op off iOS.
                onForegrounded = { engine.onAppForegrounded() },
            )
        }
        val nowMillis by engine.nowMillis.collectAsState()
        // PRD §15 device-sleep gaps: past pauses drawn as greyed "Inactivity" bands (display-only; see the engine).
        val inactivityGaps by engine.inactivityGaps.collectAsState()
        // PRD §15/§17: start of this device's open active session (null while inactive) — carves the "Sleep" band
        // to the now-line as the user keeps working through a scheduled sleep window (display-only, non-syncing).
        val activeSince by engine.activeSince.collectAsState()
        // PRD §15: end of this device's last finalized session — the locally-observed start of a pause the
        // derived gaps don't cover yet. Unioned in below so the "Inactivity" band grows live behind an
        // advancing now-line (display-only, non-syncing; see SchedulerDomain.displayInactivityGaps).
        val inactiveSince by engine.inactiveSince.collectAsState()

        // PRD §7 calendar state, hoisted so the lateral menu (month grid) and the popup week view
        // stay in sync. "today" follows the (possibly simulated) clock so day rollovers are testable.
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(tz).date
        var calendarOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Calendar)) }
        // PRD §5: the sub-list whose priority-weight window is open (opened by clicking a percentage in the
        // tree), or null when closed. Drawn on the top floating-window layer below; [weightWindowBounds] is
        // its window-space rect, used to ignore presses inside it when dismissing on outside clicks.
        var weightWindowListId by remember { mutableStateOf<CellListId?>(null) }
        var weightWindowBounds by remember { mutableStateOf<Rect?>(null) }
        // PRD §13: the task whose "see text" window is open, or null when closed. Drawn on the top
        // floating-window layer below, same as the priority-weight window; [taskTextBounds] is its rect.
        var taskTextTaskId by remember { mutableStateOf<TaskId?>(null) }
        var taskTextBounds by remember { mutableStateOf<Rect?>(null) }
        // Layout of the content area, so a press position (content-local) can be mapped to window space
        // and compared against the windows' bounds.
        var contentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        // PRD §5: the window closes when any cell enters Edit Mode (its sub-list typing context is gone).
        LaunchedEffect(schedulerState.editSession) {
            if (schedulerState.editSession != null) weightWindowListId = null
        }
        // PRD §7/§14 Chores Manager: whether the floating chores window is open (local UI state, like the
        // calendar window; the chores data itself lives in the persisted scheduler state).
        var choresManagerOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Reminders)) }
        // PRD §5/§6 History Manager: whether the floating history window is open (local UI state).
        var historyManagerOpen by remember { mutableStateOf(savedVisible(FloatingWindow.History)) }
        // Sleep schedule window: whether the floating sleep-settings window is open (local UI state).
        var sleepWindowOpen by remember { mutableStateOf(savedVisible(FloatingWindow.Sleep)) }

        // The floating windows are siblings in one Box, so their paint order is their declaration order.
        // To put the *currently focused* window on top of the layers, we keep an explicit stacking order
        // (last == top) and drive each window's zIndex from it. A window is raised when it is opened and on
        // every press inside it (see raiseOnPress). Unmanaged: the modal edit window (its own scrim already
        // sits above everything).
        var windowStack by remember {
            mutableStateOf(listOf(FloatingWindow.Calendar, FloatingWindow.Reminders, FloatingWindow.History, FloatingWindow.Sleep, FloatingWindow.TimeSim))
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
        // Persist each window's visibility whenever it opens/closes (its offset persists separately on drag-end).
        LaunchedEffect(calendarOpen) { persistPlacement(FloatingWindow.Calendar, calendarOffset, calendarOpen) }
        LaunchedEffect(choresManagerOpen) { persistPlacement(FloatingWindow.Reminders, remindersOffset, choresManagerOpen) }
        LaunchedEffect(historyManagerOpen) { persistPlacement(FloatingWindow.History, historyOffset, historyManagerOpen) }
        LaunchedEffect(sleepWindowOpen) { persistPlacement(FloatingWindow.Sleep, sleepOffset, sleepWindowOpen) }

        var selectedDate by remember { mutableStateOf(today) }
        var monthAnchor by remember { mutableStateOf(LocalDate(today.year, today.month, 1)) }

        // PRD §15: side tasks are projected from now to the END OF THE FOCUSED WEEK — the week the calendar
        // window is showing ([startOfWeek] of [selectedDate], Monday-based, exclusive end = the next Monday's
        // midnight). The scheduling horizon is the floor, so the near term is unchanged and navigating to a
        // further-out week extends the side-task markers to span it. `nowMillis` is the same `now` the last
        // schedule refresh used (the tick loop sets both together), so within the schedule window this
        // reproduces the side-task panels already in [schedulerState.panels] and only adds the tail.
        val focusedWeekEndMillis =
            startOfWeek(selectedDate).plus(7, DateTimeUnit.DAY).atStartOfDayIn(tz).toEpochMilliseconds()
        val sideTaskHorizonMillis = maxOf(nowMillis + SchedulerDomain.SCHEDULE_HORIZON_MILLIS, focusedWeekEndMillis)
        // The user's sleep windows — shown as "Sleep" blocks and avoided by the regular task fill (so no task
        // is scheduled while asleep). Side tasks, by contrast, DO project across sleep so their eye-rest / pose
        // cues still render over the "Sleep" band for a user working through the night (PRD §15). Generated
        // back over the past 168h too (the same horizon the §15 inactivity bands use), so past nights render as
        // "Sleep" and the inactivity bands can be carved around them (a night is labelled Sleep, not Inactivity).
        val displaySleepPanels =
            SchedulerDomain.sleepPanels(
                schedulerState.sleep, nowMillis - SchedulerDomain.SCHEDULE_HORIZON_MILLIS, sideTaskHorizonMillis, tz,
            )
        val displaySleepRegions =
            displaySleepPanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        // The same live-pause placement overlay the reducer refill applies (SchedulerReducer.liveRestGap):
        // the display projection folds this device's ongoing/held pause into the side-task grid, so the
        // rendered markers match the engine's placement and move with the pause (an ongoing pause is
        // presumed to serve each task — the whole grid re-places at walk-away and stays fluid under an
        // accelerated leap) instead of letting the now-line cross a stale slot or freezing everything
        // downstream of a not-yet-served pose. Placement-only — stored side-task state is untouched.
        val displaySidePanels =
            SchedulerDomain.sideTaskPanels(
                SchedulerDomain.sideTasksForPlacement(
                    schedulerState.sideTasks,
                    SchedulerDomain.liveRestGap(inactiveSince, activeSince, nowMillis),
                ),
                nowMillis,
                sideTaskHorizonMillis,
            )

        // PRD §15 (20s look-away): show the manual "Look away now" button only when the most recent past
        // side task before the now-line is a 20s look-away (a non-rest-break side task), not a rest pose.
        val showLookAwayButton =
            SchedulerDomain.lastSideTaskBefore(schedulerState.sideTasks, nowMillis)
                ?.let { panel -> schedulerState.sideTasks.any { !it.restBreak && it.title == panel.title } }
                ?: false

        // PRD §14: reminder flags are calculated for the WHOLE focused week — from now to the end of the
        // week the calendar is showing — so navigating to a week shows its reminders. Like the side-task
        // projection they are regenerated for display (anchored at today's midnight, out to the focused
        // week's end), with each tag's checked state carried over from the stored reminder panels by
        // matching its deterministic id.
        val todayStartMillis = today.atStartOfDayIn(tz).toEpochMilliseconds()
        val reminderHorizonDays =
            ((focusedWeekEndMillis - todayStartMillis) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
        val displayReminderPanels =
            SchedulerDomain.regenerateChorePanels(
                schedulerState.panels, schedulerState.chores, todayStartMillis, reminderHorizonDays, nowMillis,
            ).filter { SchedulerDomain.isReminder(it) }

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
        val displayInactivityGaps =
            SchedulerDomain.displayInactivityGaps(inactivityGaps, inactiveSince, activeSince, nowMillis)
        val pastActivityWindow = TaskTimeRange(nowMillis - SchedulerDomain.SCHEDULE_HORIZON_MILLIS, nowMillis)
        val accountActiveRegions =
            if (inactivityGaps.isEmpty()) {
                emptyList()
            } else {
                SchedulerDomain.subtractRegions(listOf(pastActivityWindow), displayInactivityGaps)
            }
        val activeRegions =
            accountActiveRegions +
                (activeSince?.takeIf { it < nowMillis }?.let { listOf(TaskTimeRange(it, nowMillis)) } ?: emptyList())
        // PRD §15: recorded past pauses render as greyed "Inactivity" bands, carved around the §17 sleep
        // windows (already "Sleep"), so the two never overlap. Sub-minute remnants are noise, not a real
        // away-from-every-device pause — e.g. the few seconds between the §17 scheduled wake (where "Sleep"
        // ends) and the first session of a freshly-opened account, which would otherwise show as a tiny
        // "20-second pause right after sleep" (see [SchedulerDomain.MIN_INACTIVITY_BAND_MILLIS]).
        val inactivityBands =
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
        val bandSignature = diagnosticsBandSignature(inactivityBands, carvedSleepHoles)
        LaunchedEffect(bandSignature) {
            Diagnostics.log("calendar shows Inactivity bands: ${Diagnostics.formatRanges(inactivityBands)}")
            if (carvedSleepHoles.isNotEmpty()) {
                Diagnostics.log(
                    "calendar Sleep bands carved by activity at: ${Diagnostics.formatRanges(carvedSleepHoles)}",
                )
            }
        }
        // Done periods (PRD §8 task record, green) plus every calendar panel (PRD §8/§9 — auto and
        // user-authored, uniform blocks) drawn the same way; reminders (PRD §14) and side tasks (PRD §15)
        // span the focused week.
        val calendarRecords =
            schedulerState.tasks.values.flatMap { task ->
                task.record.map { CalendarRecord(title = task.title, range = it, taskId = task.id) }
            } + mergePanelsForDisplay(
                schedulerState.panels, displayReminderPanels, displaySidePanels, displaySleepPanels,
                schedulerState.showSideTasks, schedulerState.showReminders, activeRegions,
            ) + inactivityBands.map { gap ->
                CalendarRecord(
                    title = "Inactivity",
                    range = gap,
                    inactivity = true,
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
                    onSelectDate = { selectedDate = it },
                    automaticSchedule = schedulerState.automaticSchedule,
                    onToggleAutomaticSchedule = { vm.dispatch(SchedulerIntent.SetAutomaticSchedule(it)) },
                    choresManagerOpen = choresManagerOpen,
                    onToggleChoresManager = { onMenuWindowClicked(FloatingWindow.Reminders) { choresManagerOpen = it } },
                    historyManagerOpen = historyManagerOpen,
                    onToggleHistoryManager = { onMenuWindowClicked(FloatingWindow.History) { historyManagerOpen = it } },
                    lookAwayVoiceEnabled = schedulerState.lookAwayVoiceEnabled,
                    onToggleLookAwayVoice = { vm.dispatch(SchedulerIntent.SetLookAwayVoice(it)) },
                    showLookAwayButton = showLookAwayButton,
                    onLookAwayNow = { engine.restartLookAway() },
                    sleepWindowOpen = sleepWindowOpen,
                    onToggleSleep = { onMenuWindowClicked(FloatingWindow.Sleep) { sleepWindowOpen = it } },
                    anyWindowOpen = calendarOpen || choresManagerOpen || historyManagerOpen || sleepWindowOpen,
                    onCloseAllWindows = {
                        calendarOpen = false
                        choresManagerOpen = false
                        historyManagerOpen = false
                        sleepWindowOpen = false
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
                        // PRD §5/§13: the priority-weight window and the "see text" window close when a press
                        // lands anywhere outside them, and that press still does its normal job (selecting a
                        // cell, focusing the calendar, …). We observe presses in the Initial pass without
                        // consuming them — an ancestor of every window, so it catches clicks on the tree and
                        // on other floating windows alike. A press inside a window's own bounds is ignored.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type != PointerEventType.Press) continue
                                    if (weightWindowListId == null && taskTextTaskId == null) continue
                                    val pos = event.changes.firstOrNull()?.position ?: continue
                                    val win = contentCoords?.localToWindow(pos) ?: continue
                                    if (weightWindowListId != null && weightWindowBounds?.contains(win) != true) {
                                        weightWindowListId = null
                                    }
                                    if (taskTextTaskId != null && taskTextBounds?.contains(win) != true) {
                                        taskTextTaskId = null
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
                                onSetTaskTextWindow = { taskTextTaskId = it },
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

                    // PRD §13: the "see text" task-text window, also on the top floating-window layer.
                    // Opened from a cell's right-click menu; auto-saves; closed by the interceptor above.
                    taskTextTaskId?.let { taskId ->
                        val task = schedulerState.tasks[taskId]
                        if (task == null) {
                            taskTextTaskId = null
                        } else {
                            TaskTextWindow(
                                taskTitle = task.title,
                                initialText = task.text,
                                onTextChange = { vm.dispatch(SchedulerIntent.SetTaskText(taskId, it)) },
                                onBoundsChange = { taskTextBounds = it },
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
                            // PRD §8 (uniform blocks): committing a drag/resize updates the panel
                            // (auto blocks become user-authored), or pins a record into a panel.
                            onCommitBounds = { block, newStart, newEnd, allowOverlap ->
                                commitBoundsIntent(
                                    block, block.taskId, block.title, newStart, newEnd, block.pins, allowOverlap,
                                )?.let(vm::dispatch)
                            },
                            onEditEntry = { block -> editingBlock = block },
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
                            // PRD §14/§15: the calendar's "Reminders" / "Side tasks" display switches (cosmetic;
                            // notifications stay on).
                            showSideTasks = schedulerState.showSideTasks,
                            onToggleSideTasks = { vm.dispatch(SchedulerIntent.SetShowSideTasks(it)) },
                            showReminders = schedulerState.showReminders,
                            onToggleReminders = { vm.dispatch(SchedulerIntent.SetShowReminders(it)) },
                            onUndo = { vm.dispatch(SchedulerIntent.Undo) },
                            onRedo = { vm.dispatch(SchedulerIntent.Redo) },
                            initialOffset = calendarOffset,
                            onOffsetChange = {
                                calendarOffset = it
                                persistPlacement(FloatingWindow.Calendar, it, true)
                            },
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
                                onDismiss = { editingBlock = null; addingBlock = null },
                                onSave = { taskId, title, startMillis, endMillis, pins ->
                                    val intent =
                                        if (isNew) {
                                            SchedulerIntent.AddTaskPanel(taskId, title, startMillis, endMillis, pins)
                                        } else {
                                            commitBoundsIntent(block, taskId, title, startMillis, endMillis, pins)
                                        }
                                    intent?.let(vm::dispatch)
                                    editingBlock = null
                                    addingBlock = null
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
                                        // Publish this desktop's sessions (leap-start finalize + the reopen
                                        // just above) BEFORE the phones see the leap end: the phone's own
                                        // leap-end derive would otherwise read this desktop's stale OPEN
                                        // server row as activity covering the whole leap and never seed the
                                        // pause — the mirror image of the desktop-side wait below.
                                        engine.pushOwnActiveSessionsAndWait()
                                        timeLink?.setPhoneForcedInactive(false)
                                        // Wait for each linked phone to push the session it finalized at leap
                                        // start (it acks over the link once the push landed): deriving before
                                        // that would read the phone's stale OPEN server row as activity
                                        // covering the whole leap and hide the just-simulated pause — the
                                        // "now-line drags the scheduled break" anomaly. No-op with no phone.
                                        timeLink?.awaitPhoneLeapAcks(SIM_PAUSE_LEAP_ACK_TIMEOUT_MILLIS)
                                        // A debug control counts as an explicit sync moment: push the finalized
                                        // sessions and re-derive the "Inactivity" bands / reseed the rest poses
                                        // now instead of waiting for the next reconcile.
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
            var showSignIn by remember { mutableStateOf(false) }
            SyncStatusChip(
                state = syncStateValue,
                onClick = { showSignIn = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).zIndex(120f),
            )
            if (showSignIn) {
                SignInDialog(
                    state = syncStateValue,
                    onSignIn = { e, p -> vm.signIn(e, p) },
                    onSignUp = { e, p -> vm.signUp(e, p) },
                    onSignOut = { vm.signOut() },
                    onDismiss = { showSignIn = false },
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
 * rendered Inactivity bands + carved-sleep holes, plus their counts. The single outermost start and end
 * are dropped because they track the sliding derive window and the advancing now-line — logging on those
 * would emit a line per tick. Any real shape change (a band added/removed, a hole opening inside the
 * coverage) moves an interior edge or a count and re-logs.
 */
private fun diagnosticsBandSignature(
    inactivityBands: List<TaskTimeRange>,
    carvedSleepHoles: List<TaskTimeRange>,
): String {
    val edges =
        buildList {
            inactivityBands.forEach { add(it.startEpochMillis); add(it.endEpochMillis) }
            carvedSleepHoles.forEach { add(it.startEpochMillis); add(it.endEpochMillis) }
        }.sorted()
    val interior = if (edges.size > 2) edges.subList(1, edges.size - 1) else emptyList()
    return "${inactivityBands.size}/${carvedSleepHoles.size}:" +
        interior.joinToString(",") { (it / 60_000).toString() }
}

private fun mergePanelsForDisplay(
    panels: List<TaskPanel>,
    reminderPanels: List<TaskPanel>,
    sidePanels: List<TaskPanel>,
    sleepPanels: List<TaskPanel>,
    showSideTasks: Boolean,
    showReminders: Boolean,
    // PRD §15/§17: intervals the device/account was ACTIVE — the visible "Sleep" bands are carved here so a
    // window the user worked through shows a gap. Bridging still uses the UNCARVED sleep windows (below), so
    // hiding side tasks doesn't fuse task blocks across a night just because part of it was carved.
    activeRegions: List<TaskTimeRange> = emptyList(),
): List<CalendarRecord> {
    // PRD §14/§15: reminder tags (zero-duration) and side tasks (very short real durations, e.g. a 20-second
    // look-away) are NOT height-proportional blocks — drawn at scale they'd be invisible. They render on
    // their own fixed-height marker paths (CalendarRecord.reminder / .sideTask) and never merge with panels.
    // PRD §14/§15: reminder tags ([reminderPanels]) and side tasks ([sidePanels]) are both projected across
    // the focused week (which may run past the schedule's fixed obstacle window in [panels]) — not taken from
    // [panels]; the regular `blocks` still come from [panels]. Within the schedule window the projections are
    // identical (same `now`, same chores/side tasks), so the blocks stay split around the side tasks exactly
    // as scheduled and the checked state of each reminder is carried over by matching its deterministic id.
    val reminders = if (showReminders) reminderPanels else emptyList()
    val sides = sidePanels
    val blocks = panels.filter { !SchedulerDomain.isReminder(it) && !it.sideTask && !it.sleep }
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
    // PRD §15 toggle: when side tasks are hidden, draw none, and let same-task panels separated only by a
    // (now-hidden) side task fuse into one block (cosmetic — the panels and the schedule are untouched).
    val sideRecords =
        if (!showSideTasks) {
            emptyList()
        } else {
            sides.map { side ->
                CalendarRecord(
                    title = side.title,
                    range = TaskTimeRange(side.startEpochMillis, side.endEpochMillis),
                    entryId = side.id,
                    entryIds = listOf(side.id),
                    sideTask = true,
                )
            }
        }
    // PRD §15: when side tasks are hidden, fuse same-task panels across the gaps the (now-hidden) side-task
    // pauses left — structurally, so the fused block doesn't flicker as `now` advances (a moving side-task
    // projection would keep drifting out of alignment with the already-scheduled gaps).
    val sleepRanges = sleepPanels.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
    val blockRecords =
        SchedulerDomain.groupSameTaskPanelsForDisplay(blocks, bridgeGaps = !showSideTasks, sleepRegions = sleepRanges).map { group ->
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
            )
        }
    // The sleep windows render as their own labeled band behind the task blocks (drawn first), carved wherever
    // the device/account was active so a night the user worked through shows a gap rather than a solid block.
    val sleepRecords =
        SchedulerDomain.carveSleepPanels(sleepPanels, activeRegions).map { sleepPanel ->
            CalendarRecord(
                title = sleepPanel.title,
                range = TaskTimeRange(sleepPanel.startEpochMillis, sleepPanel.endEpochMillis),
                entryId = sleepPanel.id,
                entryIds = listOf(sleepPanel.id),
                sleep = true,
            )
        }
    return sleepRecords + blockRecords + reminderRecords + sideRecords
}
