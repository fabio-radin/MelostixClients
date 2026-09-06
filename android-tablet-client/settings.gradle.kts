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

// "Slave" client for a generic Android tablet (4.4.4 / API 19, 800x480).
include(":app-tablet")
