package com.skano.domain.model

/**
 * Barcode symbologies Skano understands.
 *
 * The persisted contract stays Phase 1's TEXT column: the DB stores
 * [storedName], which is the enum [name]. [UNKNOWN] covers symbologies the
 * scanner reports but Skano does not model yet.
 */
enum class CodeFormat {
    EAN_13,
    EAN_8,
    UPC_A,
    UPC_E,
    CODE_128,
    UNKNOWN;

    /** The exact string persisted in the `scan_records.format` column. */
    val storedName: String
        get() = name

    companion object {
        /**
         * Maps a stored TEXT name back to a [CodeFormat].
         * Any unknown, blank, or non-matching string yields [UNKNOWN];
         * this never throws.
         */
        fun fromStored(raw: String): CodeFormat =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}
