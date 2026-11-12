plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aven.downloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ahven.downloader"
        minSdk = 26
        targetSdk = 34
        // GitHub Actions passes the build number; every build must have a higher versionCode than the last
        // so it installs over the old one and the in-app updater can compare versions.
        val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        // "owner/repo" of the PUBLIC repository whose Releases hold the APKs (empty = updater disabled).
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("UPDATE_REPO") ?: ""}\"")
    }

    // Fixed debug key so every GitHub build is signed the same way
    // (a new build can be installed over the old one without uninstalling).
    signingConfigs {
        getByName("debug") {
            // GitHub Actions passes the real signing key through environment variables (from Secrets).
            // Without them (local build, or Secrets not set) the bundled debug key is used.
            val ksPath = System.getenv("SIGN_KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("SIGN_STORE_PASSWORD")
                keyAlias = System.getenv("SIGN_KEY_ALIAS")
                keyPassword = System.getenv("SIGN_KEY_PASSWORD")
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // R8: shrinks and obfuscates the app's own code (harder to copy). Built only when the workflow asks for it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe Extractor uses newer java.* APIs than API 26 has.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 3 APKs: armeabi-v7a, arm64-v8a and universal (= v7a + arm64).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // yt-dlp / Python / FFmpeg binaries are executed from the native library directory.
            useLegacyPackaging = true
            // No x86 emulator support: keeps the universal APK much smaller.
            excludes += setOf("**/x86/**", "**/x86_64/**")
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Download history (persistent)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // In-app preview player
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // YouTube: NewPipe Extractor (pure Java, fast). Other sites: yt-dlp (Python) only as the link reader.
    // FFmpeg is NOT bundled any more: video + audio are merged with Android's own MediaMuxer (smaller app).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Network: one OkHttp client for everything the app downloads itself, with optional DNS over HTTPS.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
