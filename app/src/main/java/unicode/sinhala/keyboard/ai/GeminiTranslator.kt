package unicode.sinhala.keyboard.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** On-demand Gemini translation. Does not read the clipboard or send keystrokes. */
object GeminiTranslator {
    // FOX_GEMINI_RELIABILITY_V1: retry only transient Gemini/network errors.
    private class RetryableGeminiException(message: String) : IllegalStateException(message)

    private fun request(
        text: String,
        instructions: String,
        model: String,
        apiKey: String,
        jsonResult: Boolean
    ): String {
        var retries = 0
        while (true) {
            try {
                return requestOnce(text, instructions, model, apiKey, jsonResult)
            } catch (e: RetryableGeminiException) {
                // For 500/502/503/504: max 2 retries, with backoff.
                if (retries >= 2) throw e
            } catch (e: java.io.IOException) {
                // Network timeouts/reset: max 1 retry. Authentication/quota errors
                // are IllegalStateException and deliberately never retried.
                if (retries >= 1) {
                    throw IllegalStateException("Gemini connection timed out or interrupted", e)
                }
            }
            val pauseMs = if (retries == 0) 1200L else 3000L
            try {
                Thread.sleep(pauseMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Translation interrupted", e)
            }
            retries++
        }
    }

    private fun requestOnce(
        text: String,
        instructions: String,
        model: String,
        apiKey: String,
        jsonResult: Boolean
    ): String {
        require(text.isNotBlank()) { "Message is empty" }
        require(apiKey.isNotBlank()) { "Gemini API Key is missing" }
        val modelId = model.trim().removePrefix("models/")
        require(modelId.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid Gemini Model ID" }

        val payload = JSONObject()
            .put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", instructions))
            ))
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts", JSONArray().put(JSONObject().put("text", text))
                )
            ))

        val generation = JSONObject()
            .put("temperature", 0.2)
            .put("maxOutputTokens", 4096)
        if (jsonResult) {
            val schema = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("en", JSONObject().put("type", "string"))
                    .put("ko", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("en").put("ko"))
                .put("additionalProperties", false)
            generation.put("responseMimeType", "application/json")
            generation.put("responseJsonSchema", schema)
        }
        payload.put("generationConfig", generation)

        val connection = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        ).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 45000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val reason = when (status) {
                    400 -> "Invalid Gemini request or model"
                    401, 403 -> "Gemini API Key or permission error"
                    404 -> "Gemini model not found"
                    429 -> "Gemini rate limit reached"
                    in 500..599 -> "Gemini server error"
                    else -> "Gemini connection error"
                }
                if (status == 500 || status == 502 || status == 503 || status == 504) {
                    throw RetryableGeminiException("$reason (HTTP $status)")
                }
                throw IllegalStateException("$reason (HTTP $status)")
            }
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val response = JSONObject(responseText)
            val candidates = response.optJSONArray("candidates")
                ?: throw IllegalStateException("Gemini returned no translation candidates")
            require(candidates.length() > 0) { "Gemini returned an empty response" }
            val parts = candidates.getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val output = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (!part.optBoolean("thought", false)) {
                    output.append(part.optString("text", ""))
                }
            }
            val result = output.toString().trim()
            require(result.isNotEmpty()) { "Gemini returned empty translation text" }
            return result
        } finally {
            connection.disconnect()
        }
    }

    fun translatePair(
        encodedSource: String,
        instructions: String,
        model: String,
        apiKey: String,
        originalText: String
    ): Pair<String, String> {
        val content = request(encodedSource, instructions, model, apiKey, true)
        val json = JSONObject(content)
        val english = json.getString("en").trim()
        val korean = json.getString("ko").trim()
        require(english.isNotEmpty() && korean.isNotEmpty()) {
            "Gemini returned an empty translation"
        }
        if (SinglishTranslationHints.hasContradictoryPronoun(originalText, english, korean)) {
            throw IllegalStateException("Pronoun conflict: translate again or clarify")
        }
        if (SinglishTranslationHints.hasContradictoryIdiom(originalText, english, korean)) {
            throw IllegalStateException("Idiom conflict: translate again or clarify")
        }
        return Pair(english, korean)
    }

    fun translateSinhala(
        text: String,
        instructions: String,
        model: String,
        apiKey: String
    ): String = request(text, instructions, model, apiKey, false)
}
