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
        versionCode = 2
        versionName = "0.2.0"
    }

    // 正式簽章金鑰不進 repo — CI 由 GitHub Secrets 注入環境變數，
    // 本機由 ~/.gradle/gradle.properties 提供；兩者皆無時退回 debug key（可照常開發）
    val ksPath = System.getenv("OHMYBIAS_KEYSTORE") ?: findProperty("OHMYBIAS_KEYSTORE") as String?
    if (ksPath != null && file(ksPath).exists()) {
        signingConfigs.create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("OHMYBIAS_KEYSTORE_PASSWORD") ?: findProperty("OHMYBIAS_KEYSTORE_PASSWORD") as String?
            keyAlias = System.getenv("OHMYBIAS_KEY_ALIAS") ?: findProperty("OHMYBIAS_KEY_ALIAS") as String?
            keyPassword = System.getenv("OHMYBIAS_KEY_PASSWORD") ?: findProperty("OHMYBIAS_KEY_PASSWORD") as String?
        }
    }

    buildTypes {
        release {
            // R8 壓掉未用到的 Kotlin stdlib（dex 佔了 APK 大宗）；
            // 本專案無反射、IME/Activity 等 manifest 進入點由 AGP 自動保留
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
