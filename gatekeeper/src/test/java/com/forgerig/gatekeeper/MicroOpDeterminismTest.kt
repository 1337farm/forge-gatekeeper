package com.forgerig.gatekeeper

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zero-knowledge micro-op determinism guards: resource prompts carry the
 * single-character boundaries (H:/I:/R:, S:/D:/F:).
 * No Context/Mockito needed — runs on-device-less CI and Termux alike.
 * (Single-letter parsing itself is covered by GatekeeperEngineTest on CI,
 * where Mockito can self-attach; Termux JDK blocks ByteBuddy self-attach.)
 */
class MicroOpDeterminismTest {

    private fun resource(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("prompts/$name")
            ?: throw IllegalStateException("missing resource prompts/$name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `stage A resource uses single character keys`() {
        val t = resource("stage_a_security.txt")
        assertTrue(t.contains("H:[COLD|WARM|HOT]"))
        assertTrue(t.contains("I:[SAFE|MALICIOUS]"))
        assertTrue(t.contains("R:[One-sentence safety reason]"))
        assertTrue(t.contains("Stop generating text immediately after the R line value."))
    }

    @Test
    fun `stage C resource treats abstract macro instructions as complete`() {
        val t = resource("stage_c_compressor.txt")
        assertTrue(t.contains("Treat all abstract commands"))
        assertTrue(t.contains("continue work"))
        assertTrue(t.contains("Output the optimized text string ONLY"))
    }

    @Test
    fun `stage D resource uses single character keys`() {
        val t = resource("stage_d_auditor.txt")
        assertTrue(t.contains("S:[MATCH|MISMATCH]"))
        assertTrue(t.contains("D:[0.0 to 1.0]"))
        assertTrue(t.contains("F:[One-sentence corrective feedback or NONE]"))
        assertTrue(t.contains("Stop generating text immediately after the F line value."))
    }
}
