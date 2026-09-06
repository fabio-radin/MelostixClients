plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.hardrex.melostixclient.tablet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hardrex.melostixclient.tablet"
        // Real target: a generic Android 4.4.4 tablet (kernel 3.8.13), 800x480 display,
        // landscape. Android 4.4.4 = exactly API 19 (KitKat): no AndroidX/Compose here
        // (Compose requires minSdk 21).
        minSdk = 19
        targetSdk = 19
        versionCode = 1
        versionName = "0.1.0-skeleton"
    }

    buildFeatures {
        // Only needed to expose BuildConfig.VERSION_NAME to the network client (protocol
        // 1.2.0, optional clientVersion field of the clientHello - see
        // net/MelostixClient.kt): AGP 8+ no longer generates it by default.
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // minSdk 19 with a modern toolchain generates expected "NewApi" warnings: every call
        // still needs to be checked by hand against API level 19.
        abortOnError = false
    }
}

// No dependency beyond the plugins: generic hardware, no proprietary SDK to wire up.
