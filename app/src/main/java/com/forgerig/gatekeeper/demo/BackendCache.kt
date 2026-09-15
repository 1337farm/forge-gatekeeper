package com.forgerig.gatekeeper.demo

import android.content.Context
import com.forgerig.gatekeeper.engine.InferenceClient
import com.forgerig.gatekeeper.litert.MediaPipeLlmClient
import com.forgerig.gatekeeper.ort.OrtGenAiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

// App-scoped warmed backend cache: one native client per model path, shared
// by the Activity prewarm and the foreground InferenceService. Survives
// Activity recreation (minimize/rotate); dies with the process. Both clients
// hold only the application context, so outliving any Activity is safe.
object BackendCache {
    private val lock = Mutex()
    private var client: InferenceClient? = null
    private var modelPath: String? = null
    var lastAcquireReused: Boolean = false
        private set

    suspend fun acquire(appContext: Context, model: File): InferenceClient =
        lock.withLock {
            val path = model.absolutePath
            client?.takeIf { modelPath == path }?.let {
                lastAcquireReused = true
                return@withLock it
            }
            (client as? AutoCloseable)?.let { runCatching { it.close() } }
            client = null
            modelPath = null
            lastAcquireReused = false
            val fresh: InferenceClient = if (model.isDirectory) {
                OrtGenAiClient(appContext, model)
            } else {
                MediaPipeLlmClient(appContext, model)
            }
            client = fresh
            modelPath = path
            fresh
        }

    // Warms outside the lock: native init can take seconds, creation is fast.
    suspend fun warmup(client: InferenceClient): Long = when (client) {
        is OrtGenAiClient -> client.warmup()
        is MediaPipeLlmClient -> client.warmup()
        else -> -1L
    }

    fun providerOf(client: InferenceClient): String? =
        (client as? OrtGenAiClient)?.activeProvider

    fun warmMsOf(client: InferenceClient): Long = when (client) {
        is OrtGenAiClient -> client.lastWarmupMs
        is MediaPipeLlmClient -> client.lastWarmupMs
        else -> -1L
    }

    suspend fun drop() = lock.withLock {
        (client as? AutoCloseable)?.let { runCatching { it.close() } }
        client = null
        modelPath = null
    }
}
