package unicode.sinhala.keyboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.Normalizer

/** Indexed, read-only disk lookup. Never called on the keyboard UI thread. */
internal class FoxOfflineDictionary(private val context: Context) {
    data class Translation(val english: String, val korean: String?)

    private val fileLock = Any()

    fun prepare() {
        try {
            databaseFile()
        } catch (e: Exception) {
            Log.w("FoxOfflineDictionary", "Could not prepare offline dictionary", e)
        }
    }

    fun lookup(sinhala: String): Translation? {
        val normalized = Normalizer.normalize(sinhala, Normalizer.Form.NFC)
        if (normalized.isBlank()) return null
        return try {
            val path = databaseFile()
            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT english, korean FROM priority WHERE sinhala = ? LIMIT 1",
                    arrayOf(normalized)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        return Translation(cursor.getString(0), cursor.getString(1))
                    }
                }

                // Prefer cross-source agreement and common English vocabulary.
                // Research-derived entries are noisy, so ignore unsupported rare words.
                db.rawQuery(
                    """SELECT english, support, freq_rank FROM si_en
                       WHERE sinhala = ?
                       ORDER BY support DESC,
                                CASE WHEN freq_rank IS NULL THEN 1 ELSE 0 END,
                                freq_rank ASC, LENGTH(english) ASC
                       LIMIT 16""".trimIndent(),
                    arrayOf(normalized)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val english = cursor.getString(0)
                        val support = cursor.getInt(1)
                        val rank = if (cursor.isNull(2)) null else cursor.getInt(2)
                        if (support < 2 && (rank == null || rank !in 1..10000)) continue
                        if (english.any { !it.isLetter() && it != ' ' && it != '-' }) continue

                        // Do not arbitrarily choose between multiple Korean senses.
                        var uniqueKorean: String? = null
                        db.rawQuery(
                            "SELECT korean FROM en_ko WHERE english = ? LIMIT 2",
                            arrayOf(english)
                        ).use { ko ->
                            if (ko.moveToFirst()) {
                                val one = ko.getString(0)
                                if (!ko.moveToNext()) uniqueKorean = one
                            }
                        }
                        return Translation(english, uniqueKorean)
                    }
                }
                null
            }
        } catch (e: Exception) {
            // No user-entered text in logs; an unavailable DB must not break typing.
            Log.w("FoxOfflineDictionary", "Offline lookup unavailable", e)
            null
        }
    }

    private fun databaseFile(): File = synchronized(fileLock) {
        val target = File(context.noBackupFilesDir, "fox_dictionary_optimized_v1.sqlite")
        if (target.isFile && target.length() > 0L) return@synchronized target

        val temp = File(context.noBackupFilesDir, "fox_dictionary_optimized_v1.tmp")
        if (temp.exists() && !temp.delete()) throw IOException("Could not clear dictionary temp")
        context.assets.open("fox_dictionary_optimized.sqlite").use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output, 64 * 1024)
                output.fd.sync()
            }
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not install offline dictionary")
        }
        target
    }
}
