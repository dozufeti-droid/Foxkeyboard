package unicode.sinhala.keyboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
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

        Text("AI Provider: Not configured")
        Text("Model: Not configured")
        Text("API Key: Not configured")

        Text(
            text = "English and Korean AI translation setup",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
