// Top-level build file. Plugin versions are declared here and applied in :app.
plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.1.x: libsignal-android 0.76 ships Kotlin 2.1.0 metadata, so the
    // app must be built with a compiler that can read it.
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
