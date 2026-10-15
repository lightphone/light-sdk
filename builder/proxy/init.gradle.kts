// Points every build in the composite at the build-time Maven proxy, the only
// host the build container can reach. Project-level repositories (the SDK's
// plugin build declares some, and so may a dev's build script) are ignored.

val proxyUrl = System.getenv("LIGHT_MAVEN_PROXY")
    ?: throw GradleException("LIGHT_MAVEN_PROXY must be set")

fun RepositoryHandler.useLightProxy() {
    clear()
    maven {
        name = "LightMavenProxy"
        url = uri(proxyUrl)
        isAllowInsecureProtocol = true
    }
}

settingsEvaluated {
    pluginManagement.repositories.useLightProxy()
    dependencyResolutionManagement.repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    dependencyResolutionManagement.repositories.useLightProxy()
}
