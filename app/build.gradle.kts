import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Keys live in local.properties (gitignored). Blank when unset — the app still builds, the map
// simply renders empty tiles.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val mapsApiKey: String = localProps.getProperty("MAPS_API_KEY") ?: ""

android {
    namespace = "com.MeshLink.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.akasha.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 33
        versionName = "1.7.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Injected into the manifest's com.google.android.geo.API_KEY meta-data.
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    androidResources {
        // MediaPipe memory-maps the model file, so it must sit uncompressed in the APK. Only
        // relevant if the .task weights are ever bundled into assets/ — harmless otherwise.
        noCompress += "task"
        // The NSFW classifier is memory-mapped straight from assets, which requires it to be stored
        // uncompressed — a compressed entry has no usable file offset to map.
        noCompress += "tflite"
        // The embedder is copied out of assets on first launch. Storing it uncompressed keeps that
        // copy a straight byte transfer rather than an inflate of ~90 MB during startup.
        noCompress += "onnx"
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    buildTypes {
        debug {
            ndk {
                // Include x86_64 for emulator support during development
                abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // APK splits for GitHub releases - creates arm64, x86_64, and universal APKs
    // AAB for Play Store handles architecture distribution automatically
    // Auto-detects: splits enabled for assemble tasks, disabled for bundle tasks
    // Works in Android Studio GUI and CLI without needing extra properties
    val enableSplits = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("assemble", ignoreCase = true) &&
        !taskName.contains("bundle", ignoreCase = true)
    }

    splits {
        abi {
            isEnable = enableSplits
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            isUniversalApk = true  // For F-Droid and fallback
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Permissions
    implementation(libs.accompanist.permissions)

    // QR
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    
    // Cryptography
    implementation(libs.bundles.cryptography)
    
    // JSON
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Bluetooth
    implementation(libs.nordic.ble)

    // WebSocket
    implementation(libs.okhttp)

    // Arti (Tor in Rust) Android bridge - custom build from latest source
    // Built with rustls, 16KB page size support, and onio//un service client
    // Native libraries are in src/tor/jniLibs/ (extracted from arti-custom.aar)
    // Only included in tor flavor to reduce APK size for standard builds
    // Note: AAR is kept in libs/ for reference, but libraries loaded from jniLibs/

    // Google Play Services Location
    implementation(libs.gms.location)

    // Google Maps (Compose) for the home-screen map backdrop
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.maps.android:maps-compose:6.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Security preferences
    implementation(libs.androidx.security.crypto)
    
    // EXIF orientation handling for images
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    
    // WorkManager for background persistence
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // NanoHTTPD for embedded web portal
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    
    // Google AI Edge (Gemini Nano on-device via AICore)
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp02")

    // MediaPipe LLM Inference — runs Gemma 3 1B fully on-device, no AICore dependency
    implementation("com.google.mediapipe:tasks-genai:0.10.27")

    // TensorFlow Lite — runs the on-device NSFW image classifier (assets/nsfw.tflite).
    // Plain Interpreter rather than MediaPipe's ImageClassifier because the model carries no
    // TFLite metadata, which the higher-level task API requires.
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    // ONNX Runtime — runs all-MiniLM-L6-v2 on-device to embed queries.
    // Required even for remote search: the Ground Station's /search takes a 384-dim vector, not
    // text, so the phone must produce the embedding itself and both sides must agree bit-for-bit.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    
    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
