package com.forgerig.gatekeeper.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Demo-only model fetcher that needs NO credentials and RESUMES instead of
// restarting: a failed transfer leaves `<name>.part`, and the next tap on
// Download sends a Range request that continues exactly where it stopped —
// a 2.5GB folder that died 1MB short resumes for that last megabyte.
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

    // "bytes 0-99/1000" -> (start, total). null when absent/malformed.
    internal fun parseContentRange(range: String?): Pair<Long, Long>? {
        if (range == null) return null
        val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").matchEntire(range.trim()) ?: return null
        return m.groupValues[1].toLong() to m.groupValues[3].toLong()
    }

    suspend fun download(
        url: String,
        hfToken: String?,
        dest: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            resumable(url, hfToken, dest, expectedTotal = 0) { done, total ->
                onProgress(done, total)
            }
        }
    }

    // Downloads every inference-relevant file of a public HF repo subfolder
    // into destDir (flat: genai_config.json + weights + tokenizer land at its
    // root, which is exactly what OrtModelDir / the ORT demo expect).
    // Already-complete files are skipped; half-finished ones resume in place,
    // so a folder download survives drop-outs anywhere in the sequence.
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
            val knownTotal = entries.sumOf { it.byteSizeCoalesced() }
            if (knownTotal <= 0L) throw IOException("Cannot size model folder for download")
            destDir.mkdirs()
            var doneBytes = 0L
            for (entry in entries) {
                val dest = File(destDir, entry.fileName())
                if (entry.byteSizeCoalesced() > 0 && dest.length() == entry.byteSizeCoalesced()) {
                    doneBytes += entry.byteSizeCoalesced()
                    continue
                }
                val resolve =
                    "https://huggingface.co/${ref.repo}/resolve/main/" +
                        (ref.subfolder?.let { "$it/" } ?: "") + entry.path.substringAfterLast('/')
                resumable(resolve, null, dest, expectedTotal = entry.byteSizeCoalesced()) { done, total ->
                    onProgress(doneBytes + done, knownTotal)
                }
                doneBytes += entry.byteSizeCoalesced().takeIf { it > 0 } ?: dest.length()
            }
            if (doneBytes <= 0L) throw IOException("Downloaded model folder is empty")
            onProgress(knownTotal, knownTotal)
        }
    }

    // Streams `url` into `dest`, resuming from any existing `<name>.part`.
    //  - partial response (206): appends at the recorded offset; if the
    //    server's reported start mismatches the part, restart that file.
    //  - full response (200): Range was ignored -> restart that file.
    //  - completes only after the byte count verifies against the
    //    authoritative total (Content-Range total / x-linked-size / listing).
    private fun resumable(
        url: String,
        hfToken: String?,
        dest: File,
        expectedTotal: Long,
        onProgress: (done: Long, total: Long) -> Unit
    ) {
        dest.parentFile?.mkdirs()
        val part = File(dest.parent, "${dest.name}.part")
        var partSize = if (part.exists()) part.length() else 0L
        val conn = open(url, hfToken).also {
            if (partSize > 0) it.setRequestProperty("Range", "bytes=$partSize-")
        }
        conn.connect()
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != 206) {
            throw IOException("Model download HTTP $code: ${conn.responseMessage}")
        }
        val servedRange = parseContentRange(conn.getHeaderField("Content-Range"))
        val servedTotal = conn.getHeaderField("x-linked-size")
            ?.toLongOrNull()
            ?: servedRange?.second
            ?: conn.contentLengthLong.takeIf { it > 0 }
        // Authoritative total: prefer what the server just told us, else the
        // repo listing (may overstate Xet pointer files, so server wins).
        val total = servedTotal?.takeIf { it > 0 } ?: expectedTotal.takeIf { it > 0 } ?: 0L

        if (total > 0) {
            when {
                dest.exists() && dest.length() == total -> {
                    onProgress(total, total); return
                }
                partSize >= total -> {
                    finalize(part, dest); onProgress(total, total); return
                }
            }
        }

        val partial = code == 206
        val serverStart = servedRange?.first
        if (partial && serverStart != partSize) {
            // The offset we asked for was not honored: a partial body from an
            // unknown point cannot be appended safely. If it starts at 0 just
            // rewrite clean; otherwise re-fetch without a Range header.
            part.delete()
            if (serverStart != 0L) {
                conn.disconnect()
                return resumable(url, hfToken, dest, expectedTotal, onProgress)
            }
            partSize = 0
        } else if (code == HttpURLConnection.HTTP_OK && partSize > 0) {
            // Server answered 200 over a Range request -> it ignored the range.
            part.delete(); partSize = 0
        }

        var session = 0L
        val out = if (partSize > 0) FileOutputStream(part, true) else FileOutputStream(part)
        out.use { fos ->
            conn.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    session += n
                    fos.flush()
                    onProgress(partSize + session, total)
                }
            }
        }
        if (total > 0 && part.length() != total) {
            throw IOException(
                "Incomplete download: ${part.length()} of $total bytes — tap Download again to resume."
            )
        }
        finalize(part, dest)
        if (total > 0) onProgress(total, total)
    }

    private fun finalize(part: File, dest: File) {
        if (part.length() == 0L) throw IOException("Downloaded ${dest.name} is empty")
        if (dest.exists() && !dest.delete()) throw IOException("Cannot replace old ${dest.name}")
        if (!part.renameTo(dest)) throw IOException("Cannot finalize ${dest.name}")
    }

    @Serializable
    private data class HfTreeEntry(
        val type: String = "",
        val path: String = "",
        val size: Long = 0,
        val lfs: HfLfs? = null
    ) {
        fun fileName() = path.substringAfterLast('/')
        fun byteSizeCoalesced(): Long = (lfs?.size ?: size).let { if (it <= 0) 0 else it }
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

    private fun open(url: String, hfToken: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ForgeGatekeeperDemo/1.0")
            if (!hfToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer ${hfToken.trim()}")
            }
        }
}