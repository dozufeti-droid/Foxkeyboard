package unicode.sinhala.keyboard.ai

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Experimental web integration: explicit Korean-button tap only. No retries,
 * cookies, authentication workarounds, redirects, or automatic provider switching.
 * The caller must verify that use of this service is authorized before enabling it.
 */
internal object PapagoTranslationProvider {
    private const val ENDPOINT = "https://papago.naver.com/api/text/translation"
    private const val MAX_RESPONSE_CHARS = 65536

    fun translateEnglish(text: String): String {
        val body = PapagoWire.form(text).toByteArray(Charsets.UTF_8)
        val connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) throw PapagoHttpException(status)
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > MAX_RESPONSE_CHARS) error("Papago response too large")
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            val json = JSONObject(response)
            val translated = (json.opt("translatedText") as? String)?.trim().orEmpty()
            check(PapagoWire.validKorean(translated)) { "Invalid Korean response" }
            return translated
        } finally {
            connection.disconnect()
        }
    }
}

internal class PapagoHttpException(val status: Int) : IllegalStateException("Papago HTTP $status")
