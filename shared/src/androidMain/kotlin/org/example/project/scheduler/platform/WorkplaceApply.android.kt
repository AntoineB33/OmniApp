package org.example.project.scheduler.platform

import org.example.project.scheduler.domain.WorkplaceOs
import org.example.project.scheduler.domain.WorkplaceSetup

/** A workplace setup opens desktop windows: a phone applies none. */
actual fun workplaceOsHere(): WorkplaceOs? = null

actual fun applyWorkplaceSetup(setup: WorkplaceSetup, onDone: (error: String?) -> Unit) = onDone("This device applies no workplace setup.")
