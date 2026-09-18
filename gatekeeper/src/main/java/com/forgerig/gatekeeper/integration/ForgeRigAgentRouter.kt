package com.forgerig.gatekeeper.integration

import android.util.Log
import com.forgerig.gatekeeper.engine.GatekeeperEngine
import com.forgerig.gatekeeper.engine.TokenEstimator
import com.forgerig.gatekeeper.model.AuditStatus
import com.forgerig.gatekeeper.model.ExecutionTelemetry
import com.forgerig.gatekeeper.model.GatekeeperConfig
import com.forgerig.gatekeeper.model.GatekeeperResult
import com.forgerig.gatekeeper.model.HeatLevel

interface CloudLlmGateway {
    suspend fun generate(prompt: String): String
}

/**
 * Micro-operation contracts for the zero-knowledge compression pipeline.
 * Stage A (security) -> Stage C (compression) -> Stage D (audit) with an
 * absolute hard-stop post-audit. Abstract macro-instructions (e.g.
 * "continue work") are treated as 100% complete programmatic instructions:
 * no text-echoing, no conversational parameters, no missing-context
 * evaluation leaks into downstream generation loops.
 */
interface SecurityGate {
    fun evaluate(rawPrompt: String): SecurityScreening
}

interface PromptCompressor {
    fun compress(rawPrompt: String): String
}

interface SemanticAuditor {
    fun audit(original: String, compressed: String): SemanticAudit
}

data class SecurityScreening(
    val heat: String,
    val isMalicious: Boolean,
    val reason: String
)

data class SemanticAudit(
    val status: AuditStatus,
    val feedback: String,
    val driftScore: Double = 0.0
)

class ForgeRigAgentRouter(
    private val gatekeeper: GatekeeperEngine,
    private val cloudLlm: CloudLlmGateway
) {
    /**
     * Orchestrates prompt gating processing.
     * Patched to force an absolute hard-stop post-audit, preventing abstract intent leaks.
     *
     * State-machine guarantee: [GatekeeperEngine.processPrompt] already
     * terminates immediately after a successful Stage D evaluation and
     * directly bubbles up the optimized compression string
     * ([GatekeeperResult.Success.safeCompressedPrompt]). This router must not
     * re-enter the pipeline or start secondary generation loops — it forwards
     * the already-verified compressed prompt verbatim to the cloud gateway.
     */
    suspend fun dispatch(userPrompt: String, config: GatekeeperConfig = GatekeeperConfig()): String {
        return when (val r = gatekeeper.processPrompt(userPrompt, config)) {
            is GatekeeperResult.Success -> {
                Log.i(
                    "ForgeRig",
                    "GATEKEEPER success ratio=${r.telemetry.compressionRatioPct}% " +
                        "pre=${r.telemetry.preCompressionTokens} post=${r.telemetry.postCompressionTokens} " +
                        "iters=${r.telemetry.compressionIterations}"
                )
                Log.d("ForgeRig", "ledger=$r.telemetry")
                // CRITICAL FIX: Explicit State Machine Hard-Stop — the pipeline
                // terminated at Stage D MATCH above. Bubble up the optimized
                // compression string directly; bypass secondary generation.
                cloudLlm.generate(r.safeCompressedPrompt.trim())
            }
            is GatekeeperResult.Blocked -> {
                Log.w("ForgeRig", "GATEKEEPER blocked: ${r.reason}")
                throw SecurityException(r.reason)
            }
            is GatekeeperResult.FallbackRequired -> {
                Log.w("ForgeRig", "GATEKEEPER fallback: ${r.reason}")
                Log.d("ForgeRig", "ledger=$r.telemetry")
                cloudLlm.generate(r.sanitizedPrompt)
            }
        }
    }

    /**
     * Zero-knowledge micro-engine path: Stage A -> Stage C -> Stage D with a
     * strict state-termination guard. The pipeline terminates immediately
     * after a successful Stage D evaluation and directly bubbles up the
     * optimized compression string. Bypasses secondary generation.
     */
    fun processPromptThroughMicroEngine(
        rawPrompt: String,
        telemetry: ExecutionTelemetry,
        securityGate: SecurityGate,
        compressor: PromptCompressor,
        semanticAuditor: SemanticAuditor
    ): GatekeeperResult {
        // Step 1: Stage A Security Evaluation
        val securityResult = securityGate.evaluate(rawPrompt)
        if (securityResult.isMalicious || securityResult.heat == "HOT") {
            return GatekeeperResult.Blocked(
                reason = securityResult.reason,
                heat = runCatching { HeatLevel.valueOf(securityResult.heat) }
                    .getOrDefault(HeatLevel.HOT),
                telemetry = telemetry
            )
        }

        // Step 2: Stage C Compression
        // Abstract macro-instructions (e.g. "continue work") are complete,
        // valid instructions — shorten, never answer or echo conversationally.
        val compressedPrompt = compressor.compress(rawPrompt)

        // Step 3: Stage D Semantic Accuracy Audit
        val auditResult = semanticAuditor.audit(original = rawPrompt, compressed = compressedPrompt)

        // CRITICAL FIX: Explicit State Machine Hard-Stop
        // If Stage D flags a MATCH, return immediately. Bypasses secondary generation.
        if (auditResult.status == AuditStatus.MATCH) {
            return GatekeeperResult.Success(
                safeCompressedPrompt = compressedPrompt.trim(),
                sanitizedFallback = rawPrompt,
                heat = runCatching { HeatLevel.valueOf(securityResult.heat) }
                    .getOrDefault(HeatLevel.COLD),
                telemetry = telemetry.copy(
                    postCompressionTokens = TokenEstimator.count(compressedPrompt.trim()),
                    compressionRatioPct = TokenEstimator.ratio(
                        telemetry.preCompressionTokens,
                        TokenEstimator.count(compressedPrompt.trim())
                    ),
                    driftScore = auditResult.driftScore
                )
            )
        }

        // Fallback strategy if prompt drifted structurally during compression
        return GatekeeperResult.FallbackRequired(
            sanitizedPrompt = rawPrompt,
            reason = auditResult.feedback,
            telemetry = telemetry.copy(fallbackReason = auditResult.feedback)
        )
    }

    private fun calculateSavings(original: String, compressed: String): Double {
        if (original.isEmpty()) return 0.0
        return (original.length - compressed.length).toDouble() / original.length
    }
}
