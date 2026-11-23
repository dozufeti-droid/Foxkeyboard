package unicode.sinhala.keyboard.memory

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import unicode.sinhala.keyboard.ui.theme.UnicodeSinhalaTheme
import java.io.InputStreamReader

/**
 * V8.2: Material You UI matching the existing D Key settings cards.
 * Manual message management and the V8 database format are unchanged.
 * The IME, API selection, and translation path are deliberately untouched here.
 */
class ConversationMemoryActivity : AppCompatActivity() {
    private lateinit var store: ConversationMemoryStore

    private var profiles by mutableStateOf<List<ConversationMemoryStore.Profile>>(emptyList())
    private var activeId by mutableLongStateOf(-1L)
    private var openedProfile by mutableStateOf<Long?>(null)
    private var conversation by mutableStateOf<List<ConversationMemoryStore.Message>>(emptyList())
    private var pageOffset by mutableIntStateOf(0)
    private var profileCounts by mutableStateOf<Map<Long, Int>>(emptyMap())
    private var dialog by mutableStateOf<DialogKind?>(null)
    private var selectedMessage: ConversationMemoryStore.Message? = null
    private var importingProfile: Long? = null
    private var myExportSender = ""
    private var importRunning by mutableStateOf(false)

    private enum class DialogKind { CREATE, IMPORT, ADD, EDIT, ACTIONS, DELETE_MESSAGE, CLEAR, DELETE_PROFILE }

    private val pickTextFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = importingProfile ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        importRunning = true
        Thread {
            val outcome: Result<Int> = runCatching {
                val raw = contentResolver.openInputStream(uri)?.use { stream ->
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            require(result.length + count <= 8_000_000) { "Chat file exceeds 8 MB limit" }
                            result.append(buffer, 0, count)
                        }
                        result.toString()
                    }
                } ?: error("Could not open the selected chat file")
                val parsed = KakaoChatImporter.parse(raw, myExportSender)
                require(parsed.messages.isNotEmpty()) { "No KakaoTalk messages found" }
                require(parsed.senders.any { it.equals(myExportSender, ignoreCase = true) }) {
                    "Your exact exported sender name was not found. Found: " +
                        parsed.senders.take(4).joinToString()
                }
                val db = ConversationMemoryStore(applicationContext)
                try { db.importMessages(target, parsed.messages) } finally { db.close() }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                importRunning = false
                outcome.onSuccess { count ->
                    toast("Imported $count new messages")
                    openedProfile = target
                    pageOffset = 0
                    refresh()
                }.onFailure { error -> toast("Import failed: ${error.message}") }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConversationMemoryStore(applicationContext)
        openedProfile = savedInstanceState?.getLong("opened_profile")?.takeIf { it > 0 }
        importingProfile = savedInstanceState?.getLong("importing_profile")?.takeIf { it > 0 }
        myExportSender = savedInstanceState?.getString("my_sender").orEmpty()
        refresh()
        setContent {
            UnicodeSinhalaTheme {
                MemoryUi()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("opened_profile", openedProfile ?: -1)
        outState.putLong("importing_profile", importingProfile ?: -1)
        outState.putString("my_sender", myExportSender)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Use in-app BackHandler to navigate within profiles")
    override fun onBackPressed() {
        when {
            dialog != null -> dialog = null
            openedProfile != null -> goToProfiles()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }

    private fun refresh() {
        profiles = store.listProfiles()
        activeId = store.getActiveId()
        profileCounts = profiles.associate { it.id to store.messageCount(it.id) }
        val id = openedProfile
        if (id != null) {
            if (profiles.none { it.id == id }) {
                openedProfile = null
                conversation = emptyList()
            } else conversation = store.messagesPage(id, pageOffset, 100)
        }
    }

    private fun goToProfiles() {
        dialog = null
        openedProfile = null
        pageOffset = 0
        refresh()
    }

    private fun openProfile(id: Long) {
        openedProfile = id
        pageOffset = 0
        refresh()
    }

    private fun toggleProfile(id: Long) {
        runCatching {
            store.setActiveProfile(if (activeId == id) null else id)
            refresh()
        }.onFailure { toast(it.message ?: "Could not update active profile") }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MemoryUi() {
        BackHandler(enabled = dialog != null || openedProfile != null) {
            if (dialog != null) dialog = null else goToProfiles()
        }
        val profile = profiles.firstOrNull { it.id == openedProfile }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = profile?.name ?: "Conversation Memory",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        if (profile != null) IconButton(onClick = ::goToProfiles) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back to profiles")
                        }
                    },
                    actions = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close Conversation Memory")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { inset ->
            if (profile == null) ProfilesScreen(Modifier.padding(inset))
            else ConversationScreen(profile, Modifier.padding(inset))
        }
        MemoryDialogs()
    }

    @Composable
    private fun SectionLabel(label: String) {
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
        )
    }

    @Composable
    private fun RoseCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }

    @Composable
    private fun StatusChip(enabled: Boolean) {
        Surface(
            shape = RoundedCornerShape(100.dp),
            color = if (enabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
        ) {
            Text(
                if (enabled) "Memory ON" else "Memory OFF",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun ProfilesScreen(modifier: Modifier = Modifier) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                RoseCard(Modifier.fillMaxWidth()) {
                    Text("Chat smarter, stay natural", fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(7.dp))
                    Text("Keep conversations separate, and use the last 10 messages only when a profile is active.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    val active = profiles.firstOrNull { it.id == activeId }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(active != null)
                        Spacer(Modifier.width(9.dp))
                        Text(active?.name ?: "Normal translation mode",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                SectionLabel("YOUR PROFILES")
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                    onClick = { dialog = DialogKind.CREATE }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("Create new profile", fontWeight = FontWeight.SemiBold)
                }
            }
            if (profiles.isEmpty()) {
                item {
                    RoseCard(Modifier.fillMaxWidth()) {
                        Text("No profiles yet", fontWeight = FontWeight.Medium)
                        Text("Create a profile to import your KakaoTalk conversation.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(profiles, key = { it.id }) { profile ->
                val enabled = activeId == profile.id
                RoseCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(profile.name.firstOrNull()?.uppercase() ?: "?",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${profileCounts[profile.id] ?: 0} messages",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = enabled, onCheckedChange = { toggleProfile(profile.id) })
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { openProfile(profile.id) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open conversation")
                    }
                }
            }
            item {
                Text("Your chat history stays on this device. Context is sent only when you tap Korean Translate with a profile ON.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
            }
        }
    }

    @Composable
    private fun ConversationScreen(
        profile: ConversationMemoryStore.Profile,
        modifier: Modifier = Modifier
    ) {
        var showMenu by remember(profile.id) { mutableStateOf(false) }
        Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            RoseCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(43.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(profile.name.firstOrNull()?.uppercase() ?: "?",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text("${profileCounts[profile.id] ?: 0} saved messages",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(activeId == profile.id)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use conversation memory", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("Only for Korean Context Translate",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = activeId == profile.id,
                        onCheckedChange = { toggleProfile(profile.id) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { dialog = DialogKind.IMPORT },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import chat", fontSize = 13.sp)
                }
                FilledTonalButton(
                    onClick = { dialog = DialogKind.ADD },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Add message", fontSize = 13.sp)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Conversation options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Clear chat") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { showMenu = false; dialog = DialogKind.CLEAR })
                        DropdownMenuItem(text = { Text("Delete profile") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { showMenu = false; dialog = DialogKind.DELETE_PROFILE })
                    }
                }
            }
            if (importRunning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Importing chat…", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CHAT HISTORY", fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                Text(if (pageOffset == 0) "Latest messages" else "Older messages",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                if (conversation.isEmpty()) item {
                    RoseCard(Modifier.fillMaxWidth()) {
                        Text("No messages on this page", fontWeight = FontWeight.Medium)
                        Text("Import your KakaoTalk .txt file or add messages manually.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(conversation, key = { it.id }) { message ->
                    MessageBubble(profile.name, message)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { pageOffset = (pageOffset - 100).coerceAtLeast(0); refresh() },
                    enabled = pageOffset > 0,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("← Newer") }
                OutlinedButton(
                    onClick = { pageOffset += 100; refresh() },
                    enabled = conversation.size == 100,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Older →") }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun MessageBubble(contactName: String, message: ConversationMemoryStore.Message) {
        val mine = message.sender == "me"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.9f).combinedClickable(
                    onClick = { selectedMessage = message; dialog = DialogKind.ACTIONS },
                    onLongClick = { selectedMessage = message; dialog = DialogKind.ACTIONS }
                ),
                color = if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomEnd = if (mine) 5.dp else 18.dp,
                    bottomStart = if (mine) 18.dp else 5.dp
                )
            ) {
                Column(Modifier.padding(13.dp)) {
                    Text(if (mine) "Me" else contactName,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(5.dp))
                    Text(message.text, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 21.sp)
                    if (message.timestamp.isNotBlank()) {
                        Spacer(Modifier.height(7.dp))
                        Text(message.timestamp, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    @Composable
    private fun MemoryDialogs() {
        val kind = dialog ?: return
        val profileId = openedProfile
        val name = profiles.firstOrNull { it.id == profileId }?.name.orEmpty()
        when (kind) {
            DialogKind.CREATE -> {
                var value by remember(kind) { mutableStateOf("") }
                SimpleInputDialog(
                    title = "Create profile", label = "Profile name", value = value,
                    onValue = { value = it }, positive = "Create",
                    onDismiss = { dialog = null }, onConfirm = {
                        runCatching { store.createProfile(value) }
                            .onSuccess { id -> dialog = null; openProfile(id) }
                            .onFailure { toast(it.message ?: "Could not create profile") }
                    }
                )
            }
            DialogKind.IMPORT -> {
                var value by remember(kind) { mutableStateOf(myExportSender) }
                SimpleInputDialog(
                    title = "Import KakaoTalk chat",
                    label = "Your exact sender name in the export",
                    helper = "For example: Damith Wijekoon. Other senders become the contact. Duplicate messages are skipped.",
                    value = value, onValue = { value = it }, positive = "Choose file",
                    onDismiss = { dialog = null }, onConfirm = {
                        if (profileId != null && value.isNotBlank()) {
                            myExportSender = value.trim()
                            importingProfile = profileId
                            dialog = null
                            pickTextFile.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
                        } else toast("Enter your exported sender name")
                    }
                )
            }
            DialogKind.ADD -> {
                var value by remember(kind) { mutableStateOf("") }
                var sender by remember(kind) { mutableStateOf("contact") }
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    title = { Text("Add message") },
                    text = {
                        Column {
                            Text("Who sent this message?", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = sender == "contact", onClick = { sender = "contact" },
                                    label = { Text(name.ifEmpty { "Contact" }) })
                                FilterChip(selected = sender == "me", onClick = { sender = "me" },
                                    label = { Text("Me") })
                            }
                            OutlinedTextField(value = value, onValueChange = { value = it },
                                label = { Text("Message") }, modifier = Modifier.fillMaxWidth(),
                                minLines = 3, maxLines = 8)
                            TextButton(onClick = {
                                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                value = clip?.primaryClip?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.coerceToText(this@ConversationMemoryActivity)
                                    ?.toString().orEmpty()
                            }) { Text("Paste from clipboard") }
                        }
                    },
                    confirmButton = { TextButton(onClick = {
                        if (profileId != null) runCatching {
                            store.addMessage(profileId, sender, value)
                        }.onSuccess { dialog = null; pageOffset = 0; refresh() }
                            .onFailure { toast(it.message ?: "Could not add message") }
                    }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
                    shape = RoundedCornerShape(24.dp)
                )
            }
            DialogKind.ACTIONS -> {
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    title = { Text("Message options") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { dialog = DialogKind.EDIT },
                                modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Edit, null)
                                Spacer(Modifier.width(8.dp)); Text("Edit message")
                            }
                            OutlinedButton(onClick = { dialog = DialogKind.DELETE_MESSAGE },
                                modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Delete, null)
                                Spacer(Modifier.width(8.dp)); Text("Delete message")
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
                    shape = RoundedCornerShape(24.dp)
                )
            }
            DialogKind.EDIT -> {
                val message = selectedMessage ?: return
                var value by remember(kind, message.id) { mutableStateOf(message.text) }
                SimpleInputDialog(
                    title = "Edit message", label = "Message", value = value,
                    onValue = { value = it }, positive = "Save", multiline = true,
                    onDismiss = { dialog = null }, onConfirm = {
                        if (profileId != null) runCatching {
                            store.updateMessage(message.id, profileId, value)
                        }.onSuccess { dialog = null; refresh() }
                            .onFailure { toast(it.message ?: "Edit failed") }
                    }
                )
            }
            DialogKind.DELETE_MESSAGE -> ConfirmationDialog(
                title = "Delete this message?", detail = "This cannot be undone.",
                onCancel = { dialog = null }, onDelete = {
                    val message = selectedMessage
                    if (message != null && profileId != null) store.deleteMessage(message.id, profileId)
                    dialog = null; refresh()
                }
            )
            DialogKind.CLEAR -> ConfirmationDialog(
                title = "Clear chat history?",
                detail = "All messages in this profile will be deleted. The profile itself will remain.",
                onCancel = { dialog = null }, onDelete = {
                    if (profileId != null) store.clearMessages(profileId)
                    pageOffset = 0; dialog = null; refresh()
                }
            )
            DialogKind.DELETE_PROFILE -> ConfirmationDialog(
                title = "Delete this profile?",
                detail = "The profile and every saved message will be permanently deleted.",
                onCancel = { dialog = null }, onDelete = {
                    if (profileId != null) store.deleteProfile(profileId)
                    dialog = null; goToProfiles()
                }
            )
        }
    }

    @Composable
    private fun SimpleInputDialog(
        title: String,
        label: String,
        value: String,
        onValue: (String) -> Unit,
        positive: String,
        onDismiss: () -> Unit,
        onConfirm: () -> Unit,
        helper: String = "",
        multiline: Boolean = false
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    if (helper.isNotBlank()) {
                        Text(helper, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = value, onValueChange = onValue,
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = !multiline,
                        minLines = if (multiline) 3 else 1,
                        maxLines = if (multiline) 8 else 1
                    )
                }
            },
            confirmButton = { TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(positive) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            shape = RoundedCornerShape(24.dp)
        )
    }

    @Composable
    private fun ConfirmationDialog(
        title: String,
        detail: String,
        onCancel: () -> Unit,
        onDelete: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(title) },
            text = { Text(detail) },
            confirmButton = {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
            shape = RoundedCornerShape(24.dp)
        )
    }
}
