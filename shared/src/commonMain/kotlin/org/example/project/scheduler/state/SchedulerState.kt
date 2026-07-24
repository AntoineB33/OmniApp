package org.example.project.scheduler.state

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds

data class SchedulerSelection(
    val main: CellId? = null,
    val selected: Set<CellId> = emptySet(),
    /** Fixed end of a Shift+Click / Shift+Arrow range; `null` for disjoint Ctrl multi-select. */
    val rangeAnchor: CellId? = null,
    /**
     * Expanded occurrence that owns the current selection path for mirrored subtrees.
     * `null` for cells rendered in the root viewport list.
     */
    val renderVia: CellId? = null,
)

data class SchedulerHistory(
    val pointer: Int = -1,
    val units: List<HistoryUnit> = emptyList(),
)

/**
 * PRD §5 History Architecture: history is split into independent categories, each with its own list
 * of units and pointer — changes made in Edit Mode, selection-state changes, calendar edits, and
 * "the rest" (tree/expansion mutations). Selection history is undone/redone separately (Alt+Left /
 * Alt+Right) from the content categories (Ctrl+Z / Ctrl+Y).
 *
 * The content categories implement PRD §5's "pointer navigates by context": Ctrl+Z/Y target [Edit]
 * while an Edit-Mode session is open (so it only touches that session's text changes, skipping every
 * other unit), [Calendar] while the calendar is focused (skipping non-calendar units — and non-focus
 * Ctrl+Z skips calendar units), and otherwise [Main].
 */
enum class HistoryCategory { Edit, Selection, Calendar, Main, WindowNav }

/**
 * PRD §7: the focus targets the user navigates between — the task tree plus the floating windows. The
 * focused window is the top layer except when the task tree is focused. Persisted with the rest of the
 * app state.
 */
enum class AppWindow { Tree, Calendar, Reminders, History }

/**
 * PRD §5/§6: every History Unit lives in one shared timeline; the categories are just how the History
 * Manager *queries and groups* that timeline into columns. [WindowNav] holds window-navigation units
 * (PRD §7) — recorded for display but, for now, not walked by any undo/redo command.
 */
data class SchedulerHistories(
    val edit: SchedulerHistory = SchedulerHistory(),
    val selection: SchedulerHistory = SchedulerHistory(),
    val calendar: SchedulerHistory = SchedulerHistory(),
    val main: SchedulerHistory = SchedulerHistory(),
    val windowNav: SchedulerHistory = SchedulerHistory(),
) {
    fun forCategory(category: HistoryCategory): SchedulerHistory =
        when (category) {
            HistoryCategory.Edit -> edit
            HistoryCategory.Selection -> selection
            HistoryCategory.Calendar -> calendar
            HistoryCategory.Main -> main
            HistoryCategory.WindowNav -> windowNav
        }

    fun withCategory(category: HistoryCategory, history: SchedulerHistory): SchedulerHistories =
        when (category) {
            HistoryCategory.Edit -> copy(edit = history)
            HistoryCategory.Selection -> copy(selection = history)
            HistoryCategory.Calendar -> copy(calendar = history)
            HistoryCategory.Main -> copy(main = history)
            HistoryCategory.WindowNav -> copy(windowNav = history)
        }

    /** Every category's history, in a fixed order — for queries that span the whole timeline. */
    fun all(): List<Pair<HistoryCategory, SchedulerHistory>> =
        HistoryCategory.entries.map { it to forCategory(it) }

    /**
     * Whether any retained unit (in any category) was committed under the diverged debug clock and so
     * is pending revert at the next app start. Drives the debug panel's red "will be reverted" warning.
     */
    val hasPendingDebugRollback: Boolean
        get() = all().any { (_, history) -> history.units.any { it.debugTainted } }
}

/**
 * PRD §6: one recorded change. [timeMillis] is the exact wall-clock instant (epoch millis) of the
 * change. [chronoId] only breaks ties between units that share the same [timeMillis]: it is **0** by
 * default and becomes 1, 2, … (in commit order) when an earlier retained unit already carries the
 * same timestamp, so truly simultaneous events still sort deterministically.
 *
 * [debugTainted] marks a unit committed while the debug time-simulation clock was diverged from the
 * real wall clock (accelerated / paused / leaped). Such units are persisted like any other, but the
 * next app start reverts and deletes them (see `TaskSchedulerViewModel.loadInitialState`) so debug
 * fast-forwarding never pollutes the real saved data with future-dated changes.
 */
data class HistoryUnit(
    val timeMillis: Long,
    val chronoId: Long = 0,
    val delta: Delta,
    val debugTainted: Boolean = false,
)

/**
 * One line in the History Manager's **Notifications** column: the text of a notification the app posted,
 * with the (sim) wall-clock instant it fired at. A bounded, per-device **diagnostic** log — capped at
 * [SchedulerState.MAX_NOTIFICATION_LOG]; the app keeps the *first* that many notifications and ignores every
 * one after (a fixed audit of the earliest notifications, not a rolling tail). Derived / local-only: recorded
 * via the non-syncing [org.example.project.scheduler.state.SchedulerIntent.RecordNotification], stripped from
 * the sync fingerprint, and carried across a remote pull ([SchedulerState.withLocalViewStateFrom]) so a
 * peer's snapshot never overwrites it.
 */
data class NotificationLogEntry(
    val timeMillis: Long,
    val title: String,
    val message: String,
)

/**
 * One line in the History Manager's **Supabase usage** column: a single Supabase HTTP call the app made, with
 * the free-plan [resource] bucket it draws down, a compact [operation] label, the response [status], and the
 * best-effort request/response byte counts (egress **bandwidth** is itself a free-plan limit). A bounded,
 * per-device **diagnostic** log — a **rolling tail** capped at [SchedulerState.MAX_SUPABASE_USAGE_LOG] (the most
 * RECENT that many; unlike [NotificationLogEntry]'s first-N audit, this tracks ongoing consumption). Derived /
 * local-only: recorded via the non-syncing
 * [org.example.project.scheduler.state.SchedulerIntent.RecordSupabaseUsage], stripped from the sync fingerprint,
 * and carried across a remote pull ([SchedulerState.withLocalViewStateFrom]) so a peer's snapshot never touches it.
 */
data class SupabaseUsageEntry(
    val timeMillis: Long,
    val resource: String,
    val operation: String,
    val requestBytes: Long,
    val responseBytes: Long,
    val status: Int,
)

sealed interface Delta {
    /** PRD §5/§6 History Manager: a short human-readable name for this unit (shown in the history window). */
    val label: String

    /**
     * PRD §5/§6 History Manager: the human-readable specifics of this unit — one line per concrete change
     * (a renamed cell, an added/removed panel, the selection before→after, …). Derived from the unit's
     * own before/after data so the window can display all of a unit's data, not just its [label]. Empty
     * when the unit carries no meaningful per-item detail.
     */
    val details: List<String> get() = emptyList()

    fun undo(state: SchedulerState): SchedulerState
    fun redo(state: SchedulerState): SchedulerState
}

/** Tree + title index fields affected by structural / edit mutations. */
data class TreeSnapshot(
    val cells: Map<CellId, Cell>,
    val lists: Map<CellListId, CellList>,
    val tasks: Map<TaskId, Task>,
    val titleToTaskIds: Map<String, List<TaskId>>,
    val nextTaskCounter: Int,
    val nextCellCounter: Int,
)

data class SchedulerState(
    val rootListId: CellListId,
    val lists: Map<CellListId, CellList>,
    val cells: Map<CellId, Cell>,
    val tasks: Map<TaskId, Task>,
    val titleToTaskIds: Map<String, List<TaskId>>,
    val expanded: Set<CellId>,
    val selection: SchedulerSelection,
    val editSession: SchedulerEditSession? = null,
    val histories: SchedulerHistories = SchedulerHistories(),
    val nextTaskCounter: Int = 0,
    /** Monotonic suffix for `cell/{listId}/{n}` ids; avoids collisions between paste inserts and auto-expansion. */
    val nextCellCounter: Int = 1,
    /** In-memory clipboard for copy/paste (not persisted). */
    val clipboard: List<String> = emptyList(),
    /**
     * PRD §8/§9 task panels: the calendar blocks in the schedulable window — both scheduler-generated
     * (`auto`) panels and user-authored ones, with `pinned` panels surviving a reschedule (see
     * [TaskPanel]). Persisted user/scheduler data that lives outside [TreeSnapshot]: *manual* panel-list
     * edits (PRD §8) go through the [HistoryCategory.Calendar] stack, not the tree snapshot. An
     * automatic scheduling run (PRD §9) is derived from the state and is NOT recorded in any history.
     */
    val panels: List<TaskPanel> = emptyList(),
    /** Monotonic suffix for `panel/{n}` ids; never reused, so undo need not roll it back. */
    val nextPanelCounter: Int = 0,
    /**
     * PRD §7 Automatic Schedule Switch: while off, the §9 calculation events that update the schedule
     * are deferred until it is turned back on. Persisted (defaults on). Toggling it is not undoable.
     */
    val automaticSchedule: Boolean = true,
    /**
     * PRD §7 the window the user is currently focused on (the task tree or a floating window). Routes
     * Ctrl+Z/Y to that window's history (PRD §5/§6) and gates which surface catches letter typing
     * (PRD §8). Persisted with the rest of the app state.
     */
    val focusedWindow: AppWindow = AppWindow.Tree,
    /**
     * PRD §8 Overlap Mode: whether `O` has armed "allow overlap" for the next calendar move/resize.
     * Transient session state, not persisted and not undoable.
     */
    val overlapArmed: Boolean = false,
    /**
     * PRD §14 Chores Manager: the standalone list of chores (title + spanning time in days). Persisted
     * (survives sessions) but, like the panels/switch, lives outside the [TreeSnapshot] — editing it is
     * not routed through the Undo/Redo tree history.
     */
    val chores: List<ChoreEntry> = emptyList(),
    /**
     * PRD §18 Alarms: the alarms the account's phones ring at (edited in the left-menu Alarms window).
     * Authoritative user data — persisted **and synced**, like the reminders and the sleep schedule; each
     * phone arms its own OS-level alarm from this list, so a ring needs no server round-trip. Empty by
     * default (a fresh account has no alarm).
     */
    val alarms: List<org.example.project.scheduler.model.AlarmEntry> = emptyList(),
    /**
     * PRD §15 Screen breaks: the periodic screen breaks to weave into the auto schedule. A hardcoded set in
     * production (seeded by [org.example.project.scheduler.ui.TaskSchedulerViewModel] from
     * [org.example.project.scheduler.domain.SchedulerDomain.DEFAULT_SCREEN_BREAKS]); empty by default so the
     * scheduler tests that assert exact schedules see no screen breaks unless they opt in.
     */
    val screenBreaks: List<org.example.project.scheduler.model.ScreenBreak> = emptyList(),
    /**
     * PRD §15 Screen breaks: whether the calendar window draws the screen breaks. A purely cosmetic display
     * preference (persisted, not undoable) — when off, screen-break blocks are hidden and two same-task panels
     * separated only by a hidden screen break render as one merged block. The underlying panels and the
     * scheduling (and the screen-break notifications) are unaffected; the real spanning time never changes.
     */
    val showScreenBreaks: Boolean = false,
    /**
     * PRD §14 Reminders: whether the calendar window draws the reminder tags. A purely cosmetic display
     * preference (persisted, not undoable) — when off, reminder tags are hidden. The underlying chores and
     * their scheduling/checked state are unaffected.
     */
    val showReminders: Boolean = true,
    /**
     * PRD §15 Screen breaks (20s look-away): whether the spoken voice cue is enabled — when the look-away pause
     * is reached a voice says to look away and, at the pause's end, to resume work (in addition to the
     * notification). On by default; persisted, not undoable.
     */
    val lookAwayVoiceEnabled: Boolean = true,
    /**
     * The user's sleep schedule (a nightly window the §9 task fill and the §15 screen-break projection must
     * leave empty; see [org.example.project.scheduler.domain.SchedulerDomain.sleepPanels]). **Null by
     * default** so the scheduler tests that assert exact schedules see no sleep window unless they opt in;
     * production is seeded with [org.example.project.scheduler.domain.SchedulerDomain.DEFAULT_SLEEP] by
     * [org.example.project.scheduler.ui.TaskSchedulerViewModel]. Persisted; not undoable.
     */
    val sleep: org.example.project.scheduler.model.SleepSchedule? = null,
    /**
     * The Sleep/Work toggle (left-menu control): when the user presses **Sleep** (deliberately going away),
     * this is set to the next scheduled wake instant (epoch millis) — the button then reads **Work**. `null`
     * means working (the button reads **Sleep**). On launch the ViewModel resets it to `null` if the wake
     * instant has passed (see [org.example.project.scheduler.ui.TaskSchedulerViewModel]); until then a restart
     * keeps the sleeping state so the user needn't re-press Sleep. The external Realtime listener reads the
     * account's mode (mirrored to the `account_state` table on every toggle) to suppress the pause-end cue
     * while sleeping. Persisted + synced (authoritative, user-authored); not undoable.
     */
    val sleepingUntilMillis: Long? = null,
    /**
     * The instant the current Sleep/Work sleep session began (the user pressed **Sleep**), or `null` when
     * working. While the toggle is on, the calendar renders a live "Sleep" band `[sleepingSinceMillis, now]`
     * that grows to the now-line; when the toggle goes off (or the wake instant lapses) the reducer
     * finalizes that span as a persisted past "Sleep" panel and clears this (PRD §17 — past sleep is a
     * recorded fact, not a projection of the schedule). Persisted so a restart mid-sleep keeps the band;
     * a payload written before this field decodes to null.
     */
    val sleepingSinceMillis: Long? = null,
    /**
     * A bounded, local-only diagnostic log of the notification text the app has posted, shown as the
     * History Manager's **Notifications** column. Capped at [MAX_NOTIFICATION_LOG] — the earliest that
     * many entries are kept and the rest ignored (see [NotificationLogEntry]). Derived / local-only: it
     * never affects the sync fingerprint and is never adopted from a remote pull.
     */
    val notificationLog: List<NotificationLogEntry> = emptyList(),
    /**
     * A bounded, local-only diagnostic log of every Supabase HTTP call the app made, shown as the History
     * Manager's **Supabase usage** column so the account's draw-down on the Supabase **free-plan** limits
     * (egress bandwidth, Auth MAU, request count) is visible at a glance. A **rolling tail** capped at
     * [MAX_SUPABASE_USAGE_LOG] — the most recent that many are kept (see [SupabaseUsageEntry]). Derived /
     * local-only: it never affects the sync fingerprint and is never adopted from a remote pull.
     */
    val supabaseUsageLog: List<SupabaseUsageEntry> = emptyList(),
) {
    /** PRD §8: the calendar catches letter typing / routes Ctrl+Z/Y only while it is the focused window. */
    val calendarFocused: Boolean get() = focusedWindow == AppWindow.Calendar

    /**
     * Sleep/Work toggle: true when the user has pressed **Sleep** and the scheduled wake instant
     * ([sleepingUntilMillis]) has not yet passed at [nowMillis]. The button reads **Work** while sleeping and
     * **Sleep** while working.
     */
    fun isSleeping(nowMillis: Long): Boolean = sleepingUntilMillis?.let { nowMillis < it } ?: false

    /**
     * CLAUDE.md reconstructibility rule: the per-device **view state** that must never sync — it is only
     * useful to the local app, so switching it must not write the remote `scheduler_snapshot` and a pulled
     * remote snapshot must not adopt another device's value. It covers:
     *  - [focusedWindow] — PRD §7 window navigation (the tree vs. a floating window),
     *  - [selection] — which tree cell(s) are highlighted (cleared as a side effect of navigating away),
     *  - [showScreenBreaks] / [showReminders] — the calendar's cosmetic display switches,
     *  - the [HistoryCategory.WindowNav] and [HistoryCategory.Selection] history that records those moves,
     *  - [notificationLog] — the per-device diagnostic notification log (never synced, never adopted),
     *  - [supabaseUsageLog] — the per-device Supabase-usage diagnostic log (never synced, never adopted).
     * (Calendar *zoom* is likewise local, but it lives only in Compose UI state and is never persisted.)
     *
     * [withLocalViewStateFrom] carries these fields from [other] (used when a remote pull replaces the rest
     * of the state), and [withLocalViewStateNeutralized] resets them to canonical constants so
     * [org.example.project.scheduler.persistence.SchedulerStateCodec.syncFingerprint] treats a view-only
     * change as no authoritative change (no push).
     */
    fun withLocalViewStateFrom(other: SchedulerState): SchedulerState =
        copy(
            focusedWindow = other.focusedWindow,
            selection = other.selection,
            showScreenBreaks = other.showScreenBreaks,
            showReminders = other.showReminders,
            notificationLog = other.notificationLog,
            supabaseUsageLog = other.supabaseUsageLog,
            histories = histories
                .withCategory(HistoryCategory.WindowNav, other.histories.forCategory(HistoryCategory.WindowNav))
                .withCategory(HistoryCategory.Selection, other.histories.forCategory(HistoryCategory.Selection)),
        )

    /** See [withLocalViewStateFrom]: the local-only view state reset to canonical constants for the fingerprint. */
    fun withLocalViewStateNeutralized(): SchedulerState =
        copy(
            focusedWindow = AppWindow.Tree,
            selection = SchedulerSelection(),
            showScreenBreaks = false,
            showReminders = true,
            notificationLog = emptyList(),
            supabaseUsageLog = emptyList(),
            histories = histories
                .withCategory(HistoryCategory.WindowNav, SchedulerHistory())
                .withCategory(HistoryCategory.Selection, SchedulerHistory()),
        )

    // PRD §8: the task record is NOT part of the history state, so it is stripped from snapshots
    // (capture) and re-attached from the live tasks on restore (applyTree). Undo/Redo therefore
    // never reverts records, even though they live on the Task object.
    fun captureTree(): TreeSnapshot =
        TreeSnapshot(
            cells = cells,
            lists = lists,
            tasks = tasks.mapValues { (_, task) -> task.copy(record = emptyList()) },
            titleToTaskIds = titleToTaskIds,
            nextTaskCounter = nextTaskCounter,
            nextCellCounter = nextCellCounter,
        )

    fun applyTree(snapshot: TreeSnapshot): SchedulerState =
        copy(
            cells = snapshot.cells,
            lists = snapshot.lists,
            tasks =
                snapshot.tasks.mapValues { (id, task) ->
                    task.copy(record = tasks[id]?.record ?: emptyList())
                },
            titleToTaskIds = snapshot.titleToTaskIds,
            nextTaskCounter = snapshot.nextTaskCounter,
            nextCellCounter = snapshot.nextCellCounter,
        )

    fun allocateTaskId(): Pair<TaskId, SchedulerState> {
        val id = TaskId("task/user/$nextTaskCounter")
        return id to copy(nextTaskCounter = nextTaskCounter + 1)
    }

    fun allocateCellId(listId: CellListId): Pair<CellId, SchedulerState> {
        val id = CellId("cell/${listId.value}/$nextCellCounter")
        return id to copy(nextCellCounter = nextCellCounter + 1)
    }

    fun allocatePanelId(): Pair<String, SchedulerState> {
        val id = "panel/$nextPanelCounter"
        return id to copy(nextPanelCounter = nextPanelCounter + 1)
    }

    companion object {
        /**
         * Cap on [notificationLog]: the app keeps the FIRST this-many notifications and ignores every one
         * after (see [NotificationLogEntry]). A fixed audit of the earliest notifications, not a rolling tail.
         */
        const val MAX_NOTIFICATION_LOG = 1000

        /**
         * Cap on [supabaseUsageLog]: a **rolling tail** — the app keeps the most RECENT this-many calls and
         * drops the oldest (see [SupabaseUsageEntry]). A running view of ongoing free-plan consumption, not a
         * frozen first-N audit like [MAX_NOTIFICATION_LOG].
         */
        const val MAX_SUPABASE_USAGE_LOG = 2000

        /** Ensures [nextCellCounter] stays above every numeric suffix already used in persisted cell ids. */
        fun deriveNextCellCounter(cells: Collection<CellId>): Int {
            val maxSuffix =
                cells.maxOfOrNull { id ->
                    id.value.substringAfterLast('/').toIntOrNull() ?: -1
                } ?: -1
            return maxOf(maxSuffix + 1, 1)
        }

        fun empty(): SchedulerState {
            val placeholderId = CellId("cell/main/0")
            val placeholder =
                Cell(
                    id = placeholderId,
                    parentListId = WellKnownIds.MAIN_LIST,
                    taskId = null,
                )
            val mainList =
                CellList(
                    id = WellKnownIds.MAIN_LIST,
                    parentCellId = null,
                    cellIds = listOf(placeholderId),
                )
            val mainTask =
                Task(
                    id = WellKnownIds.MAIN_TASK,
                    title = "main",
                    childListId = WellKnownIds.MAIN_LIST,
                )
            val rootTask =
                Task(
                    id = WellKnownIds.ROOT_TASK,
                    title = "root",
                    childTaskIds = listOf(WellKnownIds.MAIN_TASK),
                )
            val tasks = mapOf(WellKnownIds.ROOT_TASK to rootTask, WellKnownIds.MAIN_TASK to mainTask)
            return SchedulerState(
                rootListId = WellKnownIds.MAIN_LIST,
                lists = mapOf(WellKnownIds.MAIN_LIST to mainList),
                cells = mapOf(placeholderId to placeholder),
                tasks = tasks,
                titleToTaskIds = SchedulerDomain.buildTitleIndex(tasks),
                expanded = emptySet(),
                selection = SchedulerSelection(),
                histories = SchedulerHistories(),
            )
        }
    }
}
