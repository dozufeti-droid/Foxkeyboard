package ime.imeui

import android.graphics.Color
import android.view.View
import android.widget.TextView
import unicode.sinhala.com.R

class TopBarController(
    private val suggestionContainer: View?,
    private val emojiButton: View?,
    private val clipboardButton: View? = null,
    private val darkTheme: Boolean = false
) {

    private var chipClipboardSuggestion: TextView? = suggestionContainer?.findViewById(R.id.chip_clipboard_suggestion)

    private fun applyColors(tv: TextView?) {
        if (tv == null) return
        if (darkTheme) {
            tv.setTextColor(Color.WHITE)
        } else {
            tv.setTextColor(Color.WHITE)
        }
    }

    fun showNormal() {
        chipClipboardSuggestion?.visibility = View.GONE
        suggestionContainer?.visibility = View.GONE
        emojiButton?.visibility = View.VISIBLE
        clipboardButton?.visibility = View.VISIBLE
    }

    fun showClipboardSuggestion(text: String, onClick: (String) -> Unit) {
        if (text.isBlank()) return
        suggestionContainer?.visibility = View.VISIBLE
        emojiButton?.visibility = View.VISIBLE
        clipboardButton?.visibility = View.VISIBLE
        // The paste chip occupies the entire suggestion row; hide stale word chips.
        for (id in intArrayOf(R.id.suggestion_1, R.id.suggestion_2, R.id.suggestion_3)) {
            suggestionContainer?.findViewById<TextView>(id)?.visibility = View.GONE
        }

        chipClipboardSuggestion?.let { chip ->
            chip.text = "Paste: ${text.replace("\n", " ")}"
            chip.visibility = View.VISIBLE
            chip.setOnClickListener {
                onClick(text)
                chip.visibility = View.GONE
            }
        }
    }

    fun hideClipboardSuggestion() {
        chipClipboardSuggestion?.visibility = View.GONE
    }

    fun showSuggestions(suggestions: List<String>, suggestionTextViews: List<TextView>, onClick: (String) -> Unit) {
        if (chipClipboardSuggestion?.visibility == View.VISIBLE) {
            // Keep clipboard suggestion active if present, or hide if suggestions are non-empty
            if (suggestions.any { it.isNotEmpty() }) {
                chipClipboardSuggestion?.visibility = View.GONE
            } else {
                return
            }
        }

        emojiButton?.visibility = View.GONE
        // Keep the clipboard button accessible while word suggestions appear.
        clipboardButton?.visibility = View.VISIBLE
        suggestionContainer?.visibility = View.VISIBLE
        for (i in 0 until 3) {
            val tv = suggestionTextViews.getOrNull(i)
            val text = suggestions.getOrNull(i) ?: ""
            if (tv != null) {
                applyColors(tv)
                tv.text = text
                tv.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                tv.setOnClickListener { onClick(text) }
            }
        }
    }
}
