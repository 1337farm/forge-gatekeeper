package com.forgerig.nanogatekeeper

import com.forgerig.nanogatekeeper.prompts.SystemPrompts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptsTest {

    @Test
    fun `security prompt is strict json schema`() {
        val p = SystemPrompts.securityPrompt()
        assertTrue(p.contains("ONLY valid JSON"))
        assertTrue(p.contains("MALICIOUS"))
    }

    @Test
    fun `compression prompt preserves entities`() {
        val p = SystemPrompts.compressionPrompt()
        assertTrue(p.contains("PRESERVE EXACTLY"))
        assertTrue(p.contains("Do not add"))
    }

    @Test
    fun `audit prompt distinguishes match mismatch`() {
        val p = SystemPrompts.auditPrompt()
        assertTrue(p.contains("MATCH|MISMATCH"))
        assertTrue(p.contains("corrective_feedback"))
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
