package unicode.sinhala.keyboard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import unicode.sinhala.com.BuildConfig
import unicode.sinhala.com.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isKeyboardEnabled: Boolean,
    isKeyboardSelected: Boolean,
    onEnableKeyboard: () -> Unit,
    onSelectKeyboard: () -> Unit
) {
    Scaffold(
        topBar = {
            if (isKeyboardEnabled && isKeyboardSelected) {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (isKeyboardEnabled && isKeyboardSelected) {
                SettingsScreen()
            } else {
                SetupScreen(
                    isKeyboardEnabled = isKeyboardEnabled,
                    onEnableKeyboard = onEnableKeyboard,
                    onSelectKeyboard = onSelectKeyboard
                )
            }
        }
    }
}

@Composable
fun SetupScreen(
    isKeyboardEnabled: Boolean,
    onEnableKeyboard: () -> Unit,
    onSelectKeyboard: () -> Unit
) {
    // DKEY_TENK2026: center the LOGO itself, not the tall content column.
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        val compact = maxHeight < 560.dp
        if (compact) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "D Key logo",
                    modifier = Modifier.size(84.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = stringResource(id = R.string.app_name), fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(text = "Version ${BuildConfig.VERSION_NAME}", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (!isKeyboardEnabled)
                        "D Key is not enabled. Please enable it in settings to continue."
                    else "D Key is enabled but not selected. Please select it to start typing.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    if (!isKeyboardEnabled) onEnableKeyboard() else onSelectKeyboard()
                }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text(text = if (!isKeyboardEnabled) "Enable Keyboard" else "Select Keyboard")
                }
            }
        } else {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "D Key logo",
                modifier = Modifier.align(Alignment.Center)
                    .size(if (maxHeight < 680.dp) 96.dp else 120.dp)
            )
            Column(
                modifier = Modifier.align(Alignment.Center)
                    .offset(y = if (maxHeight < 680.dp) 96.dp else 110.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = stringResource(id = R.string.app_name), fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Version ${BuildConfig.VERSION_NAME}", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (!isKeyboardEnabled)
                        "D Key is not enabled. Please enable it in settings to continue."
                    else "D Key is enabled but not selected. Please select it to start typing.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    if (!isKeyboardEnabled) onEnableKeyboard() else onSelectKeyboard()
                }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text(text = if (!isKeyboardEnabled) "Enable Keyboard" else "Select Keyboard")
                }
            }
        }
    }
}
