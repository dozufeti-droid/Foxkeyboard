package unicode.sinhala.keyboard.memory

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import unicode.sinhala.keyboard.ai.FoxContextTranslator
import unicode.sinhala.keyboard.ai.FoxQuickTranslator
import unicode.sinhala.keyboard.ai.SinglishTranslationHints

/** Separate context-mode controller: legacy FoxOneTapController stays untouched. */
class FoxContextTranslationController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentInfo: () -> EditorInfo?,
    private val currentEditor: () -> InputConnection?,
    private val onMeaning: (String) -> Unit
) {
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun translateKorean(row: TextView, profileId: Long) {
        val info = currentInfo() ?: return
        val editor = currentEditor() ?: return
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val allowed = variation in setOf(
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        )
        if (!allowed || (info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT ||
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
            row.text = "🇰🇷 Unavailable in this field"
            return
        }
        if (job?.isActive == true) return
        val selected = editor.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val original = selected ?: editor.getTextBeforeCursor(
            FoxQuickTranslator.MAX_AI_CHARS + 1, 0
        )?.toString()?.substringAfterLast('\n').orEmpty()
        val draft = original.trim()
        if (draft.isBlank() || original.length > FoxQuickTranslator.MAX_AI_CHARS ||
            SinglishTranslationHints.isUnfinished(draft)) {
            row.text = "🇰🇷 Check or shorten the draft"
            return
        }
        val started = SystemClock.elapsedRealtime()
        row.text = "🇰🇷 …"
        job = scope.launch {
            try {
                val (translated, profileName) = withContext(Dispatchers.IO) {
                    val store = ConversationMemoryStore(context)
                    try {
                        val profile = store.getProfile(profileId)
                        if (store.getActiveId() != profileId || profile == null) {
                            error("The active profile changed")
                        }
                        val history = store.lastTenForPrompt(profileId)
                        FoxContextTranslator.translate(context, draft, history) to profile.name
                    } finally { store.close() }
                }
                if (currentInfo() !== info || currentEditor() !== editor) return@launch
                val stillActive = context.getSharedPreferences("fox_context_memory", Context.MODE_PRIVATE)
                    .getLong("active_id", -1L) == profileId
                if (!stillActive) return@launch
                val unchanged = if (selected != null) {
                    editor.getSelectedText(0)?.toString() == selected
                } else {
                    editor.getTextBeforeCursor(original.length, 0)?.toString() == original
                }
                if (!unchanged) {
                    row.text = "🇰🇷 Draft changed; tap again"
                    return@launch
                }
                editor.beginBatchEdit()
                try {
                    if (selected == null && !editor.deleteSurroundingText(original.length, 0)) {
                        error("Could not replace the draft; Sinhala panel was not opened")
                    }
                    if (!editor.commitText(translated.korean, 1)) {
                        // Best effort: restore original draft if commit failed after deletion.
                        if (selected == null) editor.commitText(original, 1)
                        error("Could not insert Korean; Sinhala panel was not opened")
                    }
                } finally { editor.endBatchEdit() }
                row.text = "🇰🇷 ${profileName.take(9)} ✓ %.1fs".format(java.util.Locale.US,
                    (SystemClock.elapsedRealtime() - started) / 1000.0)
                onMeaning(translated.sinhalaMeaning) // Panel auto-opens *after* successful replacement.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentInfo() !== info || currentEditor() !== editor) return@launch
                val message = e.message.orEmpty()
                val hint = when {
                    e is java.net.SocketTimeoutException || message.contains("timed out", true) ->
                        "Timeout — tap again"
                    message.contains("429") -> "Rate limit — retry later"
                    message.contains("HTTP 401") || message.contains("HTTP 403") ->
                        "Check AI API key"
                    message.contains("HTTP 404") -> "Check AI model ID"
                    message.contains("HTTP 50") -> "AI busy — retry later"
                    message.contains("structured translation", true) ||
                        message.contains("incomplete Korean", true) ||
                        message.contains("Sinhala meaning", true) -> "AI format — tap again"
                    message.contains("provider", true) || message.contains("Model ID", true) ->
                        "Check AI settings"
                    else -> "Context failed — tap again"
                }
                row.text = "🇰🇷 $hint"
                Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
