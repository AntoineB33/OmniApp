package org.example.project

import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.ui.CalendarRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CalendarDisplayCache]'s re-label is a CHEAPER FORM OF DERIVING AGAIN, so it is pinned against deriving
 * again — the same shape as [CalendarDisplayEquivalenceTest], which holds the calendar's other cost-only
 * rewrites to their previous definition.
 *
 * The user's rule is what the shortcut exists for: *"each time a title is renamed with a new keystroke, the
 * titles in the calendar must update at the same time"*, and typing must not pay for the placement it did
 * not move. So the answer a re-label gives must be the answer a full derivation would have given —
 * every time it is taken, and it must not be taken when it would differ.
 */
class CalendarRelabelEquivalenceTest {

    private val T0 = 1_700_000_000_000L
    private val alpha = TaskId("task/alpha")
    private val beta = TaskId("task/beta")

    private fun tasks(alphaTitle: String, betaTitle: String = "beta") =
        mapOf(
            alpha to Task(id = alpha, title = alphaTitle),
            beta to Task(id = beta, title = betaTitle),
        )

    private fun panel(id: String, taskId: TaskId?, title: String, hour: Int) =
        TaskPanel(
            id = id,
            taskId = taskId,
            title = title,
            startEpochMillis = T0 + hour * 3_600_000L,
            endEpochMillis = T0 + (hour + 1) * 3_600_000L,
            auto = taskId != null,
        )

    private fun panels(alphaTitle: String, periodTitle: String = "a period", hour: Int = 3) =
        listOf(
            panel("p-alpha", alpha, alphaTitle, 1),
            panel("p-beta", beta, "beta", 2),
            panel("p-period", null, periodTitle, hour),
        )

    /**
     * The oracle: what the app's derivation does with titles — a block NAMES the task it holds, and a panel
     * holding none names itself.
     */
    private fun derive(tasks: Map<TaskId, Task>, panels: List<TaskPanel>): CalendarDisplay =
        CalendarDisplay(
            records = panels.map { p ->
                CalendarRecord(
                    title = p.taskId?.let { tasks[it]?.title } ?: p.title,
                    range = TaskTimeRange(p.startEpochMillis, p.endEpochMillis),
                    taskId = p.taskId,
                    entryId = p.id,
                )
            },
            displayFloorMillis = T0,
            bandSignature = "sig",
            noScreenPeriods = emptyList(),
            carvedSleepHoles = emptyList(),
            sidePanelCount = 0,
            workPlanPanelCount = panels.size,
        )

    private val key = listOf<Any?>(T0, "the rest of the reading")

    @Test
    fun an_unchanged_reading_is_not_derived_again() {
        val cache = CalendarDisplayCache()
        var derivations = 0
        val t = tasks("alpha")
        val p = panels("alpha")
        val first = cache.get(key, t, p) { derivations++; derive(t, p) }
        val second = cache.get(key, t, p) { derivations++; derive(t, p) }
        assertEquals(1, derivations, "the same inputs must not be derived twice")
        assertTrue(first === second, "and must hand back the very same reading")
    }

    @Test
    fun a_rename_relabels_exactly_as_deriving_again_would() {
        val cache = CalendarDisplayCache()
        var derivations = 0
        val before = tasks("alpha")
        val beforePanels = panels("alpha")
        cache.get(key, before, beforePanels) { derivations++; derive(before, beforePanels) }

        // What the reducer does on a rename keystroke: the task, and the panels that name it.
        val after = tasks("alphabet")
        val afterPanels = panels("alphabet")
        val relabelled = cache.get(key, after, afterPanels) { derivations++; derive(after, afterPanels) }

        assertEquals(1, derivations, "a rename must not re-derive the placement")
        assertEquals(derive(after, afterPanels), relabelled, "…and must give the answer deriving would")
        assertEquals("alphabet", relabelled.records.first { it.taskId == alpha }.title)
        assertEquals("beta", relabelled.records.first { it.taskId == beta }.title, "one task renamed, one only")
        assertEquals("a period", relabelled.records.first { it.taskId == null }.title)
    }

    @Test
    fun a_rename_to_blank_relabels_too() {
        val cache = CalendarDisplayCache()
        var derivations = 0
        val before = tasks("alpha")
        val beforePanels = panels("alpha")
        cache.get(key, before, beforePanels) { derivations++; derive(before, beforePanels) }
        val after = tasks("")
        val afterPanels = panels("")
        val relabelled = cache.get(key, after, afterPanels) { derivations++; derive(after, afterPanels) }
        assertEquals(1, derivations, "emptying a title is still only a title")
        assertEquals(derive(after, afterPanels), relabelled)
    }

    @Test
    fun a_renamed_period_is_derived_again_because_no_task_names_it() {
        val cache = CalendarDisplayCache()
        var derivations = 0
        val t = tasks("alpha")
        val beforePanels = panels("alpha")
        cache.get(key, t, beforePanels) { derivations++; derive(t, beforePanels) }
        // A hand-drawn period (no task) renamed: nothing in `tasks` can say what it is called now.
        val afterPanels = panels("alpha", periodTitle = "renamed period")
        val derived = cache.get(key, t, afterPanels) { derivations++; derive(t, afterPanels) }
        assertEquals(2, derivations, "a title only the panel knows must be derived again")
        assertEquals("renamed period", derived.records.first { it.taskId == null }.title)
    }

    @Test
    fun a_panel_that_moved_is_derived_again() {
        val cache = CalendarDisplayCache()
        var derivations = 0
        val t = tasks("alpha")
        val beforePanels = panels("alpha")
        cache.get(key, t, beforePanels) { derivations++; derive(t, beforePanels) }
        val afterPanels = panels("alpha", hour = 9) // the period is an hour elsewhere: placement moved
        cache.get(key, t, afterPanels) { derivations++; derive(t, afterPanels) }
        assertEquals(2, derivations, "a placement change is never a re-label")
    }

    @Test
    fun the_two_readings_of_the_line_do_not_evict_each_other() {
        val cache = CalendarDisplayCache()
        var derivations = 0
        val t = tasks("alpha")
        val p = panels("alpha")
        val atLine = listOf<Any?>(T0, "the rest of the reading")
        val oneMsLater = listOf<Any?>(T0 + 1, "the rest of the reading")
        cache.get(atLine, t, p) { derivations++; derive(t, p) }
        cache.get(oneMsLater, t, p) { derivations++; derive(t, p) }
        cache.get(atLine, t, p) { derivations++; derive(t, p) }
        cache.get(oneMsLater, t, p) { derivations++; derive(t, p) }
        assertEquals(2, derivations, "[withLineMotion] reads twice; both readings must be held")
    }
}
