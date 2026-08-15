plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "info.plateaukao.ohmybias"
    compileSdk = 35

    defaultConfig {
        applicationId = "info.plateaukao.ohmybias"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 壓掉未用到的 Kotlin stdlib（dex 佔了 APK 大宗）；
            // 本專案無反射、IME/Activity 等 manifest 進入點由 AGP 自動保留
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 個人專案初期以 debug key 簽章，讓 release APK 可直接安裝；
            // 上架或正式發佈前應改用正式 keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // phrases.bin 等以 assets 原樣打包（不壓縮與否皆可 — 啟動時複製到 filesDir 再 mmap）
}

dependencies {
    // 引擎層在 JVM 上測試；org.json 在 Android runtime 內建，JVM 測試需同 API 的 test-only 依賴
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
