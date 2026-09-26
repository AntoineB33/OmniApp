package org.example.project.scheduler.state

import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.domain.CalendarElements
import org.example.project.scheduler.domain.CategoryRules
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.TimerDomain
import org.example.project.scheduler.domain.ChronoDomain
import org.example.project.scheduler.domain.RelativePriorityDomain
import org.example.project.scheduler.domain.PeriodDrawing
import org.example.project.scheduler.domain.PeriodKindStyle
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.NewElementDefaults
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SchedulerRunRules
import org.example.project.scheduler.domain.PlanAbandoned
import org.example.project.scheduler.domain.SearchBudget
import org.example.project.scheduler.domain.SearchReport
import org.example.project.scheduler.model.RulePlacement
import org.example.project.scheduler.domain.TaskRelationsDomain
import org.example.project.scheduler.model.Category
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CategoryRule
import org.example.project.scheduler.model.ScheduleCycle
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ForcedTaskStart
import org.example.project.scheduler.model.ForcedTaskSwitch
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.PriorityWeightPin
import org.example.project.scheduler.model.RelativePriorityPinKey
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings
import org.example.project.scheduler.platform.ShortcutBinding
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskRelationKey
import org.example.project.scheduler.model.TaskRelationMark
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.model.TaskTreeId
import org.example.project.time.AppClock
import org.example.project.time.SystemAppClock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

object SchedulerReducer {
    /**
     * PRD §6: source of the wall-clock instant stamped on every committed History Unit. Defaults to the
     * real clock; the app shell can point it at its [AppClock] (e.g. the accelerated [SimAppClock]) so
     * recorded timestamps follow the same time the rest of the app sees.
     */
    var clock: AppClock = SystemAppClock

    /**
     * Whether changes are currently being made under the debug time-simulation clock (diverged from
     * real time). The app shell injects a live predicate (clock-divergence check); every History Unit
     * committed while this returns `true` is flagged [HistoryUnit.debugTainted] and reverted at the
     * next app start. Defaults to `{ false }` (production / tests) so nothing is ever tainted.
     */
    var debugTainting: () -> Boolean = { false }

    /**
     * PRD §6: whether this reducer stamps each History Unit with **the window it was made in**
     * ([HistoryUnit.window]) — which is [SchedulerState.focusedWindow], the one answer to "where is the user"
     * now that every window claims the focus (it used to be a second, Compose-side answer beside the focus).
     *
     * Off by default: a shell with no windows (a headless host, a test) stamps nothing, and such a unit answers
     * only to the History window's "All windows". `App.kt` turns it on.
     */
    var stampsWindow: Boolean = false

    /**
     * `docs/invariants/persistence.md` § *One history, per-device undo*: this device's id, stamped on every unit it
     * commits ([HistoryUnit.deviceId]) — the account's devices share one history, and undo/redo walk only the units
     * of the device they are pressed on. The ViewModel injects the sync device id; the default names one device,
     * which is every test and every build without sync.
     */
    var deviceId: () -> String = { LOCAL_DEVICE_ID }

    /** The device id of a build without sync. */
    const val LOCAL_DEVICE_ID: String = "local"

    /**
     * PRD §6/§9: where a run of the scheduler is recorded ([SchedulerRunEntry] — the
     * [HistorySource.SchedulerEngine] rows of the History window), including the set of rules it ran.
     *
     * An OUTPUT seam, unlike the input seams above, because this is the only place the rules exist: they are
     * built inside [SchedulerDomain.fillSchedule], and only the two plan reductions here call it for the
     * plan the app keeps. The ViewModel owns the (RAM-only, capped) list; the default `{}` means a shell
     * that does not want the diagnostic pays nothing, since `fillSchedule` only renders the rules when a
     * sink asks for them.
     */
    var recordSchedulerRun: (SchedulerRunEntry) -> Unit = {}

    /**
     * `docs/invariants/scheduler.md` § *Progressive Calculation*: what the search of the last plan reduction did
     * ([SearchReport]) and the score it reached — read by the engine to stop granting search time a stage cannot use,
     * and to publish the score beside the rules (§ *One device plans*). An output seam, like [recordSchedulerRun].
     */
    var planSearchSink: (SearchReport, Double?) -> Unit = { _, _ -> }

    /**
     * **Whether the plan reduction of this GENERATION has been superseded** — the user's rule: *"if the
     * scheduler was already running, then it stops abruptly and runs again with the new data"*.
     *
     * A fill is tens of milliseconds to seconds of straight-line CPU, so cancelling the coroutine around it
     * cannot stop it: it would run to the end and publish an answer about data nobody holds any more (and,
     * worse, lose the compare-and-set and start over). The engine stamps every re-plan it asks for with a
     * generation and answers here whether that generation is still the current one; the fill asks at every
     * checkpoint it already has ([SearchBudget.checkAbandoned]) and unwinds where it stands.
     *
     * Generation `0` is *"nobody can supersede this"* — the in-reducer re-plans that answer a press
     * (`ForceTaskStart`, `ForceTaskSwitch`, a sleep edit) must land in the state before the press returns, so
     * they are never abandoned. The default never abandons anything, which is every test and every shell
     * without an engine.
     */
    var planAbandoned: (Long) -> Boolean = { false }

    /**
     * The device's live ongoing/held pause ([SchedulerDomain.liveRestGap]), folded into screen-break
     * placement by every [SchedulerDomain.fillSchedule] call site via
     * [SchedulerDomain.liveRestPeriod] — so the placed screen-break grid moves with a pause the
     * derives haven't banked yet instead of letting the now-line cross a stale slot (spurious cue). The
     * engine injects a live provider over its inactiveSince/activeSince flows; defaults to `{ null }`
     * (production shells without an engine / tests) = no live period. Nothing is stored: the screen-break
     * configuration is never written here, and the pause reaches the bars as the period it is.
     */
    var liveRestGap: () -> SchedulerDomain.LiveRest? = { null }

    /**
     * PRD §9/§12: the stretches the DEVICES say nobody was at a screen for — both calendar layers' OS
     * lock/standby evidence intersected ([SchedulerDomain.observedNoScreenRegions]). An on-screen task banks
     * NO record inside one: §9's rule is that the app must not assume the work happened.
     *
     * Injected rather than read here because `deviceLockedIntervals` is a platform call (a process launch on
     * Windows) and this reducer is pure; the engine owns the scan and its cadence. Defaults to `{ emptyList() }`
     * (no engine / tests) — which is exactly the behaviour before this seam existed.
     *
     * This is the SECOND source of no-screen ranges, not a replacement: the user's hand-drawn "No screen"
     * panels are unioned in at each banking site. They were once the ONLY source, and since the sole producer
     * of such a panel is the §8 contextual-menu action, the rule never fired at all on an account where the
     * user had never drawn one — the app banked 43 h of "work" straight through a machine the OS reported
     * asleep (account 3, 2026-08-24). Do not narrow this back to panels.
     */
    var noScreenEvidence: () -> List<TaskTimeRange> = { emptyList() }

    /**
     * `side-dev/README.md` § *$t_p$ 3 modes*: **which mode the now-line is in** — mode 1 while any device of
     * the account is unlocked, mode 2 otherwise. Read by every [SchedulerDomain.fillSchedule] call site here,
     * and by nothing else: it decides where the three dynamic periods sit relative to the line.
     *
     * Injected for the same reason [liveRestGap] is — the answer comes from the platform lock signal and the
     * account's active sessions, which the engine owns and this reducer must stay pure of
     * ([SchedulerDomain.anyDeviceUnlockedAt] is the whole of the rule). The default is mode 1: a shell with no
     * device signal at all (tests, a headless host) should assume somebody is at a screen, which is the
     * behaviour before this seam existed.
     */
    var tpMode: () -> Int = { DynamicPeriods.MODE_AT_SCREEN }

    /**
     * PRD §9: the instant every refill materializes the work plan out to, given `now` — **$t_{goal}$**
     * ([SchedulerDomain.scheduleHorizonEndMillis] over [SchedulerDomain.scheduleGoalEndMillis]): the latest of
     * the end of the current week, the last displayed time in the calendar, and `now + 10 min`. The engine
     * injects a provider that answers all three (`SchedulerEngine.setCalendarHorizon`, and the week from the
     * zone the app runs in).
     *
     * **The default is the seam UNSET, not the goal**: the rolling floor alone, which is what a reducer nobody
     * has told the goal to can honestly reach for. The week term needs a time zone and would make every fill in
     * a test plan seven days in whatever zone the machine is in — a rule about the product read into a stub.
     * Every host installs the provider at start; tests that want a longer goal set it themselves
     * (`CalendarHorizonFixture`).
     */
    var scheduleHorizonEndMillis: (Long) -> Long = { it + 2 * SchedulerDomain.SCHEDULE_GOAL_FLOOR_MILLIS }

    /**
     * The app's one dispatch point — [reduceIntent] followed by the PRD §5 **category-rule invariant**.
     *
     * A category rule ("the tasks carrying this under that cell are worth 33 % of it") is not a fact stored
     * beside the tree: it is a statement the tree itself has to keep being true of, so it is re-established
     * after every intent rather than at the sites that might disturb it —
     * [org.example.project.scheduler.domain.CategoryRules.settle], which returns the reduced state untouched
     * (the same instance) whenever the account has no rule or every rule is already met, and which refuses
     * the whole intent with a message when the result could not be scaled back onto them. That is what "no
     * contradiction would be allowed" means here: the edit does not half-happen.
     *
     * The two projections below deliberately reduce through [reduceIntent] instead: the Search window's
     * sub-trees and the §4 template are re-rooted trees, so a rule solved against one of them would be solved against
     * the wrong root list — the settle they need is the one this method runs on the folded-back live state.
     *
     * The PRD §7 **last path** ([org.example.project.scheduler.model.Task.lastTreePath]) is kept here for the
     * same reason: whether a task is still in a task tree is a question about the account's own trees, and
     * inside a projection every task would appear to leave.
     */
    fun reduce(state: SchedulerState, intent: SchedulerIntent): SchedulerState =
        SearchDomain.withLastTreePathsStamped(
            state,
            CategoryRules.settle(state, settleDefaultSubtree(reduceIntent(state, intent))),
        )

    /**
     * PRD §4 **Default sub-tree**: every sub-list of the template is titled task cells ending in ONE empty cell,
     * exactly as in the tree — and it is the tree's own post-edit cleanup that keeps it so.
     *
     * The window runs that cleanup at its own edit boundaries, but a row whose switch is off points at a task
     * the **live** tree owns, and the live tree can lose that task without the template being edited at all: a
     * "New task" draft picked in the window and then cancelled in the tree, a task tree switched away. The row
     * then points at nothing and draws as an empty cell in the middle of its list, still carrying the switch
     * (2026-09-17, account 3). So a cell whose task resolves nowhere is emptied — it IS an empty cell, by
     * [SchedulerDomain.isTextuallyEmptyCell] — and [evaluatePostEditCleanup] is run over the projection and
     * folded back like any edit made in the window.
     *
     * The **ending** half is healed here too: a template sub-list whose last row is titled has nowhere left to
     * type, so that sub-list can never be added to again. A build that let a bound row eat its list's
     * placeholder wrote exactly that (see [mirrorsLiveTaskInDefaultSubtree]), and the state it left is on
     * disk, so the placeholder is put back on load as well as after any reduction that loses one.
     *
     * Called after every reduction and on decode. Returns [state] itself unless a row actually dangles or a
     * sub-list actually ends titled, so the per-tick cost is one lookup per template cell and per template
     * list, and a healthy template is never rewritten.
     */
    fun settleDefaultSubtree(state: SchedulerState): SchedulerState {
        val template = state.defaultSubtree.tree
        val dangling =
            template.cells.values.filter { cell ->
                val taskId = cell.taskId ?: return@filter false
                taskId !in template.tasks && taskId !in state.tasks
            }
        val unterminated =
            template.lists.values.filter { list ->
                val last = list.cellIds.lastOrNull() ?: return@filter false
                state.isTitledDefaultSubtreeRow(last)
            }
        if (dangling.isEmpty() && unterminated.isEmpty()) return state
        val projected = state.projectDefaultSubtree()
        var emptied =
            projected.copy(
                cells = projected.cells + dangling.associate { it.id to it.copy(taskId = null) },
            )
        for (list in unterminated) emptied = ensureTrailingPlaceholder(emptied, list.id)
        val folded = state.withDefaultSubtreeCapturedFrom(evaluatePostEditCleanup(emptied))
        // A switch belongs to a row with a task behind it; the emptied cell kept as a list's last one has none.
        val boundCells =
            folded.defaultSubtree.boundCells.filterTo(mutableSetOf()) {
                folded.defaultSubtree.tree.cells[it]?.taskId != null
            }
        return folded.copy(defaultSubtree = folded.defaultSubtree.copy(boundCells = boundCells))
    }

    private fun reduceIntent(state: SchedulerState, intent: SchedulerIntent): SchedulerState {
        return when (intent) {
            is SchedulerIntent.ClickCell -> reduceClick(state, intent)
            is SchedulerIntent.DragSelectCells -> reduceDragSelect(state, intent)
            is SchedulerIntent.MoveSelectedCells -> reduceMoveSelected(state, intent)
            SchedulerIntent.EmptySelectedCells -> reduceEmptySelected(state)
            is SchedulerIntent.ExitEdit -> reduceExitEdit(state, intent.navigation)
            is SchedulerIntent.ToggleExpand -> reduceToggleExpand(state, intent.cellId)
            is SchedulerIntent.CollapseSubtrees -> reduceCollapseSubtrees(state, intent.cellId)
            is SchedulerIntent.RevealCell -> reduceRevealCell(state, intent.cellId, intent.ancestors)
            is SchedulerIntent.ReplaceTaskTitles -> reduceReplaceTaskTitles(state, intent.titles)
            is SchedulerIntent.SetCellTitle -> commitDelta(state, setCellTitleDelta(state, intent.cellId, intent.title))
            is SchedulerIntent.AssignTaskId -> commitDelta(state, assignTaskIdDelta(state, intent.cellId, intent.taskId))
            is SchedulerIntent.SelectTaskTree -> reduceSelectTaskTree(state, intent.id)
            is SchedulerIntent.CreateTaskTree -> reduceCreateTaskTree(state, intent.title)
            is SchedulerIntent.RenameTaskTree -> reduceRenameTaskTree(state, intent.id, intent.title)
            is SchedulerIntent.SetTaskTreeDate -> reduceSetTaskTreeDate(state, intent.id, intent.dateMillis)
            is SchedulerIntent.DeleteTaskTree -> reduceDeleteTaskTree(state, intent.id)
            is SchedulerIntent.SetPriorityWeight ->
                commitDelta(state, priorityTreeDelta(state, "Priority weight") { applySetPriorityWeight(it, intent.cellId, intent.column, intent.value) })
            is SchedulerIntent.SetPriorityWeightTableRow -> {
                val apply = { s: SchedulerState ->
                    applySetPriorityWeightTableRow(s, intent.listId, intent.replacing, intent.taskId)
                }
                // A pick the table already holds (or one the parent sub-tree does not) changes nothing, and
                // an empty history unit is not something Ctrl+Z should have to walk back over.
                if (apply(state) === state) state
                else commitDelta(state, priorityTreeDelta(state, weightTableRowLabel(intent), apply))
            }
            is SchedulerIntent.SetOptionalTaskPathWeight ->
                commitDelta(state, priorityTreeDelta(state, "Optional task priority weight") {
                    RelativePriorityDomain.scaleOptionalTaskPath(
                        it,
                        intent.listId,
                        intent.taskId,
                        intent.column,
                        intent.factor,
                            intent.pinnedCells,
                    )
                })
            is SchedulerIntent.SetRelativePriority ->
                commitDelta(
                    state,
                    priorityTreeDelta(state, "Relative priority") {
                        RelativePriorityDomain.setRelativePriority(
                            it,
                            intent.taskId,
                            intent.relativeTo,
                            intent.value,
                            it.relativePriorityPins[RelativePriorityPinKey(intent.taskId, intent.relativeTo)].orEmpty(),
                        )
                    },
                )
            is SchedulerIntent.ToggleRelativePriorityPin ->
                reduceToggleRelativePriorityPin(state, intent.taskId, intent.relativeTo, intent.cellId)
            is SchedulerIntent.ClearRelativePriorityPins ->
                reduceClearRelativePriorityPins(state, intent.taskId, intent.relativeTo)
            is SchedulerIntent.TogglePriorityWeightPin ->
                reduceTogglePriorityWeightPin(state, intent.listId, intent.cellId, intent.column)
            is SchedulerIntent.RecordTaskRelation ->
                reduceRecordTaskRelation(
                    state,
                    TaskRelationKey(intent.taskId, intent.relativeTo),
                    intent.changed,
                )
            is SchedulerIntent.KeepTaskRelation ->
                reduceMarkTaskRelation(state, TaskRelationKey(intent.taskId, intent.relativeTo)) {
                    it.copy(kept = true, hidden = false)
                }
            is SchedulerIntent.DropTaskRelation ->
                reduceDropTaskRelation(state, TaskRelationKey(intent.taskId, intent.relativeTo))
            is SchedulerIntent.SetPriorityColumnWeight ->
                commitDelta(state, priorityTreeDelta(state, "Column weight") { applySetPriorityColumnWeight(it, intent.listId, intent.column, intent.weight) })
            is SchedulerIntent.SetPriorityDefaultWeight -> {
                val apply = { s: SchedulerState ->
                    applySetPriorityDefaultWeight(s, intent.listId, intent.column, intent.weight)
                }
                // The field commits every keystroke, so a value that is already there must not push an
                // empty unit for Ctrl+Z to walk back over — the same guard the table's other edits use.
                if (apply(state) === state) state
                else commitDelta(state, priorityTreeDelta(state, "Default weight", apply))
            }
            // PRD §5: the three structural column edits move every ROW's value with the column, and a pin
            // is beside a value — so each carries this table's pins the same way (see
            // [remapPriorityWeightPins]). The index each one actually acts on is read from the same helper
            // the apply below reads, so the pins can never land on a different column than the weights did.
            is SchedulerIntent.AddPriorityColumn -> {
                val at = state.lists[intent.listId]?.let { addColumnIndex(it, intent.index) }
                val committed =
                    commitDelta(state, priorityTreeDelta(state, "Add weight column") { applyAddPriorityColumn(it, intent.listId, intent.index) })
                if (at == null) committed
                else remapPriorityWeightPins(committed, intent.listId) { c -> if (c >= at) c + 1 else c }
            }
            // A reset moves no column, so the pins stay where they are: the field the user pinned is still
            // that field, now holding its default.
            is SchedulerIntent.ResetPriorityColumn ->
                commitDelta(state, priorityTreeDelta(state, "Reset weight column") { applyResetPriorityColumn(it, intent.listId, intent.column) })
            is SchedulerIntent.DeletePriorityColumn -> {
                val gone = state.lists[intent.listId]?.let { deleteColumnIndex(it, intent.column) }
                val committed =
                    commitDelta(state, priorityTreeDelta(state, "Delete weight column") { applyDeletePriorityColumn(it, intent.listId, intent.column) })
                if (gone == null) committed
                else remapPriorityWeightPins(committed, intent.listId) { c ->
                    when {
                        c == gone -> null
                        c > gone -> c - 1
                        else -> c
                    }
                }
            }
            is SchedulerIntent.MovePriorityColumn -> {
                val target = state.lists[intent.listId]?.let { moveColumnTarget(it, intent.from, intent.to) }
                val committed =
                    commitDelta(state, priorityTreeDelta(state, "Move weight column") { applyMovePriorityColumn(it, intent.listId, intent.from, intent.to) })
                if (target == null) committed
                else remapPriorityWeightPins(committed, intent.listId) { c ->
                    when {
                        c == intent.from -> target
                        c in (intent.from + 1)..target -> c - 1
                        c in target until intent.from -> c + 1
                        else -> c
                    }
                }
            }
            is SchedulerIntent.RestorePriorityWeights -> {
                val restore = { s: SchedulerState ->
                    applyRestorePriorityWeights(
                        s,
                        intent.listId,
                        intent.weightColumns,
                        intent.cellWeights,
                        intent.defaultWeights,
                    )
                }
                // A cancel that changes nothing (the window was opened and nothing was edited) must not
                // push an empty history unit for Ctrl+Z to walk back over.
                if (restore(state) === state) state
                else commitDelta(state, priorityTreeDelta(state, "Cancel weight edits", restore))
            }
            is SchedulerIntent.SetTaskMinimumTime ->
                commitDelta(state, priorityTreeDelta(state, "Minimum time") { applySetTaskMinimumTime(it, intent.taskId, intent.minutes) })
            is SchedulerIntent.SetTaskResilience -> {
                // Unchanged resilience is a no-op — no empty history unit for a slider put back where it was.
                val task = state.tasks[intent.taskId]
                val kind = PeriodKinds.normalize(intent.kind)
                if (task == null || kind.isEmpty() ||
                    task.resilienceFor(kind) == PeriodKinds.clamp(intent.value)
                ) {
                    state
                } else {
                    commitDelta(
                        state,
                        priorityTreeDelta(state, "Resilience") {
                            applySetTaskResilience(it, intent.taskId, kind, intent.value)
                        },
                    )
                }
            }
            is SchedulerIntent.SetPeriodResilience -> {
                // The period edit window's write: one value, many tasks, ONE history unit — checking a block
                // of tasks and typing a percentage is one gesture. Tasks already at the value are dropped
                // first, so a call that moves nobody records nothing, exactly as the single-task
                // SetTaskResilience does.
                val kind = PeriodKinds.normalize(intent.kind)
                val value = PeriodKinds.clamp(intent.value)
                val targets = periodResilienceTargets(state, intent.taskIds, kind, value)
                if (targets.isEmpty()) {
                    state
                } else {
                    commitDelta(
                        state,
                        priorityTreeDelta(state, "Resilience") { working ->
                            targets.fold(working) { acc, id -> applySetTaskResilience(acc, id, kind, value) }
                        },
                    )
                }
            }
            is SchedulerIntent.CreateCategory -> reduceCreateCategory(state, intent.title)
            is SchedulerIntent.AddTaskCategory -> reduceAddTaskCategory(state, intent.taskId, intent.title)
            is SchedulerIntent.AttachTaskCategory ->
                reduceAttachTaskCategory(state, intent.taskId, intent.categoryId)
            is SchedulerIntent.RemoveTaskCategory ->
                commitDelta(state, priorityTreeDelta(state, "Task category") {
                    applyRemoveTaskCategory(it, intent.taskId, intent.categoryId)
                })
            is SchedulerIntent.RenameCategory -> reduceRenameCategory(state, intent.categoryId, intent.title)
            is SchedulerIntent.DeleteCategory -> reduceDeleteCategory(state, intent.categoryId)
            is SchedulerIntent.SetCategoryRule ->
                reduceSetCategoryRule(state, intent.categoryId, intent.scopeCellId, intent.share)
            is SchedulerIntent.RemoveCategoryRule ->
                reduceRemoveCategoryRule(state, intent.categoryId, intent.scopeCellId)
            SchedulerIntent.DismissCategoryRuleError ->
                if (state.categoryRuleError == null) state else state.copy(categoryRuleError = null)
            is SchedulerIntent.RecordConductedBreak -> reduceRecordConductedBreak(state, intent)
            is SchedulerIntent.AddPeriodKind -> reduceAddPeriodKind(state, intent.kind)
            is SchedulerIntent.RemovePeriodKind -> reduceRemovePeriodKind(state, intent.kind)
            is SchedulerIntent.SetPeriodCompanions -> reduceSetPeriodCompanions(state, intent.kind, intent.companions)
            is SchedulerIntent.SetPeriodDrawing -> reduceSetPeriodDrawing(state, intent.kind, intent.drawing)
            is SchedulerIntent.SetScheduleUnit ->
                commitDelta(state, priorityTreeDelta(state, "Schedule unit") { applySetScheduleUnit(it, intent.taskId, intent.entries) })
            is SchedulerIntent.SetTaskText ->
                commitDelta(state, priorityTreeDelta(state, "Task text") { applySetTaskText(it, intent.taskId, intent.text) })
            is SchedulerIntent.SetChores -> reduceSetChores(state, intent.entries, intent.todayStartMillis, intent.nowMillis)
            is SchedulerIntent.SetReminderChecked -> reduceSetReminderChecked(state, intent.panelId, intent.checked, intent.nowMillis)
            is SchedulerIntent.AddReminder -> reduceAddReminder(state, intent.reminderId, intent.title, intent.atMillis, intent.checked, intent.pinned)
            is SchedulerIntent.SetAlarms -> reduceSetAlarms(state, intent.entries, intent.editKey)
            is SchedulerIntent.SetAlarmEnabled -> reduceSetAlarmEnabled(state, intent.id, intent.enabled)
            is SchedulerIntent.SetTimers -> reduceSetTimers(state, intent.entries, intent.editKey)
            is SchedulerIntent.StartTimer -> reduceTimerTransition(state, intent.id) {
                TimerDomain.started(it, intent.nowMillis)
            }
            is SchedulerIntent.PauseTimer -> reduceTimerTransition(state, intent.id) {
                TimerDomain.paused(it, intent.nowMillis)
            }
            is SchedulerIntent.ResetTimer -> reduceTimerTransition(state, intent.id, TimerDomain::reset)
            is SchedulerIntent.SetTimerCountdownField -> reduceTimerTransition(state, intent.id) {
                TimerDomain.withCountdownField(it, intent.field, intent.value, intent.nowMillis, intent.held)
            }
            is SchedulerIntent.TimerRang -> reduceTimerTransition(state, intent.id) {
                TimerDomain.rang(it, intent.nowMillis)
            }
            is SchedulerIntent.SetChronos -> reduceSetChronos(state, intent.entries, intent.editKey)
            is SchedulerIntent.StartChrono -> reduceChronoTransition(state, intent.id) {
                ChronoDomain.started(it, intent.nowMillis)
            }
            is SchedulerIntent.PauseChrono -> reduceChronoTransition(state, intent.id) {
                ChronoDomain.paused(it, intent.nowMillis)
            }
            is SchedulerIntent.ResetChrono -> reduceChronoTransition(state, intent.id, ChronoDomain::reset)
            is SchedulerIntent.NudgeTimerRemaining -> reduceTimerTransition(state, intent.id) {
                TimerDomain.nudged(it, intent.deltaMillis, intent.nowMillis)
            }
            is SchedulerIntent.SetScreenBreaks ->
                if (state.screenBreaks == intent.screenBreaks) state
                else state.copy(screenBreaks = intent.screenBreaks)
            is SchedulerIntent.RefreshSchedule ->
                // An abandoned fill returns THE STATE IT WAS GIVEN — the same instance, so
                // [TaskSchedulerViewModel.dispatch] publishes nothing, saves nothing and does not retry.
                abandonable(state) {
                    reduceRefreshSchedule(
                        state, intent.nowMillis, intent.horizonCapMillis, intent.searchMillis, intent.seeds,
                        intent.generation,
                    )
                }
            is SchedulerIntent.ExtendSchedule ->
                abandonable(state) {
                    reduceExtendSchedule(
                        state, intent.nowMillis, intent.horizonCapMillis, intent.searchMillis, intent.generation,
                    )
                }
            is SchedulerIntent.AdoptScheduleRules -> reduceAdoptScheduleRules(state, intent)
            is SchedulerIntent.AdvanceSchedule ->
                commitRecordChanges(state, advanceSchedule(state, intent.nowMillis, noScreenEvidence()))
            is SchedulerIntent.ForceTaskSwitch -> reduceForceTaskSwitch(state, intent.nowMillis)
            is SchedulerIntent.ForceTaskStart -> reduceForceTaskStart(state, intent.taskId)
            is SchedulerIntent.SetAutomaticSchedule ->
                if (state.automaticSchedule == intent.enabled) state
                else state.copy(automaticSchedule = intent.enabled)
            is SchedulerIntent.SetShowScreenBreaks ->
                if (state.showScreenBreaks == intent.show) state
                else state.copy(showScreenBreaks = intent.show)
            is SchedulerIntent.SetShowReminders ->
                if (state.showReminders == intent.show) state
                else state.copy(showReminders = intent.show)
            is SchedulerIntent.SetNotificationVoice ->
                if (state.notificationVoiceEnabled == intent.enabled) state
                else state.copy(notificationVoiceEnabled = intent.enabled)
            is SchedulerIntent.SetNotificationsEnabled ->
                if (state.notificationsEnabled == intent.enabled) state
                else state.copy(notificationsEnabled = intent.enabled)
            is SchedulerIntent.InDefaultSubtree -> reduceInDefaultSubtree(state, intent.inner)
            is SchedulerIntent.InSearchSubtree -> reduceInSearchSubtree(state, intent.inner, intent.listId, intent.readOnly)
            is SchedulerIntent.RenameTask -> reduceRenameTask(state, intent.taskId, intent.title)
            is SchedulerIntent.SetDefaultSubtreeCellBound ->
                reduceSetDefaultSubtreeCellBound(state, intent.cellId, intent.bound)
            is SchedulerIntent.SetDefaultSubtreeEnabled ->
                if (state.defaultSubtreeEnabled == intent.enabled) state
                else state.copy(defaultSubtreeEnabled = intent.enabled)
            is SchedulerIntent.AddDefaultSubtree -> reduceAddDefaultSubtree(state, intent.cellIds)
            is SchedulerIntent.SetSleepSchedule -> reduceSetSleepSchedule(state, intent.sleep, intent.todayEpochDay)
            is SchedulerIntent.SetSleepMode -> reduceSetSleepMode(state, intent.sleepingUntilMillis)
            is SchedulerIntent.MaterializePastSleep -> materializePastSleep(state, intent.ranges)
            is SchedulerIntent.ReportDeviceSleep ->
                commitRecordChanges(
                    state,
                    reduceReportDeviceSleep(
                        state,
                        intent.sleepStartEpochMillis,
                        intent.sleepEndEpochMillis,
                        noScreenEvidence(),
                    ),
                )
            is SchedulerIntent.AddTaskPanel -> reduceAddTaskPanel(state, intent)
            is SchedulerIntent.AddCalendarElements -> reduceAddCalendarElements(state, intent.drafts)
            is SchedulerIntent.AddRestrictivePeriod -> reduceAddRestrictivePeriod(state, intent)
            is SchedulerIntent.UpdateTaskPanel -> reduceUpdateTaskPanel(state, intent)
            is SchedulerIntent.PinRecordAsPanel -> reducePinRecord(state, intent)
            is SchedulerIntent.RemoveTaskPanel -> reduceRemoveTaskPanel(state, intent.id)
            is SchedulerIntent.SetPanelWeights -> reduceSetPanelWeights(state, intent)
            is SchedulerIntent.RemoveTaskPanels -> reduceRemoveTaskPanels(state, intent.ids)
            is SchedulerIntent.ReplaceTaskPanels -> reduceReplaceTaskPanels(state, intent)
            is SchedulerIntent.RemoveRecordPeriod -> reduceRemoveRecordPeriod(state, intent)
            is SchedulerIntent.StripNoScreenRecords -> reduceStripNoScreenRecords(state, intent.ranges)
            is SchedulerIntent.FocusWindow -> reduceFocusWindow(state, intent.window, intent.instance)
            is SchedulerIntent.SetCalendarFocus ->
                reduceFocusWindow(state, if (intent.focused) HistoryWindow.Calendar else HistoryWindow.Tree, "")
            is SchedulerIntent.SelectInWindow -> reduceSelectInWindow(state, intent)
            SchedulerIntent.ToggleCalendarOverlap -> state.copy(overlapArmed = !state.overlapArmed)
            is SchedulerIntent.BeginEdit -> reduceBeginEdit(state, intent)
            is SchedulerIntent.UpdateEditText -> reduceUpdateEditText(state, intent.text)
            is SchedulerIntent.SetEditMode -> reduceSetEditMode(state, intent.mode)
            is SchedulerIntent.PickTaskFromMenu -> reducePickTaskFromMenu(state, intent.taskId)
            SchedulerIntent.SelectCreateAssignTask -> reduceSelectCreateAssignTask(state)
            is SchedulerIntent.PickTitleSuggestion -> reducePickTitleSuggestion(state, intent.title)
            SchedulerIntent.CancelEdit -> reduceCancelEdit(state)
            is SchedulerIntent.NavigateSelection -> reduceNavigateSelection(state, intent.direction, intent.shift)
            is SchedulerIntent.CycleMainSelection -> reduceCycleMainSelection(state, intent.forward)
            SchedulerIntent.SelectFirstChild -> reduceSelectFirstChild(state)
            SchedulerIntent.SelectAllVisibleCells -> reduceSelectAllVisible(state)
            SchedulerIntent.CopySelection -> reduceCopySelection(state)
            SchedulerIntent.CutSelection -> reduceCutSelection(state)
            is SchedulerIntent.SetDeepCopyMaxDepth -> {
                val depth = intent.depth.coerceIn(SchedulerDomain.DEEP_COPY_DEPTH_RANGE)
                if (state.deepCopyMaxDepth == depth) state else state.copy(deepCopyMaxDepth = depth)
            }
            is SchedulerIntent.SetNewAlarmDefaults ->
                NewElementDefaults.alarmDefaults(intent.defaults).let {
                    if (it == state.newAlarmDefaults) state else state.copy(newAlarmDefaults = it)
                }
            is SchedulerIntent.SetNewTimerDefaults ->
                NewElementDefaults.timerDefaults(intent.defaults).let {
                    if (it == state.newTimerDefaults) state else state.copy(newTimerDefaults = it)
                }
            is SchedulerIntent.SetNewReminderDefaults ->
                NewElementDefaults.reminderDefaults(intent.defaults).let {
                    if (it == state.newReminderDefaults) state else state.copy(newReminderDefaults = it)
                }
            is SchedulerIntent.SetDeepCopyUnlimited ->
                if (state.deepCopyUnlimited == intent.unlimited) state
                else state.copy(deepCopyUnlimited = intent.unlimited)
            is SchedulerIntent.SetGlobalShortcutBinding ->
                reduceSetGlobalShortcutBinding(state, intent.shortcut, intent.binding)
            is SchedulerIntent.SetCopyOptions -> {
                val o = intent.options
                if (state.copyIncludeIds == o.includeIds &&
                    state.copyPriorityTables == o.priorityTables &&
                    state.copyIncludeText == o.includeText &&
                    state.copyIncludePriorityPercentages == o.includePriorityPercentages &&
                    state.copyIncludeMinimumTime == o.includeMinimumTime &&
                    state.copyExcludeTitle == o.excludeTitle
                ) {
                    state
                } else {
                    state.copy(
                        copyIncludeIds = o.includeIds,
                        copyPriorityTables = o.priorityTables,
                        copyIncludeText = o.includeText,
                        copyIncludePriorityPercentages = o.includePriorityPercentages,
                        copyIncludeMinimumTime = o.includeMinimumTime,
                        copyExcludeTitle = o.excludeTitle,
                    )
                }
            }
            is SchedulerIntent.PasteTree -> reducePasteTree(state, intent.text)
            is SchedulerIntent.RecordNotification -> reduceRecordNotification(state, intent)
            is SchedulerIntent.RecordSupabaseUsage -> reduceRecordSupabaseUsage(state, intent)
            is SchedulerIntent.MergePeerHistory -> reduceMergePeerHistory(state, intent)
            SchedulerIntent.Undo ->
                if (state.editSession != null) undo(state, HistoryCategory.Edit) else undoIn(state, changesOf(state))
            SchedulerIntent.Redo ->
                if (state.editSession != null) redo(state, HistoryCategory.Edit) else redoIn(state, changesOf(state))
            SchedulerIntent.UndoSelection -> undoIn(state, selectionsOf(state))
            SchedulerIntent.RedoSelection -> redoIn(state, selectionsOf(state))
            SchedulerIntent.UndoPosition -> undoIn(state, POSITIONS)
            SchedulerIntent.RedoPosition -> redoIn(state, POSITIONS)
        }
    }

    /**
     * Whether the fill stamped [generation] has been superseded. Generation **0** never is: it is what the
     * in-reducer re-plans answering a press carry, and a press must be in the state before it returns — so
     * that rule is held here, where every fill passes, rather than in whichever seam was installed.
     */
    private fun abandoned(generation: Long): Boolean = generation != 0L && planAbandoned(generation)

    /**
     * Run [plan] unless it is abandoned part-way ([planAbandoned]), in which case [state] is handed back
     * untouched: a superseded fill leaves no trace at all, not even the records its advance had banked (the
     * next tick banks them again).
     */
    private inline fun abandonable(state: SchedulerState, plan: () -> SchedulerState): SchedulerState =
        try {
            plan()
        } catch (abandoned: PlanAbandoned) {
            state
        }

    /**
     * PRD §5: what one history chord walks — the categories it may take a unit from, and which of their units
     * are its own. `HistoryCategory.chords` is the statement of the same mapping the History window filters on.
     */
    private class HistoryWalk(
        val categories: List<HistoryCategory>,
        val accepts: (HistoryCategory, HistoryUnit) -> Boolean = { _, _ -> true },
    )

    /**
     * `Ctrl+Z` outside an Edit Mode session: the **changes made in the focused window** — the units of
     * [HistoryCategory.Main] and [HistoryCategory.Calendar] stamped with it. A unit stamped with no window was
     * written before units knew theirs (or by a headless host): a calendar unit is the calendar's, any other the
     * tree's, which is where each was undone from then.
     */
    private fun changesOf(state: SchedulerState): HistoryWalk =
        HistoryWalk(listOf(HistoryCategory.Main, HistoryCategory.Calendar)) { category, unit ->
            (unit.window ?: if (category == HistoryCategory.Calendar) HistoryWindow.Calendar else HistoryWindow.Tree) ==
                state.focusedWindow
        }

    /**
     * `Alt+arrows`: the **selections of the focused window**. Which window a selection belongs to is read off
     * the delta ([positionOf]), never off the stamp: "go to task tree" pressed in the Search window selects a
     * cell of the TREE, and `Alt+←` in Search must not move the tree.
     */
    private fun selectionsOf(state: SchedulerState): HistoryWalk =
        HistoryWalk(listOf(HistoryCategory.Selection)) { _, unit ->
            val (window, instance) = positionOf(unit.delta)
            window == state.focusedWindow && (instance == null || instance == state.focusedInstance)
        }

    /** `Shift+Alt+arrows`: every selection of every window and every move of the focus, in the order made. */
    private val POSITIONS = HistoryWalk(listOf(HistoryCategory.Selection, HistoryCategory.WindowNav))

    /**
     * The window (and, for a per-copy selection, the copy) a selection unit is about. The tree's own selection
     * delta is the tree's; the others name their window.
     */
    private fun positionOf(delta: Delta): Pair<HistoryWindow?, String?> =
        when (delta) {
            is SetSelectionDelta -> HistoryWindow.Tree to null
            is ViewSelectionDelta -> delta.window to null
            is WindowSelectionDelta -> delta.window to delta.instance
            is FocusDelta -> delta.after to delta.afterInstance
            else -> null to null
        }

    /**
     * Append a posted notification to [SchedulerState.notificationLog], a **rolling tail** that keeps only the
     * most recent [SchedulerState.MAX_NOTIFICATION_LOG] entries (drops the oldest), exactly like
     * [reduceRecordSupabaseUsage] beside it.
     *
     * It was a frozen first-N audit until 2026-09-11 — once 1000 entries were in, this returned the same state
     * instance and every notification after was dropped. That is not a cap, it is an expiry date: the column
     * saturated on the release account at 2026-09-07 17:25:37 and would never have listed another cue, while
     * its surviving 1000 rows aged into a two-month-stale window nobody diagnoses against. The whole point of
     * the column is telling whether the cue the user just missed was one the app decided to send, and only the
     * RECENT end of the log can answer that.
     */
    private fun reduceRecordNotification(
        state: SchedulerState,
        intent: SchedulerIntent.RecordNotification,
    ): SchedulerState {
        val appended = state.notificationLog +
            NotificationLogEntry(intent.timeMillis, intent.title, intent.message, intent.cue)
        val capped =
            if (appended.size > SchedulerState.MAX_NOTIFICATION_LOG) {
                appended.takeLast(SchedulerState.MAX_NOTIFICATION_LOG)
            } else {
                appended
            }
        return state.copy(notificationLog = capped)
    }

    /**
     * Append one Supabase call to [SchedulerState.supabaseUsageLog], a **rolling tail** that keeps only the most
     * recent [SchedulerState.MAX_SUPABASE_USAGE_LOG] entries (drops the oldest) — the same shape as
     * [reduceRecordNotification] beside it, and a per-device, non-syncing diagnostic just like it
     * (see [SchedulerIntent.RecordSupabaseUsage]).
     */
    private fun reduceRecordSupabaseUsage(
        state: SchedulerState,
        intent: SchedulerIntent.RecordSupabaseUsage,
    ): SchedulerState {
        val appended = state.supabaseUsageLog +
            SupabaseUsageEntry(
                timeMillis = intent.timeMillis,
                resource = intent.resource,
                operation = intent.operation,
                requestBytes = intent.requestBytes,
                responseBytes = intent.responseBytes,
                status = intent.status,
            )
        val capped =
            if (appended.size > SchedulerState.MAX_SUPABASE_USAGE_LOG) {
                appended.takeLast(SchedulerState.MAX_SUPABASE_USAGE_LOG)
            } else {
                appended
            }
        return state.copy(supabaseUsageLog = capped)
    }

    /**
     * PRD §4: open a cell's Edit Mode — or, when one is already open on that very cell, take the keystroke
     * that asked for it into the session that is already running.
     *
     * **The letters that open a session and the ones that follow it RACE, and this is where the race is
     * settled.** `onPreviewKeyEvent` reads the state of the last *composition*, and Compose delivers key
     * events without recomposing between them, so on a frame the app owes elsewhere (a fill, a big
     * derivation) the second letter is typed against a snapshot that still says "no session open" and
     * arrives here as a second [SchedulerIntent.BeginEdit] with its own `initialText`. Starting a fresh
     * session for it threw away everything typed before it — which is exactly the "the first letters are
     * missed" a slow frame produced. A live session on the same cell therefore **absorbs** the keystroke:
     * it is [SchedulerIntent.UpdateEditText] arriving by another route, so it is reduced as one, and the
     * letters land in the order they were typed however late the frame is.
     *
     * The same reasoning makes a re-entry with no text (a second Enter / double-click delivered against the
     * stale snapshot) a **no-op** rather than a restart: restarting recaptures `treeBefore`, which is the
     * baseline Escape and Rename revert to — a stale duplicate would quietly make the half-typed title the
     * thing that "was there before".
     */
    private fun reduceBeginEdit(state: SchedulerState, intent: SchedulerIntent.BeginEdit): SchedulerState {
        state.editSession?.takeIf { it.cellId == intent.cellId }?.let { live ->
            val typed = intent.initialText ?: return state
            return reduceUpdateEditText(state, live.draftText + typed)
        }
        if (!SchedulerDomain.isSelectableCell(state, intent.cellId)) return state
        val cell = state.cells[intent.cellId] ?: return state
        val currentTitle = cell.taskId?.let { state.tasks[it]?.title }.orEmpty()
        val draft = intent.initialText ?: currentTitle
        val typingToEdit = intent.initialText != null
        val selection = selectionFor(state, main = intent.cellId)
        val withSession =
            state.copy(
                editSession =
                    SchedulerEditSession(
                        cellId = intent.cellId,
                        renderVia = selection.renderVia,
                        draftText = draft,
                        // PRD §4 default: Change Task. A session opened straight in Rename mode needs the
                        // baseline that a switch INTO Rename would have captured, or leaving it would have
                        // nothing to revert to.
                        mode = intent.mode ?: CellEditMode.ChangeTask,
                        renameTreeBefore =
                            if (intent.mode == CellEditMode.Rename) state.captureTree() else null,
                        // PRD §4 default selection: typing into a cell defaults the id menu to the first
                        // eligible existing task whose title matches (reuse it), or "New task" when none
                        // matches; re-entering an assigned cell keeps its current task selected.
                        selectedAssignTaskId =
                            if (typingToEdit) {
                                SchedulerDomain.eligibleAssignTaskIds(state, intent.cellId, draft).firstOrNull()
                            } else {
                                cell.taskId
                            },
                        newTaskDraftId = null,
                        treeBefore = state.captureTree(),
                    ),
                selection = selection,
            )
        return if (typingToEdit) {
            commitEditText(withSession, draft)
        } else {
            withSession
        }
    }

    private fun reduceUpdateEditText(state: SchedulerState, text: String): SchedulerState {
        val session = state.editSession ?: return state
        if (text == session.draftText) return state
        // PRD §4 default selection: while typing in Change Task mode the id menu re-resolves to the first
        // eligible existing task whose title matches the text (reuse it), or "New task" when none matches.
        // Switching to reuse drops the in-progress "New task" draft id; a run of non-matching keystrokes
        // keeps the same draft so a fresh task isn't spun up on every keystroke.
        val firstEligible =
            if (session.mode == CellEditMode.ChangeTask) {
                SchedulerDomain.eligibleAssignTaskIds(state, session.cellId, text).firstOrNull()
            } else {
                null
            }
        val reuseExisting = session.mode == CellEditMode.ChangeTask && firstEligible != null
        val withDraft =
            state.copy(
                editSession =
                    session.copy(
                        draftText = text,
                        selectedAssignTaskId =
                            if (session.mode == CellEditMode.ChangeTask) firstEligible else session.selectedAssignTaskId,
                        newTaskDraftId =
                            if (reuseExisting) null else session.newTaskDraftId,
                    ),
            )
        val committed = commitEditText(withDraft, text)
        // Typing switched to reusing an existing task: drop the now cell-less "New task" draft (and any
        // panels a scheduling tick gave it), mirroring PickTaskFromMenu (PRD §4).
        return if (reuseExisting) discardDraftTask(committed, session.newTaskDraftId) else committed
    }

    private fun commitEditText(base: SchedulerState, text: String): SchedulerState {
        val session = base.editSession ?: return base
        val applied = applyEditText(base, session, text)
        val withSession =
            applied.copy(
                editSession =
                    applied.editSession?.copy(draftText = text)
                        ?: session.copy(draftText = text),
            )
        // The delta is read off the edit ALREADY APPLIED, never by applying it a second time: this runs on
        // every keystroke and [applyEditText] is the whole naming path — the id resolution, the sub-list
        // minting, the weight seeding — so asking for it twice doubled what a letter costs on the frame it
        // lands in ([editTextDelta] is the same two readings, for the callers that have no applied state).
        val delta =
            TreeMutationDelta(
                before = base.captureTree(),
                after = applied.captureTree(),
                label = "Edit text",
            )
        return commitDelta(withSession, delta, HistoryCategory.Edit)
    }

    private fun reduceSetEditMode(state: SchedulerState, mode: CellEditMode): SchedulerState {
        val session = state.editSession ?: return state
        if (session.mode == mode) return state
        return when {
            session.mode == CellEditMode.Rename && mode == CellEditMode.ChangeTask -> {
                val baseline = session.renameTreeBefore ?: session.treeBefore
                state.applyTree(baseline).copy(
                    editSession =
                        session.copy(
                            mode = CellEditMode.ChangeTask,
                            renameTreeBefore = null,
                        ),
                )
            }
            mode == CellEditMode.Rename ->
                state.copy(
                    editSession =
                        session.copy(
                            mode = CellEditMode.Rename,
                            renameTreeBefore = state.captureTree(),
                        ),
                )
            else -> state.copy(editSession = session.copy(mode = mode))
        }
    }

    private fun reducePickTaskFromMenu(state: SchedulerState, taskId: TaskId): SchedulerState {
        val session = state.editSession ?: return state
        val cellId = session.cellId
        if (!SchedulerDomain.canAssignTaskId(state, cellId, taskId)) return state
        val title = state.tasks[taskId]?.title.orEmpty()
        val assigned = commitDelta(state, assignTaskIdDelta(state, cellId, taskId), HistoryCategory.Edit)
        val withDraft =
            assigned.copy(
                editSession =
                    session.copy(
                        draftText = title,
                        selectedAssignTaskId = taskId,
                        newTaskDraftId = null,
                    ),
            )
        val committed =
            if (title != session.draftText) {
                commitDelta(withDraft, editTextDelta(withDraft, title), HistoryCategory.Edit)
            } else {
                withDraft
            }
        // Reusing an existing task abandons the "New task" draft created while typing. If a scheduling
        // tick had meanwhile given that draft a calendar panel, [purgeOrphanTasks] would keep the now
        // cell-less draft alive through it, leaving a stray task with the same title (PRD §4: picking a
        // suggestion must not create a new task id). Discard the draft and its transient panels.
        return discardDraftTask(committed, session.newTaskDraftId)
    }

    /**
     * Remove an abandoned "New task" draft [draftId] (an editing artifact) once it is no longer pointed at
     * by any cell: drop the auto panels a scheduling tick may have given it, then purge the task. A no-op
     * when [draftId] is null or the task is still referenced by a cell.
     */
    private fun discardDraftTask(state: SchedulerState, draftId: TaskId?): SchedulerState {
        if (draftId == null) return state
        if (state.cells.values.any { it.taskId == draftId }) return state
        val panels = state.panels.filterNot { it.taskId == draftId }
        val pruned = if (panels.size != state.panels.size) state.copy(panels = panels) else state
        return SchedulerDomain.purgeOrphanTasks(pruned)
    }

    private fun reduceSelectCreateAssignTask(state: SchedulerState): SchedulerState {
        val session = state.editSession ?: return state
        if (session.mode != CellEditMode.ChangeTask || session.selectedAssignTaskId == null) return state
        val (newTaskId, allocated) = state.allocateTaskId()
        val withSession =
            allocated.copy(
                editSession =
                    session.copy(
                        selectedAssignTaskId = null,
                        newTaskDraftId = newTaskId,
                    ),
            )
        return commitEditText(withSession, session.draftText)
    }

    private fun reducePickTitleSuggestion(state: SchedulerState, title: String): SchedulerState {
        return reduceUpdateEditText(state, title)
    }

    private fun reduceCancelEdit(state: SchedulerState): SchedulerState {
        val session = state.editSession ?: return state
        // A canceled edit reverts to the pre-session tree and leaves no trace: the ephemeral Edit
        // Mode history is discarded and no "rest" unit is recorded (PRD §4 Cancel, §5 categories).
        return state.applyTree(session.treeBefore).copy(
            editSession = null,
            histories = state.histories.copy(edit = SchedulerHistory()),
        )
    }

    private fun reduceNavigateSelection(
        state: SchedulerState,
        direction: SelectionNavigate,
        shift: Boolean,
    ): SchedulerState {
        if (state.editSession != null) return state
        val main = state.selection.main ?: return state
        val delta = if (direction == SelectionNavigate.Next) 1 else -1
        // Resolve the neighbor by the selected *occurrence* (main + renderVia), so a mirrored
        // cell moves relative to the row actually displayed beneath it, not its first copy.
        val neighborOccurrence =
            SchedulerDomain.neighborSelectableOccurrence(state, main, state.selection.renderVia, delta)
                ?: return state
        val neighbor = neighborOccurrence.cellId
        if (!shift) {
            return commitDelta(
                state,
                SetSelectionDelta(
                    before = state.selection,
                    // Pin the new main to the exact occurrence we stepped onto.
                    after =
                        SchedulerSelection(
                            main = neighbor,
                            renderVia = neighborOccurrence.renderVia,
                        ),
                ),
                HistoryCategory.Selection,
            )
        }

        // PRD §3 Shift+Direction: extend a sequential range; reset disjoint Ctrl multi-select.
        var base = state.selection
        if (base.selected.size > 1 && base.rangeAnchor == null) {
            base = base.copy(selected = emptySet())
        }
        val anchor = base.rangeAnchor ?: main
        val range =
            SchedulerDomain.visibleSelectionRange(
                SchedulerDomain.selectableVisibleOrder(state),
                anchor,
                neighbor,
            )
        return commitDelta(
            state,
            SetSelectionDelta(
                before = state.selection,
                after =
                    selectionFor(
                        state,
                        main = neighbor,
                        selected = range,
                        rangeAnchor = anchor,
                        explicitVia = neighborOccurrence.renderVia,
                        prior = base,
                    ),
            ),
            HistoryCategory.Selection,
        )
    }

    private fun reduceCycleMainSelection(state: SchedulerState, forward: Boolean): SchedulerState {
        if (state.editSession != null) return state
        val selected = state.selection.selected
        if (selected.size <= 1) return state
        val main = state.selection.main ?: return state
        val ordered =
            SchedulerDomain.selectableVisibleOrder(state).filter { it in selected }
        if (ordered.isEmpty()) return state
        val currentIndex = ordered.indexOf(main).let { if (it < 0) 0 else it }
        val nextIndex =
            if (forward) {
                (currentIndex + 1) % ordered.size
            } else {
                (currentIndex - 1 + ordered.size) % ordered.size
            }
        return commitDelta(
            state,
            SetSelectionDelta(
                before = state.selection,
                after =
                    state.selection.copy(
                        main = ordered[nextIndex],
                        renderVia =
                            SchedulerDomain.resolveSelectionRenderVia(
                                state,
                                ordered[nextIndex],
                                prior = state.selection,
                            ),
                    ),
            ),
            HistoryCategory.Selection,
        )
    }

    private fun reduceSelectFirstChild(state: SchedulerState): SchedulerState {
        if (state.editSession != null) return state
        val main = state.selection.main ?: return state
        if (!SchedulerDomain.isSelectableCell(state, main)) return state
        val cell = state.cells[main] ?: return state
        val taskId = cell.taskId
        val childListId = taskId?.let { state.tasks[it]?.childListId }
        if (childListId == null) {
            return reduceNavigateSelection(state, SelectionNavigate.Next, shift = false)
        }
        var next = state
        if (main !in next.expanded) {
            next = commitDelta(next, ToggleExpandDelta(main))
        }
        val child = SchedulerDomain.firstSelectableChild(next, main) ?: return next
        return commitDelta(
            next,
            SetSelectionDelta(
                before = next.selection,
                after = selectionFor(next, main = child, explicitVia = main),
            ),
            HistoryCategory.Selection,
        )
    }

    /** PRD §3: select every selectable visible cell, anchored on the first with the main on the last. */
    private fun reduceSelectAllVisible(state: SchedulerState): SchedulerState {
        if (state.editSession != null) return state
        val order = SchedulerDomain.selectableVisibleOrder(state)
        if (order.isEmpty()) return state
        val after =
            SchedulerSelection(
                main = order.last(),
                selected = order.toSet(),
                rangeAnchor = order.first(),
            )
        if (after == state.selection) return state
        return commitDelta(
            state,
            SetSelectionDelta(before = state.selection, after = after),
            HistoryCategory.Selection,
        )
    }

    /**
     * PRD §14: store the reminders list and regenerate its calendar tags (anchored at [todayStartMillis]),
     * preserving each reminder's checked state. Not routed through history (the reminders list itself is
     * session/persisted state, like the §7 switch and the §9 advance tick), so editing the list is not
     * undoable — only checking a reminder off is (see [reduceSetReminderChecked]).
     */
    private fun reduceSetChores(
        state: SchedulerState,
        entries: List<org.example.project.scheduler.model.ChoreEntry>,
        todayStartMillis: Long,
        nowMillis: Long,
    ): SchedulerState {
        // PRD §14: every reminder carries a stable id; fill any blank (legacy/just-added) row here.
        val withIds = SchedulerDomain.assignReminderIds(entries)
        val panels = SchedulerDomain.regenerateChorePanels(
            state.panels, withIds, todayStartMillis, nowMillis = nowMillis,
        )
        if (state.chores == withIds && state.panels == panels) return state
        return state.copy(chores = withIds, panels = panels)
    }

    /**
     * PRD §18 Alarms: store the alarm list (minting an id for any blank row). Authoritative state, and — the
     * whole list being the user's own authorship — recorded as a **Main History Unit**, so an added row, a
     * row struck off with the bin, and every settings change on a row are Ctrl+Z-undoable and show in the
     * History window. It changes the schedule of nothing, so no panels are regenerated.
     *
     * [editKey] is the field-focus session a live text edit belongs to: the window pushes the whole list on
     * every keystroke, so without it a five-letter label would be five units to walk back (see
     * [Delta.coalesceKey]). Null for a structural change — an added or removed row, a switch, a weekday.
     */
    private fun reduceSetAlarms(
        state: SchedulerState,
        entries: List<org.example.project.scheduler.model.AlarmEntry>,
        editKey: String? = null,
    ): SchedulerState {
        val withIds = AlarmDomain.assignAlarmIds(entries)
        if (state.alarms == withIds) return state
        return commitDelta(state, AlarmsDelta(state.alarms, withIds, editKey))
    }

    /**
     * PRD §18 Alarms: the engine disarming a **one-off** alarm that has rung.
     *
     * No History Unit, deliberately: this is authored by the ring sweep, not by the user, and a unit here
     * would sit on top of the Main stack so the next Ctrl+Z would un-ring the alarm instead of undoing what
     * the user last did. The row's own on/off switch is a setting and travels through [reduceSetAlarms],
     * which does record one.
     */
    private fun reduceSetAlarmEnabled(state: SchedulerState, id: String, enabled: Boolean): SchedulerState {
        val alarm = state.alarms.firstOrNull { it.id == id } ?: return state
        if (alarm.enabled == enabled) return state
        return state.copy(alarms = state.alarms.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /**
     * PRD §18 Timers: store the timer list (minting an id for any blank row, and healing the run fields into
     * the one shape they are allowed to be in). Authoritative state, and recorded as a **Main History Unit**
     * on the same rule as the alarms above — the bin button is why: a timer struck off by mistake comes back
     * with Ctrl+Z.
     *
     * The rows carry their own run state, so a live text edit round-trips it untouched; the transitions below
     * are what actually move it — and they record nothing, because an absolute due instant undone later is
     * not the instant that was recorded (see [SchedulerIntent.StartTimer]).
     */
    private fun reduceSetTimers(
        state: SchedulerState,
        entries: List<org.example.project.scheduler.model.TimerEntry>,
        editKey: String? = null,
    ): SchedulerState {
        val withIds = TimerDomain.assignTimerIds(entries).map(TimerDomain::healed)
        if (state.timers == withIds) return state
        return commitDelta(state, TimersDelta(state.timers, withIds, editKey))
    }

    /**
     * PRD §18 Timers: apply one of the run-state writes ([TimerDomain.started] / [TimerDomain.paused] /
     * [TimerDomain.reset] / [TimerDomain.withCountdownField] / [TimerDomain.nudged]) to the timer [id]. One helper for all of them because
     * each is already a pure function on the entry — the reducer only has to find the row and skip the write
     * when nothing moved. A no-op when the id is unknown or the transition changed nothing.
     */
    private fun reduceTimerTransition(
        state: SchedulerState,
        id: String,
        transition: (org.example.project.scheduler.model.TimerEntry) -> org.example.project.scheduler.model.TimerEntry,
    ): SchedulerState {
        val timer = state.timers.firstOrNull { it.id == id } ?: return state
        val next = transition(timer)
        if (next == timer) return state
        return state.copy(timers = state.timers.map { if (it.id == id) next else it })
    }

    /** PRD §18 Chronos: store the chrono list — [reduceSetTimers]' rule, a Main History Unit. */
    private fun reduceSetChronos(
        state: SchedulerState,
        entries: List<org.example.project.scheduler.model.ChronoEntry>,
        editKey: String? = null,
    ): SchedulerState {
        val withIds = ChronoDomain.assignChronoIds(entries).map(ChronoDomain::healed)
        if (state.chronos == withIds) return state
        return commitDelta(state, ChronosDelta(state.chronos, withIds, editKey))
    }

    /** PRD §18 Chronos: one run-state write on the chrono [id] — [reduceTimerTransition]'s rule, no unit. */
    private fun reduceChronoTransition(
        state: SchedulerState,
        id: String,
        transition: (org.example.project.scheduler.model.ChronoEntry) -> org.example.project.scheduler.model.ChronoEntry,
    ): SchedulerState {
        val chrono = state.chronos.firstOrNull { it.id == id } ?: return state
        val next = transition(chrono)
        if (next == chrono) return state
        return state.copy(chronos = state.chronos.map { if (it.id == id) next else it })
    }

    /**
     * PRD §14 Reminders "checking off": flip the [checked] flag on the reminder tag [panelId] and record it
     * as a Calendar History Unit (undoable while the calendar is focused, via [commitPanels]). A no-op when
     * the id is not a reminder tag or is already in the requested state.
     */
    private fun reduceSetReminderChecked(
        state: SchedulerState,
        panelId: String,
        checked: Boolean,
        nowMillis: Long,
    ): SchedulerState {
        val panel = state.panels.firstOrNull { it.id == panelId && it.chore } ?: return state
        if (panel.checked == checked) return state
        // PRD §14: checking freezes the tag at the moment it was checked; un-checking clears that anchor.
        val checkedAtMillis = if (checked) nowMillis else null
        val updated = state.panels.map {
            if (it.id == panelId) it.copy(checked = checked, checkedAtMillis = checkedAtMillis) else it
        }
        return commitPanels(state, updated, label = if (checked) "Check reminder" else "Uncheck reminder")
    }

    /**
     * PRD §14 "add reminder": place a manually-added reminder tag at [atMillis] for reminder [reminderId]
     * (display [title]) with the chosen [checked] / [pinned] switches. It is a zero-duration chore panel with
     * the manual prefix; reminder regeneration keeps it while it is checked **or** pinned (an unchecked,
     * unpinned tag has no reference and is dropped on the next regeneration). Recorded on the Calendar history
     * stack (undoable). No-op for a blank title.
     */
    private fun reduceAddReminder(
        state: SchedulerState,
        reminderId: String,
        title: String,
        atMillis: Long,
        checked: Boolean,
        pinned: Boolean,
    ): SchedulerState {
        if (title.isBlank()) return state
        // A brand-new reminder (no id picked) gets a freshly-minted stable id rather than a blank one, so the
        // tag's panel encodes a real identity and the reminder surfaces in the id menu (a blank id decodes to
        // null and is dropped from [SchedulerDomain.allReminderEntries], making it unselectable).
        val effectiveReminderId = reminderId.ifBlank { SchedulerDomain.freshReminderId(state) }
        val (panelId, next) = state.allocatePanelId()
        val id = SchedulerDomain.MANUAL_REMINDER_PREFIX + effectiveReminderId + "/" + panelId.substringAfterLast('/')
        val panel = TaskPanel(
            id = id,
            taskId = null,
            title = title,
            startEpochMillis = atMillis,
            endEpochMillis = atMillis,
            pinned = pinned,
            auto = false,
            chore = true,
            checked = checked,
            // PRD §14: checking freezes the tag at the moment placed (anchors recurrence); unchecked has none.
            checkedAtMillis = if (checked) atMillis else null,
        )
        return commitPanels(next, next.panels + panel, label = "Add reminder")
    }

    /**
     * PRD §4/§13 **Copy (Ctrl+C)**: the **task id** of the cells the chord acts on, in the bare reference
     * shape (ADR 0012) — the very text the §13 menu's "copy task id (ctrl c)" writes, the two being one
     * gesture. Not the sub-tree: [reduceCutSelection] below still takes that, because a cut has to carry
     * back everything it deleted, and the deep-copy window is where a sub-tree copy is asked for.
     */
    private fun reduceCopySelection(state: SchedulerState): SchedulerState {
        if (state.editSession != null) return state
        val text =
            SchedulerDomain.taskIdReferenceText(
                state,
                SchedulerDomain.copyTreeTargets(state, state.selection),
            )
        if (text.isEmpty()) return state
        return state.copy(clipboard = text.split('\n'))
    }

    /**
     * PRD §4/§13 Cut (Ctrl+X): the whole sub-tree under the cells the chord acts on, and then those same cells are
     * emptied — the PRD §4 deletion, blank title and all, so the cut sub-tree's ids are freed and a paste
     * can rebuild it under them. Both halves ride [reduceEmptySelected]'s single history unit.
     */
    private fun reduceCutSelection(state: SchedulerState): SchedulerState {
        if (state.editSession != null) return state
        val text = SchedulerDomain.copyTreeText(state, state.selection)
        if (text.isEmpty()) return state
        return reduceEmptySelected(state.copy(clipboard = text.split('\n')), label = "Cut")
    }

    private fun reducePasteTree(state: SchedulerState, text: String): SchedulerState {
        if (state.editSession != null) return state
        // PRD §4 Paste: only the app's tab-indented tree format is accepted, onto a single selected cell.
        val nodes = SchedulerDomain.parseTreeText(text) ?: return state
        if (nodes.isEmpty()) return state
        val main = state.selection.main ?: return state
        if (state.selection.selected.size > 1) return state
        if (!SchedulerDomain.isSelectableCell(state, main)) return state
        val before = state.captureTree()
        val pasted = pasteTreeAtCell(state.copy(clipboard = text.split('\n')), main, nodes)
        val after = pasted.captureTree()
        if (before == after) return state
        return commitDelta(pasted, TreeMutationDelta(before = before, after = after, label = "Paste"))
    }

    /**
     * PRD §7/§13 **"add default sub-tree"**: the template applied on demand, under every cell the §13 menu
     * acts on ([SchedulerDomain.contextMenuCopyTargets], so the menu never disagrees with itself about what
     * "the cell" is when it is right-clicked inside a multi-selection).
     *
     * Three deliberate differences from the automatic graft ([graftDefaultSubtree]):
     *
     * - the [SchedulerState.defaultSubtreeEnabled] switch is **not** consulted — it governs whether *new*
     *   tasks are seeded without being asked, and this is the asking;
     * - the cell's task need not be new;
     * - the cells are expanded, so what was just added is visible rather than folded away.
     *
     * **It lands under the cell the menu was opened on, and nowhere else** — beside that cell's existing
     * children where it has some, never descending to them. The rule used to fill the sub-tree's *leaves*
     * instead, reading a broken-down cell as "then put the template on the pieces"; right-clicking
     * `why / how to measure improvement` therefore filled a grandchild two levels down, which is not a row
     * the gesture names (2026-09-21, account 3). To seed a piece, right-click the piece: this entry acts on
     * what was clicked, as every other entry of the §13 menu does.
     *
     * The rows it lays down are built by [applyDefaultSubtreeTemplate], which drives the editing primitives
     * directly — so a seeded row never seeds in turn, here as in the graft.
     */
    private fun reduceAddDefaultSubtree(state: SchedulerState, cellIds: List<CellId>): SchedulerState {
        if (state.editSession != null) return state
        if (state.defaultSubtreeIsEmpty) return state
        val targets = defaultSubtreeApplicationTargets(state, cellIds)
        if (targets.isEmpty()) return state
        val before = state.captureTree()
        var working = state
        for (cellId in targets) {
            val childListId = working.cells[cellId]?.taskId?.let { working.tasks[it]?.childListId } ?: continue
            working =
                applyDefaultSubtreeTemplate(working, childListId, state.defaultSubtree, WellKnownIds.ROOT_LIST)
        }
        val after = working.captureTree()
        if (before == after) return state
        return commitDelta(
            // The rows land at the bottom of the cell's own sub-list, so a collapsed cell would fold away
            // the very thing it was just asked for.
            working.copy(expanded = state.expanded + targets),
            TreeMutationDelta(before = before, after = after, label = "Add default sub-tree"),
        )
    }

    /**
     * PRD §7 *Search*: run [inner] against the sub-tree an expanded Search row shows ([projectSearchSubtree]) —
     * committed as ONE Main unit, the inner reduction's own units evaporating with the projection. [readOnly] (a task cut from the tree and kept by the timeline): Edit Mode is not
     * opened, and a gesture that would change the tree keeps only what it did to the window's own view state.
     */
    private fun reduceInSearchSubtree(
        state: SchedulerState,
        inner: SchedulerIntent,
        listId: CellListId,
        readOnly: Boolean,
    ): SchedulerState {
        if (inner is SchedulerIntent.InSearchSubtree) return state
        if (
            inner is SchedulerIntent.Undo || inner is SchedulerIntent.Redo ||
            inner is SchedulerIntent.UndoSelection || inner is SchedulerIntent.RedoSelection ||
            inner is SchedulerIntent.UndoPosition || inner is SchedulerIntent.RedoPosition
        ) {
            return state
        }
        if (readOnly && inner is SchedulerIntent.BeginEdit) return state
        if (state.lists[listId] == null) return state
        val projected = state.projectSearchSubtree(listId)
        val reduced = reduceIntent(projected, inner)
        if (reduced === projected) return state
        val before = state.captureTree()
        if (readOnly) {
            // Looked through, never modified: whatever the gesture did to the tree is dropped.
            if (reduced.captureTree() != before) return state
            val looked = state.withSearchViewStateFrom(reduced)
            return withViewSelectionUnit(looked, HistoryWindow.Search, state.searchSelection, looked.searchSelection)
        }
        val folded =
            state.withSearchSubtreeCapturedFrom(reduced).let {
                withViewSelectionUnit(it, HistoryWindow.Search, state.searchSelection, it.searchSelection)
            }
        val after = folded.captureTree()
        // A gesture that only moved the window's own caret, selection or expansion changes no tree and records
        // no unit — the tree's own rule for a selection-only change.
        if (before == after) return folded
        return commitDelta(
            folded,
            TreeMutationDelta(before = before, after = after, label = "Search"),
            HistoryCategory.Main,
        )
    }

    /**
     * PRD §7 *Search*: [SchedulerIntent.RenameTask]. A task only the live tree holds is the common case and is a
     * diff-sized [TreeMutationDelta]; one a stored tree carries too needs [TaskTreeDelta], the one unit that
     * carries the stored trees — kept for that case only, being the larger of the two.
     */
    private fun reduceRenameTask(state: SchedulerState, taskId: TaskId, title: String): SchedulerState {
        if (title.isBlank()) return state
        val after = SchedulerDomain.withTaskRenamed(state, taskId, title)
        if (after === state) return state
        val label = "Rename task \"" + title + "\""
        return if (after.taskTrees == state.taskTrees) {
            commitDelta(state, TreeMutationDelta(before = state.captureTree(), after = after.captureTree(), label = label))
        } else {
            commitDelta(
                state,
                TaskTreeDelta(before = state.captureTaskTreeState(), after = after.captureTaskTreeState(), label = label),
            )
        }
    }

    /**
     * PRD §4 **Default sub-tree**: run [inner] against the template rather than the live tree.
     *
     * The window is the task tree, so it emits the task tree's intents; this is the whole of what makes them
     * land on the template. The state is projected ([projectDefaultSubtree]), the intent reduced there, and
     * the result folded back with [withDefaultSubtreeCapturedFrom] — which copies onto **this** state, so
     * every live-tree field, the histories included, survives untouched. That is also why the inner
     * reduction's own history units simply evaporate: they were recorded on the projection's copy, and the
     * projection is thrown away.
     *
     * Undo/Redo are deliberately **not** forwarded: they belong to the app's stacks, where this method's own
     * unit is waiting. Forwarding one would replay a template unit against a projection and lose the live
     * pointer. The window dispatches them unwrapped.
     */
    private fun reduceInDefaultSubtree(state: SchedulerState, inner: SchedulerIntent): SchedulerState {
        if (inner is SchedulerIntent.InDefaultSubtree) return state
        if (
            inner is SchedulerIntent.Undo || inner is SchedulerIntent.Redo ||
            inner is SchedulerIntent.UndoSelection || inner is SchedulerIntent.RedoSelection ||
            inner is SchedulerIntent.UndoPosition || inner is SchedulerIntent.RedoPosition
        ) {
            return state
        }
        val before = state.defaultSubtree
        // A row pointing at a live task draws that task's own sub-list, so a gesture in this window can be an
        // edit to the account's tree ([withDefaultSubtreeCapturedFrom]). The unit has to carry both halves or
        // Ctrl+Z would put one of them back and leave the other.
        val liveBefore = state.captureTree()
        val projected = state.projectDefaultSubtree()
        val reduced = reduceIntent(projected, inner)
        if (reduced === projected) return state
        val folded =
            state.withDefaultSubtreeCapturedFrom(reduced).let {
                withViewSelectionUnit(it, HistoryWindow.DefaultSubtree, state.defaultSubtreeSelection, it.defaultSubtreeSelection)
            }
        val liveAfter = folded.captureTree()
        // A gesture that only moved the window's own caret/selection changes neither tree and records no unit
        // — the same rule the tree follows for a selection-only change.
        if (folded.defaultSubtree == before && liveAfter == liveBefore) return folded
        return commitDelta(
            folded,
            DefaultSubtreeDelta(
                before = before,
                after = folded.defaultSubtree,
                liveBefore = liveBefore,
                liveAfter = liveAfter,
                label = "Default sub-tree",
            ),
            HistoryCategory.Main,
        )
    }

    /**
     * PRD §4: flip one template row's switch. On (`bound = false`, the default) the row mints a brand new task
     * at every graft; off it points every grafted cell at the task the row holds.
     *
     * One Main unit, like every other edit made in the window.
     */
    private fun reduceSetDefaultSubtreeCellBound(
        state: SchedulerState,
        cellId: CellId,
        bound: Boolean,
    ): SchedulerState {
        val template = state.defaultSubtree
        // An empty row has no task behind it, so it has no switch to flip — and neither has a row of the
        // LIVE tree, drawn under a bound row as the mirror it is ([isTitledDefaultSubtreeRow]).
        if (!state.isTitledDefaultSubtreeRow(cellId)) return state
        val next =
            if (bound) template.boundCells + cellId else template.boundCells - cellId
        if (next == template.boundCells) return state
        val after = template.copy(boundCells = next)
        // The switch says what a future GRAFT does; it moves no tree, so the live half of the unit is empty.
        return commitDelta(
            state.copy(defaultSubtree = after),
            DefaultSubtreeDelta(before = template, after = after, label = "Default sub-tree switch"),
            HistoryCategory.Main,
        )
    }

    /**
     * PRD §7 Keyboard shortcuts: bind one system-wide chord, or (null [binding]) put it back to the one it
     * ships with.
     *
     * The stored map holds **overrides only**, so a reset removes the entry rather than writing the default
     * into it — otherwise an account would freeze the default it happened to be on when the user pressed
     * "reset", and a later build's changed default would never reach it.
     *
     * A rebinding the rules refuse is a no-op here. The window checks the same predicate and shows the
     * sentence, so this guard is the backstop for anything dispatching without asking, not the user-facing
     * check.
     */
    private fun reduceSetGlobalShortcutBinding(
        state: SchedulerState,
        shortcut: GlobalShortcut,
        binding: ShortcutBinding?,
    ): SchedulerState {
        val before = state.shortcutBindings
        if (binding != null && GlobalShortcutBindings.rejection(before, shortcut, binding) != null) return state
        val after = if (binding == null) before - shortcut else before + (shortcut to binding)
        if (after == before) return state
        return commitDelta(
            state.copy(shortcutBindings = after),
            ShortcutBindingDelta(before = before, after = after),
            HistoryCategory.Main,
        )
    }

    /**
     * PRD §5: give a task a category by NAME — the create-or-attach the row's naming field raises.
     *
     * A title an existing category already carries attaches **that** category, which is the whole reason a
     * category is an object with an id: without it, two tasks typing the same word would end up carrying two
     * different labels that look identical, and a rule could only ever govern one of them. Any other non-blank
     * title mints a new category; a blank one does nothing, exactly as it does in a cell being created.
     */
    private fun reduceAddTaskCategory(state: SchedulerState, taskId: TaskId, titleRaw: String): SchedulerState {
        val title = titleRaw.trim()
        if (title.isEmpty()) return state
        if (state.tasks[taskId] == null) return state
        val existing = state.categories.firstOrNull { it.title.equals(title, ignoreCase = true) }
        if (existing != null) return reduceAttachTaskCategory(state, taskId, existing.id)
        val (id, allocated) = state.allocateCategoryId()
        val minted = allocated.copy(categories = allocated.categories + Category(id = id, title = title))
        return commitDelta(minted, priorityTreeDelta(minted, "Task category") {
            applyAttachTaskCategory(it, taskId, id)
        })
    }

    /** PRD §5: attach an existing category to a task — what the field's identity rows raise. */
    private fun reduceAttachTaskCategory(
        state: SchedulerState,
        taskId: TaskId,
        categoryId: CategoryId,
    ): SchedulerState {
        if (state.categoryById(categoryId) == null) return state
        if (applyAttachTaskCategory(state, taskId, categoryId) === state) return state
        return commitDelta(state, priorityTreeDelta(state, "Task category") {
            applyAttachTaskCategory(it, taskId, categoryId)
        })
    }

    private fun editTextDelta(state: SchedulerState, text: String): Delta {
        val session = state.editSession ?: return NoOpDelta
        val before = state.captureTree()
        val after = applyEditText(state, session, text).captureTree()
        return TreeMutationDelta(before = before, after = after, label = "Edit text")
    }

    // PRD §4 Post-Edit Tree Evaluation: exiting Edit Mode removes empty cells (except the absolute
    // bottom cell of each sublist). PRD §5 categories: the whole session (keystrokes + cleanup) is
    // collapsed from the pre-session tree into a single "rest" unit so post-exit Ctrl+Z undoes the
    // edit as one step, and the ephemeral Edit Mode stack is discarded.
    private fun endEditSession(state: SchedulerState): SchedulerState {
        val session = state.editSession
        val cleaned = evaluatePostEditCleanup(state)
        // PRD §4 Default sub-tree: a session that CREATED a task seeds it, once, at the end — not on every
        // keystroke (each one re-runs the naming) and after the cleanup, so a cell abandoned empty is gone
        // before anything could be grafted under it. It rides the session's single "Edit" unit, so one
        // Ctrl+Z takes the seeded sub-tree back with the title that pulled it in.
        // The seeded cell is left COLLAPSED. Creating a task is not asking to see the template unfold under
        // it: the row the user just typed would jump down the screen behind a block of rows they did not
        // write, on every single creation. [applySetCellTitle] already dropped the cell from
        // [SchedulerState.expanded] where it minted the sub-list, so there is nothing to do here — only the
        // gestures that mean to open it (the arrow, Tab into the child, "add default sub-tree") do.
        val seeded =
            if (session == null) cleaned
            else graftDefaultSubtree(cleaned, session.cellId, session.treeBefore.tasks.keys)
        val before = session?.treeBefore ?: seeded.captureTree()
        val after = seeded.captureTree()
        val committed =
            if (after != before) {
                commitDelta(seeded, TreeMutationDelta(before = before, after = after, label = "Edit"))
            } else {
                seeded
            }
        return committed.copy(
            editSession = null,
            histories = committed.histories.copy(edit = SchedulerHistory()),
        )
    }

    /**
     * PRD §4 *Forced Exit* + §7 *Default sub-tree*: asking for a sub-tree while a cell is being edited ends
     * that session first.
     *
     * The default sub-tree is grafted **once, at the end of the session** ([endEditSession]) — never on a
     * keystroke, each of which re-runs the naming and can still swap the "New task" draft for an existing id.
     * So an expand arrow clicked mid-session would otherwise open the freshly named task onto nothing but its
     * empty placeholder, and the template would only turn up after the next click elsewhere had ended the
     * session for it.
     *
     * The graft leaves what it seeded COLLAPSED, so the click that forced the exit is what opens it — the
     * toggle is applied wherever the forced exit did not already leave the cell in the state the click asked
     * for, and never on a cell the post-edit cleanup has just removed.
     */
    private fun reduceToggleExpand(state: SchedulerState, cellId: CellId): SchedulerState {
        if (state.editSession == null) return commitDelta(paidDefaultSubtree(state, cellId), ToggleExpandDelta(cellId))
        val wantExpanded = cellId !in state.expanded
        val exited = endEditSession(state)
        if (exited.cells[cellId] == null) return exited
        if ((cellId in exited.expanded) == wantExpanded) return exited
        return commitDelta(paidDefaultSubtree(exited, cellId), ToggleExpandDelta(cellId))
    }

    /**
     * PRD §4 *Default sub-tree*: the rows [cellId] is owed ([materializeDefaultSubtree]), written as their
     * own Main history unit just before the cell opens.
     *
     * Two units and not one, because [ToggleExpandDelta] is a **toggle** — it undoes by expanding again —
     * so it cannot carry a tree mutation. That is the shape a forced exit followed by the expand arrow
     * already has: one Ctrl+Z closes the row, the next takes the rows it opened onto back.
     *
     * Nothing is written when the cell is being COLLAPSED: the promise is paid by opening a row, and a
     * collapse is the opposite gesture.
     */
    private fun paidDefaultSubtree(state: SchedulerState, cellId: CellId): SchedulerState {
        if (cellId in state.expanded) return state
        val before = state.captureTree()
        val paid = materializeDefaultSubtree(state, cellId)
        if (paid === state) return state
        val after = paid.captureTree()
        if (after == before) return paid
        return commitDelta(paid, TreeMutationDelta(before = before, after = after, label = "Default sub-tree"))
    }

    /**
     * Collapse the sub-trees under [cellId] in one undoable expansion-set delta: every cell below it is
     * dropped from the expansion set, [cellId] itself is NOT — the gesture trims the branch back to the
     * cell the user right-clicked, leaving its own children on screen.
     */
    private fun reduceCollapseSubtrees(state: SchedulerState, cellId: CellId): SchedulerState {
        val cell = state.cells[cellId] ?: return state
        val taskId = cell.taskId ?: return state
        val childListId = state.tasks[taskId]?.childListId ?: return state
        val subtree = mutableSetOf<CellId>()
        val visitedLists = mutableSetOf<CellListId>()

        fun walk(listId: CellListId) {
            if (!visitedLists.add(listId)) return
            val list = state.lists[listId] ?: return
            for (childId in list.cellIds) {
                subtree += childId
                val childTaskId = state.cells[childId]?.taskId ?: continue
                val nextListId = state.tasks[childTaskId]?.childListId ?: continue
                walk(nextListId)
            }
        }

        walk(childListId)
        val collapsed = state.expanded - subtree
        if (collapsed == state.expanded) return state
        return commitDelta(state, SetExpandedDelta(before = state.expanded, after = collapsed))
    }

    /**
     * PRD §4 Find & replace: put the row a search hit sits on on screen, then select it.
     *
     * Every collapsed ancestor along the hit's own path is expanded in ONE [SetExpandedDelta] — walking the
     * hits with ↑/↓ would otherwise stack a separate expand/collapse unit per level per hit, and Ctrl+Z
     * would spend a dozen presses climbing back out of the navigation before undoing anything the user did.
     *
     * Opening the find bar over a cell being edited is a §4 Forced Exit, exactly as [reduceToggleExpand]
     * treats a click on another cell's arrow.
     */
    private fun reduceRevealCell(
        state: SchedulerState,
        cellId: CellId,
        ancestors: List<CellId>,
    ): SchedulerState {
        var next = if (state.editSession != null) endEditSession(state) else state
        if (!SchedulerDomain.isSelectableCell(next, cellId)) return next

        var expandedAfter = next.expanded
        for (ancestor in ancestors) {
            if (ancestor in expandedAfter) continue
            // The same guards [ToggleExpandDelta] applies: only a populated cell with a materialized
            // sub-list can be expanded.
            val taskId = next.cells[ancestor]?.taskId ?: continue
            if (SchedulerDomain.isTextuallyEmptyCell(next, ancestor)) continue
            val childListId = next.tasks[taskId]?.childListId ?: continue
            if (next.lists[childListId] == null) continue
            expandedAfter = expandedAfter + ancestor
        }
        if (expandedAfter != next.expanded) {
            next = commitDelta(next, SetExpandedDelta(before = next.expanded, after = expandedAfter))
        }

        val after = selectionFor(next, main = cellId, explicitVia = ancestors.lastOrNull())
        if (after == next.selection) return next
        return commitDelta(
            next,
            SetSelectionDelta(before = next.selection, after = after),
            HistoryCategory.Selection,
        )
    }

    /**
     * PRD §4 Find & replace ("replace all"): rename every task in [titles], as one history unit.
     *
     * Each rename goes through [applySetCellTitle] on one of the task's own cells — the very primitive
     * Rename mode uses — so occurrences, the title index and the tombstone rule (a task with records/panels
     * keeps its title and unbinds the cell instead) all behave exactly as they do when the title is typed.
     *
     * A replacement that consumes a whole title leaves a blank one, and §4's "the blank title is what
     * deletes" then applies: the post-edit cleanup runs, and the selection is carried off any cell it
     * collected. That sweep is skipped entirely when nothing was blanked, so a plain rename never collects
     * unrelated empty cells the user left sitting mid-list.
     */
    private fun reduceReplaceTaskTitles(
        state: SchedulerState,
        titles: Map<TaskId, String>,
    ): SchedulerState {
        if (titles.isEmpty() || state.editSession != null) return state
        val before = state.captureTree()
        var next = state
        var blanked = false
        for ((taskId, title) in titles) {
            val task = next.tasks[taskId] ?: continue
            if (task.title == title) continue
            val cellId =
                task.occurrences.firstOrNull { SchedulerDomain.isSelectableCell(next, it) } ?: continue
            if (title.isEmpty()) blanked = true
            next = applySetCellTitle(next, cellId, title)
        }
        val cleaned = if (blanked) evaluatePostEditCleanup(next) else next
        val after = cleaned.captureTree()
        if (before == after) return state
        val selectionAfter =
            if (blanked) {
                adjustSelectionAfterRemovedCells(
                    beforeCleanup = next,
                    afterCleanup = cleaned,
                    selection = state.selection,
                )
            } else {
                state.selection
            }
        return commitDelta(
            state,
            EmptyCellsDelta(
                treeBefore = before,
                treeAfter = after,
                selectionBefore = state.selection,
                selectionAfter = selectionAfter,
                label = "Replace in titles",
            ),
        )
    }

    private fun reduceClick(state: SchedulerState, intent: SchedulerIntent.ClickCell): SchedulerState {
        if (!SchedulerDomain.isSelectableCell(state, intent.cellId)) return state

        // The deferred single-click reset (forceClearMulti) fires after the double-tap timeout to
        // collapse a still-intact multi-selection down to the clicked cell. If the user has since
        // clicked another cell, the previous cell's timer can still be alive and fire this stale
        // reset, momentarily re-selecting the old cell before its own deferred click re-asserts the
        // new one. Ignore it unless the clicked cell is still the main selection.
        if (intent.forceClearMulti && !intent.ctrl && !intent.shift &&
            state.selection.main != intent.cellId
        ) {
            return state
        }

        val visibleOrder =
            intent.visibleOrder.ifEmpty { SchedulerDomain.selectableVisibleOrder(state) }
        val currentMain = state.selection.main
        val newSelection =
            when {
                intent.shift && currentMain != null -> {
                    val range =
                        SchedulerDomain.visibleSelectionRange(
                            visibleOrder,
                            currentMain,
                            intent.cellId,
                        )
                    selectionFor(
                        state,
                        main = intent.cellId,
                        selected = range,
                        rangeAnchor = currentMain,
                        explicitVia = intent.renderVia,
                    )
                }
                intent.ctrl -> {
                    val base = state.selection.selected.toMutableSet()
                    state.selection.main?.let { base.add(it) }
                    val toggled =
                        if (intent.cellId in base) {
                            base - intent.cellId
                        } else {
                            base + intent.cellId
                        }
                    selectionFor(
                        state,
                        main = intent.cellId,
                        selected = toggled,
                        explicitVia = intent.renderVia,
                    )
                }
                intent.forceClearMulti ->
                    selectionFor(state, main = intent.cellId, explicitVia = intent.renderVia)
                else -> {
                    // Keep a contiguous multi-selection when clicking an already-selected
                    // cell so double-click & drag move can activate (PRD §3).
                    val preserveRange =
                        intent.cellId in state.selection.selected &&
                            state.selection.selected.size > 1
                    if (preserveRange) {
                        selectionFor(
                            state,
                            main = intent.cellId,
                            selected = state.selection.selected,
                            rangeAnchor = state.selection.rangeAnchor,
                            explicitVia = intent.renderVia ?: state.selection.renderVia,
                        )
                    } else {
                        selectionFor(state, main = intent.cellId, explicitVia = intent.renderVia)
                    }
                }
            }

        return applySelectionChange(state, newSelection, intent.cellId)
    }

    private fun reduceDragSelect(
        state: SchedulerState,
        intent: SchedulerIntent.DragSelectCells,
    ): SchedulerState {
        if (!SchedulerDomain.isSelectableCell(state, intent.anchorCellId)) return state
        if (!SchedulerDomain.isSelectableCell(state, intent.hoverCellId)) return state
        val visibleOrder =
            intent.visibleOrder.ifEmpty { SchedulerDomain.selectableVisibleOrder(state) }
        val range =
            SchedulerDomain.visibleSelectionRange(
                visibleOrder,
                intent.anchorCellId,
                intent.hoverCellId,
            )
        val newSelection =
            selectionFor(
                state,
                main = intent.anchorCellId,
                selected = range,
                rangeAnchor = intent.anchorCellId,
                explicitVia = intent.renderVia,
            )
        return applySelectionChange(state, newSelection, intent.hoverCellId)
    }

    private fun reduceMoveSelected(
        state: SchedulerState,
        intent: SchedulerIntent.MoveSelectedCells,
    ): SchedulerState {
        val block =
            SchedulerDomain.orderedActiveSelectionInList(state, state.selection)
                ?: return state
        val (sourceListId, movingOrdered) = block
        val moving = movingOrdered.toSet()
        val targetListId = state.cells[intent.targetCellId]?.parentListId ?: return state
        val targetList = state.lists[targetListId] ?: return state
        // A cross-list drop relocates the block into another layer of the tree. Reject it up front
        // when any moved task would break a PRD constraint at the destination (duplicate in the
        // list, or a cycle with its new ancestors); same-list reorders never can.
        if (targetListId != sourceListId) {
            val valid =
                movingOrdered.all { cellId ->
                    SchedulerDomain.canMoveTaskIntoList(
                        state,
                        state.cells[cellId]?.taskId,
                        targetListId,
                        intent.targetCellId,
                        moving,
                    )
                }
            if (!valid) return state
        }

        val insertIndex =
            SchedulerDomain.moveInsertIndex(
                targetList.cellIds,
                moving,
                intent.targetCellId,
                intent.insertBefore,
            )
        val before = state.captureTree()
        var moved =
            SchedulerDomain.applyMoveCellsToList(
                state,
                sourceListId,
                movingOrdered,
                targetListId,
                insertIndex,
            )
        // PRD §4 Empty cells: restore the invariant disturbed by the move — drop any empty cell that is no
        // longer its list's bottom cell, then re-append a trailing placeholder where a populated cell now
        // sits at the bottom (e.g. a task dropped below the old placeholder). Folded into this delta so it
        // undoes as one unit.
        moved = evaluatePostEditCleanup(moved)
        moved = ensureTrailingPlaceholder(moved, sourceListId)
        moved = ensureTrailingPlaceholder(moved, targetListId)
        val after = moved.captureTree()
        if (before == after) return state
        return commitDelta(moved, TreeMutationDelta(before = before, after = after, label = "Move cells"))
    }

    private fun reduceEmptySelected(state: SchedulerState, label: String = "Clear cells"): SchedulerState {
        if (state.editSession != null) return state
        val targets =
            SchedulerDomain.activeSelectionCells(state.selection)
                .filter { SchedulerDomain.isSelectableCell(state, it) }
        if (targets.isEmpty()) return state
        val before = state.captureTree()
        var next = state
        for (cellId in targets) {
            next = applySetCellTitle(next, cellId, "")
        }
        // PRD §4 Empty cells management: remove emptied cells except the absolute bottom
        // cell of each sublist (same cleanup as exiting Edit Mode).
        val cleaned = evaluatePostEditCleanup(next)
        val after = cleaned.captureTree()
        val selectionAfter =
            adjustSelectionAfterRemovedCells(
                beforeCleanup = next,
                afterCleanup = cleaned,
                selection = state.selection,
            )
        if (before == after && selectionAfter == state.selection) return state
        return commitDelta(
            state,
            EmptyCellsDelta(
                treeBefore = before,
                treeAfter = after,
                selectionBefore = state.selection,
                selectionAfter = selectionAfter,
                label = label,
            ),
        )
    }

    /**
     * PRD §7 window navigation. Focus moves and **nothing else does**: the tree's selection and its Edit
     * Mode belong to the tree, not to whichever window the pointer went to next, so reaching for the
     * calendar and coming back leaves the rename in progress exactly where it was. (A press on another task
     * CELL is the one thing that moves the selection — [reduceClick] — and Escape is the one thing that
     * leaves Edit Mode without committing to a neighbour.)
     *
     * The navigation is recorded as a WindowNav History Unit (shown in the History Manager but, for now,
     * not walked by any undo/redo command). A no-op when focus does not actually change.
     */
    private fun reduceFocusWindow(state: SchedulerState, window: HistoryWindow, instance: String): SchedulerState {
        if (state.focusedWindow == window && state.focusedInstance == instance) return state
        return commitDelta(
            state,
            FocusDelta(before = state.focusedWindow, after = window, beforeInstance = state.focusedInstance, afterInstance = instance),
            HistoryCategory.WindowNav,
        )
    }

    /** PRD §5: [SchedulerIntent.SelectInWindow] — a [WindowSelectionDelta] unless it is a reset or no change. */
    private fun reduceSelectInWindow(state: SchedulerState, intent: SchedulerIntent.SelectInWindow): SchedulerState {
        val key = windowSelectionKey(intent.window, intent.instance)
        val before = state.windowSelections[key]
        if (before == intent.key) return state
        val delta = WindowSelectionDelta(intent.window, intent.instance, before, intent.key)
        // A reset (a new search starting on its first row) is not a position the user took.
        return if (intent.record) commitDelta(state, delta, HistoryCategory.Selection) else delta.redo(state)
    }

    /**
     * PRD §5: the selection unit of a window drawn as a tree ([ViewSelectionDelta]) — committed beside whatever
     * the gesture did to the tree, when the window's selection moved.
     */
    private fun withViewSelectionUnit(
        folded: SchedulerState,
        window: HistoryWindow,
        before: SchedulerSelection,
        after: SchedulerSelection,
    ): SchedulerState =
        if (before == after) folded else commitDelta(folded, ViewSelectionDelta(window, before, after), HistoryCategory.Selection)

    private fun reduceExitEdit(
        state: SchedulerState,
        navigation: EditExitNavigation,
    ): SchedulerState {
        if (state.editSession == null) return state
        val editingCellId = state.editSession.cellId
        val editingVia = state.editSession.renderVia
        var next = endEditSession(state)

        val newMain =
            when (navigation) {
                EditExitNavigation.Down ->
                    SchedulerDomain.neighborSelectableOccurrence(next, editingCellId, editingVia, 1)
                        ?.cellId ?: editingCellId
                EditExitNavigation.Up ->
                    SchedulerDomain.neighborSelectableOccurrence(next, editingCellId, editingVia, -1)
                        ?.cellId ?: editingCellId
                EditExitNavigation.Stay -> editingCellId
                EditExitNavigation.TabToChild -> {
                    val cell = next.cells[editingCellId]
                    val taskId = cell?.taskId
                    val childListId = taskId?.let { next.tasks[it]?.childListId }
                    if (childListId == null) {
                        editingCellId
                    } else {
                        if (editingCellId !in next.expanded) {
                            // Tab OPENS the row, so it pays what the row owes exactly as the arrow does.
                            next = commitDelta(paidDefaultSubtree(next, editingCellId), ToggleExpandDelta(editingCellId))
                        }
                        SchedulerDomain.firstSelectableChild(next, editingCellId) ?: editingCellId
                    }
                }
            }

        if (newMain == next.selection.main && next.selection.selected.isEmpty()) return next
        // Only Tab-into-child renders the new main via the cell we were editing (its parent).
        // Sibling moves (Up/Down) must resolve their own render-via, otherwise the highlight
        // is pinned to the former cell and never appears on the moved selection.
        val explicitVia =
            if (navigation == EditExitNavigation.TabToChild && newMain != editingCellId) {
                editingCellId
            } else {
                null
            }
        return commitDelta(
            next,
            SetSelectionDelta(
                before = next.selection,
                after = selectionFor(next, main = newMain, explicitVia = explicitVia),
            ),
            HistoryCategory.Selection,
        )
    }

    private fun applySelectionChange(
        state: SchedulerState,
        newSelection: SchedulerSelection,
        clickedCellId: CellId,
    ): SchedulerState {
        // Skip recording a no-op selection change so re-clicking an already-selected cell (or the
        // single-click reset that collapses a still-intact multi-selection) doesn't push an empty
        // undo step onto the history.
        var next =
            if (newSelection == state.selection) {
                state
            } else {
                commitDelta(
                    state,
                    SetSelectionDelta(
                        before = state.selection,
                        after = newSelection,
                    ),
                    HistoryCategory.Selection,
                )
            }
        val editing = state.editSession
        if (editing != null && clickedCellId != editing.cellId) {
            next = endEditSession(next)
        }
        return next
    }

    // ----- PRD §8/§9 task panels (recorded in the Calendar history category) --------------------

    /** Commit a panel-list change as a calendar delta (a no-op change pushes nothing). */
    private fun commitPanels(
        state: SchedulerState,
        after: List<TaskPanel>,
        label: String = "Calendar edit",
    ): SchedulerState {
        val before = state.panels
        // PRD §8: same-task panels auto-merge (unless their pin state differs) the moment an add / edit
        // / move / resize / pin makes them touch or overlap.
        val normalized = SchedulerDomain.mergeSameTaskPanels(after)
        if (before == normalized) return state
        return commitDelta(state, PanelDelta(before, normalized, label), HistoryCategory.Calendar)
    }

    /**
     * PRD §8 pin switches → the scheduler's single fixed flag ([TaskPanel.pinned]). In this pass only the
     * **existence** pin is enforced (a fixed panel survives + constrains a reschedule); the position /
     * spanning / distance pins are stored on the panel but their partial enforcement is a follow-up.
     */
    private fun derivePinned(pins: PanelPins): Boolean = pins.existence

    /**
     * PRD §8: the same flag for a panel that may be a **restrictive period**, which never carries it.
     *
     * A period reaches the scheduler by its KIND, not by a pin (`docs/invariants/scheduler.md` § *What
     * reaches the scheduler*): [SchedulerDomain.fillSchedule] keeps every [TaskPanel.isRestrictivePeriod]
     * panel whatever its pins, and [SchedulerDomain.isSchedulerFixed] is what puts a panel in the walk's
     * **pre-placed blocks** — a list of blocks OWNED BY A TASK. Letting a hand-drawn period set `pinned`
     * would enter it there as a block owned by nobody, on top of the period it already is.
     *
     * Its `pins.existence` is still set (and shown, checked, by the calendar's pin box): a period the user
     * drew is a pre-placed thing, it is simply pre-placed as a period. The box is not a switch there — the
     * way to make the scheduler stop seeing a period is to remove it.
     */
    private fun derivePinned(pins: PanelPins, panel: TaskPanel): Boolean =
        derivePinned(pins) && !panel.isRestrictivePeriod

    private fun reduceAddTaskPanel(
        state: SchedulerState,
        intent: SchedulerIntent.AddTaskPanel,
    ): SchedulerState {
        // Keep end strictly after start so a placed panel never collapses to a zero-length block.
        val end = maxOf(intent.endEpochMillis, intent.startEpochMillis + SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS)
        val (panelId, allocated) = state.allocatePanelId()
        val panel =
            TaskPanel(
                id = panelId,
                taskId = intent.taskId,
                title = intent.title,
                startEpochMillis = intent.startEpochMillis,
                endEpochMillis = end,
                pinned = derivePinned(intent.pins),
                pins = intent.pins,
                auto = false,
            )
        val (resolved, resolvedPanels) = resolveScreenOverrides(allocated, allocated.panels + panel, panelId)
        return commitPanels(resolved, resolvedPanels, label = "Add panel")
    }

    /**
     * PRD §8 contextual menu **"add"** → *restrictive period*: lay a period of the chosen KIND.
     *
     * `side-dev/README.md` § *Restrictive Period*: a period is a start, an end and a kind, so this is the ONE
     * place a period is laid, whichever kind it is — `no on-screen task`, `no task allowed`, PRD §17's
     * `before bed`, or one the account defined. Nothing here branches on the kind: the title comes from
     * [PeriodKinds.periodTitle], the two legacy flags from [PeriodKinds.legacyNoScreenFlag] /
     * [PeriodKinds.legacyInactivityFlag] (kept only because the codec and the merge still read them), and what
     * the period DOES to the panels and the records around it is read from each task's resilience to the kind
     * ([resolveScreenOverrides], [stripRecordsUnderPeriod]).
     *
     * The record strip is applied to the span the user ENDS UP with, not the span they typed: a `no on-screen
     * task` period laid across others has swallowed them ([SchedulerDomain.unifyNoScreenPeriods]), and the
     * work under the whole union is what the period says did not happen.
     */
    private fun reduceAddRestrictivePeriod(
        state: SchedulerState,
        intent: SchedulerIntent.AddRestrictivePeriod,
    ): SchedulerState {
        val kind = PeriodKinds.normalize(intent.kind)
        if (kind.isBlank()) return state
        val end = maxOf(intent.endEpochMillis, intent.startEpochMillis + SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS)
        val (panelId, allocated) = state.allocatePanelId()
        val panel =
            TaskPanel(
                id = panelId,
                taskId = null,
                title = PeriodKinds.periodTitle(kind),
                startEpochMillis = intent.startEpochMillis,
                endEpochMillis = end,
                noScreen = PeriodKinds.legacyNoScreenFlag(kind),
                inactivity = PeriodKinds.legacyInactivityFlag(kind),
                periodKind = kind,
                // PRD §8: a period the user drew is a pre-placed thing, so the calendar's pin box reads
                // CHECKED on it. `pinned` stays false all the same — see [derivePinned]'s overload.
                pins = PanelPins(existence = true),
            )
        val (resolved, resolvedPanels) = resolveScreenOverrides(allocated, allocated.panels + panel, panelId)
        val laid = resolvedPanels.firstOrNull { it.id == panelId } ?: panel
        return stripRecordsUnderPeriod(
            commitPanels(resolved, resolvedPanels, label = "Add period"),
            laid,
        )
    }

    /**
     * PRD §8 **"add…" / "edit…" window Save**: lay (or re-lay) every element the window holds in **one**
     * calendar delta — [SchedulerIntent.AddCalendarElements].
     *
     * It is the three single-element reducers above folded into one pass, and it must be a fold rather than
     * a loop of dispatches for a reason the single case cannot show: **each element resolves its overrides
     * against the calendar the ones before it have already changed.** Adding a `no screen` period and a
     * panel inside it in one Save has to leave the panel trimmed by the period exactly as drawing them one
     * after the other would — so the panel list is carried through [resolveScreenOverrides] element by
     * element and committed once at the end, and the id allocator is carried with it so two new panels can
     * never be handed the same id.
     *
     * The record strip runs AFTER the single commit, once per period laid, because it is not a history unit
     * at all (see [stripRecordsUnderPeriod]) — it is a side effect on the tasks, and running it inside the
     * fold would have it read a panel list that is still being built.
     *
     * An [CalendarElements.Kind.Alarm] draft is skipped here and nowhere else: an alarm is a row of
     * [SchedulerIntent.SetAlarms], which is a **Main** history unit (the intent says why).
     */
    private fun reduceAddCalendarElements(
        state: SchedulerState,
        drafts: List<CalendarElements.Draft>,
    ): SchedulerState {
        if (drafts.isEmpty()) return state
        var working = state
        var panels = state.panels
        val laidPeriods = ArrayList<TaskPanel>()
        for (draft in drafts) {
            if (draft.kind == CalendarElements.Kind.Alarm) continue
            // A draft naming a panel that is gone (a sync removed it under the open window) is dropped
            // rather than re-added: the user was editing a thing that no longer exists.
            val index = draft.existingId?.let { id -> panels.indexOfFirst { it.id == id } } ?: -1
            if (draft.existingId != null && index < 0) continue
            val existing = if (index >= 0) panels[index] else null
            // A reminder tag is zero-duration by definition (PRD §14), so the minimum-length clamp every
            // other panel gets would turn every tag into a block.
            val end =
                if (draft.kind == CalendarElements.Kind.Reminder) draft.startMillis
                else maxOf(draft.endMillis, draft.startMillis + SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS)
            // Editing an auto panel makes it user-authored and re-ids it out of the ephemeral `auto/`
            // namespace — [reduceUpdateTaskPanel]'s rule, asked here for the same reason.
            val needsFreshId = existing == null || existing.auto
            val (allocatedId, allocated) =
                if (needsFreshId) working.allocatePanelId() else existing!!.id to working
            working = allocated
            val panelId =
                if (draft.kind == CalendarElements.Kind.Reminder && existing == null) {
                    reminderPanelId(working, draft, allocatedId)
                } else {
                    allocatedId
                }
            val panel = calendarElementPanel(draft, existing, panelId, end)
            val nextRaw =
                if (index >= 0) panels.toMutableList().also { it[index] = panel }
                else panels + panel
            val (resolved, resolvedPanels) = resolveScreenOverrides(working, nextRaw, panelId)
            working = resolved
            panels = resolvedPanels
            if (draft.kind == CalendarElements.Kind.RestrictivePeriod) {
                laidPeriods += resolvedPanels.firstOrNull { it.id == panelId } ?: panel
            }
        }
        var committed = commitPanels(working, panels, label = "Add to calendar")
        laidPeriods.forEach { committed = stripRecordsUnderPeriod(committed, it) }
        return committed
    }

    /**
     * PRD §14: the id a manual reminder tag's panel carries — `chore-manual/{reminderId}/{n}`, the shape
     * [reduceAddReminder] mints, so a tag laid by the multi-element window and one laid by the reminder
     * editor are the same object. A blank draft id mints a fresh reminder, since a blank one decodes to
     * `null` and would drop the tag out of the id menu entirely.
     */
    private fun reminderPanelId(
        state: SchedulerState,
        draft: CalendarElements.Draft,
        allocatedId: String,
    ): String {
        val reminderId = draft.reminderId.ifBlank { SchedulerDomain.freshReminderId(state) }
        return SchedulerDomain.MANUAL_REMINDER_PREFIX + reminderId + "/" + allocatedId.substringAfterLast('/')
    }

    /**
     * **One draft as the panel it lays** — the per-kind half of [reduceAddCalendarElements], and the only
     * `when` over [CalendarElements.Kind] in the reducer.
     *
     * Each branch is the body of the single-element reducer it replaces, so the two can never lay two
     * different panels for one description: a period takes its title and its two legacy flags from
     * [PeriodKinds], a reminder is a zero-duration `chore` panel whose `checkedAtMillis` anchors the
     * recurrence, and a task panel is the bounds-and-pins commit. [existing] is non-null on an EDIT, and is
     * copied rather than rebuilt so everything the window does not ask about (the layout weight, a record's
     * provenance) survives the Save.
     */
    private fun calendarElementPanel(
        draft: CalendarElements.Draft,
        existing: TaskPanel?,
        panelId: String,
        end: Long,
    ): TaskPanel =
        when (draft.kind) {
            CalendarElements.Kind.TaskPanel ->
                (existing ?: TaskPanel(id = panelId, taskId = null, title = "", startEpochMillis = draft.startMillis, endEpochMillis = end)).copy(
                    id = panelId,
                    taskId = draft.taskId,
                    title = draft.name,
                    startEpochMillis = draft.startMillis,
                    endEpochMillis = end,
                    pinned = derivePinned(draft.pins),
                    pins = draft.pins,
                    auto = false,
                )
            CalendarElements.Kind.RestrictivePeriod -> {
                val kind = PeriodKinds.normalize(draft.periodKind)
                (existing ?: TaskPanel(id = panelId, taskId = null, title = "", startEpochMillis = draft.startMillis, endEpochMillis = end)).copy(
                    id = panelId,
                    taskId = null,
                    title = PeriodKinds.periodTitle(kind),
                    startEpochMillis = draft.startMillis,
                    endEpochMillis = end,
                    noScreen = PeriodKinds.legacyNoScreenFlag(kind),
                    inactivity = PeriodKinds.legacyInactivityFlag(kind),
                    periodKind = kind,
                    // PRD §8: a period the user drew is a pre-placed thing, so the pin box reads checked —
                    // `pinned` itself stays false ([derivePinned]'s overload says why).
                    pins = PanelPins(existence = true),
                    auto = false,
                )
            }
            CalendarElements.Kind.Reminder ->
                (existing ?: TaskPanel(id = panelId, taskId = null, title = "", startEpochMillis = draft.startMillis, endEpochMillis = end)).copy(
                    id = panelId,
                    taskId = null,
                    title = draft.name,
                    startEpochMillis = draft.startMillis,
                    endEpochMillis = draft.startMillis,
                    pinned = draft.reminderPinned,
                    auto = false,
                    chore = true,
                    checked = draft.reminderChecked,
                    // PRD §14: checking freezes the tag where it was placed, which anchors the recurrence.
                    checkedAtMillis = if (draft.reminderChecked) draft.startMillis else null,
                )
            // Never reached: the caller skips alarms, which are rows of `SetAlarms` and not panels at all.
            CalendarElements.Kind.Alarm -> existing ?: TaskPanel(id = panelId, taskId = null, title = "", startEpochMillis = draft.startMillis, endEpochMillis = end)
        }

    /**
     * `side-dev/README.md` § *Restrictive Period*: **whether a period of [kind] REFUSES the task [panel]
     * stands for** — its resilience to that kind is `0`, so the multiplier on its priority there is zero and
     * it may not run inside.
     *
     * This one question replaced the pair the override rule used to ask ("is this an on-screen task panel?",
     * "is this a task panel at all?"), and it answers for every kind rather than for the two that have a
     * flag: against `no on-screen task` a `0` is exactly [Task.onScreen], against `no task allowed` every
     * task is `0` by its own name, and against `before bed` or one of the account's own it is whatever the
     * period's own window has handed out. Adding a kind therefore adds nothing here.
     *
     * A panel with **no backing task** is refused by every kind: a calendar-only panel is a brand-new task's
     * worth of defaults ([Task.DEFAULT_RESILIENCE] — on screen), and there is nobody to have given it a
     * value above zero for anything else.
     */
    private fun periodRefuses(state: SchedulerState, panel: TaskPanel, kind: String): Boolean {
        val task = panel.taskId?.let { state.tasks[it] } ?: return true
        return task.resilienceFor(kind) <= 0.0
    }

    /**
     * PRD §8: true for a real (auto or user-authored) TASK panel of either screen kind — what an inactivity
     * period overrides, since grey refuses on-screen and off-screen tasks alike. The periods themselves and
     * the decorative reminder / screen-break / sleep bands are never one.
     *
     * Asked through [TaskPanel.isRestrictivePeriod], the single reading of a panel's kind, so a period of a
     * kind that has no legacy flag — PRD §17's "before bed", or one of the account's own — is a period here
     * too. Spelling out the four flags said the same thing for the four kinds that had one, and only those.
     */
    private fun isTaskPanel(panel: TaskPanel): Boolean = !panel.isRestrictivePeriod && !panel.chore

    /**
     * PRD §8 override resolution: after the user lays / moves / resizes panel [changedId], trim or delete the
     * panels it now overlaps that cannot coexist with it. A covered panel is deleted; one covered at an edge
     * is trimmed; one covered in the middle is split (the far piece gets a fresh id). Pieces shorter than
     * [SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS] are dropped as slivers.
     *
     * **Who overrides whom is ONE question asked in both directions, and it is the README's:** a period and a
     * task panel cannot coexist exactly when the period REFUSES that task ([periodRefuses]). Laying the
     * period trims the task panels it refuses; laying the task panel trims the periods that refuse it. That
     * is a statement about resilience and nothing else, so it holds for the two kinds the menu used to name
     * and for `before bed` and the account's own alike — where the old spelling ("an on-screen task panel
     * overrides no-screen periods, an inactivity period overrides every task panel") was the same rule
     * enumerated for the two kinds that had a flag, and silently did nothing for a third.
     *
     * The periods it looks at on the task-panel side are the ones the **user drew**
     * ([SchedulerDomain.isUserPlaced]): a §15 screen break, a §17 sleep window and a wind-down hour are
     * `no task allowed` too, but they are re-laid wholesale by every fill and a hand-placed panel does not
     * delete them — it is placed *through* them (§15/§17 suspend a chunk rather than cutting it).
     *
     * The step BEFORE the trim is the one thing that is not an override: **two overlapping "No screen"
     * periods do not compete, they unify** ([SchedulerDomain.unifyNoScreenPeriods]) — the union is what the
     * trim below then applies, so a period laid across two others overrides the on-screen work under the
     * whole fused span. No exception list: it runs whatever [changedId] is, so a state that somehow holds
     * overlapping periods is fused by the next edit rather than kept.
     */
    private fun resolveScreenOverrides(
        state: SchedulerState,
        rawPanels: List<TaskPanel>,
        changedId: String,
    ): Pair<SchedulerState, List<TaskPanel>> {
        val panels = SchedulerDomain.unifyNoScreenPeriods(rawPanels, keepId = changedId)
        val changed = panels.firstOrNull { it.id == changedId } ?: return state to panels
        val trimTarget: (TaskPanel) -> Boolean =
            when {
                // A period the user just laid or dragged: it takes the task panels it refuses.
                changed.isRestrictivePeriod -> { p ->
                    isTaskPanel(p) && periodRefuses(state, p, changed.restrictiveKind)
                }
                !isTaskPanel(changed) -> return state to panels
                // A task panel the user just laid or dragged: it takes the hand-drawn periods that refuse it.
                else -> { p ->
                    p.isRestrictivePeriod && SchedulerDomain.isUserPlaced(p) &&
                        periodRefuses(state, changed, p.restrictiveKind)
                }
            }
        var working = state
        val out = ArrayList<TaskPanel>(panels.size)
        for (p in panels) {
            val overlaps =
                p.id != changedId && trimTarget(p) &&
                    p.startEpochMillis < changed.endEpochMillis && p.endEpochMillis > changed.startEpochMillis
            if (!overlaps) {
                out += p
                continue
            }
            val leftLen = changed.startEpochMillis - p.startEpochMillis
            val rightLen = p.endEpochMillis - changed.endEpochMillis
            if (leftLen >= SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS) {
                out += p.copy(endEpochMillis = changed.startEpochMillis)
                if (rightLen >= SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS) {
                    val (newId, allocated) = working.allocatePanelId()
                    working = allocated
                    out += p.copy(id = newId, startEpochMillis = changed.endEpochMillis)
                }
            } else if (rightLen >= SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS) {
                out += p.copy(startEpochMillis = changed.endEpochMillis)
            }
            // Fully covered (or only sub-minimum slivers remain): the panel is deleted.
        }
        return working to out
    }

    private fun reduceUpdateTaskPanel(
        state: SchedulerState,
        intent: SchedulerIntent.UpdateTaskPanel,
    ): SchedulerState {
        val panels = state.panels
        val index = panels.indexOfFirst { it.id == intent.id }
        if (index < 0) return state
        val end = maxOf(intent.endEpochMillis, intent.startEpochMillis + SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS)
        // Editing an auto panel makes it user-authored; re-id it out of the ephemeral `auto/` namespace
        // so the next reschedule's regenerated auto panels can't collide with the kept (pinned) one.
        val existing = panels[index]
        val (panelId, allocated) =
            if (existing.auto) state.allocatePanelId() else intent.id to state
        // PRD §8 Overlap Mode: an armed drag keeps the raw overlapping bounds and re-seeds this panel's
        // width to 1/n; otherwise its existing width (and the no-overlap snapped bounds) carry over.
        val weight =
            if (intent.allowOverlap) {
                SchedulerDomain.seedOverlapWeight(panels.filter { it.id != intent.id }, intent.startEpochMillis, end)
            } else {
                existing.layoutWeight
            }
        val updated =
            existing.copy(
                id = panelId,
                taskId = intent.taskId,
                title = intent.title,
                startEpochMillis = intent.startEpochMillis,
                endEpochMillis = end,
                pinned = derivePinned(intent.pins, existing),
                pins = intent.pins,
                auto = false,
                layoutWeight = weight,
            )
        val (resolved, resolvedPanels) =
            resolveScreenOverrides(allocated, allocated.panels.toMutableList().also { it[index] = updated }, panelId)
        val committed = commitPanels(resolved, resolvedPanels, label = "Edit panel")
        // PRD §8/§9: moving/resizing a period the user drew re-applies its rule over its NEW span, exactly as
        // laying it did — a period dragged over a past task must strip that work too. Asked through the kind
        // ([TaskPanel.isRestrictivePeriod]) so a kind with no legacy flag is one here as well, and through
        // [SchedulerDomain.isUserPlaced] because a fill-laid break or sleep band is not the user saying they
        // were not working. The NEW span is the resolved one: a no-screen period dragged onto another has
        // swallowed it (PRD §8 unify), and the work under the whole union is what the drag says did not
        // happen.
        val moved = resolvedPanels.firstOrNull { it.id == panelId } ?: updated
        return if (moved.isRestrictivePeriod && SchedulerDomain.isUserPlaced(moved)) {
            stripRecordsUnderPeriod(committed, moved)
        } else {
            committed
        }
    }

    /** PRD §8 (uniform blocks): convert a task-record period into a user panel; drop it from the record. */
    private fun reducePinRecord(
        state: SchedulerState,
        intent: SchedulerIntent.PinRecordAsPanel,
    ): SchedulerState {
        val sourceTask = state.tasks[intent.recordTaskId] ?: return state
        val sourceRange = TaskTimeRange(intent.recordStartEpochMillis, intent.recordEndEpochMillis)
        // Record lives outside history; removing it here is a side effect (undo won't restore it).
        val trimmedTasks =
            state.tasks + (intent.recordTaskId to sourceTask.copy(record = sourceTask.record - sourceRange))
        val end = maxOf(intent.endEpochMillis, intent.startEpochMillis + SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS)
        val (panelId, allocated) = state.copy(tasks = trimmedTasks).allocatePanelId()
        val weight =
            if (intent.allowOverlap) {
                SchedulerDomain.seedOverlapWeight(allocated.panels, intent.startEpochMillis, end)
            } else {
                1.0
            }
        val panel =
            TaskPanel(
                id = panelId,
                taskId = intent.taskId,
                title = intent.title,
                startEpochMillis = intent.startEpochMillis,
                endEpochMillis = end,
                pinned = derivePinned(intent.pins),
                pins = intent.pins,
                auto = false,
                layoutWeight = weight,
            )
        return commitPanels(allocated, allocated.panels + panel, label = "Pin record")
    }

    /** PRD §8 "Remove": delete a panel (undoable calendar delta). */
    private fun reduceRemoveTaskPanel(state: SchedulerState, id: String): SchedulerState {
        val panels = state.panels
        if (panels.none { it.id == id }) return state
        return commitPanels(state, panels.filterNot { it.id == id }, label = "Remove panel")
    }

    /** PRD §8 Overlap Mode: re-divide shared width by setting the [layoutWeight] of the given panels. */
    private fun reduceSetPanelWeights(
        state: SchedulerState,
        intent: SchedulerIntent.SetPanelWeights,
    ): SchedulerState {
        if (intent.weights.isEmpty()) return state
        var changed = false
        val updated = state.panels.map { panel ->
            val w = intent.weights[panel.id]
            if (w != null && w != panel.layoutWeight) {
                changed = true
                panel.copy(layoutWeight = w)
            } else {
                panel
            }
        }
        return if (changed) commitPanels(state, updated, label = "Resize widths") else state
    }

    /** PRD §8 "Remove" on a merged block: delete all its backing panels in one delta. */
    private fun reduceRemoveTaskPanels(state: SchedulerState, ids: List<String>): SchedulerState {
        val idSet = ids.toSet()
        if (state.panels.none { it.id in idSet }) return state
        return commitPanels(state, state.panels.filterNot { it.id in idSet }, label = "Remove block")
    }

    /**
     * PRD §8 edit/drag/resize commit on a merged block: drop [intent.removeIds] and add one
     * user-authored panel over the committed bounds. The bounds arrive already overlap-snapped from the
     * calendar block's live preview (against the other, non-merged blocks), so they are used as-is — as
     * with [reduceAddTaskPanel]. [commitPanels] then re-merges if the result abuts a same-task panel.
     */
    private fun reduceReplaceTaskPanels(
        state: SchedulerState,
        intent: SchedulerIntent.ReplaceTaskPanels,
    ): SchedulerState {
        val end = maxOf(intent.endEpochMillis, intent.startEpochMillis + SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS)
        val idSet = intent.removeIds.toSet()
        val remaining = state.panels.filterNot { it.id in idSet }
        val (panelId, allocated) = state.copy(panels = remaining).allocatePanelId()
        val weight =
            if (intent.allowOverlap) {
                SchedulerDomain.seedOverlapWeight(remaining, intent.startEpochMillis, end)
            } else {
                1.0
            }
        val panel =
            TaskPanel(
                id = panelId,
                taskId = intent.taskId,
                title = intent.title,
                startEpochMillis = intent.startEpochMillis,
                endEpochMillis = end,
                pinned = derivePinned(intent.pins),
                pins = intent.pins,
                auto = false,
                layoutWeight = weight,
            )
        val (resolved, resolvedPanels) = resolveScreenOverrides(allocated, allocated.panels + panel, panelId)
        return commitPanels(resolved, resolvedPanels, label = "Edit panel")
    }

    /**
     * PRD §9 calculation event: [advanceSchedule] then refill the non-pinned panels out to the horizon in
     * force ([scheduleHorizonEndMillis] — $t_{goal}$, not a fixed +168h) with
     * [SchedulerDomain.fillSchedule]. Gated by PRD §7: while [SchedulerState.automaticSchedule] is off
     * the refill is skipped (the event waits) — but the advance still runs so completed work is
     * recorded. The refill is NOT recorded as a History Unit (PRD §9): a schedule is derived from the
     * task tree / calendar state, not an independent user action, so it carries no undo entry — undo/redo
     * walk only the user changes and the schedule re-derives from whatever state they land on. A no-op
     * tick returns the same instance.
     */
    private fun reduceRefreshSchedule(
        state: SchedulerState,
        nowMillis: Long,
        horizonCapMillis: Long? = null,
        searchMillis: Long = 0,
        seeds: List<List<RulePlacement>> = emptyList(),
        generation: Long = 0L,
    ): SchedulerState {
        // Superseded before it even got the CPU (a burst of edits, a stage queued behind a longer one):
        // stop before the advance rather than at the first checkpoint inside the search.
        if (abandoned(generation)) throw PlanAbandoned()
        val advanced = commitRecordChanges(state, advanceSchedule(state, nowMillis, noScreenEvidence()))
        if (!advanced.automaticSchedule) return advanced
        val horizon = cappedHorizon(nowMillis, horizonCapMillis)
        val mode = tpMode()
        var rules = SchedulerRunRules.EMPTY
        var cycle: ScheduleCycle? = null
        var search: SearchReport? = null
        var score: Double? = null
        val filled =
            SchedulerDomain.fillSchedule(
                advanced,
                nowMillis,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = mode,
                horizonMillis = horizon,
                rulesSink = { rules = it },
                cycleSink = { cycle = it },
                searchBudget = SearchBudget.of(searchMillis) { abandoned(generation) },
                extraSeeds = seeds,
                searchSink = { report, cost ->
                    search = report
                    score = cost
                },
            )
        val result =
            if (filled == advanced.panels && cycle == advanced.scheduleCycle) advanced
            else advanced.copy(panels = filled, scheduleCycle = cycle)
        recordRun(SchedulerRunEntry.Kind.Replan, nowMillis, mode, horizon, result, rules, search, score)
        return result
    }

    /**
     * `docs/invariants/scheduler.md` § *Progressive Calculation*: a re-plan made INSIDE a reducer, to answer a press,
     * is the first stage of a progressive fill like any other — [SchedulerDomain.PROGRESSIVE_FIRST_STAGE_MILLIS]
     * ahead, with a short search — and the engine's horizon watcher extends it from there in doubling stages. Filling
     * to $t_{goal}$ in one go here held the UI thread for the whole fill and published nothing definitive until it
     * was done.
     */
    private fun reduceInlineReplan(state: SchedulerState, nowMillis: Long): SchedulerState =
        reduceRefreshSchedule(
            state,
            nowMillis,
            horizonCapMillis = nowMillis + SchedulerDomain.PROGRESSIVE_FIRST_STAGE_MILLIS,
            searchMillis = SchedulerDomain.INLINE_REPLAN_SEARCH_MILLIS,
        )

    /**
     * `docs/invariants/scheduler.md` § *One device plans*: a re-plan whose runs come from the account's elected
     * device ([SchedulerIntent.AdoptScheduleRules]). The same advance and the same fill as [reduceRefreshSchedule],
     * with the search replaced by the placements it was handed — so this device's own environment, elapsed head
     * and frozen past are laid exactly as its own re-plan would lay them.
     */
    private fun reduceAdoptScheduleRules(state: SchedulerState, intent: SchedulerIntent.AdoptScheduleRules): SchedulerState {
        val nowMillis = intent.nowMillis
        val advanced = commitRecordChanges(state, advanceSchedule(state, nowMillis, noScreenEvidence()))
        if (!advanced.automaticSchedule) return advanced
        val horizon = maxOf(intent.horizonMillis, nowMillis)
        val mode = tpMode()
        var rules = SchedulerRunRules.EMPTY
        var cycle: ScheduleCycle? = null
        val filled =
            SchedulerDomain.fillSchedule(
                advanced,
                nowMillis,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = mode,
                horizonMillis = horizon,
                rulesSink = { rules = it },
                cycleSink = { cycle = it },
                adoptedPlacements = intent.placements,
                adoptedCycle = intent.cycle,
            )
        val result =
            if (filled == advanced.panels && cycle == advanced.scheduleCycle) advanced
            else advanced.copy(panels = filled, scheduleCycle = cycle)
        recordRun(SchedulerRunEntry.Kind.Adopted, nowMillis, mode, horizon, result, rules)
        return result
    }

    /**
     * PRD §6/§9: hand one run of the scheduler to [recordSchedulerRun] — the History window's
     * [HistorySource.SchedulerEngine] rows. Stamped on the same clock the History Units use, so an engine
     * row and a unit committed beside it sort together in the one merged timeline.
     *
     * Recorded even when the fill changed nothing: the question the row answers is "what rule state did the
     * scheduler read, and what set of rules did it return?", and a run that reproduced the same plan answered
     * it too.
     *
     * [nowMillis] and [mode] are carried because the returned rules are *parameterized by* them
     * (`docs/scheduler_requirements.md`): the same instruction list read at another position of the line, or
     * at another mode, is a different schedule, so a row that did not name them would name no schedule.
     */
    private fun recordRun(
        kind: SchedulerRunEntry.Kind,
        nowMillis: Long,
        mode: Int,
        horizonMillis: Long,
        result: SchedulerState,
        rules: SchedulerRunRules,
        search: SearchReport? = null,
        score: Double? = null,
    ) {
        if (search != null) planSearchSink(search, score)
        recordSchedulerRun(
            SchedulerRunEntry(
                timeMillis = clock.nowMillis(),
                kind = kind,
                horizonMillis = horizonMillis,
                panelCount = result.panels.size,
                ruleState = rules.ruleState,
                rules = rules.rules,
                nowMillis = nowMillis,
                tpMode = mode,
                search = search,
                score = score,
            ),
        )
    }

    /**
     * PRD §7 **"Switch task"** ([SchedulerIntent.ForceTaskSwitch]): record the user's refusal of the task the
     * now-line is on, **lay the epsilon switch entry** on the task the plan hands the line to
     * ([placeSwitchEntry]), then re-plan around it.
     *
     * The refusal is [org.example.project.scheduler.model.ForcedTaskSwitch] — a fact about the past, read by
     * the fill as the walk's `last` — not an edit to any rule, which is why it re-plans from inside this
     * reducer instead of riding [SchedulerDomain.schedulingSignature]: were it in the signature, the tick that
     * later drops the spent marker would fire a second re-plan with the refusal gone and could hand the very
     * task back. A now-line on no task at all (a screen break, a grey period, an empty stretch) is nothing to
     * switch away from, so the press is a no-op. The marker is stored even while §7 auto-scheduling is off:
     * the plan is not being computed at all then, and the refusal is honoured by the fill that resumes it.
     */
    private fun reduceForceTaskSwitch(state: SchedulerState, nowMillis: Long): SchedulerState {
        val taskId = SchedulerDomain.taskAtNowLine(state, nowMillis) ?: return state
        val refused = state.copy(forcedSwitch = ForcedTaskSwitch(taskId, nowMillis))
        // Who the plan hands the now-line to now — the task the switch entry below states the user has
        // started. The rules the last fill returned already name them ([TaskPanel.alternativeTaskId], whose
        // README use IS this press), which costs no fill at all. Where they do not — the panels of a payload
        // just loaded carry no derived rules yet — the same answer is arrived at the slow way, by re-planning
        // with the refusal standing and reading what the fill put at the line.
        val named = SchedulerDomain.alternativeTaskAt(state.panels, nowMillis)
        val replanned = if (named == null) reduceInlineReplan(refused, nowMillis) else null
        val replacement =
            (named ?: replanned?.let { SchedulerDomain.taskAtNowLine(it, nowMillis) })
                ?.takeIf { it != taskId && SchedulerDomain.isPlaceableTask(state, it) }
        // Nobody to hand it to (the sole candidate in the period still runs — the walk's own escape): there
        // is no task the user has switched TO, so there is nothing to state and the press is the refusal
        // alone, exactly as before.
        val replacementTask = replacement ?: return replanned ?: reduceInlineReplan(refused, nowMillis)
        return reduceInlineReplan(startTaskNow(refused, replacementTask, nowMillis), nowMillis)
    }

    /**
     * PRD §7: **the user has selected [taskId] to do now** — the one thing both switch chords do, and the one
     * place it is written. `Ctrl+Shift+Alt+T` names the task from the picker; `Ctrl+Shift+Alt+Z` names it by
     * refusing the one on the line and asking the plan who runs instead. From here on the two are the same
     * press.
     *
     * Two halves, and both are needed:
     *  - the **switch entry** ([placeSwitchEntry]) — an epsilon-long block on the task, authored by the user,
     *    which is what the calendar draws with the blue outline and the check box. It says *this task, from
     *    here* and nothing about how long;
     *  - the **request** ([ForcedTaskStart]) — which is what makes the task the first run after the seed.
     *    Without it the seed is only a pre-placed block, and the best continuation after a block need not be
     *    the same task. With it, the seed and that run are one panel on the score's clock, so criterion 2
     *    (`docs/scheduler_score.md`) charges its shortfall until it reaches the task's minimum — the soft
     *    *Minimum Execution Time* goal, yielding as ever to whatever the timeline restricts.
     *
     * An outstanding refusal **of this same task** is cleared, as ever: the user has now said explicitly what
     * that press said only negatively.
     */
    private fun startTaskNow(
        state: SchedulerState,
        taskId: TaskId,
        nowMillis: Long,
    ): SchedulerState =
        placeSwitchEntry(state, taskId, nowMillis).copy(
            forcedStart = ForcedTaskStart(taskId, nowMillis),
            forcedSwitch = state.forcedSwitch?.takeIf { it.taskId != taskId },
        )

    /**
     * PRD §7 **the switch entry**: the block both switch chords lay at the now-line —
     * `[now, now + `[SchedulerDomain.SWITCH_ENTRY_MILLIS]`)` on [taskId] — before the fill is re-run around
     * it. Epsilon long, deliberately: the press says WHICH task and WHEN, never for how long.
     *
     * It is an ordinary **user-authored panel**, built exactly as the calendar's own "add" builds one
     * ([reduceAddTaskPanel]) and through the same two helpers, because it is the same thing: a period the
     * user put on the timeline by hand. That is the whole of why it draws with the blue outline and the check
     * box in its top-right corner — [SchedulerDomain.isUserPlaced] is the one question those answer, and an
     * `auto = false` panel carrying the existence pin is what it says yes to. Nothing about the picker or the
     * chord reaches the calendar; the panel does.
     *
     * Pinned, so the fill treats it as a fixed obstacle ([SchedulerDomain.isSchedulerFixed]) and plans
     * *around* it rather than over it — the press would otherwise be undone by the very re-plan it asks for.
     * Committed as a Calendar history unit like every other hand-placed block, so a chord struck by accident
     * is one Ctrl+Z away.
     */
    private fun placeSwitchEntry(
        state: SchedulerState,
        taskId: TaskId,
        nowMillis: Long,
    ): SchedulerState {
        val pins = PanelPins(existence = true)
        val (panelId, allocated) = state.allocatePanelId()
        val panel =
            TaskPanel(
                id = panelId,
                taskId = taskId,
                title = state.tasks[taskId]?.title.orEmpty(),
                startEpochMillis = nowMillis,
                endEpochMillis = nowMillis + SchedulerDomain.SWITCH_ENTRY_MILLIS,
                pinned = derivePinned(pins),
                pins = pins,
                auto = false,
            )
        val (resolved, resolvedPanels) = resolveScreenOverrides(allocated, allocated.panels + panel, panelId)
        return commitPanels(resolved, resolvedPanels, label = "Switch task")
    }

    /**
     * PRD §13 **"start this task now"** ([SchedulerIntent.ForceTaskStart]): record the user's request for
     * [taskId] and re-plan on the spot, so that task is what the now-line lands on.
     *
     * The mirror image of [reduceForceTaskSwitch], for the same reasons and with the same shape: the request
     * is a [org.example.project.scheduler.model.ForcedTaskStart] — a fact about the past, read by the fill as
     * the task of its first slot — not an edit to any rule, which is why it re-plans from inside this reducer
     * instead of riding [SchedulerDomain.schedulingSignature]. Only a **placeable** task can be asked for
     * ([SchedulerDomain.isPlaceableTask]): a parent task is a grouping the scheduler never places, and a
     * tombstone is no longer in the tree, so either request would be unanswerable — and it is that same
     * predicate the menus offering this intent are built from (PRD §13's cell menu, PRD §8's block editor and
     * PRD §7's task picker), so none of them can offer a row this line then drops. Asking for a
     * task also clears an outstanding refusal **of that same task** — the user has just said explicitly what
     * the earlier press said only negatively, and leaving both standing would have the fill place the task and
     * go on refusing it. The marker is stored even while §7 auto-scheduling is off: the plan is not being
     * computed at all then, and the request is honoured by the fill that resumes it.
     *
     * The **switch entry** ([placeSwitchEntry]) is laid first, so the fill runs around a block that already
     * says what the user is doing; the marker then carries that task on past it, rather than the plan handing
     * them somebody else an instant later.
     */
    private fun reduceForceTaskStart(state: SchedulerState, taskId: TaskId): SchedulerState {
        if (!SchedulerDomain.isPlaceableTask(state, taskId)) return state
        val now = clock.nowMillis()
        return reduceInlineReplan(startTaskNow(state, taskId, now), now)
    }

    /** $t_{goal}$, or a progressive stage's cap when that comes first (never before the now-line). */
    private fun cappedHorizon(nowMillis: Long, capMillis: Long?): Long {
        val goal = scheduleHorizonEndMillis(nowMillis)
        return if (capMillis == null) goal else minOf(goal, maxOf(nowMillis, capMillis))
    }

    /**
     * PRD §9 rolling horizon ([SchedulerIntent.ExtendSchedule]): advance, then materialize the plan further
     * WITHOUT re-planning it. Everything already laid down ahead of the now-line is kept and fed to the
     * scheduling walk as committed service, so the tail continues the same plan rather than replacing it — a horizon that
     * grew (time passing, or the calendar navigating to a further week) is not a change to the scheduling
     * rules and must not rewrite what the user is looking at. A no-op tick returns the same instance.
     */
    private fun reduceExtendSchedule(
        state: SchedulerState,
        nowMillis: Long,
        horizonCapMillis: Long? = null,
        searchMillis: Long = 0,
        generation: Long = 0L,
    ): SchedulerState {
        if (abandoned(generation)) throw PlanAbandoned()
        val advanced = commitRecordChanges(state, advanceSchedule(state, nowMillis, noScreenEvidence()))
        if (!advanced.automaticSchedule) return advanced
        val materializedUntil = SchedulerDomain.firstFreeMoment(advanced.panels, nowMillis)
        val horizon = cappedHorizon(nowMillis, horizonCapMillis)
        val mode = tpMode()
        var rules = SchedulerRunRules.EMPTY
        var cycle: ScheduleCycle? = null
        var search: SearchReport? = null
        var score: Double? = null
        val filled =
            SchedulerDomain.fillSchedule(
                advanced,
                nowMillis,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = mode,
                horizonMillis = horizon,
                keepExistingUntilMillis = materializedUntil,
                rulesSink = { rules = it },
                cycleSink = { cycle = it },
                searchBudget = SearchBudget.of(searchMillis) { abandoned(generation) },
                searchSink = { report, cost ->
                    search = report
                    score = cost
                },
            )
        val result =
            if (filled == advanced.panels && cycle == advanced.scheduleCycle) advanced
            else advanced.copy(panels = filled, scheduleCycle = cycle)
        recordRun(SchedulerRunEntry.Kind.Extension, nowMillis, mode, horizon, result, rules, search, score)
        return result
    }

    /**
     * Store the user's sleep schedule and refill so the calendar immediately reflects the new sleep window.
     * The 15-min-per-2-days wake drift is anchored at [todayEpochDay] when a goal different from the current
     * wake is set (else there is no drift). Recorded as a [SleepDelta] History Unit so the change shows in
     * the History window and is Ctrl+Z-undoable (the sleep schedule is authoritative user intent, PRD §17).
     * The panels are derived, so the immediate refill below is left off the delta — an undo reverts the
     * [sleep] field and the next schedule tick re-derives the panels to match.
     */
    private fun reduceSetSleepSchedule(
        state: SchedulerState,
        sleep: SleepSchedule,
        todayEpochDay: Long,
    ): SchedulerState {
        val anchored =
            sleep.copy(anchorEpochDay = if (sleep.goalWakeMinutes != sleep.wakeMinutes) todayEpochDay else null)
        if (state.sleep == anchored) return state
        val committed = commitDelta(state, SleepDelta(state.sleep, anchored))
        // Refill so the nightly sleep window takes effect right away (when auto-scheduling is on).
        if (!committed.automaticSchedule) return committed
        val now = clock.nowMillis()
        var cycle: ScheduleCycle? = null
        val filled =
            SchedulerDomain.fillSchedule(
                committed,
                now,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = tpMode(),
                horizonMillis = cappedHorizon(now, now + SchedulerDomain.PROGRESSIVE_FIRST_STAGE_MILLIS),
                cycleSink = { cycle = it },
                searchBudget = SearchBudget.of(SchedulerDomain.INLINE_REPLAN_SEARCH_MILLIS),
            )
        return committed.copy(panels = filled, scheduleCycle = cycle)
    }

    /**
     * PRD §8 "Remove" on a record block: drop the period from the task's record (history-excluded).
     *
     * This is the one user-authored change the engine's [SchedulerDomain.schedulingSignature] watcher cannot
     * see (records are on the derived side of that signature — the schedule-advance banks them continuously,
     * so watching them would re-plan on every tick), yet it genuinely changes the past service the virtual
     * clocks are seeded from. So it refills right here, like [reduceSetSleepSchedule] does.
     */
    private fun reduceRemoveRecordPeriod(
        state: SchedulerState,
        intent: SchedulerIntent.RemoveRecordPeriod,
    ): SchedulerState {
        val task = state.tasks[intent.taskId] ?: return state
        val range = TaskTimeRange(intent.startEpochMillis, intent.endEpochMillis)
        if (range !in task.record) return state
        val updated =
            state.copy(tasks = state.tasks + (intent.taskId to task.copy(record = task.record - range)))
        if (!updated.automaticSchedule) return updated
        val now = clock.nowMillis()
        var cycle: ScheduleCycle? = null
        val filled =
            SchedulerDomain.fillSchedule(
                updated,
                now,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = tpMode(),
                horizonMillis = cappedHorizon(now, now + SchedulerDomain.PROGRESSIVE_FIRST_STAGE_MILLIS),
                cycleSink = { cycle = it },
                searchBudget = SearchBudget.of(SchedulerDomain.INLINE_REPLAN_SEARCH_MILLIS),
            )
        return updated.copy(panels = filled, scheduleCycle = cycle)
    }

    /**
     * PRD §9/§12 retroactive: apply the "assume nothing happened" rule to work banked BEFORE that rule could
     * see the OS lock history — subtract [ranges] from every ON-SCREEN task's record and materialize the
     * removed spans as "Inactivity" panels, exactly as the banking path does going forward.
     *
     * Off-screen tasks are untouched: they are ALLOWED to run in a no-screen period (PRD §9), so their records
     * over one are true. Same reason [appendRecordOutsideNoScreen] banks their whole span.
     *
     * Refills for the same reason [reduceRemoveRecordPeriod] does: the records are the frozen past the lags
     * are replayed from, so removing some genuinely changes the plan, and the engine's signature watcher cannot see it.
     * Returns the same instance when nothing was covered, so the start-up pass is a no-op on a clean account.
     */
    private fun reduceStripNoScreenRecords(
        state: SchedulerState,
        ranges: List<TaskTimeRange>,
    ): SchedulerState = stripRecords(state, ranges, affects = { it.onScreen })

    /**
     * PRD §8/§9/§12: the same rule the moment a period is **laid by hand** rather than at the next engine
     * start — the span the user just declared they were not at a screen (or not working at all) cannot hold
     * banked work, so the records under its elapsed part go.
     *
     * **Who is affected is the period's own refusal** ([periodRefuses]), the same question the override rule
     * above asks: a record is a claim that the task ran there, and the period is the statement that it could
     * not have. A `no on-screen task` period therefore exempts the off-screen tasks (§9 lets them run inside
     * one) and a grey `no task allowed` one exempts nobody — not as two cases, but because those are the two
     * resiliences. A kind the account defined strips exactly the tasks it left at `0`.
     *
     * A record is not an Undo/Redo unit (it lives outside the history, like every other banking side effect),
     * so undoing the period restores the panels it trimmed but not the records it stripped — the same
     * contract [reducePinRecord] and the advance tick already work under.
     */
    private fun stripRecordsUnderPeriod(state: SchedulerState, panel: TaskPanel): SchedulerState {
        val kind = panel.restrictiveKind
        return stripRecords(
            state,
            listOf(TaskTimeRange(panel.startEpochMillis, panel.endEpochMillis)),
            affects = { task -> task.resilienceFor(kind) <= 0.0 },
        )
    }

    /**
     * Subtracts [ranges] from the record of every affected task (see [reduceStripNoScreenRecords] /
     * [stripRecordsUnderPeriod] for which tasks those are). What the strip vacates holds no panel: it is
     * idle time, and the calendar draws it as a DERIVED grey band. Nothing is materialized — a period the
     * user did not draw is never written into [SchedulerState.panels]. Returns the same instance when
     * nothing was covered.
     */
    private fun stripRecords(
        state: SchedulerState,
        ranges: List<TaskTimeRange>,
        affects: (Task) -> Boolean,
    ): SchedulerState {
        if (ranges.isEmpty()) return state
        val merged = SchedulerDomain.mergeOccupied(ranges)
        val removed = ArrayList<TaskTimeRange>()
        var tasks = state.tasks
        for ((id, task) in state.tasks) {
            if (!affects(task) || task.record.isEmpty()) continue
            val kept = SchedulerDomain.subtractRegions(task.record, merged)
            if (kept == task.record) continue
            removed += SchedulerDomain.intersectRegions(task.record, merged)
            tasks = tasks + (id to task.copy(record = kept))
        }
        if (removed.isEmpty()) return state
        val stripped = state.copy(tasks = tasks)
        if (!stripped.automaticSchedule) return stripped
        val now = clock.nowMillis()
        var cycle: ScheduleCycle? = null
        val filled =
            SchedulerDomain.fillSchedule(
                stripped,
                now,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = tpMode(),
                horizonMillis = cappedHorizon(now, now + SchedulerDomain.PROGRESSIVE_FIRST_STAGE_MILLIS),
                cycleSink = { cycle = it },
                searchBudget = SearchBudget.of(SchedulerDomain.INLINE_REPLAN_SEARCH_MILLIS),
            )
        return stripped.copy(panels = filled, scheduleCycle = cycle)
    }

    /**
     * `docs/invariants/persistence.md` § *One history, per-device undo*: undo THIS device's newest applied unit of
     * [category] — a unit another device made is never walked, whatever it sits between.
     */
    private fun undo(state: SchedulerState, category: HistoryCategory): SchedulerState {
        val history = state.histories.forCategory(category)
        val me = deviceId()
        val index = history.units.indexOfLast { it.ownedBy(me) && !it.undone }
        if (index < 0) return state
        val unit = history.units[index]
        val undone = unit.delta.undo(state)
        val units = history.units.toMutableList().also { it[index] = unit.copy(undone = true, changedAtMillis = clock.nowMillis()) }
        val moved = undone.copy(histories = state.histories.withCategory(category, historyOf(units, me)))
        return if (category == HistoryCategory.Edit) syncEditDraft(moved) else moved
    }

    /**
     * Undo THIS device's newest applied unit among [walk]'s — across its categories, in the order the units were
     * made ([HistoryUnit.deviceSeq], which orders one device's units whatever their category).
     */
    private fun undoIn(state: SchedulerState, walk: HistoryWalk): SchedulerState {
        val me = deviceId()
        val newest =
            walk.categories.mapNotNull { category ->
                val units = state.histories.forCategory(category).units
                val index = units.indexOfLast { it.ownedBy(me) && !it.undone && walk.accepts(category, it) }
                if (index < 0) null else Triple(category, index, units[index].deviceSeq)
            }.maxByOrNull { it.third } ?: return state
        return walkUnit(state, newest.first, newest.second, undo = true)
    }

    /** Redo THIS device's oldest undone unit among [walk]'s. */
    private fun redoIn(state: SchedulerState, walk: HistoryWalk): SchedulerState {
        val me = deviceId()
        val oldest =
            walk.categories.mapNotNull { category ->
                val units = state.histories.forCategory(category).units
                val index = units.indexOfFirst { it.ownedBy(me) && it.undone && walk.accepts(category, it) }
                if (index < 0) null else Triple(category, index, units[index].deviceSeq)
            }.minByOrNull { it.third } ?: return state
        return walkUnit(state, oldest.first, oldest.second, undo = false)
    }

    /** Undo or redo the unit at [index] of [category] — [undo] and [redo]'s one step. */
    private fun walkUnit(state: SchedulerState, category: HistoryCategory, index: Int, undo: Boolean): SchedulerState {
        val history = state.histories.forCategory(category)
        val unit = history.units[index]
        val walked = if (undo) unit.delta.undo(state) else unit.delta.redo(state)
        val units = history.units.toMutableList().also { it[index] = unit.copy(undone = undo, changedAtMillis = clock.nowMillis()) }
        return walked.copy(histories = state.histories.withCategory(category, historyOf(units, deviceId())))
    }

    /** Redo THIS device's oldest undone unit of [category]. */
    private fun redo(state: SchedulerState, category: HistoryCategory): SchedulerState {
        val history = state.histories.forCategory(category)
        val me = deviceId()
        val index = history.units.indexOfFirst { it.ownedBy(me) && it.undone }
        if (index < 0) return state
        val unit = history.units[index]
        val redone = unit.delta.redo(state)
        val units = history.units.toMutableList().also { it[index] = unit.copy(undone = false, changedAtMillis = clock.nowMillis()) }
        val moved = redone.copy(histories = state.histories.withCategory(category, historyOf(units, me)))
        return if (category == HistoryCategory.Edit) syncEditDraft(moved) else moved
    }

    private fun reduceMergePeerHistory(state: SchedulerState, intent: SchedulerIntent.MergePeerHistory): SchedulerState {
        val me = deviceId()
        var next = state
        for (name in intent.units.keys + intent.dropped.keys) {
            val category = runCatching { HistoryCategory.valueOf(name) }.getOrNull() ?: continue
            next = mergePeerUnits(next, category, intent.units[name].orEmpty(), me, intent.dropped[name].orEmpty())
        }
        return next
    }

    /** A category's history with its pointer on [me]'s newest applied unit (what the History window marks). */
    internal fun historyOf(units: List<HistoryUnit>, me: String): SchedulerHistory =
        SchedulerHistory(pointer = units.indexOfLast { it.ownedBy(me) && !it.undone }, units = units)

    /**
     * `docs/invariants/persistence.md` § *One history, per-device undo*: give every unit that has no owner — written
     * before units had one — to [me], numbered as a unit of [me] is, and undone when it sat past its category's
     * pointer. Run on load; a no-op once every unit has an owner.
     */
    fun claimUnownedUnits(state: SchedulerState, me: String): SchedulerState {
        if (state.histories.all().none { (_, h) -> h.units.any { it.deviceId.isEmpty() } }) return state
        var histories = state.histories
        for ((category, history) in state.histories.all()) {
            if (history.units.none { it.deviceId.isEmpty() }) continue
            val units = history.units.mapIndexed { index, unit ->
                if (unit.deviceId.isNotEmpty()) unit
                else unit.copy(
                    deviceId = me,
                    deviceSeq = deviceSeqOf(unit.timeMillis, unit.chronoId),
                    undone = unit.undone || index > history.pointer,
                    // Claimed now, so the next sync gives the account this device's older history too.
                    changedAtMillis = clock.nowMillis(),
                )
            }
            histories = histories.withCategory(category, historyOf(units, me))
        }
        return state.copy(histories = histories)
    }

    /**
     * A unit's number among its device's units of a category: its commit instant and tie-break, which never repeat
     * for one device — so a number is never handed out twice, even after the unit that had it is evicted.
     */
    internal fun deviceSeqOf(timeMillis: Long, chronoId: Long): Long = timeMillis * 1000 + chronoId

    /**
     * `docs/invariants/persistence.md` § *One history, per-device undo*: [state]'s history of [category] with the
     * units the account's OTHER devices have — new ones inserted in time order, known ones taking their device's
     * `undone` flag — capped like a commit. Nothing is applied: what another device changed reaches the state
     * through the sync of the state itself; its units are there to be seen, and undone on the device that made them.
     */
    fun mergePeerUnits(
        state: SchedulerState,
        category: HistoryCategory,
        peerUnits: List<HistoryUnit>,
        me: String,
        dropped: Set<Pair<String, Long>> = emptySet(),
    ): SchedulerState {
        val foreign = peerUnits.filter { it.deviceId != me && it.deviceId.isNotEmpty() }
        if (foreign.isEmpty() && dropped.isEmpty()) return state
        val history = state.histories.forCategory(category)
        val byIdentity = LinkedHashMap<Pair<String, Long>, HistoryUnit>()
        for (u in history.units) byIdentity[u.deviceId to u.deviceSeq] = u
        var changed = false
        // A redo branch its device discarded: forgotten here too.
        for (identity in dropped) if (identity.first != me && byIdentity.remove(identity) != null) changed = true
        for (u in foreign) {
            val known = byIdentity[u.deviceId to u.deviceSeq]
            if (known == null) {
                byIdentity[u.deviceId to u.deviceSeq] = u
                changed = true
            } else if (known.undone != u.undone) {
                byIdentity[u.deviceId to u.deviceSeq] = known.copy(undone = u.undone)
                changed = true
            }
        }
        if (!changed) return state
        val merged = byIdentity.values.sortedWith(compareBy({ it.timeMillis }, { it.chronoId }, { it.deviceId }, { it.deviceSeq }))
        val (capped, _) = dropOldestUntainted(merged, (merged.size - MAX_HISTORY_UNITS).coerceAtLeast(0))
        return state.copy(histories = state.histories.withCategory(category, historyOf(capped, me)))
    }

    /**
     * In-session Edit Mode undo/redo replays tree deltas, so the live [SchedulerEditSession.draftText]
     * (and therefore the text field) must be re-pulled from the edited cell's current title.
     */
    private fun syncEditDraft(state: SchedulerState): SchedulerState {
        val session = state.editSession ?: return state
        val title = state.cells[session.cellId]?.taskId?.let { state.tasks[it]?.title }.orEmpty()
        return if (title == session.draftText) {
            state
        } else {
            state.copy(editSession = session.copy(draftText = title))
        }
    }

    /**
     * Task-tree selector: make [id] the live tree. The tree being left is **flushed** into its own entry
     * first ([SchedulerState.withActiveTaskTreeFlushed]) — the trees are live alternatives, not frozen
     * backups, so everything done in one must be there when the user comes back to it.
     *
     * The whole swap is one [TaskTreeDelta], which is also what keeps the Main history coherent across
     * trees: undo walks units in order, so a tree mutation recorded under the previous tree can only be
     * reached after this unit has already put that tree back.
     */
    private fun reduceSelectTaskTree(state: SchedulerState, id: TaskTreeId): SchedulerState {
        if (state.activeTaskTreeId == id) return state
        val flushed = state.withActiveTaskTreeFlushed()
        val target = flushed.taskTrees.firstOrNull { it.id == id } ?: return state
        val after = flushed.withTaskTreeLoaded(target)
        return commitDelta(
            state,
            TaskTreeDelta(
                before = state.captureTaskTreeState(),
                after = after.captureTaskTreeState(),
                label = "Task tree \"${target.title.ifBlank { SchedulerDomain.UNTITLED_LABEL }}\"",
            ),
        )
    }

    /**
     * Task-tree selector: create a tree named [title] holding a copy of what is on screen and select it. The
     * live tree itself does not change (the copy IS it) — only the identity does, so nothing reschedules;
     * the two trees diverge from the user's next edit onward. The tree being left is flushed first, so its
     * stored copy is current before it stops being the live one.
     */
    private fun reduceCreateTaskTree(state: SchedulerState, title: String): SchedulerState {
        val name = title.trim()
        if (name.isEmpty()) return state
        val (id, allocated) = state.withActiveTaskTreeFlushed().allocateTaskTreeId()
        val entry =
            TaskTreeEntry(
                id = id,
                title = name,
                tree = state.captureTreeWithRecords(),
                expanded = state.expanded,
            )
        val after = allocated.copy(taskTrees = allocated.taskTrees + entry, activeTaskTreeId = id)
        return commitDelta(
            state,
            TaskTreeDelta(
                before = state.captureTaskTreeState(),
                after = after.captureTaskTreeState(),
                label = "New task tree \"$name\"",
            ),
        )
    }

    /** Task-tree selector (Rename mode): the same tree under a new name — content and id are untouched. */
    private fun reduceRenameTaskTree(state: SchedulerState, id: TaskTreeId, title: String): SchedulerState {
        val name = title.trim()
        val entry = state.taskTrees.firstOrNull { it.id == id } ?: return state
        if (name.isEmpty() || name == entry.title) return state
        val after =
            state.copy(taskTrees = state.taskTrees.map { if (it.id == id) it.copy(title = name) else it })
        return commitDelta(
            state,
            TaskTreeDelta(
                before = state.captureTaskTreeState(),
                after = after.captureTaskTreeState(),
                label = "Rename task tree \"${entry.title}\" → \"$name\"",
            ),
        )
    }

    /**
     * "All task trees": put [id] on the timeline at [dateMillis], or take it off with `null`. Only the
     * entry's own date moves — the tree's content and which tree is live are untouched — but the plan
     * itself changes, since the dated trees are what the scheduler blends between
     * ([SchedulerDomain.blendedTaskPriorities]); the debounced rule-change watcher picks that up because
     * the dates are part of [SchedulerDomain.schedulingSignature].
     */
    private fun reduceSetTaskTreeDate(state: SchedulerState, id: TaskTreeId, dateMillis: Long?): SchedulerState {
        val entry = state.taskTrees.firstOrNull { it.id == id } ?: return state
        if (entry.dateMillis == dateMillis) return state
        val after =
            state.copy(
                taskTrees = state.taskTrees.map { if (it.id == id) it.copy(dateMillis = dateMillis) else it },
            )
        return commitDelta(
            state,
            TaskTreeDelta(
                before = state.captureTaskTreeState(),
                after = after.captureTaskTreeState(),
                label =
                    if (dateMillis == null) "Task tree \"${entry.title}\" off the timeline"
                    else "Date task tree \"${entry.title}\"",
            ),
        )
    }

    /**
     * "All task trees" (the bin button): delete [id]. Deleting the **live** tree is deliberately not
     * destructive — the live tree fields stay exactly as they are and simply stop being named
     * (`activeTaskTreeId = null`, the state a never-named account is already in), so the bin can only ever
     * cost the user a name, never a tree's worth of work. Deleting an inactive tree does discard its stored
     * copy, which is what the button is for; Undo puts it back whole.
     */
    private fun reduceDeleteTaskTree(state: SchedulerState, id: TaskTreeId): SchedulerState {
        val entry = state.taskTrees.firstOrNull { it.id == id } ?: return state
        // Flush first: if another tree is live, its own stored copy must be current before this one's
        // removal is captured as the "after" side, or the switch away would resurrect a stale snapshot.
        val flushed = state.withActiveTaskTreeFlushed()
        val after =
            flushed.copy(
                taskTrees = flushed.taskTrees.filterNot { it.id == id },
                activeTaskTreeId = flushed.activeTaskTreeId?.takeIf { it != id },
            )
        return commitDelta(
            state,
            TaskTreeDelta(
                before = state.captureTaskTreeState(),
                after = after.captureTaskTreeState(),
                label = "Delete task tree \"${entry.title.ifBlank { SchedulerDomain.UNTITLED_LABEL }}\"",
            ),
        )
    }

    /**
     * PRD §5 the task-relations window: section 1's own **✕** — strike the pair off the list entirely.
     *
     * Two halves, because a pair reaches the list by two routes:
     *
     * - the **mark** is struck off ([TaskRelationMark.hidden]), which is what silences the half the marks carry
     *   — a pair the relative-priority window has been opened on or retargeted. Not undoable, like every other
     *   mark: it changes no priority.
     * - the **weight-table rows** the pair is made of are removed ([TaskRelationsDomain.withoutWeightTableRows]).
     *   An optional row of the target sub-list's priority-weight table IS the relation, so leaving it standing
     *   would leave the user's own table asserting a pair they have just said is not theirs — and there is no
     *   other gesture in the app that takes a row back out of a table. That half is a **tree change**, so it
     *   commits one history unit, exactly as [SchedulerIntent.SetPriorityWeightTableRow] does for the row's
     *   creation: the add and its inverse must be undoable the same way.
     *
     * A pair with no such row commits nothing — no empty history unit for Ctrl+Z to walk back over (the same
     * rule the weight window's Cancel follows).
     */
    private fun reduceDropTaskRelation(state: SchedulerState, key: TaskRelationKey): SchedulerState {
        val marked = reduceMarkTaskRelation(state, key) { it.copy(kept = false, hidden = true) }
        if (TaskRelationsDomain.withoutWeightTableRows(marked, key) === marked) return marked
        return commitDelta(
            marked,
            priorityTreeDelta(marked, "Remove optional table task") {
                TaskRelationsDomain.withoutWeightTableRows(it, key)
            },
        )
    }

    private fun commitDelta(
        state: SchedulerState,
        forward: Delta,
        category: HistoryCategory = HistoryCategory.Main,
    ): SchedulerState {
        val newState = forward.commit(state)
        val history = state.histories.forCategory(category)
        val me = deviceId()

        // PRD §5 Branching: a new mutation after an undo orphans the redo units — THIS device's undone units are
        // dropped (`docs/invariants/persistence.md` § *One history, per-device undo*); another device's are its own.
        val retained = history.units.filterNot { it.ownedBy(me) && it.undone }

        // PRD §5/§6: a live-edited field commits on every keystroke, so a unit carrying a
        // [Delta.coalesceKey] is offered to the unit at the pointer first — the same field, in the same
        // focus session, is ONE History Unit and one Ctrl+Z. The merged unit keeps the previous one's
        // timestamp (the gesture began there) and its `before` side, so undoing walks the whole edit back
        // to what the field held when it took the focus. Nothing is appended, so the cap cannot bite here.
        if (forward.coalesceKey != null) {
            val previousIndex = retained.indexOfLast { it.ownedBy(me) }
            val previous = retained.getOrNull(previousIndex)
            val merged = previous?.let { forward.coalesceOnto(it.delta) }
            if (merged != null) {
                val absorbed =
                    retained.toMutableList().also {
                        it[previousIndex] =
                            previous.copy(
                                delta = merged,
                                // A gesture that began on the real clock and ended on the diverged one is
                                // tainted: the restart rollback must still reach it.
                                debugTainted = previous.debugTainted || debugTainting(),
                                changedAtMillis = clock.nowMillis(),
                            )
                    }
                return newState.copy(histories = state.histories.withCategory(category, historyOf(absorbed, me)))
            }
        }

        // PRD §6: stamp the change's wall-clock time; chronoId stays 0 unless a unit already shares this exact
        // timestamp, in which case it is the next tie-break index (1, 2, …). Counted across EVERY category, so a
        // device's units are totally ordered by `deviceSeq` whatever their category: Shift+Alt+arrows walk two
        // stacks at once, and a press that moves the focus and selects commits one unit in each in the same
        // millisecond — which must still be walked back selection first, focus second.
        val now = clock.nowMillis()
        val chronoId = state.histories.all().sumOf { (_, h) -> h.units.count { it.timeMillis == now } }.toLong()
        val newUnit =
            HistoryUnit(
                timeMillis = now,
                chronoId = chronoId,
                delta = forward,
                debugTainted = debugTainting(),
                // PRD §6: where the change was made. A merged gesture above keeps the previous unit's
                // window along with its timestamp — one gesture is one window.
                window = if (stampsWindow) newState.focusedWindow else null,
                deviceId = me,
                deviceSeq = deviceSeqOf(now, chronoId),
                changedAtMillis = now,
            )
        val appendedUnits = retained + newUnit

        // PRD §5: each category's history list is capped — drop the oldest units once it exceeds
        // [MAX_HISTORY_UNITS], shifting the pointer back by however many were removed. Debug-tainted
        // units are exempt from the cap: dropping one would break the chain the restart rollback walks,
        // leaving a half-reverted state, so only the oldest *untainted* units are evicted.
        val overflow = (appendedUnits.size - MAX_HISTORY_UNITS).coerceAtLeast(0)
        val (cappedUnits, _) = dropOldestUntainted(appendedUnits, overflow)

        return newState.copy(histories = state.histories.withCategory(category, historyOf(cappedUnits, me)))
    }

    /**
     * PRD §8/§9: records the completed-work [record] changes between [before] and [after] (the periods
     * appended as auto panels elapse in [advanceSchedule] / a device-sleep cut) as a single Main
     * [RecordDelta], so they are Ctrl+Z-undoable and reverted by the debug-time restart rollback.
     * Returns [after] with that unit appended; every non-record field of [after] (the advanced panel
     * list, rested screen breaks, …) is preserved. A no-op when no record changed.
     */
    private fun commitRecordChanges(before: SchedulerState, after: SchedulerState): SchedulerState {
        val changedIds =
            (before.tasks.keys + after.tasks.keys).filter { id ->
                before.tasks[id]?.record.orEmpty() != after.tasks[id]?.record.orEmpty()
            }
        if (changedIds.isEmpty()) return after
        val recBefore = changedIds.associateWith { before.tasks[it]?.record.orEmpty() }
        val recAfter = changedIds.associateWith { after.tasks[it]?.record.orEmpty() }
        // Commit against `after` (records already applied), so RecordDelta.redo is a no-op replay and
        // `after`'s other fields survive; the unit still carries `recBefore` for undo and rollback.
        return commitDelta(after, RecordDelta(recBefore, recAfter), HistoryCategory.Main)
    }

    /**
     * Drops up to [count] of the oldest **untainted** units from the front of [units], leaving every
     * debug-tainted unit in place. Returns the surviving list and how many were actually removed (so
     * the caller can shift the history pointer). Used to enforce [MAX_HISTORY_UNITS] without evicting a
     * unit the restart rollback still needs.
     */
    private fun dropOldestUntainted(units: List<HistoryUnit>, count: Int): Pair<List<HistoryUnit>, Int> {
        if (count <= 0) return units to 0
        var budget = count
        var removed = 0
        val kept = ArrayList<HistoryUnit>(units.size)
        for (unit in units) {
            if (budget > 0 && !unit.debugTainted) {
                budget--
                removed++
            } else {
                kept.add(unit)
            }
        }
        return kept to removed
    }

    /**
     * PRD §6 debug-time rollback: at app start, revert every History Unit committed under the diverged
     * debug clock ([HistoryUnit.debugTainted]) and drop it from history, so fast-forwarding never leaves
     * future-dated changes in the real saved data. The *applied* tainted units (those at or before their
     * category's pointer) are undone newest-first across every category — they are the most recent
     * changes (future-dated timestamps), so unwinding in reverse commit order restores the snapshot
     * deltas cleanly. Then all tainted units (applied or still in a redo branch) are removed and each
     * pointer is shifted back past the dropped units it had already applied.
     *
     * Assumes tainted units are the tail of the timeline (the debug clock stays diverged until reset or
     * restart). The pathological "reset to real time mid-session, then edit, then restart" ordering —
     * where an untainted unit sits *after* a tainted one on the same slice — is not specially handled;
     * see [[scheduler-history-architecture]]. A no-op when nothing is tainted.
     */
    fun rollbackDebugTainted(state: SchedulerState): SchedulerState {
        val histories = state.histories
        if (!histories.hasPendingDebugRollback) return state

        val me = deviceId()
        val appliedTainted =
            histories.all().flatMap { (_, history) ->
                history.units.filter { unit -> unit.debugTainted && unit.ownedBy(me) && !unit.undone }
            }.sortedWith(
                compareByDescending<HistoryUnit> { it.timeMillis }.thenByDescending { it.chronoId },
            )
        var reverted = state
        for (unit in appliedTainted) reverted = unit.delta.undo(reverted)

        var newHistories = histories
        for ((category, history) in histories.all()) {
            if (history.units.none { it.debugTainted }) continue
            newHistories = newHistories.withCategory(category, historyOf(history.units.filterNot { it.debugTainted }, me))
        }
        return reverted.copy(histories = newHistories)
    }

    /** PRD §5: the maximum number of History Units retained per category (oldest dropped beyond this). */
    private const val MAX_HISTORY_UNITS = 1000
}

/**
 * PRD §4 Paste: rebuild a copied subtree forest at [targetCellId]. The first root populates the target
 * cell (and its descendants); each further root is added as a sibling below. Recurses into each node's
 * children, which become a populated child sub-list — each pasted cell gets a freshly allocated `taskId`
 * (via [applySetCellTitle] on an empty cell), so the constraints (no duplicate id in a list, no ancestor
 * cycle) hold by construction.
 */
private fun pasteTreeAtCell(
    state: SchedulerState,
    targetCellId: CellId,
    nodes: List<SchedulerDomain.CopiedNode>,
): SchedulerState {
    if (nodes.isEmpty()) return state
    var working = pasteNodeInto(state, targetCellId, nodes.first())
    var afterId = targetCellId
    for (node in nodes.drop(1)) {
        val (withCell, newId) = insertEmptyCellAfter(working, afterId)
        working = pasteNodeInto(withCell, newId, node)
        afterId = newId
    }
    return working
}

/**
 * PRD §13 paste: how [pasteNodeInto] resolves the identity of the task a copied node lands on.
 *
 * [Mirror] — the clipboard's id still names a live, titled task this cell may hold, so the cell is pointed
 * at **that task**: the copy comes back as a mirror of the original, and its own sub-list (which belongs to
 * the task id, not to the cell) is what shows under it, so the clipboard's children are not rebuilt.
 *
 * [Restore] — the id is free (the task was cut, or the clipboard predates this account's tree), so the task
 * is rebuilt **under that same id**, fields, children and all. The id counter is walked past it so the next
 * allocation cannot hand it out again.
 *
 * [Fresh] — no id in the clipboard (a plain title tree, or a pre-1.6.0 payload), or one the tree cannot
 * honour (it would duplicate a task inside one sub-tree — [SchedulerDomain.canAssignTaskId]): a new task is
 * minted with the copied content, exactly as paste always did.
 *
 * A PRD §13 **"copy task id"** payload ([SchedulerDomain.CopiedNode.reference]) is the one shape that may
 * only be [Mirror]: it carries no title and no field, so there is nothing for the other two to build.
 */
private enum class PasteIdentity { Mirror, Restore, Fresh }

/**
 * Set [cellId] to [node]'s task and rebuild [node]'s children under it (recursively). PRD §4: also
 * restores the copied priority-weight values — the cell's own weight row, the minimum time of its task,
 * and (before recursing) the header of the sub-list it parents. PRD §13: plus everything the cell's Edit
 * window holds — the no-screen switch, the schedule unit and the task text.
 *
 * PRD §4/§13: the copied cell **replaces** the target cell. The identity it lands on is [PasteIdentity];
 * whichever it is, the cell is bound by forcing that id through [applySetCellTitle] (never by renaming
 * whatever task the cell held), so a populated target is *vacated* by the code that already owns
 * re-pointing a cell — its task keeps its title and, when its sub-list is populated, stays a detached
 * parent the id can bring back.
 */
private fun pasteNodeInto(
    state: SchedulerState,
    cellId: CellId,
    node: SchedulerDomain.CopiedNode,
): SchedulerState {
    val live = node.taskId?.let { state.tasks[it] }
    val identity =
        when {
            node.taskId == null -> PasteIdentity.Fresh
            // A blank title is what deletes (PRD §4), so a blank-titled task under this id is not a task to
            // mirror — it is the husk of one, and the clipboard's own content is what should come back.
            live != null && live.title.isNotBlank() ->
                if (SchedulerDomain.canAssignTaskId(state, cellId, node.taskId)) PasteIdentity.Mirror
                else PasteIdentity.Fresh
            else -> PasteIdentity.Restore
        }
    // PRD §13 "copy task id": a bare reference carries an identity and nothing else, so the one thing it
    // can mean is "point this cell at that task". An id naming no live titled task, or one this cell cannot
    // hold, is a no-op — Restore would rebuild the task under the blank title that deletes, and Fresh would
    // mint an untitled clone of nothing.
    if (node.reference && identity != PasteIdentity.Mirror) return state
    var working = state
    val taskId =
        when (identity) {
            PasteIdentity.Mirror, PasteIdentity.Restore -> {
                val id = node.taskId!!
                if (identity == PasteIdentity.Restore) working = working.reserveTaskId(id)
                id
            }
            PasteIdentity.Fresh -> working.allocateTaskId().let { (id, next) -> working = next; id }
        }
    // A mirrored task keeps the title it has now — the clipboard's copy of it may be stale, and renaming a
    // task is not what pasting a mirror of it means.
    val title = if (identity == PasteIdentity.Mirror) live!!.title else node.title
    working = applySetCellTitle(working, cellId, title, forceTaskId = taskId)
    if (working.cells[cellId]?.taskId != taskId) return state
    // Links the task under its new parent (childTaskIds) and merges the occurrence — the same primitive the
    // "change task" menu and the default-subtree graft drive, rather than a second copy of those rules.
    working = applyAssignTaskId(working, cellId, taskId)
    // Restore this cell's priority-weight row (PRD §4/§5). The row belongs to the CELL, so a mirror gets it
    // too — but a bare id reference says nothing about a weight, so the cell keeps the row it already had.
    if (!node.reference) {
        working.cells[cellId]?.let { c ->
            working = working.copy(cells = working.cells + (cellId to c.copy(priorityWeights = node.rowWeights)))
        }
    }
    // A mirror is the task that is already there: its own fields and its own sub-tree win.
    if (identity == PasteIdentity.Mirror) return working
    working.tasks[taskId]?.let { t ->
        // Restore the minimum time (PRD §4/§10) only when the clipboard carried one — a plain
        // tab-indented title tree must leave the fresh task's default alone. The PRD §13 Edit-window
        // fields have no such distinction: their empty value *is* the default a fresh task gets.
        working = working.copy(
            tasks = working.tasks + (
                taskId to t.copy(
                    minimumMinutes = node.minMinutes?.coerceAtLeast(0) ?: t.minimumMinutes,
                    resilience = node.resilience,
                    scheduleUnit = node.scheduleUnit,
                    text = node.text,
                )
            ),
        )
        // PRD §5: the clipboard names categories by TITLE, so they land the way typing a name into the
        // row's field does — on the category of that name where the account has one, on a new one where it
        // has not. That is what makes a copy carry the labels between two trees of one account without the
        // ids having to survive a paste that deliberately re-mints them.
        working = attachCategoriesByTitle(working, taskId, node.categories)
    }
    // PRD §4/§7 **Default sub-tree**: pasting FOREIGN text onto an empty cell creates a task exactly as
    // typing its title does, so the template is grafted under it. The gate is the clipboard's **id**, not
    // [PasteIdentity]: an id means the app wrote this text, so what is landing is a task's own content —
    // a copy of a sub-tree must come back as itself, whether it lands as a Mirror, a Restore, or (because
    // [SchedulerDomain.canAssignTaskId] refused the id here) a Fresh clone. Only a payload with no id at
    // all — another app's tab-indented list, or a pre-1.6.0 clipboard — is a task the user is creating.
    // [graftDefaultSubtree]'s own "sub-list still untouched" guard then keeps a node that brought children
    // of its own from being seeded on top of them, so this fires only on a bare new leaf.
    fun seeded(s: SchedulerState): SchedulerState =
        if (node.taskId == null) graftDefaultSubtree(s, cellId, state.tasks.keys) else s

    if (node.children.isEmpty()) return seeded(working)
    // A non-blank title gives the cell a child sub-list with one empty placeholder (applySetCellTitle).
    val childListId = working.tasks[taskId]?.childListId ?: return seeded(working)
    // Restore the child sub-list's weight-column header (PRD §4/§5).
    working.lists[childListId]?.let { l ->
        working = working.copy(lists = working.lists + (childListId to l.copy(weightColumns = node.childHeader)))
    }
    val placeholder = working.lists[childListId]?.cellIds?.firstOrNull() ?: return seeded(working)
    working = pasteNodeInto(working, placeholder, node.children.first())
    var afterId = placeholder
    for (child in node.children.drop(1)) {
        val (withCell, newId) = insertEmptyCellAfter(working, afterId)
        working = pasteNodeInto(withCell, newId, child)
        afterId = newId
    }
    return seeded(working)
}

private fun insertEmptyCellAfter(
    state: SchedulerState,
    afterCellId: CellId,
): Pair<SchedulerState, CellId> {
    val cell = state.cells[afterCellId] ?: return state to afterCellId
    val list = state.lists[cell.parentListId] ?: return state to afterCellId
    val index = list.cellIds.indexOf(afterCellId)
    if (index < 0) return state to afterCellId

    val (newCellId, withId) = state.allocateCellId(list.id)
    val newCell =
        Cell(
            id = newCellId,
            parentListId = list.id,
            taskId = null,
        )
    val newCellIds = list.cellIds.toMutableList()
    newCellIds.add(index + 1, newCellId)
    return withId.copy(
        cells = withId.cells + (newCellId to newCell),
        lists = withId.lists + (list.id to list.copy(cellIds = newCellIds)),
    ) to newCellId
}

/**
 * When cleanup removes cells, keep selection on the cell that slid into the removed cell's
 * index (typically the next sibling below), or clear it when nothing remains selectable.
 */
private fun adjustSelectionAfterRemovedCells(
    beforeCleanup: SchedulerState,
    afterCleanup: SchedulerState,
    selection: SchedulerSelection,
): SchedulerSelection {
    fun resolveMain(oldMain: CellId?): CellId? {
        if (oldMain == null) return null
        if (oldMain in afterCleanup.cells) return oldMain
        val cell = beforeCleanup.cells[oldMain] ?: return null
        val list = beforeCleanup.lists[cell.parentListId] ?: return null
        val index = list.cellIds.indexOf(oldMain)
        if (index < 0) return null
        val afterList = afterCleanup.lists[cell.parentListId] ?: return null
        return afterList.cellIds
            .getOrNull(index.coerceAtMost(afterList.cellIds.lastIndex))
            ?.takeIf { SchedulerDomain.isSelectableCell(afterCleanup, it) }
    }

    val newSelected = selection.selected.filter { it in afterCleanup.cells }.toSet()
    val newMain = resolveMain(selection.main)
    val newAnchor = selection.rangeAnchor?.takeIf { it in afterCleanup.cells }
    val renderVia =
        when {
            newMain == null -> null
            selection.renderVia != null &&
                SchedulerDomain.isInVisualSubtree(afterCleanup, newMain, selection.renderVia) ->
                selection.renderVia
            else -> SchedulerDomain.resolveSelectionRenderVia(afterCleanup, newMain, prior = selection)
        }
    return SchedulerSelection(
        main = newMain,
        selected = newSelected,
        rangeAnchor = newAnchor,
        renderVia = renderVia,
    )
}

private fun selectionFor(
    state: SchedulerState,
    main: CellId?,
    selected: Set<CellId> = emptySet(),
    rangeAnchor: CellId? = null,
    explicitVia: CellId? = null,
    prior: SchedulerSelection = state.selection,
): SchedulerSelection {
    val renderVia =
        main?.let {
            SchedulerDomain.resolveSelectionRenderVia(state, it, explicitVia, prior)
        }
    return SchedulerSelection(
        main = main,
        selected = selected,
        rangeAnchor = rangeAnchor,
        renderVia = renderVia,
    )
}

private fun evaluatePostEditCleanup(state: SchedulerState): SchedulerState {
    val cells = state.cells.toMutableMap()
    val lists = state.lists.toMutableMap()
    val tasks = state.tasks.toMutableMap()
    var changed = false

    for ((listId, list) in state.lists) {
        if (list.cellIds.size <= 1) continue
        val lastId = list.cellIds.last()
        val retained =
            list.cellIds.filter { cellId ->
                val removable = cellId != lastId && isTextuallyEmptyCell(state, cellId)
                if (removable) {
                    val removed = cells.remove(cellId)
                    removed?.taskId?.let { taskId ->
                        tasks[taskId]?.let { task ->
                            tasks[taskId] = task.copy(occurrences = task.occurrences - cellId)
                        }
                    }
                    changed = true
                }
                !removable
            }
        if (retained.size != list.cellIds.size) {
            lists[listId] = list.copy(cellIds = retained)
        }
    }

    // Always sweep for detached subtrees: emptying a *parent* cell leaves its children dangling under it
    // without removing any placeholder, so the loop above may report no change yet a subtree still needs
    // collecting (PRD §4, see [SchedulerDomain.pruneDetachedTree]). pruneDetachedTree folds in the orphan-task
    // purge, and short-circuits to a plain purge when nothing is detached.
    val afterRemoval = if (changed) state.copy(cells = cells, lists = lists, tasks = tasks) else state
    return SchedulerDomain.pruneDetachedTree(afterRemoval)
}

private fun isTextuallyEmptyCell(state: SchedulerState, cellId: CellId): Boolean =
    SchedulerDomain.isTextuallyEmptyCell(state, cellId)

/**
 * PRD §4 Auto-Expansion invariant: a list always ends with an empty placeholder cell. When a move drops a
 * populated cell at the bottom of [listId] (e.g. dragging a task *below* the trailing placeholder), append
 * a fresh empty placeholder so the list bottom is empty again — mirroring the auto-expansion done while
 * editing. A no-op when the list already ends with an empty cell.
 */
private fun ensureTrailingPlaceholder(state: SchedulerState, listId: CellListId): SchedulerState {
    val list = state.lists[listId] ?: return state
    val lastId = list.cellIds.lastOrNull()
    if (lastId != null && SchedulerDomain.isTextuallyEmptyCell(state, lastId)) return state
    val (placeholderId, withId) = state.allocateCellId(listId)
    val placeholder = Cell(id = placeholderId, parentListId = listId, taskId = null)
    val updatedList = (withId.lists[listId] ?: list).let { it.copy(cellIds = it.cellIds + placeholderId) }
    return withId.copy(
        cells = withId.cells + (placeholderId to placeholder),
        lists = withId.lists + (listId to updatedList),
    )
}

private fun applyEditText(
    state: SchedulerState,
    session: SchedulerEditSession,
    text: String,
): SchedulerState {
    val cellId = session.cellId
    return when (session.mode) {
        CellEditMode.Rename -> applySetCellTitle(state, cellId, text)
        CellEditMode.ChangeTask ->
            if (session.selectedAssignTaskId == null) {
                applyChangeTaskNewDraft(state, session, text)
            } else {
                applySetCellTitle(state, cellId, text, forceTaskId = session.selectedAssignTaskId)
            }
    }
}

private fun applyChangeTaskNewDraft(
    state: SchedulerState,
    session: SchedulerEditSession,
    text: String,
): SchedulerState {
    val cellId = session.cellId
    val (draftTaskId, afterAlloc) =
        session.newTaskDraftId?.let { it to state }
            ?: state.allocateTaskId().let { (id, next) -> id to next }
    var working = afterAlloc
    if (working.cells[cellId]?.taskId != draftTaskId) {
        working = applyAssignTaskId(working, cellId, draftTaskId)
    }
    working = applySetCellTitle(working, cellId, text, forceTaskId = draftTaskId)
    return working.copy(editSession = session.copy(newTaskDraftId = draftTaskId))
}

private fun assignTaskIdDelta(
    state: SchedulerState,
    cellId: CellId,
    taskId: TaskId,
): Delta {
    val before = state.captureTree()
    val after =
        if (!SchedulerDomain.canAssignTaskId(state, cellId, taskId)) {
            state
        } else {
            applyAssignTaskId(state, cellId, taskId)
        }.captureTree()
    return TreeMutationDelta(before = before, after = after, label = "Assign task")
}

private fun applyAssignTaskId(state: SchedulerState, cellId: CellId, taskId: TaskId): SchedulerState {
    val cell = state.cells[cellId] ?: return state
    val targetTask = state.tasks[taskId] ?: return state

    var tasks = state.tasks.toMutableMap()
    val oldTaskId = cell.taskId

    if (oldTaskId != null && oldTaskId != taskId) {
        val oldTask = tasks[oldTaskId] ?: return state
        tasks[oldTaskId] = oldTask.copy(occurrences = oldTask.occurrences - cellId)
    }

    val cells = state.cells.toMutableMap()
    cells[cellId] = cell.copy(taskId = taskId)

    var working = state.copy(cells = cells, tasks = tasks)
    val mergedOccurrences = (targetTask.occurrences + cellId).distinct()
    tasks[taskId] = targetTask.copy(occurrences = SchedulerDomain.sortOccurrences(working, mergedOccurrences))
    working = working.copy(tasks = tasks)

    SchedulerDomain.parentTaskId(working, cellId)?.let { parentId ->
        working = working.copy(tasks = SchedulerDomain.linkChildUnderParent(working.tasks, parentId, taskId))
    }

    return SchedulerDomain.purgeOrphanTasks(working)
}

/** Wraps a priority-table mutation as an undoable [TreeMutationDelta] (PRD §6). */
private fun priorityTreeDelta(
    state: SchedulerState,
    label: String = "Tree change",
    mutate: (SchedulerState) -> SchedulerState,
): Delta {
    val before = state.captureTree()
    val after = mutate(state).captureTree()
    return TreeMutationDelta(before = before, after = after, label = label)
}

/**
 * PRD §5: the default value of a weight field by column — the first column's fields default to 1,
 * every added column's fields default to 0. Used to fill gaps when a value vector is shorter than
 * the list's column count.
 */
private fun defaultWeightAt(column: Int): Double = if (column == 0) 1.0 else 0.0

/**
 * PRD §5 the weight table's **default row**, read to this list's column count and ready to be edited.
 * [SchedulerDomain.defaultWeightRow] is the one place the row is read, so the table that draws it and the
 * seeding below can never disagree about what it says.
 */
private fun defaultWeightRow(list: CellList): MutableList<Double> =
    SchedulerDomain.defaultWeightRow(list).toMutableList()

/** Pad [weights] to at least [size] entries, filling missing columns with their default. */
private fun normalizedWeights(weights: List<Double>, size: Int): MutableList<Double> =
    MutableList(maxOf(size, weights.size)) { weights.getOrElse(it) { defaultWeightAt(it) } }

private fun applySetPriorityWeight(
    state: SchedulerState,
    cellId: CellId,
    column: Int,
    value: Double,
): SchedulerState {
    if (column < 0) return state
    val cell = state.cells[cellId] ?: return state
    // PRD §5: cell values span 0..infinity.
    val clamped = value.coerceAtLeast(0.0)
    val weights = normalizedWeights(cell.priorityWeights, column + 1)
    if (weights[column] == clamped) return state
    weights[column] = clamped
    return state.copy(cells = state.cells + (cellId to cell.copy(priorityWeights = weights)))
}

/**
 * PRD §9 the frequent tick: advance the schedule to [nowMillis] without refilling. Any non-pinned
 * auto panel that has fully elapsed (`end ≤ now`) is recorded as a completed period and dropped; the
 * in-progress auto panel covering `now` whose task was deleted or gained a child task is cut at `now`,
 * recorded, and dropped (PRD §9). The task record lives outside the Undo/Redo history, so this is a
 * non-undoable side effect; it returns the same instance when nothing changed. Future panels are left
 * untouched (the §9 refill, [reduceRefreshSchedule], regenerates them).
 */
/**
 * PRD §9/§12: every stretch an on-screen task must NOT bank a record over — what the user DREW
 * ([SchedulerDomain.assertedNoScreenRanges]: a period that is or carries "No screen", or a computer-layer
 * period overlapping a phone-layer one) UNIONED with what the devices observed ([SchedulerReducer.noScreenEvidence]).
 *
 * Both halves are needed and neither is redundant. The panels are an assertion the user made and hold whatever
 * any history says; the evidence is the OS's own lock/standby record, which is the only half that fires on an
 * account where nobody ever drew a panel — the case that let 43 h of "work" bank straight through a sleeping
 * machine before this union existed.
 */
private fun noScreenRangesFor(
    state: SchedulerState,
    noScreenEvidence: List<TaskTimeRange>,
): List<TaskTimeRange> {
    val drawn = SchedulerDomain.assertedNoScreenRanges(state.panels, state.periodKindConfig)
    if (drawn.isEmpty() && noScreenEvidence.isEmpty()) return emptyList()
    return SchedulerDomain.mergeOccupied(drawn + noScreenEvidence)
}

private fun advanceSchedule(
    state: SchedulerState,
    nowMillis: Long,
    noScreenEvidence: List<TaskTimeRange>,
): SchedulerState {
    var tasks = state.tasks
    // PRD §9/§12: an elapsed span covered by a no-screen period banks NO record for an on-screen task —
    // the app must not assume the on-screen work happened, so the period reads as past inactivity (the
    // no-screen panel stays on the calendar; the task is still owed that work).
    val noScreenRanges = noScreenRangesFor(state, noScreenEvidence)
    // PRD §7 "Switch task": the standing refusal is SPENT as soon as some other task's work is actually
    // banked past the instant it was made — that is what "the plan started something else" means, and it is
    // exactly the predicate [SchedulerDomain.liveForcedSwitchTask] reads off the past, applied incrementally
    // here so the marker cannot linger in the persisted payload once it has been honoured.
    val switch = state.forcedSwitch
    var switchSpent = false
    // PRD §13 "start this task now": the standing request is spent by exactly the same event, for the mirror
    // reason — once another task's work is banked past the instant it was made, the plan has moved on from the
    // task that was asked for, so the request has been answered ([SchedulerDomain.liveForcedStartTask]).
    val start = state.forcedStart
    var startSpent = false
    fun bank(taskId: TaskId?, startMillis: Long, endMillis: Long) {
        if (switch != null && taskId != null && taskId != switch.taskId && endMillis > switch.atMillis) {
            switchSpent = true
        }
        if (start != null && taskId != null && taskId != start.taskId && endMillis > start.atMillis) {
            startSpent = true
        }
        tasks = appendRecordOutsideNoScreen(tasks, noScreenRanges, taskId, startMillis, endMillis)
    }
    val remaining = ArrayList<TaskPanel>(state.panels.size)
    var changed = false
    for (panel in state.panels) {
        if (panel.pinned || !panel.auto) {
            remaining += panel
            continue
        }
        // A panel's task is schedulable only while it is still a leaf task present in the tree; a task
        // deleted from the tree (or one that gained a child) is no longer scheduled.
        val schedulable =
            panel.taskId != null &&
                SchedulerDomain.taskHasCells(state, panel.taskId) &&
                SchedulerDomain.isLeafTask(state, panel.taskId)
        when {
            // Elapsed auto panel → record [start, end] as completed work, drop the panel.
            panel.endEpochMillis <= nowMillis -> {
                bank(panel.taskId, panel.startEpochMillis, panel.endEpochMillis)
                changed = true
            }
            // In-progress auto panel covering `now`: keep it unless its task is no longer schedulable
            // (deleted from the tree or gained a child) — then cut at `now`, record, and drop it.
            panel.startEpochMillis <= nowMillis -> {
                if (schedulable) {
                    remaining += panel
                } else {
                    bank(panel.taskId, panel.startEpochMillis, nowMillis)
                    changed = true
                }
            }
            // Future auto panel: keep it only while its task is still schedulable. A task removed from
            // the tree must not linger in the automatic schedule (PRD §9) — its tentative future panels
            // are dropped (no work done yet, so nothing to record). Without this they would persist
            // whenever no refill runs (e.g. auto-scheduling off, PRD §7), still showing the removed task.
            else -> {
                if (schedulable) remaining += panel else changed = true
            }
        }
    }
    if (!changed && !switchSpent && !startSpent) return state
    val advanced =
        state.copy(
            tasks = tasks,
            panels = remaining,
            forcedSwitch = if (switchSpent) null else state.forcedSwitch,
            forcedStart = if (startSpent) null else state.forcedStart,
        )
    return advanced
}

/**
 * PRD §9/§12: append a worked `[start, end]` period to [taskId]'s record, minus any part covered by a
 * no-screen period when the task is on-screen — the app assumes nothing happened on screen there, so
 * that part reads as past inactivity instead of completed work. An off-screen task (allowed inside a
 * no-screen period) banks the whole span.
 */
private fun appendRecordOutsideNoScreen(
    tasks: Map<TaskId, Task>,
    noScreenRanges: List<TaskTimeRange>,
    taskId: TaskId?,
    startMillis: Long,
    endMillis: Long,
): Map<TaskId, Task> {
    if (taskId == null || endMillis <= startMillis) return tasks
    val onScreen = tasks[taskId]?.onScreen ?: true
    if (!onScreen || noScreenRanges.isEmpty()) return appendRecordMap(tasks, taskId, startMillis, endMillis)
    var out = tasks
    for (piece in SchedulerDomain.subtractRegions(listOf(TaskTimeRange(startMillis, endMillis)), noScreenRanges)) {
        out = appendRecordMap(out, taskId, piece.startEpochMillis, piece.endEpochMillis)
    }
    return out
}

/**
 * Prefix that marks a schedule-DERIVED "Sleep" panel ([SchedulerDomain.sleepPanels] ids are `sleep/{day}`),
 * which the fill regenerates every run. A MATERIALIZED past-sleep panel gets an allocated numeric id instead,
 * so the two are told apart: the fill keeps the materialized ones and re-derives the rest.
 */
private const val DERIVED_SLEEP_ID_PREFIX = "sleep/"

/**
 * Sleep/Work toggle (PRD §17). Turning the toggle **on** stamps [SchedulerState.sleepingSinceMillis] with the
 * current instant (the calendar then draws a live "Sleep" band growing to the now-line). Turning it **off**
 * (or the wake instant lapsing, both routed here as `null`) finalizes the elapsed `[sleepingSince, now]` span
 * as a persisted past "Sleep" panel and clears the session — so past sleep is a recorded fact, not a
 * projection. Persisted; not undoable; the materialized panel rides the next authoritative push, never a push
 * on its own.
 */
private fun reduceSetSleepMode(state: SchedulerState, sleepingUntilMillis: Long?): SchedulerState {
    // Same target (re-press while sleeping, or already working): nothing to do.
    if (state.sleepingUntilMillis == sleepingUntilMillis) return state
    if (sleepingUntilMillis != null) {
        // Turning on (or re-targeting the wake instant): stamp the session start only when starting a fresh
        // session; a re-target keeps the original start so the live band spans the whole session.
        val since = state.sleepingSinceMillis ?: SchedulerReducer.clock.nowMillis()
        return state.copy(sleepingUntilMillis = sleepingUntilMillis, sleepingSinceMillis = since)
    }
    // Turning off: finalize the sleep session (if any) as a past "Sleep" panel, then clear it.
    val since = state.sleepingSinceMillis
    val cleared = state.copy(sleepingUntilMillis = null, sleepingSinceMillis = null)
    if (since == null) return cleared
    return materializePastSleep(cleared, listOf(TaskTimeRange(since, SchedulerReducer.clock.nowMillis())))
}

/**
 * PRD §9/§17 past sleep: materialize [pieces] — elapsed spans a scheduled sleep window turned out to be a
 * no-screen/inactive period, or a completed Sleep-toggle session — as persisted "Sleep" panels. A sleep
 * session is a fact the USER asserted with the Sleep/Work toggle, which is why this one survives where the
 * inactivity equivalent did not: spans an existing MATERIALIZED Sleep panel already covers are skipped and
 * sub-minute slivers dropped; schedule-derived `sleep/{day}` panels (regenerated by the fill) are NOT used
 * for dedup, so a still-projected window never suppresses recording the past that slid behind the now-line.
 * Outside Undo/Redo; never a syncable change on its own.
 */
private fun materializePastSleep(
    state: SchedulerState,
    pieces: List<TaskTimeRange>,
): SchedulerState {
    val real = pieces.filter { it.endEpochMillis > it.startEpochMillis }
    if (real.isEmpty()) return state
    val existing =
        state.panels
            .filter { it.sleep && !it.id.startsWith(DERIVED_SLEEP_ID_PREFIX) }
            .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
    val fresh =
        SchedulerDomain.subtractRegions(SchedulerDomain.mergeOccupied(real), existing)
            .filter { it.endEpochMillis - it.startEpochMillis >= SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS }
    if (fresh.isEmpty()) return state
    var out = state
    val added = ArrayList<TaskPanel>(fresh.size)
    for (piece in fresh) {
        val (panelId, allocated) = out.allocatePanelId()
        out = allocated
        added +=
            TaskPanel(
                id = panelId,
                taskId = null,
                title = "Sleep",
                startEpochMillis = piece.startEpochMillis,
                endEpochMillis = piece.endEpochMillis,
                sleep = true,
            )
    }
    return out.copy(panels = out.panels + added)
}

/** Appends a `[start, end]` period to [taskId]'s record in a task map (PRD §8; outside Undo/Redo). */
private fun appendRecordMap(
    tasks: Map<TaskId, Task>,
    taskId: TaskId?,
    startMillis: Long,
    endMillis: Long,
): Map<TaskId, Task> {
    if (taskId == null || endMillis <= startMillis) return tasks
    val task = tasks[taskId] ?: return tasks
    return tasks + (taskId to task.copy(record = task.record + TaskTimeRange(startMillis, endMillis)))
}

/**
 * PRD §12 Device sleep: cut the in-progress auto panel covering [sleepStart] there. The pre-sleep
 * stretch `[panel.start, sleepStart]` was real work → record it; the sleep window itself is left as a
 * hole (no record). All non-pinned auto panels (the cut one plus any tentative future ones) are
 * dropped so the wake-time [reduceRefreshSchedule] starts a fresh schedule after the sleep. Pinned and
 * user-authored panels are untouched.
 *
 * PRD §15: a device sleep is the user taking a pause, and that is all it has to be — the recurrence bars read
 * the rest stretch straight off the timeline, so nothing is recorded into the screen-break configuration.
 */
private fun reduceReportDeviceSleep(
    state: SchedulerState,
    sleepStart: Long,
    sleepEnd: Long,
    noScreenEvidence: List<TaskTimeRange>,
): SchedulerState {
    val base = state
    val current =
        base.panels.firstOrNull {
            it.auto && !it.pinned &&
                it.startEpochMillis <= sleepStart && sleepStart < it.endEpochMillis
        } ?: return base
    val noScreenRanges = noScreenRangesFor(base, noScreenEvidence)
    val tasks =
        appendRecordOutsideNoScreen(base.tasks, noScreenRanges, current.taskId, current.startEpochMillis, sleepStart)
    val remaining = base.panels.filter { it.pinned || !it.auto }
    return base.copy(tasks = tasks, panels = remaining)
}

private fun applySetTaskMinimumTime(
    state: SchedulerState,
    taskId: TaskId,
    minutes: Int,
): SchedulerState {
    val task = state.tasks[taskId] ?: return state
    // PRD §10: minimum time can't be negative.
    val clamped = minutes.coerceAtLeast(0)
    if (task.minimumMinutes == clamped) return state
    return state.copy(tasks = state.tasks + (taskId to task.copy(minimumMinutes = clamped)))
}

/**
 * `side-dev/README.md` § *Restrictive Period*: set [taskId]'s resilience to [kind]. The value is clamped into
 * `[0, 1]` and an override equal to the kind's own default is REMOVED rather than written
 * ([Task.withResilience]) — so an untouched kind stays absent and a task never carries a value the rules
 * would refuse.
 */
private fun applySetTaskResilience(
    state: SchedulerState,
    taskId: TaskId,
    kind: String,
    value: Double,
): SchedulerState {
    val task = state.tasks[taskId] ?: return state
    val normalized = PeriodKinds.normalize(kind)
    if (normalized.isEmpty()) return state
    val next = task.withResilience(normalized, value)
    if (next == task) return state
    return state.copy(tasks = state.tasks + (taskId to next))
}

/**
 * PRD §15: record a dynamic period the app CONDUCTED, exactly where it happened.
 *
 * It is a period of [PeriodKinds.INACTIVITY] like any other, and it is marked [TaskPanel.conductedBreak] so the
 * recurrence bars know it was one of the THREE — no anchor, no cadence arithmetic, no special case, but not
 * nothing either: the README's first bar keys on a dynamic restrictive *period* ("no 20 s period in the next
 * 20 minutes") where the other two key on a rest *stretch*, and twenty seconds is far too short to be one of
 * those. Without the mark a look-away the user had just sat through barred nothing at all. Its exact span is
 * kept (a 20-second look-away is 20 seconds, not the minute a hand-drawn entry is rounded up
 * to): this is a recorded fact, not something the user is drawing.
 *
 * Outside the Undo/Redo history, like every write to the record; idempotent, so a replayed dispatch cannot
 * stack two periods over one break.
 */
private fun reduceRecordConductedBreak(
    state: SchedulerState,
    intent: SchedulerIntent.RecordConductedBreak,
): SchedulerState {
    if (intent.endEpochMillis <= intent.startEpochMillis) return state
    val already =
        state.panels.any {
            it.inactivity &&
                it.startEpochMillis == intent.startEpochMillis &&
                it.endEpochMillis == intent.endEpochMillis
        }
    if (already) return state
    val (panelId, allocated) = state.allocatePanelId()
    return allocated.copy(
        panels = allocated.panels + TaskPanel(
            id = panelId,
            taskId = null,
            title = intent.title,
            startEpochMillis = intent.startEpochMillis,
            endEpochMillis = intent.endEpochMillis,
            inactivity = true,
            periodKind = PeriodKinds.INACTIVITY,
            // What makes it one of the THREE and not a 20-second inactivity span the user drew: the README's
            // first bar is "after any dynamic restrictive period, no 20 s period in the next 20 minutes", and
            // this is the only thing on the panel that says this period was one of them.
            conductedBreak = true,
        ),
    )
}

/**
 * Which of [taskIds] [SchedulerIntent.SetPeriodResilience] actually has to move: the tasks that exist and are
 * not already at [value] for [kind]. An empty answer is what keeps a no-op bulk write out of the history.
 */
private fun periodResilienceTargets(
    state: SchedulerState,
    taskIds: List<TaskId>,
    kind: String,
    value: Double,
): List<TaskId> {
    if (kind.isEmpty()) return emptyList()
    return taskIds.distinct().filter { id ->
        val task = state.tasks[id]
        task != null && task.resilienceFor(kind) != value
    }
}

// ----- PRD §5 categories -----------------------------------------------------------------------

/*
 * How a category divides between the two kinds of write, and why: it is the SAME split the restrictive
 * periods already make (`AddPeriodKind` / `SetTaskResilience`), so there is one rule and not two.
 *
 *  - **The account's category list and its rules are not Undo/Redo units.** Defining a label, renaming it,
 *    saying what it is worth — like defining a kind of period, these are settings of the account. What they
 *    change about the schedule they change through the tree, which is undoable in its own right.
 *  - **A task carrying a category IS a tree edit**, so it is an ordinary content unit, exactly as the
 *    resilience the task is given to a kind is.
 *  - **The weights a rule moves are in no unit at all.** They are re-established after every intent from the
 *    rules themselves ([CategoryRules.settle]), so undoing to an older tree simply settles again — recording
 *    the adjustment would be recording something the state already implies (CLAUDE.md § *State*).
 */

/**
 * PRD §5: give [taskId] the categories named by [titles], minting the ones the account has not got. The
 * clipboard's route in, and the same create-or-attach the row's naming field takes — never a second rule
 * about what a category name means.
 */
private fun attachCategoriesByTitle(
    state: SchedulerState,
    taskId: TaskId,
    titles: List<String>,
): SchedulerState {
    var working = state
    for (raw in titles) {
        val title = raw.trim()
        if (title.isEmpty()) continue
        val existing = working.categories.firstOrNull { it.title.equals(title, ignoreCase = true) }
        val id =
            existing?.id ?: working.allocateCategoryId().let { (newId, allocated) ->
                working = allocated.copy(categories = allocated.categories + Category(newId, title))
                newId
            }
        working = applyAttachTaskCategory(working, taskId, id)
    }
    return working
}

private fun applyAttachTaskCategory(
    state: SchedulerState,
    taskId: TaskId,
    categoryId: CategoryId,
): SchedulerState {
    val task = state.tasks[taskId] ?: return state
    if (categoryId in task.categoryIds) return state
    return state.copy(tasks = state.tasks + (taskId to task.copy(categoryIds = task.categoryIds + categoryId)))
}

/** The bin on a drop-down row: the task stops carrying the category; the category itself is untouched. */
private fun applyRemoveTaskCategory(
    state: SchedulerState,
    taskId: TaskId,
    categoryId: CategoryId,
): SchedulerState {
    val task = state.tasks[taskId] ?: return state
    if (categoryId !in task.categoryIds) return state
    return state.copy(tasks = state.tasks + (taskId to task.copy(categoryIds = task.categoryIds - categoryId)))
}

/**
 * PRD §5/§7: mint a category nothing carries yet — the categories window's naming field.
 *
 * The create half of [SchedulerReducer.reduceAddTaskCategory] with nothing to attach it to. A title an
 * existing category already carries is left alone rather than minted a second time: the account is a SET of
 * named objects, and two of them under one spelling is what the id exists to prevent. No history unit —
 * defining a category is an account setting, and no priority has moved.
 */
private fun reduceCreateCategory(state: SchedulerState, titleRaw: String): SchedulerState {
    val title = titleRaw.trim()
    if (title.isEmpty()) return state
    if (state.categories.any { it.title.equals(title, ignoreCase = true) }) return state
    val (id, allocated) = state.allocateCategoryId()
    return allocated.copy(categories = allocated.categories + Category(id = id, title = title))
}

/**
 * PRD §5: rename a category. Everything names it by id — the tasks carrying it and its own rules — so this
 * writes one string and reaches all of them, which is exactly what makes a category an object rather than a
 * word repeated on twenty tasks. A blank name is refused: the blank title deletes a *task*, and a category is
 * deleted by its window's own button, which also takes its rules.
 */
private fun reduceRenameCategory(
    state: SchedulerState,
    categoryId: CategoryId,
    titleRaw: String,
): SchedulerState {
    val title = titleRaw.trim()
    if (title.isEmpty()) return state
    val category = state.categoryById(categoryId) ?: return state
    if (category.title == title) return state
    return state.copy(
        categories = state.categories.map { if (it.id == categoryId) it.copy(title = title) else it },
    )
}

/**
 * PRD §5: delete a category — its rules with it, and the id off every task carrying it. The one place a
 * category is deleted, as the period edit window is the one place a period is: both are the one place their
 * object is an object. An id left on a task would silently re-attach it if a later category were minted under
 * that id, which is also why ids are never reused.
 */
private fun reduceDeleteCategory(state: SchedulerState, categoryId: CategoryId): SchedulerState {
    if (state.categoryById(categoryId) == null) return state
    return state.copy(
        categories = state.categories.filterNot { it.id == categoryId },
        tasks = state.tasks.mapValues { (_, t) ->
            if (categoryId in t.categoryIds) t.copy(categoryIds = t.categoryIds - categoryId) else t
        },
    )
}

/**
 * PRD §5: set the category's rule about one scope — *the tasks carrying it under the cell [scopeCellId] are
 * worth [shareRaw] of it* (`null` = the whole tree). At most one rule per scope, so an existing rule about
 * that scope is REPLACED — and "that scope" is the SUB-LIST the cell's task owns
 * ([CategoryRules.scopeKey]), not the cell, so writing a rule about one occurrence of a mirrored task
 * replaces the rule written about another. Two statements about one sub-tree is exactly what the invariant
 * exists to prevent, and mirrored cells show one sub-tree.
 *
 * Nothing is enforced here: [SchedulerReducer.reduce] settles every rule after every intent
 * ([CategoryRules.settle]), so a rule that cannot be held refuses this write along with everything else it
 * would have dragged with it — the invariant lives in one place, not two.
 */
private fun reduceSetCategoryRule(
    state: SchedulerState,
    categoryId: CategoryId,
    scopeCellId: CellId?,
    shareRaw: Double,
): SchedulerState {
    val category = state.categoryById(categoryId) ?: return state
    if (!shareRaw.isFinite()) return state
    val share = shareRaw.coerceIn(0.0, 1.0)
    if (category.ruleAt(scopeCellId)?.share == share) return state
    val key = CategoryRules.scopeKey(state, scopeCellId)
    return state.copy(
        categories = state.categories.map { c ->
            if (c.id != categoryId) {
                c
            } else {
                c.copy(
                    rules = c.rules.filterNot { CategoryRules.scopeKey(state, it.scopeCellId) == key } +
                        CategoryRule(scopeCellId = scopeCellId, share = share),
                )
            }
        },
    )
}

/** The bin on a rule row: the category stops claiming anything about that sub-tree. */
private fun reduceRemoveCategoryRule(
    state: SchedulerState,
    categoryId: CategoryId,
    scopeCellId: CellId?,
): SchedulerState {
    val category = state.categoryById(categoryId) ?: return state
    if (category.ruleAt(scopeCellId) == null) return state
    return state.copy(
        categories = state.categories.map { c ->
            if (c.id != categoryId) c else c.copy(rules = c.rules.filterNot { it.scopeCellId == scopeCellId })
        },
    )
}

/**
 * `side-dev/README.md`: **the user defines a new kind of restrictive period.** Adding one is deliberately
 * cheap and total — a kind no task was ever told about is at that kind's own default
 * ([PeriodKinds.defaultResilience]), which for a user-defined kind is `0`, so nothing is written to a single
 * task here and the new period **turns everybody away** until its edit window hands somebody a value above
 * zero. That is why the account holds only the LIST of kinds.
 *
 * Authoritative + synced, and **not** an Undo/Redo unit — the same shape as the account's other settings
 * (`deepCopyMaxDepth`, the copy options). What *is* undoable is the resilience a task is then given, which
 * is an ordinary tree edit; defining the kind changes no schedule on its own, so there is nothing for Ctrl+Z
 * to put back. The two built-in kinds are always present and are never added to the list.
 */
private fun reduceAddPeriodKind(state: SchedulerState, kindRaw: String): SchedulerState {
    val kind = PeriodKinds.normalize(kindRaw)
    if (!PeriodKinds.isUserDefined(kind)) return state
    if (state.periodKinds.any { it.equals(kind, ignoreCase = true) }) return state
    // The period edit window: every kind wears a drawing, and a new one gets the drawing the fewest kinds
    // already wear, so it can be told apart from the others wherever they overlap. Stored rather than derived
    // from the kind's position in the list: removing another kind must not repaint this one.
    val config = state.periodKindConfig
    val worn = state.allPeriodKinds.groupingBy { config.drawing(it) }.eachCount()
    val drawing = PeriodDrawing.entries.minBy { worn[it] ?: 0 }
    return state.copy(
        periodKinds = state.periodKinds + kind,
        periodKindStyles = state.periodKindStyles + (kind to PeriodKindStyle(emptySet(), drawing)),
    )
}

/**
 * The period edit window's companions: **the kinds always present wherever a period of [kindRaw] is.** Only
 * kinds the account holds are kept, and never the kind itself. A set equal to what the kind already carries is
 * a no-op. The write is the whole style (companions + the drawing it already wears), so the override stays one
 * row per kind.
 */
private fun reduceSetPeriodCompanions(state: SchedulerState, kindRaw: String, companions: Set<String>): SchedulerState {
    val kind = PeriodKinds.normalize(kindRaw)
    val kinds = state.allPeriodKinds
    if (kind !in kinds) return state
    val kept = companions.map(PeriodKinds::normalize).filterTo(LinkedHashSet()) { it != kind && it in kinds }
    val current = state.periodKindConfig.style(kind)
    if (kept == current.companions) return state
    return state.withPeriodKindStyle(kind, current.copy(companions = kept))
}

/** The period edit window's drawing for [kindRaw]; the same drawing again is a no-op. */
private fun reduceSetPeriodDrawing(state: SchedulerState, kindRaw: String, drawing: PeriodDrawing): SchedulerState {
    val kind = PeriodKinds.normalize(kindRaw)
    if (kind !in state.allPeriodKinds) return state
    val current = state.periodKindConfig.style(kind)
    if (current.drawing == drawing) return state
    return state.withPeriodKindStyle(kind, current.copy(drawing = drawing))
}

/** Stores [style] for [kind] as an override — or drops the override when [style] is the kind's default. */
private fun SchedulerState.withPeriodKindStyle(kind: String, style: PeriodKindStyle): SchedulerState =
    copy(
        periodKindStyles =
            if (!PeriodKinds.isUserDefined(kind) && style == PeriodKinds.defaultStyle(kind)) {
                periodKindStyles - kind
            } else {
                periodKindStyles + (kind to style)
            },
    )

/**
 * Remove a user-defined kind. Every task's override for it goes with it — a resilience to a kind that no
 * longer exists is unreachable state, and leaving it behind would silently resurrect the old value if the
 * kind were ever re-added under the same name. A panel laid with that kind loses its restriction with it,
 * rather than becoming a period of a kind nothing can be resilient to. The two built-in kinds cannot be
 * removed.
 */
private fun reduceRemovePeriodKind(state: SchedulerState, kindRaw: String): SchedulerState {
    val kind = PeriodKinds.normalize(kindRaw)
    if (!PeriodKinds.isUserDefined(kind)) return state
    if (state.periodKinds.none { it == kind }) return state
    // Its style goes with it, and it stops being anybody's companion: a companion that no longer exists is
    // unreachable state, and would silently come back if the kind were ever re-added under the same name.
    // Only the OVERRIDES are touched — a default never names a user-defined kind.
    val styles =
        (state.periodKindStyles - kind).mapValues { (_, style) ->
            if (kind in style.companions) style.copy(companions = style.companions - kind) else style
        }
    return state.copy(
        periodKinds = state.periodKinds.filterNot { it == kind },
        periodKindStyles = styles,
        tasks = state.tasks.mapValues { (_, t) -> if (kind in t.resilience) t.copy(resilience = t.resilience - kind) else t },
        panels = state.panels.filterNot { it.periodKind == kind },
    )
}

private fun applySetScheduleUnit(
    state: SchedulerState,
    taskId: TaskId,
    entries: List<org.example.project.scheduler.model.ScheduleUnitEntry>,
): SchedulerState {
    val task = state.tasks[taskId] ?: return state
    // PRD §13: never persist a unit whose spanning times exceed the task's minimum time (the Save
    // button is meant to be disabled in that case — this is the reducer's matching guard).
    if (!SchedulerDomain.canSaveScheduleUnit(entries, task.minimumMinutes)) return state
    if (task.scheduleUnit == entries) return state
    return state.copy(tasks = state.tasks + (taskId to task.copy(scheduleUnit = entries)))
}

private fun applySetTaskText(
    state: SchedulerState,
    taskId: TaskId,
    text: String,
): SchedulerState {
    val task = state.tasks[taskId] ?: return state
    if (task.text == text) return state
    return state.copy(tasks = state.tasks + (taskId to task.copy(text = text)))
}

/**
 * PRD §5 the relative-priority window: flip [cellId]'s pin for the (task, ancestor) pair. The empty set is
 * dropped from the map rather than stored, so an account that never pins anything encodes nothing.
 */
private fun reduceToggleRelativePriorityPin(
    state: SchedulerState,
    taskId: TaskId,
    relativeTo: TaskId,
    cellId: CellId,
): SchedulerState {
    val key = RelativePriorityPinKey(taskId, relativeTo)
    val current = state.relativePriorityPins[key].orEmpty()
    val next = if (cellId in current) current - cellId else current + cellId
    val pins =
        if (next.isEmpty()) state.relativePriorityPins - key
        else state.relativePriorityPins + (key to next)
    return state.copy(relativePriorityPins = pins)
}

/**
 * PRD §5 the **task relations** window: apply [update] to this pair's mark, seeding the default mark when the
 * pair has never been recorded. The state is returned unchanged when the mark does not move, so a redundant
 * press (a keystroke that leaves the verdict where it was) neither saves nor pushes.
 */
private fun reduceMarkTaskRelation(
    state: SchedulerState,
    key: TaskRelationKey,
    update: (TaskRelationMark) -> TaskRelationMark,
): SchedulerState {
    val current = state.taskRelations[key] ?: TaskRelationMark()
    val next = update(current)
    if (next == current && key in state.taskRelations) return state
    return state.copy(taskRelations = state.taskRelations + (key to next))
}

/**
 * PRD §5 the task-relations window: the relative-priority window reporting on a pair it has been open on.
 *
 * The mark's mere existence is section 3's fact ("looked at, never changed"), so the recording never removes
 * an entry — it only moves the two flags:
 *
 * - **[TaskRelationMark.kept] is never touched.** Section 1 is the user's own filing; opening a window on a
 *   pair is not unfiling it.
 * - **[TaskRelationMark.hidden] is lifted only by a real change.** A struck-off pair the user merely looks at
 *   stays struck off; one they actually retarget is one they are working on again.
 * - **[TaskRelationMark.retargeted] is the verdict of THIS session**, so putting a percentage back where it
 *   was demotes the pair to section 3 — which is exactly the rule the window's field commits keystroke by
 *   keystroke.
 */
private fun reduceRecordTaskRelation(
    state: SchedulerState,
    key: TaskRelationKey,
    changed: Boolean,
): SchedulerState =
    reduceMarkTaskRelation(state, key) { mark ->
        mark.copy(retargeted = changed, hidden = mark.hidden && !changed)
    }

/**
 * PRD §5 the priority-weight window: flip one input's pin for [listId]'s table — the weight of [cellId] in
 * [column], or that column's header when [cellId] is null. The empty set is dropped from the map rather
 * than stored, so an account that never pins anything (or unpins its last one) encodes nothing.
 */
private fun reduceTogglePriorityWeightPin(
    state: SchedulerState,
    listId: CellListId,
    cellId: CellId?,
    column: Int,
): SchedulerState {
    if (column < 0) return state
    val pin = PriorityWeightPin(cellId, column)
    val current = state.priorityWeightPins[listId].orEmpty()
    val next = if (pin in current) current - pin else current + pin
    val pins =
        if (next.isEmpty()) state.priorityWeightPins - listId
        else state.priorityWeightPins + (listId to next)
    return state.copy(priorityWeightPins = pins)
}

/**
 * PRD §5: a pin names a column by INDEX, because a column has no identity of its own — so a structural
 * change to [listId]'s columns has to carry that table's pins with it. [moved] maps an old index to its new
 * one, or to null for the column that is gone.
 *
 * Applied to the state the column edit produced rather than inside its history delta, because the delta
 * carries the TREE (`captureTree`) and the pins deliberately sit outside Undo/Redo — a pin changes no
 * priority. The consequence is the one place the two can disagree: undoing a column move puts the columns
 * back and leaves the pins where the move carried them, so a pin can end up beside a neighbouring column
 * until the user flips it (the pin button says which fields are held, so it is visible, and no priority
 * moves on its own).
 */
private fun remapPriorityWeightPins(
    state: SchedulerState,
    listId: CellListId,
    moved: (Int) -> Int?,
): SchedulerState {
    val pins = state.priorityWeightPins[listId] ?: return state
    val next = pins.mapNotNull { pin -> moved(pin.column)?.let { pin.copy(column = it) } }.toSet()
    if (next == pins) return state
    return state.copy(
        priorityWeightPins =
            if (next.isEmpty()) state.priorityWeightPins - listId
            else state.priorityWeightPins + (listId to next),
    )
}

/** PRD §5 the relative-priority window's "clear pins" button. */
private fun reduceClearRelativePriorityPins(
    state: SchedulerState,
    taskId: TaskId,
    relativeTo: TaskId,
): SchedulerState {
    val key = RelativePriorityPinKey(taskId, relativeTo)
    if (state.relativePriorityPins[key] == null) return state
    return state.copy(relativePriorityPins = state.relativePriorityPins - key)
}

private fun applySetPriorityColumnWeight(
    state: SchedulerState,
    listId: CellListId,
    column: Int,
    weight: Double,
): SchedulerState {
    if (column < 0) return state
    val list = state.lists[listId] ?: return state
    // PRD §5: a column's header weight can only span 0..1.
    val clamped = weight.coerceIn(0.0, 1.0)
    val columns = MutableList(maxOf(column + 1, list.weightColumns.size)) {
        list.weightColumns.getOrElse(it) { defaultWeightAt(it) }
    }
    if (columns[column] == clamped) return state
    columns[column] = clamped
    return state.copy(lists = state.lists + (listId to list.copy(weightColumns = columns)))
}

/**
 * PRD §5 the weight table's **default row**: set what a task arriving in [listId]'s table is given in
 * [column]. Clamped to ≥ 0 like every other weight field, and returned unchanged when the row already says
 * that, so a keystroke that changes nothing costs neither a history unit nor a save.
 */
private fun applySetPriorityDefaultWeight(
    state: SchedulerState,
    listId: CellListId,
    column: Int,
    value: Double,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    if (column < 0 || column >= list.weightColumns.size) return state
    val clamped = value.coerceAtLeast(0.0)
    val defaults = defaultWeightRow(list)
    if (defaults[column] == clamped) return state
    defaults[column] = clamped
    return state.copy(lists = state.lists + (listId to list.copy(defaultWeights = defaults)))
}

/**
 * PRD §5: where an "add column" actually lands. Read by the apply below **and** by the pin remap at the
 * dispatch site, so a pin can never be carried onto a different index than the weights were.
 */
private fun addColumnIndex(list: CellList, index: Int): Int = index.coerceIn(0, list.weightColumns.size)

/** PRD §5: the column a "delete column" actually removes, or null where the table refuses (see below). */
private fun deleteColumnIndex(list: CellList, column: Int): Int? =
    if (column < 0 || column >= list.weightColumns.size || list.weightColumns.size <= 1) null else column

/** PRD §5: where a "move column" actually puts the moved column, or null where the move is a no-op. */
private fun moveColumnTarget(list: CellList, from: Int, to: Int): Int? {
    val size = list.weightColumns.size
    if (from < 0 || from >= size) return null
    // [to] is an insertion index across all columns; account for removing [from] first.
    val target = (if (to > from) to - 1 else to).coerceIn(0, size - 1)
    return if (target == from) null else target
}

private fun applyAddPriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    index: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    val at = addColumnIndex(list, index)
    // PRD §5: an added column has every field (header and cells) set to 0.
    val cells = state.cells.toMutableMap()
    for (cellId in list.cellIds) {
        val cell = cells[cellId] ?: continue
        val padded = normalizedWeights(cell.priorityWeights, list.weightColumns.size)
        padded.add(at, 0.0)
        cells[cellId] = cell.copy(priorityWeights = padded)
    }
    val columns = list.weightColumns.toMutableList().also { it.add(at, 0.0) }
    // PRD §5: the default row is a row of this table like any other, so an added column is 0 in it too.
    val defaults = defaultWeightRow(list).also { it.add(at, 0.0) }
    val lists = state.lists + (listId to list.copy(weightColumns = columns, defaultWeights = defaults))
    return state.copy(cells = cells, lists = lists)
}

private fun applyResetPriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    column: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    if (column < 0 || column >= list.weightColumns.size) return state
    val default = defaultWeightAt(column)
    val cells = state.cells.toMutableMap()
    for (cellId in list.cellIds) {
        val cell = cells[cellId] ?: continue
        val weights = normalizedWeights(cell.priorityWeights, list.weightColumns.size)
        weights[column] = default
        cells[cellId] = cell.copy(priorityWeights = weights)
    }
    val columns = list.weightColumns.toMutableList().also { it[column] = default }
    val defaults = defaultWeightRow(list).also { it[column] = default }
    return state.copy(
        cells = cells,
        lists = state.lists + (listId to list.copy(weightColumns = columns, defaultWeights = defaults)),
    )
}

private fun applyMovePriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    from: Int,
    to: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    val size = list.weightColumns.size
    val target = moveColumnTarget(list, from, to) ?: return state
    fun <T> reorder(items: MutableList<T>) {
        val moved = items.removeAt(from)
        items.add(target, moved)
    }
    val cells = state.cells.toMutableMap()
    for (cellId in list.cellIds) {
        val cell = cells[cellId] ?: continue
        val weights = normalizedWeights(cell.priorityWeights, size)
        reorder(weights)
        cells[cellId] = cell.copy(priorityWeights = weights)
    }
    val columns = list.weightColumns.toMutableList().also { reorder(it) }
    val defaults = defaultWeightRow(list).also { reorder(it) }
    return state.copy(
        cells = cells,
        lists = state.lists + (listId to list.copy(weightColumns = columns, defaultWeights = defaults)),
    )
}

private fun applyDeletePriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    column: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    // Keep at least one column so priority distribution stays well-defined ([deleteColumnIndex]).
    if (deleteColumnIndex(list, column) == null) return state
    val cells = state.cells.toMutableMap()
    for (cellId in list.cellIds) {
        val cell = cells[cellId] ?: continue
        val padded = normalizedWeights(cell.priorityWeights, list.weightColumns.size)
        padded.removeAt(column)
        cells[cellId] = cell.copy(priorityWeights = padded)
    }
    val columns = list.weightColumns.toMutableList().also { it.removeAt(column) }
    val defaults = defaultWeightRow(list).also { it.removeAt(column) }
    val lists = state.lists + (listId to list.copy(weightColumns = columns, defaultWeights = defaults))
    return state.copy(cells = cells, lists = lists)
}

/**
 * PRD §5 the priority-weight window's **Cancel**: put [listId]'s weight table back to the headers, the
 * per-cell weight rows and the default row it held when the window opened. Only that one table is touched —
 * a cell listed in [cellWeights] that has since moved to another sub-list is left to its new table, and the
 * list's membership itself is never rewritten (Cancel undoes weight edits, not tree edits).
 *
 * Returns the same instance when the table already matches, so the caller can skip the history unit.
 */
private fun applyRestorePriorityWeights(
    state: SchedulerState,
    listId: CellListId,
    weightColumns: List<Double>,
    cellWeights: Map<CellId, List<Double>>,
    defaultWeights: List<Double>,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    if (weightColumns.isEmpty()) return state
    val cells = state.cells.toMutableMap()
    var changed = false
    for ((cellId, weights) in cellWeights) {
        val cell = cells[cellId] ?: continue
        if (cell.parentListId != listId) continue
        if (cell.priorityWeights == weights) continue
        cells[cellId] = cell.copy(priorityWeights = weights)
        changed = true
    }
    val columnsChanged = list.weightColumns != weightColumns
    val defaultsChanged = defaultWeights.isNotEmpty() && list.defaultWeights != defaultWeights
    if (!changed && !columnsChanged && !defaultsChanged) return state
    val lists =
        if (columnsChanged || defaultsChanged) {
            state.lists + (
                listId to
                    list.copy(
                        weightColumns = weightColumns,
                        defaultWeights = if (defaultsChanged) defaultWeights else list.defaultWeights,
                    )
                )
        } else {
            state.lists
        }
    return state.copy(cells = cells, lists = lists)
}

/** The history unit's name for each of the three shapes of [SchedulerIntent.SetPriorityWeightTableRow]. */
private fun weightTableRowLabel(intent: SchedulerIntent.SetPriorityWeightTableRow): String = when {
    intent.replacing == null -> "Add table row"
    intent.taskId == null -> "Remove table row"
    else -> "Change table row"
}

/**
 * PRD §5: set what an **optional row** of [listId]'s priority-weight table names — add, re-point or remove,
 * the three shapes of one question (see [SchedulerIntent.SetPriorityWeightTableRow]).
 *
 * A new row arrives on the table's own **default row** ([CellList.defaultWeights]) — the same row a task
 * named in the tree arrives on, because "a task new to this table" is one question however the task got
 * here. A re-pointed row is seeded the same way: the value belonged to the task the row named, not to the
 * row's position in the table. (Until the default row existed this was a hard-coded zero, which is what an
 * untouched default row still says in every column but the first.)
 */
private fun applySetPriorityWeightTableRow(
    state: SchedulerState,
    listId: CellListId,
    replacing: TaskId?,
    taskId: TaskId?,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    // A row the table no longer holds is a stale press (a peer removed it, or an Undo did) — never a
    // silent add under another name.
    if (replacing != null && replacing !in list.optionalTaskIds) return state
    var optionalIds = list.optionalTaskIds
    var optionalValues = list.optionalTaskValues
    if (replacing != null) {
        optionalIds = optionalIds - replacing
        optionalValues = optionalValues - replacing
    }
    if (taskId != null) {
        if (taskId in optionalIds) return state
        val parentTaskId = SchedulerDomain.parentTaskIdOfList(state, listId) ?: return state
        if (taskId == parentTaskId) return state
        if (RelativePriorityDomain.optionalTaskPath(state, listId, taskId).isEmpty()) return state
        optionalIds = optionalIds + taskId
        optionalValues = optionalValues + (taskId to SchedulerDomain.defaultWeightRow(list))
    }
    if (optionalIds == list.optionalTaskIds && optionalValues == list.optionalTaskValues) return state
    return state.copy(
        lists = state.lists + (listId to list.copy(
            optionalTaskIds = optionalIds,
            optionalTaskValues = optionalValues,
        )),
    )
}

private fun setCellTitleDelta(
    state: SchedulerState,
    cellId: CellId,
    title: String,
): Delta {
    val before = state.captureTree()
    val named = applySetCellTitle(state, cellId, title)
    // PRD §4: naming an empty cell CREATES a task, so the default sub-tree (§7) is grafted under it in the
    // same history unit — undoing the title undoes the sub-tree with it.
    val after = graftDefaultSubtree(named, cellId, state.tasks.keys).captureTree()
    return TreeMutationDelta(before = before, after = after, label = "Set title")
}

/**
 * PRD §7/§13 "add default sub-tree": the cells [SchedulerIntent.AddDefaultSubtree] fills — the ones the menu
 * was opened on ([SchedulerDomain.contextMenuCopyTargets]) and no others. There is no descent: the gesture
 * names a row, so the template lands in that row's own sub-list, beside whatever it already parents.
 *
 * Two things it still has to get right:
 *
 * - **A textually empty cell is skipped.** A row emptied back to nothing keeps pointing at its now
 *   blank-titled task, so `cell.taskId != null` does not answer "is there a task here to break down?" —
 *   [SchedulerDomain.isTextuallyEmptyCell] is the question, here as everywhere else the graft asks it.
 * - **A task is filled once**, by id. A sub-list belongs to the task id, so two of the selected cells
 *   pointing at one task are one sub-list: filling it once IS filling both occurrences, and walking on
 *   would write the template into it twice.
 */
private fun defaultSubtreeApplicationTargets(
    state: SchedulerState,
    cellIds: List<CellId>,
): List<CellId> {
    val seenTasks = mutableSetOf<TaskId>()
    return cellIds.filter { cellId ->
        val taskId = state.cells[cellId]?.taskId
        taskId != null && !SchedulerDomain.isTextuallyEmptyCell(state, cellId) && seenTasks.add(taskId)
    }
}

/**
 * PRD §4 **Default sub-tree**: graft [SchedulerState.defaultSubtree] under [cellId] when the naming that just
 * happened created a *new leaf* — the cell now points at a task that did not exist in [taskIdsBefore].
 *
 * That last test is the whole gate, and it is deliberately about the **task**, not about the cell's previous
 * emptiness: typing into an empty cell and picking an existing task from the Change Task menu mirrors that
 * task, which already brings its own sub-tree along (a sub-list belongs to the task id), so there is nothing
 * to seed. The other guards are ordinary hygiene — the policy must be on, the template non-empty, the new
 * task titled, and its sub-list still untouched (only the placeholder PRD §4 *Auto-Expansion* just made).
 *
 * **It writes no rows: it records a promise** ([Task.pendingDefaultSubtree]), which
 * [materializeDefaultSubtree] pays the first time somebody opens the cell. The template appears under every
 * new task id — the rows the graft itself lays down included — so what the rule describes has no bottom:
 * grafted eagerly, the release account's four-row template had become 41 tasks nested
 * `planning / AI / planning / AI / …`, doubling with every row typed into its own window (2026-09-21,
 * account 3). Deferring is what makes the rule affordable, and what lets it be the SAME rule in all three
 * drawings of the tree, the §4 template's own window included: an account holds what has been looked at.
 *
 * Until it is opened, a task that owes the template has an empty sub-list — so it is still
 * [SchedulerDomain.isLeafTask], which is what keeps it schedulable. A tree whose every task were born a
 * parent would have no leaves at all, and the scheduler places leaves.
 *
 * A no-op returns the same state instance, so every existing edit path is unaffected while the switch is off.
 */
private fun graftDefaultSubtree(
    state: SchedulerState,
    cellId: CellId,
    taskIdsBefore: Set<TaskId>,
): SchedulerState {
    if (!state.defaultSubtreeEnabled || state.defaultSubtreeIsEmpty) return state
    val taskId = state.cells[cellId]?.taskId ?: return state
    if (taskId in taskIdsBefore) return state
    val task = state.tasks[taskId] ?: return state
    if (task.title.isBlank()) return state
    val childListId = task.childListId ?: return state
    val childList = state.lists[childListId] ?: return state
    // Only a freshly minted, still-empty sub-list is seeded — never one the user (or a paste) already
    // built. An emptied row is not something built: it still points at its blank-titled task.
    if (childList.cellIds.any { !SchedulerDomain.isTextuallyEmptyCell(state, it) }) return state
    // The template's ROOT list — and it is read against the TEMPLATE's own tree wherever the promise is
    // paid, never against this state's root list: [WellKnownIds.ROOT_LIST] is every tree's root id.
    return state.owingDefaultSubtree(taskId, listOf(WellKnownIds.ROOT_LIST))
}

/** [Task.pendingDefaultSubtree] set on [taskId] — the same state instance when it already says that. */
private fun SchedulerState.owingDefaultSubtree(
    taskId: TaskId,
    templateLists: List<CellListId>,
): SchedulerState {
    val task = tasks[taskId] ?: return this
    if (task.pendingDefaultSubtree == templateLists) return this
    return copy(tasks = tasks + (taskId to task.copy(pendingDefaultSubtree = templateLists)))
}

/**
 * PRD §4 **Default sub-tree**: pay what [cellId]'s task owes ([Task.pendingDefaultSubtree]) — the deferred
 * half of [graftDefaultSubtree], run by the gestures that OPEN a cell (the expand arrow, Tab into the child).
 *
 * **One round.** The owed template lists' rows are written here, and each row written owes its own next round
 * ([applyDefaultSubtreeTemplate]). That is what terminates: a gesture writes one level, and the level under
 * it is a promise until the user asks for that too. It is also what bounds the account — the old eager graft
 * wrote the whole template at every creation, and inside the template window that made the template itself
 * grow by a copy of itself per row typed.
 *
 * The switch is read **now**, not when the promise was made: PRD §7 calls it "whether the policy is
 * *currently* applied", so turning it off stops the rows appearing and turning it back on resumes them, while
 * the promise waits. The promise is instead dropped **unpaid** once the sub-list holds a row of its own — the
 * user built that sub-tree, and the template has nothing to add to it (the same condition
 * [graftDefaultSubtree] checks before promising anything).
 */
private fun materializeDefaultSubtree(state: SchedulerState, cellId: CellId): SchedulerState {
    val taskId = state.cells[cellId]?.taskId ?: return state
    val task = state.tasks[taskId] ?: return state
    if (task.pendingDefaultSubtree.isEmpty()) return state
    val childListId = task.childListId ?: return state
    val childList = state.lists[childListId] ?: return state
    if (childList.cellIds.any { !SchedulerDomain.isTextuallyEmptyCell(state, it) }) {
        return state.owingDefaultSubtree(taskId, emptyList())
    }
    if (!state.defaultSubtreeEnabled || state.defaultSubtreeIsEmpty) return state
    var working = state
    for (templateListId in task.pendingDefaultSubtree) {
        working = applyDefaultSubtreeTemplate(working, childListId, state.defaultSubtree, templateListId)
    }
    if (working === state) return state
    return working.owingDefaultSubtree(taskId, emptyList())
}

/**
 * Builds the template's list [templateListId] into [listId], one template row per row, by driving the
 * ordinary editing primitives: each row fills the list's trailing empty placeholder exactly as typing into it
 * would ([applySetCellTitle] then appends the next placeholder), so occurrences, `childTaskIds`, the title
 * index and auto-expansion are all maintained by the code that already owns them rather than by a second copy
 * of those rules here.
 *
 * Those primitives are called **directly**, never through the `SetCellTitle` intent — so the rows it writes
 * are not sessions of their own and nothing here re-enters the reducer.
 *
 * **It writes ONE level.** A row it lays down is a new task id like any other, so the template is owed under
 * it too ([graftDefaultSubtree]) — but as a promise, never as a descent: what each row owes is its template
 * row's **own child list** and then the template's **root**, in that order, which is the copy of that row's
 * sub-tree followed by the "it is a new task id too" part. Writing them instead would not terminate, because
 * the second half of every promise is the template itself. [materializeDefaultSubtree] is what pays the next
 * round, when the user opens the row.
 *
 * **What a row carries.** The template is a real tree of real tasks, so a grafted row is given everything the
 * cell's §13 Edit window holds — the minimum time, the screen switch, "doable during a screen break", the
 * schedule unit and the text — plus its own value in each of the sub-list's weight columns, and the sub-list
 * carries the template's weight-column header. A template that says "this is how the work splits" therefore
 * grafts those proportions rather than a flat list.
 *
 * **The switch** ([DefaultSubtreeTemplate.boundCells], off): the row is assigned the template cell's own
 * `taskId`, which is what mirrors that task's sub-tree under the new cell — and the template's children are
 * therefore *not* applied, because a sub-list belongs to the task id. A binding this tree cannot honour (the
 * task is one only the template knows, belongs to another task tree, or has since been deleted —
 * [SchedulerDomain.canAssignTaskId]) falls back to minting a new task with the row's title, so the row still
 * appears instead of silently vanishing. The **one** refusal that is not a fallback is Constraint 1: the
 * list already holds that very task, so the row is **skipped**. There is nothing to add, and a clone would
 * put the title in twice over a task already sitting there.
 */
private fun applyDefaultSubtreeTemplate(
    state: SchedulerState,
    listId: CellListId,
    template: DefaultSubtreeTemplate,
    templateListId: CellListId,
): SchedulerState {
    val templateList = template.tree.lists[templateListId] ?: return state
    var working = state
    for (templateCellId in templateList.cellIds) {
        val templateCell = template.tree.cells[templateCellId] ?: continue
        val templateTaskId = templateCell.taskId ?: continue
        // A row mirroring a live task keeps its title on that task, so fall back to the live map.
        val templateTask =
            template.tree.tasks[templateTaskId] ?: working.tasks[templateTaskId] ?: continue
        val title = templateTask.title.trim()
        // A blank row is the trailing placeholder every list carries, or a row "deleted" by emptying it.
        if (title.isEmpty()) continue
        val list = working.lists[listId] ?: return working
        // The trailing row to type into — textually empty, which an emptied row is even though it kept its
        // task. Reading `taskId == null` here found no row at all in a sub-list whose last one was emptied.
        val target = list.cellIds.lastOrNull { SchedulerDomain.isTextuallyEmptyCell(working, it) } ?: return working

        // Constraint 1, and the ONE refusal that is not a fallback: a bound row naming a task this list
        // already holds has nothing to add, so it is skipped. Cloning it under a fresh id would put the
        // row's title in the list twice over a task that is already there — two rows for one thing.
        // Every OTHER reason a binding cannot be honoured still mints (below): there the task is absent
        // from this tree, so dropping the row would lose it silently.
        if (templateCellId in template.boundCells &&
            templateTaskId in SchedulerDomain.siblingTaskIds(working, target)
        ) {
            continue
        }

        val reuse =
            templateTaskId.takeIf {
                templateCellId in template.boundCells &&
                    it in working.tasks &&
                    SchedulerDomain.canAssignTaskId(working, target, it)
            }
        working =
            if (reuse != null) {
                applySetCellTitle(applyAssignTaskId(working, target, reuse), target, title, forceTaskId = reuse)
            } else {
                applySetCellTitle(working, target, title)
            }
        // PRD §5: the row's own value in each of the sub-list's weight columns.
        working.cells[target]?.let { placed ->
            working =
                working.copy(
                    cells = working.cells + (target to placed.copy(priorityWeights = templateCell.priorityWeights)),
                )
        }
        // A mirror brings the task it points at, fields and sub-tree and all: there is nothing to copy onto
        // it, and writing the template's fields over a live task would be an edit the user never asked for.
        if (reuse != null) continue
        val newTaskId = working.cells[target]?.taskId ?: continue
        working.tasks[newTaskId]?.let { placed ->
            working =
                working.copy(
                    tasks =
                        working.tasks +
                            (
                                newTaskId to
                                    placed.copy(
                                        minimumMinutes = templateTask.minimumMinutes,
                                        resilience = templateTask.resilience,
                                        scheduleUnit = templateTask.scheduleUnit,
                                        text = templateTask.text,
                                    )
                                ),
                )
        }
        // What this row owes in turn: the template row's own children, then the template's root rows,
        // because a row the graft writes is a new task id and the policy is about every one of them.
        working =
            working.owingDefaultSubtree(
                newTaskId,
                listOfNotNull(templateTask.childListId, WellKnownIds.ROOT_LIST),
            )
    }
    // PRD §5: the sub-list's weight-column header, written after the rows so nothing the placement did to the
    // list can drop it.
    working.lists[listId]?.let { placed ->
        working =
            working.copy(
                lists = working.lists + (listId to placed.copy(weightColumns = templateList.weightColumns)),
            )
    }
    return working
}

private fun applySetCellTitle(
    state: SchedulerState,
    cellId: CellId,
    title: String,
    forceTaskId: TaskId? = null,
): SchedulerState {
    if (!SchedulerDomain.isSelectableCell(state, cellId)) return state
    val cell = state.cells[cellId] ?: return state

    var working = state
    val list = working.lists[cell.parentListId] ?: return state
    // PRD §5 the weight table's **default row**: a cell is textually empty until it is named (§4 — a blank
    // title is what deletes), so this call is the one instant it becomes a ROW of its sub-list's weight
    // table, and the row it arrives on is the table's own default. Read here rather than when the
    // placeholder cell was minted: the user may have edited the default row since, and a placeholder sits
    // at the bottom of every list for as long as the list exists.
    val wasTextuallyEmpty = SchedulerDomain.isTextuallyEmptyCell(state, cellId)

    val isNewTask = forceTaskId == null && cell.taskId == null
    val (taskId, afterAllocate) =
        when {
            forceTaskId != null -> forceTaskId to working
            cell.taskId != null -> cell.taskId to working
            else -> working.allocateTaskId().let { (id, next) -> id to next }
        }
    working = afterAllocate

    val previousTask = working.tasks[taskId]
    val previousTitle = previousTask?.title

    // Emptying a cell clears the task's SHARED title — that is PRD §4's "a blank title deletes" — but only
    // where this cell is the task's own home. Two cases where it is not, and both unbind *this* cell and
    // leave the task alone:
    //
    //  - PRD §4/§8 the **tombstone**: the task still has calendar history — a recorded period (§8) or a
    //    panel (§9) — and blanking it would render those as "(untitled)". The taskId is dropped only once
    //    nothing — no cell, panel, or record — references it ([purgeOrphanTasks]); a cell-less task is never
    //    scheduled ([schedulableLeaves] needs [taskHasCells]).
    //  - PRD §4 a **template row mirroring a live task** ([mirrorsLiveTaskInDefaultSubtree]): the title
    //    belongs to the live tree, and `withDefaultSubtreeCapturedFrom` discards every change to it. Blanking
    //    it therefore emptied nothing — the row came back reading its old title, and the placeholder the
    //    cleanup had dropped beneath it (the emptied cell becomes its list's bottom one) stayed dropped.
    val keepAsTombstone =
        title.isEmpty() && previousTask != null &&
            (
                previousTask.record.isNotEmpty() || working.panels.any { it.taskId == taskId } ||
                    working.mirrorsLiveTaskInDefaultSubtree(cellId, taskId)
                )

    val tasks = working.tasks.toMutableMap()
    val task =
        if (keepAsTombstone) {
            previousTask!!.copy(
                occurrences = SchedulerDomain.sortOccurrences(working, previousTask.occurrences - cellId),
            )
        } else {
            (previousTask ?: Task(id = taskId, title = title)).let { existing ->
                existing.copy(
                    title = title,
                    occurrences = SchedulerDomain.sortOccurrences(
                        working,
                        (existing.occurrences + cellId).distinct(),
                    ),
                )
            }
        }
    tasks[taskId] = task

    if (isNewTask) {
        SchedulerDomain.parentTaskId(working, cellId)?.let { parentId ->
            val linked = SchedulerDomain.linkChildUnderParent(tasks, parentId, taskId)
            tasks.clear()
            tasks.putAll(linked)
        }
    }

    var titleToTaskIds = working.titleToTaskIds
    // A tombstone keeps its previous title, so its title-index entry must survive too.
    if (!keepAsTombstone && previousTitle != null && previousTitle != title) {
        titleToTaskIds = SchedulerDomain.removeTitleMapping(titleToTaskIds, previousTitle, taskId)
    }
    if (title.isNotEmpty()) {
        titleToTaskIds = SchedulerDomain.addTitleMapping(titleToTaskIds, title, taskId)
    }

    val cells = working.cells.toMutableMap()
    val boundCell = cell.copy(taskId = if (keepAsTombstone) null else taskId)
    cells[cellId] =
        if (wasTextuallyEmpty && !keepAsTombstone && title.isNotEmpty()) {
            boundCell.copy(priorityWeights = SchedulerDomain.defaultWeightRow(list))
        } else {
            boundCell
        }

    var lists = working.lists.toMutableMap()
    var currentList = lists[cell.parentListId] ?: return state

    // Set when this call gives the cell's task a brand-new (necessarily empty) sub-list — see the
    // [SchedulerState.expanded] fix-up below.
    var mintedSubList = false

    if (title.isNotEmpty()) {
        val updatedTask = tasks[taskId]!!
        if (updatedTask.childListId == null) {
            mintedSubList = true
            val subListId = CellListId("${taskId.value}/children")
            // The LIST id is derived from the task id — one sub-list per task, for the life of the account.
            // The placeholder CELL is not: it comes off the shared counter like every other cell, because
            // a cell id must be minted once, ever. A hand-built `cell/<task>/children/0` was minted afresh
            // every time a task's sub-list was re-minted, and a cell KEEPS ITS ID when it is dragged
            // elsewhere — so re-titling a task whose sub-list had been pruned (its first child dragged out,
            // the task then emptied) silently overwrote that child's cell where it now lived: one cell id
            // in two lists, its task binding gone, and `parentListId` naming the wrong list. Every rule
            // that asks "what is already in this cell's list" — [SchedulerDomain.siblingTaskIds] and so
            // [SchedulerDomain.canAssignTaskId], [SchedulerDomain.eligibleAssignTaskIds] — then answered
            // about the other list, which is how the same task id could be put twice in one sub-list
            // (PRD §1 Constraint 1).
            val (subPlaceholderId, afterSubPlaceholderId) = working.allocateCellId(subListId)
            working = afterSubPlaceholderId
            val subPlaceholder =
                Cell(
                    id = subPlaceholderId,
                    parentListId = subListId,
                    taskId = null,
                )
            val subList =
                CellList(
                    id = subListId,
                    parentCellId = cellId,
                    cellIds = listOf(subPlaceholderId),
                )
            cells[subPlaceholderId] = subPlaceholder
            lists[subListId] = subList
            tasks[taskId] = updatedTask.copy(childListId = subListId)
        }

        // PRD §4 Auto-Expansion: trailing sibling placeholder only for the list bottom cell.
        if (currentList.cellIds.lastOrNull() == cellId) {
            val (placeholderId, withPlaceholderId) = working.allocateCellId(currentList.id)
            working = withPlaceholderId
            val placeholder =
                Cell(
                    id = placeholderId,
                    parentListId = currentList.id,
                    taskId = null,
                )
            cells[placeholderId] = placeholder
            currentList = currentList.copy(cellIds = currentList.cellIds + placeholderId)
            lists[currentList.id] = currentList
        }
    }

    // PRD §4 Cleanup (inverse of Auto-Expansion): when the cell directly above the
    // trailing empty placeholder is emptied while editing, drop that placeholder so the
    // now-empty cell becomes the list's bottom cell again.
    if (title.isEmpty()) {
        val ids = currentList.cellIds
        val index = ids.indexOf(cellId)
        if (index >= 0 && index == ids.size - 2 && cells[ids.last()]?.taskId == null) {
            cells.remove(ids.last())
            currentList = currentList.copy(cellIds = ids.dropLast(1))
            lists[currentList.id] = currentList
        }
    }

    var result =
        working.copy(
            cells = cells,
            lists = lists,
            tasks = tasks,
            titleToTaskIds = titleToTaskIds,
            // A freshly minted sub-list is never shown expanded. [SchedulerState.expanded] is keyed by CELL
            // id, but a sub-list belongs to the TASK — so a cell that was expanded and then emptied (PRD §4
            // *Deletion*, which takes its task's sub-list with it) keeps its entry, and the next task typed
            // into that same cell would unfold onto nothing but its bare placeholder. The entry goes stale
            // exactly here, where the new sub-list is created, so it is dropped exactly here. The only things
            // that open a new sub-list are then the ones that mean to: "add default sub-tree", Tab into the
            // child, and the user's own click on the arrow. The automatic default-subtree graft is NOT one of
            // them — creating a task leaves its cell collapsed, template or no template.
            expanded = if (mintedSubList) working.expanded - cellId else working.expanded,
        )

    // PRD §4: reassigning this cell to a different task (e.g. typing a new title in Change Task mode spins up
    // a fresh draft) leaves the cell's *previous* task behind. Drop its now-stale occurrence and, when no
    // cell points at it anymore, its ephemeral (auto, non-pinned) scheduler panels — a task typed only to
    // fill this cell is just an editing leftover, so without a real binding [purgeOrphanTasks] removes it.
    // Pinned/manual panels and recorded periods are real user data and still keep it alive (a genuinely
    // deleted scheduled task whose history is preserved, PRD §9).
    // A RENAME RENAMES WHAT THE SCHEDULE ALREADY SHOWS, at the keystroke that renames the task.
    //
    // A task panel carries its own title (that is what the calendar draws it with, and what the cue and the
    // History window read), and the fill is what writes it — so before this, a renamed task kept its old name
    // on every block already on the timeline until the next re-plan happened to rewrite them. A title is not
    // a scheduling rule (`SchedulerDomain.schedulingSignature`: only its blankness and the tie order are), so
    // that re-plan must not be what a rename waits for: the panels are renamed here instead, and the calendar
    // shows the new name on the frame the letter lands in.
    if (!keepAsTombstone && previousTitle != null && previousTitle != title) {
        val renamed = result.panels.map { if (it.taskId == taskId) it.copy(title = title) else it }
        if (renamed != result.panels) result = result.copy(panels = renamed)
    }

    val vacatedTaskId = cell.taskId
    if (vacatedTaskId != null && vacatedTaskId != taskId) {
        result.tasks[vacatedTaskId]?.let { vacated ->
            result = result.copy(
                tasks = result.tasks + (vacatedTaskId to vacated.copy(occurrences = vacated.occurrences - cellId)),
            )
        }
        if (result.cells.values.none { it.taskId == vacatedTaskId }) {
            val trimmed = result.panels.filterNot { it.taskId == vacatedTaskId && it.auto && !it.pinned }
            if (trimmed.size != result.panels.size) result = result.copy(panels = trimmed)
        }
    }

    return SchedulerDomain.purgeOrphanTasks(result)
}

internal data class EmptyCellsDelta(
    val diff: TreeDiff,
    val selectionBefore: SchedulerSelection,
    val selectionAfter: SchedulerSelection,
    // PRD §13: Ctrl+X empties the same cells, so the unit the History window shows says "Cut" instead.
    override val label: String = "Clear cells",
) : Delta {
    constructor(
        treeBefore: TreeSnapshot,
        treeAfter: TreeSnapshot,
        selectionBefore: SchedulerSelection,
        selectionAfter: SchedulerSelection,
        label: String = "Clear cells",
    ) : this(TreeDiff.of(treeBefore, treeAfter), selectionBefore, selectionAfter, label)

    override val details: List<String>
        get() = treeDiffLines(diff) + selectionDiffLines(selectionBefore, selectionAfter)

    override fun undo(state: SchedulerState): SchedulerState =
        diff.applyTo(state, forward = false).copy(selection = selectionBefore)

    override fun redo(state: SchedulerState): SchedulerState =
        diff.applyTo(state, forward = true).copy(selection = selectionAfter)

    override fun commit(state: SchedulerState): SchedulerState =
        diff.applyTo(state, forward = true, exact = true).copy(selection = selectionAfter)
}

internal data class SetSelectionDelta(
    val before: SchedulerSelection,
    val after: SchedulerSelection,
) : Delta {
    override val label: String = "Selection"

    override val details: List<String>
        get() = selectionDiffLines(before, after)

    override fun undo(state: SchedulerState): SchedulerState = state.copy(selection = before)

    override fun redo(state: SchedulerState): SchedulerState = state.copy(selection = after)
}

/**
 * PRD §7: a window-navigation unit — the focus moving from one window (and copy) to another. `Shift+Alt+←`
 * walks it back, which refocuses the window it left (`App` raises whatever [SchedulerState.focusedWindow] names).
 */
internal data class FocusDelta(
    val before: HistoryWindow,
    val after: HistoryWindow,
    val beforeInstance: String = "",
    val afterInstance: String = "",
) : Delta {
    override val label: String = "Focus ${after.name}$afterInstance"

    override val details: List<String>
        get() = listOf("focus: ${before.name}$beforeInstance → ${after.name}$afterInstance")

    override fun undo(state: SchedulerState): SchedulerState =
        state.copy(focusedWindow = before, focusedInstance = beforeInstance)

    override fun redo(state: SchedulerState): SchedulerState =
        state.copy(focusedWindow = after, focusedInstance = afterInstance)
}

/**
 * PRD §5: the selection of a window drawn as a tree — the Default sub-tree, the Search window's
 * sub-trees — before and after a gesture there. The tree's own is [SetSelectionDelta].
 */
internal data class ViewSelectionDelta(
    val window: HistoryWindow,
    val before: SchedulerSelection,
    val after: SchedulerSelection,
) : Delta {
    override val label: String = "Selection (${window.label})"

    override val details: List<String>
        get() = selectionDiffLines(before, after)

    override fun undo(state: SchedulerState): SchedulerState = apply(state, before)

    override fun redo(state: SchedulerState): SchedulerState = apply(state, after)

    private fun apply(state: SchedulerState, selection: SchedulerSelection): SchedulerState =
        when (window) {
            HistoryWindow.DefaultSubtree -> state.copy(defaultSubtreeSelection = selection)
            HistoryWindow.Search -> state.copy(searchSelection = selection)
            else -> state
        }
}

/**
 * PRD §5: the selection of a window not drawn as a tree ([SchedulerState.windowSelections]) — the Search
 * window's row, the Task trees window's open entry — in one copy of the window ([instance]).
 */
internal data class WindowSelectionDelta(
    val window: HistoryWindow,
    val instance: String,
    val before: String?,
    val after: String?,
) : Delta {
    override val label: String = "Selection (${window.label}$instance)"

    override val details: List<String>
        get() = listOf("selected: ${before ?: "nothing"} → ${after ?: "nothing"}")

    override fun undo(state: SchedulerState): SchedulerState = apply(state, before)

    override fun redo(state: SchedulerState): SchedulerState = apply(state, after)

    private fun apply(state: SchedulerState, key: String?): SchedulerState {
        val slot = windowSelectionKey(window, instance)
        return state.copy(windowSelections = if (key == null) state.windowSelections - slot else state.windowSelections + (slot to key))
    }
}

/**
 * PRD §5/§8: the whole panel list before/after a *manual* calendar add, edit, move, or resize. Lives
 * in the [HistoryCategory.Calendar] stack so it is undone/redone only while the calendar is focused
 * (PRD §8). An automatic scheduling run (PRD §9) does NOT use this delta — a derived schedule carries
 * no history unit.
 */
internal data class PanelDelta(
    val changes: EntryChanges<String, TaskPanel>,
    override val label: String = "Calendar edit",
) : Delta {
    /** Built from the whole panel list before and after; only the panels that differ are kept. */
    constructor(before: List<TaskPanel>, after: List<TaskPanel>, label: String = "Calendar edit") :
        this(EntryChanges.ofList(before, after) { it.id }, label)

    override val details: List<String>
        get() = panelDiffLines(changes)

    override fun undo(state: SchedulerState): SchedulerState =
        state.copy(panels = changes.applyToList(state.panels, forward = false) { it.id })

    override fun redo(state: SchedulerState): SchedulerState =
        state.copy(panels = changes.applyToList(state.panels, forward = true) { it.id })

    override fun commit(state: SchedulerState): SchedulerState =
        state.copy(panels = changes.applyToList(state.panels, forward = true, exact = true) { it.id })
}

/**
 * PRD §4 Find & replace: the whole expansion set before/after revealing a search hit. One unit for the
 * whole path, unlike [ToggleExpandDelta] which is one cell — see [SchedulerReducer.reduceRevealCell].
 */
internal data class SetExpandedDelta(
    val changes: SetChanges<CellId>,
) : Delta {
    constructor(before: Set<CellId>, after: Set<CellId>) : this(SetChanges.of(before, after))

    override val label: String = "Expand"

    override val details: List<String>
        get() = changes.added.map { "expand ${it.value}" } + changes.removed.map { "collapse ${it.value}" }

    override fun undo(state: SchedulerState): SchedulerState = state.copy(expanded = changes.applyTo(state.expanded, forward = false))

    override fun redo(state: SchedulerState): SchedulerState = state.copy(expanded = changes.applyTo(state.expanded, forward = true))
}

internal data class ToggleExpandDelta(
    val cellId: CellId,
) : Delta {
    override val label: String = "Expand / collapse"

    override val details: List<String>
        get() = listOf("cell ${cellId.value}")

    override fun undo(state: SchedulerState): SchedulerState = applyToggle(state)

    override fun redo(state: SchedulerState): SchedulerState = applyToggle(state)

    private fun applyToggle(state: SchedulerState): SchedulerState {
        val cell = state.cells[cellId] ?: return state
        if (cellId in state.expanded) {
            return state.copy(expanded = state.expanded - cellId)
        }
        if (cell.taskId == null || SchedulerDomain.isTextuallyEmptyCell(state, cellId)) return state
        val childListId = state.tasks[cell.taskId]?.childListId ?: return state
        if (state.lists[childListId] == null) return state
        return state.copy(expanded = state.expanded + cellId)
    }
}

internal data class TreeMutationDelta(
    val diff: TreeDiff,
    override val label: String = "Tree change",
) : Delta {
    /** Built from the whole tree before and after the change; only what differs is kept ([TreeDiff]). */
    constructor(before: TreeSnapshot, after: TreeSnapshot, label: String = "Tree change") : this(TreeDiff.of(before, after), label)

    override val details: List<String>
        get() = treeDiffLines(diff)

    override fun undo(state: SchedulerState): SchedulerState = diff.applyTo(state, forward = false)

    override fun redo(state: SchedulerState): SchedulerState = diff.applyTo(state, forward = true)

    override fun commit(state: SchedulerState): SchedulerState = diff.applyTo(state, forward = true, exact = true)
}

/**
 * A change to the **task trees** (see [TaskTreeEntry]): selecting another tree, creating one, or renaming
 * one. Captures the whole task-tree state on both sides — the stored trees, the active id, the counter, and
 * the live tree + expansion the selection projects — because a switch moves all of them at once and undo
 * must put every part back together.
 */
internal data class TaskTreeDelta(
    val diff: TaskTreesDiff,
    override val label: String,
) : Delta {
    constructor(before: TaskTreeStateSnapshot, after: TaskTreeStateSnapshot, label: String) : this(TaskTreesDiff.of(before, after), label)

    override val details: List<String>
        get() = buildList {
            diff.added.forEach { add("+ tree \"${it.title}\"") }
            diff.removed.forEach { add("− tree \"${it.title}\"") }
            diff.changed.forEach { if (it.titleBefore != it.titleAfter) add("\"${it.titleBefore}\" → \"${it.titleAfter}\"") }
            if (diff.activeBefore != diff.activeAfter) {
                add("selected: ${diff.activeBefore?.value ?: "—"} → ${diff.activeAfter?.value ?: "—"}")
            }
        }

    override fun undo(state: SchedulerState): SchedulerState = diff.applyTo(state, forward = false)

    override fun redo(state: SchedulerState): SchedulerState = diff.applyTo(state, forward = true)

    override fun commit(state: SchedulerState): SchedulerState = diff.applyTo(state, forward = true, exact = true)
}

/**
 * PRD §7 Keyboard shortcuts: one rebinding of a system-wide chord (or one reset back to the default).
 *
 * Both sides carry the whole **override map**, not just the one shortcut: a reset *removes* an entry, and a
 * delta that stated only "shortcut X is now Y" could not put a removal back. It is a Main unit like the
 * task-tree and template gestures — see [SchedulerState.shortcutBindings] for why this setting is undoable
 * where the account's other settings are not.
 */
internal data class ShortcutBindingDelta(
    val before: Map<GlobalShortcut, ShortcutBinding>,
    val after: Map<GlobalShortcut, ShortcutBinding>,
) : Delta {
    override val label: String = "Keyboard shortcut"

    override val details: List<String>
        get() = GlobalShortcut.entries.mapNotNull { shortcut ->
            val b = GlobalShortcutBindings.chordOf(before, shortcut)
            val a = GlobalShortcutBindings.chordOf(after, shortcut)
            if (b == a) null else "${shortcut.action}: $b → $a"
        }

    override fun undo(state: SchedulerState): SchedulerState = state.copy(shortcutBindings = before)

    override fun redo(state: SchedulerState): SchedulerState = state.copy(shortcutBindings = after)
}

internal object NoOpDelta : Delta {
    override val label: String = "No-op"

    override fun undo(state: SchedulerState): SchedulerState = state

    override fun redo(state: SchedulerState): SchedulerState = state
}

/**
 * PRD §4 **Default sub-tree**: one gesture in the template window.
 *
 * The window emits the task tree's own intents, so a single gesture can be several inner reductions; they are
 * collapsed into this one unit — the same thing [TaskTreeDelta] does for a tree switch — so Ctrl+Z in the
 * window undoes what the user just did rather than a fragment of it.
 *
 * **[live] is the account's tree**, and it is usually empty. A template row pointing at a live task draws
 * that task's own sub-list — one task id, one sub-list — so editing under such a row edits the real tree
 * ([withDefaultSubtreeCapturedFrom]). One gesture is still one unit, so the unit carries both halves; undoing
 * either alone would leave the other standing.
 */
internal data class DefaultSubtreeDelta(
    val tree: TreeDiff,
    val expanded: SetChanges<CellId>,
    val boundCells: SetChanges<CellId>,
    val live: TreeDiff,
    override val label: String,
) : Delta {
    constructor(
        before: DefaultSubtreeTemplate,
        after: DefaultSubtreeTemplate,
        label: String,
        liveBefore: TreeSnapshot? = null,
        liveAfter: TreeSnapshot? = null,
    ) :
        this(
            TreeDiff.of(before.tree, after.tree),
            SetChanges.of(before.expanded, after.expanded),
            SetChanges.of(before.boundCells, after.boundCells),
            if (liveBefore == null || liveAfter == null) TreeDiff.EMPTY else TreeDiff.of(liveBefore, liveAfter),
            label,
        )

    override val details: List<String>
        get() = buildList {
            val b = tree.tasks.before.mapValues { it.value.title }
            val a = tree.tasks.after.mapValues { it.value.title }
            (a.keys - b.keys).forEach { a.getValue(it).ifBlank { null }?.let { t -> add("+ \"$t\"") } }
            (b.keys - a.keys).forEach { b.getValue(it).ifBlank { null }?.let { t -> add("− \"$t\"") } }
            (b.keys intersect a.keys).forEach { id ->
                if (b.getValue(id) != a.getValue(id)) add("\"${b.getValue(id)}\" → \"${a.getValue(id)}\"")
            }
            boundCells.added.forEach { add("switch off: ${it.value}") }
            boundCells.removed.forEach { add("switch on: ${it.value}") }
            addAll(treeDiffLines(live))
        }

    override fun commit(state: SchedulerState): SchedulerState = apply(state, forward = true, exact = true)

    private fun apply(state: SchedulerState, forward: Boolean, exact: Boolean = false): SchedulerState {
        val template = state.defaultSubtree
        // The live half first: it is the ordinary tree change, and it goes through the very same [TreeDiff]
        // the tree's own units use, so a sub-tree edited through a mirror row undoes like any other.
        return live.applyTo(state, forward, exact).copy(
            defaultSubtree =
                template.copy(
                    tree = tree.applyTo(template.tree, forward, exact),
                    expanded = expanded.applyTo(template.expanded, forward),
                    boundCells = boundCells.applyTo(template.boundCells, forward),
                ),
        )
    }

    override fun undo(state: SchedulerState): SchedulerState = apply(state, forward = false)

    override fun redo(state: SchedulerState): SchedulerState = apply(state, forward = true)
}

/**
 * PRD §8/§9: a change to one or more tasks' completed-work [record]s (the periods appended by
 * [advanceSchedule] / device-sleep cuts as auto panels elapse). These were historically applied
 * outside Undo/Redo; routing them through a delta makes them undoable AND lets the debug-time
 * rollback revert future-dated records produced under accelerated time. Captures only the affected
 * tasks' records (before/after) so undo/redo touch nothing else.
 */
internal data class RecordDelta(
    val changes: RecordChanges,
) : Delta {
    constructor(before: Map<TaskId, List<TaskTimeRange>>, after: Map<TaskId, List<TaskTimeRange>>) :
        this(RecordChanges.of(before, after))

    override val label: String = "Record work"

    override val details: List<String>
        get() = changes.tasks.mapNotNull { id ->
            val plus = changes.added[id].orEmpty().size
            val minus = changes.removed[id].orEmpty().size
            if (plus != minus) "${id.value}: +$plus −$minus periods" else null
        }

    override fun undo(state: SchedulerState): SchedulerState = changes.applyTo(state, forward = false)

    override fun redo(state: SchedulerState): SchedulerState = changes.applyTo(state, forward = true)
}

/**
 * PRD §17 sleep schedule: a change to the user's wake time / goal wake / sleep duration. Authoritative
 * user intent (persisted + synced), so it is routed through Undo/Redo and shows in the History window.
 * Captures only the [sleep] schedule (before/after); the derived sleep panels re-fill on the next tick.
 */
internal data class SleepDelta(
    val before: SleepSchedule?,
    val after: SleepSchedule,
) : Delta {
    override val label: String = "Sleep schedule"

    override val details: List<String>
        get() = buildList {
            if (before?.wakeMinutes != after.wakeMinutes)
                add("wake ${before?.let { hhmm(it.wakeMinutes) } ?: "—"} → ${hhmm(after.wakeMinutes)}")
            if (before?.goalWakeMinutes != after.goalWakeMinutes)
                add("goal wake ${before?.let { hhmm(it.goalWakeMinutes) } ?: "—"} → ${hhmm(after.goalWakeMinutes)}")
            if (before?.sleepDurationMinutes != after.sleepDurationMinutes)
                add("duration ${before?.let { hhmm(it.sleepDurationMinutes) } ?: "—"} → ${hhmm(after.sleepDurationMinutes)}")
        }

    override fun undo(state: SchedulerState): SchedulerState = state.copy(sleep = before)

    override fun redo(state: SchedulerState): SchedulerState = state.copy(sleep = after)

    private fun hhmm(minutes: Int): String {
        val h = (minutes / 60) % 24
        val m = minutes % 60
        return h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
    }
}

/**
 * PRD §18 Alarms: a change to the account's **alarm list** — a row added, a row struck off with the bin, or
 * any of a row's settings edited (its time, label, days, ring length, vibration, repeat, on/off switch).
 * Authoritative user intent (persisted + synced), so it is routed through Undo/Redo and shows in the History
 * window, exactly like the sleep schedule beside it.
 *
 * [coalesceKey] is the field-focus session a live text edit belongs to. The Alarms window pushes its whole
 * list on **every keystroke**, so without it typing a five-letter label would leave five units for Ctrl+Z to
 * walk back one character at a time; two consecutive units carrying the same key are one gesture and are
 * merged by [SchedulerReducer]'s commit. It is null for a structural change — an added or removed row, a
 * switch, a weekday — which must never be absorbed into the text edit before it. Never persisted (see
 * [Delta.coalesceKey]).
 */
internal data class AlarmsDelta(
    val changes: EntryChanges<String, org.example.project.scheduler.model.AlarmEntry>,
    override val coalesceKey: String? = null,
) : Delta {
    constructor(
        before: List<org.example.project.scheduler.model.AlarmEntry>,
        after: List<org.example.project.scheduler.model.AlarmEntry>,
        coalesceKey: String? = null,
    ) : this(EntryChanges.ofList(before, after) { it.id }, coalesceKey)

    override val label: String
        get() = listLabel(changes.before.keys.toList(), changes.after.keys.toList(), "alarm")

    override val details: List<String>
        get() = alarmDetails(changes.before.values.toList(), changes.after.values.toList())

    override fun coalesceOnto(previous: Delta): Delta? =
        if (previous is AlarmsDelta && previous.coalesceKey == coalesceKey) copy(changes = previous.changes.then(changes))
        else null

    override fun undo(state: SchedulerState): SchedulerState = state.copy(alarms = changes.applyToList(state.alarms, forward = false) { it.id })

    override fun redo(state: SchedulerState): SchedulerState = state.copy(alarms = changes.applyToList(state.alarms, forward = true) { it.id })

    override fun commit(state: SchedulerState): SchedulerState =
        state.copy(alarms = changes.applyToList(state.alarms, forward = true, exact = true) { it.id })
}

/**
 * PRD §18 Timers: a change to the account's **timer list** — a row added, a row struck off with the bin, or
 * any of a row's settings edited (duration, label, ring length, vibration). [AlarmsDelta]'s rule for the
 * second section of the same window, the coalescing key included.
 *
 * It carries the rows as they are, run state included, so undoing a **deletion** brings the timer back
 * exactly as it was — a running one still running, and still due at the instant it was due at. What it never
 * records is a run-state *transition*: those are not units at all (see [SchedulerIntent.StartTimer]).
 */
internal data class TimersDelta(
    val changes: EntryChanges<String, org.example.project.scheduler.model.TimerEntry>,
    override val coalesceKey: String? = null,
) : Delta {
    constructor(
        before: List<org.example.project.scheduler.model.TimerEntry>,
        after: List<org.example.project.scheduler.model.TimerEntry>,
        coalesceKey: String? = null,
    ) : this(EntryChanges.ofList(before, after) { it.id }, coalesceKey)

    override val label: String
        get() = listLabel(changes.before.keys.toList(), changes.after.keys.toList(), "timer")

    override val details: List<String>
        get() = timerDetails(changes.before.values.toList(), changes.after.values.toList())

    override fun coalesceOnto(previous: Delta): Delta? =
        if (previous is TimersDelta && previous.coalesceKey == coalesceKey) copy(changes = previous.changes.then(changes))
        else null

    override fun undo(state: SchedulerState): SchedulerState = state.copy(timers = changes.applyToList(state.timers, forward = false) { it.id })

    override fun redo(state: SchedulerState): SchedulerState = state.copy(timers = changes.applyToList(state.timers, forward = true) { it.id })

    override fun commit(state: SchedulerState): SchedulerState =
        state.copy(timers = changes.applyToList(state.timers, forward = true, exact = true) { it.id })
}

/**
 * PRD §18 Chronos: a change to the account's **chrono list** — a row added, struck off, or relabelled.
 * [TimersDelta]'s rule: it carries the rows whole, run state included, and never records a run transition.
 */
internal data class ChronosDelta(
    val changes: EntryChanges<String, org.example.project.scheduler.model.ChronoEntry>,
    override val coalesceKey: String? = null,
) : Delta {
    constructor(
        before: List<org.example.project.scheduler.model.ChronoEntry>,
        after: List<org.example.project.scheduler.model.ChronoEntry>,
        coalesceKey: String? = null,
    ) : this(EntryChanges.ofList(before, after) { it.id }, coalesceKey)

    override val label: String
        get() = listLabel(changes.before.keys.toList(), changes.after.keys.toList(), "chrono")

    override val details: List<String>
        get() {
            fun name(c: org.example.project.scheduler.model.ChronoEntry): String = c.label.ifBlank { "chrono" }
            return listDetails(changes.before, changes.after, ::name) { b, a ->
                if (b.label != a.label) listOf("label " + quoted(b.label) + " -> " + quoted(a.label)) else emptyList()
            }
        }

    override fun coalesceOnto(previous: Delta): Delta? =
        if (previous is ChronosDelta && previous.coalesceKey == coalesceKey) copy(changes = previous.changes.then(changes))
        else null

    override fun undo(state: SchedulerState): SchedulerState = state.copy(chronos = changes.applyToList(state.chronos, forward = false) { it.id })

    override fun redo(state: SchedulerState): SchedulerState = state.copy(chronos = changes.applyToList(state.chronos, forward = true) { it.id })

    override fun commit(state: SchedulerState): SchedulerState =
        state.copy(chronos = changes.applyToList(state.chronos, forward = true, exact = true) { it.id })
}

/**
 * The label an [AlarmsDelta] / [TimersDelta] reads under in the History window: what the user did to the
 * list, named after the [noun] the list holds. Rows are identified by id, so a row edited in place is neither
 * an add nor a removal however much of it changed.
 */
private fun listLabel(before: List<String>, after: List<String>, noun: String): String {
    val added = after.count { it !in before }
    val removed = before.count { it !in after }
    return when {
        added > 0 && removed == 0 -> "Add " + noun
        removed > 0 && added == 0 -> "Remove " + noun
        added > 0 || removed > 0 -> "Change " + noun + "s"
        else -> "Edit " + noun
    }
}

/** PRD §18: the per-row lines an [AlarmsDelta] shows under its label — one per row added, removed or edited. */
private fun alarmDetails(
    before: List<org.example.project.scheduler.model.AlarmEntry>,
    after: List<org.example.project.scheduler.model.AlarmEntry>,
): List<String> {
    fun name(a: org.example.project.scheduler.model.AlarmEntry): String =
        hhmmOfDay(a.timeOfDayMinutes) + if (a.label.isBlank()) "" else " " + a.label
    return listDetails(before.associateBy { it.id }, after.associateBy { it.id }, ::name) { b, a ->
        buildList {
            if (b.timeOfDayMinutes != a.timeOfDayMinutes)
                add("time " + hhmmOfDay(b.timeOfDayMinutes) + " -> " + hhmmOfDay(a.timeOfDayMinutes))
            if (b.label != a.label) add("label " + quoted(b.label) + " -> " + quoted(a.label))
            if (b.days != a.days) add("days " + dayInitials(b.days) + " -> " + dayInitials(a.days))
            if (b.soundSeconds != a.soundSeconds)
                add("rings for " + b.soundSeconds + " s -> " + a.soundSeconds + " s")
            addAll(alertChanges(b.alert, a.alert))
            if (b.repeats != a.repeats) add("repeat " + onOff(b.repeats) + " -> " + onOff(a.repeats))
            if (b.enabled != a.enabled) add(onOff(b.enabled) + " -> " + onOff(a.enabled))
        }
    }
}

/** PRD §18: the per-row lines a [TimersDelta] shows under its label. The run state is carried, never listed. */
private fun timerDetails(
    before: List<org.example.project.scheduler.model.TimerEntry>,
    after: List<org.example.project.scheduler.model.TimerEntry>,
): List<String> {
    fun name(t: org.example.project.scheduler.model.TimerEntry): String =
        TimerDomain.formatDuration(t.durationSeconds) + if (t.label.isBlank()) "" else " " + t.label
    return listDetails(before.associateBy { it.id }, after.associateBy { it.id }, ::name) { b, a ->
        buildList {
            if (b.durationSeconds != a.durationSeconds)
                add(
                    "duration " + TimerDomain.formatDuration(b.durationSeconds) + " -> " +
                        TimerDomain.formatDuration(a.durationSeconds),
                )
            if (b.label != a.label) add("label " + quoted(b.label) + " -> " + quoted(a.label))
            if (b.soundSeconds != a.soundSeconds)
                add("rings for " + b.soundSeconds + " s -> " + a.soundSeconds + " s")
            addAll(alertChanges(b.alert, a.alert))
            if (b.goesNegative != a.goesNegative) add("below zero " + onOff(b.goesNegative) + " -> " + onOff(a.goesNegative))
        }
    }
}

/**
 * PRD §11: the lines one row's **alert block** contributes to a History Unit's details — the four channels
 * and the chosen sound, each named only when it moved.
 *
 * One function for the alarms, the timers and the reminders, for the same reason there is one
 * [org.example.project.scheduler.model.AlertSettings]: three spellings of "vibrate off -> on" would be three
 * things to keep in step, and the History window is exactly where a drift between them would show.
 */
private fun alertChanges(
    before: org.example.project.scheduler.model.AlertSettings,
    after: org.example.project.scheduler.model.AlertSettings,
): List<String> = buildList {
    if (before.sound != after.sound) add("sound " + onOff(before.sound) + " -> " + onOff(after.sound))
    // The sound's NAME is worth a line only where the sound is actually on at one end of the change: a row
    // that is silent at both ends changed nothing the user can hear.
    if (before.tone != after.tone && (before.sound || after.sound))
        add("sound " + before.tone.label + " -> " + after.tone.label)
    if (before.voice != after.voice) add("voice " + onOff(before.voice) + " -> " + onOff(after.voice))
    if (before.notification != after.notification)
        add("notification " + onOff(before.notification) + " -> " + onOff(after.notification))
    if (before.vibrate != after.vibrate)
        add("vibrate " + onOff(before.vibrate) + " -> " + onOff(after.vibrate))
}

/**
 * The shared shape of both lists' [Delta.details]: one line per row added, one per row removed, and one per
 * row that stayed and changed — that last one naming the row and then the fields [fieldChanges] found. A row
 * whose only change is one this delta does not list (a timer started while its label was being typed) yields
 * no line rather than an empty one.
 */
private fun <T> listDetails(
    before: Map<String, T>,
    after: Map<String, T>,
    name: (T) -> String,
    fieldChanges: (T, T) -> List<String>,
): List<String> = buildList {
    for ((id, row) in after) if (id !in before) add("added " + name(row))
    for ((id, row) in before) if (id !in after) add("removed " + name(row))
    for ((id, b) in before) {
        val a = after[id] ?: continue
        if (a == b) continue
        val changes = fieldChanges(b, a)
        if (changes.isNotEmpty()) add(name(b) + ": " + changes.joinToString(", "))
    }
}

/** `hh:mm` of a minutes-since-midnight time of day, for the alarm detail lines. */
private fun hhmmOfDay(minutes: Int): String {
    val h = (minutes / 60) % 24
    val m = minutes % 60
    return h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
}

/** The alarm's ringing days as the window draws them (`MTWTFSS` order), or `none` for an empty set. */
private fun dayInitials(days: Set<kotlinx.datetime.DayOfWeek>): String =
    if (days.isEmpty()) {
        "none"
    } else {
        kotlinx.datetime.DayOfWeek.entries
            .filter { it in days }
            .joinToString("") { it.name.take(1) }
    }

private fun quoted(text: String): String = "\"" + text + "\""

private fun onOff(value: Boolean): String = if (value) "on" else "off"

// ---------------------------------------------------------------------------
// PRD §5/§6 History Manager: per-delta "all the data" lines. Each derives the concrete changes of a unit
// from its own before/after data so the history window can list them under the unit's label.
// ---------------------------------------------------------------------------

/** The task title shown for [cellId] in a snapshot ("∅" for an empty / task-less cell). */
private fun TreeSnapshot.cellTitle(cellId: CellId): String =
    cells[cellId]?.taskId?.let { tasks[it]?.title }?.takeIf { it.isNotBlank() } ?: "∅"

/** Whether [cellId] carries authored content (a task with a non-blank title) in this snapshot. */
private fun TreeSnapshot.cellHasContent(cellId: CellId): Boolean =
    cells[cellId]?.taskId?.let { tasks[it]?.title }?.isNotBlank() == true

/**
 * Specifics of a tree mutation: added / removed / renamed cells, and changed weights or task fields.
 * Added / removed *empty* cells are auto-expansion scaffolding (PRD §4: the hidden sub-list placeholder
 * and the trailing sibling placeholder), not authored content — they are deduced from the populated
 * cells and re-derived on undo/redo, so they are not listed as part of the unit's delta.
 */
private fun treeDiffLines(diff: TreeDiff): List<String> {
    val lines = mutableListOf<String>()
    fun title(cells: Map<CellId, Cell>, tasks: Map<TaskId, Task>, id: CellId): String =
        cells[id]?.taskId?.let { tasks[it]?.title }?.takeIf { it.isNotBlank() } ?: "∅"
    val c = diff.cells
    val t = diff.tasks
    (c.after.keys - c.before.keys).map { title(c.after, t.after, it) }.filter { it != "∅" }.forEach { lines += "+ cell \"$it\"" }
    (c.before.keys - c.after.keys).map { title(c.before, t.before, it) }.filter { it != "∅" }.forEach { lines += "− cell \"$it\"" }
    (c.before.keys intersect c.after.keys).forEach { id ->
        // A cell that now points at another task (a title typed into an empty cell creates one) reads as its title.
        val bt = title(c.before, t.before, id)
        val at = title(c.after, t.after, id)
        if (c.before.getValue(id).taskId != c.after.getValue(id).taskId && bt != at) lines += "\"$bt\" → \"$at\""
        val bw = c.before.getValue(id).priorityWeights
        val aw = c.after.getValue(id).priorityWeights
        if (bw != aw) lines += "weights \"${title(c.after, t.after, id)}\": $bw → $aw"
    }
    (t.before.keys intersect t.after.keys).forEach { id ->
        val b = t.before.getValue(id)
        val a = t.after.getValue(id)
        if (b.title != a.title) lines += "\"${b.title.ifBlank { "∅" }}\" → \"${a.title.ifBlank { "∅" }}\""
        if (b.minimumMinutes != a.minimumMinutes) lines += "min \"${a.title}\": ${b.minimumMinutes} → ${a.minimumMinutes} min"
        if (b.scheduleUnit != a.scheduleUnit) lines += "schedule unit \"${a.title}\": ${b.scheduleUnit.size} → ${a.scheduleUnit.size} step(s)"
        if (b.text != a.text) lines += "text \"${a.title}\": ${b.text.length} → ${a.text.length} char(s)"
    }
    (diff.lists.before.keys intersect diff.lists.after.keys).forEach { id ->
        val bc = diff.lists.before.getValue(id).weightColumns
        val ac = diff.lists.after.getValue(id).weightColumns
        if (bc != ac) lines += "columns: $bc → $ac"
    }
    return lines
}

/** Specifics of a selection change: which cell is the main and how many cells are selected. */
private fun selectionDiffLines(before: SchedulerSelection, after: SchedulerSelection): List<String> {
    val lines = mutableListOf<String>()
    if (before.main != after.main) {
        lines += "main: ${before.main?.value ?: "—"} → ${after.main?.value ?: "—"}"
    }
    if (before.selected != after.selected) {
        lines += "selected: ${before.selected.size} → ${after.selected.size} cell(s)"
    }
    return lines
}

/** Specifics of a panel-list change: added (+), removed (−) and modified (~) blocks, with title + time. */
private fun panelDiffLines(changes: EntryChanges<String, TaskPanel>): List<String> {
    val lines = mutableListOf<String>()
    val beforeById = changes.before
    val afterById = changes.after
    (afterById.keys - beforeById.keys).forEach { lines += "+ ${panelSummary(afterById.getValue(it))}" }
    (beforeById.keys - afterById.keys).forEach { lines += "− ${panelSummary(beforeById.getValue(it))}" }
    (beforeById.keys intersect afterById.keys).forEach { id ->
        val a = afterById.getValue(id)
        if (beforeById.getValue(id) != a) lines += "~ ${panelSummary(a)}"
    }
    return lines
}

private fun panelSummary(panel: TaskPanel): String =
    "${panel.title.ifBlank { SchedulerDomain.UNTITLED_LABEL }}  ${formatPanelRange(panel.startEpochMillis, panel.endEpochMillis)}"

private fun formatPanelRange(startMillis: Long, endMillis: Long): String {
    val tz = TimeZone.currentSystemDefault()
    fun hm(millis: Long): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz)
        return t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
    }
    return if (startMillis == endMillis) hm(startMillis) else "${hm(startMillis)}–${hm(endMillis)}"
}
