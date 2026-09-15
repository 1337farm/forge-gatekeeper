package com.forgerig.gatekeeper.engine

import android.content.Context
import android.util.Log
import com.forgerig.gatekeeper.hardware.HardwareCapabilityEngine
import com.forgerig.gatekeeper.hardware.HardwareVerdict
import com.forgerig.gatekeeper.model.AccuracyAuditResult
import com.forgerig.gatekeeper.model.GatekeeperConfig
import com.forgerig.gatekeeper.model.GatekeeperResult
import com.forgerig.gatekeeper.model.GatekeeperStep
import com.forgerig.gatekeeper.model.HeatLevel
import com.forgerig.gatekeeper.model.InjectionVerdict
import com.forgerig.gatekeeper.model.StageAPayload
import com.forgerig.gatekeeper.model.StepExecutionRecord
import com.forgerig.gatekeeper.model.ExecutionTelemetry
import com.forgerig.gatekeeper.model.StepStatus
import com.forgerig.gatekeeper.prompts.SystemPrompts
import com.forgerig.gatekeeper.sanitizer.DeterministicScrubber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

fun interface HardwareEvaluator {
    fun evaluate(ctx: Context, config: GatekeeperConfig): HardwareVerdict
}

class GatekeeperEngine(
    private val appContext: Context,
    private val inference: InferenceClient,
    private val hardwareEvaluator: HardwareEvaluator = HardwareEvaluator { ctx, cfg ->
        HardwareCapabilityEngine.evaluate(ctx, cfg.minRamBytes)
    }
) {
    private val npuMutex = Mutex()
    private val tag = "ForgeGatekeeper"

    suspend fun processPrompt(
        rawPrompt: String,
        config: GatekeeperConfig = GatekeeperConfig(),
        // Live stage lines ("Scrub ✓ …", "Stage A … (12.3s)"): the demo
        // forwards these to the status view so a 25s run never looks stuck.
        // Library consumers that don't need UI leave the default no-op.
        onProgress: (String) -> Unit = {}
    ): GatekeeperResult {
        val t0 = System.currentTimeMillis()
        fun elapsed(): String = "%.1fs".format((System.currentTimeMillis() - t0) / 1000.0)
        val records = mutableListOf<StepExecutionRecord>()
        val skipped = LinkedHashMap<String, String>()
        val redactionEvents = mutableListOf<String>()
        var order = 0
        fun rec(step: GatekeeperStep, status: StepStatus, reason: String = "", it: Int = 0, d: Long = 0) {
            records.add(StepExecutionRecord(order++, step, status, reason, it, d))
        }
        val breaker = CircuitBreaker(config.circuitFailureThreshold, config.circuitCooldownMs)

        val s1 = System.currentTimeMillis()
        val scrub = DeterministicScrubber.scrub(rawPrompt)
        scrub.events.forEach { redactionEvents.add("${it.type}->${it.replacement}") }
        val sanitized = scrub.sanitizedText
        rec(
            GatekeeperStep.DETERMINISTIC_SCRUB, StepStatus.EXECUTED,
            "redactions=${scrub.events.size}", 0, System.currentTimeMillis() - s1
        )
        onProgress("Scrub ✓ ${scrub.events.size} redactions (${elapsed()})")
        val preTokens = TokenEstimator.count(sanitized)

        fun ledger(
            post: Int, drift: Double? = null, dropped: List<String> = emptyList(),
            injected: Boolean = false, fallback: String? = null, maxEx: Boolean = false,
            compIt: Int = 0, audIt: Int = 0
        ): ExecutionTelemetry {
            return ExecutionTelemetry(
                executionOrder = records.toList(),
                skippedSteps = skipped.toMap(),
                compressionIterations = compIt,
                auditIterations = audIt,
                preCompressionTokens = preTokens,
                postCompressionTokens = post,
                compressionRatioPct = TokenEstimator.ratio(preTokens, post),
                driftScore = drift,
                droppedConstraints = dropped,
                injectionDetected = injected,
                redactionEvents = redactionEvents.toList(),
                fallbackReason = fallback,
                maxRetriesExhausted = maxEx,
                totalDurationMs = System.currentTimeMillis() - t0
            )
        }

        val s2 = System.currentTimeMillis()
        val verdict: HardwareVerdict = try {
            hardwareEvaluator.evaluate(appContext, config)
        } catch (t: Throwable) {
            HardwareVerdict.Ineligible("hardware check error: ${t.message}")
        }
        if (verdict is HardwareVerdict.Ineligible) {
            rec(GatekeeperStep.HARDWARE_CIRCUIT_CHECK, StepStatus.FAILED, verdict.reason, 0, System.currentTimeMillis() - s2)
            for (s in listOf(
                GatekeeperStep.STAGE_A_SECURITY_EVAL, GatekeeperStep.STAGE_B_PII_REDACTION,
                GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION, GatekeeperStep.STAGE_D_ACCURACY_AUDIT
            )) {
                rec(s, StepStatus.SKIPPED, "hardware ineligible")
                skipped[s.name] = "hardware ineligible"
            }
            rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, verdict.reason)
            return GatekeeperResult.FallbackRequired(
                sanitized, "hardware: ${verdict.reason}",
                ledger(preTokens, fallback = verdict.reason)
            )
        }
        if (!breaker.canExecute()) {
            rec(GatekeeperStep.HARDWARE_CIRCUIT_CHECK, StepStatus.FAILED, "circuit OPEN", 0, System.currentTimeMillis() - s2)
            rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, "circuit open")
            return GatekeeperResult.FallbackRequired(
                sanitized, "circuit open",
                ledger(preTokens, fallback = "circuit open")
            )
        }
        rec(GatekeeperStep.HARDWARE_CIRCUIT_CHECK, StepStatus.EXECUTED, "eligible", 0, System.currentTimeMillis() - s2)
        onProgress("Hardware ✓ eligible (${elapsed()})")

        val s3 = System.currentTimeMillis()
        onProgress("Stage A security eval: querying LLM…")
        val stageA: StageAPayload = try {
            val raw = guardedInference(SystemPrompts.securityPrompt(), sanitized, config, breaker)
            parseStageA(extractJson(raw))
        } catch (e: TimeoutCancellationException) {
            breaker.recordFailure()
            rec(GatekeeperStep.STAGE_A_SECURITY_EVAL, StepStatus.FAILED, "NPU timeout")
            rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, "stage_a timeout")
            return GatekeeperResult.FallbackRequired(
                sanitized, "stage_a NPU timeout",
                ledger(preTokens, fallback = "stage_a timeout")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            breaker.recordFailure()
            // Surface the truncated cause: a bare "stage_a error" is undebuggable
            // on-device (allowlist, missing model, service errors all land here).
            val cause = "stage_a error: ${t.message}".take(1200)
            rec(GatekeeperStep.STAGE_A_SECURITY_EVAL, StepStatus.FAILED, cause)
            rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, "stage_a error")
            return GatekeeperResult.FallbackRequired(
                sanitized, cause,
                ledger(preTokens, fallback = cause)
            )
        }
        breaker.recordSuccess()
        val heat = runCatching { HeatLevel.valueOf(stageA.heat.uppercase()) }.getOrDefault(HeatLevel.COLD)
        val injection = runCatching { InjectionVerdict.valueOf(stageA.injection.uppercase()) }.getOrDefault(InjectionVerdict.SAFE)
        rec(
            GatekeeperStep.STAGE_A_SECURITY_EVAL, StepStatus.EXECUTED,
            "heat=$heat injection=$injection completeness=${stageA.completeness}", 0, System.currentTimeMillis() - s3
        )
        onProgress("Stage A ✓ heat=$heat injection=$injection (${elapsed()})")

        if (injection == InjectionVerdict.MALICIOUS) {
            for (s in listOf(
                GatekeeperStep.STAGE_B_PII_REDACTION, GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION,
                GatekeeperStep.STAGE_D_ACCURACY_AUDIT
            )) {
                rec(s, StepStatus.SKIPPED, "fail-closed: malicious")
                skipped[s.name] = "fail-closed malicious block"
            }
            val reason = "MALICIOUS blocked: ${stageA.injection_reason}".take(500)
            Log.w(tag, reason)
            return GatekeeperResult.Blocked(reason, heat, ledger(preTokens, injected = true, fallback = reason))
        }
        stageA.ambient_pii.forEach { redactionEvents.add("ambient_pii:$it") }

        var working = sanitized
        if (config.enableStageB && stageA.ambient_pii.isNotEmpty()) {
            val s4 = System.currentTimeMillis()
            try {
                working = maskAmbientPii(working, stageA.ambient_pii)
                stageA.ambient_pii.forEach { redactionEvents.add("masked:$it") }
                rec(
                    GatekeeperStep.STAGE_B_PII_REDACTION, StepStatus.EXECUTED,
                    "masked=${stageA.ambient_pii.size}", 0, System.currentTimeMillis() - s4
                )
                onProgress("Stage B ✓ masked ${stageA.ambient_pii.size} PII (${elapsed()})")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                rec(GatekeeperStep.STAGE_B_PII_REDACTION, StepStatus.FAILED, "mask error")
            }
        } else {
            val why = if (!config.enableStageB) "disabled by config" else "no ambient PII"
            rec(GatekeeperStep.STAGE_B_PII_REDACTION, StepStatus.SKIPPED, why)
            skipped[GatekeeperStep.STAGE_B_PII_REDACTION.name] = why
        }

        if (!config.enableCompression) {
            rec(GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION, StepStatus.SKIPPED, "disabled by config")
            skipped[GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION.name] = "disabled by config"
            rec(GatekeeperStep.STAGE_D_ACCURACY_AUDIT, StepStatus.SKIPPED, "compression disabled")
            skipped[GatekeeperStep.STAGE_D_ACCURACY_AUDIT.name] = "compression disabled"
            val post = TokenEstimator.count(working)
            return GatekeeperResult.Success(working, sanitized, heat, ledger(post))
        }

        var candidate = working
        var compIt = 0
        var audIt = 0
        var lastDrift: Double? = null
        var lastDropped: List<String> = emptyList()
        var corrective = ""
        var previousFailed: String? = null
        var inferCalls = 0
        var inferMs = 0L
        var inferTokens = 0

        fun timeInfer(systemPrompt: String, userContent: String): String {
            val start = System.currentTimeMillis()
            val timed = if (inference is TimedInferenceClient) {
                kotlinx.coroutines.runBlocking {
                    (inference as TimedInferenceClient).generateTimed(systemPrompt, userContent)
                }
            } else null
            val text = timed?.text ?: run {
                var out = ""
                kotlinx.coroutines.runBlocking { out = inference.generate(systemPrompt, userContent) }
                out
            }
            inferCalls++
            inferMs += timed?.generationMs ?: (System.currentTimeMillis() - start)
            inferTokens += timed?.completionTokens ?: (text.length / 4)
            return text
        }

        fun tpsLine(): String {
            if (inferCalls == 0 || inferMs <= 0) return "infer calls=$inferCalls"
            val tps = inferTokens / (inferMs / 1000.0)
            return "infer calls=$inferCalls inferMs=$inferMs tps~${"%.1f".format(tps)}"
        }

        while (compIt <= config.maxRetries) {
            val sc = System.currentTimeMillis()
            onProgress("Compress iter ${compIt + 1}: querying LLM…")
            try {
                candidate = timeInfer(
                    SystemPrompts.compressionPrompt(),
                    buildCompressionInput(working, corrective, previousFailed)
                ).trim()
                compIt++
                rec(
                    GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION,
                    if (compIt > 1) StepStatus.RETRIED else StepStatus.EXECUTED,
                    "iter=$compIt ${tpsLine()}", compIt, System.currentTimeMillis() - sc
                )
                onProgress("Compress ✓ iter=$compIt ${tpsLine()} (${elapsed()})")
            } catch (e: TimeoutCancellationException) {
                breaker.recordFailure()
                rec(GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION, StepStatus.FAILED, "NPU timeout iter=$compIt")
                rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, "compression timeout")
                return GatekeeperResult.FallbackRequired(
                    sanitized, "compression NPU timeout",
                    ledger(preTokens, fallback = "compression timeout", compIt = compIt, audIt = audIt)
                )
            } catch (e: CancellationException) {
                throw e
            }

            if (!config.enableAudit) {
                skipped[GatekeeperStep.STAGE_D_ACCURACY_AUDIT.name] = "audit disabled by config"
                rec(GatekeeperStep.STAGE_D_ACCURACY_AUDIT, StepStatus.SKIPPED, "audit disabled")
                val post = TokenEstimator.count(candidate)
                return GatekeeperResult.Success(candidate, sanitized, heat, ledger(post, compIt = compIt, audIt = audIt))
            }

            val sd = System.currentTimeMillis()
            onProgress("Audit iter ${audIt + 1}: querying LLM…")
            val audit: AccuracyAuditResult = try {
                val raw = timeInfer(
                    SystemPrompts.auditPrompt(),
                    "ORIGINAL:\n$working\n\nCOMPRESSED:\n$candidate"
                )
                audIt++
                parseAudit(raw)
            } catch (e: TimeoutCancellationException) {
                breaker.recordFailure()
                rec(GatekeeperStep.STAGE_D_ACCURACY_AUDIT, StepStatus.FAILED, "NPU timeout")
                rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, "audit timeout")
                return GatekeeperResult.FallbackRequired(
                    sanitized, "audit NPU timeout",
                    ledger(preTokens, fallback = "audit timeout", compIt = compIt, audIt = audIt)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val cause = "audit error: ${t.message}".take(1200)
                rec(GatekeeperStep.STAGE_D_ACCURACY_AUDIT, StepStatus.FAILED, cause)
                rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, "audit error")
                return GatekeeperResult.FallbackRequired(
                    sanitized, cause,
                    ledger(preTokens, fallback = cause, compIt = compIt, audIt = audIt)
                )
            }

            when (audit) {
                is AccuracyAuditResult.Match -> {
                    rec(
                        GatekeeperStep.STAGE_D_ACCURACY_AUDIT, StepStatus.EXECUTED,
                        "MATCH drift=${audit.driftScore} ${tpsLine()}", audIt, System.currentTimeMillis() - sd
                    )
                    breaker.recordSuccess()
                    val post = TokenEstimator.count(candidate)
                    Log.i(tag, "pipeline done iters=($compIt,$audIt) ${tpsLine()} totalMs=${System.currentTimeMillis() - t0}")
                    onProgress("Done ✓ iters=($compIt,$audIt) ${tpsLine()} (${elapsed()})")
                    return GatekeeperResult.Success(
                        candidate, sanitized, heat,
                        ledger(post, drift = audit.driftScore, compIt = compIt, audIt = audIt)
                    )
                }
                is AccuracyAuditResult.Mismatch -> {
                    lastDrift = audit.driftScore
                    lastDropped = audit.droppedConstraints
                    Log.i(
                        tag,
                        "audit mismatch drift=${audit.driftScore} dropped=${audit.droppedConstraints} " +
                            "hallucinations=${audit.hallucinations} ${tpsLine()}"
                    )
                    rec(
                        GatekeeperStep.STAGE_D_ACCURACY_AUDIT, StepStatus.RETRIED,
                        "MISMATCH drift=${audit.driftScore} dropped=${audit.droppedConstraints}",
                        audIt, System.currentTimeMillis() - sd
                    )
                    previousFailed = candidate
                    corrective = audit.correctiveFeedback
                    onProgress("Audit ✗ MISMATCH drift=${audit.driftScore} — retrying (${elapsed()})")
                    if (compIt > config.maxRetries || audIt > config.maxRetries) {
                        rec(
                            GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED,
                            "maxRetries=${config.maxRetries} exhausted"
                        )
                        return GatekeeperResult.FallbackRequired(
                            sanitized,
                            "accuracy maxRetries exhausted; drift=$lastDrift dropped=$lastDropped",
                            ledger(
                                preTokens, drift = lastDrift, dropped = lastDropped,
                                fallback = "maxRetries exhausted", maxEx = true,
                                compIt = compIt, audIt = audIt
                            )
                        )
                    }
                }
            }
        }
        rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, "loop guard")
        return GatekeeperResult.FallbackRequired(
            sanitized, "loop guard fallback",
            ledger(
                preTokens, drift = lastDrift, dropped = lastDropped, fallback = "loop guard",
                maxEx = true, compIt = compIt, audIt = audIt
            )
        )
    }

    private suspend fun guardedInference(
        systemPrompt: String, userContent: String,
        config: GatekeeperConfig, breaker: CircuitBreaker
    ): String {
        try {
            withTimeout(config.queueWaitTimeoutMs) {
                npuMutex.lock()
            }
        } catch (e: TimeoutCancellationException) {
            breaker.recordFailure()
            throw e
        }
        try {
            return withTimeout(config.npuExecutionTimeoutMs) {
                inference.generate(systemPrompt, userContent)
            }
        } catch (e: TimeoutCancellationException) {
            breaker.recordFailure()
            throw e
        } catch (e: CancellationException) {
            throw e
        } finally {
            if (npuMutex.isLocked) npuMutex.unlock()
        }
    }

    internal fun buildCompressionInput(original: String, corrective: String, previousFailed: String?): String {
        if (corrective.isBlank() || previousFailed == null) return original
        return "ORIGINAL TASK:\n$original\n\nYOUR PREVIOUS FAILED ATTEMPT (do not repeat these errors):\n" +
            "$previousFailed\n\nJUDGE CORRECTIVE FEEDBACK (must fix all):\n$corrective\n\n" +
            "Now produce the corrected compressed output ONLY."
    }

    internal fun parseAudit(raw: String): AccuracyAuditResult {
        val p = JSONObject(extractJson(raw))
        val status = p.optString("status")
        val drift = p.optDouble("drift_score")
        return if (status.equals("MATCH", true)) AccuracyAuditResult.Match(drift)
        else AccuracyAuditResult.Mismatch(
            drift,
            p.optStringList("dropped_constraints"),
            p.optStringList("hallucinations"),
            p.optString("corrective_feedback")
        )
    }

    internal fun parseStageA(raw: String): StageAPayload {
        val p = JSONObject(raw)
        return StageAPayload(
            heat = p.getString("heat"),
            injection = p.getString("injection"),
            injection_reason = p.optString("injection_reason"),
            ambient_pii = p.optStringList("ambient_pii"),
            completeness = p.optString("completeness", "READY"),
            missing_context = p.optString("missing_context")
        )
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr: JSONArray = optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { i -> arr.getString(i) }
    }

    internal fun extractJson(raw: String): String {
        val s = raw.trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        return if (a >= 0 && b > a) s.substring(a, b + 1) else s
    }

    internal fun maskAmbientPii(text: String, tokens: List<String>): String {
        var out = text
        for (t in tokens) {
            if (t.isBlank() || t.length < 2) continue
            out = out.replace(t, "[PII_REDACTED]")
        }
        return out
    }
}
