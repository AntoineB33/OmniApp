package org.example.project.scheduler.persistence

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import org.example.project.scheduler.persistence.db.SchedulerDatabase

actual fun createDefaultSchedulerStore(): SchedulerStore? {
    // NativeSqliteDriver creates/migrates the schema from PRAGMA user_version automatically.
    val driver = NativeSqliteDriver(SchedulerDatabase.Schema, "scheduler-state.db")
    return SqlDelightSchedulerStore(SchedulerDatabase(driver))
}
