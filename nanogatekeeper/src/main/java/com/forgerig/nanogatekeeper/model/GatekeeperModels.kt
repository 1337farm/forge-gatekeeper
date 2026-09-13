package com.forgerig.nanogatekeeper.model

import kotlinx.serialization.Serializable

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

@Serializable
data class StepExecutionRecord(
    val order: Int,
    val step: GatekeeperStep,
    val status: StepStatus,
    val reason: String = "",
    val iterations: Int = 0,
    val durationMs: Long = 0L
)

@Serializable
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
    val totalDurationMs: Long = 0L
)

data class GatekeeperConfig(
    val maxRetries: Int = 3,
    val queueWaitTimeoutMs: Long = 2_000L,
    val npuExecutionTimeoutMs: Long = 15_000L,
    val minRamBytes: Long = 7_500_000_000L,
    val requireApi34: Boolean = true,
    val enableStageB: Boolean = true,
    val enableCompression: Boolean = true,
    val enableAudit: Boolean = true,
    val circuitFailureThreshold: Int = 3,
    val circuitCooldownMs: Long = 30_000L
) {
    init {
        require(maxRetries in 0..5) { "maxRetries must be 0..5" }
        require(queueWaitTimeoutMs > 0) { "queueWaitTimeoutMs must be > 0" }
        require(npuExecutionTimeoutMs > 0) { "npuExecutionTimeoutMs must be > 0" }
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

@Serializable
data class StageAPayload(
    val heat: String,
    val injection: String,
    val injection_reason: String = "",
    val ambient_pii: List<String> = emptyList(),
    val completeness: String = "READY",
    val missing_context: String = ""
)

@Serializable
data class StageDPayload(
    val status: String,
    val drift_score: Double = 0.0,
    val dropped_constraints: List<String> = emptyList(),
    val hallucinations: List<String> = emptyList(),
    val corrective_feedback: String = ""
)
