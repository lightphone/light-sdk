plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = property("sdkGroup") as String

version = property("sdkVersion") as String

java {
    sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(
                rootProject.ext["jvmTarget"] as String
            )
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = property("sdkGroup") as String
            artifactId = "sdk-digest"
            version = property("sdkVersion") as String
        }
    }
}
