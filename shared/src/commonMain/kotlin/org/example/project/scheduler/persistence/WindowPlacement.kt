package org.example.project.scheduler.persistence

/**
 * Geometry + visibility of one floating window (e.g. the calendar, reminders, history, sleep windows),
 * persisted **locally only**. This is intentionally separate from [PersistedSnapshot]/[SchedulerState]:
 * window placement must never sync across devices and never produce an Undo/Redo History Unit.
 *
 * [x]/[y] are the window's drag offset (in px) from its centered resting position. [width]/[height] of 0
 * mean "use the window's intrinsic size" — most windows are fixed-size today, so the size columns exist
 * for forward-compatibility with resizable windows rather than being actively varied.
 */
data class WindowPlacement(
    val x: Float,
    val y: Float,
    val width: Float = 0f,
    val height: Float = 0f,
    val visible: Boolean,
)

/**
 * Optional capability of a platform store: durable, local-only storage for [WindowPlacement] keyed by a
 * window id (the `FloatingWindow` enum name). Implemented by the SQLite-backed store; stores without it
 * (e.g. web's localStorage) simply leave window placement in-memory. Detected with `store as?
 * WindowPlacementStore`, the same pattern as [SyncMetaStore].
 */
interface WindowPlacementStore {
    /** All persisted placements, keyed by window id. Empty on a first run / fresh DB. */
    fun loadPlacements(): Map<String, WindowPlacement>

    /** Upserts the placement for one window. */
    fun savePlacement(windowId: String, placement: WindowPlacement)
}
