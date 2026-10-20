package com.skano

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelRepositoryTest {

    @Test
    fun createReturnsIdAndAppearsInList() = runBlocking<Unit> {
        val container = createTestAppContainer()

        val id = container.labels.create("Groceries")

        assertTrue(id > 0)
        val labels = container.labels.observeAll().first()
        assertEquals(listOf("Groceries"), labels.map { it.name })
        assertEquals(id, labels.single().id)
    }

    @Test
    fun renameUpdatesNameAndKeepsId() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val id = container.labels.create("Groceries")

        container.labels.rename(id, "Pantry")

        val labels = container.labels.observeAll().first()
        assertEquals(listOf("Pantry"), labels.map { it.name })
        assertEquals(id, labels.single().id)
    }

    @Test
    fun deleteRemovesLabel() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val id = container.labels.create("Groceries")

        container.labels.delete(id)

        assertTrue(container.labels.observeAll().first().isEmpty())
    }

    @Test
    fun deleteLabelKeepsRecordsAliveWithNullLabel() = runBlocking<Unit> {
        val container = createTestAppContainer()
        val labelId = container.labels.create("Groceries")
        val recordId = container.records.addScan("4006381333931", "EAN_13", 1_000L).recordId
        container.records.setLabel(recordId, labelId)

        container.labels.delete(labelId)

        // SET NULL worked: the record survives with its label detached.
        val record = container.database.scanRecordsQueries.selectRecordById(recordId).executeAsOne()
        assertEquals("4006381333931", record.raw_value)
        assertNull(record.label_id)
    }
}
