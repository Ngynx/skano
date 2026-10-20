package com.skano.domain.export

import com.skano.domain.model.QueueEntryItem

/**
 * Text exports for a queue's entries.
 *
 * Empty input yields an empty string for BOTH builders — there is no
 * header-only CSV, so an empty export is simply nothing.
 */
object QueueTextExporter {

    /**
     * RFC 4180 CSV with header `value,format,quantity,label,note`, one row
     * per item, CRLF line endings, final record terminated by CRLF.
     *
     * A field is quoted only when it contains `,`, `"`, LF, or CR; `"` is
     * escaped as `""`. Empty label/note fields are emitted empty and unquoted.
     */
    fun buildCsv(items: List<QueueEntryItem>): String {
        if (items.isEmpty()) return ""
        val rows = mutableListOf(HEADER)
        items.forEach { item ->
            rows += listOf(
                escape(item.value),
                escape(item.format.storedName),
                escape(item.quantity.toString()),
                escape(item.label.orEmpty()),
                escape(item.note.orEmpty()),
            ).joinToString(",")
        }
        return rows.joinToString(CRLF) + CRLF
    }

    /**
     * Clipboard-friendly text: one line per item, exactly
     * `value,format,quantity` (no header), LF line endings. Symmetric with
     * [buildCsv], the final line ends with LF.
     */
    fun buildClipboardText(items: List<QueueEntryItem>): String {
        if (items.isEmpty()) return ""
        return items.joinToString(LF) { item ->
            "${item.value},${item.format.storedName},${item.quantity}"
        } + LF
    }

    private const val HEADER = "value,format,quantity,label,note"
    private const val CRLF = "\r\n"
    private const val LF = "\n"

    /** RFC 4180: quote when the field contains `,`, `"`, or a newline; double the quotes. */
    private fun escape(field: String): String {
        val needsQuotes = field.contains(',') || field.contains('"') ||
            field.contains('\n') || field.contains('\r')
        if (!needsQuotes) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}
