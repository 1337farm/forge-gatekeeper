plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.forgerig.gatekeeper.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forgerig.gatekeeper.demo"
        minSdk = 31
        targetSdk = 35
        // Bumped once to migrate off the retired split-APK lineage (old
        // base/runtime splits also carried 5_000_000). A higher code lets the
        // single monolith APK install as an update over any stale split pair.
        // Keep stable for monolith updates; equal-code reinstalls stay valid.
        versionCode = 5_000_001
        // Human-readable: the committing SHA beats a constant for debugging.
        versionName = System.getenv("GITHUB_SHA")?.take(10) ?: "1.0"
        ndk {
            // Demo runs on arm64-v8a phones only: a single ABI keeps the
            // monolith shell small.
            abiFilters += setOf("arm64-v8a")
        }
        // Single-language resources: drops non-English translations, shrinking
        // the monolith shell further.
        resourceConfigurations += "en"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        // Single monolith APK: strip duplicate license/notice metadata that
        // bloats the archive without affecting install or runtime.
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
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
            isMinifyEnabled = true
            isShrinkResources = true
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
}

dependencies {
    implementation(project(":gatekeeper"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
