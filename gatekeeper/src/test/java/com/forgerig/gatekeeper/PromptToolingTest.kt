package com.forgerig.gatekeeper

import com.forgerig.gatekeeper.model.PromptSet
import com.forgerig.gatekeeper.prompts.PromptFraming
import com.forgerig.gatekeeper.prompts.PromptLoader
import com.forgerig.gatekeeper.prompts.SystemPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLoaderTest {

    @Test
    fun `labeled set matches live system prompts`() {
        val p = PromptLoader.load(PromptSet.LABELED)
        assertEquals(SystemPrompts.securityPrompt(), p.security)
        assertEquals(SystemPrompts.compressionPrompt(), p.compression)
        assertEquals(SystemPrompts.auditPrompt(), p.audit)
        assertTrue(p.security.contains("HEAT:"))
        assertTrue(p.compression.contains("PRESERVE EXACTLY"))
        assertTrue(p.audit.contains("STATUS:"))
    }

    @Test
    fun `micro-op set carries single character contracts`() {
        val p = PromptLoader.load(PromptSet.MICRO_OP)
        assertTrue(p.security.contains("H:[COLD|WARM|HOT]"))
        assertTrue(p.security.contains("I:[SAFE|MALICIOUS]"))
        assertTrue(p.compression.contains("continue work"))
        assertTrue(p.audit.contains("S:[MATCH|MISMATCH]"))
        assertTrue(p.audit.contains("P:[semicolon-separated dropped items or NONE]"))
        assertTrue(p.audit.contains("A:[semicolon-separated added meanings or NONE]"))
        assertTrue(p.audit.contains("F:[One-sentence corrective feedback or NONE]"))
    }

    @Test
    fun `embedded micro-op copies never drift from resources`() {
        val embedded = PromptLoader.microOpEmbedded()
        assertEquals(resource("prompts/stage_a_security.txt"), embedded.security)
        assertEquals(resource("prompts/stage_c_compressor.txt"), embedded.compression)
        assertEquals(resource("prompts/stage_d_auditor.txt"), embedded.audit)
    }

    private fun resource(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: throw IllegalStateException("missing resource $name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

class PromptFramingTest {

    @Test
    fun `wrap labels user block under system prompt`() {
        assertEquals(
            "SYS\n\nUSER_TEXT:\nUSER",
            PromptFraming.wrap("SYS", "USER")
        )
    }

    @Test
    fun `wrap with blank system emits labeled user only`() {
        assertEquals(
            "USER_TEXT:\nUSER",
            PromptFraming.wrap("", "USER")
        )
    }

    @Test
    fun `user label matches prompt wording`() {
        assertEquals("USER_TEXT", PromptFraming.USER_LABEL)
    }
}
