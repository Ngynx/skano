package com.skano.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.skano.db.Labels
import com.skano.db.SkanoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for scan labels. Deleting a label detaches it from records
 * (FK ON DELETE SET NULL); records themselves are never deleted here.
 */
interface LabelRepository {
    /** Inserts a label and returns its new row id. */
    fun create(name: String): Long

    /** Renames the label with [id] to [newName]. */
    fun rename(id: Long, newName: String)

    /** Deletes the label; records referencing it keep their data with a null label. */
    fun delete(id: Long)

    /** Observes all labels, ordered by name. */
    fun observeAll(): Flow<List<Labels>>
}

internal class LabelRepositoryImpl(private val database: SkanoDatabase) : LabelRepository {

    override fun create(name: String): Long = database.transactionWithResult {
        database.labelsQueries.insertLabel(name)
        database.labelsQueries.selectLastInsertId().executeAsOne()
    }

    override fun rename(id: Long, newName: String) {
        database.labelsQueries.updateLabel(name = newName, id = id)
    }

    override fun delete(id: Long) {
        database.labelsQueries.deleteLabel(id)
    }

    override fun observeAll(): Flow<List<Labels>> =
        database.labelsQueries.selectAllLabels().asFlow().mapToList(Dispatchers.Default)
}
