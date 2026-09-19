package com.forgerig.gatekeeper.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.forgerig.gatekeeper.engine.GatekeeperEngine
import com.forgerig.gatekeeper.model.GatekeeperConfig
import com.forgerig.gatekeeper.model.GatekeeperResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Runs the gatekeeper pipeline in a foreground service so minimizing (or
// rotating) the app never kills inference: the Activity only starts the run
// and renders broadcasts. The warmed BackendCache client is shared with the
// Activity prewarm, so taps stay hot across recreation.
class InferenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Runs", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Gatekeeper:run"
        )
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_RUN) return START_NOT_STICKY
        if (isRunning) {
            Log.i(TAG, "run already in flight — ignoring duplicate tap")
            // Still tell the UI, so a tap during a run explains itself
            // instead of looking dead.
            sendBroadcast(
                Intent(ACTION_INFER_PROGRESS)
                    .setPackage(packageName)
                    .putExtra(EXTRA_LINE, "$lastStatus (run already in progress…)")
            )
            return START_NOT_STICKY
        }
        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
        val bypass = intent.getBooleanExtra(EXTRA_BYPASS, false)
        val forceAll = intent.getBooleanExtra(EXTRA_FORCE_ALL, false)
        val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: PROVIDER_XNNPACK
        val microOp = intent.getBooleanExtra(EXTRA_MICRO_OP, false)
        if (prompt.isBlank()) return START_NOT_STICKY
        acquireWakeLock()
        isRunning = true
        lastStatus = "Running on-device…"
        lastPrompt = prompt
        lastOutput = ""
        lastAnswer = ""
        lastTelemetry = ""
        lastDebug = ""
        lastSteps = ArrayList()
        runInference(prompt, bypass, forceAll, provider, microOp)
        return START_NOT_STICKY
    }

    private data class LegResult(
        val status: String,
        val output: String,
        val telemetry: String,
        val steps: ArrayList<String>,
        val totalMs: Long,
        val answer: String?,
        // Actual EP reported by nativeGetProvider after model creation —
        // what ORT really bound, not what was requested.
        val provider: String?
    )

    private var debugTag = ""

    private fun runInference(prompt: String, bypass: Boolean, forceAll: Boolean, provider: String, microOp: Boolean) {
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            val id = 1
            startForeground(id, runNotification("Running on-device…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            val monitor = ResourceMonitor(this@InferenceService, scope)
            monitor.start()
            try {
                val model = ModelFiles.pick(filesDir)
                if (model == null) {
                    finish(nm, id, false, "Error", "No local model — download one first.", "", prompt = prompt)
                    return@launch
                }
                val isOrt = model.isDirectory
                // Benchmark = same prompt through both ORT providers back to
                // back (XNNPACK first: it is the default path, so leg 1 often
                // starts warm). MediaPipe has no provider choice: single run.
                if (provider == PROVIDER_BOTH && isOrt && !bypass) {
                    runBenchmark(prompt, forceAll, model, nm, id, monitor, microOp)
                    return@launch
                }
                val useXnnpack = provider != PROVIDER_CPU
                val mode = if (isOrt) {
                    if (useXnnpack) "[ORT XNNPACK] " else "[ORT CPU] "
                } else "[MediaPipe] "
                if (bypass) {
                    val client = BackendCache.acquire(applicationContext, model, useXnnpack)
                    debugTag = ""
                    val rawResult = client.generate("", prompt)
                    val res = monitor.stop()
                    val telemetry = "Gatekeeper pipeline bypassed — no sanitization, redaction, compression, or audit." +
                        (res?.let { "\n${it.summaryLine()}" } ?: "")
                    val steps = RunResultFormat.encodeSteps(
                        listOf(RunResultFormat.StepItem(
                            "done", "Answer", "raw LLM reply, pipeline bypassed",
                            question = prompt, answer = rawResult
                        ))
                    )
                    finish(nm, id, true, "$mode RAW (gatekeeper bypassed)", rawResult, telemetry, steps, prompt = prompt, answer = rawResult)
                } else {
                    val leg = executePipeline(
                        prompt, model, useXnnpack, forceAll, mode, nm, id, microOp,
                        stopSampling = { monitor.stop()?.summaryLine() }
                    )
                    finish(nm, id, true, leg.status, leg.output, leg.telemetry, leg.steps, prompt = prompt, answer = leg.answer ?: leg.output)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                monitor.stop(suppressReport = true)
                BackendCache.drop()
                finish(nm, id, false, "Error: ${t.message}", "", "", prompt = prompt)
            }
        }
    }

    // One full gated run (warmup + pipeline + answer). Shared by single runs
    // and benchmark legs; stopSampling lets the benchmark keep sampling
    // across both legs and stop only once at the end.
    private suspend fun executePipeline(
        prompt: String,
        model: java.io.File,
        useXnnpack: Boolean,
        forceAll: Boolean,
        mode: String,
        nm: NotificationManager,
        id: Int,
        microOp: Boolean,
        stopSampling: suspend () -> String?
    ): LegResult {
        debugTag = if (mode.contains("CPU")) "cpu" else if (mode.contains("XNNPACK")) "xnnpack" else ""
        val client = BackendCache.acquire(applicationContext, model, useXnnpack)
        publish("$mode Loading on-device model…", nm, id)
        val warmMs = BackendCache.warmup(client)
        val provider = BackendCache.providerOf(client)
        val reused = BackendCache.lastAcquireReused
        // Provider goes to the debug log too: the status line is transient,
        // this is the durable record of which EP actually bound the model.
        debug("provider", "requested=${if (useXnnpack) "XNNPACK" else "CPU"} actual=${provider ?: "?"} warmMs=$warmMs reused=$reused")
        publish(
            "$mode " + if (reused) "Reusing warm backend" else "Ready" +
                (provider?.let { " ($it)" } ?: "") +
                (if (warmMs >= 0) " in ${warmMs}ms" else "") + " — generating…",
            nm, id
        )
        val engine = GatekeeperEngine(applicationContext, client)
        val promptSet =
            if (microOp) com.forgerig.gatekeeper.model.PromptSet.MICRO_OP
            else com.forgerig.gatekeeper.model.PromptSet.LABELED
        // forceAll overrides every config-driven skip (Stage B,
        // compression, audit, tiny-input short-circuit) so every
        // step runs — except hardware-ineligible and malicious
        // blocks, which stay fail-safe by design.
        val config = if (forceAll) {
            GatekeeperConfig(
                enableStageB = true,
                enableCompression = true,
                enableAudit = true,
                minTokensForCompression = 0,
                promptSet = promptSet
            )
        } else GatekeeperConfig(promptSet = promptSet)
        // Prompt set goes to the debug log: the status line is transient,
        // this is the durable A/B attribution record for comparison runs.
        debug("config", "promptSet=$promptSet")
        if (forceAll) debug("config", "force-all ON: Stage B + compression + audit + tiny inputs all run")
        // Full Q/A turns, keyed by engine label, joined to their timeline
        // rows at the end — payloads live under their own step, never in a
        // shared firehose.
        val qaReq = mutableMapOf<String, String>()
        val qaRes = mutableMapOf<String, String>()
        val result = engine.processPrompt(
            rawPrompt = prompt,
            config = config,
            onProgress = { line -> publish("$mode$line", nm, id) },
            onLlmEvent = { label, direction, text ->
                if (direction == "request") qaReq[label] = text else qaRes[label] = text
            }
        )
        val resourceLine = stopSampling()
        // SUCCESS answers the safe prompt so the output shows a
        // real LLM reply, not just the sanitized echo. Blocked /
        // fallback stay answer-free by design.
        val answer = if (result is GatekeeperResult.Success) {
            publish("$mode Answering…", nm, id)
            runCatching { client.generate("", result.safeCompressedPrompt) }
                .getOrElse { "Answer failed: ${it.message}" }
        } else null
        val (status, output, telemetry) =
            RunResultFormat.format(result, mode, resourceLine, answer)
        val resultTelemetry = when (result) {
            is GatekeeperResult.Success -> result.telemetry
            is GatekeeperResult.Blocked -> result.telemetry
            is GatekeeperResult.FallbackRequired -> result.telemetry
        }
        val qa = buildMap {
            (qaReq.keys + qaRes.keys).distinct().forEach { label ->
                put(label, RunResultFormat.QaTurn(
                    question = qaReq[label].orEmpty(),
                    answer = qaRes[label].orEmpty()
                ))
            }
        }
        val steps = RunResultFormat.encodeSteps(RunResultFormat.steps(resultTelemetry, qa))
        return LegResult(status, output, telemetry, steps, resultTelemetry.totalDurationMs, answer, provider)
    }

    private suspend fun runBenchmark(
        prompt: String,
        forceAll: Boolean,
        model: java.io.File,
        nm: NotificationManager,
        id: Int,
        monitor: ResourceMonitor,
        microOp: Boolean
    ) {
        val xnn = executePipeline(
            prompt, model, true, forceAll, "[ORT XNNPACK] ", nm, id, microOp,
            stopSampling = { null }
        )
        val cpu = executePipeline(
            prompt, model, false, forceAll, "[ORT CPU] ", nm, id, microOp,
            stopSampling = { null }
        )
        val res = monitor.stop()
        val resourceLine = res?.summaryLine()
        val summary = RunResultFormat.benchmarkSummary(cpu.totalMs, xnn.totalMs)
        Log.i(TAG, summary)
        Log.i(TAG, "benchmark providers: cpu=${cpu.provider} xnnpack=${xnn.provider}")
        fun legHead(name: String, leg: LegResult): String {
            val ms = "%.1f".format(leg.totalMs / 1000.0)
            return "— $name (provider=${leg.provider ?: "?"}, ${ms}s) —"
        }
        val output = "${legHead("CPU only", cpu)}\n" +
            (cpu.answer ?: cpu.output) +
            "\n\n${legHead("XNNPACK", xnn)}\n" +
            (xnn.answer ?: xnn.output)
        val telemetry = "— CPU only —\n${cpu.telemetry}\n— XNNPACK —\n${xnn.telemetry}\n" +
            summary + (resourceLine?.let { "\n$it" } ?: "")
        // Steps shown are the final (XNNPACK) leg; both legs' full detail
        // streams in the debug log with cpu:/xnnpack: tags.
        finish(nm, id, true, summary, output, telemetry, xnn.steps, prompt = prompt, answer = output)
    }

    private fun publish(line: String, nm: NotificationManager, id: Int) {
        lastStatus = line
        sendBroadcast(
            Intent(ACTION_INFER_PROGRESS)
                .setPackage(packageName)
                .putExtra(EXTRA_LINE, line)
        )
        nm.notify(id, runNotification(line))
    }

    private fun debug(label: String, text: String) {
        val line = if (debugTag.isEmpty()) "[$label] $text" else "[$debugTag:$label] $text"
        lastDebug = ((lastDebug + "\n" + line).split("\n").takeLast(MAX_DEBUG_LINES)).joinToString("\n")
        sendBroadcast(
            Intent(ACTION_INFER_DEBUG)
                .setPackage(packageName)
                .putExtra(EXTRA_DEBUG_LINE, line)
        )
    }

    private fun finish(
        nm: NotificationManager,
        id: Int,
        ok: Boolean,
        status: String,
        output: String,
        telemetry: String,
        steps: ArrayList<String> = ArrayList(),
        prompt: String = "",
        answer: String = ""
    ) {
        lastStatus = status
        lastPrompt = prompt
        lastOutput = output
        lastAnswer = answer
        lastTelemetry = telemetry
        lastSteps = steps
        nm.notify(id, doneNotification(ok, status))
        sendBroadcast(
            Intent(ACTION_INFER_DONE)
                .setPackage(packageName)
                .putExtra(EXTRA_OK, ok)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_PROMPT, prompt)
                .putExtra(EXTRA_OUTPUT, output)
                .putExtra(EXTRA_ANSWER, answer)
                .putExtra(EXTRA_TELEMETRY, telemetry)
                .putStringArrayListExtra(EXTRA_STEPS, steps)
        )
        isRunning = false
        releaseWakeLock()
        stopSelf()
    }

    private fun runNotification(line: String): Notification {
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Gatekeeper run")
            .setContentText(line.take(120))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun doneNotification(ok: Boolean, status: String): Notification {
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (ok) "Run finished" else "Run failed")
            .setContentText(status.take(120))
            .setSmallIcon(
                if (ok) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .build()
    }

    companion object {
        const val ACTION_RUN = "com.forgerig.gatekeeper.demo.action.RUN"
        const val ACTION_INFER_PROGRESS = "com.forgerig.gatekeeper.demo.action.INFER_PROGRESS"
        const val ACTION_INFER_DONE = "com.forgerig.gatekeeper.demo.action.INFER_DONE"
        const val ACTION_INFER_DEBUG = "com.forgerig.gatekeeper.demo.action.INFER_DEBUG"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_BYPASS = "bypass"
        const val EXTRA_FORCE_ALL = "force_all"
        const val EXTRA_MICRO_OP = "micro_op"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_LINE = "line"
        const val EXTRA_OK = "ok"
        const val EXTRA_STATUS = "status"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_TELEMETRY = "telemetry"
        const val EXTRA_STEPS = "steps"
        const val EXTRA_DEBUG_LINE = "debug_line"
        private const val CHANNEL = "runs"
        private const val TAG = "InferenceService"
        private const val MAX_DEBUG_LINES = 200
        const val PROVIDER_XNNPACK = "xnnpack"
        const val PROVIDER_CPU = "cpu"
        const val PROVIDER_BOTH = "both"

        @Volatile
        var isRunning: Boolean = false
            private set
        @Volatile
        var lastStatus: String = "Idle."
            private set
        @Volatile
        var lastPrompt: String = ""
            private set
        @Volatile
        var lastOutput: String = ""
            private set
        @Volatile
        var lastAnswer: String = ""
            private set
        @Volatile
        var lastTelemetry: String = ""
            private set
        @Volatile
        var lastDebug: String = ""
            private set
        @Volatile
        var lastSteps: ArrayList<String> = ArrayList()
            private set

        fun startRun(context: Context, prompt: String, bypass: Boolean, forceAll: Boolean, provider: String, microOp: Boolean) {
            context.startForegroundService(
                Intent(context, InferenceService::class.java)
                    .setAction(ACTION_RUN)
                    .putExtra(EXTRA_PROMPT, prompt)
                    .putExtra(EXTRA_BYPASS, bypass)
                    .putExtra(EXTRA_FORCE_ALL, forceAll)
                    .putExtra(EXTRA_MICRO_OP, microOp)
                    .putExtra(EXTRA_PROVIDER, provider)
            )
        }
    }
}
