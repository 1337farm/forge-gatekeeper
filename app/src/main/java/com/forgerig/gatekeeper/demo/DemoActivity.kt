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
    // Per-section stopwatch pills ("12s") in each header. A section's clock
    // starts when it first shows activity and ticks on heartbeats; the
    // active section's pill pulses. Frozen on completion.
    private val sectionTimers = LinkedHashMap<String, TextView>()
    private val sectionStartMs = LinkedHashMap<String, Long>()
    private var activeTimerKey: String? = null
    // The one expanded dropdown: only the active stage stays open while
    // running; stages finished live stay fully expanded, and the final
    // render collapses everything into a compact summary.
    private var expandedKey: String? = null
    // Stages finished during the live run stay fully expanded (section +
    // inner Q/A) while later stages stream; post-run review collapses all.
    private val completedLiveKeys = LinkedHashSet<String>()
    // Last cumulative stream length per stage: guards the shared live row
    // against stale/duplicate broadcasts and progress-line clobbering.
    private val sectionStreamLen = LinkedHashMap<String, Int>()
    // Finished LLM turns keyed by section ("Stage A", "Compress", "Audit",
    // "Answer"): attached to live cards as they complete so the expand
    // button exists during the run, not only after the DONE rebuild.
    private val liveQa = LinkedHashMap<String, RunResultFormat.QaTurn>()
    // Last overall (non-stage) status line; heartbeat ticks append the total
    // counter to it instead of flashing stage lines above the list.
    private var overallStatus = "Running on-device…"
    // Last debug text already rendered into chips (flicker guard) and the
    // auto-download once-guard across rotation.
    private var lastDebugChips: String? = null
    private var autoDownloadStarted = false
    private var pulseAnimator: ObjectAnimator? = null
    private var pulsedView: View? = null
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
                        val item = liveStepItem(line)
                        if (item != null) {
                            // Completed stage lines grow the timeline live (with
                            // the container's layout animation); the finished
                            // card freezes its section clock. The authoritative
                            // numbered render on DONE replaces these.
                            // Attach the stage's finished Q/A turn when known
                            // so the card's expand button exists live.
                            val filled = liveQa[item.label]?.takeIf {
                                item.question.isBlank() && item.answer.isBlank() &&
                                    (it.question.isNotBlank() || it.answer.isNotBlank())
                            }?.let { item.copy(question = it.question, answer = it.answer) }
                                ?: item
                            lastSteps = lastSteps + filled
                            addGroupedStep(stepsView, lastSteps.size - 1, filled, animate = true)
                        } else {
                            // In-progress stage lines live inside their own
                            // section — the status line above stays overall —
                            // while overall lines still move the status.
                            RunResultFormat.progressSection(line)?.let { key ->
                                val body = ensureLiveSection(key)
                                sectionPlaceholders.remove(key)?.let { body.removeView(it) }
                                markSectionActive(key)
                                // Never clobber an active decode stream with a
                                // short status line: the finished card replaces
                                // the live row on completion.
                                if (!sectionLiveViews.containsKey(key) || (sectionStreamLen[key] ?: 0) == 0) {
                                    liveRow(key, body).text = line.substringAfter("] ", line).trim()
                                }
                                sectionCounts[key]?.text = "${body.childCount}"
                            } ?: run {
                                overallStatus = line
                                statusView.text = line
                            }
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
                        // Broadcasts are cumulative snapshots: ignore stale or
                        // duplicate arrivals so the paragraph only grows, and
                        // never rewrites identical text (each rewrite retriggers
                        // layout and reads as looping/cycling).
                        tokenSection(label)?.let { key ->
                            val lastLen = sectionStreamLen[key] ?: 0
                            if (text.isEmpty() || (text.length == lastLen && sectionLiveViews.containsKey(key))) {
                                return
                            }
                            // Shorter snapshot after content exists means a new
                            // turn/restart for this stage: accept as a reset.
                            val body = ensureLiveSection(key)
                            sectionPlaceholders.remove(key)?.let { body.removeView(it) }
                            markSectionActive(key)
                            val live = liveRow(key, body)
                            val shown = if (text.length > 800) {
                                // Cut at a line boundary so the visible window
                                // doesn't jump mid-word as tokens stream in.
                                val tail = text.takeLast(800)
                                tail.substringAfter("\n", tail)
                            } else text
                            val next = "Decoding $label…\n" + shown
                            if (live.text.toString() != next) {
                                // Suppress the section's layout transition for
                                // per-token paints: height changes would
                                // otherwise slide-down on every token.
                                val lt = body.layoutTransition
                                body.layoutTransition = null
                                live.text = next
                                body.layoutTransition = lt
                            }
                            sectionStreamLen[key] = text.length
                            sectionCounts[key]?.text = "${body.childCount}"
                        }
                        return
                    }
                    InferenceService.ACTION_INFER_QA -> {
                        val label = intent.getStringExtra(InferenceService.EXTRA_QA_LABEL).orEmpty()
                        val question = intent.getStringExtra(InferenceService.EXTRA_QA_QUESTION).orEmpty()
                        val answer = intent.getStringExtra(InferenceService.EXTRA_QA_ANSWER).orEmpty()
                        // Engine turn labels ("compress#2", "audit#1") share
                        // the section mapping with token streams.
                        tokenSection(label)?.let { key ->
                            if (question.isNotBlank() || answer.isNotBlank()) {
                                liveQa[key] = RunResultFormat.QaTurn(question, answer)
                            }
                        }
                        return
                    }
                    InferenceService.ACTION_INFER_HEARTBEAT -> {
                        val base = intent.getStringExtra(InferenceService.EXTRA_HEARTBEAT_BASE).orEmpty()
                        val elapsed = intent.getLongExtra(InferenceService.EXTRA_HEARTBEAT_ELAPSED_S, -1)
                        // The tick's total counter belongs on the overall
                        // status; the ticking stage's own badge counts its
                        // section clock.
                        if (elapsed >= 0) statusView.text = "$overallStatus · ${elapsed}s"
                        RunResultFormat.progressSection(base)?.let { tickSectionTimer(it) }
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
                        // Total run time lands as a badge by the result,
                        // next to the status pill above the final message.
                        val totalBadge = findViewById<TextView>(R.id.totalBadge)
                        val elapsedS = intent.getLongExtra(InferenceService.EXTRA_ELAPSED_S, -1)
                        if (elapsedS >= 0) {
                            stylePill(totalBadge, "TOTAL ${elapsedS}s", "info")
                            addTooltip(totalBadge, "Total run time for this execution")
                            totalBadge.visibility = View.VISIBLE
                        } else {
                            totalBadge.visibility = View.GONE
                        }
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
                    // A second download leg may still be in flight: only
                    // restore the button when nothing is still downloading.
                    if (!DownloadService.hasActiveDownload) {
                        downloadButton.isEnabled = true
                        downloadProgress.visibility = View.GONE
                        downloadProgress.isIndeterminate = false
                    }
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
            overallStatus = statusView.text.toString()
            updateStatusBadge(statusBadge, null, statusView.text.toString())
            findViewById<TextView>(R.id.totalBadge).visibility = View.GONE
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
            sectionTimers.clear()
            sectionStartMs.clear()
            activeTimerKey = null
            expandedKey = null
            completedLiveKeys.clear()
            sectionStreamLen.clear()
            liveQa.clear()
            lastDebugChips = null
            // Sections exist from tap time: each fills as its stage starts
            // (live decode), progresses (finished cards), and completes.
            // Bypass runs a single Answer turn, so shells would only linger
            // as stale "Waiting…" rows — skip them there.
            if (!bypassSwitch.isChecked) precreateSections(stepsView)
            lastSteps = emptyList()
            // Match by label text, not spinner position: reordering the
            // provider options must not silently change the backend.
            val selected = findViewById<Spinner>(R.id.providerSpinner).selectedItem?.toString().orEmpty()
            val provider = when {
                selected.contains("CPU only", ignoreCase = true) -> InferenceService.PROVIDER_CPU
                selected.contains("benchmark", ignoreCase = true) -> InferenceService.PROVIDER_BOTH
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

    private fun pill(text: String, tone: String, description: String? = null): TextView {
        val desc = description ?: badgeTooltip(text)
        return TextView(this).apply {
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }.also { stylePill(it, text, tone) }
            .also { if (desc.isNotBlank()) addTooltip(it, desc) }
    }

    private fun badgeTooltip(text: String): String = when (text) {
        "SAFE" -> "Input passed safety check — no malicious content detected"
        "MATCH" -> "Audit found matching answers — accuracy preserved"
        "MISMATCH" -> "Audit found different answers — may need review"
        "REUSED" -> "Backend reused from previous run (warm)"
        "NEW" -> "Fresh backend allocation (cold start)"
        "WARM" -> "Backend warmup time in seconds"
        "COLD" -> "Heat=COLD — safe path, low risk"
        "WARM_HEAT" -> "Heat=WARM — moderate risk"
        "HOT" -> "Heat=HOT — high risk, requires attention"
        "FORCE-ALL" -> "All stages forced to run (overrides skips)"
        "ELIGIBLE" -> "Input eligible for compression"
        "NO PII" -> "No PII detected"
        "READY" -> "Stage ready"
        "GUARD" -> "Expansion guard prevented compression"
        "RETRIES" -> "Maximum retries exhausted"
        "TIMEOUT" -> "Operation timed out"
        "MALICIOUS" -> "Malicious input detected — blocked"
        "NEEDS CONTEXT" -> "Missing context — cannot evaluate"
        "NO AMBIENT PII" -> "No ambient PII found"
        "ITER" -> "Compression iteration count"
        "PII 0" -> "No PII redactions needed"
        "DRIFT" -> "Answer drift score"
        "DONE" -> "Stage completed successfully"
        "SKIPPED" -> "Stage skipped (not required for this input)"
        "PENDING" -> "Stage pending"
        "FAILED" -> "Stage failed"
        "SCRUB" -> "Deterministic scrub — removes secrets and PII"
        "DEVICE" -> "Hardware circuit check — validates device capability"
        "SAFETY" -> "Stage A — safety evaluation"
        "PII" -> "Stage B — PII redaction"
        "COMPRESS" -> "Stage C — semantic compression"
        "AUDIT" -> "Stage D — accuracy audit"
        "FALLBACK" -> "Fallback to sanitized prompt"
        "ANSWER" -> "Answer generation"
        "STEP" -> "Pipeline step"
        "RUNNING" -> "Processing is in progress"
        "SUCCESS" -> "Successfully completed"
        "CANCELLED" -> "Cancelled by user"
        "BLOCKED" -> "Input was blocked by gatekeeper"
        "ERROR" -> "An error occurred during processing"
        "RAW" -> "Raw LLM output (pipeline bypassed)"
        "BENCHMARK" -> "Benchmark comparison mode"
        else -> when {
            text.startsWith("EP ") -> "Execution provider that actually bound the model"
            text.startsWith("WARM ") -> "Backend warmup time for this run"
            text.startsWith("TOTAL ") -> "Total run time for this execution"
            text.startsWith("DRIFT") -> "Answer drift score from the accuracy audit"
            text.startsWith("PII ") -> "PII redaction count for this stage"
            text.startsWith("ITER ") -> "Compression iteration count"
            text.contains("→") -> "Token counts before and after compression"
            text.startsWith("FORCE") -> "All stages forced to run (overrides skips)"
            text == "SAFE" || text.startsWith("SAFE") -> "Input passed safety check"
            text.contains("READY") -> "Stage ready"
            else -> ""
        }
    }

    private fun addTooltip(view: TextView, text: String) {
        if (text.isBlank()) return
        view.contentDescription = text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.tooltipText = text
        }
        view.setOnLongClickListener {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            true
        }
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
        val desc = when (label) {
            "RUNNING" -> "Processing is in progress"
            "SUCCESS" -> "Successfully completed"
            "DONE" -> "Completed successfully"
            "CANCELLED" -> "Cancelled by user"
            "FALLBACK" -> "Pipeline fell back to sanitized mode"
            "BENCHMARK" -> "Benchmark comparison mode"
            "BLOCKED" -> "Input was blocked by gatekeeper"
            "ERROR" -> "An error occurred during processing"
            "RAW" -> "Raw LLM output (pipeline bypassed)"
            else -> "Unknown status"
        }
        badge.visibility = View.VISIBLE
        stylePill(badge, label, tone)
        addTooltip(badge, desc)
    }

    private fun renderDebugChips(container: LinearLayout, debug: String) {
        // Full rebuilds on every debug line flicker: skip when the source
        // text has not changed since the last render.
        if (debug == lastDebugChips) return
        lastDebugChips = debug
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
        sectionTimers.clear()
        sectionStartMs.clear()
        activeTimerKey = null
        completedLiveKeys.clear()
        sectionStreamLen.clear()
        liveQa.clear()
        lastDebugChips = null
        items.forEachIndexed { index, item -> addGroupedStep(container, index, item, animate = false) }
        // Final state is a compact summary: every dropdown closed, counts
        // and pills visible, tap to inspect a stage.
        expandedKey = null
        sectionBodies.keys.forEach { setSectionExpanded(it, false) }
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

    private fun setSectionExpanded(key: String, expanded: Boolean) {
        sectionBodies[key]?.visibility = if (expanded) View.VISIBLE else View.GONE
        sectionChevrons[key]?.text = if (expanded) "▼" else "›"
    }

    // Marks a section active: starts its stopwatch on first sight, shows
    // the timer badge, moves the running pulse onto it, and opens its
    // dropdown while collapsing the rest. The key guard keeps a streaming
    // stage from fighting manual expands — collapse only happens on an
    // actual stage switch. Idempotent otherwise.
    private fun markSectionActive(key: String) {
        ensureLiveSection(key)
        if (expandedKey != key) {
            // Completed live stages stay fully expanded; only pending
            // stages collapse as the active stream moves on.
            sectionBodies.keys.forEach { other ->
                when {
                    other == key -> setSectionExpanded(other, true)
                    completedLiveKeys.contains(other) -> setSectionExpanded(other, true)
                    else -> setSectionExpanded(other, false)
                }
            }
            expandedKey = key
        } else {
            setSectionExpanded(key, true)
        }
        if (!sectionStartMs.containsKey(key)) {
            sectionStartMs[key] = android.os.SystemClock.elapsedRealtime()
        }
        val timer = sectionTimers[key] ?: return
        if (timer.visibility != View.VISIBLE) {
            timer.text = "0s"
            timer.visibility = View.VISIBLE
        }
        activeTimerKey = key
        pulse(timer)
    }

    private fun tickSectionTimer(key: String) {
        val start = sectionStartMs[key] ?: return
        val timer = sectionTimers[key] ?: return
        if (timer.visibility != View.VISIBLE) timer.visibility = View.VISIBLE
        val s = (android.os.SystemClock.elapsedRealtime() - start) / 1000
        timer.text = "${s}s"
        if (activeTimerKey == key) pulse(timer)
    }

    // A finished card freezes its section clock: the pulse moves on (or
    // stops) and the badge keeps its last value as the stage total.
    private fun freezeSectionTimer(key: String) {
        if (activeTimerKey == key) {
            activeTimerKey = null
            stopPulse()
        }
    }

    // The single in-section live row per stage: decode streams and stage
    // status lines share it (last writer wins) until the finished card
    // supersedes it.
    private fun liveRow(key: String, body: LinearLayout): TextView =
        sectionLiveViews.getOrPut(key) {
            TextView(this).apply {
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

    // Engine turn labels ("compress#2", "audit#1", "answer") to the live
    // section their tokens decode into. Unknown labels stream nowhere.
    private fun tokenSection(label: String): String? = when {
        label == "stageA" -> "Stage A"
        label == "stageB" -> "Stage B"
        label == "scrub" -> "Scrub"
        label == "hardware" -> "Hardware"
        label.startsWith("compress#") || label == "compress" || label == "stageC" -> "Compress"
        label.startsWith("audit#") || label == "audit" || label == "stageD" -> "Audit"
        label == "answer" -> "Answer"
        else -> null
    }

    // The single running pulse lives on the active stage's timer pill —
    // the flash sits on the stage box with its timing, not on the status
    // line above the list.
    private fun pulse(view: TextView) {
        if (pulsedView === view && pulseAnimator != null) return
        stopPulse()
        view.setShadowLayer(16f, 0f, 0f, getColor(R.color.gatekeeper_mint))
        pulsedView = view
        pulseAnimator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.45f, 1f).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        (pulsedView as? TextView)?.let {
            it.alpha = 1f
            it.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        }
        pulsedView = null
    }

    private fun startRunPulse(statusView: TextView) {
        pulse(statusView)
    }

    private fun stopRunPulse(statusView: TextView) {
        stopPulse()
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
        // Per-stage stopwatch badge ("12s"): hidden until the stage shows
        // activity, counting while active, frozen on completion.
        val timer = TextView(this).apply {
            text = "0s"
            textSize = 11f
            setTextColor(getColor(R.color.gatekeeper_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            visibility = View.GONE
            setOnTouchListener { _, _ -> false }
        }
        val chevron = TextView(this).apply {
            text = "▼"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.gatekeeper_muted))
        }
        header.addView(timer)
        header.addView(count)
        header.addView(chevron)
        addTooltip(timer, "Stage execution time")
        addTooltip(count, "Finished cards in this stage")
        addTooltip(chevron, "Expand or collapse this stage")
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
        sectionTimers[item.label] = timer
        header.setOnClickListener {
            val expanded = body.visibility == View.VISIBLE
            body.visibility = if (expanded) View.GONE else View.VISIBLE
            chevron.text = if (expanded) "›" else "▼"
            // Keep manual expands sticky: the next markSectionActive must
            // not instantly re-collapse what the user just opened.
            if (expanded) {
                completedLiveKeys.remove(item.label)
                if (expandedKey == item.label) expandedKey = null
            } else {
                completedLiveKeys.add(item.label)
                expandedKey = item.label
            }
        }
        return body
    }

    private fun addGroupedStep(container: LinearLayout, index: Int, item: RunResultFormat.StepItem, animate: Boolean) {
        val ui = RunResultFormat.stepUi(item)
        val body = ensureSection(container, item, ui)
        // A finished card supersedes the live decode row and the waiting
        // placeholder — the section now shows final content only — and
        // freezes the section clock at its final value.
        sectionPlaceholders.remove(item.label)?.let { body.removeView(it) }
        sectionLiveViews.remove(item.label)?.let { body.removeView(it) }
        sectionStreamLen.remove(item.label)
        freezeSectionTimer(item.label)
        // Live completions land fully expanded (section + inner Q/A);
        // post-run review renders collapsed for a compact summary.
        val card = stepCard(index, item, ui, startExpanded = animate)
        body.addView(card)
        if (animate) {
            completedLiveKeys.add(item.label)
            setSectionExpanded(item.label, true)
            card.alpha = 0f
            card.translationY = dp(8).toFloat()
            card.animate().alpha(1f).translationY(0f).setDuration(250).start()
        }
        sectionCounts[item.label]?.text = "${body.childCount}"
    }

    private fun stepCard(index: Int, item: RunResultFormat.StepItem, ui: RunResultFormat.StepUi, startExpanded: Boolean = false): LinearLayout {
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
            text = if (hasQa && startExpanded) "▼" else if (hasQa) "›" else ""
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
            // Plain container, no LayoutTransition: the outer section body
            // already animates, so an animated inner block would double up
            // into a nested slide-down on every expand.
            val qa = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                visibility = if (startExpanded) View.VISIBLE else View.GONE
            }
            val split = RunResultFormat.splitRequest(item.question)
            labeledBlock(qa, getString(R.string.label_system_prompt), split.system, getColor(R.color.gatekeeper_muted), true)
            labeledBlock(qa, getString(R.string.label_request), split.user, getColor(R.color.gatekeeper_mint), true)
            labeledBlock(qa, getString(R.string.label_response), item.answer, getColor(R.color.gatekeeper_sky), true)
            row.addView(qa)
            row.setOnClickListener {
                // Opening the inner card is meaningless inside a collapsed
                // section: open the outer section first so the card is seen.
                sectionBodies[item.label]?.visibility = View.VISIBLE
                sectionChevrons[item.label]?.text = "▼"
                completedLiveKeys.add(item.label)
                expandedKey = item.label
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
            filter.addAction(InferenceService.ACTION_INFER_QA)
            filter.addAction(InferenceService.ACTION_INFER_HEARTBEAT)
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
            overallStatus = InferenceService.lastStatus
            findViewById<TextView>(R.id.debugLogView).text = InferenceService.lastDebug
            currentPrompt = InferenceService.lastPrompt.ifBlank { currentPrompt }
            findViewById<TextView>(R.id.answerPromptView).text = currentPrompt
            updateStatusBadge(findViewById(R.id.statusBadge), null, InferenceService.lastStatus)
            startRunPulse(findViewById(R.id.statusView))
        } else if (InferenceService.lastStatus != "Idle.") {
            findViewById<TextView>(R.id.statusView).text = InferenceService.lastStatus
            overallStatus = InferenceService.lastStatus
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
        runCatching { findViewById<TextView>(R.id.statusView)?.let { stopPulse() } }
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
        // Rotation recreates the activity with the button re-enabled: guard
        // so only one auto-download ever starts per process.
        if (autoDownloadStarted) return
        autoDownloadStarted = true
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