package unicode.sinhala.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import unicode.sinhala.keyboard.ai.WmTranslationRequest

/**
 * WM-inspired translation panel inside the existing IME. Existing keyboard
 * keys and AI/API-key settings are not replaced. The IME handles keyboard
 * input while this panel is open, so it never needs to focus a second editor.
 */
class WmTranslatePanel(
    context: Context,
    initialText: String,
    private val onTranslate: (WmTranslationRequest) -> Unit,
    private val onDownload: () -> Unit,
    private val onReplace: (String, String) -> Boolean,
    private val onInsert: (String) -> Boolean,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    data class Lang(val code: String, val title: String)

    companion object {
        // Only the user's three working languages. Sinhala remains the native
        // source/incoming-message language; English and Korean are prioritized.
        val languages = listOf(
            Lang("si", "Sinhala"),
            Lang("en", "English"),
            Lang("ko", "Korean")
        )
    }

    // Compact height keeps the original Fox keyboard's key rows visible.
    private val bg = Color.rgb(249, 245, 252)
    private val ink = Color.rgb(41, 34, 49)
    private val lavender = Color.rgb(231, 215, 253)
    private val sourceLanguages = listOf(Lang("auto", "Detect language")) + languages
    private val targetLanguages = languages
    private var query = initialText.take(2500)
    private var translation = ""
    private var textForTranslation = ""
    private var pendingRequest: WmTranslationRequest? = null
    private val inputText = TextView(context)
    private val resultText = TextView(context)
    private val hint = TextView(context)
    private val sourceSpinner = Spinner(context)
    private val targetSpinner = Spinner(context)
    private val engineSpinner = Spinner(context)
    private var alive = true

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun rounded(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(11).toFloat()
    }
    private fun text(label: String, size: Float = 14f, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = label
            textSize = size
            setTextColor(ink)
            gravity = Gravity.CENTER_VERTICAL
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        this.text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(ink)
        background = rounded(lavender)
        setOnClickListener { onClick() }
    }
    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun weighted(view: View, weight: Float = 1f) =
        LinearLayout.LayoutParams(0, dp(36), weight).also { it.marginEnd = dp(3) }.apply {
            view.layoutParams = this
        }
    private fun <T> setupSpinner(spinner: Spinner, labels: List<T>, title: (T) -> String) {
        spinner.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item, labels.map(title)
        )
        spinner.setBackgroundColor(Color.TRANSPARENT)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(bg)
        setPadding(dp(7), dp(5), dp(7), dp(5))
        contentDescription = "Translate tool"

        val header = row()
        val back = button("‹", onClose)
        header.addView(back, LinearLayout.LayoutParams(dp(35), dp(38)))
        val title = text("Translate", 16f, true)
        title.setPadding(dp(5), 0, 0, 0)
        header.addView(title, LinearLayout.LayoutParams(0, dp(38), 1f))
        val paste = button("Paste") {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clip.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (copied.isNotBlank()) setQuery(copied.take(2500))
        }
        header.addView(paste, LinearLayout.LayoutParams(dp(61), dp(38)))
        val download = button("↓ Offline") { onDownload() }
        download.contentDescription = "Download English and Korean translation models"
        header.addView(download, LinearLayout.LayoutParams(dp(92), dp(38)))
        addView(header)

        inputText.textSize = 17f
        inputText.setTextColor(ink)
        inputText.minLines = 1
        inputText.maxLines = 2
        inputText.gravity = Gravity.TOP
        inputText.setPadding(dp(9), dp(5), dp(9), dp(4))
        inputText.background = rounded(Color.rgb(242, 235, 248))
        inputText.contentDescription = "Source text. Type using the keyboard below."
        addView(inputText, LinearLayout.LayoutParams(-1, dp(49)))
        renderInput()

        val selectors = row()
        setupSpinner(sourceSpinner, sourceLanguages) { it.title }
        setupSpinner(targetSpinner, targetLanguages) { it.title }
        setupSpinner(engineSpinner, listOf("Online", "On device", "Automatic", "Quality AI")) { it }
        selectors.addView(sourceSpinner, weighted(sourceSpinner, 1.2f))
        val swap = button("⇄") { swapLanguages() }
        selectors.addView(swap, LinearLayout.LayoutParams(dp(32), dp(36)))
        selectors.addView(targetSpinner, weighted(targetSpinner, 1.15f))
        selectors.addView(engineSpinner, weighted(engineSpinner, 1.05f))
        addView(selectors)
        // Most outgoing chat is Sinhala / Singlish → Korean.
        sourceSpinner.setSelection(sourceLanguages.indexOfFirst { it.code == "si" })
        targetSpinner.setSelection(targetLanguages.indexOfFirst { it.code == "ko" }.coerceAtLeast(0))
        // Prefer downloaded local EN↔KO models, else explicitly selected online.
        engineSpinner.setSelection(2)

        val observer = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                invalidateTranslation()
            }
        }
        sourceSpinner.onItemSelectedListener = observer
        targetSpinner.onItemSelectedListener = observer
        engineSpinner.onItemSelectedListener = observer

        resultText.textSize = 17f
        resultText.setTextColor(ink)
        resultText.minLines = 1
        resultText.maxLines = 3
        resultText.gravity = Gravity.TOP
        resultText.setPadding(dp(5), dp(4), dp(5), dp(3))
        resultText.text = "Translation appears here"
        // Longer answers can scroll instead of increasing keyboard height.
        resultText.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        resultText.setVerticalScrollBarEnabled(true)
        addView(resultText, LinearLayout.LayoutParams(-1, dp(60)))

        hint.textSize = 11f
        hint.setTextColor(Color.rgb(103, 88, 115))
        hint.text = "EN ↔ KO offline: tap ↓ Offline once. Sinhala needs online AI."
        addView(hint, LinearLayout.LayoutParams(-1, dp(25)))

        val actions = row()
        val translateButton = button("Translate") { requestNow() }
        val replace = button("Replace text") { actOnResult(true) }
        val insert = button("Insert") { actOnResult(false) }
        actions.addView(translateButton, weighted(translateButton, 0.9f))
        actions.addView(replace, weighted(replace, 1.25f))
        actions.addView(insert, weighted(insert, 0.8f))
        addView(actions)
    }

    fun showDownloadStatus(message: String) {
        if (alive) hint.text = message
    }

    fun appendInput(value: String) {
        if (value.isEmpty()) return
        setQuery((query + value).take(2500))
    }

    fun backspace() {
        if (query.isEmpty()) return
        val cut = query.offsetByCodePoints(query.length, -1)
        setQuery(query.substring(0, cut))
    }

    private fun renderInput() {
        inputText.text = if (query.isEmpty()) "Type the text to translate..." else query
    }

    private fun setQuery(value: String) {
        query = value
        renderInput()
        invalidateTranslation()
    }

    private fun invalidateTranslation() {
        hint.text = "EN ↔ KO offline: tap ↓ Offline once. Sinhala needs online AI."
        pendingRequest = null
        translation = ""
        textForTranslation = ""
        resultText.text = "Translation appears here"
    }

    private fun swapLanguages() {
        val source = sourceSpinner.selectedItemPosition
        val target = targetSpinner.selectedItemPosition
        val oldTarget = targetLanguages[target].code
        val oldSource = sourceLanguages[source].code
        sourceSpinner.setSelection(sourceLanguages.indexOfFirst { it.code == oldTarget }.coerceAtLeast(0))
        if (oldSource != "auto") {
            targetSpinner.setSelection(targetLanguages.indexOfFirst { it.code == oldSource }.coerceAtLeast(0))
        }
    }

    fun requestNow() {
        if (!alive) return
        if (query.isBlank()) {
            resultText.text = "Enter text first"
            return
        }
        val req = WmTranslationRequest(
            text = query,
            source = sourceLanguages[sourceSpinner.selectedItemPosition].code,
            target = targetLanguages[targetSpinner.selectedItemPosition].code,
            mode = when (engineSpinner.selectedItemPosition) {
                1 -> WmTranslationRequest.Mode.ON_DEVICE
                2 -> WmTranslationRequest.Mode.AUTOMATIC
                3 -> WmTranslationRequest.Mode.QUALITY_AI
                else -> WmTranslationRequest.Mode.ONLINE
            }
        )
        pendingRequest = req
        resultText.text = "Translating..."
        onTranslate(req)
    }

    fun showTranslated(request: WmTranslationRequest, answer: String, via: String) {
        if (!alive || pendingRequest != request) return
        translation = answer
        textForTranslation = request.text
        resultText.text = answer
        hint.text = "Via $via · Tap Replace text or Insert"
    }

    fun showError(request: WmTranslationRequest, message: String) {
        if (!alive || pendingRequest != request) return
        translation = ""
        textForTranslation = ""
        resultText.text = message
        hint.text = "No message was changed"
    }

    private fun actOnResult(replace: Boolean) {
        if (translation.isBlank() || textForTranslation != query) {
            resultText.text = "Translate this text first"
            return
        }
        if (replace) {
            if (!onReplace(translation, query)) {
                hint.text = "Original message changed: use Insert or try again"
            } else {
                onClose()
            }
        } else {
            if (onInsert(translation)) onClose()
            else hint.text = "Deselect text or reopen the editor before Insert."
        }
    }

    fun dispose() {
        alive = false
    }
}
