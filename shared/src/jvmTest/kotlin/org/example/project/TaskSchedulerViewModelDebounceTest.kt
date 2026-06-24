package org.example.project

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.state.AppWindow
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TaskSchedulerViewModelDebounceTest {
    private class RecordingStore : SchedulerStore {
        var saveCount = 0
        var last: PersistedSnapshot? = null

        override fun load(): PersistedSnapshot? = null

        override fun save(snapshot: PersistedSnapshot) {
            saveCount++
            last = snapshot
        }
    }

    @Test
    fun rapid_dispatches_collapse_into_one_debounced_save() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val store = RecordingStore()
            val vm = TaskSchedulerViewModel(store = store, saveDispatcher = dispatcher)

            // Three state-changing intents in quick succession (each window focus differs).
            vm.dispatch(SchedulerIntent.FocusWindow(AppWindow.Calendar))
            vm.dispatch(SchedulerIntent.FocusWindow(AppWindow.Reminders))
            vm.dispatch(SchedulerIntent.FocusWindow(AppWindow.History))

            // Before the debounce window elapses, nothing is written.
            runCurrent()
            assertEquals(0, store.saveCount)

            // After the window elapses, the burst coalesces into exactly one write.
            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, store.saveCount)
        }
    }

    @Test
    fun flush_writes_immediately_and_cancels_pending_debounce() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val store = RecordingStore()
            val vm = TaskSchedulerViewModel(store = store, saveDispatcher = dispatcher)

            vm.dispatch(SchedulerIntent.FocusWindow(AppWindow.Calendar))
            vm.flush()
            assertEquals(1, store.saveCount)

            // The pending debounced job was canceled by flush, so no second write fires.
            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, store.saveCount)
        }
    }
}
