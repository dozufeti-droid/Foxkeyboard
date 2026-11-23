package unicode.sinhala.keyboard.memory

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStreamReader

/** Manual profile/chat management, opened from app Settings or long-press Clipboard. */
class ConversationMemoryActivity : AppCompatActivity() {
    private lateinit var store: ConversationMemoryStore
    private lateinit var root: LinearLayout
    private var openedProfile: Long? = null
    private var pageOffset = 0
    private var importingProfile: Long? = null
    private var myExportSender = ""

    private val pickTextFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = importingProfile ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        Toast.makeText(this, "Importing local chat…", Toast.LENGTH_SHORT).show()
        Thread {
            val outcome: Result<Int> = runCatching {
                val raw = contentResolver.openInputStream(uri)?.use { stream ->
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val output = StringBuilder()
                        val buffer = CharArray(8192)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            require(output.length + n <= 8_000_000) { "Export exceeds 8MB limit" }
                            output.append(buffer, 0, n)
                        }
                        output.toString()
                    }
                } ?: error("Could not open chat file")
                val parsed = KakaoChatImporter.parse(raw, myExportSender)
                require(parsed.messages.isNotEmpty()) { "No recognised KakaoTalk messages" }
                require(parsed.senders.any { it.equals(myExportSender, ignoreCase = true) }) {
                    "Your sender name was not found. Use the exact name in the export. " +
                        "Found: ${parsed.senders.take(4).joinToString()}"
                }
                val db = ConversationMemoryStore(applicationContext)
                try { db.importMessages(target, parsed.messages) } finally { db.close() }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.onSuccess {
                    toast("Imported $it messages")
                    openedProfile = target
                    pageOffset = 0
                    showConversation(target)
                }.onFailure { toast("Import failed: ${it.message}") }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConversationMemoryStore(applicationContext)
        openedProfile = savedInstanceState?.getLong("opened_profile")?.takeIf { it > 0 }
        importingProfile = savedInstanceState?.getLong("importing_profile")?.takeIf { it > 0 }
        myExportSender = savedInstanceState?.getString("my_sender").orEmpty()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        setContentView(root)
        openedProfile?.let(::showConversation) ?: showProfiles()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("opened_profile", openedProfile ?: -1)
        outState.putLong("importing_profile", importingProfile ?: -1)
        outState.putString("my_sender", myExportSender)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (openedProfile != null) {
            openedProfile = null
            showProfiles()
        } else super.onBackPressed()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun text(label: String, sp: Float = 15f): TextView = TextView(this).apply {
        this.text = label
        textSize = sp
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun horizontal(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (view in views) addView(view, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun scrollArea(): LinearLayout {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return list
    }

    private fun showProfiles() {
        openedProfile = null
        root.removeAllViews()
        root.addView(horizontal(text("Conversation Memory", 21f), button("Close") { finish() }))
        root.addView(text("Local history. Only the active profile changes Korean Context Translation.", 13f))
        root.addView(button("+ Create Profile") { createProfileDialog() })
        val list = scrollArea()
        for (profile in store.listProfiles()) {
            val enabled = store.getActiveId() == profile.id
            list.addView(horizontal(
                button("📁 ${profile.name}") { pageOffset = 0; showConversation(profile.id) },
                button(if (enabled) "Turn OFF" else "Turn ON") {
                    store.setActiveProfile(if (enabled) null else profile.id)
                    showProfiles()
                }
            ))
        }
        root.addView(text("Privacy: profile messages remain on this device until you delete them. " +
            "Only the last ten messages are sent to the chosen AI when Context Translate is used.", 12f))
    }

    private fun createProfileDialog() {
        val input = EditText(this).apply { hint = "Example: Hachiyan"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("New Conversation Profile")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                runCatching { store.createProfile(input.text.toString()) }
                    .onSuccess { pageOffset = 0; showConversation(it) }
                    .onFailure { toast(it.message ?: "Could not create profile") }
            }.show()
    }

    private fun showConversation(profileId: Long) {
        val profile = store.getProfile(profileId) ?: run { showProfiles(); return }
        openedProfile = profileId
        root.removeAllViews()
        root.addView(horizontal(button("‹ Profiles") { showProfiles() }, text(profile.name, 20f)))
        val enabled = store.getActiveId() == profileId
        root.addView(horizontal(
            button(if (enabled) "● Memory ON — Turn OFF" else "○ Memory OFF — Turn ON") {
                store.setActiveProfile(if (enabled) null else profileId)
                showConversation(profileId)
            },
            button("Import Chat") { askExportSender(profileId) }
        ))
        root.addView(horizontal(
            button("+ Add Message") { addMessageDialog(profileId, profile.name) },
            button("Clear Chat") { clearChatDialog(profileId) },
            button("Delete Profile") { deleteProfileDialog(profileId) }
        ))
        val items = store.messagesPage(profileId, pageOffset, 100)
        root.addView(text("Chat history · ${if (pageOffset == 0) "latest" else "older"} messages " +
            "(long press a message to edit/delete)", 12f))
        val list = scrollArea()
        for (message in items) {
            val who = if (message.sender == "me") "Me" else profile.name
            val label = "$who  ${message.timestamp}\n${message.text}"
            val item = text(label, 15f)
            item.setBackgroundColor(if (message.sender == "me") 0x163080CA else 0x11000000)
            item.setOnLongClickListener { messageActions(profileId, message); true }
            list.addView(item)
        }
        root.addView(horizontal(
            button("← Newer") { pageOffset = (pageOffset - 100).coerceAtLeast(0); showConversation(profileId) },
            button("Older →") { if (items.size >= 100) { pageOffset += 100; showConversation(profileId) } }
        ))
    }

    private fun askExportSender(profileId: Long) {
        val input = EditText(this).apply {
            hint = "Your name exactly as shown in exported chat"
            setSingleLine(true)
            setText(myExportSender)
        }
        AlertDialog.Builder(this).setTitle("Import KakaoTalk .txt")
            .setMessage("Select the sender name representing YOU in the export. " +
                "All other senders will be marked as the contact. Importing again skips exact duplicates.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose file") { _, _ ->
                val sender = input.text.toString().trim()
                if (sender.isBlank()) { toast("Enter your sender name first"); return@setPositiveButton }
                importingProfile = profileId
                myExportSender = sender
                pickTextFile.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
            }.show()
    }

    private fun addMessageDialog(profileId: Long, contactName: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), 0, dp(15), 0)
        }
        val contactRadio = RadioButton(this).apply { id = View.generateViewId(); text = contactName }
        val meRadio = RadioButton(this).apply { id = View.generateViewId(); text = "Me" }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(contactRadio)
            addView(meRadio)
            check(contactRadio.id)
        }
        layout.addView(group)
        val input = EditText(this).apply {
            hint = "Paste the copied message"
            minLines = 3
            maxLines = 9
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(input)
        layout.addView(button("Paste from Clipboard") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val copied = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            input.setText(copied)
        })
        AlertDialog.Builder(this).setTitle("Add Message")
            .setView(layout).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                runCatching {
                    store.addMessage(profileId,
                        if (group.checkedRadioButtonId == meRadio.id) "me" else "contact",
                        input.text.toString())
                }.onSuccess { pageOffset = 0; showConversation(profileId) }
                    .onFailure { toast(it.message ?: "Could not save message") }
            }.show()
    }

    private fun messageActions(profileId: Long, message: ConversationMemoryStore.Message) {
        AlertDialog.Builder(this).setTitle("Message")
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                if (which == 0) {
                    val input = EditText(this).apply {
                        setText(message.text)
                        minLines = 3
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    }
                    AlertDialog.Builder(this).setTitle("Edit Message").setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save") { _, _ ->
                            runCatching { store.updateMessage(message.id, profileId, input.text.toString()) }
                                .onSuccess { showConversation(profileId) }
                                .onFailure { toast(it.message ?: "Edit failed") }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle("Delete this message?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            store.deleteMessage(message.id, profileId)
                            showConversation(profileId)
                        }.show()
                }
            }.show()
    }

    private fun clearChatDialog(profileId: Long) {
        AlertDialog.Builder(this).setTitle("Delete ALL chat messages?")
            .setMessage("The profile remains, but its message history cannot be recovered.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete all") { _, _ ->
                store.clearMessages(profileId)
                pageOffset = 0
                showConversation(profileId)
            }.show()
    }

    private fun deleteProfileDialog(profileId: Long) {
        AlertDialog.Builder(this).setTitle("Delete profile and all messages?")
            .setMessage("This permanently removes the profile and its entire history.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                store.deleteProfile(profileId)
                showProfiles()
            }.show()
    }
}
