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
        // Shizuku is published on Maven Central; no extra repo needed for 12.2.0
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "LocalBridge"
include(":app")
