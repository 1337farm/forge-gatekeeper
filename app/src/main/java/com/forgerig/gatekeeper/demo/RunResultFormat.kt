package com.forgerig.gatekeeper.demo

import com.forgerig.gatekeeper.model.ExecutionTelemetry
import com.forgerig.gatekeeper.model.GatekeeperResult
import com.forgerig.gatekeeper.model.GatekeeperStep
import com.forgerig.gatekeeper.model.StepStatus

// Pure result formatting shared by the foreground service (broadcasts
// pre-rendered strings) and anything else that displays a run outcome.
object RunResultFormat {
    // One timeline row: kind in {done, skip, fail} drives the circle style.
    data class StepItem(val kind: String, val label: String, val detail: String)

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
    // reads top to bottom with the answer last.
    fun steps(telemetry: ExecutionTelemetry): List<StepItem> =
        telemetry.executionOrder.map { r ->
            val whenPart = if (r.durationMs > 0) "%.1fs".format(r.durationMs / 1000.0) else ""
            val detail = listOf(
                r.reason.takeIf { it.isNotBlank() },
                whenPart.takeIf { it.isNotBlank() }
            ).filterNotNull().joinToString(" · ")
            StepItem(stepKind(r.status), stepLabel(r.step), detail)
        }

    fun encodeSteps(items: List<StepItem>): ArrayList<String> =
        ArrayList(items.map { "${it.kind}|${it.label}|${it.detail}" })

    fun decodeSteps(raw: List<String>): List<StepItem> = raw.mapNotNull { s ->
        val parts = s.split("|", limit = 3)
        if (parts.size == 3) StepItem(parts[0], parts[1], parts[2]) else null
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
                Triple(
                    mode + "FALLBACK: ${result.reason}",
                    result.sanitizedPrompt,
                    "Compress ×${t.compressionIterations} · Audit ×${t.auditIterations} · " +
                        "Time: ${seconds(t.totalDurationMs)} · Steps: ${t.executionOrder.size} · " +
                        "Redactions: ${redactions(t.redactionEvents)}" +
                        (if (t.maxRetriesExhausted) " · retries exhausted" else "") +
                        resSuffix + skippedNote(t.skippedSteps)
                )
            }
        }
    }

    private fun seconds(ms: Long): String = "%.1fs".format(ms / 1000.0)

    private fun redactions(events: List<String>): String =
        if (events.isEmpty()) "none" else events.joinToString(", ")
}
