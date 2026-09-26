plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.exemplo.mptprinter"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "airprinter123"
            keyAlias = "airprinter"
            keyPassword = "airprinter123"
        }
    }

    defaultConfig {
        applicationId = "com.exemplo.mptprinter"
        minSdk = 23
        targetSdk = 34
        versionCode = 27
        versionName = "2.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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