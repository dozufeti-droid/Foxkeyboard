package unicode.sinhala.keyboard.ai

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.os.SystemClock

/**
 * This uses the same unofficial Google Translate web endpoint pattern that
 * WM Keyboard documents. It is NOT Google's supported Cloud Translation API.
 * It can rate-limit or cease working without warning. Run off the UI thread.
 */
object GoogleWebTranslateProvider {
    const val MAX_CHARS = 2500
    @Volatile private var blockedUntilMs: Long = 0L

    fun translate(text: String, target: String, source: String = "auto"): String {
        require(text.isNotBlank()) { "No message to translate" }
        require(text.length <= MAX_CHARS) { "Text exceeds translation limit" }
        val languageCode = Regex("[A-Za-z]{2,3}(-[A-Za-z]{2,4})?")
        require(languageCode.matches(target)) { "Invalid target language" }
        require(source == "auto" || languageCode.matches(source)) { "Invalid source language" }
        if (SystemClock.elapsedRealtime() < blockedUntilMs) {
            error("HTTP 429: cooldown active")
        }

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        val endpoint = "https://translate.googleapis.com/translate_a/single"
        val url = "$endpoint?client=gtx&sl=${encode(source)}&tl=${encode(target)}" +
            "&dt=t&ie=UTF-8&oe=UTF-8&q=${encode(text)}"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6000
            connection.readTimeout = 12000
            // WM Keyboard's browser-style network header. This does not
            // bypass or override any provider rate limit.
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status == 429) {
                // Avoid hammering the service after it has refused a request.
                val seconds = connection.getHeaderField("Retry-After")
                    ?.trim()?.toLongOrNull()?.coerceIn(60L, 3600L) ?: 60L
                blockedUntilMs = SystemClock.elapsedRealtime() + seconds * 1000L
                throw IllegalStateException("HTTP 429: rate limited. Retry after $seconds seconds")
            }
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
