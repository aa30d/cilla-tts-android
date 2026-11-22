plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cilla.tts"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cilla.tts"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("versionCode").orElse("2").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("1.1.0").get()

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    buildFeatures {
        viewBinding = true
    }

    lint {
        // Fatal lints are not a reason to fail an APK that is built to be installed by hand.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
}
