package org.example.project.scheduler.domain

import org.example.project.scheduler.model.QuotaEntry
import org.example.project.scheduler.model.QuotaLoop
import org.example.project.scheduler.model.TaskPanel

/**
 * User rule 2026-10-03: **a quota** — an amount to reach over a loop of time (a week, three days, …), and the pace it
 * should be reached at. The whole of the arithmetic, pure:
 *
 *  - **The loops.** Loop `0` runs from [QuotaEntry.startMillis] to [QuotaEntry.endMillis]; while the quota repeats,
 *    loop `k` is that one moved by `k` lengths. A [QuotaLoop] gives ONE loop its own start and end, its own amount
 *    (a factor: `2` is "two times more quota") and its number of **renewals** ([loop]).
 *  - **The target progression** at an instant is the share of the loop's time already elapsed, each stretch weighted
 *    by the quota's **resilience** to the restrictive periods covering it — the multiplier a task's share is scaled by
 *    there ([PeriodKinds.multiplier]): `0` and the progression stands still (a night it is not expected to move in),
 *    `1` and it moves at full pace ([profile], [progress]). It is a pace line: nothing is recorded as done.
 *  - **Renewal.** With `r` renewals the progression moves `r` times faster and comes back to 0 % each time it reaches
 *    100 % — twice in a loop renewed once in its middle.
 *
 * Nothing here is stored: the progression is derived from the quota, the periods and the instant.
 */
object QuotaDomain {

    /** The length a new quota's loop has, and the one a loop healed from a bad length gets: a week. */
    const val DEFAULT_LOOP_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /** Mints an id no quota in [existing] uses — the alarms', timers' and chronos' scheme. */
    fun mintQuotaId(existing: Collection<String>): String {
        val used = existing.toSet()
        var n = 0
        while ("quota-$n" in used) n++
        return "quota-$n"
    }

    /** Fills any blank id in [quotas] with a fresh unique one. */
    fun assignQuotaIds(quotas: List<QuotaEntry>): List<QuotaEntry> {
        if (quotas.none { it.id.isBlank() }) return quotas
        val used = quotas.filter { it.id.isNotBlank() }.mapTo(mutableSetOf()) { it.id }
        return quotas.map { entry ->
            if (entry.id.isNotBlank()) entry else entry.copy(id = mintQuotaId(used).also { used.add(it) })
        }
    }

    /**
     * [entry] in the one shape it is allowed to be in — applied on decode, on merge and in the reducer: a finite
     * amount ≥ 0, a loop that ends after it starts (a week when it does not), resiliences in `[0, 1]`, and its
     * particular loops one per index (≥ 0; only loop 0 when it does not repeat), each with a finite factor ≥ 0, at
     * least one renewal, and bounds that end after they start (dropped back to the regular ones otherwise).
     */
    fun healed(entry: QuotaEntry): QuotaEntry {
        val amount = entry.amount.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        // One fact, said once: a count of 0 (or less) IS "does not repeat", and a quota that does not repeat has none.
        val repeats = entry.repeats && (entry.repeatCount == null || entry.repeatCount > 0)
        val repeatCount = entry.repeatCount.takeIf { repeats }
        val end = if (entry.endMillis > entry.startMillis) entry.endMillis else entry.startMillis + DEFAULT_LOOP_MILLIS
        val resilience = entry.resilience.mapValues { PeriodKinds.clamp(it.value) }
        val loops =
            entry.loops
                .filter { it.index >= 0 && it.index <= lastLoopIndex(repeats, repeatCount) }
                .distinctBy { it.index }
                .map { loop ->
                    // Two shapes of own bounds: an END alone, inside the period it belongs to; or (an earlier build's)
                    // a start and an end, the end after the start. Anything else falls back on the regular ones.
                    val length = (end - entry.startMillis).coerceAtLeast(1L)
                    val regularStart = entry.startMillis + loop.index * length
                    val both = loop.startMillis != null && loop.endMillis != null && loop.endMillis > loop.startMillis
                    val endAlone = loop.startMillis == null && loop.endMillis != null &&
                        loop.endMillis > regularStart && loop.endMillis <= regularStart + length
                    loop.copy(
                        startMillis = loop.startMillis.takeIf { both },
                        endMillis = loop.endMillis.takeIf { both || endAlone },
                        // A number that is none (0, negative) is no number of its own: the quota's.
                        renewals = loop.renewals?.takeIf { it >= 1 }?.coerceAtMost(MAX_RENEWALS),
                    )
                }
                .filterNot { it == QuotaLoop(it.index) }
                .sortedBy { it.index }
        val healed = entry.copy(
            amount = amount, endMillis = end, resilience = resilience, loops = loops, repeats = repeats, repeatCount = repeatCount,
            renewals = entry.renewals.coerceIn(1, MAX_RENEWALS),
        )
        return if (healed == entry) entry else healed
    }

    /**
     * **The quota's resilience to [kind]** — the rate its progression moves at inside a period of it: the value the
     * quota was given, else the kind's default.
     *
     * Not [PeriodKinds.resilienceFor]: that one refuses every value for the kinds that *"allow no task"* (the 20 s
     * break, `inactivity`), which is a rule about TASKS the scheduler places. A quota is not a task — whether its
     * progression goes on through a 20 s break is the user's to say (anomaly 2026-10-04: the 20 s break was not among
     * the periods a quota could be given a value for). Untouched, those two stay at 0: the progression stands still
     * there, exactly as before.
     */
    fun resilienceFor(quota: QuotaEntry, kind: String): Double =
        quota.resilience[kind]?.let(PeriodKinds::clamp) ?: PeriodKinds.resilienceFor(emptyMap(), kind)

    /** The product of [quota]'s resilience to every one of [kinds]: overlapping periods multiply, as a task's do. */
    fun multiplier(quota: QuotaEntry, kinds: Collection<String>): Double {
        var m = 1.0
        for (kind in kinds) {
            m *= resilienceFor(quota, kind)
            if (m <= 0.0) return 0.0
        }
        return m
    }

    // ---- What is particular to one period (user rule 2026-10-04) --------------------------------------------------

    /** The bounds period [index] has when nothing is particular to it: the first one moved by `index` lengths. */
    fun regularBounds(quota: QuotaEntry, index: Int): Pair<Long, Long> {
        val length = (quota.endMillis - quota.startMillis).coerceAtLeast(1L)
        return (quota.startMillis + index * length) to (quota.endMillis + index * length)
    }

    /** What is particular to period [index] of [quota]; an entry with nothing in it when nothing is. */
    fun particular(quota: QuotaEntry, index: Int): QuotaLoop = quota.loops.firstOrNull { it.index == index } ?: QuotaLoop(index)

    /** [quota] with [change] made to what is particular to period [index] — healed, so a refused value is not kept. */
    fun withParticular(quota: QuotaEntry, index: Int, change: (QuotaLoop) -> QuotaLoop): QuotaEntry =
        healed(quota.copy(loops = quota.loops.filterNot { it.index == index } + change(particular(quota, index)).copy(index = index)))

    /**
     * Whether period [index] of [quota] may end at [endMillis]: INSIDE the period — after its start, at its regular
     * end at the latest. The quota is then at 100 % from there to the start of the next period.
     */
    fun endInsidePeriod(quota: QuotaEntry, index: Int, endMillis: Long): Boolean =
        regularBounds(quota, index).let { (start, end) -> endMillis > start && endMillis <= end }

    /** The index of [quota]'s last period: 0 when it does not repeat, its count when it has one, else without end. */
    fun lastLoopIndex(quota: QuotaEntry): Int = lastLoopIndex(quota.repeats, quota.repeatCount)

    private fun lastLoopIndex(repeats: Boolean, repeatCount: Int?): Int =
        if (!repeats) 0 else repeatCount?.coerceAtLeast(0) ?: Int.MAX_VALUE

    // ---- The period as the user states it (user rule 2026-10-04) -------------------------------------------------

    /** Whether [quota] says when its period runs — a default configuration may not (the week a quota is made in). */
    fun periodSet(quota: QuotaEntry): Boolean = quota.endMillis > quota.startMillis

    /** The length of [quota]'s period: its own, or a week while it says none. */
    fun periodLengthMillis(quota: QuotaEntry): Long =
        if (periodSet(quota)) quota.endMillis - quota.startMillis else DEFAULT_LOOP_MILLIS

    /**
     * [quota] starting at [startMillis]. An end said as a length after the start ([QuotaEntry.endByDelta]) — or not said
     * yet — moves with it; an end with a date of its own stays, and a start at or past it is refused ([quota] back).
     */
    fun withStart(quota: QuotaEntry, startMillis: Long): QuotaEntry =
        when {
            quota.endByDelta || !periodSet(quota) -> quota.copy(startMillis = startMillis, endMillis = startMillis + periodLengthMillis(quota))
            startMillis < quota.endMillis -> quota.copy(startMillis = startMillis)
            else -> quota
        }

    /** [quota] ending [lengthMillis] after its start; a length of nothing (or less) is refused. */
    fun withLength(quota: QuotaEntry, lengthMillis: Long): QuotaEntry =
        if (lengthMillis <= 0L) quota else quota.copy(endMillis = quota.startMillis + lengthMillis)

    /** [quota] ending at [endMillis]; an end not after the start — or with no start said yet — is refused. */
    fun withEnd(quota: QuotaEntry, endMillis: Long): QuotaEntry =
        if (!periodSet(quota) || endMillis <= quota.startMillis) quota else quota.copy(endMillis = endMillis)

    /** [quota] repeating [count] times after its first period: null without end, 0 not at all. */
    fun withRepeatCount(quota: QuotaEntry, count: Int?): QuotaEntry =
        healed(quota.copy(repeats = count == null || count > 0, repeatCount = count?.takeIf { it > 0 }))

    /**
     * The day a pick of [weekday] stands for, [today] being today: the latest such day up to today — so "Wednesday"
     * said on a Friday is the Wednesday just gone, the period [today] is in.
     */
    fun latestWeekday(weekday: kotlinx.datetime.DayOfWeek, today: kotlinx.datetime.LocalDate): kotlinx.datetime.LocalDate {
        val back = ((today.dayOfWeek.ordinal - weekday.ordinal) % 7 + 7) % 7
        return kotlinx.datetime.LocalDate.fromEpochDays(today.toEpochDays() - back)
    }

    /** A length as the field reads it: `7d`, `1d 12h`, `2h 30min`, `45min` — whole minutes, the largest units first. */
    fun formatLength(millis: Long): String {
        val minutes = (millis / 60_000L).coerceAtLeast(0L)
        val parts = listOf(minutes / 1440L to "d", minutes % 1440L / 60L to "h", minutes % 60L to "min")
            .filter { it.first > 0L }.map { it.first.toString() + it.second }
        return parts.joinToString(" ").ifEmpty { "0min" }
    }

    /**
     * A typed length, in millis: numbers each followed by `d` (days), `h` (hours) or `min` / `m` (minutes), in any
     * order and each at most once (`7d`, `1d 12h`, `90 min`); a bare number is days. Null for anything else, or for
     * a length of nothing.
     */
    fun parseLength(text: String): Long? {
        val trimmed = text.trim().lowercase()
        trimmed.toLongOrNull()?.let { return (it * 1440L * 60_000L).takeIf { ms -> ms > 0L } }
        val token = Regex("""(\d+)\s*(d|h|min|m)""")
        val found = token.findAll(trimmed).toList()
        if (found.isEmpty() || token.replace(trimmed, "").isNotBlank()) return null
        val units = found.map { it.groupValues[2].let { u -> if (u == "m") "min" else u } }
        if (units.distinct().size != units.size) return null
        val minutes = found.sumOf { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return null
            n * when (m.groupValues[2]) { "d" -> 1440L; "h" -> 60L; else -> 1L }
        }
        return (minutes * 60_000L).takeIf { it > 0L }
    }

    /** The most renewals one loop may have — past this the percentage is a blur, not a pace. */
    const val MAX_RENEWALS: Int = 1000

    /** One loop as it runs: its bounds, its amount factor and its renewals — [QuotaLoop] over the regular loop. */
    data class Loop(
        val index: Int,
        val startMillis: Long,
        val endMillis: Long,
        val renewals: Int,
    ) {
        val lengthMillis: Long get() = endMillis - startMillis
    }

    /** Loop [index] of [quota]: the regular one moved by `index` lengths, with what its [QuotaLoop] says of it. */
    fun loop(quota: QuotaEntry, index: Int): Loop {
        val length = (quota.endMillis - quota.startMillis).coerceAtLeast(1L)
        val own = quota.loops.firstOrNull { it.index == index }
        val start = own?.startMillis ?: (quota.startMillis + index * length)
        val end = own?.endMillis ?: (quota.endMillis + index * length)
        return Loop(index, start, end, own?.renewals ?: quota.renewals)
    }

    /**
     * The loop [nowMillis] is in: a loop whose OWN bounds hold it first (a particular loop may start and end
     * elsewhere), else the regular one by index. Before the first loop it is loop 0 (at 0 %); a quota that does not
     * repeat is always loop 0 (at 100 % once it has ended).
     */
    fun loopAt(quota: QuotaEntry, nowMillis: Long): Loop {
        quota.loops.map { loop(quota, it.index) }
            .firstOrNull { nowMillis >= it.startMillis && nowMillis < it.endMillis }
            ?.let { return it }
        if (!quota.repeats || nowMillis < quota.startMillis) return loop(quota, 0)
        val length = (quota.endMillis - quota.startMillis).coerceAtLeast(1L)
        // Past its last period (a counted repeat) it stays the last one, at 100 % — as a quota that does not repeat.
        val index = ((nowMillis - quota.startMillis) / length).coerceAtMost(lastLoopIndex(quota).toLong()).toInt()
        return loop(quota, index)
    }

    /**
     * A loop's **pace profile**: its time cut at every edge of a restrictive period inside it, each stretch with the
     * rate the progression moves at there (the quota's resilience to the periods covering it). Built when the quota
     * or the periods change — never per tick; [progress] then reads an instant off it in a walk of these stretches.
     */
    class Profile(val loop: Loop, private val edges: LongArray, private val rates: DoubleArray) {
        /** The loop's whole weighted time. 0 when the progression never moves in it. */
        val total: Double = weightedUntil(loop.endMillis)

        /** The weighted time from the loop's start to [nowMillis]. */
        fun weightedUntil(nowMillis: Long): Double {
            var sum = 0.0
            for (i in rates.indices) {
                val from = edges[i]
                if (nowMillis <= from) break
                sum += (minOf(nowMillis, edges[i + 1]) - from) * rates[i]
            }
            return sum
        }
    }

    /**
     * [loop]'s profile under [periods] — the restrictive periods on the calendar ([SchedulerDomain.restrictivePeriods]).
     * A stretch no period covers moves at full pace, which is also what a future the calendar has not planned yet is.
     */
    fun profile(quota: QuotaEntry, loop: Loop, periods: List<TaskPanel>): Profile {
        val inside = periods.filter { it.startEpochMillis < loop.endMillis && it.endEpochMillis > loop.startMillis }
        val cuts = hashSetOf(loop.startMillis, loop.endMillis)
        for (period in inside) {
            if (period.startEpochMillis > loop.startMillis) cuts += period.startEpochMillis
            if (period.endEpochMillis < loop.endMillis) cuts += period.endEpochMillis
        }
        val edges = cuts.sorted().toLongArray()
        val rates = DoubleArray((edges.size - 1).coerceAtLeast(0)) { i ->
            val middle = edges[i] + (edges[i + 1] - edges[i]) / 2
            val kinds = inside.filter { it.startEpochMillis <= middle && middle < it.endEpochMillis }.mapTo(HashSet()) { it.restrictiveKind }
            multiplier(quota, kinds)
        }
        return Profile(loop, edges, rates)
    }

    /** Where a quota stands at an instant — what the Search window's actions show. */
    data class Progress(
        val loop: Loop,
        /** The share of the loop's weighted time elapsed, `[0, 1]`. */
        val elapsed: Double,
        /** The target progression: [elapsed] run through the loop's renewals, `[0, 1]` — back to 0 at each renewal. */
        val fraction: Double,
        /** Which renewal the loop is in, from 1. */
        val renewal: Int,
        /** The amount this renewal aims at (the quota's amount times the loop's factor) and the part of it due by now. */
        val amount: Double,
        val targetAmount: Double,
    )

    /** [quota]'s progression at [nowMillis], read off the [profile] of the loop the instant is in. */
    fun progress(quota: QuotaEntry, profile: Profile, nowMillis: Long): Progress {
        val loop = profile.loop
        val elapsed =
            when {
                nowMillis <= loop.startMillis -> 0.0
                nowMillis >= loop.endMillis -> 1.0
                profile.total <= 0.0 -> 0.0
                else -> (profile.weightedUntil(nowMillis) / profile.total).coerceIn(0.0, 1.0)
            }
        val turns = elapsed * loop.renewals
        // The end of the loop is 100 % of its LAST renewal, not 0 % of one that never starts.
        val renewal = if (elapsed >= 1.0) loop.renewals else turns.toInt() + 1
        val fraction = if (elapsed >= 1.0) 1.0 else turns - turns.toInt()
        return Progress(loop, elapsed, fraction, renewal, quota.amount, quota.amount * fraction)
    }

    /** [progress] for a caller with no profile at hand (a test, a one-off): builds the loop's profile first. */
    fun progressAt(quota: QuotaEntry, periods: List<TaskPanel>, nowMillis: Long): Progress =
        progress(quota, profile(quota, loopAt(quota, nowMillis), periods), nowMillis)
}
