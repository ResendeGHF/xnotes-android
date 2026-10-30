package com.xnotes.platform

import android.content.Context
import com.xnotes.core.text.VlmLatex
import com.xnotes.core.text.VlmMarkdown
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * API keys for whole-note LaTeX export. Kept in a private SharedPreferences file
 * rather than [com.xnotes.settings.Preferences] so settings backups never carry
 * them anywhere.
 */
object LatexAiKeys {
    private const val FILE = "latex_ai"
    private const val KEY = "api_key"

    fun get(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun put(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, value.trim()).apply()
    }
}

/** Which vision-model API converts note pages to LaTeX. */
enum class LatexAiProvider(val id: String) {
    GEMINI("gemini"),
    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    /** A user-supplied endpoint speaking the OpenAI chat-completions format (self-hosted models and other services). */
    CUSTOM("custom"),
    ;

    companion object {
        fun ofId(id: String): LatexAiProvider = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

/** Provider, model and endpoint for one conversion. Keys stay out of here; see [LatexAiKeys]. */
data class LatexAiConfig(val provider: LatexAiProvider, val apiKey: String, val model: String, val baseUrl: String) {
    companion object {
        fun defaultModel(provider: LatexAiProvider): String = when (provider) {
            LatexAiProvider.GEMINI -> "gemini-2.0-flash"
            LatexAiProvider.OPENAI -> "gpt-4o-mini"
            LatexAiProvider.ANTHROPIC -> "claude-sonnet-4-5"
            // The model name always comes from Settings for custom endpoints.
            LatexAiProvider.CUSTOM -> ""
        }

        fun defaultBaseUrl(provider: LatexAiProvider): String = when (provider) {
            LatexAiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
            LatexAiProvider.OPENAI -> "https://api.openai.com/v1"
            LatexAiProvider.ANTHROPIC -> "https://api.anthropic.com/v1"
            // Custom endpoints always come from Settings; there is no default to guess.
            LatexAiProvider.CUSTOM -> ""
        }

        /** Providers that refuse unauthenticated calls. OpenAI-compatible and custom servers may run without a key. */
        fun needsApiKey(provider: LatexAiProvider): Boolean =
            provider == LatexAiProvider.GEMINI || provider == LatexAiProvider.ANTHROPIC
    }
}

/**
 * Sends page renders to the user's vision-model provider and returns cleaned LaTeX.
 * Blocks on HTTP; call off the main thread. Throws with the provider's own message
 * when the call fails, so the export dialog can report it.
 */
object VlmClient {

    /** The document format a page is converted to; the transport is identical, only the prompt and cleanup differ. */
    enum class VlmFormat { LATEX, MARKDOWN }

    /** Convert one rendered page ([png] bytes) to its body text, or "" when the model returned nothing usable. */
    fun convertPage(png: ByteArray, pageIndex: Int, pageCount: Int, config: LatexAiConfig, format: VlmFormat = VlmFormat.LATEX): String {
        if (config.apiKey.isBlank() && LatexAiConfig.needsApiKey(config.provider)) throw IllegalStateException("missing API key")
        val prompt = when (format) {
            VlmFormat.LATEX -> VlmLatex.promptFor(pageIndex, pageCount)
            VlmFormat.MARKDOWN -> VlmMarkdown.promptFor(pageIndex, pageCount)
        }
        val raw = when (config.provider) {
            LatexAiProvider.GEMINI -> callGemini(png, prompt, config)
            LatexAiProvider.ANTHROPIC -> callAnthropic(png, prompt, config)
            // Custom endpoints speak the OpenAI chat-completions format by contract.
            LatexAiProvider.OPENAI, LatexAiProvider.CUSTOM -> callOpenAi(png, prompt, config)
        }
        return when (format) {
            VlmFormat.LATEX -> VlmLatex.sanitize(raw)
            VlmFormat.MARKDOWN -> VlmMarkdown.sanitize(raw)
        }
    }

    private fun base64(png: ByteArray): String =
        android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)

    private fun post(url: String, headers: Map<String, String>, body: JSONObject, readTimeoutMs: Int = 180_000): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
            if (code !in 200..299) throw IllegalStateException(errorMessage(text, code))
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(body: String, code: Int): String {
        val msg = runCatching {
            val root = JSONObject(body)
            // Gemini shape first, then the OpenAI/Anthropic shape.
            root.optJSONObject("error")?.let {
                it.optString("message").takeIf { m -> m.isNotEmpty() }
                    ?: it.optString("type").takeIf { t -> t.isNotEmpty() }
            } ?: root.optString("message").takeIf { it.isNotEmpty() }
        }.getOrNull()
        return if (!msg.isNullOrEmpty()) "$msg (HTTP $code)" else "request failed (HTTP $code)"
    }

    private fun callGemini(png: ByteArray, prompt: String, config: LatexAiConfig): String {
        val model = config.model.ifBlank { LatexAiConfig.defaultModel(LatexAiProvider.GEMINI) }
        val base = config.baseUrl.ifBlank { LatexAiConfig.defaultBaseUrl(LatexAiProvider.GEMINI) }.trimEnd('/')
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/png")
                    .put("data", base64(png)))))))
            .put("generationConfig", JSONObject()
                .put("maxOutputTokens", 4096)
                .put("temperature", 0.1))
        val res = post("$base/models/$model:generateContent?key=${config.apiKey}", emptyMap(), body)
        val parts = res.optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("empty Gemini response")
        return buildString {
            for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
    }

    private fun callOpenAi(png: ByteArray, prompt: String, config: LatexAiConfig): String {
        val model = config.model.ifBlank { LatexAiConfig.defaultModel(LatexAiProvider.OPENAI) }
        val base = config.baseUrl.ifBlank { LatexAiConfig.defaultBaseUrl(LatexAiProvider.OPENAI) }.trimEnd('/')
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("max_tokens", 4096)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                        .put("url", "data:image/png;base64,${base64(png)}"))))))
        val headers = if (config.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${config.apiKey}")
        val res = post("$base/chat/completions", headers, body)
        val message = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("empty model response")
        val direct = message.optString("content")
        if (direct.isNotEmpty()) return direct
        val parts = message.optJSONArray("content") ?: throw IllegalStateException("empty model response")
        return buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i)
                if (p?.optString("type") == "text") append(p.optString("text"))
            }
        }
    }

    private fun callAnthropic(png: ByteArray, prompt: String, config: LatexAiConfig): String {
        val model = config.model.ifBlank { LatexAiConfig.defaultModel(LatexAiProvider.ANTHROPIC) }
        val base = config.baseUrl.ifBlank { LatexAiConfig.defaultBaseUrl(LatexAiProvider.ANTHROPIC) }.trimEnd('/')
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "image").put("source", JSONObject()
                        .put("type", "base64")
                        .put("media_type", "image/png")
                        .put("data", base64(png))))
                    .put(JSONObject().put("type", "text").put("text", prompt)))))
        val res = post(
            "$base/v1/messages",
            mapOf("x-api-key" to config.apiKey, "anthropic-version" to "2023-06-01"),
            body,
        )
        val content = res.optJSONArray("content") ?: throw IllegalStateException("empty Claude response")
        return buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i)
                if (block?.optString("type") == "text") append(block.optString("text"))
            }
        }
    }
}
