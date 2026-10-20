package com.skano.domain

import com.skano.domain.model.CodeFormat
import com.skano.domain.model.ScanEvent
import com.skano.domain.scanner.BarcodeNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

class BarcodeNormalizerTest {

    @Test
    fun ean13WithLeadingZeroFoldsToTwelveDigitUpcA() {
        // GS1: an EAN-13 starting with '0' encodes a UPC-A in its last 12 digits.
        val event = BarcodeNormalizer.normalize("0012345678905", CodeFormat.EAN_13)

        assertEquals(ScanEvent("012345678905", CodeFormat.UPC_A), event)
    }

    @Test
    fun ean13NotStartingWithZeroIsUnchanged() {
        val event = BarcodeNormalizer.normalize("4006381333931", CodeFormat.EAN_13)

        assertEquals(ScanEvent("4006381333931", CodeFormat.EAN_13), event)
    }

    @Test
    fun leadingZeroEan13WithWrongLengthIsUnchanged() {
        // 12 digits: not a 13-char EAN-13, so no fold.
        assertEquals(
            ScanEvent("012345678905", CodeFormat.EAN_13),
            BarcodeNormalizer.normalize("012345678905", CodeFormat.EAN_13),
        )
        // 14 chars: not a 13-char EAN-13, so no fold.
        assertEquals(
            ScanEvent("00123456789051", CodeFormat.EAN_13),
            BarcodeNormalizer.normalize("00123456789051", CodeFormat.EAN_13),
        )
    }

    @Test
    fun leadingZeroEan13WithNonDigitTailIsUnchanged() {
        val event = BarcodeNormalizer.normalize("012345678901A", CodeFormat.EAN_13)

        assertEquals(ScanEvent("012345678901A", CodeFormat.EAN_13), event)
    }

    @Test
    fun ean8PassesThrough() {
        assertEquals(
            ScanEvent("96385074", CodeFormat.EAN_8),
            BarcodeNormalizer.normalize("96385074", CodeFormat.EAN_8),
        )
    }

    @Test
    fun code128PassesThroughEvenWithLeadingZeroThirteenDigits() {
        // The fold rule applies ONLY to a reported EAN-13.
        assertEquals(
            ScanEvent("0123456789012", CodeFormat.CODE_128),
            BarcodeNormalizer.normalize("0123456789012", CodeFormat.CODE_128),
        )
    }

    @Test
    fun upcEPassesThrough() {
        assertEquals(
            ScanEvent("01234565", CodeFormat.UPC_E),
            BarcodeNormalizer.normalize("01234565", CodeFormat.UPC_E),
        )
    }

    @Test
    fun androidReportedTwelveDigitUpcAIsUnchanged() {
        // Android already reports a real 12-digit UPC-A: nothing to normalize.
        assertEquals(
            ScanEvent("012345678905", CodeFormat.UPC_A),
            BarcodeNormalizer.normalize("012345678905", CodeFormat.UPC_A),
        )
    }
}
