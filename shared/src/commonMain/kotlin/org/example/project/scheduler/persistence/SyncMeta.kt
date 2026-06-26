package org.example.project.scheduler.persistence

/**
 * Local cross-device sync bookkeeping (PRD §5), mirrored as the single `sync_meta` row.
 *
 * [deviceId] is a stable per-install id (an HLC tie-breaker for the Phase 2 entity merge). [lastKnownRevision]
 * is the remote `scheduler_snapshot.revision` this device last saw/wrote — the optimistic-concurrency baseline.
 * [dirty] is set when local state has changes not yet pushed. The cached [accessToken]/[refreshToken]/[userId]/
 * [email] let a signed-in session survive app restarts (refreshed silently on next launch).
 */
data class SyncMeta(
    val deviceId: String,
    val lastKnownRevision: Long = 0,
    val dirty: Boolean = false,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val userId: String? = null,
    val email: String? = null,
)

/** Durable storage for [SyncMeta]; implemented by the platform store next to the scheduler payload. */
interface SyncMetaStore {
    fun loadSyncMeta(): SyncMeta?

    fun saveSyncMeta(meta: SyncMeta)
}
