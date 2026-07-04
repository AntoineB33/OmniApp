package org.example.project.scheduler.debug

import org.example.project.time.SimAppClock

// No desktop time-sim host on web.
actual fun startTimeLink(clock: SimAppClock): TimeLink? = null
