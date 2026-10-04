package org.example.project.scheduler.platform

import platform.UIKit.UIPasteboard

// The system pasteboard, read synchronously — iOS may show its "pasted from…" banner on a read.
actual fun readSystemClipboardText(): String? = UIPasteboard.generalPasteboard.string

actual fun writeSystemClipboardText(text: String) {
    UIPasteboard.generalPasteboard.string = text
}
