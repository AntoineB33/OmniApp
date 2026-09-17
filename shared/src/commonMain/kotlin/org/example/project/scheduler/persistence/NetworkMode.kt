package org.example.project.scheduler.persistence

/**
 * `docs/invariants/sync-and-accounts.md` § *Working offline*: whether the user switched this DEVICE to work
 * completely offline. Device-level and local-only — never synced, never a History Unit, not per account — and kept
 * in its own single-row table so the whole-row `sync_meta` writes cannot clobber it. The sync engine reads it with
 * `store as? NetworkModeStore`; a store without it (web's in-memory store) simply starts online.
 */
interface NetworkModeStore {
    /** The user's last choice: true = offline, false = online, null = never chosen on this device. */
    fun loadOfflineChoice(): Boolean?

    fun saveOfflineChoice(offline: Boolean)
}
