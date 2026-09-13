package com.forgerig.nanogatekeeper.integration

import android.util.Log
import com.forgerig.nanogatekeeper.engine.NanoGatekeeperEngine
import com.forgerig.nanogatekeeper.model.GatekeeperConfig
import com.forgerig.nanogatekeeper.model.GatekeeperResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface CloudLlmGateway {
    suspend fun generate(prompt: String): String
}

class ForgeRigAgentRouter(
    private val gatekeeper: NanoGatekeeperEngine,
    private val cloudLlm: CloudLlmGateway,
    private val json: Json = Json { prettyPrint = true }
) {
    suspend fun dispatch(userPrompt: String, config: GatekeeperConfig = GatekeeperConfig()): String {
        return when (val r = gatekeeper.processPrompt(userPrompt, config)) {
            is GatekeeperResult.Success -> {
                Log.i(
                    "ForgeRig",
                    "GATEKEEPER success ratio=${r.telemetry.compressionRatioPct}% " +
                        "pre=${r.telemetry.preCompressionTokens} post=${r.telemetry.postCompressionTokens} " +
                        "iters=${r.telemetry.compressionIterations}"
                )
                Log.d("ForgeRig", "ledger=${json.encodeToString(r.telemetry)}")
                cloudLlm.generate(r.safeCompressedPrompt)
            }
            is GatekeeperResult.Blocked -> {
                Log.w("ForgeRig", "GATEKEEPER blocked: ${r.reason}")
                throw SecurityException(r.reason)
            }
            is GatekeeperResult.FallbackRequired -> {
                Log.w("ForgeRig", "GATEKEEPER fallback: ${r.reason}")
                Log.d("ForgeRig", "ledger=${json.encodeToString(r.telemetry)}")
                cloudLlm.generate(r.sanitizedPrompt)
            }
        }
    }
}
