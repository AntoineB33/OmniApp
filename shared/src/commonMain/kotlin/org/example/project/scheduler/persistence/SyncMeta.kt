package org.example.project.scheduler.persistence

/**
 * Local cross-device sync bookkeeping (PRD §5), mirrored as the single `sync_meta` row.
 *
 * [deviceId] is a stable per-install id (an HLC tie-breaker for the Phase 2 entity merge). [lastKnownRevision]
 * is the remote `scheduler_snapshot.revision` this device last saw/wrote — the optimistic-concurrency baseline.
 * [dirty] is set when local state has changes not yet pushed. The cached [accessToken]/[refreshToken]/[userId]/
 * [email] let a signed-in session survive app restarts (refreshed silently on next launch).
 *
 * [acknowledgedLogoutAtMillis] is this session's baseline for the server-side force-logout (`account_logout`,
 * set by the account-empty script): the account's `logout_at` in epoch millis that this device saw when it
 * logged in. `null` = unknown (a pre-feature session, or a login whose fetch failed); `0` = seen, no marker
 * yet. On the next reconcile the device signs itself out when the server marker advances past this baseline,
 * so a still-running app can't re-seed a snapshot the empty just deleted.
 */
data class SyncMeta(
    val deviceId: String,
    val lastKnownRevision: Long = 0,
    val dirty: Boolean = false,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val userId: String? = null,
    val email: String? = null,
    val acknowledgedLogoutAtMillis: Long? = null,
)

/** Durable storage for [SyncMeta]; implemented by the platform store next to the scheduler payload. */
interface SyncMetaStore {
    fun loadSyncMeta(): SyncMeta?

    fun saveSyncMeta(meta: SyncMeta)
}
