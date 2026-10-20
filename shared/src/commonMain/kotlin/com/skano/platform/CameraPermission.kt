package com.skano.platform

import androidx.compose.runtime.Composable

/**
 * Reactive state for the camera runtime permission.
 *
 * Implementations back [isGranted] with a `MutableState`, so reading it inside
 * a composable re-renders when the permission changes. [request] shows the
 * platform prompt (no rationale UI in this phase).
 */
interface CameraPermissionState {
    /** True when the camera permission is currently granted. Reactive. */
    val isGranted: Boolean

    /** Launches the platform permission request. Safe to call repeatedly. */
    fun request()
}

/**
 * remembers a [CameraPermissionState] for the current composition, seeded
 * from the platform's current authorization status.
 */
@Composable
expect fun rememberCameraPermissionState(): CameraPermissionState
