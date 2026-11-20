package unicode.sinhala.keyboard.ai

/**
 * Fail closed on corrupted Sinhala translations. Gemini/Groq may sometimes
 * return Singlish, English, Hangul or entirely unrelated writing systems even
 * when asked for Sinhala. This check is local and costs no network latency.
 *
 * This is a SCRIPT check, not a guarantee of semantic correctness.
 */
internal object SinhalaOutputGuard {
    fun isValid(translation: String, original: String): Boolean {
        if (translation.isBlank()) return false
        var containsSinhalaLetter = false
        var index = 0
        while (index < translation.length) {
            val codePoint = Character.codePointAt(translation, index)
            if (Character.isLetter(codePoint)) {
                // Includes the entire Sinhala Unicode block. Reject every
                // other script, including Latin (romanized Sinhala).
                if (codePoint !in 0x0D80..0x0DFF) return false
                containsSinhalaLetter = true
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
