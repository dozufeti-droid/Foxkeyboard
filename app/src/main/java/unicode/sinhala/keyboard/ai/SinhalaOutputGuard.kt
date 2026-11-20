package unicode.sinhala.keyboard.ai

/**
 * Fail closed on corrupted Sinhala translations. Gemini/Groq may sometimes
 * return Singlish, English, Hangul or entirely unrelated writing systems even
 * when asked for Sinhala. This check is local and costs no network latency.
 *
 * This is a SCRIPT check, not a guarantee of semantic correctness.
 */
/** Distinguish rejected output from missing API keys, models or offline assets. */
internal class SinhalaScriptException : IllegalStateException(
    "Sinhala output had mixed writing systems after one correction attempt"
)

internal object SinhalaOutputGuard {
    fun isValid(translation: String, original: String): Boolean {
        if (translation.isBlank()) return false
        var containsSinhalaLetter = false
        var index = 0
        while (index < translation.length) {
            val codePoint = Character.codePointAt(translation, index)
            val characterType = Character.getType(codePoint)
            val isCombiningMark = characterType == Character.NON_SPACING_MARK.toInt() ||
                characterType == Character.COMBINING_SPACING_MARK.toInt() ||
                characterType == Character.ENCLOSING_MARK.toInt()
            if (Character.isLetter(codePoint) || isCombiningMark) {
                // Reject foreign letters AND diacritics; Sinhala vowel signs
                // and virama remain valid because they belong to this block.
                if (codePoint !in 0x0D80..0x0DFF) return false
                if (Character.isLetter(codePoint)) containsSinhalaLetter = true
            }
            // Reject right-to-left direction overrides/isolate markers.
            if (codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069) {
                return false
            }
            index += Character.charCount(codePoint)
        }
        // Numbers/emoji-only input may legitimately have no Sinhala letters.
        val originalHasLetters = original.any { Character.isLetter(it) }
        return !originalHasLetters || containsSinhalaLetter
    }
}
