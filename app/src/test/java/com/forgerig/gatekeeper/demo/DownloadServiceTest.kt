package com.forgerig.gatekeeper.demo

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadServiceTest {

    private fun controller() = Robolectric.buildService(DownloadService::class.java)

    private fun modelIntent(spec: String): Intent =
        Intent(controller().get().applicationContext, DownloadService::class.java)
            .setAction(DownloadService.ACTION_MODEL)
            .putExtra(DownloadService.EXTRA_SPEC, spec)
            .putExtra(DownloadService.EXTRA_TOKEN, "")

    private fun doneIntents(c: DownloadService): List<Intent> =
        shadowOf(c.applicationContext as android.app.Application).broadcastIntents.filter { i ->
            i.action == DownloadService.ACTION_DONE &&
                i.getStringExtra(DownloadService.EXTRA_KIND) == DownloadService.KIND_MODEL
        }

    @Test
    fun `cancel while idle is a no-op`() {
        val c = controller().create().get()
        val cancel = Intent(c.applicationContext, DownloadService::class.java)
            .setAction(DownloadService.ACTION_CANCEL)
        c.onStartCommand(cancel, 0, 9)
        assertFalse(DownloadService.hasActiveDownload)
    }

    @Test
    fun `duplicate spec tap downloads once`() {
        val c = controller().create().get()
        // Unparseable spec fails fast in the worker: the guard is what keeps
        // the second tap from spawning a parallel writer on the same target.
        // The worker runs on Dispatchers.Main, paused under Robolectric, so
        // pump the looper until the single flight settles.
        c.onStartCommand(modelIntent("not a url or ref"), 0, 1)
        c.onStartCommand(modelIntent("not a url or ref"), 0, 2)
        var guard = 0
        while (DownloadService.hasActiveDownload && guard++ < 200) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(50)
        }
        assertEquals(1, doneIntents(c).size)
        assertFalse(DownloadService.hasActiveDownload)
    }
}
