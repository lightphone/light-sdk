// Adds a ReleaseBundle repository to every project that publishes, so the
// image build can write sdk-shared/sdk-server/sdk-ui as a Maven repository
// directory (see the maven-bundle stage in Dockerfile). Lives in the builder,
// not the SDK build, so it works for any SDK commit the image is built from.

val bundleDir = System.getenv("LIGHT_RELEASE_BUNDLE_DIR")
    ?: throw GradleException("LIGHT_RELEASE_BUNDLE_DIR must be set")

gradle.beforeProject {
    pluginManager.withPlugin("maven-publish") {
        extensions.getByType(PublishingExtension::class.java).repositories.maven {
            name = "ReleaseBundle"
            url = uri(bundleDir)
        }
    }
}
