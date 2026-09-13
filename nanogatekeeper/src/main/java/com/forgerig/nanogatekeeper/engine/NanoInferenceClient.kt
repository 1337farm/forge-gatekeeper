package com.forgerig.nanogatekeeper.engine

import android.content.Context

interface NanoInferenceClient {
    suspend fun generate(systemPrompt: String, userContent: String): String
}

class AICoreInferenceClient(private val appContext: Context) : NanoInferenceClient {
    override suspend fun generate(systemPrompt: String, userContent: String): String {
        return try {
            val clazz = Class.forName("com.google.ai.edge.aicore.GenerativeModel")
            val ctor = clazz.getConstructor(Context::class.java)
            val model = ctor.newInstance(appContext.applicationContext)
            val method = clazz.methods.firstOrNull { it.name == "generateContent" }
                ?: throw IllegalStateException("AICore SDK shape changed")
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                try {
                    val combined = "$systemPrompt\n\n<<<USER>>>\n$userContent\n<<<END>>>"
                    val result = method.invoke(model, combined) as? String ?: ""
                    cont.resume(result) {}
                } catch (t: Throwable) {
                    cont.resumeWith(Result.failure(t))
                }
            }
        } catch (t: Throwable) {
            throw RuntimeException("AICore inference unavailable: ${t.message}", t)
        }
    }
}
