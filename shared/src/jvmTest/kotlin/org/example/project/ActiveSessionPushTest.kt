package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.persistence.ActiveSessionRecord

/**
 * PRD §15: what [SchedulerEngine.pushableActiveSessions] lets [SchedulerEngine]'s session pushes upload.
 * Only this device's OWN observed sessions inside the derive horizon may reach the server: the legacy
 * `remote-activity` rows a pre-closed-flag build adopted (presumed — not observed — activity derived from
 * the server's own old answer) are excluded, so they can never be asserted back to the server as if
 * observed and poison every peer's derived pauses. (They are also deleted outright at startup by the
 * engine's legacy-row purge; this filter is the belt to that suspender within a run.)
 */
class ActiveSessionPushTest {
    @Test
    fun legacy_adopted_remote_activity_rows_are_never_pushed() {
        val own = ActiveSessionRecord("device-uuid", 50, 80, 80)
        val adopted = ActiveSessionRecord(SchedulerEngine.REMOTE_ACTIVITY_DEVICE_ID, 10, 50, 80)
        assertEquals(
            listOf(own),
            SchedulerEngine.pushableActiveSessions(listOf(adopted, own), horizonStartMillis = 0),
        )
    }

    @Test
    fun own_rows_outside_the_derive_horizon_are_dropped_from_the_push() {
        val stale = ActiveSessionRecord("device-uuid", 0, 5, 5)
        val fresh = ActiveSessionRecord("device-uuid", 50, 80, 80)
        assertEquals(
            listOf(fresh),
            SchedulerEngine.pushableActiveSessions(listOf(stale, fresh), horizonStartMillis = 10),
        )
    }
}
