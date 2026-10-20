package com.skano.domain.model

/**
 * One parsed row of CSV/clipboard input, ready to become a scan record
 * plus a queue entry. [label] and [note] are optional; [quantity] defaults
 * to 1 at the parsing layer.
 */
data class QueueEntryItem(
    val value: String,
    val format: CodeFormat,
    val quantity: Int,
    val label: String?,
    val note: String?,
)
