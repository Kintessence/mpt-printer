plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.exemplo.mptprinter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.exemplo.mptprinter"
        minSdk = 23
        targetSdk = 34
        versionCode = 31
        versionName = "2.9.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("Boolean", "ENABLE_INAPP_UPDATE", "true")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("Boolean", "ENABLE_INAPP_UPDATE", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}