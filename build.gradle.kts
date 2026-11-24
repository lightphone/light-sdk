plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktfmt) apply false
}

group = "com.thelightphone"

ext["compileSdk"] = 36
ext["minSdk"] = 34
ext["targetSdk"] = 34
ext["jvmTarget"] = "17"
ext["lintVersion"] = "31.12.3"

val localProperties = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Points git at .githooks (see .githooks/pre-commit) so the ktfmt pre-commit
// hook is active without a manual setup step. Runs on every Gradle
// invocation, but only shells out to `git config` when the value is wrong.
if (rootProject.file(".git").exists()) {
    fun git(vararg args: String): String =
        ProcessBuilder("git", *args)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
            .let { process ->
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                output.trim()
            }

    val desiredHooksPath = ".githooks"
    if (git("config", "--get", "core.hooksPath") != desiredHooksPath) {
        git("config", "core.hooksPath", desiredHooksPath)
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(gradle.includedBuild("plugin").task(":check"))
    }

    plugins.withId("org.jetbrains.kotlin.jvm") { apply(plugin = "com.ncorti.ktfmt.gradle") }
    plugins.withId("org.jetbrains.kotlin.android") { apply(plugin = "com.ncorti.ktfmt.gradle") }

    plugins.withId("com.ncorti.ktfmt.gradle") {
        extensions.configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
            kotlinLangStyle()
        }
    }

    afterEvaluate {
        plugins.withId("maven-publish") {
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/lightphone/light-sdk")
                        credentials {
                            username = localProperties.getProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
                            password = localProperties.getProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
                        }
                    }
                }
            }
        }
    }
}
