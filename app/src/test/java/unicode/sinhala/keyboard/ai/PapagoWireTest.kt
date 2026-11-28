package unicode.sinhala.keyboard.ai

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class PapagoWireTest {
    private fun decoded(text: String): Map<String, String> = PapagoWire.form(text)
        .split('&').associate { pair ->
            val items = pair.split('=', limit = 2)
            URLDecoder.decode(items[0], "UTF-8") to URLDecoder.decode(items[1], "UTF-8")
        }

    @Test fun requestUsesEnglishKoreanAndHonorificStyle() {
        assertEquals(mapOf("source" to "en", "target" to "ko", "text" to "Did you eat?",
            "honorific" to "true", "dict" to "false", "useGlossary" to "false"), decoded("Did you eat?"))
    }
    @Test fun textIsEncodedWithoutLosingCharacters() {
        val text = "A+B & C=50%\nHello 😊 café"
        assertEquals(text, decoded(text)["text"])
    }
    @Test fun rejectSinhalaAndAlreadyKorean() {
        for (text in listOf("සිංහල", "Hello සිංහල", "안녕", "Hello 안녕", "\u3131", "123", "  ")) {
            assertFalse(PapagoWire.isEnglishCandidate(text))
        }
    }
    @Test fun limits() {
        assertTrue(PapagoWire.isEnglishCandidate("a".repeat(2000)))
        assertFalse(PapagoWire.isEnglishCandidate("a".repeat(2001)))
    }
    @Test fun rejectInvalidOutput() {
        assertTrue(PapagoWire.validKorean("저녁 먹었어요? 😊"))
        for (text in listOf("", "null", "HTTP 200", "Hello", "안녕 සිංහල", "안녕\u0000")) {
            assertFalse(PapagoWire.validKorean(text))
        }
    }
}
