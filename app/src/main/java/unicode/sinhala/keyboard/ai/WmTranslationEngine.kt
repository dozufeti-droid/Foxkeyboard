package unicode.sinhala.keyboard.ai

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Translation-mode choices with the same meanings as WM's menu. */
data class WmTranslationRequest(
    val text: String,
    val source: String = "auto",
    val target: String,
    val mode: Mode = Mode.ONLINE
) {
    enum class Mode { ONLINE, ON_DEVICE, AUTOMATIC, QUALITY_AI }
}

data class WmTranslationResult(val text: String, val via: String)

/**
 * This is an additive integration; it is not WM Keyboard's complete native
 * Bergamot/NLLB distribution. On-device uses ML Kit supported language pairs.
 * Sinhala has no ML Kit translation model; explicitly return a useful error.
 */
object WmTranslationEngine {

    // V6 fast path: do not download or silently call online services in one-tap
    // Offline mode. Users download EN/KO packs explicitly first.
    suspend fun translateDownloaded(req: WmTranslationRequest): WmTranslationResult =
        translateOnDevice(req, allowDownload = false)

    suspend fun translate(context: Context, req: WmTranslationRequest): WmTranslationResult {
        require(req.text.isNotBlank()) { "Enter text first" }
        require(req.target in setOf("si", "en", "ko")) { "Only Sinhala, English and Korean" }
        require(req.source in setOf("auto", "si", "en", "ko")) { "Unsupported source language" }
        require(req.text.length <= GoogleWebTranslateProvider.MAX_CHARS) {
            "Maximum 2500 characters"
        }
        if (req.source == req.target) return WmTranslationResult(req.text, "Same language")
        return when (req.mode) {
            WmTranslationRequest.Mode.ONLINE -> translateOnline(context, req)
            WmTranslationRequest.Mode.QUALITY_AI -> translateQualityAi(context, req)
            WmTranslationRequest.Mode.ON_DEVICE -> translateOnDevice(req, allowDownload = true)
            WmTranslationRequest.Mode.AUTOMATIC -> {
                // Sinhala/Singlish is never silently sent to an EN/KO offline
                // engine. Do NOT retry online calls on failure (prevents a
                // second 429 request, a bug in V4).
                val looksSinhala = req.source == "si" ||
                    req.text.any { it in '\u0D80'..'\u0DFF' }
                if (looksSinhala || req.target == "si") {
                    translateOnline(context, req)
                } else {
                    try {
                        // First use an already-downloaded local model.
                        translateOnDevice(req, allowDownload = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // One online attempt only, if no offline model exists.
                        translateOnline(context, req)
                    }
                }
            }
        }
    }





    /** Every online/quality request uses the saved Gemini model and key. */
    private suspend fun translateOnline(context: Context, req: WmTranslationRequest): WmTranslationResult =
        translateQualityAi(context, req)

    private fun translateQualityAi(context: Context, req: WmTranslationRequest): WmTranslationResult =
        preferredAiTranslation(context, req, "Google Gemini")

    private fun preferredAiTranslation(
        context: Context,
        req: WmTranslationRequest,
        provider: String
    ): WmTranslationResult {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val model = prefs.getString("ai_model_$provider", "")?.trim().orEmpty()
        val key = ApiKeyStore(context).load(provider).orEmpty()
        require(model.isNotBlank() && key.isNotBlank()) { "AI provider key/model missing" }
        require(req.text.length <= 2000) { "AI message max 2000 characters" }
        val translated = if (req.target == "si") {
            AiSinhalaTranslator.translate(req.text, model, key, provider)
        } else {
            val pair = AiTranslator.translate(req.text, model, key, provider)
            if (req.target == "en") pair.first else pair.second
        }
        return WmTranslationResult(translated, "$provider (chat AI)")
    }

    /** Downloads only the EN/KO models on user action; never silently. */
    suspend fun downloadEnglishKoreanModels() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
        val client = Translation.getClient(options)
        try { client.downloadModelIfNeeded().result() }
        finally { client.close() }
    }

    private suspend fun translateOnDevice(
        req: WmTranslationRequest,
        allowDownload: Boolean
    ): WmTranslationResult {
        val language = if (req.source == "auto") {
            val client = LanguageIdentification.getClient()
            try { client.identifyLanguage(req.text).result() }
            finally { client.close() }
        } else req.source
        if (language == "und") error("Could not detect the source language")
        if (language !in setOf("en", "ko") || req.target !in setOf("en", "ko")) {
            error("Offline supports English and Korean only. Sinhala needs Online AI.")
        }
        if (language == req.target) return WmTranslationResult(req.text, "On device")
        val from = TranslateLanguage.fromLanguageTag(language)
            ?: error("No offline model for ${language}. Select Online.")
        val to = TranslateLanguage.fromLanguageTag(req.target)
            ?: error("No offline model for ${req.target}. Select Online.")

        val manager = RemoteModelManager.getInstance()
        if (!allowDownload) {
            val sourceModel = TranslateRemoteModel.Builder(from).build()
            val targetModel = TranslateRemoteModel.Builder(to).build()
            if (!manager.isModelDownloaded(sourceModel).result() ||
                !manager.isModelDownloaded(targetModel).result()) {
                error("Offline model not downloaded")
            }
        }

        val client = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
        )
        try {
            // On device is an explicit choice: ML Kit may download a language
            // model the first time. Automatic mode never triggers download.
            if (allowDownload) client.downloadModelIfNeeded().result()
            return WmTranslationResult(client.translate(req.text).result(), "On device (ML Kit)")
        } finally {
            client.close()
        }
    }

    private suspend fun <T> Task<T>.result(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { failure ->
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.cancel()
        }
    }
}
