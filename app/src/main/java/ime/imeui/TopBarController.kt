package ime.imeui

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import unicode.sinhala.com.R

class TopBarController(
    private val suggestionContainer: View?,
    private val emojiButton: View?,
    private val clipboardButton: View? = null,
    private val darkTheme: Boolean = false,
    private val voiceButton: View? = null
) {
    private val chip: TextView? = suggestionContainer?.findViewById(R.id.chip_clipboard_suggestion)
    private val scroll: HorizontalScrollView? = suggestionContainer?.findViewById(R.id.fox_suggestion_scroll)
    private val words: ViewGroup? = suggestionContainer?.findViewById(R.id.fox_suggestion_words)
    private var lastWords: List<String> = emptyList()

    private fun pinTools() {
        suggestionContainer?.visibility = View.VISIBLE
        emojiButton?.visibility = View.VISIBLE
        clipboardButton?.visibility = View.VISIBLE
        voiceButton?.visibility = View.VISIBLE
    }

    private fun clearWords() {
        scroll?.visibility = View.GONE
        scroll?.scrollTo(0, 0)
        lastWords = emptyList()
        val container = words ?: return
        for (i in 0 until container.childCount) {
            container.getChildAt(i).visibility = View.GONE
        }
    }

    fun showNormal() {
        pinTools()
        chip?.visibility = View.GONE
        clearWords()
    }

    fun showClipboardSuggestion(text: String, onClick: (String) -> Unit) {
        pinTools()
        if (text.isBlank()) return
        clearWords()
        chip?.let { view ->
            view.text = "Paste: ${text.replace("\n", " ")}"
            view.visibility = View.VISIBLE
            view.setOnClickListener {
                onClick(text)
                view.visibility = View.GONE
            }
        }
    }

    fun hideClipboardSuggestion() {
        pinTools()
        chip?.visibility = View.GONE
    }

    fun showSuggestions(suggestions: List<String>, suggestionTextViews: List<TextView>, onClick: (String) -> Unit) {
        pinTools()
        val visibleWords = suggestions.filter { it.isNotBlank() }.take(suggestionTextViews.size)
        if (visibleWords.isEmpty()) {
            clearWords()
            return
        }
        chip?.visibility = View.GONE
        scroll?.visibility = View.VISIBLE
        if (lastWords != visibleWords) {
            scroll?.scrollTo(0, 0)
            lastWords = visibleWords.toList()
        }
        for ((i, tv) in suggestionTextViews.withIndex()) {
            val word = visibleWords.getOrNull(i).orEmpty()
            tv.setTextColor(Color.WHITE)
            tv.text = word
            tv.contentDescription = word
            tv.visibility = if (word.isEmpty()) View.GONE else View.VISIBLE
            tv.setOnClickListener { if (word.isNotEmpty()) onClick(word) }
        }
    }
}
