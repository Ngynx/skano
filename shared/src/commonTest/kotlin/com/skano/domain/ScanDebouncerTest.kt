package com.skano.domain

import com.skano.domain.model.CodeFormat
import com.skano.domain.scanner.ScanDebouncer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanDebouncerTest {

    @Test
    fun firstEventEmits() {
        val debouncer = ScanDebouncer(windowMs = 1_200L)

        assertTrue(debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 0L))
    }

    @Test
    fun sameCodeInsideWindowIsSuppressed() {
        val debouncer = ScanDebouncer(windowMs = 1_200L)
        debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 0L)

        assertFalse(debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 100L))
        // State is unchanged by a suppressed detection: still suppressed near the end.
        assertFalse(debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 1_199L))
    }

    @Test
    fun sameCodeAfterWindowEmits() {
        val debouncer = ScanDebouncer(windowMs = 1_200L)
        debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 0L)

        assertTrue(debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 1_201L))
    }

    @Test
    fun exactBoundaryEmits() {
        val debouncer = ScanDebouncer(windowMs = 1_200L)
        debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 0L)

        // Window boundary is exclusive for suppression: nowMs - last == windowMs emits.
        assertTrue(debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 1_200L))
    }

    @Test
    fun equalTimestampsInsideWindowAreSuppressed() {
        val debouncer = ScanDebouncer(windowMs = 1_200L)
        debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 500L)

        assertFalse(debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 500L))
    }

    @Test
    fun differentCodeInsideWindowEmitsAndRearms() {
        val debouncer = ScanDebouncer(windowMs = 1_200L)
        debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 0L)

        // A different code always emits, even deep inside the previous window...
        assertTrue(debouncer.shouldEmit("9780201379624", CodeFormat.UPC_A, nowMs = 50L))
        // ...and becomes the new last code: the old code no longer matches the slot.
        assertTrue(debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 100L))
    }

    @Test
    fun interleavedABAEmitsAllThree() {
        val debouncer = ScanDebouncer(windowMs = 1_200L)

        assertTrue(debouncer.shouldEmit("A", CodeFormat.CODE_128, nowMs = 0L))
        assertTrue(debouncer.shouldEmit("B", CodeFormat.CODE_128, nowMs = 50L))
        // Interleaving different codes re-arms the window: the second A emits.
        assertTrue(debouncer.shouldEmit("A", CodeFormat.CODE_128, nowMs = 100L))
    }

    @Test
    fun differentFormatOfSameValueIsADifferentCode() {
        val debouncer = ScanDebouncer(windowMs = 1_200L)
        debouncer.shouldEmit("012345678905", CodeFormat.UPC_A, nowMs = 0L)

        assertTrue(debouncer.shouldEmit("012345678905", CodeFormat.EAN_13, nowMs = 10L))
    }

    @Test
    fun resetRearmsImmediately() {
        val debouncer = ScanDebouncer(windowMs = 1_200L)
        debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 0L)
        assertFalse(debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 100L))

        debouncer.reset()

        assertTrue(debouncer.shouldEmit("4006381333931", CodeFormat.EAN_13, nowMs = 200L))
    }
}
