package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.domain.TimerDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.model.TimerEntry

/**
 * PRD §18 Alarms and timers: a floating, draggable window in **two sections**.
 *
 * **Alarms** — one row each, with the time of day it rings, an optional label, **the days it is triggered
 * on** (every day by default), **how long the alarm sound lasts**, how it **announces itself** (PRD §11: the
 * four channels and the chosen sound), whether it repeats, and an on/off switch.
 *
 * **Timers** — one row each, with a duration, an optional label, the same ring length / alert block, a live
 * countdown and start / pause / reset. A timer is due at one absolute instant rather than at a wall-clock
 * time of day; everything after that instant — the arming, the sweep, the ring — is the alarms' own machinery
 * (see [TimerDomain]).
 *
 * Rows are edited live: every change pushes the parsed list up via [onChange] / [onTimersChange], which
 * persists and syncs it, so every device on the account rings at the new time, on the new days. The timer
 * **run-state** writes go through their own callbacks rather than through [onTimersChange], so editing a row's
 * settings while it counts down cannot disturb the instant it is due at — and, the other way round, the
 * countdown is three input fields (each with a right-click ± menu in its own unit) plus six ± second buttons
 * ([onSetTimerCountdownField] / [onNudgeTimerRemaining]) so the time left can be changed at any moment — before
 * the start as much as during it — without touching the row's settings.
 * Only one of those writes ever stops the countdown, and deliberately: typing into the **seconds**, the digit
 * that is itself reading down.
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 *
 * Given a [subject], the same window is the **per-object window of one alarm or one timer** (PRD §7 *Search*:
 * a right-click on its row): that row's editor alone — every setting it has, nothing to add. It is this window
 * rather than a second editor because a second copy of a row's fields, parsing and push rule is exactly the
 * copy that drifts; the rows it does not draw are still held and pushed back unchanged.
 */
@Composable
fun AlarmWindow(
    alarms: List<AlarmEntry>,
    /**
     * Persists + syncs the alarm list, and records it as a History Unit. The second argument is the
     * **field-focus session** a live text edit belongs to (null for a structural change — an added or removed
     * row, a switch, a weekday), which is what collapses the keystrokes of one field into one Ctrl+Z.
     */
    onChange: (List<AlarmEntry>, String?) -> Unit,
    /** PRD §18 Timers: the account's countdowns, including which of them are running. */
    timers: List<TimerEntry>,
    /** Persists + syncs the timer rows' settings (label, duration, ring length, vibration), as [onChange]. */
    onTimersChange: (List<TimerEntry>, String?) -> Unit,
    /** Start the timer with this id, or resume it from where a pause left it. */
    onStartTimer: (String) -> Unit,
    /** Hold the timer with this id where it is. */
    onPauseTimer: (String) -> Unit,
    /** Return the timer with this id to idle at its full duration. */
    onResetTimer: (String) -> Unit,
    /**
     * PRD §18 Timers: set one component of the timer's countdown. The finer components carry on reading down
     * through the edit; typing into the SECONDS is what pauses the row (see [TimerDomain.withCountdownField]).
     */
    onSetTimerCountdownField: (String, TimerDomain.TimerField, Int, TimerDomain.TimerCountdown?) -> Unit,
    /**
     * PRD §18 Timers: shift the timer's time left by this many millis, leaving it in the state it is in — the
     * ± second buttons, which are how the seconds move **without** stopping the countdown.
     */
    onNudgeTimerRemaining: (String, Long) -> Unit,
    /**
     * The current instant, read from the app clock (the **simulated** one under §16). Polled by this window
     * while a timer is running so the countdown reads down in real time: the engine's own now-line only
     * advances once per production tick (30 s), which is the schedule's cadence and not a countdown's.
     */
    nowMillis: () -> Long,
    onDismiss: () -> Unit,
    /**
     * PRD §5: Ctrl+Z / Ctrl+Y from inside this window. They are the app's own — the units this window
     * commits are on the Main stack — but the chord has to be caught here: it lives on the task tree's and
     * the calendar's key handlers, and neither of them can see a keystroke aimed at a floating window.
     */
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Initial size in px; `Size.Zero` opens the window at its default size. */
    initialSize: Size = Size.Zero,
    /** Persists the window's new position/size when a move or resize gesture ends (local-only geometry). */
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
    /** Time of day (minutes since midnight) to pre-fill a newly added row with — the current clock time. */
    newRowTimeOfDayMinutes: () -> Int = { 0 },
    /** The one alarm or timer this window is about, or null for the lateral-menu window listing them all. */
    subject: AlarmWindowSubject? = null,
) {
    val frame = rememberWindowFrameState(subject?.frameId ?: "Alarms", initialOffset, initialSize)
    // Per-row editable text for the parsed fields, so an in-progress "7:" / "" isn't reformatted on each
    // keystroke. Seeded from the incoming alarms; live edits drive both this and the pushed list.
    val rows = remember { mutableStateListOf<AlarmRow>().apply { addAll(alarms.map(::alarmRowOf)) } }
    // What this window last pushed. The local copy above is the truth for what the FIELDS show, so it cannot
    // simply follow [alarms] — every keystroke would be overwritten by the round-trip of its own push, and a
    // half-typed "7:" would be reformatted to "0:00" under the caret. But it must not ignore the list either:
    // an **undo** (or a peer's sync pull, or the engine disarming a one-off that rang) changes the account's
    // alarms without going through this window, and a local copy that never heard of it would go on showing
    // the row the user just struck off — and push it back at the next keystroke. So: re-seed exactly when the
    // incoming list is not the one this window last sent.
    var pushedAlarms by remember { mutableStateOf(alarms) }
    LaunchedEffect(alarms) {
        if (alarms != pushedAlarms) {
            pushedAlarms = alarms
            rows.clear()
            rows.addAll(alarms.map(::alarmRowOf))
        }
    }
    // The same, for the timers — their SETTINGS only. Whether a row is running is read live off [timers]
    // below, because it is moved by the start/pause/reset callbacks (and by a peer over sync), not by typing
    // here; keeping it in this local copy is what would let a keystroke overwrite a running countdown.
    val timerRows = remember { mutableStateListOf<TimerRow>().apply { addAll(timers.map(::timerRowOf)) } }
    // The alarms' re-seed rule, told against the SETTINGS only: the run state moves on its own (a start, a
    // pause, the ring that resets a row) and none of it is drawn from this copy, so re-seeding on it would
    // reformat a half-typed duration every time a countdown was started.
    var pushedTimerSettings by remember { mutableStateOf(timers.map(::timerRowOf)) }
    val timerSettings = timers.map(::timerRowOf)
    LaunchedEffect(timerSettings) {
        if (timerSettings != pushedTimerSettings) {
            pushedTimerSettings = timerSettings
            timerRows.clear()
            timerRows.addAll(timerSettings)
        }
    }

    // PRD §5: the field-focus session a live text edit belongs to. The window pushes its whole list on every
    // keystroke, so without this a five-letter label would be five History Units for Ctrl+Z to walk back one
    // character at a time. The epoch makes each visit to a field its own session, so leaving a field and
    // coming back to it is two units and not one.
    var editEpoch by remember { mutableStateOf(0) }
    var editKey by remember { mutableStateOf<String?>(null) }
    fun onFieldFocus(field: String, focused: Boolean) {
        if (focused) {
            editEpoch++
            editKey = field + "@" + editEpoch
        } else if (editKey?.substringBeforeLast('@') == field) {
            // Only if it is still this field's: Compose may report the gain before the loss when the focus
            // moves between two fields (the same rule the countdown's draft follows).
            editKey = null
        }
    }
    /** The key a live edit of [field] on [rowId] commits under, or null when that field does not hold it. */
    fun sessionKeyFor(rowId: String, field: String?): String? {
        if (field == null) return null
        return editKey?.takeIf { it.substringBeforeLast('@') == rowId + "/" + field }
    }

    // PRD §5/§8: this window owns the keyboard while it is the active surface, so its Ctrl+Z / Ctrl+Y reach
    // the Main stack its own units are on. The calendar's rule exactly, and for the reason the calendar has
    // it: focus is claimed when the window opens and **RECLAIMED ON EVERY PRESS INSIDE IT** (below, through
    // the same `raiseOnPress` that raises it). Claiming it once is not enough and the bin is the proof — a
    // press on a row's bin destroys the row, and with it whichever of its fields held the focus, so the
    // window would be left holding no focus at all and the very Ctrl+Z that undoes the deletion would reach
    // nobody. That is the bug this pair of lines exists to prevent; do not make either of them conditional.
    val windowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { windowFocus.requestFocus() } }

    // The countdown's own clock. The engine's now-line ticks once per production tick (30 s), which would
    // make a countdown jump in half-minutes, so this window polls the app clock itself — but only while a
    // timer is actually running, and only while the window is open. Display-only Compose state, like the
    // calendar's zoom: nothing here is persisted, synced or scheduled.

    var displayNowMillis by remember { mutableStateOf(nowMillis()) }
    val anyRunning = timers.any { it.running }
    LaunchedEffect(anyRunning) {
        displayNowMillis = nowMillis()
        while (anyRunning) {
            delay(COUNTDOWN_TICK_MILLIS)
            displayNowMillis = nowMillis()
        }
    }

    fun push(editKey: String? = null) {
        val entries =
            rows.map { row ->
                AlarmEntry(
                    id = row.id,
                    label = row.label,
                    // A half-typed time keeps the row alive at midnight rather than dropping the alarm; the
                    // field shows the error state until it parses.
                    timeOfDayMinutes = parseAlarmTime(row.timeText) ?: 0,
                    soundSeconds = parseSoundSeconds(row.soundText) ?: AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS,
                    alert = row.alert,
                    days = row.days,
                    repeats = row.repeats,
                    enabled = row.enabled,
                )
            }
        // Remember what went out, so the round-trip of this very push is not mistaken for an outside change.
        pushedAlarms = entries
        onChange(entries, editKey)
    }

    fun pushTimers(editKey: String? = null) {
        val entries =
            timerRows.map { row ->
                // Carry the run state through untouched: this push is about the settings, and the row may be
                // counting down while the user edits its label.
                val live = timers.firstOrNull { it.id == row.id }
                TimerEntry(
                    id = row.id,
                    label = row.label,
                    // A half-typed duration keeps the row alive at its default rather than dropping the
                    // timer; the field shows the error state until it parses.
                    durationSeconds = parseDurationSeconds(row.durationText) ?: TimerEntry.DEFAULT_TIMER_SECONDS,
                    soundSeconds = parseSoundSeconds(row.soundText) ?: AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS,
                    alert = row.alert,
                    endsAtMillis = live?.endsAtMillis,
                    remainingMillis = live?.remainingMillis,
                    runMillis = live?.runMillis,
                )
            }
        pushedTimerSettings = entries.map(::timerRowOf)
        onTimersChange(entries, editKey)
    }

    AppWindowFrame(
        title = subject?.title ?: "Alarms",
        state = frame,
        onClose = onDismiss,
        defaultWidth = 440.dp,
        defaultHeight = 560.dp,
        modifier = modifier
            .focusRequester(windowFocus)
            .focusable()
            // PRD §5: the window's own undo/redo, read by the app's ONE interpreter of those chords. A
            // *preview* handler, so the chord is caught whichever field inside holds the focus rather than
            // being swallowed by a text field. Everything else falls through untouched.
            .onPreviewKeyEvent { event ->
                when (undoRedoIntentFor(event)) {
                    SchedulerIntent.Undo -> onUndo()
                    SchedulerIntent.Redo -> onRedo()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
        // The frame raises the window on the Initial pass of every press inside it — and this reclaims the
        // keyboard with it, so a press that DESTROYS the focused node (the bin striking off the row whose
        // field was being typed into) still leaves the window holding the focus its Ctrl+Z needs. The
        // press is not consumed, so the field under it still takes the caret afterwards.
        onRaise = {
            onRaise()
            runCatching { windowFocus.requestFocus() }
        },
        onGeometryChange = onGeometryChange,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Follows the height the window was given (it is resizable), then scrolls — an account
                // may hold many alarms.
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (subject == null) SectionHeader("Alarms")
            if (subject == null && rows.isEmpty()) {
                Text(
                    text = "No alarm yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rows.forEachIndexed { index, row ->
                if (subject != null && (!subject.isAlarm || subject.id != row.id)) return@forEachIndexed
                AlarmRowEditor(
                    row = row,
                    onRowChange = { updated, field ->
                        rows[index] = updated
                        push(sessionKeyFor(row.id, field))
                    },
                    onRemove = {
                        rows.removeAt(index)
                        push()
                    },
                    onFieldFocus = { field, focused -> onFieldFocus(row.id + "/" + field, focused) },
                )
                if (subject == null && index != rows.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
            }

            if (subject == null) {
                Text(
                    text = "+ Add alarm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            rows.add(
                                AlarmRow(
                                    // A locally-unique id right away, so the row has an identity before the
                                    // round-trip through onChange (the reducer mints one for a blank id too).
                                    id = AlarmDomain.mintAlarmId(rows.map { it.id }),
                                    timeText = formatAlarmTime(newRowTimeOfDayMinutes()),
                                ),
                            )
                            push()
                        }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
                Text(
                    text = "Alarms ring on every phone signed in to this account.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // PRD §18 Timers: the second section. A timer is the same ring at a different kind of due
                // instant, which is why it lives in this window and not in one of its own.
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                SectionHeader("Timers")
            }
            if (subject == null && timerRows.isEmpty()) {
                Text(
                    text = "No timer yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            timerRows.forEachIndexed { index, row ->
                if (subject != null && (subject.isAlarm || subject.id != row.id)) return@forEachIndexed
                TimerRowEditor(
                    row = row,
                    // The live entry, which is where the run state lives; null only for the instant
                    // between adding a row and the push landing.
                    entry = timers.firstOrNull { it.id == row.id },
                    nowMillis = displayNowMillis,
                    onRowChange = { updated, field ->
                        timerRows[index] = updated
                        pushTimers(sessionKeyFor(row.id, field))
                    },
                    onStart = { onStartTimer(row.id) },
                    onPause = { onPauseTimer(row.id) },
                    onReset = { onResetTimer(row.id) },
                    onSetCountdownField = { field, value, held ->
                        onSetTimerCountdownField(row.id, field, value, held)
                    },
                    onNudge = { onNudgeTimerRemaining(row.id, it) },
                    // The timer's own window (PRD §7 Search) moves the time by the fields' right-click menu
                    // alone, and reads the run in reverse beside the countdown.
                    nudgeButtons = subject == null,
                    showElapsed = subject != null,
                    onRemove = {
                        timerRows.removeAt(index)
                        pushTimers()
                    },
                    onFieldFocus = { field, focused -> onFieldFocus(row.id + "/" + field, focused) },
                )
                if (subject == null && index != timerRows.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
            }

            if (subject == null) {
                Text(
                    text = "+ Add timer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            timerRows.add(
                                TimerRow(id = TimerDomain.mintTimerId(timerRows.map { it.id })),
                            )
                            pushTimers()
                        }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
                Text(
                    text = "A running timer belongs to the account, not to this device: every device rings " +
                        "when it ends.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The one alarm ([isAlarm]) or timer an [AlarmWindow] is about when it is a per-object window. One frame id
 * for all of them: at most one such window is open at a time, and asking for another replaces it
 * (`docs/invariants/popups.md`).
 */
data class AlarmWindowSubject(val id: String, val isAlarm: Boolean) {
    val title: String get() = if (isAlarm) "Alarm" else "Timer"
    val frameId: String get() = FRAME_ID

    companion object {
        const val FRAME_ID: String = "AlarmOrTimerEdit"
    }
}

/** The label above each of the window's two sections (PRD §18: alarms and timers). */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One editable alarm row: time + label on the first line, the days it is triggered on on the second, and
 * sound length / repeat on the third, and the alert block (PRD §11) on the fourth.
 */
@Composable
private fun AlarmRowEditor(
    row: AlarmRow,
    /**
     * The edited row, and the **text field** the edit came from — [FIELD_TIME], [FIELD_LABEL], [FIELD_SOUND]
     * — or null for a structural change (a switch, a weekday), which must never be absorbed into the History
     * Unit of the text edit before it.
     */
    onRowChange: (AlarmRow, String?) -> Unit,
    onRemove: () -> Unit,
    /** A text field of this row gained (true) or lost (false) the focus — one History Unit per session. */
    onFieldFocus: (String, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = row.enabled, onCheckedChange = { onRowChange(row.copy(enabled = it), null) })
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = row.timeText,
                onValueChange = { onRowChange(row.copy(timeText = it), FIELD_TIME) },
                singleLine = true,
                isError = parseAlarmTime(row.timeText) == null,
                modifier = Modifier.width(92.dp).editSession(FIELD_TIME, onFieldFocus),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = row.label,
                onValueChange = { onRowChange(row.copy(label = it), FIELD_LABEL) },
                singleLine = true,
                placeholder = { Text("Label", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f).editSession(FIELD_LABEL, onFieldFocus),
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text("🗑", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        // PRD §18: the days this alarm is triggered on — every day by default. Tapping a letter toggles that
        // weekday; the last selected one cannot be turned off, so an alarm always has a day to ring on (use
        // the on/off switch to silence it instead of emptying the week).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Days", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(2.dp))
            WEEK_DAYS.forEach { day ->
                val selected = day in row.days
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable {
                            val next = if (selected) row.days - day else row.days + day
                            if (next.isNotEmpty()) onRowChange(row.copy(days = next), null)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = dayInitial(day),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (row.days.size == 7) "Every day" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Rings for", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = row.soundText,
                onValueChange = { onRowChange(row.copy(soundText = it), FIELD_SOUND) },
                singleLine = true,
                isError = parseSoundSeconds(row.soundText) == null,
                modifier = Modifier.width(76.dp).editSession(FIELD_SOUND, onFieldFocus),
            )
            Spacer(Modifier.width(4.dp))
            Text(text = "s", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(12.dp))
            // Off = a one-off: it rings at the next of its days and then disarms itself.
            Text(text = "Repeat", style = MaterialTheme.typography.bodySmall)
            Switch(checked = row.repeats, onCheckedChange = { onRowChange(row.copy(repeats = it), null) })
        }
        // PRD §11: how this alarm makes itself heard. A structural change (no field key), so a chip pressed
        // while a text field still holds the focus is its own History Unit rather than part of that edit.
        AlertSettingsEditor(
            alert = row.alert,
            onChange = { onRowChange(row.copy(alert = it), null) },
        )
    }
}

/**
 * PRD §18 Timers: one editable timer row — duration + label on the first line, the countdown **field** and the
 * start/pause/reset controls on the second, sound length on the third and the alert block (PRD §11) on the
 * fourth.
 *
 * [entry] is the live state of this timer (null only in the instant between adding the row and the push
 * landing): it is what says whether the row is idle, running or paused, and the countdown is read off it and
 * [nowMillis] rather than stored anywhere.
 */
@Composable
private fun TimerRowEditor(
    row: TimerRow,
    entry: TimerEntry?,
    nowMillis: Long,
    /** [AlarmRowEditor]'s rule: the edited row, and the text field it came from (null for a switch). */
    onRowChange: (TimerRow, String?) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    /** A typed component, and the countdown the row held still while it was typed (see [CountdownDraft]). */
    onSetCountdownField: (TimerDomain.TimerField, Int, TimerDomain.TimerCountdown?) -> Unit,
    onNudge: (Long) -> Unit,
    onRemove: () -> Unit,
    onFieldFocus: (String, Boolean) -> Unit,
    /** The six ± second buttons under the countdown. The fields' right-click menu is there either way. */
    nudgeButtons: Boolean = true,
    /** The run in reverse — the elapsed time, going up as the countdown goes down. */
    showElapsed: Boolean = false,
) {
    val running = entry?.running == true
    val paused = entry?.paused == true
    // Before the first push lands there is no entry yet, so fall back to the typed duration.
    val remaining = entry?.remainingAtMillis(nowMillis)
        ?: ((parseDurationSeconds(row.durationText) ?: 0).toLong() * 1_000L)
    // The countdown as the three fields show it — TimerDomain's own split, so the readout and the value an
    // edit of that readout is measured against can never be two different numbers.
    val shown = TimerDomain.countdownOf(remaining)
    // PRD §18: what the user is half-way through typing, and into WHICH of the three fields — ONE draft for
    // the row, because only one field can hold the focus at a time. That is the whole of the rule: the value
    // shown is otherwise the LIVE countdown, which changes four times a second, so a field bound straight to
    // it could not be typed into at all — every tick would overwrite the keystroke. The draft is seeded on
    // focus and dropped on focus lost, at which point the field goes back to reading down. The fields NOT
    // holding it keep reading down throughout, which is exactly what "editing the hours does not stop the
    // minutes and seconds" looks like on screen. Display-only Compose state, like the window's own clock.
    var draft by remember(row.id) { mutableStateOf<CountdownDraft?>(null) }
    // The countdown is editable in all three states, the idle one included: setting up how long this run is
    // to be BEFORE pressing the button is the ordinary way to use a timer, and it is not the same question as
    // the Duration beside it (that one is the row's setting, what Reset goes back to and what a start from
    // idle takes). An idle row edited here is holding a countdown it has not started — which is a PAUSED row,
    // so the button below says Resume. The only thing that cannot be edited is a row whose entry has not
    // landed yet, in the instant between adding it and the push.
    val countdownEditable = entry != null

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = row.durationText,
                onValueChange = { onRowChange(row.copy(durationText = it), FIELD_DURATION) },
                singleLine = true,
                isError = parseDurationSeconds(row.durationText) == null,
                modifier = Modifier.width(92.dp).editSession(FIELD_DURATION, onFieldFocus),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = row.label,
                onValueChange = { onRowChange(row.copy(label = it), FIELD_LABEL) },
                singleLine = true,
                placeholder = { Text("Label", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f).editSession(FIELD_LABEL, onFieldFocus),
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text("🗑", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The countdown, as three INPUTS: derived from the end instant and the now-line, never stored, but
            // writable at any moment — before the start as much as during it. Each field moves the countdown
            // by its OWN unit, so typing into the hours leaves the minutes and seconds reading down
            // underneath it. Emphasised while it is actually running so a held row reads as held rather
            // than stuck.
            TimerDomain.TimerField.entries.forEachIndexed { index, field ->
                if (index != 0) {
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (running) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                CountdownField(
                    field = field,
                    live = shown,
                    // The hours read as a bare number; the two under a coarser component are padded, so the
                    // three fields spell the H:MM:SS the rest of the app prints.
                    pad = if (field == TimerDomain.TimerField.HOURS) 1 else 2,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onCommit = { value, held -> onSetCountdownField(field, value, held) },
                    onNudge = onNudge,
                    editable = countdownEditable,
                    running = running,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (running) {
                TimerActionChip(text = "Pause", onClick = onPause)
            } else {
                // Resuming and starting are the same button: one continues from what a pause banked, the
                // other from the full duration, and TimerDomain.started is what tells them apart. A countdown
                // edited before the start banks exactly that way, so it reads Resume from then on — the row
                // is no longer at its duration, and saying Start would be saying the duration is what runs.
                TimerActionChip(
                    text = if (paused) "Resume" else "Start",
                    enabled = paused || parseDurationSeconds(row.durationText) != null,
                    onClick = onStart,
                )
            }
            Spacer(Modifier.width(6.dp))
            TimerActionChip(text = "Reset", enabled = running || paused, onClick = onReset)
        }
        // PRD §18: the seconds, moved WITHOUT stopping the countdown. Typing into the seconds field cannot do
        // that — the digit is itself reading down, so a typed value only sticks if the row stops — which is
        // precisely why these sit beside it. They work before the start too, where there is nothing to stop:
        // they are then simply how the run about to be started is dialled in a few seconds at a time.
        if (showElapsed) {
            // PRD §7 Search, the timer's own window: the countdown in reverse. Read-only, and a mirror of the
            // countdown AS SHOWN: while a field holds the caret the fields it holds stand still, and so does this
            // — or it would run on beside a countdown that reads as stopped. The field being edited counts with
            // what is typed in it once that parses.
            val editing = draft
            val shownCountdown =
                TimerDomain.displayedCountdown(shown, editing?.held, editing?.field).let { displayed ->
                    val typed = editing?.let { parseCountdownComponent(it.text, it.field) }
                    if (editing != null && typed != null) displayed.with(editing.field, typed) else displayed
                }
            val elapsed = entry?.let { TimerDomain.elapsedMillis(it, shownCountdown) } ?: 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Elapsed", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = (if (elapsed < 0L) "−" else "") + TimerDomain.formatCountdown(abs(elapsed)),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        textAlign = TextAlign.Center,
                        color =
                            if (running) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.width(120.dp),
                )
            }
        }
        if (nudgeButtons) Row(verticalAlignment = Alignment.CenterVertically) {
            NUDGE_SECONDS.forEachIndexed { index, seconds ->
                if (index != 0) Spacer(Modifier.width(4.dp))
                TimerActionChip(
                    text = if (seconds > 0) "+" + seconds + "s" else seconds.toString() + "s",
                    enabled = countdownEditable,
                    onClick = { onNudge(seconds.toLong() * 1_000L) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Rings for", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = row.soundText,
                onValueChange = { onRowChange(row.copy(soundText = it), FIELD_SOUND) },
                singleLine = true,
                isError = parseSoundSeconds(row.soundText) == null,
                modifier = Modifier.width(76.dp).editSession(FIELD_SOUND, onFieldFocus),
            )
            Spacer(Modifier.width(4.dp))
            Text(text = "s", style = MaterialTheme.typography.bodySmall)
        }
        // PRD §11: the alarms' own block, unchanged — a timer rings exactly like an alarm.
        AlertSettingsEditor(
            alert = row.alert,
            onChange = { onRowChange(row.copy(alert = it), null) },
        )
    }
}

/**
 * PRD §5: report this field's focus, so every visit to it is one **edit session** and therefore one History
 * Unit however many characters are typed into it. The names are the window's own and only ever travel with a
 * row id in front of them.
 */
private fun Modifier.editSession(field: String, onFieldFocus: (String, Boolean) -> Unit): Modifier =
    this.onFocusChanged { onFieldFocus(field, it.isFocused) }

private const val FIELD_TIME = "time"
private const val FIELD_LABEL = "label"
private const val FIELD_SOUND = "sound"
private const val FIELD_DURATION = "duration"

/** The editable text row an alarm shows as — the seeding both the first composition and a re-seed use. */
private fun alarmRowOf(entry: AlarmEntry): AlarmRow =
    AlarmRow(
        id = entry.id,
        timeText = formatAlarmTime(entry.timeOfDayMinutes),
        label = entry.label,
        soundText = entry.soundSeconds.toString(),
        alert = entry.alert,
        days = entry.days,
        repeats = entry.repeats,
        enabled = entry.enabled,
    )

/**
 * The editable text row a timer shows as — its **settings** only, which is also what says whether an incoming
 * list differs from this window's own copy in a way the fields must follow (the run state never does).
 */
private fun timerRowOf(entry: TimerEntry): TimerRow =
    TimerRow(
        id = entry.id,
        durationText = formatDuration(entry.durationSeconds),
        label = entry.label,
        soundText = entry.soundSeconds.toString(),
        alert = entry.alert,
    )

/**
 * PRD §18 Timers: what the user is typing into the countdown — into WHICH field, and the countdown as it
 * stood when that field took the caret ([held]). ONE per row, because only one field can hold the focus.
 *
 * While it exists, the field it names and **every field to its left** show [held] rather than the live
 * countdown: a number ticking down under the cursor cannot be typed into, and neither can one whose coarser
 * neighbour changes while it is edited (the seconds wrapping would otherwise take a minute off the minutes
 * beside the caret). The fields to its RIGHT go on reading down. The commit is measured against [held]
 * ([TimerDomain.withCountdownField]), so what lands is what the user saw.
 */
private data class CountdownDraft(
    val field: TimerDomain.TimerField,
    val text: String,
    val held: TimerDomain.TimerCountdown,
)

/**
 * PRD §18 Timers: one of the three countdown components, as a field.
 *
 * It shows the [draft]'s text when the draft is its own, the draft's held value when the draft belongs to a
 * finer field (see [CountdownDraft]), and the LIVE value otherwise. The draft is seeded on focus and dropped
 * on focus lost — but only if it is still this field's, since Compose may report the gain before the loss when
 * the focus moves between two of them. Each keystroke that parses commits, like every other field in this
 * window; one that does not shows the error state until it does, so a half-typed value never reaches the
 * state.
 *
 * A **right-click** opens a menu that adds or takes away this field's OWN unit — seconds on the seconds,
 * minutes on the minutes, hours on the hours — through [onNudge], which moves the time left without
 * changing whether the timer runs. It replaces the text field's own cut/copy/paste menu, which has nothing
 * to offer a two-digit number.
 */
@Composable
private fun CountdownField(
    field: TimerDomain.TimerField,
    live: TimerDomain.TimerCountdown,
    pad: Int,
    draft: CountdownDraft?,
    onDraftChange: (CountdownDraft?) -> Unit,
    onCommit: (Int, TimerDomain.TimerCountdown) -> Unit,
    onNudge: (Long) -> Unit,
    editable: Boolean,
    running: Boolean,
) {
    fun padded(value: Int) = value.toString().padStart(pad, '0')
    val own = draft?.takeIf { it.field == field }
    // The one reading of "what the countdown shows while a field is edited" — the Elapsed field mirrors it too.
    val shownText =
        own?.text ?: padded(TimerDomain.displayedCountdown(live, draft?.held, draft?.field).component(field))
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = shownText,
            onValueChange = { text ->
                val held = own?.held ?: live
                onDraftChange(CountdownDraft(field, text, held))
                parseCountdownComponent(text, field)?.let { onCommit(it, held) }
            },
            readOnly = !editable,
            singleLine = true,
            isError = own?.let { parseCountdownComponent(it.text, field) == null } == true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                textAlign = TextAlign.Center,
                color =
                    if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier
                .width(56.dp)
                // Initial pass, and consumed: the right-click is this menu's, never the text field's own.
                .pointerInput(editable) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                if (editable) menuOpen = true
                            }
                        }
                    }
                }
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        // Held from here on: this field and every one to its left stop reading down.
                        onDraftChange(CountdownDraft(field, padded(live.component(field)), live))
                    } else if (draft?.field == field) {
                        onDraftChange(null)
                    }
                },
        )
        transientMenuDismissal(menuOpen) { menuOpen = false }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            properties = PopupProperties(focusable = false),
        ) {
            NUDGE_STEPS.forEach { step ->
                DropdownMenuItem(
                    text = { Text((if (step > 0) "+" else "−") + abs(step) + " " + unitLabel(field)) },
                    onClick = {
                        menuOpen = false
                        onNudge(step * field.unitMillis)
                    },
                )
            }
        }
    }
}

private fun unitLabel(field: TimerDomain.TimerField): String =
    when (field) {
        TimerDomain.TimerField.HOURS -> "h"
        TimerDomain.TimerField.MINUTES -> "min"
        TimerDomain.TimerField.SECONDS -> "s"
    }

/** A countdown field's right-click menu, top to bottom — in that field's own unit. */
private val NUDGE_STEPS: List<Long> = listOf(10L, 5L, 1L, -1L, -5L, -10L)

/** A small outlined text button for a timer's start/pause/reset, in the window's own flat idiom. */
@Composable
private fun TimerActionChip(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val color =
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** The in-window editing shape of one alarm (text fields kept raw so typing isn't reformatted). */
private data class AlarmRow(
    val id: String,
    val timeText: String = "",
    val label: String = "",
    val soundText: String = AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS.toString(),
    /** PRD §11: the four channels and the chosen sound, edited by the shared [AlertSettingsEditor]. */
    val alert: AlertSettings = AlertSettings.RING,
    val days: Set<DayOfWeek> = AlarmEntry.EVERY_DAY,
    val repeats: Boolean = true,
    val enabled: Boolean = true,
)

/**
 * The in-window editing shape of one timer's **settings** (text fields kept raw so typing isn't reformatted).
 * Deliberately holds no run state: that lives on the [TimerEntry] and is moved only by the three transitions.
 */
private data class TimerRow(
    val id: String,
    val durationText: String = formatDuration(TimerEntry.DEFAULT_TIMER_SECONDS),
    val label: String = "",
    val soundText: String = AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS.toString(),
    /** PRD §11: an alarm row's own field, with an alarm row's meaning. */
    val alert: AlertSettings = AlertSettings.RING,
)

/**
 * How often the window re-reads the clock while a timer counts down. Fast enough that the seconds readout
 * does not visibly stutter, and it runs only while this window is open **and** something is running.
 */
private const val COUNTDOWN_TICK_MILLIS: Long = 250L

/** Monday-first, matching the calendar's week (PRD §8). */
private val WEEK_DAYS: List<DayOfWeek> = DayOfWeek.entries.sortedBy { it.isoDayNumber }

/** The one-letter chip label for [day] (English initials; Tuesday/Thursday and Saturday/Sunday collide). */
private fun dayInitial(day: DayOfWeek): String =
    when (day) {
        DayOfWeek.MONDAY -> "M"
        DayOfWeek.TUESDAY -> "T"
        DayOfWeek.WEDNESDAY -> "W"
        DayOfWeek.THURSDAY -> "T"
        DayOfWeek.FRIDAY -> "F"
        DayOfWeek.SATURDAY -> "S"
        else -> "S"
    }

private fun formatAlarmTime(minutes: Int): String {
    val m = ((minutes % AlarmEntry.MINUTES_PER_DAY) + AlarmEntry.MINUTES_PER_DAY) % AlarmEntry.MINUTES_PER_DAY
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

/** Parses `H:MM` / `HH:MM` (00:00..23:59) to minutes since midnight, or null when it isn't a valid time. */
private fun parseAlarmTime(text: String): Int? {
    val parts = text.split(":")
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** Parses how long the alarm rings, in seconds (1..[AlarmEntry.MAX_ALARM_SOUND_SECONDS]). */
private fun parseSoundSeconds(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf { it in 1..AlarmEntry.MAX_ALARM_SOUND_SECONDS }

/**
 * PRD §18 Timers: parses a countdown length written as `SS`, `M:SS` or `H:MM:SS` into seconds, or null when
 * it is not a duration in `1..`[TimerEntry.MAX_TIMER_SECONDS]. The minutes/seconds fields are bounded at 59
 * so a typed `5:70` is an error rather than silently 6:10 — the leading field alone may run over (`90:00` is
 * an hour and a half).
 */
private fun parseDurationSeconds(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size !in 1..3) return null
    val fields = parts.map { it.trim().toIntOrNull() ?: return null }
    if (fields.any { it < 0 }) return null
    if (fields.drop(1).any { it > 59 }) return null
    val seconds = when (fields.size) {
        1 -> fields[0]
        2 -> fields[0] * 60 + fields[1]
        else -> fields[0] * 3600 + fields[1] * 60 + fields[2]
    }
    return seconds.takeIf { it in 1..TimerEntry.MAX_TIMER_SECONDS }
}

/**
 * PRD §18 Timers: parses one of the three countdown fields — a plain non-negative number, bounded by what its
 * component can hold (hours up to [TimerEntry.MAX_TIMER_SECONDS]'s 24, minutes and seconds at 59). Null while
 * it is blank or out of range, which is what holds the commit back and shows the error state.
 *
 * Deliberately NOT [parseDurationSeconds]: that one reads a whole `H:MM:SS` written into a single field, and
 * is still what the row's **Duration** setting uses.
 */
private fun parseCountdownComponent(text: String, field: TimerDomain.TimerField): Int? {
    val n = text.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return null
    val max = if (field == TimerDomain.TimerField.HOURS) TimerEntry.MAX_TIMER_SECONDS / 3600 else 59
    return n.takeIf { it in 0..max }
}

/**
 * PRD §18 Timers: the ± buttons under the countdown, in the order they are drawn. They move the seconds
 * **without** stopping the timer — the one thing typing into the seconds field cannot do.
 */
private val NUDGE_SECONDS = listOf(-10, -5, -1, 1, 5, 10)

/**
 * A duration in seconds as `M:SS`, or `H:MM:SS` once it reaches an hour. Both of these are [TimerDomain]'s,
 * because the calendar marker names a nameless timer by its duration too — one spelling, so the window and
 * the marker can never disagree about how long a timer is.
 */
private fun formatDuration(seconds: Int): String = TimerDomain.formatDuration(seconds)

