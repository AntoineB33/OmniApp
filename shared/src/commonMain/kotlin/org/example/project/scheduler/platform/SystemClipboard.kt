package org.example.project.scheduler.platform

expect fun readSystemClipboardText(): String?

expect fun writeSystemClipboardText(text: String)
