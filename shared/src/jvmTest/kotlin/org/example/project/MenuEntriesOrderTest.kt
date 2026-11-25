package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.domain.ContextDefault
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.ui.ActionMenu
import org.example.project.ui.LocalContextDefaults
import org.example.project.ui.MenuAction

/**
 * User rule 2026-11-25: a row's menu lists the entries its context's "Entries, in order" names, in that order — whatever
 * order the code gives them in — and no other.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class MenuEntriesOrderTest {
    private fun shown(defaults: List<ContextDefault>): List<String> {
        val labels = listOf("copy path", "unnamed entry", "add", "copy id (ctrl c)")
        val scene = onEdt {
            ImageComposeScene(800, 800, density = Density(1f)) {
                CompositionLocalProvider(LocalContextDefaults provides defaults) {
                    Box(Modifier.fillMaxSize()) {
                        ActionMenu("task cell menu", open = true, onDismiss = {}, actions = labels.map { MenuAction(it) {} })
                    }
                }
            }
        }
        try {
            repeat(6) { onEdt { scene.render() } }
            val texts = onEdt {
                scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
            }
            return texts.filter { it in labels }
        } finally {
            onEdt { scene.close() }
        }
    }

    @Test
    fun a_row_menu_lists_what_its_context_names_in_that_order() {
        assertEquals(listOf("add", "copy id (ctrl c)", "copy path"), shown(emptyList()))
        val changed = ContextDomain.written(
            emptyList(), "menu:task cell menu",
            ContextDomain.configOf(emptyList(), "menu:task cell menu").copy(entries = listOf("copy path", "add")),
        )
        assertEquals(listOf("copy path", "add"), shown(changed))
    }
}
