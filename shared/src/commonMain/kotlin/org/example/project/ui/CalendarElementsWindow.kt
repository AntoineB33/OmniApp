package org.example.project.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.AlarmDomain
import org.example.project.scheduler.domain.CalendarElements
import org.example.project.scheduler.domain.CalendarElements.sharedValue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.NewElementDefaults
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.TaskId

/**
 * PRD §8: **the ONE window the calendar's "add…" and "edit…" entries open** — a set of things to put on the
 * timeline, or the set already at a point on it, with their configuration asked once per group that shares
 * it.
 *
 * The three sections are the user's own:
 *  1. **the selection** — a search bar, a kind, an *add* button, and the list of what has been chosen;
 *  2. **what every element answers the same way**, asked once (the `Begins` of three things laid together);
 *  3. **everything else**, one section per set of elements that share it, titled by their names and kinds.
 *
 * Sections 2 and 3 are not written here: they fall out of
 * [CalendarElements.calendarConfigSections], which partitions the fields by who owns them, so a kind given a
 * new field grows a row in the right place with no change to this file. Which section a field lands in is
 * therefore a consequence of what is in the list, and the **one-element case needs no branch** — every field
 * is then shared by all, the window is one untitled section, and it reads exactly like the single-object
 * editor it replaces.
 *
 * **Nothing is placed until Save**, as it always was — but the window is now the placement path rather than
 * a router to three of them, so its Save is where "one Save is one Ctrl+Z" is owed
 * ([org.example.project.scheduler.state.SchedulerIntent.AddCalendarElements], which says what it costs when
 * an alarm is in the list).
 */
@Composable
fun CalendarElementsWindow(
    /** What the window is for: the two entries differ in this and in [candidates] alone. */
    mode: CalendarElementsMode,
    /** The right-click instant — where a newly added element begins. */
    atMillis: Long,
    nowMillis: Long,
    tz: TimeZone,
    /**
     * PRD §8 **"edit…"**: the elements at the mouse, which are both the window's starting list AND the only
     * things its search bar will offer. Empty when adding, where the search may reach anything the account
     * has.
     */
    candidates: List<CalendarElements.Draft>,
    /** Every kind a period can be OF (`state.allPeriodKinds`). */
    periodKinds: List<String>,
    onCreatePeriodKind: (String) -> Unit,
    taskMenuEntries: (draftText: String, excludeTaskId: TaskId?) -> List<SchedulerDomain.ChangeTaskMenuEntry>,
    taskTitleSuggestions: (String) -> List<String>,
    taskIdForTitle: (String) -> TaskId?,
    titleForTaskId: (TaskId) -> String?,
    noScreenResilienceForTaskId: (TaskId) -> Double?,
    /**
     * PRD §8 Manual add: **the task a blank search means** — the highest-priority placeable leaf
     * (`SchedulerDomain.manualAddTaskId`). Without it "add a task panel here" would need a name typed before
     * the button would light, and the one thing the old router did for free would have been lost.
     */
    defaultTaskId: () -> TaskId?,
    /** The minimum-time span a fresh panel of this task gets, in millis — the manual-add default. */
    panelSpanMillisFor: (TaskId?) -> Long,
    reminderMenuEntries: (draftText: String) -> List<SchedulerDomain.ReminderMenuEntry>,
    reminderTitleSuggestions: (String) -> List<String>,
    reminderIdForTitle: (String) -> String?,
    /** The account's alarms — their labels are what the alarm search suggests. */
    alarms: List<AlarmEntry>,
    /** PRD §18: what a new alarm starts with — the account's default configuration ([NewElementDefaults]). */
    newAlarm: AlarmEntry = NewElementDefaults.ALARM,
    onSave: (List<CalendarElements.Draft>) -> Unit,
    /** PRD §8: the bin, per row of the list — see [EditorBinButton] for why deleting travels with editing. */
    onRemove: (CalendarElements.Draft) -> Unit,
    onDismiss: () -> Unit,
) {
    var drafts by remember(candidates) { mutableStateOf(if (mode.seedsFromCandidates) candidates else emptyList()) }
    var search by remember { mutableStateOf("") }
    var kind by remember(candidates) {
        mutableStateOf(mode.initialKind(candidates))
    }
    var kindMenuOpen by remember { mutableStateOf(false) }
    // A half-typed instant belongs to the SECTION that is asking for it, not to an element: the field is one
    // question however many elements answer it. Keyed by the section's owner set so adding an unrelated
    // element below does not lose a character (see [CalendarElements.ConfigSection.key]).
    val fieldText = remember { mutableStateMapOf<String, String>() }
    val frame = rememberWindowFrameState("CalendarElements")

    val offeredKinds = mode.offeredKinds(candidates)
    val pick =
        rememberSelection(
            kind, search, candidates, drafts, mode,
            taskIdForTitle, reminderIdForTitle, titleForTaskId, defaultTaskId, periodKinds,
        )
    val sections = CalendarElements.calendarConfigSections(drafts)

    TransientPopupLayer(frame.id) {
        AppWindowFrame(
            title = mode.title + " at " + formatHm(atMillis, tz),
            state = frame,
            onClose = onDismiss,
            defaultWidth = 400.dp,
            defaultHeight = 640.dp,
            claimsKeyboard = true,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // --- 1. What to put here -------------------------------------------------------------
                EditMenuSectionLabel(mode.selectionLabel)
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(kind.label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The identity and title menus are the SAME block every naming field in the app uses — a
                // task cell's, the reminder editor's — read for whichever family the kind names. The rows
                // differ; the control does not.
                SelectionMenus(
                    kind = kind,
                    search = search,
                    mode = mode,
                    candidates = candidates,
                    taskMenuEntries = taskMenuEntries,
                    taskTitleSuggestions = taskTitleSuggestions,
                    titleForTaskId = titleForTaskId,
                    reminderMenuEntries = reminderMenuEntries,
                    reminderTitleSuggestions = reminderTitleSuggestions,
                    alarms = alarms,
                    onSearchChange = { search = it },
                )
                // The kind field of a RESTRICTIVE PERIOD is its identity, so it belongs in this section and
                // not in the configuration below — the same control the task cell's categories field is.
                if (kind == CalendarElements.Kind.RestrictivePeriod && mode == CalendarElementsMode.Add) {
                    PeriodKindField(
                        kind = search,
                        periodKinds = periodKinds,
                        onPick = { search = it },
                        onCreate = { newKind ->
                            onCreatePeriodKind(newKind)
                            search = PeriodKinds.normalize(newKind)
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, CalColors.grid, RoundedCornerShape(4.dp))
                                .menuToggleClickable(kindMenuOpen) { kindMenuOpen = it }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            text = kind.label + "  ▾",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        DropdownMenu(expanded = kindMenuOpen, onDismissRequest = { kindMenuOpen = false }) {
                            offeredKinds.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        kind = option
                                        kindMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Button(
                        // "Greyed if no element is selected": for a task and a reminder a typed name IS a
                        // selection (a new one), for a period the kind must name something, and for an
                        // alarm a label is optional — so the one question is whether the pick resolved.
                        enabled = pick != null,
                        onClick = {
                            pick?.let { chosen ->
                                drafts = drafts + seedDraft(chosen, atMillis, panelSpanMillisFor, noScreenResilienceForTaskId, newAlarm)
                                search = ""
                            }
                        },
                    ) { Text("Add") }
                }


                ElementList(
                    drafts = drafts,
                    onRemoveRow = { index ->
                        drafts.getOrNull(index)?.let { row -> if (row.existingId != null) onRemove(row) }
                        drafts = drafts.filterIndexed { i, _ -> i != index }
                    },
                )

                // --- 2 & 3. The configuration, grouped by who shares it -------------------------------
                sections.forEach { section ->
                    if (!section.sharedByAll) {
                        EditMenuSectionLabel(section.title)
                    }
                    section.fields.forEach { field ->
                        ConfigFieldRow(
                            field = field,
                            section = section,
                            nowMillis = nowMillis,
                            tz = tz,
                            fieldText = fieldText,
                            onEdit = { edit -> drafts = CalendarElements.applyToSection(drafts, section, edit) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = drafts.isNotEmpty(),
                        onClick = { onSave(drafts) },
                    ) { Text("Save") }
                }
            }
        }
    }
}

/**
 * PRD §8: **which of the two menu entries opened the window.** The two differ in exactly two things — what
 * the search bar may reach, and whether the list starts full — so they are one window and a mode, not two
 * windows that would drift.
 */
enum class CalendarElementsMode(
    val title: String,
    val selectionLabel: String,
    /** "edit…" starts with the elements at the mouse already in the list; "add…" starts empty. */
    val seedsFromCandidates: Boolean,
) {
    Add("Add", "What to put here", false),

    /**
     * PRD §8: *"in the selection section the only suggested and selectable elements are the ones that are at
     * the location of the mouse"* — so a window opened by "edit…" can never reach past what was right-clicked,
     * and an element struck off its list can be put back without reopening it.
     */
    Edit("Edit", "What is here", true),
    ;

    /** The kinds the drop-down offers: all four when adding, the kinds actually present when editing. */
    fun offeredKinds(candidates: List<CalendarElements.Draft>): List<CalendarElements.Kind> =
        when (this) {
            Add -> CalendarElements.Kind.entries.toList()
            Edit -> CalendarElements.Kind.entries.filter { k -> candidates.any { it.kind == k } }
        }

    /** What the drop-down starts on — the first kind it offers, so it is never showing an empty family. */
    fun initialKind(candidates: List<CalendarElements.Draft>): CalendarElements.Kind =
        offeredKinds(candidates).firstOrNull() ?: CalendarElements.Kind.TaskPanel
}

/**
 * PRD §8: **what the *add* button would add** — `null` when nothing is selected, which is the whole of the
 * button's greying rule.
 *
 * In **edit** mode the answer can only be one of the elements at the mouse, matched by name; in **add** mode
 * a typed name that matches nothing is a NEW element of the chosen kind, except for a restrictive period,
 * where the name must be a kind the account holds (`periodKindNamed`) — a period is *of* a kind, so a
 * spelling that names none is not a period the window can lay.
 */
@Composable
private fun rememberSelection(
    kind: CalendarElements.Kind,
    search: String,
    candidates: List<CalendarElements.Draft>,
    /** What is already in the window's list — an element cannot be added to it twice. */
    chosen: List<CalendarElements.Draft>,
    mode: CalendarElementsMode,
    taskIdForTitle: (String) -> TaskId?,
    reminderIdForTitle: (String) -> String?,
    titleForTaskId: (TaskId) -> String?,
    defaultTaskId: () -> TaskId?,
    periodKinds: List<String>,
): CalendarElements.Draft? {
    val typed = search.trim()
    if (mode == CalendarElementsMode.Edit) {
        // "edit…" starts with every element at the mouse already listed, so the *add* button is only ever
        // about putting BACK one the user struck off — which is why the offer excludes what is in the list.
        val free = candidates.filterNot { row -> chosen.any { it.existingId == row.existingId } }
        return free.firstOrNull { it.kind == kind && it.displayName.equals(typed, ignoreCase = true) }
            ?: free.firstOrNull { it.kind == kind && typed.isBlank() }
    }
    return when (kind) {
        // PRD §8 Manual add: a blank search is the DEFAULT task, not "nothing selected" — the highest-priority
        // placeable leaf, which is what the router that came before pre-filled its editor with.
        CalendarElements.Kind.TaskPanel ->
            if (typed.isBlank()) {
                defaultTaskId()?.let { id ->
                    CalendarElements.Draft(kind = kind, name = titleForTaskId(id).orEmpty(), taskId = id)
                }
            } else {
                CalendarElements.Draft(kind = kind, name = typed, taskId = taskIdForTitle(typed))
            }
        CalendarElements.Kind.RestrictivePeriod ->
            periodKindNamed(typed, periodKinds)
                .takeIf { it.isNotBlank() }
                ?.let { CalendarElements.Draft(kind = kind, name = PeriodKinds.periodTitle(it), periodKind = it) }
        CalendarElements.Kind.Reminder ->
            if (typed.isBlank()) null
            else CalendarElements.Draft(kind = kind, name = typed, reminderId = reminderIdForTitle(typed).orEmpty())
        // PRD §18: an alarm has no name it must be given, so a blank search still selects one — "add an
        // alarm here" is a complete sentence. What it may NOT do is name an existing alarm: an alarm's
        // occurrences are generated by its weekdays, so "add alarm-3 here" is an edit of alarm-3's rule, and
        // that is what the "edit…" window is for.
        CalendarElements.Kind.Alarm -> CalendarElements.Draft(kind = kind, name = typed)
    }
}

/** A freshly picked element given the bounds it starts life with, anchored at the right-click instant. */
private fun seedDraft(
    pick: CalendarElements.Draft,
    atMillis: Long,
    panelSpanMillisFor: (TaskId?) -> Long,
    noScreenResilienceForTaskId: (TaskId) -> Double?,
    newAlarm: AlarmEntry,
): CalendarElements.Draft =
    // An element already on the calendar (the "edit…" list) keeps its own bounds: the window is showing what
    // is there, and re-anchoring it at the click would move it before the user asked for anything.
    if (pick.existingId != null) {
        pick
    } else {
        pick.copy(
            startMillis = atMillis,
            endMillis = atMillis + when (pick.kind) {
                // PRD §8 Manual add: the task's own minimum time.
                CalendarElements.Kind.TaskPanel -> panelSpanMillisFor(pick.taskId)
                CalendarElements.Kind.RestrictivePeriod -> 3_600_000L
                // PRD §18: the default ring length, so `end - start` is a real `soundSeconds` from the start.
                CalendarElements.Kind.Alarm -> newAlarm.soundSeconds * 1000L
                // PRD §14: a tag has no duration at all.
                CalendarElements.Kind.Reminder -> 0L
            },
            noScreenResilience = pick.taskId?.let(noScreenResilienceForTaskId) ?: 0.0,
        ).let { seeded ->
            // PRD §18: a new alarm's own settings are the account's default configuration of one.
            if (pick.kind != CalendarElements.Kind.Alarm) seeded
            else seeded.copy(alarmDays = newAlarm.days, alert = newAlarm.alert, alarmArmed = newAlarm.enabled)
        }
    }

/**
 * PRD §8 §1d: **the list of what the window will lay**, each row with the bin that gets rid of it
 * ([EditorBinButton]'s rule, per row rather than per window — the window names several things, so one bin
 * could not say which).
 */
@Composable
private fun ElementList(drafts: List<CalendarElements.Draft>, onRemoveRow: (Int) -> Unit) {
    if (drafts.isEmpty()) {
        Text(
            text = "Nothing chosen yet.",
            style = MaterialTheme.typography.labelSmall,
            color = CalColors.muted,
        )
        return
    }
    drafts.forEachIndexed { index, draft ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, CalColors.grid, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = draft.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = draft.kind.label + if (draft.existingId == null) " · new" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = CalColors.muted,
                )
            }
            EditorBinButton { onRemoveRow(index) }
        }
    }
}

/**
 * **One configuration, asked once of everything in [section].** The renderer for a [CalendarElements.Field],
 * and the one place a field's control lives — the grouping decided WHO is being asked, this decides WHAT the
 * control is.
 *
 * A value the section's elements disagree about shows blank ([CalendarElements.sharedValue] returns null),
 * and an untouched field writes nothing because each draft still holds its own: the only thing that copies a
 * value across a section is an edit here.
 */
@Composable
private fun ConfigFieldRow(
    field: CalendarElements.Field,
    section: CalendarElements.ConfigSection,
    nowMillis: Long,
    tz: TimeZone,
    fieldText: MutableMap<String, String>,
    onEdit: ((CalendarElements.Draft) -> CalendarElements.Draft) -> Unit,
) {
    when (field) {
        CalendarElements.Field.Start ->
            BoundRow(
                label = field.label,
                infiniteLabel = "∞ (always)",
                section = section,
                nowMillis = nowMillis,
                tz = tz,
                fieldText = fieldText,
                readBound = { it.startBound },
                readMillis = { it.startMillis },
                onEdit = { bound, millis ->
                    onEdit { draft -> draft.copy(startBound = bound, startMillis = millis ?: draft.startMillis) }
                },
            )
        CalendarElements.Field.End ->
            BoundRow(
                label = field.label,
                infiniteLabel = "∞ (never)",
                section = section,
                nowMillis = nowMillis,
                tz = tz,
                fieldText = fieldText,
                readBound = { it.endBound },
                readMillis = { it.endMillis },
                onEdit = { bound, millis ->
                    onEdit { draft -> draft.copy(endBound = bound, endMillis = millis ?: draft.endMillis) }
                },
            )
        CalendarElements.Field.Pins -> {
            EditMenuSectionLabel(field.label)
            PinSwitchRow("Existence", section.sharedValue { it.pins.existence } == true) { on ->
                onEdit { it.copy(pins = it.pins.copy(existence = on)) }
            }
            PinSwitchRow("Position", section.sharedValue { it.pins.position } == true) { on ->
                onEdit { it.copy(pins = it.pins.copy(position = on)) }
            }
            PinSwitchRow("Spanning", section.sharedValue { it.pins.spanning } == true) { on ->
                onEdit { it.copy(pins = it.pins.copy(spanning = on)) }
            }
            PinSwitchRow("Distance", section.sharedValue { it.pins.distance } == true) { on ->
                onEdit { it.copy(pins = it.pins.copy(distance = on)) }
            }
        }
        CalendarElements.Field.Screen -> {
            // `side-dev/README.md`: the task's resilience to "no screen", shown as the switch it usually is.
            // Hidden where no element of the section has a task to carry it — a calendar-only "New task" has
            // no task object, so there is nothing to give a resilience to.
            if (section.drafts.none { it.taskId != null }) return
            EditMenuSectionLabel(field.label)
            PinSwitchRow("On screen", section.sharedValue { it.noScreenResilience <= 0.0 } == true) { on ->
                onEdit { it.copy(noScreenResilience = if (on) 0.0 else 1.0) }
            }
        }
        CalendarElements.Field.AlarmDays -> {
            EditMenuSectionLabel(field.label)
            val days = section.sharedValue { it.alarmDays }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    PeriodBoundChip(day.name.take(2).lowercase(), days?.contains(day) == true) {
                        onEdit { draft ->
                            val next = if (day in draft.alarmDays) draft.alarmDays - day else draft.alarmDays + day
                            draft.copy(alarmDays = next)
                        }
                    }
                }
            }
        }
        CalendarElements.Field.AlarmAlert -> {
            EditMenuSectionLabel(field.label)
            val alert = section.sharedValue { it.alert }
            AlertSettingsEditor(
                alert = alert ?: section.drafts.first().alert,
                onChange = { next -> onEdit { it.copy(alert = next) } },
            )
        }
        CalendarElements.Field.AlarmArmed -> {
            EditMenuSectionLabel(field.label)
            PinSwitchRow("Armed", section.sharedValue { it.alarmArmed } == true) { on ->
                onEdit { it.copy(alarmArmed = on) }
            }
        }
        CalendarElements.Field.ReminderChecked -> {
            EditMenuSectionLabel(field.label)
            PinSwitchRow("Already done", section.sharedValue { it.reminderChecked } == true) { on ->
                onEdit { it.copy(reminderChecked = on) }
            }
        }
        CalendarElements.Field.ReminderPinned -> {
            EditMenuSectionLabel(field.label)
            PinSwitchRow("Stays put", section.sharedValue { it.reminderPinned } == true) { on ->
                onEdit { it.copy(reminderPinned = on) }
            }
        }
    }
}

/**
 * PRD §8: **one bound asked of a whole section** — the period editor's own control, with the two modes only
 * a period can express offered only when every element in the section is one
 * ([CalendarElements.offeredBounds]).
 *
 * The date and time are held as TEXT, keyed by the section, so a half-typed instant survives the
 * recompositions the list above causes — and a mixed value shows blank rather than the first element's,
 * which is the whole point of [CalendarElements.sharedValue].
 */
@Composable
private fun BoundRow(
    label: String,
    infiniteLabel: String,
    section: CalendarElements.ConfigSection,
    nowMillis: Long,
    tz: TimeZone,
    fieldText: MutableMap<String, String>,
    readBound: (CalendarElements.Draft) -> CalendarElements.Bound,
    readMillis: (CalendarElements.Draft) -> Long,
    onEdit: (CalendarElements.Bound, Long?) -> Unit,
) {
    val offered = CalendarElements.offeredBounds(section.drafts)
    val bound = section.sharedValue(readBound) ?: CalendarElements.Bound.At
    val shared = section.sharedValue(readMillis)
    val dateKey = section.key + "/" + label + "/date"
    val timeKey = section.key + "/" + label + "/time"
    val dateText = fieldText[dateKey] ?: shared?.let { formatDate(it, tz) } ?: ""
    val timeText = fieldText[timeKey] ?: shared?.let { formatHm(it, tz) } ?: ""

    EditMenuSectionLabel(label)
    if (offered.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (CalendarElements.Bound.At in offered) {
                PeriodBoundChip("date & time", bound == CalendarElements.Bound.At) {
                    onEdit(CalendarElements.Bound.At, null)
                }
            }
            if (CalendarElements.Bound.Now in offered) {
                PeriodBoundChip("now", bound == CalendarElements.Bound.Now) {
                    onEdit(CalendarElements.Bound.Now, nowMillis)
                }
            }
            if (CalendarElements.Bound.Infinite in offered) {
                PeriodBoundChip(infiniteLabel, bound == CalendarElements.Bound.Infinite) {
                    onEdit(
                        CalendarElements.Bound.Infinite,
                        if (label == "Begins") SchedulerDomain.OPEN_PAST_MILLIS
                        else SchedulerDomain.OPEN_FUTURE_MILLIS,
                    )
                }
            }
        }
    }
    if (bound != CalendarElements.Bound.At) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = dateText,
            onValueChange = { typed ->
                fieldText[dateKey] = typed
                parseDateTime(typed, timeText, tz)?.let { onEdit(CalendarElements.Bound.At, it) }
            },
            label = { Text(if (shared == null && fieldText[dateKey] == null) "— (mixed)" else "YYYY-MM-DD") },
            singleLine = true,
            modifier = Modifier.weight(1.6f),
        )
        OutlinedTextField(
            value = timeText,
            onValueChange = { typed ->
                fieldText[timeKey] = typed
                parseDateTime(dateText, typed, tz)?.let { onEdit(CalendarElements.Bound.At, it) }
            },
            label = { Text(if (shared == null && fieldText[timeKey] == null) "—" else "HH:mm") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * PRD §8 §1a: **the identity and title menus under the search bar**, read for whichever family the chosen
 * kind names — the tasks' (a task cell's own menus), the reminders' (§14's), the account's alarms, and, in
 * **edit** mode, the elements at the mouse and nothing else.
 *
 * It is [EditModeMenuBlock] in every case, which is the point: the question "which one of these do you
 * mean?" has one control in this app, and a second list rendered a second way is how two of them come to
 * disagree about what a pick does.
 */
@Composable
private fun SelectionMenus(
    kind: CalendarElements.Kind,
    search: String,
    mode: CalendarElementsMode,
    candidates: List<CalendarElements.Draft>,
    taskMenuEntries: (String, TaskId?) -> List<SchedulerDomain.ChangeTaskMenuEntry>,
    taskTitleSuggestions: (String) -> List<String>,
    titleForTaskId: (TaskId) -> String?,
    reminderMenuEntries: (String) -> List<SchedulerDomain.ReminderMenuEntry>,
    reminderTitleSuggestions: (String) -> List<String>,
    alarms: List<AlarmEntry>,
    onSearchChange: (String) -> Unit,
) {
    if (mode == CalendarElementsMode.Edit) {
        EditModeMenuBlock(
            identityLabel = "Here",
            identityRows = candidates.filter { it.kind == kind }.map { row ->
                EditMenuItem(label = row.displayName, selected = row.displayName == search) {
                    onSearchChange(row.displayName)
                }
            },
        )
        return
    }
    when (kind) {
        CalendarElements.Kind.TaskPanel ->
            EditModeMenuBlock(
                identityLabel = "Tasks",
                identityRows = taskMenuEntries(search, null).mapNotNull { entry ->
                    entry.taskId?.let { id ->
                        EditMenuItem(label = entry.label, selected = false) {
                            onSearchChange(titleForTaskId(id) ?: entry.label)
                        }
                    }
                },
                suggestions = taskTitleSuggestions(search).map { s -> EditMenuItem(s) { onSearchChange(s) } },
            )
        CalendarElements.Kind.Reminder ->
            EditModeMenuBlock(
                identityLabel = "Reminders",
                identityRows = reminderMenuEntries(search).map { entry ->
                    EditMenuItem(label = entry.title, selected = false) { onSearchChange(entry.title) }
                },
                suggestions = reminderTitleSuggestions(search).map { s -> EditMenuItem(s) { onSearchChange(s) } },
            )
        // PRD §18: no id rows. An alarm picked by id would mean "ring alarm-3 here too", which an alarm
        // cannot be — its occurrences come from its weekdays. Existing LABELS are still offered, because
        // naming a new alarm the way an old one is named is an ordinary thing to want.
        CalendarElements.Kind.Alarm ->
            EditModeMenuBlock(
                identityLabel = "Alarms",
                identityRows = emptyList(),
                suggestions = alarms.map { it.label }
                    .filter { it.isNotBlank() && it.contains(search.trim(), ignoreCase = true) }
                    .distinct()
                    .map { s -> EditMenuItem(s) { onSearchChange(s) } },
            )
        // A period's identity is its KIND, and the kind field below the button is where it is picked.
        CalendarElements.Kind.RestrictivePeriod -> Unit
    }
}

/**
 * PRD §18: **an alarm as the window's model of it** — the ring on the local day containing [onDayMillis],
 * so the `Begins` the user sees is the occurrence they right-clicked near and `Ends` is where that ring
 * stops.
 *
 * The date is the DAY's, never the alarm's: an alarm has no date (see
 * [CalendarElements.alarmTimeOfDayMinutes]), and this is the one place the window borrows one to show.
 */
fun alarmDraft(entry: AlarmEntry, onDayMillis: Long, tz: TimeZone): CalendarElements.Draft {
    val start = AlarmDomain.nextOccurrenceMillis(entry, onDayMillis - 24 * 3_600_000L, tz) ?: onDayMillis
    return CalendarElements.Draft(
        kind = CalendarElements.Kind.Alarm,
        existingId = entry.id,
        name = entry.label.ifBlank { entry.id },
        startMillis = start,
        endMillis = start + entry.soundSeconds * 1000L,
        alarmDays = entry.days,
        alert = entry.alert,
        alarmArmed = entry.enabled,
    )
}
