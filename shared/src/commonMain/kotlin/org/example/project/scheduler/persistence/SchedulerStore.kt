package org.example.project.scheduler.persistence

/**
 * Local persistence sink for the scheduler tree (PRD §5 Persistence).
 *
 * Implementations supply platform-specific storage (e.g. a JSON file on desktop).
 * The codec lives in [SchedulerStateCodec]; this interface only moves the serialized
 * payload to/from durable storage.
 */
interface SchedulerStore {
    /** Returns the previously saved payload, or `null` for an empty/first-run DB. */
    fun load(): String?

    /** Persists the latest serialized state. Called after every committed mutation. */
    fun save(data: String)
}
