package com.forgerig.gatekeeper.demo

import com.forgerig.gatekeeper.model.ExecutionTelemetry
import com.forgerig.gatekeeper.model.GatekeeperResult
import com.forgerig.gatekeeper.model.HeatLevel
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
}
