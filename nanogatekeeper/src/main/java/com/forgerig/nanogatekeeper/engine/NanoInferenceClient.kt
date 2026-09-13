package com.forgerig.nanogatekeeper.engine

import android.content.Context
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig

interface NanoInferenceClient {
    suspend fun generate(systemPrompt: String, userContent: String): String
}

class AICoreInferenceClient(appContext: Context) : NanoInferenceClient {
    private val app: Context = appContext.applicationContext

    // Built lazily so mere construction never touches the NPU/service.
    private val model: GenerativeModel by lazy {
        val config = generationConfig {
            context = app
            temperature = 0f
            topK = 1
        }
        GenerativeModel(config, DownloadConfig())
    }

    @Volatile
    private var prepared = false

    override suspend fun generate(systemPrompt: String, userContent: String): String {
        // Documented warmup path: moves engine init overhead out of the timed
        // call and gives a missing/unprovisioned model its best chance to
        // surface before we spend the execution budget.
        if (!prepared) {
            runCatching { model.prepareInferenceEngine() }
            prepared = true
        }
        val combined = "$systemPrompt\n\n<<<USER>>>\n$userContent\n<<<END>>>"
        try {
            val response = model.generateContent(combined)
            return response.text?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("AICore returned an empty response")
        } catch (t: Throwable) {
            throw mapToActionable(t)
        }
    }

    // Raw SDK errors (e.g. "8-NOT_AVAILABLE: Required LLM feature not found")
    // are undebuggable on-device. Append remediation for known signatures.
    // Never includes user content: SDK messages carry only service state.
    private fun mapToActionable(t: Throwable): Throwable {
        if (t is kotlinx.coroutines.CancellationException) return t
        val msg = t.message ?: t.javaClass.simpleName
        val lower = msg.lowercase()
        val hint = when {
            "not_available" in lower || "required llm feature not found" in lower ->
                "Remedy: Gemini Nano is not provisioned on this device. Requires " +
                    "Pixel 8 Pro/9+, Android 14+, the AICore beta app, " +
                    "aicore-experimental enrollment, and the downloaded on-device model."
            "allowlist" in lower ->
                "Remedy: this app package is not allowlisted for AICore experimental access."
            "download" in lower ->
                "Remedy: the on-device model may still be downloading; retry in a minute."
            else -> null
        }
        val full = buildString {
            append("AICore inference failed: ").append(msg)
            if (hint != null) append(" — ").append(hint)
        }.take(600)
        return IllegalStateException(full, t)
    }

    fun close() {
        runCatching { model.close() }
    }
}
