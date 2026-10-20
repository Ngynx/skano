package com.skano.domain

import com.skano.domain.model.CodeFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeFormatTest {

    @Test
    fun storedNameRoundTripsForEveryFormat() {
        CodeFormat.entries.forEach { format ->
            assertEquals(format, CodeFormat.fromStored(format.storedName))
        }
    }

    @Test
    fun storedNameIsTheEnumName() {
        assertEquals("EAN_13", CodeFormat.EAN_13.storedName)
        assertEquals("UPC_A", CodeFormat.UPC_A.storedName)
        assertEquals("CODE_128", CodeFormat.CODE_128.storedName)
    }

    @Test
    fun unknownStringMapsToUnknown() {
        assertEquals(CodeFormat.UNKNOWN, CodeFormat.fromStored("ITF_14"))
        assertEquals(CodeFormat.UNKNOWN, CodeFormat.fromStored("ean_13"))
    }

    @Test
    fun blankStringMapsToUnknown() {
        assertEquals(CodeFormat.UNKNOWN, CodeFormat.fromStored(""))
        assertEquals(CodeFormat.UNKNOWN, CodeFormat.fromStored("   "))
    }
}
