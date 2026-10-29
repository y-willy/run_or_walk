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

val sourceRevision=providers.gradleProperty("sourceRevision").orNull ?: runCatching {
    providers.exec { commandLine("git","rev-parse","--short=12","HEAD");isIgnoreExitValue=true }
        .standardOutput.asText.get().trim().ifBlank {"unknown"}
}.getOrDefault("unknown")

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
        buildConfigField("String","SOURCE_REVISION",buildConfigString(sourceRevision))

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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            providers.gradleProperty("proofDir").orNull?.let { directory ->
                it.systemProperty("pacenudge.proofDir", directory)
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")

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

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}
