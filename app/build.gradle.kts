import java.util.Properties

// release 서명 정보: 프로젝트 맨 위 keystore.properties에서 읽음 (git에 안 올라감, 비밀번호는 여기에만)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tabdeck"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tabdeck"
        minSdk = 26
        targetSdk = 37
        versionCode = 3          // 새로 배포할 때마다 1씩 올림 (폰이 업데이트로 인식)
        versionName = "0.1.2"    // 사람이 보는 버전 (PC 프로그램과 맞춤)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (!keystoreProps.isEmpty) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // keystore.properties가 있으면 서명된 APK를 만듦 (없으면 서명 안 된 APK)
            if (!keystoreProps.isEmpty) signingConfig = signingConfigs.getByName("release")
            // 압축(R8)은 끔: 켜면 kotlin·okhttp 부품 이름이 바뀌면서 서로 접근이 막혀 켜자마자 꺼졌음 (2026-11-18 에뮬레이터 확인)
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}