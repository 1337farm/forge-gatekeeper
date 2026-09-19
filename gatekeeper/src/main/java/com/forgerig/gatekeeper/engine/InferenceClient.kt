package com.forgerig.gatekeeper.engine

// Contract for any on-device local-LLM backend (ONNX Runtime GenAI via the
// bare-metal :gatekeeper-ort module, MediaPipe .task via :gatekeeper-litert,
// or a consumer-supplied client). Every backend runs a model the app or
// consumer downloads itself; there is no built-in/provisioned LLM dependency.
interface InferenceClient {
    suspend fun generate(systemPrompt: String, userContent: String): String
}

// Timed generation: wall-clock decode duration plus a best-effort token
// count so callers can render tokens/sec without touching the model.
data class TimedGeneration(
    val text: String,
    val generationMs: Long,
    val completionTokens: Int = 0
)

interface TimedInferenceClient : InferenceClient {
    suspend fun generateTimed(systemPrompt: String, userContent: String): TimedGeneration
}

// Live token streaming: cumulative decoded text is pushed to onToken as it
// arrives (throttled by the backend) so callers can render progress instead
// of staring at a silent 30s inference window. Backends that cannot stream
// simply don't implement this — the engine falls back to generateTimed /
// generate and onToken stays silent. The returned TimedGeneration carries
// the final trimmed text plus timing, exactly like generateTimed.
interface StreamingInferenceClient : InferenceClient {
    suspend fun generateStreaming(
        systemPrompt: String,
        userContent: String,
        onToken: (cumulative: String) -> Unit
    ): TimedGeneration
}