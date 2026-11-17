package ime.suggest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import java.util.NavigableSet
import java.util.TreeSet

/** DKEY_TENK2026: 10k shipped words + up to 10k learned words, all offline. */
class SuggestionEngine(private val context: Context) {
    private val personalWords = FoxPersonalDictionary(context)
    @Volatile private var builtIn: NavigableSet<String> = TreeSet<String>()
    @Volatile private var initialized = false

    suspend fun initializeIfNeeded() {
        if (initialized) return
        withContext(Dispatchers.IO) {
            if (initialized) return@withContext
            val loaded = TreeSet<String>()
            try {
                context.assets.open("dkey_builtin_10000.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank() || line.startsWith("#")) continue
                        loaded.add(Normalizer.normalize(line.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT))
                    }
                }
            } catch (_: Exception) {
                // Keep personal suggestions available if bundled assets cannot be read.
            }
            builtIn = loaded // Immutable after publication; no concurrent mutations.
            initialized = true
        }
    }

    fun suggest(prefix: String, limit: Int = 5): List<String> {
        val key = Normalizer.normalize(prefix.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        if (key.isEmpty() || key.length > 32 || limit <= 0 || key.any { it.isWhitespace() })
            return emptyList()
        val max = limit.coerceIn(1, 8)
        val learned = personalWords.suggestions(key, max)
        if (learned.size >= max) return learned
        val others = ArrayList<String>()
        val iterator = builtIn.tailSet(key, true).iterator()
        // Bound candidate work per keypress; never search every one of the 10,000 words.
        while (iterator.hasNext() && others.size < 96) {
            val word = iterator.next()
            if (!word.startsWith(key)) break
            if (word != key && word !in learned) others.add(word)
        }
        others.sortWith(compareBy<String> { it.length }.thenBy { it })
        return learned + others.take(max - learned.size)
    }

    fun recordAccepted(word: String, lang: LanguageDetector.Language) {
        personalWords.remember(word)
    }
    fun recordTypedWord(word: String) {
        personalWords.remember(word)
    }
    fun flushTypedWords() { personalWords.flush() }

    /** Keep the legacy nested Trie API available for any existing callers/tests. */
    class Trie {
        private class Node {
            val children: MutableMap<Char, Node> = LinkedHashMap()
            var isWord = false
        }
        private val root = Node()
        fun insert(word: String) {
            var at = root
            for (ch in word) at = at.children.getOrPut(ch) { Node() }
            at.isWord = true
        }
        fun getByPrefix(prefix: String, limit: Int): List<String> {
            if (limit <= 0) return emptyList()
            var at = root
            for (ch in prefix) at = at.children[ch] ?: return emptyList()
            val result = ArrayList<String>()
            val queue = java.util.ArrayDeque<Pair<Node, String>>()
            queue.add(at to prefix)
            while (queue.isNotEmpty() && result.size < limit) {
                val (node, text) = queue.removeFirst()
                if (node.isWord) result.add(text)
                for ((ch, child) in node.children) queue.add(child to (text + ch))
            }
            return result
        }
    }
}
