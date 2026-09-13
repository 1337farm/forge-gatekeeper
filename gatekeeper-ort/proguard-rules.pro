# Keep the JNI bridge + backend surface (R8 must not rename native targets).
-keep class com.forgerig.gatekeeper.ort.LlmBridge { *; }
-keep class com.forgerig.gatekeeper.ort.** { *; }
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
# ORT classes live in external .so files, not DEX — nothing to keep here.
