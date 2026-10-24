import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Credentials stay in the ignored local file, never in tracked source.
val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun localString(name: String, default: String = ""): String =
    localConfig.getProperty(name, default)
fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""

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

        buildConfigField("String", "KAKAO_REST_KEY", buildConfigString(localString("KAKAO_REST_KEY")))
        buildConfigField("String", "KAKAO_NATIVE_KEY", buildConfigString(localString("KAKAO_NATIVE_KEY")))
        // The official Seoul key belongs only on the HTTPS gateway, never in the APK.
        buildConfigField("String", "SUBWAY_GATEWAY_URL", buildConfigString(localString("SUBWAY_GATEWAY_URL", "https://run-or-walk.willy0723.workers.dev")))
        buildConfigField("String", "SUBWAY_GATEWAY_TOKEN", buildConfigString(localString("SUBWAY_GATEWAY_TOKEN")))

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
    // Android provides org.json in production; unit tests exercise the actual provider JSON parser.
    testImplementation("org.json:json:20240303")

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