package com.forgerig.nanogatekeeper.litert

import java.io.File

// Pure file helpers for `.task` model management (no Android APIs: unit-testable).
object ModelStore {
    const val MODELS_DIR_NAME = "models"
    const val MODEL_EXTENSION = "task"

    fun modelsDir(filesDir: File): File = File(filesDir, MODELS_DIR_NAME)

    fun listModels(modelsDir: File): List<File> =
        modelsDir.listFiles { f ->
            f.isFile && f.extension.equals(MODEL_EXTENSION, ignoreCase = true)
        }?.sortedBy { it.name } ?: emptyList()

    fun isUsable(file: File): Boolean = file.isFile && file.length() > 0L

    fun defaultModelFile(filesDir: File, fileName: String): File =
        File(modelsDir(filesDir), fileName)
}
