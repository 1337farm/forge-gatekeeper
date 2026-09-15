package com.forgerig.gatekeeper.demo

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Task-manager-style sampler for one Run tap: polls our own process CPU +
// memory on a background cadence from button press until the run finishes,
// then reports peak/avg CPU%, peak/avg private dirty RSS, and the
// device-wide free RAM delta. Process CPU comes from /proc deltas (2 samples
// per tick, so first tick always reads 0); memory from Debug.getMemoryInfo.
// start() launches the sampler and returns immediately; stop() cancels,
// joins (NonCancellable, so the report survives scope cancellation) and
// builds the report.
class ResourceMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {

    data class Sample(
        val atMs: Long,
        val cpuPct: Double,
        val pssKb: Int,
        val privateDirtyKb: Int,
        val availMemMb: Long
    )

    data class Report(
        val durationMs: Long,
        val samples: Int,
        val cpuAvgPct: Double,
        val cpuPeakPct: Double,
        val pssAvgKb: Int,
        val pssPeakKb: Int,
        val pdAvgKb: Int,
        val pdPeakKb: Int,
        val availStartMb: Long,
        val availEndMb: Long
    ) {
        fun summaryLine(): String {
            val pdDeltaMb = (pdPeakKb - pdAvgKb) / 1024
            return "CPU avg ${"%.0f".format(cpuAvgPct)}% · peak ${"%.0f".format(cpuPeakPct)}% · " +
                "RAM ${pssAvgKb / 1024}MB avg, ${pssPeakKb / 1024}MB peak (+${pdDeltaMb}MB) · " +
                "free ${availStartMb}→${availEndMb}MB"
        }
    }

    private var job: Job? = null
    private var t0 = 0L
    private val samples = mutableListOf<Sample>()

    private var prevProcCpuMs = -1L
    private var prevWallMs = 0L

    private fun memInfo(): Debug.MemoryInfo = Debug.MemoryInfo().also {
        Debug.getMemoryInfo(it)
    }

    private fun availMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / 1_048_576L
    }

    private fun procCpuMs(): Long {
        return try {
            val stat = java.io.File("/proc/self/stat").readText().split(" ")
            val utime = stat.getOrNull(13)?.toLongOrNull() ?: 0L
            val stime = stat.getOrNull(14)?.toLongOrNull() ?: 0L
            val hz = 100L
            (utime + stime) * 1000L / hz
        } catch (t: Throwable) {
            -1L
        }
    }

    private suspend fun tick(): Sample = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        val cpuNow = procCpuMs()
        val cpuPct = if (prevProcCpuMs >= 0 && cpuNow >= 0 && now > prevWallMs) {
            val dCpu = (cpuNow - prevProcCpuMs).coerceAtLeast(0)
            val dWall = (now - prevWallMs).coerceAtLeast(1)
            (dCpu * 100.0 / dWall).coerceIn(0.0, 1600.0)
        } else 0.0
        if (cpuNow >= 0) {
            prevProcCpuMs = cpuNow
            prevWallMs = now
        }
        val mi = memInfo()
        Sample(
            atMs = now - t0,
            cpuPct = cpuPct,
            pssKb = mi.totalPss,
            privateDirtyKb = mi.totalPrivateDirty,
            availMemMb = availMb()
        )
    }

    suspend fun start() {
        // Join any previous sampler first so its samples can't interleave
        // with the fresh run, then launch the new sampler WITHOUT waiting
        // for it — start() must return promptly or the run never begins.
        job?.let { old ->
            runCatching {
                old.cancel()
                old.join()
            }
        }
        job = null
        samples.clear()
        t0 = SystemClock.elapsedRealtime()
        prevProcCpuMs = -1L
        prevWallMs = t0
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    samples.add(tick())
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("ResourceMonitor", "sample failed: ${t.message}")
                }
                delay(500)
            }
        }
    }

    suspend fun stop(suppressReport: Boolean = false): Report? {
        val j = job ?: return null
        job = null
        j.cancel()
        return withContext(NonCancellable) {
            runCatching { j.join() }.getOrDefault(Unit)
            if (samples.isEmpty()) return@withContext null
            val end = SystemClock.elapsedRealtime()
            val cpuVals = samples.drop(1).map { it.cpuPct }.ifEmpty { samples.map { it.cpuPct } }
            val report = Report(
                durationMs = end - t0,
                samples = samples.size,
                cpuAvgPct = cpuVals.average(),
                cpuPeakPct = cpuVals.maxOrNull() ?: 0.0,
                pssAvgKb = samples.map { it.pssKb }.average().toInt(),
                pssPeakKb = samples.maxOf { it.pssKb },
                pdAvgKb = samples.map { it.privateDirtyKb }.average().toInt(),
                pdPeakKb = samples.maxOf { it.privateDirtyKb },
                availStartMb = samples.first().availMemMb,
                availEndMb = samples.last().availMemMb
            )
            if (!suppressReport) {
                Log.i("ResourceMonitor", "run ${report.summaryLine()} over ${report.durationMs}ms (${report.samples} samples)")
            }
            report
        }
    }
}
