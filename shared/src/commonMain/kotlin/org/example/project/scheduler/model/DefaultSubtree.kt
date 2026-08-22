package org.example.project.scheduler.model

/**
 * PRD §4 **Default sub-tree**: one node of the template that is grafted under a task the moment it is
 * created — i.e. the moment the user types a title into an empty task cell (PRD §4 *Auto-Expansion*
 * gives that cell a sub-list holding a single empty placeholder; the template fills it).
 *
 * The template is a plain tree of *titles*, not of tree cells: it names what should appear, and applying it
 * is what builds real cells/lists/tasks. It is authoritative user data — nothing can re-derive it — so it is
 * persisted and synced with the rest of the account (see [org.example.project.scheduler.state.SchedulerState.defaultSubtree]).
 *
 * **[taskId] IS the switch.** The window shows a switch on every non-empty node; it is *on* exactly when the
 * node mints a **brand new task id** each time the template is applied, and *off* when the node points at one
 * particular existing task (so every leaf built from it mirrors that task). There is deliberately no separate
 * boolean: `taskId == null` ⟺ "new id" ⟺ the switch is on, which is why the switch can never be turned off
 * without picking a task in the node's edit menu, and why picking one turns it off by itself.
 *
 * A node bound to an existing task contributes **that task's own sub-tree** — a sub-list belongs to the task
 * id, not to the cell (CLAUDE.md), so the template cannot give it different children. Its [children] are kept
 * (turning the switch back on restores them) but are not applied while it is bound.
 *
 * [id] is a stable handle for the editor's rows only (`dst/{n}`); nothing outside the window refers to it.
 */
data class DefaultSubtreeNode(
    val id: String,
    val title: String = "",
    /** `null` = **new id**: mint a brand-new task each time the template is applied. */
    val taskId: TaskId? = null,
    val children: List<DefaultSubtreeNode> = emptyList(),
) {
    /** The switch's state: on when this node mints a fresh task id, off when it is bound to an existing one. */
    val newTaskId: Boolean get() = taskId == null
}
