plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.exemplo.mptprinter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.exemplo.mptprinter"
        minSdk = 21
        targetSdk = 34
<<<<<<< HEAD
        versionCode = 13
        versionName = "2.2"
=======
        versionCode = 13
        versionName = "2.2"
>>>>>>> 5c5d0029b3431fd9cad29c0b44788d01c4f0d0d5
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
