package com.skano

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueueRepositoryTest {

    @Test
    fun createRenameDeleteQueue() = runBlocking<Unit> {
        val container = createTestAppContainer()

        val id = container.queues.create("Shopping", 1_000L)
        assertTrue(id > 0)

        container.queues.rename(id, "Weekly shop")
        var queues = container.queues.observeAllWithCounts().first()
        assertEquals(listOf("Weekly shop"), queues.map { it.name })
        assertEquals(0L, queues.single().entry_count)
        assertEquals(0L, queues.single().total_quantity)

        container.queues.delete(id)
        queues = container.queues.observeAllWithCounts().first()
        assertTrue(queues.isEmpty())
    }

    @Test
    fun addingSameRecordTwiceYieldsOneEntryWithQuantityTwo() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val queueId = container.queues.create("Shopping", 1_000L)
        val recordId = container.records.addScan("4006381333931", "EAN_13", 1L).recordId

        container.queues.addRecord(queueId, recordId, 10L)
        container.queues.addRecord(queueId, recordId, 20L)

        // Core duplicate rule: one entry per code per queue, quantity counts scans.
        val entries = container.queues.observeEntries(queueId).first()
        assertEquals(1, entries.size)
        assertEquals(2L, entries.single().quantity)

        val queue = container.queues.observeAllWithCounts().first().single()
        assertEquals(1L, queue.entry_count)
        assertEquals(2L, queue.total_quantity)
    }

    @Test
    fun sameRecordInDifferentQueuesIsIndependent() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val firstQueue = container.queues.create("Shopping", 1L)
        val secondQueue = container.queues.create("Warehouse", 2L)
        val recordId = container.records.addScan("4006381333931", "EAN_13", 1L).recordId

        container.queues.addRecord(firstQueue, recordId, 10L)
        container.queues.addRecord(secondQueue, recordId, 20L)

        val firstEntries = container.queues.observeEntries(firstQueue).first()
        val secondEntries = container.queues.observeEntries(secondQueue).first()
        assertEquals(1, firstEntries.size)
        assertEquals(1L, firstEntries.single().quantity)
        assertEquals(1, secondEntries.size)
        assertEquals(1L, secondEntries.single().quantity)
    }

    @Test
    fun entriesExposeJoinedRecordAndLabelData() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val labelId = container.labels.create("Dairy")
        val recordId = container.records.addScan("3017620422003", "EAN_13", 5L).recordId
        container.records.setLabel(recordId, labelId)
        container.records.setNote(recordId, "Milk")
        val queueId = container.queues.create("Shopping", 6L)
        container.queues.addRecord(queueId, recordId, 7L)

        val entry = container.queues.observeEntries(queueId).first().single()

        assertEquals("3017620422003", entry.raw_value)
        assertEquals("EAN_13", entry.format)
        assertEquals("Milk", entry.note)
        assertEquals("Dairy", entry.label_name)
        assertEquals(1L, entry.quantity)
    }

    @Test
    fun removeEntryRemovesOnlyThatEntry() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val queueId = container.queues.create("Shopping", 1L)
        val firstRecord = container.records.addScan("1111111111111", "EAN_13", 1L).recordId
        val secondRecord = container.records.addScan("2222222222222", "EAN_13", 2L).recordId
        container.queues.addRecord(queueId, firstRecord, 10L)
        container.queues.addRecord(queueId, secondRecord, 20L)

        val entries = container.queues.observeEntries(queueId).first()
        val toRemove = entries.first { it.raw_value == "1111111111111" }
        container.queues.removeEntry(toRemove.id)

        val remaining = container.queues.observeEntries(queueId).first()
        assertEquals(listOf("2222222222222"), remaining.map { it.raw_value })
    }

    @Test
    fun clearEmptiesQueueWithoutTouchingRecords() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val queueId = container.queues.create("Shopping", 1L)
        val recordId = container.records.addScan("4006381333931", "EAN_13", 1L).recordId
        container.queues.addRecord(queueId, recordId, 10L)

        container.queues.clear(queueId)

        assertTrue(container.queues.observeEntries(queueId).first().isEmpty())
        val record = container.database.scanRecordsQueries.selectRecordById(recordId).executeAsOne()
        assertEquals("4006381333931", record.raw_value)
    }

    @Test
    fun deletingQueueCascadesEntriesButKeepsRecords() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val queueId = container.queues.create("Shopping", 1L)
        val recordId = container.records.addScan("4006381333931", "EAN_13", 1L).recordId
        container.queues.addRecord(queueId, recordId, 10L)

        container.queues.delete(queueId)

        // CASCADE worked: entries are gone...
        val remainingEntries = container.database.queueEntriesQueries.selectQueueEntries(queueId).executeAsList()
        assertTrue(remainingEntries.isEmpty())
        // ...but the record survives.
        val record = container.database.scanRecordsQueries.selectRecordById(recordId).executeAsOne()
        assertEquals("4006381333931", record.raw_value)
    }
}
