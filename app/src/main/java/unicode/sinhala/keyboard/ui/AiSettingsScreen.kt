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

        Text("Selected: $provider")
        // Google Web Translate does not need a paid API key or model ID.
        // All existing provider keys/models remain saved and are untouched.
        if (provider == "Google Web Translate") {
            Text("No API key or billing required (unofficial Google web service).")
            Text("Tap English/Korean translation on the keyboard for one-click replacement.")
            Text("Long-press a translation row to open the WM-style Translate tool.")
            Text("This service can stop working or be rate-limited by Google.")
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
