package unicode.sinhala.keyboard.ui

import android.content.Context
import android.content.Intent
import unicode.sinhala.keyboard.memory.ConversationMemoryActivity
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import ime.suggest.FoxPersonalDictionary
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import unicode.sinhala.com.R
import unicode.sinhala.keyboard.clipboard.ClipboardHistoryManager
import unicode.sinhala.keyboard.ui.components.PreferenceItem
import unicode.sinhala.keyboard.ui.components.SettingsCategory
import unicode.sinhala.keyboard.ui.components.SliderPreference
import unicode.sinhala.keyboard.ui.components.SwitchPreference

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showAiSettings by remember { mutableStateOf(false) }
    var confirmClearLearned by remember { mutableStateOf(false) }
    if (confirmClearLearned) {
        AlertDialog(
            onDismissRequest = { confirmClearLearned = false },
            title = { Text("Clear learned words?") },
            text = { Text("This removes your personal learned words. Built-in words stay available.") },
            confirmButton = { TextButton(onClick = {
                FoxPersonalDictionary.clearAll(context)
                confirmClearLearned = false
                Toast.makeText(context, "Learned words cleared", Toast.LENGTH_SHORT).show()
            }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClearLearned = false }) { Text("Cancel") } }
        )
    }
    if (showAiSettings) {
        AiSettingsScreen(onBack = { showAiSettings = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        SettingsCategory(title = "Languages")

        val layoutEnglish = rememberBooleanPreference(context, "layout_english", true)
        SwitchPreference(
            title = "English",
            checked = layoutEnglish.value,
            onCheckedChange = { layoutEnglish.value = it }
        )

        val layoutWijesekara = rememberBooleanPreference(context, "layout_wijesekara", false)
        SwitchPreference(
            title = stringResource(R.string.wijesekara),
            checked = layoutWijesekara.value,
            onCheckedChange = { layoutWijesekara.value = it }
        )

        val layoutSinglish = rememberBooleanPreference(context, "layout_singlish", true)
        SwitchPreference(
            title = stringResource(R.string.singlish),
            checked = layoutSinglish.value,
            onCheckedChange = { layoutSinglish.value = it }
        )

        SettingsCategory(title = "AI Translation")
        PreferenceItem(
            title = "AI Provider & API Key",
            summary = "Configure translation providers",
            onClick = { showAiSettings = true }
        )
        PreferenceItem(
            title = "Conversation Memory (Profiles)",
            summary = "Import KakaoTalk chat, add/delete messages, turn context on or off",
            onClick = { context.startActivity(Intent(context, ConversationMemoryActivity::class.java)) }
        )

        SettingsCategory(title = "Appearance & Theme")

        val automaticTheme = rememberBooleanPreference(context, "automatic_theme", true)
        SwitchPreference(
            title = "ස්වයංක්‍රීය තේමාව",
            summary = "System Material You dynamic colors",
            checked = automaticTheme.value,
            onCheckedChange = { automaticTheme.value = it }
        )

        val darkTheme = rememberBooleanPreference(context, "dark_theme", false)
        if (!automaticTheme.value) {
            SwitchPreference(
                title = "අඳුරු වර්ණ",
                checked = darkTheme.value,
                onCheckedChange = { darkTheme.value = it }
            )
        }

        val keyBorders = rememberBooleanPreference(context, "key_borders", true)
        SwitchPreference(
            title = "යතුරු මායිම්",
            checked = keyBorders.value,
            onCheckedChange = { keyBorders.value = it }
        )

        SettingsCategory(title = "Keyboard Dimensions (6\"+ Phones)")

        val heightPercentage = rememberIntPreference(context, "height_percentage", 124)
        SliderPreference(
            title = "උස (Height)",
            value = heightPercentage.value,
            unit = "%",
            range = 80f..140f,
            onValueChange = { heightPercentage.value = it }
        )

        val textSize = rememberIntPreference(context, "text_size", 34)
        SliderPreference(
            title = "අකුරුවල ප්‍රමාණය (Text Size)",
            value = textSize.value,
            unit = " sp",
            range = 20f..40f,
            onValueChange = { textSize.value = it }
        )

        SettingsCategory(title = "Gestures & Feedback")

        val swipeToErase = rememberBooleanPreference(context, "swipe_to_erase", true)
        SwitchPreference(
            title = "Swipe Left on Backspace to Erase",
            summary = "Drag backspace key to delete text quickly",
            checked = swipeToErase.value,
            onCheckedChange = { swipeToErase.value = it }
        )

        val swipeToMoveCursor = rememberBooleanPreference(context, "swipe_to_move_cursor", true)
        SwitchPreference(
            title = "Swipe Spacebar to Move Cursor",
            summary = "Slide left/right on spacebar to navigate cursor",
            checked = swipeToMoveCursor.value,
            onCheckedChange = { swipeToMoveCursor.value = it }
        )

        val vibration = rememberBooleanPreference(context, "vibration", false)
        SwitchPreference(
            title = "Haptic Vibration Feedback",
            summary = "Vibrate briefly on key presses",
            checked = vibration.value,
            onCheckedChange = { vibration.value = it }
        )

        // DKEY_TENK2026: privacy controls for user-learned local words.
        SettingsCategory(title = "Smart Suggestions")
        val learning = rememberBooleanPreference(context, "dkey_personal_learning", true)
        SwitchPreference(
            title = "Personal Word Learning",
            summary = "Remember completed words locally (up to 10,000)",
            checked = learning.value,
            onCheckedChange = { learning.value = it }
        )
        PreferenceItem(
            title = "Clear Learned Words",
            summary = "Remove your personal words without affecting the built-in dictionary",
            onClick = { confirmClearLearned = true }
        )

        SettingsCategory(title = "Smart Clipboard")

        var isClipboardEnabled by remember {
            mutableStateOf(ClipboardHistoryManager.isEnabled(context))
        }
        SwitchPreference(
            title = "Clipboard History",
            summary = "Automatically save copied text locally",
            checked = isClipboardEnabled,
            onCheckedChange = { checked ->
                isClipboardEnabled = checked
                ClipboardHistoryManager.setEnabled(context, checked)
            }
        )

        PreferenceItem(
            title = "Clear Clipboard History",
            summary = "Delete all stored copied items",
            onClick = {
                ClipboardHistoryManager.clearAll(context)
                Toast.makeText(context, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun rememberBooleanPreference(context: Context, key: String, defaultValue: Boolean): MutableState<Boolean> {
    val prefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getBoolean(key, defaultValue)) }

    DisposableEffect(key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, k ->
            if (k == key) {
                state.value = sharedPreferences.getBoolean(key, defaultValue)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return remember(state) {
        object : MutableState<Boolean> {
            override var value: Boolean
                get() = state.value
                set(newValue) {
                    state.value = newValue
                    prefs.edit().putBoolean(key, newValue).apply()
                }

            override fun component1() = value
            override fun component2(): (Boolean) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberIntPreference(context: Context, key: String, defaultValue: Int): MutableState<Int> {
    val prefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getInt(key, defaultValue)) }

    DisposableEffect(key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, k ->
            if (k == key) {
                state.value = sharedPreferences.getInt(key, defaultValue)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return remember(state) {
        object : MutableState<Int> {
            override var value: Int
                get() = state.value
                set(newValue) {
                    state.value = newValue
                    prefs.edit().putInt(key, newValue).apply()
                }

            override fun component1() = value
            override fun component2(): (Int) -> Unit = { value = it }
        }
    }
}
