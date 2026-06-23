package org.example.project.scheduler.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

class TaskSchedulerViewModel(
    initial: SchedulerState = SchedulerState.empty(),
    private val store: SchedulerStore? = null,
) : ViewModel() {
    // PRD §5 Initialization: load from local persistence when present, otherwise start
    // from the empty DB (root → main).
    private val _state = MutableStateFlow(loadInitialState(store, initial))
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    fun dispatch(intent: SchedulerIntent) {
        val current = _state.value
        val next = SchedulerReducer.reduce(current, intent)
        // No-op intents (e.g. a RefreshSchedule tick still within the deadline) return the same
        // instance; skip the state push and persist so the timer doesn't churn storage.
        if (next === current) return
        _state.value = next
        // PRD §5 Persistence: stream every committed mutation to local storage.
        store?.save(SchedulerStateCodec.encode(next))
    }

    companion object {
        /** PRD §5: reload persisted state; an interrupted Edit Mode session is canceled. */
        fun loadInitialState(store: SchedulerStore?, initial: SchedulerState): SchedulerState {
            val loaded = store?.load()?.let(SchedulerStateCodec::decode) ?: initial
            // PRD §6: revert any changes committed under the diverged debug clock before they reach the
            // running app, so a fast-forwarded session never pollutes the real saved data on restart.
            val clean = SchedulerReducer.rollbackDebugTainted(loaded)
            // PRD §15: side tasks are a hardcoded set (not persisted user data); seed them onto whatever
            // was loaded so they are always present in the running app, never in the bare test states.
            val seeded = clean.copy(sideTasks = SchedulerDomain.DEFAULT_SIDE_TASKS)
            return if (seeded.editSession != null) {
                SchedulerReducer.reduce(seeded, SchedulerIntent.CancelEdit)
            } else {
                seeded
            }
        }
    }
}

