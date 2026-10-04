package org.example.project

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskPanel

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes*, **mode 1**: the line is always on a task, even while it pushes
 * a "no screen" period forward, and the RULES name that task: a fill in mode 1 lays it as a run held at the line
 * ([TaskPanel.lineBound]), never as a task inside the period. What the calendar draws is the panels as the rules give
 * them at the line ([SchedulerDomain.atLine], exactly as `App` draws them).
 */
internal fun drawnAt(plan: List<TaskPanel>, nowMillis: Long): List<TaskPanel> = SchedulerDomain.atLine(plan, nowMillis)
