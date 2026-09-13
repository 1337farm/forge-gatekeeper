package com.forgerig.nanogatekeeper.ort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OrtModelDirTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `missing dir reported`() {
        val missing = OrtModelDir.missingEntries(File(tmp.root, "nope"))
        assertEquals(1, missing.size)
        assertTrue(missing[0].startsWith("not a directory"))
    }

    @Test
    fun `empty dir reports config and weights`() {
        val dir = tmp.newFolder("empty")
        val missing = OrtModelDir.missingEntries(dir)
        assertTrue(missing.contains(OrtModelDir.GENAI_CONFIG))
        assertTrue(missing.contains("model weights/tokenizer files"))
    }

    @Test
    fun `valid folder passes`() {
        val dir = tmp.newFolder("model")
        File(dir, OrtModelDir.GENAI_CONFIG).writeText("{}")
        File(dir, "model.onnx").writeBytes(ByteArray(16))
        File(dir, "tokenizer.json").writeText("{}")
        assertTrue(OrtModelDir.missingEntries(dir).isEmpty())
        OrtModelDir.requireValid(dir)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requireValid throws without native lib`() {
        // Must fail BEFORE any System.loadLibrary: no .so needed for this test.
        OrtModelDir.requireValid(File(tmp.root, "nope"))
    }
}
