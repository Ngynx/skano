package com.skano.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android actual backed by a `MutableState`: [isGranted] is seeded from the
 * current permission status and updated when the request dialog resolves
 * (denied -> false). No rationale UI in this phase.
 */
@Composable
actual fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGrantedNow -> granted = isGrantedNow }
    return remember(launcher) {
        object : CameraPermissionState {
            override val isGranted: Boolean
                get() = granted

            override fun request() {
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}
