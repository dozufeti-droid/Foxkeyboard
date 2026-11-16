package unicode.sinhala.keyboard.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AiTranslator {

    fun translate(
        text: String,
        model: String,
        apiKey: String
    ): Pair<String, String> {

        require(text.isNotBlank())
        require(model.isNotBlank())
        require(apiKey.isNotBlank())

        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "You are a professional translator. " +
                        "Translate the user's text naturally into " +
                        "English and Korean. Preserve meaning, tone, " +
                        "and politeness. Return ONLY a JSON object " +
                        "with two string fields: en and ko. " +
                        "Do not add explanations."
                    )
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", text)
            )

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)

        val connection = URL(
            "https://api.groq.com/openai/v1/chat/completions"
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.doOutput = true

            connection.setRequestProperty(
                "Authorization",
                "Bearer $apiKey"
            )
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )

            connection.outputStream.use {
                it.write(
                    body.toString().toByteArray(Charsets.UTF_8)
                )
            }

            if (connection.responseCode !in 200..299) {
                throw Exception(
                    "Translation failed: HTTP ${connection.responseCode}"
                )
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val content = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val cleaned = content.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val result = JSONObject(cleaned)

            return Pair(
                result.getString("en"),
                result.getString("ko")
            )

        } finally {
            connection.disconnect()
        }
    }
}
