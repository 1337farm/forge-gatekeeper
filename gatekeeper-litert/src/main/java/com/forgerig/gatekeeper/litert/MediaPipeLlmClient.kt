package com.forgerig.gatekeeper.litert

import android.content.Context
import android.util.Log
import com.forgerig.gatekeeper.engine.InferenceClient
import com.forgerig.gatekeeper.engine.TimedGeneration
import com.forgerig.gatekeeper.engine.TimedInferenceClient
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Device-universal local-LLM backend: runs a `.task` model (e.g. Gemma 3n)
// fully on-device via MediaPipe LLM Inference (CPU+XNNPack, GPU where
// available). Same InferenceClient contract as the bare-metal ORT backend, so
// the gatekeeper pipeline (mutex, audit loop, telemetry) is unchanged.
class MediaPipeLlmClient(
    appContext: Context,
    private val modelFile: File,
    private val maxTokens: Int = 2048,
    private val maxTopK: Int = 40
) : InferenceClient, TimedInferenceClient, AutoCloseable {

    constructor(appContext: Context, modelFile: File) : this(
        appContext,
        modelFile,
        2048,
        40
    )

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

    @Volatile
    var lastWarmupMs: Long = -1L
        private set

    @Volatile
    var lastGenerateMs: Long = -1L
        private set

    // First-touch init keeps cold start off the Activity path and logs the
    // one-time cost (session/model load) separately from decode.
    suspend fun warmup(): Long = withContext(Dispatchers.IO) {
        if (llmRef.isInitialized()) return@withContext lastWarmupMs
        val start = android.os.SystemClock.elapsedRealtime()
        llm // force lazy session creation
        lastWarmupMs = android.os.SystemClock.elapsedRealtime() - start
        Log.i(
            "MediaPipeLlmClient",
            "warmup model=${modelFile.name} bytes=${modelFile.length()} ms=$lastWarmupMs"
        )
        lastWarmupMs
    }

    override suspend fun generate(systemPrompt: String, userContent: String): String =
        generateTimed(systemPrompt, userContent).text

    override suspend fun generateTimed(systemPrompt: String, userContent: String): TimedGeneration =
        withContext(Dispatchers.IO) {
            if (!llmRef.isInitialized()) warmup()
            val start = android.os.SystemClock.elapsedRealtime()
            val text = try {
                llm.generateResponse("$systemPrompt\n\n<<<USER>>>\n$userContent\n<<<END>>>")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw IllegalStateException("Local LLM failed: ${t.message}".take(600), t)
            }
            lastGenerateMs = android.os.SystemClock.elapsedRealtime() - start
            val tps = if (lastGenerateMs > 0) (text.length / 4.0) / (lastGenerateMs / 1000.0) else 0.0
            Log.i(
                "MediaPipeLlmClient",
                "generate ms=$lastGenerateMs completionChars=${text.length} " +
                    "tps~${"%.1f".format(tps)}"
            )
            TimedGeneration(text, lastGenerateMs, text.length / 4)
        }

    override fun close() {
        if (llmRef.isInitialized()) runCatching { llmRef.value.close() }
    }
}
