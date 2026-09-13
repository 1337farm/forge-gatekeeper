package com.forgerig.nanogatekeeper.demo

import com.forgerig.nanogatekeeper.demo.ModelDownloader.RepoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelDownloaderTest {

    @Test
    fun `repo with subfolder parses`() {
        val ref = ModelDownloader.parseRepoRef(
            "microsoft/Phi-3-mini-4k-instruct-onnx:cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4"
        )
        assertEquals(RepoRef(
            "microsoft/Phi-3-mini-4k-instruct-onnx",
            "cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4"
        ), ref)
    }

    @Test
    fun `bare repo parses with null subfolder`() {
        assertEquals(RepoRef("owner/model", null), ModelDownloader.parseRepoRef("owner/model"))
        assertEquals(RepoRef("owner/model", null), ModelDownloader.parseRepoRef(" owner/model/ "))
    }

    @Test
    fun `repo with empty subfolder yields null subfolder`() {
        assertEquals(RepoRef("owner/model", null), ModelDownloader.parseRepoRef("owner/model:"))
    }

    @Test
    fun `malformed refs rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownloader.parseRepoRef("not-a-repo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownloader.parseRepoRef("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownloader.parseRepoRef("a/b/c")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownloader.parseRepoRef("a/b/c:sub")
        }
    }

    @Test
    fun `default ORT ref points at the public phi3 model`() {
        val ref = ModelDownloader.parseRepoRef(ModelDownloader.DEFAULT_ORT_REF)
        assertEquals("microsoft/Phi-3-mini-4k-instruct-onnx", ref.repo)
        assertEquals(
            "cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4",
            ref.subfolder
        )
    }
}