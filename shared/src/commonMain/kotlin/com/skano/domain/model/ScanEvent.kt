package com.skano.domain.model

/**
 * A normalized, ready-to-persist scan: the code value plus its format.
 * Produced by the scanner pipeline after [com.skano.domain.scanner.BarcodeNormalizer].
 */
data class ScanEvent(val value: String, val format: CodeFormat)
