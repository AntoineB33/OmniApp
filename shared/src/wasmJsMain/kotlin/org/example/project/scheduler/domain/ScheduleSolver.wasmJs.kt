package org.example.project.scheduler.domain

/** No platform solver here: the step-bounded passes and the exhaustive search are the whole of the search. */
internal actual fun platformScheduleSolver(): ExternalScheduleSolver? = null
