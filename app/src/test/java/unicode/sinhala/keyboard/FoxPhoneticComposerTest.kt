package unicode.sinhala.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed regression cases transcribed from the Helakuru FACTS phonetic guide (2022). */
class FoxPhoneticComposerTest {
    private val official = listOf(
        "a" to "අ",
        "aa" to "ආ",
        "A" to "ඇ",
        "Aa" to "ඈ",
        "AA" to "ඈ",
        "i" to "ඉ",
        "ii" to "ඊ",
        "u" to "උ",
        "uu" to "ඌ",
        "R" to "ඍ",
        "Ru" to "ඎ",
        "e" to "එ",
        "ee" to "ඒ",
        "ai" to "ඓ",
        "o" to "ඔ",
        "oo" to "ඕ",
        "au" to "ඖ",
        "ou" to "ඖ",
        "ka" to "ක",
        "ga" to "ග",
        "cha" to "ච",
        "ja" to "ජ",
        "ta" to "ට",
        "da" to "ඩ",
        "tha" to "ත",
        "dha" to "ද",
        "qa" to "ද",
        "na" to "න",
        "Na" to "ණ",
        "pa" to "ප",
        "ba" to "බ",
        "ma" to "ම",
        "ya" to "ය",
        "ra" to "ර",
        "la" to "ල",
        "La" to "ළ",
        "wa" to "ව",
        "va" to "ව",
        "sa" to "ස",
        "sha" to "ශ",
        "Sa" to "ෂ",
        "Sha" to "ෂ",
        "ha" to "හ",
        "fa" to "ෆ",
        "kha" to "ඛ",
        "gha" to "ඝ",
        "chha" to "ඡ",
        "Ta" to "ඨ",
        "Da" to "ඪ",
        "thha" to "ථ",
        "dhha" to "ධ",
        "pha" to "ඵ",
        "bha" to "භ",
        "zga" to "ඟ",
        "zja" to "ඦ",
        "zda" to "ඬ",
        "zdha" to "ඳ",
        "zqa" to "ඳ",
        "zka" to "ඤ",
        "zha" to "ඥ",
        "Ba" to "ඹ",
        "Lu" to "ළු",
        "k" to "ක්",
        "ka" to "ක",
        "kaa" to "කා",
        "kA" to "කැ",
        "kAa" to "කෑ",
        "kAA" to "කෑ",
        "ki" to "කි",
        "kii" to "කී",
        "ku" to "කු",
        "kuu" to "කූ",
        "kru" to "කෘ",
        "kruu" to "කෲ",
        "ke" to "කෙ",
        "kee" to "කේ",
        "kai" to "කෛ",
        "ko" to "කො",
        "koo" to "කෝ",
        "kau" to "කෞ",
        "kaH" to "කඃ",
        "kax" to "කං",
        "kazn" to "කං",
        "kaX" to "කඞ",
        "kya" to "ක්‍ය",
        "kra" to "ක්‍ර",
    )

    @Test fun directDeletionRemovesVisibleSinhalaUnitWithoutRevertingToEarlierSpelling() {
        val cases = listOf(
            "කා" to 2, "කෑ" to 2, "ක්" to 2, "ක්‍රා" to 5,
            "ක්‍යා" to 5, "අම්මා" to 2, "hello " to 0, "" to 0
        )
        for ((word, expected) in cases) {
            assertEquals("Delete $word", expected, FoxPhoneticComposer.visibleUnitToDelete(word))
        }
    }

    @Test fun allPublishedExamplesRenderCorrectly() {
        assertEquals(86, official.size)
        for ((keys, expected) in official) {
            assertEquals("Official mapping: $keys", expected, FoxPhoneticComposer.convert(keys))
        }
    }

    @Test fun eachPublishedExampleWorksKeyByKeyAndBackspacesCleanly() {
        for ((keys, expected) in official) {
            val composer = FoxPhoneticComposer()
            var text = ""
            for (key in keys) {
                val edit = composer.type(key.toString(), text)!!
                text = text.dropLast(edit.removeCodePoints) + edit.insert
            }
            assertEquals("Incremental $keys", expected, text)
            for (key in keys.reversed()) {
                val edit = composer.backspace(text)!!
                text = text.dropLast(edit.removeCodePoints) + edit.insert
            }
            assertEquals("Backspace $keys", "", text)
        }
    }

    @Test fun expandedWordsAndConjuncts() {
        val words = listOf(
            "ammaa" to "අම්මා",
            "mama" to "මම",
            "aayuboowan" to "ආයුබෝවන්",
            "pansala" to "පන්සල",
            "aadharaya" to "ආදරය",
            "thaaththaa" to "තාත්තා",
            "vyaapaaraya" to "ව්‍යාපාරය",
            "krii" to "ක්‍රී",
            "kyaa" to "ක්‍යා",
            "kraa" to "ක්‍රා",
            "kru" to "කෘ",
            "kruu" to "කෲ",
            "zga" to "ඟ",
            "zqa" to "ඳ",
            "kAa" to "කෑ",
            "kaH" to "කඃ",
            "kazn" to "කං",
        )
        for ((keys, expected) in words) {
            assertEquals(keys, expected, FoxPhoneticComposer.convert(keys))
        }
    }

    @Test fun consonantVowelGridForCommonSyllables() {
        val bases = listOf(
            "k" to "ක",
            "kh" to "ඛ",
            "g" to "ග",
            "gh" to "ඝ",
            "ch" to "ච",
            "chh" to "ඡ",
            "j" to "ජ",
            "jh" to "ඣ",
            "t" to "ට",
            "T" to "ඨ",
            "d" to "ඩ",
            "D" to "ඪ",
            "th" to "ත",
            "thh" to "ථ",
            "dh" to "ද",
            "dhh" to "ධ",
            "q" to "ද",
            "n" to "න",
            "N" to "ණ",
            "p" to "ප",
            "ph" to "ඵ",
            "b" to "බ",
            "bh" to "භ",
            "m" to "ම",
            "B" to "ඹ",
            "y" to "ය",
            "r" to "ර",
            "l" to "ල",
            "L" to "ළ",
            "w" to "ව",
            "v" to "ව",
            "s" to "ස",
            "sh" to "ශ",
            "S" to "ෂ",
            "Sh" to "ෂ",
            "h" to "හ",
            "f" to "ෆ",
            "zg" to "ඟ",
            "zj" to "ඦ",
            "zd" to "ඬ",
            "zdh" to "ඳ",
            "zq" to "ඳ",
            "zk" to "ඤ",
            "zh" to "ඥ",
        )
        val signs = listOf(
            "a" to "",
            "aa" to "ා",
            "A" to "ැ",
            "Aa" to "ෑ",
            "AA" to "ෑ",
            "i" to "ි",
            "ii" to "ී",
            "u" to "ු",
            "uu" to "ූ",
            "e" to "ෙ",
            "ee" to "ේ",
            "ai" to "ෛ",
            "o" to "ො",
            "oo" to "ෝ",
            "au" to "ෞ",
            "ou" to "ෞ",
        )
        for ((romanBase, sinhalaBase) in bases) {
            assertEquals("Bare consonant $romanBase", "${sinhalaBase}්", FoxPhoneticComposer.convert(romanBase))
            for ((romanVowel, sign) in signs) {
                assertEquals("$romanBase$romanVowel", sinhalaBase + sign,
                    FoxPhoneticComposer.convert(romanBase + romanVowel))
            }
        }
    }

    @Test fun cursorChangeSelectionAndHardLimitDoNotEraseExistingText() {
        val composer = FoxPhoneticComposer()
        var editor = "hello "
        for (key in "ammaa") {
            val edit = composer.type(key.toString(), editor)!!
            editor = editor.dropLast(edit.removeCodePoints) + edit.insert
        }
        assertEquals("hello අම්මා", editor)
        val afterCursorChange = composer.type("k", "elsewhere")!!
        assertEquals(0, afterCursorChange.removeCodePoints)
        assertEquals("ක්", afterCursorChange.insert)
        assertNotNull(composer.backspace("ක්"))
        assertEquals(null, composer.backspace(""))
        composer.reset()
        assertEquals(null, composer.type(" ", "anything"))
        for (key in "a".repeat(120)) {
            val edit = composer.type(key.toString(), "" )!!
            assertTrue(edit.removeCodePoints >= 0)
        }
    }
}
