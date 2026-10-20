package com.skano.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.skano.domain.model.CodeFormat
import java.util.concurrent.Executors

/**
 * CameraX + ML Kit (bundled offline model) implementation of [BarcodeScannerView].
 *
 * Continuous scanning with STRATEGY_KEEP_ONLY_LATEST: one frame is analyzed at
 * a time, the first non-blank barcode of each frame is delivered on the main
 * thread, and the ImageProxy is always closed. Missing camera or provider
 * failures leave the view empty (error UI is a later phase).
 */
@Composable
actual fun BarcodeScannerView(
    formats: Set<CodeFormat>,
    onDetected: (value: String, format: CodeFormat) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Always call the latest onDetected from the analyzer, even after recomposition.
    val currentOnDetected by rememberUpdatedState(onDetected)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Resolve the provider off the composition thread; bind only when ready.
    LaunchedEffect(context) {
        cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull()
    }

    DisposableEffect(lifecycleOwner, cameraProvider, formats) {
        val provider = cameraProvider
        if (provider == null) {
            // Provider failed to load: leave the view empty, never crash.
            onDispose {}
        } else {
            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val mainHandler = Handler(Looper.getMainLooper())
            val analyzerExecutor = Executors.newSingleThreadExecutor()
            val mlKitScanner = createMlKitScanner(formats)
            analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                analyzeFrame(imageProxy, mlKitScanner, mainHandler) { value, format ->
                    currentOnDetected(value, format)
                }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (_: Exception) {
                // No back camera or bind failure: keep the view empty for now.
            }
            onDispose {
                provider.unbindAll()
                analyzerExecutor.shutdown()
                mlKitScanner.close()
            }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}

/** When true, each delivered detection logs one line — for manual on-device A→B testing; a later phase removes it. */
private const val DEBUG_LOG_DETECTIONS = true

// ImageProxy.image is the CameraX-recommended way to reach the frame for
// InputImage.fromMediaImage; it must be consumed before the proxy closes.
private fun analyzeFrame(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    mainHandler: Handler,
    deliver: (value: String, format: CodeFormat) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    try {
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                    ?: return@addOnSuccessListener
                val value = barcode.rawValue ?: return@addOnSuccessListener
                val format = barcode.format.toCodeFormat()
                // Contract: onDetected always fires on the main thread.
                mainHandler.post {
                    if (DEBUG_LOG_DETECTIONS) Log.d("Skano", "detection: $value ($format)")
                    deliver(value, format)
                }
            }
            .addOnFailureListener {
                // Analysis errors are non-fatal; the frame is simply skipped.
            }
            .addOnCompleteListener {
                // ALWAYS release the frame, success or failure.
                imageProxy.close()
            }
    } catch (_: RuntimeException) {
        // Synchronous failure: never leak the proxy.
        imageProxy.close()
    }
}

/** Creates the ML Kit scanner once per bind; [formats] empty or unmapped falls back to all formats. */
private fun createMlKitScanner(formats: Set<CodeFormat>): BarcodeScanner {
    val mapped = formats.mapNotNull { it.toMlKitFormat() }.distinct().toIntArray()
    return if (mapped.isEmpty()) {
        BarcodeScanning.getClient()
    } else {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(mapped.first(), *mapped.copyOfRange(1, mapped.size))
            .build()
        BarcodeScanning.getClient(options)
    }
}

private fun CodeFormat.toMlKitFormat(): Int? = when (this) {
    CodeFormat.EAN_13 -> Barcode.FORMAT_EAN_13
    CodeFormat.EAN_8 -> Barcode.FORMAT_EAN_8
    CodeFormat.UPC_A -> Barcode.FORMAT_UPC_A
    CodeFormat.UPC_E -> Barcode.FORMAT_UPC_E
    CodeFormat.CODE_128 -> Barcode.FORMAT_CODE_128
    CodeFormat.UNKNOWN -> null
}

private fun Int.toCodeFormat(): CodeFormat = when (this) {
    Barcode.FORMAT_EAN_13 -> CodeFormat.EAN_13
    Barcode.FORMAT_EAN_8 -> CodeFormat.EAN_8
    Barcode.FORMAT_UPC_A -> CodeFormat.UPC_A
    Barcode.FORMAT_UPC_E -> CodeFormat.UPC_E
    Barcode.FORMAT_CODE_128 -> CodeFormat.CODE_128
    // Anything else the scanner reports: the domain's catch-all.
    else -> CodeFormat.UNKNOWN
}
