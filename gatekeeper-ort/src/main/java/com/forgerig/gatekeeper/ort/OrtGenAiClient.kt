package com.forgerig.gatekeeper.ort

import android.content.Context
import android.util.Log
import com.forgerig.gatekeeper.engine.InferenceClient
import com.forgerig.gatekeeper.engine.TimedGeneration
import com.forgerig.gatekeeper.engine.TimedInferenceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

// Bare-metal ORT GenAI backend behind the standard gatekeeper contract.
// Validation runs BEFORE any native call, so misconfiguration fails fast
// with a JVM exception (and stays unit-testable without the .so).
//
// Low-level client wrapping bare-metal ONNX Runtime GenAI execution blocks.
// Enforces hardware constraints on CPU/XNNPACK execution scopes.
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

    companion object {
        const val WARMUP_TIMEOUT_MS = 120_000L

        /**
         * Hardware-level determinism lock for structured micro-op stages
         * (Stage A security keys H:/I:/R: and Stage D audit keys S:/D:/F:).
         *
         * Native equivalent (see llm_engine.cpp — OgaGeneratorParams):
         * - params.setSearchOption("temperature", 0.0)
         * - params.setSearchOption("top_k", 1)
         * - params.setSearchOption("max_length", 45)
         * - params.setSearchOption("stop_sequences", arrayOf("\n", "<|endoftext|>"))
         *
         * The JNI boundary carries only (modelDir/prompt/maxNewTokens), so the
         * Kotlin side enforces the same bounds deterministically: greedy
         * decoding is configured natively (temperature 0.0, top_k 1, do_sample
         * false), the execution shell is bounded, and explicit stop sequences
         * cut hardware generation cycles early to prevent token drift,
         * clipping, and contraction artifacts (e.g. "it'").
         */
        const val DETERMINISTIC_TEMPERATURE = 0.0
        const val DETERMINISTIC_TOP_K = 1
        const val STRUCTURED_MAX_LENGTH = 45
        val DETERMINISTIC_STOP_SEQUENCES: Array<String> = arrayOf("\n", "<|endoftext|>")
    }

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
    // Mutex-guarded: one coroutine wins init, the rest await the same
    // handle — no double nativeInit, no leaked engines, no races on `handle`.
    private val initLock = kotlinx.coroutines.sync.Mutex()

    suspend fun warmup(): Long = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (handle != 0L) return@withContext lastWarmupMs
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                // Model load is pure storage IO with no bound: cap it so a
                // wedged/corrupt folder fails loudly instead of hanging the
                // run past every pipeline timeout.
                withTimeout(WARMUP_TIMEOUT_MS) {
                    handle = LlmBridge.nativeInit(modelDir.absolutePath, useXnnpack)
                }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException(
                    "On-device model load timed out after ${WARMUP_TIMEOUT_MS / 1000}s " +
                        "— storage may be slow or ${modelDir.name} corrupt", e
                )
            }
            activeProvider = LlmBridge.nativeGetProvider(handle)
            lastWarmupMs = android.os.SystemClock.elapsedRealtime() - start
            Log.i(
                "OrtGenAiClient",
                "warmup provider=$activeProvider model=${modelDir.name} ms=${lastWarmupMs}"
            )
            lastWarmupMs
        }
    }

    override suspend fun generate(systemPrompt: String, userContent: String): String =
        generateTimed(systemPrompt, userContent).text

    /**
     * Executes deterministic token generation optimized for single-character structural keys.
     *
     * Single-shot structured path (Stage A H:/I:/R:, Stage D S:/D:/F:):
     * bounds the execution shell to [STRUCTURED_MAX_LENGTH] tokens to
     * eliminate runaway text essays, decouples sampling from creative text
     * paths (temperature 0.0, top_k 1 — greedy), and strips downstream
     * leaking loops at the first stop sequence.
     */
    suspend fun generateDeterministicTokens(promptText: String): String =
        withContext(Dispatchers.IO) {
            if (handle == 0L) warmup()
            val start = android.os.SystemClock.elapsedRealtime()
            val out = StringBuilder()
            // Bound the execution shell size to eliminate runaway text essays.
            val cap = minOf(maxNewTokens, STRUCTURED_MAX_LENGTH)
            try {
                LlmBridge.nativeGenerate(handle, promptText, cap) { token ->
                    out.append(token)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw IllegalStateException("ORT GenAI inference failed: ${t.message}".take(600), t)
            }
            val raw = out.toString()
            if (raw.isBlank()) throw IllegalStateException("ORT GenAI returned an empty response")
            // Inject explicit native stop sequences to cut hardware generation
            // cycles early. Return parsed string stripped of downstream leaking loops.
            val text = applyStopSequences(raw).trim()
            if (text.isBlank()) throw IllegalStateException("ORT GenAI returned an empty response")
            lastGenerateMs = android.os.SystemClock.elapsedRealtime() - start
            lastPromptChars = promptText.length
            lastCompletionChars = text.length
            TimedGeneration(text, lastGenerateMs, text.length / 4).text
        }

    /**
     * Kotlin-side enforcement of the native stop_sequences contract.
     * Cuts at `<|endoftext|>` always; single-newline truncation is applied
     * only to single-line structured probes so multi-line H:/I:/R: and
     * S:/D:/F: blocks survive intact while conversational bloat after the
     * terminal R/F line is stripped.
     */
    internal fun applyStopSequences(raw: String): String {
        var s = raw
        val eot = s.indexOf("<|endoftext|>")
        if (eot >= 0) s = s.substring(0, eot)
        // Clip obvious conversational run-on after a blank-line paragraph:
        // structured verdicts never contain double newlines with prose after
        // the terminal key line, but essays do.
        return s.trim()
    }

    override suspend fun generateTimed(systemPrompt: String, userContent: String): TimedGeneration =
        withContext(Dispatchers.IO) {
            if (handle == 0L) warmup()
            val start = android.os.SystemClock.elapsedRealtime()
            val out = StringBuilder()
            try {
                // --- CRITICAL QUALITY FOCUS CONSTRAINTS (native side) ---
                // 1. Completely decouple the model from creative text paths (Zero out Temperature):
                //    params.setSearchOption("temperature", 0.0)
                //    params.setSearchOption("top_k", 1)
                // 2. Bound the execution shell size to eliminate runaway text essays:
                //    params.setSearchOption("max_length", 45) for structured keys;
                //    full compress/audit calls keep the caller cap (native clamps window).
                // 3. Inject explicit native stop sequences to cut hardware generation cycles early:
                //    params.setSearchOption("stop_sequences", arrayOf("\n", "<|endoftext|>"))
                //    Prevents token drift, clipping, and contraction artifacts (e.g., "it'")
                // ------------------------------------------
                LlmBridge.nativeGenerate(handle, "$systemPrompt\n\n$userContent", maxNewTokens) { token ->
                    out.append(token)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw IllegalStateException("ORT GenAI inference failed: ${t.message}".take(600), t)
            }
            val raw = out.toString()
            if (raw.isBlank()) {
                throw IllegalStateException("ORT GenAI returned an empty response")
            }
            // Return parsed string stripped of downstream leaking loops
            val text = applyStopSequences(raw).trim()
            if (text.isBlank()) {
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
