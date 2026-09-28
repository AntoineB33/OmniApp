package org.example.project.scheduler.persistence

import org.example.project.scheduler.domain.BankedBreak
import org.example.project.scheduler.domain.FrozenScreenBreaks

/**
 * Optional capability of a platform store: durable storage for the screen breaks the now-line has banked
 * ([FrozenScreenBreaks]) — `docs/scheduler_requirements.md` § *frozen past*.
 *
 * LOCAL-ONLY — never synced, never a History Unit. Each device banks the breaks its own line passed; the record is
 * a fact about what this device's scheduler returned, and a restart that forgot it would re-derive the past off
 * whatever the environment says by then, which is the very drift the record exists to end. Pruned past
 * [org.example.project.scheduler.domain.SchedulerDomain.SCREEN_BREAK_HISTORY_RETENTION_MILLIS].
 *
 * Implemented by the SQLite-backed store; a store without it keeps the record in memory only. Detected with
 * `store as? FrozenScreenBreakStore`, the same pattern as [DeclaredAwayStore].
 */
interface FrozenScreenBreakStore {
    /** The banked record, or null on a first run / fresh DB (nothing banked yet). */
    fun loadFrozenScreenBreaks(): FrozenScreenBreaks?

    /**
     * Adds [added], drops every break that ended at or before [pruneBeforeMillis], and stores the front and where the
     * line was ([FrozenScreenBreaks.lineMillis]).
     */
    fun saveFrozenScreenBreaks(added: List<BankedBreak>, untilMillis: Long, lineMillis: Long, pruneBeforeMillis: Long)
}
