package com.forgerig.gatekeeper.demo

import java.io.File

// Pure model discovery over the app files dir (no Android APIs:
// unit-testable). Prefers a valid ORT GenAI folder, else the first
// non-empty .task file — the same pick the demo and the service share.
object ModelFiles {
    fun pick(filesDir: File): File? {
        val ort = File(filesDir, "ort-models").listFiles()
            ?.filter { it.isDirectory && File(it, "genai_config.json").isFile && (it.list()?.size ?: 0) > 1 }
            ?.sortedBy { it.name }
            ?.firstOrNull()
        if (ort != null) return ort
        return File(filesDir, "models")
            .listFiles { f -> f.isFile && f.extension.equals("task", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.firstOrNull { it.length() > 0 }
    }
}
