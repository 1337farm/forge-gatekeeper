package com.forgerig.gatekeeper.litert

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtDeterminismTest {

    @Test
    fun `litert backend locks determinism`() {
        assertEquals(0.0f, MediaPipeLlmClient.DETERMINISTIC_TEMPERATURE, 1e-6f)
        assertEquals(1, MediaPipeLlmClient.DETERMINISTIC_TOP_K)
        assertEquals(45, MediaPipeLlmClient.STRUCTURED_MAX_LENGTH)
    }
}
