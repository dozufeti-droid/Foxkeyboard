package unicode.sinhala.keyboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import unicode.sinhala.keyboard.ai.ApiKeyStore
import unicode.sinhala.keyboard.ai.AiConnectionTester

@Composable
fun AiConnectionControls(
    provider: String,
    model: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) {
        ApiKeyStore(context)
    }

    var testing by remember { mutableStateOf(false) }
    var result by remember(provider, model) {
        mutableStateOf("")
    }

    Button(
        enabled = !testing && model.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            scope.launch {
                testing = true

                result = withContext(Dispatchers.IO) {
                    runCatching {
                        val key = store.load(provider)

                        if (key.isNullOrBlank()) {
                            "No API Key saved"
                        } else {
                            AiConnectionTester.test(
                                provider,
                                model,
                                key
                            )
                        }
                    }.getOrElse {
                        "Connection test failed"
                    }
                }

                testing = false
            }
        }
    ) {
        Text(
            if (testing) "Testing Connection..."
            else "Test Connection"
        )
    }

    if (result.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(result)
    }
}
