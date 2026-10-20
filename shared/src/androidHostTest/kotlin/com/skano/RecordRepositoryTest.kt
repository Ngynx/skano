package com.skano

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordRepositoryTest {

    @Test
    fun addScanInsertsAndReadsBack() = runBlocking<Unit> {
        val container = createTestAppContainer()

        val result = container.records.addScan("4006381333931", "EAN_13", 1_000L)

        assertTrue(result.recordId > 0)
        assertEquals(1L, result.count)
        val record = container.database.scanRecordsQueries.selectRecordById(result.recordId).executeAsOne()
        assertEquals("4006381333931", record.raw_value)
        assertEquals("EAN_13", record.format)
        assertEquals(1_000L, record.created_at)
        assertNull(record.label_id)
        assertNull(record.note)
        assertEquals(1L, record.scan_count)
    }

    @Test
    fun addScanSameValueAndFormatIsIdempotentWithGrowingCount() = runBlocking<Unit> {
        val container = createTestAppContainer()

        // A record is the CODE, not an occurrence: scanning it twice must
        // resolve to one row and the same id (queue quantities depend on it),
        // while the scan_count reflects each sighting.
        val first = container.records.addScan("4006381333931", "EAN_13", 1_000L)
        val second = container.records.addScan("4006381333931", "EAN_13", 2_000L)

        assertEquals(first.recordId, second.recordId)
        assertEquals(1L, first.count)
        assertEquals(2L, second.count)
        val rows = container.database.scanRecordsQueries.searchRecords("").executeAsList()
        assertEquals(1, rows.size)
        assertEquals(2L, rows.single().scan_count)
    }

    @Test
    fun addScanSameValueDifferentFormatReturnsDifferentIdAndIndependentCount() = runBlocking<Unit> {
        val container = createTestAppContainer()

        val ean13 = container.records.addScan("0123456789051", "EAN_13", 1L)
        val upcA = container.records.addScan("0123456789051", "UPC_A", 2L)

        assertNotEquals(ean13.recordId, upcA.recordId)
        // Counts are per (value, format): each code starts and stays at 1 here.
        assertEquals(1L, ean13.count)
        assertEquals(1L, upcA.count)
        assertEquals(2, container.database.scanRecordsQueries.searchRecords("").executeAsList().size)
    }

    @Test
    fun rescanningSameCodeIntoQueueKeepsOneEntryWithQuantity() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val queueId = container.queues.create("Shopping", 1L)

        // Full pipeline: repeated scans collapse to ONE record id, so the
        // queue's UNIQUE(queue_id, record_id) collides and quantity grows.
        val first = container.records.addScan("4006381333931", "EAN_13", 10L)
        val second = container.records.addScan("4006381333931", "EAN_13", 20L)
        assertEquals(first.recordId, second.recordId)

        container.queues.addRecord(queueId, first.recordId, 10L)
        container.queues.addRecord(queueId, second.recordId, 20L)

        val entries = container.queues.observeEntries(queueId).first()
        assertEquals(1, entries.size)
        assertEquals(2L, entries.single().quantity)
    }

    @Test
    fun searchByRawValueSubstring() = runBlocking<Unit> {
        val container = createTestAppContainer()
        container.records.addScan("4006381333931", "EAN_13", 1L)
        container.records.addScan("9780201379624", "UPC_A", 2L)

        val results = container.records.observeSearch("6381").first()

        assertEquals(listOf("4006381333931"), results.map { it.raw_value })
    }

    @Test
    fun searchByLabelName() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val labelId = container.labels.create("Dairy")
        val recordId = container.records.addScan("1234567890123", "EAN_13", 1L).recordId
        container.records.setLabel(recordId, labelId)
        container.records.addScan("9999999999999", "EAN_13", 2L)

        val results = container.records.observeSearch("Dairy").first()

        assertEquals(listOf("1234567890123"), results.map { it.raw_value })
    }

    @Test
    fun searchMatchingNothingReturnsEmpty() = runBlocking<Unit> {
        val container = createTestAppContainer()
        container.records.addScan("4006381333931", "EAN_13", 1L)

        val results = container.records.observeSearch("zzz-no-match").first()

        assertTrue(results.isEmpty())
    }

    @Test
    fun setLabelAndNoteReadBackAndClear() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val labelId = container.labels.create("Snacks")
        val recordId = container.records.addScan("5000112637922", "EAN_13", 1L).recordId

        container.records.setLabel(recordId, labelId)
        container.records.setNote(recordId, "Bought weekly")

        val record = container.database.scanRecordsQueries.selectRecordById(recordId).executeAsOne()
        assertEquals(labelId, record.label_id)
        assertEquals("Bought weekly", record.note)

        container.records.setLabel(recordId, null)
        container.records.setNote(recordId, null)

        val cleared = container.database.scanRecordsQueries.selectRecordById(recordId).executeAsOne()
        assertNull(cleared.label_id)
        assertNull(cleared.note)
    }
}
