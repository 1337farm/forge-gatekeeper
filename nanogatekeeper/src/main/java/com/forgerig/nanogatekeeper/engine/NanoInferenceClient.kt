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

    override suspend fun generate(systemPrompt: String, userContent: String): String {
        val combined = "$systemPrompt\n\n<<<USER>>>\n$userContent\n<<<END>>>"
        val response = model.generateContent(combined)
        return response.text?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("AICore returned an empty response")
    }

    fun prepare() {
        model.prepareInferenceEngine()
    }

    fun close() {
        runCatching { model.close() }
    }
}
