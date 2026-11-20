package unicode.sinhala.keyboard.ai

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * This uses the same unofficial Google Translate web endpoint pattern that
 * WM Keyboard documents. It is NOT Google's supported Cloud Translation API.
 * It can rate-limit or cease working without warning. Run off the UI thread.
 */
object GoogleWebTranslateProvider {
    const val MAX_CHARS = 2500

    fun translate(text: String, target: String, source: String = "auto"): String {
        require(text.isNotBlank()) { "No message to translate" }
        require(text.length <= MAX_CHARS) { "Text exceeds translation limit" }
        require(target in setOf("si", "en", "ko")) { "Invalid target language" }
        require(source in setOf("auto", "si", "en", "ko")) { "Invalid source language" }

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        val endpoint = "https://translate.googleapis.com/translate_a/single"
        val url = "$endpoint?client=gtx&sl=${encode(source)}&tl=${encode(target)}" +
            "&dt=t&ie=UTF-8&oe=UTF-8&q=${encode(text)}"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status == 429) throw IllegalStateException("HTTP 429: rate limited")
            if (status !in 200..299) throw IllegalStateException("Google HTTP $status")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(body: String): String {
        val sentences = JSONArray(body).getJSONArray(0)
        val output = buildString {
            for (index in 0 until sentences.length()) {
                val segment = sentences.optJSONArray(index) ?: continue
                if (!segment.isNull(0)) append(segment.optString(0))
            }
        }.trim()
        require(output.isNotBlank()) { "No translated text returned" }
        return output
    }
}
