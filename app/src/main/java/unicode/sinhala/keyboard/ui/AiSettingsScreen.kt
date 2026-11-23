package unicode.sinhala.keyboard.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Single supported online AI translation provider: Google Gemini. */
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val provider = "Google Gemini"
    var model by remember { mutableStateOf(prefs.getString("ai_model_$provider", "") ?: "") }
    var quickEnabled by remember {
        mutableStateOf(prefs.getBoolean("wm_fast_one_tap_enabled", true))
    }
    // Old Groq/Google-Web selections are ignored even before this screen is opened;
    // set the visible preference to match the new default without touching keys/models.
    LaunchedEffect(Unit) {
        prefs.edit()
            .putString("ai_provider", provider)
            .putString("wm_one_tap_provider", "Follow AI Provider")
            .putString("wm_online_fallback", "Off")
            .apply()
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Gemini Translation Settings", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(onClick = onBack) { Text("Back to Settings") }
        HorizontalDivider()
        Text("AI Provider", style = MaterialTheme.typography.titleMedium)
        Text("Google Gemini", color = MaterialTheme.colorScheme.primary,
             style = MaterialTheme.typography.titleLarge)
        Text("All online English, Korean and Sinhala translations use this Gemini model.")
        HorizontalDivider()
        Text("⚡ One-tap Translate", style = MaterialTheme.typography.titleMedium)
        Text("English/Korean replaces the draft. Sinhala translates copied chat messages.")
        Row {
            Switch(checked = quickEnabled, onCheckedChange = { checked ->
                quickEnabled = checked
                prefs.edit().putBoolean("wm_fast_one_tap_enabled", checked).apply()
            })
            Spacer(Modifier.width(12.dp))
            Text(if (quickEnabled) "Fast one-tap ON" else "Legacy two-language preview")
        }
        HorizontalDivider()
        AiKeyControls(provider)
        OutlinedTextField(
            value = model,
            onValueChange = { value ->
                model = value
                prefs.edit().putString("ai_model_$provider", value).apply()
            },
            label = { Text("Gemini Model ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        AiConnectionControls(provider, model)
    }
}
