# Monolith demo: MediaPipe tasks-genai references AutoValue-generated builder
# supertypes only for reflection/annotations; never hit at runtime. Ignore
# the missing symbol so R8 completes (same as consumer rules stripping it).
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**

# Keep the demo entry and the bundled backend implementations reachable
# through the direct constructor calls in DemoActivity.
-keep class com.forgerig.gatekeeper.demo.DemoActivity { *; }
-keep class com.forgerig.gatekeeper.ort.OrtGenAiClient { <init>(...); }
-keep class com.forgerig.gatekeeper.ort.LlmBridge { *; }
# The JNI side looks up LlmBridge$TokenListener.onToken(String) by name at
# runtime — R8 renamed the SAM in the release build (hence the obfuscated
# "Lc/b;.onToken" on device), so keep the whole callback surface.
-keep class com.forgerig.gatekeeper.ort.LlmBridge$* { *; }
-keep class com.forgerig.gatekeeper.litert.MediaPipeLlmClient { <init>(...); }
