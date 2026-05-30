package org.example.project.scheduler.persistence

/**
 * Platform-local persistence for the scheduler (PRD §5).
 * Returns `null` on platforms without durable storage wired yet.
 */
expect fun createDefaultSchedulerStore(): SchedulerStore?
