package unicode.sinhala.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Public Helakuru FACTS phonetic spellings, checked through actual per-key composition. */
class FoxMahapranaTypingTest {
    @Test fun aspiratedConsonantsAndVowelSigns() {
        val bases = listOf("kh" to "ඛ", "gh" to "ඝ", "chh" to "ඡ", "T" to "ඨ",
            "D" to "ඪ", "thh" to "ථ", "dhh" to "ධ", "ph" to "ඵ", "bh" to "භ")
        val vowels = listOf("a" to "", "aa" to "ා", "i" to "ි", "ii" to "ී", "u" to "ු", "uu" to "ූ", "e" to "ෙ", "ee" to "ේ", "o" to "ො", "oo" to "ෝ", "A" to "ැ", "Aa" to "ෑ")
        for ((keys, base) in bases) for ((v, sign) in vowels) {
            val composer = FoxPhoneticComposer()
            var rendered = ""
            for (k in keys + v) {
                val edit = composer.type(k.toString(), rendered)!!
                rendered = rendered.dropLast(edit.removeCodePoints) + edit.insert
            }
            assertEquals(keys + v, base + sign, rendered)
            assertEquals("delete " + keys + v, (base + sign).length, FoxPhoneticComposer.visibleUnitToDelete(rendered))
        }
    }
    @Test fun normalAndAspiratedDoNotConflict() {
        for ((keys, expected) in listOf("tha" to "ත", "thha" to "ථ", "dha" to "ද", "dhha" to "ධ", "ta" to "ට", "Ta" to "ඨ", "da" to "ඩ", "Da" to "ඪ", "cha" to "ච", "chha" to "ඡ", "baya" to "බය", "bhaaSaa" to "භාෂා", "dhhanaya" to "ධනය")) {
            assertEquals(keys, expected, FoxPhoneticComposer.convert(keys))
        }
    }
    @Test fun requestedCapitalFirstSpellingsAndBothKaKeys() {
        val bases = listOf("Kh" to "ඛ", "Gh" to "ඝ", "Ch" to "ඡ", "T" to "ඨ", "D" to "ඪ",
            "Th" to "ථ", "Dh" to "ධ", "Ph" to "ඵ", "Bh" to "භ", "ch" to "ච", "c" to "ක", "k" to "ක")
        val vowels = listOf("" to "්", "a" to "", "aa" to "ා", "i" to "ි", "ii" to "ී", "u" to "ු", "uu" to "ූ", "e" to "ෙ", "ee" to "ේ", "o" to "ො", "oo" to "ෝ", "A" to "ැ", "Aa" to "ෑ")
        for ((keys, base) in bases) for ((vowel, sign) in vowels) {
            val composer = FoxPhoneticComposer()
            var rendered = ""
            for (key in keys + vowel) {
                val edit = composer.type(key.toString(), rendered)!!
                rendered = rendered.dropLast(edit.removeCodePoints) + edit.insert
            }
            assertEquals(keys + vowel, base + sign, rendered)
            assertEquals("delete " + keys + vowel, (base + sign).length, FoxPhoneticComposer.visibleUnitToDelete(rendered))
        }
        assertEquals("ථා", FoxPhoneticComposer.convert("Thaa"))
        assertEquals("ධනය", FoxPhoneticComposer.convert("Dhanaya"))
        assertEquals("භාෂා", FoxPhoneticComposer.convert("BhaaSaa"))
        assertEquals("ක", FoxPhoneticComposer.keyHint("c"))
    }
    @Test fun visualHintsMatchRealKeys() {
        assertEquals("ඨ", FoxPhoneticComposer.keyHint("T"))
        assertEquals("ඪ", FoxPhoneticComposer.keyHint("D"))
        assertEquals("ක", FoxPhoneticComposer.keyHint("k"))
        assertEquals("ට", FoxPhoneticComposer.keyHint("t"))
        assertEquals("ඇ", FoxPhoneticComposer.keyHint("A"))
        assertEquals("", FoxPhoneticComposer.keyHint("z"))
        for (key in 'a'..'z') {
            val hint = FoxPhoneticComposer.keyHint(key.toString())
            org.junit.Assert.assertTrue(hint.length <= 1 && !hint.contains('/'))
        }
    }
}
