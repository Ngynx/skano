package com.skano

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.skano.db.SkanoDatabase
import com.skano.di.AppContainer
import java.util.Properties

/**
 * Builds an [AppContainer] backed by an in-memory SQLite database with
 * foreign key enforcement enabled.
 *
 * The `foreign_keys` connection property makes the JDBC driver issue
 * `PRAGMA foreign_keys = ON`, mirroring the production drivers (Android
 * `onConfigure` + native driver `extendedConfig`). Without it the
 * ON DELETE CASCADE / SET NULL tests would silently fail.
 */
fun createTestAppContainer(): AppContainer {
    val properties = Properties().apply { put("foreign_keys", "true") }
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, properties)
    SkanoDatabase.Schema.create(driver)
    return AppContainer(driver)
}
