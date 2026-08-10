pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // MPAndroidChart is published here, not on Maven Central
    }
}

rootProject.name = "ahead-android"
include(":app")

// Shared rate/dedup/trend-trajectory math with ahead-lite-android - see
// ../ahead-rate-math/CLAUDE.md. Included by relative path rather than a
// published artifact; only works because all Ahead repos live as sibling
// folders under one workspace (see the workspace CLAUDE.md).
include(":ratemath")
project(":ratemath").projectDir = File(rootDir, "../ahead-rate-math")
