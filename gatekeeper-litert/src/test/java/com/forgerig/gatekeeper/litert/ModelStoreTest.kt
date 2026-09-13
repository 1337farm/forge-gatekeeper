package com.forgerig.gatekeeper.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `empty dir lists nothing`() {
        assertTrue(ModelStore.listModels(tmp.root).isEmpty())
    }

    @Test
    fun `missing dir lists nothing`() {
        assertTrue(ModelStore.listModels(File(tmp.root, "nope")).isEmpty())
    }

    @Test
    fun `only task files listed, sorted`() {
        File(tmp.root, "b.task").writeBytes(ByteArray(8))
        File(tmp.root, "a.task").writeBytes(ByteArray(8))
        File(tmp.root, "notes.txt").writeText("x")
        File(tmp.root, "sub").mkdir()
        val names = ModelStore.listModels(tmp.root).map { it.name }
        assertEquals(listOf("a.task", "b.task"), names)
    }

    @Test
    fun `usability requires non-empty file`() {
        val missing = File(tmp.root, "m.task")
        val empty = File(tmp.root, "e.task").apply { createNewFile() }
        val full = File(tmp.root, "f.task").apply { writeBytes(ByteArray(8)) }
        assertFalse(ModelStore.isUsable(missing))
        assertFalse(ModelStore.isUsable(empty))
        assertTrue(ModelStore.isUsable(full))
    }

    @Test
    fun `default path nests under models dir`() {
        val f = ModelStore.defaultModelFile(tmp.root, "gemma.task")
        assertEquals(File(File(tmp.root, "models"), "gemma.task"), f)
    }
}
