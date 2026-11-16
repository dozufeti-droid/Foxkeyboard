package unicode.sinhala.keyboard.ai

import java.util.Locale

/** Helps the translator interpret *Romanized Sinhala*, without altering the draft. */
object SinglishTranslationHints {
    private val strongWords = setOf(
        "meyalage", "eyalage", "eyange", "mage", "oyage", "apey", "mata",
        "karanne", "karanna", "weda", "nane", "naha", "nehe", "bahala",
        "meke", "meka", "pissu", "natanawa", "natanava", "thranslate",
        "ganna", "kiyanne", "kohomada", "mokakda", "oyata", "mama"
    )
    private val meanings = linkedMapOf(
        "meyalage" to "these people's / their; NEVER my",
        "eyalage" to "their; NEVER my",
        "eyange" to "their; NEVER my",
        "mage" to "my / mine; NEVER their",
        "oyage" to "your; NEVER my",
        "apey" to "our; NEVER my",
        "mata" to "to me / for me",
        "mama" to "I / me",
        "api" to "we / us (not the English acronym API)",
        "eka" to "that one / it (depends on context)",
        "weda" to "work / function (as a verb with karanne)",
        "karanne" to "does / is doing (depends on context)",
        "nane" to "negative ending: isn't / doesn't; sometimes 'isn't it?'",
        "naha" to "not / no",
        "nehe" to "not / no",
        "bahala" to "after getting off / get off (a bus or vehicle)",
        "ganna" to "get / take; in call ekak ganna means call",
        "ai" to "why, when used in Sinhala phrasing; NOT Artificial Intelligence",
        "ayi" to "why",
        "meke" to "in this / of this / this thing's (NOT my)",
        "meka" to "this / this thing (NOT I or my)",
        "pissu" to "crazy / malfunctioning in idiomatic phrases",
        "natanawa" to "acts up / goes haywire in the idiom pissu natanawa",
        "natanava" to "acts up / goes haywire in the idiom pissu natanava",
        "thranslate" to "translation or translator (misspelling of English translate)"
    )
    private fun words(text: String): Set<String> =
        Regex("[a-zA-Z]+")
            .findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .toSet()

    fun notes(text: String): String {
        val present = words(text)
        if (present.none { it in strongWords }) return ""
        val detected = meanings.filterKeys { it in present }
        val clues = mutableListOf<String>()
        detected.forEach { (key, value) -> clues.add("$key = $value") }
        if (hasHaywireIdiom(text)) {
            clues.add(
                "IDIOM 'pissu natanawa' = is acting up / is going haywire / is a mess. " +
                "This is AFFIRMATIVE negative criticism, NOT 'isn't crazy' or 'not weird'. " +
                "'meke thranslate eka pissu natanawa' = " +
                "'This translation feature is acting up.'"
            )
        }
        return clues.joinToString("; ")
    }

    fun isUnfinished(text: String): Boolean {
        if (notes(text).isEmpty()) return false
        return Regex("(?:,|\\s)(?:ai|ayi|aei)\\s+[a-zA-Z]{1,2}$", RegexOption.IGNORE_CASE)
            .containsMatchIn(text.trim())
    }

    fun hasHaywireIdiom(text: String): Boolean =
        Regex("""\bpissu\s+natan(?:awa|ava|ne|na)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

    fun hasContradictoryIdiom(text: String, english: String, korean: String): Boolean {
        if (!hasHaywireIdiom(text)) return false
        val contradictoryEnglish = Regex(
            """\b(?:isn't|is not|not|doesn't|does not)\s+(?:crazy|mad|weird|strange|broken|a mess)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(english)
        val contradictoryKorean = Regex(
            """(?:이상하지\s*않|미치지\s*않|문제\s*없|정상적으로\s*작동|괜찮)"""
        ).containsMatchIn(korean)
        return contradictoryEnglish || contradictoryKorean
    }

    fun hasContradictoryPronoun(text: String, english: String, korean: String): Boolean {
        val tokens = words(text)
        val thirdPerson = tokens.any { it == "meyalage" || it == "eyalage" || it == "eyange" }
        val firstPerson = tokens.any { it == "mage" || it == "mama" }
        if (!thirdPerson || firstPerson) return false
        return Regex("^\\s*(my|mine|your|our)\\b", RegexOption.IGNORE_CASE).containsMatchIn(english) ||
            Regex("^\\s*(제|내|우리|저희)\\s*(웹사이트|홈페이지|사이트)").containsMatchIn(korean)
    }
}
