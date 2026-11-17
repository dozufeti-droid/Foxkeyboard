package ime.suggest

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

/** DKEY_TENK2026: Indexed, local personal words. 10,000 limit; no network access. */
class FoxPersonalDictionary(context: Context) {
    private val appContext = context.applicationContext
    private val helper = WordsDb(appContext)
    private val db: SQLiteDatabase = helper.writableDatabase
    private val maxWords = 10_000

    init { importOldWordsIfNeeded() }

    companion object {
        private const val TABLE = "learned_words"
        private const val LEGACY_PREFS = "fox_local_words_v1"

        /** Clear learned words, not the shipped built-in dictionary. */
        fun clearAll(context: Context) {
            val helper = WordsDb(context.applicationContext)
            try { helper.writableDatabase.delete(TABLE, null, null) }
            finally { helper.close() }
            context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .edit().remove("words").apply()
        }
    }

    private class WordsDb(context: Context) :
        SQLiteOpenHelper(context, "dkey_learned_words_v2.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE learned_words (word TEXT PRIMARY KEY COLLATE BINARY, count INTEGER NOT NULL, recent INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX learned_words_ranking ON learned_words(count, recent)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No destructive migrations; future version migrations need explicit rules.
        }
    }

    private fun canonical(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun valid(word: String): Boolean = word.length in 2..32 &&
        word.any { Character.isLetter(it) } && word.all {
            val type = Character.getType(it)
            Character.isLetter(it) || type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() || it == '\u200d'
        }

    private fun importOldWordsIfNeeded() {
        val prefs = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("words", null) ?: return
        try {
            if (DatabaseUtils.queryNumEntries(db, TABLE) == 0L) {
                val items = JSONArray(raw)
                db.beginTransaction()
                try {
                    for (i in 0 until minOf(items.length(), maxWords)) {
                        val item = items.optJSONObject(i) ?: continue
                        val word = canonical(item.optString("word", ""))
                        if (!valid(word)) continue
                        val cv = ContentValues().apply {
                            put("word", word)
                            put("count", item.optInt("count", 1).coerceIn(1, 5000))
                            put("recent", item.optLong("recent", 0L).coerceAtLeast(0L))
                        }
                        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            // Remove only after a successful migration, never before.
            prefs.edit().remove("words").apply()
        } catch (_: Exception) {
            // On failure retain legacy data for retry; never block typing.
        }
    }

    @Synchronized fun remember(raw: String) {
        val word = canonical(raw)
        if (!valid(word)) return
        try {
            val oldCount = db.rawQuery("SELECT count FROM $TABLE WHERE word = ?", arrayOf(word))
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val cv = ContentValues().apply {
                put("word", word)
                put("count", (oldCount + 1).coerceAtMost(5000))
                put("recent", System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            if (oldCount == 0 && DatabaseUtils.queryNumEntries(db, TABLE) > maxWords) {
                db.execSQL("DELETE FROM $TABLE WHERE word = (SELECT word FROM $TABLE ORDER BY count ASC, recent ASC LIMIT 1)")
            }
        } catch (_: Exception) { }
    }

    @Synchronized fun suggestions(prefix: String, limit: Int): List<String> {
        val candidate = canonical(prefix)
        if (candidate.isBlank() || candidate.length > 32 || limit <= 0 ||
            !candidate.all { Character.isLetter(it) ||
                Character.getType(it) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt() || it == '\u200d'
            }) return emptyList()
        return try {
            val result = ArrayList<String>(limit)
            // Indexed range scan instead of scanning and sorting 10,000 rows on each keypress.
            db.rawQuery(
                "SELECT word FROM $TABLE WHERE word >= ? AND word < ? AND word <> ? ORDER BY count DESC, recent DESC LIMIT ?",
                arrayOf(candidate, candidate + '\uffff', candidate, limit.coerceIn(1, 8).toString())
            ).use { cursor -> while (cursor.moveToNext()) result.add(cursor.getString(0)) }
            result
        } catch (_: Exception) { emptyList() }
    }

    fun flush() { /* SQLite updates are committed at completed-word boundaries. */ }
}
