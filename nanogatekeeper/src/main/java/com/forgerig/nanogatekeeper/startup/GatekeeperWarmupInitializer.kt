package com.forgerig.nanogatekeeper.startup

import android.content.Context
import androidx.startup.Initializer
import com.forgerig.nanogatekeeper.engine.AICoreInferenceClient
import com.forgerig.nanogatekeeper.engine.NanoGatekeeperEngine
import com.forgerig.nanogatekeeper.model.GatekeeperConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GatekeeperWarmupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                val engine = NanoGatekeeperEngine(
                    context.applicationContext,
                    AICoreInferenceClient(context.applicationContext)
                )
                engine.processPrompt("Warmup: summarize OK.", GatekeeperConfig(maxRetries = 0))
            }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
