package com.skano.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.skano.db.Scan_records
import com.skano.db.SkanoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * Result of [RecordRepository.addScan]: the stable record id for the code,
 * its TOTAL scan count (occurrences, not rows — the row is per code), and
 * the record's current manual note so the batch panel can show an existing
 * reference label without a second query.
 */
data class ScanCountResult(val recordId: Long, val count: Long, val note: String?)

/**
 * CRUD for scanned records. [format] is a plain TEXT column holding the
 * stored format name (e.g. "EAN_13"); the domain layer maps it via
 * [com.skano.domain.model.CodeFormat].
 */
interface RecordRepository {
    /**
     * Records one occurrence of ([rawValue], [format]), creating the record on
     * first sight and incrementing its scan count on every sighting. Returns
     * the stable record id plus the NEW count for that code, so callers can
     * show "scanned N times" without an extra query.
     */
    fun addScan(rawValue: String, format: String, createdAt: Long): ScanCountResult

    /**
     * Observes records whose raw value OR joined label name contains [query].
     * An empty query matches everything.
     */
    fun observeSearch(query: String): Flow<List<Scan_records>>

    /** Assigns ([labelId] non-null) or clears ([labelId] null) the record's label. */
    fun setLabel(recordId: Long, labelId: Long?)

    /** Sets or clears the record's free-text note. */
    fun setNote(recordId: Long, note: String?)
}

internal class RecordRepositoryImpl(private val database: SkanoDatabase) : RecordRepository {

    override fun addScan(rawValue: String, format: String, createdAt: Long): ScanCountResult =
        database.transactionWithResult {
            // Portable get-or-increment: SELECT the (value, format) row; on a
            // hit bump scan_count and return the new total, on a miss INSERT
            // with scan_count = 1 and return that fresh row's id.
            val existing = database.scanRecordsQueries
                .selectRecordIdAndScanCount(rawValue, format)
                .executeAsOneOrNull()
            if (existing != null) {
                // UPDATE/INSERT execute immediately (QueryResult<Long>); we
                // only need the side effect, the new count is count + 1.
                database.scanRecordsQueries.incrementScanCount(existing.id)
                ScanCountResult(existing.id, existing.scan_count + 1L, existing.note)
            } else {
                database.scanRecordsQueries.insertRecordWithScanCount(rawValue, format, createdAt)
                // Do NOT trust the INSERT's QueryResult<Long> as the row id:
                // on the Android driver it is rows-affected (always 1), not
                // last_insert_rowid. Re-selecting is the portable way.
                val created = database.scanRecordsQueries
                    .selectRecordIdAndScanCount(rawValue, format)
                    .executeAsOne()
                ScanCountResult(created.id, 1L, created.note)
            }
        }

    override fun observeSearch(query: String): Flow<List<Scan_records>> =
        database.scanRecordsQueries.searchRecords(query).asFlow().mapToList(Dispatchers.Default)

    override fun setLabel(recordId: Long, labelId: Long?) {
        database.scanRecordsQueries.updateRecordLabel(label_id = labelId, id = recordId)
    }

    override fun setNote(recordId: Long, note: String?) {
        database.scanRecordsQueries.updateRecordNote(note = note, id = recordId)
    }
}
