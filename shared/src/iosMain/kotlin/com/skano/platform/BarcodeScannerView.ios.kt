package com.skano.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.skano.domain.model.CodeFormat
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectType
import platform.AVFoundation.AVMetadataObjectTypeCode128Code
import platform.AVFoundation.AVMetadataObjectTypeEAN13Code
import platform.AVFoundation.AVMetadataObjectTypeEAN8Code
import platform.AVFoundation.AVMetadataObjectTypeUPCECode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CATransaction
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import platform.UIKit.UIView
import kotlin.concurrent.Volatile

/**
 * AVFoundation implementation of [BarcodeScannerView].
 *
 * A dedicated `ScannerPreviewView` (UIView subclass) hosts an
 * `AVCaptureVideoPreviewLayer` sublayer (aspect-fill) and re-frames it from
 * `layoutSubviews` — UIKitView attaches the view at zero size, so a plain
 * UIView would leave the preview invisible. One `AVCaptureSession` pipeline is
 * created per composition, started only when camera permission is already
 * authorized, and fully torn down on dispose so navigating in/out never stacks
 * sessions. Detections are delivered on the MAIN thread, continuously, with no
 * persistence — matching the commonMain contract. Missing camera or unmappable
 * formats leave the view empty and never crash (no fake detections).
 *
 * Kotlin/Native 2.4.20 AVFoundation interop forms proven by compile probes
 * (do NOT guess — these are the accepted forms):
 * - NSObject lives in `platform.darwin` (NOT `platform.Foundation` as before).
 * - Category methods `authorizationStatusForMediaType` /
 *   `defaultDeviceWithDeviceType` import as extension functions and need
 *   explicit imports; `defaultDeviceWithMediaType` and
 *   `AVCaptureDeviceInput.deviceInputWithDevice` are companion members (no import).
 * - NS_ENUM / NS_EXTENSIBLE_STRING_ENUM constants are TOP-LEVEL vals:
 *   `AVCaptureSessionPresetHigh`, `AVCaptureDeviceTypeBuiltInWideAngleCamera`,
 *   `AVCaptureDevicePositionBack`, `AVMetadataObjectTypeEAN13Code`, ...,
 *   `AVLayerVideoGravityResizeAspectFill`, `AVCaptureVideoOrientationPortrait`,
 *   `AVAuthorizationStatusAuthorized`.
 * - `AVMetadataObjectType` is a nullable String typealias; metadata object
 *   type lists are `List<String?>`.
 * - The delegate protocol is `AVCaptureMetadataOutputObjectsDelegateProtocol`;
 *   `captureOutput` takes `didOutputMetadataObjects: List<*>`; the setter is
 *   `setMetadataObjectsDelegate(objectsDelegate, queue)` (member, not import).
 * - `session.isRunning` is a FUNCTION (`isRunning()`), not a property.
 * - GCD: `dispatch_async(dispatch_get_main_queue()) {}`,
 *   `dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)`,
 *   `dispatch_queue_create(label, null)`.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun BarcodeScannerView(
    formats: Set<CodeFormat>,
    onDetected: (value: String, format: CodeFormat) -> Unit,
    modifier: Modifier,
) {
    // Always invoke the latest lambda after recomposition; the delegate
    // (plain ObjC object) cannot observe Compose state directly.
    val currentOnDetected by rememberUpdatedState(onDetected)
    val deliver: (String, CodeFormat) -> Unit = remember {
        { value, format -> currentOnDetected(value, format) }
    }
    val pipeline = remember(formats) { ScannerPipeline(formats, deliver) }

    DisposableEffect(pipeline) {
        pipeline.start()
        onDispose { pipeline.release() }
    }

    UIKitView(
        factory = { ScannerPreviewView(pipeline).also(pipeline::attachPreviewTo) },
        modifier = modifier.fillMaxSize(),
        update = { pipeline.updatePreviewFrame(it.bounds) },
    )
}

/** When true, each detection prints one line — for manual on-device testing only. */
private const val DEBUG_LOG_DETECTIONS = true

/**
 * Host UIView that keeps [ScannerPipeline]'s preview layer glued to the view
 * bounds on every layout pass. A plain UIView is NOT enough: [UIKitView]
 * attaches it at zero size and only fires `update` on recomposition, so the
 * layer must react to `layoutSubviews` itself or the preview never renders.
 */
@OptIn(ExperimentalForeignApi::class)
private class ScannerPreviewView(
    private val pipeline: ScannerPipeline,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        pipeline.updatePreviewFrame(bounds)
        CATransaction.commit()
    }
}

/**
 * The whole AVFoundation pipeline for one composition lifetime. All state is
 * owned here so dispose deterministically stops the session, detaches the
 * delegate, and drops inputs/outputs — repeated navigation must not stack
 * sessions or leak delegates.
 */
@OptIn(ExperimentalForeignApi::class)
private class ScannerPipeline(
    formats: Set<CodeFormat>,
    private val onDetected: (value: String, format: CodeFormat) -> Unit,
) {
    private val session = AVCaptureSession()
    private val output = AVCaptureMetadataOutput()
    private var cameraInput: AVCaptureDeviceInput? = null
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session)
    private val metadataQueue: dispatch_queue_t =
        dispatch_queue_create("com.skano.scanner.metadata", null)

    /** Read on the metadata/main queues, written on main during dispose. */
    @Volatile
    var released: Boolean = false
        private set

    /**
     * STRONG reference to the ObjC delegate. AVCaptureMetadataOutput holds
     * `metadataObjectsDelegate` WEAKLY: a delegate with no strong owner is
     * collected by Kotlin/Native after the first callback batch, the weak
     * link nils out, and every later detection silently stops arriving
     * (first scan works, the rest never fire). Declared BEFORE [configured]
     * because configure() installs it into the output.
     */
    private val metadataDelegate: MetadataDelegate = MetadataDelegate(this)

    /** True once inputs/outputs are configured; false leaves the view empty. */
    private val configured: Boolean = configure(formats)

    init {
        previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    private fun configure(formats: Set<CodeFormat>): Boolean {
        val types = formats.mapNotNull { it.toAVMetadataObjectType() }.distinct()
        if (types.isEmpty()) return false
        session.beginConfiguration()
        session.sessionPreset = AVCaptureSessionPresetHigh
        // Back camera first; fall back to any video device; still nil (e.g.
        // simulator) => stay empty, never crash.
        val camera = AVCaptureDevice.defaultDeviceWithDeviceType(
            deviceType = AVCaptureDeviceTypeBuiltInWideAngleCamera,
            mediaType = AVMediaTypeVideo,
            position = AVCaptureDevicePositionBack,
        ) ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = camera?.let { AVCaptureDeviceInput.deviceInputWithDevice(device = it, error = null) }
        val hasCamera = input != null && session.canAddInput(input)
        if (hasCamera) {
            session.addInput(input)
            cameraInput = input
        }
        // addOutput MUST happen before metadataObjectTypes, or AVFoundation throws.
        val hasOutput = session.canAddOutput(output)
        if (hasOutput) session.addOutput(output)
        session.commitConfiguration()
        if (!hasCamera || !hasOutput) return false
        output.metadataObjectTypes = types
        output.setMetadataObjectsDelegate(
            objectsDelegate = metadataDelegate,
            queue = metadataQueue,
        )
        return true
    }

    /** startRunning blocks — always call it off the main thread. */
    fun start() {
        if (!configured || released) return
        if (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) !=
            AVAuthorizationStatusAuthorized
        ) {
            return
        }
        dispatch_async(globalQueue()) {
            if (!released && !session.isRunning()) session.startRunning()
        }
    }

    /** Idempotent teardown: no leaked sessions or delegates after dispose. */
    fun release() {
        if (released) return
        released = true
        output.setMetadataObjectsDelegate(objectsDelegate = null, queue = null)
        dispatch_async(globalQueue()) {
            if (session.isRunning()) session.stopRunning()
            session.beginConfiguration()
            cameraInput?.let { session.removeInput(it) }
            cameraInput = null
            session.removeOutput(output)
            session.commitConfiguration()
        }
    }

    /** Hosts the preview as a plain sublayer — no UIView subclassing. */
    fun attachPreviewTo(view: UIView) {
        previewLayer.frame = view.bounds
        previewLayer.connection?.videoOrientation = AVCaptureVideoOrientationPortrait
        view.layer.addSublayer(previewLayer)
    }

    fun updatePreviewFrame(frame: CValue<CGRect>) {
        previewLayer.frame = frame
    }

    fun emit(value: String, format: CodeFormat) {
        if (DEBUG_LOG_DETECTIONS) println("Skano debug scan: $value ($format)")
        onDetected(value, format)
    }
}

/**
 * Continuous metadata scanning: takes the FIRST non-blank barcode of each
 * frame batch and hops to MAIN before emitting (commonMain contract).
 */
@OptIn(ExperimentalForeignApi::class)
private class MetadataDelegate(
    private val pipeline: ScannerPipeline,
) : NSObject(),
    AVCaptureMetadataOutputObjectsDelegateProtocol {
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        if (pipeline.released) return
        val barcode = didOutputMetadataObjects.firstOrNull()
            as? AVMetadataMachineReadableCodeObject ?: return
        val value = barcode.stringValue?.takeIf { it.isNotBlank() } ?: return
        val format = barcode.type.toCodeFormat()
        // Contract: onDetected always fires on the main thread.
        dispatch_async(dispatch_get_main_queue()) {
            if (!pipeline.released) pipeline.emit(value, format)
        }
    }
}

/** CodeFormat -> AV metadata type; unmappable formats are skipped. */
private fun CodeFormat.toAVMetadataObjectType(): AVMetadataObjectType = when (this) {
    // AVFoundation has no dedicated UPC-A type: UPC-A symbols are reported as
    // EAN-13 with a leading '0' and folded to UPC_A by BarcodeNormalizer.
    CodeFormat.EAN_13, CodeFormat.UPC_A -> AVMetadataObjectTypeEAN13Code
    CodeFormat.EAN_8 -> AVMetadataObjectTypeEAN8Code
    CodeFormat.UPC_E -> AVMetadataObjectTypeUPCECode
    CodeFormat.CODE_128 -> AVMetadataObjectTypeCode128Code
    CodeFormat.UNKNOWN -> null
}

/** AV metadata type -> CodeFormat; symbologies Skano does not model map to UNKNOWN. */
private fun AVMetadataObjectType.toCodeFormat(): CodeFormat = when (this) {
    AVMetadataObjectTypeEAN13Code -> CodeFormat.EAN_13
    AVMetadataObjectTypeEAN8Code -> CodeFormat.EAN_8
    AVMetadataObjectTypeUPCECode -> CodeFormat.UPC_E
    AVMetadataObjectTypeCode128Code -> CodeFormat.CODE_128
    else -> CodeFormat.UNKNOWN
}

private fun globalQueue(): dispatch_queue_t =
    dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)
