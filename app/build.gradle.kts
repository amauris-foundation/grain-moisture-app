plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PversionCode=<GitHub run number>; local builds default to 1.
val buildNumber: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "org.amauris.grainmoisture"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.amauris.grainmoisture"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Prototype signing: the key is committed so every CI build can update the
    // previous install. Move it to a GitHub secret before any wider release.
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "grainmoisture"
            keyAlias = "grainmoisture"
            keyPassword = "grainmoisture"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
