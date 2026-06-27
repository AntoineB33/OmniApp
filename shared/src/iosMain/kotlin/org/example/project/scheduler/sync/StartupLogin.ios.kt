package org.example.project.scheduler.sync

/** iOS has no script-driven launch path; sign-in stays interactive. */
actual fun startupLoginCredentials(): StartupLogin? = null
