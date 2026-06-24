package org.example.project.scheduler.persistence

import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json

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

    override fun save(snapshot: PersistedSnapshot) {
        localStorage.setItem(STORAGE_KEY, json.encodeToString(snapshot))
    }
}

actual fun createDefaultSchedulerStore(): SchedulerStore? = LocalStorageSchedulerStore()
