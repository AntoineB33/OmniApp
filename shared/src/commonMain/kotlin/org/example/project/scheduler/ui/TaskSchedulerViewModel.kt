package org.example.project.scheduler.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

class TaskSchedulerViewModel(
    initial: SchedulerState = SchedulerState.empty(),
    private val store: SchedulerStore? = null,
    // Off the main thread: the debounced SQLite write is blocking IO. Injectable so tests can drive it
    // with a virtual-time test dispatcher.
    saveDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    // PRD §5 Initialization: load from local persistence when present, otherwise start
    // from the empty DB (root → main).
    private val _state = MutableStateFlow(loadInitialState(store, initial))
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    // PRD §5 Persistence: every change updates the in-memory state immediately; the SQLite DB is then
    // updated on a debounce so a burst of edits (e.g. typing a cell) collapses into one write instead
    // of one per keystroke.
    private val saveScope = CoroutineScope(SupervisorJob() + saveDispatcher)
    private var saveJob: Job? = null

    fun dispatch(intent: SchedulerIntent) {
        val current = _state.value
        val next = SchedulerReducer.reduce(current, intent)
        // No-op intents (e.g. a RefreshSchedule tick still within the deadline) return the same
        // instance; skip the state push and persist so the timer doesn't churn storage.
        if (next === current) return
        _state.value = next
        scheduleSave()
    }

    /** Schedules a debounced persist of the current state, replacing any pending one. */
    private fun scheduleSave() {
        val store = store ?: return
        saveJob?.cancel()
        saveJob =
            saveScope.launch {
                delay(SAVE_DEBOUNCE_MILLIS)
                store.save(SchedulerStateCodec.encodeSnapshot(_state.value))
            }
    }

    /**
     * Cancels any pending debounce and writes the current state synchronously. Call before the app
     * closes so a change made within the debounce window is not lost.
     */
    fun flush() {
        val store = store ?: return
        saveJob?.cancel()
        saveJob = null
        store.save(SchedulerStateCodec.encodeSnapshot(_state.value))
    }

    override fun onCleared() {
        flush()
        saveScope.cancel()
        super.onCleared()
    }

    companion object {
        /** PRD §5: how long edits must be quiet before the debounced write to SQLite fires. */
        private const val SAVE_DEBOUNCE_MILLIS = 400L

        /** PRD §5: reload persisted state; an interrupted Edit Mode session is canceled. */
        fun loadInitialState(store: SchedulerStore?, initial: SchedulerState): SchedulerState {
            val loaded = store?.load()?.let(SchedulerStateCodec::decodeSnapshot) ?: initial
            // PRD §6: revert any changes committed under the diverged debug clock before they reach the
            // running app, so a fast-forwarded session never pollutes the real saved data on restart.
            val clean = SchedulerReducer.rollbackDebugTainted(loaded)
            // PRD §15: side tasks are a hardcoded set (not persisted user data); seed them onto whatever
            // was loaded so they are always present in the running app, never in the bare test states.
            // The sleep schedule is seeded with the default only when none was persisted, so production
            // always has a sleep window (07:30 / 8h30) while bare test states keep `sleep = null`.
            val seeded =
                clean.copy(
                    sideTasks = SchedulerDomain.DEFAULT_SIDE_TASKS,
                    sleep = clean.sleep ?: SchedulerDomain.DEFAULT_SLEEP,
                )
            return if (seeded.editSession != null) {
                SchedulerReducer.reduce(seeded, SchedulerIntent.CancelEdit)
            } else {
                seeded
            }
        }
    }
}
