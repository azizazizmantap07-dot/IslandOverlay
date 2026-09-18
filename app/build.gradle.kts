import java.util.Properties

plugins {
    id("com.android.application")
    // Kotlin is built into AGP 9 — do not apply org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.compose")
}

// Loads keystore.properties if present (for local builds). In CI, the same
// values are provided via environment variables (see .github/workflows/build.yml)
// so neither path needs the keystore file or password committed to the repo.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingProp(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

android {
    namespace = "com.hyperisland.root"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hyperisland.root"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "1.3.2-single-island"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A single, permanent signing identity used for EVERY debug build —
        // local or CI. Without this, each machine (and every fresh GitHub
        // Actions runner) generates its own ~/.android/debug.keystore, so the
        // APK's signature changes build to build and Android refuses to
        // install-over-update ("signatures don't match" → forces uninstall).
        //
        // Keystore file/password are never committed:
        // - Local:  put keystore.properties (see keystore.properties.example)
        //           next to this file, pointing at keystore/hyperisland-debug.keystore
        // - CI:     provided via KEYSTORE_BASE64 / KEYSTORE_PASSWORD secrets,
        //           decoded to a file at build time (see workflow)
        val storeFilePath = signingProp("storeFile", "KEYSTORE_FILE")
        val storePw = signingProp("storePassword", "KEYSTORE_PASSWORD")
        val keyAliasVal = signingProp("keyAlias", "KEYSTORE_ALIAS") ?: "hyperisland"
        val keyPw = signingProp("keyPassword", "KEYSTORE_KEY_PASSWORD") ?: storePw

        if (storeFilePath != null && storePw != null) {
            create("consistent") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = storePw
                keyAlias = keyAliasVal
                keyPassword = keyPw
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
            signingConfigs.findByName("consistent")?.let { signingConfig = it }
        }
        debug {
            // NO applicationIdSuffix → install menimpa versi sebelumnya tanpa uninstall
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
            // Falls back to AGP's own auto-generated debug keystore only if
            // "consistent" wasn't configured (e.g. secrets not set yet) so
            // the build never hard-fails — it just won't be install-over-update
            // until the signing config above is wired up.
            signingConfigs.findByName("consistent")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // Needed for the IHeadsUpControl AIDL interface used by
        // HeadsUpRootService (libsu RootService) — AGP 8+ defaults this
        // to false.
        aidl = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.savedstate:savedstate-ktx:1.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.github.topjohnwu.libsu:service:5.2.2")
    implementation("com.github.topjohnwu.libsu:io:5.2.2")

    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // LSPosed / Xposed API (compile only — provided at runtime by LSPosed)
    compileOnly(files("libs/xposed-api-stub.jar"))
}
