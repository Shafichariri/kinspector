package dev.inspector.ui

import platform.UIKit.UIPasteboard

internal actual fun copyToClipboard(text: String) {
    UIPasteboard.generalPasteboard.string = text
}
