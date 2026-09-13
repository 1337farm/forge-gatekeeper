package com.forgerig.nanogatekeeper

import com.forgerig.nanogatekeeper.security.PromptObfuscator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PromptObfuscatorTest {

    @Test
    fun `mask roundtrip preserves content`() {
        val plain = "You are a lossless semantic compressor."
        val decoded = PromptObfuscator.decodeToString(PromptObfuscator.mask(plain.toByteArray(Charsets.UTF_8)))
        assertEquals(plain, decoded)
    }

    @Test
    fun `masked bytes differ from plaintext`() {
        val plain = "secret prompt".toByteArray(Charsets.UTF_8)
        val masked = PromptObfuscator.mask(plain)
        assertFalse(masked.contentEquals(plain))
        assertEquals(plain.size, masked.size)
    }

    @Test
    fun `unmask is symmetric`() {
        val raw = "audit judge".toByteArray(Charsets.UTF_8)
        assertEquals(String(raw, Charsets.UTF_8), String(PromptObfuscator.unmask(PromptObfuscator.mask(raw)), Charsets.UTF_8))
    }
}
