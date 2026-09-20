package com.forgerig.gatekeeper.demo

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InferenceServiceTest {

    @After
    fun tearDown() {
        // A prior test may leave a no-model run finishing on a background
        // thread; wait for the service to settle so isRunning never leaks.
        var guard = 0
        while (InferenceService.isRunning && guard++ < 100) Thread.sleep(50)
    }

    private fun controller() = Robolectric.buildService(InferenceService::class.java)

    private fun runIntent(prompt: String): Intent =
        Intent(controller().get().applicationContext, InferenceService::class.java)
            .setAction(InferenceService.ACTION_RUN)
            .putExtra(InferenceService.EXTRA_PROMPT, prompt)
            .putExtra(InferenceService.EXTRA_BYPASS, false)
            .putExtra(InferenceService.EXTRA_FORCE_ALL, false)
            .putExtra(InferenceService.EXTRA_PROVIDER, InferenceService.PROVIDER_XNNPACK)
            .putExtra(InferenceService.EXTRA_MICRO_OP, false)

    @Test
    fun `blank prompt never starts a run`() {
        val c = controller().create().get()
        c.onStartCommand(runIntent("   "), 0, 1)
        assertFalse(InferenceService.isRunning)
    }

    @Test
    fun `cancel while idle is a no-op`() {
        val c = controller().create().get()
        assertFalse(InferenceService.isRunning)
        val cancel = Intent(c.applicationContext, InferenceService::class.java)
            .setAction(InferenceService.ACTION_CANCEL)
        c.onStartCommand(cancel, 0, 2)
        assertFalse(InferenceService.isRunning)
    }

    @Test
    fun `duplicate tap keeps the first prompt and explains itself`() {
        val c = controller().create().get()
        c.onStartCommand(runIntent("first prompt"), 0, 1)
        assertTrue(InferenceService.isRunning)
        assertEquals("first prompt", InferenceService.lastPrompt)
        c.onStartCommand(runIntent("second prompt"), 0, 2)
        assertEquals("first prompt", InferenceService.lastPrompt)
        val explained = shadowOf(c.applicationContext as android.app.Application).broadcastIntents.any { i ->
            i.action == InferenceService.ACTION_INFER_PROGRESS &&
                (i.getStringExtra(InferenceService.EXTRA_LINE).orEmpty()
                    .contains("run already in progress"))
        }
        assertTrue(explained)
    }
}
