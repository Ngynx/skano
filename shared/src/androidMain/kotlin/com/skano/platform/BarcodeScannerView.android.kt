package com.skano.platform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
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
import kotlin.math.max

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
    // Drawn ABOVE previewView inside the FrameLayout wrapper below.
    val overlayView = remember { ScanOverlayView(context) }
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
                analyzeFrame(
                    imageProxy,
                    mlKitScanner,
                    mainHandler,
                    previewView,
                    overlayView,
                ) { value, format ->
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

    // FrameLayout so the detection overlay can be layered above the camera
    // image; both children fill it explicitly (matching FILL_CENTER geometry).
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                addView(
                    previewView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    overlayView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

/** When true, each delivered detection logs one line — for manual on-device A→B testing; a later phase removes it. */
private const val DEBUG_LOG_DETECTIONS = true

// ImageProxy.image is the CameraX-recommended way to reach the frame for
// InputImage.fromMediaImage; it must be consumed before the proxy closes.
// Geometry needed for the overlay (size/rotation) is captured BEFORE any
// listener can close the proxy.
private fun analyzeFrame(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    mainHandler: Handler,
    previewView: PreviewView,
    overlayView: ScanOverlayView,
    deliver: (value: String, format: CodeFormat) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
    val proxyWidth = imageProxy.width
    val proxyHeight = imageProxy.height
    val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
    try {
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                    ?: return@addOnSuccessListener
                val value = barcode.rawValue ?: return@addOnSuccessListener
                val format = barcode.format.toCodeFormat()
                // Copy: the box must outlive the proxy close in onComplete.
                val box = barcode.boundingBox?.let { Rect(it) }
                // Contract: onDetected always fires on the main thread.
                mainHandler.post {
                    if (DEBUG_LOG_DETECTIONS) Log.d("Skano", "detection: $value ($format)")
                    deliver(value, format)
                    if (box != null) {
                        showDetectionOverlay(
                            box, rotationDegrees, proxyWidth, proxyHeight,
                            previewView, overlayView,
                        )
                    }
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

/**
 * Maps [box] (ML Kit upright image space — rotation already applied by
 * InputImage) into view space with the same math PreviewView.FILL_CENTER uses
 * for the preview buffer, then shows it. Main thread only; silently skips when
 * the view (or the buffer) has no size yet.
 */
private fun showDetectionOverlay(
    box: Rect,
    rotationDegrees: Int,
    proxyWidth: Int,
    proxyHeight: Int,
    previewView: PreviewView,
    overlayView: ScanOverlayView,
) {
    val viewW = previewView.width
    val viewH = previewView.height
    if (viewW <= 0 || viewH <= 0) return
    // Buffer size is pre-rotation; upright swaps the sides at 90°/270°.
    val uprightW: Int
    val uprightH: Int
    if (rotationDegrees == 90 || rotationDegrees == 270) {
        uprightW = proxyHeight
        uprightH = proxyWidth
    } else {
        uprightW = proxyWidth
        uprightH = proxyHeight
    }
    if (uprightW <= 0 || uprightH <= 0) return
    val scale = max(viewW.toFloat() / uprightW, viewH.toFloat() / uprightH)
    val dx = (viewW - uprightW * scale) / 2f
    val dy = (viewH - uprightH * scale) / 2f
    overlayView.show(
        RectF(
            box.left * scale + dx,
            box.top * scale + dy,
            box.right * scale + dx,
            box.bottom * scale + dy,
        ),
    )
}

/**
 * Detection rectangle drawn above the camera preview: 2.5dp white
 * anti-aliased stroke with sharp corners over a 15%-white fill. Appears
 * instantly on [show], holds 1200ms, then fades out over 800ms; a new [show]
 * during hold or fade cancels the pending work and re-shows at the new
 * position. All calls happen on the main thread.
 */
private class ScanOverlayView(context: Context) : View(context) {
    private val density: Float = resources.displayMetrics.density
    private val cornerRadius: Float = 0f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(38, 255, 255, 255)
    }

    /** Null once the fade finished; only touched on the main thread. */
    private var rect: RectF? = null

    /** Fired after the hold: fade out, then clear the rect. */
    private val fadeAction = Runnable {
        animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction {
                rect = null
                alpha = 1f
                invalidate()
            }
            .start()
    }

    fun show(rect: RectF) {
        // Cancel any pending hold or in-flight fade — never let it hide this
        // new rect. Order matters: even if cancel() ran the old end action,
        // the assignments below win.
        animate().cancel()
        removeCallbacks(fadeAction)
        this.rect = rect
        alpha = 1f
        invalidate()
        postDelayed(fadeAction, HOLD_MS)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = rect ?: return
        canvas.drawRoundRect(r, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(r, cornerRadius, cornerRadius, strokePaint)
    }

    private companion object {
        const val HOLD_MS = 1200L
        const val FADE_MS = 800L
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
