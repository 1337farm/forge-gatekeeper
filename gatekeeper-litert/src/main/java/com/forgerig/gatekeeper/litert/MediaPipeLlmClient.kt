package com.forgerig.gatekeeper.litert

import android.content.Context
import android.util.Log
import com.forgerig.gatekeeper.engine.InferenceClient
import com.forgerig.gatekeeper.engine.TimedGeneration
import com.forgerig.gatekeeper.engine.TimedInferenceClient
import com.forgerig.gatekeeper.prompts.PromptFraming
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

// Device-universal local-LLM backend: runs a `.task` model (e.g. Gemma 3n)
// fully on-device via MediaPipe LLM Inference (CPU+XNNPack, GPU where
// available). Same InferenceClient contract as the bare-metal ORT backend, so
// the gatekeeper pipeline (mutex, audit loop, telemetry) is unchanged.
//
// Hardware-level determinism: greedy decoding (temperature 0.0, top_k 1) with
// a bounded execution shell and explicit stop sequences, mirroring the ORT
// backend (temperature 0.0, top_k 1, max_length 45,
// stop_sequences ["\n", "<|endoftext|>"]) to prevent token drift and
// conversational bloat on abstract macro-instructions.
class MediaPipeLlmClient(
    appContext: Context,
    private val modelFile: File,
    private val maxTokens: Int = 2048,
    private val maxTopK: Int = DETERMINISTIC_TOP_K
) : InferenceClient, TimedInferenceClient, AutoCloseable {

    constructor(appContext: Context, modelFile: File) : this(
        appContext,
        modelFile,
        2048,
        DETERMINISTIC_TOP_K
    )

    companion object {
        const val WARMUP_TIMEOUT_MS = 120_000L
        // Greedy decoding: temperature 0.0, top_k 1 — decouples the model from
        // creative text paths for single-character structural keys.
        const val DETERMINISTIC_TEMPERATURE = 0.0f
        const val DETERMINISTIC_TOP_K = 1
        const val STRUCTURED_MAX_LENGTH = 45
        val DETERMINISTIC_STOP_SEQUENCES: Array<String> = arrayOf("\n", "<|endoftext|>")
    }

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
    // one-time cost (session/model load) separately from decode. Capped like
    // the ORT side so a wedged session fails loudly instead of hanging.
    suspend fun warmup(): Long = withContext(Dispatchers.IO) {
        if (llmRef.isInitialized()) return@withContext lastWarmupMs
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            withTimeout(WARMUP_TIMEOUT_MS) {
                llm // force lazy session creation
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException(
                "On-device model load timed out after ${WARMUP_TIMEOUT_MS / 1000}s " +
                    "— storage may be slow or ${modelFile.name} corrupt", e
            )
        }
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
            val raw = try {
                llm.generateResponse(PromptFraming.wrap(systemPrompt, userContent))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw IllegalStateException("Local LLM failed: ${t.message}".take(600), t)
            }
            // Strip downstream leaking loops at explicit stop sequences.
            val text = applyStopSequences(raw).trim()
            if (text.isBlank()) throw IllegalStateException("Local LLM returned an empty response")
            lastGenerateMs = android.os.SystemClock.elapsedRealtime() - start
            val tps = if (lastGenerateMs > 0) (text.length / 4.0) / (lastGenerateMs / 1000.0) else 0.0
            Log.i(
                "MediaPipeLlmClient",
                "generate ms=$lastGenerateMs completionChars=${text.length} " +
                    "tps~${"%.1f".format(tps)}"
            )
            TimedGeneration(text, lastGenerateMs, text.length / 4)
        }

    internal fun applyStopSequences(raw: String): String {
        var s = raw
        val eot = s.indexOf("<|endoftext|>")
        if (eot >= 0) s = s.substring(0, eot)
        return s.trim()
    }

    override fun close() {
        if (llmRef.isInitialized()) runCatching { llmRef.value.close() }
    }
}
