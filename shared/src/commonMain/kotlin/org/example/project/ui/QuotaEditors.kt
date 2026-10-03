package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    val periods = remember(state.panels) { SchedulerDomain.restrictivePeriods(state) }
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
                        (if (loop.renewals > 1) " · renewal ${progress.renewal} of ${loop.renewals}" else "") +
                        (if (loop.amountFactor != 1.0) " · ×" + SearchDomain.formatQuotaNumber(loop.amountFactor) + " quota" else ""),
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
 * The first loop — its start and its end, a day and a time each — and whether it repeats (every next loop is the first
 * one moved by its length). An end not after the start is refused by the field (shown as an error), never written.
 */
@Composable
internal fun QuotaLoopEditor(state: SchedulerState, quotas: List<QuotaEntry>, run: (SchedulerIntent) -> Unit) {
    // An unset loop (the untouched default: the week a quota is made in) shows no date and has no length yet.
    fun set(quota: QuotaEntry) = quota.endMillis > quota.startMillis
    val start = quotas.map { it.startMillis.takeIf { _ -> set(it) } }.distinct().singleOrNull()
    val end = quotas.map { it.endMillis.takeIf { _ -> set(it) } }.distinct().singleOrNull()
    val blank = if (quotas.size > 1) "mixed" else if (quotas.any { it.isDefault }) "the week it is made in" else "YYYY-MM-DD HH:MM"
    val repeats = quotas.map { it.repeats }.distinct().singleOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("from", style = MaterialTheme.typography.bodySmall)
            InstantField(start, blank) { at ->
                // Moving the start keeps each quota's length (a week for a loop not set yet): moved, not stretched.
                if (at != null) {
                    write(state, quotas, run) {
                        it.copy(startMillis = at, endMillis = at + if (set(it)) it.endMillis - it.startMillis else QuotaDomain.DEFAULT_LOOP_MILLIS)
                    }
                }
            }
            Text("to", style = MaterialTheme.typography.bodySmall)
            InstantField(end, blank) { at ->
                if (at != null && quotas.all { set(it) && at > it.startMillis }) write(state, quotas, run) { it.copy(endMillis = at) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = repeats == true,
                enabled = quotas.isNotEmpty(),
                onCheckedChange = { on -> write(state, quotas, run) { it.copy(repeats = on) } },
            )
            Text(
                when (repeats) {
                    true -> "repeats, each loop following the last"
                    false -> "does not repeat"
                    null -> "mixed"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The quota's **resilience** to one kind of restrictive period, a task's own control: the rate its progression moves
 * at inside such a period — 0 % and it stands still there, 100 % and the period changes nothing.
 */
@Composable
internal fun QuotaResilienceEditor(
    state: SchedulerState,
    quotas: List<QuotaEntry>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    run: (SchedulerIntent) -> Unit,
) {
    // The period is the WINDOW's ([SearchDomain.Config.resiliencePeriod]) — the one the tasks' Resilience action
    // reads, so a period edit window's Search names it for both.
    val kind = SearchDomain.resiliencePeriodOf(state, config)
    val values = quotas.map { quota -> kind?.let { PeriodKinds.resilienceFor(quota.resilience, it) } }
    val shared = values.distinct().singleOrNull()
    var draft by remember(shared, kind) { mutableStateOf(shared?.let { SearchDomain.formatQuotaNumber(it * 100.0) }.orEmpty()) }
    val value = draft.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()?.let { PeriodKinds.clamp(it / 100.0) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ChoiceDropDown(
            options = SearchDomain.resilienceKinds(state),
            selected = kind,
            label = { it },
            onSelect = { onConfigChange(config.copy(resiliencePeriod = it)) },
            placeholder = "period",
            modifier = Modifier.width(150.dp),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            suffix = { Text("%") },
            placeholder = { Text(if (shared == null && quotas.isNotEmpty() && kind != null) "mixed" else "") },
            modifier = Modifier.width(96.dp),
        )
        FrameButton("Apply", enabled = kind != null && value != null && values.any { it != value }) {
            val chosen = kind
            if (chosen != null && value != null) write(state, quotas, run) { it.copy(resilience = it.resilience + (chosen to value)) }
        }
    }
}

/**
 * **What is particular to one loop** of each added quota: its own start and end (blank: the regular ones), its amount
 * factor (2 — two times more quota) and its renewals (2 — the progression moves twice as fast and comes back to 0 % on
 * reaching 100 % in the loop's middle). The form opens on the loop the quota is in now; "Set" writes it, a loop back
 * at 1 × 1 with the regular bounds is no longer particular, and ✕ makes one regular again.
 */
@Composable
internal fun QuotaLoopsEditor(state: SchedulerState, quotas: List<QuotaEntry>, run: (SchedulerIntent) -> Unit, nowMillis: () -> Long) {
    if (quotas.isEmpty()) {
        Text("No quota is added.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val tz = TimeZone.currentSystemDefault()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (quota in quotas) {
            if (quota.isDefault) {
                Text("A default configuration has no particular loop: a new quota starts with none.", style = MaterialTheme.typography.bodySmall)
                continue
            }
            if (quotas.size > 1) Text(nameOf(quota), style = MaterialTheme.typography.labelMedium)
            for (own in quota.loops) {
                val loop = QuotaDomain.loop(quota, own.index)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "loop " + (own.index + 1) + ": " + formatInstant(loop.startMillis, tz) + " → " + formatInstant(loop.endMillis, tz) +
                            " · ×" + SearchDomain.formatQuotaNumber(loop.amountFactor) + " quota · " +
                            loop.renewals + (if (loop.renewals == 1) " renewal" else " renewals"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    FrameButton("✕") { write(state, listOf(quota), run) { q -> q.copy(loops = q.loops.filterNot { it.index == own.index }) } }
                }
            }
            val current = remember(quota.id) { QuotaDomain.loopAt(quota, nowMillis()).index }
            var indexText by remember(quota.id) { mutableStateOf((current + 1).toString()) }
            var factorText by remember(quota.id) { mutableStateOf("1") }
            var renewalsText by remember(quota.id) { mutableStateOf("1") }
            var ownStart by remember(quota.id) { mutableStateOf<Long?>(null) }
            var ownEnd by remember(quota.id) { mutableStateOf<Long?>(null) }
            val index = indexText.trim().toIntOrNull()?.takeIf { it >= 1 && (quota.repeats || it == 1) }
            val factor = factorText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            val renewals = renewalsText.trim().toIntOrNull()?.takeIf { it in 1..QuotaDomain.MAX_RENEWALS }
            val boundsOk = (ownStart == null && ownEnd == null) || (ownStart != null && ownEnd != null && ownEnd!! > ownStart!!)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = indexText, onValueChange = { indexText = it }, singleLine = true, isError = index == null,
                    label = { Text("Loop") }, modifier = Modifier.width(84.dp),
                )
                OutlinedTextField(
                    value = factorText, onValueChange = { factorText = it }, singleLine = true, isError = factor == null,
                    label = { Text("× quota") }, modifier = Modifier.width(96.dp),
                )
                OutlinedTextField(
                    value = renewalsText, onValueChange = { renewalsText = it }, singleLine = true, isError = renewals == null,
                    label = { Text("Renewals") }, modifier = Modifier.width(104.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                InstantField(ownStart, "regular start") { ownStart = it }
                InstantField(ownEnd, "regular end") { ownEnd = it }
                FrameButton("Set", enabled = index != null && factor != null && renewals != null && boundsOk) {
                    val at = (index ?: return@FrameButton) - 1
                    val loop = QuotaLoop(at, ownStart, ownEnd, factor ?: 1.0, renewals ?: 1)
                    write(state, listOf(quota), run) { q -> q.copy(loops = q.loops.filterNot { it.index == at } + loop) }
                }
            }
            if (!boundsOk) {
                Text(
                    "A loop's own start and end go together, the end after the start.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
