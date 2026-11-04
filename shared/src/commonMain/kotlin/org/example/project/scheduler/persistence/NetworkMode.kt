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

/**
 * User rule 2026-11-04 (docs/SERVER_SETUP.md): **the Supabase project this device syncs with**, chosen on its Settings
 * page. Device-level and local-only, like [NetworkModeStore] — never synced (an account lives on ONE server), never a
 * History Unit — in its own one-row table (`server_choice`, migration 19.sqm). A store without it (web's in-memory
 * store) uses the built-in project.
 */
interface ServerChoiceStore {
    /** The project chosen, or null for the one built into the app. */
    fun loadServerChoice(): org.example.project.scheduler.sync.SupabaseConfig?

    /** [config] chosen; null goes back to the built-in project. */
    fun saveServerChoice(config: org.example.project.scheduler.sync.SupabaseConfig?)
}
