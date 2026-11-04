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
        // GitHub 빌드 번호를 버전으로 사용 (로컬 빌드는 1)
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    // 고정 서명키: 빌드할 때마다 같은 키로 서명돼서 새 APK를 덮어쓰기 설치 가능
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 글자 인식 (OCR) — 폰 안에서 처리, 인터넷 불필요
    implementation("com.google.mlkit:text-recognition:16.0.1")            // 영어 등 라틴 문자
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")   // 일본어
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")    // 중국어
    // 번역 — 언어 모델은 처음 한 번만 다운로드 (언어당 약 30MB)
    implementation("com.google.mlkit:translate:17.0.3")
    // [실험] 폰 안의 Gemini Nano (갤럭시 S26 등 지원 기기만)
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
