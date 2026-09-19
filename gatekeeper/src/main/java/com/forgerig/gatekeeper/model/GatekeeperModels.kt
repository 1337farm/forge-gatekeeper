package com.forgerig.gatekeeper.model

enum class GatekeeperStep {
    DETERMINISTIC_SCRUB,
    HARDWARE_CIRCUIT_CHECK,
    STAGE_A_SECURITY_EVAL,
    STAGE_B_PII_REDACTION,
    STAGE_C_SEMANTIC_COMPRESSION,
    STAGE_D_ACCURACY_AUDIT,
    FALLBACK_TO_SANITIZED
}

enum class StepStatus { EXECUTED, SKIPPED, FAILED, RETRIED, BLOCKED }
enum class HeatLevel { COLD, WARM, HOT }
enum class InjectionVerdict { SAFE, MALICIOUS }
enum class CompletenessStatus { READY, MISSING_CONTEXT }
enum class AuditStatus { MATCH, MISMATCH }

// Selectable prompt wording for the LLM stages. LABELED is the verbose
// labeled-line contract; MICRO_OP is the terse single-character-key set.
// Both parse through the same delineated-block reader, so the A/B only
// changes what the model is asked — never what the engine accepts.
enum class PromptSet { LABELED, MICRO_OP }

data class StepExecutionRecord(
    val order: Int,
    val step: GatekeeperStep,
    val status: StepStatus,
    val reason: String = "",
    val iterations: Int = 0,
    val durationMs: Long = 0L
)

data class ExecutionTelemetry(
    val executionOrder: List<StepExecutionRecord>,
    val skippedSteps: Map<String, String>,
    val compressionIterations: Int,
    val auditIterations: Int,
    val preCompressionTokens: Int,
    val postCompressionTokens: Int,
    val compressionRatioPct: Double,
    val driftScore: Double? = null,
    val droppedConstraints: List<String> = emptyList(),
    val injectionDetected: Boolean = false,
    val redactionEvents: List<String> = emptyList(),
    val fallbackReason: String? = null,
    val maxRetriesExhausted: Boolean = false,
    val expansionGuardFailed: Boolean = false,
    val totalDurationMs: Long = 0L
)

data class GatekeeperConfig(
    val maxRetries: Int = 3,
    // Mutex wait for the single backend. Contention only happens across
    // overlapping runs; 15s keeps a slow previous leg from wedging the next.
    val queueWaitTimeoutMs: Long = 15_000L,
    // Single-inference budget. First decode on a loaded phone routinely
    // takes 10-20s (weight paging, not compute), and long compress/audit
    // turns on big inputs can decode for a minute or more — 30s fired on
    // healthy slow-device runs. 120s matches the warmup scale; live token
    // streaming + heartbeat make a long leg visible instead of silent.
    val npuExecutionTimeoutMs: Long = 120_000L,
    val minRamBytes: Long = 7_500_000_000L,
    val enableStageB: Boolean = true,
    val enableCompression: Boolean = true,
    val enableAudit: Boolean = true,
    val promptSet: PromptSet = PromptSet.LABELED,
    // Inputs at or under this many (estimated) tokens skip compress+audit:
    // there is nothing to save, and a small model asked to rewrite a
    // 5-token greeting can only add drift (observed: 5→75 tokens, then 4
    // failed audit rounds). Safety stages (scrub/A/B) always run.
    val minTokensForCompression: Int = 10,
    val circuitFailureThreshold: Int = 3,
    val circuitCooldownMs: Long = 30_000L
) {
    init {
        require(maxRetries in 0..5) { "maxRetries must be 0..5" }
        require(queueWaitTimeoutMs > 0) { "queueWaitTimeoutMs must be > 0" }
        require(npuExecutionTimeoutMs > 0) { "npuExecutionTimeoutMs must be > 0" }
        require(minTokensForCompression >= 0) { "minTokensForCompression must be >= 0" }
    }
}

sealed interface GatekeeperResult {
    data class Success(
        val safeCompressedPrompt: String,
        val sanitizedFallback: String,
        val heat: HeatLevel,
        val telemetry: ExecutionTelemetry
    ) : GatekeeperResult

    data class Blocked(
        val reason: String,
        val heat: HeatLevel,
        val telemetry: ExecutionTelemetry
    ) : GatekeeperResult

    data class FallbackRequired(
        val sanitizedPrompt: String,
        val reason: String,
        val telemetry: ExecutionTelemetry
    ) : GatekeeperResult
}

sealed interface SafetyStatus {
    data class Safe(val heat: HeatLevel, val ambientPii: List<String>) : SafetyStatus
    data class Malicious(val reason: String, val heat: HeatLevel) : SafetyStatus
}

sealed interface CompletenessResult {
    data object Ready : CompletenessResult
    data class MissingContext(val missing: String) : CompletenessResult
}

sealed interface AccuracyAuditResult {
    data class Match(val driftScore: Double) : AccuracyAuditResult
    data class Mismatch(
        val driftScore: Double,
        val droppedConstraints: List<String>,
        val hallucinations: List<String>,
        val correctiveFeedback: String
    ) : AccuracyAuditResult
}

data class StageAPayload(
    val heat: String,
    val injection: String,
    val injection_reason: String = "",
    val ambient_pii: List<String> = emptyList(),
    val completeness: String = "READY",
    val missing_context: String = ""
)

data class StageDPayload(
    val status: String,
    val drift_score: Double = 0.0,
    val dropped_constraints: List<String> = emptyList(),
    val hallucinations: List<String> = emptyList(),
    val corrective_feedback: String = ""
)
