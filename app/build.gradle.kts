plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.forgerig.gatekeeper.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forgerig.gatekeeper.demo"
        minSdk = 31
        targetSdk = 35
        // FROZEN across releases: Android requires every split APK to carry the
        // exact versionCode of the base. A frozen code lets the native-runtime
        // split from an older release pair with any newer base, so updates
        // re-download only the ~30MB base and reuse the cached runtime split.
        // Picked to sit far above any historical GITHUB_RUN_NUMBER (early RUN
        // builds shipped monotonic codes ~1..20) so upgrading from those stays
        // a valid update; equal-code reinstalls are always permitted.
        versionCode = 5_000_000
        // Human-readable: the committing SHA beats a constant for debugging.
        versionName = System.getenv("GITHUB_SHA")?.take(10) ?: "1.0"
        ndk {
            // Demo runs on arm64-v8a phones only. Pruning the x86/x86_64/
            // armeabi-v7a ABIs MediaPipe tasks-genai ships kills ~82MB of
            // dead weight from the uploaded APK.
            abiFilters += setOf("arm64-v8a")
        }
    }

    bundle {
        // Only two artifacts: base-master.apk (code+resources) and the frozen
        // split_config.arm64_v8a.apk (natives). No per-language/density splits.
        abi { enableSplit = true }
        density { enableSplit = false }
        language { enableSplit = false }
    }

    packaging {
        jniLibs {
            // Compress native libs inside the APK (extract at install): the
            // on-disk/downloaded APK shrinks ~55% while behavior is identical.
            // Worth it for a sideloaded demo; swap to false for store-style
            // playback/aligned builds where install speed matters more.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        // Stable demo certificate (keystore/gatekeeper-demo.keystore, a
        // committed demo-only key) so every CI build installs as an update.
        // Falls back to the ephemeral debug key when the file is absent
        // (fresh clones before the keystore lands, forks).
        val demoKs = rootProject.file("keystore/gatekeeper-demo.keystore")
        create("demo") {
            if (demoKs.exists()) {
                storeFile = demoKs
                storePassword = System.getenv("DEMO_KEYSTORE_PASSWORD") ?: "gatekeeper"
                keyAlias = "demo"
                keyPassword = System.getenv("DEMO_KEY_PASSWORD") ?: "gatekeeper"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    if (rootProject.file("keystore/gatekeeper-demo.keystore").exists()) {
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("demo") }
            getByName("release") { signingConfig = signingConfigs.getByName("demo") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":gatekeeper"))
    implementation(project(":gatekeeper-litert"))
    implementation(project(":gatekeeper-ort"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Minimal Compose for the ORT streaming demo (MainActivity only).
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
