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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DemoActivity : Activity() {

    private val scope = MainScope()

    private var downloadReceiver: BroadcastReceiver? = null
    // Model form starts expanded only when there is nothing on disk; once
    // a model exists the card collapses to a one-line summary + Change.
    private var modelFormExpanded = false
    // Last decoded timeline rows (mirrors stepsView) for Copy + re-attach.
    private var lastSteps: List<RunResultFormat.StepItem> = emptyList()

    private fun capabilityLine(): String {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = mi.totalMem / 1_000_000_000.0
        // NPU detect (display only, no execution): Hexagon HTP runtime lives
        // in /vendor on Snapdragon devices. Absent file (or SELinux-denied
        // read, which also reports absent) means no NPU target exists.
        val soc = (Build.SOC_MANUFACTURER ?: "unknown") + " " + (Build.SOC_MODEL ?: "unknown")
        val htp = runCatching {
            listOf("/vendor/lib64/libQnnHtp.so", "/vendor/lib/libQnnHtp.so")
                .any { java.io.File(it).exists() }
        }.getOrDefault(false)
        return "Device: API ${android.os.Build.VERSION.SDK_INT} · " +
            "RAM ${String.format("%.1f", gb)}GB · SoC $soc · " +
            if (htp) "Hexagon NPU present" else "no HTP libs (CPU/XNNPACK only)"
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
        val debugLogView = findViewById<TextView>(R.id.debugLogView)
        val bypassSwitch = findViewById<Switch>(R.id.bypassGatekeeper)
        val forceAllSwitch = findViewById<Switch>(R.id.forceAllSteps)
        val modelUrl = findViewById<EditText>(R.id.modelUrl)
        val hfToken = findViewById<EditText>(R.id.hfToken)
        val downloadButton = findViewById<Button>(R.id.downloadButton)
        val downloadProgress = findViewById<ProgressBar>(R.id.downloadProgress)
        val modelStatus = findViewById<TextView>(R.id.modelStatus)
        val modelToggleButton = findViewById<Button>(R.id.modelToggleButton)
        val modelForm = findViewById<View>(R.id.modelForm)
        val updateStatus = findViewById<TextView>(R.id.updateStatus)
        val stepsView = findViewById<LinearLayout>(R.id.stepsView)

        modelUrl.setText(ModelDownloader.DEFAULT_ORT_REF)
        modelFormExpanded = ModelFiles.pick(filesDir) == null
        updateModelSection()
        refreshModelStatus(modelStatus)
        checkForUpdate(updateStatus)
        maybeAutoDownloadModel(modelStatus, downloadProgress, downloadButton)
        // Model already on disk (restart, reinstall-over-data): warm it now
        // in the background so the first Run tap is hot, not cold.
        prewarmBackend()

        modelToggleButton.setOnClickListener {
            modelFormExpanded = !modelFormExpanded
            updateModelSection()
        }

        // Force-all only applies to the gated pipeline; with bypass on the
        // switch hides so it can never suggest otherwise.
        fun syncForceSwitch() {
            val bypassed = bypassSwitch.isChecked
            if (bypassed) forceAllSwitch.isChecked = false
            forceAllSwitch.visibility = if (bypassed) View.GONE else View.VISIBLE
            forceAllSwitch.isEnabled = !bypassed
        }
        syncForceSwitch()
        bypassSwitch.setOnCheckedChangeListener { _, _ -> syncForceSwitch() }

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
                    InferenceService.ACTION_INFER_PROGRESS -> {
                        val line = intent.getStringExtra(InferenceService.EXTRA_LINE)
                            ?: return
                        statusView.text = line
                        // Completed stage lines grow the timeline live (with
                        // the container's layout animation); the in-progress
                        // "…querying" lines only move the status above. The
                        // authoritative numbered render on DONE replaces these.
                        liveStepItem(line)?.let { item ->
                            lastSteps = lastSteps + item
                            addStepRow(stepsView, lastSteps.size - 1, item)
                        }
                        return
                    }
                    InferenceService.ACTION_INFER_DEBUG -> {
                        val line = intent.getStringExtra(InferenceService.EXTRA_DEBUG_LINE)
                        if (!line.isNullOrBlank()) {
                            val kept = (debugLogView.text.toString().split("\n") + line)
                                .takeLast(200)
                                .joinToString("\n")
                            debugLogView.text = kept
                        }
                        return
                    }
                    InferenceService.ACTION_INFER_DONE -> {
                        runButton.isEnabled = true
                        val ok = intent.getBooleanExtra(InferenceService.EXTRA_OK, false)
                        statusView.text = intent.getStringExtra(InferenceService.EXTRA_STATUS)
                            ?: statusView.text
                        outputView.text = intent.getStringExtra(InferenceService.EXTRA_OUTPUT).orEmpty()
                        telemetryView.text = intent.getStringExtra(InferenceService.EXTRA_TELEMETRY).orEmpty()
                        lastSteps = RunResultFormat.decodeSteps(
                            intent.getStringArrayListExtra(InferenceService.EXTRA_STEPS)
                                ?: emptyList()
                        )
                        renderSteps(stepsView, lastSteps)
                        if (ok) Toast.makeText(this@DemoActivity, "Run finished.", Toast.LENGTH_SHORT).show()
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
                    refreshModelStatus(modelStatus)
                    modelStatus.text = if (ok) "Model ready: $message" else "Download failed: $message"
                    if (ok) {
                        Toast.makeText(this@DemoActivity, "Model ready.", Toast.LENGTH_SHORT).show()
                        // New bytes may mean a different model: drop any cached
                        // handle before re-picking and prewarming, then fold
                        // the form away — the summary line carries it now.
                        modelFormExpanded = false
                        updateModelSection()
                        scope.launch(Dispatchers.IO) {
                            BackendCache.drop()
                            prewarmBackend()
                        }
                    }
                }
            }
        }

        val caps = capabilityLine()
        statusView.text = "$caps\nIdle."

        copyButton.setOnClickListener {
            val status = statusView.text.toString()
            val output = outputView.text.toString()
            val telemetry = telemetryView.text.toString()
            val debug = debugLogView.text.toString()
            val steps = stepsText()
            val payload = listOf(caps, status, steps, output, telemetry, debug)
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
            if (ModelFiles.pick(filesDir) == null) {
                statusView.text = "No local model — tap Download (no token needed) or adb push a folder."
                return@setOnClickListener
            }
            if (InferenceService.isRunning) {
                statusView.text = "A run is already in progress — wait for it to finish."
                return@setOnClickListener
            }
            // The run lives in a foreground service: minimizing, rotating,
            // or leaving the app never stops inference. Stage lines and the
            // final result arrive back here as broadcasts.
            runButton.isEnabled = false
            statusView.text = "Running on-device (background-safe)…"
            outputView.text = ""
            telemetryView.text = ""
            debugLogView.text = ""
            stepsView.removeAllViews()
            lastSteps = emptyList()
            val provider = when (findViewById<Spinner>(R.id.providerSpinner).selectedItemPosition) {
                1 -> InferenceService.PROVIDER_CPU
                2 -> InferenceService.PROVIDER_BOTH
                else -> InferenceService.PROVIDER_XNNPACK
            }
            InferenceService.startRun(this, raw, bypassSwitch.isChecked, forceAllSwitch.isChecked, provider)
        }
    }

    private fun renderSteps(container: LinearLayout, items: List<RunResultFormat.StepItem>) {
        container.removeAllViews()
        items.forEachIndexed { index, item -> addStepRow(container, index, item) }
    }

    private fun addStepRow(container: LinearLayout, index: Int, item: RunResultFormat.StepItem) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
            isClickable = true
            isFocusable = true
        }
        val circle = TextView(this).apply {
            text = "${index + 1}"
            gravity = android.view.Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val size = (28 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (10 * resources.displayMetrics.density).toInt()
            }
            when (item.kind) {
                "skip" -> {
                    setBackgroundResource(R.drawable.circle_skip)
                    setTextColor(getColor(R.color.gatekeeper_muted))
                }
                "fail" -> {
                    setBackgroundResource(R.drawable.circle_fail)
                    setTextColor(getColor(R.color.gatekeeper_navy))
                }
                else -> {
                    setBackgroundResource(R.drawable.circle_done)
                    setTextColor(getColor(R.color.gatekeeper_navy))
                }
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(this).apply {
            text = item.label
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.gatekeeper_text))
        }
        texts.addView(title)
        if (item.detail.isNotBlank()) {
            texts.addView(TextView(this).apply {
                text = item.detail
                textSize = 12f
                setTextColor(getColor(R.color.gatekeeper_muted))
            })
        }
        val qaContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        if (item.question.isNotBlank()) {
            qaContainer.addView(TextView(this).apply {
                text = "Q: ${item.question}"
                textSize = 11f
                setTextColor(getColor(R.color.gatekeeper_muted))
                setTextIsSelectable(true)
                setTypeface(android.graphics.Typeface.MONOSPACE)
            })
        }
        if (item.answer.isNotBlank()) {
            qaContainer.addView(TextView(this).apply {
                text = "A: ${item.answer}"
                textSize = 11f
                setTextColor(getColor(R.color.gatekeeper_text))
                setTextIsSelectable(true)
                setTypeface(android.graphics.Typeface.MONOSPACE)
            })
        }
        val hasQa = item.question.isNotBlank() || item.answer.isNotBlank()
        if (hasQa) {
            qaContainer.visibility = View.GONE
            texts.addView(qaContainer)
        }
        val icon = TextView(this).apply {
            text = if (hasQa) "›" else ""
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.gatekeeper_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER }
        }
        row.addView(circle)
        row.addView(texts)
        row.addView(icon)
        container.addView(row)
        if (hasQa) {
            row.setOnClickListener {
                val expanded = qaContainer.visibility == View.VISIBLE
                qaContainer.visibility = if (expanded) View.GONE else View.VISIBLE
                icon.text = if (expanded) "›" else "▼"
            }
        }
    }

    // Maps a live status line to a finished timeline row. In-progress lines
    // ("…querying LLM…", "Loading…", "Answering…") return null — they move
    // the status text only, until their ✓/Skip/Done line lands.
    private fun liveStepItem(line: String): RunResultFormat.StepItem? {
        val t = line.trim()
        // Duplicate-tap echo carries an old status line — status only, never
        // a timeline row, or finished steps would duplicate.
        if (t.endsWith("(run already in progress…)")) return null
        // Strip the "[ORT native] "/"[MediaPipe] " mode prefix the service adds.
        val body = t.substringAfter("] ", t).trim()
        if (body.contains("✓")) {
            val label = body.substringBefore("✓").trim().trimEnd(':').ifBlank { "Step" }
            return RunResultFormat.StepItem("done", label, body.substringAfter("✓").trim())
        }
        if (body.startsWith("Skip")) {
            return RunResultFormat.StepItem(
                "skip", "Skipped", body.removePrefix("Skip").trim().trimStart(':').trim()
            )
        }
        if (body.startsWith("Done")) {
            return RunResultFormat.StepItem("done", "Done", body.removePrefix("Done").trim().trimStart('✓').trim())
        }
        return null
    }

    private fun stepsText(): String =
        lastSteps.mapIndexed { i, s ->
            buildString {
                append("${i + 1}. ${s.label}")
                if (s.detail.isNotBlank()) append(" — ${s.detail}")
                if (s.question.isNotBlank()) append("\nQ: ${s.question}")
                if (s.answer.isNotBlank()) append("\nA: ${s.answer}")
            }
        }.joinToString("\n")

    private fun prewarmBackend() {
        scope.launch(Dispatchers.IO) {
            val model = ModelFiles.pick(filesDir) ?: return@launch
            runCatching {
                val client = BackendCache.acquire(applicationContext, model)
                BackendCache.warmup(client)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        downloadReceiver?.let {
            val filter = IntentFilter(DownloadService.ACTION_DONE)
            filter.addAction(DownloadService.ACTION_PROGRESS)
            filter.addAction(InferenceService.ACTION_INFER_PROGRESS)
            filter.addAction(InferenceService.ACTION_INFER_DEBUG)
            filter.addAction(InferenceService.ACTION_INFER_DONE)
            registerReceiver(it, filter, RECEIVER_NOT_EXPORTED)
        }
        // Coming back mid-run (minimized/rotated): reflect the service truth
        // instead of a stale Idle screen. The Run button always mirrors the
        // service — a done broadcast missed while stopped must never leave
        // it disabled with no way to send a new message.
        findViewById<Button>(R.id.runButton).isEnabled = !InferenceService.isRunning
        if (InferenceService.isRunning) {
            findViewById<TextView>(R.id.statusView).text = InferenceService.lastStatus
            findViewById<TextView>(R.id.debugLogView).text = InferenceService.lastDebug
        } else if (InferenceService.lastStatus != "Idle.") {
            findViewById<TextView>(R.id.statusView).text = InferenceService.lastStatus
            findViewById<TextView>(R.id.outputView).text = InferenceService.lastOutput
            findViewById<TextView>(R.id.telemetryView).text = InferenceService.lastTelemetry
            findViewById<TextView>(R.id.debugLogView).text = InferenceService.lastDebug
            lastSteps = RunResultFormat.decodeSteps(InferenceService.lastSteps)
            renderSteps(findViewById(R.id.stepsView), lastSteps)
        }
    }

    override fun onStop() {
        downloadReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
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
        if (ModelFiles.pick(filesDir) != null) return
        downloadButton.isEnabled = false
        downloadProgress.visibility = View.VISIBLE
        downloadProgress.isIndeterminate = true
        modelStatus.text = "No model on disk yet — downloading the default model…"
        DownloadService.startModelDownload(this, ModelDownloader.DEFAULT_ORT_REF, hfTokenValue())
    }

    private fun hfTokenValue(): String =
        runCatching { findViewById<EditText>(R.id.hfToken).text.toString() }.getOrDefault("")

    private fun refreshModelStatus(modelStatus: TextView) {
        val picked = ModelFiles.pick(filesDir)
        modelStatus.text = if (picked == null) {
            "Local model: none."
        } else if (picked.isDirectory) {
            "Model: ${picked.name}/ (ORT GenAI native)"
        } else {
            "Model: ${picked.name} (${picked.length() / 1_048_576} MB, MediaPipe)"
        }
    }

    private fun updateModelSection() {
        val form = findViewById<View>(R.id.modelForm)
        val toggle = findViewById<Button>(R.id.modelToggleButton)
        form.visibility = if (modelFormExpanded) View.VISIBLE else View.GONE
        toggle.text = getString(
            if (modelFormExpanded) R.string.hide_model_form else R.string.change_model
        )
    }
}