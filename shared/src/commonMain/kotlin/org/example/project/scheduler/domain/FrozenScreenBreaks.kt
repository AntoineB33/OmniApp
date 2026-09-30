package org.example.project.scheduler.domain

/**
 * One of the three dynamic restrictive periods, **banked as the now-line reached it** — `[startMillis, endMillis)`,
 * in the role [label] names ([DynamicPeriods.LABEL_20S] / [DynamicPeriods.LABEL_5MIN] / [DynamicPeriods.LABEL_15MIN]).
 *
 * A recorded fact, not a derivation: the walk that placed it read an environment, a set of tasks, a
 * configuration and a mode that may all be different by now, and none of those may move it any more
 * (`docs/scheduler_requirements.md` § *frozen past*).
 */
data class BankedBreak(val label: String, val startMillis: Long, val endMillis: Long)

/**
 * `docs/scheduler_requirements.md` § *frozen past*: **the three dynamic periods behind the banked front** —
 * every occurrence the line has made a fact of, and [untilMillis], the instant up to which they are the whole
 * answer. Before it the placement is this record; from it on the walk continues from the bars the record sets
 * ([DynamicPeriods.Frozen]).
 *
 * The front is the line ([SchedulerDomain.bankScreenBreaks]): no rule of the requirements moves a break behind the
 * line, so nothing there waits to be decided. A break the line is inside is banked whole, from the instant it
 * started; the one thing that removes a banked break is the requirements' own exception (a pose the line is inside
 * when it switches to mode 1). **The calendar draws the past from this record and nothing else**
 * ([SchedulerDomain.takenScreenBreakPanels]).
 *
 * Kept per device and never synced (`docs/invariants/screen-breaks.md`); pruned past
 * [SchedulerDomain.SCREEN_BREAK_HISTORY_RETENTION_MILLIS] — the requirements' exception 2, a limit on the memory the
 * frozen timeline history may take.
 */
data class FrozenScreenBreaks(
    val breaks: List<BankedBreak>,
    val untilMillis: Long,
    /**
     * Where the line last was when this record was banked — the front itself, since the front is the line. An engine that starts finding it far behind the clock was not running in between, which is
     * the requirements' *"no CPU were available during this period"*: the line is walked there, in mode 2.
     */
    val lineMillis: Long = untilMillis,
) {
    /**
     * Whether the walk may continue from this record at the line [nowMillis]: its front is not ahead of the line
     * (the front never passes the line, so a record that is ahead was banked on another clock — a clock set back, a
     * record read against a timeline it was not banked on) and not older than [STALE_AFTER_MILLIS] (the app was not
     * running: nothing was banked for that stretch, and a walk across it is re-derived from the line's own origin
     * rather than dragged across days). The older record still answers for the past it holds.
     */
    fun continuesAt(nowMillis: Long): Boolean =
        untilMillis <= nowMillis && untilMillis >= nowMillis - STALE_AFTER_MILLIS

    /**
     * The banked break covering [millis], or null — bisected, since it is asked at every advance of the line and the
     * record holds up to 90 days ([breaks] is kept sorted by start, and no break lasts anywhere near a day).
     */
    fun coveringAt(millis: Long): BankedBreak? {
        var lo = 0
        var hi = breaks.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (breaks[mid].startMillis <= millis) lo = mid + 1 else hi = mid
        }
        var k = lo - 1
        while (k >= 0 && breaks[k].startMillis > millis - COVERING_REACH_MILLIS) {
            val b = breaks[k]
            if (b.startMillis <= millis && millis < b.endMillis) return b
            k--
        }
        return null
    }

    companion object {
        /** How far back a break covering an instant may have started: none lasts anywhere near a day. */
        private const val COVERING_REACH_MILLIS: Long = 24L * 60L * 60L * 1000L

        /** How far behind the line a front may be for the walk to continue from it ([continuesAt]). */
        const val STALE_AFTER_MILLIS: Long = 2L * 24L * 60L * 60L * 1000L
    }

    /** The record as the walk reads it: each banked break with its role's CURRENT cadence and its own length. */
    fun toWalk(specs: List<DynamicPeriods.Spec>): DynamicPeriods.Frozen {
        val byLabel = specs.associateBy { it.label }
        val instances =
            breaks.filter { it.endMillis > it.startMillis }.sortedBy { it.startMillis }.map { banked ->
                val cadence = byLabel[banked.label]?.cadenceMillis ?: 0L
                DynamicPeriods.Instance(
                    DynamicPeriods.Spec(banked.label, banked.endMillis - banked.startMillis, cadence),
                    banked.startMillis,
                )
            }
        return DynamicPeriods.Frozen(instances, untilMillis)
    }
}
