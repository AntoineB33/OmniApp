package org.example.project.scheduler.state

import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTreeId
import org.example.project.scheduler.model.TaskTimeRange

/** PRD §8 extend/shorten: which edge of a calendar block the user grabbed. */
enum class CalendarEdge { Start, End }

enum class SelectionNavigate {
    /** Up / Left — previous visible selectable cell. */
    Previous,
    /** Down / Right — next visible selectable cell. */
    Next,
}

enum class EditExitNavigation {
    /** Enter — commit and move selection down one visible cell. */
    Down,
    /** Shift+Enter / Shift+Tab — commit and move selection up one visible cell. */
    Up,
    /** Tab — expand sublist and move selection to the first child. */
    TabToChild,
    /** Commit and exit edit mode, leaving the selection on the edited cell. */
    Stay,
}

sealed interface SchedulerIntent {
    data class ClickCell(
        val cellId: CellId,
        val ctrl: Boolean,
        val shift: Boolean,
        val visibleOrder: List<CellId>,
        /** Expanded parent occurrence for this row (mirrored subtree path). */
        val renderVia: CellId? = null,
        /** Double-click on a non-movable cell clears multi-selection (PRD §3). */
        val forceClearMulti: Boolean = false,
    ) : SchedulerIntent

    data class DragSelectCells(
        val anchorCellId: CellId,
        val hoverCellId: CellId,
        val visibleOrder: List<CellId>,
        val renderVia: CellId? = null,
    ) : SchedulerIntent

    data class MoveSelectedCells(
        val targetCellId: CellId,
        val insertBefore: Boolean,
    ) : SchedulerIntent

    data object ClearSelection : SchedulerIntent

    data object EmptySelectedCells : SchedulerIntent

    data class ExitEdit(val navigation: EditExitNavigation) : SchedulerIntent

    data class ToggleExpand(val cellId: CellId) : SchedulerIntent

    /**
     * Collapse the sub-trees UNDER [cellId] — every cell below it, but not [cellId] itself, which stays
     * open on its own children. Recorded as one expansion-set delta.
     */
    data class CollapseSubtrees(val cellId: CellId) : SchedulerIntent

    data class SetCellTitle(
        val cellId: CellId,
        val title: String,
    ) : SchedulerIntent

    data class AssignTaskId(
        val cellId: CellId,
        val taskId: TaskId,
    ) : SchedulerIntent

    /**
     * Task-tree selector (the field above the tree): make [id] the live task tree. The tree being left is
     * flushed into its own entry first, so nothing done in it is lost (see [TaskTreeEntry]). Recorded as an
     * undoable Main History Unit; a no-op when [id] is already active or names no tree.
     */
    data class SelectTaskTree(val id: TaskTreeId) : SchedulerIntent

    /**
     * Task-tree selector: create a task tree named [title] holding a **copy of the current tree** and make it
     * the live one — so the user can diverge it from what is on screen. The tree being left is flushed first.
     * Recorded as an undoable Main History Unit; a no-op for a blank title.
     */
    data class CreateTaskTree(val title: String) : SchedulerIntent

    /**
     * Task-tree selector, Rename mode: rename the task tree [id] (its content and identity are untouched —
     * the same tree under a new name). Recorded as an undoable Main History Unit.
     */
    data class RenameTaskTree(val id: TaskTreeId, val title: String) : SchedulerIntent

    /**
     * "All task trees" window: put the task tree [id] on the timeline at [dateMillis] (or take it off with
     * `null`). A dated tree is a keyframe of the account's priorities — see [TaskTreeEntry.dateMillis].
     * Recorded as an undoable Main History Unit; a no-op when the date is already that.
     */
    data class SetTaskTreeDate(val id: TaskTreeId, val dateMillis: Long?) : SchedulerIntent

    /**
     * "All task trees" window (the bin button): delete the task tree [id] for good. Deleting the tree that
     * is currently live keeps what is on screen — only the *name* goes away, leaving the live tree unnamed
     * — so no work is ever lost by pressing the bin. Recorded as an undoable Main History Unit.
     */
    data class DeleteTaskTree(val id: TaskTreeId) : SchedulerIntent

    /** PRD §5: set a cell's value in a weight column (clamped to ≥ 0); recorded as a content delta. */
    data class SetPriorityWeight(
        val cellId: CellId,
        val column: Int,
        val value: Double,
    ) : SchedulerIntent

    /**
     * PRD §5: **what task an optional row of this sub-list's priority-weight table names** — the row's whole
     * identity, in one intent, exactly as a cell of the tree has one ([SetCellTitle] / [AssignTaskId]).
     *
     * The three gestures the table's rows offer are the three shapes of this one question, so each is one
     * history unit and none of them can be half-done:
     *
     * - [replacing] `null`, [taskId] set — the **add row** names a task: a new optional row, seeded at zero
     *   in every column.
     * - [replacing] set, [taskId] set — an existing row is **re-pointed** at another task, its own value
     *   going with the row it replaces (a row is a reading of a sub-tree, not a number the user typed).
     * - [replacing] set, [taskId] `null` — the row is **removed**. PRD §4's rule read here: emptying a row's
     *   title is what deletes it, the same gesture as emptying a cell of the tree.
     *
     * [taskId] must have a live occurrence chain under the list's own parent task
     * ([RelativePriorityDomain.optionalTaskPath]) — the row states its share of *that* sub-tree, so a task
     * sitting nowhere under it states nothing. [SchedulerDomain.eligibleWeightTableTaskIds] is the same
     * predicate asked of the menu, so a refused pick is never offered.
     */
    data class SetPriorityWeightTableRow(
        val listId: CellListId,
        /** The task the row names today, or null when this is the table's trailing **add** row. */
        val replacing: TaskId? = null,
        /** The task the row is to name, or null to remove the row. */
        val taskId: TaskId? = null,
    ) : SchedulerIntent

    /** PRD §5: scale one column of an optional task row's path weights by [factor]. */
    data class SetOptionalTaskPathWeight(
        val listId: CellListId,
        val taskId: TaskId,
        val column: Int,
        val factor: Double,
        val pinnedCells: Set<CellId> = emptySet(),
    ) : SchedulerIntent

    /**
     * PRD §5 the relative-priority window: set [taskId]'s priority **relative to** [relativeTo] to [value]
     * (a fraction in `[0,1]`). Every cell of every occurrence chain has its percentage scaled by one common
     * factor to get there, except the cells pinned for this (task, ancestor) pair. Recorded as one content
     * delta, like any other weight edit — however many cells it had to move.
     */
    data class SetRelativePriority(
        val taskId: TaskId,
        val relativeTo: TaskId,
        val value: Double,
    ) : SchedulerIntent

    /**
     * PRD §5 the relative-priority window: pin/unpin [cellId] for the (task, ancestor) pair, so its
     * percentage holds while the relative priority is retargeted. Not undoable (it changes no priority).
     */
    data class ToggleRelativePriorityPin(
        val taskId: TaskId,
        val relativeTo: TaskId,
        val cellId: CellId,
    ) : SchedulerIntent

    /** PRD §5 the relative-priority window: drop every pin of this (task, ancestor) pair. */
    data class ClearRelativePriorityPins(
        val taskId: TaskId,
        val relativeTo: TaskId,
    ) : SchedulerIntent

    /**
     * PRD §5 the **task relations** window: record that the relative-priority window has been open on this
     * (task, target) pair, and whether it left the percentage [changed].
     *
     * Raised by that window itself — once when it settles on a pair, and again on every keystroke of the
     * percentage field, because the field commits every keystroke and the user's rule is about where the
     * number *ends up*: typing a value and putting it back leaves the pair exactly as untouched as never
     * having typed at all. So [changed] is the verdict of this window session against the value the pair
     * read when the window settled on it, not "an edit happened".
     *
     * Not undoable — it records no priority change, only that the user looked (the same reason a pin is not).
     */
    data class RecordTaskRelation(
        val taskId: TaskId,
        val relativeTo: TaskId,
        val changed: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §5 the task-relations window: file this pair in **section 1** — the list the user curates by hand.
     * Offered on every row that is not there already; it also lifts a [DropTaskRelation]. Not undoable.
     */
    data class KeepTaskRelation(
        val taskId: TaskId,
        val relativeTo: TaskId,
    ) : SchedulerIntent

    /**
     * PRD §5 the task-relations window: section 1's own button — strike this pair off the list entirely,
     * whichever section would otherwise hold it. Touching the pair again from the relative-priority window
     * brings it back.
     *
     * **It also removes the priority-weight-table row the pair was made of**, where that is how it got onto
     * the list: an **optional row** of the target sub-list's table *is* the relation, so striking the pair
     * off has to take the row with it — otherwise the user's own table goes on asserting a pair they have
     * just said is not theirs, and there would be no gesture anywhere that takes a row back out of a table.
     *
     * So this is the one relations intent that is **partly undoable**: the mark is not a history unit (it
     * changes no priority, like a pin), and the row's removal is one — a tree change, the exact inverse of
     * [SetPriorityWeightTableRow], which is a unit for the same reason. A pair with no such row commits
     * nothing at all.
     */
    data class DropTaskRelation(
        val taskId: TaskId,
        val relativeTo: TaskId,
    ) : SchedulerIntent

    /** PRD §5: set the nominal header weight of a sub-list's priority column (clamped to ≥ 0). */
    data class SetPriorityColumnWeight(
        val listId: CellListId,
        val column: Int,
        val weight: Double,
    ) : SchedulerIntent

    /** PRD §5: insert a new priority weight column at [index] (default appends to the end). */
    data class AddPriorityColumn(
        val listId: CellListId,
        val index: Int = Int.MAX_VALUE,
    ) : SchedulerIntent

    /** PRD §5: reset a column (header + every cell) to its default value ("Reset to default"). */
    data class ResetPriorityColumn(
        val listId: CellListId,
        val column: Int,
    ) : SchedulerIntent

    /** PRD §5: delete a priority weight column from a sub-list ("Delete column"). */
    data class DeletePriorityColumn(
        val listId: CellListId,
        val column: Int,
    ) : SchedulerIntent

    /**
     * PRD §5 the priority-weight window's **Cancel**: put the sub-list's weight table back to what it was
     * when the window opened — [weightColumns] for the headers, [cellWeights] for each listed cell's row.
     * A cell that has since left the sub-list keeps whatever its own table gave it.
     *
     * Recorded as one ordinary content delta, like any other weight edit, which is exactly what makes
     * Ctrl+Z undo the cancel itself.
     */
    data class RestorePriorityWeights(
        val listId: CellListId,
        val weightColumns: List<Double>,
        val cellWeights: Map<CellId, List<Double>>,
    ) : SchedulerIntent

    /** PRD §5: reorder a priority column by dragging it to a new position. */
    data class MovePriorityColumn(
        val listId: CellListId,
        val from: Int,
        val to: Int,
    ) : SchedulerIntent

    /** PRD §10: set a task's minimum time in minutes (clamped to ≥ 0); recorded as a content delta. */
    data class SetTaskMinimumTime(
        val taskId: TaskId,
        val minutes: Int,
    ) : SchedulerIntent

    /**
     * `side-dev/README.md` § *Restrictive Period*: set [taskId]'s **resilience** to one [kind] of restrictive
     * period — the multiplier in `[0, 1]` on its priority percentage inside such a period. `0` forbids it
     * there, `1` leaves it untouched. Replaces the old pair of screen switches: "on screen" is exactly a `0`
     * against [org.example.project.scheduler.domain.PeriodKinds.NO_SCREEN].
     *
     * Recorded as a content delta (part of the tree Undo/Redo history).
     */
    data class SetTaskResilience(
        val taskId: TaskId,
        val kind: String,
        val value: Double,
    ) : SchedulerIntent

    /**
     * PRD §15 "Look away now" (the lateral-menu button and `Ctrl+Shift+Alt+E`): **a dynamic period the app
     * actually conducted**, recorded where it happened.
     *
     * `side-dev/README.md` calls this a pre-placed restrictive period, and that is exactly what it is: a span
     * of [org.example.project.scheduler.domain.PeriodKinds.NO_TASK] the user really took, standing on the
     * timeline for the recurrence bars to read as the rest stretch it is. So it needs no anchor and no rule
     * of its own — the next 20 s period is barred for twenty minutes after it by the ordinary bar.
     *
     * Recorded only when the break RAN TO ITS END. One that was interrupted (the user pressed again, or the
     * app stopped) never reaches this intent and leaves no trace, which is the asymmetry §15 asks for.
     *
     * A recorded fact about the past, like a task record: authoritative, persisted and synced, and — like
     * every other write to the record — outside the Undo/Redo history.
     */
    data class RecordConductedBreak(
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * `side-dev/README.md`: **define a new kind of restrictive period.** Raised by the task edit window's
     * `+`, which is where the user meets the kinds in the first place.
     *
     * The new kind is **added to every task at the default value `0`** — nothing is written to a single task
     * to say so, because that is what an absent override means
     * ([org.example.project.scheduler.domain.PeriodKinds.defaultResilience]). So a new period turns everybody
     * away until its own edit window hands somebody a value above zero, and a task created *later* carries
     * the same answer as the ones that were already there. A blank or built-in name is a no-op.
     */
    data class AddPeriodKind(val kind: String) : SchedulerIntent

    /**
     * Remove a user-defined kind, with every task's override for it and every panel laid with it. The two
     * built-in kinds cannot be removed. Raised by the **period edit window's** delete button — the one place
     * a period is deleted, since that window is the one place a period is an object in its own right.
     */
    data class RemovePeriodKind(val kind: String) : SchedulerIntent

    /**
     * The **period edit window's** write: give every task in [taskIds] the same resilience [value] to [kind].
     *
     * It is [SetTaskResilience] over a set rather than a task, and it exists for the grain of the history
     * rather than for the arithmetic: checking twenty tasks and typing one percentage is **one** gesture, so
     * it must be **one** Undo/Redo unit. The window's per-row field raises it too, with a single-element
     * list, so there is one write path and not two. Tasks already at [value] contribute nothing, and a call
     * that moves nobody records no unit at all.
     */
    data class SetPeriodResilience(
        val taskIds: List<TaskId>,
        val kind: String,
        val value: Double,
    ) : SchedulerIntent

    // ----- PRD §5 categories ---------------------------------------------------------------------

    /**
     * Give [taskId] a category, named the way a task cell is named: the text the user typed.
     *
     * The field is a naming field, so this is the **create-or-attach** intent and not two: a title an
     * existing category already carries attaches THAT category (the identity rows offer it explicitly, and
     * typing the name reaches the same answer), and any other non-blank title mints a new one. A blank title
     * does nothing, exactly as it does in a cell being created.
     *
     * A content unit, like every other edit to the task tree.
     */
    data class AddTaskCategory(val taskId: TaskId, val title: String) : SchedulerIntent

    /**
     * PRD §5/§7 the **categories window**'s naming field: define a category the account has not got, carried
     * by no task at all.
     *
     * The same create-or-attach rule as [AddTaskCategory] with the attach half missing — there is no task
     * here — so a title an existing category already carries is a **no-op**: that category is already a row
     * of the list above the field, and minting a second one under the same spelling is the very thing the id
     * exists to prevent. A blank title does nothing, as everywhere else a thing is named.
     *
     * An account setting, like renaming and deleting one: it records **no** history unit (as `AddPeriodKind`
     * does not), because no task's priority moves.
     */
    data class CreateCategory(val title: String) : SchedulerIntent

    /**
     * Give [taskId] the category [categoryId] — what the field’s **identity rows** raise. Attaching a
     * category the task already carries is a no-op, as is naming one the account no longer holds.
     */
    data class AttachTaskCategory(val taskId: TaskId, val categoryId: CategoryId) : SchedulerIntent

    /**
     * The bin on a row of the categories drop-down: take [categoryId] off [taskId]. The category itself
     * survives — it is the account’s, and other tasks may carry it. Deleting the category is
     * [DeleteCategory], and it lives in the category edit window, which is the one place a category is an
     * object in its own right.
     */
    data class RemoveTaskCategory(val taskId: TaskId, val categoryId: CategoryId) : SchedulerIntent

    /** Rename a category. It is named by id everywhere, so this reaches every task and every rule at once. */
    data class RenameCategory(val categoryId: CategoryId, val title: String) : SchedulerIntent

    /**
     * The category edit window’s **Delete**: drop the category, its rules, and the id from every task
     * carrying it. The one place a category is deleted, for the same reason the period edit window is the one
     * place a period is.
     */
    data class DeleteCategory(val categoryId: CategoryId) : SchedulerIntent

    /**
     * The category edit window’s rule editor: *the tasks carrying [categoryId] under the cell
     * [scopeCellId] are worth [share] of it* (`null` = the whole tree). At most one rule per scope, so this
     * REPLACES the rule already there rather than adding beside it — two rules about one sub-tree would be
     * the plainest contradiction there is, and "one sub-tree" is the LIST the scope cell's task owns, so
     * two cells of one mirrored task are one scope
     * ([org.example.project.scheduler.domain.CategoryRules.scopeKey]).
     *
     * A cell and not a task, because a task can appear several times in the tree: "under Book" names no
     * place when there are two of them, so the window asks which task CELL.
     *
     * The reducer applies it and then re-establishes every rule
     * ([org.example.project.scheduler.domain.CategoryRules.settle]); a rule that cannot be held is refused
     * with a message and nothing is written.
     */
    data class SetCategoryRule(
        val categoryId: CategoryId,
        val scopeCellId: CellId?,
        val share: Double,
    ) : SchedulerIntent

    /** The bin on a rule row: [categoryId] stops making any claim about [scopeCellId]’s sub-tree. */
    data class RemoveCategoryRule(val categoryId: CategoryId, val scopeCellId: CellId?) : SchedulerIntent

    /**
     * Clear [SchedulerState.categoryRuleError] — the OK of the notice the app raised when it refused an edit
     * that would have broken a rule. Local-only view state, so this is no history unit and no push.
     */
    data object DismissCategoryRuleError : SchedulerIntent

    /**
     * PRD §13 Schedule Unit "Save": replace a task's schedule unit with [entries] (empty clears it).
     * Recorded as a content delta so it is part of the Undo/Redo history (PRD §6). The caller (the edit
     * window) only enables Save when [SchedulerDomain.canSaveScheduleUnit] holds, but the reducer also
     * defends against an over-budget sum so it can never persist an invalid unit.
     */
    data class SetScheduleUnit(
        val taskId: TaskId,
        val entries: List<org.example.project.scheduler.model.ScheduleUnitEntry>,
    ) : SchedulerIntent

    /**
     * PRD §13 Edit window Save (text section): replace a task's free-form [text] document. Recorded as a content delta so
     * it is part of the Undo/Redo history (PRD §6).
     */
    data class SetTaskText(
        val taskId: TaskId,
        val text: String,
    ) : SchedulerIntent

    /**
     * PRD §14 Reminders: replace the whole reminders list with [entries] (rows are edited live in the
     * floating window) and regenerate the reminder calendar tags anchored at [todayStartMillis] (local
     * midnight of today, supplied by the caller which knows the time zone). Persisted but not part of the
     * tree Undo/Redo history (see [SchedulerState.chores]); a reminder's checked state survives the
     * regeneration.
     */
    data class SetChores(
        val entries: List<org.example.project.scheduler.model.ChoreEntry>,
        val todayStartMillis: Long = 0L,
        // PRD §14: a reminder with no time-of-day is placed at the current time; this carries it (the time
        // zone is the caller's). Defaults to [todayStartMillis] (midnight) so callers that omit it are
        // unaffected and a not-defined time falls back to midnight.
        val nowMillis: Long = todayStartMillis,
    ) : SchedulerIntent

    /**
     * PRD §14 Reminders "checking off": mark the reminder tag [panelId] as [checked] (done) or un-check it.
     * Recorded as a Calendar History Unit (undoable while the calendar is focused), like any other panel
     * change. A no-op when [panelId] is not a reminder tag or already in the requested state.
     */
    data class SetReminderChecked(
        val panelId: String,
        val checked: Boolean,
        // PRD §14: the clock time of the check, so a checked reminder freezes at this point on the
        // calendar timeline (caller's time zone). Defaults to 0 for callers that omit it.
        val nowMillis: Long = 0L,
    ) : SchedulerIntent

    /**
     * PRD §14 "add reminder": place a manually-added reminder tag for [reminderId] (titled [title]) at
     * [atMillis], with the user-chosen [checked] (already done) and [pinned] (stays put) switches. Recorded
     * as a Calendar History Unit. The tag survives reminder regeneration while it is checked **or** pinned
     * (it is not produced by the recurrence scheduler); checking anchors the reminder's recurrence.
     */
    data class AddReminder(
        val reminderId: String,
        val title: String,
        val atMillis: Long,
        val checked: Boolean,
        val pinned: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §18 Alarms: replace the whole alarm list with [entries] (rows are edited live in the Alarms
     * window). Blank ids are filled in with a fresh `alarm-{n}`. Authoritative — persisted and synced, so
     * every phone of the account arms the new times; not part of the tree Undo/Redo history.
     */
    data class SetAlarms(
        val entries: List<org.example.project.scheduler.model.AlarmEntry>,
    ) : SchedulerIntent

    /**
     * PRD §18 Alarms: arm/disarm one alarm by id. Dispatched by the user's row switch and by the engine when
     * a **one-off** alarm has rung (it disarms itself, leaving the row for re-arming). A no-op when the alarm
     * is unknown or already in that state.
     */
    data class SetAlarmEnabled(
        val id: String,
        val enabled: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §18 Timers: replace the whole timer list with [entries] (rows are edited live in the Alarms
     * window's Timers section). Blank ids are filled in with a fresh `timer-{n}`. Authoritative — persisted
     * and synced, like the alarms; not part of the tree Undo/Redo history.
     *
     * This carries the rows' **settings** (label, duration, ring length, vibration). Whether a timer is
     * running is moved only by [StartTimer] / [PauseTimer] / [ResetTimer] / [SetTimerCountdownField] /
     * [NudgeTimerRemaining], so editing a row's settings while it counts down cannot disturb its end
     * instant.
     */
    data class SetTimers(
        val entries: List<org.example.project.scheduler.model.TimerEntry>,
    ) : SchedulerIntent

    /**
     * PRD §18 Timers: start one timer, or resume it from where a [PauseTimer] left it — it becomes due at
     * [nowMillis] plus whatever is left. [nowMillis] is passed in rather than read, so the reducer stays a
     * pure function of its inputs. A no-op when the timer is unknown or already running.
     */
    data class StartTimer(
        val id: String,
        val nowMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §18 Timers: hold one timer where it is, banking the time left at [nowMillis] so [StartTimer]
     * resumes from there. A no-op when the timer is unknown or not running.
     */
    data class PauseTimer(
        val id: String,
        val nowMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §18 Timers: return one timer to idle at its full duration. Dispatched by the row's reset button and
     * by the engine when the timer has **rung** — a timer is a one-off by nature, so going off puts the row
     * back where it was started from. A no-op when the timer is unknown or already idle.
     */
    data class ResetTimer(
        val id: String,
    ) : SchedulerIntent

    /**
     * PRD §18 Timers: set one component of a timer's countdown — what each of the Alarms window's three
     * countdown fields dispatches, so the time left can be changed at any moment without stopping and
     * restarting the countdown.
     *
     * The edit is a **shift by that component's own delta**, which is what leaves the finer components
     * running: typing into the hours leaves the minutes and seconds reading down, typing into the minutes
     * leaves the seconds reading down. [TimerDomain.TimerField.SECONDS] is the exception and **pauses** the
     * row — the seconds are the digit that is itself moving, so a typed value could not otherwise stick.
     * [TimerDomain.withCountdownField] is the whole rule; an idle row is unchanged, its countdown being the
     * duration that [SetTimers] edits.
     *
     * Together with [NudgeTimerRemaining] this is the exact counterpart of [SetTimers]' rule the other way
     * round: that one carries the settings and never disturbs the due instant, these move the due instant and
     * never touch the settings.
     *
     * [nowMillis] is passed in rather than read, like [StartTimer]'s, so the reducer stays pure. A no-op when
     * the timer is unknown or nothing moved.
     */
    data class SetTimerCountdownField(
        val id: String,
        val field: org.example.project.scheduler.domain.TimerDomain.TimerField,
        val value: Int,
        val nowMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §18 Timers: shift one timer's time left by [deltaMillis], leaving it in the state it is in — the
     * Alarms window's `−10s / −5s / −1s / +1s / +5s / +10s` buttons.
     *
     * They exist because [SetTimerCountdownField] deliberately **stops** a running timer when the seconds are
     * typed into: this is how the seconds are moved without stopping it. A running row stays running and just
     * becomes due sooner or later; a paused one stays paused with the new amount banked; an idle one is
     * unchanged. See [TimerDomain.nudged]. A no-op when the timer is unknown or nothing moved.
     */
    data class NudgeTimerRemaining(
        val id: String,
        val deltaMillis: Long,
        val nowMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §15 Screen breaks: replace the screen-break list — used at launch to seed each screen break's
     * the screen-break list (durations/intervals, debug overrides included). Session state,
     * not undoable.
     */
    data class SetScreenBreaks(
        val screenBreaks: List<org.example.project.scheduler.model.ScreenBreak>,
    ) : SchedulerIntent

    /**
     * PRD §9 calculation event: regenerate the schedule against [nowMillis] — advance past any
     * completed panel, then refill the non-pinned panels out to the horizon in force — $t_{goal}$, not a
     * fixed +168h (`SchedulerReducer.scheduleHorizonEndMillis`, [SchedulerDomain.scheduleGoalEndMillis],
     * [SchedulerDomain.fillSchedule]).
     * Dispatched by the debounced tree-change event and the deferred calendar timer. Gated by PRD §7:
     * a no-op while [SchedulerState.automaticSchedule] is off (the event waits for it to turn on). The
     * refill is committed as a Calendar History Unit (PRD §9); the record side effects are not undoable.
     */
    data class RefreshSchedule(
        val nowMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §9 rolling horizon: materialize MORE of the existing plan, without re-planning it.
     *
     * Dispatched when the horizon grows — the user navigates the calendar to a further-out week, or `now`
     * advances far enough that the materialized tail falls short ([SchedulerDomain.horizonRefillDueMillis]).
     * Neither is a change to the scheduling *rules*, so the plan the user is already looking at must not be
     * rewritten: the auto panels ahead of the now-line are kept and only the tail past them is generated
     * ([SchedulerDomain.fillSchedule]'s `keepExistingUntilMillis`). Re-planning from `now` happens only on a
     * [RefreshSchedule], which the engine fires when [SchedulerDomain.schedulingSignature] moves — or, at
     * most once an hour, when the plan has simply gone that long without being re-planned.
     *
     * Derived state only (like [AdvanceSchedule]): never a syncable change, never a History Unit.
     */
    data class ExtendSchedule(
        val nowMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §9: the frequent tick — advance the schedule to [nowMillis] without refilling. Records the
     * elapsed period of any completed auto panel (and cuts the current one if its task was deleted or
     * gained a child), so the calendar stays truthful even while §7 auto-scheduling is off. Touches
     * only the history-excluded record / panel-progress state, so it is not undoable.
     */
    data class AdvanceSchedule(
        val nowMillis: Long,
    ) : SchedulerIntent

    /** PRD §7 Automatic Schedule Switch: enable/disable auto-scheduling. Persisted; not undoable. */
    data class SetAutomaticSchedule(
        val enabled: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §7 **"Switch task"** — the lateral-menu button and its system-wide `Ctrl+Shift+Alt+Z` chord: the
     * task the now-line sits on at [nowMillis] is refused there, so the plan starts a *different* one from
     * [nowMillis] on.
     *
     * Records the refusal ([org.example.project.scheduler.model.ForcedTaskSwitch]) and re-plans on the spot,
     * rather than leaving it to [org.example.project.scheduler.domain.SchedulerDomain.schedulingSignature]:
     * the press IS the calculation event (the same reason `RemoveRecordPeriod` refills inside its own
     * reducer), and a marker in the signature would fire a second, *un*-refused re-plan the moment the marker
     * was spent. A no-op when the now-line is on no task at all. Persisted + synced; not undoable.
     */
    data class ForceTaskSwitch(
        val nowMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §13 **"start this task now"** — the task cell's right-click menu: [taskId] must be the task the plan
     * places at the now-line.
     *
     * The mirror image of [ForceTaskSwitch], and recorded the same way: a standing
     * [org.example.project.scheduler.model.ForcedTaskStart] plus an immediate re-plan from inside its own
     * reducer, because the press IS the calculation event and a marker in
     * [org.example.project.scheduler.domain.SchedulerDomain.schedulingSignature] would fire a second,
     * *un*-asked re-plan the moment it was spent. Unlike the menu's "copy" this names ONE task however many
     * cells are selected — "start *this* task" has no meaning for a block. A task that is not a schedulable
     * leaf is a no-op (a parent task is a grouping and is never placed). The instant is the reducer's clock,
     * like the other user-authored edits that re-plan on the spot. Persisted + synced; not undoable.
     */
    data class ForceTaskStart(
        val taskId: TaskId,
    ) : SchedulerIntent

    /**
     * PRD §15 Screen breaks: show/hide the screen breaks on the calendar (a cosmetic display preference). Hiding
     * does not touch the schedule or notifications. Persisted; not undoable.
     */
    data class SetShowScreenBreaks(
        val show: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §14 Reminders: show/hide the reminder tags on the calendar (a cosmetic display preference). Hiding
     * does not touch the chores or their scheduling/checked state. Persisted; not undoable.
     */
    data class SetShowReminders(
        val show: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §15 Screen breaks: enable/disable the spoken voice cue for the 20s look-away pause. Persisted; not
     * undoable; does not touch the schedule.
     */
    data class SetLookAwayVoice(
        val enabled: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §11 Notifications: turn the app's system notifications on/off — the lateral menu's **Notifications**
     * switch and the `Ctrl+Shift+Alt+N` chord, which are the same lever from two places.
     *
     * Silences what the app *posts* and nothing else: the notification log keeps every entry (so the History
     * window's Notifications column is unchanged), the voice cues have their own switch, and the schedule is
     * untouched. See [org.example.project.scheduler.state.SchedulerState.notificationsEnabled]. Persisted +
     * synced; not undoable.
     */
    data class SetNotificationsEnabled(
        val enabled: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §4 **Default sub-tree**: run [inner] against the *template* instead of the live tree.
     *
     * The "Default sub-tree" window (§7) draws the template with the task tree's own component, so it emits
     * the task tree's own intents — `ClickCell`, `SetCellTitle`, `ToggleExpand`, `Copy`, `Paste`, the lot.
     * This wrapper is what points them at the template: the reducer projects the template into a state
     * ([org.example.project.scheduler.state.projectDefaultSubtree]), reduces [inner] there, and folds the
     * result back. Every live-tree field is left exactly as it was, so nothing done in the window can reach
     * the real tree.
     *
     * The whole thing lands as **one** Main history unit, like a task-tree switch — so one Ctrl+Z undoes one
     * gesture in the window, not the handful of inner units the reducer would otherwise have recorded.
     *
     * Authoritative — persisted and synced; it never re-plans (the template is not part of
     * [org.example.project.scheduler.domain.SchedulerDomain.schedulingSignature]: nothing is scheduled until
     * it is applied to a real cell).
     */
    data class InDefaultSubtree(
        val inner: SchedulerIntent,
    ) : SchedulerIntent

    /**
     * PRD §4: flip one template row's switch — on (the row mints a brand new task each graft) or off (every
     * cell built from the row mirrors the task the row points at). Carried separately from [InDefaultSubtree]
     * because it edits the template's own metadata rather than its tree.
     */
    data class SetDefaultSubtreeCellBound(
        val cellId: org.example.project.scheduler.model.CellId,
        val bound: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §7 **All tasks**: run [inner] against the live tree seen **through that window** — re-rooted at a
     * synthetic list holding [rootCells] (one cell per task, in the sorter's order) and carrying the
     * window's own expansion/selection/edit session instead of the tree's
     * ([org.example.project.scheduler.state.projectTaskList]).
     *
     * The window draws the task tree's own component, so it emits the task tree's own intents — `ClickCell`,
     * `SetCellTitle`, `ToggleExpand`, `Copy`, `Paste`, the lot. This wrapper is what points them at the
     * window's rows rather than the tree's: without it the arrow keys, `Ctrl+A` and Ctrl+F would all walk the
     * tree's order, and an edit would move the tree's caret.
     *
     * The rows travel with the intent because the order is the **window's** Compose-only sorter state (PRD §7
     * — an ordering is a way of looking at the tree, never a fact about it), so the reducer has nothing to
     * recompute it from and must be told exactly which rows the gesture was made on.
     *
     * What it edits is the **live tree**, so the whole gesture lands as **one Main history unit** and syncs
     * like any other tree edit. The inner reduction's own units evaporate with the projection, exactly as
     * they do for [InDefaultSubtree].
     */
    data class InTaskList(
        val inner: SchedulerIntent,
        val rootCells: List<CellId>,
    ) : SchedulerIntent

    /**
     * PRD §7 "All tasks": close every row the window has open — the button beside its sorter.
     *
     * Local view state ([org.example.project.scheduler.state.SchedulerState.taskListExpanded]): not
     * persisted, not synced, and no Undo/Redo unit, like the sorter it sits beside.
     */
    data object CollapseTaskListRows : SchedulerIntent

    /**
     * PRD §4/§7: turn the default-sub-tree policy on/off (the switch left of the lateral-menu button). Off
     * means new cells are created bare, exactly as before the feature existed; the template itself is kept
     * either way. Persisted + synced; not undoable.
     */
    data class SetDefaultSubtreeEnabled(
        val enabled: Boolean,
    ) : SchedulerIntent

    /**
     * PRD §7/§13 cell contextual menu **"add default sub-tree"**: apply the template on demand under the
     * **leaves** of the sub-trees [cellIds] root — a cell that parents nothing being its own leaf. A template
     * says how a piece of work breaks down, so asking for it on a cell that is already broken down asks for
     * it on the pieces, not for a second copy of it beside them.
     *
     * Deliberately independent of the [SetDefaultSubtreeEnabled] switch (that switch governs the *automatic*
     * graft; asking for it explicitly is always an answer) and, unlike the graft, it does not care whether
     * the task is new. One undoable Main history unit for the whole set, and every cell walked is expanded so
     * the rows — which land at the bottom of the sub-tree — are actually visible.
     */
    data class AddDefaultSubtree(
        val cellIds: List<CellId>,
    ) : SchedulerIntent

    /**
     * Sets the user's sleep schedule (wake/goal/duration). The scheduler then leaves the nightly sleep
     * window empty. [todayEpochDay] anchors the 15-min-per-2-days wake drift at the current local day when
     * a goal different from the current wake is set. Persisted; recorded as an undoable Main History Unit
     * (shows in the History window, [SleepDelta]); triggers a schedule refill.
     */
    data class SetSleepSchedule(
        val sleep: org.example.project.scheduler.model.SleepSchedule,
        val todayEpochDay: Long,
    ) : SchedulerIntent

    /**
     * Sleep/Work toggle (left-menu control): set the next scheduled wake instant (epoch millis) when the user
     * presses **Sleep**, or `null` when they press **Work** (or the wake instant lapses on launch). Persisted +
     * synced (authoritative); not undoable; does not touch the schedule. The ViewModel also mirrors the mode to
     * the `account_state` table so the server cron (`tick_pause_cues()`) can suppress the pause-end cue while
     * sleeping.
     */
    data class SetSleepMode(
        val sleepingUntilMillis: Long?,
    ) : SchedulerIntent

    /**
     * PRD §12 Device sleep: the device was asleep for `[sleepStartEpochMillis, sleepEndEpochMillis]`
     * (detected on wake as a tick gap far larger than the cadence), so the user was NOT doing the
     * scheduled task during it. Cuts the in-progress scheduled period at the sleep start (recording
     * only the pre-sleep work) and clears the schedule; the following [RefreshSchedule] (at wake time)
     * re-picks a task starting after the sleep, leaving the sleep window as a hole in the calendar
     * panel. Not undoable (touches the history-excluded schedule/record only).
     */
    data class ReportDeviceSleep(
        val sleepStartEpochMillis: Long,
        val sleepEndEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §9/§17 past sleep: materialize the given elapsed spans — scheduled sleep windows the engine found
     * were no-screen/inactive periods — as persisted "Sleep" panels (so past sleep is a recorded fact, not a
     * retroactive projection of the schedule). Dispatched by the engine, which owns the derived inactivity;
     * the reducer skips spans an existing materialized Sleep panel already covers and drops sub-minute
     * slivers. Not undoable and, like the record bank, not a syncable change on its own.
     */
    data class MaterializePastSleep(
        val ranges: List<org.example.project.scheduler.model.TaskTimeRange>,
    ) : SchedulerIntent

    /**
     * [initialText] non-null when entering via typing (replaces cell content with first keystroke).
     *
     * [mode] forces the session's Edit Mode instead of letting it open on PRD §4's default (Change Task).
     * The one caller is PRD §7's "All tasks" window: its root rows are **always in renaming mode**, because
     * the row IS the task and re-pointing it at another one there would say nothing (see
     * [SchedulerReducer]'s `reduceInTaskList`). Null everywhere else, which is the default.
     */
    data class BeginEdit(
        val cellId: CellId,
        val initialText: String? = null,
        val mode: CellEditMode? = null,
    ) : SchedulerIntent

    data class UpdateEditText(val text: String) : SchedulerIntent

    data class SetEditMode(val mode: CellEditMode) : SchedulerIntent

    data class PickTaskFromMenu(val taskId: TaskId) : SchedulerIntent

    data object SelectCreateAssignTask : SchedulerIntent

    data class PickTitleSuggestion(val title: String) : SchedulerIntent

    data object CancelEdit : SchedulerIntent

    data class NavigateSelection(
        val direction: SelectionNavigate,
        /** Shift+Direction expands a visible range from [SchedulerSelection.rangeAnchor] or main. */
        val shift: Boolean = false,
    ) : SchedulerIntent

    data class CycleMainSelection(val forward: Boolean) : SchedulerIntent

    /** Tab on a single selected cell: expand and focus the first child when populated. */
    data object SelectFirstChild : SchedulerIntent

    /** PRD §3: Ctrl+A selects every visible (selectable) cell. */
    data object SelectAllVisibleCells : SchedulerIntent

    /**
     * PRD §4 Find & replace: bring the cell a search hit sits on **on screen** and select it — every
     * ancestor along [ancestors] (outermost first, as
     * [org.example.project.scheduler.domain.TaskTreeSearch.Match.ancestors] recorded it) is expanded in one
     * history unit, so walking the hits never buries Ctrl+Z under a pile of expand/collapse units.
     *
     * The last ancestor is the occurrence the row is rendered under, which is what the selection's
     * `renderVia` is set to — a mirrored cell must highlight in the copy the find bar navigated to, not in
     * some other one.
     */
    data class RevealCell(
        val cellId: CellId,
        val ancestors: List<CellId> = emptyList(),
    ) : SchedulerIntent

    /**
     * PRD §4 Find & replace ("replace all"): rename each task in [titles] to its new title, as ONE Main
     * history unit.
     *
     * Renaming here means exactly what Rename mode means — the **task's** title changes, so every cell
     * pointing at it follows. Keying by task is what keeps a mirrored task from being rewritten once per
     * occurrence. Each rename runs through the same primitive typing a title does, so a replacement that
     * empties a title deletes the cell by §4's ordinary rule ("the blank title is what deletes").
     */
    data class ReplaceTaskTitles(val titles: Map<TaskId, String>) : SchedulerIntent

    /**
     * PRD §4 Copy (Ctrl+C): serialize the selected cells' subtrees — **whole**, however deep they run — to the
     * (system) clipboard. The §13 deep-copy window's maximum depth is that window's own; the chord asks nobody
     * and truncates nothing. What each task carries is still the account's three copy switches.
     */
    data object CopySelection : SchedulerIntent

    /**
     * PRD §4/§13 Cut (Ctrl+X): the same whole-sub-tree copy [CopySelection] takes, then the selected cells are
     * emptied — one history unit, so a single Ctrl+Z puts the cut sub-tree back.
     */
    data object CutSelection : SchedulerIntent

    /**
     * PRD §13 deep copy: the account's one maximum depth (see [SchedulerState.deepCopyMaxDepth]), set from
     * the deep-copy window. Clamped into [org.example.project.scheduler.domain.SchedulerDomain.DEEP_COPY_DEPTH_RANGE]; not an Undo/Redo unit.
     */
    data class SetDeepCopyMaxDepth(val depth: Int) : SchedulerIntent

    data class SetDeepCopyUnlimited(val unlimited: Boolean) : SchedulerIntent

    /**
     * PRD §13 deep copy: the account's three **what does a copy carry** switches (see
     * [org.example.project.scheduler.domain.SchedulerDomain.CopyOptions]), set from the deep-copy window
     * when it copies. Like the depth, one answer for the whole account and not an Undo/Redo unit.
     */
    data class SetCopyOptions(val options: org.example.project.scheduler.domain.SchedulerDomain.CopyOptions) :
        SchedulerIntent

    /**
     * PRD §7 Keyboard shortcuts: bind the system-wide [shortcut] to [binding], or — with [binding] null —
     * put it back to the chord it ships with
     * ([org.example.project.scheduler.platform.GlobalShortcut.defaultBinding]).
     *
     * Refused (a no-op) when
     * [org.example.project.scheduler.platform.GlobalShortcutBindings.rejection] has an answer: the window
     * shows that same sentence rather than dispatching, so the reducer's guard is the backstop and not the
     * user-facing check. An accepted rebinding is **one Undo/Redo unit** in the Main history.
     */
    data class SetGlobalShortcutBinding(
        val shortcut: org.example.project.scheduler.platform.GlobalShortcut,
        val binding: org.example.project.scheduler.platform.ShortcutBinding?,
    ) : SchedulerIntent

    /**
     * PRD §4 Paste: rebuild the tree structure serialized in [text] at the single selected cell — a
     * no-op unless [text] is in the app's tab-indented format.
     */
    data class PasteTree(val text: String) : SchedulerIntent

    /**
     * PRD §8 Manual add / edit window "save": add a user-authored panel (`auto = false`) with the
     * given task/title/bounds. [taskId] is null for a calendar-only "New task" (does NOT create a tree
     * task). [pinned] reflects the edit-window pin toggle. Recorded as a calendar delta.
     */
    data class AddTaskPanel(
        val taskId: TaskId?,
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val pins: org.example.project.scheduler.model.PanelPins,
    ) : SchedulerIntent

    /**
     * PRD §8 contextual menu "add a no-screen period": lay a user-authored "No screen" panel over the
     * given span. On-screen task panels it overlaps are trimmed/deleted to fit it (screen-override
     * resolution), and the §9 fill then only places off-screen tasks inside it. Undoable calendar delta.
     */
    data class AddNoScreenPeriod(
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §8/§12 contextual menu "add an inactivity period": lay a user-authored "Inactivity" panel over
     * the given span — a real panel recording that the user was away from every device. It stays a
     * *screen* period (no fill constraint, screen breaks keep their cadence). Undoable calendar delta.
     */
    data class AddInactivityPeriod(
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §8 edit window / drag / resize commit: replace a panel's task/title/bounds and [pinned] state
     * (the edit-window pin toggle). Editing turns an auto panel into a user-authored one (re-id'd into
     * the persistent `panel/{n}` namespace). [taskId] is null for a calendar-only "New task". Recorded
     * as a calendar delta.
     */
    data class UpdateTaskPanel(
        val id: String,
        val taskId: TaskId?,
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val pins: org.example.project.scheduler.model.PanelPins,
        /** PRD §8 Overlap Mode: keep the raw (possibly overlapping) bounds and seed the panel to 1/n. */
        val allowOverlap: Boolean = false,
    ) : SchedulerIntent

    /**
     * PRD §8 task contextual menu ("Remove"): delete a panel. Recorded as a calendar delta so it can
     * be undone while the calendar is focused.
     */
    data class RemoveTaskPanel(
        val id: String,
    ) : SchedulerIntent

    /**
     * PRD §8 "Remove" on a *merged* calendar block (consecutive same-task panels shown as one): delete
     * every backing panel at once, as a single undoable calendar delta.
     */
    data class RemoveTaskPanels(
        val ids: List<String>,
    ) : SchedulerIntent

    /**
     * PRD §8 edit / drag / resize commit on a *merged* calendar block (consecutive same-task panels
     * shown as one): drop all of [removeIds] and lay down a single user-authored panel with the given
     * task/title/bounds/[pinned] — so interacting with the merged block treats the whole visible span as
     * one decision. The result is normalized with the same-task merge on commit. Recorded as one delta.
     */
    data class ReplaceTaskPanels(
        val removeIds: List<String>,
        val taskId: TaskId?,
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val pins: org.example.project.scheduler.model.PanelPins,
        /** PRD §8 Overlap Mode: keep the raw (possibly overlapping) bounds and seed the panel to 1/n. */
        val allowOverlap: Boolean = false,
    ) : SchedulerIntent

    /**
     * PRD §8 Overlap Mode: set the horizontal [TaskPanel.layoutWeight] of one or more panels (by id) —
     * dispatched when the user drags a vertical edge between two overlapping panels to re-divide their
     * shared width. Recorded as a calendar delta so it is undoable.
     */
    data class SetPanelWeights(val weights: Map<String, Double>) : SchedulerIntent

    /**
     * PRD §8 task contextual menu ("Remove") on an auto task-record block: drop the
     * `[startEpochMillis, endEpochMillis]` period from [taskId]'s record. The record lives outside the
     * Undo/Redo history (PRD §8), so this is a side effect, not an undoable delta.
     */
    data class RemoveRecordPeriod(
        val taskId: TaskId,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    ) : SchedulerIntent

    /**
     * PRD §9/§12 retroactive: drop from every ON-SCREEN task's record the parts covered by [ranges] — the
     * stretches the devices say nobody was at a screen for (`SchedulerDomain.observedNoScreenRegions`) — and
     * materialize them as "Inactivity" panels instead.
     *
     * The same rule `SchedulerReducer.noScreenEvidence` now applies as work is banked, applied ONCE at
     * start-up to the work banked before that rule existed. It is needed because the rule used to key on
     * hand-drawn "No screen" panels alone and so never fired on an account without one: account 3 carried
     * 43 h of recorded on-screen "work" over spans its own OS reported the machine asleep.
     *
     * Unlike the tick that banks records, this **syncs**. `Task.record` is authoritative and the three-way
     * merge UNIONS it, so a deletion that stayed local would be resurrected by the next peer that still had
     * the span. Not undoable — the record lives outside Undo/Redo (PRD §8), like [RemoveRecordPeriod].
     */
    data class StripNoScreenRecords(
        val ranges: List<TaskTimeRange>,
    ) : SchedulerIntent

    /**
     * PRD §8 (uniform blocks): convert an auto task-record period into a user-authored panel. Removes
     * the `[recordStartEpochMillis, recordEndEpochMillis]` range from [recordTaskId]'s record and adds
     * a panel with the given task/title/bounds and [pinned] state.
     */
    data class PinRecordAsPanel(
        val recordTaskId: TaskId,
        val recordStartEpochMillis: Long,
        val recordEndEpochMillis: Long,
        val taskId: TaskId?,
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val pins: org.example.project.scheduler.model.PanelPins,
        /** PRD §8 Overlap Mode: keep the raw (possibly overlapping) bounds and seed the panel to 1/n. */
        val allowOverlap: Boolean = false,
    ) : SchedulerIntent

    /**
     * PRD §7 window navigation: move focus to [window] (the task tree or a floating window). Focusing a
     * floating window forcibly exits any tree Edit Mode and clears the tree selection (PRD §4); the
     * navigation itself is recorded as a (currently non-undoable) WindowNav History Unit (PRD §7).
     */
    data class FocusWindow(val window: AppWindow) : SchedulerIntent

    /**
     * PRD §5/§7: shorthand for focusing the calendar (true) or returning focus to the task tree (false);
     * delegates to [FocusWindow].
     */
    data class SetCalendarFocus(val focused: Boolean) : SchedulerIntent

    /**
     * PRD §8 Overlap Mode: toggle "allow overlap" for the next calendar move/resize (pressing `O` while
     * the calendar is focused). Transient — not undoable.
     */
    data object ToggleCalendarOverlap : SchedulerIntent

    /**
     * Append a posted notification's text to the local-only diagnostic log shown in the History Manager's
     * Notifications column. Dispatched by [org.example.project.scheduler.engine.SchedulerEngine.notifyUser]
     * every time a system notification is shown. Non-syncing (derived, per-device) and capped at
     * [org.example.project.scheduler.state.SchedulerState.MAX_NOTIFICATION_LOG] — a no-op once full.
     */
    data class RecordNotification(
        val title: String,
        val message: String,
        val timeMillis: Long,
    ) : SchedulerIntent

    /**
     * Append one completed Supabase HTTP call to the local-only diagnostic log shown in the History Manager's
     * **Supabase usage** column. Dispatched by [org.example.project.scheduler.ui.TaskSchedulerViewModel] as it
     * collects [org.example.project.scheduler.sync.RemoteSnapshotClient.usageEvents]. Non-syncing (derived,
     * per-device); a rolling tail capped at
     * [org.example.project.scheduler.state.SchedulerState.MAX_SUPABASE_USAGE_LOG].
     */
    data class RecordSupabaseUsage(
        val resource: String,
        val operation: String,
        val requestBytes: Long,
        val responseBytes: Long,
        val status: Int,
        val timeMillis: Long,
    ) : SchedulerIntent

    /** Ctrl+Z / Ctrl+Y — undo/redo the content history (Edit Mode while editing, else "the rest"). */
    data object Undo : SchedulerIntent
    data object Redo : SchedulerIntent

    /** Alt+Left / Alt+Right — undo/redo the independent selection-state history (PRD §5). */
    data object UndoSelection : SchedulerIntent
    data object RedoSelection : SchedulerIntent
}

