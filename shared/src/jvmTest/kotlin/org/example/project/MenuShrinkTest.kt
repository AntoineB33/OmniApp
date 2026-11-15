package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.LocalMenuShortfall
import org.example.project.ui.shrinksInMenu

/**
 * Anomaly 2026-11-15: *"there are two vertical scrollbars when the height is adjusted to not hide the pip"*. A menu made
 * shorter hands what it lost to the part that scrolls on its own ([shrinksInMenu]): that part is that much shorter, so
 * the menu's own column has nothing to scroll — one bar — and back at its own height it is as tall as before.
 */
class MenuShrinkTest {
    @Test
    fun theListThatScrollsGivesUpTheHeightTheMenuLost() {
        var shortfall by mutableIntStateOf(0)
        var listHeight = -1
        var outer: androidx.compose.foundation.ScrollState? = null
        val scene = onEdt {
            ImageComposeScene(400, 1000, density = Density(1f)) {
                val menuScroll = rememberScrollState()
                outer = menuScroll
                CompositionLocalProvider(LocalMenuShortfall provides shortfall) {
                    // The menu's own column, as the desktop's menu window draws it, shown 300 tall at its own height.
                    Box(Modifier.heightIn(max = (300 - shortfall).dp)) {
                        Column(Modifier.verticalScroll(menuScroll)) {
                            Box(Modifier.height(60.dp).width(100.dp))
                            // The ▾ tree's column: its own scroll, at most 240 tall, holding 1000.
                            Column(
                                Modifier.shrinksInMenu().heightIn(max = 240.dp).onSizeChanged { listHeight = it.height }
                                    .verticalScroll(rememberScrollState()),
                            ) { Box(Modifier.height(1000.dp).width(100.dp)) }
                        }
                    }
                }
            }
        }
        try {
            repeat(2) { onEdt { scene.render() } }
            assertEquals(240, listHeight, "at its own height the list is as tall as it was")
            assertEquals(0, onEdt { outer!!.maxValue }, "and the menu has nothing to scroll")
            shortfall = 100
            repeat(3) { onEdt { scene.render() } }
            assertEquals(140, listHeight, "shown 100 shorter, the list is 100 shorter")
            assertEquals(0, onEdt { outer!!.maxValue }, "and the menu still has nothing to scroll: one scrollbar")
            shortfall = 0
            repeat(3) { onEdt { scene.render() } }
            assertEquals(240, listHeight, "back at its own height, the list grows back")
        } finally {
            onEdt { scene.close() }
        }
    }
}
