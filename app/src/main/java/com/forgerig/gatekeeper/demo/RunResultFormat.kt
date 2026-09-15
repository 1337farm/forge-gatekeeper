package com.forgerig.gatekeeper.demo

import com.forgerig.gatekeeper.model.GatekeeperResult

// Pure result formatting shared by the foreground service (broadcasts
// pre-rendered strings) and anything else that displays a run outcome.
object RunResultFormat {
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
                    "tokens ${t.preCompressionTokens} → ${t.postCompressionTokens} " +
                        "(${String.format("%.1f", t.compressionRatioPct)}% saved) · " +
                        "compress iters=${t.compressionIterations} audit iters=${t.auditIterations} · " +
                        "steps=${t.executionOrder.size} total=${t.totalDurationMs}ms · " +
                        "redactions=${t.redactionEvents}$resSuffix"
                )
            }
            is GatekeeperResult.Blocked -> {
                Triple(
                    mode + "BLOCKED (heat=${result.heat}): ${result.reason}",
                    "(nothing sent anywhere)",
                    "total=${result.telemetry.totalDurationMs}ms · " +
                        "redactions=${result.telemetry.redactionEvents}$resSuffix"
                )
            }
            is GatekeeperResult.FallbackRequired -> {
                val t = result.telemetry
                Triple(
                    mode + "FALLBACK: ${result.reason}",
                    result.sanitizedPrompt,
                    "maxRetriesExhausted=${t.maxRetriesExhausted} · " +
                        "compress iters=${t.compressionIterations} audit iters=${t.auditIterations} · " +
                        "steps=${t.executionOrder.size} total=${t.totalDurationMs}ms · " +
                        "redactions=${t.redactionEvents}$resSuffix"
                )
            }
        }
    }
}
