package com.skano.domain.scanner

import com.skano.domain.model.CodeFormat

/**
 * Anti-duplicate gate for continuous camera detections of the same code.
 *
 * Single-slot semantics: only the LAST emitted (value, format, timestamp)
 * is remembered — not a map of all seen codes.
 *
 * - Same (value, format) inside [windowMs]  -> suppressed (state unchanged).
 * - Same (value, format) at/after [windowMs] -> emitted, timestamp updated.
 *   The window boundary is exclusive for suppression: `nowMs - last >= windowMs`
 *   emits.
 * - A DIFFERENT (value, format) at any time -> always emitted and becomes the
 *   new last code. Interleaving different codes therefore re-arms the window:
 *   the sequence A -> B -> A emits all three events, because B replaces the
 *   single slot and the second A no longer matches it.
 *
 * Call [reset] when leaving the scanning screen to clear the slot.
 */
class ScanDebouncer(private val windowMs: Long = 1_200L) {

    private var lastValue: String? = null
    private var lastFormat: CodeFormat? = null
    private var lastTimestampMs: Long = 0L

    /**
     * Returns true when the detection ([value], [format]) at [nowMs] should be
     * emitted, false when it is a duplicate inside the window.
     */
    fun shouldEmit(value: String, format: CodeFormat, nowMs: Long): Boolean {
        val sameCode = value == lastValue && format == lastFormat
        if (!sameCode) {
            lastValue = value
            lastFormat = format
            lastTimestampMs = nowMs
            return true
        }
        // sameCode implies lastValue/lastTimestampMs were set by a prior emit.
        if (nowMs - lastTimestampMs >= windowMs) {
            lastTimestampMs = nowMs
            return true
        }
        return false
    }

    /** Clears the remembered code, re-arming the gate for an immediate re-emit. */
    fun reset() {
        lastValue = null
        lastFormat = null
        lastTimestampMs = 0L
    }
}
