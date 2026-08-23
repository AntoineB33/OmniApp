package org.example.project.scheduler.state

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.RelativePriorityPinKey
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTreeId
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

/**
 * One **task tree**: a named alternative version of the whole task tree, picked in the selector above the
 * tree. The trees are *live*, not frozen backups — the selected one IS the tree the app shows and edits, and
 * switching away flushes whatever the user did into [tree] before loading the next one (see
 * [SchedulerState.withActiveTaskTreeFlushed]). So the account can hold, say, a "Work" and a "Studies"
 * arrangement of its tasks and move between them without either losing anything.
 *
 * [tree] keeps the tasks' completed-work **records** (unlike [SchedulerState.captureTree], which strips them
 * for the Undo/Redo history): a task that exists only in an inactive tree has nowhere else for its record to
 * live, so stripping here would erase it at the next switch. [expanded] rides along so a tree comes back
 * expanded the way it was left.
 *
 * While a tree is the active one its [tree] is **stale by design** — the live [SchedulerState] fields are the
 * truth for it, and the flush on the way out is what makes the stored copy current again. Nothing ever reads
 * the active entry's [tree] without flushing first.
 */
data class TaskTreeEntry(
    val id: TaskTreeId,
    val title: String,
    val tree: TreeSnapshot,
    val expanded: Set<CellId> = emptySet(),
    /**
     * The instant this tree's priorities are the account's *actual* ones — set in the "All task trees"
     * window and drawn on its timeline. `null` (the default, and what every pre-existing payload decodes
     * to) means the tree is not on the timeline at all and takes no part in the blend.
     *
     * A dated tree is a **keyframe**: between two consecutive dates the scheduler follows a continuous
     * linear interpolation of the two trees' absolute priorities, with a task missing from one side
     * counting as 0% there (see [org.example.project.scheduler.domain.SchedulerDomain.blendedTaskPriorities]).
     * Before the first date and after the last one the nearest keyframe holds unchanged.
     */
    val dateMillis: Long? = null,
)

/**
 * Everything a task-tree switch/creation/rename moves at once — the stored trees, which one is active, the
 * id counter, and the live tree + expansion the selected one projects into [SchedulerState]. Captured
 * before/after by the reducer's `TaskTreeDelta` so Ctrl+Z walks a switch back as one step.
 *
 * Undo can only reach a tree mutation *older* than a switch by undoing that switch first (history is walked
 * in order), which is what keeps a [TreeMutationDelta] recorded under one tree from ever being replayed onto
 * another.
 */
data class TaskTreeStateSnapshot(
    val trees: List<TaskTreeEntry>,
    val activeId: TaskTreeId?,
    val nextCounter: Int,
    val tree: TreeSnapshot,
    val expanded: Set<CellId>,
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
     * The named alternative **task trees** of this account, in creation order (see [TaskTreeEntry]) — the
     * selector above the task tree lists these. Authoritative user data: persisted **and** synced, so every
     * device of the account sees (and works in) the same set. Empty until the user creates the first one,
     * which is why a payload written before the selector existed needs no migration: it simply decodes with
     * no trees and an unnamed live tree.
     */
    val taskTrees: List<TaskTreeEntry> = emptyList(),
    /**
     * The [TaskTreeEntry] the live tree currently *is*, or `null` when the tree has never been named (the
     * default for a fresh/legacy account). Authoritative and synced with [taskTrees]: the live tree fields
     * and this id travel together, so switching tree on one device switches it everywhere.
     */
    val activeTaskTreeId: TaskTreeId? = null,
    /** Monotonic suffix for `tree/{n}` ids; never reused, so a deleted/renamed tree can't collide. */
    val nextTaskTreeCounter: Int = 0,
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
     * PRD §4 **Default sub-tree**: the template grafted under every task the user creates by typing into an
     * empty cell — a tree of titles, each either minting a fresh task id or bound to one existing task (see
     * [org.example.project.scheduler.model.DefaultSubtreeNode]). Authoritative user data (nothing re-derives
     * it): persisted **and** synced, like the alarms. Empty by default, and a payload written before the
     * window existed decodes to empty, which is also what [defaultSubtreeEnabled] = false means in practice.
     */
    val defaultSubtree: List<org.example.project.scheduler.model.DefaultSubtreeNode> = emptyList(),
    /**
     * PRD §4/§7: whether the [defaultSubtree] policy is **currently applied** — the switch left of the
     * "Default sub-tree" button in the lateral menu. Off by default (and for every payload written before the
     * feature existed), so an existing account's cells keep behaving exactly as they did. Turning it off
     * leaves the template stored and every sub-tree it already produced untouched: it only stops *future*
     * cells from being seeded. Persisted + synced; not undoable (like the §7 automatic-schedule switch).
     */
    val defaultSubtreeEnabled: Boolean = false,
    /**
     * PRD §13 **deep copy**: the maximum number of levels a copy takes — **one number for the whole
     * account**, not a per-copy question. The deep-copy window opens on it and writes it back, and §4's
     * Ctrl+C / Ctrl+X then copy by it without asking anything (ADR 0012). Authoritative user-authored
     * setting: persisted + synced, and (like the §7 switch) not an Undo/Redo unit. A payload written before
     * the setting existed decodes to [org.example.project.scheduler.domain.SchedulerDomain.DEEP_COPY_DEFAULT_DEPTH].
     */
    val deepCopyMaxDepth: Int = SchedulerDomain.DEEP_COPY_DEFAULT_DEPTH,
    /**
     * PRD §5 the relative-priority window: the cells whose percentage is **pinned** while a relative
     * priority is retargeted, per (task, ancestor) pair (see [RelativePriorityPinKey]). Authoritative
     * user-authored data — it cannot be recomputed from anything else — so it is persisted **and** synced
     * like the alarms; a payload written before the window existed decodes to an empty map. Toggling a pin
     * changes no priority by itself, so (like the §7 switch) it is not an Undo/Redo unit. A pin naming a
     * cell that no longer exists is simply ignored, so a tree edit never has to prune this.
     */
    val relativePriorityPins: Map<RelativePriorityPinKey, Set<CellId>> = emptyMap(),
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

    /**
     * The live tree **with the tasks' records kept** — what a [TaskTreeEntry] stores. [captureTree] strips
     * records because Undo/Redo must never revert them (PRD §8); a stored task tree is the opposite case:
     * it is the only home a record has while its tree is not the active one.
     */
    fun captureTreeWithRecords(): TreeSnapshot =
        TreeSnapshot(
            cells = cells,
            lists = lists,
            tasks = tasks,
            titleToTaskIds = titleToTaskIds,
            nextTaskCounter = nextTaskCounter,
            nextCellCounter = nextCellCounter,
        )

    /**
     * Projects a stored task tree onto the live state — the inverse of [captureTreeWithRecords]. The id
     * counters take the **max** of both sides: ids are handed out from one counter but live in every tree, so
     * adopting a tree whose counter is lower would re-mint ids another tree already uses.
     */
    fun applyTreeWithRecords(snapshot: TreeSnapshot): SchedulerState =
        copy(
            cells = snapshot.cells,
            lists = snapshot.lists,
            tasks = snapshot.tasks,
            titleToTaskIds = snapshot.titleToTaskIds,
            nextTaskCounter = maxOf(nextTaskCounter, snapshot.nextTaskCounter),
            nextCellCounter = maxOf(nextCellCounter, snapshot.nextCellCounter),
        )

    /** The selected task tree's entry, or null when the live tree has never been named. */
    val activeTaskTree: TaskTreeEntry?
        get() = activeTaskTreeId?.let { id -> taskTrees.firstOrNull { it.id == id } }

    /**
     * Writes the live tree + expansion into the active [TaskTreeEntry], making the stored copy current. Run
     * before anything replaces the live tree (a switch, a create) so the tree being left keeps every edit
     * made in it. A no-op when no tree is selected.
     */
    fun withActiveTaskTreeFlushed(): SchedulerState {
        val activeId = activeTaskTreeId ?: return this
        if (taskTrees.none { it.id == activeId }) return this
        val captured = captureTreeWithRecords()
        return copy(
            taskTrees =
                taskTrees.map { entry ->
                    if (entry.id == activeId) entry.copy(tree = captured, expanded = expanded) else entry
                },
        )
    }

    /**
     * Makes [entry] the live tree: its cells/lists/tasks/records and expansion replace the current ones, and
     * the per-tree UI state that cannot survive the swap (the selection, an in-flight edit session) is
     * cleared — every id it names belongs to the tree being left. Callers must have flushed first.
     */
    fun withTaskTreeLoaded(entry: TaskTreeEntry): SchedulerState =
        applyTreeWithRecords(entry.tree).copy(
            activeTaskTreeId = entry.id,
            expanded = entry.expanded.filter { it in entry.tree.cells }.toSet(),
            selection = SchedulerSelection(),
            editSession = null,
        )

    /** The task-tree fields as one value, for the reducer's undoable `TaskTreeDelta`. */
    fun captureTaskTreeState(): TaskTreeStateSnapshot =
        TaskTreeStateSnapshot(
            trees = taskTrees,
            activeId = activeTaskTreeId,
            nextCounter = nextTaskTreeCounter,
            tree = captureTreeWithRecords(),
            expanded = expanded,
        )

    /**
     * Restores a [captureTaskTreeState] value (a task-tree switch, creation or rename, and the undo/redo of
     * each). The selection and any in-flight edit session are dropped **only when the live tree actually
     * changes** — every id they name would then belong to the tree being left — so creating or renaming a
     * tree, which touches no cell, leaves the user's selection exactly where it was.
     */
    fun applyTaskTreeState(snapshot: TaskTreeStateSnapshot): SchedulerState {
        val treeChanged =
            snapshot.tree.cells != cells || snapshot.tree.tasks != tasks || snapshot.tree.lists != lists
        return applyTreeWithRecords(snapshot.tree).copy(
            taskTrees = snapshot.trees,
            activeTaskTreeId = snapshot.activeId,
            nextTaskTreeCounter = maxOf(nextTaskTreeCounter, snapshot.nextCounter),
            expanded = snapshot.expanded,
            selection = if (treeChanged) SchedulerSelection() else selection,
            editSession = if (treeChanged) null else editSession,
        )
    }

    fun allocateTaskTreeId(): Pair<TaskTreeId, SchedulerState> {
        val id = TaskTreeId("tree/$nextTaskTreeCounter")
        return id to copy(nextTaskTreeCounter = nextTaskTreeCounter + 1)
    }

    fun allocateTaskId(): Pair<TaskId, SchedulerState> {
        val id = TaskId("task/user/$nextTaskCounter")
        return id to copy(nextTaskCounter = nextTaskCounter + 1)
    }

    /**
     * Take [taskId] out of circulation: a task rebuilt under an id it already had (PRD §13 paste, ADR 0012)
     * is not minted by [allocateTaskId], so the counter has to be walked past its suffix by hand or the very
     * next allocation would hand the same id to a different task. A no-op for an id of another shape.
     */
    fun reserveTaskId(taskId: TaskId): SchedulerState {
        val suffix = taskId.value.substringAfterLast('/').toIntOrNull() ?: return this
        return if (suffix < nextTaskCounter) this else copy(nextTaskCounter = suffix + 1)
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
