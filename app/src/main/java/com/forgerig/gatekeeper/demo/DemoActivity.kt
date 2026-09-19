package com.forgerig.gatekeeper.demo

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    private var currentPrompt = ""
    private var currentAnswer = ""
    // Live per-section decode rows ("Compress" -> its streaming TextView).
    // Removed when the stage's finished card lands; cleared on re-render.
    private val sectionLiveViews = LinkedHashMap<String, TextView>()
    // "Waiting for stage…" placeholders in pre-created shells, removed as
    // soon as a section shows real content (live stream or finished card).
    private val sectionPlaceholders = LinkedHashMap<String, View>()
    private var pulseAnimator: ObjectAnimator? = null
    private val sectionBodies = LinkedHashMap<String, LinearLayout>()
    private val sectionCounts = LinkedHashMap<String, TextView>()
    private val sectionChevrons = LinkedHashMap<String, TextView>()

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
        val promptSetSwitch = findViewById<Switch>(R.id.promptSetSwitch)
        val modelUrl = findViewById<EditText>(R.id.modelUrl)
        val hfToken = findViewById<EditText>(R.id.hfToken)
        val downloadButton = findViewById<Button>(R.id.downloadButton)
        val downloadProgress = findViewById<ProgressBar>(R.id.downloadProgress)
        val modelStatus = findViewById<TextView>(R.id.modelStatus)
        val modelToggleButton = findViewById<Button>(R.id.modelToggleButton)
        val modelForm = findViewById<View>(R.id.modelForm)
        val updateStatus = findViewById<TextView>(R.id.updateStatus)
        val stepsView = findViewById<LinearLayout>(R.id.stepsView)
        val statusBadge = findViewById<TextView>(R.id.statusBadge)
        val pipelineMetaView = findViewById<LinearLayout>(R.id.pipelineMetaView)
        val answerPromptView = findViewById<TextView>(R.id.answerPromptView)

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

        // Force-all and the prompt-set A/B only apply to the gated
        // pipeline; with bypass on the switches hide so they can never
        // suggest otherwise.
        fun syncForceSwitch() {
            val bypassed = bypassSwitch.isChecked
            if (bypassed) forceAllSwitch.isChecked = false
            forceAllSwitch.visibility = if (bypassed) View.GONE else View.VISIBLE
            forceAllSwitch.isEnabled = !bypassed
            promptSetSwitch.visibility = if (bypassed) View.GONE else View.VISIBLE
            promptSetSwitch.isEnabled = !bypassed
        }
        syncForceSwitch()
        bypassSwitch.setOnCheckedChangeListener { _, _ -> syncForceSwitch() }
        renderDebugChips(pipelineMetaView, debugLogView.text.toString())

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
                            addGroupedStep(stepsView, lastSteps.size - 1, item, animate = true)
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
                            renderDebugChips(pipelineMetaView, kept)
                        }
                        return
                    }
                    InferenceService.ACTION_INFER_TOKEN -> {
                        val label = intent.getStringExtra(InferenceService.EXTRA_STREAM_LABEL).orEmpty()
                        val text = intent.getStringExtra(InferenceService.EXTRA_STREAM_TEXT).orEmpty()
                        // Tokens land inside their stage's own section card —
                        // the section fills live instead of a raw block above.
                        tokenSection(label)?.let { key ->
                            val body = ensureLiveSection(key)
                            sectionPlaceholders.remove(key)?.let { body.removeView(it) }
                            val live = sectionLiveViews.getOrPut(key) {
                                TextView(this@DemoActivity).apply {
                                    textSize = 12f
                                    setTypeface(android.graphics.Typeface.MONOSPACE)
                                    setTextColor(getColor(R.color.gatekeeper_muted))
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                    ).apply { topMargin = dp(6) }
                                    body.addView(this)
                                }
                            }
                            live.text = "Decoding $label…\n" + text.takeLast(800)
                            sectionCounts[key]?.text = "${body.childCount}"
                        }
                        return
                    }
                    InferenceService.ACTION_INFER_DONE -> {
                        runButton.isEnabled = true
                        runButton.text = getString(R.string.run)
                        val ok = intent.getBooleanExtra(InferenceService.EXTRA_OK, false)
                        val status = intent.getStringExtra(InferenceService.EXTRA_STATUS).orEmpty()
                        val output = intent.getStringExtra(InferenceService.EXTRA_OUTPUT).orEmpty()
                        val answer = intent.getStringExtra(InferenceService.EXTRA_ANSWER).orEmpty()
                        val prompt = intent.getStringExtra(InferenceService.EXTRA_PROMPT)
                            .takeUnless { it.isNullOrBlank() }
                            ?: currentPrompt.ifBlank { input.text.toString() }
                        currentPrompt = prompt
                        currentAnswer = answer.ifBlank { output }
                        statusView.text = status.ifBlank { statusView.text }
                        updateStatusBadge(statusBadge, ok, statusView.text.toString())
                        outputView.text = output
                        answerPromptView.text = currentPrompt
                        telemetryView.text = intent.getStringExtra(InferenceService.EXTRA_TELEMETRY).orEmpty()
                        lastSteps = RunResultFormat.resolveAuditPlaceholders(
                            RunResultFormat.decodeSteps(
                                intent.getStringArrayListExtra(InferenceService.EXTRA_STEPS)
                                    ?: emptyList()
                            )
                        )
                        renderSteps(stepsView, lastSteps)
                        stopRunPulse(statusView)
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
            val prompt = answerPromptView.text.toString()
            val output = outputView.text.toString()
            val telemetry = telemetryView.text.toString()
            val debug = debugLogView.text.toString()
            val steps = stepsText()
            val promptBlock = prompt.trim().takeIf { it.isNotEmpty() }?.let { "Original request:\n$it" }.orEmpty()
            val responseBlock = output.trim().takeIf { it.isNotEmpty() }?.let { "Final response:\n$it" }.orEmpty()
            val payload = listOf(caps, status, steps, promptBlock, responseBlock, telemetry, debug)
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
                statusView.text = "Cancelling…"
                InferenceService.cancelRun(this)
                return@setOnClickListener
            }
            // The run lives in a foreground service: minimizing, rotating,
            // or leaving the app never stops inference. Stage lines and the
            // final result arrive back here as broadcasts.
            runButton.isEnabled = false
            runButton.text = getString(R.string.cancel_run)
            statusView.text = "Running on-device (background-safe)…"
            updateStatusBadge(statusBadge, null, statusView.text.toString())
            startRunPulse(statusView)
            currentPrompt = raw
            currentAnswer = ""
            outputView.text = ""
            answerPromptView.text = raw
            telemetryView.text = ""
            debugLogView.text = ""
            pipelineMetaView.removeAllViews()
            pipelineMetaView.visibility = View.GONE
            stepsView.removeAllViews()
            sectionBodies.clear()
            sectionCounts.clear()
            sectionChevrons.clear()
            sectionLiveViews.clear()
            sectionPlaceholders.clear()
            // Sections exist from tap time: each fills as its stage starts
            // (live decode), progresses (finished cards), and completes.
            // Bypass runs a single Answer turn, so shells would only linger
            // as stale "Waiting…" rows — skip them there.
            if (!bypassSwitch.isChecked) precreateSections(stepsView)
            lastSteps = emptyList()
            val provider = when (findViewById<Spinner>(R.id.providerSpinner).selectedItemPosition) {
                1 -> InferenceService.PROVIDER_CPU
                2 -> InferenceService.PROVIDER_BOTH
                else -> InferenceService.PROVIDER_XNNPACK
            }
            InferenceService.startRun(this, raw, bypassSwitch.isChecked, forceAllSwitch.isChecked, provider, promptSetSwitch.isChecked)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toneColor(tone: String): Int = when (tone) {
        "success" -> getColor(R.color.gatekeeper_mint)
        "warning" -> getColor(R.color.gatekeeper_amber)
        "error" -> getColor(R.color.gatekeeper_rose)
        "info" -> getColor(R.color.gatekeeper_sky)
        else -> getColor(R.color.gatekeeper_muted)
    }

    private fun stylePill(view: TextView, text: String, tone: String) {
        val color = toneColor(tone)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor((color and 0x00FFFFFF) or 0x26000000)
            setStroke(dp(1), color)
        }
        view.background = background
        view.setTextColor(color)
        view.text = text
        view.setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun pill(text: String, tone: String): TextView {
        return TextView(this).apply {
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }.also { stylePill(it, text, tone) }
    }

    private fun animatedColumn(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val transition = LayoutTransition()
            transition.enableTransitionType(LayoutTransition.CHANGING)
            layoutTransition = transition
        }
    }

    private fun labeledBlock(parent: LinearLayout, label: String, value: String, accent: Int, mono: Boolean) {
        if (value.isBlank()) return
        parent.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })
        parent.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextIsSelectable(true)
            if (mono) setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(getColor(R.color.gatekeeper_text))
            setBackgroundResource(R.drawable.field_bg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })
    }

    private fun updateStatusBadge(badge: TextView, ok: Boolean?, status: String) {
        val text = status.trim()
        val upper = text.uppercase()
        val label = when {
            text.isBlank() || text == "Idle." || text.endsWith("Idle.") -> ""
            text.startsWith("Running") -> "RUNNING"
            upper.startsWith("CANCELLED") -> "CANCELLED"
            upper.contains("FALLBACK") -> "FALLBACK"
            upper.contains("BLOCKED") -> "BLOCKED"
            upper.contains("SUCCESS") -> "SUCCESS"
            upper.contains("RAW") -> "RAW"
            upper.startsWith("BENCHMARK") -> "BENCHMARK"
            upper.startsWith("ERROR") || ok == false -> "ERROR"
            ok == true -> "DONE"
            else -> ""
        }
        val tone = when (label) {
            "SUCCESS", "RAW", "DONE" -> "success"
            "FALLBACK", "BENCHMARK" -> "warning"
            "BLOCKED", "ERROR" -> "error"
            "RUNNING" -> "info"
            else -> "muted"
        }
        if (label.isBlank()) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        stylePill(badge, label, tone)
    }

    private fun renderDebugChips(container: LinearLayout, debug: String) {
        container.removeAllViews()
        val chips = RunResultFormat.debugChips(debug)
        if (chips.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        chips.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
            pair.forEach { row.addView(pill(it.text, it.tone)) }
            container.addView(row)
        }
    }

    private fun renderSteps(container: LinearLayout, items: List<RunResultFormat.StepItem>) {
        container.removeAllViews()
        sectionBodies.clear()
        sectionCounts.clear()
        sectionChevrons.clear()
        sectionLiveViews.clear()
        sectionPlaceholders.clear()
        items.forEachIndexed { index, item -> addGroupedStep(container, index, item, animate = false) }
    }

    // Shells for every stage, created at tap time so the pipeline visibly
    // fills top-to-bottom as data arrives. Keys match the live timeline
    // labels ("Scrub ✓ …" -> "Scrub") so finished cards land in their own
    // pre-created shell instead of spawning new sections mid-run.
    private val pendingSectionKeys =
        listOf("Scrub", "Hardware", "Stage A", "Stage B", "Compress", "Audit", "Answer")

    private fun precreateSections(container: LinearLayout) {
        pendingSectionKeys.forEach { key ->
            val body = ensureLiveSection(key)
            if (!sectionPlaceholders.containsKey(key)) {
                val placeholder = TextView(this).apply {
                    text = "Waiting for stage…"
                    textSize = 12f
                    setTextColor(getColor(R.color.gatekeeper_muted))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                body.addView(placeholder)
                sectionPlaceholders[key] = placeholder
            }
        }
    }

    private fun ensureLiveSection(key: String): LinearLayout {
        sectionBodies[key]?.let { return it }
        val container = findViewById<LinearLayout>(R.id.stepsView)
        val item = RunResultFormat.StepItem("pending", key, "")
        return ensureSection(container, item, RunResultFormat.stepUi(item))
    }

    // Engine turn labels ("compress#2", "audit#1", "answer") to the live
    // section their tokens decode into. Unknown labels stream nowhere.
    private fun tokenSection(label: String): String? = when {
        label == "stageA" -> "Stage A"
        label.startsWith("compress#") -> "Compress"
        label.startsWith("audit#") -> "Audit"
        label == "answer" -> "Answer"
        else -> null
    }

    private fun startRunPulse(statusView: TextView) {
        stopRunPulse(statusView)
        statusView.setShadowLayer(16f, 0f, 0f, getColor(R.color.gatekeeper_mint))
        pulseAnimator = ObjectAnimator.ofFloat(statusView, "alpha", 1f, 0.45f, 1f).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopRunPulse(statusView: TextView) {
        pulseAnimator?.cancel()
        pulseAnimator = null
        statusView.alpha = 1f
        statusView.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
    }

    private fun ensureSection(container: LinearLayout, item: RunResultFormat.StepItem, ui: RunResultFormat.StepUi): LinearLayout {
        sectionBodies[item.label]?.let { return it }
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.field_bg)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        header.addView(pill(ui.stageText, ui.stageTone))
        header.addView(TextView(this).apply {
            text = item.label
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.gatekeeper_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val count = TextView(this).apply {
            text = "0"
            textSize = 11f
            setTextColor(getColor(R.color.gatekeeper_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        val chevron = TextView(this).apply {
            text = "▼"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.gatekeeper_muted))
        }
        header.addView(count)
        header.addView(chevron)
        val body = animatedColumn().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        section.addView(header)
        section.addView(body)
        container.addView(section)
        sectionBodies[item.label] = body
        sectionCounts[item.label] = count
        sectionChevrons[item.label] = chevron
        header.setOnClickListener {
            val expanded = body.visibility == View.VISIBLE
            body.visibility = if (expanded) View.GONE else View.VISIBLE
            chevron.text = if (expanded) "›" else "▼"
        }
        return body
    }

    private fun addGroupedStep(container: LinearLayout, index: Int, item: RunResultFormat.StepItem, animate: Boolean) {
        val ui = RunResultFormat.stepUi(item)
        val body = ensureSection(container, item, ui)
        // A finished card supersedes the live decode row and the waiting
        // placeholder — the section now shows final content only.
        sectionPlaceholders.remove(item.label)?.let { body.removeView(it) }
        sectionLiveViews.remove(item.label)?.let { body.removeView(it) }
        val card = stepCard(index, item, ui)
        body.addView(card)
        if (animate) {
            card.alpha = 0f
            card.translationY = dp(8).toFloat()
            card.animate().alpha(1f).translationY(0f).setDuration(250).start()
        }
        sectionCounts[item.label]?.text = "${body.childCount}"
    }

    private fun stepCard(index: Int, item: RunResultFormat.StepItem, ui: RunResultFormat.StepUi): LinearLayout {
        val hasQa = item.question.isNotBlank() || item.answer.isNotBlank()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            isClickable = hasQa
            isFocusable = hasQa
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        top.addView(TextView(this).apply {
            text = "${index + 1}"
            gravity = android.view.Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
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
        })
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val summary = item.detail.ifBlank { if (hasQa) "Model turn" else "" }
        if (summary.isNotBlank()) {
            meta.addView(TextView(this).apply {
                text = summary
                textSize = 12f
                setTextColor(getColor(R.color.gatekeeper_muted))
            })
        }
        if (ui.chips.isNotEmpty()) {
            // Chips wrap into rows of two: three wide pills in one row
            // overflow narrow screens and the last badge reads cut off.
            ui.chips.chunked(2).forEach { pair ->
                val chips = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                pair.forEach { chips.addView(pill(it.text, it.tone)) }
                meta.addView(chips)
            }
        }
        top.addView(meta)
        top.addView(pill(ui.statusText, ui.statusTone))
        val icon = TextView(this).apply {
            text = if (hasQa) "›" else ""
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.gatekeeper_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        top.addView(icon)
        row.addView(top)
        if (hasQa) {
            val qa = animatedColumn().apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                visibility = View.GONE
            }
            val split = RunResultFormat.splitRequest(item.question)
            labeledBlock(qa, getString(R.string.label_system_prompt), split.system, getColor(R.color.gatekeeper_muted), true)
            labeledBlock(qa, getString(R.string.label_request), split.user, getColor(R.color.gatekeeper_mint), true)
            labeledBlock(qa, getString(R.string.label_response), item.answer, getColor(R.color.gatekeeper_sky), true)
            row.addView(qa)
            row.setOnClickListener {
                val expanded = qa.visibility == View.VISIBLE
                qa.visibility = if (expanded) View.GONE else View.VISIBLE
                icon.text = if (expanded) "›" else "▼"
            }
        }
        return row
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
            val rest = body.removePrefix("Skip").trim().trimStart(':').trim()
            val detail = rest.substringAfter(':', rest).trim().ifBlank { rest }
            return RunResultFormat.StepItem(
                "skip", RunResultFormat.skipSection(rest), detail
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
            filter.addAction(InferenceService.ACTION_INFER_TOKEN)
            filter.addAction(InferenceService.ACTION_INFER_DONE)
            registerReceiver(it, filter, RECEIVER_NOT_EXPORTED)
        }
        // Coming back mid-run (minimized/rotated): reflect the service truth
        // instead of a stale Idle screen. The Run button always mirrors the
        // service — a done broadcast missed while stopped must never leave
        // it disabled with no way to send a new message.
        findViewById<Button>(R.id.runButton).isEnabled = !InferenceService.isRunning
        findViewById<Button>(R.id.runButton).text = getString(
            if (InferenceService.isRunning) R.string.cancel_run else R.string.run
        )
        if (InferenceService.isRunning) {
            findViewById<TextView>(R.id.statusView).text = InferenceService.lastStatus
            findViewById<TextView>(R.id.debugLogView).text = InferenceService.lastDebug
            currentPrompt = InferenceService.lastPrompt.ifBlank { currentPrompt }
            findViewById<TextView>(R.id.answerPromptView).text = currentPrompt
            updateStatusBadge(findViewById(R.id.statusBadge), null, InferenceService.lastStatus)
            startRunPulse(findViewById(R.id.statusView))
        } else if (InferenceService.lastStatus != "Idle.") {
            findViewById<TextView>(R.id.statusView).text = InferenceService.lastStatus
            currentPrompt = InferenceService.lastPrompt.ifBlank { currentPrompt }
            currentAnswer = InferenceService.lastAnswer.ifBlank { InferenceService.lastOutput }
            findViewById<TextView>(R.id.answerPromptView).text = currentPrompt
            findViewById<TextView>(R.id.outputView).text = InferenceService.lastOutput
            findViewById<TextView>(R.id.telemetryView).text = InferenceService.lastTelemetry
            findViewById<TextView>(R.id.debugLogView).text = InferenceService.lastDebug
            updateStatusBadge(findViewById(R.id.statusBadge), null, InferenceService.lastStatus)
            lastSteps = RunResultFormat.resolveAuditPlaceholders(
                RunResultFormat.decodeSteps(InferenceService.lastSteps)
            )
            renderSteps(findViewById(R.id.stepsView), lastSteps)
        }
        renderDebugChips(
            findViewById(R.id.pipelineMetaView),
            InferenceService.lastDebug
        )
    }

    override fun onStop() {
        downloadReceiver?.let { runCatching { unregisterReceiver(it) } }
        runCatching { findViewById<TextView>(R.id.statusView)?.let { stopRunPulse(it) } }
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