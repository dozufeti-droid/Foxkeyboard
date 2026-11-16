package unicode.sinhala.keyboard.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import unicode.sinhala.keyboard.ai.ApiKeyStore

@Composable
fun AiKeyControls(provider: String) {
    val context = LocalContext.current
    val keyStore = remember(context) {
        ApiKeyStore(context)
    }

    var keyInput by remember(provider) {
        mutableStateOf("")
    }

    var hasKey by remember(provider) {
        mutableStateOf(
            keyStore.load(provider) != null
        )
    }

    var message by remember(provider) {
        mutableStateOf("")
    }

    val supported = Build.VERSION.SDK_INT >= 23

    Text(
        "API Key",
        style = MaterialTheme.typography.titleMedium
    )

    Text(
        if (hasKey) "Key saved for $provider"
        else "No API Key saved for $provider"
    )

    OutlinedTextField(
        value = keyInput,
        onValueChange = { keyInput = it },
        label = { Text("Enter API Key") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            enabled = supported && keyInput.isNotBlank(),
            onClick = {
                runCatching {
                    keyStore.save(provider, keyInput)
                }.onSuccess {
                    keyInput = ""
                    hasKey = true
                    message = "Encrypted key saved"
                }.onFailure {
                    message = "Could not save API Key"
                }
            }
        ) {
            Text(
                if (hasKey) "Replace Key"
                else "Save Key"
            )
        }

        OutlinedButton(
            enabled = hasKey,
            onClick = {
                keyStore.remove(provider)
                keyInput = ""
                hasKey = false
                message = "API Key removed"
            }
        ) {
            Text("Remove Key")
        }
    }

    if (message.isNotEmpty()) {
        Text(message)
    }

    if (!supported) {
        Text("Secure key storage requires Android 6.0+")
    }

    Text(
        "Your key is stored encrypted on this device. " +
        "AI translation is not connected yet."
    )
}
