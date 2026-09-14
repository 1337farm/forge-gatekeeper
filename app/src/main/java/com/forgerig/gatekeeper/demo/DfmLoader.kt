package com.forgerig.gatekeeper.demo

import android.content.Context
import android.util.Log
import com.forgerig.gatekeeper.engine.InferenceClient
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

@Serializable
private data class DfmReleaseAsset(
    val name: String = "",
    val browser_download_url: String = ""
)

@Serializable
private data class DfmLatestRelease(
    val tag_name: String = "",
    val assets: List<DfmReleaseAsset> = emptyList()
)

@Serializable
private data class DfmMetadata(
    val tag: String = "",
    val asset: String = ""
)

class DfmLoader(private val context: Context) {

    private val dfmDir: File = File(context.filesDir, "dfms")
    private val tag = "DfmLoader"
    private val json = Json { ignoreUnknownKeys = true }
    private val nativeLoadOrder = listOf(
        "libonnxruntime.so",
        "libonnxruntime-genai.so",
        "libllm_engine.so"
    )

    suspend fun ensureDfm(backend: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val asset = assetName(backend)
                val dfmBackendDir = File(dfmDir, backend)
                val classesJar = File(dfmBackendDir, "classes.jar")
                val latest = try {
                    fetchLatestRelease()
                } catch (e: Exception) {
                    Log.w(tag, "Latest release lookup failed; using cached DFM when present", e)
                    null
                }
                val metadata = readMetadata(dfmBackendDir)
                if (classesJar.exists()) {
                    if (latest == null) return@withContext true
                    if (metadata?.tag == latest.tag_name && metadata?.asset == asset) {
                        Log.i(tag, "DFM $backend already matches ${latest.tag_name}")
                        return@withContext true
                    }
                } else if (latest == null) {
                    return@withContext false
                }
                val release = latest ?: return@withContext false
                val downloadUrl = release.assets.firstOrNull { it.name == asset }?.browser_download_url
                    ?: throw IOException("DFM asset $asset not found in release ${release.tag_name}")
                if (dfmBackendDir.exists()) dfmBackendDir.deleteRecursively()
                dfmBackendDir.mkdirs()
                val zipFile = File(context.cacheDir, asset)
                Log.i(tag, "Downloading DFM $asset from release ${release.tag_name}")
                ModelDownloader.download(downloadUrl, null, zipFile) { _, _ -> }
                extractZip(zipFile, dfmBackendDir)
                runCatching { zipFile.delete() }
                if (!classesJar.exists()) {
                    throw IOException("DFM $asset did not contain classes.jar")
                }
                writeMetadata(dfmBackendDir, DfmMetadata(release.tag_name, asset))
                true
            } catch (e: Exception) {
                Log.e(tag, "Failed to ensure DFM $backend", e)
                false
            }
        }
    }

    fun getInferenceClient(backend: String, model: File): InferenceClient {
        val dfmBackendDir = File(dfmDir, backend)
        val classesJar = File(dfmBackendDir, "classes.jar")
        if (!classesJar.exists()) {
            throw IllegalStateException("DFM not present for $backend")
        }
        val optimizedDir = File(context.filesDir, "dex/$backend")
        optimizedDir.mkdirs()
        val jniDir = File(dfmBackendDir, "jni/arm64-v8a")
        val librarySearchPath = if (jniDir.exists()) jniDir.absolutePath else null
        val classLoader = DexClassLoader(
            classesJar.absolutePath,
            optimizedDir.absolutePath,
            librarySearchPath,
            context.classLoader
        )
        val className = when (backend) {
            "ort" -> "com.forgerig.gatekeeper.ort.OrtGenAiClient"
            else -> throw IllegalArgumentException("Unsupported DFM backend: $backend")
        }
        val clientClass = classLoader.loadClass(className)
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

    private fun assetName(backend: String): String {
        return when (backend) {
            "ort" -> "gatekeeper-ort-dfm.zip"
            else -> throw IllegalArgumentException("Unsupported DFM backend: $backend")
        }
    }

    private fun fetchLatestRelease(): DfmLatestRelease {
        val url = URL("https://api.github.com/repos/1337farm/forge-gatekeeper/releases/latest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ForgeGatekeeperDemo/1.0")
        }
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Latest release lookup HTTP ${connection.responseCode}")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        return json.decodeFromString(DfmLatestRelease.serializer(), body)
    }

    private fun metadataFile(dfmBackendDir: File): File {
        return File(dfmBackendDir, "dfm.json")
    }

    private fun readMetadata(dfmBackendDir: File): DfmMetadata? {
        return try {
            val file = metadataFile(dfmBackendDir)
            if (!file.isFile) return null
            json.decodeFromString(DfmMetadata.serializer(), file.readText())
        } catch (e: Exception) {
            Log.w(tag, "Ignoring unreadable DFM metadata", e)
            null
        }
    }

    private fun writeMetadata(dfmBackendDir: File, metadata: DfmMetadata) {
        metadataFile(dfmBackendDir).writeText(json.encodeToString(DfmMetadata.serializer(), metadata))
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
