package org.example.project.scheduler.persistence

/**
 * Local cross-device sync bookkeeping (PRD §5), mirrored as the single `sync_meta` row (device-level fields)
 * plus the `account_sync` row of the ACTIVE account ([userId]).
 *
 * [deviceId] is a stable per-install id (an HLC tie-breaker for the Phase 2 entity merge), allocated once and
 * kept across sign-outs. The cached [accessToken]/[refreshToken]/[userId]/[email] let the active account's
 * session survive app restarts (refreshed silently on next launch).
 *
 * The app is ALWAYS connected to an account: [userId] is null only in the brief window before the first
 * account exists (a fresh install that has not yet been able to reach Supabase to create its GUEST account —
 * see [org.example.project.scheduler.sync.SchedulerSyncEngine.ensureAccount]). A GUEST account is a real
 * account with no credentials, so it is exactly the one with `email == null`.
 *
 * [lastKnownRevision], [dirty] and [acknowledgedLogoutAtMillis] are per-ACCOUNT, stored in `account_sync`
 * under [userId] and loaded back with it:
 * - [lastKnownRevision] is the remote `scheduler_snapshot.revision` this device last saw/wrote for that
 *   account — an optimistic-concurrency baseline that would be meaningless (and destructive) if it travelled
 *   across an account switch.
 * - [dirty] is set when that account's local state has changes not yet pushed.
 * - [acknowledgedLogoutAtMillis] is the login baseline for the server-side force-logout (`account_logout`,
 *   set by the account-empty script): the account's `logout_at` in epoch millis this device saw when it
 *   logged in. `null` = unknown (a pre-feature session, or a login whose fetch failed); `0` = seen, no marker
 *   yet. On the next reconcile the device signs itself out when the server marker advances past this
 *   baseline, so a still-running app can't re-seed a snapshot the empty just deleted.
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

    /**
     * Persists [meta]. The device-level fields are always written. The per-account fields
     * ([SyncMeta.lastKnownRevision], [SyncMeta.dirty], [SyncMeta.acknowledgedLogoutAtMillis]) are written
     * under [SyncMeta.userId] — but a save that CHANGES the active account is an account switch, and must
     * leave the account being switched to with its own persisted bookkeeping rather than inheriting the
     * previous account's (see [SqlDelightSchedulerStore.saveSyncMeta]).
     */
    fun saveSyncMeta(meta: SyncMeta)

    /**
     * Re-keys the local scheduler partition of the `''` (unclaimed) account onto [accountId], if any — the
     * data a device wrote before it had any account at all (a fresh install working offline, or a pre-v9 DB
     * migrated while signed out). Called once, when the device's first GUEST account is created, so that
     * work is not orphaned. A no-op when nothing is unclaimed or when [accountId] already has a partition.
     * Default no-op for stores that keep no per-account partitions.
     */
    fun adoptUnclaimedAccountData(accountId: String) = Unit
}
