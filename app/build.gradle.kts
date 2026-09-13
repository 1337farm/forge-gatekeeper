plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.forgerig.nanogatekeeper.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forgerig.nanogatekeeper.demo"
        minSdk = 31 // AICore SDK floor (Google requirement); library itself stays 26
        targetSdk = 35
        // Monotonic per CI run so store builds always install as updates.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        // Stable demo certificate (keystore/nanogatekeeper-demo.keystore, a
        // committed demo-only key) so every CI build installs as an update.
        // Falls back to the ephemeral debug key when the file is absent
        // (fresh clones before the keystore lands, forks).
        val demoKs = rootProject.file("keystore/nanogatekeeper-demo.keystore")
        create("demo") {
            if (demoKs.exists()) {
                storeFile = demoKs
                storePassword = System.getenv("DEMO_KEYSTORE_PASSWORD") ?: "nanogatekeeper"
                keyAlias = "demo"
                keyPassword = System.getenv("DEMO_KEY_PASSWORD") ?: "nanogatekeeper"
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
    if (rootProject.file("keystore/nanogatekeeper-demo.keystore").exists()) {
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
}

dependencies {
    implementation(project(":nanogatekeeper"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
