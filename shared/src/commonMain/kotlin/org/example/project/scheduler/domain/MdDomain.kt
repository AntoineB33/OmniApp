package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-19: *"Add the md element type. In the action section for an element (like a task element), the user can
 * add md elements. It replaces the current text of a task element. Association between elements doesn't mean one is the
 * child of the other."*
 *
 * **An md element is a markdown text** ([text]), **associated with the elements it is about** ([of], their Search keys,
 * `Kind/id`). An association is no link of [ElementTree]: neither is a parent nor a child of the other, and nothing in the
 * tree of parents and children reads it.
 */
data class MdElement(
    val id: String,
    val text: String = "",
    /** The keys of the elements this one is associated with, in the order it was associated with them. */
    val of: List<String> = emptyList(),
)

object MdDomain {
    /**
     * User rule 2026-11-20: the md element the scheduler's menu shows alone by default, *"one called life context"*. A fixed
     * id, so two devices that make it make the same row; made at the first press of its button ([withLifeContext]).
     */
    const val LIFE_CONTEXT_ID: String = "md-life-context"
    const val LIFE_CONTEXT_TEXT: String = "# Life context"

    /** [elements] with the Life context md element, or null when it is there already (renamed or not: its id says it). */
    fun withLifeContext(elements: List<MdElement>): List<MdElement>? =
        if (elements.any { it.id == LIFE_CONTEXT_ID }) null else elements + MdElement(LIFE_CONTEXT_ID, LIFE_CONTEXT_TEXT)

    /** The first free `md-N`. */
    fun mintId(used: Collection<String>): String {
        val taken = used.toHashSet()
        var n = 1
        while ("md-$n" in taken) n++
        return "md-$n"
    }

    /** What a Search row names it by: its first line that says something, without the markdown marks in front of it. */
    fun title(element: MdElement): String =
        element.text.lineSequence().map { it.trim().trimStart('#', '-', '*', '>', ' ').trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    /** The md elements associated with [key], in the account's order. */
    fun associatedWith(state: SchedulerState, key: String): List<MdElement> = state.mdElements.filter { key in it.of }

    /** The md elements associated with any of [keys], each once. */
    fun associatedWithAny(state: SchedulerState, keys: Collection<String>): List<MdElement> {
        val wanted = keys.toHashSet()
        return state.mdElements.filter { md -> md.of.any { it in wanted } }
    }

    /** [elements] with a new md element of [text] associated with [keys], under [id]. */
    fun added(elements: List<MdElement>, id: String, keys: List<String>, text: String = ""): List<MdElement> =
        elements + MdElement(id = id, text = text, of = keys.distinct())

    /** [elements] with [id] no longer associated with any of [keys]: the element itself stays, an md of its own. */
    fun dissociated(elements: List<MdElement>, id: String, keys: Collection<String>): List<MdElement> =
        elements.map { if (it.id == id) it.copy(of = it.of - keys.toSet()) else it }

    /** The id the text a task held is lifted into: a function of the task, so two devices lift it into the same row. */
    fun liftedId(taskId: TaskId): String = "md-task-" + taskId.value

    /**
     * User rule 2026-11-19, *"it replaces the current text of a task element"*: **the text a task holds is moved into an md
     * element associated with it** — on decode (what an older build wrote) and wherever a task is made with a text (a paste,
     * the default sub-tree, a copy). [taskIds] null walks every task. A task whose lifted element is already there (another
     * device lifted it) only loses its text. The ONE place a task's text becomes an md element.
     */
    fun liftTaskTexts(state: SchedulerState, taskIds: Collection<TaskId>? = null): SchedulerState {
        val ids = taskIds ?: state.tasks.keys
        val texted = ids.mapNotNull { id -> state.tasks[id]?.takeIf { it.text.isNotEmpty() } }
        if (texted.isEmpty()) return state
        val present = state.mdElements.mapTo(HashSet()) { it.id }
        // On decode another device may have lifted the same text already; a task made with a text later is given a new one.
        val lifted =
            texted.filter { taskIds != null || liftedId(it.id) !in present }.map { task ->
                val id = liftedId(task.id).takeIf { it !in present } ?: mintId(present)
                present += id
                MdElement(id = id, text = task.text, of = listOf(SearchDomain.taskKey(task.id)))
            }
        val tasks = state.tasks + texted.associate { it.id to it.copy(text = "") }
        return state.copy(tasks = tasks, mdElements = state.mdElements + lifted)
    }

    /** What a copy of the tree carries as a task's text (`- text:`): its md elements' texts, one after the other. */
    fun copiedText(state: SchedulerState, taskId: TaskId): String =
        associatedWith(state, SearchDomain.taskKey(taskId)).map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")

    /** A copy of a task ([from]) is associated with what it is associated with ([to] beside it). */
    fun withCopyAssociated(state: SchedulerState, from: TaskId, to: TaskId): SchedulerState {
        val fromKey = SearchDomain.taskKey(from)
        val toKey = SearchDomain.taskKey(to)
        if (state.mdElements.none { fromKey in it.of }) return state
        return state.copy(mdElements = state.mdElements.map { if (fromKey in it.of && toKey !in it.of) it.copy(of = it.of + toKey) else it })
    }
}
