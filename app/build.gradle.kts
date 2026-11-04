plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nahyunung.screentranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nahyunung.screentranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 글자 인식 (OCR) — 폰 안에서 처리, 인터넷 불필요
    implementation("com.google.mlkit:text-recognition:16.0.1")            // 영어 등 라틴 문자
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")   // 일본어
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")    // 중국어
    // 번역 — 언어 모델은 처음 한 번만 다운로드 (언어당 약 30MB)
    implementation("com.google.mlkit:translate:17.0.3")
}
