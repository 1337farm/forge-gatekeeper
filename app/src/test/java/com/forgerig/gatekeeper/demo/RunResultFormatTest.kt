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
}
