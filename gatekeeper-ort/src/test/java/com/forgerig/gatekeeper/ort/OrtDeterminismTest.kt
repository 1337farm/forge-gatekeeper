package com.forgerig.gatekeeper.ort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrtDeterminismTest {

    @Test
    fun `ort backend locks determinism`() {
        assertEquals(0.0, OrtGenAiClient.DETERMINISTIC_TEMPERATURE, 1e-9)
        assertEquals(1, OrtGenAiClient.DETERMINISTIC_TOP_K)
        assertEquals(45, OrtGenAiClient.STRUCTURED_MAX_LENGTH)
        assertTrue(OrtGenAiClient.DETERMINISTIC_STOP_SEQUENCES.contains("\n"))
        assertTrue(OrtGenAiClient.DETERMINISTIC_STOP_SEQUENCES.contains("<|endoftext|>"))
    }
}
