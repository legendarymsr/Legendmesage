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
        // CI passes -PappVersionCode / -PappVersionName so every published build
        // has a higher, visible version (and installs as a real update).
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.1.0-dev"

        // libsignal and (later) Tor ship native libraries for every ABI, which
        // balloons the APK. Restrict to arm64, which every modern phone uses.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Two apps from one codebase:
    //  - standard : the messenger (launches MainActivity)
    //  - debugkit : a separate, side-by-side "LegendMsg Debug" app whose
    //               launcher is the Debug & test hub.
    flavorDimensions += "mode"
    productFlavors {
        create("standard") {
            dimension = "mode"
            // Block screenshots/recording and hide content in recents.
            buildConfigField("boolean", "SECURE_WINDOWS", "true")
        }
        create("debugkit") {
            dimension = "mode"
            applicationIdSuffix = ".debugkit"
            versionNameSuffix = "-debug"
            // Keep the debug app screenshot-able so self-test output can be shared.
            buildConfigField("boolean", "SECURE_WINDOWS", "false")
        }
    }

    signingConfigs {
        // A throwaway key committed to the repo so sideloaded builds from here
        // are signed consistently and update over each other. It carries NO
        // trust — do not treat a signature by this key as proof of anything.
        create("shared") {
            storeFile = rootProject.file("keystore/legendmessage.jks")
            storePassword = "legendmessage"
            keyAlias = "legendmessage"
            keyPassword = "legendmessage"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
        buildConfig = true
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
    // TorService uses LocalBroadcastManager but the AAR doesn't pull it in.
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Encrypted message history at rest.
    implementation("net.zetetic:sqlcipher-android:4.9.0")
    implementation("androidx.sqlite:sqlite:2.4.0")
}
