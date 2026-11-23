package unicode.sinhala.keyboard

/**
 * Pure, offline Romanized-Sinhala composition for the SINGLISH key face.
 *
 * The conversion rules are independently implemented from the publicly documented
 * Helakuru FACTS phonetic examples (https://facts.helakuru.lk/sinhala-typing/phonetic).
 * No network, dictionary, transliteration provider, or translation API is involved.
 * The editor-facing IME owns all InputConnection operations.
 */
internal class FoxPhoneticComposer {
    internal data class Edit(val removeCodePoints: Int, val insert: String)

    private val roman = StringBuilder()
    private var shown: String = ""

    fun reset() {
        roman.setLength(0)
        shown = ""
    }

    /** Previous output must still be immediately before the cursor. */
    fun type(key: String, beforeCursor: String): Edit? {
        if (key.length != 1 || !key[0].isAsciiLetter()) {
            reset()
            return null
        }
        if (!beforeCursor.endsWith(shown)) reset()
        // A hard bound prevents a long uninterrupted word from slowing down typing.
        if (roman.length >= 96) reset()
        val prior = shown
        roman.append(key)
        val next = convert(roman.toString())
        shown = next
        return delta(prior, next)
    }

    /** Remove one phonetic key instead of tearing apart Sinhala combining marks. */
    fun backspace(beforeCursor: String): Edit? {
        if (roman.isEmpty() || !beforeCursor.endsWith(shown)) {
            reset()
            return null
        }
        val prior = shown
        roman.deleteCharAt(roman.lastIndex)
        val next = convert(roman.toString())
        shown = next
        return delta(prior, next)
    }

    private fun delta(previous: String, next: String): Edit {
        var unchanged = 0
        while (unchanged < previous.length && unchanged < next.length &&
            previous[unchanged] == next[unchanged]) unchanged++
        // The renderer emits Sinhala BMP characters and ZWJ, so no surrogate split here.
        return Edit(previous.substring(unchanged).codePointCount(0, previous.length - unchanged),
            next.substring(unchanged))
    }

    companion object {
        /** Number of code points in the last visible Sinhala unit, not roman keystrokes. */
        internal fun visibleUnitToDelete(before: String): Int {
            if (before.isEmpty()) return 0
            val cps = before.map { it.toInt() }
            if (cps.isEmpty()) return 0
            var i = cps.size - 1
            fun sinhala(cp: Int) = cp in 0x0D80..0x0DFF
            fun sign(cp: Int) = cp in 0x0DCA..0x0DDF || cp in 0x0DF2..0x0DF4 || cp == 0x0D82 || cp == 0x0D83
            if (!sinhala(cps[i])) return 0
            // Consume final vowel signs, anusvara, visarga and virama.
            while (i >= 0 && sign(cps[i])) i--
            if (i < 0) return cps.size
            // Consume the base Sinhala character.
            if (!sinhala(cps[i])) return 0
            i--
            // Include consonant + virama + ZWJ + consonant conjuncts (ක්‍ර / ක්‍ය).
            while (i >= 1 && cps[i] == 0x200D && cps[i - 1] == 0x0DCA) {
                i -= 2
                if (i >= 0 && sinhala(cps[i])) i-- else break
            }
            return cps.size - (i + 1)
        }
        private data class Vowel(val roman: String, val independent: String, val sign: String)
        private data class Consonant(val roman: String, val sinhala: String)

        private val vowels = listOf(
            Vowel("aa", "ආ", "ා"), Vowel("Aa", "ඈ", "ෑ"), Vowel("AA", "ඈ", "ෑ"),
            Vowel("ii", "ඊ", "ී"), Vowel("uu", "ඌ", "ූ"),
            Vowel("Ru", "ඎ", "ෲ"), Vowel("ee", "ඒ", "ේ"),
            Vowel("I", "ඊ", "ී"), Vowel("U", "ඌ", "ූ"),
            Vowel("E", "ඒ", "ේ"), Vowel("O", "ඕ", "ෝ"),
            Vowel("ai", "ඓ", "ෛ"), Vowel("oo", "ඕ", "ෝ"),
            Vowel("au", "ඖ", "ෞ"), Vowel("ou", "ඖ", "ෞ"),
            Vowel("a", "අ", ""), Vowel("A", "ඇ", "ැ"),
            Vowel("i", "ඉ", "ි"), Vowel("u", "උ", "ු"),
            Vowel("R", "ඍ", "ෘ"), Vowel("e", "එ", "ෙ"), Vowel("o", "ඔ", "ො")
        ).sortedByDescending { it.roman.length }

        private val consonants = listOf(
            Consonant("chh", "ඡ"), Consonant("thh", "ථ"), Consonant("dhh", "ධ"),
            Consonant("zdh", "ඳ"),
            Consonant("ch", "ච"), Consonant("kh", "ඛ"), Consonant("gh", "ඝ"),
            Consonant("jh", "ඣ"), Consonant("th", "ත"), Consonant("dh", "ද"),
            Consonant("qh", "ධ"), Consonant("ph", "ඵ"), Consonant("bh", "භ"),
            Consonant("sh", "ශ"), Consonant("Sh", "ෂ"),
            Consonant("zg", "ඟ"), Consonant("zj", "ඦ"),
            Consonant("zd", "ඬ"), Consonant("zq", "ඳ"),
            Consonant("zk", "ඤ"), Consonant("zh", "ඥ"),
            Consonant("k", "ක"), Consonant("K", "ඛ"),
            Consonant("g", "ග"), Consonant("G", "ඝ"),
            Consonant("c", "ච"), Consonant("C", "ඡ"),
            Consonant("j", "ජ"), Consonant("J", "ඣ"),
            Consonant("t", "ට"), Consonant("T", "ඨ"),
            Consonant("d", "ඩ"), Consonant("D", "ඪ"),
            Consonant("q", "ද"), Consonant("Q", "ධ"),
            Consonant("n", "න"), Consonant("N", "ණ"),
            Consonant("p", "ප"), Consonant("P", "ඵ"),
            Consonant("b", "බ"), Consonant("m", "ම"), Consonant("M", "ම"),
            Consonant("B", "ඹ"), Consonant("y", "ය"), Consonant("Y", "ය"),
            Consonant("r", "ර"),
            Consonant("l", "ල"), Consonant("L", "ළ"),
            Consonant("w", "ව"), Consonant("W", "ව"),
            Consonant("v", "ව"), Consonant("V", "ව"),
            Consonant("s", "ස"), Consonant("S", "ෂ"),
            Consonant("h", "හ"), Consonant("f", "ෆ"), Consonant("F", "ෆ")
        ).sortedByDescending { it.roman.length }

        private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
        private fun matchVowel(s: String, at: Int): Vowel? =
            vowels.firstOrNull { s.startsWith(it.roman, at) }
        private fun matchConsonant(s: String, at: Int): Consonant? =
            consonants.firstOrNull { s.startsWith(it.roman, at) }

        /** Stateless O(length * constant alphabet) rendering. */
        internal fun convert(s: String): String {
            val result = StringBuilder(s.length * 2)
            var i = 0
            while (i < s.length) {
                val c = matchConsonant(s, i)
                if (c == null) {
                    val v = matchVowel(s, i)
                    if (v != null) {
                        result.append(v.independent)
                        i += v.roman.length
                    } else if (s.startsWith("zn", i) && result.isNotEmpty()) {
                        result.append('ං')
                        i += 2
                    } else {
                        when (s[i]) {
                            'x' -> result.append('ං')
                            'H' -> result.append('ඃ')
                            'X' -> result.append('ඞ') // Published kaX -> කඞ
                            else -> result.append(s[i]) // Never silently drop unknown input.
                        }
                        i++
                    }
                    continue
                }

                i += c.roman.length
                // r + u/uu is the vocalic-R sign, not rakaransaya.
                if (c.roman != "r" && s.startsWith("r", i)) {
                    val uv = when {
                        s.startsWith("uu", i + 1) -> 2
                        s.startsWith("u", i + 1) -> 1
                        else -> 0
                    }
                    if (uv > 0) {
                        result.append(c.sinhala).append(if (uv == 2) "ෲ" else "ෘ")
                        i += 1 + uv
                        continue
                    }
                }

                // Explicit conjuncts: consonant + ra/ya as a single grapheme.
                if (c.roman != "r" && c.roman != "y" && i < s.length &&
                    (s[i] == 'r' || s[i] == 'y')) {
                    val medial = s[i]
                    i++
                    val v = matchVowel(s, i)
                    result.append(c.sinhala).append("්\u200D").append(if (medial == 'r') 'ර' else 'ය')
                    if (v == null) result.append('්') else {
                        result.append(v.sign)
                        i += v.roman.length
                    }
                    continue
                }

                val v = matchVowel(s, i)
                result.append(c.sinhala)
                if (v == null) result.append('්') else {
                    result.append(v.sign)
                    i += v.roman.length
                }
            }
            return result.toString()
        }
    }
}
