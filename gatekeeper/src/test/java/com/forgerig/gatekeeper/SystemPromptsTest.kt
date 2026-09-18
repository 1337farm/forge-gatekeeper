package com.forgerig.gatekeeper

import com.forgerig.gatekeeper.prompts.SystemPrompts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptsTest {

    @Test
    fun `security prompt is strict labeled lines`() {
        val p = SystemPrompts.securityPrompt()
        assertTrue(p.contains("HEAT:"))
        assertTrue(p.contains("AMBIENT_PII:"))
        assertTrue(p.contains("MALICIOUS"))
        assertFalse(p.contains("{"))
    }

    @Test
    fun `compression prompt preserves entities`() {
        val p = SystemPrompts.compressionPrompt()
        assertTrue(p.contains("PRESERVE EXACTLY"))
        assertTrue(p.contains("Do not add"))
        assertTrue(p.contains("never an answerer") || p.contains("never answer"))
    }

    @Test
    fun `audit prompt distinguishes match mismatch`() {
        val p = SystemPrompts.auditPrompt()
        assertTrue(p.contains("MATCH|MISMATCH") || p.contains("STATUS:"))
        assertTrue(p.contains("FEEDBACK"))
        assertTrue(p.contains("same thing"))
        assertFalse(p.contains("{"))
    }

    @Test
    fun `decoded prompts are non-empty and distinct`() {
        val a = SystemPrompts.securityPrompt()
        val c = SystemPrompts.compressionPrompt()
        val d = SystemPrompts.auditPrompt()
        assertFalse(a.isBlank())
        assertFalse(c.isBlank())
        assertFalse(d.isBlank())
        assertFalse(a == c)
        assertFalse(c == d)
    }
}
