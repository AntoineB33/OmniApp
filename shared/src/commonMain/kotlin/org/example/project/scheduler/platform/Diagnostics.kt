package org.example.project.scheduler.platform

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.TaskTimeRange

/**
 * Append one raw line to this device's local diagnostics file (best-effort; must never throw).
 * Desktop: `<stateDir>/diagnostics.log` (next to the DB, shared with the account scripts' markers);
 * Android: `filesDir/diagnostics.log` (readable over `adb shell run-as`, see
 * scripts/collect-diagnostics.bat) mirrored to Logcat. The file is local-only — it never syncs.
 */
expect fun appendDiagnosticsLine(line: String)

/**
 * Append-only cross-device diagnostics timeline for the derived-pause / calendar-band pipeline.
 *
 * Every line is `yyyy-MM-dd HH:mm:ss.SSS [kind] message` stamped with the REAL wall clock (never the
 * debug [org.example.project.time.SimAppClock]), so lines written by the desktop app, the Android app,
 * and the account .bat scripts merge into one chronological story — "18:33 the script emptied the
 * account, 18:34 the phone opened, and here are the exact Inactivity bands its calendar rendered".
 * Merge/retrieve with scripts/collect-diagnostics.bat.
 */
object Diagnostics {
    fun log(message: String) {
        val kind = currentDeviceKind().name.lowercase()
        appendDiagnosticsLine("${formatWallStamp(Clock.System.now().toEpochMilliseconds())} [$kind] $message")
    }

    /** Local-time instant for message bodies — day + time is enough to line ranges up on the calendar. */
    fun formatInstant(epochMillis: Long): String {
        val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${p2(t.monthNumber)}-${p2(t.dayOfMonth)} ${p2(t.hour)}:${p2(t.minute)}:${p2(t.second)}"
    }

    /** Compact `start → end; …` listing, capped so a week of session fragments can't flood a line. */
    fun formatRanges(ranges: List<TaskTimeRange>, cap: Int = 12): String {
        if (ranges.isEmpty()) return "(none)"
        val shown = ranges.take(cap).joinToString("; ") {
            "${formatInstant(it.startEpochMillis)} → ${formatInstant(it.endEpochMillis)}"
        }
        val more = ranges.size - cap
        return if (more > 0) "$shown; … (+$more more)" else shown
    }

    /** Full sortable line prefix — this exact shape is what merge_diagnostics.py orders the timeline by. */
    private fun formatWallStamp(epochMillis: Long): String {
        val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        val millis = (epochMillis % 1000 + 1000) % 1000
        return "${t.year}-${p2(t.monthNumber)}-${p2(t.dayOfMonth)} " +
            "${p2(t.hour)}:${p2(t.minute)}:${p2(t.second)}.${millis.toString().padStart(3, '0')}"
    }

    private fun p2(v: Int) = v.toString().padStart(2, '0')
}
