plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.wsvdmeer.pwncompanion"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.wsvdmeer.pwncompanion"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship arm64-v8a only (real devices) — the native llama.cpp libs are large
        ndk {
            abiFilters += "arm64-v8a"
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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true   // expose VERSION_NAME to the UI
    }
    lint {
        disable.add("ForegroundServiceType")
    }

    // llama.cpp JNI — builds libllama-jni.so from C++ source via CMake
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    // GGUF files are already compressed — disable aapt re-compression so
    // Android can mmap them from the APK directly if needed.
    androidResources {
        noCompress += "gguf"
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // Networking & WebSocket
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Background Tasks
    implementation(libs.androidx.work.runtime.ktx)

    // ...existing code...

    // Data Storage (DataStore - replaces Room, no annotation processing)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // HTTP client for model download with progress tracking (kept for future use)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // On-device LLM inference — llama.cpp via JNI (see app/src/main/cpp/)
    // Supports standard GGUF files (Qwen2, LLaMA, Mistral, etc.)
    // Note: native .so is built by CMake/NDK, not a Gradle dependency.

    // ...existing code...
    implementation(libs.androidx.notification)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}