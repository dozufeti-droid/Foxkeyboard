package unicode.sinhala.keyboard.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.Normalizer

internal object FoxSmartRules {
    const val RPM = 15
    const val RPD = 500
    fun pacificDay(now: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("America/Los_Angeles")
    }.format(Date(now))
    fun normalized(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
        .trim().replace(Regex("\\s+"), " ")
    fun isSinhala(cp: Int) = cp in 0x0D80..0x0DFF
    fun isHangul(cp: Int) = cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF ||
        cp in 0x3130..0x318F || cp in 0xA960..0xA97F || cp in 0xD7B0..0xD7FF
    fun isLatin(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
    fun allowedText(s: String, target: String, original: String): Boolean {
        if (s.isBlank()) return false
        var hasTarget = false
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            if (cp in 0x202A..0x202E || cp in 0x2066..0x2069) return false
            val kind = Character.getType(cp)
            val mark = kind == Character.NON_SPACING_MARK.toInt() ||
                kind == Character.COMBINING_SPACING_MARK.toInt() || kind == Character.ENCLOSING_MARK.toInt()
            val letter = Character.isLetter(cp)
            val wanted = when(target) { "si" -> isSinhala(cp); "ko" -> isHangul(cp); else -> isLatin(cp) }
            if (wanted && letter) hasTarget = true
            // Emoji variation selectors and keycap marks are not foreign letters.
            if ((letter || mark) && !wanted && cp !in 0xFE00..0xFE0F && cp !in 0xE0100..0xE01EF && cp != 0x20E3) {
                // Outgoing Korean may preserve Latin brands/names; source-exact only.
                if (target != "ko" || !isLatin(cp)) return false
            }
            i += Character.charCount(cp)
        }
        if (target == "ko") {
            for (m in Regex("[\\p{IsLatin}]+(?:[0-9._'-]*[\\p{IsLatin}]+)*").findAll(s)) {
                if (!original.contains(m.value, ignoreCase = true)) return false
            }
        }
        return hasTarget || !original.any { Character.isLetter(it) }
    }
    fun sourceWord(word: String, source: String): Boolean {
        val w = normalized(word)
        if (w.isBlank() || w.length > 160) return false
        if (!normalized(source).contains(w, ignoreCase = true)) return false
        return allowedText(w, "ko", source) || allowedText(w, "en", source)
    }
}
