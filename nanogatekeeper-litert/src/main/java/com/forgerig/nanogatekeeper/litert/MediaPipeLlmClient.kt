package com.forgerig.nanogatekeeper.litert

import android.content.Context
import com.forgerig.nanogatekeeper.engine.NanoInferenceClient
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Device-universal local-LLM backend: runs a `.task` model (e.g. Gemma 3n)
// fully on-device via MediaPipe LLM Inference (CPU+XNNPack, GPU where
// available). Same NanoInferenceClient contract as the AICore backend, so the
// gatekeeper pipeline (mutex, audit loop, telemetry) is unchanged.
class MediaPipeLlmClient(
    appContext: Context,
    modelFile: File,
    private val maxTokens: Int = 2048,
    private val maxTopK: Int = 40
) : NanoInferenceClient, AutoCloseable {

    private val app: Context = appContext.applicationContext

    init {
        require(modelFile.isFile && modelFile.length() > 0) {
            "Model .task missing or empty: ${modelFile.absolutePath}"
        }
    }

    private val llmRef = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(maxTokens)
            .setMaxTopK(maxTopK)
            .build()
        LlmInference.createFromOptions(app, options)
    }
    private val llm: LlmInference get() = llmRef.value

    override suspend fun generate(systemPrompt: String, userContent: String): String =
        withContext(Dispatchers.IO) {
            try {
                llm.generateResponse("$systemPrompt\n\n<<<USER>>>\n$userContent\n<<<END>>>")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw IllegalStateException("Local LLM failed: ${t.message}".take(600), t)
            }
        }

    override fun close() {
        if (llmRef.isInitialized()) runCatching { llmRef.value.close() }
    }
}
