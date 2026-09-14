package com.forgerig.gatekeeper.demo

import android.content.Context
import android.util.Log
import com.forgerig.gatekeeper.engine.InferenceClient
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private data class DfmChunk(val name: String, val sha256: String)

class DfmLoader(private val context: Context) {

    var lastError: String? = null
        private set

    private val dfmDir: File = File(context.filesDir, "dfms")
    private val tag = "DfmLoader"
    private val nativeLoadOrder = listOf(
        "libonnxruntime.so",
        "libonnxruntime-genai.so",
        "libllm_engine.so"
    )

    suspend fun ensureDfm(
        backend: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val chunks = try {
                    fetchChunkManifest().chunkList(backend)
                } catch (e: Exception) {
                    Log.w(tag, "Chunk manifest lookup failed; falling back to monolith asset", e)
                    null
                }
                if (chunks == null) {
                    legacyMonolith(backend, onProgress)
                } else {
                    ensureChunks(backend, chunks, onProgress)
                }
            } catch (e: Exception) {
                lastError = rootCause(e)
                Log.e(tag, "Failed to ensure DFM $backend", e)
                false
            }
        }
    }

    private suspend fun ensureChunks(
        backend: String,
        chunks: List<DfmChunk>,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val dir = File(dfmDir, backend)
        val local = readChunkState(dir)
        if (dirHasJars(dir) && local != null && local == chunks.associate { it.name to it.sha256 }) {
            Log.i(tag, "DFM $backend chunks already match")
            return true
        }
        makeWritable(dir)
        dir.deleteRecursively()
        dir.mkdirs()
        val ok = try {
            chunks.forEach { chunk ->
                downloadChunk(dir, chunk, onProgress)
            }
            if (!dirHasJars(dir)) {
                throw IOException("DFM $backend chunks did not contain any class jar")
            }
            writeChunkState(dir, chunks.associate { it.name to it.sha256 })
            makeReadOnly(dir)
            true
        } catch (e: Exception) {
            runCatching { dir.deleteRecursively() }
            lastError = rootCause(e)
            Log.e(tag, "DFM $backend chunks failed", e)
            false
        }
        return ok
    }

    private fun dirHasJars(dir: File): Boolean =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.isNotEmpty() ?: false

    private fun jarPriority(jarName: String): Int = when {
        jarName.contains("ort.jar") || jarName.contains("litert.jar") -> 3
        jarName.contains("tasks-genai") -> 2
        jarName.contains("guava") || jarName.contains("protobuf") -> 1
        else -> 0
    }

    private suspend fun downloadChunk(
        dir: File,
        chunk: DfmChunk,
        onProgress: (Long, Long) -> Unit
    ) {
        val zipFile = File(context.cacheDir, chunk.name)
        val partFile = File(context.cacheDir, "${chunk.name}.part")
        val url = "https://github.com/1337farm/forge-gatekeeper/releases/latest/download/${chunk.name}"
        var attempt = 0
        while (true) {
            try {
                Log.i(tag, "Downloading chunk ${chunk.name} (attempt ${attempt + 1})")
                ModelDownloader.download(url, null, zipFile, onProgress)
                if (sha256(zipFile) != chunk.sha256.lowercase()) {
                    throw IOException("Chunk ${chunk.name} failed integrity check")
                }
                extractZip(zipFile, dir)
                runCatching { zipFile.delete() }
                return
            } catch (e: Exception) {
                attempt++
                if (attempt >= 3) throw e
                runCatching { zipFile.delete() }
                runCatching { partFile.delete() }
            }
        }
    }

    private fun rootCause(e: Throwable): String {
        var t: Throwable? = e
        var depth = 0
        while (t?.cause != null && t.cause !== t && depth < 8) {
            t = t.cause
            depth++
        }
        val message = t?.message?.takeIf { it.isNotBlank() } ?: t?.javaClass?.simpleName.orEmpty()
        return "${t?.javaClass?.simpleName}: $message".take(300)
    }

    private suspend fun legacyMonolith(
        backend: String,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val dir = File(dfmDir, backend)
        if (dirHasJars(dir)) return true
        makeWritable(dir)
        dir.mkdirs()
        val asset = "gatekeeper-$backend-dfm.zip"
        val url = "https://github.com/1337farm/forge-gatekeeper/releases/latest/download/$asset"
        Log.i(tag, "Downloading monolith DFM from $url")
        val zipFile = File(context.cacheDir, asset)
        ModelDownloader.download(url, null, zipFile, onProgress)
        extractZip(zipFile, dir)
        runCatching { zipFile.delete() }
        if (!dirHasJars(dir)) {
            throw IOException("DFM $asset did not contain a class jar")
        }
        makeReadOnly(dir)
        return true
    }

    fun getInferenceClient(backend: String, model: File): InferenceClient {
        val dfmBackendDir = File(dfmDir, backend)
        val jars = dfmBackendDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.sortedBy { jarPriority(it.name) } ?: emptyList()
        if (jars.isEmpty()) {
            throw IllegalStateException("DFM not present for $backend")
        }
        makeReadOnly(dfmBackendDir)
        val optimizedDir = File(context.filesDir, "dex/$backend")
        optimizedDir.mkdirs()
        val jniDir = File(dfmBackendDir, "jni/arm64-v8a")
        val librarySearchPath = if (jniDir.exists()) jniDir.absolutePath else null
        var parent: ClassLoader = context.classLoader
        jars.forEach { jar ->
            parent = DexClassLoader(
                jar.absolutePath,
                optimizedDir.absolutePath,
                librarySearchPath,
                parent
            )
        }
        val className = when (backend) {
            "ort" -> "com.forgerig.gatekeeper.ort.OrtGenAiClient"
            "litert" -> "com.forgerig.gatekeeper.litert.MediaPipeLlmClient"
            else -> throw IllegalArgumentException("Unsupported DFM backend: $backend")
        }
        val clientClass = parent.loadClass(className)
        val constructor = clientClass.getConstructor(Context::class.java, File::class.java)
        return constructor.newInstance(context, model) as InferenceClient
    }

    fun loadNativeLibs(backend: String) {
        val jniDir = File(File(dfmDir, backend), "jni/arm64-v8a")
        if (!jniDir.exists()) return
        val libs = jniDir.listFiles { file -> file.isFile && file.name.endsWith(".so") }?.toList()
            ?: return
        libs.sortedWith(
            compareBy(
                { file ->
                    nativeLoadOrder.indexOf(file.name).takeIf { it >= 0 } ?: Int.MAX_VALUE
                },
                { file -> file.name }
            )
        ).forEach { lib ->
            try {
                System.load(lib.absolutePath)
                Log.i(tag, "Loaded native lib: ${lib.name}")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(tag, "Failed to load ${lib.name}: ${e.message}")
            }
        }
    }

    private fun fetchChunkManifest(): JSONObject {
        val url = URL("https://github.com/1337farm/forge-gatekeeper/releases/latest/download/dfm-chunks.json")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ForgeGatekeeperDemo/1.0")
        }
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Chunk manifest lookup HTTP ${connection.responseCode}")
        }
        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    }

    private fun JSONObject.chunkList(backend: String): List<DfmChunk>? {
        val arr = optJSONArray(backend) ?: return null
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DfmChunk(o.getString("name"), o.getString("sha256"))
        }
    }

    private fun chunkStateFile(dir: File): File = File(dir, "chunks.json")

    private fun readChunkState(dir: File): Map<String, String>? {
        return try {
            val file = chunkStateFile(dir)
            if (!file.isFile) return null
            val o = JSONObject(file.readText())
            val chunks = o.optJSONObject("chunks") ?: return null
            chunks.keys().asSequence().associateWith { chunks.getString(it) }
        } catch (e: Exception) {
            Log.w(tag, "Ignoring unreadable chunk state", e)
            null
        }
    }

    private fun writeChunkState(dir: File, state: Map<String, String>) {
        val o = JSONObject()
        val chunks = JSONObject()
        state.forEach { (name, sha) -> chunks.put(name, sha) }
        o.put("chunks", chunks)
        chunkStateFile(dir).writeText(o.toString())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun makeReadOnly(dfmBackendDir: File) {
        runCatching {
            dfmBackendDir.walkTopDown().forEach { file ->
                if (file.isDirectory) {
                    file.setReadable(true, false)
                    file.setExecutable(true, false)
                    file.setWritable(false, false)
                } else {
                    file.setReadable(true, false)
                    file.setExecutable(false, false)
                    file.setWritable(false, false)
                }
            }
        }
    }

    private fun makeWritable(dfmBackendDir: File) {
        runCatching {
            dfmBackendDir.walkTopDown().forEach { file ->
                file.setWritable(true, true)
            }
        }
    }

    private fun extractZip(zip: File, dest: File) {
        dest.mkdirs()
        ZipInputStream(FileInputStream(zip)).use { zipInput ->
            var entry: ZipEntry? = zipInput.getNextEntry()
            while (entry != null) {
                val entryFile = File(dest, entry.name)
                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { out ->
                        zipInput.copyTo(out, 8192)
                    }
                }
                zipInput.closeEntry()
                entry = zipInput.getNextEntry()
            }
        }
    }
}
