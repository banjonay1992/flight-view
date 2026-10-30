import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Endpoints come from android/secrets.properties (git-ignored). See secrets.example.properties.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String) = "\"${secrets.getProperty(key) ?: System.getenv(key) ?: ""}\""

android {
    namespace = "com.nayproductions.flightview"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nayproductions.flightview"
        minSdk = 26
        targetSdk = 36
        // CI passes -PversionCode=<run number> so every Play upload is higher than the last.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE_URL", secret("FV_API_BASE_URL"))
        buildConfigField("String", "SUPABASE_URL", secret("FV_SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_ANON_KEY", secret("FV_SUPABASE_ANON_KEY"))
    }

    // Upload key for Play App Signing. CI unpacks it from the ANDROID_SIGNING secret;
    // Google holds the real app signing key, so a lost upload key can be reset in Play Console.
    val keystorePath = System.getenv("FV_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("upload") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("FV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FV_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("FV_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("upload")
            // Off for the first test builds: R8 plus Ktor/serialization reflection is the most likely
            // source of release-only crashes. Turn on once a release build has been smoke-tested.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
