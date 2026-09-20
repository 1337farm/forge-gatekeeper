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

    data class UiChip(val text: String, val tone: String)
    data class StepUi(
        val statusText: String,
        val statusTone: String,
        val stageText: String,
        val stageTone: String,
        val chips: List<UiChip>
    )
    data class StepRequest(val system: String, val user: String)

    fun stepUi(item: StepItem): StepUi {
        val statusText = when (item.kind) {
            "done" -> "DONE"
            "skip" -> "SKIPPED"
            "pending" -> "PENDING"
            else -> "FAILED"
        }
        val statusTone = when (item.kind) {
            "done" -> "success"
            "skip" -> "muted"
            "pending" -> "muted"
            else -> "warning"
        }
        val upper = item.detail.uppercase()
        val stage = when {
            item.label.startsWith("Scrub") -> "SCRUB" to "info"
            item.label.startsWith("Hardware") -> "DEVICE" to "info"
            item.label.startsWith("Stage A") -> "SAFETY" to "info"
            item.label.startsWith("Stage B") -> "PII" to "info"
            item.label.startsWith("Stage C") -> "COMPRESS" to if (item.kind == "fail") "error" else "warning"
            item.label.startsWith("Stage D") -> "AUDIT" to when {
                upper.contains("MISMATCH") -> "warning"
                upper.contains("MATCH") -> "success"
                else -> "info"
            }
            // Live short labels ("Compress ✓ …") share their final pills.
            item.label.startsWith("Compress") -> "COMPRESS" to if (item.kind == "fail") "error" else "warning"
            item.label.startsWith("Audit") -> "AUDIT" to "info"
            item.label.startsWith("Fallback") -> "FALLBACK" to "warning"
            item.label.startsWith("Answer") -> "ANSWER" to "success"
            item.label.startsWith("Done") -> "DONE" to "success"
            item.label.startsWith("Skipped") -> "SKIPPED" to "muted"
            else -> "STEP" to "muted"
        }
        val chips = mutableListOf<UiChip>()
        if (upper.contains("MISMATCH")) chips.add(UiChip("MISMATCH", "warning"))
        else if (upper.contains("MATCH")) chips.add(UiChip("MATCH", "success"))
        if (upper.contains("MALICIOUS")) chips.add(UiChip("MALICIOUS", "error"))
        else if (upper.contains("SAFE")) chips.add(UiChip("SAFE", "success"))
        if (upper.contains("MISSING_CONTEXT")) chips.add(UiChip("NEEDS CONTEXT", "warning"))
        else if (upper.contains("READY")) chips.add(UiChip("READY", "success"))
        if (upper.contains("NO AMBIENT PII")) chips.add(UiChip("NO PII", "muted"))
        if (upper.contains("ELIGIBLE")) chips.add(UiChip("ELIGIBLE", "success"))
        if (upper.contains("NO COMPRESSION") || upper.contains("EXPANSION GUARD") || upper.contains("OUTPUT EXPANDED")) {
            chips.add(UiChip("GUARD", "error"))
        }
        if (upper.contains("RETRIES EXHAUSTED") || upper.contains("MAXRETRIES")) chips.add(UiChip("RETRIES", "warning"))
        if (upper.contains("TIMEOUT")) chips.add(UiChip("TIMEOUT", "error"))
        Regex("(?i)\\bheat\\s*[:=]\\s*([A-Za-z]+)").find(item.detail)?.let {
            val value = it.groupValues[1].uppercase()
            chips.add(UiChip(value, when (value) {
                "COLD" -> "info"
                "WARM" -> "warning"
                "HOT" -> "error"
                else -> "muted"
            }))
        }
        Regex("(?i)\\binjection\\s*[:=]\\s*([A-Za-z]+)").find(item.detail)?.let {
            val value = it.groupValues[1].uppercase()
            if (value != "SAFE" && value != "MALICIOUS") chips.add(UiChip(value, "muted"))
        }
        Regex("(?i)\\bcompleteness\\s*[:=]\\s*([A-Za-z_]+)").find(item.detail)?.let {
            val value = it.groupValues[1].uppercase()
            if (value != "READY" && value != "MISSING_CONTEXT") chips.add(UiChip(value, "muted"))
        }
        Regex("(?i)\\bdrift\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)").find(item.detail)?.let {
            val drift = it.groupValues[1].toDoubleOrNull() ?: 0.0
            chips.add(UiChip("DRIFT $drift", if (drift >= 0.5) "error" else "warning"))
        }
        Regex("(?i)\\bredactions\\s*[:=]\\s*(\\d+)").find(item.detail)?.let {
            val count = it.groupValues[1].toIntOrNull() ?: 0
            chips.add(UiChip("PII $count", if (count == 0) "muted" else "success"))
        }
        Regex("(?i)\\biter\\s*[:=]\\s*(\\d+)").find(item.detail)?.let {
            chips.add(UiChip("ITER ${it.groupValues[1]}", "muted"))
        }
        Regex("(?i)\\bin\\s*[:=]?\\s*(\\d+)\\s+out\\s*[:=]?\\s*(\\d+)").find(item.detail)?.let {
            val input = it.groupValues[1].toIntOrNull() ?: 0
            val output = it.groupValues[2].toIntOrNull() ?: 0
            val compressed = input > 0 && output in 1..input - 1
            chips.add(UiChip("$input→$output", if (compressed) "success" else "warning"))
        }
        return StepUi(statusText, statusTone, stage.first, stage.second, chips.take(4))
    }

    fun debugChips(debug: String): List<UiChip> {
        val chips = mutableListOf<UiChip>()
        val providerLines = Regex(
            "(?m)^\\[(?:(cpu|xnnpack):)?provider\\]\\s+" +
                "requested=([^\\s]+)\\s+actual=([^\\s]+)\\s+" +
                "warmMs=(\\d+)\\s+reused=(\\w+)$"
        ).findAll(debug).toList()
        val multiLegCount = providerLines.size
        providerLines.forEach { match ->
            val leg = match.groupValues[1]
            // Tag the leg only in multi-leg (benchmark) runs: single-leg
            // chips keep their short text, while benchmark legs no longer
            // collapse into one chip via distinctBy.
            val multiLeg = multiLegCount > 1
            val tag = if (leg.isNotBlank() && multiLeg) " [$leg]" else ""
            val requested = match.groupValues[2]
            val actual = match.groupValues[3].takeUnless { it == "?" } ?: requested
            if (actual.isNotBlank()) chips.add(UiChip("EP ${actual.uppercase()}$tag", "info"))
            val warmMs = match.groupValues[4].toLongOrNull() ?: 0L
            if (warmMs > 0) chips.add(UiChip("WARM ${"%.1f".format(warmMs / 1000.0)}s$tag", "muted"))
            val reused = match.groupValues[5].toBoolean()
            chips.add(UiChip((if (reused) "REUSED" else "NEW") + tag, if (reused) "success" else "warning"))
        }
        if (debug.contains("force-all ON", ignoreCase = true)) {
            chips.add(UiChip("FORCE-ALL", "warning"))
        }
        return chips.distinctBy { it.text to it.tone }
    }

    fun splitRequest(question: String): StepRequest {
        val text = question.trim()
        if (text.isEmpty()) return StepRequest("", "")
        val marker = Regex("(?im)(^|\\n)user:\\s*").find(text) ?: return StepRequest("", text)
        var system = text.substring(0, marker.range.first).trim()
        if (system.startsWith("system:", ignoreCase = true)) system = system.substring(7).trim()
        var user = text.substring(marker.range.last + 1).trim()
        if (user.startsWith("user:", ignoreCase = true)) user = user.substring(5).trim()
        return StepRequest(system, user)
    }

    // Maps a live "Skip <stage>: <reason>" line to the timeline section it
    // belongs in, so skipped stages resolve their pre-created shell instead
    // of spawning a stray "Skipped" section mid-run.
    fun skipSection(rest: String): String {
        val stage = rest.substringBefore(':').trim()
        return when {
            stage.startsWith("Stage B", ignoreCase = true) -> "Stage B"
            stage.contains("compress", ignoreCase = true) -> "Compress"
            stage.contains("audit", ignoreCase = true) -> "Audit"
            else -> "Skipped"
        }
    }

    // Maps a live progress line to the timeline section it belongs in, so
    // stage status and timing live inside their own card instead of the
    // status line above. Completion/skip/done lines return null — they
    // arrive as rows through liveStepItem, not status.
    fun progressSection(line: String): String? {
        val body = line.substringAfter("] ").trim()
        return when {
            body.startsWith("Stage A") -> "Stage A"
            body.startsWith("Stage B") -> "Stage B"
            body.startsWith("Compress") -> "Compress"
            body.startsWith("Audit") -> "Audit"
            body.startsWith("Answering") -> "Answer"
            body.startsWith("Scrub") -> "Scrub"
            body.startsWith("Hardware") -> "Hardware"
            else -> null
        }
    }

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

    // Single Q/A turn codec for the service's reopen-replay snapshot, keyed
    // by engine label. Base64 never contains "|", so the framing can't desync.
    fun encodeQa(question: String, answer: String): String =
        "${b64(question)}|${b64(answer)}"

    fun decodeQa(s: String): QaTurn? {
        val parts = s.split("|", limit = 2)
        if (parts.size != 2) return null
        val q = unb64(parts[0]) ?: return null
        val a = unb64(parts[1]) ?: return null
        return QaTurn(q, a)
    }

    fun decodeSteps(raw: List<String>): List<StepItem> = raw.mapNotNull { s ->
        val parts = s.split("|", limit = 5)
        if (parts.size == 3) {
            // Pre-Q/A payloads (older broadcasts): pad with blanks.
            StepItem(parts[0], parts[1], parts[2])
        } else if (parts.size == 5) {
            val q = unb64(parts[3])
            val a = unb64(parts[4])
            if (q == null || a == null) {
                // Corrupt payload: keep a visible placeholder so the
                // timeline never silently shortens.
                StepItem("skip", "Step", "undecodable timeline row")
            } else StepItem(parts[0], parts[1], parts[2], q, a)
        } else StepItem("skip", "Step", "undecodable timeline row")
    }

    fun resolveAuditPlaceholders(items: List<StepItem>): List<StepItem> {
        val compressAnswers = items
            .filter { it.label.startsWith("Stage C") }
            .map { it.answer }
        if (compressAnswers.isEmpty()) return items
        val placeholder = Regex("↳ compress iter (\\d+) output \\(logged above, fed in full\\)")
        return items.map { item ->
            if (!item.label.startsWith("Stage D")) item else {
                val match = placeholder.find(item.question) ?: return@map item
                val iter = match.groupValues[1].toIntOrNull() ?: return@map item
                val candidate = compressAnswers.getOrNull(iter - 1).orEmpty()
                if (candidate.isBlank()) item
                else item.copy(question = item.question.replace(match.value, candidate))
            }
        }
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
        // Telemetry is built as one string per line and joined with "\n" so a
        // reason line can never glue itself to the resource line ("…inputCPU
        // avg…"): every section owns its own line.
        fun lines(vararg parts: String?): String =
            parts.mapNotNull { it?.takeIf { s -> s.isNotBlank() } }.joinToString("\n")
        val skipped = { t: ExecutionTelemetry -> skippedNote(t.skippedSteps).trim().takeIf { it.isNotEmpty() } }
        return when (result) {
            is GatekeeperResult.Success -> {
                val t = result.telemetry
                Triple(
                    mode + "SUCCESS (heat=${result.heat})",
                    result.safeCompressedPrompt +
                        (answer?.let { "\n\n— Answer —\n$it" } ?: ""),
                    lines(
                        "Tokens: ${t.preCompressionTokens} → ${t.postCompressionTokens} " +
                            "(${String.format("%.1f", t.compressionRatioPct)}% saved) · " +
                            "Compress ×${t.compressionIterations} · Audit ×${t.auditIterations}",
                        "Time: ${seconds(t.totalDurationMs)} · Steps: ${t.executionOrder.size} · " +
                            "Redactions: ${redactions(t.redactionEvents)}",
                        resourceLine,
                        skipped(t)
                    )
                )
            }
            is GatekeeperResult.Blocked -> {
                Triple(
                    mode + "BLOCKED (heat=${result.heat}): ${result.reason}",
                    "(nothing sent anywhere)",
                    lines(
                        "Time: ${seconds(result.telemetry.totalDurationMs)} · " +
                            "Redactions: ${redactions(result.telemetry.redactionEvents)}",
                        resourceLine,
                        skipped(result.telemetry)
                    )
                )
            }
            is GatekeeperResult.FallbackRequired -> {
                val t = result.telemetry
                val reasonLine = if (t.expansionGuardFailed) {
                    "Reason: expansion guard failed — model cannot compress this input"
                } else if (t.maxRetriesExhausted) {
                    "Reason: retries exhausted"
                } else {
                    null
                }
                Triple(
                    mode + "FALLBACK: ${result.reason}",
                    result.sanitizedPrompt,
                    lines(
                        "Compress ×${t.compressionIterations} · Audit ×${t.auditIterations}",
                        "Time: ${seconds(t.totalDurationMs)} · Steps: ${t.executionOrder.size} · " +
                            "Redactions: ${redactions(t.redactionEvents)}",
                        reasonLine,
                        resourceLine,
                        skipped(t)
                    )
                )
            }
        }
    }

    private fun seconds(ms: Long): String = "%.1fs".format(ms / 1000.0)

    private fun redactions(events: List<String>): String =
        if (events.isEmpty()) "none" else events.joinToString(", ")
}
