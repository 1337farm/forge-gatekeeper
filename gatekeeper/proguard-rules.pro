# Safety-critical Edge AI ProGuard rules.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,RuntimeVisibleAnnotations
-keepnames class com.forgerig.gatekeeper.model.** { *; }
-keep class com.forgerig.gatekeeper.model.** { *; }
-keepclassmembers class com.forgerig.gatekeeper.model.** {
    <init>(...);
    <fields>;
    <methods>;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep interface com.forgerig.gatekeeper.engine.** { *; }
-keep class com.forgerig.gatekeeper.engine.GatekeeperEngine { *; }
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
# Bytecode defense: system prompts are stored as XOR-masked byte[] (see
# PromptObfuscator + SystemPrompts) and decoded at runtime into volatile
# memory only. No plaintext prompt strings exist in DEX.
