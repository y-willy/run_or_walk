import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val kakaoNativeKey = Properties().run {
    val propertiesFile = rootProject.file("local.properties")

    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use {
            load(it)
        }
    }

    getProperty("KAKAO_NATIVE_KEY", "")
}

// 개발 테스트용 REST 키
val kakaoRestKey = Properties().run {
    val propertiesFile = rootProject.file("local.properties")

    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use {
            load(it)
        }
    }

    getProperty("KAKAO_REST_KEY", "")
}

// ODsay API 키 가져오기
val odsayApiKey = Properties().run {

    // 프로젝트 최상위 local.properties 파일
    val file = rootProject.file("local.properties")

    // 파일이 존재하면 읽기
    if (file.exists()) {
        file.inputStream().use {
            load(it)
        }
    }

    // API 키 반환
    getProperty("ODSAY_API_KEY", "")
}

android {

    namespace = "com.example.run_or_walk"

    compileSdk {
        version = release(37)
    }

    defaultConfig {

        applicationId = "com.example.run_or_walk"

        minSdk = 26
        targetSdk = 37

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "KAKAO_REST_KEY",
            "\"$kakaoRestKey\""
        )
        // 카카오 네이티브 키를 BuildConfig에 등록
        buildConfigField(
            "String",
            "KAKAO_NATIVE_KEY",
            "\"$kakaoNativeKey\""
        )
        // ODsay API 키를 BuildConfig에 등록
        buildConfigField(
            "String",
            "ODSAY_API_KEY",
            "\"$odsayApiKey\""
        )
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation("com.kakao.maps.open:android:2.15.2")
    implementation(platform(libs.androidx.compose.bom))
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)

    androidTestImplementation(
        platform(libs.androidx.compose.bom)
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}