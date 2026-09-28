package org.example.project.scheduler.domain

/**
 * One of the three dynamic restrictive periods, **banked as the now-line passed it** — `[startMillis, endMillis)`,
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
 * The front trails the line only where the past may still legitimately move: a "no on-screen task" chain the
 * line is inside may still pull a break back onto its start (the requirements' dynamic-period rule), so the
 * front waits at that chain's start until the chain ends ([SchedulerDomain.bankScreenBreaks]).
 *
 * Kept per device and never synced (`docs/invariants/screen-breaks.md`); pruned past
 * [SchedulerDomain.SCREEN_BREAK_HISTORY_RETENTION_MILLIS] — the requirements' exception 2, a limit on the memory the
 * frozen timeline history may take.
 */
data class FrozenScreenBreaks(
    val breaks: List<BankedBreak>,
    val untilMillis: Long,
    /**
     * Where the line last was when this record was banked — at or past [untilMillis] (the front waits behind the line
     * inside a chain). An engine that starts finding it far behind the clock was not running in between, which is
     * the requirements' *"no CPU were available during this period"*: the line is walked there, in mode 2.
     */
    val lineMillis: Long = untilMillis,
    /**
     * The breaks the line has STARTED but that are not banked yet (in progress at the line, or inside the "no
     * on-screen task" chain the front waits at) — in memory only, never persisted. Work is never recorded inside one
     * of these either: the task panel under a break the line has just reached is recorded up to the instant the plan
     * is re-made, a few milliseconds into the break.
     */
    val pending: List<BankedBreak> = emptyList(),
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

    companion object {
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
