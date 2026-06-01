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

rootProject.name = "light-sdk"

includeBuild("plugin")

include(":lint-rules")
include(":sdk:shared")
include(":sdk:ui")
include(":sdk:client")
include(":sdk:server")
include(":sdk:emulator")
include(":tool")
include(":ui-demo")
