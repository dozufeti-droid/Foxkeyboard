package unicode.sinhala.keyboard.memory

/** Parses KakaoTalk's exported *text* conversation. Never reads KakaoTalk private app data. */
object KakaoChatImporter {
    private val englishDate = Regex("^\\d{1,2} [A-Za-z]+ \\d{4} at .+$")
    private val isoDate = Regex("^\\d{4}-\\d{2}-\\d{2} .+$")
    private val koreanLine = Regex("^\\[(.+?)] \\[(.+?)] (.*)$")

    data class Result(val messages: List<ConversationMemoryStore.Draft>, val senders: Set<String>)

    fun senderNames(raw: String): Set<String> = lines(raw).mapNotNull { parseHeader(it)?.first }.toSet()

    fun parse(raw: String, myExportName: String): Result {
        require(myExportName.isNotBlank()) { "Enter your sender name as it appears in the export" }
        val messages = ArrayList<ConversationMemoryStore.Draft>()
        val senders = linkedSetOf<String>()
        var sender: String? = null
        var time = ""
        val body = StringBuilder()
        fun flush() {
            val currentSender = sender ?: return
            val text = body.toString().trim()
            if (text.isNotBlank()) {
                messages.add(ConversationMemoryStore.Draft(
                    if (currentSender.equals(myExportName.trim(), ignoreCase = true)) "me" else "contact",
                    text, time
                ))
            }
            body.setLength(0)
        }
        for (line in lines(raw)) {
            val header = parseHeader(line)
            if (header != null) {
                flush()
                sender = header.first
                senders.add(header.first)
                time = header.second
                body.append(header.third)
            } else if (sender != null) {
                // Continuation lines belong to the previous message, including empty lines.
                body.append('\n').append(line)
            }
        }
        flush()
        return Result(messages, senders)
    }

    private fun lines(raw: String) = raw.removePrefix("\uFEFF").lineSequence().map { it.trimEnd('\r') }

    private fun parseHeader(line: String): Triple<String, String, String>? {
        val kor = koreanLine.matchEntire(line)
        if (kor != null) return Triple(kor.groupValues[1], kor.groupValues[2], kor.groupValues[3])
        val sep = line.indexOf(", ")
        val colon = line.indexOf(" : ", startIndex = sep.coerceAtLeast(0) + 2)
        if (sep < 0 || colon < 0) return null
        val stamp = line.substring(0, sep)
        if (!englishDate.matches(stamp) && !isoDate.matches(stamp)) return null
        val sender = line.substring(sep + 2, colon).trim()
        if (sender.isEmpty()) return null
        return Triple(sender, stamp, line.substring(colon + 3))
    }
}
