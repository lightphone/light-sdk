plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

sourceSets.main {
    resources.srcDir(rootProject.file("trust-format"))
}

sourceSets.test {
    resources.srcDir(rootProject.file("signer/tests/vectors"))
}

val checkTrustIsolation by tasks.registering {
    doLast {
        fileTree("src/main").matching { include("**/*.kt", "**/*.java") }.forEach {
            check(!Regex("(?m)^\\s*import\\s+(?:static\\s+)?android\\.").containsMatchIn(it.readText())) {
                "Android import in trust main sources: $it"
            }
        }
        configurations.forEach { configuration ->
            configuration.dependencies.forEach { dependency ->
                check(dependency.name != "apksig" || configuration.name == "testImplementation") {
                    "apksig is only allowed in testImplementation"
                }
            }
        }
        listOf("compileClasspath", "runtimeClasspath").forEach { name ->
            check(configurations.getByName(name).resolvedConfiguration.resolvedArtifacts.none { it.name == "apksig" }) {
                "apksig must not enter the production dependency graph"
            }
        }
    }
}
tasks.named("check") { dependsOn(checkTrustIsolation) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.apksig)
}

tasks.test {
    useJUnitPlatform()
    System.getProperty("lightStampedApk")?.let { systemProperty("lightStampedApk", it) }
    System.getProperty("lightStampCertSha256")?.let { systemProperty("lightStampCertSha256", it) }
}
