plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nerpudino.scrollreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nerpudino.scrollreader"
        minSdk = 29
        targetSdk = 35
        // Bump BOTH on every release: versionCode +1, versionName per semver.
        // The release tag is v<versionName>; see CHANGELOG.md.
        versionCode = 7
        versionName = "1.5.0"
    }

    // One fixed key for every build, so each new version installs over the old one.
    // (Personal sideloaded app: the key lives in the repo on purpose.)
    signingConfigs {
        create("fixed") {
            storeFile = file("scrollreader.keystore")
            storePassword = "scrollreader"
            keyAlias = "scrollreader"
            keyPassword = "scrollreader"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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

// No third-party dependencies: everything uses Android platform APIs only.
