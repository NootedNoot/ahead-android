import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Read from local.properties (already gitignored - see sdk.dir above it) so
// the actual shared-secret value never enters source control. A fresh clone
// with no key configured just gets an empty string, which the backend will
// correctly reject with 401 rather than silently working.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val aheadApiKey: String = localProperties.getProperty("ahead.api.key", "")

android {
    namespace = "com.aheadt1d.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aheadt1d.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "BACKEND_BASE_URL", "\"https://ahead-backend-production-ee80.up.railway.app\"")
        // Set via `ahead.api.key=...` in local.properties, and as the
        // AHEAD_API_KEY env var on the Railway deployment - never commit the
        // actual value.
        buildConfigField("String", "AHEAD_API_KEY", "\"$aheadApiKey\"")
    }

    buildTypes {
        debug {
            // Uses defaultConfig's Railway URL above. If you need to point at a
            // local dev server again later, override BACKEND_BASE_URL here.
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // BottomSheetDialog for the event-tag edit/delete sheet on the trend chart.
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Verify this is still the latest via Android Studio's dependency suggestions -
    // Health Connect's client library was still pre-1.0 stable for a long stretch.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Notes history screen's list.
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Custom event tagging (UserEvent) local storage.
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Pure-Kotlin local unit tests (AgpMetricsCalculator etc.) - JVM only, no
    // Android framework/emulator needed.
    testImplementation("junit:junit:4.13.2")
}