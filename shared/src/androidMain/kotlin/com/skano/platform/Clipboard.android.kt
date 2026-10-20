package com.skano.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android actual: wraps [ClipboardManager] from the system service. The
 * [Context] comes from `LocalContext` — the reason this is a remembered
 * factory instead of a plain `object`.
 */
@Composable
actual fun rememberPlatformClipboard(): PlatformClipboard {
    val context = LocalContext.current
    return remember(context) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        object : PlatformClipboard {
            override fun setText(text: String) {
                manager.setPrimaryClip(ClipData.newPlainText("Skano", text))
            }
        }
    }
}
