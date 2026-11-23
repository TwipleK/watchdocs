import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: reads ../signing/keystore.properties (kept OUT of the repo).
// Without it, release builds fall back to the debug key so anyone can still build.
val releaseKeys = Properties().apply {
    val f = rootProject.file("../signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // Must match the phone app's applicationId (and signing key) for the Wearable Data Layer.
    namespace = "com.watchdocs.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.watchdocs.app"
        minSdk = 30
        targetSdk = 34
        versionCode = (property("watchdocs.versionCode") as String).toInt()
        versionName = property("watchdocs.versionName") as String
    }

    signingConfigs {
        if (!releaseKeys.isEmpty) {
            create("release") {
                storeFile = rootProject.file("../signing/" + releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
