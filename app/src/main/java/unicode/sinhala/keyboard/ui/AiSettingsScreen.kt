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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import unicode.sinhala.keyboard.ai.FoxQuickTranslator
import unicode.sinhala.keyboard.ai.WmTranslationEngine

@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(
            "prefs", Context.MODE_PRIVATE
        )
    }

    var provider by remember {
        mutableStateOf(
            prefs.getString("ai_provider", "GroqCloud")
                ?: "GroqCloud"
        )
    }

    var expanded by remember {
        mutableStateOf(false)
    }

    val providers = listOf(
        "GroqCloud",
        "Google Gemini",
        "OpenRouter",
        "Google Web Translate"
    )

    var model by remember(provider) { mutableStateOf(prefs.getString("ai_model_$provider", "") ?: "") }
    var quickEnabled by remember {
        mutableStateOf(prefs.getBoolean("wm_fast_one_tap_enabled", true))
    }
    var quickEngine by remember {
        mutableStateOf(prefs.getString("wm_one_tap_provider", FoxQuickTranslator.FOLLOW)
            ?: FoxQuickTranslator.FOLLOW)
    }
    var quickExpanded by remember { mutableStateOf(false) }
    var offlineStatus by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val quickEngines = listOf(FoxQuickTranslator.FOLLOW, "GroqCloud", "Google Gemini",
        "Google Web Translate", FoxQuickTranslator.OFFLINE)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "AI Translation Settings",
            style = MaterialTheme.typography.headlineSmall
        )

        OutlinedButton(onClick = onBack) {
            Text("Back to Settings")
        }

        HorizontalDivider()

        Text(
            text = "AI Provider",
            style = MaterialTheme.typography.titleMedium
        )

        Box {
            OutlinedButton(
                onClick = { expanded = true }
            ) {
                Text(provider + "  ▼")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                }
            ) {
                providers.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            provider = name
                            prefs.edit()
                                .putString("ai_provider", name)
                                .apply()
                            expanded = false
                        }
                    )
                }
            }
        }

        HorizontalDivider()
        Text("⚡ One-tap Translate",
            style = MaterialTheme.typography.titleMedium)
        Text("One tap: English/Korean replace the draft; Sinhala translates copied text.")
        Row {
            Switch(checked = quickEnabled, onCheckedChange = { value ->
                quickEnabled = value
                prefs.edit().putBoolean("wm_fast_one_tap_enabled", value).apply()
            })
            Spacer(Modifier.width(12.dp))
            Text(if (quickEnabled) "One-tap ON" else "Old translation mode")
        }
        if (quickEnabled) {
            Text("Translation engine")
            Box {
                OutlinedButton(onClick = { quickExpanded = true }) {
                    Text(quickEngine + "  ▼")
                }
                DropdownMenu(expanded = quickExpanded, onDismissRequest = {
                    quickExpanded = false
                }) {
                    quickEngines.forEach { engine ->
                        DropdownMenuItem(text = { Text(engine) }, onClick = {
                            quickEngine = engine
                            prefs.edit().putString("wm_one_tap_provider", engine).apply()
                            quickExpanded = false
                        })
                    }
                }
            }
            Text("For Sinhala quality, use Gemini or GroqCloud. Offline is EN ↔ KO only.")
            if (quickEngine == FoxQuickTranslator.OFFLINE) {
                Text("Offline is only for English ↔ Korean. Sinhala/Singlish requires an online provider.")
            }
            OutlinedButton(onClick = {
                offlineStatus = "Downloading EN/KO models..."
                coroutineScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            WmTranslationEngine.downloadEnglishKoreanModels()
                        }
                        offlineStatus = "English/Korean models ready for Offline one-tap"
                    } catch (e: Exception) {
                        offlineStatus = "Download failed. Check internet and available storage."
                    }
                }
            }) { Text("↓ Download EN ↔ KO Offline Models") }
            if (offlineStatus.isNotBlank()) Text(offlineStatus)
        }
        HorizontalDivider()
        // Google Web Translate does not need a paid API key or model ID.
        // All existing provider keys/models remain saved and are untouched.
        if (provider == "Google Web Translate") {
            Text("Unofficial Google Web: no key, but may rate-limit or mistranslate Sinhala.")
            var fallback by remember {
                mutableStateOf(prefs.getString("wm_online_fallback", "Off") ?: "Off")
            }
            var fallbackExpanded by remember { mutableStateOf(false) }
            Text("Optional online fallback (sends text to your saved AI provider only if enabled)")
            Box {
                OutlinedButton(onClick = { fallbackExpanded = true }) {
                    Text("Fallback: $fallback  ▼")
                }
                DropdownMenu(expanded = fallbackExpanded, onDismissRequest = {
                    fallbackExpanded = false
                }) {
                    listOf("Off", "GroqCloud", "Google Gemini").forEach { value ->
                        DropdownMenuItem(text = { Text(value) }, onClick = {
                            fallback = value
                            prefs.edit().putString("wm_online_fallback", value).apply()
                            fallbackExpanded = false
                        })
                    }
                }
            }
        } else {
            AiKeyControls(provider)
            OutlinedTextField(
                value = model,
                onValueChange = { value ->
                    model = value
                    prefs.edit().putString("ai_model_$provider", value).apply()
                },
                label = { Text("Model ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            AiConnectionControls(provider, model)
        }


    }
}
