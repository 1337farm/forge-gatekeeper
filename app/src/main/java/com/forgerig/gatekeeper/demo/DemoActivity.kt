package com.forgerig.gatekeeper.demo

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import android.app.Activity
import com.forgerig.gatekeeper.engine.GatekeeperEngine
import com.forgerig.gatekeeper.engine.InferenceClient
import com.forgerig.gatekeeper.litert.MediaPipeLlmClient
import com.forgerig.gatekeeper.model.GatekeeperConfig
import com.forgerig.gatekeeper.model.GatekeeperResult
import com.forgerig.gatekeeper.ort.OrtGenAiClient
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class DemoActivity : Activity() {

    private val scope = MainScope()

    private var localEngine: GatekeeperEngine? = null
    private var localClient: AutoCloseable? = null
    private var downloadReceiver: BroadcastReceiver? = null

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

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val input = findViewById<EditText>(R.id.input)
        val runButton = findViewById<Button>(R.id.runButton)
        val copyButton = findViewById<Button>(R.id.copyButton)
        val statusView = findViewById<TextView>(R.id.statusView)
        val outputView = findViewById<TextView>(R.id.outputView)
        val telemetryView = findViewById<TextView>(R.id.telemetryView)
        val bypassSwitch = findViewById<Switch>(R.id.bypassGatekeeper)
        val modelUrl = findViewById<EditText>(R.id.modelUrl)
        val hfToken = findViewById<EditText>(R.id.hfToken)
        val downloadButton = findViewById<Button>(R.id.downloadButton)
        val downloadProgress = findViewById<ProgressBar>(R.id.downloadProgress)
        val modelStatus = findViewById<TextView>(R.id.modelStatus)
        val updateStatus = findViewById<TextView>(R.id.updateStatus)

        modelUrl.setText(ModelDownloader.DEFAULT_ORT_REF)
        refreshModelStatus(modelStatus)
        checkForUpdate(updateStatus)
        maybeAutoDownloadModel(modelStatus, downloadProgress, downloadButton)

        downloadButton.setOnClickListener {
            val spec = modelUrl.text.toString().trim()
            if (spec.isBlank()) {
                modelStatus.text = "Enter a .task URL or an owner/repo[:subfolder] model first."
                return@setOnClickListener
            }
            downloadButton.isEnabled = false
            downloadProgress.visibility = View.VISIBLE
            downloadProgress.isIndeterminate = true
            modelStatus.text = "Downloading in background — see notification."
            DownloadService.startModelDownload(this, spec, hfToken.text.toString())
        }

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    DownloadService.ACTION_PROGRESS -> {
                        val done = intent.getLongExtra(DownloadService.EXTRA_DONE, -1)
                        val total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, -1)
                        if (done >= 0 && total > 0) {
                            downloadProgress.visibility = View.VISIBLE
                            downloadProgress.isIndeterminate = false
                            downloadProgress.progress =
                                ((done * 100) / total).toInt().coerceIn(0, 100)
                            val downloaded = done / 1_048_576
                            val expected = total / 1_048_576
                            modelStatus.text =
                                if (done >= total) "Finalizing model…"
                                else "Downloading model: $downloaded / $expected MB"
                        }
                        return
                    }
                    DownloadService.ACTION_DONE -> Unit
                    else -> return
                }
                val kind = intent.getStringExtra(DownloadService.EXTRA_KIND).orEmpty()
                val ok = intent.getBooleanExtra(DownloadService.EXTRA_OK, false)
                val message = intent.getStringExtra(DownloadService.EXTRA_MESSAGE).orEmpty()
                if (kind == DownloadService.KIND_MODEL) {
                    downloadButton.isEnabled = true
                    downloadProgress.visibility = View.GONE
                    downloadProgress.isIndeterminate = false
                    closeLocalEngine()
                    refreshModelStatus(modelStatus)
                    modelStatus.text = if (ok) "Model ready: $message" else "Download failed: $message"
                    if (ok) Toast.makeText(this@DemoActivity, "Model ready.", Toast.LENGTH_SHORT).show()
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
            scope.launch {
                try {
                    val bypass = bypassSwitch.isChecked
                    val freshModel = pickLocalModel()
                    if (freshModel == null) {
                        downloadProgress.visibility = View.GONE
                        downloadProgress.isIndeterminate = false
                        statusView.text = "No local model — tap Download (no token needed) or adb push a folder."
                        return@launch
                    }
                    val model = freshModel
                    val mode = if (model.isDirectory) "[ORT native] " else "[MediaPipe] "
                    closeLocalEngine()
                    val client = getClient(model)

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

    private fun getClient(model: File): InferenceClient {
        return if (model.isDirectory) {
            OrtGenAiClient(this, model)
        } else {
            MediaPipeLlmClient(this, model)
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

    override fun onStart() {
        super.onStart()
        downloadReceiver?.let {
            val filter = IntentFilter(DownloadService.ACTION_DONE)
            filter.addAction(DownloadService.ACTION_PROGRESS)
            registerReceiver(it, filter, RECEIVER_NOT_EXPORTED)
        }
    }

    override fun onStop() {
        downloadReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        closeLocalEngine()
        super.onDestroy()
    }

    private fun checkForUpdate(updateStatus: TextView) {
        scope.launch {
            try {
                val manifest = fetchReleaseManifest()
                val installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                if (installed != "1.0" && manifest.commit.isNotBlank() && manifest.commit != installed) {
                    updateStatus.visibility = View.VISIBLE
                    updateStatus.text = "Update available (${manifest.commit}) — tap to open the release."
                    updateStatus.setOnClickListener {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/1337farm/forge-gatekeeper/releases/tag/latest")
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                updateStatus.visibility = View.GONE
            }
        }
    }

    private fun maybeAutoDownloadModel(
        modelStatus: TextView,
        downloadProgress: ProgressBar,
        downloadButton: Button
    ) {
        if (pickLocalModel() != null) return
        downloadButton.isEnabled = false
        downloadProgress.visibility = View.VISIBLE
        downloadProgress.isIndeterminate = true
        modelStatus.text = "No model on disk yet — downloading the default model…"
        DownloadService.startModelDownload(this, ModelDownloader.DEFAULT_ORT_REF, hfTokenValue())
    }

    private fun hfTokenValue(): String =
        runCatching { findViewById<EditText>(R.id.hfToken).text.toString() }.getOrDefault("")

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