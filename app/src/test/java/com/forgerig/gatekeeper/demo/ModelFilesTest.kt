package com.forgerig.gatekeeper.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelFilesTest {

    private fun filesDir(): File = Files.createTempDirectory("filesDir").toFile()

    @Test
    fun `empty dirs pick nothing`() {
        val root = filesDir()
        File(root, "ort-models").mkdirs()
        File(root, "models").mkdirs()
        assertNull(ModelFiles.pick(root))
    }

    @Test
    fun `valid ort folder wins over task file`() {
        val root = filesDir()
        val ort = File(File(root, "ort-models"), "phi")
        ort.mkdirs()
        File(ort, "genai_config.json").writeText("{}")
        File(ort, "model.onnx").writeText("x")
        val task = File(File(root, "models").apply { mkdirs() }, "m.task")
        task.writeBytes(ByteArray(8))
        assertEquals(ort, ModelFiles.pick(root))
    }

    @Test
    fun `incomplete ort folder falls through to task`() {
        val root = filesDir()
        val ort = File(File(root, "ort-models"), "phi")
        ort.mkdirs()
        File(ort, "genai_config.json").writeText("{}")
        val task = File(File(root, "models").apply { mkdirs() }, "m.task")
        task.writeBytes(ByteArray(8))
        assertEquals(task, ModelFiles.pick(root))
    }

    @Test
    fun `empty task file is skipped`() {
        val root = filesDir()
        val models = File(root, "models").apply { mkdirs() }
        File(models, "empty.task").createNewFile()
        assertNull(ModelFiles.pick(root))
    }
}
