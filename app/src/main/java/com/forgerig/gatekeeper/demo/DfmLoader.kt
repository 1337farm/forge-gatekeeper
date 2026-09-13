package com.forgerig.gatekeeper.demo

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import com.forgerig.gatekeeper.engine.InferenceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Loads Dynamic Feature Modules (DFMs) from GitHub releases.
 * DFM ZIP contains classes.jar and optional jni/arm64-v8a/*.so.
 * Extracts to files/dfms/<name>/ and loads via DexClassLoader.
 */
class DfmLoader(private val context: Context) {

    private val dfmDir: File = File(context.filesDir, "dfms")
    private val tag = "DfmLoader"

    /**
     * Download and extract the DFM for the given backend name (e.g., "ort" or "litert").
     * Returns true if successful, false otherwise.
     */
    suspend fun ensureDfm(backend: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val dfmBackendDir = File(dfmDir, backend)
                // Already extracted? Check for classes.jar
                if (File(dfmBackendDir, "classes.jar").exists()) {
                    Log.i(tag, "DFM $backend already present")
                    return@withContext true
                }
                // Download DFM ZIP from latest GitHub release
                val zipFile = File(context.cacheDir, "$backend-dfm.zip")
                val url = "https://github.com/1337farm/forge-gatekeeper/download/latest/$backend-dfm.zip"
                Log.i(tag, "Downloading DFM from $url")
                ModelDownloader.download(url, null, zipFile) { done, total ->
                    // progress omitted for simplicity
                }
                // Extract ZIP
                extractZip(zipFile, dfmBackendDir)
                // Verify classes.jar exists
                File(dfmBackendDir, "classes.jar").exists()
            } catch (e: Exception) {
                Log.e(tag, "Failed to ensure DFM $backend", e)
                false
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

    /**
     * Returns an InferenceClient instance for the given backend and model location.
     * The caller must ensure the DFM is already present (ensureDfm called).
     * Uses DexClassLoader to load the backend class from the DFM's classes.jar.
     */
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
            "litert" -> "com.forgerig.gatekeeper.litert.MediaPipeLlmClient"
            else -> throw IllegalArgumentException("Unknown backend: $backend")
        }
        val clientClass = classLoader.loadClass(className)
        val constructor = clientClass.getConstructor(Context::class.java, File::class.java)
        return constructor.newInstance(context, model) as InferenceClient
    }

    /**
     * Loads native libraries from the DFM's jni/arm64-v8a directory.
     * Must be called before using any backend that depends on native code.
     */
    fun loadNativeLibs(backend: String) {
        val dfmBackendDir = File(dfmDir, backend)
        val jniDir = File(dfmBackendDir, "jni/arm64-v8a")
        if (!jniDir.exists()) return
        jniDir.listFiles { it -> it.name.endsWith(".so") }?.forEach { lib ->
            try {
                System.load(lib.absolutePath)
                Log.i(tag, "Loaded native lib: ${lib.name}")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(tag, "Failed to load ${lib.name}: ${e.message}")
            }
        }
    }
}