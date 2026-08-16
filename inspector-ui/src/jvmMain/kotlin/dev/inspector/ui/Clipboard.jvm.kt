package dev.inspector.ui

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal actual fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
