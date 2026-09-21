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
        // Order matters: libonnxruntime-genai.so DT_NEEDEDs libonnxruntime.so
        // (plus libmat.so inside AARs), so the plain runtime must be pulled
        // into the linker namespace FIRST — otherwise dlopen of the GenAI
        // lib explodes with `dlopen failed: library "libmat.so" not found`
        // style cascades. Keep this static init even if unused so that
        // loading LlmBridge always preloads the graph (idempotent, burnt
        // once per process).
        runCatching { System.loadLibrary("onnxruntime") }
        runCatching { System.loadLibrary("onnxruntime-genai") }
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
    // Throws RuntimeException on inference failure, CancellationException
    // when nativeCancelGenerate() aborted the decode loop.
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        listener: TokenListener
    ): Int

    // Hard-cancel hook: bumps the native decode-loop epoch so any in-flight
    // nativeGenerate aborts at its next token instead of running to cap.
    external fun nativeCancelGenerate()

    external fun nativeRelease(handle: Long)
}
