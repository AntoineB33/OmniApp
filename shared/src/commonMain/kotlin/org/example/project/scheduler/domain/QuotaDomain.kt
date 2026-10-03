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
        val end = if (entry.endMillis > entry.startMillis) entry.endMillis else entry.startMillis + DEFAULT_LOOP_MILLIS
        val resilience = entry.resilience.mapValues { PeriodKinds.clamp(it.value) }
        val loops =
            entry.loops
                .filter { it.index >= 0 && (entry.repeats || it.index == 0) }
                .distinctBy { it.index }
                .map { loop ->
                    val bounded = loop.startMillis != null && loop.endMillis != null && loop.endMillis > loop.startMillis
                    loop.copy(
                        startMillis = loop.startMillis.takeIf { bounded },
                        endMillis = loop.endMillis.takeIf { bounded },
                        amountFactor = loop.amountFactor.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0,
                        renewals = loop.renewals.coerceIn(1, MAX_RENEWALS),
                    )
                }
                .filterNot { it == QuotaLoop(it.index) }
                .sortedBy { it.index }
        val healed = entry.copy(amount = amount, endMillis = end, resilience = resilience, loops = loops)
        return if (healed == entry) entry else healed
    }

    /** The most renewals one loop may have — past this the percentage is a blur, not a pace. */
    const val MAX_RENEWALS: Int = 1000

    /** One loop as it runs: its bounds, its amount factor and its renewals — [QuotaLoop] over the regular loop. */
    data class Loop(
        val index: Int,
        val startMillis: Long,
        val endMillis: Long,
        val amountFactor: Double,
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
        return Loop(index, start, end, own?.amountFactor ?: 1.0, own?.renewals ?: 1)
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
        val index = ((nowMillis - quota.startMillis) / length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
            PeriodKinds.multiplier(quota.resilience, kinds)
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
        val amount = quota.amount * loop.amountFactor
        return Progress(loop, elapsed, fraction, renewal, amount, amount * fraction)
    }

    /** [progress] for a caller with no profile at hand (a test, a one-off): builds the loop's profile first. */
    fun progressAt(quota: QuotaEntry, periods: List<TaskPanel>, nowMillis: Long): Progress =
        progress(quota, profile(quota, loopAt(quota, nowMillis), periods), nowMillis)
}
