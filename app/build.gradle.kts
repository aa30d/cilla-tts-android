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
        versionCode = providers.gradleProperty("versionCode").orElse("4").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("1.2.0").get()

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

/**
 * The prebuilt `libptts_ffi.so` files are not committed (see `.gitignore`): they are built by
 * `native/build-ptts-ffi.sh`, locally or in CI. Their symbol manifest records what the Kotlin code
 * calls, so a library left over from an older build fails here with an actionable message instead
 * of throwing `FunctionNotFoundException` on a device.
 */
val checkPttsFfi by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks app/src/main/jniLibs/libptts_ffi.so against its symbol manifest"
    commandLine(
            "python3",
            "native/check-ptts-ffi.py",
            layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath
        )
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(checkPttsFfi) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
}
