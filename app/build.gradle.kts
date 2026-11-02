plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.edge60pro.punchholecompanion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.edge60pro.companion.studio"
        minSdk = 31
        targetSdk = 34
        versionCode = 2
        versionName = "2.0-Studio"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release-key.jks")
            storePassword = "Edge60Pro2026"
            keyAlias = "companion_key"
            keyPassword = "Edge60Pro2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.airbnb.android:lottie:6.5.2")
}
