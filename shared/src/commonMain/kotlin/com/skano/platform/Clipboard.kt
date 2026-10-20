package com.skano.platform

import androidx.compose.runtime.Composable

/**
 * Minimal seam over the system clipboard so commonMain UI can copy scan
 * values without touching Android/iOS APIs directly.
 *
 * Implemented as an interface + @Composable remember factory — the same
 * shape as [CameraPermissionState]/[rememberCameraPermissionState] — because
 * the Android implementation needs a `Context` (from `LocalContext`) that a
 * plain `object` cannot obtain, while iOS needs no state at all.
 */
interface PlatformClipboard {
    /** Copies [text] to the system clipboard. */
    fun setText(text: String)
}

/**
 * remembers a [PlatformClipboard] for the current composition: one instance
 * per screen, bound to the platform's clipboard service.
 */
@Composable
expect fun rememberPlatformClipboard(): PlatformClipboard
