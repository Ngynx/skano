package com.skano.domain

import com.skano.domain.export.QueueTextExporter
import com.skano.domain.model.CodeFormat
import com.skano.domain.model.QueueEntryItem
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueTextExporterTest {

    private fun item(
        value: String = "4006381333931",
        format: CodeFormat = CodeFormat.EAN_13,
        quantity: Int = 1,
        label: String? = null,
        note: String? = null,
    ) = QueueEntryItem(value, format, quantity, label, note)

    @Test
    fun csvContainsHeaderAndOneRowPerItem() {
        val csv = QueueTextExporter.buildCsv(
            listOf(
                item(value = "4006381333931", quantity = 2, label = "Dairy", note = "Milk"),
                item(value = "9780201379624", format = CodeFormat.UPC_A),
            ),
        )

        assertEquals(
            "value,format,quantity,label,note\r\n" +
                "4006381333931,EAN_13,2,Dairy,Milk\r\n" +
                "9780201379624,UPC_A,1,,\r\n",
            csv,
        )
    }

    @Test
    fun csvQuotesFieldContainingComma() {
        val csv = QueueTextExporter.buildCsv(listOf(item(label = "Dairy, chilled")))

        assertEquals(
            "value,format,quantity,label,note\r\n" +
                "4006381333931,EAN_13,1,\"Dairy, chilled\",\r\n",
            csv,
        )
    }

    @Test
    fun csvDoublesEmbeddedQuotes() {
        val csv = QueueTextExporter.buildCsv(listOf(item(note = "say \"hi\"")))

        assertEquals(
            "value,format,quantity,label,note\r\n" +
                "4006381333931,EAN_13,1,,\"say \"\"hi\"\"\"\r\n",
            csv,
        )
    }

    @Test
    fun csvQuotesFieldContainingNewline() {
        val csv = QueueTextExporter.buildCsv(listOf(item(note = "line1\nline2")))

        assertEquals(
            "value,format,quantity,label,note\r\n" +
                "4006381333931,EAN_13,1,,\"line1\nline2\"\r\n",
            csv,
        )
    }

    @Test
    fun csvQuotesFieldContainingCarriageReturn() {
        val csv = QueueTextExporter.buildCsv(listOf(item(note = "line1\r\nline2")))

        assertEquals(
            "value,format,quantity,label,note\r\n" +
                "4006381333931,EAN_13,1,,\"line1\r\nline2\"\r\n",
            csv,
        )
    }

    @Test
    fun csvEmitsEmptyUnquotedFieldsForNullLabelAndNote() {
        val csv = QueueTextExporter.buildCsv(listOf(item(label = null, note = null)))

        assertEquals(
            "value,format,quantity,label,note\r\n" +
                "4006381333931,EAN_13,1,,\r\n",
            csv,
        )
    }

    @Test
    fun clipboardTextIsValueFormatQuantityLinesWithLf() {
        val text = QueueTextExporter.buildClipboardText(
            listOf(
                item(value = "4006381333931", quantity = 2),
                item(value = "9780201379624", format = CodeFormat.UPC_A, quantity = 1),
            ),
        )

        assertEquals(
            "4006381333931,EAN_13,2\n" +
                "9780201379624,UPC_A,1\n",
            text,
        )
    }

    @Test
    fun emptyListYieldsEmptyStringForBothBuilders() {
        // Empty input is an empty export for BOTH formats: no header-only CSV.
        assertEquals("", QueueTextExporter.buildCsv(emptyList()))
        assertEquals("", QueueTextExporter.buildClipboardText(emptyList()))
    }
}
