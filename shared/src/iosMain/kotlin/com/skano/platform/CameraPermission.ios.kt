package com.skano.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS actual reading AVCaptureDevice authorization directly. [request] shows
 * the system prompt; the completion hops to the main queue before updating the
 * state so Compose observes the change safely.
 *
 * NOTE: `authorizationStatusForMediaType` and `requestAccessForMediaType` are
 * Objective-C category members; Kotlin/Native imports them as extension
 * functions on `AVCaptureDevice.Companion`, so they require explicit imports
 * (they are NOT plain companion members). `AVAuthorizationStatusAuthorized`
 * is a top-level constant, not an enum entry.
 */
@Composable
actual fun rememberCameraPermissionState(): CameraPermissionState {
    var granted by remember {
        mutableStateOf(
            AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) ==
                AVAuthorizationStatusAuthorized,
        )
    }
    return remember {
        object : CameraPermissionState {
            override val isGranted: Boolean
                get() = granted

            override fun request() {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { isGrantedNow ->
                    dispatch_async(dispatch_get_main_queue()) {
                        granted = isGrantedNow
                    }
                }
            }
        }
    }
}
