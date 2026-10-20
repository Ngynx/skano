package com.skano.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.skano.db.SkanoDatabase

// SQLite does NOT enforce foreign keys unless PRAGMA foreign_keys=ON is set
// per connection. The native driver enables it through the extended config,
// which applies the pragma on every connection it opens.
actual fun createDriver(context: Any?): SqlDriver =
    NativeSqliteDriver(
        schema = SkanoDatabase.Schema,
        name = "skano.db",
        onConfiguration = { config: DatabaseConfiguration ->
            config.copy(
                extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true)
            )
        },
    )
