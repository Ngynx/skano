package com.skano.domain.scanner

import com.skano.domain.model.CodeFormat
import com.skano.domain.model.ScanEvent

/**
 * Cross-platform barcode value normalization.
 *
 * iOS AVFoundation reports UPC-A as an EAN-13 with a leading '0' (GS1 rule:
 * an EAN-13 starting with '0' encodes a UPC-A in its last 12 digits), while
 * Android reports a real 12-digit UPC-A. To store the same thing on both
 * platforms, a 13-digit EAN-13 starting with '0' whose remaining 12
 * characters are digits is folded to a 12-digit [CodeFormat.UPC_A].
 *
 * Every other combination passes through unchanged. This never throws and
 * performs no trimming beyond the stated rule.
 */
object BarcodeNormalizer {

    /** Normalizes a reported detection into the canonical [ScanEvent] to persist. */
    fun normalize(value: String, reported: CodeFormat): ScanEvent {
        val isUpcAEan13Fold =
            reported == CodeFormat.EAN_13 &&
                value.length == 13 &&
                value.startsWith("0") &&
                value.drop(1).all { it.isDigit() }
        return if (isUpcAEan13Fold) {
            ScanEvent(value.substring(1), CodeFormat.UPC_A)
        } else {
            ScanEvent(value, reported)
        }
    }
}
