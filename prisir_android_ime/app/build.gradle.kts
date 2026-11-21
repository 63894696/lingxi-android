plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lingxi.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lingxi.ime"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "2.0.0-alpha.9"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 全量词库打进 assets（装完即用）；db 不压缩，首启拷贝更快
    androidResources {
        noCompress += listOf("db", "midx")
    }
    packaging { resources.excludes += setOf("META-INF/*") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // 手写识别：on-device ONNX Runtime（完全本地，与 Windows 的 prisir_hw.exe 同一套 ochw 模型）
    // 手写功能已下线（2026-11-19）：ONNX Runtime 不再需要，包体省一大截
    // implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
