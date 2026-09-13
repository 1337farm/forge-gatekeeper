package com.forgerig.nanogatekeeper.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

sealed interface HardwareVerdict {
    data object Eligible : HardwareVerdict
    data class Ineligible(val reason: String) : HardwareVerdict
}

object HardwareCapabilityEngine {
    const val AICORE_PACKAGE = "com.google.android.aicore"

    fun evaluate(ctx: Context, minRamBytes: Long, requireApi34: Boolean): HardwareVerdict {
        if (requireApi34 && Build.VERSION.SDK_INT < 34) {
            return HardwareVerdict.Ineligible(
                "Requires Android 14+ (API 34), found ${Build.VERSION.SDK_INT}"
            )
        }
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem < minRamBytes) {
            return HardwareVerdict.Ineligible("RAM ${mi.totalMem} < threshold $minRamBytes")
        }
        if (!isAicorePresent(ctx)) {
            return HardwareVerdict.Ineligible("AICore service package missing")
        }
        return HardwareVerdict.Eligible
    }

    fun isAicorePresent(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(AICORE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun osEligibilityMap(): Map<Int, Boolean> {
        val m = LinkedHashMap<Int, Boolean>()
        for (api in 26..35) m[api] = api >= 34
        return m
    }
}
