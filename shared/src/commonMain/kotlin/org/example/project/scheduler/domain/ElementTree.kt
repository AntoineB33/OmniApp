package org.example.project.scheduler.domain

import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.CategoryKind
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-02: **[child] is a child of [parent]**, both elements named as the Search window names its rows
 * ([SearchDomain.keyOf]: `Timer/timer-3`). Authoritative, persisted and synced, one row per link. Its [id] is made of the two
 * ([ElementTree.linkId]), so two devices making the same link make one row. The order among one parent's children is [rank]:
 * the rows of a sync come back in no order of theirs.
 *
 * **Carrying a category is being a child of it**: a category is an element, so an element's categories are its parents that
 * are categories, and there is no second list. A key naming what the account no longer holds is ignored, never removed: an
 * undo of the deletion brings the element back with its links (the rule of a category id on a task).
 */
data class ElementLink(val id: String, val parent: String, val child: String, val rank: Double = 0.0)

/**
 * **The one reading of "parent" and "child" between elements.** Two things stay where they were, and this routes to them:
 * a task's children are its sub-list's tasks (the tree), and the categories a task carries are [org.example.project.scheduler.model.Task.categoryIds].
 * Every other relation is an [ElementLink].
 */
object ElementTree {
    fun linkId(parent: String, child: String): String = "$parent>$child"

    fun kindOf(key: String): Kind? = Kind.entries.firstOrNull { it.name == key.substringBefore('/') }

    private fun idOf(key: String): String = key.substringAfter('/')

    private fun taskIdOf(key: String): TaskId? = TaskId(idOf(key)).takeIf { kindOf(key) == Kind.Task }

    fun categoryKey(id: org.example.project.scheduler.model.CategoryId): String = Kind.Category.name + "/" + id.value

    /** What [key]'s window can be told to add under: everything but a "creation" row, which is a command and no element. */
    fun isElement(kind: Kind): Boolean = kind != Kind.Creation

    /** What can be told apart as an element to pick: the kinds of the account, the others coming and going on their own. */
    private val PICKED_KINDS =
        listOf(
            Kind.Category, Kind.RestrictivePeriod, Kind.Alarm, Kind.Timer, Kind.Chrono, Kind.Quota, Kind.Reminder,
            Kind.TaskTree, Kind.WorkplaceSetup,
        )

    /** The direct children of [key], in their order. */
    fun childrenOf(state: SchedulerState, key: String): List<String> {
        val own = state.elementLinks.filter { it.parent == key }.sortedWith(compareBy({ it.rank }, { it.id })).map { it.child }
        val taskId = taskIdOf(key)
        if (taskId != null) {
            val listId = state.tasks[taskId]?.childListId ?: return own
            val titled = state.lists[listId]?.cellIds.orEmpty().mapNotNull { state.cells[it]?.taskId }
                .filter { state.tasks[it]?.title?.isNotEmpty() == true }
            return titled.map(SearchDomain::taskKey).distinct() + own
        }
        if (kindOf(key) != Kind.Category) return own
        val categoryId = org.example.project.scheduler.model.CategoryId(idOf(key))
        return state.tasks.values.filter { categoryId in it.categoryIds }.sortedBy { it.title.lowercase() }.map { SearchDomain.taskKey(it.id) } + own
    }

    /** The direct parents of [key], categories included: a task's are the tree's and what it carries, then the links'. */
    fun parentsOf(state: SchedulerState, key: String): List<String> {
        val links = state.elementLinks.filter { it.child == key }.map { it.parent }
        val taskId = taskIdOf(key) ?: return links
        val parents = TaskPathsDomain.occurrences(state, taskId).mapNotNull { it.parentTaskId }.map(SearchDomain::taskKey)
        val categories = state.tasks[taskId]?.categoryIds.orEmpty().map(::categoryKey)
        return (parents + categories + links).distinct()
    }

    /** Everything under [key] at any depth, nearest first, each once; [key] itself only where a merge made a loop. */
    fun descendantsOf(state: SchedulerState, key: String): List<String> {
        val seen = LinkedHashSet<String>()
        val pending = ArrayDeque(childrenOf(state, key))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (seen.add(next)) pending.addAll(childrenOf(state, next))
        }
        return seen.toList()
    }

    /**
     * **What may be a child of what**, the one rule. An element is no child of itself, nor of what stands under it. A task
     * has task children alone, through the tree's own assignment rule. A task cell category is carried by one occurrence of
     * a task, so nothing is its child. [below] is what stands under [child], for a caller asking about several parents.
     */
    fun canLink(state: SchedulerState, parent: String, child: String, below: Collection<String> = descendantsOf(state, child)): Boolean {
        val parentKind = kindOf(parent) ?: return false
        val childKind = kindOf(child) ?: return false
        if (!isElement(parentKind) || !isElement(childKind) || parent == child || parent in below) return false
        if (parentKind == Kind.Task) {
            val taskId = taskIdOf(child)?.takeIf { childKind == Kind.Task } ?: return false
            return TaskPathsDomain.canAddUnder(state, taskId, TaskId(idOf(parent)))
        }
        if (parentKind == Kind.Category) {
            val category = state.categories.firstOrNull { it.id.value == idOf(parent) } ?: return false
            if (category.kind != CategoryKind.TaskId) return false
            if (child in childrenOf(state, parent)) return false
        }
        return state.elementLinks.none { it.parent == parent && it.child == child }
    }

    /** [links] with [child] at the end of [parent]'s children; as they were when it stands there. */
    fun linked(links: List<ElementLink>, parent: String, child: String): List<ElementLink> {
        val id = linkId(parent, child)
        if (links.any { it.id == id }) return links
        val rank = (links.filter { it.parent == parent }.maxOfOrNull { it.rank } ?: 0.0) + 1.0
        return links + ElementLink(id, parent, child, rank)
    }

    /** [links] without [child] under [parent]. */
    fun unlinked(links: List<ElementLink>, parent: String, child: String): List<ElementLink> = links.filterNot { it.parent == parent && it.child == child }

    /** Whether the row of [child] under [parent] can be taken out from there: the last place of a task in the tree cannot. */
    fun canUnlink(state: SchedulerState, parent: String, child: String): Boolean {
        val taskId = taskIdOf(child) ?: return true
        if (kindOf(parent) != Kind.Task) return true
        val places = TaskPathsDomain.occurrences(state, taskId)
        return places.size > 1 && places.any { it.parentTaskId?.let(SearchDomain::taskKey) == parent }
    }

    /**
     * The intents that make [parent] a parent of each of [children] ([on]) or no longer one. A task under a task goes
     * through the tree, a task under a category through what it carries, and every other link is one list edit. What
     * [canLink] refuses is left out, so one refused child does not stop the others.
     */
    fun parentIntents(state: SchedulerState, children: List<String>, parent: String, on: Boolean): List<SchedulerIntent> {
        val kept = children.filter { child -> kindOf(child)?.let(::isElement) == true && (!on || canLink(state, parent, child)) }
        val parentKind = kindOf(parent)
        val tasks = kept.mapNotNull(::taskIdOf)
        val out = ArrayList<SchedulerIntent>()
        val inTree =
            when (parentKind) {
                Kind.Task -> {
                    val parentId = TaskId(idOf(parent))
                    tasks.forEach { task ->
                        if (on) {
                            out += SchedulerIntent.AddTaskPath(task, parentId)
                        } else {
                            TaskPathsDomain.occurrences(state, task).firstOrNull { it.parentTaskId == parentId }
                                ?.let { out += SchedulerIntent.RemoveTaskPath(it.cellId) }
                        }
                    }
                    true
                }
                Kind.Category -> {
                    val carried = tasks.filter { (org.example.project.scheduler.model.CategoryId(idOf(parent)) in state.tasks[it]?.categoryIds.orEmpty()) != on }
                    if (carried.isNotEmpty()) {
                        out += SchedulerIntent.SetTasksCategory(carried, org.example.project.scheduler.model.CategoryId(idOf(parent)), on)
                    }
                    true
                }
                else -> false
            }
        val rest = if (inTree) kept.filter { taskIdOf(it) == null } else kept
        val next = rest.fold(state.elementLinks) { links, child -> if (on) linked(links, parent, child) else unlinked(links, parent, child) }
        if (next != state.elementLinks) out += SchedulerIntent.SetElementLinks(next)
        return out
    }

    /** The categories [key] carries, by title: what its row says. A task's say it in their own column. */
    fun categoryTitles(state: SchedulerState): Map<String, List<String>> {
        val titles = state.categories.associate { categoryKey(it.id) to it.title }
        return state.elementLinks.filter { it.parent in titles }.groupBy({ it.child }, { titles.getValue(it.parent) })
    }

    /**
     * What the element selector of "Add a parent" lists: every element of the account, as key and name. The windows, the
     * history and the logs come and go on their own, so they are reached by the Search window's own lists instead.
     */
    fun pickable(state: SchedulerState): List<Pair<String, String>> {
        val tasks = state.tasks.values.filter { it.title.isNotEmpty() }.sortedBy { it.title.lowercase() }.map { SearchDomain.taskKey(it.id) to Kind.Task.label + ": " + it.title }
        val items = PICKED_KINDS.flatMap { kind -> SearchDomain.itemResults(state, kind, "").map { SearchDomain.keyOf(it) to kind.label + ": " + it.name } }
        return tasks + items
    }

    /** The setups applying the setup [id] applies: itself, then what stands under it, each once. */
    fun workplaceFamily(state: SchedulerState, id: String): List<WorkplaceSetup> {
        val byId = state.workplaceSetups.associateBy { it.id }
        val key = Kind.WorkplaceSetup.name + "/" + id
        val under = descendantsOf(state, key).filter { kindOf(it) == Kind.WorkplaceSetup }.mapNotNull { byId[idOf(it)] }
        return (listOfNotNull(byId[id]) + under).distinctBy { it.id }
    }

    /**
     * The Search window a double-click on the blank field under [parent] opens, on [page]: its kind (a category has children
     * of every kind), [parent] excluded from the list as no child of itself, and [SearchDomain.Config.elementSource] saying
     * where it comes from, for "Add a parent".
     */
    fun childSearchConfig(parent: String, page: String): SearchDomain.Config {
        val config = SearchDomain.pageDefaultConfig(page)
        val kind = kindOf(parent)?.takeIf { it != Kind.Category }
        return config.copy(
            kinds = kind?.let(::setOf) ?: config.kinds,
            filters = config.filters.copy(excludedElements = setOf(parent)),
            elementSource = parent,
        )
    }
}
