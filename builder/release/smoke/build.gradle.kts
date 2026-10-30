plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.thelightphone.bundlesmoke"
    compileSdk = 36
    defaultConfig {
        minSdk = 34
    }
}

val sdkVersion = providers.gradleProperty("sdkVersion").get()

dependencies {
    implementation("com.thelightphone:sdk-server:$sdkVersion")
    implementation("com.thelightphone:sdk-ui:$sdkVersion")
}

// Fails if any published POM or Gradle module metadata points at something
// that does not resolve.
tasks.register("resolveBundle") {
    val classpaths = listOf("releaseCompileClasspath", "releaseRuntimeClasspath")
        .map { configurations.getByName(it) }
    doLast {
        classpaths.forEach { classpath ->
            val files = classpath.resolve()
            logger.lifecycle("${classpath.name}: ${files.size} files")
        }
    }
}
