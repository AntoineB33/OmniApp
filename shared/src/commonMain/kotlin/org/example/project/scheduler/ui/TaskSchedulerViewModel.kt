package org.example.project.scheduler.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

class TaskSchedulerViewModel(
    initial: SchedulerState = SchedulerState.empty(),
) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SchedulerState> = _state.asStateFlow()

    fun dispatch(intent: SchedulerIntent) {
        _state.value = SchedulerReducer.reduce(_state.value, intent)
    }
}

