package org.example.project.scheduler.persistence

/**
 * PRD §8/§15: one stretch this install's **"I'm away" button** was on for, from [startMillis] to
 * [endMillis] (epoch millis).
 *
 * It is a recorded FACT, not something re-derivable: "I'm away" says *this device's screen is not in use*
 * while the machine stays UNLOCKED, so no OS session log will ever show the stretch. That is the whole
 * reason it needs a row — the layer it feeds
 * ([org.example.project.scheduler.domain.SchedulerDomain.declaredAwayRegions]), the hatch drawn out of it
 * and the §9 record bank have no other source for it, and a restart that forgot it left the calendar with
 * no record of a stretch the `t_p` mode had been 3 for.
 *
 * LOCAL-ONLY — never synced, never a History Unit. The ACCOUNT's away history is the server's
 * (`device_away` / `away_spans`, read by `SchedulerEngine.awaySpansFor`); this is this DEVICE's own layer,
 * which is per device kind and says nothing about a peer.
 */
data class DeclaredAwaySpanRecord(
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * Optional capability of a platform store: durable storage for [DeclaredAwaySpanRecord]s, keyed by
 * `startMillis`. Implemented by the SQLite-backed store; stores without it (e.g. web's localStorage) keep
 * the episodes in memory only and go on forgetting them at a restart. Detected with
 * `store as? DeclaredAwayStore`, the same pattern as [ActiveSessionStore] / [SleepScanCheckpointStore].
 */
interface DeclaredAwayStore {
    /** Every persisted "I'm away" episode, oldest first. Empty on a first run / fresh DB. */
    fun loadDeclaredAwaySpans(): List<DeclaredAwaySpanRecord>

    /**
     * Upserts one episode. The key is its START, so the beat that extends a live episode rewrites that one
     * row rather than accumulating fragments of it — and the row an unclean shutdown leaves behind is the
     * episode closed at the last beat.
     */
    fun saveDeclaredAwaySpan(record: DeclaredAwaySpanRecord)

    /** Drops every episode that had already ENDED at [floorMillis] — the evidence window's back edge. */
    fun pruneDeclaredAwaySpans(floorMillis: Long)
}
