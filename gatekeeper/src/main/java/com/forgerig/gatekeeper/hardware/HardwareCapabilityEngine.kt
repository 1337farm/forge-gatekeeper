package com.forgerig.gatekeeper.hardware

import android.app.ActivityManager
import android.content.Context

sealed interface HardwareVerdict {
    data object Eligible : HardwareVerdict
    data class Ineligible(val reason: String) : HardwareVerdict
}

// Pure resource gate for the on-device LLM path: the local models this project
// drives (ORT GenAI INT4 folders, MediaPipe .task) run on any Android 6+ phone
// with enough RAM, so eligibility is about memory headroom, not manufacturer
// AI packages. The former built-in NPU presence probe was removed with it.
object HardwareCapabilityEngine {
    fun evaluate(ctx: Context, minRamBytes: Long): HardwareVerdict {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem < minRamBytes) {
            return HardwareVerdict.Ineligible("RAM ${mi.totalMem} < threshold $minRamBytes")
        }
        return HardwareVerdict.Eligible
    }
}