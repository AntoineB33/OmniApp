package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.domain.CategoryRules
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TaskRelationsDomain
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState

/**
 * **One rule for how a task is named.** `SchedulerDomain.taskTitleLabel` is the whole of it, and the point of
 * these tests is that the surfaces which used to spell it out for themselves now agree with it by
 * construction rather than by having been kept in step.
 *
 * The rule had drifted into six spellings — `UNTITLED_LABEL`, `.ifBlank { "(untitled)" }`,
 * `.ifEmpty { "(untitled)" }`, `.orEmpty().ifBlank { … }` — across thirteen render sites, which is the
 * "second copy of a rule" `CLAUDE.md` names as the source of nearly every recorded regression. The companion
 * half of the rule (a named task is drawn in its own colour) lives in `org.example.project.ui.TaskTitleLabel`
 * and is a Compose concern, so what can be pinned here is the string.
 */
class TaskTitleLabelTest {

    private val titled = TaskId("task/titled")
    private val blank = TaskId("task/blank")

    private fun state(): SchedulerState =
        SchedulerState.empty().let { base ->
            base.copy(
                tasks = base.tasks +
                    (titled to Task(id = titled, title = "Write")) +
                    // A blank title is not a broken task: emptying a cell DELETES by blanking the title, and
                    // the id lives on for as long as a panel or a record still points at it.
                    (blank to Task(id = blank, title = "")),
            )
        }

    @Test
    fun aTitledTaskIsNamedByItsTitle() {
        assertEquals("Write", SchedulerDomain.taskTitleLabel(state(), titled))
    }

    @Test
    fun aBlankTitleBecomesThePlaceholder() {
        assertEquals(SchedulerDomain.UNTITLED_LABEL, SchedulerDomain.taskTitleLabel(state(), blank))
    }

    /** Whitespace is blank: a title of spaces would otherwise draw as an empty, unclickable-looking cell. */
    @Test
    fun aWhitespaceTitleIsBlankToo() {
        assertEquals(SchedulerDomain.UNTITLED_LABEL, SchedulerDomain.taskTitleLabel("   "))
        assertEquals(SchedulerDomain.UNTITLED_LABEL, SchedulerDomain.taskTitleLabel(null))
    }

    /** A task the state does not hold at all — a tombstone a keyframe still names — is not a crash. */
    @Test
    fun anUnknownTaskIsThePlaceholder() {
        assertEquals(
            SchedulerDomain.UNTITLED_LABEL,
            SchedulerDomain.taskTitleLabel(state(), TaskId("task/nowhere")),
        )
        assertEquals(SchedulerDomain.UNTITLED_LABEL, SchedulerDomain.taskTitleLabel(state(), null))
    }

    /**
     * The root is the one task with a name of its own: it stands for the whole tree and the user never wrote
     * its title, so it reads `root` and not the placeholder its blank-ish title would otherwise produce.
     */
    @Test
    fun theRootIsNamedRoot() {
        assertEquals(SchedulerDomain.ROOT_LABEL, SchedulerDomain.taskTitleLabel(state(), WellKnownIds.ROOT_TASK))
    }

    /**
     * The funnel's whole purpose: the surfaces that used to hold their own copy now answer through it. If a
     * future edit gives one of them a spelling of its own, this is what says so.
     */
    @Test
    fun theOtherNamingSurfacesAgreeWithIt() {
        val s = state()
        for (taskId in listOf(titled, blank, WellKnownIds.ROOT_TASK)) {
            assertEquals(
                SchedulerDomain.taskTitleLabel(s, taskId),
                TaskRelationsDomain.label(s, taskId),
                "the task-relations window names $taskId differently from the one rule",
            )
        }
        // A category rule's scope is a PATH of these labels, so the whole-tree scope is the root's name and
        // nothing else — a second spelling here is a rule that silently stops resolving.
        assertEquals(SchedulerDomain.ROOT_LABEL, CategoryRules.scopeLabel(s, null))
    }
}
