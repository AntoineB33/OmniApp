package org.example.project.scheduler.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.example.project.scheduler.persistence.AndroidSchedulerStoreHolder

actual fun readSystemClipboardText(): String? {
    val context = AndroidSchedulerStoreHolder.context ?: return null
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    return manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}

actual fun writeSystemClipboardText(text: String) {
    val context = AndroidSchedulerStoreHolder.context ?: return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("OmniApp", text))
}
