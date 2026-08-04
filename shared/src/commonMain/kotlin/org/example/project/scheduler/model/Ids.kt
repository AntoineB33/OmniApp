package org.example.project.scheduler.model

import kotlin.jvm.JvmInline

@JvmInline
value class TaskId(val value: String)

@JvmInline
value class CellId(val value: String)

/**
 * Identity of one **task tree** — a named alternative version of the whole tree, selected in the field
 * above the tree (see [org.example.project.scheduler.state.TaskTreeEntry]). Minted as `tree/{n}` and never
 * reused, so a title can be renamed / duplicated without the selection ever becoming ambiguous.
 */
@JvmInline
value class TaskTreeId(val value: String)

