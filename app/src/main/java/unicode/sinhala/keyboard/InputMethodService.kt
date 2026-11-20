package unicode.sinhala.keyboard
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import android.widget.PopupMenu
import android.os.Handler
import android.os.Looper

import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import unicode.sinhala.com.R
import unicode.sinhala.keyboard.Maps.keyLabelsLettersEnglish
import unicode.sinhala.keyboard.Maps.keyLabelsLettersEnglishShifted
import unicode.sinhala.keyboard.Maps.keyLabelsNumbers
import unicode.sinhala.keyboard.Maps.keyLabelsSpecialEnglish
import unicode.sinhala.keyboard.Maps.keyLabelsLettersWijesekara
import unicode.sinhala.keyboard.Maps.keyLabelsLettersWijesekaraShifted
import unicode.sinhala.keyboard.Maps.keyLabelsNumbersWijesekara
import unicode.sinhala.keyboard.Maps.keyLabelsSpecialWijesekaraSinhala
import unicode.sinhala.keyboard.Maps.keyLabelsSpecialWijesekaraSinhalaShifted
import unicode.sinhala.keyboard.Maps.singlishMap
import unicode.sinhala.keyboard.swaraSignMap
import unicode.sinhala.keyboard.Maps.symbolsMap
import unicode.sinhala.keyboard.Maps.symbolsMapShifted
import ime.suggest.SuggestionEngine
import ime.suggest.LanguageDetector
import ime.imeui.DebouncedInputHandler
import ime.imeui.TopBarController
import android.widget.TextView
import androidx.compose.ui.semantics.text
import java.text.Normalizer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import unicode.sinhala.keyboard.clipboard.ClipboardHistoryManager

class InputMethodService : android.inputmethodservice.InputMethodService(),
    KeyboardView.ClickListener, KeyboardView.SwipeListener, LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var keyboardView: KeyboardView
    private lateinit var keyboardLayout: KeyboardLayout

    private var caps = false
    private var shift = false


    private var keyboardSymbolsActive = false

    private var mComposing = ""
    private var tComposing = ""


    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    // One-tap English/Korean replacement + copied-message Sinhala translation.
    // Existing keyboard UI, voice and AI providers are not replaced.
    private val foxOneTapController by lazy {
        FoxOneTapController(this, serviceScope,
            currentInfo = { currentInputEditorInfo },
            currentEditor = { currentInputConnection })
    }


    private var aiTranslationJob: Job? = null
    private var aiSinhalaJob: Job? = null
    private var aiSinhalaSource: String? = null
    private var aiSinhalaResult: String? = null

    private var aiTranslationSource: String? = null
    private var aiTranslationEnglish: String? = null
    private var aiTranslationKorean: String? = null

    // An opt-in WM-style tool; does not replace any existing translate rows.
    private var wmTranslatePanel: WmTranslatePanel? = null
    private var wmTranslateJob: Job? = null
    private var wmModelDownloadJob: Job? = null
    private var wmPreviousTranslationRowsVisibility = View.VISIBLE

    private var userInvokedInputMethodPicker = false

    private var suggestionEngine: SuggestionEngine? = null
    private var debouncer: DebouncedInputHandler? = null
    private var topBarController: TopBarController? = null
    private var suggestionTextViews: List<TextView> = emptyList()
    // Android system speech service, not Gemini. User starts and stops microphone explicitly.
    private var foxVoiceRecognizer: SpeechRecognizer? = null
    private var foxVoiceListening = false
    private var foxVoiceLanguageOverride: String? = null
    private val foxVoiceHandler = Handler(Looper.getMainLooper())
    private var foxVoiceSession = 0
    private var foxVoiceFailures = 0

    private var suggestionsEnabled = true

    // Lifecycle and SavedStateRegistry support
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        Log.d("IME", "onCreate called")
        // Local suggestions do not need the old on-disk dictionary assets.
        suggestionEngine = SuggestionEngine(this)
        // DKEY_TENK2026: parse the 10k shipped words off the keyboard UI thread.
        serviceScope.launch(Dispatchers.IO) { suggestionEngine?.initializeIfNeeded() }
        debouncer = DebouncedInputHandler(serviceScope, 700L)

        // Remember the chosen dictation language without changing keyboard language.
        foxVoiceLanguageOverride = getSharedPreferences("fox_voice_ui", android.content.Context.MODE_PRIVATE)
            .getString("language", null)?.takeIf { it in listOf("si-LK", "en-US", "ko-KR") }
        EmojiData.loadRecentEmojis(this)
        registerClipboardListener()
    }


    override fun onDestroy() {
        finishFoxVoiceRecognition()
        unregisterClipboardListener()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
        serviceJob.cancel()
        suggestionEngine?.flushTypedWords()
        Log.d("IME", "onDestroy called")
    }

    private var clipboardManager: ClipboardManager? = null
    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    private fun registerClipboardListener() {
        if (clipboardManager == null) {
            clipboardManager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        }
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
        } catch (e: Exception) {
            Log.e("IME", "Failed to add primary clip listener", e)
        }
    }

    private fun unregisterClipboardListener() {
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
        } catch (e: Exception) {
            Log.e("IME", "Failed to remove primary clip listener", e)
        }
    }

    private fun onClipboardChanged() {
        if (!ClipboardHistoryManager.isEnabled(this)) return
        val clipData = clipboardManager?.primaryClip ?: return
        if (clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).coerceToText(this)?.toString()
            if (!text.isNullOrBlank()) {
                val newClip = ClipboardHistoryManager.addClip(this, text)
                if (newClip != null) {
                    showClipboardChip(newClip.text)
                }
            }
        }
    }

    private fun checkAndShowRecentClipboardChip() {
        val recent = ClipboardHistoryManager.getLatestClipIfRecent(this, 60_000L)
        if (recent != null) {
            showClipboardChip(recent.text)
        } else {
            topBarController?.hideClipboardSuggestion()
        }
    }


    private fun onSinhalaTranslateTap() {
        if (getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("wm_fast_one_tap_enabled", true)) {
            foxOneTapController.translateClipboardToSinhala(
                keyboardView.aiSinhalaButtonView,
                keyboardView.aiSinhalaPanelView,
                keyboardView.aiSinhalaTextView
            )
            return
        }
        val panel = keyboardView.aiSinhalaPanelView
        val output = keyboardView.aiSinhalaTextView
        val button = keyboardView.aiSinhalaButtonView

        // Only read copied plain text after the user taps.
        val source = try {
            clipboardManager?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
                ?.trim()
        } catch (_: Exception) {
            null
        }

        if (source.isNullOrBlank()) {
            panel.visibility = View.VISIBLE
            output.text = "Copy an English or Korean message first."
            button.text = "🇱🇰 Sinhala Translate"
            return
        }

        if (source.length > 16000) {
            panel.visibility = View.VISIBLE
            output.text = "Message is too long (maximum 16000 characters)."
            button.text = "🇱🇰 Sinhala Translate"
            return
        }

        if (source == aiSinhalaSource && aiSinhalaResult != null) {
            val show = panel.visibility != View.VISIBLE
            panel.visibility = if (show) View.VISIBLE else View.GONE
            button.text = if (show) {
                "🇱🇰 Hide translation"
            } else {
                "🇱🇰 Show translation"
            }
            return
        }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val provider = prefs.getString("ai_provider", "GroqCloud")
            ?: "GroqCloud"
        val model = prefs.getString("ai_model_$provider", "")
            ?.trim().orEmpty()

        if (provider == "Google Web Translate" &&
            source.length > unicode.sinhala.keyboard.ai.GoogleWebTranslateProvider.MAX_CHARS) {
            panel.visibility = View.VISIBLE
            output.text = "Google Web: message too long (2500 characters maximum)."
            return
        }
        if (provider != "Google Web Translate" &&
            (provider !in setOf("GroqCloud", "Google Gemini") || model.isBlank())) {
            panel.visibility = View.VISIBLE
            output.text = "Select GroqCloud or Google Gemini and set Model ID in AI Settings."
            return
        }

        aiSinhalaJob?.cancel()
        aiSinhalaSource = source
        aiSinhalaResult = null

        panel.visibility = View.VISIBLE
        output.text = "Translating to Sinhala..."
        button.text = "🇱🇰 Translating..."

        aiSinhalaJob = serviceScope.launch {
            try {
                val translation = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (provider == "Google Web Translate") {
                        unicode.sinhala.keyboard.ai.GoogleWebTranslateProvider.translate(
                            source, target = "si", source = "auto"
                        )
                    } else {
                        val key = unicode.sinhala.keyboard.ai.ApiKeyStore(
                            this@InputMethodService
                        ).load(provider) ?: error("No saved API Key")

                        unicode.sinhala.keyboard.ai.AiSinhalaTranslator.translate(
                            source, model, key, provider
                        )
                    }
                }

                if (aiSinhalaSource != source) return@launch

                aiSinhalaResult = translation
                output.text = translation
                panel.visibility = View.VISIBLE
                button.text = "🇱🇰 Hide translation"

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (aiSinhalaSource != source) return@launch
                val reason = e.message.orEmpty()
                output.text = when {
                    provider == "Google Web Translate" ->
                        "Google Web translation unavailable. Retry or select an AI provider."
                    reason.contains("rate limit", true) || reason.contains("HTTP 429") ->
                        "Gemini free limit reached. Try later."
                    reason.contains("server error", true) || reason.contains("HTTP 50") ->
                        "Gemini is busy. Please retry shortly."
                    reason.contains("timed out", true) || reason.contains("connection", true) ->
                        "Connection failed. Check internet and retry."
                    reason.contains("API Key", true) || reason.contains("permission", true) ->
                        "Check saved API Key and model settings."
                    else -> "Translation failed. Please try again."
                }
                button.text = "🇱🇰 Sinhala Translate"
            }
        }
    }

    private fun showClipboardChip(text: String) {
        topBarController?.showClipboardSuggestion(text) { pastedText ->
            specialClick(pastedText)
        }
    }

    private fun commitWijesekaraChar(char: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""

        val composed = when (before + char) {
            "අැ" -> "ඇ"
            "අා" -> "ආ"
            "එ්" -> "ඒ"
            "එෙ" -> "ඓ"
            "ෙඑ" -> "ඓ"
            "ඔ්" -> "ඕ"
            "උ්" -> "ඌ"


            // Consonant + 'e' sign combinations


            else -> null
        }

        if (composed != null) {
            // If we have a composition, delete the previous character and commit the new one.
            ic.deleteSurroundingText(1, 0)
            ic.commitText(composed, 1)
        } else {
            // Otherwise, just commit the character the user typed.
            ic.commitText(char, 1)
        }
    }



    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        if (::keyboardView.isInitialized) return keyboardView

        try {
            keyboardView = KeyboardView(
                this,
                this,
                this,
                Prefs.getRowHeight(this),
                Prefs.getDarkTheme(this),
                Prefs.getKeyBorders(this),
                Prefs.getSwipeToErase(this),
                Prefs.getSwipeToMoveCursor(this),
                Prefs.getTextSize(this)
            )

            keyboardLayout = Prefs.getSelectedLayout(this)
            setKeyboardLayout(keyboardLayout)

            // Setup top bar controller with views from keyboardView and pass dark theme preference
            topBarController = TopBarController(
                keyboardView.suggestionContainerView,
                keyboardView.emojiButtonView,
                keyboardView.clipboardButtonView,
                Prefs.getDarkTheme(this)
            )
            suggestionTextViews = keyboardView.getSuggestionTextViews()
            keyboardView.voiceContainerView.setOnClickListener { startOrStopFoxVoiceTyping() }
            // DKEY_TENK2026: no separate language button; hold the mic to choose.
            keyboardView.voiceContainerView.setOnLongClickListener {
                showFoxVoiceLanguageMenu()
                true
            }
            refreshFoxVoiceBadge()
            keyboardView.aiSinhalaButtonView.setOnClickListener {
                onSinhalaTranslateTap()
            }
            // Long press = the additional WM-style Translate tool. Short
            // taps keep their original Groq/Gemini/Google V2 behaviour.
            keyboardView.aiSinhalaButtonView.setOnLongClickListener {
                openWmTranslationTool()
                true
            }

            
            keyboardView.aiEnglishView.setOnClickListener {
                onAiTranslationRowTap(true)
            }
            keyboardView.aiKoreanView.setOnClickListener {
                onAiTranslationRowTap(false)
            }
            keyboardView.aiEnglishView.setOnLongClickListener {
                openWmTranslationTool()
                true
            }
            keyboardView.aiKoreanView.setOnLongClickListener {
                openWmTranslationTool()
                true
            }

            return keyboardView
        } catch (t: Throwable) {
            Log.e("IME", "Keyboard view creation failed, providing safe fallback view", t)

            // Provide a safe, minimal fallback view so IME does not crash.
            val fallback = View(this)
            try {
                // Attempt to set a sensible background color from theme attr if available, else default to white/black depending on night mode
                val typedValue = android.util.TypedValue()
                val theme = theme
                // First try app-specific fox_background (safe, non-colliding), then fall back to platform background attr
                var got = theme.resolveAttribute(R.attr.fox_background, typedValue, true)
                if (!got) {
                    try {
                        got = theme.resolveAttribute(android.R.attr.background, typedValue, true)
                    } catch (_: Exception) {
                        // some devices/themes might not expose android attr; ignore and fallback below
                        got = false
                    }
                }
                if (got) {
                    if (typedValue.resourceId != 0) {
                        fallback.setBackgroundResource(typedValue.resourceId)
                    } else {
                        try {
                            fallback.setBackgroundColor(typedValue.data)
                        } catch (e: Exception) {
                            fallback.setBackgroundColor(if (Prefs.getDarkTheme(this)) 0xFF263238.toInt() else 0xFFECEFF1.toInt())
                        }
                    }
                } else {
                    fallback.setBackgroundColor(if (Prefs.getDarkTheme(this)) 0xFF263238.toInt() else 0xFFECEFF1.toInt())
                }
            } catch (inner: Throwable) {
                Log.e("IME", "Failed to set fallback background", inner)
            }

            // Return fallback view to avoid crashing the IME.
            return fallback
        }
    }

     override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
         super.onStartInputView(info, restarting)
        closeWmTranslationTool()
        foxOneTapController.cancel()
        aiSinhalaJob?.cancel()
        aiSinhalaSource = null
        aiSinhalaResult = null
        if (::keyboardView.isInitialized) {
            keyboardView.aiSinhalaPanelView.visibility = View.GONE
            keyboardView.aiSinhalaButtonView.text = "🇱🇰 Sinhala Translate"
        }

        aiTranslationJob?.cancel()
        aiTranslationSource = null
        aiTranslationEnglish = null
        aiTranslationKorean = null
        if (::keyboardView.isInitialized) {
            keyboardView.aiEnglishView.text = "🇬🇧 English translation"
            keyboardView.aiKoreanView.text = "🇰🇷 Korean translation"
        }
         lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
         Log.d("IME", "onStartInputView called restarting=$restarting info=")

         val desired = Prefs.getSelectedLayout(this)
         if (!::keyboardView.isInitialized) {

             onCreateInputView()
         }


        if (userInvokedInputMethodPicker) {

            userInvokedInputMethodPicker = false
            Log.d("IME", "Skipping automatic keyboard layout change after input method picker")
        } else {
            setKeyboardLayout(desired)
        }


        try {
            if (::keyboardView.isInitialized) {
                keyboardView.updateDimensions(
                    Prefs.getRowHeight(this),
                    Prefs.getTextSize(this)
                )
            }
            updateKeyboard()
        } catch (t: Throwable) {
            Log.e("IME", "updateKeyboard failed in onStartInputView", t)
        }

        if (isNumericInputType(info)) {
            keyboardView.toggleNumpadView(true)
        } else {
            keyboardView.toggleNumpadView(false)
        }

        checkAndShowRecentClipboardChip()


        if (restarting || info == null || currentInputConnection == null) {
            resetKeyboardState()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        // Never dictate into another field after focus changes.
        if (foxVoiceListening) finishFoxVoiceRecognition()
        super.onStartInput(attribute, restarting)
        Log.d("IME", "onStartInput called restarting=$restarting")

        if (currentInputConnection == null && restarting) {
            resetKeyboardState()
        }


        try {
            updateKeyboard()
        } catch (t: Throwable) {
            Log.e("IME", "updateKeyboard failed in onStartInput", t)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        finishFoxVoiceRecognition()
        closeWmTranslationTool()
        foxOneTapController.cancel()
        super.onFinishInputView(finishingInput)
        aiSinhalaJob?.cancel()
        aiSinhalaSource = null
        aiSinhalaResult = null
        if (::keyboardView.isInitialized) {
            keyboardView.aiSinhalaPanelView.visibility = View.GONE
        }

        aiTranslationJob?.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        Log.d("IME", "onFinishInputView called finishingInput=$finishingInput")

        if (finishingInput) {
            resetKeyboardState()
        }
    }


    private fun resetKeyboardState() {
        mComposing = ""
        tComposing = ""
    }

    override fun onEvaluateFullscreenMode(): Boolean {

        return false
    }

    // FoxProVoiceLearning2026: learn completed words only when the editor permits it.
    private fun foxCanLearnFromEditor(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return false
        if ((info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI
        )
    }

    private fun foxShouldLearnFromEditor(): Boolean =
        foxCanLearnFromEditor() && getSharedPreferences("prefs", MODE_PRIVATE)
            .getBoolean("dkey_personal_learning", true)

    private fun rememberFoxCompletedWord() {
        if (!foxShouldLearnFromEditor()) return
        val before = currentInputConnection?.getTextBeforeCursor(75, 0)
            ?.toString().orEmpty()
        val word = before.takeLastWhile { ch ->
            val category = Character.getType(ch)
            Character.isLetter(ch) || category == Character.NON_SPACING_MARK.toInt() ||
                category == Character.COMBINING_SPACING_MARK.toInt()
        }
        if (word.isNotBlank()) suggestionEngine?.recordTypedWord(word)
    }

    // Helper to request suggestions for a token
    private fun requestSuggestionsForToken(token: String) {
        // Do not auto-hide suggestions once opened. Only disable suggestions for password fields or when disabled in prefs.
        if (!suggestionsEnabled || !foxCanLearnFromEditor()) {
            topBarController?.showNormal()
            return
        }
        // Do not auto-hide suggestions on idle - only hide on explicit actions (space/action)
        debouncer?.onTyping(token, onTypingImmediate = { t ->
            // Pure local lookup (<=500 words): avoid scheduling a coroutine per keypress.
            try {
                val sList = suggestionEngine?.suggest(Normalizer.normalize(t, Normalizer.Form.NFC), 3) ?: emptyList<String>()
                if (sList.isNotEmpty()) {
                    topBarController?.showSuggestions(sList, suggestionTextViews) { suggestion ->
                        onSuggestionClicked(suggestion)
                    }
                } else {
                    // Never leave an unrelated/stale dictionary suggestion visible.
                    topBarController?.showNormal()
                }
            } catch (_: Exception) {
                topBarController?.showNormal()
            }
        }, onIdle = null)
    }

    override fun letterOrSymbolClick(tag: String) {
        wmTranslatePanel?.let { panel ->
            panel.appendInput(tag)
            vibrate()
            return
        }
        when {
            keyboardLayout == KeyboardLayout.SINGLISH && !keyboardSymbolsActive -> {
                singlishInput(tag)
            }

            keyboardLayout == KeyboardLayout.WIJESEKARA && !keyboardSymbolsActive -> {
                commitWijesekaraChar(tag)
            }

            else -> {
                currentInputConnection?.commitText(tag, 1)
            }
        }

        vibrate()

        try {
            val textBefore = currentInputConnection
                ?.getTextBeforeCursor(50, 0)
                ?.toString() ?: ""
            val token = textBefore.takeLastWhile { !it.isWhitespace() }
            requestSuggestionsForToken(token)
        } catch (_: Throwable) {}

        checkAutoUnshift()
    }




    // Mic: tap to start/stop, long-press to choose language.
    private fun foxSpeechLanguage(): String = foxVoiceLanguageOverride ?: when (keyboardLayout) {
        KeyboardLayout.ENGLISH -> "en-US"
        else -> "si-LK"
    }

    private fun refreshFoxVoiceBadge() {
        if (!::keyboardView.isInitialized) return
        val language = foxSpeechLanguage()
        val label = when (language) {
            "si-LK" -> "සි"
            "ko-KR" -> "한"
            else -> "EN"
        }
        val name = when (language) {
            "si-LK" -> "Sinhala"
            "ko-KR" -> "Korean"
            else -> "English"
        }
        keyboardView.updateVoiceLanguageBadge(label, name)
    }
    private fun showFoxVoiceLanguageMenu() {
        if (!::keyboardView.isInitialized) return
        try {
            val menu = PopupMenu(keyboardView.context, keyboardView.voiceContainerView)
            menu.menu.add(0, 1, 0, "🇱🇰 Sinhala")
            menu.menu.add(0, 2, 1, "🇬🇧 English")
            menu.menu.add(0, 3, 2, "🇰🇷 Korean")
            menu.setOnMenuItemClickListener { item ->
                val language = when (item.itemId) {
                    1 -> "si-LK"
                    2 -> "en-US"
                    3 -> "ko-KR"
                    else -> return@setOnMenuItemClickListener false
                }
                val resumeDictation = foxVoiceListening
                if (resumeDictation) finishFoxVoiceRecognition()
                foxVoiceLanguageOverride = language
                getSharedPreferences("fox_voice_ui", android.content.Context.MODE_PRIVATE)
                    .edit().putString("language", language).apply()
                refreshFoxVoiceBadge()
                if (resumeDictation) startOrStopFoxVoiceTyping()
                true
            }
            menu.show()
        } catch (error: Exception) {
            Log.e("FoxVoice", "Unable to show voice language popup", error)
            Toast.makeText(this, "Could not open language menu", Toast.LENGTH_SHORT).show()
        }
    }
    // FoxProVoiceLearning2026: mic tap alone controls the dictation session.
    // Android recognizers stop at natural pauses. Start a fresh recognition round
    // after each result / silence until the user explicitly taps the mic to stop.
    private fun finishFoxVoiceRecognition() {
        foxVoiceListening = false
        foxVoiceSession += 1 // invalidate callbacks from earlier recognizers
        foxVoiceHandler.removeCallbacksAndMessages(null)
        if (::keyboardView.isInitialized) keyboardView.setVoiceListening(false)
        val old = foxVoiceRecognizer
        foxVoiceRecognizer = null
        try { old?.cancel() } catch (_: Exception) { }
        try { old?.destroy() } catch (_: Exception) { }
    }

    private fun foxCanDictate(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return cls == InputType.TYPE_CLASS_TEXT && variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        ) && currentInputConnection != null
    }

    private fun startOrStopFoxVoiceTyping() {
        if (foxVoiceListening) {
            finishFoxVoiceRecognition() // stop immediately; don't keep the mic open
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            try {
                startActivity(Intent(this, VoicePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.e("FoxVoice", "Could not request microphone permission", e)
                Toast.makeText(this, "Enable microphone permission", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!foxCanDictate()) {
            Toast.makeText(this, "Voice typing unavailable in this field", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "No speech recognition service installed", Toast.LENGTH_LONG).show()
            return
        }
        finishFoxVoiceRecognition()
        foxVoiceListening = true
        foxVoiceSession += 1
        foxVoiceFailures = 0
        if (::keyboardView.isInitialized) keyboardView.setVoiceListening(true)
        beginFoxVoiceRound(foxVoiceSession)
    }

    private fun scheduleFoxVoiceRound(session: Int, delay: Long) {
        if (!foxVoiceListening || session != foxVoiceSession) return
        foxVoiceHandler.postDelayed({
            if (!foxVoiceListening || session != foxVoiceSession) return@postDelayed
            val old = foxVoiceRecognizer
            foxVoiceRecognizer = null
            try { old?.destroy() } catch (_: Exception) { }
            beginFoxVoiceRound(session)
        }, delay)
    }

    private fun beginFoxVoiceRound(session: Int) {
        if (!foxVoiceListening || session != foxVoiceSession) return
        if (!isInputViewShown || !foxCanDictate() ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishFoxVoiceRecognition()
            return
        }
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            foxVoiceRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { }
                override fun onBeginningOfSpeech() { }
                override fun onRmsChanged(rmsdB: Float) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { }
                override fun onPartialResults(partialResults: Bundle?) { }
                override fun onEvent(eventType: Int, params: Bundle?) { }
                override fun onError(error: Int) {
                    if (!foxVoiceListening || session != foxVoiceSession ||
                        foxVoiceRecognizer !== recognizer) return
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            foxVoiceFailures = 0
                            scheduleFoxVoiceRound(session, 800L)
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_SERVER -> {
                            foxVoiceFailures += 1
                            if (foxVoiceFailures <= 3) {
                                scheduleFoxVoiceRound(session, 1200L * foxVoiceFailures)
                            } else {
                                finishFoxVoiceRecognition()
                                Toast.makeText(this@InputMethodService,
                                    "Voice service unavailable. Try again.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        else -> {
                            finishFoxVoiceRecognition()
                            Toast.makeText(this@InputMethodService,
                                "Voice typing stopped ($error)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                override fun onResults(results: Bundle?) {
                    if (!foxVoiceListening || session != foxVoiceSession ||
                        foxVoiceRecognizer !== recognizer) return
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (spoken.isNotBlank() && foxCanDictate()) {
                        currentInputConnection?.commitText("$spoken ", 1)
                    }
                    foxVoiceFailures = 0
                    scheduleFoxVoiceRound(session, 420L)
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, foxSpeechLanguage())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e("FoxVoice", "Could not restart speech recognition", e)
            finishFoxVoiceRecognition()
            Toast.makeText(this, "Voice typing unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens the new translator above the SAME keyboard without changing
     * existing keys, layout, typing preferences or API credentials.
     */
    private fun openWmTranslationTool() {
        if (!::keyboardView.isInitialized) return
        if (wmTranslatePanel != null) return
        val info = currentInputEditorInfo ?: return
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT ||
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 ||
            isInPasswordField()) {
            Toast.makeText(this, "Translate is unavailable in this field", Toast.LENGTH_SHORT).show()
            return
        }
        val editor = currentInputConnection ?: return
        val selected = editor.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val original = selected ?: editor.getTextBeforeCursor(2500, 0)
            ?.toString()?.substringAfterLast('\n').orEmpty()

        lateinit var panel: WmTranslatePanel
        panel = WmTranslatePanel(
            context = this,
            initialText = original,
            onTranslate = { request ->
                wmTranslateJob?.cancel()
                wmTranslateJob = serviceScope.launch {
                    try {
                        val translated = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            unicode.sinhala.keyboard.ai.WmTranslationEngine.translate(
                                this@InputMethodService, request
                            )
                        }
                        if (wmTranslatePanel === panel) {
                            panel.showTranslated(request, translated.text, translated.via)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (wmTranslatePanel === panel) {
                            val message = when {
                                e.message?.contains("429") == true ->
                                    "Google rate limited. Switch to an enabled AI fallback."
                                e.message?.contains("model", true) == true ->
                                    e.message.orEmpty()
                                e.message?.contains("offline", true) == true ->
                                    e.message.orEmpty()
                                else -> "Translation unavailable: ${e.message.orEmpty().take(65)}"
                            }
                            panel.showError(request, message)
                        }
                    }
                }
            },
            onDownload = {
                wmModelDownloadJob?.cancel()
                panel.showDownloadStatus("Downloading English/Korean offline models...")
                wmModelDownloadJob = serviceScope.launch {
                    try {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            unicode.sinhala.keyboard.ai.WmTranslationEngine.downloadEnglishKoreanModels()
                        }
                        if (wmTranslatePanel === panel) {
                            panel.showDownloadStatus("EN/Korean offline models ready. Select Automatic.")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (wmTranslatePanel === panel) {
                            panel.showDownloadStatus("Model download failed. Check internet/storage.")
                        }
                    }
                }
            },
            onReplace = { translated, currentQuery ->
                // Never replace a changed draft or text in a different editor.
                if (currentQuery != original || currentInputEditorInfo !== info ||
                    currentInputConnection !== editor || original.isEmpty()) {
                    false
                } else {
                    val stillOriginal = if (selected != null) {
                        editor.getSelectedText(0)?.toString() == original
                    } else {
                        editor.getTextBeforeCursor(original.length, 0)?.toString() == original
                    }
                    if (!stillOriginal) false
                    else {
                        editor.beginBatchEdit()
                        try {
                            if (selected == null) editor.deleteSurroundingText(original.length, 0)
                            editor.commitText(translated, 1)
                        } finally {
                            editor.endBatchEdit()
                        }
                        true
                    }
                }
            },
            onInsert = { translated ->
                // commitText would REPLACE a selected range. Insert must never
                // silently destroy it. Ask user to deselect first.
                if (currentInputEditorInfo !== info || currentInputConnection !== editor ||
                    editor.getSelectedText(0)?.isNotEmpty() == true) {
                    false
                } else {
                    editor.commitText(translated, 1)
                    true
                }
            },
            onClose = { closeWmTranslationTool() }
        )
        wmTranslatePanel = panel
        val existingRows = keyboardView.findViewById<View>(R.id.ai_translation_rows)
        wmPreviousTranslationRowsVisibility = existingRows?.visibility ?: View.VISIBLE
        existingRows?.visibility = View.GONE
        keyboardView.addView(
            panel, 0,
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun closeWmTranslationTool() {
        wmTranslateJob?.cancel()
        wmTranslateJob = null
        wmModelDownloadJob?.cancel()
        wmModelDownloadJob = null
        val panel = wmTranslatePanel ?: return
        wmTranslatePanel = null
        panel.dispose()
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        if (::keyboardView.isInitialized) {
            keyboardView.findViewById<View>(R.id.ai_translation_rows)?.visibility =
                wmPreviousTranslationRowsVisibility
        }
    }

    /**
     * One click while Google Web Translate is selected: translate then replace
     * the selected text, or the last line before the cursor. Never auto-send.
     * Typed text goes to Google's unofficial translation endpoint ONLY on tap.
     */
    private fun onGoogleWebTranslationTap(target: String) {
        val info = currentInputEditorInfo ?: return
        val editor = currentInputConnection ?: return
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val allowed = variation == InputType.TYPE_TEXT_VARIATION_NORMAL ||
            variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE ||
            variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        val row = if (target == "en") keyboardView.aiEnglishView
            else keyboardView.aiKoreanView
        val flag = if (target == "en") "🇬🇧" else "🇰🇷"
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT ||
            !allowed ||
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
            row.text = "$flag Unavailable in this field"
            return
        }
        val selectedText = editor.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val sourceText = selectedText ?: editor.getTextBeforeCursor(
            unicode.sinhala.keyboard.ai.GoogleWebTranslateProvider.MAX_CHARS + 1, 0
        )?.toString()?.substringAfterLast('\n').orEmpty()
        val trimmedText = sourceText.trim()
        if (trimmedText.isEmpty()) {
            row.text = "$flag Enter a message first"
            return
        }
        if (sourceText.length > unicode.sinhala.keyboard.ai.GoogleWebTranslateProvider.MAX_CHARS) {
            row.text = "$flag Message too long for Google Web"
            return
        }
        aiTranslationJob?.cancel()
        aiTranslationSource = null
        aiTranslationEnglish = null
        aiTranslationKorean = null
        row.text = "$flag Translating..."
        aiTranslationJob = serviceScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    unicode.sinhala.keyboard.ai.GoogleWebTranslateProvider.translate(
                        trimmedText, target = target, source = "auto"
                    )
                }
                // Never replace a message if the app, editor or draft changed.
                if (currentInputEditorInfo !== info || currentInputConnection !== editor ||
                    getSharedPreferences("prefs", MODE_PRIVATE)
                        .getString("ai_provider", "GroqCloud") != "Google Web Translate") {
                    return@launch
                }
                val active = currentInputConnection ?: return@launch
                if (selectedText != null) {
                    if (active.getSelectedText(0)?.toString() != selectedText) {
                        row.text = "$flag Text changed — translate again"
                        return@launch
                    }
                } else {
                    val before = active.getTextBeforeCursor(sourceText.length, 0)
                        ?.toString().orEmpty()
                    if (before != sourceText) {
                        row.text = "$flag Text changed — translate again"
                        return@launch
                    }
                }
                active.beginBatchEdit()
                try {
                    if (selectedText == null) {
                        active.deleteSurroundingText(sourceText.length, 0)
                    }
                    active.commitText(result, 1)
                } finally {
                    active.endBatchEdit()
                }
                row.text = "$flag ${result.take(75)}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentInputEditorInfo !== info) return@launch
                row.text = when {
                    e.message?.contains("429") == true -> "$flag Google rate limit — try later"
                    e is java.net.SocketTimeoutException -> "$flag Google timed out — retry"
                    else -> "$flag Google Web unavailable — try AI provider"
                }
            }
        }
    }

    private fun onAiTranslationRowTap(english: Boolean) {
        if (getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("wm_fast_one_tap_enabled", true)) {
            foxOneTapController.translateDraft(
                if (english) "en" else "ko",
                if (english) keyboardView.aiEnglishView else keyboardView.aiKoreanView
            )
            return
        }
        // Only the newly selected Google provider uses the one-click flow.
        // GroqCloud, Gemini, OpenRouter and saved API keys are unaffected.
        val activeProvider = getSharedPreferences("prefs", MODE_PRIVATE)
            .getString("ai_provider", "GroqCloud")
        if (activeProvider == "Google Web Translate") {
            onGoogleWebTranslationTap(if (english) "en" else "ko")
            return
        }
        val source = aiTranslationSource
        val translated = if (english) {
            aiTranslationEnglish
        } else {
            aiTranslationKorean
        }

        if (source == null || translated == null) {
            requestAiTranslation()
            return
        }

        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()
        val selectionMatches = selected?.trim() == source

        if (!selectionMatches) {
            val before = ic.getTextBeforeCursor(500, 0)
                ?.toString().orEmpty()

            if (!before.endsWith(source)) {
                requestAiTranslation()
                return
            }
        }

        ic.beginBatchEdit()
        try {
            if (!selectionMatches) {
                ic.deleteSurroundingText(source.length, 0)
            }
            ic.commitText(translated, 1)
        } finally {
            ic.endBatchEdit()
        }

        aiTranslationSource = null
        aiTranslationEnglish = null
        aiTranslationKorean = null

        keyboardView.aiEnglishView.text =
            "🇬🇧 Tap to translate"
        keyboardView.aiKoreanView.text =
            "🇰🇷 Tap to translate"
    }

    private fun requestAiTranslation() {
        aiTranslationSource = null
        aiTranslationEnglish = null
        aiTranslationKorean = null

        val info = currentInputEditorInfo ?: return
        val inputType = info.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        val allowed = variation == InputType.TYPE_TEXT_VARIATION_NORMAL ||
            variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE ||
            variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT

        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT ||
            !allowed ||
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
            keyboardView.aiEnglishView.text = "English: Unavailable in this field"
            keyboardView.aiKoreanView.text = "Korean: Unavailable in this field"
            return
        }

        val selected = currentInputConnection
            ?.getSelectedText(0)?.toString()

        val source = (selected?.takeIf { it.isNotBlank() }
            ?: currentInputConnection
                ?.getTextBeforeCursor(500, 0)
                ?.toString()
                ?.substringAfterLast('\n'))
            ?.trim()

        if (source.isNullOrBlank()) {
            keyboardView.aiEnglishView.text = "English: Enter some text"
            keyboardView.aiKoreanView.text = "Korean: Enter some text"
            return
        }
        // Fox AI quality repair v4: do not invent an ending for unfinished Singlish.
        if (unicode.sinhala.keyboard.ai.SinglishTranslationHints.isUnfinished(source)) {
            keyboardView.aiEnglishView.text = "🇬🇧 Finish the sentence, then translate"
            keyboardView.aiKoreanView.text = "🇰🇷 Finish the sentence, then translate"
            return
        }


        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val provider = prefs.getString("ai_provider", "GroqCloud")
            ?: "GroqCloud"

        if (provider !in setOf("GroqCloud", "Google Gemini")) {
            keyboardView.aiEnglishView.text = "English: Select GroqCloud or Gemini"
            keyboardView.aiKoreanView.text = "Korean: Select GroqCloud or Gemini"
            return
        }

        val model = prefs.getString("ai_model_$provider", "")
            ?.trim().orEmpty()

        if (model.isBlank()) {
            keyboardView.aiEnglishView.text = "English: Set Model ID"
            keyboardView.aiKoreanView.text = "Korean: Set Model ID"
            return
        }

        aiTranslationJob?.cancel()
        keyboardView.aiEnglishView.text = "English: Translating..."
        keyboardView.aiKoreanView.text = "Korean: Translating..."

        aiTranslationJob = serviceScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val key = unicode.sinhala.keyboard.ai.ApiKeyStore(
                        this@InputMethodService
                    ).load(provider) ?: error("No saved API Key")

                    unicode.sinhala.keyboard.ai.AiTranslator.translate(
                        source, model, key, provider
                    )
                }

                if (currentInputEditorInfo !== info) return@launch

                // The user may have edited the message while the network call ran.
                val latestDraft = (
                    currentInputConnection?.getSelectedText(0)?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: currentInputConnection?.getTextBeforeCursor(500, 0)
                            ?.toString()?.substringAfterLast('\n')
                )?.trim()
                if (latestDraft != source) {
                    keyboardView.aiEnglishView.text = "🇬🇧 Text changed — translate again"
                    keyboardView.aiKoreanView.text = "🇰🇷 Text changed — translate again"
                    return@launch
                }

                aiTranslationSource = source
                aiTranslationEnglish = result.first
                aiTranslationKorean = result.second
                keyboardView.aiEnglishView.text = "🇬🇧 ${result.first}"
                keyboardView.aiKoreanView.text = "🇰🇷 ${result.second}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentInputEditorInfo !== info) return@launch
                if (e.message?.startsWith("Pronoun conflict") == true ||
                    e.message?.startsWith("Idiom conflict") == true) {
                    keyboardView.aiEnglishView.text = "🇬🇧 Meaning unclear — rephrase"
                    keyboardView.aiKoreanView.text = "🇰🇷 Check the Sinhala wording"
                } else {
                    val reason = e.message.orEmpty()
                    val hint = when {
                        reason.contains("rate limit", true) || reason.contains("HTTP 429") ->
                            "Free limit reached — retry later"
                        reason.contains("server error", true) || reason.contains("HTTP 50") ->
                            "AI server busy — retry shortly"
                        reason.contains("timed out", true) || reason.contains("connection", true) ->
                            "Connection timed out — retry"
                        reason.contains("API Key", true) || reason.contains("permission", true) ->
                            "Check saved API Key"
                        else -> "Translation failed — retry"
                    }
                    keyboardView.aiEnglishView.text = "🇬🇧 $hint"
                    keyboardView.aiKoreanView.text = "🇰🇷 $hint"
                }
            }
        }
    }

    private fun checkAutoUnshift() {
        if (caps && !shift) {
            caps = false
            updateKeyboard()
        }
    }

    private fun isInPasswordField(): Boolean {
        val t = currentInputEditorInfo
        return t != null && (t.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                t != null && (t.inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun onSuggestionClicked(suggestion: String) {
        val ic = currentInputConnection ?: return
        // Replace current token with suggestion
        val before = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(100, 0)?.toString() ?: ""
        val tokenStart = before.lastIndexOfAny(charArrayOf(' ', '\n', '\t')).let { if (it < 0) 0 else it + 1 }
        val token = before.substring(tokenStart)
        // delete token
        for (i in 0 until token.codePointCount(0, token.length)) {
            ic.deleteSurroundingTextInCodePoints(1, 0)
        }
        // commit suggestion
        ic.commitText(suggestion, 1)
        // append a space? Do not append automatically — keep behavior stable

        // DKEY_TENK2026: never learn in private fields or when opted out.
        if (foxShouldLearnFromEditor()) {
            serviceScope.launch {
                val lang = LanguageDetector.detectLanguage(suggestion)
                suggestionEngine?.recordAccepted(suggestion, lang)
            }
        }

        // Do NOT hide suggestions here; per user request keep suggestion bar open until explicit Action or Space is pressed.
        // Resetting debounce is optional; keep it running so suggestions update if user continues typing.
    }

    private var lastChar: CHAR? = null
    private var lastLetter: CHAR? = null
    // The first h in ch is part of Helakuru-style ch; only chh makes ඡ.
    private var pendingChDigraph = false
    private var positionFlag = ""

    private fun hasPositionChanged(): Boolean =
        currentInputConnection.getTextBeforeCursor(5, 0)?.toString() != positionFlag

    private fun singlishInput(input: String) {
        var output = ""
        var erasePreviousChars = 0
        var mLastChar: CHAR? = null
        var mLastLetter: CHAR? = null
        var tLastChar: CHAR? = null
        var tLastLetter: CHAR? = null

        if (!hasPositionChanged()) {
            mLastChar = lastChar
            mLastLetter = lastLetter
        } else {
            // Cursor movement, external edits and other keyboard actions break a digraph.
            pendingChDigraph = false
        }

        lastChar = null
        lastLetter = null

        var singlishChar: CHAR = getSinglishChars(input) ?: CHAR.EMPTY

        // c + h is still ච; c + h + h becomes ඡ. Keep the first h as
        // pending state rather than inserting an extra character.
        val previousChPending = pendingChDigraph
        pendingChDigraph = false
        if ((input == "h" || input == "H") &&
            mLastChar == CHAR.SIGN_AL_LAKUNA &&
            mLastLetter == CHAR.ALPAPRAANA_CAYANNA &&
            !previousChPending
        ) {
            pendingChDigraph = true
            lastChar = mLastChar
            lastLetter = mLastLetter
            positionFlag = currentInputConnection?.getTextBeforeCursor(5, 0)
                ?.toString().orEmpty()
            return
        }

        fun newLetter() {
            output = singlishChar.text
            if (singlishChar.type == CharType.WYANJANA) {
                output += CHAR.SIGN_AL_LAKUNA.text
                tLastChar = CHAR.SIGN_AL_LAKUNA
            }
        }

        if (mLastChar == null || mLastChar == CHAR.EMPTY) {
            if (input == "z" || input == "Z") tLastChar = CHAR.MARK_SANYAKA
            else newLetter()
        } else {
            when {
                input == "z" || input == "Z" -> tLastChar = CHAR.MARK_SANYAKA
                mLastChar.type == CharType.WYANJANA ->
                    when (singlishChar.code) {
                        CHAR.AYANNA.code -> output = CHAR.AELA_PILLA.text
                        CHAR.IYANNA.code -> output = CHAR.KOMBU_DEKA.text
                        CHAR.UYANNA.code -> output = CHAR.KOMBUVA_HAA_GAYANUKITTA.text
                        else -> newLetter()
                    }

                mLastChar.code == CHAR.SIGN_AL_LAKUNA.code -> {
                    when (singlishChar.code) {
                        CHAR.AYANNA.code -> {
                            erasePreviousChars = 1
                            tLastChar = mLastLetter
                        }

                        CHAR.RAYANNA.code -> {
                            output =
                                CHAR.ZERO_WIDTH_JOINER.text + CHAR.RAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                            tLastChar = CHAR.SIGN_AL_LAKUNA
                        }

                        CHAR.YAYANNA.code -> {
                            output =
                                CHAR.ZERO_WIDTH_JOINER.text + CHAR.YAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                            tLastChar = CHAR.SIGN_AL_LAKUNA
                        }

                        CHAR.HAYANNA.code -> {
                            if (mLastLetter != null) {
                                when (mLastLetter.code) {
                                    CHAR.ALPAPRAANA_TTAYANNA.code -> {
                                        output =
                                            CHAR.ALPAPRAANA_TAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.ALPAPRAANA_TAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.MAHAAPRAANA_TTAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_TAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_TAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_DDAYANNA.code -> {
                                        output =
                                            CHAR.ALPAPRAANA_DAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.ALPAPRAANA_DAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.MAHAAPRAANA_DDAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_DAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_DAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_KAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_KAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_KAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_GAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_GAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_GAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_CAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_CAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_CAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_JAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_JAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_JAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_TAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_TAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_TAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_DAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_DAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_DAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_PAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_PAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_PAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_BAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_BAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_BAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.DANTAJA_SAYANNA.code -> {
                                        output =
                                            CHAR.TAALUJA_SAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.TAALUJA_SAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.SANYAKA_DDAYANNA.code -> {
                                        output =
                                            CHAR.SANYAKA_DAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.SANYAKA_DAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.MUURDHAJA_SAYANNA.code -> {
                                        output =
                                            CHAR.MUURDHAJA_SAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MUURDHAJA_SAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    else -> newLetter()
                                }
                            } else newLetter()
                        }

                        else -> {
                            when (singlishChar.type) {
                                CharType.SWARA -> {
                                    if (mLastLetter != null) {
                                        if (singlishChar.code == CHAR.AYANNA.code)
                                            erasePreviousChars = 1
                                        else {
                                            swaraSignMap[singlishChar.code].let { sign ->
                                                if (sign != null) {
                                                    output += sign.text
                                                    tLastChar = sign
                                                    erasePreviousChars = 1
                                                } else output = singlishChar.text
                                            }
                                        }
                                    } else output = singlishChar.text
                                }

                                else -> newLetter()
                            }
                        }
                    }
                }

                mLastChar.type == CharType.PILI -> {
                    when {
                        mLastChar.code == CHAR.KETTI_AEDA_PILLA.code && singlishChar.code == CHAR.AYANNA.code -> {
                            output = CHAR.DIGA_AEDA_PILLA.text
                            erasePreviousChars = 1
                            tLastChar = CHAR.DIGA_AEDA_PILLA
                        }

                        mLastChar.code == CHAR.KETTI_IS_PILLA.code && singlishChar.code == CHAR.IYANNA.code -> {
                            output = CHAR.DIGA_IS_PILLA.text
                            erasePreviousChars = 1
                            tLastChar = CHAR.DIGA_IS_PILLA
                        }

                        mLastChar.code == CHAR.KETTI_PAA_PILLA.code && singlishChar.code == CHAR.UYANNA.code -> {
                            output = CHAR.DIGA_PAA_PILLA.text
                            erasePreviousChars = 1
                            tLastChar = CHAR.DIGA_PAA_PILLA
                        }

                        mLastChar.code == CHAR.GAETTA_PILLA.code && singlishChar.code == CHAR.IYANNA.code -> {
                            output = CHAR.DIGA_GAETTA_PILLA.text
                            erasePreviousChars = 1
                            tLastChar = CHAR.DIGA_GAETTA_PILLA
                        }

                        mLastChar.code == CHAR.KOMBUVA.code && singlishChar.code == CHAR.EYANNA.code -> {
                            output = CHAR.DIGA_KOMBUVA.text
                            erasePreviousChars = 1
                            tLastChar = CHAR.DIGA_KOMBUVA
                        }

                        mLastChar.code == CHAR.KOMBUVA_HAA_AELA_PILLA.code && singlishChar.code == CHAR.OYANNA.code -> {
                            output = CHAR.KOMBUVA_HAA_DIGA_AELA_PILLA.text
                            erasePreviousChars = 1
                            tLastChar = CHAR.KOMBUVA_HAA_DIGA_AELA_PILLA
                        }

                        else -> newLetter()
                    }
                }

                mLastChar.code == CHAR.MARK_SANYAKA.code -> {
                    when (singlishChar.code) {
                        CHAR.ALPAPRAANA_KAYANNA.code -> singlishChar = CHAR.TAALUJA_NAASIKYAYA
                        CHAR.ALPAPRAANA_GAYANNA.code -> singlishChar = CHAR.SANYAKA_GAYANNA
                        CHAR.ALPAPRAANA_JAYANNA.code -> singlishChar = CHAR.SANYAKA_JAYANNA
                        CHAR.ALPAPRAANA_DDAYANNA.code -> singlishChar = CHAR.SANYAKA_DDAYANNA
                        CHAR.ALPAPRAANA_BAYANNA.code -> singlishChar = CHAR.AMBA_BAYANNA
                        CHAR.HAYANNA.code -> singlishChar = CHAR.TAALUJA_SANYOOGA_NAAKSIKYAYA
                    }
                    newLetter()
                }

                else -> {
                    if (mLastLetter != null) {
                        when (mLastLetter) {
                            CHAR.AYANNA -> {
                                when (singlishChar.code) {
                                    CHAR.AYANNA.code -> {
                                        output = CHAR.AAYANNA.text
                                        erasePreviousChars = 1
                                        tLastLetter = CHAR.AAYANNA
                                    }

                                    CHAR.IYANNA.code -> {
                                        output = CHAR.AIYANNA.text
                                        erasePreviousChars = 1
                                        tLastLetter = CHAR.AIYANNA
                                    }

                                    CHAR.UYANNA.code -> {
                                        output = CHAR.AUYANNA.text
                                        erasePreviousChars = 1
                                        tLastLetter = CHAR.AUYANNA
                                    }

                                    else -> newLetter()
                                }
                            }

                            CHAR.AEYANNA -> {
                                if (singlishChar.code == CHAR.AYANNA.code) {
                                    output = CHAR.AEEYANNA.text
                                    erasePreviousChars = 1
                                    tLastLetter = CHAR.AEEYANNA
                                } else newLetter()
                            }

                            CHAR.IYANNA -> {
                                if (singlishChar.code == CHAR.IYANNA.code) {
                                    output = CHAR.IIYANNA.text
                                    erasePreviousChars = 1
                                    tLastLetter = CHAR.IIYANNA
                                } else newLetter()
                            }

                            CHAR.UYANNA -> {
                                if (singlishChar.code == CHAR.UYANNA.code) {
                                    output = CHAR.UUYANNA.text
                                    erasePreviousChars = 1
                                    tLastLetter = CHAR.UUYANNA
                                } else newLetter()
                            }

                            CHAR.IRUYANNA -> {
                                // Helakuru phonetic documentation uses Ru for ඎ.
                                if (singlishChar.code == CHAR.UYANNA.code) {
                                    output = CHAR.IRUUYANNA.text
                                    erasePreviousChars = 1
                                    tLastLetter = CHAR.IRUUYANNA
                                } else newLetter()
                            }

                            CHAR.EYANNA -> {
                                if (singlishChar.code == CHAR.EYANNA.code) {
                                    output = CHAR.EEYANNA.text
                                    erasePreviousChars = 1
                                    tLastLetter = CHAR.EEYANNA
                                } else newLetter()
                            }

                            CHAR.OYANNA -> {
                                when (singlishChar.code) {
                                    CHAR.OYANNA.code -> {
                                        output = CHAR.OOYANNA.text
                                        erasePreviousChars = 1
                                        tLastLetter = CHAR.OOYANNA
                                    }
                                    CHAR.UYANNA.code -> {
                                        // Both au and ou produce ඖ.
                                        output = CHAR.AUYANNA.text
                                        erasePreviousChars = 1
                                        tLastLetter = CHAR.AUYANNA
                                    }
                                    else -> newLetter()
                                }
                            }

                            else -> newLetter()
                        }
                    } else newLetter()
                }
            }
        }

        if (erasePreviousChars > 0) erasePrevious(erasePreviousChars)

        val ic = currentInputConnection
        if (ic != null) {
            try {
                ic.commitText(output, 1)
            } catch (t: Throwable) {
                Log.e("IME", "singlishInput commit failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in singlishInput")
        }

        lastChar = tLastChar ?: tLastLetter ?: singlishChar
        lastLetter = tLastLetter ?: singlishChar
        positionFlag = currentInputConnection.getTextBeforeCursor(5, 0)?.toString() ?: ""


    }

    private fun getSinglishChars(input: String): CHAR? = singlishMap[input]

    override fun emojiClick(tag: String) {
        wmTranslatePanel?.let { panel ->
            panel.appendInput(tag)
            return
        }
        val ic = currentInputConnection
        if (ic != null) {
            try {
                ic.commitText(tag, 1)
                EmojiData.addRecentEmoji(this, tag)
            } catch (t: Throwable) {
                Log.e("IME", "emoji commit failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in emojiClick")
        }
        vibrate()
        checkAutoUnshift()
    }

    override fun numberClick(tag: String) {
        wmTranslatePanel?.let { panel ->
            panel.appendInput(tag)
            return
        }
        val ic = currentInputConnection
        if (ic != null) {
            try {

                val toCommit = when (keyboardLayout) {
                    KeyboardLayout.WIJESEKARA -> Maps.keyLabelsNumbersWijesekara[tag] ?: tag
                    else -> tag
                }
                ic.commitText(toCommit, 1)
            } catch (t: Throwable) {
                Log.e("IME", "number commit failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in numberClick")
        }
        vibrate()
        checkAutoUnshift()
    }


    override fun functionClick(type: Function) {
        wmTranslatePanel?.let { panel ->
            if (type == Function.BACKSPACE) {
                panel.backspace()
                return
            }
            if (type == Function.ACTION) {
                panel.requestNow()
                return
            }
        }
        val ic = currentInputConnection
        when (type) {
            Function.ACTION -> {
                if (ic != null) {
                    try {
                        val editorInfo = currentInputEditorInfo
                        val actionId = editorInfo?.actionId ?: 0
                        if (actionId != 0) ic.performEditorAction(actionId)
                        else ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    } catch (t: Throwable) {
                        Log.e("IME", "performEditorAction/sendKeyEvent failed", t)
                    }
                } else {
                    Log.w("IME", "currentInputConnection is null in ACTION")
                }
                // Hide suggestions explicitly when user presses action
                topBarController?.showNormal()
                debouncer?.cancel()
            }

            Function.SHIFT -> {
                if (!caps) {
                    caps = true
                    shift = false
                } else if (!shift) {
                    shift = true
                } else {
                    caps = false
                    shift = false
                }
                updateKeyboard()
            }

            Function.LANG -> {
                try {
                    val enabled = Prefs.getEnabledLayouts(this)
                    val currentIndex = enabled.indexOf(keyboardLayout).let { if (it < 0) 0 else it }
                    val next = enabled[(currentIndex + 1) % enabled.size]
                    setKeyboardLayout(next)


                    mComposing = ""
                } catch (t: Throwable) {
                    Log.e("IME", "language switch failed", t)

                    setKeyboardLayout(if (keyboardLayout == KeyboardLayout.ENGLISH) Prefs.getKeyboardLayout(this) else KeyboardLayout.ENGLISH)
                }
            }

            Function.IME -> {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                if (imm != null) {
                    try {

                        userInvokedInputMethodPicker = true
                        imm.showInputMethodPicker()
                    } catch (t: Throwable) {
                        Log.e("IME", "showInputMethodPicker failed", t)
                        userInvokedInputMethodPicker = false
                    }
                } else {
                    Log.w("IME", "InputMethodManager is null in Function.IME")
                }
            }

            Function.BACKSPACE -> {
                if (ic != null) {
                    try {
                        if (ic.getSelectedText(0).isNullOrEmpty()) {
                            ic.deleteSurroundingTextInCodePoints(1, 0)
                        } else {
                            ic.commitText("", 1)


                        }
                    } catch (t: Throwable) {
                        Log.e("IME", "BACKSPACE operation failed", t)
                    }
                } else {
                    Log.w("IME", "currentInputConnection is null in BACKSPACE")
                }


                lastChar = null
                lastLetter = null
                positionFlag = ""
                mComposing = ""
            }
            Function.PANEL -> {

                keyboardSymbolsActive = !keyboardSymbolsActive
                updateKeyboard()
            }
        }
        vibrate()
    }

    override fun specialClick(tag: String) {
        wmTranslatePanel?.let { panel ->
            val character = when (tag) {
                "32" -> " "
                "44" -> ","
                "46" -> "."
                else -> if (tag.isNotEmpty() && tag.all { it.isDigit() }) {
                    tag.toIntOrNull()?.toChar()?.toString() ?: tag
                } else tag
            }
            panel.appendInput(character)
            return
        }
        if (tag == " " || tag == "32") rememberFoxCompletedWord()
        val ic = currentInputConnection
        if (ic != null) {
            try {

                val toCommit = if (tag.isNotEmpty() && tag.all { it.isDigit() }) {
                    try {
                        val code = tag.toInt()
                        when (code) {
                            32 -> " "
                            else -> code.toChar().toString()
                        }
                    } catch (t: Throwable) {
                        tag
                    }
                } else tag

                ic.commitText(toCommit, 1)


            } catch (t: Throwable) {
                Log.e("IME", "specialClick commit failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in specialClick")
        }
        vibrate()

        if (tag == " " || tag == "32") {

            lastChar = null
            lastLetter = null
            positionFlag = ""
            // hide suggestions on space/punctuation
            topBarController?.showNormal()
            debouncer?.cancel()
        }
        checkAutoUnshift()
    }

    override fun eraseDo() {
        val ic = currentInputConnection
        if (ic != null) {
            try {
                ic.deleteSurroundingTextInCodePoints(1, 0)
            } catch (t: Throwable) {
                Log.e("IME", "eraseDo failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in eraseDo")
        }
    }

    override fun eraseUndo() {

    }

    override fun eraseDone() {

    }

    override fun moveRight() {
        val ic = currentInputConnection
        if (ic != null) {
            try {
                val newCursorPosition = (ic.getTextBeforeCursor(100, 0)?.length ?: 0) + 1
                ic.setSelection(newCursorPosition, newCursorPosition)
            } catch (t: Throwable) {
                Log.e("IME", "moveRight failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in moveRight")
        }
    }

    override fun moveLeft() {
        val ic = currentInputConnection
        if (ic != null) {
            try {
                val currentCursorPosition = ic.getTextBeforeCursor(100, 0)?.length ?: 0
                if (currentCursorPosition > 0) {
                    ic.setSelection(currentCursorPosition - 1, currentCursorPosition - 1)
                }
            } catch (t: Throwable) {
                Log.e("IME", "moveLeft failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in moveLeft")
        }
    }

    private fun setKeyboardLayout(layout: KeyboardLayout) {
        keyboardLayout = layout


      try {
          Prefs.setSelectedLayout(this, layout)
        } catch (t: Throwable) {
            Log.e("IME", "Failed to persist selected keyboard layout", t)
       }

         when (layout) {
             KeyboardLayout.ENGLISH -> {
                 keyboardView.setLangIndicator("EN")
                 keyboardView.setLangIndicatorIcon(R.drawable.ic_lang_en)
                 updateKeyboard()
             }
             KeyboardLayout.WIJESEKARA -> {
                 keyboardView.setLangIndicator("SI")
                 keyboardView.setLangIndicatorIcon(R.drawable.ic_lang_si)


                 updateKeyboard()


             }
             KeyboardLayout.SINGLISH -> {
                 keyboardView.setLangIndicator("SI")
                 keyboardView.setLangIndicatorIcon(R.drawable.ic_lang_si)
                 updateKeyboard()
             }
         }
     }

    private fun updateKeyboard() {
        if (::keyboardView.isInitialized) refreshFoxVoiceBadge()
        // Match familiar compact phonetic/English layout; preserve symbols row.
        keyboardView.setNumberRowVisible(
            keyboardSymbolsActive || keyboardLayout == KeyboardLayout.WIJESEKARA
        )

        if (keyboardSymbolsActive) {
            try {
                val symMap = if (caps) symbolsMapShifted else symbolsMap
                val letters = mutableMapOf<String, String>()
                for (c in 'a'..'z') {
                    val key = c.toString()
                    letters[key] = symMap[key] ?: ""
                }
                keyboardView.setLetterKeys(letters)

                keyboardView.setNumberKeys(keyLabelsNumbers)
                keyboardView.setSpecialKeys(keyLabelsSpecialEnglish)
                keyboardView.setSecondaryLabels(null)
                return
            } catch (t: Throwable) {
                Log.e("IME", "failed to render symbols keyboard", t)
            }
        }

        val keySet = if (caps) keyLabelsLettersEnglishShifted else keyLabelsLettersEnglish
        var secondaryLabels: Map<String, String>? = null

        when (keyboardLayout) {
            KeyboardLayout.ENGLISH -> {
                keyboardView.setLetterKeys(keySet)
                keyboardView.setNumberKeys(keyLabelsNumbers)
                keyboardView.setSpecialKeys(keyLabelsSpecialEnglish)
                keyboardView.setSecondaryLabels(
                    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
                        .zip(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
                        .toMap()
                )
            }

            KeyboardLayout.WIJESEKARA -> {
                val sinhalaKeySet = if (caps) keyLabelsLettersWijesekaraShifted else keyLabelsLettersWijesekara
                keyboardView.setLetterKeys(sinhalaKeySet)
                keyboardView.setNumberKeys(keyLabelsNumbersWijesekara)
                val specialKeys = if (caps) keyLabelsSpecialWijesekaraSinhalaShifted else keyLabelsSpecialWijesekaraSinhala
                keyboardView.setSpecialKeys(specialKeys)
                keyboardView.setSecondaryLabels(null)


            }

            KeyboardLayout.SINGLISH -> {
                keyboardView.setLetterKeys(keySet)
                keyboardView.setNumberKeys(keyLabelsNumbers)
                keyboardView.setSpecialKeys(keyLabelsSpecialEnglish)


                val labels = mutableMapOf<String, String>()
                for ((k, v) in keySet) {
                    val key = k.lowercase()

                    val charMap = singlishMap[if (caps) key.uppercase() else key]
                    if (charMap != null && charMap != CHAR.EMPTY) {
                        labels[k] = charMap.text
                    }
                }
                keyboardView.setSecondaryLabels(labels)
            }
        }

        keyboardView.buttonActionShift.setImageResource(
            if (caps) R.drawable.ic_shift_pressed
            else R.drawable.ic_shift
        )


        val editorInfo = currentInputEditorInfo
        if (editorInfo != null) {
            val imeAction = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
            val (iconRes, desc) = when (imeAction) {
                EditorInfo.IME_ACTION_GO -> Pair(R.drawable.ic_keyboard_return, "Go")
                EditorInfo.IME_ACTION_SEARCH -> Pair(R.drawable.ic_search, "Search")
                EditorInfo.IME_ACTION_SEND -> Pair(R.drawable.ic_send, "Send")
                EditorInfo.IME_ACTION_NEXT -> Pair(R.drawable.ic_keyboard_arrow_right, "Next")
                EditorInfo.IME_ACTION_DONE -> Pair(R.drawable.ic_check, "Done")
                EditorInfo.IME_ACTION_NONE -> Pair(R.drawable.ic_keyboard_return, "Enter")
                else -> Pair(R.drawable.ic_keyboard_return, "Enter")
            }

            try {
                keyboardView.buttonActionAction.setImageResource(iconRes)
                keyboardView.buttonActionAction.contentDescription = desc
                keyboardView.numpadActionView.setImageResource(iconRes)
                keyboardView.numpadActionView.contentDescription = desc
            } catch (t: Throwable) {

                Log.e("IME", "Failed to set action icon resource", t)
                keyboardView.buttonActionAction.setImageResource(R.drawable.ic_keyboard_return)
                keyboardView.buttonActionAction.contentDescription = "Enter"
                keyboardView.numpadActionView.setImageResource(R.drawable.ic_keyboard_return)
                keyboardView.numpadActionView.contentDescription = "Enter"
            }
        } else {

            keyboardView.buttonActionAction.setImageResource(R.drawable.ic_keyboard_return)
            keyboardView.buttonActionAction.contentDescription = "Enter"
            keyboardView.numpadActionView.setImageResource(R.drawable.ic_keyboard_return)
            keyboardView.numpadActionView.contentDescription = "Enter"
        }
    }

    private fun isNumericInputType(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        return inputClass == InputType.TYPE_CLASS_NUMBER ||
               inputClass == InputType.TYPE_CLASS_PHONE ||
               inputClass == InputType.TYPE_CLASS_DATETIME
    }

    private fun vibrate() {
        if (!Prefs.getVibration(this)) return

        try {
            if (::keyboardView.isInitialized) {
                keyboardView.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }
        } catch (_: Throwable) { }

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(15)
                }
            }
        } catch (t: Throwable) {
            Log.e("IME", "vibrate failed", t)
        }
    }




    private fun erasePrevious(count: Int = 1) {
        val ic = currentInputConnection ?: return


        fun deleteUnits(units: Int) {
            try {
                ic.deleteSurroundingText(units, 0)
            } catch (t: Throwable) {
                Log.e("IME", "deleteSurroundingText failed in erasePrevious", t)
            }
        }

        if (count == 1) {

            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length >= 2) {
                val ch = before[before.length - 2]
                if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) {
                    deleteUnits(2)
                } else {
                    deleteUnits(1)
                }
            } else {
                deleteUnits(1)
            }
        } else {
            deleteUnits(count)
        }


        try {
            val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            if (before == CHAR.ZERO_WIDTH_JOINER.text) {

                deleteUnits(1)

                erasePrevious(1)
            }
        } catch (t: Throwable) {
            Log.e("IME", "post-delete ZWJ check failed", t)
        }
    }
}
