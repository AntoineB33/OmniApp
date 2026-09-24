package org.example.project.scheduler.persistence

/**
 * Geometry + visibility of one floating window (e.g. the calendar, reminders, history, sleep windows),
 * persisted **locally only**. This is intentionally separate from [PersistedSnapshot]/[SchedulerState]:
 * window placement must never sync across devices and never produce an Undo/Redo History Unit.
 *
 * [x]/[y] are the window's drag offset (in px) from its centered resting position, and [width]/[height] its
 * size; 0 means "use the window's default size". Both are the window's NORMAL geometry — what it has with no
 * axis filled — which is what un-filling it goes back to.
 *
 * [fillWidth]/[fillHeight] (both = maximized) and [minimized] (reduced to the bar along the bottom of the app)
 * are the window's chrome state, so a window comes back the way it was left. [config] is the window's OWN
 * configuration, serialized by that window's owner (the Search window's query and kinds), or null.
 */
data class WindowPlacement(
    val x: Float,
    val y: Float,
    val width: Float = 0f,
    val height: Float = 0f,
    val visible: Boolean,
    val fillWidth: Boolean = false,
    val fillHeight: Boolean = false,
    val minimized: Boolean = false,
    val config: String? = null,
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
