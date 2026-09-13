# ForgeNanoGatekeeper

Standalone Android library (AAR): zero-cloud-leak, on-device AI firewall,
semantic token compressor, privacy filter, and accuracy-audited safety
gatekeeper for ForgeRig. Gemini Nano via AICore, NPU-serialized.

## Modules

- `:nanogatekeeper` — the library (`com.forgerig.nanogatekeeper`)

## Verify

```sh
./gradlew :nanogatekeeper:testDebugUnitTest --stacktrace
bash scripts/smoke-test.sh
```

## Consumer (ForgeRig)

```kotlin
val result = gatekeeper.processPrompt(raw)
when (result) {
    is GatekeeperResult.Success -> cloud.generate(result.safeCompressedPrompt)
    is GatekeeperResult.FallbackRequired -> cloud.generate(result.sanitizedPrompt)
    is GatekeeperResult.Blocked -> throw SecurityException(result.reason)
}
```

See `nanogatekeeper/src/main/java/com/forgerig/nanogatekeeper/integration/ForgeRigAgentRouter.kt`.
