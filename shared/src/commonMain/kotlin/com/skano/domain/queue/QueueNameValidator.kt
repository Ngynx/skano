package com.skano.domain.queue

/**
 * Validation for queue/label names and free-text notes.
 *
 * Returns STABLE lowercase error codes for the UI layer to map to localized
 * copy — never user-facing sentences:
 * - `"blank"`    : name/label trims to empty
 * - `"too_long"` : name/label trimmed length > 60, or note length > 1000
 *
 * `null` means valid. Names/labels are checked on their TRIMMED form
 * (non-empty after trim, max [MAX_NAME_LENGTH] trimmed chars). Notes are
 * optional, so a blank note is valid; only [MAX_NOTE_LENGTH] on the raw
 * note is enforced.
 */
object QueueNameValidator {

    const val MAX_NAME_LENGTH = 60
    const val MAX_NOTE_LENGTH = 1_000

    /** Returns null when valid, else `"blank"` or `"too_long"`. */
    fun validateQueueName(name: String): String? = validateName(name)

    /** Same rules as [validateQueueName]; label names share the contract. */
    fun validateLabelName(name: String): String? = validateName(name)

    /**
     * Returns null when valid, else `"too_long"`.
     * Blank notes are valid (notes are optional).
     */
    fun validateNote(note: String): String? =
        if (note.length > MAX_NOTE_LENGTH) "too_long" else null

    private fun validateName(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "blank"
            trimmed.length > MAX_NAME_LENGTH -> "too_long"
            else -> null
        }
    }
}
