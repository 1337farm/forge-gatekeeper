package com.forgerig.gatekeeper.ort

import android.content.Context
import com.forgerig.gatekeeper.engine.InferenceClient
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
) : InferenceClient, AutoCloseable {

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

    override suspend fun generate(systemPrompt: String, userContent: String): String =
        withContext(Dispatchers.IO) {
            if (handle == 0L) {
                handle = LlmBridge.nativeInit(modelDir.absolutePath, useXnnpack)
                activeProvider = LlmBridge.nativeGetProvider(handle)
            }
            val out = StringBuilder()
            try {
                LlmBridge.nativeGenerate(handle, "$systemPrompt\n\n$userContent", maxNewTokens) { token ->
                    out.append(token)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw IllegalStateException("ORT GenAI inference failed: ${t.message}".take(600), t)
            }
            out.toString().ifBlank {
                throw IllegalStateException("ORT GenAI returned an empty response")
            }
        }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            runCatching { LlmBridge.nativeRelease(h) }
        }
    }
}
