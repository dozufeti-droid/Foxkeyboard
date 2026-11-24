package unicode.sinhala.keyboard.ai

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** One-tap Gemini only. No cross-provider forwarding or quota-key rotation. */
internal object FoxGeminiSmart {
    private enum class Fault { AUTH, QUOTA, BUSY, NETWORK, OUTPUT, PERMANENT, LOCAL_LIMIT }
    private class Failure(val fault: Fault, message: String) : IllegalStateException(message)
    private data class Key(val slot: Int, val value: String)
    private data class Result(val text: String?, val failure: Failure?, val optional: Boolean)
    private val pool = Executors.newFixedThreadPool(3)
    private val invalidKeysUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun keyFingerprint(key: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private data class Cached(val text: String, val at: Long)
    private val cache = object : LinkedHashMap<String, Cached>(48, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > 48
    }
    private fun keyForCache(model: String, target: String, original: String, prompt: String, request: String, keys: List<Key>): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(
            listOf(model, target, original, prompt, request, keys.joinToString { it.value }, if (target == "si") "chat-si-v2" else "")
                .joinToString("\u0000").toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun getCached(key: String): String? = synchronized(cache) {
        cache[key]?.takeIf { android.os.SystemClock.elapsedRealtime() - it.at < 120000L }?.text
    }
    private fun cacheResult(key: String, text: String) = synchronized(cache) {
        cache[key] = Cached(text, android.os.SystemClock.elapsedRealtime()); Unit
    }
    suspend fun translate(context: Context, original: String, target: String, rawModel: String,
                          outgoingPrompt: String, request: String): String {
        if (target == "si") FoxSinhalaChat.local(original)?.let { return "තේරුම:\n$it" }
        val c = context.applicationContext
        val model = rawModel.removePrefix("models/")
        require(model.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid Gemini Model ID" }
        val store = ApiKeyStore(c)
        val keys = (0..2).mapNotNull { n -> store.load(FoxGeminiUsage.slotName(n))?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { Key(n, it) } }.distinctBy { it.value }
        require(keys.isNotEmpty()) { "Save an API key for Google Gemini in AI Settings" }
        val prefs = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val hedge = prefs.getBoolean("fox_gemini_speed_hedge", true)
        val cacheEnabled = prefs.getBoolean("fox_gemini_session_cache", false)
        val ck = keyForCache(model, target, original, outgoingPrompt, request, keys)
        if (cacheEnabled) getCached(ck)?.let { return it }
        var result: String? = null
        try {
            result = withTimeout(if (target == "si") 20000L else 16000L) {
                var last: Failure? = null
                // Backup credentials are for explicit authentication failure only, not 429/503.
                for (key in keys) {
                    val fingerprint = keyFingerprint(key.value)
                    if ((invalidKeysUntil[fingerprint] ?: 0L) > android.os.SystemClock.elapsedRealtime()) continue
                    try {
                        return@withTimeout attempt(c, key, model, original, target, outgoingPrompt, request, hedge)
                    } catch (e: Failure) {
                        last = e
                        if (e.fault != Fault.AUTH) throw e
                        invalidKeysUntil[fingerprint] = android.os.SystemClock.elapsedRealtime() + 300000L
                    }
                }
                throw last ?: Failure(Fault.AUTH, "Gemini API key or permissions rejected")
            }
        } catch (e: TimeoutCancellationException) {
            // Keep parent cancellation intact; this timeout is the total translation deadline.
            currentCoroutineContext().ensureActive()
            throw Failure(Fault.NETWORK, "Gemini connection timed out; retry")
        }
        val answer = requireNotNull(result)
        if (cacheEnabled) cacheResult(ck, answer)
        return answer
    }
    private suspend fun attempt(c: Context, key: Key, model: String, original: String, target: String,
                                outgoingPrompt: String, request: String, hedge: Boolean): String {
        try {
            return race(c, key, model, original, target, outgoingPrompt, request, hedge)
        } catch (e: Failure) {
            when(e.fault) {
                Fault.BUSY, Fault.NETWORK -> delay(1000L + Random.nextLong(0L, 350L))
                Fault.OUTPUT -> Unit
                else -> throw e
            }
            // A single bounded recovery request. No retries on quota, permission, or permanent errors.
            try {
                return call(c, key, model, original, target, outgoingPrompt, request, true, false)
            } catch (recovery: Failure) {
                // Sinhala-only final attempt after bad output; never bypass quota/auth/policy.
                if (target != "si" || recovery.fault != Fault.OUTPUT) throw recovery
                return call(c, key, model, original, target, outgoingPrompt, request, true, false, finalRecovery = true)
            }
        }
    }
    private suspend fun race(c: Context, key: Key, model: String, original: String, target: String,
                             outgoingPrompt: String, request: String, hedge: Boolean): String = coroutineScope {
        val ch = Channel<Result>(2)
        val hedgeStarted = AtomicBoolean(false)
        fun start(optional: Boolean, wait: Long): Job = launch {
            if (wait > 0) delay(wait)
            if (optional) hedgeStarted.set(true)
            try {
                ch.send(Result(call(c, key, model, original, target, outgoingPrompt, request, false, optional), null, optional))
            } catch (e: CancellationException) { throw e }
            catch (e: Failure) { ch.send(Result(null, e, optional)) }
            catch (_: Exception) { ch.send(Result(null, Failure(Fault.OUTPUT, "Invalid Gemini response"), optional)) }
        }
        val first = start(false, 0)
        // Hedge the SAME authorized credential, after a delay, only with quota headroom.
        // No simultaneous account/key rotation and no duplicate when first response is fast.
        val second = if (hedge) start(true, 1700L) else null
        try {
            val a = ch.receive()
            if (a.text != null) return@coroutineScope a.text
            val f = requireNotNull(a.failure)
            if (f.fault in setOf(Fault.QUOTA, Fault.AUTH, Fault.PERMANENT)) throw f
            if (second == null || (!a.optional && f.fault == Fault.LOCAL_LIMIT)) throw f
            // If primary fails before hedge dispatch, stop delayed duplicate; backoff normally.
            if (!a.optional && !hedgeStarted.get()) throw f
            val b = ch.receive()
            if (b.text != null) return@coroutineScope b.text
            val g = requireNotNull(b.failure)
            if (g.fault in setOf(Fault.QUOTA, Fault.AUTH, Fault.PERMANENT)) throw g
            throw if (f.fault == Fault.LOCAL_LIMIT) g else f
        } finally { first.cancel(); second?.cancel(); ch.cancel() }
    }
    private suspend fun call(c: Context, key: Key, model: String, original: String, target: String,
                             prompt: String, request: String, correction: Boolean, optional: Boolean, finalRecovery: Boolean = false): String {
        if (!FoxGeminiUsage.reserve(c, key.slot, optional)) throw Failure(Fault.LOCAL_LIMIT, "Gemini local request limit; retry later")
        val raw = network(key.value, model, target, prompt, if (target == "si") original else request, correction, finalRecovery) { ms -> FoxGeminiUsage.block(ms) }
        val parsed = try { validate(raw, original, target) } catch (e: Failure) { throw e }
            catch (_: Exception) { throw Failure(Fault.OUTPUT, "Invalid Gemini response format") }
        FoxGeminiUsage.success(c, key.slot)
        return parsed
    }
    private fun schema(): JSONObject {
        val string = JSONObject().put("type", "string")
        val item = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("word", string).put("meaning", string))
            .put("required", JSONArray().put("word").put("meaning"))
        return JSONObject().put("type", "object")
            .put("properties", JSONObject().put("meaning", string).put("vocabulary", JSONObject()
                .put("type", "array").put("items", item)))
            .put("required", JSONArray().put("meaning").put("vocabulary"))
    }
    private fun payload(model: String, target: String, prompt: String, request: String, correction: Boolean, finalRecovery: Boolean): JSONObject {
        val json = target == "si" && FoxSinhalaChat.useJson(request, correction)
        val instructions = if (target == "si") FoxSinhalaChat.prompt(json, correction, finalRecovery) else prompt +
            if (correction) "\nCheck the source meaning carefully. Return a complete translation in the requested language only." else ""
        val cfg = JSONObject().put("temperature", 0.1)
            .put("maxOutputTokens", if (target == "si") (request.length * 5 + 256).coerceIn(512, 4096)
                else (request.length * 3 + 128).coerceIn(384, 4096))
        if (model in setOf("gemini-2.5-flash", "gemini-2.5-flash-lite")) cfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        if (model == "gemini-3.5-flash-lite") cfg.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
        if (model == "gemini-3.8-flash") cfg.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        if (json) cfg.put("responseMimeType", "application/json").put("responseJsonSchema", schema())
        return JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", request)))))
            .put("generationConfig", cfg)
    }
    private fun validate(raw: String, original: String, target: String): String {
        if (target == "si") {
            // Accept plain text from the fast path or recovery, and structured output for longer messages.
            val clean = raw.trim()
            if (clean.startsWith("```") || clean.startsWith("[")) throw Failure(Fault.OUTPUT, "Invalid Gemini response format")
            val j = if (clean.startsWith("{")) JSONObject(clean) else null
            val meaning = FoxSinhalaChat.normalizeMeaning(j?.optString("meaning") ?: clean)
            if (!FoxSinhalaChat.validMeaning(meaning, original)) throw Failure(Fault.OUTPUT, "Sinhala explanation has invalid writing systems")
            val vocab = j?.optJSONArray("vocabulary") ?: JSONArray()
            val seen = HashSet<String>()
            val rows = ArrayList<String>()
            for (i in 0 until minOf(vocab.length(), 3)) {
                val item = vocab.optJSONObject(i) ?: continue
                val w = item.optString("word").trim(); val m = FoxSinhalaChat.normalizeMeaning(item.optString("meaning"))
                if (FoxSmartRules.sourceWord(w, original) && FoxSinhalaChat.validMeaning(m, w) && seen.add(w)) rows.add("$w - $m")
            }
            // Do not throw away a good complete explanation because of one optional invalid vocabulary item.
            return "තේරුම:\n$meaning" + if (rows.isEmpty()) "" else "\n\nවචන සහ තේරුම්:\n" + rows.joinToString("\n")
        }
        if (!FoxSmartRules.allowedText(raw, target, original)) throw Failure(Fault.OUTPUT, "Invalid translation language")
        val en = if (target == "en") raw else ""; val ko = if (target == "ko") raw else ""
        if (SinglishTranslationHints.hasContradictoryIdiom(original, en, ko) || SinglishTranslationHints.hasContradictoryPronoun(original, en, ko))
            throw Failure(Fault.OUTPUT, "Translation meaning conflict")
        return raw.trim()
    }
    private suspend fun network(key: String, model: String, target: String, prompt: String, request: String,
                                correction: Boolean, finalRecovery: Boolean, onQuota: (Long) -> Unit): String = suspendCancellableCoroutine { cont ->
        val active = AtomicReference<HttpURLConnection?>()
        val future = pool.submit {
            var conn: HttpURLConnection? = null
            try {
                if (!cont.isActive) return@submit
                val body = payload(model, target, prompt, request, correction, finalRecovery).toString().toByteArray(Charsets.UTF_8)
                val connection = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
                conn = connection; active.set(connection)
                if (!cont.isActive) return@submit
                connection.requestMethod = "POST"; connection.connectTimeout = 2800; connection.readTimeout = 6500
                connection.instanceFollowRedirects = false; connection.doOutput = true
                connection.setRequestProperty("x-goog-api-key", key)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    if (code == 429) {
                        val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 60L
                        onQuota(seconds.coerceIn(1, 86400) * 1000L)
                        throw Failure(Fault.QUOTA, "Gemini rate limit reached (HTTP 429)")
                    }
                    val keyInvalid = code == 400 && runCatching {
                        val error = connection.errorStream?.bufferedReader()?.use { it.readText().take(12000) }.orEmpty()
                        val details = JSONObject(error).optJSONObject("error")?.optJSONArray("details") ?: JSONArray()
                        (0 until details.length()).any { details.optJSONObject(it)?.optString("reason") == "API_KEY_INVALID" }
                    }.getOrDefault(false)
                    val kind = when { code == 401 || keyInvalid -> Fault.AUTH; code in 500..599 -> Fault.BUSY; else -> Fault.PERMANENT }
                    // 403 may be project policy, region, or permission: don't rotate on ambiguous denial.
                    throw Failure(kind, "Gemini HTTP $code")
                }
                val j = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val candidate = j.optJSONArray("candidates")?.optJSONObject(0) ?: throw Failure(Fault.OUTPUT, "Gemini returned no candidate")
                val finish = candidate.optString("finishReason")
                if (finish.isNotEmpty() && finish != "STOP") throw Failure(if (finish == "MAX_TOKENS") Fault.OUTPUT else Fault.PERMANENT, "Gemini incomplete response: $finish")
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: throw Failure(Fault.OUTPUT, "Gemini returned empty response")
                val text = buildString { for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (!p.optBoolean("thought", false)) append(p.optString("text"))
                } }.trim()
                if (text.isBlank()) throw Failure(Fault.OUTPUT, "Gemini returned empty response")
                if (cont.isActive) cont.resume(text)
            } catch (e: Exception) {
                val failure = when(e) { is Failure -> e; is IOException -> Failure(Fault.NETWORK, "Gemini connection timed out or interrupted"); else -> Failure(Fault.OUTPUT, "Invalid Gemini response") }
                if (cont.isActive) cont.resumeWithException(failure)
            } finally { active.set(null); conn?.disconnect() }
        }
        cont.invokeOnCancellation { future.cancel(true); active.getAndSet(null)?.let { connection ->
            // Disconnect outside the caller thread; cancellation should not stall result delivery.
            Thread { runCatching { connection.disconnect() } }.apply { isDaemon = true; start() }
        } }
    }
}
