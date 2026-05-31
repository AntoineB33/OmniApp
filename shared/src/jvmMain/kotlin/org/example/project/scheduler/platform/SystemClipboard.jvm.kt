package org.example.project.scheduler.platform

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

actual fun readSystemClipboardText(): String? =
    runCatching {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return null
        if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            contents.getTransferData(DataFlavor.stringFlavor) as? String
        } else {
            null
        }
    }.getOrNull()

actual fun writeSystemClipboardText(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
