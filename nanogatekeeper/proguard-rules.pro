# Safety-critical Edge AI ProGuard rules.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,RuntimeVisibleAnnotations
-keepnames class com.forgerig.nanogatekeeper.model.** { *; }
-keep class com.forgerig.nanogatekeeper.model.** { *; }
-keepclassmembers class com.forgerig.nanogatekeeper.model.** {
    <init>(...);
    <fields>;
    <methods>;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-keep class **$$serializer { *; }
-keepclassmembers class **$$serializer { *; }
-keep class androidx.startup.** { *; }
-keep class com.forgerig.nanogatekeeper.startup.** { *; }
-keep interface com.forgerig.nanogatekeeper.engine.** { *; }
-keep class com.forgerig.nanogatekeeper.engine.NanoGatekeeperEngine { *; }
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
# Bytecode defense: system prompts are stored as XOR-masked byte[] (see
# PromptObfuscator + SystemPrompts) and decoded at runtime into volatile
# memory only. No plaintext prompt strings exist in DEX.
