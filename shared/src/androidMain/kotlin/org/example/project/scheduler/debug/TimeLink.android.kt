package org.example.project.scheduler.debug

import org.example.project.time.SimAppClock

// The phone is the CLIENT of the time-link, not the host: it dials the desktop server (see the Android app's
// TimeLinkClient), so there is no server to start here. Android never runs under desktop time-sim anyway.
actual fun startTimeLink(clock: SimAppClock): TimeLink? = null
