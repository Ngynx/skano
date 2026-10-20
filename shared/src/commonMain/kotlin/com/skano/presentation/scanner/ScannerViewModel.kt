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
 * One distinct code seen this session, as shown in the batch stack panel.
 *
 * [count] mirrors the repository's persisted count for that (value, format),
 * so a repeat re-scan bumps the row in place instead of adding a new one.
 * [format] participates in identity: the same digits in two symbologies are
 * two different codes.
 * [recordId] is the stable database row id, used to persist the manual
 * reference label ([note]) back to that row. [note] is the user's free-text
 * reference (e.g. "A07"); blank means no label.
 */
data class ScannedEntry(
    val value: String,
    val format: CodeFormat,
    val count: Long,
    val recordId: Long,
    val note: String?,
)

/**
 * Immutable UI state for the scanning screen.
 *
 * [lastScan] is null before the first emission (and after a dismiss);
 * [count] is the persisted scan count of that code, so the overlay can show
 * "×N" and prove a re-scan actually reached the repository.
 * [batchMode] is false (single mode) by default: detections are locked while
 * a chip is visible; true lets detections flow continuously.
 * [scanned] is the session stack shown by the batch panel: first-seen order
 * (oldest first), one entry per distinct (value, format) with its running
 * count. It accumulates in BOTH modes, so enabling batch mode mid-session
 * reveals codes scanned earlier. Dismissing the chip does not clear it;
 * only [ScannerViewModel.clearScanned] does.
 */
data class ScannerUiState(
    val lastScan: ScanEvent? = null,
    val count: Long = 0L,
    val batchMode: Boolean = false,
    val scanned: List<ScannedEntry> = emptyList(),
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
     *
     * Every emitted scan ALSO maintains [ScannerUiState.scanned] in both
     * modes: a new (value, format) is appended (first-seen order), a repeat
     * updates its count in place so rows never duplicate or reorder. One
     * `copy()` publishes everything so the chip and the stack never disagree.
     */
    fun onRawDetection(value: String, reported: CodeFormat) {
        val state = _uiState.value
        if (!state.batchMode && state.lastScan != null) return
        val normalized = BarcodeNormalizer.normalize(value, reported)
        if (!debouncer.shouldEmit(normalized.value, normalized.format, nowMs())) return
        val result = records.addScan(normalized.value, normalized.format.storedName, nowMs())
        val entry = ScannedEntry(
            value = normalized.value,
            format = normalized.format,
            count = result.count,
            recordId = result.recordId,
            note = result.note,
        )
        // copy() so the mode survives a new scan; constructing a fresh state
        // would silently drop back to single mode after the first batch scan.
        _uiState.value = state.copy(
            lastScan = normalized,
            count = result.count,
            scanned = upsertScanned(state.scanned, entry),
        )
    }

    /**
     * Returns [scanned] with [entry] applied: appended when (value, format)
     * is new, or written back at its EXISTING index when it repeats — the
     * panel's row order must track first sighting, never scan frequency.
     * A repeat updates ONLY the count: the entry's recordId and the user's
     * manual note survive re-scans (the repository would return the same
     * values anyway, but keeping the existing ones makes the intent local).
     */
    private fun upsertScanned(scanned: List<ScannedEntry>, entry: ScannedEntry): List<ScannedEntry> {
        val index = scanned.indexOfFirst { it.value == entry.value && it.format == entry.format }
        if (index < 0) return scanned + entry
        val existing = scanned[index]
        return scanned.toMutableList().also {
            it[index] = entry.copy(recordId = existing.recordId, note = existing.note)
        }
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
     * after a dismiss. [ScannerUiState.scanned] survives: the chip and the
     * batch stack are independent views over the same session.
     */
    fun dismissLastScan() {
        _uiState.value = _uiState.value.copy(lastScan = null, count = 0L)
        debouncer.reset()
    }

    /**
     * Empties the batch stack panel (view-only). Everything else — batch
     * mode, the visible chip, its count — is untouched, and the database is
     * NOT rolled back: records are durable, this only resets what the panel
     * shows for the current session.
     */
    fun clearScanned() {
        _uiState.value = _uiState.value.copy(scanned = emptyList())
    }

    /**
     * Sets or clears the manual reference label for one stack entry.
     * [note] is normalized first: blank/whitespace-only input becomes null
     * so the UI treats "cleared" and "never set" identically. The change is
     * persisted via [RecordRepository.setNote], then applied to the matching
     * scanned entry IN PLACE (same index, stable order) in one state write;
     * an unknown recordId leaves the state untouched.
     */
    fun setEntryNote(recordId: Long, note: String?) {
        val normalized = note?.trim()?.takeIf { it.isNotEmpty() }
        records.setNote(recordId, normalized)
        val state = _uiState.value
        val index = state.scanned.indexOfFirst { it.recordId == recordId }
        if (index < 0) return
        val updated = state.scanned.toMutableList().also {
            it[index] = it[index].copy(note = normalized)
        }
        _uiState.value = state.copy(scanned = updated)
    }

    /** Clears the debouncer slot; call when leaving the scanning screen. */
    fun resetDebouncer() {
        debouncer.reset()
    }
}
