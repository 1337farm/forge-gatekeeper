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

    private fun clip(s: String, n: Int): String =
        if (s.length <= n) s else s.take(n) + "…<${s.length - n} more chars>"

    // logcat truncates past ~4KB per call: chunk long payloads with sequence
    // markers so full requests/responses survive intact (in-app rows were
    // already full; this makes adb logcat match them).
    private fun logLong(msg: String) {
        if (msg.length <= 3500) {
            Log.i(tag, msg)
            return
        }
        val parts = msg.chunked(3500)
        parts.forEachIndexed { i, p -> Log.i(tag, "[${i + 1}/${parts.size}] $p") }
    }

    companion object {
        // Bare greetings/fillers the on-device judge mistakes for entities.
        // Compared lowercase against trimmed ambient_pii entries.
        internal val GENERIC_TOKENS = setOf(
            "hi", "hey", "hello", "yo", "thanks", "thank", "please",
            "ok", "okay", "yes", "no", "bye", "thanks!"
        )
    }

    suspend fun processPrompt(
        rawPrompt: String,
        config: GatekeeperConfig = GatekeeperConfig(),
        // Live stage lines ("Scrub ✓ …", "Stage A … (12.3s)"): the demo
        // forwards these to the status view so a 25s run never looks stuck.
        // Library consumers that don't need UI leave the default no-op.
        onProgress: (String) -> Unit = {},
        // Per-LLM-call debug events (label, "request"/"response", payload):
        // the demo mirrors these into its on-screen debug log and logcat.
        // Same truncation policy as the log lines below.
        onLlmEvent: (label: String, direction: String, text: String) -> Unit = { _, _, _ -> }
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
            expansionGuardFailed: Boolean = false,
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
                expansionGuardFailed = expansionGuardFailed,
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
            val raw = guardedInference(SystemPrompts.securityPrompt(), sanitized, config, breaker, "stageA", onLlmEvent)
            parseStageA(extractJson(raw))
        } catch (e: TimeoutCancellationException) {
            breaker.recordFailure()
            rec(GatekeeperStep.STAGE_A_SECURITY_EVAL, StepStatus.FAILED, "inference timeout")
            rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, "stage_a timeout")
            return GatekeeperResult.FallbackRequired(
                sanitized, "stage_a inference timeout",
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
        // The on-device judge over-reports: observed ambient_pii:["hi"] for
        // the input "hi", which Stage B then masked into oblivion. Validate
        // the list — real PII is rarely 1-2 chars or a bare greeting —
        // instead of trusting it blindly. Dropped entries are logged.
        val ambientPii = stageA.ambient_pii
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val bogusPii = ambientPii.filter { it.length < 3 || it.lowercase() in GENERIC_TOKENS }
        if (bogusPii.isNotEmpty()) {
            Log.i(tag, "ambient PII rejected as non-entity: $bogusPii")
        }
        val validPii = ambientPii - bogusPii.toSet()
        validPii.forEach { redactionEvents.add("ambient_pii:$it") }

        var working = sanitized
        if (config.enableStageB && validPii.isNotEmpty()) {
            val s4 = System.currentTimeMillis()
            try {
                working = maskAmbientPii(working, validPii)
                validPii.forEach { redactionEvents.add("masked:$it") }
                rec(
                    GatekeeperStep.STAGE_B_PII_REDACTION, StepStatus.EXECUTED,
                    "masked=${validPii.size}", 0, System.currentTimeMillis() - s4
                )
                onProgress("Stage B ✓ masked ${validPii.size} PII (${elapsed()})")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                rec(GatekeeperStep.STAGE_B_PII_REDACTION, StepStatus.FAILED, "mask error")
            }
        } else {
            val why = if (!config.enableStageB) "disabled by config" else "no ambient PII"
            rec(GatekeeperStep.STAGE_B_PII_REDACTION, StepStatus.SKIPPED, why)
            skipped[GatekeeperStep.STAGE_B_PII_REDACTION.name] = why
            onProgress("Skip Stage B: $why (${elapsed()})")
        }

        if (!config.enableCompression) {
            rec(GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION, StepStatus.SKIPPED, "disabled by config")
            skipped[GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION.name] = "disabled by config"
            rec(GatekeeperStep.STAGE_D_ACCURACY_AUDIT, StepStatus.SKIPPED, "compression disabled")
            skipped[GatekeeperStep.STAGE_D_ACCURACY_AUDIT.name] = "compression disabled"
            onProgress("Skip compress+audit: disabled by config (${elapsed()})")
            val post = TokenEstimator.count(working)
            return GatekeeperResult.Success(working, sanitized, heat, ledger(post))
        }

        val workingTokens = TokenEstimator.count(working)
        if (workingTokens <= config.minTokensForCompression) {
            val why = "short input ($workingTokens tokens), nothing to compress"
            rec(GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION, StepStatus.SKIPPED, why)
            skipped[GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION.name] = why
            rec(GatekeeperStep.STAGE_D_ACCURACY_AUDIT, StepStatus.SKIPPED, "compression skipped")
            skipped[GatekeeperStep.STAGE_D_ACCURACY_AUDIT.name] = "compression skipped"
            onProgress("Skip compress+audit: $why (${elapsed()})")
            return GatekeeperResult.Success(working, sanitized, heat, ledger(workingTokens))
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

        // Suspend — never runBlocking: processPrompt runs on the caller's
        // scope (Main in the demo), so blocking here would freeze the UI
        // and no progress line would ever paint.
        // Every call is logged with its full payload: `label` names the
        // step, `logUser` may replace `userContent` for display only when
        // the payload is already logged verbatim by the previous step
        // (marked with ↳ instead of reprinting). Only scrubbed/derived
        // text is ever logged — never the raw prompt.
        suspend fun timeInfer(
            label: String,
            systemPrompt: String,
            userContent: String,
            logUser: String = userContent
        ): String {
            val start = System.currentTimeMillis()
            logLong("$label request system=${clip(systemPrompt, 300)}\nuser:\n$logUser")
            // Full payload goes to the callback (in-app step rows) as well.
            onLlmEvent(label, "request", "system:\n$systemPrompt\nuser:\n$logUser")
            val timed = if (inference is TimedInferenceClient) {
                (inference as TimedInferenceClient).generateTimed(systemPrompt, userContent)
            } else null
            val text = timed?.text ?: inference.generate(systemPrompt, userContent)
            inferCalls++
            inferMs += timed?.generationMs ?: (System.currentTimeMillis() - start)
            inferTokens += timed?.completionTokens ?: (text.length / 4)
            logLong("$label response (${text.length} chars):\n$text")
            onLlmEvent(label, "response", text)
            return text
        }

        fun tpsLine(): String {
            if (inferCalls == 0 || inferMs <= 0) return "infer calls=$inferCalls"
            val tps = inferTokens / (inferMs / 1000.0)
            return "infer calls=$inferCalls inferMs=$inferMs tps~${"%.1f".format(tps)}"
        }

        fun maxRetriesFallback(): GatekeeperResult.FallbackRequired {
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

        while (compIt <= config.maxRetries) {
            val sc = System.currentTimeMillis()
            onProgress("Compress iter ${compIt + 1}: querying LLM…")
            try {
                candidate = cleanCandidate(
                    timeInfer(
                        "compress#${compIt + 1}",
                        SystemPrompts.compressionPrompt(),
                        buildCompressionInput(working, corrective, previousFailed)
                    ).trim()
                )
                compIt++
                val candTokens = TokenEstimator.count(candidate)
                // Deterministic backstop for small-model scaffolding: a real
                // compression never balloons past ~3x (or +40 tokens slack
                // for tiny inputs). Without this, hallucinated explanations
                // sail to the auditor, which can rubber-stamp them MATCH.
                val cap = maxOf(workingTokens * 3, workingTokens + 40)
                // For non-tiny inputs, require actual compression: output
                // must be shorter than input. A 21→26 token "compression"
                // is not compression at all — the audit correctly rejects
                // it, and retrying wastes the full budget. Fail fast.
                val noCompression = workingTokens > 5 && candTokens >= workingTokens
                if (candTokens > cap || noCompression) {
                    val reason = if (noCompression) {
                        "no compression ($candTokens vs $workingTokens input tokens)"
                    } else {
                        "output expanded ${candTokens} vs ${workingTokens} input tokens"
                    }
                    lastDropped = listOf(reason)
                    rec(
                        GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION, StepStatus.FAILED,
                        reason, compIt, System.currentTimeMillis() - sc
                    )
                    previousFailed = candidate
                    corrective = "Output MUST be shorter than the input. " +
                        "Compress, do not explain. Target: fewer than $workingTokens tokens."
                    onProgress("Compress ✗ $reason — retrying (${elapsed()})")
                    if (compIt >= config.maxRetries) {
                        rec(
                            GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED,
                            "expansion guard failed after ${compIt + 1} attempts"
                        )
                        return GatekeeperResult.FallbackRequired(
                            sanitized, "expansion guard failed; model cannot compress",
                            ledger(
                                preTokens, drift = lastDrift, dropped = lastDropped,
                                fallback = "expansion guard", maxEx = true,
                                expansionGuardFailed = true,
                                compIt = compIt, audIt = audIt
                            )
                        )
                    }
                    continue
                }
                rec(
                    GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION,
                    if (compIt > 1) StepStatus.RETRIED else StepStatus.EXECUTED,
                    "iter=$compIt in=${workingTokens} out=$candTokens ${tpsLine()}", compIt, System.currentTimeMillis() - sc
                )
                onProgress("Compress ✓ iter=$compIt in=${workingTokens} out=$candTokens ${tpsLine()} (${elapsed()})")
            } catch (e: TimeoutCancellationException) {
                breaker.recordFailure()
                rec(GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION, StepStatus.FAILED, "inference timeout iter=$compIt")
                rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, "compression timeout")
                return GatekeeperResult.FallbackRequired(
                    sanitized, "compression inference timeout",
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
                    "audit#${audIt + 1}",
                    SystemPrompts.auditPrompt(),
                    "ORIGINAL:\n$working\n\nCOMPRESSED:\n$candidate",
                    // The candidate was just logged verbatim as the compress
                    // response feeding this step — reference it, don't reprint.
                    logUser = "ORIGINAL:\n$working\n\nCOMPRESSED:\n" +
                        "↳ compress iter $compIt output (logged above, fed in full)"
                )
                audIt++
                parseAudit(raw)
            } catch (e: TimeoutCancellationException) {
                breaker.recordFailure()
                rec(GatekeeperStep.STAGE_D_ACCURACY_AUDIT, StepStatus.FAILED, "inference timeout")
                rec(GatekeeperStep.FALLBACK_TO_SANITIZED, StepStatus.EXECUTED, "audit timeout")
                return GatekeeperResult.FallbackRequired(
                    sanitized, "audit inference timeout",
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
                        return maxRetriesFallback()
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
        config: GatekeeperConfig, breaker: CircuitBreaker,
        label: String = "infer",
        onLlmEvent: (label: String, direction: String, text: String) -> Unit = { _, _, _ -> }
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
            logLong("$label request system=${clip(systemPrompt, 300)}\nuser:\n$userContent")
            onLlmEvent(label, "request", "system:\n$systemPrompt\nuser:\n$userContent")
            val out = withTimeout(config.npuExecutionTimeoutMs) {
                inference.generate(systemPrompt, userContent)
            }
            logLong("$label response (${out.length} chars):\n$out")
            onLlmEvent(label, "response", out)
            return out
        } catch (e: TimeoutCancellationException) {
            breaker.recordFailure()
            throw e
        } catch (e: CancellationException) {
            throw e
        } finally {
            if (npuMutex.isLocked) npuMutex.unlock()
        }
    }

    // The compressor input is framed with explicit markers so a small model
    // treats the working text as a PROMPT to shorten, never as an instruction
    // to answer or execute. Retry inputs carry the failed output plus the
    // judge feedback, never bare prose the model could wander off with.
    internal fun buildCompressionInput(original: String, corrective: String, previousFailed: String?): String {
        if (corrective.isBlank() || previousFailed == null) {
            return "PROMPT TO COMPRESS (rewrite shorter, do not answer):\n$original"
        }
        return "ORIGINAL PROMPT TO COMPRESS:\n$original\n\nYOUR PREVIOUS FAILED OUTPUT (do not repeat):\n" +
            "$previousFailed\n\nJUDGE CORRECTIVE FEEDBACK (must fix all):\n$corrective\n\n" +
            "Now output the corrected compressed prompt ONLY."
    }

    // Small models wrap output in scaffolding ("USER_TEXT:…",
    // "COMPRESSED_OUTPUT:…", "response:…", Example/Original/Compressed
    // blocks, ``` fences, wrapped quotes) instead of emitting compressed
    // text only. Strip it deterministically; only the surviving payload
    // reaches the auditor and the answer step.
    internal fun cleanCandidate(raw: String): String {
        var s = raw.trim()
        val payload = s.indexOf("COMPRESSED_OUTPUT:")
        if (payload >= 0) s = s.substring(payload + "COMPRESSED_OUTPUT:".length)
        val explanation = s.indexOf("[Explanation")
        if (explanation >= 0) {
            var end = explanation
            while (end > 0 && (s[end - 1] == '-' || s[end - 1].isWhitespace())) end--
            s = s.substring(0, end)
        }
        s = s.replace("```json", "").replace("```", "").trim()
        s = Regex("(?i)^(?:user_text|compressed_output|compressed|response|output|prompt\\s+to\\s+compress|original\\s+prompt\\s+to\\s+compress)\\s*(?:\\([^)]*\\))?\\s*:\\s*")
            .replace(s, "")
        val lines = s.lines()
        val marker = Regex("(?i)^(?:example|examples|original|compressed|response|output)\\s*:")
        val cut = lines.indices.drop(1).firstOrNull { marker.containsMatchIn(lines[it]) }
        if (cut != null) s = lines.subList(0, cut).joinToString("\n")
        s = s.trim()
        if (s.length >= 2) {
            val first = s.first()
            val last = s.last()
            val quoted = (first == '"' && last == '"') ||
                (first == '\'' && last == '\'') ||
                (first == '“' && last == '”') ||
                (first == '‘' && last == '’')
            if (quoted) s = s.substring(1, s.length - 1).trim()
        }
        return s
    }

    internal fun parseAudit(raw: String): AccuracyAuditResult {
        delineatedBlocks(raw)
            .mapNotNull { parseDelineatedAudit(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { return combineVotes(it) }
        val bodies = extractJsonObjects(extractJson(raw)).ifEmpty { listOf(extractJson(raw)) }
        val votes = bodies.mapNotNull { body ->
            runCatching { parseAuditBody(body) }.getOrNull()
                ?: runCatching { lenientAudit(body) }.getOrNull()
        }
        if (votes.isEmpty()) {
            throw IllegalArgumentException("no audit verdict in response")
        }
        if (votes.size == 1) return votes[0]
        // Rambling judges emit several contradictory verdicts in one
        // response (observed: MISMATCH/MATCH x5). Majority wins; ties fail
        // safe to MISMATCH. Drift is averaged, lists unioned.
        if (votes.size > 1) {
            Log.i(tag, "audit returned ${votes.size} verdicts; majority decides")
        }
        return combineVotes(votes)
    }

    private fun combineVotes(votes: List<AccuracyAuditResult>): AccuracyAuditResult {
        if (votes.size == 1) return votes[0]
        val matches = votes.filterIsInstance<AccuracyAuditResult.Match>()
        val mismatches = votes.filterIsInstance<AccuracyAuditResult.Mismatch>()
        if (matches.size > mismatches.size) {
            return AccuracyAuditResult.Match(matches.map { it.driftScore }.average())
        }
        return AccuracyAuditResult.Mismatch(
            driftScore = votes.map {
                when (it) {
                    is AccuracyAuditResult.Match -> it.driftScore
                    is AccuracyAuditResult.Mismatch -> it.driftScore
                }
            }.average(),
            droppedConstraints = mismatches.flatMap { it.droppedConstraints }.distinct(),
            hallucinations = mismatches.flatMap { it.hallucinations }.distinct(),
            correctiveFeedback = mismatches.firstOrNull()?.correctiveFeedback.orEmpty()
        )
    }

    // Labeled-line blocks ("STATUS: …", "HEAT: …"). The instructed reply
    // format: plain lines beat JSON for small models (no quotes/braces to
    // drop, no fences to strip). Continuation lines glue to the previous key;
    // unknown KEY: lines glue too, so prose with colons can't desync blocks.
    // A block starts at STATUS/HEAT; blocks without STATUS/HEAT are ignored
    // by the verdict builders below.
    private val DELINEATED_KEYS = setOf(
        "STATUS", "DRIFT", "DROPPED", "HALLUCINATIONS", "FEEDBACK",
        "HEAT", "INJECTION", "REASON", "AMBIENT_PII", "COMPLETENESS", "MISSING"
    )

    internal fun delineatedBlocks(raw: String): List<Map<String, String>> {
        val blocks = mutableListOf<MutableMap<String, String>>()
        var cur: MutableMap<String, String>? = null
        var lastKey: String? = null
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("```")) continue
            val m = Regex("^([A-Za-z_]+)\\s*:\\s*(.*)$").matchEntire(t)
            val key = m?.groupValues?.get(1)?.uppercase()
            if (m != null && key != null && key in DELINEATED_KEYS) {
                if (key == "STATUS" || key == "HEAT") {
                    cur = mutableMapOf()
                    blocks.add(cur)
                }
                if (cur == null) {
                    cur = mutableMapOf()
                    blocks.add(cur)
                }
                cur[key] = m.groupValues[2].trim()
                lastKey = key
            } else {
                val k = lastKey
                val c = cur
                if (k != null && c != null) {
                    c[k] = ((c[k] ?: "") + " " + t).trim()
                }
            }
        }
        return blocks
    }

    private fun delineatedList(value: String?): List<String> {
        if (value.isNullOrBlank() || value.equals("NONE", ignoreCase = true)) return emptyList()
        return value.split(";")
            .map { it.trim().trimStart('-').trim() }
            .filter { it.isNotEmpty() && !it.equals("NONE", ignoreCase = true) }
    }

    private fun parseDelineatedAudit(block: Map<String, String>): AccuracyAuditResult? {
        val status = block["STATUS"] ?: return null
        val drift = block["DRIFT"]?.toDoubleOrNull() ?: 0.0
        return if (status.equals("MATCH", ignoreCase = true)) AccuracyAuditResult.Match(drift)
        else AccuracyAuditResult.Mismatch(
            drift,
            delineatedList(block["DROPPED"]),
            delineatedList(block["HALLUCINATIONS"]),
            block["FEEDBACK"].orEmpty()
        )
    }

    private fun parseAuditBody(body: String): AccuracyAuditResult {
        val p = JSONObject(body)
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

    // All balanced top-level {...} objects in a response, string-aware so
    // braces inside quotes (or markdown) can't desync the scan. Powers the
    // multi-verdict vote; unbalanced tails are skipped, never fatal.
    internal fun extractJsonObjects(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val start = s.indexOf('{', i)
            if (start < 0) break
            var depth = 0
            var inStr = false
            var esc = false
            var j = start
            while (j < s.length) {
                val c = s[j]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                } else {
                    if (c == '"') inStr = true
                    else if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            out.add(s.substring(start, j + 1))
                            break
                        }
                    }
                }
                j++
            }
            i = if (depth == 0 && j < s.length) j + 1 else start + 1
        }
        return out
    }

    private fun lenientAudit(json: String): AccuracyAuditResult? {
        val status = Regex(""""status"\s*:\s*"(MATCH|MISMATCH)"""", RegexOption.IGNORE_CASE)
            .find(json)?.groupValues?.get(1) ?: return null
        fun number(key: String): Double =
            Regex(""""$key"\s*:\s*([0-9]+(?:\.[0-9]+)?)""").find(json)
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        fun arrayOf(key: String): List<String> {
            val body = Regex(
                """"$key"\s*:\s*\[(.*?)\]"""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(json)?.groupValues?.get(1) ?: return emptyList()
            return body.split(",")
                .map { it.trim().trim('"').trim() }
                .filter { it.isNotEmpty() }
        }
        fun textOf(key: String): String =
            Regex(""""$key"\s*:\s*"(.*?)"""", RegexOption.DOT_MATCHES_ALL)
                .find(json)?.groupValues?.get(1) ?: ""
        val drift = number("drift_score")
        return if (status.equals("MATCH", true)) AccuracyAuditResult.Match(drift)
        else AccuracyAuditResult.Mismatch(
            drift,
            arrayOf("dropped_constraints"),
            arrayOf("hallucinations"),
            textOf("corrective_feedback")
        )
    }

    internal fun parseStageA(raw: String): StageAPayload {
        delineatedBlocks(raw).firstOrNull { it.containsKey("HEAT") && it.containsKey("INJECTION") }?.let { b ->
            val pii = delineatedList(b["AMBIENT_PII"])
            return StageAPayload(
                heat = b["HEAT"].takeIf { !it.isNullOrBlank() } ?: "COLD",
                injection = b["INJECTION"].takeIf { !it.isNullOrBlank() } ?: "SAFE",
                injection_reason = b["REASON"].orEmpty(),
                ambient_pii = pii,
                completeness = b["COMPLETENESS"]?.takeIf { it.isNotBlank() } ?: "READY",
                missing_context = b["MISSING"].orEmpty()
            )
        }
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
