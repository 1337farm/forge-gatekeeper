package com.forgerig.gatekeeper.demo

import com.forgerig.gatekeeper.model.ExecutionTelemetry
import com.forgerig.gatekeeper.model.GatekeeperResult
import com.forgerig.gatekeeper.model.GatekeeperStep
import com.forgerig.gatekeeper.model.StepStatus

// Pure result formatting shared by the foreground service (broadcasts
// pre-rendered strings) and anything else that displays a run outcome.
object RunResultFormat {
    // One timeline row: kind in {done, skip, fail} drives the circle style.
    // question/answer carry that step's LLM turn, if it made one — the demo
    // renders them labeled Q:/A: under the row so every turn reads in place.
    data class StepItem(
        val kind: String,
        val label: String,
        val detail: String,
        val question: String = "",
        val answer: String = ""
    )

    // Full LLM turn payloads keyed by engine label ("stageA",
    // "compress#N", "audit#N"). Matched to rows by record type in order, so
    // every turn's Q/A lands on exactly its own step.
    data class QaTurn(val question: String, val answer: String)

    fun stepLabel(step: GatekeeperStep): String = when (step) {
        GatekeeperStep.DETERMINISTIC_SCRUB -> "Scrub"
        GatekeeperStep.HARDWARE_CIRCUIT_CHECK -> "Hardware check"
        GatekeeperStep.STAGE_A_SECURITY_EVAL -> "Stage A · Security eval"
        GatekeeperStep.STAGE_B_PII_REDACTION -> "Stage B · PII redaction"
        GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION -> "Stage C · Compression"
        GatekeeperStep.STAGE_D_ACCURACY_AUDIT -> "Stage D · Accuracy audit"
        GatekeeperStep.FALLBACK_TO_SANITIZED -> "Fallback"
    }

    fun stepKind(status: StepStatus): String = when (status) {
        StepStatus.EXECUTED, StepStatus.RETRIED -> "done"
        StepStatus.SKIPPED -> "skip"
        else -> "fail"
    }

    // Ordered timeline rows straight from the engine's execution records —
    // the demo renders one numbered circle per row, so the full prompt flow
    // reads top to bottom with the answer last. Pass the run's QA turns to
    // inline each step's own question and answer under its row.
    fun steps(
        telemetry: ExecutionTelemetry,
        qa: Map<String, QaTurn> = emptyMap()
    ): List<StepItem> {
        var compressN = 0
        var auditN = 0
        return telemetry.executionOrder.map { r ->
            val whenPart = if (r.durationMs > 0) "%.1fs".format(r.durationMs / 1000.0) else ""
            val detail = listOf(
                r.reason.takeIf { it.isNotBlank() },
                whenPart.takeIf { it.isNotBlank() }
            ).filterNotNull().joinToString(" · ")
            val key = when (r.step) {
                GatekeeperStep.STAGE_A_SECURITY_EVAL -> "stageA"
                GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION -> "compress#${++compressN}"
                GatekeeperStep.STAGE_D_ACCURACY_AUDIT -> "audit#${++auditN}"
                else -> ""
            }
            val turn = qa[key]
            StepItem(
                stepKind(r.status), stepLabel(r.step), detail,
                question = turn?.question.orEmpty(),
                answer = turn?.answer.orEmpty()
            )
        }
    }

    // Q/A ride Base64 so model text (pipes, newlines, emoji) can never
    // desync the "|" framing. java.util.Base64 is JVM- and Android-safe.
    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun unb64(s: String): String? = runCatching {
        String(java.util.Base64.getDecoder().decode(s), Charsets.UTF_8)
    }.getOrNull()

    fun encodeSteps(items: List<StepItem>): ArrayList<String> =
        ArrayList(items.map { "${it.kind}|${it.label}|${it.detail}|${b64(it.question)}|${b64(it.answer)}" })

    fun decodeSteps(raw: List<String>): List<StepItem> = raw.mapNotNull { s ->
        val parts = s.split("|", limit = 5)
        if (parts.size == 3) {
            // Pre-Q/A payloads (older broadcasts): pad with blanks.
            StepItem(parts[0], parts[1], parts[2])
        } else if (parts.size == 5) {
            val q = unb64(parts[3])
            val a = unb64(parts[4])
            if (q == null || a == null) null
            else StepItem(parts[0], parts[1], parts[2], q, a)
        } else null
    }

    fun skippedNote(skipped: Map<String, String>): String =
        if (skipped.isEmpty()) "" else "\nskipped: " +
            skipped.entries.joinToString("; ") { "${it.key} (${it.value})" }

    // Head-to-head line for benchmark mode (pipeline ms per leg, warmup
    // excluded on both sides). Names the faster leg and the speedup factor.
    fun benchmarkSummary(cpuMs: Long, xnnpackMs: Long): String {
        fun s(ms: Long): String = "%.1fs".format(ms / 1000.0)
        if (cpuMs <= 0 || xnnpackMs <= 0) {
            return "Benchmark: CPU ${s(cpuMs)} vs XNNPACK ${s(xnnpackMs)} (incomplete)"
        }
        return if (xnnpackMs < cpuMs) {
            "Benchmark: CPU ${s(cpuMs)} vs XNNPACK ${s(xnnpackMs)} " +
                "(XNNPACK ${"%.1f".format(cpuMs.toDouble() / xnnpackMs)}× faster)"
        } else {
            "Benchmark: CPU ${s(cpuMs)} vs XNNPACK ${s(xnnpackMs)} " +
                "(CPU ${"%.1f".format(xnnpackMs.toDouble() / cpuMs)}× faster)"
        }
    }

    fun format(
        result: GatekeeperResult,
        mode: String,
        resourceLine: String? = null,
        answer: String? = null
    ): Triple<String, String, String> {
        val resSuffix = resourceLine?.let { "\n$it" } ?: ""
        return when (result) {
            is GatekeeperResult.Success -> {
                val t = result.telemetry
                Triple(
                    mode + "SUCCESS (heat=${result.heat})",
                    result.safeCompressedPrompt +
                        (answer?.let { "\n\n— Answer —\n$it" } ?: ""),
                    "Tokens: ${t.preCompressionTokens} → ${t.postCompressionTokens} " +
                        "(${String.format("%.1f", t.compressionRatioPct)}% saved) · " +
                        "Compress ×${t.compressionIterations} · Audit ×${t.auditIterations}\n" +
                        "Time: ${seconds(t.totalDurationMs)} · Steps: ${t.executionOrder.size} · " +
                        "Redactions: ${redactions(t.redactionEvents)}$resSuffix" +
                        skippedNote(t.skippedSteps)
                )
            }
            is GatekeeperResult.Blocked -> {
                Triple(
                    mode + "BLOCKED (heat=${result.heat}): ${result.reason}",
                    "(nothing sent anywhere)",
                    "Time: ${seconds(result.telemetry.totalDurationMs)} · " +
                        "Redactions: ${redactions(result.telemetry.redactionEvents)}$resSuffix" +
                        skippedNote(result.telemetry.skippedSteps)
                )
            }
            is GatekeeperResult.FallbackRequired -> {
                val t = result.telemetry
                val reasonLine = if (t.expansionGuardFailed) {
                    "expansion guard failed — model cannot compress this input"
                } else if (t.maxRetriesExhausted) {
                    "retries exhausted"
                } else {
                    ""
                }
                Triple(
                    mode + "FALLBACK: ${result.reason}",
                    result.sanitizedPrompt,
                    "Compress ×${t.compressionIterations} · Audit ×${t.auditIterations} · " +
                        "Time: ${seconds(t.totalDurationMs)} · Steps: ${t.executionOrder.size} · " +
                        "Redactions: ${redactions(t.redactionEvents)}" +
                        (if (reasonLine.isNotEmpty()) " · $reasonLine" else "") +
                        resSuffix + skippedNote(t.skippedSteps)
                )
            }
        }
    }

    private fun seconds(ms: Long): String = "%.1fs".format(ms / 1000.0)

    private fun redactions(events: List<String>): String =
        if (events.isEmpty()) "none" else events.joinToString(", ")
}
