package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.PopupProperties
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.QuotaDomain
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.QuotaEntry
import org.example.project.scheduler.model.QuotaLoop
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-03: **the Search window's actions on the added quotas** — where each one stands (its target
 * progression), its amount, its loop, its resilience to the restrictive periods and what is particular to one loop.
 * Every setting writes the whole quota list ([SchedulerIntent.SetQuotas]): one History Unit per change.
 */

/**
 * The added quotas, in the account's order — led by the **default configuration** (what a new quota starts with,
 * [SchedulerState.newQuotaDefaults]) when the "New quota" creation row is among the added elements: the actions then
 * edit it like any quota (user rule 2026-10-03). It wears [SearchDomain.DEFAULT_CONFIGURATION_ID], no quota's own id.
 */
internal fun addedQuotas(state: SchedulerState, added: List<SearchDomain.Result>): List<QuotaEntry> {
    val ids = SearchDomain.addedIds(added, SearchDomain.Kind.Quota).toSet()
    val own = state.quotas.filter { it.id in ids }
    return if (SearchDomain.defaultConfigurationAdded(added, SearchDomain.Kind.Quota)) {
        listOf(state.newQuotaDefaults.copy(id = SearchDomain.DEFAULT_CONFIGURATION_ID)) + own
    } else {
        own
    }
}

private val QuotaEntry.isDefault: Boolean get() = id == SearchDomain.DEFAULT_CONFIGURATION_ID

/** How a quota is named in a line about it: its title, or what the default configuration is. */
private fun nameOf(quota: QuotaEntry): String = if (quota.isDefault) "New quota (default)" else quota.title.ifBlank { "Quota" }

/**
 * [change] applied to every one of [quotas]: the account's quotas as one write of the list ([SchedulerIntent.SetQuotas],
 * a History Unit), the default configuration as its own setting ([SchedulerIntent.SetNewQuotaDefaults]).
 */
private fun write(state: SchedulerState, quotas: List<QuotaEntry>, run: (SchedulerIntent) -> Unit, change: (QuotaEntry) -> QuotaEntry) {
    quotas.firstOrNull { it.isDefault }?.let { run(SchedulerIntent.SetNewQuotaDefaults(change(it))) }
    val ids = quotas.filterNot { it.isDefault }.mapTo(HashSet()) { it.id }
    if (ids.isNotEmpty()) run(SchedulerIntent.SetQuotas(state.quotas.map { if (it.id in ids) change(it) else it }))
}

private fun formatInstant(millis: Long, tz: TimeZone): String {
    val t = kotlin.time.Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz)
    return t.date.toString() + " " + t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
}

private fun parseInstant(text: String, tz: TimeZone): Long? =
    runCatching { LocalDateTime.parse(text.trim().replace(' ', 'T')).toInstant(tz).toEpochMilliseconds() }.getOrNull()

private fun percent(fraction: Double): String = SearchDomain.formatQuotaNumber(kotlin.math.round(fraction * 1000.0) / 10.0) + " %"

/** A day and a time of day, `YYYY-MM-DD HH:MM` on this device's clock; [blank] is what an empty field reads. */
@Composable
private fun InstantField(millis: Long?, blank: String, onChange: (Long?) -> Unit) {
    val tz = TimeZone.currentSystemDefault()
    var draft by remember(millis) { mutableStateOf(millis?.let { formatInstant(it, tz) }.orEmpty()) }
    OutlinedTextField(
        value = draft,
        onValueChange = { typed ->
            draft = typed
            if (typed.isBlank()) onChange(null) else parseInstant(typed, tz)?.let(onChange)
        },
        singleLine = true,
        isError = draft.isNotBlank() && parseInstant(draft, tz) == null,
        placeholder = { Text(blank) },
        modifier = Modifier.width(190.dp),
    )
}

/**
 * **Where each added quota stands now**: the target progression (a percentage), the amount due by now of the amount
 * this renewal aims at, and the loop it is in. The loop's pace profile is built when the quota or the calendar's
 * periods change; the instant alone moves it after that, re-read each time the percentage shown can have changed (a
 * thousandth of a renewal — never more often than a second, never a request: a display resample).
 */
@Composable
internal fun QuotaProgressEditor(state: SchedulerState, quotas: List<QuotaEntry>, nowMillis: () -> Long) {
    if (quotas.isEmpty()) {
        Text("No quota is added.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val tz = TimeZone.currentSystemDefault()
    var now by remember { mutableStateOf(nowMillis()) }
    val shortestStep =
        (quotas.filterNot { it.isDefault }.minOfOrNull { quota -> QuotaDomain.loopAt(quota, now).let { it.lengthMillis / (1000L * it.renewals) } } ?: 60_000L)
            .coerceIn(1_000L, 60_000L)
    LaunchedEffect(shortestStep) {
        while (true) {
            delay(shortestStep)
            now = nowMillis()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (quota in quotas) {
            if (quota.isDefault) {
                Text("A default configuration has no progression: it is what a new quota starts with.", style = MaterialTheme.typography.bodySmall)
                continue
            }
            val loop = QuotaDomain.loopAt(quota, now)
            // The periods of the WHOLE loop, the nights already slept included ([SchedulerDomain.quotaPeriods]).
            val periods = remember(state.panels, state.sleep, loop.startMillis, loop.endMillis) {
                SchedulerDomain.quotaPeriods(state, loop.startMillis, loop.endMillis, tz)
            }
            val profile = remember(quota, loop, periods) { QuotaDomain.profile(quota, loop, periods) }
            val progress = QuotaDomain.progress(quota, profile, now)
            Column {
                Text(
                    (if (quotas.size > 1) nameOf(quota) + ": " else "") + percent(progress.fraction),
                    style = MaterialTheme.typography.titleMedium,
                )
                val unit = if (quota.unit.isBlank()) "" else " " + quota.unit
                Text(
                    SearchDomain.formatQuotaNumber(progress.targetAmount) + " of " + SearchDomain.formatQuotaNumber(progress.amount) + unit + " due by now",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "loop " + (loop.index + 1) + ": " + formatInstant(loop.startMillis, tz) + " → " + formatInstant(loop.endMillis, tz) +
                        (if (loop.renewals > 1) " · renewal ${progress.renewal} of ${loop.renewals}" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The amount and its unit, over every added quota: what they share, "mixed" where they differ. */
@Composable
internal fun QuotaAmountEditor(state: SchedulerState, quotas: List<QuotaEntry>, run: (SchedulerIntent) -> Unit) {
    val sharedAmount = quotas.map { it.amount }.distinct().singleOrNull()
    val sharedUnit = quotas.map { it.unit }.distinct().singleOrNull()
    var amountText by remember(sharedAmount) { mutableStateOf(sharedAmount?.let(SearchDomain::formatQuotaNumber).orEmpty()) }
    var unitText by remember(sharedUnit) { mutableStateOf(sharedUnit.orEmpty()) }
    val amount = amountText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            singleLine = true,
            isError = amountText.isNotBlank() && amount == null,
            placeholder = { Text(if (sharedAmount == null && quotas.isNotEmpty()) "mixed" else "amount") },
            modifier = Modifier.width(110.dp),
        )
        OutlinedTextField(
            value = unitText,
            onValueChange = { unitText = it },
            singleLine = true,
            placeholder = { Text(if (sharedUnit == null && quotas.isNotEmpty()) "mixed" else "unit") },
            modifier = Modifier.width(130.dp),
        )
        FrameButton(
            "Apply",
            enabled = quotas.isNotEmpty() && amount != null && quotas.any { it.amount != amount || it.unit != unitText.trim() },
        ) {
            amount?.let { value -> write(state, quotas, run) { it.copy(amount = value, unit = unitText.trim()) } }
        }
    }
}

/**
 * **The quota's period, and how often it comes round** (user rule 2026-10-04):
 *  - its START — a day ([DayField]: a weekday, which stands for the latest such day, or a date off the calendar) and a
 *    time of day;
 *  - its END — a switch says which way it is stated: a LENGTH after the start (the default, 7 days), or a date and a
 *    time of its own. A moved start carries a length along and leaves a date where it is ([QuotaDomain.withStart]);
 *  - how many times it REPEATS after its first period — blank is without end, the default; 0 is not at all.
 * Over every added quota at once: a field reads what they share, "mixed" otherwise. A value the rules refuse (an end
 * not after the start, a length of nothing) shows as an error and is never written.
 */
@Composable
internal fun QuotaLoopEditor(state: SchedulerState, quotas: List<QuotaEntry>, run: (SchedulerIntent) -> Unit) {
    val tz = TimeZone.currentSystemDefault()
    val today = kotlin.time.Clock.System.now().toLocalDateTime(tz).date
    fun <T> shared(read: (QuotaEntry) -> T): T? = quotas.map(read).distinct().singleOrNull()
    fun local(millis: Long): LocalDateTime = kotlin.time.Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz)
    fun at(date: LocalDate, minutes: Int): Long =
        LocalDateTime(date.year, date.month, date.day, minutes / 60, minutes % 60).toInstant(tz).toEpochMilliseconds()
    // A period not said yet (the untouched default: the week a quota is made in) reads as no date; an edit of its
    // start dates it from this week's Monday.
    val unsetDate = QuotaDomain.latestWeekday(DayOfWeek.MONDAY, today)
    fun startDate(q: QuotaEntry): LocalDate? = if (QuotaDomain.periodSet(q)) local(q.startMillis).date else null
    fun startMinutes(q: QuotaEntry): Int = if (QuotaDomain.periodSet(q)) local(q.startMillis).let { it.hour * 60 + it.minute } else 0
    val unsetBlank = if (quotas.size > 1) "mixed" else if (quotas.any { it.isDefault }) "the week it is made in" else "day"
    val byDelta = shared { it.endByDelta }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("starts", style = MaterialTheme.typography.bodySmall)
            DayField(shared(::startDate), unsetBlank, today, enabled = quotas.isNotEmpty()) { date ->
                write(state, quotas, run) { QuotaDomain.withStart(it, at(date, startMinutes(it))) }
            }
            Text("at", style = MaterialTheme.typography.bodySmall)
            TimeOfDayField(shared(::startMinutes), enabled = quotas.isNotEmpty()) { minutes ->
                write(state, quotas, run) { QuotaDomain.withStart(it, at(startDate(it) ?: unsetDate, minutes)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = byDelta == true,
                enabled = quotas.isNotEmpty(),
                onCheckedChange = { on -> write(state, quotas, run) { it.copy(endByDelta = on) } },
            )
            when (byDelta) {
                null -> Text("ends: mixed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                true -> {
                    Text("ends", style = MaterialTheme.typography.bodySmall)
                    LengthField(shared { QuotaDomain.periodLengthMillis(it) }, mixed = quotas.size > 1) { length ->
                        // A period not said yet takes this week's Monday as its start, like an edit of the start.
                        write(state, quotas, run) { q ->
                            QuotaDomain.withLength(if (QuotaDomain.periodSet(q)) q else QuotaDomain.withStart(q, at(unsetDate, 0)), length)
                        }
                    }
                    Text("after the start", style = MaterialTheme.typography.bodySmall)
                }
                false -> {
                    fun endDate(q: QuotaEntry): LocalDate? = if (QuotaDomain.periodSet(q)) local(q.endMillis).date else null
                    fun endMinutes(q: QuotaEntry): Int = if (QuotaDomain.periodSet(q)) local(q.endMillis).let { it.hour * 60 + it.minute } else 0
                    val refused = { q: QuotaEntry, end: Long -> QuotaDomain.withEnd(q, end) == q && end != q.endMillis }
                    Text("ends on", style = MaterialTheme.typography.bodySmall)
                    DayField(shared(::endDate), unsetBlank, today, enabled = quotas.any(QuotaDomain::periodSet)) { date ->
                        if (quotas.none { refused(it, at(date, endMinutes(it))) }) {
                            write(state, quotas, run) { QuotaDomain.withEnd(it, at(date, endMinutes(it))) }
                        }
                    }
                    Text("at", style = MaterialTheme.typography.bodySmall)
                    TimeOfDayField(shared(::endMinutes), enabled = quotas.any(QuotaDomain::periodSet)) { minutes ->
                        if (quotas.none { q -> endDate(q)?.let { refused(q, at(it, minutes)) } == true }) {
                            write(state, quotas, run) { q -> endDate(q)?.let { QuotaDomain.withEnd(q, at(it, minutes)) } ?: q }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The count as the field reads it: blank = without end, 0 = it does not repeat.
            fun countOf(q: QuotaEntry): String = if (!q.repeats) "0" else q.repeatCount?.toString().orEmpty()
            val sharedCount = shared(::countOf)
            var draft by remember(sharedCount) { mutableStateOf(sharedCount.orEmpty()) }
            val typed = draft.trim()
            val valid = typed.isEmpty() || typed == "∞" || typed.toIntOrNull()?.let { it >= 0 } == true
            Text("repeats", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = draft,
                onValueChange = { text ->
                    draft = text
                    val t = text.trim()
                    val count = if (t.isEmpty() || t == "∞") null else t.toIntOrNull()?.takeIf { it >= 0 }
                    if (t.isEmpty() || t == "∞" || count != null) write(state, quotas, run) { QuotaDomain.withRepeatCount(it, count) }
                },
                singleLine = true,
                enabled = quotas.isNotEmpty(),
                isError = !valid,
                placeholder = { Text(if (sharedCount == null && quotas.size > 1) "mixed" else "∞") },
                modifier = Modifier.width(96.dp).endsEditOnOutsidePress { draft = sharedCount.orEmpty() },
            )
            Text(
                when {
                    sharedCount == null -> "times"
                    sharedCount.isEmpty() -> "times — without end"
                    sharedCount == "0" -> "times — it does not repeat"
                    else -> "times after the first period"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val WEEKDAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * **A day**, as a field with a drop-down (user rule 2026-10-04): a row of the seven days of the week — a pick is the
 * latest such day up to [today] ([QuotaDomain.latestWeekday]) — and, below it, a calendar to pick a date instead
 * (the calendar window's own month grid). The field reads the day picked, with its weekday.
 */
@Composable
internal fun DayField(date: LocalDate?, blank: String, today: LocalDate, enabled: Boolean, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var month by remember(date, open) { mutableStateOf(date ?: today) }
    Box(Modifier.width(170.dp)) {
        Text(
            text = (date?.let { WEEKDAY_SHORT[it.dayOfWeek.ordinal] + " " + it } ?: blank) + "  ▾",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled && date != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .then(if (enabled) Modifier.menuToggleClickable(open) { open = it } else Modifier)
                .padding(horizontal = 10.dp, vertical = 14.dp),
        )
        transientMenuDismissal(open) { open = false }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            Column(Modifier.width(250.dp).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    DayOfWeek.entries.forEach { weekday ->
                        val picked = date?.dayOfWeek == weekday
                        Text(
                            text = WEEKDAY_SHORT[weekday.ordinal],
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            color = if (picked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                .clickable {
                                    open = false
                                    onPick(QuotaDomain.latestWeekday(weekday, today))
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                MiniMonth(
                    monthAnchor = month,
                    onMonthAnchorChange = { month = it },
                    selectedDate = date ?: today,
                    today = today,
                    onSelectDate = {
                        open = false
                        onPick(it)
                    },
                )
            }
        }
    }
}

/**
 * **A field's edit ends on the first press outside it** (`popups.md`, [leaveOnOutsidePress]): a text field alone keeps
 * the caret when the press lands on something that takes no focus — a bare stretch of the window — and the user is
 * left in an edit they meant to leave (anomaly 2026-10-04). [onLeft] runs when the field loses the focus, however.
 */
@Composable
private fun Modifier.endsEditOnOutsidePress(onLeft: () -> Unit = {}): Modifier {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    return this
        .leaveOnOutsidePress(focused) { focusManager.clearFocus() }
        .onFocusChanged { state ->
            if (focused && !state.isFocused) onLeft()
            focused = state.isFocused
        }
}

/**
 * **What a field reads once the value it edits has changed** (anomaly 2026-10-05: `20:30`, one backspace, and the field
 * read `20:03`). A field writes what is typed as soon as it reads as a value, and the stored value then comes back to
 * it: [draft] is kept while it still says that value (`20:3` IS 20:03 — the hand is not done typing), and replaced by
 * the stored value's own text only when it says something else (written by another field, or by Undo).
 */
internal fun <T> draftAfterStoreChange(draft: String, stored: T?, format: (T) -> String, parse: (String) -> T?): String =
    if (parse(draft) == stored) draft else stored?.let(format).orEmpty()

/** A field's text over the value it edits ([draftAfterStoreChange]): never rewritten under the hand typing it. */
@Composable
private fun <T> rememberFieldDraft(stored: T?, format: (T) -> String, parse: (String) -> T?): androidx.compose.runtime.MutableState<String> {
    val draft = remember { mutableStateOf(stored?.let(format).orEmpty()) }
    var seen by remember { mutableStateOf(stored) }
    if (seen != stored) {
        seen = stored
        draft.value = draftAfterStoreChange(draft.value, stored, format, parse)
    }
    return draft
}

/** A time of day as its field reads it: `HH:MM`. */
internal fun timeOfDayText(minutes: Int): String =
    (minutes / 60).toString().padStart(2, '0') + ":" + (minutes % 60).toString().padStart(2, '0')

/** A typed time of day in minutes — `7`, `07:5`, `07:05` — or null for anything else. */
internal fun timeOfDayOf(text: String): Int? {
    val parts = text.trim().split(':')
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = if (parts.size > 1) parts[1].toIntOrNull() ?: return null else 0
    return if (parts.size <= 2 && h in 0..23 && m in 0..59) h * 60 + m else null
}

/**
 * A time of day, `HH:MM`; [minutes] null reads "mixed". A time that does not parse shows as an error, unwritten — and
 * so does one the caller [refused]. Left, the field reads [minutes] again: it never goes on showing a time that is
 * not the one stored.
 */
@Composable
internal fun TimeOfDayField(
    minutes: Int?,
    enabled: Boolean,
    refused: Boolean = false,
    /** What the empty field reads while [minutes] is null: several values, or none given yet. */
    blank: String = "mixed",
    /** The field reads the stored time again — typed back, or left: whatever was refused is no longer on screen. */
    onSettled: () -> Unit = {},
    /**
     * The right-click menu's step ([TimeNudgeMenu]), where it is not simply the time of day moved round the clock: a
     * time that belongs to a date carries the date along.
     */
    onNudge: ((deltaMinutes: Int) -> Unit)? = null,
    onChange: (Int) -> Unit,
) {
    fun format(m: Int) = timeOfDayText(m)
    fun parse(text: String): Int? = timeOfDayOf(text)
    var draft by rememberFieldDraft(minutes, ::timeOfDayText, ::timeOfDayOf)
    TimeNudgeMenu(
        enabled = enabled && minutes != null,
        onNudge = { delta -> if (onNudge != null) onNudge(delta) else minutes?.let { onChange(nudgedTimeOfDay(it, delta)) } },
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { text ->
                draft = text
                val typed = parse(text)
                // The stored time typed back is no change to write — but it IS the end of whatever was refused before
                // it (anomaly 2026-10-04: the red message stayed on after the real end was typed back).
                if (typed != null && typed == minutes) onSettled() else typed?.let(onChange)
            },
            singleLine = true,
            enabled = enabled,
            // Red for what is ON SCREEN: a refusal no longer colours a field that reads the stored time.
            isError = (refused && parse(draft) != minutes) || (draft.isNotBlank() && parse(draft) == null),
            placeholder = { Text(if (minutes == null) blank else "HH:MM") },
            modifier = Modifier.width(92.dp).endsEditOnOutsidePress {
                draft = minutes?.let(::format).orEmpty()
                onSettled()
            },
        )
    }
}

/**
 * **A length of time: a number and the unit it is in** (user rule 2026-10-05) — a field, and a button whose drop-down
 * lists the units ([QuotaDomain.LengthUnit]: minutes, hours, days, weeks). The number is written as soon as it is one
 * (to the minute, [QuotaDomain.parseLengthIn]); picking a unit keeps the number and writes it in that unit. A stored
 * length reads in the largest unit it is a whole number of ([QuotaDomain.lengthUnitOf]) — but never changes unit under
 * the hand that is typing it (`24` hours stays hours while a `240` is on its way).
 * [blank] is what the empty field reads; where no length is an answer too, emptying the field says it ([onBlank]).
 */
@Composable
internal fun LengthField(
    millis: Long?,
    mixed: Boolean,
    blank: String = "7",
    /** The unit the drop-down stands on while there is no length to read one off. */
    defaultUnit: QuotaDomain.LengthUnit = QuotaDomain.LengthUnit.Days,
    onBlank: (() -> Unit)? = null,
    onChange: (Long) -> Unit,
) {
    var unit by remember { mutableStateOf(millis?.let(QuotaDomain::lengthUnitOf) ?: defaultUnit) }
    var draft by remember { mutableStateOf(millis?.let { QuotaDomain.lengthIn(it, unit) }.orEmpty()) }
    // The stored length changed and it is not what the field says: written elsewhere (another field, Undo), so read again.
    var seen by remember { mutableStateOf(millis) }
    if (seen != millis) {
        seen = millis
        if (QuotaDomain.parseLengthIn(draft, unit) != millis) {
            millis?.let { unit = QuotaDomain.lengthUnitOf(it) }
            draft = millis?.let { QuotaDomain.lengthIn(it, unit) }.orEmpty()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { text ->
                draft = text
                if (text.isBlank() && millis != null) onBlank?.invoke()
                QuotaDomain.parseLengthIn(text, unit)?.takeIf { it != millis }?.let(onChange)
            },
            singleLine = true,
            isError = draft.isNotBlank() && QuotaDomain.parseLengthIn(draft, unit) == null,
            placeholder = { Text(if (millis == null && mixed) "mixed" else blank) },
            // Left, it reads the stored length again: a length that was not one is not left on screen.
            modifier = Modifier.width(96.dp).endsEditOnOutsidePress { draft = millis?.let { QuotaDomain.lengthIn(it, unit) }.orEmpty() },
        )
        ChoiceDropDown(
            options = QuotaDomain.LengthUnit.entries,
            selected = unit,
            label = { it.label },
            onSelect = { picked ->
                unit = picked
                QuotaDomain.parseLengthIn(draft, picked)?.takeIf { it != millis }?.let(onChange)
            },
            modifier = Modifier.width(120.dp),
        )
    }
}

/**
 * The quota's **resilience** to every kind of restrictive period, a task's own number: the rate its progression moves
 * at inside such a period — 0 % and it stands still there, 100 % and the period changes nothing. User rule
 * 2026-10-04: ONE button, whose drop-down lists every period with a field for its value — each field over every added
 * quota at once (what they share, "mixed" otherwise), written as soon as it reads as a percentage.
 *
 * The menu takes the keyboard (`focusable`), unlike the app's other menus (`popups.md`): a field in a popup that
 * cannot be focused cannot be typed into. It leaves on the first press outside it, which it consumes.
 */
@Composable
internal fun QuotaResilienceEditor(state: SchedulerState, quotas: List<QuotaEntry>, run: (SchedulerIntent) -> Unit) {
    // EVERY kind of period, the 20 s break and `inactivity` included: those two "allow no task", which is a rule about
    // tasks — a quota's progression through them is the user's to say ([QuotaDomain.resilienceFor]).
    val kinds = state.allPeriodKinds
    var open by remember { mutableStateOf(false) }
    // How many periods the added quotas do not all pass through untouched: what the button says without being opened.
    val changed = kinds.count { kind -> quotas.any { QuotaDomain.resilienceFor(it, kind) != 1.0 } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            FrameButton("Resilience values  ▾", enabled = quotas.isNotEmpty() && kinds.isNotEmpty()) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (kind in kinds) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                kind,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(190.dp),
                            )
                            ResilienceField(quotas.map { QuotaDomain.resilienceFor(it, kind) }) { value ->
                                write(state, quotas, run) { it.copy(resilience = it.resilience + (kind to value)) }
                            }
                        }
                    }
                }
            }
        }
        Text(
            when {
                quotas.isEmpty() -> ""
                changed == 0 -> "100 % in every period"
                changed == 1 -> "1 period not at 100 %"
                else -> "$changed periods not at 100 %"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One period's resilience, a percentage in 0…100: the value [values] share, else "mixed"; written once it parses. */
@Composable
private fun ResilienceField(values: List<Double>, onChange: (Double) -> Unit) {
    val shared = values.distinct().singleOrNull()
    fun parse(text: String): Double? =
        text.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.let { it / 100.0 }
    // `5.` on its way to `5.5` is 5 %: the field keeps the point ([draftAfterStoreChange]).
    var draft by rememberFieldDraft(shared, { SearchDomain.formatQuotaNumber(it * 100.0) }, ::parse)
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            draft = text
            parse(text)?.takeIf { value -> values.any { it != value } }?.let(onChange)
        },
        singleLine = true,
        isError = draft.isNotBlank() && parse(draft) == null,
        suffix = { Text("%") },
        placeholder = { Text(if (shared == null) "mixed" else "") },
        modifier = Modifier.width(110.dp),
    )
}

/**
 * **How many times the percentage restarts in one period** (user rule 2026-10-04, [QuotaEntry.renewals]): with 2 it
 * reaches 100 % where it would have reached 50 %, restarts at 0 % and reaches 100 % again at the period's end. Over
 * every added quota; written once it reads as a whole number of at least 1.
 */
@Composable
internal fun QuotaRestartsEditor(state: SchedulerState, quotas: List<QuotaEntry>, run: (SchedulerIntent) -> Unit) {
    val shared = quotas.map { it.renewals }.distinct().singleOrNull()
    var draft by remember(shared) { mutableStateOf(shared?.toString().orEmpty()) }
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..QuotaDomain.MAX_RENEWALS }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { text ->
                draft = text
                parse(text)?.takeIf { n -> quotas.any { it.renewals != n } }?.let { n -> write(state, quotas, run) { it.copy(renewals = n) } }
            },
            singleLine = true,
            enabled = quotas.isNotEmpty(),
            isError = draft.isNotBlank() && parse(draft) == null,
            placeholder = { Text(if (shared == null && quotas.size > 1) "mixed" else "1") },
            modifier = Modifier.width(96.dp).endsEditOnOutsidePress { draft = shared?.toString().orEmpty() },
        )
        Text(
            when (shared) {
                null -> "runs from 0 to 100 % in a period"
                1 -> "run from 0 to 100 % in a period"
                else -> "runs from 0 to 100 % in a period: 100 % every 1/$shared of it, then back to 0 %"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun localOf(millis: Long, tz: TimeZone): LocalDateTime = kotlin.time.Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz)

internal fun instantOf(date: LocalDate, minutes: Int, tz: TimeZone): Long =
    LocalDateTime(date.year, date.month, date.day, minutes / 60, minutes % 60).toInstant(tz).toEpochMilliseconds()

/**
 * **The particular loops** of each added quota (user rule 2026-10-04): a LIST — "add a loop" puts the period of the
 * given number in it — and, on each of its elements, what is particular to that period:
 *  - its **restarts** (blank: the quota's own number);
 *  - its **ending time**, which has to be INSIDE the period: the quota is at 100 % from there to the start of the
 *    next period. "regular end" gives it back the period's own;
 *  - ✕ takes it off the list: nothing is particular to it any more.
 * A value is written as soon as it is one; a refused one (an end outside the period, a number that is none) shows as
 * an error and is never written. An element with nothing particular yet is only on screen until it is given something.
 */
@Composable
internal fun QuotaLoopsEditor(state: SchedulerState, quotas: List<QuotaEntry>, run: (SchedulerIntent) -> Unit, nowMillis: () -> Long) {
    if (quotas.isEmpty()) {
        Text("No quota is added.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val tz = TimeZone.currentSystemDefault()
    val today = kotlin.time.Clock.System.now().toLocalDateTime(tz).date
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (quota in quotas) {
            if (quota.isDefault) {
                Text("A default configuration has no particular loop: a new quota starts with none.", style = MaterialTheme.typography.bodySmall)
                continue
            }
            if (quotas.size > 1) Text(nameOf(quota), style = MaterialTheme.typography.labelMedium)
            // The periods added here that hold nothing particular yet: on screen only, until given something.
            var pending by remember(quota.id) { mutableStateOf(emptyList<Int>()) }
            val last = QuotaDomain.lastLoopIndex(quota)
            val listed = (quota.loops.map { it.index } + pending).distinct().filter { it <= last }.sorted()
            if (listed.isEmpty()) {
                Text("No particular loop.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (index in listed) {
                ParticularLoopRow(
                    quota = quota,
                    index = index,
                    tz = tz,
                    today = today,
                    onChange = { change -> write(state, listOf(quota), run) { QuotaDomain.withParticular(it, index, change) } },
                    onRemove = {
                        pending = pending - index
                        write(state, listOf(quota), run) { q -> q.copy(loops = q.loops.filterNot { it.index == index }) }
                    },
                )
            }
            // "Add a loop": the period the quota is in now, else the first one after it not on the list yet.
            val suggested = remember(quota.id, listed) {
                generateSequence(QuotaDomain.loopAt(quota, nowMillis()).index) { it + 1 }.first { it !in listed || it >= last }
            }
            var numberText by remember(quota.id, suggested) { mutableStateOf((suggested + 1).toString()) }
            val number = numberText.trim().toIntOrNull()?.takeIf { it >= 1 && it - 1 <= last && it - 1 !in listed }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FrameButton("+ Add a loop", enabled = number != null) { number?.let { pending = pending + (it - 1) } }
                Text("n°", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = numberText,
                    onValueChange = { numberText = it },
                    singleLine = true,
                    isError = number == null,
                    modifier = Modifier.width(84.dp).endsEditOnOutsidePress(),
                )
            }
        }
    }
}

/** One element of the particular loops' list ([QuotaLoopsEditor]): period [index] of [quota] and what is particular to it. */
@Composable
private fun ParticularLoopRow(
    quota: QuotaEntry,
    index: Int,
    tz: TimeZone,
    today: LocalDate,
    onChange: ((QuotaLoop) -> QuotaLoop) -> Unit,
    onRemove: () -> Unit,
) {
    val own = QuotaDomain.particular(quota, index)
    val loop = QuotaDomain.loop(quota, index)
    val (regularStart, regularEnd) = QuotaDomain.regularBounds(quota, index)
    var endRefused by remember(quota.id, index) { mutableStateOf(false) }
    fun setEnd(endMillis: Long) {
        endRefused = !QuotaDomain.endInsidePeriod(quota, index, endMillis)
        // Its own end alone: a start of its own (an earlier build's shape) goes, the period starts where it does.
        if (!endRefused) onChange { it.copy(startMillis = null, endMillis = endMillis.takeIf { e -> e != regularEnd }) }
    }
    val end = localOf(loop.endMillis, tz)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Loop " + (index + 1) + " · " + formatInstant(regularStart, tz) + " → " + formatInstant(regularEnd, tz),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            FrameButton("✕", onClick = onRemove)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("restarts", style = MaterialTheme.typography.bodySmall)
            var restarts by remember(quota.id, index, own.renewals) { mutableStateOf(own.renewals?.toString().orEmpty()) }
            fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..QuotaDomain.MAX_RENEWALS }
            OutlinedTextField(
                value = restarts,
                onValueChange = { text ->
                    restarts = text
                    if (text.isBlank()) onChange { it.copy(renewals = null) }
                    else parse(text)?.takeIf { it != own.renewals }?.let { n -> onChange { it.copy(renewals = n) } }
                },
                singleLine = true,
                isError = restarts.isNotBlank() && parse(restarts) == null,
                placeholder = { Text(quota.renewals.toString()) },
                modifier = Modifier.width(84.dp).endsEditOnOutsidePress { restarts = own.renewals?.toString().orEmpty() },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ends", style = MaterialTheme.typography.bodySmall)
            DayField(end.date, "day", today, enabled = true) { date -> setEnd(instantOf(date, end.hour * 60 + end.minute, tz)) }
            Text("at", style = MaterialTheme.typography.bodySmall)
            // A refused time is shown as the error it is, and the field reads the real end again once it is left:
            // what is typed and refused is never what the loop ends at (anomaly 2026-10-04).
            TimeOfDayField(end.hour * 60 + end.minute, enabled = true, refused = endRefused, onSettled = { endRefused = false }) { minutes ->
                setEnd(instantOf(end.date, minutes, tz))
            }
            // Something to go back from: an end of its own, or a refusal on screen (a refused date stores nothing, so
            // the button was grey over the very error it clears — anomaly 2026-10-04).
            FrameButton("regular end", enabled = own.endMillis != null || endRefused) {
                endRefused = false
                onChange { it.copy(startMillis = null, endMillis = null) }
            }
        }
        Text(
            when {
                endRefused -> "NOT SAVED — the ending time has to be inside the loop: after " + formatInstant(regularStart, tz) +
                    ", " + formatInstant(regularEnd, tz) + " at the latest."
                own.endMillis != null -> "At 100 % from " + formatInstant(loop.endMillis, tz) + " until the next loop starts."
                else -> "Ends with the loop."
            },
            style = if (endRefused) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
            color = if (endRefused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
