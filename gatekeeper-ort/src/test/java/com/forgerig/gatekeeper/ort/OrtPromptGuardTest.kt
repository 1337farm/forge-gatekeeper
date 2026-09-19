package com.forgerig.gatekeeper.ort

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrtPromptGuardTest {

    @Test
    fun `short prompts pass the guard`() {
        assertFalse(OrtGenAiClient.promptTooLong(2000, 512))
    }

    @Test
    fun `observed 1244-token failure now passes pre-flight`() {
        // The real Stage A failure: ~5200 prompt chars (≈1244 true tokens)
        // rejected by a blind 512-token headroom although prompt + 512 new
        // fits the 4096 window comfortably. Native sizes max_length from the
        // true count; the guard must not reintroduce the false rejection.
        assertFalse(OrtGenAiClient.promptTooLong(5200, 512))
    }

    @Test
    fun `genuinely oversize prompts fail fast`() {
        // ~3900 estimated tokens + 512 new clearly exceeds the window.
        assertTrue(OrtGenAiClient.promptTooLong(15000, 512))
    }

    @Test
    fun `window constant matches native clamp`() {
        assertTrue(OrtGenAiClient.CONTEXT_WINDOW_TOKENS == 4096)
    }
}
