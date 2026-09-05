pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "melostix-tablet-client"

// Client "slave" per tablet Android generico (4.4.4 / API 19, 800x480).
include(":app-tablet")
