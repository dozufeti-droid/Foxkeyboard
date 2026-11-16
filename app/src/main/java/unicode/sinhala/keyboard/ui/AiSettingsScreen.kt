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
        "OpenRouter"
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
