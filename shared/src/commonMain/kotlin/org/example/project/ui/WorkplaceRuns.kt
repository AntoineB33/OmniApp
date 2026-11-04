package org.example.project.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import org.example.project.scheduler.domain.WorkplaceDomain
import org.example.project.scheduler.domain.WorkplaceOs
import org.example.project.scheduler.domain.WorkplaceSetup
import org.example.project.scheduler.platform.applyWorkplaceSetup
import org.example.project.scheduler.platform.workplaceOsHere

/**
 * **The one way a workplace setup is applied**, from its Apply button or its row: the setups being applied and what went
 * wrong with each, which the button shows wherever the press came from. A second press while one runs is ignored: it would
 * open the windows twice.
 */
class WorkplaceRuns(
    private val run: (WorkplaceSetup, (String?) -> Unit) -> Unit = ::applyWorkplaceSetup,
    here: () -> WorkplaceOs? = ::workplaceOsHere,
) {
    /** The OS of this device, asked once: the answer can start a process. */
    val here: WorkplaceOs? by lazy(here)
    private val running = mutableStateMapOf<String, Unit>()
    private val errors = mutableStateMapOf<String, String>()

    fun isRunning(id: String): Boolean = id in running

    fun errorOf(id: String): String? = errors[id]

    fun canApply(setup: WorkplaceSetup): Boolean = WorkplaceDomain.canApply(setup, here)

    /** Applies [setup] where it can be applied; [onFailed] runs off the caller's thread, the error kept for [errorOf]. */
    fun apply(setup: WorkplaceSetup, onFailed: () -> Unit = {}) = applyAll(listOf(setup), onFailed)

    /**
     * Applies [setups], a setup and its children ([org.example.project.scheduler.domain.ElementTree.workplaceFamily]), one
     * after the other: each takes the foreground and the keyboard as it opens, so two at once would type into each other.
     * One that fails does not stop the next.
     */
    fun applyAll(setups: List<WorkplaceSetup>, onFailed: () -> Unit = {}) {
        val next = setups.filter { canApply(it) && !isRunning(it.id) }.distinctBy { it.id }
        next.forEach {
            errors.remove(it.id)
            running[it.id] = Unit
        }
        fun step(index: Int) {
            val setup = next.getOrNull(index) ?: return
            run(setup) { error ->
                if (error != null) errors[setup.id] = error
                running.remove(setup.id)
                if (error != null) onFailed()
                step(index + 1)
            }
        }
        step(0)
    }
}

/** The app's [WorkplaceRuns], for the rows that say a setup is running ([org.example.project.scheduler.domain.SearchDomain.shownDetail]); none in a preview. */
val LocalWorkplaceRuns = staticCompositionLocalOf<WorkplaceRuns?> { null }
