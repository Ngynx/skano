package com.skano.domain

import com.skano.domain.queue.QueueNameValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueNameValidatorTest {

    @Test
    fun emptyNameIsBlank() {
        assertEquals("blank", QueueNameValidator.validateQueueName(""))
    }

    @Test
    fun whitespaceOnlyNameIsBlank() {
        assertEquals("blank", QueueNameValidator.validateQueueName("   "))
        assertEquals("blank", QueueNameValidator.validateLabelName("\t\n"))
    }

    @Test
    fun trimmedNameIsValid() {
        assertNull(QueueNameValidator.validateQueueName("  Shopping  "))
    }

    @Test
    fun nameOfExactlySixtyCharsIsValid() {
        assertNull(QueueNameValidator.validateQueueName("a".repeat(60)))
    }

    @Test
    fun nameOfSixtyOneCharsIsTooLong() {
        assertEquals("too_long", QueueNameValidator.validateQueueName("a".repeat(61)))
    }

    @Test
    fun nameLengthIsCheckedOnTheTrimmedForm() {
        // 60 meaningful chars plus trailing spaces: raw length > 60 but valid.
        assertNull(QueueNameValidator.validateQueueName("a".repeat(60) + "   "))
        // 61 meaningful chars even after trimming: invalid.
        assertEquals("too_long", QueueNameValidator.validateQueueName("a".repeat(61) + "   "))
    }

    @Test
    fun labelNameFollowsQueueNameRules() {
        assertNull(QueueNameValidator.validateLabelName("Dairy"))
        assertNull(QueueNameValidator.validateLabelName("a".repeat(60)))
        assertEquals("blank", QueueNameValidator.validateLabelName(""))
        assertEquals("too_long", QueueNameValidator.validateLabelName("a".repeat(61)))
    }

    @Test
    fun blankNoteIsValid() {
        assertNull(QueueNameValidator.validateNote(""))
        assertNull(QueueNameValidator.validateNote("   "))
    }

    @Test
    fun noteOfExactlyThousandCharsIsValid() {
        assertNull(QueueNameValidator.validateNote("n".repeat(1_000)))
    }

    @Test
    fun noteOfThousandAndOneCharsIsTooLong() {
        assertEquals("too_long", QueueNameValidator.validateNote("n".repeat(1_001)))
    }
}
