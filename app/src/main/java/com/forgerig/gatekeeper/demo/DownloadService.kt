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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = 0
    private var nextId = 1
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Gatekeeper:download"
        )
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MODEL -> {
                val spec = intent.getStringExtra(EXTRA_SPEC).orEmpty()
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                runDownload(KIND_MODEL, "Downloading model") { notify ->
                    downloadModel(spec, token, notify)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runDownload(
        kind: String,
        title: String,
        block: suspend ((Long, Long) -> Unit) -> String
    ) {
        running++
        val id = nextId++
        acquireWakeLock()
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            startForeground(id, progressNotification(title, -1, -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            var lastSent = 0L
            try {
                val detail = block { done, total ->
                    // The downloader emits per-buffer; batch to ~1/s so the UI
                    // thread is not spammed with hundreds of sticky intents/renders.
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (done >= total || now - lastSent >= 750) {
                        lastSent = now
                        sendProgress(kind, done, total)
                        nm.notify(id, progressNotification(title, done, total))
                    }
                }
                nm.notify(id, doneNotification(title, true, detail))
                broadcast(kind, true, detail)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                nm.notify(id, doneNotification(title, false, t.message ?: "failed"))
                broadcast(kind, false, t.message ?: "failed")
            } finally {
                if (--running == 0) {
                    releaseWakeLock()
                    stopSelf()
                }
            }
        }
    }

    private suspend fun downloadModel(
        spec: String,
        token: String,
        notify: (Long, Long) -> Unit
    ): String {
        if (spec.isBlank()) throw IllegalArgumentException("Empty model spec")
        return if (spec.contains("://")) {
            val fileName = spec.substringAfterLast('/').substringBefore('?')
                .takeIf { it.endsWith(".task", ignoreCase = true) }
                ?: ModelDownloader.DEFAULT_MODEL_FILE
            val dest = File(File(filesDir, "models"), fileName)
            ModelDownloader.download(spec, token.ifBlank { null }, dest, notify)
            fileName
        } else {
            val ref = ModelDownloader.parseRepoRef(spec)
            val dir = File(
                File(filesDir, "ort-models"),
                ref.repo.substringAfterLast('/').take(40)
            )
            ModelDownloader.downloadOrtFolder(ref, dir, notify)
            dir.name
        }
    }

    private fun progressNotification(title: String, done: Long, total: Long): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0 && done >= 0) {
            val pct = ((done * 100) / total).toInt()
            builder.setContentText("$pct%")
                .setProgress(100, pct, false)
        } else {
            builder.setContentText("Downloading…")
                .setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun doneNotification(title: String, ok: Boolean, detail: String): Notification {
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(if (ok) detail else "Failed: $detail")
            .setSmallIcon(
                if (ok) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .build()
    }

    private fun broadcast(kind: String, ok: Boolean, message: String) {
        sendBroadcast(
            Intent(ACTION_DONE)
                .setPackage(packageName)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_OK, ok)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private fun sendProgress(kind: String, done: Long, total: Long) {
        sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(packageName)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total)
        )
    }

    companion object {
        const val ACTION_MODEL = "com.forgerig.gatekeeper.demo.action.MODEL"
        const val ACTION_DONE = "com.forgerig.gatekeeper.demo.action.DONE"
        const val ACTION_PROGRESS = "com.forgerig.gatekeeper.demo.action.PROGRESS"
        const val EXTRA_SPEC = "spec"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_KIND = "kind"
        const val EXTRA_OK = "ok"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DONE = "done"
        const val EXTRA_TOTAL = "total"
        const val KIND_MODEL = "model"
        private const val CHANNEL = "downloads"

        fun startModelDownload(context: Context, spec: String, token: String) {
            context.startForegroundService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_MODEL)
                    .putExtra(EXTRA_SPEC, spec)
                    .putExtra(EXTRA_TOKEN, token)
            )
        }
    }
}
