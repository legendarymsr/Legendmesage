plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.legend.legendmessage"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.legend.legendmessage"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // libsignal and (later) Tor ship native libraries for every ABI, which
        // balloons the APK. Restrict to arm64, which every modern phone uses.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // libsignal-android requires core library desugaring.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Test-only native lib shipped inside libsignal-android; not needed at runtime.
            excludes += "**/libsignal_jni_testing.so"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Signal protocol: X3DH/PQXDH + Double Ratchet. NOTE: AGPL-3.0-licensed.
    implementation("org.signal:libsignal-android:0.76.1")

    // QR generation + scanning for contact pairing.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Tor: embedded daemon + onion services (transport). BSD-3-Clause.
    implementation("info.guardianproject:tor-android:0.4.7.14")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
}
