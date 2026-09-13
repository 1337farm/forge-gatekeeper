package com.forgerig.nanogatekeeper.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Demo-only model fetcher that needs NO credentials:
//  - single-file `.task` bundles for the MediaPipe backend, and
//  - whole GenAI folders for the bare-metal ORT backend, pulled from a
//    Hugging Face repo's file listing (public repos require no token).
// Network is used ONLY here; inference itself is fully offline.
object ModelDownloader {

    const val DEFAULT_MODEL_URL =
        "https://huggingface.co/google/gemma-3n-E2B-it-int4/resolve/main/gemma-3n-E2B-it-int4.task"
    const val DEFAULT_MODEL_FILE = "gemma-3n-E2B-it-int4.task"

    // Tokenless default for the ORT backend: MIT-licensed, public, int4,
    // ~2.5 GB weights -> fits the 12 GB target envelope.
    const val DEFAULT_ORT_REPO = "microsoft/Phi-3-mini-4k-instruct-onnx"
    const val DEFAULT_ORT_SUBFOLDER = "cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4"
    const val DEFAULT_ORT_REF = "$DEFAULT_ORT_REPO:$DEFAULT_ORT_SUBFOLDER"

    data class RepoRef(val repo: String, val subfolder: String?)

    // "owner/name" or "owner/name:sub/folder".
    fun parseRepoRef(text: String): RepoRef {
        val trimmed = text.trim().trimEnd('/')
        val (repoPart, sub) = if (trimmed.contains(':')) {
            trimmed.substringBefore(':') to trimmed.substringAfter(':', "").trim('/')
        } else {
            trimmed to ""
        }
        val repo = repoPart.trim('/')
        if (repo.isBlank() || repo.count { it == '/' } != 1) {
            throw IllegalArgumentException("Expected owner/repo or owner/repo:subfolder")
        }
        return RepoRef(repo, sub.ifBlank { null })
    }

    suspend fun download(
        url: String,
        hfToken: String?,
        dest: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val conn = open(url, hfToken)
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Model download HTTP ${conn.responseCode}: ${conn.responseMessage}")
            }
            dest.transfer(conn, onProgress)
        }
    }

    // Downloads every inference-relevant file of a public HF repo subfolder
    // into destDir (flat: genai_config.json + weights + tokenizer land at its
    // root, which is exactly what OrtModelDir / the ORT demo expect).
    suspend fun downloadOrtFolder(
        ref: RepoRef,
        destDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val entries = listOrtFiles(ref.repo, ref.subfolder)
            if (entries.isEmpty()) {
                throw IOException(
                    "No model files at ${ref.repo}/${ref.subfolder ?: ""} " +
                        "(wrong path, or gated repo needs a token the demo will not use)."
                )
            }
            val knownTotal = entries.sumOf { it.byteSize }
            destDir.mkdirs()
            var doneBytes = 0L
            for (entry in entries) {
                val dest = File(destDir, entry.fileName())
                val resolve =
                    "https://huggingface.co/${ref.repo}/resolve/main/" +
                        (ref.subfolder?.let { "$it/" } ?: "") + entry.path.substringAfterLast('/')
                val conn = open(resolve, null)
                conn.connect()
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("$resolve HTTP ${conn.responseCode}: ${conn.responseMessage}")
                }
                var fileTotal = entry.byteSize
                dest.transfer(conn) { read, total ->
                    if (total > 0) fileTotal = total
                    onProgress(doneBytes + read, knownTotal)
                }
                doneBytes += fileTotal
            }
            if (doneBytes <= 0L) throw IOException("Downloaded model folder is empty")
        }
    }

    @Serializable
    private data class HfTreeEntry(
        val type: String = "",
        val path: String = "",
        val size: Long = 0,
        val lfs: HfLfs? = null
    ) {
        fun fileName() = path.substringAfterLast('/')
        val byteSize: Long get() = lfs?.size ?: size
    }

    @Serializable
    private data class HfLfs(val size: Long = 0)

    // Public tree API: for public repos it answers with no credentials. Only
    // inference-relevant files are kept (ORT GenAI needs genai_config.json,
    // the tokenizer files and the model weights).
    private fun listOrtFiles(repo: String, subfolder: String?): List<HfTreeEntry> {
        val sub = subfolder?.trim('/') ?: ""
        val api = "https://huggingface.co/api/models/$repo/tree/main" +
            (if (sub.isNotEmpty()) "/$sub" else "") + "?recursive=false"
        val conn = open(api, null)
        conn.connect()
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Model folder metadata HTTP ${conn.responseCode}: ${conn.responseMessage}")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val entries = try {
            Json { ignoreUnknownKeys = true }.decodeFromString<List<HfTreeEntry>>(body)
        } catch (e: Exception) {
            throw IOException("Cannot parse Hugging Face file listing: ${e.message}", e)
        }
        return entries
            .filter { it.type == "file" && isOrtRelevant(it.fileName()) }
            .sortedBy { it.fileName() }
    }

    private fun isOrtRelevant(name: String): Boolean = when {
        name == "genai_config.json" -> true
        name == "config.json" -> true
        name == "tokenizer.json" || name == "tokenizer.model" ||
            name == "tokenizer_config.json" -> true
        name == "added_tokens.json" || name == "special_tokens_map.json" -> true
        name.endsWith(".onnx", ignoreCase = true) ||
            name.endsWith(".onnx.data", ignoreCase = true) -> true
        else -> false
    }

    // Atomic single-file transfer: stream to <name>.part, fsync, then rename.
    // hfToken stays optional; leaving it blank hits public repos tokenlessly.
    private fun File.transfer(
        conn: HttpURLConnection,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        parentFile?.mkdirs()
        val tmp = File(parent, "$name.part")
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    out.flush()
                    onProgress(done, total)
                }
            }
        }
        if (tmp.length() == 0L) throw IOException("Downloaded $name is empty")
        if (exists() && !delete()) throw IOException("Cannot replace old $name")
        if (!tmp.renameTo(this)) throw IOException("Cannot finalize $name")
    }

    private fun open(url: String, hfToken: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ForgeNanoGatekeeperDemo/1.0")
            if (!hfToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer ${hfToken.trim()}")
            }
        }
}