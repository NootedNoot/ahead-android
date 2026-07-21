plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    // KSP over kapt: kapt is legacy/in maintenance mode and KSP2 tracks current
    // Kotlin releases directly, which matters since this project pins a very
    // recent Kotlin (2.2.10).
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
