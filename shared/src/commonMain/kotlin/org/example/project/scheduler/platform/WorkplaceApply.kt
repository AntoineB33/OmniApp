package org.example.project.scheduler.platform

import org.example.project.scheduler.domain.WorkplaceOs
import org.example.project.scheduler.domain.WorkplaceSetup

/** The OS whose workplace setups this device applies; null where it applies none. A capability of the build's source set. */
expect fun workplaceOsHere(): WorkplaceOs?

/**
 * Opens the windows of [setup], off the caller's thread, and tells [onDone] what went wrong, null for nothing. It opens
 * windows and types into them: only ever the user's own press.
 */
expect fun applyWorkplaceSetup(setup: WorkplaceSetup, onDone: (error: String?) -> Unit)
