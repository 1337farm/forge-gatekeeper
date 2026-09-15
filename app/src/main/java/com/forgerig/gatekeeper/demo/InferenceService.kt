package com.forgerig.gatekeeper.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
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

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Runs", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        super.onDestroy()
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
        if (prompt.isBlank()) return START_NOT_STICKY
        isRunning = true
        lastStatus = "Running on-device…"
        lastOutput = ""
        lastTelemetry = ""
        lastDebug = ""
        lastSteps = ArrayList()
        runInference(prompt, bypass, forceAll)
        return START_NOT_STICKY
    }

    private fun runInference(prompt: String, bypass: Boolean, forceAll: Boolean) {
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            val id = 1
            startForeground(id, runNotification("Running on-device…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            val monitor = ResourceMonitor(this@InferenceService, scope)
            monitor.start()
            try {
                val model = ModelFiles.pick(filesDir)
                if (model == null) {
                    finish(nm, id, false, "Error", "No local model — download one first.", "")
                    return@launch
                }
                val mode = if (model.isDirectory) "[ORT native] " else "[MediaPipe] "
                val client = BackendCache.acquire(applicationContext, model)
                publish("$mode Loading on-device model…", nm, id)
                val warmMs = BackendCache.warmup(client)
                val provider = BackendCache.providerOf(client)
                val reused = BackendCache.lastAcquireReused
                publish(
                    "$mode " + if (reused) "Reusing warm backend" else "Ready" +
                        (provider?.let { " ($it)" } ?: "") +
                        (if (warmMs >= 0) " in ${warmMs}ms" else "") + " — generating…",
                    nm, id
                )
                if (bypass) {
                    val rawResult = client.generate("", prompt)
                    val res = monitor.stop()
                    val telemetry = "Gatekeeper pipeline bypassed — no sanitization, redaction, compression, or audit." +
                        (res?.let { "\n${it.summaryLine()}" } ?: "")
                    val steps = RunResultFormat.encodeSteps(
                        listOf(RunResultFormat.StepItem("done", "Answer", "raw LLM reply, pipeline bypassed"))
                    )
                    finish(nm, id, true, "$mode RAW (gatekeeper bypassed)", rawResult, telemetry, steps)
                } else {
                    val engine = GatekeeperEngine(applicationContext, client)
                    // forceAll overrides every config-driven skip (Stage B,
                    // compression, audit, tiny-input short-circuit) so every
                    // step runs — except hardware-ineligible and malicious
                    // blocks, which stay fail-safe by design.
                    val config = if (forceAll) {
                        GatekeeperConfig(
                            enableStageB = true,
                            enableCompression = true,
                            enableAudit = true,
                            minTokensForCompression = 0
                        )
                    } else GatekeeperConfig()
                    if (forceAll) debug("config", "force-all ON: Stage B + compression + audit + tiny inputs all run")
                    val result = engine.processPrompt(
                        rawPrompt = prompt,
                        config = config,
                        onProgress = { line -> publish("$mode$line", nm, id) },
                        onLlmEvent = { label, direction, text -> debug(label, "$direction: $text") }
                    )
                    val res = monitor.stop()
                    // SUCCESS answers the safe prompt so the output shows a
                    // real LLM reply, not just the sanitized echo. Blocked /
                    // fallback stay answer-free by design.
                    val answer = if (result is GatekeeperResult.Success) {
                        publish("$mode Answering…", nm, id)
                        runCatching { client.generate("", result.safeCompressedPrompt) }
                            .getOrElse { "Answer failed: ${it.message}" }
                    } else null
                    val (status, output, telemetry) =
                        RunResultFormat.format(result, mode, res?.summaryLine(), answer)
                    val resultTelemetry = when (result) {
                        is GatekeeperResult.Success -> result.telemetry
                        is GatekeeperResult.Blocked -> result.telemetry
                        is GatekeeperResult.FallbackRequired -> result.telemetry
                    }
                    val steps = RunResultFormat.encodeSteps(RunResultFormat.steps(resultTelemetry))
                    finish(nm, id, true, status, output, telemetry, steps)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                monitor.stop(suppressReport = true)
                BackendCache.drop()
                finish(nm, id, false, "Error: ${t.message}", "", "")
            }
        }
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
        val line = "[$label] $text"
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
        steps: ArrayList<String> = ArrayList()
    ) {
        lastStatus = status
        lastOutput = output
        lastTelemetry = telemetry
        lastSteps = steps
        nm.notify(id, doneNotification(ok, status))
        sendBroadcast(
            Intent(ACTION_INFER_DONE)
                .setPackage(packageName)
                .putExtra(EXTRA_OK, ok)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_OUTPUT, output)
                .putExtra(EXTRA_TELEMETRY, telemetry)
                .putStringArrayListExtra(EXTRA_STEPS, steps)
        )
        isRunning = false
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
        const val EXTRA_LINE = "line"
        const val EXTRA_OK = "ok"
        const val EXTRA_STATUS = "status"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_TELEMETRY = "telemetry"
        const val EXTRA_STEPS = "steps"
        const val EXTRA_DEBUG_LINE = "debug_line"
        private const val CHANNEL = "runs"
        private const val TAG = "InferenceService"
        private const val MAX_DEBUG_LINES = 200

        @Volatile
        var isRunning: Boolean = false
            private set
        @Volatile
        var lastStatus: String = "Idle."
            private set
        @Volatile
        var lastOutput: String = ""
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

        fun startRun(context: Context, prompt: String, bypass: Boolean, forceAll: Boolean) {
            context.startForegroundService(
                Intent(context, InferenceService::class.java)
                    .setAction(ACTION_RUN)
                    .putExtra(EXTRA_PROMPT, prompt)
                    .putExtra(EXTRA_BYPASS, bypass)
                    .putExtra(EXTRA_FORCE_ALL, forceAll)
            )
        }
    }
}
