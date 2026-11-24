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

@Composable
internal fun FoxGeminiSmartControls() {
    val context = LocalContext.current
    val store = remember(context) { ApiKeyStore(context) }
    val prefs = remember(context) { context.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE) }
    var stats by remember { mutableStateOf(FoxGeminiUsage.snapshot(context)) }
    var saved by remember { mutableStateOf((0..2).map { !store.load(FoxGeminiUsage.slotName(it)).isNullOrBlank() }) }
    var hedge by remember { mutableStateOf(prefs.getBoolean("fox_gemini_speed_hedge", true)) }
    var cache by remember { mutableStateOf(prefs.getBoolean("fox_gemini_session_cache", false)) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(context) {
        while (true) {
            stats = FoxGeminiUsage.snapshot(context)
            saved = (0..2).map { !store.load(FoxGeminiUsage.slotName(it)).isNullOrBlank() }
            delay(1500L)
        }
    }
    HorizontalDivider()
    Text("Gemini Smart One-tap", style = MaterialTheme.typography.titleMedium)
    Text("Primary key is managed above. Backup keys are used only for invalid credentials, not quota or policy restrictions.")
    Text("Local attempts: ${stats.minute} / ${FoxSmartRules.RPM} last 60 sec · ${stats.daily} / ${FoxSmartRules.RPD} today")
    Text("Local daily headroom: ${(FoxSmartRules.RPD - stats.daily).coerceAtLeast(0)} · resets at Pacific midnight")
    if (stats.blockedMs > 0) Text("Quota cooldown: ${(stats.blockedMs + 999) / 1000}s")
    Text("Counts include sent attempts and reserved/cancelled attempts for one-tap only. Other apps, settings tests and old translation mode are not included. Actual Google quota can be lower; check AI Studio.")
    for (slot in 0..2) {
        val label = if (slot == 0) "Key 1 · Primary" else "Key ${slot + 1} · Backup"
        Text("$label · ${if (saved[slot]) "Saved" else "Not saved"}")
        Text("Today: ${stats.perKey[slot]} attempts · ${stats.successes[slot]} valid responses")
        if (slot > 0) {
            var input by remember(slot) { mutableStateOf("") }
            OutlinedTextField(value = input, onValueChange = { input = it },
                label = { Text("Enter backup API key ${slot + 1}") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = input.isNotBlank(), onClick = {
                    val candidate = input.trim()
                    if ((0..2).any { it != slot && store.load(FoxGeminiUsage.slotName(it)) == candidate }) {
                        notice = "Duplicate key: use a different authorized credential."
                    } else {
                        try {
                            store.save(FoxGeminiUsage.slotName(slot), candidate)
                            input = ""; notice = "Backup saved securely."
                            saved = (0..2).map { !store.load(FoxGeminiUsage.slotName(it)).isNullOrBlank() }
                        } catch (_: Exception) { notice = "Could not securely save key." }
                    }
                }) { Text("Save") }
                OutlinedButton(enabled = saved[slot], onClick = {
                    store.remove(FoxGeminiUsage.slotName(slot)); input = ""
                    saved = (0..2).map { !store.load(FoxGeminiUsage.slotName(it)).isNullOrBlank() }
                    notice = "Backup removed."
                }) { Text("Remove") }
            }
        }
    }
    if (notice.isNotBlank()) Text(notice)
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
