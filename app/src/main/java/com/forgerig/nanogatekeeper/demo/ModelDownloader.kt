package com.forgerig.nanogatekeeper.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Demo-only model fetcher: downloads a `.task` LLM into app storage with
// progress. Network is used ONLY here; inference itself is fully offline.
object ModelDownloader {

    const val DEFAULT_MODEL_URL =
        "https://huggingface.co/google/gemma-3n-E2B-it-int4/resolve/main/gemma-3n-E2B-it-int4.task"
    const val DEFAULT_MODEL_FILE = "gemma-3n-E2B-it-int4.task"

    suspend fun download(
        url: String,
        hfToken: String?,
        dest: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (!hfToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer ${hfToken.trim()}")
                }
            }
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Model download HTTP ${conn.responseCode}: ${conn.responseMessage}")
            }
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parent, "${dest.name}.part")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (tmp.length() == 0L) throw IOException("Downloaded model is empty")
            if (dest.exists() && !dest.delete()) throw IOException("Cannot replace old model file")
            if (!tmp.renameTo(dest)) throw IOException("Cannot finalize model file")
        }
    }
}
