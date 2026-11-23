package unicode.sinhala.keyboard.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AiConnectionTester {

    fun test(
        provider: String,
        model: String,
        apiKey: String
    ): String {

        if (model.isBlank()) return "Enter a Model ID"
        if (apiKey.isBlank()) return "No API Key saved"

        require(provider == "Google Gemini") { "Only Google Gemini is supported" }
        val id = model.trim().removePrefix("models/")
        if (!id.matches(Regex("[A-Za-z0-9._-]+"))) return "Invalid Gemini Model ID"
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$id:generateContent"
        val payload = JSONObject().put("contents", JSONArray().put(
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Reply with OK.")))
        ))

        return try {
            val conn = URL(endpoint).openConnection()
                as HttpURLConnection

            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = false
                conn.doOutput = true

                conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )

                conn.setRequestProperty("x-goog-api-key", apiKey)

                conn.outputStream.use {
                    it.write(
                        payload.toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                }

                when (conn.responseCode) {
                    in 200..299 -> "Connection Successful"
                    400 -> "Invalid request or Model ID"
                    401, 403 -> "Invalid API Key or access denied"
                    402 -> "Payment or credits required"
                    404 -> "Model or endpoint not found"
                    429 -> "Free limit or rate limit exceeded"
                    in 500..599 -> "AI provider server error"
                    else -> "Connection failed: HTTP ${conn.responseCode}"
                }
            } finally {
                conn.disconnect()
            }

        } catch (_: Exception) {
            "Network error or connection timed out"
        }
    }
}
