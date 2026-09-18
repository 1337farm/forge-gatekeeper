package com.forgerig.gatekeeper.demo

import com.forgerig.gatekeeper.model.ExecutionTelemetry
import com.forgerig.gatekeeper.model.GatekeeperResult
import com.forgerig.gatekeeper.model.GatekeeperStep
import com.forgerig.gatekeeper.model.HeatLevel
import com.forgerig.gatekeeper.model.StepExecutionRecord
import com.forgerig.gatekeeper.model.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunResultFormatTest {

    private fun telemetry() = ExecutionTelemetry(
        executionOrder = emptyList(),
        skippedSteps = emptyMap(),
        compressionIterations = 0,
        auditIterations = 0,
        preCompressionTokens = 5,
        postCompressionTokens = 5,
        compressionRatioPct = 0.0
    )

    @Test
    fun `success without answer shows safe prompt only`() {
        val (_, output, _) = RunResultFormat.format(
            GatekeeperResult.Success("hello", "hello", HeatLevel.COLD, telemetry()),
            "[ORT native] "
        )
        assertTrue(output == "hello")
    }

    @Test
    fun `success with answer appends reply`() {
        val (_, output, _) = RunResultFormat.format(
            GatekeeperResult.Success("hello", "hello", HeatLevel.COLD, telemetry()),
            "[ORT native] ",
            answer = "Hi there!"
        )
        assertTrue(output.contains("hello"))
        assertTrue(output.contains("Hi there!"))
    }

    @Test
    fun `step ui exposes safety badges and chips`() {
        val ui = RunResultFormat.stepUi(
            RunResultFormat.StepItem(
                "done",
                "Stage A · Security eval",
                "heat=COLD injection=SAFE completeness=READY; · 10.2s",
                "system:\nSYS\nuser:\nUSER",
                "HEAT: COLD"
            )
        )
        assertEquals("DONE", ui.statusText)
        assertEquals("SAFETY", ui.stageText)
        val chips = ui.chips.map { it.text }
        assertTrue(chips.contains("SAFE"))
        assertTrue(chips.contains("READY"))
        assertTrue(chips.contains("COLD"))
    }

    @Test
    fun `step ui flags no-compression guard output`() {
        val ui = RunResultFormat.stepUi(
            RunResultFormat.StepItem(
                "fail",
                "Stage C · Compression",
                "no compression (26 vs 21 input tokens) · 18.4s"
            )
        )
        assertEquals("COMPRESS", ui.stageText)
        assertEquals("error", ui.stageTone)
        assertTrue(ui.chips.any { it.text == "GUARD" && it.tone == "error" })
    }

    @Test
    fun `debug chips parse provider warmup reuse and force all`() {
        val debug = """
            [xnnpack:provider] requested=XNNPACK actual=XNNPACK warmMs=3920 reused=true
            [config] force-all ON: Stage B + compression + audit + tiny inputs all run
        """.trimIndent()
        val chips = RunResultFormat.debugChips(debug)
        val texts = chips.map { it.text }
        assertTrue(texts.contains("EP XNNPACK"))
        assertTrue(texts.contains("WARM 3.9s"))
        assertTrue(texts.contains("REUSED"))
        assertTrue(texts.contains("FORCE-ALL"))
    }

    @Test
    fun `debug chips fall back to requested provider when actual unknown`() {
        val debug = "[provider] requested=CPU actual=? warmMs=1200 reused=false"
        val chips = RunResultFormat.debugChips(debug)
        val texts = chips.map { it.text }
        assertTrue(texts.contains("EP CPU"))
        assertTrue(texts.contains("WARM 1.2s"))
        assertTrue(texts.contains("NEW"))
    }

    @Test
    fun `split request separates system prompt and user text`() {
        val split = RunResultFormat.splitRequest("system:\nSYS RULES\nuser:\nUSER TEXT")
        assertEquals("SYS RULES", split.system)
        assertEquals("USER TEXT", split.user)
    }

    @Test
    fun `resolve audit placeholders substitutes compress candidate`() {
        val items = listOf(
            RunResultFormat.StepItem("done", "Stage C · Compression", "iter=1", "qC", "CANDIDATE ONE"),
            RunResultFormat.StepItem(
                "done", "Stage D · Accuracy audit", "MISMATCH",
                "ORIGINAL:\nfoo\n\nCOMPRESSED:\n↳ compress iter 1 output (logged above, fed in full)",
                "STATUS: MISMATCH"
            )
        )
        val resolved = RunResultFormat.resolveAuditPlaceholders(items)
        assertTrue(resolved[1].question.contains("CANDIDATE ONE"))
        assertTrue(!resolved[1].question.contains("logged above"))
    }

    @Test
    fun `resolve audit placeholders leaves unknown iters alone`() {
        val items = listOf(
            RunResultFormat.StepItem(
                "done", "Stage D · Accuracy audit", "MISMATCH",
                "ORIGINAL:\nfoo\n\nCOMPRESSED:\n↳ compress iter 9 output (logged above, fed in full)",
                "STATUS: MISMATCH"
            )
        )
        val resolved = RunResultFormat.resolveAuditPlaceholders(items)
        assertTrue(resolved[0].question.contains("logged above"))
    }

    @Test
    fun `steps timeline labels kinds and survives encode round-trip`() {
        val telemetry = telemetry().copy(
            executionOrder = listOf(
                StepExecutionRecord(0, GatekeeperStep.DETERMINISTIC_SCRUB, StepStatus.EXECUTED, "redactions=0", 0, 12),
                StepExecutionRecord(1, GatekeeperStep.STAGE_B_PII_REDACTION, StepStatus.SKIPPED, "no ambient PII"),
                StepExecutionRecord(2, GatekeeperStep.STAGE_A_SECURITY_EVAL, StepStatus.FAILED, "boom", 0, 300)
            )
        )
        val items = RunResultFormat.steps(telemetry)
        assertEquals(3, items.size)
        assertEquals("done", items[0].kind)
        assertEquals("Scrub", items[0].label)
        assertEquals("skip", items[1].kind)
        assertEquals("fail", items[2].kind)
        val decoded = RunResultFormat.decodeSteps(RunResultFormat.encodeSteps(items))
        assertEquals(items, decoded)
    }

    @Test
    fun `steps attach each turn to its own row`() {
        val telemetry = telemetry().copy(
            executionOrder = listOf(
                StepExecutionRecord(0, GatekeeperStep.STAGE_A_SECURITY_EVAL, StepStatus.EXECUTED, "heat=COLD", 0, 100),
                StepExecutionRecord(1, GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION, StepStatus.EXECUTED, "iter=1", 1, 200),
                StepExecutionRecord(2, GatekeeperStep.STAGE_C_SEMANTIC_COMPRESSION, StepStatus.EXECUTED, "iter=2", 2, 300),
                StepExecutionRecord(3, GatekeeperStep.STAGE_D_ACCURACY_AUDIT, StepStatus.EXECUTED, "MATCH", 1, 400)
            )
        )
        val qa = mapOf(
            "stageA" to RunResultFormat.QaTurn("qA", "aA"),
            "compress#1" to RunResultFormat.QaTurn("qC1", "aC1"),
            "compress#2" to RunResultFormat.QaTurn("qC2", "aC2"),
            // Pipes, newlines and emoji must not desync rows or leak across.
            "audit#1" to RunResultFormat.QaTurn("q|D1\n🤖", "a|D1")
        )
        val items = RunResultFormat.steps(telemetry, qa)
        assertEquals("qA", items[0].question)
        assertEquals("aA", items[0].answer)
        assertEquals("qC1", items[1].question)
        assertEquals("aC2", items[2].answer)
        assertEquals("q|D1\n🤖", items[3].question)
        assertEquals("a|D1", items[3].answer)
        assertEquals(items, RunResultFormat.decodeSteps(RunResultFormat.encodeSteps(items)))
    }

    @Test
    fun `benchmark summary names faster leg with factor`() {
        val line = RunResultFormat.benchmarkSummary(cpuMs = 45200, xnnpackMs = 31800)
        assertTrue(line.contains("CPU 45.2s"))
        assertTrue(line.contains("XNNPACK 31.8s"))
        assertTrue(line.contains("XNNPACK 1.4× faster"))
        val flipped = RunResultFormat.benchmarkSummary(cpuMs = 20000, xnnpackMs = 40000)
        assertTrue(flipped.contains("CPU 2.0× faster"))
    }

    @Test
    fun `fallback telemetry keeps reason and resource on separate lines`() {
        val t = telemetry().copy(expansionGuardFailed = true, maxRetriesExhausted = true)
        val (_, _, telemetryText) = RunResultFormat.format(
            GatekeeperResult.FallbackRequired("sanitized", "expansion guard failed", t),
            "[ORT XNNPACK] ",
            "CPU avg 392% · peak 428% · RAM 3384MB avg"
        )
        val lines = telemetryText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(lines.any { it.startsWith("Reason:") })
        assertTrue(lines.any { it.startsWith("CPU avg") })
        assertTrue(lines.none { it.contains("inputCPU") || it.contains("input CPU avg") })
    }
}
