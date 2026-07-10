package org.example.project.scheduler.platform

import platform.Foundation.NSLog

// iOS keeps the timeline in the system log only (retrieve via Console.app / `log stream`); no file
// retrieval script exists for iOS yet, so a durable copy would have no reader.
actual fun appendDiagnosticsLine(line: String) {
    NSLog("%@", line)
}
