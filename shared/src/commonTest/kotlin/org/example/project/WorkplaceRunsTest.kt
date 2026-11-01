package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.WorkplaceOs
import org.example.project.scheduler.domain.WorkplaceSetup
import org.example.project.scheduler.domain.WorkplaceWindow
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.WorkplaceRuns

/** User rule 2026-11-01: double-clicking a workplace setup applies it. Nothing here runs the script: it opens windows. */
class WorkplaceRunsTest {
    private val setup = WorkplaceSetup("workplace-1", "Code", WorkplaceOs.Windows11, listOf(WorkplaceWindow(listOf("C:\\code"))))

    private class Fake {
        val asked = ArrayList<String>()
        var finish: (String?) -> Unit = {}
        val runs = WorkplaceRuns({ setup, done -> asked += setup.id; finish = done }, { WorkplaceOs.Windows11 })
    }

    private fun openers(applied: MutableList<String>) =
        SearchRowOpeners(
            onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {},
            onApplyWorkplaceSetup = { applied += it },
        )

    @Test
    fun a_double_click_applies_it_instead_of_adding_it() {
        assertTrue(SearchRowOpeners.doubleClickOpens(Kind.WorkplaceSetup))
        assertTrue(SearchRowOpeners.doubleClickOpens(Kind.Creation))
        assertFalse(SearchRowOpeners.doubleClickOpens(Kind.Timer), "the others are added")
    }

    @Test
    fun opening_its_row_asks_for_that_setup() {
        val applied = ArrayList<String>()
        openers(applied).open(SchedulerState.empty(), SearchDomain.ItemResult(Kind.WorkplaceSetup, "workplace-1", "Code", ""))
        assertEquals(listOf("workplace-1"), applied)
    }

    @Test
    fun a_second_press_while_it_runs_opens_nothing_more() {
        val fake = Fake()
        fake.runs.apply(setup)
        fake.runs.apply(setup)
        assertEquals(listOf("workplace-1"), fake.asked)
        assertTrue(fake.runs.isRunning(setup.id))
        fake.finish(null)
        assertFalse(fake.runs.isRunning(setup.id))
        fake.runs.apply(setup)
        assertEquals(2, fake.asked.size, "finished: it can be applied again")
    }

    @Test
    fun what_went_wrong_is_kept_and_told_until_the_next_try() {
        val fake = Fake()
        var failed = 0
        fake.runs.apply(setup) { failed++ }
        fake.finish("lost the foreground")
        assertEquals("lost the foreground", fake.runs.errorOf(setup.id))
        assertEquals(1, failed)
        fake.runs.apply(setup) { failed++ }
        assertNull(fake.runs.errorOf(setup.id))
        fake.finish(null)
        assertEquals(1, failed)
    }

    @Test
    fun a_setup_it_cannot_apply_runs_nothing() {
        val fake = Fake()
        fake.runs.apply(WorkplaceSetup("empty"))
        assertEquals(emptyList(), fake.asked)
        val elsewhere = WorkplaceRuns({ _, _ -> error("must not run") }, { null })
        elsewhere.apply(setup)
        assertFalse(elsewhere.isRunning(setup.id))
    }
}
