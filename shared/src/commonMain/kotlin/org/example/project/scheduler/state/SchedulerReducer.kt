package org.example.project.scheduler.state

import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.TimerDomain
import org.example.project.scheduler.domain.RelativePriorityDomain
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ForcedTaskStart
import org.example.project.scheduler.model.ForcedTaskSwitch
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.RelativePriorityPinKey
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings
import org.example.project.scheduler.platform.ShortcutBinding
import org.example.project.scheduler.model.TaskPanel
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
     * `side-dev/README.md` § *$t_p$ 2 modes*: **which mode the now-line is in** — mode 1 while any device of
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
     * PRD §9: the instant every refill materializes the work plan out to, given `now` — **the horizon follows
     * the week the calendar is DISPLAYING** ([SchedulerDomain.scheduleHorizonEndMillis]) so the app never
     * computes days the user is not looking at. The engine injects a provider over the focused week `App.kt`
     * publishes (`SchedulerEngine.setCalendarHorizon`); the default (no engine / tests) is the calendar-closed
     * floor, [SchedulerDomain.MIN_SCHEDULE_HORIZON_MILLIS], which is all a headless app needs for its
     * notifications and cues.
     */
    var scheduleHorizonEndMillis: (Long) -> Long = { SchedulerDomain.scheduleHorizonEndMillis(it, null) }

    fun reduce(state: SchedulerState, intent: SchedulerIntent): SchedulerState {
        return when (intent) {
            is SchedulerIntent.ClickCell -> reduceClick(state, intent)
            is SchedulerIntent.DragSelectCells -> reduceDragSelect(state, intent)
            is SchedulerIntent.MoveSelectedCells -> reduceMoveSelected(state, intent)
            SchedulerIntent.ClearSelection -> reduceClearSelection(state)
            SchedulerIntent.EmptySelectedCells -> reduceEmptySelected(state)
            is SchedulerIntent.ExitEdit -> reduceExitEdit(state, intent.navigation)
            is SchedulerIntent.ToggleExpand -> reduceToggleExpand(state, intent.cellId)
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
            is SchedulerIntent.SetPriorityColumnWeight ->
                commitDelta(state, priorityTreeDelta(state, "Column weight") { applySetPriorityColumnWeight(it, intent.listId, intent.column, intent.weight) })
            is SchedulerIntent.AddPriorityColumn ->
                commitDelta(state, priorityTreeDelta(state, "Add weight column") { applyAddPriorityColumn(it, intent.listId, intent.index) })
            is SchedulerIntent.ResetPriorityColumn ->
                commitDelta(state, priorityTreeDelta(state, "Reset weight column") { applyResetPriorityColumn(it, intent.listId, intent.column) })
            is SchedulerIntent.DeletePriorityColumn ->
                commitDelta(state, priorityTreeDelta(state, "Delete weight column") { applyDeletePriorityColumn(it, intent.listId, intent.column) })
            is SchedulerIntent.MovePriorityColumn ->
                commitDelta(state, priorityTreeDelta(state, "Move weight column") { applyMovePriorityColumn(it, intent.listId, intent.from, intent.to) })
            is SchedulerIntent.RestorePriorityWeights -> {
                val restore = { s: SchedulerState ->
                    applyRestorePriorityWeights(s, intent.listId, intent.weightColumns, intent.cellWeights)
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
            is SchedulerIntent.RecordConductedBreak -> reduceRecordConductedBreak(state, intent)
            is SchedulerIntent.AddPeriodKind -> reduceAddPeriodKind(state, intent.kind)
            is SchedulerIntent.RemovePeriodKind -> reduceRemovePeriodKind(state, intent.kind)
            is SchedulerIntent.SetScheduleUnit ->
                commitDelta(state, priorityTreeDelta(state, "Schedule unit") { applySetScheduleUnit(it, intent.taskId, intent.entries) })
            is SchedulerIntent.SetTaskText ->
                commitDelta(state, priorityTreeDelta(state, "Task text") { applySetTaskText(it, intent.taskId, intent.text) })
            is SchedulerIntent.SetChores -> reduceSetChores(state, intent.entries, intent.todayStartMillis, intent.nowMillis)
            is SchedulerIntent.SetReminderChecked -> reduceSetReminderChecked(state, intent.panelId, intent.checked, intent.nowMillis)
            is SchedulerIntent.AddReminder -> reduceAddReminder(state, intent.reminderId, intent.title, intent.atMillis, intent.checked, intent.pinned)
            is SchedulerIntent.SetAlarms -> reduceSetAlarms(state, intent.entries)
            is SchedulerIntent.SetAlarmEnabled -> reduceSetAlarmEnabled(state, intent.id, intent.enabled)
            is SchedulerIntent.SetTimers -> reduceSetTimers(state, intent.entries)
            is SchedulerIntent.StartTimer -> reduceTimerTransition(state, intent.id) {
                TimerDomain.started(it, intent.nowMillis)
            }
            is SchedulerIntent.PauseTimer -> reduceTimerTransition(state, intent.id) {
                TimerDomain.paused(it, intent.nowMillis)
            }
            is SchedulerIntent.ResetTimer -> reduceTimerTransition(state, intent.id, TimerDomain::reset)
            is SchedulerIntent.SetScreenBreaks ->
                if (state.screenBreaks == intent.screenBreaks) state
                else state.copy(screenBreaks = intent.screenBreaks)
            is SchedulerIntent.RefreshSchedule -> reduceRefreshSchedule(state, intent.nowMillis)
            is SchedulerIntent.ExtendSchedule -> reduceExtendSchedule(state, intent.nowMillis)
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
            is SchedulerIntent.SetLookAwayVoice ->
                if (state.lookAwayVoiceEnabled == intent.enabled) state
                else state.copy(lookAwayVoiceEnabled = intent.enabled)
            is SchedulerIntent.SetNotificationsEnabled ->
                if (state.notificationsEnabled == intent.enabled) state
                else state.copy(notificationsEnabled = intent.enabled)
            is SchedulerIntent.InDefaultSubtree -> reduceInDefaultSubtree(state, intent.inner)
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
            is SchedulerIntent.AddNoScreenPeriod -> reduceAddNoScreenPeriod(state, intent)
            is SchedulerIntent.AddInactivityPeriod -> reduceAddInactivityPeriod(state, intent)
            is SchedulerIntent.UpdateTaskPanel -> reduceUpdateTaskPanel(state, intent)
            is SchedulerIntent.PinRecordAsPanel -> reducePinRecord(state, intent)
            is SchedulerIntent.RemoveTaskPanel -> reduceRemoveTaskPanel(state, intent.id)
            is SchedulerIntent.SetPanelWeights -> reduceSetPanelWeights(state, intent)
            is SchedulerIntent.RemoveTaskPanels -> reduceRemoveTaskPanels(state, intent.ids)
            is SchedulerIntent.ReplaceTaskPanels -> reduceReplaceTaskPanels(state, intent)
            is SchedulerIntent.RemoveRecordPeriod -> reduceRemoveRecordPeriod(state, intent)
            is SchedulerIntent.StripNoScreenRecords -> reduceStripNoScreenRecords(state, intent.ranges)
            is SchedulerIntent.FocusWindow -> reduceFocusWindow(state, intent.window)
            is SchedulerIntent.SetCalendarFocus ->
                reduceFocusWindow(state, if (intent.focused) AppWindow.Calendar else AppWindow.Tree)
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
            is SchedulerIntent.SetGlobalShortcutBinding ->
                reduceSetGlobalShortcutBinding(state, intent.shortcut, intent.binding)
            is SchedulerIntent.SetCopyOptions -> {
                val o = intent.options
                if (state.copyIncludeIds == o.includeIds &&
                    state.copyPriorityTables == o.priorityTables &&
                    state.copyIncludeText == o.includeText
                ) {
                    state
                } else {
                    state.copy(
                        copyIncludeIds = o.includeIds,
                        copyPriorityTables = o.priorityTables,
                        copyIncludeText = o.includeText,
                    )
                }
            }
            is SchedulerIntent.PasteTree -> reducePasteTree(state, intent.text)
            is SchedulerIntent.RecordNotification -> reduceRecordNotification(state, intent)
            is SchedulerIntent.RecordSupabaseUsage -> reduceRecordSupabaseUsage(state, intent)
            SchedulerIntent.Undo -> undo(state, contentCategory(state))
            SchedulerIntent.Redo -> redo(state, contentCategory(state))
            SchedulerIntent.UndoSelection -> undo(state, HistoryCategory.Selection)
            SchedulerIntent.RedoSelection -> redo(state, HistoryCategory.Selection)
        }
    }

    /**
     * PRD §5 context-aware pointer: Ctrl+Z/Y target the Edit Mode stack while editing, the calendar
     * stack while the calendar is focused, otherwise "the rest". Each stack's pointer only walks its
     * own units, so the active context skips every history unit that does not belong to it.
     */
    private fun contentCategory(state: SchedulerState): HistoryCategory =
        when {
            state.editSession != null -> HistoryCategory.Edit
            state.calendarFocused -> HistoryCategory.Calendar
            else -> HistoryCategory.Main
        }

    /**
     * Append a posted notification to [SchedulerState.notificationLog], keeping only the FIRST
     * [SchedulerState.MAX_NOTIFICATION_LOG] entries. Once the log is full this is a no-op (returns the same
     * state instance), so the ViewModel skips the persist and no further notifications are recorded.
     */
    private fun reduceRecordNotification(
        state: SchedulerState,
        intent: SchedulerIntent.RecordNotification,
    ): SchedulerState {
        if (state.notificationLog.size >= SchedulerState.MAX_NOTIFICATION_LOG) return state
        return state.copy(
            notificationLog = state.notificationLog +
                NotificationLogEntry(intent.timeMillis, intent.title, intent.message),
        )
    }

    /**
     * Append one Supabase call to [SchedulerState.supabaseUsageLog], a **rolling tail** that keeps only the most
     * recent [SchedulerState.MAX_SUPABASE_USAGE_LOG] entries (drops the oldest). Unlike the notification log this
     * never saturates to a no-op — it always reflects the latest traffic — but it is likewise a per-device,
     * non-syncing diagnostic (see [SchedulerIntent.RecordSupabaseUsage]).
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

    private fun reduceBeginEdit(state: SchedulerState, intent: SchedulerIntent.BeginEdit): SchedulerState {
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
        return commitDelta(withSession, editTextDelta(base, text), HistoryCategory.Edit)
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
     * PRD §18 Alarms: store the alarm list (minting an id for any blank row). Authoritative state, but — like
     * the reminders list — it is not routed through the Undo/Redo history; it changes the schedule of nothing,
     * so no panels are regenerated either.
     */
    private fun reduceSetAlarms(
        state: SchedulerState,
        entries: List<org.example.project.scheduler.model.AlarmEntry>,
    ): SchedulerState {
        val withIds = AlarmDomain.assignAlarmIds(entries)
        return if (state.alarms == withIds) state else state.copy(alarms = withIds)
    }

    /** PRD §18 Alarms: arm/disarm one alarm (the row switch, and a one-off disarming itself after it rang). */
    private fun reduceSetAlarmEnabled(state: SchedulerState, id: String, enabled: Boolean): SchedulerState {
        val alarm = state.alarms.firstOrNull { it.id == id } ?: return state
        if (alarm.enabled == enabled) return state
        return state.copy(alarms = state.alarms.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /**
     * PRD §18 Timers: store the timer list (minting an id for any blank row, and healing the run fields into
     * the one shape they are allowed to be in). Authoritative state, and — like the alarms beside it — not
     * routed through the Undo/Redo history.
     *
     * The rows carry their own run state, so a live text edit round-trips it untouched; the transitions below
     * are what actually move it.
     */
    private fun reduceSetTimers(
        state: SchedulerState,
        entries: List<org.example.project.scheduler.model.TimerEntry>,
    ): SchedulerState {
        val withIds = TimerDomain.assignTimerIds(entries).map(TimerDomain::healed)
        return if (state.timers == withIds) state else state.copy(timers = withIds)
    }

    /**
     * PRD §18 Timers: apply one of the three run transitions ([TimerDomain.started] / [TimerDomain.paused] /
     * [TimerDomain.reset]) to the timer [id]. One helper for all three because each is already a pure
     * function on the entry — the reducer only has to find the row and skip the write when nothing moved.
     * A no-op when the id is unknown or the transition changed nothing.
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

    private fun reduceCopySelection(state: SchedulerState): SchedulerState {
        if (state.editSession != null) return state
        val text = SchedulerDomain.copyTreeText(state, state.selection)
        if (text.isEmpty()) return state
        return state.copy(clipboard = text.split('\n'))
    }

    /**
     * PRD §4/§13 Cut (Ctrl+X): the whole-sub-tree copy [reduceCopySelection] takes, and then those same cells are
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
     * Where the rows land is [defaultSubtreeApplicationTargets]: the **leaves** of the sub-tree the cell
     * roots, which is the cell itself when it parents nothing. A template is a description of how a piece of
     * work is broken down, so asking for it on a cell that is already broken down means asking for it on the
     * pieces — pushing a second copy of it in beside them is not what "add default sub-tree" reads as.
     *
     * The rows it lays down are built by [applyDefaultSubtreeTemplate], which drives the editing primitives
     * directly — so a seeded row never seeds in turn, here as in the graft.
     */
    private fun reduceAddDefaultSubtree(state: SchedulerState, cellIds: List<CellId>): SchedulerState {
        if (state.editSession != null) return state
        if (state.defaultSubtreeIsEmpty) return state
        val targets = defaultSubtreeApplicationTargets(state, cellIds)
        if (targets.leaves.isEmpty()) return state
        val before = state.captureTree()
        var working = state
        for (cellId in targets.leaves) {
            val childListId = working.cells[cellId]?.taskId?.let { working.tasks[it]?.childListId } ?: continue
            working =
                applyDefaultSubtreeTemplate(working, childListId, state.defaultSubtree, WellKnownIds.MAIN_LIST)
        }
        val after = working.captureTree()
        if (before == after) return state
        return commitDelta(
            // Everything walked, not only what was filled: the seeded rows sit at the bottom of the sub-tree,
            // so the ancestors have to be open for them to be visible at all.
            working.copy(expanded = state.expanded + targets.visited),
            TreeMutationDelta(before = before, after = after, label = "Add default sub-tree"),
        )
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
            inner is SchedulerIntent.UndoSelection || inner is SchedulerIntent.RedoSelection
        ) {
            return state
        }
        val before = state.defaultSubtree
        val projected = state.projectDefaultSubtree()
        val reduced = reduce(projected, inner)
        if (reduced === projected) return state
        val folded = state.withDefaultSubtreeCapturedFrom(reduced)
        // A gesture that only moved the window's own caret/selection changes no template and records no unit
        // — the same rule the tree follows for a selection-only change.
        if (folded.defaultSubtree == before) return folded
        return commitDelta(
            folded,
            DefaultSubtreeDelta(before = before, after = folded.defaultSubtree, label = "Default sub-tree"),
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
        // An empty row has no task behind it, so it has no switch to flip.
        if (template.tree.cells[cellId]?.taskId == null) return state
        val next =
            if (bound) template.boundCells + cellId else template.boundCells - cellId
        if (next == template.boundCells) return state
        val after = template.copy(boundCells = next)
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
        val seeded =
            if (session == null) {
                cleaned
            } else {
                val grafted = graftDefaultSubtree(cleaned, session.cellId, session.treeBefore.tasks.keys)
                // Show what was just created rather than leaving it folded away behind a collapsed cell.
                if (grafted === cleaned) cleaned else grafted.copy(expanded = grafted.expanded + session.cellId)
            }
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
     * The graft expands the cell it seeded, so the toggle itself is applied only where the forced exit did
     * not already leave the cell in the state the click asked for — and never on a cell the post-edit cleanup
     * has just removed.
     */
    private fun reduceToggleExpand(state: SchedulerState, cellId: CellId): SchedulerState {
        if (state.editSession == null) return commitDelta(state, ToggleExpandDelta(cellId))
        val wantExpanded = cellId !in state.expanded
        val exited = endEditSession(state)
        if (exited.cells[cellId] == null) return exited
        if ((cellId in exited.expanded) == wantExpanded) return exited
        return commitDelta(exited, ToggleExpandDelta(cellId))
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

    private fun reduceClearSelection(state: SchedulerState): SchedulerState {
        if (state.selection.main == null &&
            state.selection.selected.isEmpty() &&
            state.editSession == null
        ) {
            return state
        }
        var next = state
        if (state.editSession != null) {
            next = endEditSession(state)
        }
        if (next.selection.main == null && next.selection.selected.isEmpty()) return next
        return commitDelta(
            next,
            SetSelectionDelta(before = next.selection, after = SchedulerSelection()),
            HistoryCategory.Selection,
        )
    }

    /**
     * PRD §7 window navigation. Moving focus to a *floating* window forcibly exits any tree Edit Mode and
     * clears the tree selection (PRD §4 "Forced Exit"; the selection "disappears") — [reduceClearSelection]
     * does both and records the Selection-state change. The navigation itself is then recorded as a
     * WindowNav History Unit (shown in the History Manager but, for now, not walked by any undo/redo
     * command). A no-op when focus does not actually change.
     */
    private fun reduceFocusWindow(state: SchedulerState, window: AppWindow): SchedulerState {
        if (state.focusedWindow == window) return state
        val cleared = if (window != AppWindow.Tree) reduceClearSelection(state) else state
        return commitDelta(
            cleared,
            FocusDelta(before = cleared.focusedWindow, after = window),
            HistoryCategory.WindowNav,
        )
    }

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
                            next = commitDelta(next, ToggleExpandDelta(editingCellId))
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
     * PRD §8 "add a no-screen period": lay a "No screen" panel; on-screen task panels it covers are trimmed,
     * and the on-screen RECORDS under its elapsed part are stripped ([stripRecordsUnderPeriod]).
     */
    private fun reduceAddNoScreenPeriod(
        state: SchedulerState,
        intent: SchedulerIntent.AddNoScreenPeriod,
    ): SchedulerState {
        val end = maxOf(intent.endEpochMillis, intent.startEpochMillis + SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS)
        val (panelId, allocated) = state.allocatePanelId()
        val panel =
            TaskPanel(
                id = panelId,
                taskId = null,
                title = "No screen",
                startEpochMillis = intent.startEpochMillis,
                endEpochMillis = end,
                noScreen = true,
                periodKind = PeriodKinds.NO_SCREEN,
            )
        val (resolved, resolvedPanels) = resolveScreenOverrides(allocated, allocated.panels + panel, panelId)
        return stripRecordsUnderPeriod(commitPanels(resolved, resolvedPanels, label = "Add no-screen period"), panel)
    }

    /**
     * PRD §8/§12 "add an inactivity period": a real GREY panel recording the user was away. Grey means the
     * scheduler places nothing here — not even a task that needs no screen — so unlike a no-screen period it
     * overrides EVERY task panel it covers (see [resolveScreenOverrides]) and strips every task record under
     * its elapsed part, on-screen or not. That is what "an inactivity period from ∞ to now" does: it wipes
     * the past clean of scheduled work.
     */
    private fun reduceAddInactivityPeriod(
        state: SchedulerState,
        intent: SchedulerIntent.AddInactivityPeriod,
    ): SchedulerState {
        val end = maxOf(intent.endEpochMillis, intent.startEpochMillis + SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS)
        val (panelId, allocated) = state.allocatePanelId()
        val panel =
            TaskPanel(
                id = panelId,
                taskId = null,
                title = "Inactivity",
                startEpochMillis = intent.startEpochMillis,
                endEpochMillis = end,
                inactivity = true,
                periodKind = PeriodKinds.NO_TASK,
            )
        val (resolved, resolvedPanels) = resolveScreenOverrides(allocated, allocated.panels + panel, panelId)
        return stripRecordsUnderPeriod(
            commitPanels(resolved, resolvedPanels, label = "Add inactivity period"),
            panel,
        )
    }

    /**
     * PRD §8/§9: true for a panel the screen-override rule treats as an **on-screen task panel** — a real
     * (auto or user-authored) task block whose task is on-screen ([Task.onScreen]; a calendar-only panel
     * with no backing task defaults to on-screen). Reminder tags, screen-break/sleep bands and the
     * no-screen/inactivity periods themselves are never one.
     */
    private fun isOnScreenTaskPanel(state: SchedulerState, panel: TaskPanel): Boolean =
        isTaskPanel(panel) && (panel.taskId?.let { state.tasks[it]?.onScreen } ?: true)

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
     * PRD §8 screen-override resolution: after the user lays / moves / resizes panel [changedId], trim or
     * delete the panels it now overlaps that cannot coexist with it — an on-screen task panel overrides
     * no-screen periods to fit itself, and a no-screen period overrides on-screen task panels. An
     * **inactivity period is grey**, and grey means the scheduler places nothing there (PRD §8/§9): it
     * overrides every task panel it covers, off-screen ones included, and every task panel overrides it in
     * turn. A covered panel is
     * deleted; one covered at an edge is trimmed; one covered in the middle is split (the far piece gets a
     * fresh id). Pieces shorter than [SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS] are dropped as slivers.
     */
    private fun resolveScreenOverrides(
        state: SchedulerState,
        panels: List<TaskPanel>,
        changedId: String,
    ): Pair<SchedulerState, List<TaskPanel>> {
        val changed = panels.firstOrNull { it.id == changedId } ?: return state to panels
        val trimTarget: (TaskPanel) -> Boolean =
            when {
                // Grey refuses everybody (PRD §8/§9), so an inactivity period overrides every task panel.
                changed.inactivity -> { p -> isTaskPanel(p) }
                changed.noScreen -> { p -> isOnScreenTaskPanel(state, p) }
                !isTaskPanel(changed) -> return state to panels
                isOnScreenTaskPanel(state, changed) -> { p -> p.noScreen || p.inactivity }
                // An off-screen task may run inside a no-screen period, but never inside a grey one.
                else -> { p -> p.inactivity }
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
                pinned = derivePinned(intent.pins),
                pins = intent.pins,
                auto = false,
                layoutWeight = weight,
            )
        val (resolved, resolvedPanels) =
            resolveScreenOverrides(allocated, allocated.panels.toMutableList().also { it[index] = updated }, panelId)
        val committed = commitPanels(resolved, resolvedPanels, label = "Edit panel")
        // PRD §8/§9: moving/resizing a no-screen or inactivity period re-applies its rule over its NEW span,
        // exactly as laying it did — a period dragged over a past task must strip that work too.
        return if (updated.noScreen || updated.inactivity) {
            stripRecordsUnderPeriod(committed, updated)
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
     * force ([scheduleHorizonEndMillis] — the end of the DISPLAYED week, not a fixed +168h) with
     * [SchedulerDomain.fillSchedule]. Gated by PRD §7: while [SchedulerState.automaticSchedule] is off
     * the refill is skipped (the event waits) — but the advance still runs so completed work is
     * recorded. The refill is NOT recorded as a History Unit (PRD §9): a schedule is derived from the
     * task tree / calendar state, not an independent user action, so it carries no undo entry — undo/redo
     * walk only the user changes and the schedule re-derives from whatever state they land on. A no-op
     * tick returns the same instance.
     */
    private fun reduceRefreshSchedule(state: SchedulerState, nowMillis: Long): SchedulerState {
        val advanced = commitRecordChanges(state, advanceSchedule(state, nowMillis, noScreenEvidence()))
        if (!advanced.automaticSchedule) return advanced
        val filled =
            SchedulerDomain.fillSchedule(
                advanced,
                nowMillis,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = tpMode(),
                horizonMillis = scheduleHorizonEndMillis(nowMillis),
            )
        if (filled == advanced.panels) return advanced
        return advanced.copy(panels = filled)
    }

    /**
     * PRD §7 **"Switch task"** ([SchedulerIntent.ForceTaskSwitch]): record the user's refusal of the task the
     * now-line is on, then re-plan so something else starts here.
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
        return reduceRefreshSchedule(state.copy(forcedSwitch = ForcedTaskSwitch(taskId, nowMillis)), nowMillis)
    }

    /**
     * PRD §13 **"start this task now"** ([SchedulerIntent.ForceTaskStart]): record the user's request for
     * [taskId] and re-plan on the spot, so that task is what the now-line lands on.
     *
     * The mirror image of [reduceForceTaskSwitch], for the same reasons and with the same shape: the request
     * is a [org.example.project.scheduler.model.ForcedTaskStart] — a fact about the past, read by the fill as
     * the task of its first slot — not an edit to any rule, which is why it re-plans from inside this reducer
     * instead of riding [SchedulerDomain.schedulingSignature]. Only a **schedulable leaf** can be asked for: a
     * parent task is a grouping the scheduler never places, so the request would be unanswerable. Asking for a
     * task also clears an outstanding refusal **of that same task** — the user has just said explicitly what
     * the earlier press said only negatively, and leaving both standing would have the fill place the task and
     * go on refusing it. The marker is stored even while §7 auto-scheduling is off: the plan is not being
     * computed at all then, and the request is honoured by the fill that resumes it.
     */
    private fun reduceForceTaskStart(state: SchedulerState, taskId: TaskId): SchedulerState {
        if (!SchedulerDomain.taskHasCells(state, taskId) || !SchedulerDomain.isLeafTask(state, taskId)) return state
        val now = clock.nowMillis()
        val requested =
            state.copy(
                forcedStart = ForcedTaskStart(taskId, now),
                forcedSwitch = state.forcedSwitch?.takeIf { it.taskId != taskId },
            )
        return reduceRefreshSchedule(requested, now)
    }

    /**
     * PRD §9 rolling horizon ([SchedulerIntent.ExtendSchedule]): advance, then materialize the plan further
     * WITHOUT re-planning it. Everything already laid down ahead of the now-line is kept and fed to the
     * scheduling walk as committed service, so the tail continues the same plan rather than replacing it — a horizon that
     * grew (time passing, or the calendar navigating to a further week) is not a change to the scheduling
     * rules and must not rewrite what the user is looking at. A no-op tick returns the same instance.
     */
    private fun reduceExtendSchedule(state: SchedulerState, nowMillis: Long): SchedulerState {
        val advanced = commitRecordChanges(state, advanceSchedule(state, nowMillis, noScreenEvidence()))
        if (!advanced.automaticSchedule) return advanced
        val materializedUntil = SchedulerDomain.firstFreeMoment(advanced.panels, nowMillis)
        val filled =
            SchedulerDomain.fillSchedule(
                advanced,
                nowMillis,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = tpMode(),
                horizonMillis = scheduleHorizonEndMillis(nowMillis),
                keepExistingUntilMillis = materializedUntil,
            )
        if (filled == advanced.panels) return advanced
        return advanced.copy(panels = filled)
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
        val filled =
            SchedulerDomain.fillSchedule(
                committed,
                now,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = tpMode(),
                horizonMillis = scheduleHorizonEndMillis(now),
            )
        return committed.copy(panels = filled)
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
        return updated.copy(
            panels = SchedulerDomain.fillSchedule(
                updated,
                now,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = tpMode(),
                horizonMillis = scheduleHorizonEndMillis(now),
            ),
        )
    }

    /**
     * PRD §9/§12 retroactive: apply the "assume nothing happened" rule to work banked BEFORE that rule could
     * see the OS lock history — subtract [ranges] from every ON-SCREEN task's record and materialize the
     * removed spans as "Inactivity" panels, exactly as the banking path does going forward.
     *
     * Off-screen tasks are untouched: they are ALLOWED to run in a no-screen period (PRD §9), so their records
     * over one are true. Same reason [appendRecordOutsideNoScreen] banks their whole span.
     *
     * Refills for the same reason [reduceRemoveRecordPeriod] does: the records seed the virtual clocks' past
     * service, so removing some genuinely changes the plan, and the engine's signature watcher cannot see it.
     * Returns the same instance when nothing was covered, so the start-up pass is a no-op on a clean account.
     */
    private fun reduceStripNoScreenRecords(
        state: SchedulerState,
        ranges: List<TaskTimeRange>,
    ): SchedulerState = stripRecords(state, ranges, onScreenOnly = true)

    /**
     * PRD §8/§9/§12: the same rule the moment a period is **laid by hand** rather than at the next engine
     * start — the span the user just declared they were not at a screen (or not working at all) cannot hold
     * banked work, so the records under its elapsed part go. [TaskPanel.inactivity] decides who is affected:
     * a no-screen period exempts off-screen tasks (§9 lets them run inside one), a grey inactivity period
     * exempts nobody.
     *
     * A record is not an Undo/Redo unit (it lives outside the history, like every other banking side effect),
     * so undoing the period restores the panels it trimmed but not the records it stripped — the same
     * contract [reducePinRecord] and the advance tick already work under.
     */
    private fun stripRecordsUnderPeriod(state: SchedulerState, panel: TaskPanel): SchedulerState =
        stripRecords(
            state,
            listOf(TaskTimeRange(panel.startEpochMillis, panel.endEpochMillis)),
            onScreenOnly = !panel.inactivity,
        )

    /**
     * Subtracts [ranges] from the record of every affected task (see [reduceStripNoScreenRecords] /
     * [stripRecordsUnderPeriod] for which tasks those are) and materializes what was removed as past
     * "Inactivity" panels. Returns the same instance when nothing was covered.
     */
    private fun stripRecords(
        state: SchedulerState,
        ranges: List<TaskTimeRange>,
        onScreenOnly: Boolean,
    ): SchedulerState {
        if (ranges.isEmpty()) return state
        val merged = SchedulerDomain.mergeOccupied(ranges)
        val removed = ArrayList<TaskTimeRange>()
        var tasks = state.tasks
        for ((id, task) in state.tasks) {
            if ((onScreenOnly && !task.onScreen) || task.record.isEmpty()) continue
            val kept = SchedulerDomain.subtractRegions(task.record, merged)
            if (kept == task.record) continue
            removed += SchedulerDomain.intersectRegions(task.record, merged)
            tasks = tasks + (id to task.copy(record = kept))
        }
        if (removed.isEmpty()) return state
        val stripped = materializePastInactivity(state.copy(tasks = tasks), removed)
        if (!stripped.automaticSchedule) return stripped
        val now = clock.nowMillis()
        return stripped.copy(
            panels = SchedulerDomain.fillSchedule(
                stripped,
                now,
                liveRest = liveRestGap(),
                noScreenEvidence = noScreenEvidence(),
                tpMode = tpMode(),
                horizonMillis = scheduleHorizonEndMillis(now),
            ),
        )
    }

    private fun undo(state: SchedulerState, category: HistoryCategory): SchedulerState {
        val history = state.histories.forCategory(category)
        val pointer = history.pointer
        if (pointer < 0) return state
        val unit = history.units[pointer]
        val undone = unit.delta.undo(state)
        val moved =
            undone.copy(
                histories = state.histories.withCategory(category, history.copy(pointer = pointer - 1)),
            )
        return if (category == HistoryCategory.Edit) syncEditDraft(moved) else moved
    }

    private fun redo(state: SchedulerState, category: HistoryCategory): SchedulerState {
        val history = state.histories.forCategory(category)
        val next = history.pointer + 1
        if (next >= history.units.size) return state
        val unit = history.units[next]
        val redone = unit.delta.redo(state)
        val moved =
            redone.copy(
                histories = state.histories.withCategory(category, history.copy(pointer = next)),
            )
        return if (category == HistoryCategory.Edit) syncEditDraft(moved) else moved
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
                label = "Task tree \"${target.title.ifBlank { "(untitled)" }}\"",
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
                label = "Delete task tree \"${entry.title.ifBlank { "(untitled)" }}\"",
            ),
        )
    }

    private fun commitDelta(
        state: SchedulerState,
        forward: Delta,
        category: HistoryCategory = HistoryCategory.Main,
    ): SchedulerState {
        val newState = forward.redo(state)
        val history = state.histories.forCategory(category)

        // PRD §5 Branching: a new mutation after an undo orphans the redo units — keep only the prefix
        // up to the pointer, then append this unit after it.
        val retained =
            if (history.pointer == history.units.lastIndex) history.units
            else history.units.take(history.pointer + 1)

        // PRD §6: stamp the change's wall-clock time; chronoId stays 0 unless an already-retained unit
        // shares this exact timestamp, in which case it is the next tie-break index (1, 2, …).
        val now = clock.nowMillis()
        val newUnit =
            HistoryUnit(
                timeMillis = now,
                chronoId = retained.count { it.timeMillis == now }.toLong(),
                delta = forward,
                debugTainted = debugTainting(),
            )
        val appendedUnits = retained + newUnit
        val appendedPointer = retained.size

        // PRD §5: each category's history list is capped — drop the oldest units once it exceeds
        // [MAX_HISTORY_UNITS], shifting the pointer back by however many were removed. Debug-tainted
        // units are exempt from the cap: dropping one would break the chain the restart rollback walks,
        // leaving a half-reverted state, so only the oldest *untainted* units are evicted.
        val overflow = (appendedUnits.size - MAX_HISTORY_UNITS).coerceAtLeast(0)
        val (cappedUnits, removed) = dropOldestUntainted(appendedUnits, overflow)
        val cappedPointer = appendedPointer - removed

        return newState.copy(
            histories =
                state.histories.withCategory(
                    category,
                    history.copy(pointer = cappedPointer, units = cappedUnits),
                ),
        )
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

        val appliedTainted =
            histories.all().flatMap { (_, history) ->
                history.units.filterIndexed { index, unit -> unit.debugTainted && index <= history.pointer }
            }.sortedWith(
                compareByDescending<HistoryUnit> { it.timeMillis }.thenByDescending { it.chronoId },
            )
        var reverted = state
        for (unit in appliedTainted) reverted = unit.delta.undo(reverted)

        var newHistories = histories
        for ((category, history) in histories.all()) {
            if (history.units.none { it.debugTainted }) continue
            val kept = ArrayList<HistoryUnit>(history.units.size)
            var droppedAtOrBeforePointer = 0
            history.units.forEachIndexed { index, unit ->
                if (unit.debugTainted) {
                    if (index <= history.pointer) droppedAtOrBeforePointer++
                } else {
                    kept.add(unit)
                }
            }
            newHistories =
                newHistories.withCategory(
                    category,
                    history.copy(units = kept, pointer = history.pointer - droppedAtOrBeforePointer),
                )
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
    // Restore this cell's priority-weight row (PRD §4/§5). The row belongs to the CELL, so a mirror gets it too.
    working.cells[cellId]?.let { c ->
        working = working.copy(cells = working.cells + (cellId to c.copy(priorityWeights = node.rowWeights)))
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
 * PRD §9/§12: every stretch an on-screen task must NOT bank a record over — the user's hand-drawn "No screen"
 * panels UNIONED with what the devices observed ([SchedulerReducer.noScreenEvidence]).
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
    val drawn = state.panels.filter { it.noScreen }.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
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
    val coveredPieces = ArrayList<TaskTimeRange>()
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
        coveredPieces += noScreenCoveredPieces(tasks, noScreenRanges, taskId, startMillis, endMillis)
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
    return materializePastInactivity(advanced, coveredPieces)
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
 * PRD §9/§12 "past no-screen ⇒ past inactivity": the parts of a banked `[start, end]` span that a
 * no-screen period covered for an on-screen task — exactly the spans [appendRecordOutsideNoScreen]
 * refuses to record. [materializePastInactivity] turns them into real "Inactivity" panels.
 */
private fun noScreenCoveredPieces(
    tasks: Map<TaskId, Task>,
    noScreenRanges: List<TaskTimeRange>,
    taskId: TaskId?,
    startMillis: Long,
    endMillis: Long,
): List<TaskTimeRange> {
    if (taskId == null || endMillis <= startMillis || noScreenRanges.isEmpty()) return emptyList()
    if (!(tasks[taskId]?.onScreen ?: true)) return emptyList()
    val span = listOf(TaskTimeRange(startMillis, endMillis))
    return SchedulerDomain.subtractRegions(span, SchedulerDomain.subtractRegions(span, noScreenRanges))
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
 * no-screen/inactive period, or a completed Sleep-toggle session — as persisted "Sleep" panels. Mirrors
 * [materializePastInactivity]: spans an existing MATERIALIZED Sleep panel already covers are skipped and
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

/**
 * PRD §8/§9/§12: materialize [pieces] — elapsed spans where scheduled on-screen work was covered by a
 * no-screen period — as real "Inactivity" panels. The no-screen panel is decorative (§8 taxonomy), so
 * without this the covered stretch would hold no real panel at all. Spans an inactivity panel already
 * covers are skipped and sub-minute slivers dropped. Like the record bank this runs outside Undo/Redo
 * and must never count as a syncable change on its own (it rides the next authoritative push).
 */
private fun materializePastInactivity(
    state: SchedulerState,
    pieces: List<TaskTimeRange>,
): SchedulerState {
    if (pieces.isEmpty()) return state
    val existing =
        state.panels.filter { it.inactivity }.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
    val fresh =
        SchedulerDomain.subtractRegions(SchedulerDomain.mergeOccupied(pieces), existing)
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
                title = "Inactivity",
                startEpochMillis = piece.startEpochMillis,
                endEpochMillis = piece.endEpochMillis,
                inactivity = true,
                periodKind = PeriodKinds.NO_TASK,
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
    val covered =
        noScreenCoveredPieces(base.tasks, noScreenRanges, current.taskId, current.startEpochMillis, sleepStart)
    val tasks =
        appendRecordOutsideNoScreen(base.tasks, noScreenRanges, current.taskId, current.startEpochMillis, sleepStart)
    val remaining = base.panels.filter { it.pinned || !it.auto }
    return materializePastInactivity(base.copy(tasks = tasks, panels = remaining), covered)
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
 * It is a period of [PeriodKinds.NO_TASK] like any other, so the recurrence bars read it as the rest stretch
 * it is and bar what follows by the ordinary rule — no anchor, no cadence arithmetic, no special case. Its
 * exact span is kept (a 20-second look-away is 20 seconds, not the minute a hand-drawn entry is rounded up
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
            periodKind = PeriodKinds.NO_TASK,
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
    return state.copy(periodKinds = state.periodKinds + kind)
}

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
    return state.copy(
        periodKinds = state.periodKinds.filterNot { it == kind },
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

private fun applyAddPriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    index: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    val at = index.coerceIn(0, list.weightColumns.size)
    // PRD §5: an added column has every field (header and cells) set to 0.
    val cells = state.cells.toMutableMap()
    for (cellId in list.cellIds) {
        val cell = cells[cellId] ?: continue
        val padded = normalizedWeights(cell.priorityWeights, list.weightColumns.size)
        padded.add(at, 0.0)
        cells[cellId] = cell.copy(priorityWeights = padded)
    }
    val columns = list.weightColumns.toMutableList().also { it.add(at, 0.0) }
    val lists = state.lists + (listId to list.copy(weightColumns = columns))
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
    return state.copy(cells = cells, lists = state.lists + (listId to list.copy(weightColumns = columns)))
}

private fun applyMovePriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    from: Int,
    to: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    val size = list.weightColumns.size
    if (from < 0 || from >= size) return state
    // [to] is an insertion index across all columns; account for removing [from] first.
    val target = (if (to > from) to - 1 else to).coerceIn(0, size - 1)
    if (target == from) return state
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
    return state.copy(cells = cells, lists = state.lists + (listId to list.copy(weightColumns = columns)))
}

private fun applyDeletePriorityColumn(
    state: SchedulerState,
    listId: CellListId,
    column: Int,
): SchedulerState {
    val list = state.lists[listId] ?: return state
    // Keep at least one column so priority distribution stays well-defined.
    if (column < 0 || column >= list.weightColumns.size || list.weightColumns.size <= 1) return state
    val cells = state.cells.toMutableMap()
    for (cellId in list.cellIds) {
        val cell = cells[cellId] ?: continue
        val padded = normalizedWeights(cell.priorityWeights, list.weightColumns.size)
        padded.removeAt(column)
        cells[cellId] = cell.copy(priorityWeights = padded)
    }
    val columns = list.weightColumns.toMutableList().also { it.removeAt(column) }
    val lists = state.lists + (listId to list.copy(weightColumns = columns))
    return state.copy(cells = cells, lists = lists)
}

/**
 * PRD §5 the priority-weight window's **Cancel**: put [listId]'s weight table back to the headers and the
 * per-cell weight rows it held when the window opened. Only that one table is touched — a cell listed in
 * [cellWeights] that has since moved to another sub-list is left to its new table, and the list's
 * membership itself is never rewritten (Cancel undoes weight edits, not tree edits).
 *
 * Returns the same instance when the table already matches, so the caller can skip the history unit.
 */
private fun applyRestorePriorityWeights(
    state: SchedulerState,
    listId: CellListId,
    weightColumns: List<Double>,
    cellWeights: Map<CellId, List<Double>>,
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
    if (!changed && !columnsChanged) return state
    val lists =
        if (columnsChanged) state.lists + (listId to list.copy(weightColumns = weightColumns)) else state.lists
    return state.copy(cells = cells, lists = lists)
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
 * The cells [SchedulerIntent.AddDefaultSubtree] fills, and every cell it walked through to reach them.
 */
private class DefaultSubtreeTargets(val leaves: List<CellId>, val visited: Set<CellId>)

/**
 * PRD §7/§13 "add default sub-tree": the **leaves** of the sub-trees [cellIds] root — a cell that parents
 * nothing being its own leaf, which is what makes the plain case (an empty cell, one template under it) and
 * the deep case (a cell already broken down, the template under each piece) the same rule.
 *
 * Two things this must get right:
 *
 * - **The targets are read off the state BEFORE anything is written.** Filling a leaf gives it children, so a
 *   traversal that kept walking a state it was mutating would come back to that task — mirrored elsewhere in
 *   the same sub-tree — find it no longer a leaf, and seed the rows it had just laid down. That is the
 *   cascade the graft avoids by calling the primitives directly, arriving here by another route.
 * - **A task is visited once**, by id. A sub-list belongs to the task id, so every occurrence of a task is
 *   the same sub-list: seeding it once IS seeding all of them, and the id set doubles as the cycle guard.
 */
private fun defaultSubtreeApplicationTargets(
    state: SchedulerState,
    cellIds: List<CellId>,
): DefaultSubtreeTargets {
    val leaves = mutableListOf<CellId>()
    val visited = mutableSetOf<CellId>()
    val seenTasks = mutableSetOf<TaskId>()
    fun visit(cellId: CellId) {
        val taskId = state.cells[cellId]?.taskId ?: return
        if (!seenTasks.add(taskId)) return
        visited += cellId
        val children =
            state.tasks[taskId]?.childListId
                ?.let { state.lists[it]?.cellIds.orEmpty() }
                .orEmpty()
                .filter { state.cells[it]?.taskId != null }
        if (children.isEmpty()) leaves += cellId else children.forEach(::visit)
    }
    cellIds.forEach(::visit)
    return DefaultSubtreeTargets(leaves = leaves, visited = visited)
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
    // Only a freshly minted, still-empty sub-list is seeded — never one the user (or a paste) already built.
    if (childList.cellIds.any { state.cells[it]?.taskId != null }) return state
    return applyDefaultSubtreeTemplate(state, childListId, state.defaultSubtree, WellKnownIds.MAIN_LIST)
}

/**
 * Builds the template's list [templateListId] into [listId], one template row per row, by driving the
 * ordinary editing primitives: each row fills the list's trailing empty placeholder exactly as typing into it
 * would ([applySetCellTitle] then appends the next placeholder), so occurrences, `childTaskIds`, the title
 * index and auto-expansion are all maintained by the code that already owns them rather than by a second copy
 * of those rules here.
 *
 * Those primitives are called **directly**, never through the `SetCellTitle` intent — which is what makes the
 * graft terminate: a row this builds is a task *the graft* created, not one the user created, so it must not
 * be seeded in turn (that would be an unbounded cascade, every seeded row re-applying the whole template for
 * ever). The only descent is into the template's own child lists, so the recursion is bounded by the
 * template's depth; [visitedTemplateLists] is belt and braces against a template that somehow mirrors itself.
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
 * task is one only the template knows, belongs to another task tree, has since been deleted, or would
 * duplicate a task inside the sub-tree — [SchedulerDomain.canAssignTaskId]) falls back to minting a new task
 * with the row's title, so the row still appears instead of silently vanishing.
 */
private fun applyDefaultSubtreeTemplate(
    state: SchedulerState,
    listId: CellListId,
    template: DefaultSubtreeTemplate,
    templateListId: CellListId,
    visitedTemplateLists: MutableSet<CellListId> = mutableSetOf(),
): SchedulerState {
    if (!visitedTemplateLists.add(templateListId)) return state
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
        val target = list.cellIds.lastOrNull { working.cells[it]?.taskId == null } ?: return working

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
        val templateChildListId = templateTask.childListId ?: continue
        val newChildListId = working.tasks[newTaskId]?.childListId ?: continue
        working =
            applyDefaultSubtreeTemplate(
                working,
                newChildListId,
                template,
                templateChildListId,
                visitedTemplateLists,
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

    // PRD §4/§8: emptying a cell whose task still has calendar history — a recorded period (§8) or a panel
    // (§9) — must NOT rename that task to blank, or its records/panels would render as "(untitled)". Keep
    // the task as a tombstone the calendar still labels and unbind *this* cell instead of clearing the
    // shared title. The taskId is dropped only once nothing — no cell, panel, or record — references it
    // ([purgeOrphanTasks]); a cell-less task is never scheduled ([schedulableLeaves] needs [taskHasCells]).
    val keepAsTombstone =
        title.isEmpty() && previousTask != null &&
            (previousTask.record.isNotEmpty() || working.panels.any { it.taskId == taskId })

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
    cells[cellId] = cell.copy(taskId = if (keepAsTombstone) null else taskId)

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
            val subPlaceholderId = CellId("cell/${subListId.value}/0")
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
            // that open a new sub-list are then the ones that mean to: the default-subtree graft (which
            // re-adds the cell in [endEditSession] once it has rows to show), "add default sub-tree", Tab
            // into the child, and the user's own click on the arrow.
            expanded = if (mintedSubList) working.expanded - cellId else working.expanded,
        )

    // PRD §4: reassigning this cell to a different task (e.g. typing a new title in Change Task mode spins up
    // a fresh draft) leaves the cell's *previous* task behind. Drop its now-stale occurrence and, when no
    // cell points at it anymore, its ephemeral (auto, non-pinned) scheduler panels — a task typed only to
    // fill this cell is just an editing leftover, so without a real binding [purgeOrphanTasks] removes it.
    // Pinned/manual panels and recorded periods are real user data and still keep it alive (a genuinely
    // deleted scheduled task whose history is preserved, PRD §9).
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
    val treeBefore: TreeSnapshot,
    val treeAfter: TreeSnapshot,
    val selectionBefore: SchedulerSelection,
    val selectionAfter: SchedulerSelection,
    // PRD §13: Ctrl+X empties the same cells, so the unit the History window shows says "Cut" instead.
    override val label: String = "Clear cells",
) : Delta {

    override val details: List<String>
        get() = treeDiffLines(treeBefore, treeAfter) + selectionDiffLines(selectionBefore, selectionAfter)

    override fun undo(state: SchedulerState): SchedulerState =
        state.applyTree(treeBefore).copy(selection = selectionBefore)

    override fun redo(state: SchedulerState): SchedulerState =
        state.applyTree(treeAfter).copy(selection = selectionAfter)
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

/** PRD §7: a window-navigation unit — the focus moving from one window to another. */
internal data class FocusDelta(
    val before: AppWindow,
    val after: AppWindow,
) : Delta {
    override val label: String = "Focus ${after.name}"

    override val details: List<String>
        get() = listOf("focus: ${before.name} → ${after.name}")

    override fun undo(state: SchedulerState): SchedulerState = state.copy(focusedWindow = before)

    override fun redo(state: SchedulerState): SchedulerState = state.copy(focusedWindow = after)
}

/**
 * PRD §5/§8: the whole panel list before/after a *manual* calendar add, edit, move, or resize. Lives
 * in the [HistoryCategory.Calendar] stack so it is undone/redone only while the calendar is focused
 * (PRD §8). An automatic scheduling run (PRD §9) does NOT use this delta — a derived schedule carries
 * no history unit.
 */
internal data class PanelDelta(
    val before: List<TaskPanel>,
    val after: List<TaskPanel>,
    override val label: String = "Calendar edit",
) : Delta {
    override val details: List<String>
        get() = panelDiffLines(before, after)

    override fun undo(state: SchedulerState): SchedulerState = state.copy(panels = before)

    override fun redo(state: SchedulerState): SchedulerState = state.copy(panels = after)
}

/**
 * PRD §4 Find & replace: the whole expansion set before/after revealing a search hit. One unit for the
 * whole path, unlike [ToggleExpandDelta] which is one cell — see [SchedulerReducer.reduceRevealCell].
 */
internal data class SetExpandedDelta(
    val before: Set<CellId>,
    val after: Set<CellId>,
) : Delta {
    override val label: String = "Expand"

    override val details: List<String>
        get() = (after - before).map { "expand ${it.value}" } + (before - after).map { "collapse ${it.value}" }

    override fun undo(state: SchedulerState): SchedulerState = state.copy(expanded = before)

    override fun redo(state: SchedulerState): SchedulerState = state.copy(expanded = after)
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
    val before: TreeSnapshot,
    val after: TreeSnapshot,
    override val label: String = "Tree change",
) : Delta {
    override val details: List<String>
        get() = treeDiffLines(before, after)

    override fun undo(state: SchedulerState): SchedulerState = state.applyTree(before)

    override fun redo(state: SchedulerState): SchedulerState = state.applyTree(after)
}

/**
 * A change to the **task trees** (see [TaskTreeEntry]): selecting another tree, creating one, or renaming
 * one. Captures the whole task-tree state on both sides — the stored trees, the active id, the counter, and
 * the live tree + expansion the selection projects — because a switch moves all of them at once and undo
 * must put every part back together.
 */
internal data class TaskTreeDelta(
    val before: TaskTreeStateSnapshot,
    val after: TaskTreeStateSnapshot,
    override val label: String,
) : Delta {
    override val details: List<String>
        get() = buildList {
            val b = before.trees.associate { it.id to it.title }
            val a = after.trees.associate { it.id to it.title }
            (a.keys - b.keys).forEach { add("+ tree \"${a.getValue(it)}\"") }
            (b.keys - a.keys).forEach { add("− tree \"${b.getValue(it)}\"") }
            (b.keys intersect a.keys).forEach { id ->
                if (b.getValue(id) != a.getValue(id)) add("\"${b.getValue(id)}\" → \"${a.getValue(id)}\"")
            }
            if (before.activeId != after.activeId) {
                val from = before.activeId?.let { b[it] } ?: "—"
                val to = after.activeId?.let { a[it] } ?: "—"
                add("selected: \"$from\" → \"$to\"")
            }
        }

    override fun undo(state: SchedulerState): SchedulerState = state.applyTaskTreeState(before)

    override fun redo(state: SchedulerState): SchedulerState = state.applyTaskTreeState(after)
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
 * window undoes what the user just did rather than a fragment of it. Only the template moves: the live tree
 * is not part of either side.
 */
internal data class DefaultSubtreeDelta(
    val before: DefaultSubtreeTemplate,
    val after: DefaultSubtreeTemplate,
    override val label: String,
) : Delta {
    override val details: List<String>
        get() = buildList {
            val b = before.tree.tasks.mapValues { it.value.title }
            val a = after.tree.tasks.mapValues { it.value.title }
            (a.keys - b.keys).forEach { a.getValue(it).ifBlank { null }?.let { t -> add("+ \"$t\"") } }
            (b.keys - a.keys).forEach { b.getValue(it).ifBlank { null }?.let { t -> add("− \"$t\"") } }
            (b.keys intersect a.keys).forEach { id ->
                if (b.getValue(id) != a.getValue(id)) add("\"${b.getValue(id)}\" → \"${a.getValue(id)}\"")
            }
            (after.boundCells - before.boundCells).forEach { add("switch off: ${it.value}") }
            (before.boundCells - after.boundCells).forEach { add("switch on: ${it.value}") }
        }

    override fun undo(state: SchedulerState): SchedulerState = state.copy(defaultSubtree = before)

    override fun redo(state: SchedulerState): SchedulerState = state.copy(defaultSubtree = after)
}

/**
 * PRD §8/§9: a change to one or more tasks' completed-work [record]s (the periods appended by
 * [advanceSchedule] / device-sleep cuts as auto panels elapse). These were historically applied
 * outside Undo/Redo; routing them through a delta makes them undoable AND lets the debug-time
 * rollback revert future-dated records produced under accelerated time. Captures only the affected
 * tasks' records (before/after) so undo/redo touch nothing else.
 */
internal data class RecordDelta(
    val before: Map<TaskId, List<TaskTimeRange>>,
    val after: Map<TaskId, List<TaskTimeRange>>,
) : Delta {
    override val label: String = "Record work"

    override val details: List<String>
        get() = (before.keys + after.keys).distinct().mapNotNull { id ->
            val b = before[id].orEmpty().size
            val a = after[id].orEmpty().size
            if (a != b) "${id.value}: $b → $a periods" else null
        }

    override fun undo(state: SchedulerState): SchedulerState = applyRecords(state, before)

    override fun redo(state: SchedulerState): SchedulerState = applyRecords(state, after)

    private fun applyRecords(
        state: SchedulerState,
        records: Map<TaskId, List<TaskTimeRange>>,
    ): SchedulerState {
        var tasks = state.tasks
        for ((id, rec) in records) {
            val task = tasks[id] ?: continue
            tasks = tasks + (id to task.copy(record = rec))
        }
        return state.copy(tasks = tasks)
    }
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
private fun treeDiffLines(before: TreeSnapshot, after: TreeSnapshot): List<String> {
    val lines = mutableListOf<String>()
    (after.cells.keys - before.cells.keys)
        .filter { after.cellHasContent(it) }
        .forEach { lines += "+ cell \"${after.cellTitle(it)}\"" }
    (before.cells.keys - after.cells.keys)
        .filter { before.cellHasContent(it) }
        .forEach { lines += "− cell \"${before.cellTitle(it)}\"" }
    (before.cells.keys intersect after.cells.keys).forEach { id ->
        val bt = before.cellTitle(id)
        val at = after.cellTitle(id)
        if (bt != at) lines += "\"$bt\" → \"$at\""
        val bw = before.cells.getValue(id).priorityWeights
        val aw = after.cells.getValue(id).priorityWeights
        if (bw != aw) lines += "weights \"$at\": $bw → $aw"
    }
    (before.tasks.keys intersect after.tasks.keys).forEach { id ->
        val b = before.tasks.getValue(id)
        val a = after.tasks.getValue(id)
        if (b.minimumMinutes != a.minimumMinutes) {
            lines += "min \"${a.title}\": ${b.minimumMinutes} → ${a.minimumMinutes} min"
        }
        if (b.scheduleUnit != a.scheduleUnit) {
            lines += "schedule unit \"${a.title}\": ${b.scheduleUnit.size} → ${a.scheduleUnit.size} step(s)"
        }
        if (b.text != a.text) {
            lines += "text \"${a.title}\": ${b.text.length} → ${a.text.length} char(s)"
        }
    }
    (before.lists.keys intersect after.lists.keys).forEach { id ->
        val bc = before.lists.getValue(id).weightColumns
        val ac = after.lists.getValue(id).weightColumns
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
private fun panelDiffLines(before: List<TaskPanel>, after: List<TaskPanel>): List<String> {
    val lines = mutableListOf<String>()
    val beforeById = before.associateBy { it.id }
    val afterById = after.associateBy { it.id }
    (afterById.keys - beforeById.keys).forEach { lines += "+ ${panelSummary(afterById.getValue(it))}" }
    (beforeById.keys - afterById.keys).forEach { lines += "− ${panelSummary(beforeById.getValue(it))}" }
    (beforeById.keys intersect afterById.keys).forEach { id ->
        val a = afterById.getValue(id)
        if (beforeById.getValue(id) != a) lines += "~ ${panelSummary(a)}"
    }
    return lines
}

private fun panelSummary(panel: TaskPanel): String =
    "${panel.title.ifBlank { "(untitled)" }}  ${formatPanelRange(panel.startEpochMillis, panel.endEpochMillis)}"

private fun formatPanelRange(startMillis: Long, endMillis: Long): String {
    val tz = TimeZone.currentSystemDefault()
    fun hm(millis: Long): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz)
        return t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
    }
    return if (startMillis == endMillis) hm(startMillis) else "${hm(startMillis)}–${hm(endMillis)}"
}
