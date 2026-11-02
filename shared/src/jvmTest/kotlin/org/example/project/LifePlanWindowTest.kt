package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.LifePlanDomain
import org.example.project.scheduler.domain.LifePlanEntry
import org.example.project.scheduler.domain.LifePlanKind
import org.example.project.scheduler.domain.LifePlanSection
import org.example.project.scheduler.domain.LifePlanView
import org.example.project.ui.LifePlanWindow
import org.example.project.ui.LocalWindowFrameHost
import org.example.project.ui.WindowFrameHost

/**
 * The life plan's window drawn off screen. Presses stay below the section field: a drop-down is an OS window of its own on
 * the desktop (`popups.md`), which a test must not open.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class LifePlanWindowTest {
    private class Plan(entries: List<LifePlanEntry>, view: LifePlanView) {
        var entries by mutableStateOf(entries)
        var view by mutableStateOf(view)
    }

    private fun scene(plan: Plan) =
        ImageComposeScene(620, 680, density = Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalWindowFrameHost provides WindowFrameHost()) {
                    Box(Modifier.fillMaxSize()) {
                        LifePlanWindow(
                            entries = plan.entries,
                            view = plan.view,
                            onViewChange = { plan.view = it },
                            onEntriesChange = { next, _ -> plan.entries = next },
                            onDismiss = {},
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }

    private fun ImageComposeScene.frames() {
        var time = 0L
        repeat(6) {
            time += 16_000_000L
            render(time)
        }
    }

    private fun ImageComposeScene.click(at: Offset) {
        val primary = PointerButtons(isPrimaryPressed = true)
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at, buttons = primary, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
        frames()
    }

    /** Presses each spot in turn until [done]: where a row stands depends on the frame's own measures. */
    private fun ImageComposeScene.clickUntil(spots: Sequence<Offset>, done: () -> Boolean): Boolean {
        for (spot in spots) {
            click(spot)
            if (done()) return true
        }
        return false
    }

    @Test
    fun new_step_adds_a_row_to_the_list() {
        val plan = Plan(emptyList(), LifePlanView())
        scene(plan).use { scene ->
            scene.frames()
            // "+ New step" stands under the list, at the window's bottom left.
            val spots = (640 downTo 500 step 8).asSequence().map { Offset(70f, it.toFloat()) }
            assertTrue(scene.clickUntil(spots) { plan.entries.isNotEmpty() }, "the button was found")
            assertEquals(LifePlanKind.Step, plan.entries.single().kind)
        }
    }

    @Test
    fun the_arrow_of_a_step_shows_its_sub_elements() {
        val (one, parent) = LifePlanDomain.addedStep(emptyList(), null)
        val (entries, child) = LifePlanDomain.addedStep(one, parent)
        val plan = Plan(entries, LifePlanView())
        scene(plan).use { scene ->
            scene.frames()
            val spots = (125..200 step 8).asSequence().flatMap { y -> (45..80 step 6).asSequence().map { x -> Offset(x.toFloat(), y.toFloat()) } }
            assertTrue(scene.clickUntil(spots) { plan.view.expanded.isNotEmpty() }, "the arrow was found")
            assertEquals(listOf(parent), plan.view.expanded)
            assertEquals(listOf(parent, child), LifePlanDomain.stepRows(plan.entries, plan.view.expanded.toSet()).map { it.step.id })
        }
    }

    @Test
    fun every_section_draws_with_a_plan_and_with_none() {
        val (tabbed, tab) = LifePlanDomain.addedTab(emptyList())
        val written = LifePlanDomain.withText(tabbed, tab, "one\ntwo")
        for (entries in listOf(emptyList(), written)) for (section in LifePlanSection.entries) {
            val plan = Plan(entries, LifePlanView(section.name, tab))
            scene(plan).use { it.frames() }
            assertEquals(entries, plan.entries, "drawing changes nothing")
        }
    }
}
