package unicode.sinhala.keyboard.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import unicode.sinhala.keyboard.ai.ApiKeyStore
import unicode.sinhala.keyboard.ai.FoxGeminiUsage
import unicode.sinhala.keyboard.ai.FoxSmartRules

/** All three credentials together, visible even before the model is entered. */
@Composable
internal fun FoxGeminiSmartControls(model: String) {
    val context = LocalContext.current
    val store = remember(context) { ApiKeyStore(context) }
    val prefs = remember(context) { context.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE) }
    val smartModel = model.trim().removePrefix("models/") == "gemini-3.5-flash-lite"
    val inputs = remember { mutableStateListOf("", "", "") }
    var saved by remember { mutableStateOf((0..2).map { !store.load(FoxGeminiUsage.slotName(it)).isNullOrBlank() }) }
    var stats by remember { mutableStateOf(FoxGeminiUsage.snapshot(context)) }
    var notice by remember { mutableStateOf("") }
    var hedge by remember { mutableStateOf(prefs.getBoolean("fox_gemini_speed_hedge", true)) }
    var cache by remember { mutableStateOf(prefs.getBoolean("fox_gemini_session_cache", false)) }
    fun refreshSaved() {
        saved = (0..2).map { !store.load(FoxGeminiUsage.slotName(it)).isNullOrBlank() }
    }
    // Do not decrypt credentials every polling cycle; only refresh counters.
    LaunchedEffect(context) {
        while (true) {
            stats = FoxGeminiUsage.snapshot(context)
            delay(1500L)
        }
    }
    Text("Gemini API Keys", style = MaterialTheme.typography.titleLarge)
    Text("Paste up to 3 authorized keys below. Saved keys are encrypted and stay on this device.")
    for (slot in 0..2) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = if (slot == 0) "API Key 1 · Primary" else "API Key ${slot + 1} · Backup"
            OutlinedTextField(value = inputs[slot], onValueChange = { inputs[slot] = it },
                label = { Text(label) }, placeholder = { Text(if (saved[slot]) "Saved · paste to replace" else "Paste API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("${if (saved[slot]) "Saved" else "Not saved"} · Today: ${stats.perKey[slot]} local attempts · ${stats.successes[slot]} valid",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = inputs[slot].isNotBlank(), onClick = {
                    val candidate = inputs[slot].trim()
                    if ((0..2).any { it != slot && store.load(FoxGeminiUsage.slotName(it)) == candidate }) {
                        notice = "Duplicate key. Use a different authorized credential."
                    } else {
                        try {
                            store.save(FoxGeminiUsage.slotName(slot), candidate)
                            inputs[slot] = ""; refreshSaved(); notice = "Key ${slot + 1} saved securely."
                        } catch (_: Exception) { notice = "Could not securely save key ${slot + 1}." }
                    }
                }) { Text("Save") }
                TextButton(enabled = saved[slot], onClick = {
                    store.remove(FoxGeminiUsage.slotName(slot)); inputs[slot] = ""; refreshSaved()
                    notice = "Key ${slot + 1} removed."
                }) { Text("Remove") }
            }
        }
    }
    Button(enabled = inputs.any { it.isNotBlank() }, modifier = Modifier.fillMaxWidth(), onClick = {
        val desired = (0..2).map { slot -> inputs[slot].trim().ifEmpty { store.load(FoxGeminiUsage.slotName(slot)).orEmpty() } }
        val nonEmpty = desired.filter { it.isNotBlank() }
        if (nonEmpty.distinct().size != nonEmpty.size) notice = "Duplicate keys. No changes saved."
        else {
            var count = 0
            try {
                for (slot in 0..2) if (inputs[slot].isNotBlank()) {
                    store.save(FoxGeminiUsage.slotName(slot), desired[slot]); inputs[slot] = ""; count++
                }
                notice = "$count key(s) saved securely."
            } catch (_: Exception) { notice = "$count key(s) saved; another key could not be saved. Check the remaining fields." }
            refreshSaved()
        }
    }) { Text("Save all entered keys") }
    if (notice.isNotBlank()) Text(notice)
    HorizontalDivider()
    Text("Gemini Smart One-tap", style = MaterialTheme.typography.titleMedium)
    if (!smartModel) Text("Set Model ID above to gemini-3.5-flash-lite to enable the Smart engine. Other models use the existing primary-key engine.")
    Text("Backups are for invalid credentials, not quota or policy restrictions.")
    Text("Local attempts: ${stats.minute} / ${FoxSmartRules.RPM} last 60 sec · ${stats.daily} / ${FoxSmartRules.RPD} today")
    Text("Estimated local daily headroom: ${(FoxSmartRules.RPD - stats.daily).coerceAtLeast(0)} · Pacific midnight reset")
    if (stats.blockedMs > 0) Text("Quota cooldown: ${(stats.blockedMs + 999) / 1000}s")
    Text("Counts include reserved/cancelled one-tap attempts. Other apps and settings tests are not counted. Check AI Studio for actual quota.")
    Row { Switch(checked = hedge, onCheckedChange = {
        hedge = it; prefs.edit().putBoolean("fox_gemini_speed_hedge", it).apply()
    }); Spacer(Modifier.width(12.dp)); Text("Fast hedge (same key, only when slow and quota allows)") }
    Row { Switch(checked = cache, onCheckedChange = {
        cache = it; prefs.edit().putBoolean("fox_gemini_session_cache", it).apply()
    }); Spacer(Modifier.width(12.dp)); Text("Optional short RAM-only translation cache") }
    OutlinedButton(onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/rate-limit"))) }
    }) { Text("Actual Google rate limits ↗") }
}
