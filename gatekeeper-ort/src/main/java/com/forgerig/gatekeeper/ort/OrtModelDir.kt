package com.forgerig.gatekeeper.ort

import java.io.File

// Pure model-folder validation (no Android APIs: unit-testable).
// A GenAI model folder must carry genai_config.json plus the referenced
// weights/tokenizer (e.g. cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4/).
object OrtModelDir {

    const val GENAI_CONFIG = "genai_config.json"

    fun missingEntries(modelDir: File): List<String> {
        if (!modelDir.isDirectory) return listOf("not a directory: ${modelDir.absolutePath}")
        val missing = mutableListOf<String>()
        if (!File(modelDir, GENAI_CONFIG).isFile) missing.add(GENAI_CONFIG)
        if ((modelDir.list() ?: emptyArray()).size <= 1) {
            missing.add("model weights/tokenizer files")
        }
        return missing
    }

    fun requireValid(modelDir: File) {
        val missing = missingEntries(modelDir)
        require(missing.isEmpty()) {
            "Invalid GenAI model folder ${modelDir.absolutePath}, missing: $missing"
        }
    }
}
