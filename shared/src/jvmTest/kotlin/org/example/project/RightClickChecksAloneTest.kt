package org.example.project

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.scheduler.domain.PressDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.ui.CHECK_ONLY_LABEL
import org.example.project.ui.CheckBoxDropDown
import org.example.project.ui.LocalContextDefaults

/**
 * User rule 2026-11-27: *"Add a search configuration to make the right-click on a category of rows to have their check
 * box checked and every other check box in the list unchecked."* "Right-click checks it alone", on in a context: the
 * right-click checks the row alone; Shift + right-click still opens the row menu, so nothing it offers is out of reach.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class RightClickChecksAloneTest {

    private val defaults = ContextDomain.written(
        emptyList(), ContextDomain.DROP_DOWN,
        ContextDomain.configOf(emptyList(), ContextDomain.DROP_DOWN).copy(rightClickChecksAlone = true),
    )

    @Test
    fun on_in_the_context_a_right_click_checks_the_row_alone_and_shift_opens_its_menu() = onEdt<Unit> {
        var checked by mutableStateOf(setOf(0, 2, 3))
        var deployed by mutableStateOf(false)
        val scene = ImageComposeScene(width = 500, height = 700, density = Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalContextDefaults provides defaults) {
                    Column(Modifier.fillMaxSize()) {
                        CheckBoxDropDown("right_click_checks_alone",
                            options = listOf(0, 1, 2, 3), checked = checked, face = "kinds", label = { "Kind number $it" },
                            onChange = { checked = it }, deploy = !deployed, onDeployed = { deployed = true },
                        )
                    }
                }
            }
        }
        try {
            var time = 0L
            fun frames() = repeat(20) { time += 16_000_000L; scene.render(time) }
            fun texts() = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
            fun rightClick(text: String, shift: Boolean) {
                val at = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }.boundsInWindow.center
                val mods = PointerKeyboardModifiers(isShiftPressed = shift)
                scene.sendPointerEvent(PointerEventType.Move, at, keyboardModifiers = mods)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary, keyboardModifiers = mods)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Secondary, keyboardModifiers = mods)
                frames()
            }
            frames()
            rightClick("Kind number 1", shift = false)
            assertEquals(setOf(1), checked, "the row right-clicked, alone")
            assertFalse(CHECK_ONLY_LABEL in texts(), "no menu: the right-click did it")
            if ("Kind number 2" !in texts()) {
                deployed = false
                frames()
            }
            rightClick("Kind number 2", shift = true)
            assertEquals(setOf(1), checked, "Shift + right-click checks nothing")
            assertTrue(CHECK_ONLY_LABEL in texts(), "it opens the row menu: ${texts()}")
        } finally {
            scene.close()
        }
    }

    @Test
    fun clicks_and_keys_says_what_the_right_click_does_in_each_context() {
        val groups = PressDomain.groups(defaults, SearchDomain.Kind.Option)
        val dropDown = groups.single { g -> g.contexts.any { it.id == ContextDomain.DROP_DOWN } }
        assertTrue(dropDown.behaviours.any { it.press == PressDomain.Press.RightClick && "checks it and unchecks every other" in it.does })
        assertTrue(groups.size > 1, "the other contexts keep the menu")
        // And "State in each context" names where it is on: the drop-downs, each named one inheriting it.
        val on = ContextDomain.groups(defaults, SearchDomain.Setting.RightClickChecksAlone).single { it.on }.contexts.map { it.id }
        assertTrue(ContextDomain.DROP_DOWN in on)
        assertTrue(on.all { it == ContextDomain.DROP_DOWN || it.startsWith(ContextDomain.DROP_DOWN_PREFIX) }, "the drop-downs alone: $on")
    }
}
