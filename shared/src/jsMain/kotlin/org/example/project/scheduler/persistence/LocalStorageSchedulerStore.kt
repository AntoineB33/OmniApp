package org.example.project.scheduler.persistence

import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json
import org.example.project.scheduler.platform.Diagnostics

private const val STORAGE_KEY = "omniapp.scheduler-state"
private val json = Json { ignoreUnknownKeys = true }

/**
 * Web persistence (PRD §5): SQLite is not wired on JS yet, so the whole [PersistedSnapshot] (state
 * payload + per-unit history rows) is serialized to a single browser-localStorage entry.
 */
private class LocalStorageSchedulerStore : SchedulerStore {
    override fun load(): PersistedSnapshot? =
        localStorage.getItem(STORAGE_KEY)?.let {
            runCatching { json.decodeFromString<PersistedSnapshot>(it) }.getOrNull()
        }

    // A browser caps an origin's localStorage (about 5 MB), and the whole account is ONE entry: a large history
    // overflows it and `setItem` throws. Swallowed, so the save that failed does not also take down the sync push
    // the view model requests after it — the server still has every edit (`docs/PLATFORMS.md`).
    override fun save(snapshot: PersistedSnapshot) {
        runCatching { localStorage.setItem(STORAGE_KEY, json.encodeToString(snapshot)) }
            .onFailure { Diagnostics.log("web store: local save failed (${it.message}); the server copy is unaffected") }
    }
}

actual fun createDefaultSchedulerStore(): SchedulerStore? = LocalStorageSchedulerStore()
