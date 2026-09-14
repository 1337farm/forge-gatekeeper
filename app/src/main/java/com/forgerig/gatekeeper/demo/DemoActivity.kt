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
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.forgerig.gatekeeper.engine.GatekeeperEngine
import com.forgerig.gatekeeper.engine.InferenceClient
import com.forgerig.gatekeeper.litert.MediaPipeLlmClient
import com.forgerig.gatekeeper.model.GatekeeperConfig
import com.forgerig.gatekeeper.model.GatekeeperResult
import kotlinx.coroutines.launch
import java.io.File

class DemoActivity : ComponentActivity() {

    private var localEngine: GatekeeperEngine? = null
    private var localClient: AutoCloseable? = null
    private var ortDfmReady = false

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
        val downloadDFMButton = findViewById<Button>(R.id.downloadDFMButton)
        val dfmStatus = findViewById<TextView>(R.id.dfmStatus)
        val bypassSwitch = findViewById<Switch>(R.id.bypassGatekeeper)
        val modelUrl = findViewById<EditText>(R.id.modelUrl)
        val hfToken = findViewById<EditText>(R.id.hfToken)
        val downloadButton = findViewById<Button>(R.id.downloadButton)
        val downloadProgress = findViewById<ProgressBar>(R.id.downloadProgress)
        val modelStatus = findViewById<TextView>(R.id.modelStatus)

        downloadDFMButton.setOnClickListener {
            lifecycleScope.launch {
                downloadDFMButton.isEnabled = false
                dfmStatus.text = "Downloading ORT backend DFM…"
                val ok = DfmLoader(this@DemoActivity).ensureDfm("ort")
                ortDfmReady = ok
                dfmStatus.text = if (ok) "ORT backend ready." else "ORT download failed."
                downloadDFMButton.isEnabled = true
            }
        }

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
                        val dest = File(filesDir, "models").let { File(it, fileName) }
                        modelStatus.text = "Downloading $fileName…"
                        ModelDownloader.download(spec, token, dest) { done, total ->
                            runOnUiThread { report(done, total, modelStatus, downloadProgress) }
                        }
                    } else {
                        val ref = ModelDownloader.parseRepoRef(spec)
                        val dir = File(filesDir, "ort-models")
                            .let { File(it, ref.repo.substringAfterLast('/').take(40)) }
                        modelStatus.text = "Downloading ${ref.repo}${ref.subfolder?.let { "/$it" } ?: ""}…"
                        ModelDownloader.downloadOrtFolder(ref, dir) { done, total ->
                            runOnUiThread { report(done, total, modelStatus, downloadProgress) }
                        }
                    }
                    closeLocalEngine()
                    refreshModelStatus(modelStatus)
                    Toast.makeText(this@DemoActivity, "Model ready.", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    modelStatus.text = "Download failed: ${t.message} — tap Download again to resume."
                } finally {
                    downloadButton.isEnabled = true
                    downloadProgress.visibility = View.GONE
                }
            }
        }

        val caps = capabilityLine()
        statusView.text = "$caps\nIdle."

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
                    val model = pickLocalModel()
                    if (model == null) {
                        statusView.text = "No local model — tap Download (no token needed) or adb push a folder."
                        return@launch
                    }
                    val mode = if (model.isDirectory) "[ORT native] " else "[MediaPipe] "
                    val bypass = bypassSwitch.isChecked
                    val dfmLoader = DfmLoader(this@DemoActivity)
                    closeLocalEngine()
                    val client = getClient(model, dfmLoader)

                    if (bypass) {
                        try {
                            val rawResult = client.generate("", raw)
                            outputView.text = rawResult
                            statusView.text = "$mode RAW (gatekeeper bypassed)"
                            telemetryView.text = "Gatekeeper pipeline bypassed — no sanitization, redaction, compression, or audit."
                        } finally {
                            (client as? AutoCloseable)?.let { runCatching { it.close() } }
                        }
                    } else {
                        val engine = GatekeeperEngine(applicationContext, client)
                        localEngine = engine
                        localClient = client as? AutoCloseable
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

    private suspend fun getClient(model: File, dfmLoader: DfmLoader): InferenceClient {
        if (model.isDirectory) {
            if (!ortDfmReady) {
                ortDfmReady = dfmLoader.ensureDfm("ort")
                dfmLoader.loadNativeLibs("ort")
            }
            return dfmLoader.getInferenceClient("ort", model)
        } else {
            return MediaPipeLlmClient(applicationContext, model)
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

    private fun report(done: Long, total: Long, modelStatus: TextView, downloadProgress: ProgressBar) {
        if (total > 0) {
            downloadProgress.progress = ((done * 100) / total).toInt()
            modelStatus.text = "Downloading: ${done / 1_048_576} / ${total / 1_048_576} MB"
        } else {
            modelStatus.text = "Downloading: ${done / 1_048_576} MB"
        }
    }

    private fun pickLocalModel(): File? {
        val ortRoot = File(filesDir, "ort-models")
        val ort = ortRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "genai_config.json").isFile && (it.list()?.size ?: 0) > 1 }
            ?.sortedBy { it.name }
            ?.firstOrNull()
        if (ort != null) return ort
        val modelsDir = File(filesDir, "models")
        val models = modelsDir.listFiles { f -> f.isFile && f.extension.equals("task", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()
        return models.firstOrNull { it.length() > 0 }
    }

    private fun refreshModelStatus(modelStatus: TextView) {
        val picked = pickLocalModel()
        modelStatus.text = if (picked == null) {
            "Local model: none. Tap Download (no token needed) or adb push a model."
        } else if (picked.isDirectory) {
            "Local model: ${picked.name}/ (ORT GenAI native)"
        } else {
            "Local model: ${picked.name} (${picked.length() / 1_048_576} MB, MediaPipe)"
        }
    }

    private fun closeLocalEngine() {
        localClient?.let { runCatching { it.close() } }
        localClient = null
        localEngine = null
    }
}