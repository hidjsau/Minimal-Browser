plugins {
    id("com.android.application")
}

android {
    // Namespace stays the same as the original code so its R and imports resolve in later steps.
    namespace = "dev.sk2andy.materialbrowser"
    compileSdk = 36

    defaultConfig {
        // Different from the original app, so both can be installed side by side.
        applicationId = "dev.minimalcandy.browser"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Fixed key (committed), so every build installs over the previous one and keeps logins.
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
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
