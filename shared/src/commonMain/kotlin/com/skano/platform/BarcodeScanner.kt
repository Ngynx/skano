package com.skano.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.skano.domain.model.CodeFormat

/**
 * Live camera preview that decodes barcodes of the requested [formats].
 *
 * Contract for implementations:
 * - [onDetected] is invoked on the MAIN thread with the raw decoded value and
 *   the reported [CodeFormat] (unknown symbologies map to [CodeFormat.UNKNOWN]).
 * - The camera keeps running after a detection (continuous scanning); callers
 *   apply their own de-duplication (see
 *   [com.skano.domain.scanner.ScanDebouncer]).
 * - The composable owns the whole camera lifecycle: it binds the camera on
 *   enter and unbinds/releases it on dispose. Callers must not manage the
 *   provider themselves.
 * - Implementations must NOT persist anything — persistence is the
 *   ViewModel's job.
 *
 * Implementations that cannot decode yet (Phase 4 iOS) render a placeholder
 * and never invoke [onDetected].
 */
@Composable
expect fun BarcodeScannerView(
    formats: Set<CodeFormat>,
    onDetected: (value: String, format: CodeFormat) -> Unit,
    modifier: Modifier = Modifier,
)
