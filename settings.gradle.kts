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
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "light-sdk"

includeBuild("plugin")
include(":lint-rules")
include(":sdk:shared")
include(":sdk:ui")
include(":sdk:client")
include(":tool")

// The tool builder passes -DlightSdk.toolOnly=true to configure only what
// :tool needs.
if (providers.systemProperty("lightSdk.toolOnly").orNull != "true") {
    include(":sdk:trust")
    include(":sdk:server")
    include(":sdk:emulator")
    include(":examples:ui-demo")
    project(":examples:ui-demo").projectDir = file("examples/ui-demo")
    include(":examples:tool-manager-demo")
    project(":examples:tool-manager-demo").projectDir = file("examples/tool-manager-demo")
    include(":examples:weather")
    project(":examples:weather").projectDir = file("examples/weather")
    include(":examples:authenticator")
    project(":examples:authenticator").projectDir = file("examples/authenticator")
    include(":examples:audio-demo")
    project(":examples:audio-demo").projectDir = file("examples/audio-demo")
}
