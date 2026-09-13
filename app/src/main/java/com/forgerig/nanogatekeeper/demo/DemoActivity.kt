package com.forgerig.nanogatekeeper.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.forgerig.nanogatekeeper.engine.AICoreInferenceClient
import com.forgerig.nanogatekeeper.engine.NanoGatekeeperEngine
import com.forgerig.nanogatekeeper.model.GatekeeperConfig
import com.forgerig.nanogatekeeper.model.GatekeeperResult
import kotlinx.coroutines.launch

class DemoActivity : AppCompatActivity() {

    private lateinit var engine: NanoGatekeeperEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = NanoGatekeeperEngine(
            applicationContext,
            AICoreInferenceClient(applicationContext)
        )

        val input = findViewById<EditText>(R.id.input)
        val runButton = findViewById<Button>(R.id.runButton)
        val copyButton = findViewById<Button>(R.id.copyButton)
        val statusView = findViewById<TextView>(R.id.statusView)
        val outputView = findViewById<TextView>(R.id.outputView)
        val telemetryView = findViewById<TextView>(R.id.telemetryView)

        // Copies the full on-screen report (status + output + telemetry) so
        // fallback/error diagnoses survive — never the raw input.
        copyButton.setOnClickListener {
            val status = statusView.text.toString()
            val output = outputView.text.toString()
            val telemetry = telemetryView.text.toString()
            val payload = listOf(status, output, telemetry)
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
            runButton.isEnabled = false
            statusView.text = "Running on-device…"
            outputView.text = ""
            telemetryView.text = ""
            lifecycleScope.launch {
                try {
                    render(engine.processPrompt(raw, GatekeeperConfig()), statusView, outputView, telemetryView)
                } catch (t: Throwable) {
                    statusView.text = "Error: ${t.message}"
                } finally {
                    runButton.isEnabled = true
                }
            }
        }
    }

    private fun render(
        result: GatekeeperResult,
        statusView: TextView,
        outputView: TextView,
        telemetryView: TextView
    ) {
        when (result) {
            is GatekeeperResult.Success -> {
                val t = result.telemetry
                statusView.text = "SUCCESS (heat=${result.heat})"
                outputView.text = result.safeCompressedPrompt
                telemetryView.text = "tokens ${t.preCompressionTokens} → ${t.postCompressionTokens} " +
                    "(${String.format("%.1f", t.compressionRatioPct)}% saved) · " +
                    "compress iters=${t.compressionIterations} audit iters=${t.auditIterations} · " +
                    "redactions=${t.redactionEvents}"
            }
            is GatekeeperResult.Blocked -> {
                statusView.text = "BLOCKED (heat=${result.heat}): ${result.reason}"
                outputView.text = "(nothing sent anywhere)"
                telemetryView.text = "redactions=${result.telemetry.redactionEvents}"
            }
            is GatekeeperResult.FallbackRequired -> {
                val t = result.telemetry
                statusView.text = "FALLBACK: ${result.reason}"
                outputView.text = result.sanitizedPrompt
                telemetryView.text = "maxRetriesExhausted=${t.maxRetriesExhausted} · " +
                    "redactions=${t.redactionEvents}"
            }
        }
    }
}
