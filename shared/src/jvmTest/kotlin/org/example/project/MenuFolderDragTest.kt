package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.ui.CustomMenuButton
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.CustomMenuSection

/** User rule 2026-11-01, on real pointer events: a button dragged over another makes a folder, its edge moves it. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class MenuFolderDragTest {
    private class Probe {
        var buttons by mutableStateOf(listOf(item("a"), item("b"), item("c")))
        var grouped: Pair<String, String>? = null
        var moved: Pair<String, String?>? = null
        var clicked = ArrayList<String>()
    }

    private companion object {
        fun item(id: String) = CustomMenuButton(id, "Search", "Button $id")
    }

    private fun scene(probe: Probe, customizing: Boolean = true) =
        ImageComposeScene(width = 300, height = 400, density = Density(1f)) {
            MaterialTheme {
                Box(Modifier.width(188.dp)) {
                    CustomMenuSection(
                        buttons = probe.buttons,
                        onClick = { probe.clicked += it.id },
                        editingId = null,
                        onStartRename = {},
                        onRename = { _, _ -> },
                        onEditDone = {},
                        onRemove = {},
                        customizing = customizing,
                        onMove = { id, before, _ -> probe.moved = id to before },
                        onGroup = { id, target -> probe.grouped = id to target },
                        onToggleFolder = {},
                    )
                }
            }
        }

    private fun drag(scene: ImageComposeScene, from: Offset, by: Float) {
        var time = 0L
        fun frames() = repeat(4) { time += 16_000_000L; scene.render(time) }
        frames()
        val primary = PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Move, from)
        scene.sendPointerEvent(PointerEventType.Press, from, buttons = primary, button = PointerButton.Primary)
        frames()
        val steps = 6
        for (i in 1..steps) {
            scene.sendPointerEvent(PointerEventType.Move, from + Offset(0f, by * i / steps), buttons = primary)
            frames()
        }
        scene.sendPointerEvent(PointerEventType.Release, from + Offset(0f, by), buttons = PointerButtons(), button = PointerButton.Primary)
        frames()
    }

    /** A row is about 40 px tall, and they stand 10 px apart. */
    private val rowPitch = 50f

    @Test
    fun dragged_over_the_middle_of_another_it_groups() {
        val probe = Probe()
        val scene = scene(probe)
        try {
            drag(scene, Offset(60f, 20f), rowPitch)
            assertEquals("a" to "b", probe.grouped)
            assertNull(probe.moved)
        } finally {
            scene.close()
        }
    }

    @Test
    fun dragged_between_two_it_moves() {
        val probe = Probe()
        val scene = scene(probe)
        try {
            drag(scene, Offset(60f, 20f), rowPitch * 1.5f)
            assertEquals("a" to "c", probe.moved)
            assertNull(probe.grouped)
        } finally {
            scene.close()
        }
    }

    @Test
    fun a_press_on_a_folder_reaches_it_and_while_customizing_it_does_not() {
        val folder = CustomMenuButton("f1", "", "Folder", folder = true)
        val probe = Probe().apply { buttons = listOf(folder, item("a").copy(parent = "f1")) }
        fun press(scene: ImageComposeScene) {
            scene.render(0)
            scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 20f))
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 20f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 20f), buttons = PointerButtons(), button = PointerButton.Primary)
            scene.render(100_000_000L)
        }
        var scene = scene(probe, customizing = false)
        try {
            press(scene)
            assertEquals(listOf("f1"), probe.clicked)
        } finally {
            scene.close()
        }
        probe.clicked.clear()
        scene = scene(probe, customizing = true)
        try {
            press(scene)
            assertEquals(emptyList<String>(), probe.clicked)
        } finally {
            scene.close()
        }
    }
}
