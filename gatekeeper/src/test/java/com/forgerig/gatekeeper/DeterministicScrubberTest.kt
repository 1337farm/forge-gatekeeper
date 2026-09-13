package com.forgerig.gatekeeper

import com.forgerig.gatekeeper.sanitizer.DeterministicScrubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicScrubberTest {

    @Test
    fun `redacts email with word boundaries`() {
        val r = DeterministicScrubber.scrub("contact alice@example.com now")
        assertTrue(r.sanitizedText.contains("[EMAIL_REDACTED]"))
        assertFalse(r.sanitizedText.contains("alice@example.com"))
    }

    @Test
    fun `redacts ipv4`() {
        val r = DeterministicScrubber.scrub("server at 192.168.1.10 down")
        assertTrue(r.sanitizedText.contains("[IP_REDACTED]"))
    }

    @Test
    fun `redacts aws key`() {
        val r = DeterministicScrubber.scrub("key AKIAIOSFODNN7EXAMPLE here")
        assertTrue(r.sanitizedText.contains("[API_KEY_REDACTED]"))
    }

    @Test
    fun `redacts valid credit card via luhn`() {
        val r = DeterministicScrubber.scrub("pay 4111111111111111 now")
        assertTrue(r.sanitizedText.contains("[CARD_REDACTED]"))
    }

    @Test
    fun `does not redact non-luhn digit runs`() {
        val r = DeterministicScrubber.scrub("build 1234567890123 done")
        assertFalse(r.sanitizedText.contains("[CARD_REDACTED]"))
    }

    @Test
    fun `preserves whitespace topology and code blocks`() {
        val code = "def f():\n    return 1\n\n\nx = 2  # contact bob@test.io"
        val r = DeterministicScrubber.scrub(code)
        assertTrue(r.sanitizedText.contains("def f():\n    return 1\n\n\nx = 2"))
        assertTrue(r.sanitizedText.contains("    return 1"))
        assertTrue(r.sanitizedText.contains("\n\n\n"))
        assertTrue(r.sanitizedText.contains("[EMAIL_REDACTED]"))
    }

    @Test
    fun `masks generic secret without flattening`() {
        val r = DeterministicScrubber.scrub("line1\napi_key: supersecretvalue123\nline3")
        assertTrue(r.sanitizedText.contains("api_key=[SECRET_REDACTED]"))
        assertTrue(r.sanitizedText.startsWith("line1\n"))
        assertTrue(r.sanitizedText.endsWith("\nline3"))
    }

    @Test
    fun `plain text passes through untouched`() {
        val plain = "Hello world, please summarize this doc."
        val r = DeterministicScrubber.scrub(plain)
        assertEquals(plain, r.sanitizedText)
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun `shannon entropy of uniform string is zero`() {
        assertEquals(0.0, DeterministicScrubber.shannonEntropy("aaaaaaaa"), 1e-9)
    }

    @Test
    fun `shannon entropy of random token is high`() {
        assertTrue(DeterministicScrubber.shannonEntropy("xK9#mQ2\$vL8@nP4wZ7!") > 3.5)
    }

    @Test
    fun `source never uses split flattening`() {
        val src = java.io.File("src/main/java/com/forgerig/gatekeeper/sanitizer/DeterministicScrubber.kt")
            .readText()
        assertFalse(src.contains("split(Regex"))
    }
}
