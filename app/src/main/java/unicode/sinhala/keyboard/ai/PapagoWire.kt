package unicode.sinhala.keyboard.ai

import java.net.URLEncoder

/** Wire contract from the November 26, 2026 manual translation test. */
internal object PapagoWire {
    const val MAX_CHARS = 2000

    fun hasHangul(text: String): Boolean = text.any {
        it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' ||
            it in '\uA960'..'\uA97F' || it in '\uAC00'..'\uD7FF' ||
            it in '\uFFA0'..'\uFFDC'
    }

    // A conservative script check, not automatic language detection.
    fun isEnglishCandidate(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_CHARS &&
            text.any { it in 'a'..'z' || it in 'A'..'Z' } &&
            text.none { it in '\u0D80'..'\u0DFF' } && !hasHangul(text)

    fun form(text: String): String {
        require(isEnglishCandidate(text)) { "Translate to English first" }
        return listOf(
            "source" to "en", "target" to "ko", "text" to text,
            "honorific" to "false", "dict" to "false", "useGlossary" to "false"
        ).joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
    }

    fun validKorean(result: String): Boolean =
        result.isNotBlank() && result.length <= MAX_CHARS * 4 && hasHangul(result) &&
            result.none { it in '\u0D80'..'\u0DFF' || it == '\u0000' }
}
