package org.example.project.scheduler.platform

/** Claude Code runs in a desktop terminal: this device opens none. */
actual fun claudeCodeOpensHere(): Boolean = false

actual fun openClaudeCodeOn(files: Map<String, String>, onDone: (folder: String?, error: String?) -> Unit) =
    onDone(null, "This device does not open Claude Code: use the desktop app.")
