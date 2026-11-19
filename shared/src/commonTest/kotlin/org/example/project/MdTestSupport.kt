package org.example.project

import org.example.project.scheduler.domain.MdDomain
import org.example.project.scheduler.domain.MdElement
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/** [state] with a new md element of [text] associated with [taskId] (user rule 2026-11-19: a task's text is its md elements). */
internal fun withMd(state: SchedulerState, taskId: TaskId, text: String): SchedulerState =
    SchedulerReducer.reduce(
        state,
        SchedulerIntent.SetMdElements(
            state.mdElements + MdElement(MdDomain.mintId(state.mdElements.map { it.id }), text, listOf(SearchDomain.taskKey(taskId))),
        ),
    )
