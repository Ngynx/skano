package com.skano.platform

import app.cash.sqldelight.db.SqlDriver

/**
 * Creates the platform [SqlDriver] for the Skano database.
 *
 * [context] is a platform-specific context object:
 * - Android: an `android.content.Context`.
 * - iOS: ignored (pass `null`).
 */
expect fun createDriver(context: Any?): SqlDriver
