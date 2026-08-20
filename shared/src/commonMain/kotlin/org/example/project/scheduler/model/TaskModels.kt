package org.example.project.scheduler.model

import kotlin.jvm.JvmInline
import kotlinx.datetime.DayOfWeek

/** PRD §10: a task's minimum time defaults to 45 minutes. */
const val DEFAULT_MINIMUM_MINUTES: Int = 45

data class Task(
    val id: TaskId,
    val title: String,
    val childTaskIds: List<TaskId> = emptyList(),
    /** Cells referencing this task, ordered by shortest path (maintained on mutation). */
    val occurrences: List<CellId> = emptyList(),
    /** Shared sublist for all cells pointing at this task (mirrored sub-trees). */
    val childListId: CellListId? = null,
    /**
     * PRD §10 Minimum time for a task: the smallest duration (in minutes) the scheduler may allocate
     * to this task. Stored on the task object; edited via the per-row min-time input field. Defaults
     * to [DEFAULT_MINIMUM_MINUTES] (45 min) so a freshly created task already has a usable slot.
     */
    val minimumMinutes: Int = DEFAULT_MINIMUM_MINUTES,
    /**
     * PRD §8/§9 Task record: the periods during which the user did this task, used by the calendar
     * (visualization) and the scheduler (the §9 task score's recent-share term). Per PRD §8 this is intentionally
     * excluded from the Undo/Redo history state (see [org.example.project.scheduler.state.SchedulerState]).
     */
    val record: List<TaskTimeRange> = emptyList(),
    /**
     * PRD §13 Schedule Unit: an ordered list of named sub-steps (each with its own spanning time) that
     * subdivide this task's allotted slot. Empty when the task has no schedule unit. Only meaningful for
     * leaf tasks (PRD §13: the Edit window shows the schedule-unit section only when the task has no
     * child task).
     * Part of the Task Tree domain object (PRD §6), so edits go through the content Undo/Redo history.
     */
    val scheduleUnit: List<ScheduleUnitEntry> = emptyList(),
    /**
     * Free-form text document attached to this task, shown/edited in the third section of the PRD §13
     * Edit window (opened from a populated cell's right-click menu). Empty when the task has no notes. Part of the
     * Task Tree domain object (PRD §6), so edits go through the content Undo/Redo history.
     */
    val text: String = "",
    /**
     * PRD §8 "On screen" switch (calendar edit window): whether this task is done at a screen. An
     * on-screen task may only be placed on screen periods; an off-screen task only on no-screen
     * periods. Defaults to on-screen (the pre-switch behaviour — everything scheduled was screen work).
     */
    val onScreen: Boolean = true,
    /**
     * PRD §8 "Doable during a screen break" switch: whether this task may be scheduled inside the
     * **5-minute** pose (§15), past its closed first minute
     * ([org.example.project.scheduler.domain.SchedulerDomain.SCREEN_BREAK_CLOSED_HEAD_MILLIS]). Even when
     * on, a task whose minimum time exceeds what is left of the break never fits there. Defaults off.
     *
     * It is **not** what opens the 15-minute pose: that one is [ScreenBreakPeriod.OffScreenOnly] and accepts
     * every task with [onScreen] off, this flag or not. So the switch is exactly "short-break work".
     *
     * **Invariant: this implies `!`[onScreen]** — a screen break is time away from the screen, so only a
     * task that needs no screen can be done in one. It is enforced where the flag is written
     * (`SchedulerReducer.applySetTaskScreenFlags`, and the edit window only offers the switch while "On
     * screen" is off) and healed on decode, so a payload written before the invariant cannot resurrect an
     * on-screen break-doable task.
     */
    val doableDuringBreak: Boolean = false,
)

/**
 * PRD §13 Schedule Unit element: one sequential sub-step of a task, with a [title] and a [spanMinutes]
 * spanning time (minutes, matching [Task.minimumMinutes]). The running sum of an entry's span and all
 * preceding ones gives its deadline offset from the task's start (see
 * [org.example.project.scheduler.domain.SchedulerDomain.scheduleUnitDeadlines]).
 */
data class ScheduleUnitEntry(
    val title: String,
    val spanMinutes: Int,
)

/**
 * PRD §14 Reminders entry: one row of the standalone reminders list — a [title], its recurrence
 * [spanDays] (in days), and [timeOfDayMinutes] (the "time in the day" the reminder is placed, as minutes
 * since local midnight in `0..1439`, or **negative** for "not defined" → the current time at generation).
 * Independent of the task tree: the reminders list is its own top-level state, not a per-task field. The
 * reminder scheduler turns each entry into recurring **zero-duration, checkable calendar tags** (see
 * [org.example.project.scheduler.domain.SchedulerDomain.choreScheduledPanels]) — unlike the panels they
 * replace, a reminder has no spanning time and is not an obstacle to the §9 task scheduler.
 *
 * [spanDays] may be entered as an arithmetic formula in the UI (e.g. `31/21`); [daysFormula] keeps that raw
 * text so the field round-trips, while [spanDays] is its evaluated value (see
 * [org.example.project.scheduler.domain.SchedulerDomain.evaluateDayFormula]). A value `≥ 1` is a day
 * cadence (`2` = alternate days), `0 < spanDays < 1` is a daily reminder, and `≤ 0` (blank) is a one-off.
 *
 * (The `Chore*` names are retained internally for now; the user-facing concept is "Reminders".)
 */
data class ChoreEntry(
    val title: String,
    val spanDays: Double,
    val timeOfDayMinutes: Int = 0,
    val daysFormula: String = "",
    /**
     * PRD §14: a stable identity for the reminder (like a task's `taskId`), so a checked reminder added
     * from the calendar can reference "the reminder of this id" independently of its (editable) title.
     * Blank for entries created before ids existed or freshly added in the manager; [assignReminderIds]
     * fills any blank with a unique `reminder-{n}` on load and whenever the reminders list is set.
     */
    val id: String = "",
    /**
     * PRD §14: the unit the recurrence number is entered in (the dropdown beside the "Days" field). The
     * cadence [spanDays] is the entered number times the unit's [ChoreRecurrenceUnit.daysPerUnit]; the unit
     * itself is kept so the field round-trips. Defaults to [ChoreRecurrenceUnit.Days].
     */
    val recurrenceUnit: ChoreRecurrenceUnit = ChoreRecurrenceUnit.Days,
    /**
     * PRD §14 "constrained in": the [id] of another reminder this one is *constrained to*, or blank when
     * unconstrained. A constrained reminder is only ever placed on days the constraining reminder also
     * occurs, while still averaging its own cadence ([spanDays]) — e.g. a "2.5 per week" reminder
     * constrained to a "every 3 days" reminder appears ~2.5 times a week but only on those 3-day days (see
     * [org.example.project.scheduler.domain.SchedulerDomain.constrainedOccurrenceOffsets]). A blank /
     * dangling / cyclic reference falls back to the reminder's own unconstrained cadence.
     */
    val constrainedToReminderId: String = "",
)

/**
 * PRD §14 Reminders recurrence unit: the user enters a recurrence number/formula and picks a unit; the unit
 * maps that number to the cadence in days ([ChoreEntry.spanDays]) via [toDays]. There are two families:
 * **interval** units (*every n …*: [Days], [Weeks], [Months], [Years]) multiply by the period length, and
 * **rate** units (*n times per …*: [PerWeek], [PerMonth], [PerYear]) divide the period length by the number.
 * Months and years use the mean Gregorian year length (365.24219 days): a month is `365.24219 / 12` days.
 */
enum class ChoreRecurrenceUnit(val label: String) {
    Days("days"),
    Weeks("weeks"),
    Months("months"),
    Years("years"),
    PerWeek("per week"),
    PerMonth("per month"),
    PerYear("per year");

    /** Cadence in days for an entered recurrence [number] (0 → 0, i.e. a one-off / blank). */
    fun toDays(number: Double): Double = when (this) {
        Days -> number
        Weeks -> number * DAYS_PER_WEEK
        Months -> number * DAYS_PER_MONTH
        Years -> number * DAYS_PER_YEAR
        PerWeek -> if (number != 0.0) DAYS_PER_WEEK / number else 0.0
        PerMonth -> if (number != 0.0) DAYS_PER_MONTH / number else 0.0
        PerYear -> if (number != 0.0) DAYS_PER_YEAR / number else 0.0
    }

    /** Inverse of [toDays]: the field number that yields cadence [spanDays] in this unit (for seeding the UI). */
    fun fromDays(spanDays: Double): Double = when (this) {
        Days -> spanDays
        Weeks -> spanDays / DAYS_PER_WEEK
        Months -> spanDays / DAYS_PER_MONTH
        Years -> spanDays / DAYS_PER_YEAR
        PerWeek -> if (spanDays != 0.0) DAYS_PER_WEEK / spanDays else 0.0
        PerMonth -> if (spanDays != 0.0) DAYS_PER_MONTH / spanDays else 0.0
        PerYear -> if (spanDays != 0.0) DAYS_PER_YEAR / spanDays else 0.0
    }

    companion object {
        const val DAYS_PER_YEAR: Double = 365.24219
        const val DAYS_PER_MONTH: Double = DAYS_PER_YEAR / 12
        const val DAYS_PER_WEEK: Double = 7.0
    }
}

/**
 * PRD §15: **which periods a screen break is**, in the sense of `side-dev/README.md` — a period is a window of
 * the timeline together with the set of tasks it accepts, and that is the whole of what a break is to the §9
 * scheduler. The three shapes are the three breaks, and they are exactly the periods of `side-dev`'s **test
 * 11**: a window accepting nothing, and a "1 minute accepting nothing, then a tail accepting one restricted
 * set" stretch.
 */
enum class ScreenBreakPeriod {
    /**
     * One period accepting **nobody**, end to end — the 20-second look-away (test 11's sliding 20 s window).
     * Because it excludes everyone equally it creates no influence field, which is why a look-away recurring
     * every 20 minutes forever does not distort the plan around each of its occurrences.
     */
    Closed,

    /**
     * Two periods: a closed **first minute**
     * ([org.example.project.scheduler.domain.SchedulerDomain.SCREEN_BREAK_CLOSED_HEAD_MILLIS], clamped to the
     * break's own length) accepting nobody, then a tail accepting the tasks that need no screen **and** are
     * marked *doable during a screen break* (PRD §8). The 5-minute pose — test 11's "1min: nothing" +
     * "4min: only A" stretch, verbatim.
     */
    ClosedMinuteThenBreakDoable,

    /**
     * One period, open end to end, accepting **every task that needs no screen** (`onScreen == false`,
     * whatever its *doable during a break* flag) — the 15-minute pose. Long enough that getting off the
     * screen is not a separate step and broad enough to be genuine off-screen work time, so unlike the
     * 5-minute pose it has no closed head and does not restrict itself to the break-doable subset.
     */
    OffScreenOnly,
}

/**
 * PRD §15 Screen break: a task to do periodically, placed on the calendar with a **real spanning time** (a
 * [TaskPanel] with `screenBreak = true`). It recurs every [intervalMillis] for [durationMillis]. The
 * distinguishing rule (vs a simple task): when the §9 auto scheduler splits a task panel around a side
 * task, the screen break's time is *not* counted against that task's minimum — the task resumes after and
 * still accumulates its full minimum (see [org.example.project.scheduler.domain.SchedulerDomain.fillSchedule]).
 */
data class ScreenBreak(
    val title: String,
    val intervalMillis: Long,
    val durationMillis: Long,
    /**
     * PRD §15: when true this is a **rest pose** (the 5/15-min poses) eligible for the merge — when the
     * shorter pose's next window overlaps the longer's, the shorter becomes a longer-length pause and the
     * longer's occurrence is pushed back (see
     * [org.example.project.scheduler.domain.SchedulerDomain.screenBreakPanels]). The look-away (false) never
     * merges. All screen breaks — rest poses and the look-away alike — are otherwise scheduled the same way
     * (next occurrence at `lastRestMillis + intervalMillis`).
     */
    val restBreak: Boolean = false,
    /**
     * PRD §15: epoch millis of the most recent qualifying pause (a device sleep ≥ [durationMillis], from the
     * OS sleep history), or 0 when none is known. The next occurrence is due at `lastRestMillis + intervalMillis`
     * — clamped forward to the now-line once that has passed without the pause being taken (0 ⇒ due now). See
     * [org.example.project.scheduler.domain.SchedulerDomain.screenBreakNextStart].
     */
    val lastRestMillis: Long = 0L,
    /**
     * PRD §15: the minimum length a pause must reach to *anchor* this pose (a "qualifying pause"), in millis.
     * `0` (the default) means "use [durationMillis]" — the production rule, where a pose is triggered by a
     * pause at least as long as the break itself. Setting it **decouples** the qualifying-pause length from
     * the drawn break length, used by the debug fast-break override to place a short (5-second) break only
     * after a long (e.g. ≥2-hour) real pause. Read everywhere through [qualifyingPauseMillis], never directly.
     */
    val pauseThresholdMillis: Long = 0L,
    /**
     * PRD §15: the STABLE identifier of this break type — `"look_away"` / `"5min_break"` / `"15min_break"`.
     * The [title] is display text and the durations move under the debug fast-break knobs, so neither can name
     * a break across the wire; this can. It is what a device publishes in its presence row so the server can
     * look the break's configured length + vocal message up in `break_config` (both changeable over HTTP —
     * migration 20260724000000). Blank on an ad-hoc break, which simply has no server-side configuration.
     */
    val key: String = "",
    /**
     * PRD §15: the **period(s) this break is** to the §9 scheduler — see [ScreenBreakPeriod].
     *
     * The default reproduces the pre-1.6.0 rule from [restBreak] alone (a rest pose is a closed minute then a
     * break-doable tail, anything else is closed end to end), so an ad-hoc break that does not state a shape
     * keeps behaving exactly as it did. The three production breaks state theirs explicitly
     * ([org.example.project.scheduler.domain.SchedulerDomain.DEFAULT_SCREEN_BREAKS]) — in particular the
     * 15-minute pose is [ScreenBreakPeriod.OffScreenOnly], not a second copy of the 5-minute one. Note `copy()`
     * does not re-evaluate this default, so the debug fast-break knobs (which only retime) never move a shape.
     */
    val shape: ScreenBreakPeriod =
        if (restBreak) ScreenBreakPeriod.ClosedMinuteThenBreakDoable else ScreenBreakPeriod.Closed,
) {
    /**
     * The minimum pause length that anchors this pose (sets [lastRestMillis]). It is [pauseThresholdMillis]
     * when that is set (> 0), else [durationMillis] — so production breaks (which leave it 0) keep the
     * "a pause at least as long as the break" rule unchanged.
     */
    val qualifyingPauseMillis: Long
        get() = if (pauseThresholdMillis > 0L) pauseThresholdMillis else durationMillis
}

/**
 * An **alarm** (PRD §18): a wall-clock time of day at which every phone signed in to the account rings.
 *
 * Authoritative user data — it is edited in the Alarms window (left menu), persisted and synced like the
 * reminders/sleep schedule, and never re-derived. Each phone schedules the ring itself from this synced
 * list (an OS-level exact alarm), so it fires offline and while the app is backgrounded/killed; the desktop
 * only edits alarms (it never rings).
 *
 * [timeOfDayMinutes] is minutes since local midnight (`0..1439`), so the alarm follows the device's wall
 * clock. [soundSeconds] is how long the alarm sound lasts, [vibrate] whether the phone also vibrates.
 *
 * [days] is **the days the alarm is triggered on** — the local weekdays it may ring on, defaulting to
 * [EVERY_DAY]. It is a property of the alarm itself (not of the device), so it is synced with the rest of
 * the entry and every device of the account rings on the same days. An empty set never rings (the row is
 * kept, like a disarmed one). [repeats] then says whether it keeps coming round on those days or is a
 * one-off that disables itself once it has rung ([enabled] = false, the row staying ready to be re-armed).
 */
data class AlarmEntry(
    /** Stable identity (`alarm-{n}`), so a ring can name its alarm across devices and edits. */
    val id: String,
    val label: String = "",
    val timeOfDayMinutes: Int = 0,
    val soundSeconds: Int = DEFAULT_ALARM_SOUND_SECONDS,
    val vibrate: Boolean = true,
    /** The local weekdays this alarm rings on — every day unless the user narrowed it. */
    val days: Set<DayOfWeek> = EVERY_DAY,
    val repeats: Boolean = true,
    val enabled: Boolean = true,
) {
    /**
     * Whether this alarm can ever ring: armed, at a real time of day, lasting a positive time, and with at
     * least one day to ring on. [soundSeconds] is the length of the whole ring — the sound, and the
     * vibration alongside it.
     */
    val schedulable: Boolean
        get() = enabled && timeOfDayMinutes in 0..<MINUTES_PER_DAY && soundSeconds > 0 && days.isNotEmpty()

    /** Whether this alarm rings on [day] — i.e. whether [day] is one of the days the user selected. */
    fun ringsOn(day: DayOfWeek): Boolean = day in days

    companion object {
        /** How long the alarm sound lasts by default. */
        const val DEFAULT_ALARM_SOUND_SECONDS: Int = 30

        /** Upper bound on [soundSeconds] (10 minutes) — long enough to wake, short enough to stop itself. */
        const val MAX_ALARM_SOUND_SECONDS: Int = 600

        const val MINUTES_PER_DAY: Int = 24 * 60

        /** The default [days]: an alarm rings every day unless the user picks a narrower set. */
        val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.entries.toSet()
    }
}

/**
 * The user's sleep schedule: a nightly window `[wake − sleepDuration, wake)` (local wall-clock) that the
 * task scheduler and screen-break projection must leave empty (see
 * [org.example.project.scheduler.domain.SchedulerDomain.sleepPanels]).
 *
 * [wakeMinutes] is the current wake time as minutes since local midnight; [goalWakeMinutes] is the wake
 * time it drifts toward by 15 min every 2 days; [sleepDurationMillis-equivalent] is held as
 * [sleepDurationMinutes] (constant, so bedtime drifts with wake). [anchorEpochDay] is the local day the
 * drift started (set when settings are saved with a goal different from the current wake), or null when
 * there is no drift. See [org.example.project.scheduler.domain.SchedulerDomain.effectiveWakeMinutes].
 */
data class SleepSchedule(
    val wakeMinutes: Int = 450, // 07:30
    val goalWakeMinutes: Int = 450, // 07:30
    val sleepDurationMinutes: Int = 510, // 8h30
    val anchorEpochDay: Long? = null,
)

/**
 * PRD §9 Task record entry: a single period `[start, end]` the user spent on a task, as epoch
 * milliseconds (stored as primitives so it serializes without a custom time serializer).
 */
data class TaskTimeRange(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

/**
 * PRD §8/§9 task panel: one block on the calendar in the schedulable window. A panel is either
 * **auto** (scheduler-generated, [auto] = true — the §9 "task to do now" and the panels that follow
 * it out to +168h) or **user-authored** ([auto] = false — placed via the right-click "add a task"
 * action / edit window, or produced by pinning a record). Orthogonally, a panel is **pinned**
 * ([pinned] = true) when the user has locked it in the edit window: pinned panels survive a
 * reschedule and constrain it (the auto fill flows around them, PRD §9/§10), whereas non-pinned
 * panels are wiped and regenerated on every scheduling run.
 *
 * [taskId] is null for a calendar-only "New task" — creating one here intentionally does NOT create a
 * task in the tree (PRD §8); [title] is the shown label in either case. Like the task record, panels
 * are persisted user/scheduler data that lives outside the [org.example.project.scheduler.state.TreeSnapshot];
 * *manual* panel-list edits go through the [org.example.project.scheduler.state.HistoryCategory.Calendar]
 * stack (PRD §5/§8), while an automatic scheduling run (PRD §9) is derived from the state and recorded
 * in no history.
 */
/** PRD §8: the calendar unit a panel's position is confined to when "position pinned" (else [Exact]). */
enum class PositionGranularity { Exact, Hour, Day, Week, Month, Year }

/**
 * PRD §8 pin switches: the four independent ways a calendar panel can be pinned, edited in the panel's
 * edit window. [existence] keeps the occurrence; [position] (confined to [positionGranularity]) fixes
 * where it sits; [spanning] fixes its duration; [distance] fixes the gap to a reference occurrence
 * ([distanceRefTaskId] at [distanceRefStartMillis]). The scheduler's single fixed flag
 * ([TaskPanel.pinned]) is derived from these; in this pass only [existence] is enforced (the rest are
 * stored and shown, enforcement is a follow-up).
 */
data class PanelPins(
    val existence: Boolean = false,
    val position: Boolean = false,
    val positionGranularity: PositionGranularity = PositionGranularity.Exact,
    val spanning: Boolean = false,
    val distance: Boolean = false,
    val distanceRefTaskId: TaskId? = null,
    val distanceRefStartMillis: Long? = null,
)

data class TaskPanel(
    val id: String,
    val taskId: TaskId?,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    /**
     * PRD §9: the scheduler's "fixed" flag — a pinned panel survives + constrains a reschedule
     * ([org.example.project.scheduler.domain.SchedulerDomain.isSchedulerFixed]). Derived from [pins] at
     * save time (see the reducer's `derivePinned`); kept as its own field so the scheduler, the display
     * merge, and the chore logic that read it are unchanged.
     */
    val pinned: Boolean = false,
    /** True for scheduler-generated panels (the §9 auto fill); false for user-authored ones. */
    val auto: Boolean = false,
    /**
     * PRD §14 Reminders: true for a **zero-duration, checkable tag** generated by the reminder scheduler
     * (its [taskId] is null and `start == end`). A reminder has no spanning time, so — unlike the 5-minute
     * panels it replaced — it is NOT an obstacle to the §9 task scheduler ([SchedulerDomain.isSchedulerFixed]
     * excludes it; the auto fill flows straight through). It is kept across rescheduling all the same.
     */
    val chore: Boolean = false,
    /**
     * PRD §14 Reminders: whether the user has checked this reminder off (done). An unchecked reminder
     * whose scheduled time has passed is *overdue* and accumulates on the calendar's now-line
     * ([SchedulerDomain.overdueReminders]). Checking/un-checking is a Calendar History Unit (undoable).
     * Always false for non-reminder panels.
     */
    val checked: Boolean = false,
    /**
     * PRD §14 Reminders: the clock time (epoch millis) at which this reminder was checked off, or null
     * while unchecked. A checked reminder freezes on the calendar at this point on the timeline — it does
     * not snap back to its scheduled slot, nor does it track the live now-line. Always null for
     * non-reminder panels.
     */
    val checkedAtMillis: Long? = null,
    /**
     * PRD §15 Screen breaks: true for a periodic screen-break panel (real spanning time, null taskId). The §9
     * auto fill treats it as a fixed obstacle but, unlike a pinned panel, splitting a task around it does
     * not reduce that task's minimum time (the task resumes after and still gets its full minimum).
     * Regenerated deterministically on every fill (like auto panels), so it is not retained across one.
     */
    val screenBreak: Boolean = false,
    /**
     * The user's sleep window (see [SleepSchedule]): true for a generated nightly obstacle panel
     * (null taskId, title "Sleep"). Like a screen-break panel it is a fixed obstacle the §9 fill skips
     * (resuming after, no minimum charged) and the screen-break projection avoids; it is regenerated every
     * fill and rendered as a distinct block on the calendar.
     */
    val sleep: Boolean = false,
    /**
     * PRD §8/§9 no-screen period: true for a user-authored "No screen" panel (null taskId). It is NOT a
     * generic fixed obstacle — it *classifies* the timeline for the §9 fill: an on-screen task
     * ([Task.onScreen]) may not be placed inside it, an off-screen task may ONLY be placed inside one.
     * Authoritative (persisted + synced), kept across a fill, and trimmed/deleted automatically when the
     * user lays an on-screen task panel over it (and vice versa — see the reducer's screen-override
     * resolution). Its past portion banks NO task records (the app assumes nothing happened on screen).
     */
    val noScreen: Boolean = false,
    /**
     * PRD §8/§12 inactivity period: true for a user-authored "Inactivity" panel (null taskId) marking a
     * span the user was away from every device. Unlike [noScreen] it still counts as a *screen* period
     * (screen breaks keep their cadence through it) and it never constrains or trims the §9 fill — it is
     * a real panel recording a fact, rendered like the derived Inactivity bands but editable/removable.
     */
    val inactivity: Boolean = false,
    /**
     * PRD §8 Overlap Mode: this panel's relative horizontal weight within any time slice it shares with
     * other panels. Within a slice each active panel's width is `weight / Σ weights`, so equal weights
     * give every panel `1/n`. Only meaningful where panels overlap; a panel that is alone always fills
     * the column. Default 1.0.
     */
    val layoutWeight: Double = 1.0,
    /** PRD §8 pin switches: the four independent pin dimensions (see [PanelPins]); drives [pinned]. */
    val pins: PanelPins = PanelPins(),
)

/**
 * A UI cell that points to a [taskId] (or null for placeholders/empty cells).
 * Multiple cells can point to the same [taskId] across different parents/lists.
 */
data class Cell(
    val id: CellId,
    val parentListId: CellListId,
    val taskId: TaskId?,
    /**
     * PRD §5 Priority assignment: this cell's value in each of its sub-list's weight columns
     * (index-aligned with [CellList.weightColumns]). A missing/short entry is treated as 1.
     * Values may be any number ≥ 0 (0 is allowed); default 1.
     */
    val priorityWeights: List<Double> = listOf(1.0),
)

/**
 * PRD §5 the relative-priority window: the key a set of **pinned** cells is filed under — the task whose
 * relative priority is being edited, together with the ancestor task it is being measured against. Pinning
 * is per pair by design (the user's rule): a cell pinned while editing task A relative to root is not
 * pinned when the same window is opened for another task, or for A against another ancestor.
 */
data class RelativePriorityPinKey(val taskId: TaskId, val relativeTo: TaskId)

@JvmInline
value class CellListId(val value: String)

data class CellList(
    val id: CellListId,
    val parentCellId: CellId?, // null for the list rendered in the viewport (main's children)
    val cellIds: List<CellId>,
    /**
     * PRD §5 priority weight table: the nominal header weight of each priority column for this
     * sub-list. The absolute weight of column n is `header[n] * (1 - Σ preceding absolute weights)`.
     * Defaults to a single column of weight 1 (equivalent to a plain weighted split).
     */
    val weightColumns: List<Double> = listOf(1.0),
)
