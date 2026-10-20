package com.skano.presentation.scanner

import com.skano.data.repository.RecordRepository
import com.skano.domain.model.CodeFormat
import com.skano.domain.model.ScanEvent
import com.skano.domain.scanner.BarcodeNormalizer
import com.skano.domain.scanner.ScanDebouncer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable UI state for the scanning screen.
 *
 * [lastScan] is null before the first emission (and after a dismiss);
 * [count] is the persisted scan count of that code, so the overlay can show
 * "×N" and prove a re-scan actually reached the repository.
 * [batchMode] is false (single mode) by default: detections are locked while
 * a chip is visible; true lets detections flow continuously.
 */
data class ScannerUiState(
    val lastScan: ScanEvent? = null,
    val count: Long = 0L,
    val batchMode: Boolean = false,
)

/**
 * Scanning-screen state: turns raw camera detections into debounced,
 * normalized, persisted scan records.
 *
 * Pure Kotlin — no Android/Compose imports. The clock is injected
 * ([nowMs]) so tests control time; production passes
 * [com.skano.platform.currentTimeMillis].
 */
class ScannerViewModel(
    private val records: RecordRepository,
    private val nowMs: () -> Long,
    private val debouncer: ScanDebouncer = ScanDebouncer(),
) {

    private val _uiState = MutableStateFlow(ScannerUiState())

    /** The current overlay state: last persisted scan plus its count. */
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    /**
     * Handles one raw camera detection: normalizes it, gates it through the
     * debouncer, persists it (which returns the code's new scan count), and
     * publishes both as [uiState].
     *
     * In single mode a visible chip LOCKS further detections: we return before
     * touching the debouncer or the DB, so the lock lasts exactly as long as
     * the chip is on screen and the ✕ is the only way to re-arm scanning.
     * Batch mode keeps the pipeline open (continuous, accumulating scans).
     */
    fun onRawDetection(value: String, reported: CodeFormat) {
        val state = _uiState.value
        if (!state.batchMode && state.lastScan != null) return
        val normalized = BarcodeNormalizer.normalize(value, reported)
        if (!debouncer.shouldEmit(normalized.value, normalized.format, nowMs())) return
        val result = records.addScan(normalized.value, normalized.format.storedName, nowMs())
        // copy() so the mode survives a new scan; constructing a fresh state
        // would silently drop back to single mode after the first batch scan.
        _uiState.value = state.copy(lastScan = normalized, count = result.count)
    }

    /**
     * Flips single/batch mode. Everything else in [uiState] (the current
     * chip and its count) is preserved, so toggling mid-scan never hides
     * or rewrites the visible result.
     */
    fun toggleBatchMode() {
        val state = _uiState.value
        _uiState.value = state.copy(batchMode = !state.batchMode)
        // Debug: proves each physical tap flips exactly once (device diagnosis).
        println("Skano debug toggle: batchMode=${_uiState.value.batchMode}")
    }

    /**
     * Clears the overlay (lastScan/count back to defaults, mode retained)
     * and re-arms the debouncer, so the SAME code can immediately re-scan
     * after a dismiss.
     */
    fun dismissLastScan() {
        _uiState.value = _uiState.value.copy(lastScan = null, count = 0L)
        debouncer.reset()
    }

    /** Clears the debouncer slot; call when leaving the scanning screen. */
    fun resetDebouncer() {
        debouncer.reset()
    }
}
