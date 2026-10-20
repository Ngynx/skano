package com.skano.platform

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.skano.db.SkanoDatabase

actual fun createDriver(context: Any?): SqlDriver {
    val androidContext = context as? Context
        ?: error("createDriver on Android requires an android.content.Context")
    // SQLite does NOT enforce foreign keys unless PRAGMA foreign_keys=ON is set
    // per connection. Without it, ON DELETE CASCADE / SET NULL silently do nothing.
    val callback = object : AndroidSqliteDriver.Callback(SkanoDatabase.Schema) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }
    }
    return AndroidSqliteDriver(SkanoDatabase.Schema, androidContext, "skano.db", callback = callback)
}
