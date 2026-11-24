package unicode.sinhala.keyboard.ai

import org.junit.Assert.*
import org.junit.Test

class FoxHybridChatTest {
    @Test fun bilingualParsing() {
        val p = FoxHybridChat.parse("EN: I live in Seoul and have a housemate.\nSI: මම සෝල්වල housemate කෙනෙක් එක්ක ජීවත් වෙනවා.")
        assertTrue(p.complete)
        assertTrue(p.display().contains("housemate"))
        assertEquals("I live in Seoul and have a housemate.", p.english)
    }
    @Test fun recoveryPreservesGoodHalf() {
        val p = FoxHybridChat.parse("EN: I turned off notifications.\nSI: 알람을 꺼둡니다")
        assertFalse(p.complete)
        assertNotNull(p.english)
        val next = FoxHybridChat.parse("මම notifications අක්‍රිය කරලා.", "si")
        assertTrue(p.merge(next).complete)
        assertEquals(p.english, p.merge(next).english)
        assertTrue(FoxHybridChat.isPartialDisplay(p.display()))
    }
    @Test fun mixedScriptsDoNotRejectSinhala() {
        val p = FoxHybridChat.parse("EN: WhatsApp notifications are on.\nSI: WhatsApp notifications එනවා. ㅎㅎ")
        assertTrue(p.complete)
        assertTrue(p.sinhala!!.contains("හහ්"))
        assertTrue(FoxHybridChat.parse("SI: මම React AWS SEO API භාවිතා කරනවා.").useful)
    }
    @Test fun plainRecoveryAndOffline() {
        assertEquals("I understand.", FoxHybridChat.parse("I understand.", "en").english)
        assertTrue(FoxHybridChat.local("맞아요")!!.complete)
        assertTrue(FoxHybridChat.local("thank you")!!.complete)
        assertTrue(FoxHybridChat.local("ㅋㅋㅋ")!!.complete)
        assertNull(FoxHybridChat.local("맞아요?"))
        assertNull(FoxHybridChat.parse("서울에서 자취하고 있어요").english)
    }
    @Test fun noLanguageAndUnlabelled() {
        assertFalse(FoxHybridChat.parse("").useful)
        assertTrue(FoxHybridChat.parse("Hello!\nහලෝ!").complete)
        assertTrue(FoxHybridChat.parse("```text\nEN: Okay.\nSI: හරි.\n```").complete)
    }
}
