package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.SectionSeparator

class SectionSeparatorDragTest {
    private fun dragged(steps: Int, stepPx: Float, renderEachStep: Boolean, density: Float): Float {
        var width = 0f
        ImageComposeScene(1000, 400, density = Density(density)) {
            var w by remember { mutableStateOf(300f) }
            width = w
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight())
                SectionSeparator(vertical = true, onDrag = { d -> w -= d / density })
                Box(Modifier.width(w.dp).fillMaxHeight())
            }
        }.use { scene ->
            scene.render()
            val x = 1000f - 300f * density - 4.5f * density
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, 200f))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, 200f))
            scene.render()
            for (step in 1..steps) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(x - stepPx * step, 200f))
                if (renderEachStep) scene.render()
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(x - stepPx * steps, 200f))
            scene.render()
        }
        return width
    }

    @Test
    fun the_line_follows_the_pointer() {
        for (density in listOf(1f, 1.5f)) for (render in listOf(true, false)) {
            assertEquals(400f, dragged(100, density, render, density), 0.5f, "1px steps, density $density, render $render")
            assertEquals(400f, dragged(10, 10f * density, render, density), 0.5f, "10px steps, density $density, render $render")
        }
    }
}
