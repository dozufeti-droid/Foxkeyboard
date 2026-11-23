package unicode.sinhala.keyboard.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/** Local, user-controlled conversation history. Never synchronised or backed up by this app. */
class ConversationMemoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "fox_conversation_memory.db", null, 1) {

    data class Profile(val id: Long, val name: String)
    data class Message(
        val id: Long,
        val profileId: Long,
        val sender: String, // "me" or "contact"
        val text: String,
        val timestamp: String
    )
    data class Draft(val sender: String, val text: String, val timestamp: String = "")

    private val prefs = context.applicationContext.getSharedPreferences("fox_context_memory", Context.MODE_PRIVATE)

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE profiles (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL COLLATE NOCASE UNIQUE
        )""")
        db.execSQL("""CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
            sender TEXT NOT NULL CHECK(sender IN ('me','contact')),
            text TEXT NOT NULL,
            original_timestamp TEXT NOT NULL DEFAULT '',
            import_key TEXT
        )""")
        db.execSQL("CREATE INDEX messages_by_profile ON messages(profile_id, id DESC)")
        db.execSQL("CREATE UNIQUE INDEX unique_import ON messages(profile_id, import_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations must preserve users' histories.
        throw IllegalStateException("Unsupported conversation memory database version $oldVersion")
    }

    fun createProfile(input: String): Long {
        val name = input.trim()
        require(name.isNotEmpty() && name.length <= 80) { "Profile name must be 1–80 characters" }
        return writableDatabase.insertOrThrow("profiles", null, ContentValues().apply {
            put("name", name)
        })
    }

    fun listProfiles(): List<Profile> {
        val out = ArrayList<Profile>()
        readableDatabase.rawQuery("SELECT id, name FROM profiles ORDER BY name COLLATE NOCASE", null).use {
            while (it.moveToNext()) out.add(Profile(it.getLong(0), it.getString(1)))
        }
        return out
    }

    /** Read-only count; does not load full chat history into the settings UI. */
    fun messageCount(profileId: Long): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM messages WHERE profile_id = ?", arrayOf(profileId.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun getProfile(id: Long): Profile? {
        readableDatabase.rawQuery("SELECT name FROM profiles WHERE id = ?", arrayOf(id.toString())).use {
            return if (it.moveToFirst()) Profile(id, it.getString(0)) else null
        }
    }

    fun getActiveId(): Long = prefs.getLong("active_id", -1L)

    fun getActiveProfile(): Profile? = getActiveId().takeIf { it > 0 }?.let(::getProfile)

    fun setActiveProfile(id: Long?) {
        require(id == null || getProfile(id) != null) { "Profile does not exist" }
        prefs.edit().apply {
            if (id == null) remove("active_id") else putLong("active_id", id)
        }.apply()
    }

    fun addMessage(profileId: Long, sender: String, text: String, timestamp: String = "") : Long {
        require(sender == "me" || sender == "contact") { "Invalid sender" }
        val body = text.trim()
        require(body.isNotEmpty() && body.length <= 20000) { "Message must be 1–20000 characters" }
        return writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("profile_id", profileId)
            put("sender", sender)
            put("text", body)
            put("original_timestamp", timestamp.take(100))
        })
    }

    fun importMessages(profileId: Long, drafts: List<Draft>): Int {
        require(getProfile(profileId) != null) { "Profile does not exist" }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            var count = 0
            for (draft in drafts) {
                if (draft.sender !in setOf("me", "contact")) continue
                val body = draft.text.trim()
                if (body.isEmpty() || body.length > 20000) continue
                val identifying = "${draft.timestamp}|${draft.sender}|$body"
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(identifying.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val result = db.insertWithOnConflict("messages", null, ContentValues().apply {
                    put("profile_id", profileId)
                    put("sender", draft.sender)
                    put("text", body)
                    put("original_timestamp", draft.timestamp.take(100))
                    put("import_key", hash)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (result >= 0) count++
            }
            db.setTransactionSuccessful()
            count
        } finally {
            db.endTransaction()
        }
    }

    /** Page newest-first in SQL and return chronological order for the chat viewer. */
    fun messagesPage(profileId: Long, offset: Int = 0, limit: Int = 100): List<Message> {
        val out = ArrayList<Message>()
        val safeLimit = limit.coerceIn(1, 200)
        readableDatabase.rawQuery(
            "SELECT id, profile_id, sender, text, original_timestamp FROM messages " +
                "WHERE profile_id = ? ORDER BY id DESC LIMIT $safeLimit OFFSET ${offset.coerceAtLeast(0)}",
            arrayOf(profileId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) out.add(Message(
                cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4)
            ))
        }
        return out.asReversed()
    }

    /** Maximum ten records, capped to keep network payload small. Called from Dispatchers.IO. */
    fun lastTenForPrompt(profileId: Long): List<Draft> = messagesPage(profileId, 0, 10)
        .map { Draft(it.sender, it.text.take(320), it.timestamp.take(40)) }

    fun updateMessage(id: Long, profileId: Long, text: String) {
        val value = text.trim()
        require(value.isNotEmpty() && value.length <= 20000) { "Invalid message" }
        writableDatabase.update("messages", ContentValues().apply { put("text", value) },
            "id = ? AND profile_id = ?", arrayOf(id.toString(), profileId.toString()))
    }

    fun deleteMessage(id: Long, profileId: Long) {
        writableDatabase.delete("messages", "id = ? AND profile_id = ?",
            arrayOf(id.toString(), profileId.toString()))
    }

    fun clearMessages(profileId: Long) {
        writableDatabase.delete("messages", "profile_id = ?", arrayOf(profileId.toString()))
    }

    fun deleteProfile(id: Long) {
        if (getActiveId() == id) setActiveProfile(null)
        writableDatabase.delete("profiles", "id = ?", arrayOf(id.toString()))
    }
}
