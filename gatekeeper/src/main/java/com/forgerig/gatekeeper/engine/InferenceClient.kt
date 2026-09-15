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