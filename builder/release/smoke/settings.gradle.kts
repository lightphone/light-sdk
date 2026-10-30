// Consumer-side check of the release bundle: resolves the SDK artifacts the
// way LightOS does, from the bundle directory, with everything else from the
// build-time Maven proxy. Run by the bundle-smoke stage in Dockerfile.

pluginManagement {
    repositories {
        maven {
            name = "LightMavenProxy"
            url = uri(providers.gradleProperty("lightMavenProxy").get())
            isAllowInsecureProtocol = true
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // com.thelightphone artifacts may only come from the bundle.
        exclusiveContent {
            forRepository {
                maven {
                    name = "ReleaseBundle"
                    url = uri(providers.gradleProperty("lightReleaseBundle").get())
                }
            }
            filter { includeGroup("com.thelightphone") }
        }
        maven {
            name = "LightMavenProxy"
            url = uri(providers.gradleProperty("lightMavenProxy").get())
            isAllowInsecureProtocol = true
        }
    }
    versionCatalogs {
        create("libs") {
            from(files(providers.gradleProperty("lightSdkCatalog").get()))
        }
    }
}

rootProject.name = "bundle-smoke"
