package org.example.project.scheduler.persistence

import kotlinx.browser.localStorage

private const val STORAGE_KEY = "omniapp.scheduler-state"

private class LocalStorageSchedulerStore : SchedulerStore {
    override fun load(): String? = localStorage.getItem(STORAGE_KEY)

    override fun save(data: String) {
        localStorage.setItem(STORAGE_KEY, data)
    }
}

actual fun createDefaultSchedulerStore(): SchedulerStore? = LocalStorageSchedulerStore()
