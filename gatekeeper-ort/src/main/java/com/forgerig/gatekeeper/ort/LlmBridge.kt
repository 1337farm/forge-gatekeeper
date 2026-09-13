package com.forgerig.gatekeeper.ort

// Thin JNI boundary to libllm_engine.so (ONNX Runtime GenAI, C API).
//
// Hot-path rule: only primitive strings cross in either direction.
// - In:  modelDir / prompt / maxNewTokens (configured once / per call).
// - Out: one String per generated token via TokenListener.
// The listener object itself is passed once per generate() call (setup, not
// the hot path) and never stored natively beyond the call.
object LlmBridge {

    init {
        System.loadLibrary("llm_engine")
    }

    fun interface TokenListener {
        fun onToken(token: String)
    }

    // Returns a native engine handle (>0). Throws RuntimeException on failure.
    external fun nativeInit(modelDir: String, useXnnpack: Boolean): Long

    // Effective execution provider for a handle ("XNNPACK" or "CPU").
    external fun nativeGetProvider(handle: Long): String

    // Streams up to maxNewTokens tokens; returns the generated token count.
    // Throws RuntimeException on inference failure.
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        listener: TokenListener
    ): Int

    external fun nativeRelease(handle: Long)
}
