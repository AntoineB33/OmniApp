package org.example.project

import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.scheduler.state.SchedulerState

class SharedCommonTest {
    @Test
    fun scheduler_module_is_present_on_common_source_set() {
        assertTrue(SchedulerState.empty().tasks.isNotEmpty())
    }
}
