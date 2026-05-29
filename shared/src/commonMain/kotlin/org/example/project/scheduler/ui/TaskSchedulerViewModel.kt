package org.example.project.scheduler.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _state =
        MutableStateFlow(
            store?.load()?.let(SchedulerStateCodec::decode) ?: initial,
        )
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    fun dispatch(intent: SchedulerIntent) {
        val next = SchedulerReducer.reduce(_state.value, intent)
        _state.value = next
        // PRD §5 Persistence: stream every committed mutation to local storage.
        store?.save(SchedulerStateCodec.encode(next))
    }
}

