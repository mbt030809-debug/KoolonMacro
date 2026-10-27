plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.koolon.macro"
    compileSdk = 34
    signingConfigs {
        // 고정 키: 빌드마다 서명이 같아야 덮어쓰기 설치(업데이트)가 됨
        getByName("debug") {
            storeFile = file("koolon-debug.jks")
            storePassword = "koolon1234"
            keyAlias = "koolon"
            keyPassword = "koolon1234"
        }
    }
    defaultConfig {
        applicationId = "com.koolon.macro"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
