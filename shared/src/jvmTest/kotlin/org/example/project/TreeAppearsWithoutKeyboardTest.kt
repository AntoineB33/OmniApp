package org.example.project

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskTreeView
import org.example.project.ui.OutlinedTextField

/**
 * Anomaly 2026-11-12: *"I tried to type something in the search bar of the action section, but only the first character
 * got there, and it is not in edit mode anymore."* The letter re-sorted the actions, the "Children" action's sub-tree was
 * drawn anew, and a tree drawn took the keyboard whatever it was told. A tree that may not have the keyboard
 * (`keyboardActive = false`, a Search sub-tree nobody went to) leaves it where it is as it appears.
 */
class TreeAppearsWithoutKeyboardTest {
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test
    fun a_tree_appearing_without_the_keyboard_leaves_the_field_its_caret() {
        var state = SchedulerState.empty()
        state = SchedulerReducer.reduce(state, SchedulerIntent.SetCellTitle(state.lists[state.rootListId]!!.cellIds.last(), "Apple"))
        var fieldFocused = false
        var showTree by mutableStateOf(false)
        val scene = onEdt {
            ImageComposeScene(800, 600, density = Density(1f)) {
                MaterialTheme {
                    Column {
                        val focus = remember { FocusRequester() }
                        var text by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.focusRequester(focus).onFocusChanged { fieldFocused = it.isFocused },
                        )
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        if (showTree) {
                            TaskTreeView(
                                state = state,
                                priorities = emptyMap(),
                                onIntent = {},
                                keyboardActive = false,
                                modifier = Modifier.fillMaxWidth().height(300.dp),
                            )
                        }
                    }
                }
            }
        }
        try {
            repeat(4) { onEdt { scene.render() } }
            assertTrue(fieldFocused, "the field holds the caret")
            showTree = true
            repeat(4) { onEdt { scene.render() } }
            assertTrue(fieldFocused, "a tree that may not have the keyboard did not take it as it appeared")
        } finally {
            onEdt { scene.close() }
        }
    }
}
