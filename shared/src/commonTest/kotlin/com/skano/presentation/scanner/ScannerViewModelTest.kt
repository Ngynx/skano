package com.skano.presentation.scanner

import com.skano.data.repository.RecordRepository
import com.skano.data.repository.ScanCountResult
import com.skano.db.Scan_records
import com.skano.domain.model.CodeFormat
import com.skano.domain.model.ScanEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * ScannerViewModel tests with a hand-written fake repository (per-(value,
 * format) incrementing counts) and a settable fake clock. No real time, no
 * database.
 */
class ScannerViewModelTest {

    /** In-memory [RecordRepository]: one row per (value, format), count grows per sighting. */
    private class FakeRecordRepository : RecordRepository {
        data class Row(val rawValue: String, val format: String, val createdAt: Long)

        val rows = mutableListOf<Row>()
        var addScanCalls = 0
            private set

        private var nextId = 1L
        private val idsByCode = mutableMapOf<Pair<String, String>, Long>()
        private val countsByCode = mutableMapOf<Pair<String, String>, Long>()

        override fun addScan(rawValue: String, format: String, createdAt: Long): ScanCountResult {
            addScanCalls++
            val code = rawValue to format
            val id = idsByCode.getOrPut(code) {
                rows += Row(rawValue, format, createdAt)
                nextId++
            }
            val count = (countsByCode[code] ?: 0L) + 1L
            countsByCode[code] = count
            return ScanCountResult(id, count)
        }

        override fun observeSearch(query: String): Flow<List<Scan_records>> = flowOf(emptyList())

        override fun setLabel(recordId: Long, labelId: Long?) = Unit

        override fun setNote(recordId: Long, note: String?) = Unit
    }

    private var now = 0L
    private val records = FakeRecordRepository()
    private val viewModel = ScannerViewModel(records, nowMs = { now })

    @Test
    fun firstDetectionPersistsOnceAndPublishesUiStateWithCountOne() {
        assertEquals(ScannerUiState(), viewModel.uiState.value)

        // iOS-style report: EAN-13 with leading zero folds to 12-digit UPC-A.
        viewModel.onRawDetection("0012345678905", CodeFormat.EAN_13)

        assertEquals(1, records.addScanCalls)
        assertEquals(
            listOf(FakeRecordRepository.Row("012345678905", "UPC_A", 0L)),
            records.rows,
        )
        assertEquals(
            ScannerUiState(ScanEvent("012345678905", CodeFormat.UPC_A), count = 1L),
            viewModel.uiState.value,
        )
    }

    @Test
    fun sameDetectionInsideWindowDoesNotPersistAgain() {
        viewModel.onRawDetection("0012345678905", CodeFormat.EAN_13)
        val stateAfterFirst = viewModel.uiState.value

        now = 500L
        viewModel.onRawDetection("0012345678905", CodeFormat.EAN_13)

        assertEquals(1, records.addScanCalls)
        assertEquals(1, records.rows.size)
        assertEquals(stateAfterFirst, viewModel.uiState.value)
    }

    @Test
    fun sameDetectionAfterWindowPersistsAgainWithIncrementedCount() {
        // The single-mode lock would swallow this re-scan (the chip is still
        // visible); batch mode keeps the pipeline open so the debouncer
        // WINDOW, not the lock, is what this test exercises.
        viewModel.toggleBatchMode()
        viewModel.onRawDetection("0012345678905", CodeFormat.EAN_13)

        now = 1_201L
        viewModel.onRawDetection("0012345678905", CodeFormat.EAN_13)

        // The repository keeps ONE row per code but the count grows, and the
        // overlay state must show the NEW count (×2) so a re-scan is visible.
        assertEquals(2, records.addScanCalls)
        assertEquals(1, records.rows.size)
        assertEquals(
            ScannerUiState(
                ScanEvent("012345678905", CodeFormat.UPC_A),
                count = 2L,
                batchMode = true,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun differentCodeInsideWindowStillEmits() {
        // Same rationale as above: single mode locks while the chip is up,
        // so this stays on batch mode to cover the debouncer's
        // "different code always emits" rule.
        viewModel.toggleBatchMode()
        viewModel.onRawDetection("0012345678905", CodeFormat.EAN_13)

        now = 100L
        viewModel.onRawDetection("SKANO-01", CodeFormat.CODE_128)

        assertEquals(2, records.addScanCalls)
        assertEquals(
            listOf(
                FakeRecordRepository.Row("012345678905", "UPC_A", 0L),
                FakeRecordRepository.Row("SKANO-01", "CODE_128", 100L),
            ),
            records.rows,
        )
        assertEquals(
            ScannerUiState(
                ScanEvent("SKANO-01", CodeFormat.CODE_128),
                count = 1L,
                batchMode = true,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun dismissClearsUiStateAndRearmsDebouncerForImmediateRescan() {
        viewModel.onRawDetection("4006381333931", CodeFormat.EAN_13)
        assertEquals(
            ScannerUiState(ScanEvent("4006381333931", CodeFormat.EAN_13), count = 1L),
            viewModel.uiState.value,
        )

        viewModel.dismissLastScan()
        assertEquals(ScannerUiState(), viewModel.uiState.value)

        // Same code immediately after dismiss must re-emit (debouncer reset)
        // and bump the count to 2.
        now = 100L
        viewModel.onRawDetection("4006381333931", CodeFormat.EAN_13)

        assertEquals(2, records.addScanCalls)
        assertEquals(
            ScannerUiState(ScanEvent("4006381333931", CodeFormat.EAN_13), count = 2L),
            viewModel.uiState.value,
        )
    }

    @Test
    fun defaultStateIsSingleMode() {
        assertFalse(viewModel.uiState.value.batchMode)
        assertEquals(ScannerUiState(), viewModel.uiState.value)
    }

    @Test
    fun singleModeLocksDetectionsWhileChipVisible() {
        viewModel.onRawDetection("0012345678905", CodeFormat.EAN_13)
        val chipVisible = viewModel.uiState.value

        // A DIFFERENT code while the chip is up must be swallowed entirely:
        // no debouncer write, no repository write, state untouched.
        now = 100L
        viewModel.onRawDetection("SKANO-01", CodeFormat.CODE_128)

        assertEquals(chipVisible, viewModel.uiState.value)
        assertEquals(1, records.addScanCalls)
        assertEquals(1, records.rows.size)
    }

    @Test
    fun singleModeScanAfterDismissShowsNewCode() {
        viewModel.onRawDetection("0012345678905", CodeFormat.EAN_13)
        viewModel.dismissLastScan()

        now = 100L
        viewModel.onRawDetection("SKANO-01", CodeFormat.CODE_128)

        assertEquals(
            ScannerUiState(ScanEvent("SKANO-01", CodeFormat.CODE_128), count = 1L),
            viewModel.uiState.value,
        )
        assertEquals(2, records.addScanCalls)
    }

    @Test
    fun singleModeRescanAfterDismissIncrementsCount() {
        viewModel.onRawDetection("4006381333931", CodeFormat.EAN_13)
        viewModel.dismissLastScan()

        // Same code right after dismiss: the lock is gone and the debouncer
        // was reset, so it persists again and the count grows to ×2.
        now = 100L
        viewModel.onRawDetection("4006381333931", CodeFormat.EAN_13)

        assertEquals(1, records.rows.size)
        assertEquals(
            ScannerUiState(ScanEvent("4006381333931", CodeFormat.EAN_13), count = 2L),
            viewModel.uiState.value,
        )
    }

    @Test
    fun batchModeScanReplacesChipImmediatelyAndPersistsBoth() {
        viewModel.toggleBatchMode()

        viewModel.onRawDetection("0012345678905", CodeFormat.EAN_13)
        now = 100L
        viewModel.onRawDetection("SKANO-01", CodeFormat.CODE_128)

        assertEquals(2, records.addScanCalls)
        assertEquals(2, records.rows.size)
        assertEquals(
            ScannerUiState(
                ScanEvent("SKANO-01", CodeFormat.CODE_128),
                count = 1L,
                batchMode = true,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun toggleBatchModePreservesScanStateAndOnlyFlipsFlag() {
        viewModel.onRawDetection("0012345678905", CodeFormat.EAN_13)
        val withScan = viewModel.uiState.value
        assertEquals(withScan.lastScan?.value, "012345678905")

        viewModel.toggleBatchMode()
        assertEquals(withScan.copy(batchMode = true), viewModel.uiState.value)

        viewModel.toggleBatchMode()
        assertEquals(withScan, viewModel.uiState.value)
    }
}
