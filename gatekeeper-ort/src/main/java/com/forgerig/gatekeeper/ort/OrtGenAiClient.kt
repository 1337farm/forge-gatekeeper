package com.forgerig.gatekeeper.ort

import android.content.Context
import android.util.Log
import com.forgerig.gatekeeper.engine.InferenceClient
import com.forgerig.gatekeeper.engine.TimedGeneration
import com.forgerig.gatekeeper.engine.TimedInferenceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Bare-metal ORT GenAI backend behind the standard gatekeeper contract.
// Validation runs BEFORE any native call, so misconfiguration fails fast
// with a JVM exception (and stays unit-testable without the .so).
class OrtGenAiClient(
    appContext: Context,
    private val modelDir: File,
    private val maxNewTokens: Int = 512,
    private val useXnnpack: Boolean = true
) : InferenceClient, TimedInferenceClient, AutoCloseable {

    constructor(appContext: Context, modelDir: File) : this(
        appContext,
        modelDir,
        512,
        true
    )

    @Suppress("unused")
    private val app: Context = appContext.applicationContext

    init {
        OrtModelDir.requireValid(modelDir)
        require(maxNewTokens in 1..4096) { "maxNewTokens must be 1..4096" }
    }

    @Volatile
    private var handle: Long = 0L

    @Volatile
    var activeProvider: String = "unknown"
        private set

    @Volatile
    var lastWarmupMs: Long = -1L
        private set

    @Volatile
    var lastGenerateMs: Long = -1L
        private set

    @Volatile
    var lastPromptChars: Int = 0
        private set

    @Volatile
    var lastCompletionChars: Int = 0
        private set

    // First-touch init keeps cold start off the Activity path and logs the
    // one-time cost (GenAI model/tokenizer load) separately from decode.
    suspend fun warmup(): Long = withContext(Dispatchers.IO) {
        if (handle != 0L) return@withContext lastWarmupMs
        val start = android.os.SystemClock.elapsedRealtime()
        handle = LlmBridge.nativeInit(modelDir.absolutePath, useXnnpack)
        activeProvider = LlmBridge.nativeGetProvider(handle)
        lastWarmupMs = android.os.SystemClock.elapsedRealtime() - start
        Log.i(
            "OrtGenAiClient",
            "warmup provider=$activeProvider model=${modelDir.name} ms=${lastWarmupMs}"
        )
        lastWarmupMs
    }

    override suspend fun generate(systemPrompt: String, userContent: String): String =
        generateTimed(systemPrompt, userContent).text

    override suspend fun generateTimed(systemPrompt: String, userContent: String): TimedGeneration =
        withContext(Dispatchers.IO) {
            if (handle == 0L) warmup()
            val start = android.os.SystemClock.elapsedRealtime()
            val out = StringBuilder()
            try {
                LlmBridge.nativeGenerate(handle, "$systemPrompt\n\n$userContent", maxNewTokens) { token ->
                    out.append(token)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw IllegalStateException("ORT GenAI inference failed: ${t.message}".take(600), t)
            }
            val text = out.toString().ifBlank {
                throw IllegalStateException("ORT GenAI returned an empty response")
            }
            lastGenerateMs = android.os.SystemClock.elapsedRealtime() - start
            lastPromptChars = systemPrompt.length + userContent.length
            lastCompletionChars = text.length
            val tps = if (lastGenerateMs > 0) (text.length / 4.0) / (lastGenerateMs / 1000.0) else 0.0
            Log.i(
                "OrtGenAiClient",
                "generate provider=$activeProvider ms=${lastGenerateMs} " +
                    "promptChars=$lastPromptChars completionChars=$lastCompletionChars " +
                    "tps~${"%.1f".format(tps)}"
            )
            TimedGeneration(text, lastGenerateMs, text.length / 4)
        }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            runCatching { LlmBridge.nativeRelease(h) }
        }
    }
}
