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

repositories {
    google()
    mavenCentral()
}

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

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("lightSdk") {
            id = "com.thelightphone.light-sdk"
            implementationClass = "com.thelightphone.plugin.LightSdkPlugin"
        }
    }
}
