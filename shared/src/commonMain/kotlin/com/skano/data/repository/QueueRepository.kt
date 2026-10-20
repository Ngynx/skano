package com.skano.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.skano.db.SelectQueueEntries
import com.skano.db.SelectQueuesWithCounts
import com.skano.db.SkanoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * Scan queues: named buckets of scan records with quantities.
 *
 * Duplicate policy inside a queue: a code appears ONCE per queue with a
 * quantity counter. Adding is UPDATE-then-INSERT inside one transaction
 * (portable to every SQLite shipped with minSdk; upsert would need 3.24+).
 */
interface QueueRepository {
    /** Creates a queue and returns its new row id. */
    fun create(name: String, createdAt: Long): Long

    /** Renames the queue. */
    fun rename(id: Long, newName: String)

    /** Deletes the queue; its entries cascade away, records survive. */
    fun delete(id: Long)

    /** Observes all queues with entry count and total quantity. */
    fun observeAllWithCounts(): Flow<List<SelectQueuesWithCounts>>

    /** Adds a record to a queue, or increments its quantity if already present. */
    fun addRecord(queueId: Long, recordId: Long, addedAt: Long)

    /** Observes a queue's entries joined with record and label data. */
    fun observeEntries(queueId: Long): Flow<List<SelectQueueEntries>>

    /** Removes a single entry from its queue. */
    fun removeEntry(entryId: Long)

    /** Empties a queue without touching the underlying records. */
    fun clear(queueId: Long)
}

internal class QueueRepositoryImpl(private val database: SkanoDatabase) : QueueRepository {

    override fun create(name: String, createdAt: Long): Long = database.transactionWithResult {
        database.scanQueuesQueries.insertQueue(name, createdAt)
        database.scanQueuesQueries.selectLastInsertId().executeAsOne()
    }

    override fun rename(id: Long, newName: String) {
        database.scanQueuesQueries.renameQueue(name = newName, id = id)
    }

    override fun delete(id: Long) {
        database.scanQueuesQueries.deleteQueue(id)
    }

    override fun observeAllWithCounts(): Flow<List<SelectQueuesWithCounts>> =
        database.scanQueuesQueries.selectQueuesWithCounts().asFlow().mapToList(Dispatchers.Default)

    override fun addRecord(queueId: Long, recordId: Long, addedAt: Long) {
        database.transaction {
            val updated = database.queueEntriesQueries
                .incrementEntry(queue_id = queueId, record_id = recordId)
                .value
            if (updated == 0L) {
                database.queueEntriesQueries
                    .insertEntry(queue_id = queueId, record_id = recordId, added_at = addedAt)
            }
        }
    }

    override fun observeEntries(queueId: Long): Flow<List<SelectQueueEntries>> =
        database.queueEntriesQueries.selectQueueEntries(queueId).asFlow().mapToList(Dispatchers.Default)

    override fun removeEntry(entryId: Long) {
        database.queueEntriesQueries.deleteQueueEntry(id = entryId)
    }

    override fun clear(queueId: Long) {
        database.queueEntriesQueries.clearQueue(queue_id = queueId)
    }
}
