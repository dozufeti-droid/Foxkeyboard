package unicode.sinhala.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import unicode.sinhala.keyboard.ai.FoxQuickTranslator
import unicode.sinhala.keyboard.ai.SinhalaScriptException
import unicode.sinhala.keyboard.ai.SinglishTranslationHints

/**
 * Opt-in default V6 one-tap UI. Does not alter the keyboard layout at all.
 * It never sends text automatically; only the user taps a language button.
 * It never commits a translation after the editor, selection or draft changes.
 */
class FoxOneTapController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentInfo: () -> EditorInfo?,
    private val currentEditor: () -> InputConnection?
) {
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun translateDraft(target: String, row: TextView) {
        val flag = if (target == "en") "🇬🇧" else "🇰🇷"
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
            row.text = "$flag Unavailable in this field"
            return
        }
        if (job?.isActive == true) {
            row.text = "$flag Translating..."
            return // do not send two concurrent copies of the same chat
        }
        val selected = editor.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
        val original = selected ?: editor.getTextBeforeCursor(
            FoxQuickTranslator.MAX_AI_CHARS + 1, 0
        )?.toString()?.substringAfterLast('\n').orEmpty()
        val text = original.trim()
        if (text.isBlank()) {
            row.text = "$flag Type a message first"
            return
        }
        if (original.length > FoxQuickTranslator.MAX_AI_CHARS) {
            row.text = "$flag Quick translate max 2000 characters"
            return
        }
        if (SinglishTranslationHints.isUnfinished(text)) {
            row.text = "$flag Finish the Singlish sentence first"
            return
        }
        row.text = "$flag Translating..."
        job = scope.launch {
            try {
                val translated = withContext(Dispatchers.IO) {
                    FoxQuickTranslator.translate(context, text, target)
                }
                // A callback might arrive after the user switched apps, selected
                // a different draft, or typed another character. Never overwrite it.
                if (currentInfo() !== info || currentEditor() !== editor) return@launch
                val unchanged = if (selected != null) {
                    editor.getSelectedText(0)?.toString() == selected
                } else {
                    editor.getTextBeforeCursor(original.length, 0)?.toString() == original
                }
                if (!unchanged) {
                    row.text = "$flag Draft changed; tap again"
                    return@launch
                }
                editor.beginBatchEdit()
                try {
                    if (selected == null) editor.deleteSurroundingText(original.length, 0)
                    editor.commitText(translated, 1)
                } finally { editor.endBatchEdit() }
                row.text = "$flag ✓ ${translated.take(70)}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentInfo() !== info || currentEditor() !== editor) return@launch
                row.text = errorLabel(flag, e)
            }
        }
    }

    fun translateClipboardToSinhala(button: TextView, panel: View, output: TextView) {
        if (job?.isActive == true) {
            button.text = "🇱🇰 Translating..."
            return
        }
        val copied = try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString()?.trim()
        } catch (_: Exception) { null }
        if (copied.isNullOrBlank()) {
            panel.visibility = View.VISIBLE
            output.text = "Copy a Korean or English message first."
            button.text = "🇱🇰 Sinhala Translate"
            return
        }
        if (copied.length > FoxQuickTranslator.MAX_AI_CHARS) {
            panel.visibility = View.VISIBLE
            output.text = "Quick translation max 2000 characters. Disable V6 quick mode for longer messages."
            return
        }
        panel.visibility = View.VISIBLE
        output.text = "Translating to Sinhala..."
        button.text = "🇱🇰 Translating..."
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    FoxQuickTranslator.translate(context, copied, "si")
                }
                // If user changed clipboard while translating, never display
                // the returned text as though it translated the new clipboard.
                val nowCopied = try {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.text?.toString()?.trim()
                } catch (_: Exception) { null }
                if (nowCopied != copied) {
                    output.text = "Clipboard changed. Tap to translate again."
                    button.text = "🇱🇰 Sinhala Translate"
                    return@launch
                }
                output.text = result
                button.text = "🇱🇰 Sinhala Translate"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                output.text = errorLabel("🇱🇰", e)
                button.text = "🇱🇰 Sinhala Translate"
            }
        }
    }

    private fun errorLabel(flag: String, error: Exception): String {
        val message = error.message.orEmpty()
        return when {
            error is SinhalaScriptException ->
                "🇱🇰 Sinhala output contained mixed letters twice. Retry this message."
            message.contains("429") -> "$flag AI rate limit; retry later"
            message.contains("HTTP 401") || message.contains("HTTP 403") ->
                "$flag API key or permissions rejected; check AI Settings"
            message.contains("HTTP 404") ->
                "$flag Selected AI model unavailable; check Model ID"
            message.contains("HTTP 50") -> "$flag AI service busy; retry"
            error is java.net.SocketTimeoutException -> "$flag Connection timed out; retry"
            message.contains("API key", ignoreCase = true) ->
                "$flag API key missing; check AI Settings"
            message.contains("Model ID", ignoreCase = true) ->
                "$flag Model ID missing; check AI Settings"
            message.contains("conflict", ignoreCase = true) -> "$flag Meaning unclear; rephrase"
            message.contains("already", ignoreCase = true) -> "$flag Already translated"
            else -> "$flag Translation unavailable; retry"
        }
    }
}
