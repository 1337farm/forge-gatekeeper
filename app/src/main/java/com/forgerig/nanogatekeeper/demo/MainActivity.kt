package com.forgerig.nanogatekeeper.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forgerig.nanogatekeeper.ort.LlmBridge
import com.forgerig.nanogatekeeper.ort.OrtModelDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Minimal bare-metal ORT GenAI demo: streams tokens straight from
// libllm_engine.so (OgaGenerator_GenerateNextToken) into Compose state.
// Model folder layout: genai_config.json + weights + tokenizer
// (e.g. cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4 from a *-onnx repo).
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OrtChatScreen(
                    defaultModelDir = firstValidModelDir()?.absolutePath ?: "",
                    onInit = { dir -> initHandle(dir) },
                    onGenerate = { handle, prompt, maxTokens, onToken ->
                        generateStreaming(handle, prompt, maxTokens, onToken)
                    },
                    onRelease = { handle -> releaseHandle(handle) }
                )
            }
        }
    }

    private fun firstValidModelDir(): File? {
        val root = File(filesDir, "ort-models")
        return root.listFiles()
            ?.filter { it.isDirectory && OrtModelDir.missingEntries(it).isEmpty() }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }

    private suspend fun initHandle(modelDir: String): Long =
        withContext(Dispatchers.IO) {
            val dir = File(modelDir)
            OrtModelDir.requireValid(dir)
            val handle = LlmBridge.nativeInit(dir.absolutePath, true)
            if (handle == 0L) throw IllegalStateException("nativeInit failed")
            handle
        }

    private suspend fun generateStreaming(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        LlmBridge.nativeGenerate(handle, prompt, maxTokens) { token -> onToken(token) }
    }

    private fun releaseHandle(handle: Long) {
        runCatching { LlmBridge.nativeRelease(handle) }
    }
}

@Composable
private fun OrtChatScreen(
    defaultModelDir: String,
    onInit: suspend (String) -> Long,
    onGenerate: suspend (Long, String, Int, (String) -> Unit) -> Int,
    onRelease: (Long) -> Unit
) {
    var modelDir by remember { mutableStateOf(defaultModelDir) }
    var prompt by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Enter a model folder, then Send.") }
    var busy by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    DisposableEffect(Unit) {
        onDispose { if (handle != 0L) onRelease(handle) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("ORT GenAI native demo", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = modelDir,
            onValueChange = { modelDir = it },
            label = { Text("Model folder (genai_config.json)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && prompt.isNotBlank() && modelDir.isNotBlank(),
                onClick = {
                    busy = true
                    output = ""
                    status = "Loading model…"
                    scope.launch {
                        try {
                            val h = if (handle != 0L) handle else onInit(modelDir.trim())
                            handle = h
                            val provider = withContext(Dispatchers.IO) {
                                LlmBridge.nativeGetProvider(h)
                            }
                            status = "Generating ($provider)…"
                            val t0 = System.currentTimeMillis()
                            val count = onGenerate(h, prompt.trim(), 512) { token ->
                                output += token
                            }
                            val secs = (System.currentTimeMillis() - t0) / 1000.0
                            status = "Done: $count tokens via $provider " +
                                "(${String.format("%.1f", count / secs.coerceAtLeast(0.01))} tok/s)"
                        } catch (t: Throwable) {
                            status = "Error: ${t.message}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) {
                Text("Send")
            }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
        Text(output, style = MaterialTheme.typography.bodyMedium)
    }
}
