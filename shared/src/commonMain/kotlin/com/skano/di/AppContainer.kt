package com.skano.di

import app.cash.sqldelight.db.SqlDriver
import com.skano.data.repository.LabelRepository
import com.skano.data.repository.LabelRepositoryImpl
import com.skano.data.repository.QueueRepository
import com.skano.data.repository.QueueRepositoryImpl
import com.skano.data.repository.RecordRepository
import com.skano.data.repository.RecordRepositoryImpl
import com.skano.db.SkanoDatabase

/**
 * Manual dependency container for the shared module. UI wiring happens in a
 * later phase; this class only exists so repositories are constructible and
 * testable today.
 */
class AppContainer(driver: SqlDriver) {
    val database: SkanoDatabase = SkanoDatabase(driver)
    val labels: LabelRepository = LabelRepositoryImpl(database)
    val records: RecordRepository = RecordRepositoryImpl(database)
    val queues: QueueRepository = QueueRepositoryImpl(database)
}
