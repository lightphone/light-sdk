import java.util.Properties

plugins {
    kotlin("jvm") version "2.3.20"
    `java-gradle-plugin`
}

val rootProps = Properties().apply {
    rootDir.resolve("../gradle.properties").inputStream().use { load(it) }
}

group = rootProps.getProperty("sdkGroup")
version = rootProps.getProperty("sdkVersion")

dependencies {
    implementation(gradleApi())
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.6")
    implementation("org.tomlj:tomlj:1.1.1")
    // AGP is supplied by the consumer's classpath at runtime; we only need
    // the types to compile against here, not to bundle them.
    compileOnly("com.android.tools.build:gradle:8.12.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(17)
}

// The SDK's catalog sits outside this included build, so read media3's version
// from it directly; capabilities add media3 artifacts that must match the SDK's.
val media3Version = Regex("""^media3\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
    .find(rootDir.resolve("../gradle/libs.versions.toml").readText())
    ?.groupValues?.get(1)
    ?: error("media3 version not found in ../gradle/libs.versions.toml")

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val sdkVersion = version.toString()
    inputs.property("sdkVersion", sdkVersion)
    inputs.property("media3Version", media3Version)
    filesMatching("**/sdk-version.txt") {
        expand("sdkVersion" to sdkVersion)
    }
    filesMatching("**/media3-version.txt") {
        expand("media3Version" to media3Version)
    }
}

gradlePlugin {
    plugins {
        create("lightSdk") {
            id = "com.thelightphone.light-sdk"
            implementationClass = "com.thelightphone.plugin.LightSdkPlugin"
        }
    }
}
