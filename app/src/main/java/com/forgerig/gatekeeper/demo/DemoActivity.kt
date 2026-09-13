package com.forgerig.gatekeeper.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.forgerig.gatekeeper.engine.GatekeeperEngine
import com.forgerig.gatekeeper.engine.InferenceClient
import com.forgerig.gatekeeper.litert.MediaPipeLlmClient
import com.forgerig.gatekeeper.litert.ModelStore
import com.forgerig.gatekeeper.ort.OrtGenAiClient
import com.forgerig.gatekeeper.ort.OrtModelDir
import com.forgerig.gatekeeper.model.GatekeeperConfig
import com.forgerig.gatekeeper.model.GatekeeperResult
import kotlinx.coroutines.launch
import java.io.File

class DemoActivity : AppCompatActivity() {

    private var localEngine: GatekeeperEngine? = null
    private var localClient: AutoCloseable? = null
    private var localModelPath: String? = null

    // One-line device capability snapshot so fallbacks are self-diagnosing
    // (API level + total RAM; the gatekeeper only needs memory headroom).
    private fun capabilityLine(): String {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = mi.totalMem / 1_000_000_000.0
        return "Device: API ${android.os.Build.VERSION.SDK_INT} · " +
            "RAM ${String.format("%.1f", gb)}GB"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.input)
        val runButton = findViewById<Button>(R.id.runButton)
        val copyButton = findViewById<Button>(R.id.copyButton)
        val statusView = findViewById<TextView>(R.id.statusView)
        val outputView = findViewById<TextView>(R.id.outputView)
        val telemetryView = findViewById<TextView>(R.id.telemetryView)
        val ortDemoButton = findViewById<Button>(R.id.ortDemoButton)
        ortDemoButton.setOnClickListener {
            startActivity(android.content.Intent(this, MainActivity::class.java))
        }
        val bypassSwitch = findViewById<Switch>(R.id.bypassGatekeeper)
        ortDemoButton.setOnClickListener {
            startActivity(android.content.Intent(this, MainActivity::class.java))
        }
        val modelUrl = findViewById<EditText>(R.id.modelUrl)
        val hfToken = findViewById<EditText>(R.id.hfToken)
        val downloadButton = findViewById<Button>(R.id.downloadButton)
        val downloadProgress = findViewById<ProgressBar>(R.id.downloadProgress)
        val modelStatus = findViewById<TextView>(R.id.modelStatus)

        modelUrl.setText(ModelDownloader.DEFAULT_ORT_REF)
        refreshModelStatus(modelStatus)

        downloadButton.setOnClickListener {
            val spec = modelUrl.text.toString().trim()
            if (spec.isBlank()) {
                modelStatus.text = "Enter a .task URL or an owner/repo[:subfolder] model first."
                return@setOnClickListener
            }
            downloadButton.isEnabled = false
            downloadProgress.visibility = View.VISIBLE
            downloadProgress.progress = 0
            modelStatus.text = "Resolving $spec…"
            lifecycleScope.launch {
                try {
                    val token = hfToken.text.toString() // optional; blank = public repos only
                    if (spec.contains("://")) {
                        val fileName = spec.substringAfterLast('/').substringBefore('?')
                            .takeIf { it.endsWith(".task", ignoreCase = true) }
                            ?: ModelDownloader.DEFAULT_MODEL_FILE
                        val dest = ModelStore.defaultModelFile(filesDir, fileName)
                        modelStatus.text = "Downloading $fileName…"
                        ModelDownloader.download(spec, token, dest) { done, total ->
                            runOnUiThread { report(done, total, modelStatus, downloadProgress) }
                        }
                    } else {
                        val ref = ModelDownloader.parseRepoRef(spec)
                        val dir = File(
                            File(filesDir, "ort-models"),
                            ref.repo.substringAfterLast('/').take(40)
                        )
                        modelStatus.text = "Downloading ${ref.repo}${ref.subfolder?.let { "/$it" } ?: ""}…"
                        ModelDownloader.downloadOrtFolder(ref, dir) { done, total ->
                            runOnUiThread { report(done, total, modelStatus, downloadProgress) }
                        }
                    }
                    // Fresh files: drop any cached client bound to the old ones.
                    closeLocalEngine()
                    refreshModelStatus(modelStatus)
                    Toast.makeText(this@DemoActivity, "Model ready.", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    modelStatus.text = "Download failed: ${t.message} " +
                            "— tap Download again to resume from where it stopped."
                } finally {
                    downloadButton.isEnabled = true
                    downloadProgress.visibility = View.GONE
                }
            }
        }

        val caps = capabilityLine()
        statusView.text = "$caps\nIdle."

        // Copies the full on-screen report (caps + status + output + telemetry)
        // so fallback/error diagnoses survive — never the raw input.
        copyButton.setOnClickListener {
            val status = statusView.text.toString()
            val output = outputView.text.toString()
            val telemetry = telemetryView.text.toString()
            val payload = listOf(caps, status, output, telemetry)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "Idle." }
                .joinToString("\n\n")
            if (payload.isBlank()) {
                Toast.makeText(this, getString(R.string.nothing_to_copy), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("gatekeeper", payload))
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }

        runButton.setOnClickListener {
            val raw = input.text.toString()
            if (raw.isBlank()) {
                statusView.text = "Type a prompt first."
                return@setOnClickListener
            }
            runButton.isEnabled = false
            statusView.text = "Running on-device…"
            outputView.text = ""
            telemetryView.text = ""
            lifecycleScope.launch {
                try {
                    val picked = pickLocalModel()
                    if (picked == null) {
                        statusView.text = "No local model — hit Download above " +
                            "(defaults to a tokenless public ORT model) or adb push a folder."
return@launch
            }
            // Bypass gatekeeper pipeline if requested
            val bypass = bypassSwitch.isChecked
            if (bypass) {
                val client: InferenceClient = if (picked.isDirectory) {
                    OrtGenAiClient(applicationContext, picked)
                } else {
                    MediaPipeLlmClient(applicationContext, picked)
                }
                localClient = client as AutoCloseable
                val rawResult = client.generate("", raw)
                val mode = if (picked.isDirectory) "[ORT native] " else "[MediaPipe] "
                statusView.text = "$mode RAW (gatekeeper bypassed)"
                outputView.text = rawResult
                telemetryView.text = "Gatekeeper pipeline bypassed — no sanitization, redaction, compression, or audit."
            } else {
                val engine = localEngineFor(picked)
                val mode = if (picked.isDirectory) "[ORT native] " else "[MediaPipe] "
                val result = engine.processPrompt(raw, GatekeeperConfig())
                render(result, statusView, outputView, telemetryView, mode)
            }
                } catch (t: Throwable) {
                    statusView.text = "Error: ${t.message}"
                } finally {
                    runButton.isEnabled = true
                }
            }
        }
    }

    private fun render(
        result: GatekeeperResult,
        statusView: TextView,
        outputView: TextView,
        telemetryView: TextView,
        mode: String
    ) {
        when (result) {
            is GatekeeperResult.Success -> {
                val t = result.telemetry
                statusView.text = mode + "SUCCESS (heat=${result.heat})"
                outputView.text = result.safeCompressedPrompt
                telemetryView.text = "tokens ${t.preCompressionTokens} → ${t.postCompressionTokens} " +
                    "(${String.format("%.1f", t.compressionRatioPct)}% saved) · " +
                    "compress iters=${t.compressionIterations} audit iters=${t.auditIterations} · " +
                    "redactions=${t.redactionEvents}"
            }
            is GatekeeperResult.Blocked -> {
                statusView.text = mode + "BLOCKED (heat=${result.heat}): ${result.reason}"
                outputView.text = "(nothing sent anywhere)"
                telemetryView.text = "redactions=${result.telemetry.redactionEvents}"
            }
            is GatekeeperResult.FallbackRequired -> {
                val t = result.telemetry
                statusView.text = mode + "FALLBACK: ${result.reason}"
                outputView.text = result.sanitizedPrompt
                telemetryView.text = "maxRetriesExhausted=${t.maxRetriesExhausted} · " +
                    "redactions=${t.redactionEvents}"
            }
        }
    }

    override fun onDestroy() {
        closeLocalEngine()
        super.onDestroy()
    }

    // Shares one progress renderer between .task and ORT-folder downloads.
    private fun report(
        done: Long,
        total: Long,
        modelStatus: TextView,
        downloadProgress: ProgressBar
    ) {
        if (total > 0) {
            downloadProgress.progress = ((done * 100) / total).toInt()
            modelStatus.text = "Downloading: ${done / 1_048_576} / ${total / 1_048_576} MB"
        } else {
            modelStatus.text = "Downloading: ${done / 1_048_576} MB"
        }
    }

    private fun pickLocalModel(): File? {
        // Bare-metal ORT GenAI folders win over .task files when both exist.
        val ortRoot = File(filesDir, "ort-models")
        val ort = ortRoot.listFiles()
            ?.filter { it.isDirectory && OrtModelDir.missingEntries(it).isEmpty() }
            ?.sortedBy { it.name }
            ?.firstOrNull()
        if (ort != null) return ort
        val models = ModelStore.listModels(ModelStore.modelsDir(filesDir))
        return models.firstOrNull { ModelStore.isUsable(it) }
    }

    private fun refreshModelStatus(modelStatus: TextView) {
        val picked = pickLocalModel()
        modelStatus.text = if (picked == null) {
            "Local model: none. Tap Download (no token needed) or adb push a " +
                "GenAI folder into files/ort-models/."
        } else if (picked.isDirectory) {
            "Local model: ${picked.name}/ (ORT GenAI native)"
        } else {
            "Local model: ${picked.name} (${picked.length() / 1_048_576} MB, MediaPipe)"
        }
    }

    private fun localEngineFor(model: File): GatekeeperEngine {
        val cached = localEngine
        if (cached != null && localModelPath == model.absolutePath) return cached
        closeLocalEngine()
        val client: InferenceClient = if (model.isDirectory) {
            OrtGenAiClient(applicationContext, model)
        } else {
            MediaPipeLlmClient(applicationContext, model)
        }
        val engine = GatekeeperEngine(applicationContext, client)
        localClient = client as AutoCloseable
        localEngine = engine
        localModelPath = model.absolutePath
        return engine
    }

    private fun closeLocalEngine() {
        localClient?.let { runCatching { it.close() } }
        localClient = null
        localEngine = null
        localModelPath = null
    }
}
