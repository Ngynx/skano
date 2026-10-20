package com.skano.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIPasteboard

/**
 * iOS actual: [UIPasteboard.generalPasteboard] is the system pasteboard;
 * `setString` writes plain text. No Context needed, so a stateless singleton
 * suffices — still remembered per composition for a symmetric call site.
 */
@Composable
actual fun rememberPlatformClipboard(): PlatformClipboard = remember {
    object : PlatformClipboard {
        override fun setText(text: String) {
            UIPasteboard.generalPasteboard.setString(text)
        }
    }
}
