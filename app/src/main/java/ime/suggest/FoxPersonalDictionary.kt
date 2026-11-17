package ime.suggest

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** FoxProVoiceLearning2026: local, capped word history. No network or full message logs. */
class FoxPersonalDictionary(context: Context) {
    private data class Usage(var count: Int, var recent: Long)
    private val prefs = context.getSharedPreferences("fox_local_words_v1", Context.MODE_PRIVATE)
    private val words = LinkedHashMap<String, Usage>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val save = Runnable { persist() }
    private var sequence = 0L
    private val maxWords = 500

    init {
        try {
            val items = JSONArray(prefs.getString("words", "[]") ?: "[]")
            for (i in 0 until minOf(items.length(), maxWords)) {
                val item = items.optJSONObject(i) ?: continue
                val word = item.optString("word", "")
                if (!valid(word)) continue
                val count = item.optInt("count", 1).coerceIn(1, 5000)
                val used = item.optLong("recent", 0L).coerceAtLeast(0L)
                words[word] = Usage(count, used)
                if (used > sequence) sequence = used
            }
        } catch (_: Exception) { // A corrupt cache should never crash the keyboard.
            words.clear()
        }
    }

    private fun canonical(word: String): String =
        Normalizer.normalize(word.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun valid(word: String): Boolean = word.length in 2..32 && word.all {
        val category = Character.getType(it)
        Character.isLetter(it) || category == Character.NON_SPACING_MARK.toInt() ||
            category == Character.COMBINING_SPACING_MARK.toInt()
    }

    fun remember(raw: String) {
        val word = canonical(raw)
        if (!valid(word)) return
        val usage = words.getOrPut(word) { Usage(0, 0L) }
        usage.count = (usage.count + 1).coerceAtMost(5000)
        usage.recent = ++sequence
        if (words.size > maxWords) {
            val least = words.minByOrNull { it.value.count.toLong() * 1000L + it.value.recent / 20L }
            if (least != null) words.remove(least.key)
        }
        mainHandler.removeCallbacks(save)
        mainHandler.postDelayed(save, 1200L)
    }

    fun suggestions(prefix: String, limit: Int): List<String> {
        val candidate = canonical(prefix)
        if (candidate.isBlank() || candidate.length > 32 || limit <= 0) return emptyList()
        return words.asSequence()
            .filter { it.key.startsWith(candidate) && it.key != candidate }
            .sortedWith(
                compareByDescending<Map.Entry<String, Usage>> { it.value.count }
                    .thenByDescending { it.value.recent }
            )
            .take(limit)
            .map { it.key }
            .toList()
    }

    fun flush() {
        mainHandler.removeCallbacks(save)
        persist()
    }

    private fun persist() {
        try {
            val result = JSONArray()
            for ((word, usage) in words) {
                result.put(JSONObject().put("word", word)
                    .put("count", usage.count).put("recent", usage.recent))
            }
            prefs.edit().putString("words", result.toString()).apply()
        } catch (_: Exception) { }
    }
}
