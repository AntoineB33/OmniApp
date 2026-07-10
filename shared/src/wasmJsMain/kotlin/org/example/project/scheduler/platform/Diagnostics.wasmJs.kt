package org.example.project.scheduler.platform

// Web has no local file to append to; the browser console is the timeline.
actual fun appendDiagnosticsLine(line: String) {
    println(line)
}
