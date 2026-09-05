plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.hardrex.melostixclient.tablet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hardrex.melostixclient.tablet"
        // Target reale: tablet generico Android 4.4.4 (kernel 3.8.13), display 800x480,
        // landscape. Android 4.4.4 = API 19 esatto (KitKat): niente AndroidX/Compose qui
        // (Compose richiede minSdk 21).
        minSdk = 19
        targetSdk = 19
        versionCode = 1
        versionName = "0.1.0-skeleton"
    }

    buildFeatures {
        // Serve solo per esporre BuildConfig.VERSION_NAME al client di rete (protocollo 1.2.0,
        // campo opzionale clientVersion del clientHello - vedi net/MelostixClient.kt): AGP 8+
        // non lo genera piu' di default.
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
        // minSdk 19 con toolchain moderna genera avvisi "NewApi" attesi: ogni chiamata va
        // comunque verificata a mano contro il livello API 19.
        abortOnError = false
    }
}

// Nessuna dipendenza oltre ai plugin: hardware generico, nessun SDK proprietario da collegare.
